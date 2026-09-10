package com.aneb.probe.ui.prototype

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeModeScreenConfirmationTest {
    @Test
    fun chineseFirstUseExplainsScopeCountsAndKeepsOriginalActions() {
        val screen = source("ui/prototype/PrototypeModeScreen.kt")
        listOf(
            "手机到所选节点", "不是真实 AI 应用或模型推理测试",
            "Quick 每种条件测 1 次（共 3 次）", "Acceptance 每种条件测 3 次（共 9 次）",
            "基线 Baseline", "慢速 Slow", "不稳定 Unstable", "运营商评级或 SLA",
            "检查连接", "已保存的测试", "取消本轮测试",
        ).forEach { assertTrue("Missing first-use explanation: $it", screen.contains(it)) }
        assertTrue(screen.contains("confirmation.runOrder"))
        assertTrue(screen.contains("Modifier.verticalScroll(rememberScrollState())"))
    }

    @Test
    fun quickSelectionRequiresConfirmationBeforeStarting() {
        var quickStarts = 0
        var acceptanceStarts = 0

        val selected = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.QUICK)

        assertEquals(0, quickStarts)
        assertEquals("Quick", selected.pending?.modeLabel)
        assertEquals(3, selected.pending?.runCount)

        val confirmed = selected.confirm(
            onStartQuick = { quickStarts += 1 },
            onStartAcceptance = { acceptanceStarts += 1 },
        )

        assertEquals(1, quickStarts)
        assertEquals(0, acceptanceStarts)
        assertNull(confirmed.pending)
    }

    @Test
    fun quickConfirmationDisclosesItsFixedOrderAndDuration() {
        val confirmation = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.QUICK)
            .pending
            ?: error("Quick confirmation was not selected")

        assertEquals("B1 → S1 → U1", confirmation.runOrder)
        assertEquals("约 35 秒", confirmation.estimatedDuration)
    }

    @Test
    fun acceptanceConfirmationDisclosesTheFixedCampaignAndEvidenceBoundary() {
        val confirmation = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.ACCEPTANCE)
            .pending
            ?: error("Acceptance confirmation was not selected")

        assertEquals("Acceptance", confirmation.modeLabel)
        assertEquals(9, confirmation.runCount)
        assertEquals(
            "B1 → S1 → U1 → B2 → S2 → U2 → B3 → S3 → U3",
            confirmation.runOrder,
        )
        assertEquals("约 1 分 45 秒", confirmation.estimatedDuration)
        assertEquals(
            "结果保存在本机，保存后可导出" +
                "未验证的五文件 ZIP 备份；它不是节点的正式报告。",
            confirmation.evidenceNotice,
        )
        assertEquals(
            "仅测手机到所选节点的应用层合成流，不代表真实 AI 表现、AQS、运营商评级或 SLA。",
            confirmation.claimBoundary,
        )
    }

    @Test
    fun acceptanceStartsOnlyAfterItsConfirmation() {
        var quickStarts = 0
        var acceptanceStarts = 0
        val selected = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.ACCEPTANCE)

        val confirmed = selected.confirm(
            onStartQuick = { quickStarts += 1 },
            onStartAcceptance = { acceptanceStarts += 1 },
        )

        assertEquals(0, quickStarts)
        assertEquals(1, acceptanceStarts)
        assertNull(confirmed.pending)
    }

    @Test
    fun cancellingTheConfirmationClosesItWithoutStartingEitherCampaign() {
        var quickStarts = 0
        var acceptanceStarts = 0
        val cancelled = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.ACCEPTANCE)
            .cancel()

        val unchanged = cancelled.confirm(
            onStartQuick = { quickStarts += 1 },
            onStartAcceptance = { acceptanceStarts += 1 },
        )

        assertNull(unchanged.pending)
        assertEquals(0, quickStarts)
        assertEquals(0, acceptanceStarts)
    }

    @Test
    fun modeButtonsOnlySelectAndTheConfirmationOwnsTheStartCallbacks() {
        val source = source("ui/prototype/PrototypeModeScreen.kt")

        assertTrue(source.contains("var launchState by remember"))
        assertTrue(source.contains("PrototypeCampaignLaunchMode.QUICK"))
        assertTrue(source.contains("PrototypeCampaignLaunchMode.ACCEPTANCE"))
        assertTrue(source.contains("launchState = launchState.confirm("))
        assertTrue(source.contains("Text(\"开始测试\""))
        assertTrue(source.contains("launchState = launchState.cancel()"))
        assertTrue(source.contains("confirmation.runOrder"))
        assertTrue(source.contains("confirmation.estimatedDuration"))
        assertTrue(source.contains("confirmation.evidenceNotice"))
        assertTrue(source.contains("confirmation.claimBoundary"))
        assertFalse(source.contains("onClick = onStartQuick"))
        assertFalse(source.contains("onClick = onStartAcceptance"))
    }

    private fun source(relativePath: String): String {
        val path = listOf(
            Path.of("app/probe/src/main/java/com/aneb/probe/$relativePath"),
            Path.of("src/main/java/com/aneb/probe/$relativePath"),
            Path.of("../../app/probe/src/main/java/com/aneb/probe/$relativePath"),
        ).firstOrNull(Files::isRegularFile)
            ?: error("source fixture was not found: $relativePath")
        return Files.readAllBytes(path).toString(UTF_8)
    }
}
