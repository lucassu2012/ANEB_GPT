package com.aneb.probe.net

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * SocketFactory 包装器：对每个出站测量 TCP 套接字显式开启 `TCP_NODELAY`（禁用 Nagle 算法）。
 *
 * 根因（2026-11-20 P40 Pro ↔ miw2 实证）：OkHttp 4.12 不主动置位 `TCP_NODELAY`，Android 默认
 * 开启 Nagle 算法。Profile 4 实时语音 `/api/v1/realtime-sim` 与 `/api/v1/upload` 均以 20ms
 * 间隔连续发送 160B 小帧（远小于 MSS）；当链路 RTT > 20ms 时，Nagle 会扣留未满 MSS 的小帧
 * 及紧随其后的 `speech_commit` 控制帧，人为放大 M3 上行抖动、M4 TTFB 与 M1' 口到耳时延。
 * 开启 `TCP_NODELAY` 与 Go `net.Dialer`、Cronet 及实时通信客户端口径对齐，同时完整保留底层
 * [delegate]（含 `Network.socketFactory` 绑定网路由）。
 */
internal class TcpNoDelaySocketFactory(
    private val delegate: SocketFactory = getDefault(),
) : SocketFactory() {

    private fun Socket.configureNoDelay(): Socket = apply {
        tcpNoDelay = true
    }

    override fun createSocket(): Socket =
        delegate.createSocket().configureNoDelay()

    override fun createSocket(host: String, port: Int): Socket =
        delegate.createSocket(host, port).configureNoDelay()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).configureNoDelay()

    override fun createSocket(host: InetAddress, port: Int): Socket =
        delegate.createSocket(host, port).configureNoDelay()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).configureNoDelay()
}
