package com.aneb.probe.research.engineering

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared SystemUI MediaProjection consent confirmer for explicit PO-authorized
 * controlled R/B runs (no appops bypass). Verifies app identity and screen-capture
 * scope keywords in the live SystemUI window before tapping the positive button.
 */
internal object ProjectionConsentHelper {
    fun confirmVisibleProjectionConsentOnce(
        automation: UiAutomation,
        session: TongyiProjectionCaptureService.Session,
        deadlineNs: Long,
        writeReceipt: (name: String, value: JSONObject) -> Unit,
    ) {
        val positiveLabels = setOf("立即开始", "允许", "开始录制", "开始", "Start now", "Allow")
        val negativeLabels = setOf("取消", "拒绝", "不允许", "Cancel", "Deny")
        val scopeKeywords = listOf("录屏", "录制", "屏幕", "投射", "截取", "screen", "recording", "casting")

        data class ConsentScan(
            val windowId: Int,
            val texts: List<String>,
            val scopeVerified: Boolean,
            val buttonNode: AccessibilityNodeInfo?,
            val buttonId: String?,
            val buttonLabel: String?,
            val buttonBounds: Rect?,
            val candidateCount: Int,
        )

        fun findSystemUiRoot(): AccessibilityNodeInfo? {
            automation.rootInActiveWindow?.let { if (it.packageName?.toString() == "com.android.systemui") return it }
            return runCatching {
                automation.windows.firstOrNull { it.root?.packageName?.toString() == "com.android.systemui" }?.root
            }.getOrNull()
        }

        fun scan(sysRoot: AccessibilityNodeInfo): ConsentScan {
            val texts = mutableListOf<String>()
            val idMatches = mutableListOf<Triple<AccessibilityNodeInfo, String, Rect>>()
            val textMatches = mutableListOf<Triple<AccessibilityNodeInfo, String, Rect>>()
            var visited = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 64 || visited >= 256) return
                visited++
                if (!node.isVisibleToUser) return
                val t = node.text?.toString()?.trim().orEmpty()
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) texts += t
                if (d.isNotEmpty() && d != t) texts += d
                val label = t.ifEmpty { d }
                val resId = node.viewIdResourceName?.toString().orEmpty()
                if (node.isEnabled && label !in negativeLabels) {
                    val box = Rect().also(node::getBoundsInScreen)
                    if (box.width() > 0 && box.height() > 0) {
                        if (resId == "android:id/button1") {
                            idMatches += Triple(node, label, box)
                        } else if ((node.isClickable || node.parent?.isClickable == true) && label in positiveLabels) {
                            textMatches += Triple(node, label, box)
                        }
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(sysRoot, 0)
            val joined = texts.joinToString(" ").lowercase()
            val appMatch = joined.contains("aneb") || joined.contains("com.aneb.probe")
            val actionMatch = scopeKeywords.any { joined.contains(it) }
            val chosenList = if (idMatches.isNotEmpty()) idMatches else textMatches
            val chosen = if (chosenList.size == 1) chosenList.first() else null
            return ConsentScan(
                windowId = sysRoot.windowId,
                texts = texts,
                scopeVerified = appMatch && actionMatch,
                buttonNode = chosen?.first,
                buttonId = chosen?.first?.viewIdResourceName,
                buttonLabel = chosen?.second,
                buttonBounds = chosen?.third,
                candidateCount = chosenList.size,
            )
        }

        var settledScan: ConsentScan? = null
        while (SystemClock.elapsedRealtimeNanos() < deadlineNs) {
            session.requireHealthy()
            if (session.grantReady && session.activityClosed) return
            val sysRoot = findSystemUiRoot()
            if (sysRoot != null) {
                val first = scan(sysRoot)
                if (first.scopeVerified && first.buttonNode != null) {
                    // Wait once for bottom-sheet entrance animation to finish, then re-read final resting bounds.
                    SystemClock.sleep(500L)
                    settledScan = findSystemUiRoot()?.let(::scan) ?: first
                    break
                }
            }
            SystemClock.sleep(100L)
        }

        val verified = settledScan
        if (verified == null || !verified.scopeVerified || verified.buttonBounds == null || verified.buttonNode == null) {
            writeReceipt(
                "projection-consent-confirm.json",
                JSONObject()
                    .put("explicit_card_flag", true)
                    .put("no_appops_bypass", true)
                    .put("clicked", false)
                    .put("reason", "VERIFIED_SYSTEMUI_CONSENT_BUTTON_NOT_FOUND")
                    .put("window_id", verified?.windowId ?: -1)
                    .put("scope_verified", verified?.scopeVerified ?: false)
                    .put("candidate_count", verified?.candidateCount ?: 0)
                    .put("dialog_texts", JSONArray(verified?.texts ?: emptyList<String>())),
            )
            return
        }

        val box = verified.buttonBounds
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        fun injectTap(action: Int): Boolean {
            val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, cx, cy, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            return try { automation.injectInputEvent(ev, true) } finally { ev.recycle() }
        }
        val downOk = injectTap(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(60L)
        val upOk = if (downOk) injectTap(MotionEvent.ACTION_UP) else false
        var a11yFallbackUsed = false
        var a11yFallbackOk = false

        val afterTouchDeadline = minOf(deadlineNs, SystemClock.elapsedRealtimeNanos() + 3_000_000_000L)
        while (SystemClock.elapsedRealtimeNanos() < afterTouchDeadline && !(session.grantReady && session.activityClosed)) {
            session.requireHealthy()
            SystemClock.sleep(80L)
        }
        if (!(session.grantReady && session.activityClosed)) {
            val retry = findSystemUiRoot()?.let(::scan)
            if (retry != null && retry.scopeVerified && retry.buttonNode != null) {
                a11yFallbackUsed = true
                a11yFallbackOk = retry.buttonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val afterA11yDeadline = minOf(deadlineNs, SystemClock.elapsedRealtimeNanos() + 3_000_000_000L)
                while (SystemClock.elapsedRealtimeNanos() < afterA11yDeadline && !(session.grantReady && session.activityClosed)) {
                    session.requireHealthy()
                    SystemClock.sleep(80L)
                }
            }
        }

        writeReceipt(
            "projection-consent-confirm.json",
            JSONObject()
                .put("explicit_card_flag", true)
                .put("no_appops_bypass", true)
                .put("clicked", true)
                .put("window_package", "com.android.systemui")
                .put("window_id", verified.windowId)
                .put("scope_verified", verified.scopeVerified)
                .put("dialog_texts", JSONArray(verified.texts))
                .put("matched_button_id", verified.buttonId ?: JSONObject.NULL)
                .put("matched_button_text", verified.buttonLabel ?: JSONObject.NULL)
                .put("matched_button_bounds", JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
                .put("tap_x", cx)
                .put("tap_y", cy)
                .put("touch_injected", downOk && upOk)
                .put("a11y_fallback_used", a11yFallbackUsed)
                .put("a11y_fallback_ok", a11yFallbackOk)
                .put("grant_ready_after_confirm", session.grantReady),
        )
    }
}
