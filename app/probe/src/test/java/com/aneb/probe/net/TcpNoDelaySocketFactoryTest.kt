package com.aneb.probe.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

class TcpNoDelaySocketFactoryTest {

    @Test
    fun unconnectedSocketEnablesTcpNoDelay() {
        var delegated = false
        val delegate = object : SocketFactory() {
            override fun createSocket(): Socket {
                delegated = true
                return Socket()
            }

            override fun createSocket(host: String, port: Int): Socket = Socket(host, port)
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                Socket(host, port, localHost, localPort)
            override fun createSocket(host: InetAddress, port: Int): Socket = Socket(host, port)
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                Socket(address, port, localAddress, localPort)
        }

        TcpNoDelaySocketFactory(delegate).createSocket().use { socket ->
            assertTrue("Must delegate socket creation to preserve bound network factory", delegated)
            assertTrue("TCP_NODELAY must be enabled on measurement sockets", socket.tcpNoDelay)
        }
    }

    @Test
    fun connectedSocketEnablesTcpNoDelay() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val factory = TcpNoDelaySocketFactory()
            factory.createSocket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                server.accept().use {
                    assertTrue("Connected socket must have TCP_NODELAY enabled", client.tcpNoDelay)
                }
            }
        }
    }
}
