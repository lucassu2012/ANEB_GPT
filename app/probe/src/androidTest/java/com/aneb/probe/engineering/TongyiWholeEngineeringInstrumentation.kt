package com.aneb.probe.engineering

import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.FrameStats
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.aneb.probe.research.engineering.OwnedProcessLifecycle
import com.aneb.probe.research.engineering.OwnedShellScriptTransport
import com.aneb.probe.research.engineering.TongyiEngineeringRunner
import com.aneb.probe.research.engineering.TongyiPreparationRunner
import com.aneb.probe.research.engineering.TongyiUiReferenceQualificationRunner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
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
        check(mode == "COLLECTION" || mode == "PREPARATION_ONLY" || mode == "UI_REFERENCE_QUALIFICATION" || mode == "UI_SOURCE_INSPECTION") { "UNKNOWN_EXECUTION_MODE" }
        val preparationOnly = mode == "PREPARATION_ONLY"
        val qualificationOnly = mode == "UI_REFERENCE_QUALIFICATION"
        val sourceInspectionOnly = mode == "UI_SOURCE_INSPECTION"
        val condition = if (sourceInspectionOnly) "UI_SOURCE_INSPECTION" else if (qualificationOnly) "UI_REFERENCE_QUALIFICATION" else if (preparationOnly) "PREPARATION_ONLY" else checkNotNull(args.getString("network_condition"))
        val ui = JSONObject(String(Base64.decode(checkNotNull(args.getString("ui_contract_b64")), Base64.DEFAULT), Charsets.UTF_8))
        val parent = File(instrumentation.targetContext.getExternalFilesDir(null), "tongyi-engineering")
        check(parent.exists() || parent.mkdirs())
        val root = File(parent, runId)
        if (preparationOnly || qualificationOnly) {
            check(ui.getString("binding_scope") == "root_current_807_ui_frame") { "CURRENT_FRAME_CONTRACT_REQUIRED" }
            check(ui.getString("menu_argb_sha256").lowercase() == TongyiPreparationRunner.MENU_SHA256) { "FIXED_MENU_PIN_REQUIRED" }
            check(ui.getBoolean("new_session_text_only_attachment_contract") && ui.getJSONArray("attachment_resource_ids").length() == 0) {
                "ACCEPTED_NEW_SESSION_TEXT_ONLY_CONTRACT_REQUIRED"
            }
        }
        if (sourceInspectionOnly) {
            check(ui.getString("binding_scope") == "root_current_807_ui_frame") { "CURRENT_FRAME_CONTRACT_REQUIRED" }
        }
        val backend = NativePhone(root, condition, ui, preparationOnly, qualificationOnly, sourceInspectionOnly)
        if (qualificationOnly || sourceInspectionOnly) {
            check(args.getString("network_condition") == null) { "QUALIFICATION_NETWORK_TRANSITION_FORBIDDEN" }
            // The frozen qualifier, not this entry point, exclusively creates the consumed root.
            val runner = TongyiUiReferenceQualificationRunner(backend.qualificationAndroid(), SystemClock::elapsedRealtimeNanos)
            val result = if (sourceInspectionOnly) runner.runSourceInspectionOnce(runId, root) else runner.runOnce(runId, root)
            val terminal = JSONObject().put("run_id", runId).put("network_condition", JSONObject.NULL)
                .put("execution_mode", mode).put("feature_release", "tongyi-engineering-807")
                .put("payload_sha256", PAYLOAD_SHA).put("ui_contract", ui)
                .put("state", result.state).put("reason", result.reason ?: JSONObject.NULL)
                .put("send_calls", 0).put("capture", "NOT_STARTED").put("owned_cleanup", result.ownedCleanup)
                .put("claim_scope", if (sourceInspectionOnly) "ui_source_inspection_only" else "ui_reference_qualification_only").put("metrics", JSONObject.NULL)
                .put("formal_new_slots", 0).put("reference_accepted", false)
                .put("retained_stages", backend.qualificationRecords())
                .put("rejected_observation_retention", backend.qualificationRejectionRetention() ?: JSONObject.NULL)
                .put("sampling_claim", "sequential_public_sources_not_atomic_not_equivalent_not_accepted_reference")
                .put("clock_domain", "Android_elapsedRealtimeNanos_raw_operational_only").put("boot_id", backend.bootId)
            if (sourceInspectionOnly) {
                terminal.put("retained_sources", backend.sourceInspectionRecords()).put("accepted_stages", JSONArray())
                terminal.put("source_rejection", result.sourceRejection?.let { rejected ->
                    JSONObject().put("phase", rejected.phase.name).put("source_label", rejected.sourceLabel)
                        .put("returned_frame", rejected.frame != null).put("retention_attempted", rejected.retentionAttempted)
                        .put("diagnostic_retained", rejected.retained)
                        .put("retention_failure", rejected.retentionFailure ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
            }
            backend.sealQualificationTerminal(terminal)
            instrumentation.sendStatus(0, Bundle().apply { putString("aneb_terminal", terminal.toString()) })
            return
        }
        val result = if (preparationOnly) TongyiPreparationRunner(backend.preparationAndroid(), SystemClock::elapsedRealtimeNanos)
            .runOnce(runId, root) else TongyiEngineeringRunner(backend, SystemClock::elapsedRealtimeNanos).runOnce(runId, condition, root)
        if (preparationOnly) {
            val retention = runCatching { backend.persistRejectedPreparationObservation() }
            val report = retention.getOrNull() ?: retention.exceptionOrNull()?.let {
                "NOT_RETAINED:${it.javaClass.simpleName}"
            }
            if (report != null) runCatching {
                instrumentation.sendStatus(0, Bundle().apply { putString("aneb_preparation_rejection_retention", report) })
            }
        }
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
        private val qualificationOnly: Boolean = false,
        private val sourceInspectionOnly: Boolean = false,
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
        private data class PreparationObservationBundle(
            val frame: TongyiPreparationRunner.Frame,
            val nodeProjection: ByteArray?,
            val nodeProjectionFailure: String?,
            val diagnosticPng: ByteArray?,
            val pngCaptureState: String,
            val pngCaptureFailure: String?,
            val pngSource: String,
            val pngWidth: Int?,
            val pngHeight: Int?,
            val nodeValuesObservedNanos: Long,
            val pngStartedNanos: Long?,
            val pngCompletedNanos: Long?,
        )
        // One slot only. Pinning a rejection freezes this exact Frame and its acquired bytes.
        private var preparationObservationBundle: PreparationObservationBundle? = null
        private var preparationRejectionReason: String? = null
        private var preparationRejectionPinFailure: String? = null
        private var preparationRejectionPersistenceAttempted = false
        private val qualificationStageRecords = JSONArray()
        private val sourceInspectionRecords = JSONArray()
        private var qualificationRejectionRecord: JSONObject? = null
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

        fun qualificationRecords() = JSONArray(qualificationStageRecords.toString())
        fun sourceInspectionRecords() = JSONArray(sourceInspectionRecords.toString())

        fun qualificationRejectionRetention() = qualificationRejectionRecord?.let { JSONObject(it.toString()) }

        fun sealQualificationTerminal(value: JSONObject) {
            val raw = (value.toString(2) + "\n").toByteArray(Charsets.UTF_8)
            val file = File(root, "TERMINAL.json")
            check(file.createNewFile()) { "QUALIFICATION_TERMINAL_ALREADY_EXISTS" }
            FileOutputStream(file).use { it.write(raw); it.fd.sync() }
            check(file.readBytes().contentEquals(raw)) { "QUALIFICATION_TERMINAL_READBACK_FAILED" }
        }

        /** Separate input-only authority acquisition. Never enters legacy preparation/collection actions. */
        fun qualificationAndroid(): TongyiUiReferenceQualificationRunner.Android {
            check((qualificationOnly || sourceInspectionOnly) && !preparationOnly)
            data class Projection(val frame: TongyiPreparationRunner.Frame,
                val handles: List<AccessibilityNodeInfo>, val nanos: Long, val windowId: Int)
            data class GraphicsBinding(val owner: OwnedProcessLifecycle.Owner, val windowId: Int,
                val width: Int, val height: Int, val rotation: Int, val rootBounds: TongyiPreparationRunner.Bounds,
                val windowBounds: TongyiPreparationRunner.Bounds)
            data class GraphicsQualification(val binding: GraphicsBinding, val until: Long)
            data class GraphicsRecord(val refreshPeriodNanos: Long, val frames: List<List<Long>>)
            data class Capture(val frame: TongyiPreparationRunner.Frame, val stage: TongyiUiReferenceQualificationRunner.Stage,
                val observationId: String, val before: Projection, val after: Projection,
                val files: Map<String, ByteArray>, val xmlFile: File, val xmlNanos: Long, val pngNanos: Long,
                val sourceLabel: String? = null)
            var captured: Capture? = null
            var sourceReadinessProjection: Projection? = null
            var homeGraphics: GraphicsQualification? = null
            var sourceSequence = 0
            var retainedFrame: TongyiPreparationRunner.Frame? = null
            var writeAttempted = false
            var ownDraft: TongyiPreparationRunner.Node? = null
            var ownDraftOwner: String? = null
            var ownCleared = false
            fun gate(deadline: Long, cleanup: Boolean = false) {
                check(SystemClock.elapsedRealtimeNanos() < (if (cleanup) deadline else minOf(deadline, preparationDeadline))) {
                    "QUALIFICATION_DEADLINE_EXPIRED"
                }
                if (!cleanup) check(!File(root, "CANCEL").exists()) { "CANCELLED_NO_RETRY" }
            }
            fun requireOwner(deadline: Long, cleanup: Boolean = false): OwnedProcessLifecycle.Owner {
                gate(deadline, cleanup)
                val owner = checkNotNull(targetOwner) { "QUALIFICATION_OWNER_UNKNOWN" }
                check(targetPid == owner.pid && owner.uid == targetUid && owner.commandLine == listOf(TARGET)) {
                    "QUALIFICATION_OWNER_CHANGED"
                }
                check(device.currentPackageName == TARGET && shell("pidof $TARGET").trim() == owner.pid) {
                    "QUALIFICATION_TARGET_NOT_CURRENT"
                }
                processes.requireAlive(owner, readDeadline(deadline))
                check(automation.rootInActiveWindow?.packageName?.toString() == TARGET) { "QUALIFICATION_ROOT_NOT_OWNED" }
                gate(deadline, cleanup)
                return owner
            }
            fun nodeBounds(node: AccessibilityNodeInfo): TongyiPreparationRunner.Bounds {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                return TongyiPreparationRunner.Bounds(rect.left, rect.top, rect.right, rect.bottom)
            }
            fun project(deadline: Long, cleanup: Boolean = false): Projection {
                val owner = requireOwner(deadline, cleanup)
                val handles = ArrayList<AccessibilityNodeInfo>()
                val values = ArrayList<TongyiPreparationRunner.Node>()
                fun walk(node: AccessibilityNodeInfo, parent: String?) {
                    gate(deadline, cleanup)
                    check(handles.size < 2048) { "CURRENT_TREE_UNBOUNDED" }
                    val id = handles.size.toString()
                    handles.add(node)
                    values.add(TongyiPreparationRunner.Node(id, parent, node.packageName?.toString().orEmpty(),
                        node.className?.toString().orEmpty(), nodeBounds(node), visible(node), node.isEnabled,
                        node.isClickable, node.text?.toString().orEmpty(), node.viewIdResourceName.orEmpty(),
                        node.hintText?.toString(), node.isShowingHintText, node.isEditable, node.isFocused,
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }, node.contentDescription?.toString().orEmpty()))
                    for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, id) }
                }
                val projectedRoot = checkNotNull(automation.rootInActiveWindow)
                val windowId = projectedRoot.windowId // The same root handle whose immutable values are walked.
                walk(projectedRoot, null)
                val frame = TongyiPreparationRunner.Frame(device.displayWidth, device.displayHeight, device.displayRotation,
                    values.first().bounds, "${owner.pid}/${owner.uid}/${owner.startTicks}/" +
                        Base64.encodeToString(owner.rawCommandLine.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), values.toList(), null)
                requireOwner(deadline, cleanup)
                return Projection(frame, handles, SystemClock.elapsedRealtimeNanos(), windowId)
            }
            fun graphicsBinding(value: Projection, deadline: Long): GraphicsBinding {
                gate(deadline)
                val owner = requireOwner(deadline)
                val projectedRoot = value.handles.first()
                check(value.windowId >= 0 && projectedRoot.windowId == value.windowId) {
                    "QUALIFICATION_GRAPHICS_UNSUPPORTED_WINDOW_ID"
                }
                val window = checkNotNull(projectedRoot.window) { "QUALIFICATION_GRAPHICS_UNSUPPORTED_WINDOW" }
                val windowRoot = checkNotNull(window.root) { "QUALIFICATION_GRAPHICS_UNSUPPORTED_WINDOW_ROOT" }
                check(window.isActive && window.id == value.windowId && windowRoot.windowId == value.windowId &&
                    windowRoot.packageName?.toString() == TARGET && nodeBounds(windowRoot) == value.frame.rootBounds) {
                    "QUALIFICATION_GRAPHICS_ACTIVE_WINDOW_CHANGED"
                }
                val rect = Rect()
                window.getBoundsInScreen(rect)
                check(rect.width() > 0 && rect.height() > 0) { "QUALIFICATION_GRAPHICS_UNSUPPORTED_WINDOW_BOUNDS" }
                requireOwner(deadline)
                gate(deadline)
                return GraphicsBinding(owner, value.windowId, value.frame.width, value.frame.height,
                    value.frame.rotation, value.frame.rootBounds,
                    TongyiPreparationRunner.Bounds(rect.left, rect.top, rect.right, rect.bottom))
            }
            fun requireGraphicsBinding(value: Projection, qualification: GraphicsQualification?, deadline: Long) {
                if (qualification != null) check(deadline == qualification.until &&
                    graphicsBinding(value, deadline) == qualification.binding) { "QUALIFICATION_GRAPHICS_CAPTURE_BINDING_CHANGED" }
                gate(deadline)
            }
            fun boundsJson(frame: TongyiPreparationRunner.Frame) = JSONArray(listOf(frame.rootBounds.left,
                frame.rootBounds.top, frame.rootBounds.right, frame.rootBounds.bottom))
            fun nativeBytes(value: Projection, stage: TongyiUiReferenceQualificationRunner.Stage, observation: String,
                sourceLabel: String? = null) =
                (JSONObject(String(preparationNodeProjection(value.frame), Charsets.UTF_8))
                    .put("kind", "public_accessibility_immutable_native_node_values_not_raw_xml")
                    .put("source", "public_Android_Native").put("observation_id", observation).put("stage", stage.name)
                    .put("owner", value.frame.owner).put("display", JSONArray(listOf(value.frame.width, value.frame.height, value.frame.rotation)))
                    .put("root_bounds", boundsJson(value.frame)).put("elapsed_realtime_nanos", value.nanos.toString())
                    .apply { if (sourceLabel != null) put("source_label", sourceLabel) }
                    .toString(2) + "\n").toByteArray(Charsets.UTF_8)
            fun preserveAcquired(name: String, bytes: ByteArray, deadline: Long = preparationDeadline) {
                gate(deadline)
                val file = File(root, name)
                check(file.createNewFile()) { "SOURCE_ORIGINAL_ALREADY_EXISTS_NO_RETRY" }
                FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
                check(file.readBytes().contentEquals(bytes)) { "SOURCE_ORIGINAL_READBACK_FAILED" }
                gate(deadline)
            }
            fun freshBinding(observed: TongyiPreparationRunner.Frame, node: TongyiPreparationRunner.Node, deadline: Long): Projection {
                check(captured?.frame === observed && retainedFrame === observed &&
                    observed.nodes.singleOrNull { it.id == node.id } == node) { "EXACT_RETAINED_FRAME_NODE_REQUIRED" }
                val fresh = project(deadline)
                check(fresh.frame == observed.copy(menuArgbSha256 = null)) { "QUALIFICATION_CURRENT_FRAME_CHANGED" }
                val actual = fresh.handles[node.id.toInt()]
                check(actual.refresh()) { "QUALIFICATION_NODE_REFRESH_FAILED" }
                val refreshed = project(deadline)
                check(refreshed.frame == fresh.frame && refreshed.frame.nodes.singleOrNull { it.id == node.id } == node) {
                    "QUALIFICATION_CURRENT_NODE_CHANGED"
                }
                return refreshed
            }
            fun empty(node: TongyiPreparationRunner.Node) = if (!node.showingHint) node.text.isEmpty()
                else !node.hint.isNullOrEmpty() && (node.text.isEmpty() || node.text == node.hint)
            return object : TongyiUiReferenceQualificationRunner.Android {
                override fun launchOwned(deadlineNanos: Long) {
                    homeGraphics = null
                    this@NativePhone.launchOwned(deadlineNanos)
                }
                override fun currentState(deadlineNanos: Long): TongyiPreparationRunner.Frame = try {
                    project(deadlineNanos).also {
                        if (sourceInspectionOnly) sourceReadinessProjection = it.copy(handles = emptyList())
                    }.frame
                } catch (failure: Exception) { homeGraphics = null; throw failure }
                override fun awaitOwnedHomeGraphics(deadlineNanos: Long) {
                    homeGraphics = null
                    gate(deadlineNanos)
                    check(Build.VERSION.SDK_INT >= 21) { "QUALIFICATION_GRAPHICS_UNSUPPORTED_API" }
                    val binding = graphicsBinding(project(deadlineNanos), deadlineNanos)
                    var previous: GraphicsRecord? = null
                    while (true) {
                        check(graphicsBinding(project(deadlineNanos), deadlineNanos) == binding) {
                            "QUALIFICATION_GRAPHICS_WINDOW_BINDING_CHANGED"
                        }
                        gate(deadlineNanos)
                        val stats = checkNotNull(automation.getWindowContentFrameStats(binding.windowId)) {
                            "QUALIFICATION_GRAPHICS_UNSUPPORTED_STATS"
                        }
                        gate(deadlineNanos) // A synchronous Binder getter has no forcibly enforced timeout argument.
                        val count = stats.frameCount
                        check(count in 1..2048 && stats.refreshPeriodNano > 0) {
                            "QUALIFICATION_GRAPHICS_UNSUPPORTED_EMPTY_OR_UNBOUNDED_STATS"
                        }
                        val rows = (0 until count).map { index -> listOf(stats.getFramePostedTimeNano(index),
                            stats.getFrameReadyTimeNano(index), stats.getFramePresentedTimeNano(index)) }
                        val record = GraphicsRecord(stats.refreshPeriodNano, rows) // Full original order, no tail/filter.
                        check(graphicsBinding(project(deadlineNanos), deadlineNanos) == binding) {
                            "QUALIFICATION_GRAPHICS_WINDOW_BINDING_CHANGED"
                        }
                        // No public stable row ID/drop-vs-pending discriminator: never correlate or forget an unresolved row.
                        // Fail this entire boundary immediately; history rollover cannot later manufacture READY.
                        check(rows.all { row -> row.all { it != FrameStats.UNDEFINED_TIME_NANO && it >= 0 } }) {
                            "QUALIFICATION_GRAPHICS_PENDING_HISTORY_UNKNOWN"
                        }
                        gate(deadlineNanos)
                        if (previous == record) {
                            homeGraphics = GraphicsQualification(binding, deadlineNanos)
                            return
                        }
                        previous = record
                        gate(deadlineNanos)
                        SystemClock.sleep(50)
                        gate(deadlineNanos)
                    }
                }
                override fun currentFrame(menuPixels: Boolean): TongyiPreparationRunner.Frame {
                    val qualification = if (menuPixels) checkNotNull(homeGraphics) {
                        "QUALIFICATION_GRAPHICS_PRECONDITION_REQUIRED"
                    } else null
                    homeGraphics = null // Consume once, including every failure during this acquisition.
                    val captureDeadline = qualification?.until ?: preparationDeadline
                    gate(captureDeadline)
                    val sourceLabel = if (sourceInspectionOnly) {
                        check(sourceSequence < 2) { "SOURCE_TWO_ACQUISITIONS_ALREADY_CONSUMED" }
                        "source-home-${++sourceSequence}"
                    } else null
                    val stage = if (sourceInspectionOnly) TongyiUiReferenceQualificationRunner.Stage.HOME else
                        TongyiUiReferenceQualificationRunner.Stage.values().getOrNull(qualificationStageRecords.length())
                            ?: error("QUALIFICATION_ALL_STAGES_ALREADY_RETAINED")
                    check(menuPixels == (sourceInspectionOnly || stage == TongyiUiReferenceQualificationRunner.Stage.HOME)) { "QUALIFICATION_CAPTURE_ORDER_CHANGED" }
                    val before = project(captureDeadline)
                    requireGraphicsBinding(before, qualification, captureDeadline)
                    val prefix = (sourceLabel ?: stage.name.lowercase()) + "-"
                    val observation = root.name + "-" + (sourceLabel ?: stage.name) + "-" + before.nanos
                    val beforeBytes = nativeBytes(before, stage, observation, sourceLabel)
                    if (sourceInspectionOnly) preserveAcquired(prefix + "native-before.json", beforeBytes, captureDeadline)
                    val xmlFile = File(root, prefix + "hierarchy.xml")
                    check(xmlFile.createNewFile()) { "QUALIFICATION_XML_ALREADY_EXISTS_NO_RETRY" }
                    device.dumpWindowHierarchy(xmlFile) // Exactly one raw XML acquisition, at this capture only.
                    if (sourceInspectionOnly) FileOutputStream(xmlFile, true).use { it.fd.sync() }
                    val xml = xmlFile.readBytes().also { check(it.isNotEmpty()) { "QUALIFICATION_XML_EMPTY" } }
                    val xmlNanos = SystemClock.elapsedRealtimeNanos()
                    requireOwner(captureDeadline)
                    if (qualification != null) requireGraphicsBinding(project(captureDeadline), qualification, captureDeadline)
                    val screenshot = checkNotNull(automation.takeScreenshot()) { "QUALIFICATION_PNG_UNAVAILABLE" }
                    val bitmap = try { checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) {
                        "QUALIFICATION_ARGB_UNAVAILABLE"
                    } } finally { screenshot.recycle() }
                    var menuSha: String? = null
                    val png = try {
                        check(bitmap.width == before.frame.width && bitmap.height == before.frame.height) { "QUALIFICATION_PNG_GEOMETRY_CHANGED" }
                        // Source-only writes each acquired original before later validation can fail.
                        // Still exactly one screenshot, software Bitmap and PNG encoding per observation.
                        val acquiredPng = if (sourceInspectionOnly) ByteArrayOutputStream().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "QUALIFICATION_PNG_ENCODING_FAILED" }
                            output.toByteArray().also { preserveAcquired(prefix + "screen.png", it, captureDeadline) }
                        } else null
                        if (qualification != null) requireGraphicsBinding(project(captureDeadline), qualification, captureDeadline)
                        if (menuPixels) {
                            val pixels = IntArray(10000)
                            bitmap.getPixels(pixels, 0, 100, 56, 193, 100, 100)
                            val header = "ANEB807_MENU_ARGB_V1\n100\n100\n".toByteArray(Charsets.UTF_8)
                            val canonical = ByteArray(header.size + 40000)
                            header.copyInto(canonical)
                            pixels.forEachIndexed { index, argb ->
                                check(argb ushr 24 == 255) { "MENU_PIXEL_ALPHA_MISMATCH" }
                                for (channel in 0..3) canonical[header.size + index * 4 + channel] = (argb ushr (24 - channel * 8)).toByte()
                            }
                            menuSha = sha(canonical) // The same single Bitmap, never a second image.
                        }
                        acquiredPng ?: ByteArrayOutputStream().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "QUALIFICATION_PNG_ENCODING_FAILED" }
                            output.toByteArray()
                        }
                    } finally { bitmap.recycle() }
                    val pngNanos = SystemClock.elapsedRealtimeNanos()
                    val after = project(captureDeadline)
                    val afterBytes = nativeBytes(after, stage, observation, sourceLabel)
                    if (sourceInspectionOnly) preserveAcquired(prefix + "native-after.json", afterBytes, captureDeadline)
                    requireGraphicsBinding(after, qualification, captureDeadline)
                    if (!sourceInspectionOnly) check(after.frame == before.frame) { "QUALIFICATION_NATIVE_CHANGED_DURING_SEQUENTIAL_CAPTURE" }
                    val frame = before.frame.copy(menuArgbSha256 = menuSha)
                    captured = Capture(frame, stage, observation, before, after, mapOf(
                        prefix + "native-before.json" to beforeBytes,
                        prefix + "hierarchy.xml" to xml, prefix + "screen.png" to png,
                        prefix + "native-after.json" to afterBytes), xmlFile, xmlNanos, pngNanos, sourceLabel)
                    gate(captureDeadline)
                    return frame
                }
                override fun retainRejectedSourceObservation(phase: TongyiUiReferenceQualificationRunner.SourcePhase,
                    sourceLabel: String, observed: TongyiPreparationRunner.Frame, reason: String, deadlineNanos: Long): Boolean {
                    check(sourceInspectionOnly) { "SOURCE_REJECTION_MODE_REQUIRED" }
                    if (qualificationRejectionRecord != null) return false
                    val pins = JSONArray()
                    val report = JSONObject().put("status", "NOT_RETAINED").put("reason", reason)
                        .put("phase", phase.name).put("source_label", sourceLabel)
                        .put("diagnostic_only", true).put("accepted_stage", false).put("accepted_source", false)
                        .put("reference_accepted", false).put("deadline_nanos", minOf(deadlineNanos, preparationDeadline).toString())
                        .put("artifacts", pins)
                    qualificationRejectionRecord = report
                    return try {
                        gate(deadlineNanos)
                        val readiness = phase == TongyiUiReferenceQualificationRunner.SourcePhase.INITIAL_READINESS ||
                            phase == TongyiUiReferenceQualificationRunner.SourcePhase.SECOND_READINESS
                        val projection = if (readiness) {
                            check(sourceLabel == "source-home-${sourceSequence + 1}") { "SOURCE_REJECTED_READINESS_LABEL_CHANGED" }
                            checkNotNull(sourceReadinessProjection) { "SOURCE_REJECTED_READINESS_NOT_CACHED" }
                        } else {
                            check(phase == TongyiUiReferenceQualificationRunner.SourcePhase.SOURCE_VALIDATION ||
                                phase == TongyiUiReferenceQualificationRunner.SourcePhase.PAIR_VALIDATION) { "SOURCE_REJECTED_RETURNED_PHASE_REQUIRED" }
                            val bundle = checkNotNull(captured) { "SOURCE_REJECTED_CAPTURE_NOT_CACHED" }
                            check(bundle.frame === observed && bundle.sourceLabel == sourceLabel) { "SOURCE_REJECTED_CAPTURE_BINDING_CHANGED" }
                            Projection(observed, emptyList(), bundle.before.nanos, bundle.before.windowId)
                        }
                        check(projection.frame === observed) { "SOURCE_REJECTED_EXACT_RETURNED_FRAME_REQUIRED" }
                        // Only immutable cached values and their original observation clock. No project/owner/node/XML/PNG call.
                        val observation = root.name + "-" + sourceLabel + "-" + phase.name + "-" + projection.nanos
                        val value = JSONObject(String(nativeBytes(projection, TongyiUiReferenceQualificationRunner.Stage.HOME,
                            observation, sourceLabel), Charsets.UTF_8))
                            .put("phase", phase.name).put("diagnostic_only", true).put("accepted_stage", false)
                            .put("accepted_source", false).put("reference_accepted", false)
                            .put("projection_source", if (readiness) "cached_returned_readiness_native_values_only" else "cached_returned_source_native_values_only")
                        val raw = (value.toString(2) + "\n").toByteArray(Charsets.UTF_8)
                        report.put("observation_id", observation).put("owner", observed.owner)
                            .put("display", JSONArray(listOf(observed.width, observed.height, observed.rotation)))
                            .put("root_bounds", boundsJson(observed)).put("elapsed_realtime_nanos", projection.nanos.toString())
                            .put("sampling_claim", "one_cached_native_projection_no_new_query_no_xml_no_png_not_a_source_pair")
                            .put("readiness_only", readiness)
                        gate(deadlineNanos)
                        val file = File(root, sourceLabel + "-rejected-native.json")
                        check(file.createNewFile()) { "SOURCE_REJECTED_NATIVE_ALREADY_EXISTS_NO_RETRY" }
                        FileOutputStream(file).use { it.write(raw); it.fd.sync() }
                        gate(deadlineNanos)
                        val actual = file.readBytes()
                        check(actual.contentEquals(raw)) { "SOURCE_REJECTED_NATIVE_READBACK_FAILED" }
                        pins.put(JSONObject().put("path", file.name).put("bytes", actual.size).put("sha256", sha(actual)))
                        gate(deadlineNanos)
                        val receipt = JSONObject(report.toString()).apply { remove("status"); put("originals_readback_complete", true) }
                        val receiptRaw = (receipt.toString(2) + "\n").toByteArray(Charsets.UTF_8)
                        val receiptFile = File(root, "source-rejected-observation-retention.json")
                        check(receiptFile.createNewFile()) { "SOURCE_REJECTED_RECEIPT_ALREADY_EXISTS_NO_RETRY" }
                        FileOutputStream(receiptFile).use { it.write(receiptRaw); it.fd.sync() }
                        gate(deadlineNanos)
                        val receiptActual = receiptFile.readBytes()
                        check(receiptActual.contentEquals(receiptRaw)) { "SOURCE_REJECTED_RECEIPT_READBACK_FAILED" }
                        gate(deadlineNanos)
                        report.put("receipt", JSONObject().put("path", receiptFile.name).put("bytes", receiptActual.size).put("sha256", sha(receiptActual)))
                            .put("status", "RETAINED")
                        true
                    } catch (failure: Exception) {
                        report.put("status", "NOT_RETAINED")
                            .put("retention_failure", failure.javaClass.simpleName + ":" + (failure.message ?: "UNKNOWN"))
                        false
                    }
                }
                override fun retainRejectedObservation(stage: TongyiUiReferenceQualificationRunner.Stage,
                    observed: TongyiPreparationRunner.Frame, reason: String, deadlineNanos: Long): Boolean {
                    // One attempt only, using immutable cached bytes. No UI/owner query or fresh acquisition here.
                    if (qualificationRejectionRecord != null) return false
                    val pins = JSONArray()
                    val report = JSONObject().put("status", "NOT_RETAINED").put("reason", reason)
                        .put("diagnostic_only", true).put("accepted_stage", false).put("reference_accepted", false)
                        .put("stage", stage.name).put("deadline_nanos", minOf(deadlineNanos, preparationDeadline).toString())
                        .put("artifacts", pins)
                    qualificationRejectionRecord = report
                    return try {
                        val bundle = checkNotNull(captured) { "QUALIFICATION_REJECTED_CAPTURE_NOT_CACHED" }
                        check(bundle.frame === observed && bundle.stage == stage && retainedFrame !== observed &&
                            stage.ordinal == qualificationStageRecords.length() && bundle.files.size == 4) {
                            "QUALIFICATION_REJECTED_EXACT_CAPTURE_REQUIRED"
                        }
                        report.put("observation_id", bundle.observationId)
                            .put("owner_before", bundle.before.frame.owner).put("owner_after", bundle.after.frame.owner)
                            .put("display", JSONArray(listOf(observed.width, observed.height, observed.rotation)))
                            .put("root_bounds", boundsJson(observed)).put("source", "public_Android_Native_XML_PNG_sequential")
                            .put("sampling_order", JSONArray(listOf("NATIVE_BEFORE", "RAW_XML", "PNG", "NATIVE_AFTER")))
                            .put("native_before_nanos", bundle.before.nanos.toString()).put("xml_nanos", bundle.xmlNanos.toString())
                            .put("png_nanos", bundle.pngNanos.toString()).put("native_after_nanos", bundle.after.nanos.toString())
                            .put("atomic_same_frame", false).put("menu_argb_sha256", observed.menuArgbSha256 ?: JSONObject.NULL)
                            .put("expected_menu_argb_sha256", TongyiPreparationRunner.MENU_SHA256)
                        bundle.files.forEach { (name, bytes) ->
                            gate(deadlineNanos)
                            val file = File(root, name)
                            if (file != bundle.xmlFile) {
                                check(file.createNewFile()) { "QUALIFICATION_REJECTED_ORIGINAL_ALREADY_EXISTS_NO_RETRY" }
                                FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
                            }
                            gate(deadlineNanos)
                            val actual = file.readBytes()
                            check(actual.isNotEmpty() && actual.contentEquals(bytes)) { "QUALIFICATION_REJECTED_ORIGINAL_READBACK_FAILED" }
                            pins.put(JSONObject().put("path", name).put("bytes", actual.size).put("sha256", sha(actual)))
                        }
                        gate(deadlineNanos)
                        // This receipt states the four completed readbacks, not acceptance or atomic sampling.
                        val receipt = JSONObject(report.toString()).apply {
                            remove("status")
                            put("originals_readback_complete", true)
                        }
                        val raw = (receipt.toString(2) + "\n").toByteArray(Charsets.UTF_8)
                        val file = File(root, "qualification-rejected-observation-retention.json")
                        check(file.createNewFile()) { "QUALIFICATION_REJECTED_RECEIPT_ALREADY_EXISTS_NO_RETRY" }
                        FileOutputStream(file).use { it.write(raw); it.fd.sync() }
                        gate(deadlineNanos)
                        val actual = file.readBytes()
                        check(actual.contentEquals(raw)) { "QUALIFICATION_REJECTED_RECEIPT_READBACK_FAILED" }
                        gate(deadlineNanos)
                        report.put("receipt", JSONObject().put("path", file.name).put("bytes", actual.size).put("sha256", sha(actual)))
                            .put("status", "RETAINED")
                        true
                    } catch (failure: Exception) {
                        report.put("status", "NOT_RETAINED")
                            .put("retention_failure", failure.javaClass.simpleName + ":" + (failure.message ?: "UNKNOWN"))
                        false
                    }
                }
                override fun retainStage(stage: TongyiUiReferenceQualificationRunner.Stage, observed: TongyiPreparationRunner.Frame): Boolean {
                    check(!sourceInspectionOnly) { "SOURCE_UIQ_RETENTION_FORBIDDEN" }
                    val bundle = checkNotNull(captured)
                    check(bundle.frame === observed && bundle.stage == stage &&
                        stage.ordinal == qualificationStageRecords.length()) { "QUALIFICATION_EXACT_CAPTURE_NOT_CACHED" }
                    val pins = JSONArray()
                    bundle.files.forEach { (name, bytes) ->
                        gate(preparationDeadline)
                        val file = File(root, name)
                        if (file != bundle.xmlFile) {
                            check(file.createNewFile()) { "QUALIFICATION_ORIGINAL_ALREADY_EXISTS_NO_RETRY" }
                            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
                        }
                        val actual = file.readBytes()
                        check(actual.isNotEmpty() && actual.contentEquals(bytes)) { "QUALIFICATION_ORIGINAL_READBACK_FAILED" }
                        pins.put(JSONObject().put("path", name).put("bytes", actual.size).put("sha256", sha(actual)))
                    }
                    gate(preparationDeadline)
                    qualificationStageRecords.put(JSONObject().put("stage", stage.name).put("observation_id", bundle.observationId)
                        .put("owner_before", bundle.before.frame.owner).put("owner_after", bundle.after.frame.owner)
                        .put("display", JSONArray(listOf(observed.width, observed.height, observed.rotation)))
                        .put("root_bounds", boundsJson(observed)).put("source", "public_Android_Native_XML_PNG_sequential")
                        .put("sampling_order", JSONArray(listOf("NATIVE_BEFORE", "RAW_XML", "PNG", "NATIVE_AFTER")))
                        .put("native_before_nanos", bundle.before.nanos.toString()).put("xml_nanos", bundle.xmlNanos.toString())
                        .put("png_nanos", bundle.pngNanos.toString()).put("native_after_nanos", bundle.after.nanos.toString())
                        .put("atomic_same_frame", false).put("reference_accepted", false)
                        .put("menu_argb_sha256", observed.menuArgbSha256 ?: JSONObject.NULL).put("artifacts", pins))
                    retainedFrame = observed
                    return true
                }
                override fun retainSource(sourceLabel: String, observed: TongyiPreparationRunner.Frame): Boolean {
                    check(sourceInspectionOnly && qualificationStageRecords.length() == 0) { "SOURCE_MODE_REQUIRED" }
                    val bundle = checkNotNull(captured)
                    check(bundle.frame === observed && bundle.sourceLabel == sourceLabel &&
                        sourceLabel == "source-home-${sourceInspectionRecords.length() + 1}" && bundle.files.size == 4) {
                        "SOURCE_EXACT_CAPTURE_NOT_CACHED"
                    }
                    val pins = JSONArray()
                    bundle.files.forEach { (name, bytes) ->
                        gate(preparationDeadline)
                        val actual = File(root, name).readBytes()
                        check(actual.isNotEmpty() && actual.contentEquals(bytes)) { "SOURCE_ORIGINAL_READBACK_FAILED" }
                        pins.put(JSONObject().put("path", name).put("bytes", actual.size).put("sha256", sha(actual)))
                    }
                    sourceInspectionRecords.put(JSONObject().put("stage", "HOME").put("source_label", sourceLabel)
                        .put("observation_id", bundle.observationId)
                        .put("owner_before", bundle.before.frame.owner).put("owner_after", bundle.after.frame.owner)
                        .put("display", JSONArray(listOf(observed.width, observed.height, observed.rotation)))
                        .put("root_bounds", boundsJson(observed)).put("source", "public_Android_Native_XML_PNG_sequential")
                        .put("sampling_order", JSONArray(listOf("NATIVE_BEFORE", "RAW_XML", "PNG", "NATIVE_AFTER")))
                        .put("native_before_nanos", bundle.before.nanos.toString()).put("xml_nanos", bundle.xmlNanos.toString())
                        .put("png_nanos", bundle.pngNanos.toString()).put("native_after_nanos", bundle.after.nanos.toString())
                        .put("atomic_same_frame", false).put("reference_accepted", false)
                        .put("menu_argb_sha256", observed.menuArgbSha256 ?: JSONObject.NULL).put("artifacts", pins))
                    check(bundle.before.frame == bundle.after.frame) { "SOURCE_NATIVE_CHANGED_DURING_SEQUENTIAL_CAPTURE" }
                    gate(preparationDeadline)
                    return true
                }
                override fun clickNamedCurrent(role: TongyiUiReferenceQualificationRunner.NamedClick,
                    observed: TongyiPreparationRunner.Frame, node: TongyiPreparationRunner.Node): Boolean {
                    check(!sourceInspectionOnly) { "SOURCE_CLICK_FORBIDDEN" }
                    val deadline = minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.INPUT_NANOS)
                    check(node.clickable && node.enabled && node.visible && node.packageName == TARGET) { "QUALIFICATION_CLICK_NOT_ALLOWED" }
                    when (role) {
                        TongyiUiReferenceQualificationRunner.NamedClick.MENU -> {
                            check(observed.menuArgbSha256 == TongyiPreparationRunner.MENU_SHA256 &&
                                node.className == "android.widget.FrameLayout" && node.bounds == TongyiPreparationRunner.Bounds(56,193,156,293)) {
                                "QUALIFICATION_MENU_BINDING_MISMATCH"
                            }
                            check(observed.nodes.singleOrNull { it.id == node.parent }?.let {
                                it.className == "android.widget.LinearLayout" && !it.clickable && it.bounds == node.bounds
                            } == true) { "QUALIFICATION_MENU_DIRECT_PARENT_REQUIRED" }
                        }
                        TongyiUiReferenceQualificationRunner.NamedClick.NEW_SESSION,
                        TongyiUiReferenceQualificationRunner.NamedClick.COMPOSER_FOCUS -> {
                            val newSession = role == TongyiUiReferenceQualificationRunner.NamedClick.NEW_SESSION
                            val witness = observed.nodes.singleOrNull { it.visible && it.packageName == TARGET &&
                                it.className == "android.widget.TextView" && it.text == (if (newSession) "新建对话" else "发消息或按住说话...") &&
                                (!newSession || it.resourceId == "com.aliyun.tongyi:id/tv_new_session") }
                            check(witness != null && witness.parent == node.id &&
                                node.className == (if (newSession) "android.widget.LinearLayout" else "android.view.ViewGroup")) {
                                "QUALIFICATION_EXACT_DIRECT_TARGET_REQUIRED"
                            }
                        }
                    }
                    val fresh = freshBinding(observed, node, deadline)
                    requireOwner(deadline)
                    val accepted = fresh.handles[node.id.toInt()].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    gate(deadline)
                    return accepted
                }
                override fun setTextCurrent(observed: TongyiPreparationRunner.Frame,
                    editor: TongyiPreparationRunner.Node, text: String): Boolean {
                    check(!sourceInspectionOnly) { "SOURCE_TEXT_FORBIDDEN" }
                    val deadline = minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.INPUT_NANOS)
                    check(text == TongyiEngineeringRunner.PAYLOAD || text.isEmpty()) { "QUALIFICATION_TEXT_CAPABILITY_NOT_ALLOWED" }
                    check(editor.editable && editor.focused && editor.setTextAvailable) { "QUALIFICATION_EDITOR_NOT_CURRENT" }
                    if (text.isEmpty()) check(ownDraft == editor && ownDraftOwner == observed.owner &&
                        !editor.showingHint && editor.text == TongyiEngineeringRunner.PAYLOAD) { "QUALIFICATION_ATTRIBUTED_OWN_DRAFT_REQUIRED" }
                    else check(!writeAttempted && empty(editor) && text.toByteArray(Charsets.UTF_8).size == 239) { "QUALIFICATION_EMPTY_EDITOR_REQUIRED" }
                    val fresh = freshBinding(observed, editor, deadline)
                    if (text.isNotEmpty()) writeAttempted = true
                    check(fresh.handles[editor.id.toInt()].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    })) { "QUALIFICATION_SET_TEXT_REFUSED" }
                    while (true) {
                        val readback = project(deadline)
                        val input = readback.frame.nodes.singleOrNull { it.visible && it.editable && it.packageName == TARGET }
                            ?: error("QUALIFICATION_READBACK_EDITOR_UNKNOWN")
                        check(input.id == editor.id && input.parent == editor.parent && input.className == editor.className &&
                            input.bounds == editor.bounds && input.focused && input.setTextAvailable) { "QUALIFICATION_READBACK_BINDING_CHANGED" }
                        if (text.isNotEmpty() && !input.showingHint && input.text == text && input.text.toByteArray(Charsets.UTF_8).size == 239) {
                            ownDraft = input
                            ownDraftOwner = readback.frame.owner
                            break
                        }
                        if (text.isEmpty() && empty(input)) { ownCleared = true; ownDraft = null; break }
                        check(empty(input) || (!input.showingHint && input.text == TongyiEngineeringRunner.PAYLOAD)) {
                            "QUALIFICATION_UNKNOWN_REPLACEMENT_DRAFT_NOT_TOUCHED"
                        }
                        gate(deadline)
                        SystemClock.sleep(50)
                    }
                    gate(deadline)
                    return true
                }
                override fun sleepMillis(milliseconds: Long) = SystemClock.sleep(milliseconds)
                override fun cleanupOwned(): Boolean {
                    homeGraphics = null
                    // No recorder call and no collection draft branch. Process closure is attempted even if input cleanup fails.
                    val deadline = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
                    var draftSafe = !writeAttempted || ownCleared
                    if (!draftSafe && ownDraft != null) draftSafe = runCatching {
                        val fresh = project(deadline, cleanup = true)
                        val input = fresh.frame.nodes.singleOrNull { it.visible && it.editable && it.packageName == TARGET }
                            ?: error("QUALIFICATION_CLEANUP_EDITOR_UNKNOWN")
                        if (!empty(input)) {
                            check(input == ownDraft && fresh.frame.owner == ownDraftOwner) { "QUALIFICATION_UNKNOWN_DRAFT_NOT_CLEARED" }
                            val actual = fresh.handles[input.id.toInt()]
                            check(actual.refresh() && project(deadline, cleanup = true).frame == fresh.frame) { "QUALIFICATION_CLEANUP_BINDING_CHANGED" }
                            check(actual.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                            })) { "QUALIFICATION_OWN_CLEAR_REFUSED" }
                            val cleared = project(deadline, cleanup = true).frame.nodes.singleOrNull { it.visible && it.editable && it.packageName == TARGET }
                            check(cleared != null && empty(cleared)) { "QUALIFICATION_OWN_CLEAR_UNCONFIRMED" }
                        }
                        true
                    }.getOrDefault(false)
                    val owner = targetOwner
                    val closed = if (owner == null) targetPid == null else runCatching {
                        when (val observed = processes.observe(owner.pid, readDeadline(deadline))) {
                            OwnedProcessLifecycle.Observation.Absent -> Unit
                            is OwnedProcessLifecycle.Observation.Unknown -> error("QUALIFICATION_TARGET_CLOSURE_UNKNOWN_${observed.reason}")
                            is OwnedProcessLifecycle.Observation.Alive -> check(observed.owner == owner && owner.uid == targetUid) {
                                "QUALIFICATION_TARGET_IDENTITY_DRIFT_NOT_TOUCHED"
                            }
                        }
                        check(processes.stopAndConfirm(owner, OwnedProcessLifecycle.Stop.TARGET_FORCE_STOP, deadline))
                        processes.requirePackageAbsent(TARGET, readDeadline(deadline))
                        true
                    }.getOrDefault(false)
                    if (closed) { targetOwner = null; targetPid = null; targetTicks = null }
                    if (closed && (device.currentPackageName == TARGET || device.currentPackageName == context.packageName)) device.pressHome()
                    val absent = runCatching { processes.requirePackageAbsent(TARGET, readDeadline(deadline)); true }.getOrDefault(false)
                    return draftSafe && closed && absent && device.currentPackageName == "com.huawei.android.launcher"
                }
            }
        }

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
                    val observationOwner = targetOwner
                    val before = nodes()
                    val observed = values(before)
                    val rootBounds = bounds(before.first())
                    val rotation = device.displayRotation
                    var width = device.displayWidth
                    var height = device.displayHeight
                    val nodeValuesObservedNanos = SystemClock.elapsedRealtimeNanos()
                    var pixelSha: String? = null
                    var diagnosticPng: ByteArray? = null
                    var pngCaptureState = "NOT_REQUESTED"
                    var pngCaptureFailure: String? = null
                    var pngSource = "NOT_REQUESTED"
                    var pngWidth: Int? = null
                    var pngHeight: Int? = null
                    var pngStartedNanos: Long? = null
                    var pngCompletedNanos: Long? = null
                    if (menuPixels) {
                        pngSource = "existing_menu_public_argb_png"
                        pngStartedNanos = SystemClock.elapsedRealtimeNanos()
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
                            // Reuse this acquisition's existing PNG; no second screenshot or Bitmap pointer.
                            val reuse = runCatching {
                                check(SystemClock.elapsedRealtimeNanos() < preparationDeadline) { "DIAGNOSTIC_PREPARATION_DEADLINE_EXPIRED" }
                                File(root, "$label.png").readBytes().also { check(it.isNotEmpty()) }
                            }
                            diagnosticPng = reuse.getOrNull()
                            pngCaptureState = if (reuse.isSuccess) "ACQUIRED" else "NOT_RETAINED"
                            pngCaptureFailure = reuse.exceptionOrNull()?.javaClass?.simpleName
                            pngWidth = bitmap.width
                            pngHeight = bitmap.height
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
                        val completed = SystemClock.elapsedRealtimeNanos()
                        pngCompletedNanos = completed
                        if (completed >= preparationDeadline) {
                            diagnosticPng = null
                            pngCaptureState = "NOT_RETAINED"
                            pngCaptureFailure = "DIAGNOSTIC_PREPARATION_DEADLINE_EXPIRED"
                        }
                    } else {
                        currentNodes = before
                        val incompatibleGeometry = width != 1200 || height != 2640 || rotation != 0 ||
                            rootBounds != TongyiPreparationRunner.Bounds(0, 0, 1200, 2486)
                        if (incompatibleGeometry && preparationRejectionReason == null) {
                            pngSource = "same_observation_incompatible_geometry_diagnostic"
                            pngStartedNanos = SystemClock.elapsedRealtimeNanos()
                            // Synchronous public capture, sequential after node values; not atomic or a selector source.
                            val capture = runCatching {
                                check(SystemClock.elapsedRealtimeNanos() < preparationDeadline) { "DIAGNOSTIC_PREPARATION_DEADLINE_EXPIRED" }
                                requireDiagnosticCaptureOwner(observationOwner)
                                val screenshot = checkNotNull(automation.takeScreenshot()) { "DIAGNOSTIC_SCREENSHOT_UNAVAILABLE" }
                                val bitmap = try { checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) {
                                    "DIAGNOSTIC_ARGB_BITMAP_UNAVAILABLE"
                                } } finally { screenshot.recycle() }
                                val acquired = try {
                                    ByteArrayOutputStream().use { output ->
                                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "DIAGNOSTIC_PNG_ENCODING_FAILED" }
                                        Triple(output.toByteArray(), bitmap.width, bitmap.height)
                                    }
                                } finally { bitmap.recycle() }
                                requireDiagnosticCaptureOwner(observationOwner)
                                check(SystemClock.elapsedRealtimeNanos() < preparationDeadline) { "DIAGNOSTIC_PREPARATION_DEADLINE_EXPIRED" }
                                acquired
                            }
                            val completed = SystemClock.elapsedRealtimeNanos()
                            pngCompletedNanos = completed
                            capture.getOrNull()?.takeIf { completed < preparationDeadline }?.let { acquired ->
                                diagnosticPng = acquired.first
                                pngWidth = acquired.second
                                pngHeight = acquired.third
                            }
                            pngCaptureState = if (diagnosticPng != null) "ACQUIRED" else "NOT_RETAINED"
                            pngCaptureFailure = if (completed >= preparationDeadline) "DIAGNOSTIC_PREPARATION_DEADLINE_EXPIRED"
                                else capture.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                        }
                    }
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
                            if (preparationRejectionReason == null) {
                                val projection = runCatching { preparationNodeProjection(it) }
                                preparationObservationBundle = PreparationObservationBundle(it, projection.getOrNull(),
                                    projection.exceptionOrNull()?.javaClass?.simpleName, diagnosticPng, pngCaptureState,
                                    pngCaptureFailure, pngSource, pngWidth, pngHeight, nodeValuesObservedNanos,
                                    pngStartedNanos, pngCompletedNanos)
                            }
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
                override fun onFrameRejected(observed: TongyiPreparationRunner.Frame, reason: String) {
                    if (preparationRejectionReason != null) return
                    if (preparationObservationBundle?.frame !== observed) {
                        preparationRejectionPinFailure = "REJECTED_FRAME_REFERENCE_NOT_CACHED"
                        return
                    }
                    preparationRejectionReason = reason // Memory-only pin; no UI, filesystem or resampling.
                }
                override fun sleepMillis(milliseconds: Long) = SystemClock.sleep(milliseconds)
                override fun cleanupOwned() = this@NativePhone.cleanupOwned()
            }
        }

        private fun requireDiagnosticCaptureOwner(expected: OwnedProcessLifecycle.Owner?) {
            check(SystemClock.elapsedRealtimeNanos() < preparationDeadline) { "DIAGNOSTIC_PREPARATION_DEADLINE_EXPIRED" }
            check(expected != null && targetOwner == expected && targetPid == expected.pid) { "DIAGNOSTIC_OWNER_CHANGED" }
            check(device.currentPackageName == TARGET && shell("pidof $TARGET").trim() == expected.pid)
            processes.requireAlive(expected, readDeadline(preparationDeadline))
            check(automation.rootInActiveWindow?.packageName?.toString() == TARGET)
            check(SystemClock.elapsedRealtimeNanos() < preparationDeadline) { "DIAGNOSTIC_PREPARATION_DEADLINE_EXPIRED" }
        }

        private fun preparationFrameMetadata(frame: TongyiPreparationRunner.Frame) = JSONObject()
            .put("width", frame.width).put("height", frame.height).put("rotation", frame.rotation)
            .put("root_bounds", JSONArray(listOf(frame.rootBounds.left, frame.rootBounds.top, frame.rootBounds.right, frame.rootBounds.bottom)))
            .put("owner", frame.owner).put("owner_encoding", "pid/uid/start_ticks/raw_commandline_base64")
            .put("menu_argb_sha256", frame.menuArgbSha256 ?: JSONObject.NULL)

        private fun preparationNodeProjection(frame: TongyiPreparationRunner.Frame): ByteArray =
            (JSONObject().put("kind", "same_returned_frame_node_value_projection_not_raw_uiautomator_xml")
                .put("frame", preparationFrameMetadata(frame))
                .put("nodes", JSONArray().apply {
                    frame.nodes.forEach { node ->
                        put(JSONObject().put("id", node.id).put("parent", node.parent ?: JSONObject.NULL)
                            .put("package_name", node.packageName).put("class_name", node.className)
                            .put("bounds", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
                            .put("visible", node.visible).put("enabled", node.enabled).put("clickable", node.clickable)
                            .put("text", node.text).put("resource_id", node.resourceId).put("hint", node.hint ?: JSONObject.NULL)
                            .put("showing_hint", node.showingHint).put("editable", node.editable).put("focused", node.focused)
                            .put("set_text_available", node.setTextAvailable).put("description", node.description))
                    }
                }).toString(2) + "\n").toByteArray(Charsets.UTF_8)

        private fun writeNewPreparationDiagnostic(name: String, cached: ByteArray): JSONObject {
            val file = File(root, name)
            check(file.createNewFile()) { "REJECTION_RETENTION_ALREADY_EXISTS_NO_RETRY" }
            FileOutputStream(file).use { output -> output.write(cached); output.fd.sync() }
            val actual = file.readBytes()
            check(actual.contentEquals(cached)) { "REJECTION_RETENTION_READBACK_MISMATCH" }
            return JSONObject().put("name", name).put("bytes", actual.size).put("sha256", sha(actual))
                .put("cached_bytes_sha256", sha(cached)).put("readback_equals_cached_bytes", true)
        }

        /** Runs only after preparation runOnce returns; uses pinned memory, never live UI or ownership. */
        fun persistRejectedPreparationObservation(): String? {
            check(preparationOnly)
            if (preparationRejectionPersistenceAttempted) return null
            preparationRejectionPersistenceAttempted = true
            val reason = preparationRejectionReason
            if (reason == null && preparationRejectionPinFailure == null) return null
            val bundle = if (reason != null) preparationObservationBundle else null
            val files = JSONArray()
            val report = JSONObject().put("kind", "private_preparation_rejected_frame_diagnostic")
                .put("reason", reason ?: JSONObject.NULL).put("pin_failure", preparationRejectionPinFailure ?: JSONObject.NULL)
                .put("claim", "sequential_diagnostic_only_not_atomic_not_selector_or_qualification_source")
                .put("clock_domain", "Android_elapsedRealtimeNanos_raw_operational_only")
                .put("files", files)
            var projectionRetained = false
            var pngRetained = false
            if (bundle != null) {
                report.put("frame", preparationFrameMetadata(bundle.frame))
                    .put("sample_order", "immutable_node_values_then_optional_public_screenshot_then_returned_frame_then_rejection_pin_then_cleanup_then_persistence")
                    .put("node_values_observed_nanos", bundle.nodeValuesObservedNanos.toString())
                    .put("png_started_nanos", bundle.pngStartedNanos?.toString() ?: JSONObject.NULL)
                    .put("png_completed_nanos", bundle.pngCompletedNanos?.toString() ?: JSONObject.NULL)
                    .put("png_source", bundle.pngSource).put("png_capture_state", bundle.pngCaptureState)
                    .put("png_capture_failure", bundle.pngCaptureFailure ?: JSONObject.NULL)
                    .put("diagnostic_png_width", bundle.pngWidth ?: JSONObject.NULL)
                    .put("diagnostic_png_height", bundle.pngHeight ?: JSONObject.NULL)
                    .put("capture_budget", "original_preparation_deadline_unchanged_synchronous_api_not_hard_timeout")
                    .put("node_projection_failure", bundle.nodeProjectionFailure ?: JSONObject.NULL)
                bundle.nodeProjection?.let { bytes ->
                    val saved = runCatching { writeNewPreparationDiagnostic("preparation-rejected-node-projection.json", bytes) }
                    saved.getOrNull()?.let { files.put(it); projectionRetained = true }
                    saved.exceptionOrNull()?.let { report.put("node_projection_persistence_failure", it.javaClass.simpleName) }
                }
                bundle.diagnosticPng?.let { bytes ->
                    val saved = runCatching { writeNewPreparationDiagnostic("preparation-rejected-diagnostic.png", bytes) }
                    saved.getOrNull()?.let { files.put(it); pngRetained = true }
                    saved.exceptionOrNull()?.let { report.put("png_persistence_failure", it.javaClass.simpleName) }
                }
            }
            report.put("node_projection_persistence_state", if (projectionRetained) "RETAINED" else "NOT_RETAINED")
                .put("png_persistence_state", if (pngRetained) "RETAINED" else "NOT_RETAINED")
                .put("retention_state", if (projectionRetained && pngRetained) "RETAINED" else if (projectionRetained || pngRetained) "PARTIAL" else "NOT_RETAINED")
            val seal = runCatching { writeNewPreparationDiagnostic("preparation-rejection-retention.json",
                (report.toString(2) + "\n").toByteArray(Charsets.UTF_8)) }
            seal.getOrNull()?.let { report.put("report_file", it).put("report_persistence_state", "RETAINED") }
            seal.exceptionOrNull()?.let { report.put("report_persistence_state", "NOT_RETAINED")
                .put("retention_state", "PARTIAL_REPORT_NOT_RETAINED").put("report_persistence_failure", it.javaClass.simpleName) }
            return report.toString()
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
