package com.aneb.probe.research.business

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.aneb.probe.research.engineering.OwnedProcessLifecycle
import com.aneb.probe.research.engineering.OwnedShellScriptTransport
import com.aneb.probe.research.engineering.TongyiEngineeringRunner
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One run's public Android shell boundary. No OS-child exit status is available here. */
internal class VideoOwnerShell(
    private val automation: UiAutomation,
    private val directory: File,
    private val nowNs: () -> Long,
) : OwnedProcessLifecycle.Shell {
    private class ReadState {
        val bytes = ByteArrayOutputStream()
        val descriptor = AtomicReference<ParcelFileDescriptor?>(null)
        val apiCommand = AtomicReference<String?>(null)
        val failure = AtomicReference<String?>(null)
        val cancelled = AtomicBoolean(false)
        val eof = AtomicBoolean(false)
        val descriptorClosed = AtomicBoolean(false)
        val overLimit = AtomicBoolean(false)
        val submissionReturned = AtomicBoolean(false)
        val finishedNs = AtomicReference<Long?>(null)
        lateinit var thread: Thread
    }

    private val scripts = OwnedShellScriptTransport(directory)
    private val lifecycle = OwnedProcessLifecycle(this, TongyiEngineeringRunner.MonotonicClock { nowNs() })
    private val readers = ArrayList<ReadState>()
    private var sequence = 0
    private var decisionSequence = 0
    private var readerClosureUnknown = false

    init {
        check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) {
            "VIDEO_OWN_SHELL_DIRECTORY_REQUIRED"
        }
    }

    @Synchronized
    override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult {
        check(command.isNotBlank() && '\u0000' !in command) { "VIDEO_SHELL_COMMAND_INVALID" }
        check(readersClosed()) { "VIDEO_PREVIOUS_SHELL_READER_CLOSURE_UNKNOWN" }
        val started = nowNs()
        check(started < deadlineNanos) { "VIDEO_SHELL_DEADLINE" }
        val localDeadline = minOf(deadlineNanos, started + minOf(MAX_READ_NS, Long.MAX_VALUE - started))
        val callId = ++sequence
        val marker = "ANEB_VIDEO_RC_" + UUID.randomUUID().toString().replace("-", "")
        val state = ReadState()
        // The marker is an encoded status of this script block, never native waitpid evidence.
        val script = "{\n$command\n} 2>&1\naneb_video_rc=\$?\nprintf '\\n$marker:%s\\n' \"\$aneb_video_rc\""
        state.thread = Thread({
            try {
                scripts.execute(script) { apiCommand ->
                    state.apiCommand.set(apiCommand)
                    check(!state.cancelled.get() && nowNs() < localDeadline) { "VIDEO_SHELL_SUBMISSION_DEADLINE" }
                    automation.executeShellCommand(apiCommand).also { state.descriptor.set(it) }
                }
                state.submissionReturned.set(true)
                val descriptor = checkNotNull(state.descriptor.get()) { "VIDEO_SHELL_DESCRIPTOR_MISSING" }
                if (!state.cancelled.get() && nowNs() < localDeadline) {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        val buffer = ByteArray(8192)
                        while (!state.cancelled.get() && nowNs() < localDeadline) {
                            val count = input.read(buffer)
                            if (count < 0) { state.eof.set(true); break }
                            if (count == 0) continue
                            synchronized(state.bytes) {
                                if (!state.cancelled.get()) {
                                    val retained = minOf(count, MAX_RAW_BYTES - state.bytes.size())
                                    if (retained > 0) state.bytes.write(buffer, 0, retained)
                                    if (retained < count) {
                                        state.overLimit.set(true)
                                        state.failure.compareAndSet(null, "VIDEO_SHELL_RAW_LIMIT_EXCEEDED")
                                        state.cancelled.set(true)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                state.failure.compareAndSet(null, error.javaClass.simpleName + ":" + error.message.orEmpty())
            } finally {
                closeDescriptor(state)
                state.finishedNs.set(nowNs())
            }
        }, "aneb-video-owner-shell-$callId").also { it.isDaemon = true }
        readers.add(state)
        state.thread.start()
        try {
            val remaining = localDeadline - nowNs()
            if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(state.thread, remaining)
        } catch (_: InterruptedException) {
            state.failure.compareAndSet(null, "VIDEO_SHELL_CALLER_INTERRUPTED")
            Thread.currentThread().interrupt()
        }
        val aliveAtCutoff = state.thread.isAlive
        if (aliveAtCutoff) {
            // A late public API return is fenced by cancelled and closes its own eventual PFD.
            // There is no retry, thread-stop, kill, or invented cancellation of the shell child.
            readerClosureUnknown = true
            state.cancelled.set(true)
            closeDescriptor(state)
        }
        val raw = synchronized(state.bytes) { state.bytes.toByteArray() }
        val text = raw.toString(Charsets.UTF_8)
        val boundary = text.lastIndexOf("\n$marker:")
        val trailer = if (boundary >= 0) text.substring(boundary) else ""
        val rcText = Regex("\\n$marker:([0-9]{1,3})\\n").matchEntire(trailer)?.groupValues?.get(1)
        val scriptRc = rcText?.toIntOrNull()?.takeIf { it in 0..255 }
        val uniqueMarker = boundary >= 0 && text.indexOf("\n$marker:") == boundary
        val snapshotNs = nowNs()
        val finishedNs = state.finishedNs.get()
        val eofAtSnapshot = state.eof.get()
        val closedAtSnapshot = state.descriptorClosed.get()
        val failureAtSnapshot = state.failure.get()
        val overLimitAtSnapshot = state.overLimit.get()
        val apiCommandAtSnapshot = state.apiCommand.get()
        if (finishedNs == null || finishedNs > localDeadline) readerClosureUnknown = true
        val completeBeforeReceipt = !aliveAtCutoff && eofAtSnapshot && closedAtSnapshot &&
            failureAtSnapshot == null && !overLimitAtSnapshot && uniqueMarker && scriptRc != null &&
            finishedNs != null && finishedNs <= localDeadline && snapshotNs <= localDeadline
        val rawFile = File(directory, "video-owner-shell-$callId.raw")
        writeOnce(rawFile, raw)
        val receipt = JSONObject()
            .put("schema_version", "aneb_video_owner_shell_v1")
            .put("command", command)
            .put("public_api_command", apiCommandAtSnapshot ?: JSONObject.NULL)
            .put("script_path", apiCommandAtSnapshot?.removePrefix("sh ") ?: JSONObject.NULL)
            .put("started_elapsed_realtime_ns", started.toString())
            .put("deadline_elapsed_realtime_ns", deadlineNanos.toString())
            .put("bounded_read_deadline_elapsed_realtime_ns", localDeadline.toString())
            .put("snapshot_elapsed_realtime_ns", snapshotNs.toString())
            .put("worker_finished_elapsed_realtime_ns", finishedNs?.toString() ?: JSONObject.NULL)
            .put("submission_returned", state.submissionReturned.get())
            .put("descriptor_acquired", state.descriptor.get() != null)
            .put("actual_descriptor_eof", eofAtSnapshot)
            .put("descriptor_closed_at_snapshot", closedAtSnapshot)
            .put("reader_alive_at_cutoff", aliveAtCutoff)
            .put("reader_closure_unknown", readerClosureUnknown)
            .put("raw_file", rawFile.name)
            .put("raw_bytes", raw.size)
            .put("raw_limit_bytes", MAX_RAW_BYTES)
            .put("raw_limit_exceeded", overLimitAtSnapshot)
            .put("raw_state", if (eofAtSnapshot && !aliveAtCutoff) "EOF_COMPLETED_BYTES" else "PARTIAL_BYTES_AT_SNAPSHOT")
            .put("raw_sha256", sha256(raw))
            .put("script_exit_code", scriptRc ?: JSONObject.NULL)
            .put("script_status_kind", "encoded_script_block_status_not_os_child_exit_receipt")
            .put("native_os_direct_exit", "UNOBSERVED")
            .put("completed_before_receipt_fsync", completeBeforeReceipt)
            .put("completion_semantics", "actual_eof_and_closed_worker_and_valid_script_status_and_deadline; return_also_checks_after_receipt_fsync")
            .put("error", failureAtSnapshot ?: JSONObject.NULL)
        writeOnce(File(directory, "video-owner-shell-$callId.json"), (receipt.toString(2) + "\n").toByteArray(Charsets.UTF_8))
        val completed = completeBeforeReceipt && nowNs() <= localDeadline
        return OwnedProcessLifecycle.ShellResult(
            completed,
            scriptRc,
            if (uniqueMarker && scriptRc != null) text.substring(0, boundary) else text,
            if (completed) "" else failureAtSnapshot ?: "VIDEO_SHELL_TIMEOUT_OR_INCOMPLETE",
        )
    }

    override fun sleepMillis(milliseconds: Long) {
        require(milliseconds in 0..50) { "VIDEO_SHELL_SLEEP_NOT_BOUNDED" }
        SystemClock.sleep(milliseconds)
    }

    /** Complete process-list evidence, including this package's colon-suffixed processes. */
    fun requirePackageAbsent(packageName: String, deadlineNanos: Long) {
        requirePackage(packageName)
        val result = execute("ps -A -o PID,NAME", deadlineNanos)
        requireComplete(result, deadlineNanos)
        check(result.exitCode == 0) { "VIDEO_PACKAGE_PROCESS_LIST_UNKNOWN" }
        val lines = result.stdout.trim().lines().map(String::trim)
        check(lines.size >= 2 && lines.first().split(Regex("\\s+")) == listOf("PID", "NAME")) {
            "VIDEO_PACKAGE_PROCESS_LIST_UNKNOWN"
        }
        val rows = lines.drop(1).map { line ->
            line.split(Regex("\\s+"), limit = 2).also {
                check(it.size == 2 && PID.matches(it[0]) && it[1].isNotEmpty()) { "VIDEO_PACKAGE_PROCESS_LIST_UNKNOWN" }
            }
        }
        check(rows.map { it[0] }.distinct().size == rows.size) { "VIDEO_PACKAGE_PROCESS_LIST_UNKNOWN" }
        check(rows.none { it[1] == PACKAGE || it[1].startsWith("$PACKAGE:") }) { "VIDEO_PACKAGE_STILL_PRESENT" }
    }

    /** null means pidof found no MAIN process, not proof that every package process is absent. */
    fun findPackageOwner(packageName: String, expectedUid: Int, deadlineNanos: Long): OwnedProcessLifecycle.Owner? {
        requirePackage(packageName)
        require(expectedUid >= 0) { "VIDEO_EXPECTED_UID_INVALID" }
        val result = execute("pidof $PACKAGE", deadlineNanos)
        requireComplete(result, deadlineNanos)
        if (result.exitCode == 1 && result.stdout.trim().isEmpty()) return null
        check(result.exitCode == 0) { "VIDEO_PACKAGE_PID_UNKNOWN" }
        val pids = result.stdout.trim().split(Regex("\\s+"))
        check(pids.size == 1 && PID.matches(pids.single())) { "VIDEO_PACKAGE_PID_NOT_UNIQUE" }
        val observed = lifecycle.observe(pids.single(), deadlineNanos)
        val owner = (observed as? OwnedProcessLifecycle.Observation.Alive)?.owner
            ?: error("VIDEO_PACKAGE_OWNER_UNKNOWN_OR_DISAPPEARED")
        check(owner.uid == expectedUid && owner.commandLine == listOf(PACKAGE)) { "VIDEO_PACKAGE_IDENTITY_MISMATCH" }
        return owner
    }

    /** One mutation at most. Read/compare/action is not an atomic kernel identity-and-stop operation. */
    fun stopOwnedPackage(owner: OwnedProcessLifecycle.Owner, packageName: String, deadlineNanos: Long): Boolean {
        requirePackage(packageName)
        check(PID.matches(owner.pid) && owner.uid >= 0 && owner.commandLine == listOf(PACKAGE)) {
            "VIDEO_STOP_OWNER_NOT_ADMITTED"
        }
        var stopped = false
        var reason = "VIDEO_STOP_UNKNOWN"
        try {
            when (val current = lifecycle.observe(owner.pid, deadlineNanos)) {
                OwnedProcessLifecycle.Observation.Absent -> {
                    requirePackageAbsent(packageName, deadlineNanos)
                    stopped = true
                    reason = "MAIN_AND_PACKAGE_ALREADY_ABSENT_NO_STOP"
                }
                is OwnedProcessLifecycle.Observation.Unknown -> reason = "OWNER_UNKNOWN_NOT_STOPPED"
                is OwnedProcessLifecycle.Observation.Alive -> {
                    if (current.owner != owner) {
                        reason = "OWNER_REPLACED_NOT_STOPPED"
                    } else {
                        val result = execute("am force-stop $PACKAGE", deadlineNanos)
                        requireComplete(result, deadlineNanos)
                        check(result.exitCode == 0 && result.stdout.isBlank()) { "VIDEO_FORCE_STOP_RESULT_UNKNOWN" }
                        check(lifecycle.observe(owner.pid, deadlineNanos) == OwnedProcessLifecycle.Observation.Absent) {
                            "VIDEO_STOP_MAIN_EXIT_UNKNOWN_OR_REPLACED_NO_RETRY"
                        }
                        requirePackageAbsent(packageName, deadlineNanos)
                        stopped = true
                        reason = "EXACT_OWNER_PRECHECK_AND_PACKAGE_ABSENCE_OBSERVED"
                    }
                }
            }
        } catch (error: Exception) {
            reason = error.javaClass.simpleName + ":" + error.message.orEmpty()
        }
        val receipt = JSONObject().put("schema_version", "aneb_video_owner_stop_v1")
            .put("package", packageName).put("pid", owner.pid).put("uid", owner.uid)
            .put("start_ticks", owner.startTicks).put("confirmed_absent", stopped).put("reason", reason)
            .put("deadline_elapsed_realtime_ns", deadlineNanos.toString())
            .put("identity_action_atomic", false).put("native_os_direct_exit", "UNOBSERVED")
        writeOnce(File(directory, "video-owner-stop-${++decisionSequence}.json"), (receipt.toString(2) + "\n").toByteArray(Charsets.UTF_8))
        return stopped && nowNs() <= deadlineNanos && readersClosed()
    }

    /** Sticky UNKNOWN after a reader/submission remained live at its deadline; never silently rehabilitated. */
    @Synchronized
    fun readersClosed(): Boolean = !readerClosureUnknown && readers.all {
        !it.thread.isAlive && it.descriptorClosed.get()
    }

    private fun requirePackage(packageName: String) {
        require(packageName == PACKAGE) { "VIDEO_ONLY_DOUYIN_PACKAGE_ADMITTED" }
    }

    private fun requireComplete(result: OwnedProcessLifecycle.ShellResult, deadlineNanos: Long) {
        check(result.completed && result.stderr.isEmpty() && nowNs() <= deadlineNanos && readersClosed()) {
            "VIDEO_SHELL_RESULT_UNKNOWN"
        }
    }

    private fun closeDescriptor(state: ReadState) {
        val descriptor = state.descriptor.get()
        if (descriptor == null) {
            // Only the worker's finally knows there can be no late descriptor acquisition.
            if (Thread.currentThread() === state.thread) state.descriptorClosed.set(true)
            return
        }
        try {
            descriptor.close()
            state.descriptorClosed.set(true)
        } catch (error: Exception) {
            state.failure.compareAndSet(null, "VIDEO_SHELL_DESCRIPTOR_CLOSE_FAILED:" + error.javaClass.simpleName)
        }
    }

    private fun writeOnce(file: File, bytes: ByteArray) {
        check(file.createNewFile()) { "VIDEO_SHELL_EVIDENCE_ALREADY_EXISTS_NO_RETRY" }
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val PACKAGE = "com.ss.android.ugc.aweme"
        private const val MAX_RAW_BYTES = 1_048_576
        private const val MAX_READ_NS = 5_000_000_000L
        private val PID = Regex("[1-9][0-9]*")
    }
}
