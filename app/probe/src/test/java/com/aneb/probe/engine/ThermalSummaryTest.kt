package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THERMAL 接线（D-556）：[ThermalSummary.fold] 的语义守卫 + 对 EnvMonitors 发射格式的反向钉。
 */
class ThermalSummaryTest {

    @Test
    fun `空列表——无一条 THERMAL 事件 = 双 null（无监控）`() {
        assertEquals(ThermalSummary.Env(null, null), ThermalSummary.fold(emptyList()))
    }

    @Test
    fun `只有不可用路径事件 = 双 null——unavailable 与 registration_failed 不含 status=`() {
        assertEquals(
            ThermalSummary.Env(null, null),
            ThermalSummary.fold(
                listOf("power_manager_unavailable", "listener_registration_failed: boom"),
            ),
        )
    }

    @Test
    fun `initial_unknown（初值读取失败的诚实标记）不计入——只有它时=双 null`() {
        assertEquals(
            ThermalSummary.Env(null, null),
            ThermalSummary.fold(listOf("initial_unknown: status_read_failed")),
        )
        assertEquals(
            "初值失败但监听器活着——真事件照常折叠",
            ThermalSummary.Env("light", 0),
            ThermalSummary.fold(listOf("initial_unknown: status_read_failed", "status=light polluting=false")),
        )
    }

    @Test
    fun `只有初始 none = (none, 0)——0 是真实读数（监控在位且全程干净），非 R-10 伪装`() {
        assertEquals(
            ThermalSummary.Env("none", 0),
            ThermalSummary.fold(listOf("initial status=none polluting=false")),
        )
    }

    @Test
    fun `未识别或大小写错误的状态字即使带 polluting=true 也不得累加污染计数`() {
        assertEquals(
            ThermalSummary.Env(null, null),
            ThermalSummary.fold(listOf("status=unknown_word polluting=true", "status=SEVERE polluting=true")),
        )
        assertEquals(
            ThermalSummary.Env("none", 0),
            ThermalSummary.fold(
                listOf(
                    "initial status=none polluting=false",
                    "status=unknown_word polluting=true",
                    "status=SEVERE polluting=true",
                ),
            ),
        )
    }

    @Test
    fun `max 折叠取最烈一档且与事件次序无关；moderate 未到 SEVERE，污染计数保持 0`() {
        val details = listOf(
            "initial status=none polluting=false",
            "status=moderate polluting=false",
            "status=light polluting=false",
        )
        assertEquals(ThermalSummary.Env("moderate", 0), ThermalSummary.fold(details))
        assertEquals(
            "max 必须与事件次序无关",
            ThermalSummary.Env("moderate", 0),
            ThermalSummary.fold(details.reversed()),
        )
    }

    @Test
    fun `污染计数只数 polluting=true 的事件——critical 压过 severe，计数 2`() {
        assertEquals(
            ThermalSummary.Env("critical", 2),
            ThermalSummary.fold(
                listOf(
                    "initial status=none polluting=false",
                    "status=severe polluting=true",
                    "status=critical polluting=true",
                    "status=light polluting=false",
                ),
            ),
        )
    }

    @Test
    fun `发射端格式反向钉——EnvMonitors emitThermal 的模板串必须原样在源码里`() {
        val src = repoFile("app/probe/src/main/java/com/aneb/probe/engine/EnvMonitors.kt")
            .readText(Charsets.UTF_8)
        assertTrue(
            "EnvMonitors.emitThermal 的 detail 模板改了——ThermalSummary.DETAIL_FORMAT 的正则" +
                "会静默解析不到、一切 run 折成双 null（谎称无监控）。两处必须一起改。",
            src.contains("\${prefix}status=\${thermalName(status)} polluting=\$polluting"),
        )
        assertTrue(
            "EnvMonitors 的 initial_unknown 诚实标记没了——初值读取失败将重新伪装成正常事件或整个消失。",
            src.contains("\"initial_unknown: status_read_failed\""),
        )
    }

    @Test
    fun `调用点钉——TestEngine 必须把 fold 结果真的传给 build（函数级守卫防不住这一面）`() {
        val src = repoFile("app/probe/src/main/java/com/aneb/probe/engine/TestEngine.kt")
            .readText(Charsets.UTF_8)
        assertTrue(
            "TestEngine 不再把 ThermalSummary.fold(thermalDetails…) 传给 ResultReporter.build——" +
                "env 块将从一切真实 run 上消失且零测试变红。",
            src.contains("env = ThermalSummary.fold(thermalDetails.toList())"),
        )
        assertTrue(
            "THERMAL 事件不再被累计（收集点旁挂被删）——fold 恒收空列表，一切 run 双 null。",
            src.contains("if (ev.type == EnvEventType.THERMAL) thermalDetails.add(ev.detail)"),
        )
    }

    private fun repoFile(relFromRepoRoot: String): File {
        var cur: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (cur != null) {
            val cand = File(cur, relFromRepoRoot)
            if (cand.isFile) return cand
            cur = cur.parentFile
        }
        error("找不到 $relFromRepoRoot（从 user.dir 向上未命中）")
    }
}
