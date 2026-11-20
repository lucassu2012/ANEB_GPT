package com.aneb.probe.research.business

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaExtractor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
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
import java.security.MessageDigest

/**
 * Debug-only Real Voice App (`com.larus.nova` 15080040 `real_time_call`) instrumentation
 * for Profile 4 correlation (M2.3 / F10.1). Captures 30fps MediaProjection (`video.mp4`)
 * + 100ms Accessibility timeline (`ui_timeline.json`) of `voice_call_status_text` & `subtitleText`
 * inside a bounded `phone-forward-condition v4` window with fail-closed owned cleanup.
 */
@RunWith(AndroidJUnit4::class)
class DoubaoVoiceCallInstrumentation {

    @Test
    fun observeVoiceCallPhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation

        require(BuildConfig.DEBUG) { "DEBUG_REQUIRED" }
        require(
            arguments.getString("feature_release") == "profile4-voice-correlation-1" &&
                arguments.getString("live_authorized") == "true",
        ) { "RELEASE_REQUIRED" }

        val conditionId = checkNotNull(arguments.getString("condition_id"))
        require(conditionId in setOf("R", "B1", "B2")) { "CONDITION_INVALID" }
        val runId = checkNotNull(arguments.getString("run_id"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "RUN_ID_INVALID" }
        val expectedVersion = checkNotNull(arguments.getString("app_version_code")?.toLongOrNull()?.takeIf { it > 0L })
        val observeMs = arguments.getString("observe_ms")?.toLongOrNull() ?: 65_000L
        require(observeMs in 10_000L..75_000L) { "OBSERVE_MS_INVALID" }
        val disableVideo = (arguments.getString("disable_video") ?: "true") == "true"
        val confirmVisibleConsent = (arguments.getString("confirm_visible_projection_consent") ?: "true") == "true"

        val parent = File(checkNotNull(context.getExternalFilesDir(null)), "real-voice").canonicalFile
        check(parent.isDirectory || parent.mkdirs()) { "OUTPUT_PARENT_UNAVAILABLE" }
        val root = File(parent, runId)
        check(root.mkdir()) { "REAL_VOICE_RUN_ALREADY_CONSUMED" }

        val shell = VideoOwnerShell(automation, root.canonicalFile, SystemClock::elapsedRealtimeNanos, allowedPackage = PACKAGE)
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val prepDeadlineNs = startedNs + 40_000_000_000L
        var owner: OwnedProcessLifecycle.Owner? = null
        var launched = false
        var serviceFlags: Int? = null
        var projection: TongyiProjectionCaptureService.Session? = null
        var recordingClosed = false
        var ownedCleanup = false
        var termination = "NOT_DISPATCHED"
        var failure: String? = null
        var videoDisabledConfirmed: Boolean? = null
        var callReadyNs: Long? = null
        val uiTimeline = ArrayList<JSONObject>()

        fun writeJson(name: String, value: JSONObject) {
            val file = File(root, name)
            check(file.createNewFile()) { "FILE_EXISTS:$name" }
            FileOutputStream(file).use { it.write((value.toString(2) + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }

        fun waitUntil(deadlineNs: Long, ignoreCancel: Boolean = false, condition: () -> Boolean) {
            while (true) {
                check(SystemClock.elapsedRealtimeNanos() < deadlineNs && (ignoreCancel || !File(root, "CANCEL").exists())) {
                    "DEADLINE_OR_CANCEL"
                }
                if (condition()) return
                SystemClock.sleep(50L)
            }
        }

        fun findVisibleNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            val r = automation.rootInActiveWindow ?: return null
            if (r.packageName?.toString() != PACKAGE) return null
            var visited = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
                if (depth > 64 || visited >= 2048) return null
                visited++
                if (!node.isVisibleToUser) return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    walk(c, depth + 1)?.let { return it }
                }
                return null
            }
            return walk(r, 0)
        }

        fun tapCenter(box: Rect): Boolean {
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

        fun saveScreenshot(label: String) {
            val bmp = automation.takeScreenshot() ?: return
            try {
                val png = File(root, "$label.png")
                if (png.createNewFile()) {
                    FileOutputStream(png).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.fd.sync()
                    }
                }
            } finally {
                bmp.recycle()
            }
        }

        fun checkWifiQualified(): String {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val active = checkNotNull(cm.activeNetwork) { "DEFAULT_NETWORK_MISSING" }
            check(cm.allNetworks.none { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
                "ACTIVE_VPN_NOT_ALLOWED"
            }
            val caps = checkNotNull(cm.getNetworkCapabilities(active))
            check(
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            ) { "WIFI_NOT_QUALIFIED" }
            val lp = checkNotNull(cm.getLinkProperties(active))
            check(lp.httpProxy == null && cm.defaultProxy == null) { "PROXY_PRESENT" }
            val iface = checkNotNull(lp.interfaceName?.takeIf { it.isNotBlank() }) { "INTERFACE_UNKNOWN" }
            return "wifi:$active:$iface"
        }

        @Suppress("DEPRECATION")
        fun displayMetrics(): Pair<Int, Int> {
            val metrics = DisplayMetrics()
            context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            return metrics.widthPixels to metrics.heightPixels
        }

        fun sealFinalMedia() {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(File(root, "video.mp4").absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString("mime")?.startsWith("video/") == true
                } ?: error("MP4_NO_VIDEO_TRACK")
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
                    if (!extractor.advance()) break
                }
                check(samples > 0) { "MP4_NO_VIDEO_SAMPLE" }
                writeJson(
                    "FINAL_MEDIA.json",
                    JSONObject()
                        .put("actual_video_track", track)
                        .put("sample_count", samples)
                        .put("first_presentation_time_us", first)
                        .put("last_presentation_time_us", last)
                        .put("pts_nondecreasing", nondecreasing),
                )
            } finally {
                extractor.release()
            }
        }

        try {
            check(automation.rootInActiveWindow?.packageName?.toString() == "com.huawei.android.launcher") {
                "LAUNCHER_NOT_FOCUSED"
            }
            val info = context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            check(info.longVersionCode == expectedVersion) { "APP_VERSION_MISMATCH:${info.longVersionCode}" }
            val netBefore = checkWifiQualified()
            shell.requirePackageAbsent(PACKAGE, prepDeadlineNs)

            val access = automation.serviceInfo
            serviceFlags = access.flags
            access.flags = access.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                if (confirmVisibleConsent) AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS else 0
            automation.serviceInfo = access

            val appInfo = checkNotNull(info.applicationInfo)
            val sigs = JSONArray()
            info.signingInfo?.apkContentsSigners?.forEach { sigs.put(sha256(it.toByteArray())) }
            writeJson(
                "app-identity.json",
                JSONObject()
                    .put("package", PACKAGE)
                    .put("version_code", info.longVersionCode)
                    .put("version_name", info.versionName)
                    .put("uid", appInfo.uid)
                    .put("network_before", netBefore)
                    .put("certificate_sha256", sigs),
            )

            // 1. Acquire MediaProjection & start 30fps video.mp4
            val session = TongyiProjectionCaptureService.createSession(context, root, prepDeadlineNs)
            projection = session
            session.launchConsent()
            if (confirmVisibleConsent) {
                ProjectionConsentHelper.confirmVisibleProjectionConsentOnce(
                    automation,
                    session,
                    minOf(prepDeadlineNs, SystemClock.elapsedRealtimeNanos() + 20_000_000_000L),
                    ::writeJson,
                )
            }
            waitUntil(prepDeadlineNs) { session.requireHealthy(); session.grantReady && session.activityClosed }
            waitUntil(minOf(prepDeadlineNs, SystemClock.elapsedRealtimeNanos() + 5_000_000_000L)) {
                automation.rootInActiveWindow?.packageName?.toString() == "com.huawei.android.launcher"
            }

            val display = displayMetrics()
            session.startRecording(display.first, display.second, context.resources.configuration.densityDpi, prepDeadlineNs)
            val video = File(root, "video.mp4")
            waitUntil(prepDeadlineNs) {
                session.requireHealthy()
                session.recordingStarted && video.isFile && video.length() >= 64L
            }
            val prevLen = video.length()
            waitUntil(prepDeadlineNs) { session.requireRecordingActive(); video.length() > prevLen }
            writeJson(
                "OWNED_PROJECTION_CAPTURE.json",
                JSONObject()
                    .put("backend", "android_media_projection_v1")
                    .put("run_id", runId)
                    .put("width", display.first)
                    .put("height", display.second)
                    .put("fps_target", 30),
            )

            // 2. Cold-launch Doubao & tap real_time_call_ic_container
            val launchIntent = checkNotNull(context.packageManager.getLaunchIntentForPackage(PACKAGE)) {
                "APP_LAUNCH_UNAVAILABLE"
            }
            launched = true
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val launchEnd = minOf(prepDeadlineNs, SystemClock.elapsedRealtimeNanos() + 15_000_000_000L)
            while (SystemClock.elapsedRealtimeNanos() < launchEnd) {
                owner = shell.findPackageOwner(PACKAGE, appInfo.uid, launchEnd)
                if (owner != null && automation.rootInActiveWindow?.packageName?.toString() == PACKAGE) break
                SystemClock.sleep(100L)
            }
            check(owner != null) { "DOUBAO_OWNER_UNCONFIRMED" }

            var callBtn = findVisibleNode {
                it.viewIdResourceName == "com.larus.nova:id/real_time_call_ic_container" && it.isClickable && it.isEnabled
            }
            var backTries = 0
            val btnDeadline = minOf(prepDeadlineNs, SystemClock.elapsedRealtimeNanos() + 12_000_000_000L)
            val btnWaitStart = SystemClock.elapsedRealtimeNanos()
            while (callBtn == null && SystemClock.elapsedRealtimeNanos() < btnDeadline) {
                if ((SystemClock.elapsedRealtimeNanos() - btnWaitStart) > 1_500_000_000L && backTries < 2 &&
                    automation.rootInActiveWindow?.packageName?.toString() == PACKAGE
                ) {
                    backTries++
                    automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    SystemClock.sleep(600L)
                } else {
                    SystemClock.sleep(200L)
                }
                callBtn = findVisibleNode {
                    it.viewIdResourceName == "com.larus.nova:id/real_time_call_ic_container" && it.isClickable && it.isEnabled
                }
            }
            val callBtnNode = checkNotNull(callBtn) { "REAL_TIME_CALL_BUTTON_NOT_VISIBLE" }
            val callBtnBox = Rect().also(callBtnNode::getBoundsInScreen)
            check(tapCenter(callBtnBox)) { "TAP_REAL_TIME_CALL_FAILED" }

            // 3. Wait for RealtimeCallActivity (hangup button + status text)
            val inCallDeadline = SystemClock.elapsedRealtimeNanos() + 15_000_000_000L
            waitUntil(inCallDeadline) {
                findVisibleNode { it.viewIdResourceName == "com.larus.nova:id/voice_call_hangup" } != null &&
                    findVisibleNode { it.viewIdResourceName == "com.larus.nova:id/voice_call_status_text" } != null
            }
            SystemClock.sleep(1_500L)

            if (disableVideo) {
                val videoBtn = findVisibleNode {
                    it.viewIdResourceName == "com.larus.nova:id/voice_call_video" && it.isClickable
                }
                if (videoBtn != null && videoBtn.isSelected) {
                    val vBox = Rect().also(videoBtn::getBoundsInScreen)
                    tapCenter(vBox)
                    SystemClock.sleep(600L)
                    val afterVideoBtn = findVisibleNode { it.viewIdResourceName == "com.larus.nova:id/voice_call_video" }
                    videoDisabledConfirmed = (afterVideoBtn != null && !afterVideoBtn.isSelected)
                } else {
                    videoDisabledConfirmed = (videoBtn != null && !videoBtn.isSelected)
                }
            }

            // 4. Signal readiness to Host & observe 100ms UI timeline for observeMs
            val readyNs = SystemClock.elapsedRealtimeNanos()
            callReadyNs = readyNs
            val readyEpochMs = System.currentTimeMillis()
            val initStatus = findVisibleNode { it.viewIdResourceName == "com.larus.nova:id/voice_call_status_text" }?.text?.toString().orEmpty()
            val initSub = findVisibleNode { it.viewIdResourceName == "com.larus.nova:id/subtitleText" }?.text?.toString().orEmpty()
            writeJson(
                "READY.json",
                JSONObject()
                    .put("run_id", runId)
                    .put("condition_id", conditionId)
                    .put("ready_elapsed_ns", readyNs)
                    .put("ready_epoch_ms", readyEpochMs)
                    .put("video_disabled_confirmed", videoDisabledConfirmed ?: JSONObject.NULL)
                    .put("initial_status", initStatus)
                    .put("initial_subtitle", initSub),
            )
            instrumentation.sendStatus(
                1,
                Bundle().apply {
                    putString("voice_call_ready", "true")
                    putLong("ready_elapsed_ns", readyNs)
                    putLong("ready_epoch_ms", readyEpochMs)
                },
            )
            saveScreenshot("call-ready")

            var prevStatus: String? = null
            var prevSub: String? = null
            val obsDeadline = readyNs + observeMs * 1_000_000L
            while (SystemClock.elapsedRealtimeNanos() < obsDeadline) {
                session.requireRecordingActive()
                check(!File(root, "CANCEL").exists()) { "CANCELLED" }
                val curNs = SystemClock.elapsedRealtimeNanos()
                val curEpoch = System.currentTimeMillis()
                val stText = findVisibleNode { it.viewIdResourceName == "com.larus.nova:id/voice_call_status_text" }?.text?.toString() ?: ""
                val subText = findVisibleNode { it.viewIdResourceName == "com.larus.nova:id/subtitleText" }?.text?.toString() ?: ""
                if (stText != prevStatus || subText != prevSub) {
                    uiTimeline.add(
                        JSONObject()
                            .put("t_rel_ms", (curNs - readyNs) / 1_000_000.0)
                            .put("elapsed_ns", curNs)
                            .put("epoch_ms", curEpoch)
                            .put("status", stText)
                            .put("subtitle", subText),
                    )
                    prevStatus = stText
                    prevSub = subText
                }
                SystemClock.sleep(100L)
            }
            saveScreenshot("call-end")

            // 5. Hang up cleanly & close MediaProjection
            findVisibleNode { it.viewIdResourceName == "com.larus.nova:id/voice_call_hangup" && it.isClickable }?.let {
                val hBox = Rect().also(it::getBoundsInScreen)
                tapCenter(hBox)
                SystemClock.sleep(800L)
            }
            val closeDeadline = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
            session.close()
            waitUntil(closeDeadline, ignoreCancel = true) { session.isClosed() }
            sealFinalMedia()
            session.releaseClosedSession()
            recordingClosed = true

            checkWifiQualified()
            termination = "VOICE_CALL_OBSERVED"
        } catch (err: Throwable) {
            termination = "ERROR"
            failure = (err.javaClass.name + ":" + err.message.orEmpty()).take(2048)
        } finally {
            val cleanDeadline = SystemClock.elapsedRealtimeNanos() + 30_000_000_000L
            var ok = true
            projection?.let { s ->
                if (!recordingClosed) {
                    runCatching {
                        s.close()
                        waitUntil(cleanDeadline, ignoreCancel = true) { s.isClosed() }
                        s.releaseClosedSession()
                        recordingClosed = true
                    }.onFailure { ok = false }
                }
            }
            runCatching {
                val known = owner
                if (known != null) ok = shell.stopOwnedPackage(known, PACKAGE, cleanDeadline) && ok
                else if (launched) shell.requirePackageAbsent(PACKAGE, cleanDeadline)
            }.onFailure { ok = false }

            serviceFlags?.let { flags ->
                runCatching {
                    val info = automation.serviceInfo
                    info.flags = flags
                    automation.serviceInfo = info
                }
            }
            val fg = automation.rootInActiveWindow?.packageName?.toString()
            if (fg != "com.huawei.android.launcher") {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    waitUntil(cleanDeadline, ignoreCancel = true) {
                        automation.rootInActiveWindow?.packageName?.toString() == "com.huawei.android.launcher"
                    }
                }.onFailure { ok = false }
            }
            ok = shell.readersClosed() && ok
            ownedCleanup = ok

            val arr = JSONArray()
            uiTimeline.forEach { arr.put(it) }
            writeJson(
                "ui_timeline.json",
                JSONObject()
                    .put("run_id", runId)
                    .put("condition_id", conditionId)
                    .put("ready_elapsed_ns", callReadyNs ?: JSONObject.NULL)
                    .put("events", arr),
            )
            writeJson(
                "raw.json",
                JSONObject()
                    .put("format", "doubao-voice-call-raw-1")
                    .put("run_id", runId)
                    .put("condition_id", conditionId)
                    .put("package", PACKAGE)
                    .put("expected_version_code", expectedVersion)
                    .put("observe_ms", observeMs)
                    .put("disable_video", disableVideo)
                    .put("video_disabled_confirmed", videoDisabledConfirmed ?: JSONObject.NULL)
                    .put("termination", termination)
                    .put("failure", failure ?: JSONObject.NULL)
                    .put("owned_cleanup", ownedCleanup)
                    .put("timeline_event_count", uiTimeline.size)
                    .put("started_ns", startedNs)
                    .put("finished_ns", SystemClock.elapsedRealtimeNanos()),
            )
        }
        check(termination == "VOICE_CALL_OBSERVED" && ownedCleanup && failure == null) {
            "DOUBAO_VOICE_CALL_FAILED:$termination:$failure:cleanup=$ownedCleanup"
        }
    }

    @Test
    fun validateOnly() {
        check(PACKAGE == "com.larus.nova")
    }

    companion object {
        private const val PACKAGE = "com.larus.nova"
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
