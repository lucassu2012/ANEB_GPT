package com.aneb.probe.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ReachabilityProbe.deriveE01Pair 纯逻辑单测（阶段3 SNI 双通道）。
 * 网络探测本身需真机，JVM 层只锚定基址推导：E-01 的 sslip 主机名/bare-IP
 * 互推为 (带 SNI, bare-IP) 一对（保端口）；非 E-01 目标返回 null（不探测）。
 */
class ReachabilityProbeTest {

    private val sni = ReachabilityProbe.E01_SNI_HOST
    private val ip = ReachabilityProbe.E01_IP

    @Test
    fun `sslip hostname base derives both endpoints with port`() {
        val pair = ReachabilityProbe.deriveE01Pair("https://$sni:8443")
        assertEquals("https://$sni:8443" to "https://$ip:8443", pair)
    }

    @Test
    fun `bare-IP base derives both endpoints with port`() {
        val pair = ReachabilityProbe.deriveE01Pair("https://$ip:8443")
        assertEquals("https://$sni:8443" to "https://$ip:8443", pair)
    }

    @Test
    fun `trailing slash tolerated`() {
        val pair = ReachabilityProbe.deriveE01Pair("https://$ip:8443/")
        assertEquals("https://$sni:8443" to "https://$ip:8443", pair)
    }

    @Test
    fun `no explicit port omits port suffix`() {
        val pair = ReachabilityProbe.deriveE01Pair("https://$sni")
        assertEquals("https://$sni" to "https://$ip", pair)
    }

    @Test
    fun `non-E01 host returns null`() {
        assertNull(ReachabilityProbe.deriveE01Pair("https://example.com:8443"))
        assertNull(ReachabilityProbe.deriveE01Pair("https://10.0.2.2:8443"))
        assertNull(ReachabilityProbe.deriveE01Pair("http://localhost:8080"))
    }

    @Test
    fun `garbage base returns null`() {
        assertNull(ReachabilityProbe.deriveE01Pair(""))
        assertNull(ReachabilityProbe.deriveE01Pair("not-a-url"))
    }
}
