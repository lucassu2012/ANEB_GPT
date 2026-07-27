package com.aneb.probe.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkUdpPacketTest {
    @Test fun packetRoundTripsIdentity() {
        val packet = NetworkUdpPacket.encode(RUN_ID, 42, 123456789L, 256)
        val decoded = NetworkUdpPacket.decode(packet, packet.size)
        assertEquals(RUN_ID, decoded?.runId)
        assertEquals(42, decoded?.seq)
        assertEquals(123456789L, decoded?.sentAtNanos)
    }

    @Test fun unknownPacketIsRejected() {
        assertNull(NetworkUdpPacket.decode(ByteArray(32), 32))
    }

    @Test fun legacyPacketPreservesFrozenAneb1Layout() {
        val packet = NetworkUdpPacket.encodeLegacy(9, 1234L, 17)
        val decoded = NetworkUdpPacket.decodeLegacy(packet, packet.size)
        assertEquals("ANEB1", packet.copyOfRange(0, 5).toString(Charsets.US_ASCII))
        assertEquals(9, decoded?.seq)
        assertEquals(1234L, decoded?.sentAtNanos)
        assertNull(NetworkUdpPacket.decode(packet, packet.size))
    }

    private companion object {
        const val RUN_ID = "00000000-0000-4000-8000-000000000003"
    }
}
