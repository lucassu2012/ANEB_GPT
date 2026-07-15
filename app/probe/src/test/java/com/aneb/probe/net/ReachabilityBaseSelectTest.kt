package com.aneb.probe.net

import com.aneb.probe.net.ReachabilityProbe.DualReach
import com.aneb.probe.net.ReachabilityProbe.Reach
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ReachabilityProbe.preferredMeasureBase] 选路单测（D-25 SNI-RST 自动旁路）。
 *
 * 锚定"仅当 E-01 sslip 主机名 + SNI 被 RST + bare-IP 可达时切 bare-IP，其余一律不变"，
 * 防止回归成"无脑切/永不切"。真机实测正是 sni=rst/ip=ok 触发本切换，run 才出有效 AQS。
 */
class ReachabilityBaseSelectTest {

    private val sniBase = "https://${ReachabilityProbe.E01_SNI_HOST}:8443"   // sslip 主机名
    private val ipBase = "https://${ReachabilityProbe.E01_IP}:8443"         // bare-IP 等价

    private fun reach(sni: String, ip: String) =
        DualReach(sni = Reach(sni, if (sni == "ok") 100L else null), ip = Reach(ip, if (ip == "ok") 100L else null))

    @Test fun `sslip + sni_rst + ip_ok 切到 bare-IP`() {
        // 真机电信蜂窝实测场景：SNI 被 DPI RST、bare-IP 通 → 测量必须走 bare-IP
        assertEquals(ipBase, ReachabilityProbe.preferredMeasureBase(sniBase, reach("rst", "ok")))
    }

    @Test fun `sslip + sni_ok 不切（保留观测真实 SNI 路径）`() {
        assertEquals(sniBase, ReachabilityProbe.preferredMeasureBase(sniBase, reach("ok", "ok")))
    }

    @Test fun `sslip + sni_rst 但 ip 非 ok 不切（不切到坏路）`() {
        assertEquals(sniBase, ReachabilityProbe.preferredMeasureBase(sniBase, reach("rst", "timeout")))
        assertEquals(sniBase, ReachabilityProbe.preferredMeasureBase(sniBase, reach("rst", "error:X")))
    }

    @Test fun `reach 未探测(null) 不切`() {
        assertEquals(sniBase, ReachabilityProbe.preferredMeasureBase(sniBase, null))
    }

    @Test fun `已在 bare-IP 不再切（幂等）`() {
        assertEquals(ipBase, ReachabilityProbe.preferredMeasureBase(ipBase, reach("rst", "ok")))
    }

    @Test fun `非 E-01 目标原样返回`() {
        val other = "https://example.com:8443"
        assertEquals(other, ReachabilityProbe.preferredMeasureBase(other, reach("rst", "ok")))
    }

    @Test fun `带尾斜杠的 sslip 也能识别并切`() {
        assertEquals(ipBase, ReachabilityProbe.preferredMeasureBase("$sniBase/", reach("rst", "ok")))
    }
}
