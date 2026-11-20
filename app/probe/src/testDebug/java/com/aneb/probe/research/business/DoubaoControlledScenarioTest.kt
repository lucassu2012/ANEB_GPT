package com.aneb.probe.research.business

import com.aneb.probe.research.BusinessVideoRawFormat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DoubaoControlledScenarioTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `controlled R and B runs enforce recording before send, write SEND_INTENT before tap, and pass BusinessVideoRawFormat`() {
        val profile = File("src/debug/assets/business-profiles/doubao-chat-single-send-v1.json").readText()
        val plan = DoubaoScenarioProfile.compile(profile)

        for ((cond, mode, rate) in listOf(
            Triple("R", "inspect", 32_000L),
            Triple("B", "limit", 32_000L),
        )) {
            val runId = "doubao_test_$cond"
            val root = File(temporary.root, runId)
            val phone = FakeDoubaoPhone(root)
            val binding = DoubaoRunBinding(
                runId = runId,
                profileSha256 = plan.profileSourceSha256,
                appVersionCode = 15080040L,
                conditionId = cond,
                networkKey = "wifi-default:100:wlan0",
                clockId = "android-elapsed:$runId",
                uiObservationMethod = "doubao-public-selectors-1",
                hostNetworkCondition = VideoHostNetworkBinding(
                    topologyId = "ethernet-winrt-hotspot-20261119",
                    conditionMode = mode,
                    requestSha256 = "a".repeat(64),
                    uplinkInterfaceIndex = 12,
                    apInterfaceIndex = 16,
                    downlinkIpBytesPerSecond = rate,
                ),
            )

            val result = DoubaoControlledScenario(phone) { phone.time }.runOnce(profile, binding, root)

            assertEquals("CAPTURED_AWAITING_REVIEW", result.state)
            assertEquals("OBSERVATION_CAPTURED", result.category)
            assertEquals("1", result.swipeCalls)
            assertTrue(result.ownedCleanup)
            assertNull(result.reason)
            assertEquals(listOf("prepare", "startRecording", "save:pre-send", "sendOnce", "observeUntil", "stopRecording", "cleanupOwned"), phone.steps)
            assertTrue(phone.sendIntentExistedAtSend)
            assertTrue(File(root, "SEND_INTENT.json").isFile)
            assertTrue(File(root, "result.json").isFile)

            val rawObj = Json.parseToJsonElement(File(root, "result.json").readText()).jsonObject
            val attempts = BusinessVideoRawFormat.readAttempts(rawObj)
            assertEquals(1, attempts.size)
            assertEquals(runId, attempts.single().attemptId)
        }
    }

    @Test
    fun `ambiguous false return from sendOnce never retries and still runs owned cleanup`() {
        val profile = File("src/debug/assets/business-profiles/doubao-chat-single-send-v1.json").readText()
        val plan = DoubaoScenarioProfile.compile(profile)
        val root = File(temporary.root, "doubao_send_false")
        val phone = FakeDoubaoPhone(root, sendReturn = false)
        val binding = DoubaoRunBinding(
            runId = root.name,
            profileSha256 = plan.profileSourceSha256,
            appVersionCode = 15080040L,
            conditionId = "B",
            networkKey = "wifi-default:100:wlan0",
            clockId = "android-elapsed:${root.name}",
            uiObservationMethod = "doubao-public-selectors-1",
        )

        val result = DoubaoControlledScenario(phone) { phone.time }.runOnce(profile, binding, root)

        assertEquals("STOPPED", result.state)
        assertEquals("OPERATION_UNKNOWN", result.category)
        assertEquals("SEND_RETURNED_FALSE_NO_RETRY", result.reason)
        assertEquals("UNKNOWN", result.swipeCalls)
        assertTrue(result.ownedCleanup)
        assertEquals(1, phone.steps.count { it == "sendOnce" })
        assertTrue("cleanupOwned" in phone.steps)
    }

    @Test
    fun `cancel file during preparation stops cleanly without calling sendOnce`() {
        val profile = File("src/debug/assets/business-profiles/doubao-chat-single-send-v1.json").readText()
        val plan = DoubaoScenarioProfile.compile(profile)
        val root = File(temporary.root, "doubao_cancelled")
        val phone = FakeDoubaoPhone(root, createCancelInPrepare = true)
        val binding = DoubaoRunBinding(
            runId = root.name,
            profileSha256 = plan.profileSourceSha256,
            appVersionCode = 15080040L,
            conditionId = "R",
            networkKey = "wifi-default:100:wlan0",
            clockId = "android-elapsed:${root.name}",
            uiObservationMethod = "doubao-public-selectors-1",
        )

        val result = DoubaoControlledScenario(phone) { phone.time }.runOnce(profile, binding, root)

        assertEquals("STOPPED", result.state)
        assertEquals("CANCELLED", result.category)
        assertEquals("0", result.swipeCalls)
        assertTrue(result.ownedCleanup)
        assertFalse("sendOnce" in phone.steps)
        assertTrue("cleanupOwned" in phone.steps)
    }

    @Test
    fun `hostNetworkCondition rejects colliding uplink and ap interface indices`() {
        val profile = File("src/debug/assets/business-profiles/doubao-chat-single-send-v1.json").readText()
        val plan = DoubaoScenarioProfile.compile(profile)
        val root = File(temporary.root, "doubao_bad_iface")
        val phone = FakeDoubaoPhone(root)
        val binding = DoubaoRunBinding(
            runId = root.name,
            profileSha256 = plan.profileSourceSha256,
            appVersionCode = 15080040L,
            conditionId = "B",
            networkKey = "wifi-default:100:wlan0",
            clockId = "android-elapsed:${root.name}",
            uiObservationMethod = "doubao-public-selectors-1",
            hostNetworkCondition = VideoHostNetworkBinding(
                topologyId = "ethernet-winrt-hotspot-20261119",
                conditionMode = "limit",
                requestSha256 = "b".repeat(64),
                uplinkInterfaceIndex = 12,
                apInterfaceIndex = 12,
                downlinkIpBytesPerSecond = 32_000L,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DoubaoControlledScenario(phone) { phone.time }.runOnce(profile, binding, root)
        }
        assertFalse(root.exists())
    }

    private class FakeDoubaoPhone(
        private val root: File,
        private val sendReturn: Boolean = true,
        private val createCancelInPrepare: Boolean = false,
    ) : DoubaoControlledScenario.Phone {
        var time = 1_000_000_000L
        val steps = mutableListOf<String>()
        var sendIntentExistedAtSend = false

        override fun prepare(deadlineNs: Long) {
            steps += "prepare"
            time += 500_000_000L
            if (createCancelInPrepare) File(root, "CANCEL").writeText("cancel")
        }

        override fun startRecording(deadlineNs: Long) {
            steps += "startRecording"
            time += 200_000_000L
        }

        override fun saveOriginals(label: String) {
            steps += "save:$label"
            time += 10_000_000L
        }

        override fun sendOnce(): Boolean {
            steps += "sendOnce"
            sendIntentExistedAtSend = File(root, "SEND_INTENT.json").isFile
            time += 60_000_000L
            return sendReturn
        }

        override fun observeUntil(deadlineNs: Long) {
            steps += "observeUntil"
            time = deadlineNs
        }

        override fun stopRecording() {
            steps += "stopRecording"
            time += 100_000_000L
        }

        override fun cleanupOwned(deadlineNs: Long): Boolean {
            steps += "cleanupOwned"
            time += 50_000_000L
            return true
        }
    }
}
