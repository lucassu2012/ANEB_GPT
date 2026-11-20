package com.aneb.probe.research.business

/**
 * Pure pre-run eligibility guard for dismissing the already observed Douyin 400601
 * "发现抖音朋友" modal during separate prerequisite preparation (never inside measurement,
 * never clicking "继续", never entering credentials, and never claiming account login).
 */
data class VideoModalActionNode(
    val packageName: String,
    val windowId: Int,
    val className: String?,
    val text: String?,
    val description: String?,
    val visible: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val editable: Boolean = false,
    val password: Boolean = false,
    val bounds: VideoViewport,
)

sealed interface VideoFriendsInviteDeclineDecision {
    data class EligibleSingleDecline(
        val windowId: Int,
        val declineBounds: VideoViewport,
        val accountState: String = "UNKNOWN_NOT_REAUTHENTICATED",
    ) : VideoFriendsInviteDeclineDecision

    data class NotPresent(val reason: String) : VideoFriendsInviteDeclineDecision
    data class Refused(val reason: String) : VideoFriendsInviteDeclineDecision
}

object VideoFriendsInviteModalGuard {
    private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
    private const val TITLE_TEXT = "发现抖音朋友"
    private const val BODY_PREFIX = "登录后"
    private const val DECLINE_TEXT = "拒绝"
    private const val CONTINUE_TEXT = "继续"

    fun evaluate(
        activePackage: String,
        activeWindowId: Int,
        displayWidth: Int,
        displayHeight: Int,
        nodes: List<VideoModalActionNode>,
    ): VideoFriendsInviteDeclineDecision {
        if (activePackage != DOUYIN_PACKAGE || activeWindowId < 0 || displayWidth <= 0 || displayHeight <= 0) {
            return VideoFriendsInviteDeclineDecision.Refused("UNEXPECTED_FOREGROUND_PACKAGE_OR_WINDOW")
        }
        val visibleInTargetPkg = nodes.filter { it.visible && it.packageName == DOUYIN_PACKAGE }
        if (visibleInTargetPkg.isEmpty()) {
            return VideoFriendsInviteDeclineDecision.NotPresent("NO_VISIBLE_TARGET_NODES")
        }
        if (visibleInTargetPkg.any {
                it.password || it.editable || (it.className?.contains("EditText", ignoreCase = true) == true)
            }) {
            return VideoFriendsInviteDeclineDecision.Refused("CREDENTIAL_OR_EDITABLE_INPUT_PRESENT")
        }
        val modalCandidates = visibleInTargetPkg.filter { node ->
            val t = node.text.orEmpty()
            val d = node.description.orEmpty()
            t == TITLE_TEXT || BODY_PREFIX in t || t == DECLINE_TEXT || d == DECLINE_TEXT || t == CONTINUE_TEXT || d == CONTINUE_TEXT
        }
        if (modalCandidates.isEmpty()) {
            return VideoFriendsInviteDeclineDecision.NotPresent("INVITE_MODAL_NOT_OBSERVED")
        }
        if (modalCandidates.any { it.windowId != activeWindowId }) {
            return VideoFriendsInviteDeclineDecision.Refused("CROSS_WINDOW_MODAL_NODES")
        }
        val hasTitle = modalCandidates.any { it.text == TITLE_TEXT }
        val hasBody = modalCandidates.any { BODY_PREFIX in it.text.orEmpty() }
        val hasContinue = modalCandidates.any { it.text == CONTINUE_TEXT || it.description == CONTINUE_TEXT }
        val declines = modalCandidates.filter { it.text == DECLINE_TEXT || it.description == DECLINE_TEXT }
        if (!hasTitle || !hasBody || !hasContinue || declines.isEmpty()) {
            return VideoFriendsInviteDeclineDecision.Refused("PARTIAL_MODAL_SIGNATURE_NO_ACTION")
        }
        if (declines.size != 1) {
            return VideoFriendsInviteDeclineDecision.Refused("DECLINE_NODE_NOT_UNIQUE")
        }
        val decline = declines.single()
        val b = decline.bounds
        val validBounds = b.left >= 0 && b.top >= 0 && b.right <= displayWidth && b.bottom <= displayHeight &&
            b.right > b.left && b.bottom > b.top
        if (!decline.enabled || !decline.clickable || !validBounds) {
            return VideoFriendsInviteDeclineDecision.Refused("DECLINE_NODE_NOT_ACTIONABLE")
        }
        return VideoFriendsInviteDeclineDecision.EligibleSingleDecline(
            windowId = activeWindowId,
            declineBounds = b,
        )
    }
}
