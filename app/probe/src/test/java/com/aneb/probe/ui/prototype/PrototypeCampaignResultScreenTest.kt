package com.aneb.probe.ui.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class PrototypeCampaignResultScreenTest {
    @Test
    fun `publication acknowledgement removes only the warning and keeps interrupted measurements`() {
        val interruption = PrototypeCampaignBlockingErrorPresentation(
            code = "P008_STREAM_INTERRUPTED",
            title = "数据流中断",
            cause = "No terminal receipt",
            action = "Keep partial evidence",
            detail = "40/120 个事件已保留",
            evidenceRetained = true,
        )
        val original = PrototypeCampaignResultLoadState.Ready(
            campaignId = "saved-interrupted",
            presentation = resultPresentation("部分完成").copy(blockingError = interruption),
            publicationWarning = "P018_EVIDENCE_PUBLICATION_FAILED",
        )

        val acknowledged = original.withConfirmedPublication() as PrototypeCampaignResultLoadState.Ready

        assertEquals(null, acknowledged.publicationWarning)
        assertTrue(acknowledged.presentation.integrity.contains("原节点已确认发布"))
        assertTrue(acknowledged.presentation.integrity.contains("设备 ZIP 仍未验证"))
        assertEquals(
            original.presentation,
            acknowledged.presentation.copy(integrity = original.presentation.integrity),
        )
        assertEquals("P018_EVIDENCE_PUBLICATION_FAILED", original.publicationWarning)
    }

    @Test
    fun `action state projection controls both actions and exact status message`() {
        val expected = listOf(
            Triple(PrototypeCampaignResultActionState.Idle, true, null),
            Triple(PrototypeCampaignResultActionState.Exporting, false, "正在导出…"),
            Triple(PrototypeCampaignResultActionState.PreparingShare, false, "正在准备分享…"),
            Triple(PrototypeCampaignResultActionState.Publishing, false, "正在重试发布证据…"),
            Triple(PrototypeCampaignResultActionState.Published, true, "原节点已确认收到并发布证据；不表示测试成功。"),
            Triple(
                PrototypeCampaignResultActionState.PublicationFailed,
                true,
                "P018 · 发布失败，本地证据已保留。恢复原节点后重试。",
            ),
            Triple(PrototypeCampaignResultActionState.Saved, true, "已保存到 Downloads/ANEB"),
            Triple(PrototypeCampaignResultActionState.ShareOpened, true, "已打开分享面板"),
            Triple(
                PrototypeCampaignResultActionState.ShareUnavailable,
                true,
                "已保存，但暂时无法分享",
            ),
            Triple(PrototypeCampaignResultActionState.Failed, true, "导出失败"),
        )

        expected.forEach { (state, actionsEnabled, message) ->
            val presentation = prototypeCampaignResultActionPresentation(state)
            assertEquals(actionsEnabled, presentation.actionsEnabled)
            assertEquals(message, presentation.message)
        }
    }

    @Test
    fun `ready actions use the independent action state projection`() {
        val screen = source("ui/prototype/PrototypeCampaignResultScreen.kt")

        assertTrue(
            screen.contains(
                "actionState: PrototypeCampaignResultActionState = " +
                    "PrototypeCampaignResultActionState.Idle",
            ),
        )
        assertTrue(
            screen.contains(
                "val actionPresentation = prototypeCampaignResultActions(loadState, actionState)",
            ),
        )
        assertTrue(screen.contains("actionPresentation = requireNotNull(actionPresentation)"))
        assertEquals(
            3,
            Regex("enabled = actionPresentation\\.actionsEnabled")
                .findAll(screen)
                .count(),
        )
        assertTrue(screen.contains("actionPresentation.message?.let"))
    }

    @Test
    fun `only ready load states expose the same action contract for complete and partial results`() {
        val complete = PrototypeCampaignResultLoadState.Ready(
            campaignId = "campaign-complete",
            presentation = resultPresentation("已完成"),
        )
        val partial = PrototypeCampaignResultLoadState.Ready(
            campaignId = "campaign-partial",
            presentation = resultPresentation("部分完成"),
        )
        val expectedIdle = prototypeCampaignResultActionPresentation(
            PrototypeCampaignResultActionState.Idle,
        )

        assertEquals(
            null,
            prototypeCampaignResultActions(
                PrototypeCampaignResultLoadState.Loading("campaign-loading"),
                PrototypeCampaignResultActionState.Idle,
            ),
        )
        assertEquals(
            null,
            prototypeCampaignResultActions(
                PrototypeCampaignResultLoadState.Unavailable("campaign-unavailable"),
                PrototypeCampaignResultActionState.Failed,
            ),
        )
        assertEquals(
            expectedIdle,
            prototypeCampaignResultActions(complete, PrototypeCampaignResultActionState.Idle),
        )
        assertEquals(
            expectedIdle,
            prototypeCampaignResultActions(partial, PrototypeCampaignResultActionState.Idle),
        )
        assertEquals(
            "导出失败",
            prototypeCampaignResultActions(
                complete,
                PrototypeCampaignResultActionState.Failed,
            )?.message,
        )
    }

    @Test
    fun `result screen renders validated states and ready actions`() {
        val screen = source("ui/prototype/PrototypeCampaignResultScreen.kt")
        val activity = source("ui/MainActivity.kt")

        assertTrue(screen.contains("fun PrototypeCampaignResultScreen("))
        assertTrue(screen.contains("loadState: PrototypeCampaignResultLoadState"))
        assertTrue(screen.contains("onExport: () -> Unit"))
        assertTrue(screen.contains("onShare: () -> Unit"))
        assertTrue(screen.contains("BackHandler(onBack = onBack)"))
        assertTrue(screen.contains("AnebTopBar(showBack = true, onBack = onBack)"))
        assertTrue(screen.contains("is PrototypeCampaignResultLoadState.Loading"))
        assertTrue(screen.contains("is PrototypeCampaignResultLoadState.Unavailable"))
        assertTrue(screen.contains("is PrototypeCampaignResultLoadState.Ready"))
        assertTrue(screen.contains("presentation.integrity"))
        assertTrue(screen.contains("publicationWarning = loadState.publicationWarning"))
        assertTrue(screen.contains("publicationWarning?.let"))
        assertTrue(
            screen.contains(
                "P018 · 证据发布失败，本地测试结果仍已保存",
            ),
        )
        assertTrue(screen.contains("presentation.evidenceBadge"))
        assertTrue(screen.contains("presentation.confidenceExplanation"))
        assertTrue(screen.contains("presentation.disclosure"))
        assertTrue(screen.contains("presentation.attemptedRuns"))
        assertTrue(screen.contains("presentation.successfulRuns"))
        assertTrue(screen.contains("presentation.failedRuns"))
        assertTrue(screen.contains("presentation.notStartedRuns"))
        assertTrue(screen.contains("presentation.blockingError?.let"))
        assertTrue(screen.contains("error.code"))
        assertTrue(screen.contains("error.title"))
        assertTrue(screen.contains("error.cause"))
        assertTrue(screen.contains("error.action"))
        assertTrue(screen.contains("error.detail"))
        assertTrue(screen.contains("presentation.conditions.forEach"))
        assertTrue(screen.contains("condition.metricNullReason"))
        assertTrue(screen.contains("condition.rpiNullReasons"))
        assertTrue(screen.contains("Text(\"导出 ZIP\""))
        assertTrue(screen.contains("Text(\"分享 ZIP\""))
        assertTrue(screen.contains("Text(\"返回\""))
        assertTrue(screen.contains("onClick = onExport"))
        assertTrue(screen.contains("onClick = onShare"))
        assertTrue(screen.contains("onClick = onRetryPublication"))
        assertTrue(screen.contains("Text(\"重试发布证据\""))

        assertTrue(activity.contains("PrototypeCampaignResultScreen("))
        assertTrue(activity.contains("prototypeCampaignResultNavigator.dismiss("))
        assertTrue(activity.contains("screen = Screen.PrototypeMode"))
    }

    private fun source(relativePath: String): String {
        val path = listOf(
            Path.of("app/probe/src/main/java/com/aneb/probe/$relativePath"),
            Path.of("src/main/java/com/aneb/probe/$relativePath"),
            Path.of("../../app/probe/src/main/java/com/aneb/probe/$relativePath"),
        ).firstOrNull(Files::isRegularFile) ?: error("source fixture was not found: $relativePath")
        return Files.readAllBytes(path).toString(UTF_8)
    }

    private fun resultPresentation(status: String): PrototypeCampaignResultPresentation =
        PrototypeCampaignResultPresentation(
            campaignId = "campaign-$status",
            status = status,
            campaignMode = "Quick",
            attemptedRuns = "3",
            successfulRuns = "3",
            failedRuns = "0",
            notStartedRuns = "0",
            integrity = "本地测试结果已保存 · 证据包尚未验证",
            evidenceBadge = "应用层合成条件",
            confidenceExplanation = "Evidence completeness only",
            rpiLabel = "Relative Prototype Index",
            disclosure = "Synthetic local probe result",
            conditions = emptyList(),
        )
}
