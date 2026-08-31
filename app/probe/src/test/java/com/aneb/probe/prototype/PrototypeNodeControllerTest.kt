package com.aneb.probe.prototype

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PrototypeNodeControllerTest {
    @Test
    fun userConfiguredNodeIsSavedAndCheckedThroughThePrototypeRunUrl() = runBlocking {
        val settings = RecordingNodeSettings()
        var checkedUrl: String? = null
        var checkedTicket: CompatibleNodeTicket? = null
        val controller = PrototypeNodeController(
            settings = settings,
            compatibilityChecker = PrototypeNodeCompatibilityChecker { runUrl ->
                checkedUrl = runUrl
                testTicket(runUrl).also { checkedTicket = it }
            },
        )

        val state = controller.configureAndCheck("  http://10.0.2.2:18088/  ")

        assertEquals("http://10.0.2.2:18088", settings.savedUrl)
        assertEquals(
            "http://10.0.2.2:18088/api/v1/prototype/runs",
            checkedUrl,
        )
        assertTrue(state is PrototypeNodeState.Compatible)
        val compatible = state as PrototypeNodeState.Compatible
        assertEquals("http://10.0.2.2:18088", compatible.nodeBaseUrl)
        assertEquals("prototype-node-test", compatible.capability.serverVersion)
        assertSame(checkedTicket, compatible.ticket)
        assertSame(checkedTicket, controller.ticketForStart("http://10.0.2.2:18088/"))
        assertNull(controller.ticketForStart("http://192.168.1.20:18088"))
        assertTrue(compatible.canStartQuick)

        controller.invalidateTicket()
        assertNull(controller.ticketForStart("http://10.0.2.2:18088"))
    }

    @Test
    fun reachableIncompatibleNodeIsVisibleButCannotStartQuick() = runBlocking {
        val settings = RecordingNodeSettings()
        val controller = PrototypeNodeController(
            settings = settings,
            compatibilityChecker = PrototypeNodeCompatibilityChecker {
                throw PrototypeNodeIncompatibleException("claim scope does not match")
            },
        )

        val state = controller.configureAndCheck("http://192.168.1.20:18088")

        assertTrue(state is PrototypeNodeState.ConnectedIncompatible)
        val incompatible = state as PrototypeNodeState.ConnectedIncompatible
        assertEquals("http://192.168.1.20:18088", incompatible.nodeBaseUrl)
        assertEquals("claim scope does not match", incompatible.message)
        assertFalse(incompatible.canStartQuick)
        assertEquals("http://192.168.1.20:18088", settings.savedUrl)
    }

    @Test
    fun urlEditInvalidatesAnInFlightCheckBeforeItsTicketCanBePublished() = runBlocking {
        val checkEntered = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val controller = PrototypeNodeController(
            settings = RecordingNodeSettings(),
            compatibilityChecker = PrototypeNodeCompatibilityChecker { runUrl ->
                checkEntered.complete(Unit)
                releaseCheck.await()
                testTicket(runUrl)
            },
        )
        val pending = async {
            runCatching { controller.configureAndCheck("http://10.0.2.2:18088") }
        }
        checkEntered.await()

        controller.invalidateTicket()
        releaseCheck.complete(Unit)

        assertTrue(pending.await().exceptionOrNull() is IllegalArgumentException)
        assertNull(controller.ticketForStart("http://10.0.2.2:18088"))
    }

    @Test
    fun urlEditAlsoInvalidatesAnInFlightIncompatibleResult() = runBlocking {
        val checkEntered = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val controller = PrototypeNodeController(
            settings = RecordingNodeSettings(),
            compatibilityChecker = PrototypeNodeCompatibilityChecker {
                checkEntered.complete(Unit)
                releaseCheck.await()
                throw PrototypeNodeIncompatibleException("late incompatible node A")
            },
        )
        val pending = async {
            runCatching { controller.configureAndCheck("http://10.0.2.2:18088") }
        }
        checkEntered.await()

        controller.invalidateTicket()
        releaseCheck.complete(Unit)

        assertTrue(pending.await().exceptionOrNull() is IllegalArgumentException)
        assertNull(controller.ticketForStart("http://10.0.2.2:18088"))
    }

    @Test
    fun cleartextNodePolicyAllowsOnlyLiteralPrivateOrLoopbackAddresses() {
        listOf(
            "http://10.0.2.2:18088",
            "http://127.0.0.1:18088",
            "http://172.16.4.9:18088",
            "http://192.168.42.10:18088",
        ).forEach { input ->
            assertTrue(PrototypeNodeEndpoint.parse(input).runUrl.endsWith("/api/v1/prototype/runs"))
        }
        listOf(
            "http://10.0.2.2",
            "http://8.8.8.8:18088",
            "http://11.0.0.1:18088",
            "http://169.254.1.1:18088",
            "http://100.64.0.1:18088",
            "http://224.0.0.1:18088",
            "http://node.example.com:18088",
            "http://2130706433:18088",
            "http://0177.0.0.1:18088",
            "http://0x7f000001:18088",
            "http://[::1]:18088",
            "http://[::ffff:192.168.1.20]:18088",
            "http://user@192.168.1.20:18088",
            "http://192.168.1.20:18088?target=public.example",
            "http://192.168.1.20:18088#public.example",
            "http://192.168.1.20:18088/api/v1/prototype/runs",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                PrototypeNodeEndpoint.parse(input)
            }
        }
        assertEquals(
            "https://prototype.example.com/api/v1/prototype/runs",
            PrototypeNodeEndpoint.parse("https://prototype.example.com").runUrl,
        )
    }

    @Test
    fun runUrlMustRoundTripToTheExactCapabilityAndRunOrigin() {
        val endpoint = PrototypeNodeEndpoint.parseRunUrl(
            "http://192.168.1.20:18088/api/v1/prototype/runs",
        )

        assertEquals("http://192.168.1.20:18088", endpoint.baseUrl)
        assertEquals(
            "http://192.168.1.20:18088/api/v1/prototype/capabilities",
            endpoint.capabilityUrl,
        )
        listOf(
            "http://192.168.1.20:18088/api/v1/prototype/runs?mode=quick",
            "http://192.168.1.20:18088/api/v1/prototype/capabilities",
            "http://8.8.8.8:18088/api/v1/prototype/runs",
            "http://192.168.1.20/api/v1/prototype/runs",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                PrototypeNodeEndpoint.parseRunUrl(input)
            }
        }
    }

    private class RecordingNodeSettings : PrototypeNodeSettings {
        var savedUrl: String? = null

        override fun loadNodeUrl(): String = savedUrl.orEmpty()

        override fun saveNodeUrl(nodeBaseUrl: String) {
            savedUrl = nodeBaseUrl
        }
    }

    private fun testTicket(runUrl: String): CompatibleNodeTicket {
        val endpoint = PrototypeNodeEndpoint.parseRunUrl(runUrl)
        return CompatibleNodeTicket.fromValidatedCapability(
            endpoint = endpoint,
            rawCapabilityBody = "{\"server_version\":\"prototype-node-test\"}",
            identity = PrototypeCapabilityIdentity(
                schemaVersion = "aneb-prototype-capabilities-0.1",
                productVersion = "prototype-0.1",
                protocolVersion = "prototype-stream-0.1",
                serverVersion = "prototype-node-test",
                serverBinarySha256 = "0".repeat(64),
                claimScope = "application_end_to_end_to_probe_node",
                evidenceMode = "synthetic_application_impairment",
                impairmentLayer = "application",
                profileManifestSha256 =
                    "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc",
                workload = PrototypeCapabilityWorkloadIdentity(
                    id = "streaming_text_reference_v0.1",
                    version = "0.1",
                    contentEventCount = 120,
                ),
                conditions = listOf(
                    condition("baseline_v0.1", 50, "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e"),
                    condition("slow_v0.1", 125, "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062"),
                    condition("unstable_v0.1", 65, "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58"),
                ),
                evidenceSchemaVersion = "aneb-prototype-evidence-0.1",
                scorePolicyId = "rpi-0.1",
                terminalReceiptVersion = "prototype-terminal-receipt-0.1",
            ),
        )
    }

    private fun condition(
        id: String,
        nominalIntervalMs: Int,
        scheduleSha256: String,
    ) = PrototypeCapabilityConditionIdentity(id, "0.1", nominalIntervalMs, scheduleSha256)
}
