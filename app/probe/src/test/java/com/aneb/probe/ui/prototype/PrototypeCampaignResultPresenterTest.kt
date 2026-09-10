package com.aneb.probe.ui.prototype

import android.content.Context
import androidx.room.Room
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.prototype.PrototypeCampaignPersistenceFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PrototypeCampaignResultPresenterTest {
    @Test
    fun `complete Acceptance result presents nine runs as three aggregated conditions`() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
            try {
                val repository = PrototypeCampaignRoomRepository(database)
                val config = PrototypeCampaignPersistenceFixture.campaignConfig(
                    "campaign-result-presenter-acceptance",
                )
                val result = PrototypeCampaignPersistenceFixture.completeAcceptanceCampaign(config)
                repository.save(config, result)
                val stored = requireNotNull(repository.load(config.campaignId))

                val presentation = PrototypeCampaignResultPresenter.present(stored)

                assertEquals("Acceptance", presentation.campaignMode)
                assertEquals("9", presentation.attemptedRuns)
                assertEquals("9", presentation.successfulRuns)
                assertEquals(listOf("基线 Baseline", "慢速 Slow", "不稳定 Unstable"), presentation.conditions.map {
                    it.title
                })
                stored.summary.conditionSummaries.zip(presentation.conditions).forEach {
                        (summary, condition) ->
                    assertEquals("HIGH", condition.confidence)
                    assertEquals(metric(summary.medianTtftMs, " ms"), condition.ttft)
                    assertEquals(metric(summary.medianCompletionMs, " ms"), condition.completion)
                    assertNull(condition.metricNullReason)
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun `complete result is presented only from the validated stored campaign`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        try {
            val repository = PrototypeCampaignRoomRepository(database)
            val config = PrototypeCampaignPersistenceFixture.campaignConfig(CAMPAIGN_ID)
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            repository.save(config, result)
            val stored = requireNotNull(repository.load(CAMPAIGN_ID))

            val presentation = PrototypeCampaignResultPresenter.present(stored)

            assertEquals(CAMPAIGN_ID, presentation.campaignId)
            assertEquals(stored.nodeBaseUrl, presentation.publicationNodeUrl)
            assertEquals("已完成", presentation.status)
            assertEquals("Quick", presentation.campaignMode)
            listOf("TTFT", "Completion", "Stall", "Success rate", "events/s", "不是 0", "不是正式 ANEB 行业评分")
                .forEach { assertTrue("Missing metric boundary: $it", presentation.disclosure.contains(it)) }
            assertEquals("3", presentation.attemptedRuns)
            assertEquals("3", presentation.successfulRuns)
            assertEquals("0", presentation.failedRuns)
            assertEquals("0", presentation.notStartedRuns)
            assertEquals(
                "本地测试结果已保存 · 证据包尚未验证",
                presentation.integrity,
            )
            assertEquals(
                "相对原型指标 RPI（同一轮合成条件对比，不是网络绝对评分）",
                presentation.rpiLabel,
            )
            assertEquals(listOf("基线 Baseline", "慢速 Slow", "不稳定 Unstable"), presentation.conditions.map { it.title })
            val authoritativeBaseline = stored.summary.conditionSummaries.first()
            with(presentation.conditions.first()) {
                assertEquals("baseline_v0.1", conditionId)
                assertEquals(metric(authoritativeBaseline.medianTtftMs, " ms"), ttft)
                assertEquals(metric(authoritativeBaseline.medianCompletionMs, " ms"), completion)
                assertEquals(metric(authoritativeBaseline.medianStreamEventRateEps, " events/s"), eventRate)
                assertEquals(metric(authoritativeBaseline.medianStallCount), stallCount)
                assertEquals(metric(authoritativeBaseline.medianStallDurationMs, " ms"), stallDuration)
                assertEquals(percent(authoritativeBaseline.successRate), successRate)
                assertEquals("100", rpi)
                assertEquals("LOW", confidence)
                assertNull(metricNullReason)
                assertEquals(emptyList<String>(), rpiNullReasons)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `partial result keeps authoritative null zero and machine reasons distinct`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        try {
            val repository = PrototypeCampaignRoomRepository(database)
            val config = PrototypeCampaignPersistenceFixture.campaignConfig(PARTIAL_CAMPAIGN_ID)
            val result = PrototypeCampaignPersistenceFixture.partialQuickCampaign(config)
            repository.save(config, result)
            val stored = requireNotNull(repository.load(PARTIAL_CAMPAIGN_ID))

            val presentation = PrototypeCampaignResultPresenter.present(stored)

            assertEquals("部分完成", presentation.status)
            assertEquals("2", presentation.attemptedRuns)
            assertEquals("1", presentation.successfulRuns)
            assertEquals("1", presentation.failedRuns)
            assertEquals("1", presentation.notStartedRuns)
            with(presentation.conditions[0]) {
                assertEquals("0", stallCount)
                assertEquals("0 ms", stallDuration)
                assertEquals("—", rpi)
                assertNull(metricNullReason)
                assertEquals(listOf("campaign_incomplete"), rpiNullReasons)
            }
            with(presentation.conditions[1]) {
                assertEquals("—", ttft)
                assertEquals("—", completion)
                assertEquals("—", eventRate)
                assertEquals("—", stallCount)
                assertEquals("—", stallDuration)
                assertEquals("0%", successRate)
                assertEquals("—", rpi)
                assertEquals("NONE", confidence)
                assertEquals("stream_interrupted", metricNullReason)
                assertEquals(
                    listOf(
                        "campaign_incomplete",
                        "no_successful_condition_run",
                        "mandatory_metric_missing",
                    ),
                    rpiNullReasons,
                )
            }
            with(presentation.conditions[2]) {
                assertEquals("—", ttft)
                assertEquals("0%", successRate)
                assertEquals("—", rpi)
                assertEquals("not_started", metricNullReason)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `invalid sequence result exposes frozen P009 evidence and recovery guidance`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        try {
            val repository = PrototypeCampaignRoomRepository(database)
            val config = PrototypeCampaignPersistenceFixture.campaignConfig(
                PrototypeCampaignPersistenceFixture.INVALID_SEQUENCE_CAMPAIGN_ID,
            )
            val result = PrototypeCampaignPersistenceFixture.invalidSequenceQuickCampaign(config)
            repository.save(config, result)
            val stored = requireNotNull(repository.load(config.campaignId))

            val presentation = PrototypeCampaignResultPresenter.present(stored)

            val error = presentation.blockingError
            assertNotNull(error)
            requireNotNull(error)
            assertEquals("P009_INVALID_SEQUENCE", error.code)
            assertEquals("事件顺序无效", error.title)
            assertEquals(
                "内容事件存在缺失、重复或乱序。",
                error.cause,
            )
            assertEquals(
                "证据已保留，请报告此实现缺陷。",
                error.action,
            )
            assertEquals(true, error.evidenceRetained)
            assertEquals("invalid_sequence", presentation.conditions.first().metricNullReason)
        } finally {
            database.close()
        }
    }

    @Test
    fun `Acceptance P009 stays visible when earlier successful runs keep aggregate medians`() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
            try {
                val repository = PrototypeCampaignRoomRepository(database)
                val config = PrototypeCampaignPersistenceFixture.campaignConfig(
                    "campaign-result-presenter-acceptance-p009",
                )
                val result = PrototypeCampaignPersistenceFixture
                    .invalidSequenceAcceptanceCampaign(config)
                assertEquals(
                    listOf(
                        "COMPLETE", "COMPLETE", "COMPLETE",
                        "COMPLETE", "COMPLETE", "COMPLETE",
                        "COMPLETE", "INVALID_SEQUENCE", "NOT_STARTED",
                    ),
                    result.runs.map { it.status.name },
                )
                repository.save(config, result)
                val stored = requireNotNull(repository.load(config.campaignId))

                val presentation = PrototypeCampaignResultPresenter.present(stored)

                val slow = presentation.conditions.single { it.conditionId == "slow_v0.1" }
                assertFalse(slow.ttft == "—")
                assertFalse(slow.completion == "—")
                assertEquals("P009_INVALID_SEQUENCE", presentation.blockingError?.code)
                assertTrue(presentation.blockingError?.detail?.contains("Run 8") == true)
                assertTrue(presentation.blockingError?.detail?.contains("slow_v0.1") == true)
            } finally {
                database.close()
            }
        }

    @Test
    fun `condition cards preserve frozen identities and the complete claim boundary`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        try {
            val repository = PrototypeCampaignRoomRepository(database)
            val config = PrototypeCampaignPersistenceFixture.campaignConfig(CLAIM_CAMPAIGN_ID)
            repository.save(config, PrototypeCampaignPersistenceFixture.completeQuickCampaign(config))
            val stored = requireNotNull(repository.load(CLAIM_CAMPAIGN_ID))

            val presentation = PrototypeCampaignResultPresenter.present(stored)

            assertEquals("应用层合成条件", presentation.evidenceBadge)
            assertEquals(
                "Confidence 表示本轮 Quick 的证据完整度，" +
                    "不是行业或网络质量的统计置信区间。",
                presentation.confidenceExplanation,
            )
            assertEquals(
                listOf(
                    "baseline_v0.1" to "基线 Baseline",
                    "slow_v0.1" to "慢速 Slow",
                    "unstable_v0.1" to "不稳定 Unstable",
                ),
                presentation.conditions.map { condition -> condition.conditionId to condition.title },
            )
            assertEquals(
                "此分数将确定性应用层合成条件与同一轮 Baseline 基线比较。" +
                    "它不是正式 ANEB 行业评分，也不代表第三方 AI 应用的" +
                    "网络需求。这些结果来自本机" +
                    "探针的应用层合成测量，不测量或代表 IP 丢包、RAN、核心网、运营商、公网、" +
                    "真实第三方 AI 应用、模型推理、AQS、MOS、网络质量、SLA 或等级。",
                presentation.disclosure.substringAfter("\n\n"),
            )
            listOf("Excellent", "Good", "Poor").forEach { forbiddenGrade ->
                assertFalse(presentation.disclosure.contains(forbiddenGrade))
            }
        } finally {
            database.close()
        }
    }

    private fun metric(value: Double?, suffix: String = ""): String = value?.let {
        String.format(Locale.ROOT, "%.6f", it).trimEnd('0').trimEnd('.') + suffix
    } ?: "—"

    private fun percent(value: Double): String = metric(value * 100.0, "%")

    private companion object {
        const val CAMPAIGN_ID = "campaign-result-presenter-complete"
        const val PARTIAL_CAMPAIGN_ID = "campaign-result-presenter-partial"
        const val CLAIM_CAMPAIGN_ID = "campaign-result-presenter-claims"
    }
}
