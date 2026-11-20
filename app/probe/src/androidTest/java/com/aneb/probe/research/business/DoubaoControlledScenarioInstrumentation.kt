package com.aneb.probe.research.business

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaExtractor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Xml
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.aneb.probe.BuildConfig
import com.aneb.probe.research.engineering.OwnedProcessLifecycle
import com.aneb.probe.research.engineering.ProjectionConsentHelper
import com.aneb.probe.research.engineering.TongyiProjectionCaptureService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.StringWriter
import java.security.MessageDigest

/** Explicit Debug instrumentation for Doubao (com.larus.nova) controlled R/B capture via MediaProjection 30fps. */
@RunWith(AndroidJUnit4::class)
class DoubaoControlledScenarioInstrumentation {
    @Test fun runOneProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(
            BuildConfig.DEBUG &&
                arguments.getString("feature_release") == "video-business-profile-1" &&
                arguments.getString("live_authorized") == "true",
        ) { "EXPLICIT_DOUBAO_CONTROLLED_RELEASE_REQUIRED" }

        val mode = arguments.getString("execution_mode")
        check(mode == "CONTROLLED_CONDITION_RUN") { "DOUBAO_MODE_UNSUPPORTED" }
        val runId = checkNotNull(arguments.getString("run_id"))
        check(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        check(arguments.getString("max_gestures") == "1")

        val source = instrumentation.targetContext.assets.open("business-profiles/doubao-chat-single-send-v1.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val plan = DoubaoScenarioProfile.compile(source)
        check(arguments.getString("profile_sha256") == plan.profileSourceSha256) { "DOUBAO_PROFILE_PIN_MISMATCH" }

        val version = checkNotNull(arguments.getString("app_version_code")?.toLongOrNull()?.takeIf { it > 0 })
        val parent = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "video-business").canonicalFile
        check(parent.isDirectory || parent.mkdirs())
        val root = File(parent, runId)

        val uiText = checkNotNull(
            arguments.getString("ui_binding_b64")?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) },
        ) { "DOUBAO_UI_BINDING_REQUIRED" }
        check(sha(uiText.toByteArray(Charsets.UTF_8)) == arguments.getString("ui_binding_sha256")) {
            "DOUBAO_UI_BINDING_SHA_MISMATCH"
        }
        val observationMethod = checkNotNull(arguments.getString("ui_observation_method")?.takeIf { it.isNotBlank() })
        val network = checkNotNull(arguments.getString("network_key")?.takeIf { it.isNotBlank() })

        val conditionId = checkNotNull(arguments.getString("condition_id"))
        check(conditionId in setOf("R", "B")) { "DOUBAO_CONDITION_ID_INVALID" }
        val hostMode = checkNotNull(arguments.getString("host_condition_mode"))
        check((conditionId == "R" && hostMode == "inspect") || (conditionId == "B" && hostMode == "limit")) {
            "DOUBAO_CONDITION_MODE_MISMATCH"
        }
        val hostCondition = VideoHostNetworkBinding(
            topologyId = checkNotNull(arguments.getString("host_topology_id")?.takeIf { it.isNotBlank() }),
            conditionMode = hostMode,
            requestSha256 = checkNotNull(arguments.getString("host_request_sha256")),
            uplinkInterfaceIndex = arguments.getString("host_uplink_if_index")?.toIntOrNull(),
            apInterfaceIndex = arguments.getString("host_ap_if_index")?.toIntOrNull(),
            downlinkIpBytesPerSecond = arguments.getString("host_downlink_ip_bytes_per_second")?.toLongOrNull(),
        )
        val confirmVisibleConsent = arguments.getString("confirm_visible_projection_consent") == "true"
        val phone = NativeDoubaoPhone(
            instrumentation = instrumentation,
            root = root,
            expectedVersion = version,
            uiText = uiText,
            expectedNetworkKey = network,
            expectedPrompt = plan.definition.prompt,
            confirmVisibleProjectionConsent = confirmVisibleConsent,
        )
        val clockId = "android-elapsed:$runId"
        val binding = DoubaoRunBinding(
            runId = runId,
            profileSha256 = plan.profileSourceSha256,
            appVersionCode = version,
            conditionId = conditionId,
            networkKey = network,
            clockId = clockId,
            uiObservationMethod = observationMethod,
            hostNetworkCondition = hostCondition,
        )
        val result = DoubaoControlledScenario(phone, SystemClock::elapsedRealtimeNanos).runOnce(source, binding, root)
        check(result.ownedCleanup) { "DOUBAO_OWNED_CLEANUP_UNCONFIRMED" }
        check(result.state == "CAPTURED_AWAITING_REVIEW") {
            "DOUBAO_CAPTURE_STOPPED:${result.category}:${result.reason}"
        }
    }

    companion object {
        internal const val PACKAGE = "com.larus.nova"
        internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        internal fun writeJson(root: File, name: String, value: JSONObject) {
            writeOriginal(root, name, value.toString(2) + "\n")
        }
        internal fun writeOriginal(root: File, name: String, value: String) {
            val file = File(root, name)
            check(file.createNewFile()) { "DOUBAO_ORIGINAL_ALREADY_EXISTS" }
            FileOutputStream(file).use { it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
    }
}

private class NativeDoubaoPhone(
    private val instrumentation: Instrumentation,
    private val root: File,
    private val expectedVersion: Long,
    private val uiText: String,
    private val expectedNetworkKey: String,
    private val expectedPrompt: String,
    private val confirmVisibleProjectionConsent: Boolean,
) : DoubaoControlledScenario.Phone {
    private val context: Context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val ui = JSONObject(uiText)
    private val selectors = ui.getJSONObject("selectors")
    private val editTextId = selectors.getString("edit_text_id")
    private val sendButtonId = selectors.getString("send_button_id")
    private val sendButtonDesc = selectors.getString("send_button_desc")
    private val newChatIdContains = selectors.getString("new_chat_id_contains")
    private val packageName = DoubaoControlledScenarioInstrumentation.PACKAGE
    private val shell by lazy {
        VideoOwnerShell(automation, root.canonicalFile, SystemClock::elapsedRealtimeNanos, allowedPackage = packageName)
    }

    private var owner: OwnedProcessLifecycle.Owner? = null
    private var launched = false
    private var serviceFlags: Int? = null
    private var projection: TongyiProjectionCaptureService.Session? = null
    private var preparationEnd = 0L
    private var recordingClosed = false

    override fun prepare(deadlineNs: Long) {
        preparationEnd = deadlineNs
        check(ui.getString("package_name") == packageName) { "DOUBAO_UI_BINDING_PACKAGE_MISMATCH" }
        check(ui.getLong("app_version_code") == expectedVersion) { "DOUBAO_UI_BINDING_VERSION_MISMATCH" }
        val initial = automation.rootInActiveWindow?.packageName?.toString()
        check(initial == "com.huawei.android.launcher") { "DOUBAO_LAUNCHER_NOT_FOCUSED_UNKNOWN_SESSION_UNTOUCHED" }

        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.longVersionCode == expectedVersion) { "DOUBAO_APP_VERSION_CHANGED" }
        check(networkKey() == expectedNetworkKey) { "DOUBAO_REFERENCE_NETWORK_CHANGED_BEFORE_PREPARATION" }
        shell.requirePackageAbsent(packageName, deadlineNs)

        val access = automation.serviceInfo
        serviceFlags = access.flags
        access.flags = access.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            if (confirmVisibleProjectionConsent) AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS else 0
        automation.serviceInfo = access

        val appInfo = checkNotNull(info.applicationInfo)
        val signatures = JSONArray()
        info.signingInfo?.apkContentsSigners?.forEach {
            signatures.put(DoubaoControlledScenarioInstrumentation.sha(it.toByteArray()))
        }
        write(
            "app-identity.json",
            JSONObject()
                .put("package", packageName)
                .put("version_code", info.longVersionCode)
                .put("version_name", info.versionName)
                .put("uid", appInfo.uid)
                .put("certificate_sha256", signatures)
                .put("account_state", "UNKNOWN_NOT_REAUTHENTICATED")
                .put("cache_state", "UNKNOWN_NOT_CLEARED"),
        )
        DoubaoControlledScenarioInstrumentation.writeOriginal(root, "ui-binding.json", uiText)

        // Acquire MediaProjection consent while Launcher is foregrounded.
        val session = TongyiProjectionCaptureService.createSession(context, root, preparationEnd)
        projection = session
        session.launchConsent()
        if (confirmVisibleProjectionConsent) {
            ProjectionConsentHelper.confirmVisibleProjectionConsentOnce(
                automation,
                session,
                minOf(preparationEnd, SystemClock.elapsedRealtimeNanos() + 20_000_000_000L),
                ::write,
            )
        }
        waitUntil(preparationEnd) { session.requireHealthy(); session.grantReady && session.activityClosed }
        waitUntil(minOf(preparationEnd, SystemClock.elapsedRealtimeNanos() + 5_000_000_000L)) {
            automation.rootInActiveWindow?.packageName?.toString() == "com.huawei.android.launcher"
        }
        check(automation.rootInActiveWindow?.packageName?.toString() == "com.huawei.android.launcher") {
            "DOUBAO_UNEXPECTED_FOREGROUND_AFTER_CONSENT"
        }

        // Cold-launch Doubao
        val launchIntent = checkNotNull(context.packageManager.getLaunchIntentForPackage(packageName)) {
            "DOUBAO_APP_LAUNCH_UNAVAILABLE"
        }
        launched = true
        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val launchEnd = minOf(deadlineNs, SystemClock.elapsedRealtimeNanos() + 15_000_000_000L)
        while (SystemClock.elapsedRealtimeNanos() < launchEnd) {
            owner = shell.findPackageOwner(packageName, appInfo.uid, launchEnd)
            if (owner != null && automation.rootInActiveWindow?.packageName?.toString() == packageName) break
            SystemClock.sleep(100L)
        }
        check(owner != null) { "DOUBAO_STARTED_PROCESS_OWNER_UNCONFIRMED" }

        // Wait for EditText; dismiss promo overlay via BACK if present.
        var editNode = findVisibleEditor()
        val uiReadyEnd = minOf(deadlineNs, SystemClock.elapsedRealtimeNanos() + 12_000_000_000L)
        var backAttempts = 0
        val waitStarted = SystemClock.elapsedRealtimeNanos()
        while (editNode == null && SystemClock.elapsedRealtimeNanos() < uiReadyEnd) {
            check(!File(root, "CANCEL").exists()) { "CANCELLED_NO_RETRY" }
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - waitStarted) / 1_000_000L
            if (elapsedMs > 1_500L && backAttempts < 2 &&
                automation.rootInActiveWindow?.packageName?.toString() == packageName
            ) {
                backAttempts++
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                SystemClock.sleep(600L)
            } else {
                SystemClock.sleep(250L)
            }
            editNode = findVisibleEditor()
        }
        check(editNode != null) { "DOUBAO_EDIT_TEXT_NOT_VISIBLE" }

        // Tap New Chat if visible so both R and B start on a clean conversation.
        val newChatNode = findVisibleNode { node ->
            node.viewIdResourceName?.contains(newChatIdContains) == true && node.isClickable && node.isEnabled
        }
        if (newChatNode != null) {
            val ncBox = Rect().also(newChatNode::getBoundsInScreen)
            if (ncBox.width() > 0 && ncBox.height() > 0) {
                tapCenter(ncBox)
                SystemClock.sleep(1_000L)
                val freshEnd = minOf(deadlineNs, SystemClock.elapsedRealtimeNanos() + 5_000_000_000L)
                while (SystemClock.elapsedRealtimeNanos() < freshEnd) {
                    if (findVisibleEditor() != null) break
                    SystemClock.sleep(150L)
                }
            }
        }

        val targetEditor = checkNotNull(findVisibleEditor()) { "DOUBAO_EDIT_TEXT_MISSING_AFTER_NEW_CHAT" }
        val edBox = Rect().also(targetEditor::getBoundsInScreen)
        check(edBox.width() > 0 && edBox.height() > 0) { "DOUBAO_EDIT_TEXT_EMPTY_BOUNDS" }
        tapCenter(edBox)
        SystemClock.sleep(700L)

        val focusedEditor = checkNotNull(findVisibleEditor()) { "DOUBAO_EDIT_TEXT_LOST_AFTER_TAP" }
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedPrompt)
        }
        check(focusedEditor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
            "DOUBAO_ACTION_SET_TEXT_REFUSED"
        }
        SystemClock.sleep(600L)

        var readbackOk = false
        val readbackEnd = minOf(deadlineNs, SystemClock.elapsedRealtimeNanos() + 4_000_000_000L)
        while (SystemClock.elapsedRealtimeNanos() < readbackEnd) {
            val current = findVisibleEditor()
            if (current != null && current.text?.toString() == expectedPrompt && findUniqueSendButton() != null) {
                readbackOk = true
                break
            }
            SystemClock.sleep(100L)
        }
        check(readbackOk) { "DOUBAO_DRAFT_READBACK_OR_SEND_BUTTON_UNCONFIRMED" }
    }

    override fun startRecording(deadlineNs: Long) {
        val session = checkNotNull(projection) { "DOUBAO_PREPARATION_CONSENT_REQUIRED" }
        check(session.grantReady && session.activityClosed) { "DOUBAO_PREPARATION_CONSENT_NOT_CLOSED" }
        val display = display()
        val readyEnd = minOf(preparationEnd, deadlineNs)
        check(SystemClock.elapsedRealtimeNanos() < readyEnd) { "DOUBAO_RECORDING_READY_DEADLINE_EXPIRED" }

        session.startRecording(display.first, display.second, context.resources.configuration.densityDpi, readyEnd)
        val video = File(root, "video.mp4")
        waitUntil(readyEnd) {
            session.requireHealthy()
            session.recordingStarted && video.isFile && video.length() >= 64L && runCatching {
                video.inputStream().use { input ->
                    val header = ByteArray(12)
                    input.read(header) == 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                }
            }.getOrDefault(false)
        }
        val previousLength = video.length()
        waitUntil(readyEnd) {
            session.requireRecordingActive()
            video.length() > previousLength
        }
        session.requireRecordingActive()
        write(
            "OWNED_PROJECTION_CAPTURE.json",
            JSONObject()
                .put("backend", "android_media_projection_v1")
                .put("run_id", root.name)
                .put("session_id", session.id)
                .put("owner_package", context.packageName)
                .put("video", video.name)
                .put("width", display.first)
                .put("height", display.second)
                .put("density_dpi", context.resources.configuration.densityDpi)
                .put("fps_target", 30)
                .put("ready_file_bytes", video.length())
                .put("ready_elapsed_ns", SystemClock.elapsedRealtimeNanos().toString()),
        )
    }

    override fun saveOriginals(label: String) {
        val bitmap = automation.takeScreenshot()
        if (bitmap != null) {
            val pngFile = File(root, "$label.png")
            check(pngFile.createNewFile()) { "DOUBAO_ORIGINAL_SCREENSHOT_EXISTS:$label" }
            try {
                FileOutputStream(pngFile).use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    out.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
        }
        val rootNode = automation.rootInActiveWindow
        if (rootNode != null) {
            val xmlFile = File(root, "$label.xml")
            check(xmlFile.createNewFile()) { "DOUBAO_ORIGINAL_XML_EXISTS:$label" }
            val xmlText = serializeHierarchy(rootNode)
            FileOutputStream(xmlFile).use { out ->
                out.write(xmlText.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }
    }

    override fun sendOnce(): Boolean {
        val session = checkNotNull(projection) { "DOUBAO_PROJECTION_REQUIRED" }
        session.requireRecordingActive()
        check(automation.rootInActiveWindow?.packageName?.toString() == packageName) {
            "DOUBAO_FOREGROUND_CHANGED_BEFORE_SEND"
        }
        val editor = checkNotNull(findVisibleEditor()) { "DOUBAO_EDITOR_MISSING_BEFORE_SEND" }
        check(editor.text?.toString() == expectedPrompt) { "DOUBAO_DRAFT_DRIFT_BEFORE_SEND" }
        val sendNode = checkNotNull(findUniqueSendButton()) { "DOUBAO_SEND_BUTTON_MISSING_BEFORE_SEND" }
        val sendBox = Rect().also(sendNode::getBoundsInScreen)
        check(sendBox.width() > 0 && sendBox.height() > 0) { "DOUBAO_SEND_BUTTON_EMPTY_BOUNDS" }

        val started = SystemClock.elapsedRealtimeNanos()
        val tapped = tapCenter(sendBox)
        val returned = SystemClock.elapsedRealtimeNanos()
        write(
            "send-tap.json",
            JSONObject()
                .put("started_elapsed_ns", started.toString())
                .put("returned_elapsed_ns", returned.toString())
                .put("send_bounds", JSONArray(listOf(sendBox.left, sendBox.top, sendBox.right, sendBox.bottom)))
                .put("tap_x", sendBox.exactCenterX())
                .put("tap_y", sendBox.exactCenterY())
                .put("injection_returned_true", tapped),
        )
        return tapped
    }

    override fun observeUntil(deadlineNs: Long) {
        val session = checkNotNull(projection)
        saveOriginals("after-send")
        while (SystemClock.elapsedRealtimeNanos() < deadlineNs) {
            session.requireRecordingActive()
            check(automation.rootInActiveWindow?.packageName?.toString() == packageName) {
                "DOUBAO_FOREGROUND_LOST_DURING_OBSERVATION"
            }
            if (File(root, "CANCEL").exists()) throw VideoOperationCancelled()
            SystemClock.sleep(100L)
        }
        saveOriginals("capture-end")
    }

    override fun stopRecording() {
        val session = checkNotNull(projection)
        val closureDeadline = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
        session.close()
        waitUntil(closureDeadline, ignoreCancel = true) { session.isClosed() }
        if (session.mediaRequired) {
            sealFinalMedia()
        }
        write(
            "PROJECTION_CAPTURE_CLOSED.json",
            JSONObject()
                .put("backend", "android_media_projection_v1")
                .put("run_id", root.name)
                .put("session_id", session.id)
                .put("resources_closed", true)
                .put("encoder_start_returned", session.recordingStarted)
                .put("media_required", session.mediaRequired),
        )
        session.releaseClosedSession()
        recordingClosed = true
    }

    override fun cleanupOwned(deadlineNs: Long): Boolean {
        var ok = true
        projection?.let { session ->
            if (!recordingClosed) {
                try {
                    session.close()
                    waitUntil(deadlineNs, ignoreCancel = true) { session.isClosed() }
                    session.releaseClosedSession()
                    recordingClosed = true
                } catch (_: Exception) {
                    ok = false
                }
            }
        }
        try {
            val known = owner
            if (known != null) ok = shell.stopOwnedPackage(known, packageName, deadlineNs) && ok
            else if (launched) shell.requirePackageAbsent(packageName, deadlineNs)
        } catch (_: Exception) {
            ok = false
        }
        try {
            serviceFlags?.let { flags ->
                val info = automation.serviceInfo
                info.flags = flags
                automation.serviceInfo = info
            }
            val foreground = automation.rootInActiveWindow?.packageName?.toString()
            if ((launched || projection != null) && foreground in setOf(packageName, context.packageName)) {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                waitUntil(deadlineNs, ignoreCancel = true) {
                    automation.rootInActiveWindow?.packageName?.toString() == "com.huawei.android.launcher"
                }
            } else if (foreground != "com.huawei.android.launcher") {
                ok = false
            }
            ok = shell.readersClosed() && ok
        } catch (_: Exception) {
            ok = false
        }
        write(
            "native-cleanup.json",
            JSONObject()
                .put("owned_launch_invoked", launched)
                .put("known_main_pid", owner?.pid ?: JSONObject.NULL)
                .put("projection_closed", projection == null || recordingClosed)
                .put("owned_cleanup", ok)
                .put("global_clean", "NOT_CLAIMED_REQUIRES_ROOT_POSTFLIGHT")
                .put("settings_changed", false),
        )
        return ok
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
            write(
                "FINAL_MEDIA.json",
                JSONObject()
                    .put("actual_video_track", track)
                    .put("sample_count", samples)
                    .put("first_presentation_time_us", first)
                    .put("last_presentation_time_us", last)
                    .put("pts_nondecreasing", nondecreasing)
                    .put("clock_domain", "original_MP4_MediaExtractor_presentation_microseconds"),
            )
        } finally {
            extractor.release()
        }
    }

    private fun findVisibleNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val rootNode = automation.rootInActiveWindow ?: return null
        if (rootNode.packageName?.toString() != packageName) return null
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 64 || visited >= 2048) return null
            visited++
            if (!node.isVisibleToUser) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)?.let { return it }
            }
            return null
        }
        return walk(rootNode, 0)
    }

    private fun findVisibleEditor(): AccessibilityNodeInfo? =
        findVisibleNode { node ->
            node.viewIdResourceName == editTextId &&
                node.className?.toString() == "android.widget.EditText" &&
                node.isEnabled
        }

    private fun findUniqueSendButton(): AccessibilityNodeInfo? {
        val rootNode = automation.rootInActiveWindow ?: return null
        if (rootNode.packageName?.toString() != packageName) return null
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 64 || visited >= 2048) return
            visited++
            if (!node.isVisibleToUser) return
            val idMatch = node.viewIdResourceName == sendButtonId
            val descMatch = node.contentDescription?.toString() == sendButtonDesc
            if ((idMatch || descMatch) && node.isClickable && node.isEnabled) {
                matches += node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(rootNode, 0)
        return matches.singleOrNull()
    }

    private fun tapCenter(box: Rect): Boolean {
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int): Boolean {
            val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, cx, cy, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            return try {
                automation.injectInputEvent(ev, true)
            } finally {
                ev.recycle()
            }
        }
        if (!inject(MotionEvent.ACTION_DOWN)) return false
        SystemClock.sleep(60L)
        return inject(MotionEvent.ACTION_UP)
    }

    private fun serializeHierarchy(rootNode: AccessibilityNodeInfo): String {
        val writer = StringWriter()
        val serializer = Xml.newSerializer()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)
        serializer.startTag("", "hierarchy")
        serializer.attribute("", "rotation", rotation().toString())
        fun writeNode(node: AccessibilityNodeInfo, index: Int, depth: Int) {
            if (depth > 64) return
            val box = Rect().also(node::getBoundsInScreen)
            serializer.startTag("", "node")
            serializer.attribute("", "index", index.toString())
            serializer.attribute("", "text", node.text?.toString().orEmpty())
            serializer.attribute("", "resource-id", node.viewIdResourceName.orEmpty())
            serializer.attribute("", "class", node.className?.toString().orEmpty())
            serializer.attribute("", "package", node.packageName?.toString().orEmpty())
            serializer.attribute("", "content-desc", node.contentDescription?.toString().orEmpty())
            serializer.attribute("", "clickable", node.isClickable.toString())
            serializer.attribute("", "enabled", node.isEnabled.toString())
            serializer.attribute("", "focused", node.isFocused.toString())
            serializer.attribute("", "bounds", "[${box.left},${box.top}][${box.right},${box.bottom}]")
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { writeNode(it, i, depth + 1) }
            }
            serializer.endTag("", "node")
        }
        writeNode(rootNode, 0, 0)
        serializer.endTag("", "hierarchy")
        serializer.endDocument()
        return writer.toString()
    }

    private fun networkKey(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = checkNotNull(cm.activeNetwork) { "DOUBAO_DEFAULT_NETWORK_MISSING" }
        check(cm.allNetworks.none { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
            "DOUBAO_ACTIVE_VPN_NOT_ALLOWED"
        }
        val caps = checkNotNull(cm.getNetworkCapabilities(active))
        check(
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
        ) { "DOUBAO_REFERENCE_DEFAULT_WIFI_NOT_QUALIFIED" }
        val iface = checkNotNull(cm.getLinkProperties(active)?.interfaceName?.takeIf { it.isNotBlank() }) {
            "DOUBAO_DEFAULT_INTERFACE_UNKNOWN"
        }
        return "wifi-default:$active:$iface"
    }

    @Suppress("DEPRECATION")
    private fun display(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation

    private fun waitUntil(deadlineNs: Long, ignoreCancel: Boolean = false, condition: () -> Boolean) {
        while (true) {
            check(SystemClock.elapsedRealtimeNanos() < deadlineNs && (ignoreCancel || !File(root, "CANCEL").exists())) {
                "DOUBAO_DEADLINE_OR_CANCEL"
            }
            if (condition()) return
            SystemClock.sleep(50L)
        }
    }

    private fun write(name: String, value: JSONObject) =
        DoubaoControlledScenarioInstrumentation.writeJson(root, name, value)
}
