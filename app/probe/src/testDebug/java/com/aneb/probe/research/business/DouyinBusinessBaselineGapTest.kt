package com.aneb.probe.research.business

import com.aneb.probe.research.BusinessVideoRawFormat
import com.aneb.probe.research.engineering.OwnedProcessLifecycle
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DouyinBusinessBaselineGapTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `targeted v2 composite modal blocker classifies Douyin friends invite without relaxing feed anchor or empty window`() {
        val binding = modalAwareDouyinBinding()
        assertEquals(
            VideoUiQueries.TARGETED_MODAL_AWARE_METHOD,
            VideoUiQueries.selectMethod(VideoUiQueries.TARGETED_MODAL_AWARE_METHOD, binding),
        )
        val queries = VideoUiQueries.compile(binding)
        assertEquals(
            listOf(
                VideoUiQuery("feed_anchor", "TEXT", "推荐"),
                VideoUiQuery("gesture_viewport", "VIEW_ID", "com.ss.android.ugc.aweme:id/viewpager"),
                VideoUiQuery("blockers.LOGIN_REQUIRED.0.0", "TEXT", "发现抖音朋友"),
                VideoUiQuery("blockers.LOGIN_REQUIRED.0.1", "TEXT", "登录后"),
                VideoUiQuery("blockers.LOGIN_REQUIRED.0.2", "TEXT", "拒绝"),
                VideoUiQuery("blockers.LOGIN_REQUIRED.0.3", "TEXT", "继续"),
            ),
            queries,
        )

        val viewport = VideoUiPublicNode(
            resourceId = "com.ss.android.ugc.aweme:id/viewpager",
            text = null,
            description = "视频",
            bounds = VideoViewport(0, 0, 1200, 2478),
        )
        val selectedRecommend = VideoUiPublicNode(
            resourceId = null,
            text = null,
            description = "已选中，推荐，按钮",
            bounds = VideoViewport(876, 154, 1028, 299),
        )
        val innerRecommendText = VideoUiPublicNode(
            resourceId = "com.ss.android.ugc.aweme:id/5e=",
            text = "推荐",
            description = null,
            bounds = VideoViewport(899, 191, 1005, 262),
        )

        // 1. Captured positive feed shape (ui-6): FEED_READY
        val ready = VideoUiAdmission.classify(binding, listOf(viewport, selectedRecommend, innerRecommendText))
        assertEquals("FEED_READY", ready.kind)
        assertNull(ready.reason)

        // 2. Missing top recommendation anchor (ui-3 shape): MUST stay UNKNOWN / FEED_ANCHOR_MISSING
        val missingAnchor = VideoUiAdmission.classify(binding, listOf(viewport))
        assertEquals("UNKNOWN", missingAnchor.kind)
        assertEquals("FEED_ANCHOR_MISSING", missingAnchor.reason)

        // 3. Empty active window (ui-7 shape without recalled modal text): MUST stay UNKNOWN / VIDEO_VIEWPORT_MISSING
        val emptyWindow = VideoUiAdmission.classify(binding, emptyList())
        assertEquals("UNKNOWN", emptyWindow.kind)
        assertEquals("VIDEO_VIEWPORT_MISSING", emptyWindow.reason)

        // 4. Incidental video caption text "继续" or "拒绝" without full 4-element invite signature: NOT a login block
        val captionWithContinue = VideoUiPublicNode(null, "继续", null, VideoViewport(100, 2000, 300, 2100))
        assertEquals(
            "FEED_READY",
            VideoUiAdmission.classify(binding, listOf(viewport, selectedRecommend, captionWithContinue)).kind,
        )

        // 5. Full 4-element Douyin 400601 "发现抖音朋友" modal in new window: structured LOGIN_REQUIRED / FRIENDS_INVITE_LOGIN_MODAL
        val inviteModalNodes = listOf(
            VideoUiPublicNode(null, "发现抖音朋友", null, VideoViewport(320, 960, 880, 1060)),
            VideoUiPublicNode(
                null,
                "登录后才能找到朋友，查看朋友发布的作品，收到朋友分享的视频。",
                null,
                VideoViewport(180, 1100, 1020, 1280),
            ),
            VideoUiPublicNode(null, "拒绝", null, VideoViewport(180, 1480, 560, 1620)),
            VideoUiPublicNode(null, "继续", null, VideoViewport(640, 1480, 1020, 1620)),
        )
        val modalDecision = VideoUiAdmission.classify(binding, inviteModalNodes)
        assertEquals("LOGIN_REQUIRED", modalDecision.kind)
        assertEquals("FRIENDS_INVITE_LOGIN_MODAL", modalDecision.reason)

        // Verify post-swipe appearance in VideoScenarioExecution records ACCOUNT_BLOCKED / FRIENDS_INVITE_LOGIN_MODAL
        val profile = File("src/debug/assets/business-profiles/douyin-feed-single-switch-v1.json").readText()
        val plan = VideoScenarioProfile.compile(profile)
        val runDir = File(temporary.root, "post-swipe-friends-modal")
        val phone = ModalAfterSwipePhone(runDir, modalDecision)
        val runBinding = VideoRunBinding(
            runId = runDir.name,
            profileSha256 = plan.profileSourceSha256,
            appVersionCode = 400601L,
            conditionId = "QUAL_REFERENCE",
            networkKey = "wifi-default:416:wlan0",
            clockId = "test-clock",
            uiObservationMethod = VideoUiQueries.TARGETED_MODAL_AWARE_METHOD,
        )
        val result = VideoScenarioExecution(phone) { phone.time }.runOnce(profile, runBinding, runDir)
        assertEquals("STOPPED", result.state)
        assertEquals("ACCOUNT_BLOCKED", result.category)
        assertEquals("FRIENDS_INVITE_LOGIN_MODAL", result.reason)
        assertEquals("1", result.swipeCalls)
        assertTrue(result.ownedCleanup)
        assertNull(result.firstObservedPlaybackWaitMs)
    }

    @Test
    fun `pre run decline guard admits only unique actionable same window decline and never touches continue or inputs`() {
        val baseNodes = listOf(
            VideoModalActionNode("com.ss.android.ugc.aweme", 47982, "android.widget.TextView", "发现抖音朋友", null, true, true, false, bounds = VideoViewport(320, 960, 880, 1060)),
            VideoModalActionNode("com.ss.android.ugc.aweme", 47982, "android.widget.TextView", "登录后才能找到朋友，查看朋友发布的作品，收到朋友分享的视频。", null, true, true, false, bounds = VideoViewport(180, 1100, 1020, 1280)),
            VideoModalActionNode("com.ss.android.ugc.aweme", 47982, "android.widget.TextView", "拒绝", null, true, true, true, bounds = VideoViewport(180, 1480, 560, 1620)),
            VideoModalActionNode("com.ss.android.ugc.aweme", 47982, "android.widget.TextView", "继续", null, true, true, true, bounds = VideoViewport(640, 1480, 1020, 1620)),
        )

        val admitted = VideoFriendsInviteModalGuard.evaluate("com.ss.android.ugc.aweme", 47982, 1200, 2640, baseNodes)
        assertTrue(admitted is VideoFriendsInviteDeclineDecision.EligibleSingleDecline)
        admitted as VideoFriendsInviteDeclineDecision.EligibleSingleDecline
        assertEquals(47982, admitted.windowId)
        assertEquals(VideoViewport(180, 1480, 560, 1620), admitted.declineBounds)
        assertEquals("UNKNOWN_NOT_REAUTHENTICATED", admitted.accountState)

        // Duplicate "拒绝" -> refused
        val duplicateDecline = baseNodes + baseNodes[2].copy(bounds = VideoViewport(180, 1650, 560, 1750))
        assertEquals(
            VideoFriendsInviteDeclineDecision.Refused("DECLINE_NODE_NOT_UNIQUE"),
            VideoFriendsInviteModalGuard.evaluate("com.ss.android.ugc.aweme", 47982, 1200, 2640, duplicateDecline),
        )

        // Non-clickable "拒绝" -> refused
        val nonClickable = baseNodes.map { if (it.text == "拒绝") it.copy(clickable = false) else it }
        assertEquals(
            VideoFriendsInviteDeclineDecision.Refused("DECLINE_NODE_NOT_ACTIONABLE"),
            VideoFriendsInviteModalGuard.evaluate("com.ss.android.ugc.aweme", 47982, 1200, 2640, nonClickable),
        )

        // Cross-window modal nodes -> refused
        val crossWindow = baseNodes.map { if (it.text == "拒绝") it.copy(windowId = 47981) else it }
        assertEquals(
            VideoFriendsInviteDeclineDecision.Refused("CROSS_WINDOW_MODAL_NODES"),
            VideoFriendsInviteModalGuard.evaluate("com.ss.android.ugc.aweme", 47982, 1200, 2640, crossWindow),
        )

        // Editable / credential input present -> refused
        val withEditText = baseNodes + VideoModalActionNode(
            "com.ss.android.ugc.aweme", 47982, "android.widget.EditText", "", "输入手机号", true, true, true, editable = true,
            bounds = VideoViewport(180, 1300, 1020, 1400),
        )
        assertEquals(
            VideoFriendsInviteDeclineDecision.Refused("CREDENTIAL_OR_EDITABLE_INPUT_PRESENT"),
            VideoFriendsInviteModalGuard.evaluate("com.ss.android.ugc.aweme", 47982, 1200, 2640, withEditText),
        )

        // Normal feed without invite modal -> NotPresent
        val normalFeed = listOf(
            VideoModalActionNode("com.ss.android.ugc.aweme", 47981, "android.widget.TextView", null, "已选中，推荐，按钮", true, true, true, bounds = VideoViewport(876, 154, 1028, 299)),
        )
        assertEquals(
            VideoFriendsInviteDeclineDecision.NotPresent("INVITE_MODAL_NOT_OBSERVED"),
            VideoFriendsInviteModalGuard.evaluate("com.ss.android.ugc.aweme", 47981, 1200, 2640, normalFeed),
        )
    }

    @Test
    fun `owned stop confirmation resolves 1d03ac2 post stop stat ENOENT and dying colon process without second force stop`() {
        val pid = "6826"
        val owner = OwnedProcessLifecycle.Owner(
            pid = pid,
            uid = 10345,
            startTicks = "918273645",
            commandLine = listOf(VideoOwnedStopConfirmation.PACKAGE),
        )
        val stat = "$pid (${VideoOwnedStopConfirmation.PACKAGE}) " +
            MutableList(20) { "0" }.also { it[0] = "S"; it[19] = owner.startTicks }.joinToString(" ") + "\n"
        var now = 1_000L
        val deadline = 5_000_000_000L
        val steps = ArrayDeque(
            listOf(
                // Pre-stop Alive check
                "ps -A -o PID" to OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n$pid\n"),
                "cat /proc/$pid/stat" to OwnedProcessLifecycle.ShellResult(true, 0, stat),
                "cat /proc/$pid/status" to OwnedProcessLifecycle.ShellResult(true, 0, "Name:\taweme\nUid:\t10345\t10345\t10345\t10345\n"),
                "cat /proc/$pid/cmdline" to OwnedProcessLifecycle.ShellResult(true, 0, owner.rawCommandLine),
                "cat /proc/$pid/stat" to OwnedProcessLifecycle.ShellResult(true, 0, stat),
                // Single force-stop
                "am force-stop com.ss.android.ugc.aweme" to OwnedProcessLifecycle.ShellResult(true, 0, ""),
                // Exact 1d03ac2 race: ps sees 6826, /proc/6826/stat returns ENOENT
                "ps -A -o PID" to OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n$pid\n"),
                "cat /proc/$pid/stat" to OwnedProcessLifecycle.ShellResult(true, 1, "cat: /proc/$pid/stat: No such file or directory\n"),
                // Fresh full-list witness confirms 6826 exited
                "ps -A -o PID" to OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n"),
                // Dying colon child process still reaping on first check, gone 50ms later
                "ps -A -o PID,NAME" to OwnedProcessLifecycle.ShellResult(true, 0, "PID NAME\n1 init\n6890 com.ss.android.ugc.aweme:push\n"),
                "ps -A -o PID,NAME" to OwnedProcessLifecycle.ShellResult(true, 0, "PID NAME\n1 init\n"),
            ),
        )
        val commands = mutableListOf<String>()
        var sleeps = 0
        val shell = object : OwnedProcessLifecycle.Shell {
            override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult {
                commands += command
                val (expectedCmd, reply) = steps.removeFirst()
                assertEquals(expectedCmd, command)
                return reply
            }

            override fun sleepMillis(milliseconds: Long) {
                assertEquals(50L, milliseconds)
                sleeps++
                now += 50_000_000L
            }
        }
        val lifecycle = VideoOwnedStopConfirmation.newLifecycle(shell) { now }
        val outcome = VideoOwnedStopConfirmation.stopOwnedPackage(
            shell = shell,
            lifecycle = lifecycle,
            nowNs = { now },
            readersHealthy = { true },
            owner = owner,
            packageName = VideoOwnedStopConfirmation.PACKAGE,
            deadlineNanos = deadline,
        )
        assertTrue(outcome.stopped)
        assertEquals("EXACT_OWNER_PRECHECK_AND_PACKAGE_ABSENCE_OBSERVED", outcome.reason)
        assertEquals(1, commands.count { it == "am force-stop com.ss.android.ugc.aweme" })
        assertTrue(commands.none { it.startsWith("ps -p ") })
        assertEquals(1, sleeps)
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `owned stop confirmation immediately rejects PID replacement after force stop without sleeping`() {
        val pid = "6826"
        val owner = OwnedProcessLifecycle.Owner(pid, 10345, "918273645", listOf(VideoOwnedStopConfirmation.PACKAGE))
        val statBefore = "$pid (${VideoOwnedStopConfirmation.PACKAGE}) " +
            MutableList(20) { "0" }.also { it[0] = "S"; it[19] = owner.startTicks }.joinToString(" ") + "\n"
        val statReplaced = "$pid (${VideoOwnedStopConfirmation.PACKAGE}) " +
            MutableList(20) { "0" }.also { it[0] = "S"; it[19] = "999999999" }.joinToString(" ") + "\n"
        val steps = ArrayDeque(
            listOf(
                "ps -A -o PID" to OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n$pid\n"),
                "cat /proc/$pid/stat" to OwnedProcessLifecycle.ShellResult(true, 0, statBefore),
                "cat /proc/$pid/status" to OwnedProcessLifecycle.ShellResult(true, 0, "Name:\taweme\nUid:\t10345\t10345\t10345\t10345\n"),
                "cat /proc/$pid/cmdline" to OwnedProcessLifecycle.ShellResult(true, 0, owner.rawCommandLine),
                "cat /proc/$pid/stat" to OwnedProcessLifecycle.ShellResult(true, 0, statBefore),
                "am force-stop com.ss.android.ugc.aweme" to OwnedProcessLifecycle.ShellResult(true, 0, ""),
                "ps -A -o PID" to OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n$pid\n"),
                "cat /proc/$pid/stat" to OwnedProcessLifecycle.ShellResult(true, 0, statReplaced),
                "cat /proc/$pid/status" to OwnedProcessLifecycle.ShellResult(true, 0, "Name:\taweme\nUid:\t10345\t10345\t10345\t10345\n"),
                "cat /proc/$pid/cmdline" to OwnedProcessLifecycle.ShellResult(true, 0, owner.rawCommandLine),
                "cat /proc/$pid/stat" to OwnedProcessLifecycle.ShellResult(true, 0, statReplaced),
            ),
        )
        val shell = object : OwnedProcessLifecycle.Shell {
            override fun execute(command: String, deadlineNanos: Long) = steps.removeFirst().second
            override fun sleepMillis(milliseconds: Long) = fail("must not sleep when PID is replaced")
        }
        val lifecycle = VideoOwnedStopConfirmation.newLifecycle(shell) { 1_000L }
        val outcome = VideoOwnedStopConfirmation.stopOwnedPackage(
            shell = shell,
            lifecycle = lifecycle,
            nowNs = { 1_000L },
            readersHealthy = { true },
            owner = owner,
            packageName = VideoOwnedStopConfirmation.PACKAGE,
            deadlineNanos = 5_000_000_000L,
        )
        assertFalse(outcome.stopped)
        assertTrue(outcome.reason.contains("VIDEO_STOP_OWNER_REPLACED_NOT_STOPPED"))
    }

    @Test
    fun `controlled host network condition binding persists in raw result and imports cleanly`() {
        val profile = File("src/debug/assets/business-profiles/douyin-feed-single-switch-v1.json").readText()
        val plan = VideoScenarioProfile.compile(profile)
        val runDir = File(temporary.root, "controlled-b-1")
        val phone = ModalAfterSwipePhone(runDir, null)
        val hostBinding = VideoHostNetworkBinding(
            topologyId = "ethernet-winrt-hotspot-20261119",
            conditionMode = "limit",
            requestSha256 = "c".repeat(64),
            uplinkInterfaceIndex = 12,
            apInterfaceIndex = 14,
            downlinkIpBytesPerSecond = 125_000L,
        )
        val binding = VideoRunBinding(
            runId = runDir.name,
            profileSha256 = plan.profileSourceSha256,
            appVersionCode = 400601L,
            conditionId = "B",
            networkKey = "wifi-default:416:wlan0",
            clockId = "test-clock",
            uiObservationMethod = VideoUiQueries.TARGETED_MODAL_AWARE_METHOD,
            hostNetworkCondition = hostBinding,
        )
        val result = VideoScenarioExecution(phone) { phone.time }.runOnce(profile, binding, runDir)
        assertEquals("CAPTURED_AWAITING_REVIEW", result.state)
        assertEquals(hostBinding, result.hostNetworkCondition)
        assertNull(result.firstObservedPlaybackWaitMs)
        assertNull(result.playerFirstFrameMs)
        assertEquals("not_computed", result.stallStatus)

        val rawJson = Json.parseToJsonElement(File(runDir, "result.json").readText()).jsonObject
        val imported = BusinessVideoRawFormat.readAttempts(rawJson)
        assertEquals(1, imported.size)
    }

    private class ModalAfterSwipePhone(
        private val directory: File,
        private val postSwipeModal: VideoUiDecision?,
    ) : VideoScenarioExecution.Phone {
        var time = 1_000_000_000L
        private var swiped = false
        private val readyViewport = VideoViewport(0, 0, 1200, 2478)

        private fun readyPage() = VideoPage(
            "com.ss.android.ugc.aweme",
            400601L,
            "FEED_READY",
            "wifi-default:416:wlan0",
            readyViewport,
            "ui-ready.json",
        )

        override fun prepare(deadlineNs: Long) = readyPage()
        override fun startRecording(deadlineNs: Long) {}
        override fun inspect(): VideoPage {
            if (swiped && postSwipeModal != null) {
                return VideoPage(
                    "com.ss.android.ugc.aweme",
                    400601L,
                    postSwipeModal.kind,
                    "wifi-default:416:wlan0",
                    postSwipeModal.viewport,
                    "ui-modal.json",
                    postSwipeModal.reason,
                )
            }
            return readyPage()
        }

        override fun swipeOnce(swipe: VideoSwipe): Boolean {
            swiped = true
            time += swipe.durationMs * 1_000_000L
            return true
        }

        override fun frame(sequence: Int): VideoFrameObservation {
            val requested = time
            time += 10_000_000L
            val bytes = "OFFLINE_BOUNDARY_$sequence".toByteArray()
            val file = File(directory, "frame-$sequence.bin")
            Files.write(file.toPath(), bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return VideoFrameObservation(sequence, requested, time, time, file.name, hash, bytes.size.toLong())
        }

        override fun sleepMillis(milliseconds: Long) { time += milliseconds * 1_000_000L }
        override fun stopRecording() {}
        override fun cleanupOwned(deadlineNs: Long) = true
    }

    private fun modalAwareDouyinBinding(): String = """{
      "observation_method":"targeted-public-selectors-2",
      "admission_scope":"KNOWN_RECOMMENDATION_FEED_ONLY",
      "blocker_coverage":"NON_EXHAUSTIVE",
      "app_version_code":400601,
      "display_width":1200,
      "display_height":2640,
      "rotation":0,
      "viewport_bounds":[0,0,1200,2478],
      "feed_anchor":{"description":"已选中，推荐，按钮","query_text":"推荐"},
      "gesture_viewport":{"resource_id":"com.ss.android.ugc.aweme:id/viewpager","description":"视频"},
      "blockers":{
        "LOGIN_REQUIRED":[{
          "reason":"FRIENDS_INVITE_LOGIN_MODAL",
          "all_of":[
            {"text":"发现抖音朋友"},
            {"text_contains":"登录后","query_text":"登录后"},
            {"text":"拒绝"},
            {"text":"继续"}
          ]
        }],
        "PAYMENT_REQUIRED":[],
        "PROTOCOL_REQUIRED":[],
        "AD_PRESENT":[],
        "BUSINESS_ERROR":[]
      },
      "uncovered_blocker_kinds":["PAYMENT_REQUIRED","PROTOCOL_REQUIRED","AD_PRESENT","BUSINESS_ERROR"]
    }"""
}
