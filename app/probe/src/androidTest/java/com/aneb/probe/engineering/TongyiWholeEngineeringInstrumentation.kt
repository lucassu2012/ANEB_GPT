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
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.aneb.probe.research.engineering.OwnedProcessLifecycle
import com.aneb.probe.research.engineering.EngineeringTarget
import com.aneb.probe.research.engineering.DeepSeekUiAdapter
import com.aneb.probe.research.engineering.DeepSeekDraftRoleQualification
import com.aneb.probe.research.engineering.DeepSeekPublicUiObservation
import com.aneb.probe.research.engineering.DeepSeekModeOperationDiagnostic
import com.aneb.probe.research.engineering.OwnedShellScriptTransport
import com.aneb.probe.research.engineering.PublicUiIdleTimeoutScope
import com.aneb.probe.research.engineering.QualificationOwnerProbeScope
import com.aneb.probe.research.engineering.QualificationOwnerShellTransport
import com.aneb.probe.research.engineering.TongyiEngineeringRunner
import com.aneb.probe.research.engineering.TongyiPreparationRunner
import com.aneb.probe.research.engineering.TongyiProjectionCaptureService
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
        val target = EngineeringTarget.fromId(args.getString("target_profile") ?: "tongyi")
        check(args.getString("target_package")?.let { it == target.packageName } != false) { "TARGET_PROFILE_PACKAGE_MISMATCH" }
        check(target == EngineeringTarget.TONGYI || mode == "COLLECTION") { "TARGET_MODE_UNSUPPORTED" }
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
        val backend = NativePhone(root, condition, ui, preparationOnly, qualificationOnly, sourceInspectionOnly, target)
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
            if (qualificationOnly) backend.qualificationTargetCurrentDiagnostic()?.let {
                terminal.put("target_current_rejection_diagnostic", it)
            }
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
            if (qualificationOnly) runCatching {
                backend.takeQualificationControlFailure()?.let { observation ->
                    val diagnostic = JSONObject().put("diagnostic_only", true).put("reference_accepted", false)
                        .put("formal_new_slots", 0).put("now_nanos", observation.nowNanos.toString())
                        .put("preparation_deadline_nanos", observation.preparationDeadlineNanos.toString())
                        .put("local_deadline_nanos", observation.localDeadlineNanos.toString())
                        .put("cancel_observed", observation.cancelObserved ?: JSONObject.NULL)
                        .put("core_retained_stages", JSONArray(observation.retainedStages.map { it.name }))
                        .put("original_stack", observation.originalStack)
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "ANEB807 operational diagnostic only; not accepted evidence/reference/new slots\n" +
                            diagnostic.toString() + "\n")
                    })
                }
            }
            return
        }
        val result = if (preparationOnly) TongyiPreparationRunner(backend.preparationAndroid(), SystemClock::elapsedRealtimeNanos)
            .runOnce(runId, root) else TongyiEngineeringRunner(backend, SystemClock::elapsedRealtimeNanos, target).runOnce(runId, condition, root)
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
            .put("target_profile", target.id).put("target_package", target.packageName)
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
        runCatching { backend.persistOriginalPreparationFailure() }
    }

    /** Compile-only access to the real ordinary adapter; no preparation or capability substitution. */
    internal fun ordinaryPhoneForPreparationTracer(root: File, condition: String, ui: JSONObject,
        target: EngineeringTarget = EngineeringTarget.TONGYI): TongyiEngineeringRunner.Phone =
        NativePhone(root, condition, ui, preparationOnly = false, qualificationOnly = false, sourceInspectionOnly = false, target = target)

    /** Separate limited entry: never exposes Phone, accepts a SEND selector, or invokes COLLECTION. */
    internal fun runDeepSeekDraftRoleQualificationOnce(runId: String, root: File, ui: JSONObject): DeepSeekDraftRoleQualification.Result {
        check(sha(TongyiEngineeringRunner.PAYLOAD.toByteArray(Charsets.UTF_8)) == PAYLOAD_SHA)
        check(ui.getString("binding_scope") == "current_owned_deepseek_ui" &&
            listOf("capture_backend", "deepseek_contract", "send", "send_role_binding", "menu_argb_sha256").none(ui::has)) {
            "DEEPSEEK_NO_SEND_QUALIFICATION_CONTRACT_REQUIRED"
        }
        val contract = ui.getJSONObject("deepseek_qualification")
        check(contract.length() == 1 && contract.getString("visible_modes") == "thinking_off_search_off" &&
            ui.getBoolean("new_session_text_only_attachment_contract") && ui.getJSONArray("attachment_resource_ids").length() == 0) {
            "DEEPSEEK_NO_SEND_VISIBLE_TEXT_CONTRACT_REQUIRED"
        }
        val backend = NativePhone(root, "DEEPSEEK_DRAFT_ROLE_QUALIFICATION", ui, target = EngineeringTarget.DEEPSEEK)
        val result = DeepSeekDraftRoleQualification(backend.deepSeekDraftQualificationAndroid(), SystemClock::elapsedRealtimeNanos)
            .runOnce(runId, root)
        try { backend.sealDeepSeekDraftQualification(result) }
        catch (retention: Exception) {
            throw IllegalStateException("DEEPSEEK_QUALIFICATION_REPORT_NOT_RETAINED_original_failure=${result.failure ?: "<NONE>"}", retention)
        }
        return result
    }

    /** Current public APIs only; no UI contract, payload, preparation or action capability. */
    internal fun runDeepSeekPublicUiObservationOnce(runId: String, root: File): DeepSeekPublicUiObservation.Result {
        val backend = NativePhone(root, "DEEPSEEK_PUBLIC_UI_OBSERVATION", JSONObject(), target = EngineeringTarget.DEEPSEEK)
        val result = DeepSeekPublicUiObservation(backend.publicUiObservationAndroid(), SystemClock::elapsedRealtimeNanos)
            .runOnce(runId, root)
        try { backend.sealPublicUiObservation(result) }
        catch (retention: Exception) {
            throw IllegalStateException("DEEPSEEK_PUBLIC_UI_REPORT_NOT_RETAINED_original_failure=${result.failure ?: "<NONE>"}", retention)
        }
        return result
    }

    /** Separate explicitly selected diagnostic; never constructs a collection contract. */
    internal fun runDeepSeekModeFeedbackOnce(runId: String, root: File): DeepSeekModeOperationDiagnostic.Result {
        val backend = NativePhone(root, "DEEPSEEK_SAME_MODE_FEEDBACK", JSONObject(), target = EngineeringTarget.DEEPSEEK)
        val result = DeepSeekModeOperationDiagnostic(backend.modeFeedbackAndroid(), SystemClock::elapsedRealtimeNanos)
            .runOnce(runId, root)
        try { backend.sealModeFeedback(result) } // Buffers persisted only after shared owned cleanup.
        catch (retention: Exception) {
            throw IllegalStateException("MODE_FEEDBACK_REPORT_NOT_RETAINED_original_failure=${(result.gate?.failure ?: result.failure)?.message ?: "<NONE>"}", retention)
        }
        return result
    }

    private class NativePhone(
        private val root: File,
        private val condition: String,
        private val ui: JSONObject,
        private val preparationOnly: Boolean = false,
        private val qualificationOnly: Boolean = false,
        private val sourceInspectionOnly: Boolean = false,
        private val target: EngineeringTarget = EngineeringTarget.TONGYI,
    ) : TongyiEngineeringRunner.Phone {
        private val TARGET = target.packageName
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val automation = instrumentation.uiAutomation
        private val device = UiDevice.getInstance(instrumentation)
        private val context = instrumentation.targetContext
        private var targetPid: String? = null
        private var targetTicks: String? = null
        private var targetOwner: OwnedProcessLifecycle.Owner? = null
        private var freshEmpty = false
        private var ordinaryOwnDraft: AccessibilityNodeInfo? = null
        private var ordinaryOwnDraftParent: AccessibilityNodeInfo? = null
        private var ordinaryOwnDraftWindow = -1
        private var ordinaryOwnDraftOwner: OwnedProcessLifecycle.Owner? = null
        private var draftQualificationActive = false
        private var draftQualificationCachedNodes: ByteArray? = null
        private var draftQualificationSelectionAdvertised: Boolean? = null
        private var draftQualificationSelectionReturned: Boolean? = null
        private var draftQualificationSelectionStart: Int? = null
        private var draftQualificationSelectionEnd: Int? = null
        private var draftQualificationFullSelectionConfirmed: Boolean? = null
        private var draftQualificationEmptyConfirmed: Boolean? = null
        private var modeFeedbackActive = false
        private data class ModeFeedbackSample(val label: String, val source: JSONObject, val png: ByteArray?)
        private val modeFeedbackSamples = ArrayList<ModeFeedbackSample>(2)
        private var modeFeedbackClickReturned: Boolean? = null
        private var modeFeedbackClickFailure: String? = null
        private data class OrdinaryGraphicsBinding(
            val owner: OwnedProcessLifecycle.Owner, val windowId: Int, val width: Int, val height: Int,
            val rotation: Int, val rootBounds: TongyiPreparationRunner.Bounds, val windowBounds: TongyiPreparationRunner.Bounds,
        )
        private data class OrdinaryGraphicsReadiness(val binding: OrdinaryGraphicsBinding, val until: Long)
        private data class OrdinaryGraphicsRecord(val refreshPeriodNanos: Long, val frames: List<List<Long>>)
        private data class OrdinaryMenuObservation(val frame: TongyiPreparationRunner.Frame, val canonicalArgb: ByteArray)
        private var ordinaryMenuObservation: OrdinaryMenuObservation? = null
        private var ordinaryMenuRejectionReason: String? = null
        private var ordinaryMenuPersistenceAttempted = false
        private var originalPreparationFailure: Exception? = null
        private var ordinaryPreInputPreparation = false
        private var ordinaryPidRejectionIdentityAttempted = false
        private var capture: ParcelFileDescriptor? = null
        private var recorderOwner: OwnedProcessLifecycle.Owner? = null
        private var captureReader: Thread? = null
        private val captureReaderFailure = AtomicReference<Exception?>(null)
        private var captureReaderEof = false
        private var recordingClosureUnknown = false
        private var projectionSession: TongyiProjectionCaptureService.Session? = null
        private var instrumentationOwnershipWritten = false
        private data class CleanupOriginalFailure(
            val original: Throwable, val phase: String, val keyRequestIndex: Int?, val expectedRemainingLength: Int?,
            val targetClosureDeadlineNanos: Long?, val lastDeadlineCheckNanos: Long?,
        )
        private var recordingCleanupFailure: CleanupOriginalFailure? = null
        private var targetCleanupFailure: CleanupOriginalFailure? = null
        private var cleanupPhase = "target_not_entered"
        private var cleanupKeyRequestIndex: Int? = null
        private var cleanupExpectedRemainingLength: Int? = null
        private var cleanupLastDeadlineCheckNanos: Long? = null
        private var cleanupFailurePersistenceAttempted = false
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
        private var qualificationTargetCurrentRejectionRecord: JSONObject? = null
        private var qualificationControlFailure: TongyiUiReferenceQualificationRunner.GateFailureObservation? = null
        private val scriptTransport = OwnedShellScriptTransport(root)
        private val processes = OwnedProcessLifecycle(object : OwnedProcessLifecycle.Shell {
            override fun execute(command: String, deadlineNanos: Long) = processShell(command, deadlineNanos)
            override fun sleepMillis(milliseconds: Long) = SystemClock.sleep(milliseconds)
        }, SystemClock::elapsedRealtimeNanos, target)
        private val clearPackageIdleScope = PublicUiIdleTimeoutScope(
            readTimeoutMillis = { Configurator.getInstance().getWaitForIdleTimeout() },
            writeTimeoutMillis = { value ->
                Configurator.getInstance().setWaitForIdleTimeout(value)
                Unit
            },
        )
        val bootId: String? = runCatching { shell("cat /proc/sys/kernel/random/boot_id").trim() }.getOrNull()
        private val targetUid = context.packageManager.getApplicationInfo(TARGET, 0).uid

        private fun launchOwned(deadlineNanos: Long) {
            preparationDeadline = deadlineNanos
            persistInstrumentationOwnership()
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

        private fun persistInstrumentationOwnership() {
            if (instrumentationOwnershipWritten) return
            writeJson("OWNED_INSTRUMENTATION.json", JSONObject().put("package", context.packageName)
                .put("pid", Process.myPid().toString()).put("uid", Process.myUid())
                .put("start_ticks", processTicks(Process.myPid().toString())).put("run_id", root.name))
            instrumentationOwnershipWritten = true
        }

        private fun prepareProjectionCapture(deadlineNanos: Long) {
            check(!preparationOnly && !qualificationOnly && !sourceInspectionOnly) { "PROJECTION_ORDINARY_MODE_REQUIRED" }
            check(ui.get("capture_backend") == "android_media_projection_v1") { "UNKNOWN_CAPTURE_BACKEND" }
            preparationDeadline = deadlineNanos
            persistInstrumentationOwnership() // Root can attribute/close this process even if consent fails.
            check(device.currentPackageName == "com.huawei.android.launcher") { "LAUNCHER_NOT_CURRENT" }
            val session = TongyiProjectionCaptureService.createSession(context, root, preparationDeadline)
            projectionSession = session
            writeJson("PROJECTION_CONSENT_PENDING.json", JSONObject().put("backend", "android_media_projection_v1")
                .put("run_id", root.name).put("session_id", session.id)
                .put("preparation_deadline_nanos", preparationDeadline.toString()))
            session.launchConsent() // Normal OS dialog only; Source never approves it.
            waitUntil(preparationDeadline) {
                check(SystemClock.elapsedRealtimeNanos() < preparationDeadline) { "PROJECTION_ORIGINAL_DEADLINE_EXPIRED" }
                session.requireHealthy()
                session.grantReady && session.activityClosed
            }
            val foreground = device.currentPackageName
            check(foreground == context.packageName || foreground == "com.huawei.android.launcher") {
                "PROJECTION_SETUP_FOREGROUND_NOT_OWNED"
            }
            if (foreground == context.packageName) check(device.pressHome()) { "PROJECTION_OWN_SETUP_HOME_REFUSED" }
            waitUntil(preparationDeadline) {
                check(SystemClock.elapsedRealtimeNanos() < preparationDeadline) { "PROJECTION_ORIGINAL_DEADLINE_EXPIRED" }
                session.requireHealthy()
                val current = device.currentPackageName
                check(current == context.packageName || current == "com.huawei.android.launcher") {
                    "PROJECTION_SETUP_FOREGROUND_NOT_OWNED"
                }
                current == "com.huawei.android.launcher"
            }
        }

        override fun prepare(deadlineNanos: Long): TongyiEngineeringRunner.Snapshot {
            ordinaryPreInputPreparation = !preparationOnly && !qualificationOnly && !sourceInspectionOnly
            try {
                if (target == EngineeringTarget.DEEPSEEK) return prepareDeepSeek(deadlineNanos)
                check(ui.getString("binding_scope") == "root_current_807_ui_frame") { "CURRENT_FRAME_CONTRACT_REQUIRED" }
                check(ui.getString("menu_argb_sha256").lowercase() == TongyiPreparationRunner.MENU_SHA256) { "FIXED_MENU_PIN_REQUIRED" }
                check(ui.getJSONObject("menu").length() == 0 && ui.getJSONObject("focus_hint").length() == 0) {
                    "CURRENT_FRAME_UNBOUND_ROLE_CONTRACT_REQUIRED"
                }
                fun current(frame: TongyiPreparationRunner.Frame) = frame.nodes.filter { it.visible && it.packageName == TARGET }
                fun inside(bounds: TongyiPreparationRunner.Bounds, container: TongyiPreparationRunner.Bounds) =
                    bounds.left >= container.left && bounds.top >= container.top && bounds.right <= container.right &&
                        bounds.bottom <= container.bottom && bounds.right > bounds.left && bounds.bottom > bounds.top
                fun directParent(frame: TongyiPreparationRunner.Frame, text: String, resource: String?, className: String): TongyiPreparationRunner.Node {
                    val visible = current(frame)
                    val witness = visible.singleOrNull { it.text == text && it.className == "android.widget.TextView" &&
                        (resource == null || it.resourceId == resource) } ?: error("CURRENT_SEMANTIC_WITNESS_NOT_UNIQUE")
                    val parent = visible.singleOrNull { it.id == witness.parent && it.className == className && it.enabled && it.clickable }
                        ?: error("EXACT_DIRECT_PARENT_REQUIRED")
                    check(witness.enabled && inside(witness.bounds, parent.bounds) && inside(parent.bounds, frame.rootBounds)) {
                        "CURRENT_DIRECT_PARENT_BOUNDS_INVALID"
                    }
                    return parent
                }
                fun menu(frame: TongyiPreparationRunner.Frame): TongyiPreparationRunner.Node {
                    val visible = current(frame)
                    check(visible.count { it.text == "快速" } == 1 && visible.count { it.text == "发消息或按住说话..." } == 1 &&
                        visible.none { it.editable } && frame.menuArgbSha256 == TongyiPreparationRunner.MENU_SHA256) {
                        "CURRENT_MENU_PIXELS_MISMATCH"
                    }
                    val target = visible.singleOrNull { it.className == "android.widget.FrameLayout" &&
                        it.bounds == TongyiPreparationRunner.Bounds(56,193,156,293) && it.enabled && it.clickable &&
                        it.text.isEmpty() && it.description.isEmpty() && it.resourceId.isEmpty() && it.hint.isNullOrEmpty() }
                        ?: error("CURRENT_IMAGE_NODE_NOT_UNIQUE")
                    val wrapper = visible.singleOrNull { it.id == target.parent && it.className == "android.widget.LinearLayout" &&
                        it.enabled && !it.clickable && it.bounds == target.bounds } ?: error("CURRENT_MENU_PARENT_MISMATCH")
                    val toolbar = visible.singleOrNull { it.id == wrapper.parent && it.className == "android.widget.FrameLayout" &&
                        it.enabled && it.bounds == TongyiPreparationRunner.Bounds(0,154,1200,333) } ?: error("CURRENT_TOOLBAR_MISMATCH")
                    check(visible.filter { it.parent == toolbar.id }.map { it.className } == List(3) { "android.widget.LinearLayout" } &&
                        inside(target.bounds, frame.rootBounds) && inside(toolbar.bounds, frame.rootBounds)) { "CURRENT_TOOLBAR_STRUCTURE_MISMATCH" }
                    return target
                }
                fun clickBound(menuPixels: Boolean = false, select: (TongyiPreparationRunner.Frame) -> TongyiPreparationRunner.Node) {
                    if (menuPixels) ordinaryMenuObservation = null
                    val graphics = if (menuPixels) awaitOrdinaryHomeGraphics(minOf(preparationDeadline,
                        SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.READY_NANOS)) else null
                    try {
                        action {
                            val (observed, _) = ordinaryPreparationFrame(menuPixels, graphics)
                            val target = select(observed)
                            val (fresh, handles) = ordinaryPreparationFrame()
                            check(fresh == observed.copy(menuArgbSha256 = null)) { "CURRENT_BINDING_FRAME_CHANGED" }
                            val actual = handles[target.id.toInt()]
                            check(actual.refresh() && preparationNodeValues(handles) == fresh.nodes) { "CURRENT_NODE_CHANGED" }
                            requireOwnedCurrent()
                            check(actual.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "PUBLIC_ACTION_REFUSED" }
                        }
                        if (menuPixels) ordinaryMenuObservation = null
                    } catch (failure: Exception) {
                        if (menuPixels && ordinaryMenuObservation != null) ordinaryMenuRejectionReason = failure.message ?: failure.javaClass.simpleName
                        throw failure
                    }
                }
                if (ui.has("capture_backend")) prepareProjectionCapture(deadlineNanos)
                launchOwned(deadlineNanos)
                // Only inspect the declared visible empty-state witnesses, not old chat content.
                requireVisible("initial_empty")
                val existing = editNodeOrNull()
                check(existing == null || currentEmpty(existing)) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
                requireVisible("ordinary_mode")
                clickBound(menuPixels = true, select = ::menu)
                waitUntil(minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.READY_NANOS)) {
                    matching("new_session").size == 1
                }
                check(ui.getJSONObject("new_session").getString("resource_id") == "com.aliyun.tongyi:id/layout_new_session_float") {
                    "CURRENT_NEW_SESSION_PARENT_CONTRACT_REQUIRED"
                }
                clickBound { frame ->
                    directParent(frame, "新建对话", "com.aliyun.tongyi:id/tv_new_session", "android.widget.LinearLayout").also {
                        check(it.resourceId == "com.aliyun.tongyi:id/layout_new_session_float") { "CURRENT_NEW_SESSION_PARENT_REQUIRED" }
                    }
                }
                waitUntil(minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.READY_NANOS)) {
                    matching("new_empty").size == 1
                }
                requireVisible("new_empty")
                requireVisible("ordinary_mode")
                freshEmpty = true
                saveOriginals("new-empty")
                clickBound { frame -> directParent(frame, "发消息或按住说话...", null, "android.view.ViewGroup") }
                waitUntil(minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.INPUT_NANOS)) {
                    editNodeOrNull()?.isFocused == true
                }
                check(currentEmpty(editNode())) { "NEW_SESSION_DRAFT_NOT_EMPTY" }
                ordinaryPreInputPreparation = false // The public snapshot guard is outside this diagnostic scope.
                return snapshot()
            } catch (failure: Exception) {
                originalPreparationFailure = failure // Memory only: preserve this same exception and its original stack.
                throw failure
            } finally {
                ordinaryPreInputPreparation = false
            }
        }

        private fun prepareDeepSeek(deadlineNanos: Long): TongyiEngineeringRunner.Snapshot {
            check(!preparationOnly && !qualificationOnly && !sourceInspectionOnly &&
                ui.getString("binding_scope") == "current_owned_deepseek_ui") { "DEEPSEEK_CURRENT_FRAME_CONTRACT_REQUIRED" }
            val contract = ui.getJSONObject("deepseek_contract")
            check(contract.length() == 3 && contract.getString("visible_modes") == "thinking_off_search_off" &&
                contract.get("invocation_authorized") is Boolean &&
                contract.getString("send_description").let { it.isNotBlank() && it.length <= 128 } &&
                !ui.has("send_role_binding") && !ui.has("menu_argb_sha256") && !ui.has("send")) { "DEEPSEEK_TARGET_CONTRACT_REQUIRED" }
            check(ui.get("capture_backend") == "android_media_projection_v1") { "DEEPSEEK_EXPLICIT_PROJECTION_BACKEND_REQUIRED" }
            prepareProjectionCapture(deadlineNanos)
            launchOwned(deadlineNanos)
            deepSeekAdapter().prepare()
            freshEmpty = true
            saveOriginals("new-empty")
            ordinaryPreInputPreparation = false
            return snapshot()
        }

        fun publicUiObservationAndroid(): DeepSeekPublicUiObservation.Android {
            check(target == EngineeringTarget.DEEPSEEK && !preparationOnly && !qualificationOnly && !sourceInspectionOnly)
            return object : DeepSeekPublicUiObservation.Android {
                override fun launchOwned(deadlineNanos: Long) = this@NativePhone.launchOwned(deadlineNanos)
                override fun readCurrent(): ByteArray {
                    val (frame, handles) = deepSeekFrame() // One intentional Frame, using the existing walker.
                    check(frame.nodes.all { it.packageName == TARGET && it.windowId == frame.windowId }) {
                        "DEEPSEEK_CURRENT_WINDOW_REQUIRED"
                    }
                    return currentPublicNodeBytes(frame, handles, intentionalObservation = true)
                }
                override fun cleanupOwned() = this@NativePhone.cleanupOwned()
            }
        }

        fun modeFeedbackAndroid(): DeepSeekModeOperationDiagnostic.Android {
            check(target == EngineeringTarget.DEEPSEEK && !preparationOnly && !qualificationOnly && !sourceInspectionOnly)
            return object : DeepSeekModeOperationDiagnostic.Android {
                override fun diagnoseFirstMode(deadlineNanos: Long): DeepSeekUiAdapter.ModeGate {
                    ordinaryPreInputPreparation = true
                    modeFeedbackActive = true
                    try {
                        launchOwned(deadlineNanos)
                        return deepSeekAdapter().diagnoseFirstMode { frame, mode, until, observedAt ->
                            captureModeFeedback("P1", frame, mode, until, observedAt)
                            val sample = modeFeedbackSamples.last().source
                            check(sample.optBoolean("capture_within_original_until") && !sample.has("capture_failure")) {
                                "MODE_FEEDBACK_P1_TEMPORAL_OR_CAPTURE_UNKNOWN"
                            }
                        }
                    } finally {
                        modeFeedbackActive = false
                        ordinaryPreInputPreparation = false
                    }
                }
                override fun cleanupOwned() = this@NativePhone.cleanupOwned()
            }
        }

        /** Copied Frame values + one public image, sequential rather than atomic. No tree read. */
        private fun captureModeFeedback(label: String, frame: DeepSeekUiAdapter.Frame, mode: String,
            until: Long, observedAt: Long) {
            check(modeFeedbackActive && label in setOf("P0", "P1") &&
                modeFeedbackSamples.none { it.label == label } && modeFeedbackSamples.size < 2) {
                "MODE_FEEDBACK_SINGLE_PAIR_ONLY"
            }
            val started = SystemClock.elapsedRealtimeNanos()
            val source = JSONObject().put("label", label).put("mode", mode).put("owner", frame.owner)
                .put("source_time_meaning", if (label == "P0") "native_binding_completed_not_frame_acquisition_timestamp"
                    else "existing_successor_frame_returned_before_capture")
                .put("window_id", frame.windowId).put("root_id", frame.rootId)
                .put("display", JSONArray(listOf(frame.width, frame.height, frame.rotation)))
                .put("source_observed_elapsed_realtime_nanos", observedAt.toString())
                .put("original_until_elapsed_realtime_nanos", until.toString())
                .put("capture_started_elapsed_realtime_nanos", started.toString())
                .put("mode_nodes", JSONArray().apply {
                    val labels = frame.nodes.filter { it.visible && it.enabled && it.text in setOf("深度思考", "智能搜索") }
                    val ids = labels.map { it.id }.toSet() + labels.mapNotNull { it.parent }.toSet()
                    frame.nodes.filter { it.id in ids }.forEach { node ->
                        put(JSONObject().put("id", node.id).put("parent", node.parent ?: JSONObject.NULL)
                            .put("text", node.text).put("description", node.description).put("class", node.className)
                            .put("package", node.packageName).put("window_id", node.windowId)
                            .put("bounds", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
                            .put("visible", node.visible).put("enabled", node.enabled).put("clickable", node.clickable)
                            .put("checkable", node.checkable).put("checked", node.checked))
                    }
                }).put("sampling_claim", "copied_existing_frame_then_one_public_png_not_atomic_no_second_tree")
            var png: ByteArray? = null
            try {
                check(observedAt < until && started < until) { "MODE_FEEDBACK_CAPTURE_NOT_STARTED_WITHIN_ORIGINAL_UNTIL" }
                val screenshot = checkNotNull(automation.takeScreenshot()) { "MODE_FEEDBACK_SCREENSHOT_UNAVAILABLE" }
                val bitmap = try { checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) {
                    "MODE_FEEDBACK_SOFTWARE_BITMAP_UNAVAILABLE"
                } } finally { screenshot.recycle() }
                try {
                    source.put("png_width", bitmap.width).put("png_height", bitmap.height)
                    check(bitmap.width == frame.width && bitmap.height == frame.height) { "MODE_FEEDBACK_PNG_GEOMETRY_CHANGED" }
                    png = ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "MODE_FEEDBACK_PNG_ENCODING_FAILED" }
                        output.toByteArray()
                    }
                } finally { bitmap.recycle() }
            } catch (failure: Exception) {
                source.put("capture_failure", failure.message ?: failure.javaClass.simpleName)
                    .put("capture_original_stack", failure.stackTraceToString().take(16_384))
            } finally {
                val completed = SystemClock.elapsedRealtimeNanos()
                source.put("capture_completed_elapsed_realtime_nanos", completed.toString())
                    .put("capture_within_original_until", observedAt < until && started < until && completed < until)
                modeFeedbackSamples += ModeFeedbackSample(label, source, png)
            }
        }

        fun sealModeFeedback(result: DeepSeekModeOperationDiagnostic.Result) {
            val retained = JSONArray()
            val retentionFailures = JSONArray()
            modeFeedbackSamples.forEach { sample ->
                fun retain(name: String, bytes: ByteArray) {
                    try {
                        check(File(root, name).createNewFile()) { "MODE_FEEDBACK_ARTIFACT_ALREADY_EXISTS" }
                        FileOutputStream(File(root, name)).use { it.write(bytes); it.fd.sync() }
                        retained.put(JSONObject().put("path", name).put("bytes", bytes.size).put("sha256", sha(bytes)))
                    } catch (failure: Exception) {
                        retentionFailures.put(JSONObject().put("path", name).put("failure", failure.message ?: failure.javaClass.simpleName))
                    }
                }
                sample.png?.let { retain("mode-${sample.label}.png", it) }
                retain("mode-${sample.label}-source.json", (sample.source.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            }
            val gate = result.gate
            val pair = modeFeedbackSamples.takeIf { it.size == 2 }
            val pairBound = pair != null && pair[0].source.getString("owner") == pair[1].source.getString("owner") &&
                pair[0].source.getInt("window_id") == pair[1].source.getInt("window_id") &&
                pair.all { it.png != null && it.source.optBoolean("capture_within_original_until") && !it.source.has("capture_failure") }
            writeJson("DEEPSEEK_SAME_MODE_FEEDBACK.json", JSONObject().put("kind", "private_same_operation_mode_feedback_only")
                .put("feature_release", "deepseek-same-mode-feedback-811").put("run_id", root.name)
                .put("target_profile", target.id).put("target_package", TARGET)
                .put("state", "DIAGNOSTIC_STOP_NOT_PREPARATION_QUALIFICATION")
                .put("mode_observation", if (gate != null && gate.mode == null) "NOT_OBSERVED" else "ACTUAL_GATE_RECORDED_OR_UNKNOWN")
                .put("mode", gate?.mode ?: JSONObject.NULL)
                .put("native_operation_returned", gate?.operationReturned ?: JSONObject.NULL)
                .put("parameterless_parent_click_returned", modeFeedbackClickReturned ?: JSONObject.NULL)
                .put("parameterless_parent_click_failure", modeFeedbackClickFailure ?: JSONObject.NULL)
                .put("original_mode_gate_returned", gate?.gateReturned ?: JSONObject.NULL)
                .put("original_failure", (gate?.failure ?: result.failure)?.let { it.message ?: it.javaClass.simpleName } ?: JSONObject.NULL)
                .put("original_stack", (gate?.failure ?: result.failure)?.stackTraceToString()?.take(16_384) ?: JSONObject.NULL)
                .put("feedback_failure", gate?.feedbackFailure?.let { it.message ?: it.javaClass.simpleName } ?: JSONObject.NULL)
                .put("pair_temporally_and_owner_window_bound", pairBound)
                .put("visual_interpretation", "UNKNOWN_ROOT_MUST_VIEW_ACTUAL_FULL_IMAGES")
                .put("retained_originals", retained).put("retention_failures", retentionFailures)
                .put("owned_cleanup", result.ownedCleanup)
                .put("cleanup_failure", result.cleanupFailure?.let { it.message ?: it.javaClass.simpleName } ?: JSONObject.NULL)
                .put("preparation_deadline_elapsed_realtime_nanos", result.preparationDeadlineNanos.toString())
                .put("clock_domain", "Android_elapsedRealtimeNanos_raw_operational_only").put("boot_id", bootId ?: JSONObject.NULL)
                .put("sampling_claim", "sequential_frame_and_png_not_atomic_not_continuous_not_zero_interference")
                .put("input", "NOT_STARTED").put("focus", "NOT_STARTED").put("selection", "NOT_STARTED")
                .put("send_calls", 0).put("recording", "NOT_STARTED").put("network_transition", "NOT_STARTED")
                .put("claim_scope", "private_diagnostic_only").put("metrics", JSONObject.NULL)
                .put("formal_new_slots", 0).put("reference_accepted", false).put("rpi", "not_computed"))
        }

        fun sealPublicUiObservation(result: DeepSeekPublicUiObservation.Result) {
            val preserved = JSONArray()
            listOf("OWNED_INSTRUMENTATION.json", "OWNED_TARGET.json", "current-public-ui-nodes.json").forEach { name ->
                val file = File(root, name)
                if (file.isFile) {
                    val bytes = file.readBytes()
                    preserved.put(JSONObject().put("path", name).put("bytes", bytes.size).put("sha256", sha(bytes)))
                }
            }
            writeJson("DEEPSEEK_PUBLIC_UI_OBSERVATION.json", JSONObject().put("kind", "private_read_only_native_public_ui_observation")
                .put("run_id", root.name).put("target_profile", target.id).put("target_package", TARGET)
                .put("feature_release", "deepseek-public-ui-observation-811")
                .put("state", result.state).put("source_retained", result.sourceRetained).put("preserved_files", preserved)
                .put("failure", result.failure ?: JSONObject.NULL).put("original_stack", result.originalStack ?: JSONObject.NULL)
                .put("owned_cleanup", result.ownedCleanup)
                .put("preparation_deadline_elapsed_realtime_nanos", result.preparationDeadlineNanos?.toString() ?: JSONObject.NULL)
                .put("observation_policy", "one_intentional_public_frame_no_polling_not_a_failure_snapshot")
                .put("input", "NOT_STARTED").put("preparation", "NOT_STARTED").put("mode_actions", "NOT_STARTED")
                .put("focus", "NOT_STARTED").put("selection", "NOT_STARTED").put("send_calls", 0)
                .put("recording", "NOT_STARTED").put("network_transition", "NOT_STARTED")
                .put("qualification", "NOT_PERFORMED").put("claim_scope", "private_public_api_observation_only")
                .put("clock_domain", "Android_elapsedRealtimeNanos_raw_operational_only").put("boot_id", bootId ?: JSONObject.NULL)
                .put("metrics", JSONObject.NULL).put("formal_new_slots", 0).put("reference_accepted", false).put("rpi", "not_computed"))
        }

        fun deepSeekDraftQualificationAndroid(): DeepSeekDraftRoleQualification.Android {
            check(target == EngineeringTarget.DEEPSEEK && !preparationOnly && !qualificationOnly && !sourceInspectionOnly)
            draftQualificationActive = true // Per-instance only; never enables a COLLECTION capability.
            return object : DeepSeekDraftRoleQualification.Android {
                override fun prepareEmpty(deadlineNanos: Long) {
                    ordinaryPreInputPreparation = true
                    try {
                        launchOwned(deadlineNanos)
                        deepSeekAdapter().prepare()
                        freshEmpty = true
                    } finally { ordinaryPreInputPreparation = false }
                }
                override fun enterExactAndReadback(text: String): DeepSeekDraftRoleQualification.Readback {
                    this@NativePhone.enterExact(text) // Original SET_TEXT + fresh full same-handle/parent/window/owner assertions.
                    val actual = checkNotNull(ordinaryOwnDraft)
                    val parent = checkNotNull(ordinaryOwnDraftParent)
                    val owner = checkNotNull(targetOwner)
                    // Copy that successful original readback object; no refresh/tree/owner/UI acquisition.
                    return DeepSeekDraftRoleQualification.Readback(actual.packageName?.toString().orEmpty(),
                        "${owner.pid}/${owner.uid}/${owner.startTicks}", actual.windowId, parent.windowId,
                        ordinaryOwnDraftOwner == owner && actual.windowId == ordinaryOwnDraftWindow,
                        actual.text?.toString().orEmpty(), actual.isShowingHintText, actual.isFocused)
                }
                override fun retainCurrent(label: String) {
                    check(label == "new-empty" || label == "owned-draft")
                    val (frame, handles) = deepSeekFrame()
                    check(deepSeekAdapter().modesOff(frame)) { "DEEPSEEK_VISIBLE_OFF_OFF_REQUIRED" }
                    val editor = frame.nodes.singleOrNull { it.visible && it.editable } ?: error("DEEPSEEK_EDITOR_NOT_UNIQUE")
                    check(editor.focused && editor.enabled && editor.setTextAvailable) { "DEEPSEEK_QUALIFICATION_FOCUSED_EDITOR_REQUIRED" }
                    if (label == "new-empty") check(currentEmpty(handles[editor.id.toInt()])) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
                    else {
                        val actual = handles[editor.id.toInt()]
                        val parent = handles.singleOrNull { it == ordinaryOwnDraftParent }
                        check(actual == ordinaryOwnDraft && parent != null && editor.parent?.let { handles[it.toInt()] } == parent &&
                            targetOwner == ordinaryOwnDraftOwner && frame.windowId == ordinaryOwnDraftWindow &&
                            !editor.showingHint && editor.text == TongyiEngineeringRunner.PAYLOAD) { "DEEPSEEK_QUALIFIED_DRAFT_CHANGED" }
                    }
                    writeDraftQualificationNodes("$label-nodes.json")
                    saveOriginals(label) // Existing sequential PNG/XML/owner sources, not an atomic frame.
                }
                override fun retainRejectedCached(): Boolean {
                    if (draftQualificationCachedNodes == null) return false
                    writeDraftQualificationNodes("rejected-current-nodes.json") // Cached pre-cleanup acquisition only; no resampling.
                    return true
                }
                override fun cleanupOwned() = this@NativePhone.cleanupOwned()
            }
        }

        private fun cacheDraftQualificationNodes(frame: DeepSeekUiAdapter.Frame, handles: List<AccessibilityNodeInfo>) {
            draftQualificationCachedNodes = currentPublicNodeBytes(frame, handles)
        }

        private fun currentPublicNodeBytes(frame: DeepSeekUiAdapter.Frame, handles: List<AccessibilityNodeInfo>,
            intentionalObservation: Boolean = false): ByteArray {
            val nodes = JSONArray()
            frame.nodes.forEachIndexed { index, node ->
                val actual = handles[index]
                val value = JSONObject().put("id", node.id).put("parent", node.parent ?: JSONObject.NULL)
                    .put("package", node.packageName).put("class", node.className).put("window_id", node.windowId)
                    .put("bounds", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
                    .put("text", if (intentionalObservation) (actual.text?.toString() ?: JSONObject.NULL) else node.text)
                    .put("description", if (intentionalObservation) (actual.contentDescription?.toString() ?: JSONObject.NULL) else node.description)
                    .put("hint", node.hint ?: JSONObject.NULL)
                    .put("showing_hint", node.showingHint).put("visible", node.visible).put("enabled", node.enabled)
                    .put("clickable", node.clickable).put("checkable", node.checkable).put("checked", node.checked)
                    .put("editable", node.editable).put("focused", node.focused)
                    .put("action_ids", JSONArray(actual.actionList.map { it.id }))
                if (intentionalObservation) value.put("focusable", actual.isFocusable)
                    .put("frame_action_support", JSONObject().put("set_text", node.setTextAvailable)
                        .put("click", node.clickAvailable).put("focus", node.focusAvailable))
                nodes.put(value)
            }
            val source = JSONObject().put("kind", if (intentionalObservation) "private_intentional_current_public_ui_observation"
                else "private_current_public_objects_no_send_role_assignment")
                .put("owner", frame.owner).put("window_id", frame.windowId).put("root_id", frame.rootId)
                .put("display", JSONArray(listOf(frame.width, frame.height, frame.rotation)))
                .put("editor_ids", JSONArray(frame.nodes.filter { it.visible && it.editable }.map { it.id }))
                .put("described_node_ids", JSONArray(frame.nodes.filter { it.visible && it.description.isNotEmpty() }.map { it.id }))
                .put("nodes", nodes).put("sampling_claim", "sequential_public_sources_not_atomic")
            if (intentionalObservation) source.put("source_timing", "one_intentional_frame_then_raw_nullable_text_action_and_focusable_getters_not_atomic")
            else source.put("failure_time_relation", "UNKNOWN_last_existing_acquisition_not_a_fresh_failure_snapshot")
            return (source.put("role_assignment", "UNKNOWN_no_description_is_promoted_to_SEND").put("reference_accepted", false)
                .put("formal_new_slots", 0).toString(2) + "\n").toByteArray(Charsets.UTF_8)
        }

        private fun writeDraftQualificationNodes(name: String) {
            check(!File(root, name).exists()) { "QUALIFICATION_OBSERVATION_ALREADY_RETAINED" }
            FileOutputStream(File(root, name)).use {
                it.write(checkNotNull(draftQualificationCachedNodes)); it.fd.sync()
            }
        }

        fun sealDeepSeekDraftQualification(result: DeepSeekDraftRoleQualification.Result) {
            val originals = JSONArray()
            root.listFiles().orEmpty().filter { it.isFile && (it.name.startsWith("new-empty") ||
                it.name.startsWith("owned-draft") || it.name == "rejected-current-nodes.json" || it.name.startsWith("OWNED_")) }
                .sortedBy { it.name }.forEach { file ->
                    val bytes = file.readBytes()
                    originals.put(JSONObject().put("path", file.name).put("bytes", bytes.size).put("sha256", sha(bytes)))
                }
            writeJson("DEEPSEEK_DRAFT_ROLE_QUALIFICATION.json", JSONObject().put("kind", "private_no_send_draft_role_qualification")
                .put("run_id", root.name).put("target_profile", target.id).put("target_package", TARGET)
                .put("feature_release", "deepseek-no-send-qualification-811").put("payload_sha256", PAYLOAD_SHA)
                .put("preparation_deadline_elapsed_realtime_nanos", if (preparationDeadline > 0) preparationDeadline.toString() else JSONObject.NULL)
                .put("clock_domain", "Android_elapsedRealtimeNanos_raw_operational_only")
                .put("state", result.state).put("input_readback_confirmed", result.inputReadbackConfirmed)
                .put("retained_stages", JSONArray(result.retainedStages)).put("retained_originals", originals)
                .put("failure", result.failure ?: JSONObject.NULL).put("original_stack", result.originalStack ?: JSONObject.NULL)
                .put("owned_cleanup", result.ownedCleanup).put("rejected_cached_nodes_retained", result.rejectedRetained)
                .put("rejected_retention_failure", result.rejectedRetentionFailure ?: JSONObject.NULL)
                .put("selection_observation", JSONObject()
                    .put("advertised", draftQualificationSelectionAdvertised ?: JSONObject.NULL)
                    .put("original_action_returned", draftQualificationSelectionReturned ?: JSONObject.NULL)
                    .put("actual_start", draftQualificationSelectionStart ?: JSONObject.NULL)
                    .put("actual_end", draftQualificationSelectionEnd ?: JSONObject.NULL)
                    .put("full_selection_confirmed", draftQualificationFullSelectionConfirmed ?: JSONObject.NULL)
                    .put("empty_readback_confirmed", draftQualificationEmptyConfirmed ?: JSONObject.NULL))
                .put("send_calls", 0).put("recording", "NOT_STARTED").put("network_transition", "NOT_STARTED")
                .put("claim_scope", "private_method_migration_qualification_only").put("metrics", JSONObject.NULL)
                .put("formal_new_slots", 0).put("reference_accepted", false).put("rpi", "not_computed"))
        }

        /** Immutable values from this actual tree; indices are acquisition-local handles, never selectors. */
        private fun deepSeekNodeValues(handles: List<AccessibilityNodeInfo>) = handles.mapIndexed { index, node ->
            val rect = Rect().also(node::getBoundsInScreen)
            DeepSeekUiAdapter.Node(index.toString(), handles.indexOf(node.parent).takeIf { it >= 0 }?.toString(),
                TongyiPreparationRunner.Bounds(rect.left, rect.top, rect.right, rect.bottom),
                node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.hintText?.toString(),
                node.isShowingHintText, visible(node), node.isEnabled, node.isClickable, node.isCheckable, node.isChecked,
                node.isEditable, node.isFocused, node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT },
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_FOCUS }, node.windowId, node.packageName?.toString().orEmpty(),
                node.className?.toString().orEmpty())
        }

        private fun deepSeekFrame(): Pair<DeepSeekUiAdapter.Frame, List<AccessibilityNodeInfo>> {
            val (base, handles) = ordinaryPreparationFrame(deadlineNanos = preparationDeadline)
            val rootNode = handles.first()
            val window = checkNotNull(rootNode.window) { "DEEPSEEK_PUBLIC_WINDOW_REQUIRED" }
            check(rootNode.windowId >= 0 && window.isActive && window.id == rootNode.windowId && window.root == rootNode) {
                "DEEPSEEK_CURRENT_WINDOW_CHANGED"
            }
            val frame = DeepSeekUiAdapter.Frame(base.owner, rootNode.windowId, base.width, base.height, base.rotation,
                "0", deepSeekNodeValues(handles))
            if (draftQualificationActive) cacheDraftQualificationNodes(frame, handles)
            return frame to handles
        }

        private fun deepSeekAdapter() = DeepSeekUiAdapter(object : DeepSeekUiAdapter.Android {
            override fun nowNanos() = SystemClock.elapsedRealtimeNanos()
            override fun sleepMillis(milliseconds: Long) = SystemClock.sleep(milliseconds)
            override fun read() = deepSeekFrame().first
            override fun activateCheckedMode(frame: DeepSeekUiAdapter.Frame, label: DeepSeekUiAdapter.Node, control: DeepSeekUiAdapter.Node) {
                val until = minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.INPUT_NANOS)
                action {
                    val (fresh, handles) = deepSeekFrame()
                    check(fresh == frame && fresh.nodes.singleOrNull { it.id == label.id } == label &&
                        fresh.nodes.singleOrNull { it.id == control.id } == control) { "DEEPSEEK_SEMANTIC_MODE_FRAME_CHANGED" }
                    // The adapter proved this same Frame's empty bounded composer before requesting one ON-mode operation.
                    check(label.text in setOf("深度思考", "智能搜索") && label.className.isNotBlank() &&
                        label.parent == control.id && label.visible && label.enabled && !label.editable &&
                        control.visible && control.enabled && control.checkable && control.checked &&
                        control.clickable && control.clickAvailable && !control.editable) { "DEEPSEEK_CHECKED_MODE_ACTION_REQUIRED" }
                    val labels = device.findObjects(By.pkg(TARGET).text(label.text).clazz(label.className))
                    check(labels.size == 1) { "DEEPSEEK_SEMANTIC_MODE_LABEL_NOT_UNIQUE" }
                    val publicLabel = labels.single()
                    val publicControl = checkNotNull(publicLabel.parent) { "DEEPSEEK_SEMANTIC_MODE_PARENT_MISSING" }
                    fun matchesPublic(actual: UiObject2, expected: DeepSeekUiAdapter.Node): Boolean {
                        val bounds = actual.visibleBounds
                        return actual.applicationPackage == expected.packageName && actual.className == expected.className &&
                            actual.text.orEmpty() == expected.text && actual.contentDescription.orEmpty() == expected.description &&
                            actual.isEnabled == expected.enabled && bounds.left == expected.bounds.left && bounds.top == expected.bounds.top &&
                            bounds.right == expected.bounds.right && bounds.bottom == expected.bounds.bottom
                    }
                    check(matchesPublic(publicLabel, label) && matchesPublic(publicControl, control) &&
                        publicControl.isCheckable && publicControl.isChecked && publicControl.isClickable) {
                        "DEEPSEEK_SEMANTIC_MODE_PUBLIC_RELATION_CHANGED"
                    }
                    if (modeFeedbackActive) captureModeFeedback("P0", frame, label.text, until, SystemClock.elapsedRealtimeNanos())
                    val actualLabel = handles[label.id.toInt()]
                    val actualControl = handles[control.id.toInt()]
                    check(actualLabel.refresh() && actualControl.refresh() && actualLabel.parent == actualControl &&
                        deepSeekNodeValues(handles) == frame.nodes) { "DEEPSEEK_SEMANTIC_MODE_SOURCE_CHANGED" }
                    requireOwnedCurrent(until)
                    val activeRoot = checkNotNull(automation.rootInActiveWindow) { "DEEPSEEK_SEMANTIC_MODE_ROOT_MISSING" }
                    val window = checkNotNull(activeRoot.window) { "DEEPSEEK_SEMANTIC_MODE_WINDOW_MISSING" }
                    check(activeRoot == handles.first() && activeRoot.windowId == frame.windowId &&
                        window.isActive && window.id == frame.windowId && window.root == activeRoot) {
                        "DEEPSEEK_SEMANTIC_MODE_WINDOW_CHANGED"
                    }
                    check(publicControl.isEnabled && publicControl.isCheckable && publicControl.isChecked && publicControl.isClickable) {
                        "DEEPSEEK_SEMANTIC_MODE_NO_LONGER_ON"
                    }
                    check(SystemClock.elapsedRealtimeNanos() < until) { "DEEPSEEK_SEMANTIC_MODE_ACTION_EXPIRED" }
                    // One distinct semantic parent operation INSTEAD OF Accessibility CLICK; no fallback or local OFF mutation.
                    if (modeFeedbackActive) {
                        try { publicControl.click(); modeFeedbackClickReturned = true }
                        catch (failure: Exception) {
                            modeFeedbackClickReturned = false
                            modeFeedbackClickFailure = failure.message ?: failure.javaClass.simpleName
                            throw failure
                        }
                    } else publicControl.click()
                }
            }
            override fun activateEmptyComposer(frame: DeepSeekUiAdapter.Frame, hint: DeepSeekUiAdapter.Node) {
                // Starts before the original action wrapper: never grants a later five-second budget.
                val until = minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.INPUT_NANOS)
                action {
                    val (fresh, handles) = deepSeekFrame()
                    check(fresh == frame && fresh.nodes.singleOrNull { it.id == hint.id } == hint) {
                        "DEEPSEEK_SEMANTIC_ACTIVATION_FRAME_CHANGED"
                    }
                    // The adapter already proved this same Frame's empty/OFF-OFF bounded shared composer.
                    val holder = fresh.nodes.single { it.id == hint.parent }
                    check(hint.text == "发消息或按住说话" && hint.className == "android.widget.TextView" &&
                        hint.visible && hint.enabled && !hint.editable && !hint.clickAvailable && !hint.focusAvailable &&
                        holder.className == "androidx.compose.ui.viewinterop.ViewFactoryHolder" &&
                        holder.visible && holder.enabled && !holder.editable && !holder.clickAvailable && !holder.focusAvailable) {
                        "DEEPSEEK_SEMANTIC_EMPTY_HINT_REQUIRED"
                    }
                    val candidates = device.findObjects(By.pkg(TARGET).text(hint.text).clazz(hint.className))
                    check(candidates.size == 1) { "DEEPSEEK_SEMANTIC_HINT_NOT_UNIQUE" }
                    val candidate = candidates.single()
                    val parent = checkNotNull(candidate.parent) { "DEEPSEEK_SEMANTIC_HOLDER_MISSING" }
                    fun matchesPublic(actual: UiObject2, expected: DeepSeekUiAdapter.Node): Boolean {
                        val bounds = actual.visibleBounds
                        return actual.applicationPackage == expected.packageName && actual.className == expected.className &&
                            actual.text.orEmpty() == expected.text && actual.contentDescription.orEmpty() == expected.description &&
                            actual.isEnabled == expected.enabled && bounds.left == expected.bounds.left && bounds.top == expected.bounds.top &&
                            bounds.right == expected.bounds.right && bounds.bottom == expected.bounds.bottom
                    }
                    check(matchesPublic(candidate, hint) && matchesPublic(parent, holder)) {
                        "DEEPSEEK_SEMANTIC_PUBLIC_RELATION_CHANGED"
                    }
                    val actual = handles[hint.id.toInt()]
                    check(actual.refresh() && actual.parent == handles[holder.id.toInt()] && deepSeekNodeValues(handles) == frame.nodes) {
                        "DEEPSEEK_SEMANTIC_SOURCE_CHANGED"
                    }
                    requireOwnedCurrent(until)
                    val activeRoot = checkNotNull(automation.rootInActiveWindow) { "DEEPSEEK_SEMANTIC_ROOT_MISSING" }
                    val window = checkNotNull(activeRoot.window) { "DEEPSEEK_SEMANTIC_WINDOW_MISSING" }
                    check(activeRoot == handles.first() && activeRoot.windowId == frame.windowId &&
                        window.isActive && window.id == frame.windowId && window.root == activeRoot) {
                        "DEEPSEEK_SEMANTIC_WINDOW_CHANGED"
                    }
                    check(SystemClock.elapsedRealtimeNanos() < until) { "DEEPSEEK_SEMANTIC_ACTIVATION_EXPIRED" }
                    // Distinct public semantic UI operation; no Accessibility-handle alias or action_id is claimed.
                    candidate.click()
                }
            }
            override fun invoke(frame: DeepSeekUiAdapter.Frame, node: DeepSeekUiAdapter.Node, kind: DeepSeekUiAdapter.Action) {
                action {
                    val (fresh, handles) = deepSeekFrame()
                    check(fresh == frame && fresh.nodes.singleOrNull { it.id == node.id } == node) { "DEEPSEEK_ACTION_FRAME_CHANGED" }
                    val actual = handles[node.id.toInt()]
                    val actionId = when (kind) {
                        DeepSeekUiAdapter.Action.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
                        DeepSeekUiAdapter.Action.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS
                    }
                    check(actual.refresh() && deepSeekNodeValues(handles) == frame.nodes &&
                        actual.actionList.any { it.id == actionId }) { "DEEPSEEK_PUBLIC_ACTION_CHANGED" }
                    requireOwnedCurrent()
                    check(actual.performAction(actionId)) { "DEEPSEEK_PUBLIC_ACTION_REFUSED" }
                }
            }
        }, preparationDeadline)

        private fun currentDeepSeekSendRole(input: AccessibilityNodeInfo, empty: Boolean,
            acquisition: Pair<DeepSeekUiAdapter.Frame, List<AccessibilityNodeInfo>>): AccessibilityNodeInfo? {
            if (empty) return null
            val (frame, handles) = acquisition
            val owner = checkNotNull(targetOwner)
            check(ordinaryOwnDraftOwner == owner && input == ordinaryOwnDraft && input.parent == ordinaryOwnDraftParent &&
                input.windowId == ordinaryOwnDraftWindow && input.windowId == frame.windowId && input.isEnabled && input.isFocused &&
                !input.isShowingHintText && input.text?.toString() == TongyiEngineeringRunner.PAYLOAD) { "DEEPSEEK_OWNED_DRAFT_REQUIRED" }
            val editor = frame.nodes.singleOrNull { it.visible && it.editable } ?: error("DEEPSEEK_EDITOR_NOT_UNIQUE")
            check(handles[editor.id.toInt()] == input) { "DEEPSEEK_EDITOR_BINDING_CHANGED" }
            val candidate = deepSeekAdapter().sendCandidate(frame, ui.getJSONObject("deepseek_contract").getString("send_description"))
                ?: return null
            val actual = handles[candidate.id.toInt()]
            check(input.refresh() && actual.refresh() && deepSeekNodeValues(handles) == frame.nodes &&
                targetOwner == owner && input == ordinaryOwnDraft && input.parent == ordinaryOwnDraftParent &&
                input.windowId == ordinaryOwnDraftWindow) { "DEEPSEEK_SEND_BINDING_CHANGED" }
            return actual
        }

        override fun enterExact(text: String) {
            check(text == TongyiEngineeringRunner.PAYLOAD)
            val owner = checkNotNull(targetOwner)
            lateinit var input: AccessibilityNodeInfo
            lateinit var parent: AccessibilityNodeInfo
            var windowId = -1
            action {
                input = editNode()
                check(input.refresh() && input.isEnabled && input.isFocused && currentEmpty(input) &&
                    input.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) { "CURRENT_EMPTY_FOCUS_REQUIRED" }
                parent = checkNotNull(input.parent) { "CURRENT_EDITOR_PARENT_REQUIRED" }
                windowId = input.windowId
                check(windowId >= 0 && parent.windowId == windowId) { "CURRENT_EDITOR_WINDOW_CHANGED" }
                check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) {
                    "SET_TEXT_REFUSED"
                }
            }
            val readback = editNode()
            check(targetOwner == owner && readback == input && readback.parent == parent && readback.windowId == windowId &&
                readback.isEnabled && readback.isFocused && readback.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                "CURRENT_EDITOR_READBACK_BINDING_CHANGED"
            }
            check(!readback.isShowingHintText && readback.text?.toString() == text &&
                readback.text.toString().toByteArray(Charsets.UTF_8).size == 239) { "COMPLETE_239B_READBACK_MISMATCH" }
            ordinaryOwnDraft = readback
            ordinaryOwnDraftParent = parent
            ordinaryOwnDraftWindow = windowId
            ordinaryOwnDraftOwner = owner
        }

        override fun snapshot(): TongyiEngineeringRunner.Snapshot {
            projectionSession?.requireHealthy()
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
            val empty = currentEmpty(input)
            val deepSeek = if (target == EngineeringTarget.DEEPSEEK) deepSeekFrame() else null
            return TongyiEngineeringRunner.Snapshot(TARGET, true, if (deepSeek != null) deepSeekAdapter().modesOff(deepSeek.first)
                else matching("ordinary_mode").size == 1,
                noAttachments, freshEmpty && empty, if (empty) "" else input.text?.toString().orEmpty(),
                input.isFocused, if (deepSeek != null) currentDeepSeekSendRole(input, empty, deepSeek) != null
                else if (ui.has("send_role_binding")) currentPrivateSendRole(input, empty) != null
                else if (!ui.has("send")) false else matching("send").singleOrNull()?.let { it.isEnabled && it.isClickable } == true,
                actual, noVpn)
        }

        /** Read-only current-role binding; invocation separately requires explicit Boolean authorization inside action. */
        private fun currentPrivateSendRole(input: AccessibilityNodeInfo, empty: Boolean): AccessibilityNodeInfo? {
            val contract = ui.getJSONObject("send_role_binding")
            check(!ui.has("send") && ui.getString("binding_scope") == "root_current_807_ui_frame" && contract.length() == 3 &&
                contract.getString("scope") == "current_owned_239b_composer_frame" &&
                contract.getString("role") == "unique_at_then_two_blank_actions_rightmost" &&
                contract.get("invocation_authorized") is Boolean) { "CURRENT_PRIVATE_SEND_ROLE_CONTRACT_REQUIRED" }
            if (empty) return null
            val owner = checkNotNull(targetOwner)
            val handles = nodes() // One fresh ordinary full-owner/current tree; no polling or screenshot.
            val values = preparationNodeValues(handles)
            val projected = handles.zip(values).toMap()
            fun value(node: AccessibilityNodeInfo) = checkNotNull(projected[node]) { "CURRENT_PRIVATE_SEND_NODE_OUTSIDE_TREE" }
            val current = handles.filter { value(it).visible && value(it).packageName == TARGET }
            fun children(node: AccessibilityNodeInfo) = handles.filter { it.parent == node }
            fun inside(child: AccessibilityNodeInfo, parent: AccessibilityNodeInfo): Boolean {
                val a = value(child).bounds
                val b = value(parent).bounds
                return a.right > a.left && a.bottom > a.top && a.left >= b.left && a.top >= b.top &&
                    a.right <= b.right && a.bottom <= b.bottom
            }
            fun blank(node: AccessibilityNodeInfo) = value(node).let {
                it.text.isEmpty() && it.description.isEmpty() && it.resourceId.isEmpty() && it.hint.isNullOrEmpty() && !it.showingHint
            }
            val editor = current.singleOrNull { value(it).editable && value(it).className == "android.widget.EditText" }
                ?: error("CURRENT_PRIVATE_SEND_EDITOR_NOT_UNIQUE")
            val windowId = editor.windowId
            check(windowId >= 0 && owner == ordinaryOwnDraftOwner && editor == input && editor == ordinaryOwnDraft &&
                editor.parent == ordinaryOwnDraftParent && windowId == ordinaryOwnDraftWindow &&
                value(editor).enabled && value(editor).focused && value(editor).setTextAvailable && !value(editor).showingHint &&
                value(editor).text == TongyiEngineeringRunner.PAYLOAD && value(editor).text.toByteArray(Charsets.UTF_8).size == 239) {
                "CURRENT_PRIVATE_SEND_OWNED_DRAFT_REQUIRED"
            }
            val rootNode = handles.first()
            val window = checkNotNull(rootNode.window) { "CURRENT_PRIVATE_SEND_WINDOW_REQUIRED" }
            check(rootNode.windowId == windowId && window.isActive && window.id == windowId && window.root == rootNode) {
                "CURRENT_PRIVATE_SEND_WINDOW_CHANGED"
            }
            val ancestors = ArrayList<AccessibilityNodeInfo>()
            var ancestor = editor.parent
            var child = editor
            while (ancestor != null) {
                check(ancestors.size < handles.size && ancestor !in ancestors && ancestor in current &&
                    ancestor.windowId == windowId && inside(child, ancestor)) { "CURRENT_PRIVATE_SEND_ANCESTRY_CHANGED" }
                ancestors.add(ancestor)
                child = ancestor
                ancestor = ancestor.parent
            }
            check(ancestors.lastOrNull() == rootNode) { "CURRENT_PRIVATE_SEND_ROOT_CHANGED" }
            val anchor = current.singleOrNull { value(it).description == "@" } ?: error("CURRENT_PRIVATE_SEND_AT_NOT_UNIQUE")
            val row = checkNotNull(anchor.parent) { "CURRENT_PRIVATE_SEND_ROW_REQUIRED" }
            val actionArea = checkNotNull(row.parent) { "CURRENT_PRIVATE_SEND_ACTION_AREA_REQUIRED" }
            val composer = checkNotNull(actionArea.parent) { "CURRENT_PRIVATE_SEND_COMPOSER_REQUIRED" }
            check(row in current && actionArea in current && composer in ancestors &&
                value(row).className == "android.widget.LinearLayout" &&
                value(actionArea).className == "android.view.ViewGroup" && value(composer).className == "android.view.ViewGroup" &&
                value(row).enabled && value(actionArea).enabled && value(composer).enabled &&
                row.windowId == windowId && actionArea.windowId == windowId && composer.windowId == windowId &&
                inside(row, actionArea) && inside(actionArea, composer)) { "CURRENT_PRIVATE_SEND_COMPOSER_RELATION_REQUIRED" }
            val actions = children(row)
            check(actions.size == 3 && actions.distinct().size == 3 && actions.all {
                it in current && value(it).className == "android.widget.FrameLayout" && value(it).enabled && value(it).clickable &&
                    !value(it).editable && it.windowId == windowId && inside(it, row)
            }) { "CURRENT_PRIVATE_SEND_ACTIONS_NOT_UNIQUE" }
            check(anchor in actions && value(anchor).text.isEmpty() && value(anchor).resourceId.isEmpty() &&
                value(anchor).hint.isNullOrEmpty() && !value(anchor).showingHint) { "CURRENT_PRIVATE_SEND_AT_ACTION_REQUIRED" }
            val blanks = actions.filter { it != anchor && blank(it) }
            check(blanks.size == 2) { "CURRENT_PRIVATE_SEND_BLANK_ACTIONS_REQUIRED" }
            val images = actions.map { action ->
                val image = children(action).singleOrNull() ?: error("CURRENT_PRIVATE_SEND_SINGLE_IMAGE_REQUIRED")
                check(image in current && value(image).className == "android.widget.ImageView" && value(image).enabled && !value(image).clickable &&
                    !value(image).editable && blank(image) && image.windowId == windowId && inside(image, action)) {
                    "CURRENT_PRIVATE_SEND_IMAGE_CHANGED"
                }
                image
            }
            check(images.distinct().size == 3) { "CURRENT_PRIVATE_SEND_IMAGES_NOT_DISTINCT" }
            val candidate = blanks.singleOrNull { right -> blanks.all { it == right || value(it).bounds.right <= value(right).bounds.left } }
                ?: error("CURRENT_PRIVATE_SEND_RIGHT_ACTION_NOT_UNIQUE")
            val middle = blanks.single { it != candidate }
            check(value(anchor).bounds.right <= value(middle).bounds.left &&
                actions.all { value(it).bounds.top == value(anchor).bounds.top && value(it).bounds.bottom == value(anchor).bounds.bottom }) {
                "CURRENT_PRIVATE_SEND_ACTION_ORDER_CHANGED"
            }
            check(editor.refresh() && candidate.refresh() && preparationNodeValues(handles) == values && targetOwner == owner &&
                editor == ordinaryOwnDraft && editor.parent == ordinaryOwnDraftParent && editor.windowId == windowId &&
                rootNode.windowId == windowId) { "CURRENT_PRIVATE_SEND_BINDING_CHANGED" }
            return candidate
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
            if (ui.has("capture_backend")) {
                check(ui.get("capture_backend") == "android_media_projection_v1") { "UNKNOWN_CAPTURE_BACKEND" }
                check(projectionSession != null) { "PROJECTION_OWNED_SETUP_REQUIRED" }
            }
            projectionSession?.let { session ->
                val width = device.displayWidth
                val height = device.displayHeight
                val density = context.resources.configuration.densityDpi
                session.startRecording(width, height, density, readyDeadlineNanos)
                val video = File(root, "video.mp4")
                waitUntil(readyDeadlineNanos) {
                    check(SystemClock.elapsedRealtimeNanos() < readyDeadlineNanos) { "PROJECTION_ORIGINAL_READY_EXPIRED" }
                    session.requireHealthy()
                    check(!File(root, "CANCEL").exists()) { "CANCELLED_NO_RETRY" }
                    session.recordingStarted && video.isFile && video.length() >= 64 && runCatching {
                        video.inputStream().use { input ->
                            val header = ByteArray(12)
                            input.read(header) == 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                        }
                    }.getOrDefault(false)
                }
                val previousLength = video.length()
                waitUntil(readyDeadlineNanos) {
                    check(SystemClock.elapsedRealtimeNanos() < readyDeadlineNanos) { "PROJECTION_ORIGINAL_READY_EXPIRED" }
                    session.requireRecordingActive()
                    check(!File(root, "CANCEL").exists()) { "CANCELLED_NO_RETRY" }
                    video.length() > previousLength
                }
                check(SystemClock.elapsedRealtimeNanos() < readyDeadlineNanos) { "PROJECTION_ORIGINAL_READY_EXPIRED" }
                session.requireRecordingActive()
                writeJson("OWNED_PROJECTION_CAPTURE.json", JSONObject().put("backend", "android_media_projection_v1")
                    .put("run_id", root.name).put("session_id", session.id).put("owner_package", context.packageName)
                    .put("owner_instrumentation_pid", Process.myPid()).put("owner_uid", Process.myUid())
                    .put("video", video.name).put("width", width).put("height", height).put("density_dpi", density)
                    .put("budget_seconds", 90).put("ready_file_bytes", video.length())
                    .put("ready_qualification", "operational_file_growth_not_frame_decoding"))
                check(SystemClock.elapsedRealtimeNanos() < readyDeadlineNanos) { "PROJECTION_ORIGINAL_READY_EXPIRED" }
                session.requireRecordingActive()
                check(!File(root, "CANCEL").exists()) { "CANCELLED_NO_RETRY" }
                return
            }
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
            if (target == EngineeringTarget.DEEPSEEK) {
                action {
                    val session = projectionSession
                    session?.requireRecordingActive()
                    val authorized = ui.getJSONObject("deepseek_contract").get("invocation_authorized")
                    check(authorized is Boolean && authorized) { "DEEPSEEK_SEND_INVOCATION_NOT_AUTHORIZED" }
                    val input = editNode()
                    val candidate = checkNotNull(currentDeepSeekSendRole(input, currentEmpty(input), deepSeekFrame())) {
                        "DEEPSEEK_CURRENT_SEND_ROLE_REQUIRED"
                    }
                    check(projectionSession === session) { "CURRENT_CAPTURE_SESSION_CHANGED" }
                    session?.requireRecordingActive()
                    check(candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "DEEPSEEK_SEND_CLICK_REFUSED" }
                }
            } else if (ui.has("send_role_binding")) {
                action {
                    val session = projectionSession
                    session?.requireRecordingActive()
                    val authorized = ui.getJSONObject("send_role_binding").get("invocation_authorized")
                    check(authorized is Boolean && authorized) { "CURRENT_PRIVATE_SEND_INVOCATION_NOT_AUTHORIZED" }
                    val input = editNode()
                    val candidate = checkNotNull(currentPrivateSendRole(input, currentEmpty(input))) { "CURRENT_PRIVATE_SEND_ROLE_REQUIRED" }
                    check(projectionSession === session) { "CURRENT_CAPTURE_SESSION_CHANGED" }
                    session?.requireRecordingActive()
                    check(candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "CURRENT_PRIVATE_SEND_CLICK_REFUSED" }
                }
            } else action {
                val session = projectionSession
                session?.requireRecordingActive()
                click("send", session)
            }
        }

        override fun observeUntil(deadlineNanos: Long) {
            saveOriginals("after-send")
            while (SystemClock.elapsedRealtimeNanos() < deadlineNanos) {
                projectionSession?.requireRecordingActive()
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
            projectionSession?.let { session ->
                val closureDeadline = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
                session.close()
                while (!session.isClosed()) {
                    check(SystemClock.elapsedRealtimeNanos() < closureDeadline) { "PROJECTION_RESOURCE_CLOSURE_UNKNOWN" }
                    SystemClock.sleep(50) // Cancellation cannot skip owned resource teardown.
                }
                check(SystemClock.elapsedRealtimeNanos() < closureDeadline) { "PROJECTION_RESOURCE_CLOSURE_UNKNOWN" }
                if (session.mediaRequired) sealFinalMedia()
                writeJson("PROJECTION_CAPTURE_CLOSED.json", JSONObject().put("backend", "android_media_projection_v1")
                    .put("run_id", root.name).put("session_id", session.id).put("resources_closed", true)
                    .put("encoder_start_returned", session.recordingStarted).put("media_required", session.mediaRequired))
                session.releaseClosedSession()
                projectionSession = null // Only confirmed resource/Service closure and required media seal release ownership.
                return
            }
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
            sealFinalMedia()
            recorderOwner = null // Only actual shell exit, reader EOF and final media seal release ownership.
            capture = null
        }

        private fun sealFinalMedia() {
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
        }

        override fun cleanupOwned(): Boolean {
            val result = cleanupOwnedDecision() // Returns only after its original finally has completed.
            runCatching { persistCleanupOriginalFailure(result) }
            return result
        }

        private fun cleanupOwnedDecision(): Boolean {
            try {
                var clean = preparationOnly || runCatching { stopOwnedRecording() }.onFailure { original ->
                    if (recordingCleanupFailure == null) recordingCleanupFailure =
                        CleanupOriginalFailure(original, "recording_close", null, null, null, null)
                }.isSuccess
                val owner = targetOwner
                var targetClosed = owner == null && targetPid == null
                if (owner != null) {
                    val closureDeadline = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
                    cleanupKeyRequestIndex = null
                    cleanupExpectedRemainingLength = null
                    cleanupLastDeadlineCheckNanos = null
                    targetClosed = runCatching {
                        cleanupPhase = "target_observe"
                        when (val observed = processes.observe(owner.pid, readDeadline(closureDeadline))) {
                            OwnedProcessLifecycle.Observation.Absent -> Unit
                            is OwnedProcessLifecycle.Observation.Unknown -> error("TARGET_CLOSURE_UNKNOWN_${observed.reason}")
                            is OwnedProcessLifecycle.Observation.Alive -> {
                            cleanupPhase = "target_owner_uid_binding"
                            check(observed.owner == owner) { "TARGET_IDENTITY_DRIFT_NOT_TOUCHED" }
                            check(context.packageManager.getApplicationInfo(TARGET, 0).uid == targetUid)
                            cleanupPhase = "target_current_package"
                            if (device.currentPackageName == TARGET) {
                                cleanupPhase = "target_editor_read"
                                val input = editNodeOrNull(closureDeadline)
                                cleanupPhase = "target_draft_guard"
                                if (preparationOnly) {
                                    check(input == null || currentEmpty(input)) { "PREPARATION_UNKNOWN_DRAFT_NOT_TOUCHED" }
                                } else if (input != null && !input.isShowingHintText && input.text?.toString() == TongyiEngineeringRunner.PAYLOAD) {
                                    clearExactOwnedDraft(owner, closureDeadline)
                                } else {
                                    check(input == null || currentEmpty(input)) { "UNKNOWN_REPLACEMENT_DRAFT_NOT_TOUCHED" }
                                }
                            }
                            }
                        }
                        cleanupPhase = "target_stop"
                        check(processes.stopAndConfirm(owner, OwnedProcessLifecycle.Stop.TARGET_FORCE_STOP, closureDeadline))
                        cleanupPhase = "target_absence"
                        processes.requirePackageAbsent(TARGET, readDeadline(closureDeadline))
                    }.onFailure { original ->
                        if (targetCleanupFailure == null) targetCleanupFailure = CleanupOriginalFailure(original, cleanupPhase,
                            cleanupKeyRequestIndex, cleanupExpectedRemainingLength, closureDeadline, cleanupLastDeadlineCheckNanos)
                    }.isSuccess
                    clean = targetClosed && clean
                    if (targetClosed) {
                        targetOwner = null
                        targetPid = null
                        targetTicks = null
                        ordinaryOwnDraft = null
                        ordinaryOwnDraftParent = null
                        ordinaryOwnDraftWindow = -1
                        ordinaryOwnDraftOwner = null
                    }
                    if (targetClosed && (device.currentPackageName == TARGET || device.currentPackageName == context.packageName)) {
                        clean = device.pressHome() && clean
                    }
                }
                val noTarget = runCatching {
                    processes.requirePackageAbsent(TARGET, readDeadline())
                }.isSuccess
                return clean && targetClosed && noTarget && device.currentPackageName == "com.huawei.android.launcher"
            } finally {
                // Cached failed-acquisition values only; no UI/owner/clock query and no effect on cleanup truth.
                runCatching { persistRejectedOrdinaryMenuObservation() }
            }
        }

        private fun persistCleanupOriginalFailure(result: Boolean) {
            val failures = listOfNotNull(recordingCleanupFailure, targetCleanupFailure)
            if (failures.isEmpty() || cleanupFailurePersistenceAttempted) return
            cleanupFailurePersistenceAttempted = true
            val report = JSONObject().put("kind", "operational_native_cleanup_original_failure_not_formal_terminal")
                .put("run_id", root.name).put("owned_cleanup_result", result)
                .put("failures", JSONArray().apply {
                    failures.forEach { observed ->
                        put(JSONObject().put("original_exception_class", observed.original.javaClass.name)
                            .put("original_message", observed.original.message ?: JSONObject.NULL)
                            .put("original_stack", observed.original.stackTraceToString())
                            .put("phase", observed.phase).put("key_request_index", observed.keyRequestIndex ?: JSONObject.NULL)
                            .put("expected_remaining_length", observed.expectedRemainingLength ?: JSONObject.NULL)
                            .put("target_closure_deadline_nanos", observed.targetClosureDeadlineNanos?.toString() ?: JSONObject.NULL)
                            .put("last_existing_clear_deadline_check_nanos", observed.lastDeadlineCheckNanos?.toString() ?: JSONObject.NULL))
                    }
                })
            val file = File(root, "native-cleanup-original-failure.json")
            if (!file.createNewFile()) return
            FileOutputStream(file).use {
                it.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        }

        /** Same owned byte-acquisition boundary; the caller supplies only its receipt name. */
        private fun ownerGroupOsPipe(nextReceiptName: () -> String): QualificationOwnerShellTransport.OsPipe =
            object : QualificationOwnerShellTransport.OsPipe {
                override fun execute(command: String, deadlineNanos: Long) = processShell(command, deadlineNanos)
                override fun sleepMillis(milliseconds: Long) = SystemClock.sleep(milliseconds)
                override fun executeOwnedScript(apiCommand: String, pid: String, nonce: String,
                    deadlineNanos: Long): QualificationOwnerShellTransport.Acquisition {
                    val started = SystemClock.elapsedRealtimeNanos()
                    val bytes = ByteArrayOutputStream()
                    val failure = AtomicReference<Throwable?>(null)
                    val actualEof = java.util.concurrent.atomic.AtomicBoolean(false)
                    var descriptor: ParcelFileDescriptor? = null
                    var reader: Thread? = null
                    try {
                        check(started < deadlineNanos) { "QUALIFICATION_OWNER_PIPE_DEADLINE" }
                        val acquired = automation.executeShellCommand(apiCommand)
                        descriptor = acquired
                        reader = Thread {
                            try {
                                ParcelFileDescriptor.AutoCloseInputStream(acquired).use { input ->
                                    val buffer = ByteArray(4096)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) { actualEof.set(true); break }
                                        if (count > 0) synchronized(bytes) { bytes.write(buffer, 0, count) }
                                    }
                                }
                            } catch (error: Exception) { failure.compareAndSet(null, error) }
                        }.also { it.start() }
                        val remainingMillis = (deadlineNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                        if (remainingMillis > 0) reader.join(remainingMillis)
                    } catch (error: Exception) { failure.compareAndSet(null, error) }
                    val eof = actualEof.get() && reader?.isAlive == false && failure.get() == null
                    if (!eof) runCatching { descriptor?.close() }.exceptionOrNull()?.let {
                        failure.compareAndSet(null, it)
                    }
                    val raw = synchronized(bytes) { bytes.toByteArray() }
                    val error = failure.get()?.toString() ?: if (!eof) "QUALIFICATION_OWNER_PIPE_TIMEOUT_OR_INCOMPLETE" else ""
                    val observedNanos = SystemClock.elapsedRealtimeNanos()
                    writeJson(nextReceiptName(), JSONObject()
                        .put("kind", "one_owned_public_shell_byte_acquisition_not_identity_or_os_exit_proof")
                        .put("public_api_command", apiCommand).put("script_path", apiCommand.removePrefix("sh "))
                        .put("pid", pid).put("nonce", nonce)
                        .put("deadline_elapsed_realtime_nanos", deadlineNanos.toString())
                        .put("started_elapsed_realtime_nanos", started.toString())
                        .put("snapshot_elapsed_realtime_nanos", observedNanos.toString())
                        .put("descriptor_acquired", descriptor != null).put("actual_descriptor_eof", eof)
                        .put("reader_alive_at_snapshot", reader?.isAlive ?: JSONObject.NULL)
                        .put("raw_state", if (eof) "EOF_COMPLETED_BYTES" else if (raw.isNotEmpty())
                            "PARTIAL_BYTES_AT_SNAPSHOT" else "NO_BYTES_OBSERVED_AT_SNAPSHOT")
                        .put("raw_stdout_b64", if (descriptor != null) Base64.encodeToString(raw, Base64.NO_WRAP) else JSONObject.NULL)
                        .put("raw_bytes", if (descriptor != null) raw.size else JSONObject.NULL)
                        .put("raw_sha256", if (descriptor != null) sha(raw) else JSONObject.NULL)
                        .put("error", error).put("native_os_direct_exit", "UNOBSERVED")
                        .put("per_command_status_kind", "encoded_script_status_not_native_os_direct_exit")
                        .put("completion_semantics", "five_results_share_one_descriptor_eof_marker_is_not_eof"))
                    // Producer checks this same until after submission AND this receipt's fsync cost.
                    return QualificationOwnerShellTransport.Acquisition(raw, eof, error)
                }
            }

        private fun clearExactOwnedDraft(owner: OwnedProcessLifecycle.Owner, closureDeadline: Long) {
            check(!preparationOnly && !qualificationOnly && !sourceInspectionOnly) { "ORDINARY_OWN_DRAFT_CLEAR_ONLY" }
            val payload = TongyiEngineeringRunner.PAYLOAD
            val bytes = payload.toByteArray(Charsets.UTF_8)
            check(bytes.size == 239 && sha(bytes) == PAYLOAD_SHA && payload.length == 81 &&
                payload.length == payload.codePointCount(0, payload.length)) {
                "EXACT_FIXED_DRAFT_REQUIRED"
            }
            var clearOwnerPipeSequence = 0
            val clearOwnerScope = QualificationOwnerProbeScope(QualificationOwnerShellTransport(scriptTransport,
                ownerGroupOsPipe { "ordinary-clear-owner-pipe-${++clearOwnerPipeSequence}.json" },
                SystemClock::elapsedRealtimeNanos), SystemClock::elapsedRealtimeNanos,
                QualificationOwnerProbeScope.Presence.WHOLE_LIST)
            fun withinClosure() {
                val now = SystemClock.elapsedRealtimeNanos()
                cleanupLastDeadlineCheckNanos = now // Reuse this same original read in the unchanged comparison.
                check(now < closureDeadline) { "OWN_DRAFT_CLEAR_CLOSURE_EXPIRED" }
            }
            fun boundDraft(expected: String, fullSelectionRequired: Boolean = false): AccessibilityNodeInfo {
                cleanupPhase = "clear_bound_read"
                cleanupExpectedRemainingLength = expected.length
                withinClosure()
                val current = checkNotNull(editNodeOrNull(closureDeadline, clearOwnerScope)) { "OWN_DRAFT_CLEAR_EDITOR_MISSING" }
                withinClosure()
                check(current.refresh()) { "OWN_DRAFT_CLEAR_EDITOR_REFRESH_FAILED" }
                withinClosure()
                val parent = checkNotNull(current.parent) { "OWN_DRAFT_CLEAR_PARENT_MISSING" }
                check(parent.refresh()) { "OWN_DRAFT_CLEAR_PARENT_REFRESH_FAILED" }
                withinClosure()
                cleanupPhase = "clear_bound_checks"
                check(targetOwner == owner && ordinaryOwnDraftOwner == owner && current == ordinaryOwnDraft &&
                    parent == ordinaryOwnDraftParent && current.windowId == ordinaryOwnDraftWindow &&
                    parent.windowId == ordinaryOwnDraftWindow && current.packageName?.toString() == TARGET &&
                    current.className?.toString() == "android.widget.EditText" && current.isEditable &&
                    current.isEnabled && current.isFocused && visible(current)) { "UNKNOWN_REPLACEMENT_DRAFT_NOT_TOUCHED" }
                check(if (expected.isEmpty()) currentEmpty(current) else !current.isShowingHintText && current.text?.toString() == expected) {
                    "OWN_DRAFT_EXPECTED_PREFIX_CHANGED_NOT_TOUCHED"
                }
                if (fullSelectionRequired) {
                    if (draftQualificationActive) draftQualificationFullSelectionConfirmed = false
                    check(expected == payload && current.textSelectionStart.also {
                        if (draftQualificationActive) draftQualificationSelectionStart = it
                    } == 0 && current.textSelectionEnd.also {
                        if (draftQualificationActive) draftQualificationSelectionEnd = it
                    } == payload.length) { "OWN_DRAFT_FULL_SELECTION_UNCONFIRMED" }
                    if (draftQualificationActive) draftQualificationFullSelectionConfirmed = true
                }
                withinClosure()
                return current
            }
            fun deletePair() {
                val keyCode = KeyEvent.KEYCODE_DEL
                cleanupPhase = "clear_delete_pair"
                cleanupKeyRequestIndex = (cleanupKeyRequestIndex ?: 0) + 1 // Single DEL request; not proof of clearing.
                withinClosure()
                val downTime = SystemClock.uptimeMillis() // Public KeyEvent time, not the Core event/metric clock.
                val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                try {
                    check(automation.injectInputEvent(down, true)) { "OWN_DRAFT_KEY_DOWN_REFUSED" }
                    withinClosure()
                } finally {
                    // Release this exact key even after a failed/ambiguous down or an expired deadline.
                    val up = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                    check(automation.injectInputEvent(up, true)) { "OWN_DRAFT_KEY_UP_REFUSED" }
                }
                withinClosure()
            }
            val selectionTarget = boundDraft(payload)
            cleanupPhase = "clear_select_all"
            val selectionAdvertised = selectionTarget.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }
            if (draftQualificationActive) draftQualificationSelectionAdvertised = selectionAdvertised
            check(selectionAdvertised) {
                "OWN_DRAFT_SELECTION_UNSUPPORTED"
            }
            withinClosure()
            val selectionReturned = selectionTarget.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, payload.length)
            })
            if (draftQualificationActive) draftQualificationSelectionReturned = selectionReturned
            check(selectionReturned) { "OWN_DRAFT_SELECTION_REFUSED" }
            withinClosure()
            boundDraft(payload, fullSelectionRequired = true)
            deletePair()
            boundDraft("") // Same owned editor/hint must actually be empty; no further deletion.
            if (draftQualificationActive) draftQualificationEmptyConfirmed = true
            cleanupPhase = "clear_home_before_stop"
            withinClosure()
            check(device.pressHome()) { "OWN_DRAFT_HOME_REFUSED" }
            withinClosure()
            check(device.currentPackageName == "com.huawei.android.launcher" &&
                automation.rootInActiveWindow?.packageName?.toString() == "com.huawei.android.launcher") {
                "OWN_DRAFT_LAUNCHER_UNCONFIRMED"
            }
            withinClosure()
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

        private fun requireOwnedCurrent(deadlineNanos: Long = Long.MAX_VALUE,
            clearOwnerScope: QualificationOwnerProbeScope? = null) {
            val currentPackage = if (clearOwnerScope != null)
                clearPackageIdleScope.run { device.currentPackageName } else device.currentPackageName
            check(currentPackage == TARGET) { "CURRENT_TARGET_PACKAGE_MISMATCH_cached=${currentPackage ?: "<NULL>"}" }
            val expectedPid = targetPid
            var currentPid: String? = null
            val pidMatches = expectedPid != null && shell("pidof $TARGET").trim().also { currentPid = it } == targetPid
            if (!pidMatches) {
                val original = IllegalStateException(
                    "CURRENT_TARGET_PID_MISMATCH_expected=${expectedPid}_cached=${currentPid?.let { "'$it'" } ?: "<NOT_QUERIED>"}")
                if (ordinaryPreInputPreparation) {
                    try {
                        retainOrdinaryPidRejectionIdentity(original, expectedPid, currentPid, minOf(deadlineNanos, preparationDeadline))
                    } catch (diagnostic: Throwable) {
                        if (diagnostic !== original) runCatching { original.addSuppressed(diagnostic) }
                    }
                }
                throw original
            }
            if (clearOwnerScope == null) processes.requireAlive(checkNotNull(targetOwner), readDeadline(deadlineNanos))
            else clearOwnerScope.requireAlive(checkNotNull(targetOwner), readDeadline(deadlineNanos))
            val currentRootPackage = automation.rootInActiveWindow?.packageName?.toString()
            check(currentRootPackage == TARGET) { "CURRENT_ROOT_PACKAGE_MISMATCH_cached=${currentRootPackage ?: "<NULL>"}" }
        }

        private fun retainOrdinaryPidRejectionIdentity(original: IllegalStateException, expectedPid: String?,
            cachedPidof: String?, deadlineNanos: Long) {
            if (ordinaryPidRejectionIdentityAttempted) return
            ordinaryPidRejectionIdentityAttempted = true
            val results = JSONArray()
            val report = JSONObject().put("kind", "operational_ordinary_preinput_pid_rejection_identity_not_admission")
                .put("run_id", root.name).put("expected_pid", expectedPid ?: JSONObject.NULL)
                .put("cached_pidof", cachedPidof ?: JSONObject.NULL)
                .put("original_exception_class", original.javaClass.name).put("original_message", original.message)
                .put("absolute_deadline_nanos", deadlineNanos.toString())
                .put("preparation_absolute_deadline_nanos", preparationDeadline.toString())
                .put("identity_at_original_pidof", "UNKNOWN_LATER_ACQUISITIONS_CANNOT_BACKFILL")
                .put("identity_verdict", "UNKNOWN_DIAGNOSTIC_ONLY_ORIGINAL_REJECTION_UNCHANGED")
                .put("native_os_direct_exit", "UNOBSERVED_ENCODED_SCRIPT_STATUS_ONLY")
                .put("results", results)
            try {
                val pids = cachedPidof?.trim()?.split(Regex("\\s+")).orEmpty()
                check(pids.size == 2 && pids.distinct().size == 2 && pids.all { it.matches(Regex("[1-9][0-9]*")) }) {
                    "PID_DIAGNOSTIC_REQUIRES_EXACTLY_TWO_UNIQUE_POSITIVE_TOKENS"
                }
                check(preparationDeadline > 0 && deadlineNanos <= preparationDeadline) { "PID_DIAGNOSTIC_PREPARATION_DEADLINE_REQUIRED" }
                report.put("pid_tokens", JSONArray(pids))
                val commands = listOf("ps -A -o PID,NAME") + pids.flatMap { pid ->
                    listOf("cat /proc/$pid/stat", "cat /proc/$pid/status", "cat /proc/$pid/cmdline", "cat /proc/$pid/stat")
                }
                for (command in commands) {
                    val entry = JSONObject().put("command", command)
                    results.put(entry)
                    val started = SystemClock.elapsedRealtimeNanos()
                    entry.put("started_elapsed_realtime_nanos", started.toString())
                    if (started >= deadlineNanos) {
                        entry.put("state", "NOT_ATTEMPTED_DEADLINE_EXPIRED")
                        continue
                    }
                    try {
                        val sequence = processShellSequence
                        val reply = processShell(command, deadlineNanos)
                        check(processShellSequence == sequence + 1) { "PID_DIAGNOSTIC_RECEIPT_SEQUENCE_UNKNOWN" }
                        val name = "process-shell-" + processShellSequence + ".json"
                        val receiptBytes = File(root, name).readBytes()
                        val metadata = JSONObject(receiptBytes.toString(Charsets.UTF_8))
                        check(metadata.getString("command") == command &&
                            metadata.getString("deadline_elapsed_realtime_nanos") == deadlineNanos.toString()) {
                            "PID_DIAGNOSTIC_RECEIPT_BINDING_UNKNOWN"
                        }
                        entry.put("receipt", JSONObject().put("path", name).put("bytes", receiptBytes.size).put("sha256", sha(receiptBytes)))
                            .put("completed", reply.completed).put("encoded_exit_code", reply.exitCode ?: JSONObject.NULL)
                            .put("stdout_eof", metadata.getBoolean("stdout_eof")).put("error", reply.stderr)
                            .put("raw_byte_source", "unmodified referenced receipt raw_stdout_b64 including NUL and original marker")
                        val finished = SystemClock.elapsedRealtimeNanos()
                        entry.put("finished_elapsed_realtime_nanos", finished.toString())
                            .put("state", if (reply.completed && reply.exitCode == 0 && reply.stderr.isEmpty() &&
                                metadata.getBoolean("stdout_eof") && finished <= deadlineNanos)
                                "COMPLETE_SCRIPT_RESULT_NOT_OWNER_PROOF" else "UNKNOWN_OR_INCOMPLETE")
                    } catch (diagnostic: Throwable) {
                        entry.put("state", "UNKNOWN_OR_INCOMPLETE").put("diagnostic_error", diagnostic.toString())
                    }
                }
            } catch (diagnostic: Throwable) {
                report.put("diagnostic_error", diagnostic.toString())
            }
            val persistenceStarted = SystemClock.elapsedRealtimeNanos()
            report.put("persistence_started_elapsed_realtime_nanos", persistenceStarted.toString())
                .put("io_may_delay_original_finally", true)
            check(persistenceStarted < deadlineNanos) { "PID_DIAGNOSTIC_DEADLINE_BEFORE_SIDECAR" }
            val file = File(root, "ordinary-pid-rejection-identity.json")
            check(file.createNewFile()) { "PID_DIAGNOSTIC_SIDECAR_ALREADY_EXISTS_NO_RETRY" }
            FileOutputStream(file).use { it.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(SystemClock.elapsedRealtimeNanos() <= deadlineNanos) { "PID_DIAGNOSTIC_PERSISTENCE_EXCEEDED_DEADLINE" }
        }

        private fun currentEmpty(node: AccessibilityNodeInfo): Boolean = if (!node.isShowingHintText) node.text.isNullOrEmpty()
            else !node.hintText.isNullOrEmpty() && (node.text.isNullOrEmpty() || node.text?.toString() == node.hintText?.toString())

        private fun preparationNodeValues(nodes: List<AccessibilityNodeInfo>) = nodes.mapIndexed { index, node ->
            TongyiPreparationRunner.Node(index.toString(), nodes.indexOf(node.parent).takeIf { it >= 0 }?.toString(),
                node.packageName?.toString().orEmpty(), node.className?.toString().orEmpty(), run {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    TongyiPreparationRunner.Bounds(rect.left, rect.top, rect.right, rect.bottom)
                }, visible(node), node.isEnabled, node.isClickable, node.text?.toString().orEmpty(), node.viewIdResourceName.orEmpty(),
                node.hintText?.toString(), node.isShowingHintText, node.isEditable, node.isFocused,
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }, node.contentDescription?.toString().orEmpty())
        }

        private fun ordinaryGraphicsGate(deadline: Long) {
            check(SystemClock.elapsedRealtimeNanos() < minOf(deadline, preparationDeadline)) { "ORDINARY_GRAPHICS_DEADLINE" }
            check(!File(root, "CANCEL").exists()) { "CANCELLED_NO_RETRY" }
        }

        private fun ordinaryGraphicsBinding(value: Pair<TongyiPreparationRunner.Frame, List<AccessibilityNodeInfo>>,
            deadline: Long): OrdinaryGraphicsBinding {
            ordinaryGraphicsGate(deadline)
            requireOwnedCurrent(deadline)
            val owner = checkNotNull(targetOwner)
            val (frame, handles) = value
            check(frame.owner == "${owner.pid}/${owner.uid}/${owner.startTicks}/" +
                Base64.encodeToString(owner.rawCommandLine.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)) { "ORDINARY_GRAPHICS_OWNER_CHANGED" }
            val projectedRoot = handles.first()
            val windowId = projectedRoot.windowId
            check(windowId >= 0) { "ORDINARY_GRAPHICS_UNSUPPORTED_WINDOW_ID" }
            val window = checkNotNull(projectedRoot.window) { "ORDINARY_GRAPHICS_UNSUPPORTED_WINDOW" }
            val windowRoot = checkNotNull(window.root) { "ORDINARY_GRAPHICS_UNSUPPORTED_WINDOW_ROOT" }
            val rootRect = Rect()
            windowRoot.getBoundsInScreen(rootRect)
            check(window.isActive && window.id == windowId && windowRoot.windowId == windowId &&
                windowRoot.packageName?.toString() == TARGET &&
                TongyiPreparationRunner.Bounds(rootRect.left, rootRect.top, rootRect.right, rootRect.bottom) == frame.rootBounds) {
                "ORDINARY_GRAPHICS_ACTIVE_WINDOW_CHANGED"
            }
            val rect = Rect()
            window.getBoundsInScreen(rect)
            check(rect.width() > 0 && rect.height() > 0) { "ORDINARY_GRAPHICS_UNSUPPORTED_WINDOW_BOUNDS" }
            requireOwnedCurrent(deadline)
            ordinaryGraphicsGate(deadline)
            return OrdinaryGraphicsBinding(owner, windowId, frame.width, frame.height, frame.rotation, frame.rootBounds,
                TongyiPreparationRunner.Bounds(rect.left, rect.top, rect.right, rect.bottom))
        }

        private fun awaitOrdinaryHomeGraphics(deadline: Long): OrdinaryGraphicsReadiness {
            ordinaryGraphicsGate(deadline)
            check(Build.VERSION.SDK_INT >= 21) { "ORDINARY_GRAPHICS_UNSUPPORTED_API" }
            val binding = ordinaryGraphicsBinding(ordinaryPreparationFrame(deadlineNanos = deadline), deadline)
            var previous: OrdinaryGraphicsRecord? = null
            while (true) {
                check(ordinaryGraphicsBinding(ordinaryPreparationFrame(deadlineNanos = deadline), deadline) == binding) {
                    "ORDINARY_GRAPHICS_WINDOW_BINDING_CHANGED"
                }
                ordinaryGraphicsGate(deadline)
                val stats = checkNotNull(automation.getWindowContentFrameStats(binding.windowId)) { "ORDINARY_GRAPHICS_UNSUPPORTED_STATS" }
                ordinaryGraphicsGate(deadline) // The synchronous public Binder getter cannot be forcibly timed out.
                val count = stats.frameCount
                check(count in 1..2048 && stats.refreshPeriodNano > 0) { "ORDINARY_GRAPHICS_UNSUPPORTED_EMPTY_OR_UNBOUNDED_STATS" }
                val rows = (0 until count).map { index -> listOf(stats.getFramePostedTimeNano(index),
                    stats.getFrameReadyTimeNano(index), stats.getFramePresentedTimeNano(index)) }
                val record = OrdinaryGraphicsRecord(stats.refreshPeriodNano, rows)
                check(ordinaryGraphicsBinding(ordinaryPreparationFrame(deadlineNanos = deadline), deadline) == binding) {
                    "ORDINARY_GRAPHICS_WINDOW_BINDING_CHANGED"
                }
                // Reject the whole boundary on any pending row; never filter, restart or forget invalid history.
                check(rows.all { row -> row.all { it != FrameStats.UNDEFINED_TIME_NANO && it >= 0 } }) {
                    "ORDINARY_GRAPHICS_PENDING_HISTORY_UNKNOWN"
                }
                ordinaryGraphicsGate(deadline)
                if (previous == record) return OrdinaryGraphicsReadiness(binding, deadline)
                previous = record
                ordinaryGraphicsGate(deadline)
                SystemClock.sleep(50)
                ordinaryGraphicsGate(deadline)
            }
        }

        private fun ordinaryPreparationFrame(menuPixels: Boolean = false, graphics: OrdinaryGraphicsReadiness? = null,
            deadlineNanos: Long = graphics?.until ?: Long.MAX_VALUE): Pair<TongyiPreparationRunner.Frame, List<AccessibilityNodeInfo>> {
            if (deadlineNanos != Long.MAX_VALUE) ordinaryGraphicsGate(deadlineNanos)
            var handles = nodes(deadlineNanos) // Preserve the ordinary full-list process/foreground/root checks.
            val values = preparationNodeValues(handles)
            val owner = checkNotNull(targetOwner)
            val frame = TongyiPreparationRunner.Frame(device.displayWidth, device.displayHeight, device.displayRotation,
                values.first().bounds, "${owner.pid}/${owner.uid}/${owner.startTicks}/" +
                    Base64.encodeToString(owner.rawCommandLine.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), values, null)
            val bounds = frame.rootBounds
            check(frame.width == 1200 && frame.height == 2640 && frame.rotation == 0 && values.first().visible &&
                values.first().packageName == TARGET && bounds.left >= 0 && bounds.top >= 0 &&
                bounds.right <= frame.width && bounds.bottom <= frame.height && bounds.right > bounds.left && bounds.bottom > bounds.top) {
                "CURRENT_GEOMETRY_INVALID"
            }
            var menuSha: String? = null
            if (menuPixels) {
                val readiness = checkNotNull(graphics) { "ORDINARY_GRAPHICS_PRECONDITION_REQUIRED" }
                check(ordinaryGraphicsBinding(frame to handles, readiness.until) == readiness.binding) {
                    "ORDINARY_GRAPHICS_CAPTURE_BINDING_CHANGED"
                }
                val screenshot = checkNotNull(automation.takeScreenshot()) { "PUBLIC_SCREENSHOT_UNAVAILABLE" }
                val bitmap = try { checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) {
                    "PUBLIC_ARGB_BITMAP_UNAVAILABLE"
                } } finally { screenshot.recycle() }
                try {
                    check(bitmap.width == frame.width && bitmap.height == frame.height) { "PUBLIC_SCREENSHOT_VIEWPORT_MISMATCH" }
                    val pixels = IntArray(10000)
                    bitmap.getPixels(pixels, 0, 100, 56, 193, 100, 100)
                    val header = "ANEB807_MENU_ARGB_V1\n100\n100\n".toByteArray(Charsets.UTF_8)
                    val canonical = ByteArray(header.size + 40000)
                    header.copyInto(canonical)
                    pixels.forEachIndexed { index, argb ->
                        check(argb ushr 24 == 255) { "MENU_PIXEL_ALPHA_MISMATCH" }
                        for (channel in 0..3) canonical[header.size + index * 4 + channel] = (argb ushr (24 - channel * 8)).toByte()
                    }
                    menuSha = sha(canonical)
                    ordinaryMenuObservation = OrdinaryMenuObservation(frame.copy(nodes = frame.nodes.toList(), menuArgbSha256 = menuSha),
                        canonical.copyOf()) // No Bitmap or live accessibility handle escapes this acquisition.
                } finally { bitmap.recycle() }
                val after = ordinaryPreparationFrame(deadlineNanos = readiness.until)
                handles = after.second
                check(after.first.nodes == values) { "TREE_CHANGED_DURING_PUBLIC_SCREENSHOT" }
                check(ordinaryGraphicsBinding(after, readiness.until) == readiness.binding) { "ORDINARY_GRAPHICS_CAPTURE_BINDING_CHANGED" }
            }
            if (deadlineNanos != Long.MAX_VALUE) ordinaryGraphicsGate(deadlineNanos)
            return frame.copy(menuArgbSha256 = menuSha) to handles
        }

        private fun persistRejectedOrdinaryMenuObservation() {
            val cached = ordinaryMenuObservation ?: return
            val reason = ordinaryMenuRejectionReason ?: return
            if (ordinaryMenuPersistenceAttempted) return
            ordinaryMenuPersistenceAttempted = true
            val visible = cached.frame.nodes.filter { it.visible && it.packageName == TARGET }
            val canonical = writeNewPreparationDiagnostic("ordinary-menu-rejected-canonical.argb", cached.canonicalArgb)
            val projection = writeNewPreparationDiagnostic("ordinary-menu-rejected-frame.json", preparationNodeProjection(cached.frame))
            writeNewPreparationDiagnostic("ordinary-menu-rejected-observation.json", (JSONObject()
                .put("kind", "operational_diagnostic_only_cached_ordinary_menu_rejection")
                .put("reason", reason).put("diagnostic_only", true).put("reference_accepted", false)
                .put("sampling_claim", "this_acquisition_sequential_native_values_and_canonical_argb_not_atomic_no_resampling")
                .put("quick_count", visible.count { it.text == "快速" })
                .put("hint_count", visible.count { it.text == "发消息或按住说话..." })
                .put("editable_count", visible.count { it.editable })
                .put("menu_argb_sha256", cached.frame.menuArgbSha256)
                .put("expected_menu_argb_sha256", TongyiPreparationRunner.MENU_SHA256)
                .put("canonical_argb", canonical).put("immutable_frame", projection).toString(2) + "\n").toByteArray(Charsets.UTF_8))
        }

        /** Called after the original Core finally and terminal/status, using only the original exception. */
        fun persistOriginalPreparationFailure() {
            val failure = originalPreparationFailure ?: return
            writeJson("ordinary-preparation-original-failure.json", JSONObject()
                .put("kind", "operational_original_prepare_exception_not_formal_terminal")
                .put("reason", failure.message ?: failure.javaClass.simpleName)
                .put("exception_class", failure.javaClass.name).put("original_stack", failure.stackTraceToString()))
        }

        fun qualificationRecords() = JSONArray(qualificationStageRecords.toString())
        fun sourceInspectionRecords() = JSONArray(sourceInspectionRecords.toString())

        fun qualificationRejectionRetention() = qualificationRejectionRecord?.let { JSONObject(it.toString()) }
        fun qualificationTargetCurrentDiagnostic() = qualificationTargetCurrentRejectionRecord?.let { JSONObject(it.toString()) }

        fun takeQualificationControlFailure(): TongyiUiReferenceQualificationRunner.GateFailureObservation? {
            val observation = qualificationControlFailure
            qualificationControlFailure = null
            return observation
        }

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
            val idleTimeoutScope = PublicUiIdleTimeoutScope(
                readTimeoutMillis = { Configurator.getInstance().getWaitForIdleTimeout() },
                writeTimeoutMillis = { value ->
                    Configurator.getInstance().setWaitForIdleTimeout(value)
                    Unit
                },
            )
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
            data class FailedInput(val expected: TongyiPreparationRunner.Frame, val editor: TongyiPreparationRunner.Node,
                val actual: TongyiPreparationRunner.Frame, val input: TongyiPreparationRunner.Node,
                val observationNanos: Long, val nativeUntil: Long, val operation: String)
            var captured: Capture? = null
            var failedInput: FailedInput? = null
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
            var ownerPipeSequence = 0
            val ownerScope = QualificationOwnerProbeScope(QualificationOwnerShellTransport(scriptTransport,
                ownerGroupOsPipe { "qualification-owner-pipe-${++ownerPipeSequence}.json" }, SystemClock::elapsedRealtimeNanos), SystemClock::elapsedRealtimeNanos,
                if (qualificationOnly) QualificationOwnerProbeScope.Presence.OWNED_PID_POSITIVE
                else QualificationOwnerProbeScope.Presence.WHOLE_LIST)
            fun preserveAcquired(name: String, bytes: ByteArray, deadline: Long = preparationDeadline) {
                gate(deadline)
                val file = File(root, name)
                check(file.createNewFile()) { "SOURCE_ORIGINAL_ALREADY_EXISTS_NO_RETRY" }
                FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
                check(file.readBytes().contentEquals(bytes)) { "SOURCE_ORIGINAL_READBACK_FAILED" }
                gate(deadline)
            }
            fun requireOwner(deadline: Long, cleanup: Boolean = false): OwnedProcessLifecycle.Owner {
                gate(deadline, cleanup)
                val owner = checkNotNull(targetOwner) { "QUALIFICATION_OWNER_UNKNOWN" }
                check(targetPid == owner.pid && owner.uid == targetUid && owner.commandLine == listOf(TARGET)) {
                    "QUALIFICATION_OWNER_CHANGED"
                }
                val currentPackage = if (qualificationOnly && !cleanup)
                    idleTimeoutScope.run { device.currentPackageName } else device.currentPackageName
                if (qualificationOnly && !cleanup) ownerScope.requireTargetCurrent(TARGET, owner.pid, currentPackage,
                    readPidof = { shell("pidof $TARGET") },
                    retainRejection = { snapshot ->
                        check(qualificationTargetCurrentRejectionRecord == null) { "TARGET_CURRENT_DIAGNOSTIC_ALREADY_ATTEMPTED" }
                        val until = minOf(deadline, preparationDeadline)
                        val report = JSONObject().put("kind", "qualification_target_current_same_call_scalar_diagnostic")
                            .put("status", "NOT_RETAINED").put("reason", "QUALIFICATION_TARGET_NOT_CURRENT")
                            .put("diagnostic_only", true).put("accepted_stage", false).put("reference_accepted", false)
                            .put("draft_cleanup_authority", false).put("owner_guard_deadline_nanos", deadline.toString())
                            .put("preparation_until_nanos", preparationDeadline.toString())
                            .put("diagnostic_until_nanos", until.toString())
                            .put("deadline_role", "existing_bound_not_observation_timestamp").put("write_attempted", false)
                        qualificationTargetCurrentRejectionRecord = report
                        try {
                            gate(until)
                            report.put("expected_package", snapshot.expectedPackage).put("expected_pid", snapshot.expectedPid)
                                .put("expected_uid", owner.uid).put("expected_start_ticks", owner.startTicks)
                                .put("current_package", snapshot.currentPackage ?: JSONObject.NULL)
                                .put("pidof_attempted", snapshot.pidofAttempted).put("raw_pidof", snapshot.rawPidof ?: JSONObject.NULL)
                                .put("pidof_result_kind", if (snapshot.pidofAttempted) "RETURNED_STRING_NO_OS_RC_OR_EOF" else "NOT_INVOKED")
                                .put("sampling_claim", "same_call_cached_scalar_values_no_new_query_no_frame_no_xml_no_png")
                                .put("write_attempted", true)
                            val value = JSONObject(report.toString()).apply { remove("status") }
                            val raw = (value.toString(2) + "\n").toByteArray(Charsets.UTF_8)
                            val name = "target-current-rejected-observation.json"
                            preserveAcquired(name, raw, until)
                            report.put("artifact", JSONObject().put("path", name).put("bytes", raw.size).put("sha256", sha(raw)))
                            gate(until)
                            report.put("status", "RETAINED")
                        } catch (secondary: Throwable) {
                            report.put("retention_failure", secondary.javaClass.simpleName + ":" + (secondary.message ?: "UNKNOWN"))
                            throw secondary // Public guard keeps this secondary; the original mismatch stays primary.
                        }
                    })
                else check(currentPackage == TARGET && shell("pidof $TARGET").trim() == owner.pid) {
                    "QUALIFICATION_TARGET_NOT_CURRENT"
                }
                if (cleanup) processes.requireAlive(owner, readDeadline(deadline))
                else ownerScope.requireAlive(owner, readDeadline(deadline))
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
                failedInput = null // A new acquisition cannot reuse an earlier attempt's rejected readback.
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
                val frame = TongyiPreparationRunner.Frame(device.displayWidth, device.displayHeight,
                    if (qualificationOnly && !cleanup) idleTimeoutScope.run { device.displayRotation } else device.displayRotation,
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
                    failedInput = null
                    homeGraphics = null
                    this@NativePhone.launchOwned(deadlineNanos)
                }
                override fun currentState(deadlineNanos: Long): TongyiPreparationRunner.Frame = try {
                    project(deadlineNanos).also {
                        if (sourceInspectionOnly) sourceReadinessProjection = it.copy(handles = emptyList())
                    }.frame
                } catch (failure: Exception) { homeGraphics = null; throw failure }
                override fun awaitOwnedHomeGraphics(deadlineNanos: Long) {
                    failedInput = null
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
                    failedInput = null
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
                    // The same single raw XML acquisition; SOURCE keeps its original SDK policy.
                    if (qualificationOnly) idleTimeoutScope.run { device.dumpWindowHierarchy(xmlFile) }
                    else device.dumpWindowHierarchy(xmlFile)
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
                    failedInput = null
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
                    failedInput = null // Also clear on every aborted/pre-action failure, before any new attempt.
                    check(!sourceInspectionOnly) { "SOURCE_TEXT_FORBIDDEN" }
                    val deadline = minOf(preparationDeadline, SystemClock.elapsedRealtimeNanos() + TongyiEngineeringRunner.INPUT_NANOS)
                    check(text == TongyiEngineeringRunner.PAYLOAD || text.isEmpty()) { "QUALIFICATION_TEXT_CAPABILITY_NOT_ALLOWED" }
                    check(editor.editable && editor.focused && editor.setTextAvailable) { "QUALIFICATION_EDITOR_NOT_CURRENT" }
                    if (text.isEmpty()) check(ownDraft == editor && ownDraftOwner == observed.owner &&
                        !editor.showingHint && editor.text == TongyiEngineeringRunner.PAYLOAD) { "QUALIFICATION_ATTRIBUTED_OWN_DRAFT_REQUIRED" }
                    else check(!writeAttempted && empty(editor) && text.toByteArray(Charsets.UTF_8).size == 239) { "QUALIFICATION_EMPTY_EDITOR_REQUIRED" }
                    val fresh = freshBinding(observed, editor, deadline)
                    val actionEditor = fresh.handles[editor.id.toInt()]
                    val actionParent = checkNotNull(editor.parent?.toIntOrNull()?.let { fresh.handles.getOrNull(it) }) {
                        "QUALIFICATION_CURRENT_NODE_CHANGED"
                    }
                    val actionWindowId = fresh.windowId
                    check(actionWindowId >= 0 && actionEditor.windowId == actionWindowId && actionParent.windowId == actionWindowId) {
                        "QUALIFICATION_CURRENT_NODE_CHANGED"
                    }
                    if (text.isNotEmpty()) writeAttempted = true
                    check(actionEditor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    })) { "QUALIFICATION_SET_TEXT_REFUSED" }
                    while (true) {
                        val readback = project(deadline)
                        val input = readback.frame.nodes.singleOrNull { it.visible && it.editable && it.packageName == TARGET }
                            ?: error("QUALIFICATION_READBACK_EDITOR_UNKNOWN")
                        val inputEditor = input.id.toIntOrNull()?.let { readback.handles.getOrNull(it) }
                        val inputParent = input.parent?.toIntOrNull()?.let { readback.handles.getOrNull(it) }
                        val bounds = input.bounds
                        val rootBounds = fresh.frame.rootBounds
                        check(input.id == editor.id && input.parent == editor.parent && input.className == editor.className &&
                            inputEditor != null && inputParent != null && inputEditor == actionEditor && inputParent == actionParent &&
                            readback.windowId == actionWindowId && inputEditor.windowId == actionWindowId && inputParent.windowId == actionWindowId &&
                            readback.frame.owner == fresh.frame.owner && readback.frame.width == fresh.frame.width &&
                            readback.frame.height == fresh.frame.height && readback.frame.rotation == fresh.frame.rotation &&
                            readback.frame.rootBounds == rootBounds && bounds.left >= rootBounds.left && bounds.top >= rootBounds.top &&
                            bounds.right <= rootBounds.right && bounds.bottom <= rootBounds.bottom &&
                            bounds.right > bounds.left && bounds.bottom > bounds.top && input.enabled && input.focused && input.setTextAvailable) {
                            // Preserve the actual returned readback; native editor/parent identity permits TYPE/CLEAR geometry changes.
                            failedInput = FailedInput(observed, editor, readback.frame, input, readback.nanos,
                                deadline, if (text.isEmpty()) "CLEAR" else "TYPE")
                            "QUALIFICATION_READBACK_BINDING_CHANGED"
                        }
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
                override fun retainFailedInputObservation(observed: TongyiPreparationRunner.Frame,
                    editor: TongyiPreparationRunner.Node, reason: String, deadlineNanos: Long): Boolean {
                    val cached = failedInput
                    failedInput = null // Consume identity once, including mismatched/expired/failed retention.
                    if (reason != "QUALIFICATION_READBACK_BINDING_CHANGED") return false
                    if (qualificationRejectionRecord != null) return false // Never overwrite an earlier diagnostic.
                    val pins = JSONArray()
                    val report = JSONObject().put("kind", "cached_failed_input_native_diagnostic_only")
                        .put("status", "NOT_RETAINED").put("reason", reason).put("artifacts", pins)
                        .put("diagnostic_only", true).put("accepted_stage", false).put("reference_accepted", false)
                        .put("draft_cleanup_authority", false).put("core_caller_until_nanos", deadlineNanos.toString())
                        .put("persistence_attempt_nanos", SystemClock.elapsedRealtimeNanos().toString())
                    qualificationRejectionRecord = report
                    if (cached == null) {
                        report.put("retention_failure", "NO_MATCHING_RETURNED_FAILED_INPUT")
                        return false
                    }
                    val until = minOf(deadlineNanos, cached.nativeUntil, preparationDeadline)
                    report.put("native_input_until_nanos", cached.nativeUntil.toString())
                        .put("preparation_until_nanos", preparationDeadline.toString())
                        .put("diagnostic_until_nanos", until.toString())
                        .put("observation_elapsed_realtime_nanos", cached.observationNanos.toString())
                        .put("operation", cached.operation)
                    return try {
                        check(observed === cached.expected && editor === cached.editor &&
                            cached.expected.nodes.singleOrNull { it.id == editor.id } === editor &&
                            cached.actual.nodes.singleOrNull { it.visible && it.editable && it.packageName == TARGET } === cached.input) {
                            "FAILED_INPUT_CACHE_BINDING_MISMATCH"
                        }
                        gate(until)
                        // Pure immutable-value serialization only. No project/owner/handles/XML/PNG or UI action.
                        val expected = JSONObject(String(preparationNodeProjection(cached.expected), Charsets.UTF_8))
                        val actual = JSONObject(String(preparationNodeProjection(cached.actual), Charsets.UTF_8))
                        val value = JSONObject().put("kind", "public_Android_Native_cached_failed_input_unaccepted")
                            .put("diagnostic_only", true).put("accepted_stage", false).put("reference_accepted", false)
                            .put("draft_cleanup_authority", false).put("reason", reason).put("operation", cached.operation)
                            .put("observation_elapsed_realtime_nanos", cached.observationNanos.toString())
                            .put("core_caller_until_nanos", deadlineNanos.toString())
                            .put("native_input_until_nanos", cached.nativeUntil.toString())
                            .put("diagnostic_until_nanos", until.toString())
                            .put("actual_native_values", actual).put("expected_native_values", expected)
                            .put("actual_input", actual.getJSONArray("nodes").getJSONObject(cached.actual.nodes.indexOf(cached.input)))
                            .put("expected_editor", expected.getJSONArray("nodes").getJSONObject(cached.expected.nodes.indexOf(cached.editor)))
                            .put("sampling_claim", "already_returned_failed_readback_no_new_query_no_xml_no_png_not_accepted_stage")
                        val raw = (value.toString(2) + "\n").toByteArray(Charsets.UTF_8)
                        report.put("native_write_attempted", true).put("native_path", "failed-input-native.json")
                        preserveAcquired("failed-input-native.json", raw, until)
                        pins.put(JSONObject().put("path", "failed-input-native.json").put("bytes", raw.size).put("sha256", sha(raw)))
                        val receipt = JSONObject(report.toString()).apply {
                            remove("status")
                            put("native_original_readback_complete", true)
                        }
                        val receiptRaw = (receipt.toString(2) + "\n").toByteArray(Charsets.UTF_8)
                        report.put("receipt_write_attempted", true).put("receipt_path", "failed-input-retention.json")
                        preserveAcquired("failed-input-retention.json", receiptRaw, until)
                        report.put("receipt", JSONObject().put("path", "failed-input-retention.json")
                            .put("bytes", receiptRaw.size).put("sha256", sha(receiptRaw))).put("status", "RETAINED")
                        true
                    } catch (failure: Exception) {
                        report.put("retention_failure", failure.javaClass.simpleName + ":" + (failure.message ?: "UNKNOWN"))
                        false
                    }
                }
                override fun observeControlFailure(observation: TongyiUiReferenceQualificationRunner.GateFailureObservation) {
                    if (qualificationControlFailure == null) qualificationControlFailure = observation
                }
                override fun sleepMillis(milliseconds: Long) = SystemClock.sleep(milliseconds)
                override fun cleanupOwned(): Boolean {
                    failedInput = null
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
            fun values(nodes: List<AccessibilityNodeInfo>) = preparationNodeValues(nodes)
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

        private fun nodes(deadlineNanos: Long = Long.MAX_VALUE,
            clearOwnerScope: QualificationOwnerProbeScope? = null): List<AccessibilityNodeInfo> {
            requireOwnedCurrent(deadlineNanos, clearOwnerScope)
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
        private fun click(name: String, expectedProjection: TongyiProjectionCaptureService.Session? = null) {
            val node = checkNotNull(matching(name).singleOrNull()) { "CURRENT_TARGET_NOT_UNIQUE_$name" }
            check(node.isEnabled && node.isClickable) { "ANDROID_CLICK_REFUSED_$name" }
            if (name == "send") {
                check(projectionSession === expectedProjection) { "CURRENT_CAPTURE_SESSION_CHANGED" }
                expectedProjection?.requireRecordingActive()
            }
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "ANDROID_CLICK_REFUSED_$name"
            }
        }
        private fun editNodeOrNull(deadlineNanos: Long = Long.MAX_VALUE,
            clearOwnerScope: QualificationOwnerProbeScope? = null): AccessibilityNodeInfo? = nodes(deadlineNanos, clearOwnerScope).filter {
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
        private const val PAYLOAD_SHA = "f1e312c9123478fcf64c9ad056a436a493f6bd5dde09af029fc8ad62186efac5"
        private fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
