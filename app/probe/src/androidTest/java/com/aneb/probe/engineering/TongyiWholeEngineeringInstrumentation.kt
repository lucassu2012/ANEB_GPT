package com.aneb.probe.engineering

import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.aneb.probe.research.engineering.OwnedProcessLifecycle
import com.aneb.probe.research.engineering.OwnedShellScriptTransport
import com.aneb.probe.research.engineering.TongyiEngineeringRunner
import com.aneb.probe.research.engineering.TongyiPreparationRunner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** New Android executor contract. Never used as a Windows/Sky fallback. */
@RunWith(AndroidJUnit4::class)
class TongyiWholeEngineeringInstrumentation {
    @Test fun collectOnePrivateEngineeringObservation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("feature_release") == "tongyi-engineering-807") { "NEW_FEATURE_RELEASE_REQUIRED" }
        check(args.getString("payload_sha256") == PAYLOAD_SHA) { "FIXED_PAYLOAD_NOT_BOUND" }
        check(sha(TongyiEngineeringRunner.PAYLOAD.toByteArray(Charsets.UTF_8)) == PAYLOAD_SHA)
        val runId = checkNotNull(args.getString("run_id"))
        val mode = args.getString("execution_mode") ?: "COLLECTION"
        check(mode == "COLLECTION" || mode == "PREPARATION_ONLY") { "UNKNOWN_EXECUTION_MODE" }
        val preparationOnly = mode == "PREPARATION_ONLY"
        val condition = if (preparationOnly) "PREPARATION_ONLY" else checkNotNull(args.getString("network_condition"))
        val ui = JSONObject(String(Base64.decode(checkNotNull(args.getString("ui_contract_b64")), Base64.DEFAULT), Charsets.UTF_8))
        val parent = File(instrumentation.targetContext.getExternalFilesDir(null), "tongyi-engineering")
        check(parent.exists() || parent.mkdirs())
        val root = File(parent, runId)
        if (preparationOnly) {
            check(ui.getString("binding_scope") == "root_current_807_ui_frame") { "CURRENT_FRAME_CONTRACT_REQUIRED" }
            check(ui.getString("menu_argb_sha256").lowercase() == TongyiPreparationRunner.MENU_SHA256) { "FIXED_MENU_PIN_REQUIRED" }
            check(ui.getBoolean("new_session_text_only_attachment_contract") && ui.getJSONArray("attachment_resource_ids").length() == 0) {
                "ACCEPTED_NEW_SESSION_TEXT_ONLY_CONTRACT_REQUIRED"
            }
        }
        val backend = NativePhone(root, condition, ui, preparationOnly)
        val result = if (preparationOnly) TongyiPreparationRunner(backend.preparationAndroid(), SystemClock::elapsedRealtimeNanos)
            .runOnce(runId, root) else TongyiEngineeringRunner(backend, SystemClock::elapsedRealtimeNanos).runOnce(runId, condition, root)
        val terminal = JSONObject()
            .put("run_id", runId).put("network_condition", if (preparationOnly) JSONObject.NULL else condition)
            .put("state", result.state).put("send_calls", when (result.sendCalls) {
                "0" -> 0; "1" -> 1; else -> "UNKNOWN"
            })
            .put("owned_cleanup", result.ownedCleanup).put("reason", result.reason ?: JSONObject.NULL)
            .put("claim_scope", if (preparationOnly) "preparation_inspection_only" else "private_engineering_observation")
            .put("backend_session", "UNKNOWN").put("backend_model", "UNKNOWN")
            .put("rpi", "not_computed").put("formal_new_slots", 0)
            .put("metrics", JSONObject.NULL)
            .put("recording_readiness", if (preparationOnly) "NOT_STARTED" else "owned_process_and_current_mp4_file_growth_operational_only")
            .put("send_first_response_video_coverage", "UNKNOWN")
            .put("semantic_video_review", "NOT_RUN")
            .put("clock_domain", "Android_elapsedRealtimeNanos_raw_operational_only")
            .put("boot_id", backend.bootId)
            .put("operational_events", JSONArray().apply {
                result.operationalEvents.forEach { (name, nanos) ->
                    put(JSONObject().put("event", name).put("elapsed_realtime_nanos", nanos.toString()))
                }
            })
        if (preparationOnly) terminal.put("execution_mode", mode).put("capture", "NOT_STARTED").put("send_selector", "UNKNOWN")
        FileOutputStream(File(root, "TERMINAL.json")).use {
            it.write((terminal.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("aneb_terminal", terminal.toString()) })
    }

    private class NativePhone(
        private val root: File,
        private val condition: String,
        private val ui: JSONObject,
        private val preparationOnly: Boolean = false,
    ) : TongyiEngineeringRunner.Phone {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val automation = instrumentation.uiAutomation
        private val device = UiDevice.getInstance(instrumentation)
        private val context = instrumentation.targetContext
        private var targetPid: String? = null
        private var targetTicks: String? = null
        private var targetOwner: OwnedProcessLifecycle.Owner? = null
        private var freshEmpty = false
        private var capture: ParcelFileDescriptor? = null
        private var recorderOwner: OwnedProcessLifecycle.Owner? = null
        private var captureReader: Thread? = null
        private val captureReaderFailure = AtomicReference<Exception?>(null)
        private var captureReaderEof = false
        private var recordingClosureUnknown = false
        private var preparationDeadline = 0L
        private var processShellSequence = 0
        private var preparationFrameSequence = 0
        private var preparationGeometrySequence = 0
        private val scriptTransport = OwnedShellScriptTransport(root)
        private val processes = OwnedProcessLifecycle(object : OwnedProcessLifecycle.Shell {
            override fun execute(command: String, deadlineNanos: Long) = processShell(command, deadlineNanos)
            override fun sleepMillis(milliseconds: Long) = SystemClock.sleep(milliseconds)
        }, SystemClock::elapsedRealtimeNanos)
        val bootId: String? = runCatching { shell("cat /proc/sys/kernel/random/boot_id").trim() }.getOrNull()
        private val targetUid = context.packageManager.getApplicationInfo(TARGET, 0).uid

        private fun launchOwned(deadlineNanos: Long) {
            preparationDeadline = deadlineNanos
            writeJson("OWNED_INSTRUMENTATION.json", JSONObject().put("package", context.packageName)
                .put("pid", Process.myPid().toString()).put("uid", Process.myUid())
                .put("start_ticks", processTicks(Process.myPid().toString())).put("run_id", root.name))
            processes.requirePackageAbsent(TARGET, readDeadline(preparationDeadline))
            check(device.currentPackageName == "com.huawei.android.launcher") { "LAUNCHER_NOT_CURRENT" }
            val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(TARGET))
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil(minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.READY_NANOS)) {
                device.currentPackageName == TARGET
            }
            val pid = shell("pidof $TARGET").trim()
            check(pid.matches(Regex("[0-9]+"))) { "TARGET_PID_NOT_UNIQUE" }
            val owner = (processes.observe(pid, readDeadline(preparationDeadline)) as? OwnedProcessLifecycle.Observation.Alive)?.owner
                ?: error("TARGET_OWNER_UNKNOWN")
            check(owner.uid == targetUid && owner.commandLine == listOf(TARGET)) { "TARGET_OWNER_MISMATCH" }
            val ticks = owner.startTicks
            targetPid = pid
            targetTicks = ticks
            targetOwner = owner
            writeJson("OWNED_TARGET.json", JSONObject().put("package", TARGET)
                .put("pid", pid).put("uid", owner.uid).put("start_ticks", ticks)
                .put("cmdline", JSONArray(owner.commandLine)).put("raw_cmdline_b64",
                    Base64.encodeToString(owner.rawCommandLine.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
        }

        override fun prepare(deadlineNanos: Long): TongyiEngineeringRunner.Snapshot {
            launchOwned(deadlineNanos)
            // Only inspect the declared visible empty-state witnesses, not old chat content.
            requireVisible("initial_empty")
            val existing = editNodeOrNull()
            check(existing == null || existing.text.isNullOrEmpty()) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
            requireVisible("ordinary_mode")
            action { click("menu") }
            action { click("new_session") }
            waitUntil(minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.READY_NANOS)) {
                matching("new_empty").size == 1
            }
            requireVisible("new_empty")
            requireVisible("ordinary_mode")
            freshEmpty = true
            saveOriginals("new-empty")
            action { click("focus_hint") }
            waitUntil(minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.INPUT_NANOS)) {
                editNodeOrNull()?.isFocused == true
            }
            check(editNode().text.isNullOrEmpty()) { "NEW_SESSION_DRAFT_NOT_EMPTY" }
            return snapshot()
        }

        override fun enterExact(text: String) {
            check(text == TongyiEngineeringRunner.PAYLOAD)
            val input = editNode()
            check(input.isFocused && input.text.isNullOrEmpty()) { "CURRENT_EMPTY_FOCUS_REQUIRED" }
            action {
                check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) {
                    "SET_TEXT_REFUSED"
                }
            }
            check(editNode().text?.toString() == text) { "COMPLETE_239B_READBACK_MISMATCH" }
        }

        override fun snapshot(): TongyiEngineeringRunner.Snapshot {
            requireOwnedCurrent()
            val input = editNode()
            val network = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = network.getNetworkCapabilities(network.activeNetwork)
            val actual = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "W1"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "C1"
                else -> "UNKNOWN"
            }
            val noVpn = capabilities != null &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val attachmentIds = ui.getJSONArray("attachment_resource_ids")
            val noAttachments = freshEmpty && (0 until attachmentIds.length()).all { index ->
                nodes().none { it.viewIdResourceName == attachmentIds.getString(index) && visible(it) }
            }
            // [] is allowed only in the explicitly accepted new-session/text-only contract.
            check(attachmentIds.length() > 0 || ui.optBoolean("new_session_text_only_attachment_contract", false)) {
                "ATTACHMENT_ABSENCE_CONTRACT_REQUIRED"
            }
            return TongyiEngineeringRunner.Snapshot(TARGET, true, matching("ordinary_mode").size == 1,
                noAttachments, freshEmpty && input.text.isNullOrEmpty(), input.text?.toString().orEmpty(),
                input.isFocused, matching("send").singleOrNull()?.let { it.isEnabled && it.isClickable } == true,
                actual, noVpn)
        }

        override fun saveOriginals(label: String) {
            requireOwnedCurrent()
            check(label.matches(Regex("[A-Za-z0-9_-]+")))
            check(device.takeScreenshot(File(root, "$label.png"))) { "SCREENSHOT_FAILED" }
            device.dumpWindowHierarchy(File(root, "$label.xml"))
            writeJson("$label-owner.json", JSONObject().put("package", TARGET).put("uid", targetUid)
                .put("pid", targetPid).put("start_ticks", targetTicks)
                .put("elapsed_realtime_nanos", SystemClock.elapsedRealtimeNanos().toString()))
        }

        override fun startRecording(readyDeadlineNanos: Long) {
            check(!preparationOnly) { "PREPARATION_CAPTURE_UNREACHABLE" }
            check(shell("pidof screenrecord").trim().isEmpty()) { "UNKNOWN_CAPTURE_NOT_TOUCHED" }
            val video = File(root, "video.mp4")
            val pidFile = File(root, "capture.pid")
            check(!video.exists() && !pidFile.exists())
            // shell is the official Android UiAutomation process API, not a Windows UI route.
            capture = scriptTransport.execute("echo \$\$ > ${pidFile.absolutePath}; exec screenrecord --time-limit 90 ${video.absolutePath} 2>${File(root, "screenrecord.stderr.raw").absolutePath}",
                automation::executeShellCommand).handle
            captureReader = Thread {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(capture)).use { input ->
                        File(root, "screenrecord.stdout.raw").outputStream().use { output -> input.copyTo(output) }
                    }
                    captureReaderEof = true
                } catch (error: Exception) { captureReaderFailure.set(error) }
            }.also { it.start() }
            waitUntil(readyDeadlineNanos) { pidFile.isFile && pidFile.readText().trim().matches(Regex("[0-9]+")) }
            val pid = pidFile.readText().trim()
            waitUntil(readyDeadlineNanos) {
                when (val observed = processes.observe(pid, readDeadline(readyDeadlineNanos))) {
                    is OwnedProcessLifecycle.Observation.Alive -> {
                        if (observed.owner.commandLine.first().substringAfterLast('/') == "screenrecord" &&
                            observed.owner.commandLine.last() == video.absolutePath) {
                            recorderOwner = observed.owner
                            true
                        } else false // The same shell may not yet have completed exec(screenrecord).
                    }
                    OwnedProcessLifecycle.Observation.Absent -> error("CAPTURE_EXITED_BEFORE_READINESS")
                    is OwnedProcessLifecycle.Observation.Unknown -> error("CAPTURE_OWNER_UNKNOWN_${observed.reason}")
                }
            }
            val owner = checkNotNull(recorderOwner)
            // Operational encoder/file-start witness, not a decoded first frame or a scientific T0.
            waitUntil(readyDeadlineNanos) {
                video.isFile && video.length() >= 64 && runCatching {
                    video.inputStream().use { input ->
                        val header = ByteArray(12)
                        input.read(header) == 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                    }
                }.getOrDefault(false)
            }
            val previousLength = video.length()
            waitUntil(readyDeadlineNanos) { video.length() > previousLength }
            writeJson("OWNED_CAPTURE.json", JSONObject().put("pid", pid).put("uid", owner.uid)
                .put("start_ticks", owner.startTicks).put("cmdline", JSONArray(owner.commandLine))
                .put("raw_cmdline_b64", Base64.encodeToString(owner.rawCommandLine.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                .put("video", video.name).put("budget_seconds", 90).put("ready_file_bytes", video.length())
                .put("ready_qualification", "operational_file_growth_not_frame_decoding"))
        }

        override fun sendOnce() {
            check(!preparationOnly) { "PREPARATION_SEND_UNREACHABLE" }
            requireOwnedCurrent()
            check(editNode().text?.toString() == TongyiEngineeringRunner.PAYLOAD)
            action { click("send") }
        }

        override fun observeUntil(deadlineNanos: Long) {
            saveOriginals("after-send")
            while (SystemClock.elapsedRealtimeNanos() < deadlineNanos) {
                requireOwnedCurrent()
                if (File(root, "CANCEL").exists()) error("CANCELLED_NO_RETRY")
                SystemClock.sleep(100)
            }
            saveOriginals("capture-end")
            // Capture termination is not App completion, instruction success, or a metric.
        }

        override fun stopOwnedRecording() {
            check(!recordingClosureUnknown) { "CAPTURE_CLOSURE_ALREADY_UNKNOWN_NOT_RETRIED" }
            try { closeRecording() } catch (failure: Exception) {
                recordingClosureUnknown = true
                throw failure
            }
        }

        private fun closeRecording() {
            val owner = recorderOwner ?: run {
                check(capture == null) { "CAPTURE_STARTED_WITHOUT_KNOWN_OWNER_NOT_CLOSED" }
                return
            }
            check(owner.commandLine.last() == File(root, "video.mp4").absolutePath) { "CAPTURE_PATH_NOT_OWNED" }
            val closureDeadline = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
            check(processes.stopAndConfirm(owner, OwnedProcessLifecycle.Stop.CAPTURE_SIGINT, closureDeadline))
            val remainingMillis = (closureDeadline - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
            if (captureReader?.isAlive == true && remainingMillis > 0) captureReader?.join(remainingMillis)
            check(captureReader?.isAlive != true && captureReaderEof && captureReaderFailure.get() == null) {
                "CAPTURE_STREAM_EOF_UNKNOWN"
            }
            check(processes.observe(owner.pid, readDeadline(closureDeadline)) == OwnedProcessLifecycle.Observation.Absent) {
                "OWN_CAPTURE_PROCESS_EXIT_UNKNOWN"
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(File(root, "video.mp4").absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString("mime")?.startsWith("video/") == true
                } ?: error("ORIGINAL_MP4_HAS_NO_VIDEO_TRACK")
                extractor.selectTrack(track)
                var samples = 0
                var first: Long? = null
                var last: Long? = null
                var nondecreasing = true
                while (extractor.sampleTime >= 0) {
                    val pts = extractor.sampleTime
                    if (first == null) first = pts
                    if (last != null && pts < last) nondecreasing = false
                    last = pts
                    samples++
                    check(samples <= 100_000) { "MEDIA_SAMPLE_BOUND_EXCEEDED" }
                    if (!extractor.advance()) break
                }
                check(samples > 0) { "ORIGINAL_MP4_HAS_NO_VIDEO_SAMPLE" }
                writeJson("FINAL_MEDIA.json", JSONObject().put("actual_video_track", track)
                    .put("sample_count", samples).put("first_presentation_time_us", first)
                    .put("last_presentation_time_us", last).put("pts_nondecreasing", nondecreasing)
                    .put("clock_domain", "original_MP4_MediaExtractor_presentation_microseconds")
                    .put("decoded_frames", "NOT_RUN").put("send_response_semantic_coverage", "UNKNOWN"))
            } finally { extractor.release() }
            recorderOwner = null // Only actual shell exit, reader EOF and final media seal release ownership.
            capture = null
        }

        override fun cleanupOwned(): Boolean {
            var clean = preparationOnly || runCatching { stopOwnedRecording() }.isSuccess
            val owner = targetOwner
            var targetClosed = owner == null && targetPid == null
            if (owner != null) {
                val closureDeadline = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
                targetClosed = runCatching {
                    when (val observed = processes.observe(owner.pid, readDeadline(closureDeadline))) {
                        OwnedProcessLifecycle.Observation.Absent -> Unit
                        is OwnedProcessLifecycle.Observation.Unknown -> error("TARGET_CLOSURE_UNKNOWN_${observed.reason}")
                        is OwnedProcessLifecycle.Observation.Alive -> {
                        check(observed.owner == owner) { "TARGET_IDENTITY_DRIFT_NOT_TOUCHED" }
                        check(context.packageManager.getApplicationInfo(TARGET, 0).uid == targetUid)
                        if (device.currentPackageName == TARGET) {
                            val input = editNodeOrNull()
                            if (preparationOnly) {
                                check(input == null || currentEmpty(input)) { "PREPARATION_UNKNOWN_DRAFT_NOT_TOUCHED" }
                            } else if (input?.text?.toString() == TongyiEngineeringRunner.PAYLOAD) {
                                check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                                })) { "OWN_DRAFT_CLEAR_FAILED" }
                                check(editNode().text.isNullOrEmpty()) { "OWN_DRAFT_CLEAR_UNCONFIRMED" }
                            } else {
                                check(input?.text.isNullOrEmpty()) { "UNKNOWN_REPLACEMENT_DRAFT_NOT_TOUCHED" }
                            }
                        }
                        }
                    }
                    check(processes.stopAndConfirm(owner, OwnedProcessLifecycle.Stop.TARGET_FORCE_STOP, closureDeadline))
                    processes.requirePackageAbsent(TARGET, readDeadline(closureDeadline))
                }.isSuccess
                clean = targetClosed && clean
                if (targetClosed) {
                    targetOwner = null
                    targetPid = null
                    targetTicks = null
                }
                if (targetClosed && (device.currentPackageName == TARGET || device.currentPackageName == context.packageName)) {
                    clean = device.pressHome() && clean
                }
            }
            val noTarget = runCatching {
                processes.requirePackageAbsent(TARGET, readDeadline())
            }.isSuccess
            return clean && targetClosed && noTarget && device.currentPackageName == "com.huawei.android.launcher"
        }

        private fun readDeadline(outer: Long = Long.MAX_VALUE) = minOf(outer,
            SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.INPUT_NANOS)

        /** Explicit RC and actual stream completion from the existing official test-shell channel. */
        private fun processShell(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult {
            check(!command.contains('\''))
            val marker = "ANEB807_" + UUID.randomUUID().toString().replace("-", "")
            val submitted = scriptTransport.execute("{ $command; } 2>&1; aneb_rc=\$?; printf \"\\n$marker:%s\\n\" \"\$aneb_rc\"",
                automation::executeShellCommand)
            val descriptor = submitted.handle
            val raw = AtomicReference<ByteArray?>(null)
            val failure = AtomicReference<Exception?>(null)
            val reader = Thread {
                try { ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { raw.set(it.readBytes()) } }
                catch (error: Exception) { failure.set(error) }
            }.also { it.start() }
            val remainingMillis = (deadlineNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
            if (remainingMillis > 0) reader.join(remainingMillis)
            val eof = !reader.isAlive && raw.get() != null && failure.get() == null
            if (!eof) runCatching { descriptor.close() }
            val text = raw.get()?.toString(Charsets.UTF_8).orEmpty()
            val boundary = text.lastIndexOf("\n$marker:")
            val rc = if (boundary >= 0) text.substring(boundary + marker.length + 2).trim().toIntOrNull() else null
            val complete = eof && rc != null && rc in 0..255 && text.endsWith('\n') &&
                SystemClock.elapsedRealtimeNanos() <= deadlineNanos
            val reply = OwnedProcessLifecycle.ShellResult(complete, rc,
                if (boundary >= 0) text.substring(0, boundary) else text,
                failure.get()?.toString() ?: if (!complete) "TEST_SHELL_TIMEOUT_OR_INCOMPLETE" else "")
            writeJson("process-shell-${++processShellSequence}.json", JSONObject().put("command", command)
                .put("script_path", submitted.scriptPath).put("public_api_command", submitted.apiCommand)
                .put("deadline_elapsed_realtime_nanos", deadlineNanos.toString())
                .put("completed", complete).put("stdout_eof", eof)
                .put("exit_code", rc ?: JSONObject.NULL)
                .put("raw_stdout_b64", Base64.encodeToString(raw.get() ?: byteArrayOf(), Base64.NO_WRAP))
                .put("error", reply.stderr))
            return reply
        }

        private fun shell(command: String) = device.executeShellCommand(command)
        private fun processTicks(pid: String): String {
            check(pid.matches(Regex("[0-9]+")))
            val raw = shell("cat /proc/$pid/stat").trim()
            val end = raw.lastIndexOf(')')
            check(end >= 0)
            return raw.substring(end + 2).split(Regex("\\s+"))[19].also {
                check(it.matches(Regex("[0-9]+")))
            }
        }

        private fun requireOwnedCurrent() {
            check(device.currentPackageName == TARGET)
            check(targetPid != null && shell("pidof $TARGET").trim() == targetPid)
            processes.requireAlive(checkNotNull(targetOwner), readDeadline())
            check(automation.rootInActiveWindow?.packageName?.toString() == TARGET)
        }

        private fun currentEmpty(node: AccessibilityNodeInfo): Boolean = if (!node.isShowingHintText) node.text.isNullOrEmpty()
            else !node.hintText.isNullOrEmpty() && (node.text.isNullOrEmpty() || node.text?.toString() == node.hintText?.toString())

        /** The only substituted surface in core HOST tests is the actual public Android boundary. */
        fun preparationAndroid(): TongyiPreparationRunner.Android {
            check(preparationOnly)
            var currentNodes = emptyList<AccessibilityNodeInfo>()
            var lastFrame: TongyiPreparationRunner.Frame? = null
            fun bounds(node: AccessibilityNodeInfo): TongyiPreparationRunner.Bounds {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                return TongyiPreparationRunner.Bounds(rect.left, rect.top, rect.right, rect.bottom)
            }
            fun values(nodes: List<AccessibilityNodeInfo>) = nodes.mapIndexed { index, node ->
                TongyiPreparationRunner.Node(index.toString(), nodes.indexOf(node.parent).takeIf { it >= 0 }?.toString(),
                    node.packageName?.toString().orEmpty(), node.className?.toString().orEmpty(), bounds(node), visible(node),
                    node.isEnabled, node.isClickable, node.text?.toString().orEmpty(), node.viewIdResourceName.orEmpty(),
                    node.hintText?.toString(), node.isShowingHintText, node.isEditable, node.isFocused,
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }, node.contentDescription?.toString().orEmpty())
            }
            return object : TongyiPreparationRunner.Android {
                override fun launchOwned(deadlineNanos: Long) = this@NativePhone.launchOwned(deadlineNanos)
                override fun currentFrame(menuPixels: Boolean): TongyiPreparationRunner.Frame {
                    requireOwnedCurrent()
                    check(!File(root, "CANCEL").exists()) { "CANCELLED_NO_RETRY" }
                    val before = nodes()
                    val observed = values(before)
                    val rootBounds = bounds(before.first())
                    val rotation = device.displayRotation
                    var width = device.displayWidth
                    var height = device.displayHeight
                    var pixelSha: String? = null
                    if (menuPixels) {
                        val screenshot = checkNotNull(automation.takeScreenshot()) { "PUBLIC_SCREENSHOT_UNAVAILABLE" }
                        // Public screenshots may use a hardware Bitmap; CPU pixel reads require software.
                        // Always use the same public ARGB_8888 decoding step, never a tolerant fallback.
                        val bitmap = try { checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) {
                            "PUBLIC_ARGB_BITMAP_UNAVAILABLE"
                        } } finally { screenshot.recycle() }
                        try {
                            width = bitmap.width
                            height = bitmap.height
                            check(width == 1200 && height == 2640 && rotation == 0) { "PUBLIC_SCREENSHOT_VIEWPORT_MISMATCH" }
                            val pixels = IntArray(10000)
                            bitmap.getPixels(pixels, 0, 100, 56, 193, 100, 100)
                            val header = "ANEB807_MENU_ARGB_V1\n100\n100\n".toByteArray(Charsets.UTF_8)
                            val canonical = ByteArray(header.size + 40000)
                            header.copyInto(canonical)
                            pixels.forEachIndexed { index, argb ->
                                check(argb ushr 24 == 255) { "MENU_PIXEL_ALPHA_MISMATCH" }
                                val offset = header.size + index * 4
                                for (channel in 0..3) canonical[offset + channel] = (argb ushr (24 - channel * 8)).toByte()
                            }
                            pixelSha = sha(canonical)
                            val label = "menu-frame-${++preparationFrameSequence}"
                            FileOutputStream(File(root, "$label.png")).use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PUBLIC_SCREENSHOT_SAVE_FAILED" }
                                output.fd.sync()
                            }
                            val owner = checkNotNull(targetOwner)
                            writeJson("$label.json", JSONObject().put("region_sha256", pixelSha)
                                .put("canonical", "ANEB807_MENU_ARGB_V1_LF_100_LF_100_LF_then_y_x_ARGB_bytes")
                                .put("region_bounds", JSONArray(listOf(56,193,156,293)))
                                .put("width", width).put("height", height).put("rotation", rotation)
                                .put("root_bounds", JSONArray(listOf(rootBounds.left,rootBounds.top,rootBounds.right,rootBounds.bottom)))
                                .put("pid", owner.pid).put("uid", owner.uid).put("start_ticks", owner.startTicks)
                                .put("elapsed_realtime_nanos", SystemClock.elapsedRealtimeNanos().toString())
                                .put("qualification", "exact_current_engineering_frame_not_atomic_vendor_semantics"))
                        } finally { bitmap.recycle() }
                        val after = nodes()
                        check(values(after) == observed && bounds(after.first()) == rootBounds && device.displayRotation == rotation) {
                            "TREE_CHANGED_DURING_PUBLIC_SCREENSHOT"
                        }
                        currentNodes = after
                    } else currentNodes = before
                    val owner = checkNotNull(targetOwner)
                    return TongyiPreparationRunner.Frame(width, height, rotation, rootBounds,
                        "${owner.pid}/${owner.uid}/${owner.startTicks}/" +
                            Base64.encodeToString(owner.rawCommandLine.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), observed, pixelSha)
                        .also {
                            writeJson("preparation-geometry-${++preparationGeometrySequence}.json", JSONObject()
                                .put("width", it.width).put("height", it.height).put("rotation", it.rotation)
                                .put("root_bounds", JSONArray(listOf(it.rootBounds.left, it.rootBounds.top,
                                    it.rootBounds.right, it.rootBounds.bottom)))
                                .put("owner", it.owner)
                                .put("elapsed_realtime_nanos", SystemClock.elapsedRealtimeNanos().toString()))
                            lastFrame = it
                        }
                }
                override fun clickCurrent(node: TongyiPreparationRunner.Node): Boolean {
                    val menu = node.bounds == TongyiPreparationRunner.Bounds(56,193,156,293) && node.className == "android.widget.FrameLayout"
                    check(menu || (node.bounds == TongyiPreparationRunner.Bounds(242,2339,813,2486) && node.className == "android.widget.LinearLayout") ||
                        (node.bounds == TongyiPreparationRunner.Bounds(40,2348,1160,2486) && node.className == "android.view.ViewGroup")) {
                        "PREPARATION_CLICK_CAPABILITY_NOT_ALLOWED"
                    }
                    val expected = checkNotNull(lastFrame)
                    val fresh = currentFrame(menu)
                    check(fresh == expected) { "CURRENT_BINDING_FRAME_CHANGED" }
                    check(fresh.nodes.singleOrNull { it.id == node.id } == node) { "CURRENT_NODE_CHANGED" }
                    if (menu) check(fresh.menuArgbSha256 == TongyiPreparationRunner.MENU_SHA256) { "CURRENT_MENU_PIXELS_MISMATCH" }
                    val actual = currentNodes[node.id.toInt()]
                    check(actual.refresh()) { "CURRENT_NODE_REFRESH_FAILED" }
                    requireOwnedCurrent()
                    check(values(currentNodes) == fresh.nodes) { "CURRENT_NODE_CHANGED" }
                    return actual.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                override fun setTextCurrent(node: TongyiPreparationRunner.Node, text: String): Boolean {
                    check(text == TongyiEngineeringRunner.PAYLOAD || text.isEmpty()) { "PREPARATION_TEXT_CAPABILITY_NOT_ALLOWED" }
                    val expected = checkNotNull(lastFrame)
                    val fresh = currentFrame(false)
                    check(fresh == expected) { "CURRENT_EDITOR_FRAME_CHANGED" }
                    check(fresh.nodes.singleOrNull { it.id == node.id } == node && node.editable && node.focused && node.setTextAvailable) {
                        "CURRENT_EDITOR_CHANGED"
                    }
                    if (text.isEmpty()) check(!node.showingHint && node.text == TongyiEngineeringRunner.PAYLOAD) { "EXACT_OWN_DRAFT_REQUIRED" }
                    val actual = currentNodes[node.id.toInt()]
                    check(actual.refresh()) { "CURRENT_EDITOR_REFRESH_FAILED" }
                    requireOwnedCurrent()
                    check(values(currentNodes) == fresh.nodes) { "CURRENT_EDITOR_CHANGED" }
                    return actual.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    })
                }
                override fun saveOriginals(label: String) = this@NativePhone.saveOriginals(label)
                override fun sleepMillis(milliseconds: Long) = SystemClock.sleep(milliseconds)
                override fun cleanupOwned() = this@NativePhone.cleanupOwned()
            }
        }

        private fun nodes(): List<AccessibilityNodeInfo> {
            requireOwnedCurrent()
            val output = ArrayList<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo) {
                check(output.size < 2048) { "CURRENT_TREE_UNBOUNDED" }
                output.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
            }
            walk(checkNotNull(automation.rootInActiveWindow))
            return output
        }

        private fun visible(node: AccessibilityNodeInfo): Boolean {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (!current.isVisibleToUser) return false
                current = current.parent
            }
            return node.packageName?.toString() == TARGET
        }

        private fun matching(name: String): List<AccessibilityNodeInfo> {
            val selector = ui.getJSONObject(name)
            val fields = listOf("text", "description", "resource_id", "hint")
            check(fields.any { selector.optString(it).isNotEmpty() }) { "SEMANTIC_SELECTOR_REQUIRED_$name" }
            return nodes().filter { node ->
                visible(node) && fields.all { field ->
                    !selector.has(field) || selector.getString(field) == when (field) {
                        "text" -> node.text?.toString().orEmpty()
                        "description" -> node.contentDescription?.toString().orEmpty()
                        "resource_id" -> node.viewIdResourceName.orEmpty()
                        else -> node.hintText?.toString().orEmpty()
                    }
                }
            }
        }

        private fun requireVisible(name: String) { check(matching(name).size == 1) { "CURRENT_WITNESS_NOT_UNIQUE_$name" } }
        private fun click(name: String) {
            val node = checkNotNull(matching(name).singleOrNull()) { "CURRENT_TARGET_NOT_UNIQUE_$name" }
            check(node.isEnabled && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "ANDROID_CLICK_REFUSED_$name"
            }
        }
        private fun editNodeOrNull(): AccessibilityNodeInfo? = nodes().filter {
            visible(it) && it.className?.toString() == "android.widget.EditText" && it.isEditable
        }.let { check(it.size <= 1) { "EDITOR_NOT_UNIQUE" }; it.singleOrNull() }
        private fun editNode() = checkNotNull(editNodeOrNull()) { "CURRENT_EDITOR_UNAVAILABLE" }
        private fun action(block: () -> Unit) {
            val started = SystemClock.elapsedRealtimeNanos()
            check(started < preparationDeadline) { "PREPARATION_180_EXPIRED" }
            if (File(root, "CANCEL").exists()) error("CANCELLED_NO_RETRY")
            requireOwnedCurrent()
            block()
            check(SystemClock.elapsedRealtimeNanos() - started <= TongyiEngineeringRunner.INPUT_NANOS) { "INPUT_FIVE_SECOND_LIMIT" }
        }
        private fun waitUntil(deadline: Long, predicate: () -> Boolean) {
            while (!predicate()) {
                if (File(root, "CANCEL").exists()) error("CANCELLED_NO_RETRY")
                check(SystemClock.elapsedRealtimeNanos() < deadline) { "ANDROID_READINESS_DEADLINE" }
                SystemClock.sleep(50)
            }
        }
        private fun writeJson(name: String, value: JSONObject) {
            check(!File(root, name).exists())
            FileOutputStream(File(root, name)).use {
                it.write((value.toString(2) + "\n").toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        }
    }

    companion object {
        private const val TARGET = "com.aliyun.tongyi"
        private const val PAYLOAD_SHA = "f1e312c9123478fcf64c9ad056a436a493f6bd5dde09af029fc8ad62186efac5"
        private fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
