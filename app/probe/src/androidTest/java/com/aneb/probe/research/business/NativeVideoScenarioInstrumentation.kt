package com.aneb.probe.research.business

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.aneb.probe.BuildConfig
import com.aneb.probe.research.engineering.OwnedProcessLifecycle
import com.aneb.probe.research.engineering.TongyiProjectionCaptureService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Explicit Debug instrumentation only. Normal build/unit-test gates never launch this entry. */
@RunWith(AndroidJUnit4::class)
class NativeVideoScenarioInstrumentation {
    @Test fun runOneProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(BuildConfig.DEBUG && arguments.getString("feature_release") == "video-business-profile-1" &&
            arguments.getString("live_authorized") == "true") { "EXPLICIT_NEW_VIDEO_RELEASE_REQUIRED" }
        val mode = arguments.getString("execution_mode")
        check(mode in setOf("SOURCE_PREPARATION", "REFERENCE_QUALIFICATION")) { "VIDEO_MODE_UNSUPPORTED" }
        val runId = checkNotNull(arguments.getString("run_id"))
        check(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        check(arguments.getString("max_gestures") == if (mode == "SOURCE_PREPARATION") "0" else "1")
        val source = instrumentation.targetContext.assets.open("business-profiles/douyin-feed-single-switch-v1.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val plan = VideoScenarioProfile.compile(source)
        check(arguments.getString("profile_sha256") == plan.profileSourceSha256) { "VIDEO_PROFILE_PIN_MISMATCH" }
        val version = checkNotNull(arguments.getString("app_version_code")?.toLongOrNull()?.takeIf { it > 0 })
        val parent = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "video-business").canonicalFile
        check(parent.isDirectory || parent.mkdirs())
        val root = File(parent, runId)
        val uiText = arguments.getString("ui_binding_b64")?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
        if (mode == "REFERENCE_QUALIFICATION") {
            check(uiText != null && sha(uiText.toByteArray()) == arguments.getString("ui_binding_sha256")) { "VIDEO_UI_BINDING_REQUIRED" }
        }
        val network = if (mode == "REFERENCE_QUALIFICATION")
            checkNotNull(arguments.getString("network_key")?.takeIf { it.isNotBlank() }) else null
        val phone = NativeVideoPhone(instrumentation, root, version, uiText, network)
        val clockId = "android-elapsed:$runId"
        if (mode == "SOURCE_PREPARATION") {
            check(root.mkdir()) { "VIDEO_SOURCE_OPPORTUNITY_ALREADY_CONSUMED" }
            writeJson(root, "profile-source.json", JSONObject(source))
            var clean = false
            var failure: String? = null
            try {
                val page = phone.prepare(SystemClock.elapsedRealtimeNanos() + 360_000_000_000L)
                if (page.packageName == PACKAGE) phone.sourceScreenshot()
                else failure = "SOURCE_TARGET_NOT_VISIBLE"
            } catch (error: Exception) {
                failure = error.javaClass.simpleName + ":" + error.message.orEmpty()
            } finally {
                clean = phone.cleanupOwned(SystemClock.elapsedRealtimeNanos() + 210_000_000_000L)
                writeJson(root, "source-terminal.json", JSONObject().put("format", "video-profile-source-1")
                    .put("run_id", runId).put("clock_id", clockId).put("gestures", 0)
                    .put("state", if (failure == null) "SOURCE_RETAINED_NOT_SEMANTIC_ADMISSION" else "SOURCE_STOPPED")
                    .put("reference_accepted", false)
                    .put("sample_executed", false).put("metrics", JSONObject.NULL)
                    .put("owned_cleanup", clean).put("failure", failure ?: JSONObject.NULL))
            }
            check(clean) { "VIDEO_SOURCE_CLEANUP_UNCONFIRMED" }
            check(failure == null) { "VIDEO_SOURCE_STOPPED" }
            return
        }
        val binding = VideoRunBinding(runId, plan.profileSourceSha256, version, "QUAL_REFERENCE", checkNotNull(network), clockId)
        val result = VideoScenarioExecution(phone, SystemClock::elapsedRealtimeNanos).runOnce(source, binding, root)
        check(result.ownedCleanup) { "VIDEO_OWNED_CLEANUP_UNCONFIRMED" }
        // CAPTURED means pending independent media/content review, not successful playback or a valid metric.
        check(result.state == "CAPTURED_AWAITING_REVIEW") { "VIDEO_REFERENCE_STOPPED:${result.category}:${result.reason}" }
    }

    companion object {
        internal const val PACKAGE = "com.ss.android.ugc.aweme"
        internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        internal fun writeJson(root: File, name: String, value: JSONObject) {
            writeOriginal(root, name, value.toString(2) + "\n")
        }
        internal fun writeOriginal(root: File, name: String, value: String) {
            val file = File(root, name)
            check(file.createNewFile()) { "VIDEO_ORIGINAL_ALREADY_EXISTS" }
            FileOutputStream(file).use { it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
    }
}

/** One concrete on-phone adapter. No SEND/like/follow/purchase or network-setting methods exist. */
private class NativeVideoPhone(
    private val instrumentation: Instrumentation,
    private val root: File,
    private val expectedVersion: Long,
    private val uiText: String?,
    private val expectedNetworkKey: String?,
) : VideoScenarioExecution.Phone {
    private val context: Context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val ui = uiText?.let(::JSONObject)
    private val shell by lazy { VideoOwnerShell(automation, root.canonicalFile, SystemClock::elapsedRealtimeNanos) }
    private var owner: OwnedProcessLifecycle.Owner? = null
    private var launched = false
    private var serviceFlags: Int? = null
    private var projection: TongyiProjectionCaptureService.Session? = null
    private var preparationEnd = 0L
    private var readSequence = 0
    private var gestureConsumed = false
    private var inputAttempted = false
    private var expectedViewport: VideoViewport? = null
    private var recordingClosed = false
    private val packageName = NativeVideoScenarioInstrumentation.PACKAGE

    override fun prepare(deadlineNs: Long): VideoPage {
        preparationEnd = deadlineNs
        val initial = automation.rootInActiveWindow?.packageName?.toString()
        check(initial == "com.huawei.android.launcher") { "VIDEO_LAUNCHER_NOT_FOCUSED_UNKNOWN_SESSION_UNTOUCHED" }
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.longVersionCode == expectedVersion) { "VIDEO_APP_VERSION_CHANGED" }
        expectedNetworkKey?.let { check(networkKey() == it) { "VIDEO_REFERENCE_NETWORK_CHANGED_BEFORE_PREPARATION" } }
        shell.requirePackageAbsent(packageName, deadlineNs)
        val access = automation.serviceInfo
        serviceFlags = access.flags
        access.flags = access.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = access
        val appInfo = checkNotNull(info.applicationInfo)
        val signatures = JSONArray()
        info.signingInfo?.apkContentsSigners?.forEach { signatures.put(NativeVideoScenarioInstrumentation.sha(it.toByteArray())) }
        write("app-identity.json", JSONObject().put("package", packageName).put("version_code", info.longVersionCode)
            .put("version_name", info.versionName).put("uid", appInfo.uid).put("certificate_sha256", signatures)
            .put("account_state", "UNKNOWN_NOT_REAUTHENTICATED").put("cache_state", "UNKNOWN_NOT_CLEARED"))
        ui?.let {
            check(it.getLong("app_version_code") == expectedVersion)
            check(it.getInt("display_width") > 0 && it.getInt("display_height") > 0 && it.getInt("rotation") in 0..3)
            expectedViewport = VideoUiAdmission.compile(checkNotNull(uiText))
            NativeVideoScenarioInstrumentation.writeOriginal(root, "ui-binding.json", uiText)
        }
        if (ui != null) {
            // The normal OS grant belongs to preparation, not the separate 20-second encoder-ready budget.
            val session = TongyiProjectionCaptureService.createSession(context, root, preparationEnd)
            projection = session
            session.launchConsent()
            waitUntil(preparationEnd) { session.requireHealthy(); session.grantReady && session.activityClosed }
            check(automation.rootInActiveWindow?.packageName?.toString() == "com.huawei.android.launcher") {
                "VIDEO_UNEXPECTED_FOREGROUND_AFTER_CONSENT"
            }
        }
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(packageName)) { "VIDEO_APP_LAUNCH_UNAVAILABLE" }
        launched = true // The launch may be ambiguous; ownership must still be observed before stopping anything.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val launchEnd = minOf(deadlineNs, SystemClock.elapsedRealtimeNanos() + 15_000_000_000L)
        while (SystemClock.elapsedRealtimeNanos() < launchEnd) {
            owner = shell.findPackageOwner(packageName, appInfo.uid, launchEnd)
            if (owner != null && automation.rootInActiveWindow?.packageName?.toString() == packageName) break
            SystemClock.sleep(100L)
        }
        check(owner != null) { "VIDEO_STARTED_PROCESS_OWNER_UNCONFIRMED" }
        // One declared startup settle; no menu traversal or retries to find another eligible item.
        SystemClock.sleep(1_000L)
        check(SystemClock.elapsedRealtimeNanos() < deadlineNs)
        return inspect()
    }

    override fun startRecording(deadlineNs: Long) {
        val session = checkNotNull(projection) { "VIDEO_PREPARATION_CONSENT_REQUIRED" }
        check(session.grantReady && session.activityClosed) { "VIDEO_PREPARATION_CONSENT_NOT_CLOSED" }
        val display = display()
        val readyEnd = minOf(preparationEnd, deadlineNs)
        check(SystemClock.elapsedRealtimeNanos() < deadlineNs) { "VIDEO_RECORDING_READY_DEADLINE_EXPIRED" }
        session.startRecording(display.first, display.second, context.resources.configuration.densityDpi, minOf(deadlineNs, readyEnd))
        val video = File(root, "video.mp4")
        waitUntil(minOf(deadlineNs, readyEnd)) {
            session.requireHealthy()
            session.recordingStarted && video.isFile && video.length() >= 64L
        }
        session.requireRecordingActive()
        write("recording-ready.json", JSONObject().put("session_id", session.id).put("video", video.name)
            .put("ready_elapsed_ns", SystemClock.elapsedRealtimeNanos().toString())
            .put("ready_kind", "operational_recorder_state_and_file_size_not_decoded_frame_or_pts_bridge"))
    }

    override fun inspect(): VideoPage {
        val started = SystemClock.elapsedRealtimeNanos()
        val currentRoot = automation.rootInActiveWindow
        val currentPackage = currentRoot?.packageName?.toString().orEmpty()
        val network = networkKey()
        val pixels = display()
        val rows = JSONArray()
        val visible = mutableListOf<AccessibilityNodeInfo>()
        fun visibleWithinCurrentRoot(node: AccessibilityNodeInfo): Boolean {
            if (currentRoot == null || node.windowId != currentRoot.windowId) return false
            var ancestor: AccessibilityNodeInfo? = node
            repeat(129) {
                val current = ancestor ?: return false
                if (!current.isVisibleToUser) return false
                if (current == currentRoot) return true
                ancestor = current.parent
            }
            error("VIDEO_PUBLIC_PARENT_DEPTH_LIMIT")
        }
        fun walk(node: AccessibilityNodeInfo, parentVisible: Boolean, depth: Int) {
            check(depth <= 128 && rows.length() < 2048) { "VIDEO_PUBLIC_TREE_LIMIT" }
            val isVisible = parentVisible && node.isVisibleToUser
            val bounds = Rect().also(node::getBoundsInScreen)
            rows.put(JSONObject().put("resource_id", node.viewIdResourceName ?: JSONObject.NULL)
                .put("class", node.className?.toString()).put("text", node.text?.toString())
                .put("description", node.contentDescription?.toString()).put("visible", isVisible)
                .put("enabled", node.isEnabled).put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
            if (isVisible && node.packageName?.toString() == packageName) visible += node
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, isVisible, depth + 1) }
        }
        var queryMode = "full_public_tree"
        if (currentPackage == packageName && currentRoot != null) {
            if (!inputAttempted || ui == null) {
                walk(currentRoot, true, 0)
            } else {
                // Do not dump the whole hierarchy before every sampled image. Use current public
                // selector queries; an unsupported description-only contract keeps the full path.
                val selectors = mutableListOf(ui.getJSONObject("feed_anchor"), ui.getJSONObject("gesture_viewport"))
                if (ui.has("loading_marker")) selectors += ui.getJSONObject("loading_marker")
                val blockers = ui.getJSONObject("blockers")
                blockers.keys().forEach { key ->
                    val list = blockers.getJSONArray(key)
                    for (index in 0 until list.length()) selectors += list.getJSONObject(index)
                }
                if (selectors.all { it.optString("resource_id").isNotBlank() || it.optString("text").isNotBlank() }) {
                    queryMode = "current_public_selector_queries_not_full_tree"
                    val observed = selectors.flatMap { selector ->
                        if (selector.optString("resource_id").isNotBlank())
                            currentRoot.findAccessibilityNodeInfosByViewId(selector.getString("resource_id"))
                        else currentRoot.findAccessibilityNodeInfosByText(selector.getString("text"))
                    }.distinct()
                    check(observed.size <= 2048) { "VIDEO_PUBLIC_QUERY_LIMIT" }
                    for (node in observed) {
                        val box = Rect().also(node::getBoundsInScreen)
                        val isVisible = visibleWithinCurrentRoot(node)
                        rows.put(JSONObject().put("resource_id", node.viewIdResourceName).put("class", node.className?.toString())
                            .put("text", node.text?.toString()).put("description", node.contentDescription?.toString())
                            .put("visible", isVisible).put("bounds", JSONArray(listOf(box.left, box.top, box.right, box.bottom))))
                        if (isVisible && node.packageName?.toString() == packageName) visible += node
                    }
                } else walk(currentRoot, true, 0)
            }
        }
        fun matches(selector: JSONObject): List<AccessibilityNodeInfo> {
            val keys = listOf("resource_id", "text", "description")
            check(keys.any { selector.optString(it).isNotBlank() }) { "VIDEO_PUBLIC_SELECTOR_REQUIRED" }
            return visible.filter { node -> keys.all { field -> !selector.has(field) || selector.getString(field) == when (field) {
                "resource_id" -> node.viewIdResourceName.orEmpty()
                "text" -> node.text?.toString().orEmpty()
                else -> node.contentDescription?.toString().orEmpty()
            } } }
        }
        var kind = "UNKNOWN"
        var viewport = VideoViewport(0, 0, 0, 0)
        if (ui != null && currentPackage == packageName) {
            check(pixels.first == ui.getInt("display_width") && pixels.second == ui.getInt("display_height") &&
                rotation() == ui.getInt("rotation")) { "VIDEO_DISPLAY_CHANGED" }
            val blockers = ui.getJSONObject("blockers")
            val blocked = blockers.keys().asSequence().filter { key ->
                val selectors = blockers.getJSONArray(key)
                (0 until selectors.length()).any { matches(selectors.getJSONObject(it)).isNotEmpty() }
            }.toList()
            val region = matches(ui.getJSONObject("gesture_viewport")).singleOrNull()
            if (region != null) {
                val box = Rect().also(region::getBoundsInScreen)
                check(box.left >= 0 && box.top >= 0 && box.right <= pixels.first && box.bottom <= pixels.second && box.width() > 0 && box.height() > 0)
                viewport = VideoViewport(box.left, box.top, box.right, box.bottom)
            }
            kind = when {
                blocked.size > 1 -> "UNKNOWN"
                blocked.size == 1 -> blocked.single()
                viewport != expectedViewport -> "UNKNOWN"
                matches(ui.getJSONObject("feed_anchor")).size == 1 && region != null -> "FEED_READY"
                ui.has("loading_marker") && matches(ui.getJSONObject("loading_marker")).size == 1 -> "VIDEO_LOADING"
                else -> "UNKNOWN"
            }
        }
        val name = "ui-${readSequence++}.json"
        write(name, JSONObject().put("source", "sequential_public_accessibility_not_atomic_or_private_state_proof")
            .put("requested_elapsed_ns", started.toString()).put("returned_elapsed_ns", SystemClock.elapsedRealtimeNanos().toString())
            .put("package", currentPackage).put("window_id", currentRoot?.windowId ?: -1).put("network_key", network)
            .put("query_mode", queryMode)
            .put("admission_scope", ui?.optString("admission_scope") ?: "SOURCE_ONLY_NO_ADMISSION")
            .put("blocker_coverage", ui?.optString("blocker_coverage") ?: "UNASSESSED")
            .put("uncovered_blocker_kinds", ui?.optJSONArray("uncovered_blocker_kinds") ?: JSONObject.NULL)
            .put("viewport_binding_matches", expectedViewport?.let { it == viewport } ?: JSONObject.NULL)
            .put("width", pixels.first).put("height", pixels.second).put("rotation", rotation()).put("kind", kind).put("nodes", rows))
        return VideoPage(currentPackage, context.packageManager.getPackageInfo(packageName, 0).longVersionCode,
            kind, network, viewport, name)
    }

    override fun swipeOnce(swipe: VideoSwipe): Boolean {
        check(!gestureConsumed) { "VIDEO_GESTURE_ALREADY_CONSUMED" }
        gestureConsumed = true
        val session = checkNotNull(projection)
        session.requireRecordingActive()
        val expectedOwner = checkNotNull(owner)
        val currentOwner = shell.findPackageOwner(packageName, expectedOwner.uid, minOf(preparationEnd, SystemClock.elapsedRealtimeNanos() + 10_000_000_000L))
        check(currentOwner == expectedOwner) { "VIDEO_GESTURE_OWNER_CHANGED" }
        val page = inspect()
        check(page.kind == "FEED_READY" && page.packageName == packageName && page.versionCode == expectedVersion)
        check(page.networkKey == expectedNetworkKey) { "VIDEO_REFERENCE_NETWORK_CHANGED_BEFORE_GESTURE" }
        val box = page.viewport
        val x = box.left + (box.right - box.left) * swipe.x.toFloat()
        val from = box.top + (box.bottom - box.top) * swipe.fromY.toFloat()
        val to = box.top + (box.bottom - box.top) * swipe.toY.toFloat()
        val started = SystemClock.elapsedRealtimeNanos()
        val down = SystemClock.uptimeMillis() // MotionEvent's API timebase, not the measurement clock.
        var released = false
        var attempted = false
        var accepted = false
        var returnedNormally = false
        fun inject(action: Int, y: Float): Boolean {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        try {
            if (File(root, "CANCEL").exists()) throw VideoOperationCancelled()
            attempted = true
            inputAttempted = true
            accepted = inject(MotionEvent.ACTION_DOWN, from)
            val steps = (swipe.durationMs / 10L).toInt().coerceAtLeast(1)
            for (step in 1..steps) {
                if (!accepted) break
                if (File(root, "CANCEL").exists()) throw VideoOperationCancelled()
                session.requireRecordingActive()
                val planned = down + swipe.durationMs * step / steps
                val remaining = planned - SystemClock.uptimeMillis()
                if (remaining > 0L) SystemClock.sleep(remaining)
                if (File(root, "CANCEL").exists()) throw VideoOperationCancelled()
                accepted = inject(MotionEvent.ACTION_MOVE, from + (to - from) * step / steps)
            }
            if (accepted) { accepted = inject(MotionEvent.ACTION_UP, to); released = accepted }
            returnedNormally = true
            return accepted
        } finally {
            if (attempted && !released) runCatching { inject(MotionEvent.ACTION_CANCEL, to) }
            write("gesture-native.json", JSONObject().put("requested_duration_ms", swipe.durationMs)
                .put("started_elapsed_ns", started.toString()).put("returned_elapsed_ns", SystemClock.elapsedRealtimeNanos().toString())
                .put("down_uptime_ms", down).put("x", x).put("from_y", from).put("to_y", to)
                .put("pre_gesture_ui_reference", page.originalReference).put("owner_pid", expectedOwner.pid)
                .put("owner_start_ticks", expectedOwner.startTicks).put("returned_normally", returnedNormally)
                .put("injection_returned_true", returnedNormally && accepted).put("business_success", JSONObject.NULL)
                .put("claim", "one_input_invocation_not_playback_success_or_atomic_page_transaction"))
        }
    }

    override fun frame(sequence: Int): VideoFrameObservation {
        if (File(root, "CANCEL").exists()) throw VideoOperationCancelled()
        checkNotNull(projection).requireRecordingActive()
        val frame = savePng(sequence, "frames/frame-${sequence.toString().padStart(4, '0')}.png")
        checkNotNull(projection).requireRecordingActive()
        return frame
    }

    fun sourceScreenshot() { savePng(0, "source-public.png") }

    private fun savePng(sequence: Int, relative: String): VideoFrameObservation {
        check(automation.rootInActiveWindow?.packageName?.toString() == packageName) { "VIDEO_SCREENSHOT_TARGET_CHANGED" }
        val started = SystemClock.elapsedRealtimeNanos()
        val bitmap = checkNotNull(automation.takeScreenshot()) { "VIDEO_PUBLIC_SCREENSHOT_UNAVAILABLE" }
        val returned = SystemClock.elapsedRealtimeNanos()
        val width = bitmap.width
        val height = bitmap.height
        val file = File(root, relative)
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        check(file.createNewFile()) { "VIDEO_FRAME_ALREADY_EXISTS" }
        try {
            FileOutputStream(file).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.fd.sync() }
        } finally { bitmap.recycle() }
        val written = SystemClock.elapsedRealtimeNanos()
        val bytes = file.readBytes()
        val result = VideoFrameObservation(sequence, started, returned, written, relative,
            NativeVideoScenarioInstrumentation.sha(bytes), bytes.size.toLong())
        write("frame-source-$sequence.json", JSONObject().put("sequence", sequence).put("file", relative)
            .put("requested_elapsed_ns", started.toString()).put("returned_elapsed_ns", returned.toString())
            .put("written_elapsed_ns", written.toString()).put("bytes", result.bytes).put("sha256", result.sha256)
            .put("width", width).put("height", height)
            .put("source", "UiAutomation.takeScreenshot_not_mp4_pts_or_physical_presentation_time"))
        ui?.let { check(width == it.getInt("display_width") && height == it.getInt("display_height")) { "VIDEO_SCREENSHOT_DISPLAY_CHANGED" } }
        return result
    }

    override fun sleepMillis(milliseconds: Long) { SystemClock.sleep(milliseconds) }

    override fun stopRecording() {
        val session = checkNotNull(projection)
        session.close()
        waitUntil(SystemClock.elapsedRealtimeNanos() + 20_000_000_000L) { session.isClosed() }
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
                }
                catch (_: Exception) { ok = false }
            }
        }
        try {
            val known = owner
            if (known != null) ok = shell.stopOwnedPackage(known, packageName, deadlineNs) && ok
            else if (launched) shell.requirePackageAbsent(packageName, deadlineNs) // Never stop an unattributed partial launch.
        } catch (_: Exception) { ok = false }
        try {
            serviceFlags?.let { flags -> val info = automation.serviceInfo; info.flags = flags; automation.serviceInfo = info }
            val foreground = automation.rootInActiveWindow?.packageName?.toString()
            if ((launched || projection != null) && foreground in setOf(packageName, context.packageName)) {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitUntil(deadlineNs, ignoreCancel = true) { automation.rootInActiveWindow?.packageName?.toString() == "com.huawei.android.launcher" }
            } else if (foreground != "com.huawei.android.launcher") {
                // A rejected/unknown foreground session is not this run's UI to dismiss with HOME.
                ok = false
            }
            ok = shell.readersClosed() && ok
        } catch (_: Exception) { ok = false }
        write("native-cleanup.json", JSONObject().put("owned_launch_invoked", launched).put("known_main_pid", owner?.pid ?: JSONObject.NULL)
            .put("projection_closed", projection == null || recordingClosed).put("owned_cleanup", ok)
            .put("global_clean", "NOT_CLAIMED_REQUIRES_ROOT_POSTFLIGHT").put("settings_changed", false))
        return ok
    }

    private fun networkKey(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = checkNotNull(cm.activeNetwork) { "VIDEO_DEFAULT_NETWORK_MISSING" }
        check(cm.allNetworks.none { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
            "VIDEO_ACTIVE_VPN_NOT_ALLOWED"
        }
        val caps = checkNotNull(cm.getNetworkCapabilities(active))
        check(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            "VIDEO_REFERENCE_DEFAULT_WIFI_NOT_QUALIFIED"
        }
        val iface = checkNotNull(cm.getLinkProperties(active)?.interfaceName?.takeIf { it.isNotBlank() }) { "VIDEO_DEFAULT_INTERFACE_UNKNOWN" }
        return "wifi-default:$active:$iface"
    }

    @Suppress("DEPRECATION") private fun display(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }
    @Suppress("DEPRECATION") private fun rotation() = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
    private fun waitUntil(deadlineNs: Long, ignoreCancel: Boolean = false, condition: () -> Boolean) {
        while (true) {
            check(SystemClock.elapsedRealtimeNanos() < deadlineNs && (ignoreCancel || !File(root, "CANCEL").exists())) { "VIDEO_DEADLINE_OR_CANCEL" }
            if (condition()) return
            SystemClock.sleep(50L)
        }
    }
    private fun write(name: String, value: JSONObject) = NativeVideoScenarioInstrumentation.writeJson(root, name, value)
}
