package com.aneb.probe.ui.prototype

import com.aneb.probe.prototype.PrototypeNodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeNodeErrorPresentationTest {
    @Test
    fun `incompatible node presents P007 without allowing a campaign`() {
        val incompatible = PrototypeNodeState.ConnectedIncompatible(
            "http://192.168.1.20:18088", "claim scope does not match",
        )
        val presentation = prototypeNodeErrorPresentation(incompatible, null)
        assertEquals("P007_CONTRACT_MISMATCH · 已连接，但协议不兼容", presentation?.title)
        assertTrue(presentation!!.detail.startsWith("claim scope does not match"))
        assertFalse(incompatible.canStartQuick)
    }

    @Test
    fun `unreachable node presents the required P006 code`() {
        val presentation = prototypeNodeErrorPresentation(null, "无法连接此 Prototype 节点。")
        assertEquals("P006_NODE_UNREACHABLE · 节点不可达", presentation?.title)
        assertTrue(presentation!!.detail.startsWith("无法连接此 Prototype 节点。"))
    }

    @Test
    fun `node failures explain recovery without claiming a new result`() {
        val unreachable = prototypeNodeErrorPresentation(null, "无法连接此 Prototype 节点。")!!
        assertTrue(unreachable.detail.contains("请检查节点地址、同一局域网及启动器的防火墙说明。"))
        assertTrue(unreachable.detail.contains("未启动测试，已保存结果未改变。"))
        val incompatible = prototypeNodeErrorPresentation(
            PrototypeNodeState.ConnectedIncompatible("http://192.168.1.20:18088", "claim scope does not match"),
            null,
        )!!
        assertTrue(incompatible.detail.contains("请使用同一发布包中的 APK 和服务端，再检查连接。"))
        assertTrue(incompatible.detail.contains("未启动测试，已保存结果未改变。"))
    }

    @Test
    fun `node failures retain their diagnostic detail without enabling a campaign`() {
        val unreachable = prototypeNodeErrorPresentation(null, "无法连接此 Prototype 节点。")
        assertTrue(unreachable!!.detail.startsWith("无法连接此 Prototype 节点。"))
        val incompatible = PrototypeNodeState.ConnectedIncompatible(
            "http://192.168.1.20:18088", "claim scope does not match",
        )
        assertTrue(prototypeNodeErrorPresentation(incompatible, null)!!.detail.startsWith("claim scope does not match"))
        assertFalse(incompatible.canStartQuick)
        assertNull(prototypeNodeErrorPresentation(null, null))
    }
}
