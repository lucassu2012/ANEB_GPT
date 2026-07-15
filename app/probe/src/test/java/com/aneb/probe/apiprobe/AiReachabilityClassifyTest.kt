package com.aneb.probe.apiprobe

import com.aneb.probe.apiprobe.AiReachabilityProbe.Status
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * [AiReachabilityProbe.classify] 纯逻辑单测（mode① 无 key 可达性看板）。
 * 网络探测本身需真机；JVM 层只锚定异常→[Status] 的分类判定（连接层口径，
 * 不看 HTTP 语义）。与 net/ReachabilityProbe 同分类风格。
 */
class AiReachabilityClassifyTest {

    @Test
    fun `ssl handshake failure classifies as RST`() {
        assertEquals(Status.RST, AiReachabilityProbe.classify(SSLHandshakeException("handshake_failure")))
    }

    @Test
    fun `unknown host classifies as DNS_FAIL`() {
        assertEquals(Status.DNS_FAIL, AiReachabilityProbe.classify(UnknownHostException("api.example.com")))
    }

    @Test
    fun `socket timeout classifies as TIMEOUT`() {
        assertEquals(Status.TIMEOUT, AiReachabilityProbe.classify(SocketTimeoutException("connect timed out")))
    }

    @Test
    fun `connection reset IOException classifies as RST`() {
        assertEquals(Status.RST, AiReachabilityProbe.classify(IOException("Connection reset")))
    }

    @Test
    fun `reset match is case-insensitive`() {
        assertEquals(Status.RST, AiReachabilityProbe.classify(IOException("Connection RESET by peer")))
    }

    @Test
    fun `timeout worded IOException classifies as TIMEOUT`() {
        assertEquals(Status.TIMEOUT, AiReachabilityProbe.classify(IOException("read timed out")))
    }

    @Test
    fun `other IOException classifies as ERROR`() {
        assertEquals(Status.ERROR, AiReachabilityProbe.classify(IOException("unexpected end of stream")))
    }

    @Test
    fun `IOException with null message classifies as ERROR`() {
        assertEquals(Status.ERROR, AiReachabilityProbe.classify(IOException()))
    }

    @Test
    fun `non-IO throwable classifies as ERROR`() {
        assertEquals(Status.ERROR, AiReachabilityProbe.classify(IllegalStateException("boom")))
    }
}
