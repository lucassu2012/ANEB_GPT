package com.aneb.probe.engine

import com.aneb.probe.data.ContinuityResultEntity
import com.aneb.probe.scoring.AqsScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 阶段2 C03 接线单测：AQS v0.2 的 continuity 数据可用性判定（24h 窗口、
 * C1/C2 齐备才算可用、多候选取最新、未来脏数据剔除）与 C 组 KpiValue 映射。
 * 出分数学本身由 AqsScorerV02Test 锚定，这里只测接线选择/映射合同。
 */
class AqsV02GateTest {

    private val now = 1_752_000_000_000L

    private fun continuity(
        runId: String = "c-run-1",
        startedAtEpochMs: Long = now - 60_000L,
        c1: Double? = 0.01,
        c2: Double? = 1500.0,
        segmentsTotal: Int = 4,
        recoveryMsCsv: String = "1200.0,1500.0,1800.0",
    ) = ContinuityResultEntity(
        runId = runId,
        startedAtEpochMs = startedAtEpochMs,
        serverBase = "http://10.0.2.2:8443",
        transport = "auto",
        tokens = 1200,
        rateTps = 40.0,
        segmentsTotal = segmentsTotal,
        abnormalDisconnects = 1,
        c1DropRate = c1,
        c1Grade = null,
        recoveryMsCsv = recoveryMsCsv,
        c2RecoveryMsP50 = c2,
        c2Grade = null,
        c3LadderCsv = "",
        c3FunctionalOnly = true,
        pathChangeEvents = 0,
        status = "completed",
        aqsVersionCandidate = "aqs-v0.2",
    )

    // ---------- 可用性判定 ----------

    @Test
    fun selectPicksLatestUsableWithin24h() {
        val old = continuity(runId = "old", startedAtEpochMs = now - 10_000_000L)
        val newer = continuity(runId = "newer", startedAtEpochMs = now - 1_000_000L)
        assertEquals("newer", AqsV02Gate.select(listOf(old, newer), now)!!.runId)
    }

    @Test
    fun selectRejectsOlderThan24hAndFutureRows() {
        val expired = continuity(startedAtEpochMs = now - AqsV02Gate.CONTINUITY_MAX_AGE_MS - 1L)
        val future = continuity(startedAtEpochMs = now + 1L) // 墙钟脏数据
        assertNull(AqsV02Gate.select(listOf(expired, future), now))
        // 恰在窗口边界（含）→ 可用
        val boundary = continuity(startedAtEpochMs = now - AqsV02Gate.CONTINUITY_MAX_AGE_MS)
        assertEquals(boundary.runId, AqsV02Gate.select(listOf(boundary), now)!!.runId)
    }

    @Test
    fun selectRequiresBothC1AndC2NonNull() {
        // C1/C2 任一缺失（R-10 null 语义）→ 不算可用 → 只显 v0.1（语义不变）
        val noC1 = continuity(runId = "no-c1", c1 = null)
        val noC2 = continuity(runId = "no-c2", c2 = null, startedAtEpochMs = now - 1L)
        assertNull(AqsV02Gate.select(listOf(noC1, noC2), now))
        // 较旧但齐备的一条胜过较新但缺失的
        val usableOlder = continuity(runId = "usable", startedAtEpochMs = now - 5_000_000L)
        assertEquals("usable", AqsV02Gate.select(listOf(noC2, usableOlder), now)!!.runId)
    }

    @Test
    fun selectEmptyCandidatesYieldsNull() {
        assertNull(AqsV02Gate.select(emptyList(), now))
    }

    // ---------- C 组映射 ----------

    @Test
    fun toContinuityKpiMapsValuesAndSampleCounts() {
        val kpi = AqsV02Gate.toContinuityKpi(continuity())
        assertEquals(0.01, kpi.c1SessionDropRate.value!!, 1e-12)
        assertEquals("ratio", kpi.c1SessionDropRate.unit)
        assertEquals(4, kpi.c1SessionDropRate.sampleCount) // segmentsTotal
        assertEquals(1500.0, kpi.c2RecoveryMs.value!!, 1e-12)
        assertEquals("ms", kpi.c2RecoveryMs.unit)
        assertEquals(3, kpi.c2RecoveryMs.sampleCount) // recoveryMsCsv 3 项
        // 空 csv → 0 样本
        assertEquals(0, AqsV02Gate.recoverySampleCount(continuity(recoveryMsCsv = "")))
    }

    @Test
    fun selectedEntityScoresAsV02ThroughExistingScorerEntry() {
        // 接线端到端：select→toContinuityKpi→AqsScorer 既有 v0.2 入口，版本号透出 v0.2
        val chosen = AqsV02Gate.select(listOf(continuity(c1 = 0.005, c2 = 1000.0)), now)!!
        val kpi = AqsV02Gate.toContinuityKpi(chosen)
        assertEquals(85.0, AqsScorer.C1_ANCHORS.score(kpi.c1SessionDropRate.value!!), 1e-9)
        assertEquals(AqsScorer.AQS_VERSION_V02, "aqs-v0.2")
    }
}
