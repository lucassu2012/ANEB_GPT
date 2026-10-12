package com.aneb.probe.research.engineering

import java.io.File
import java.io.FileOutputStream

/** Debug-only private collector. No production UI, scoring, or M6 route. */
class TongyiEngineeringRunner(
    private val phone: Phone,
    private val clock: MonotonicClock,
) {
    fun interface MonotonicClock { fun nowNanos(): Long }

    /** Only the external Android UI/OS boundary is replaceable in HOST tests. */
    interface Phone {
        fun prepare(deadlineNanos: Long): Snapshot
        fun enterExact(text: String)
        fun snapshot(): Snapshot
        fun saveOriginals(label: String)
        fun startRecording(readyDeadlineNanos: Long)
        fun sendOnce()
        fun observeUntil(deadlineNanos: Long)
        fun stopOwnedRecording()
        fun cleanupOwned(): Boolean
    }

    data class Snapshot(
        val packageName: String,
        val ownedLifecycle: Boolean,
        val ordinaryTextVisible: Boolean,
        val noAttachments: Boolean,
        val emptyNewSession: Boolean,
        val composerText: String,
        val composerFocused: Boolean,
        val uniqueSend: Boolean,
        val networkCondition: String,
        val notVpn: Boolean,
    )

    data class Result(
        val state: String,
        val sendCalls: String,
        val ownedCleanup: Boolean,
        val reason: String?,
        val operationalEvents: List<Pair<String, Long>>,
    )

    fun runOnce(runId: String, networkCondition: String, directory: File): Result {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(networkCondition in setOf("W1", "C1"))
        check(directory.mkdir()) { "OPPORTUNITY_ALREADY_CONSUMED_NO_RETRY" }
        val events = ArrayList<Pair<String, Long>>()
        fun event(name: String): Long = clock.nowNanos().also { events.add(name to it) }
        val started = event("preparation_started")
        val preparationDeadline = started + PREP_NANOS
        var sends = "0"
        var reason: String? = null
        var collected = false
        var cleaned = false
        fun inPreparation() { check(clock.nowNanos() < preparationDeadline) { "PREPARATION_180_EXPIRED" } }
        fun eligible(snapshot: Snapshot, empty: Boolean) {
            check(snapshot.packageName == PACKAGE && snapshot.ownedLifecycle) { "CURRENT_OWNER_REQUIRED" }
            check(snapshot.ordinaryTextVisible && snapshot.noAttachments) { "ORDINARY_TEXT_NO_ATTACHMENTS_REQUIRED" }
            check(snapshot.networkCondition == networkCondition && snapshot.notVpn) { "CURRENT_NETWORK_MISMATCH" }
            check(snapshot.composerFocused) { "CURRENT_FOCUS_REQUIRED" }
            if (empty) check(snapshot.emptyNewSession && snapshot.composerText.isEmpty()) { "CURRENT_NEW_EMPTY_REQUIRED" }
            else check(snapshot.composerText == PAYLOAD && snapshot.uniqueSend) { "COMPLETE_DRAFT_AND_UNIQUE_SEND_REQUIRED" }
        }
        try {
            eligible(phone.prepare(preparationDeadline), empty = true)
            inPreparation()
            val inputStarted = event("input_started")
            phone.enterExact(PAYLOAD)
            check(clock.nowNanos() - inputStarted <= INPUT_NANOS) { "INPUT_FIVE_SECOND_LIMIT" }
            eligible(phone.snapshot(), empty = false)
            inPreparation()
            phone.saveOriginals("complete-draft")
            val recordingStarted = event("recording_start_requested")
            phone.startRecording(minOf(preparationDeadline, recordingStarted + READY_NANOS))
            inPreparation()
            val goObserved = event("send_admission_observed")
            eligible(phone.snapshot(), empty = false)
            // fsync before a potentially ambiguous UI invocation. This opportunity is never replayed.
            val intent = File(directory, "SEND_INTENT.json")
            check(intent.createNewFile()) { "SEND_OPPORTUNITY_ALREADY_CONSUMED" }
            FileOutputStream(intent).use {
                it.write("{\"run_id\":\"$runId\",\"network_condition\":\"$networkCondition\",\"payload_bytes\":239,\"send_state\":\"consumed_before_invocation\"}\n".toByteArray())
                it.fd.sync()
            }
            inPreparation()
            check(clock.nowNanos() - goObserved <= INPUT_NANOS) { "GO_FIVE_SECOND_FRESHNESS_EXPIRED" }
            sends = "UNKNOWN"
            event("send_invocation_started")
            phone.sendOnce()
            sends = "1" // Invocation return, not delivery, success, or a scientific event timestamp.
            val returned = event("send_invocation_returned")
            check(returned - goObserved <= INPUT_NANOS) { "SEND_FIVE_SECOND_LIMIT" }
            phone.observeUntil(recordingStarted + CAPTURE_NANOS)
            phone.stopOwnedRecording()
            event("capture_closed")
            collected = true
        } catch (failure: Exception) {
            reason = failure.message ?: failure.javaClass.simpleName
            event("failure_retained")
        } finally {
            cleaned = try { phone.cleanupOwned() } catch (_: Exception) { false }
            event("owned_cleanup_checked")
        }
        return Result(if (collected && cleaned) "OBSERVATION_CLOSED" else "OBSERVATION_UNKNOWN",
            sends, cleaned, reason ?: if (!cleaned) "OWNED_CLEANUP_UNKNOWN" else null, events)
    }

    companion object {
        const val PACKAGE = "com.aliyun.tongyi"
        const val PAYLOAD = "请给出三条整理书桌的实用建议，分别涉及纸张、文具和桌面清洁。每条单独一行，每行约15个汉字。只写这三行，不加标题、编号或额外解释。请直接回答，不搜索、不调用工具。"
        const val PREP_NANOS = 180_000_000_000L
        const val READY_NANOS = 20_000_000_000L
        const val INPUT_NANOS = 5_000_000_000L
        const val CAPTURE_NANOS = 90_000_000_000L
    }
}

/** Actual public UiAutomation String-command boundary; scripts stay in the owned run. */
class OwnedShellScriptTransport(private val directory: File) {
    data class Submission<T>(val handle: T, val scriptPath: String, val apiCommand: String)
    private var sequence = 0

    fun <T> execute(script: String, publicApi: (String) -> T): Submission<T> {
        check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) { "OWN_SCRIPT_DIRECTORY_REQUIRED" }
        val file = File(directory, "shell-script-${++sequence}.sh")
        check(file.absolutePath.matches(Regex("[A-Za-z0-9_./:\\\\-]+"))) { "SHELL_SCRIPT_PATH_NOT_SINGLE_SAFE_TOKEN" }
        check(file.createNewFile()) { "OWN_SCRIPT_ALREADY_EXISTS_NO_RETRY" }
        FileOutputStream(file).use {
            it.write((script + "\n").toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        // API29 forwards to Runtime.exec(String): it tokenizes whitespace, not shell quotes.
        val command = "sh ${file.absolutePath}"
        return Submission(publicApi(command), file.absolutePath, command)
    }
}

/** Shared by the native backend and HOST tests; only the external test shell is replaceable. */
class OwnedProcessLifecycle(
    private val shell: Shell,
    private val clock: TongyiEngineeringRunner.MonotonicClock,
) {
    interface Shell {
        fun execute(command: String, deadlineNanos: Long): ShellResult
        fun sleepMillis(milliseconds: Long)
    }
    data class ShellResult(val completed: Boolean, val exitCode: Int?, val stdout: String, val stderr: String = "")
    data class Owner(val pid: String, val uid: Int, val startTicks: String, val commandLine: List<String>,
        val rawCommandLine: String = commandLine.joinToString("\u0000", postfix = "\u0000"))
    enum class Stop { CAPTURE_SIGINT, TARGET_FORCE_STOP }
    sealed interface Observation {
        data class Alive(val owner: Owner) : Observation
        data object Absent : Observation
        data class Unknown(val reason: String) : Observation
    }

    private fun read(command: String, deadlineNanos: Long): String {
        check(clock.nowNanos() < deadlineNanos) { "PROCESS_OBSERVATION_DEADLINE" }
        val result = shell.execute(command, deadlineNanos)
        check(result.completed && result.exitCode == 0 && result.stderr.isEmpty() &&
            clock.nowNanos() <= deadlineNanos) { "TEST_SHELL_RESULT_UNKNOWN" }
        return result.stdout
    }

    private fun ticks(pid: String, raw: String): String {
        val end = raw.lastIndexOf(')')
        check(end >= 0 && raw.substringBefore('(').trim() == pid) { "PROCESS_STAT_UNKNOWN" }
        val fields = raw.substring(end + 1).trim().split(Regex("\\s+"))
        check(fields.size >= 20 && fields[19].matches(Regex("[0-9]+"))) { "PROCESS_STAT_UNKNOWN" }
        return fields[19]
    }

    fun observe(pid: String, deadlineNanos: Long): Observation = try {
        check(pid.matches(Regex("[1-9][0-9]*"))) { "INVALID_PROCESS_ID" }
        val lines = read("ps -A -o PID", deadlineNanos).trim().lines().map(String::trim)
        check(lines.size >= 2 && lines.first() == "PID" &&
            lines.drop(1).all { it.matches(Regex("[1-9][0-9]*")) }) { "PROCESS_LIST_UNKNOWN" }
        if (pid !in lines.drop(1)) Observation.Absent else {
            val start = ticks(pid, read("cat /proc/$pid/stat", deadlineNanos))
            val uidLine = read("cat /proc/$pid/status", deadlineNanos).lines().filter { it.startsWith("Uid:") }.single()
            val uids = uidLine.substringAfter(':').trim().split(Regex("\\s+"))
            check(uids.size == 4 && uids.all { it.matches(Regex("[0-9]+")) }) { "PROCESS_UID_UNKNOWN" }
            val rawCommand = read("cat /proc/$pid/cmdline", deadlineNanos)
            check(rawCommand.endsWith('\u0000')) { "PROCESS_COMMAND_UNKNOWN" }
            // Keep exact raw bytes in ownership; trailing /proc argv storage padding is not a new argument.
            val command = rawCommand.trimEnd('\u0000').split('\u0000')
            check(command.isNotEmpty() && command.all { it.isNotEmpty() }) { "PROCESS_COMMAND_UNKNOWN" }
            check(ticks(pid, read("cat /proc/$pid/stat", deadlineNanos)) == start) { "PROCESS_IDENTITY_CHANGED_DURING_READ" }
            Observation.Alive(Owner(pid, uids.first().toInt(), start, command, rawCommand))
        }
    } catch (failure: Exception) { Observation.Unknown(failure.message ?: "PROCESS_OBSERVATION_UNKNOWN") }

    fun requireAlive(owner: Owner, deadlineNanos: Long) {
        check((observe(owner.pid, deadlineNanos) as? Observation.Alive)?.owner == owner) {
            "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED"
        }
    }

    /** A successful complete shell process list, not pidof's empty/nonzero response. */
    fun requirePackageAbsent(packageName: String, deadlineNanos: Long) {
        check(packageName == TongyiEngineeringRunner.PACKAGE)
        val lines = read("ps -A -o PID,NAME", deadlineNanos).trim().lines().map(String::trim)
        check(lines.size >= 2 && lines.first().split(Regex("\\s+")) == listOf("PID", "NAME")) {
            "PACKAGE_PROCESS_LIST_UNKNOWN"
        }
        val names = lines.drop(1).map {
            val row = it.split(Regex("\\s+"), limit = 2)
            check(row.size == 2 && row[0].matches(Regex("[1-9][0-9]*")) && row[1].isNotEmpty()) {
                "PACKAGE_PROCESS_LIST_UNKNOWN"
            }
            row[1]
        }
        check(names.none { it == packageName || it.startsWith("$packageName:") }) { "TARGET_PROCESS_STILL_PRESENT" }
    }

    fun stopAndConfirm(owner: Owner, stop: Stop, deadlineNanos: Long): Boolean {
        when (val observed = observe(owner.pid, deadlineNanos)) {
            Observation.Absent -> return true
            is Observation.Unknown -> error("OWN_PROCESS_OBSERVATION_UNKNOWN_${observed.reason}")
            is Observation.Alive -> check(observed.owner == owner) { "OWN_PROCESS_IDENTITY_DRIFT_NOT_STOPPED" }
        }
        val command = when (stop) {
            Stop.CAPTURE_SIGINT -> {
                check(owner.commandLine.first().substringAfterLast('/') == "screenrecord") { "CAPTURE_COMMAND_NOT_OWNED" }
                "kill -2 ${owner.pid}"
            }
            Stop.TARGET_FORCE_STOP -> {
                check(owner.commandLine.first() == TongyiEngineeringRunner.PACKAGE) { "TARGET_COMMAND_NOT_OWNED" }
                "am force-stop ${TongyiEngineeringRunner.PACKAGE}"
            }
        }
        read(command, deadlineNanos)
        while (true) {
            when (val observed = observe(owner.pid, deadlineNanos)) {
                Observation.Absent -> return true
                is Observation.Unknown -> error("OWN_PROCESS_EXIT_UNKNOWN_${observed.reason}")
                is Observation.Alive -> check(observed.owner == owner) { "OWN_PROCESS_REPLACEMENT_NOT_STOPPED" }
            }
            check(clock.nowNanos() < deadlineNanos) { "OWN_PROCESS_EXIT_DEADLINE" }
            shell.sleepMillis(50)
        }
    }
}
