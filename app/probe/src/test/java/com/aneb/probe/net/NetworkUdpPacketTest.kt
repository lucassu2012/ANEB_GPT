package com.aneb.probe.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkUdpPacketTest {
    @Test fun packetRoundTripsIdentity() {
        val packet = NetworkUdpPacket.encode(42, 123456789L, 256)
        val decoded = NetworkUdpPacket.decode(packet, packet.size)
        assertEquals(42, decoded?.seq)
        assertEquals(123456789L, decoded?.sentAtNanos)
    }

    @Test fun unknownPacketIsRejected() {
        assertNull(NetworkUdpPacket.decode(ByteArray(32), 32))
    }
}
