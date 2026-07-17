package com.aneb.probe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TestRunDao {
    @Insert
    suspend fun insert(run: TestRun)

    @Query("SELECT * FROM test_run ORDER BY startedAtEpochMs DESC")
    suspend fun all(): List<TestRun>

    @Query("SELECT * FROM test_run WHERE runId = :runId")
    suspend fun byId(runId: String): TestRun?
}

@Dao
interface BasicSpeedResultDao {
    @Insert
    suspend fun insert(result: BasicSpeedResultEntity)

    @Query("SELECT * FROM basic_speed_result ORDER BY startedAtEpochMs DESC")
    suspend fun all(): List<BasicSpeedResultEntity>

    @Query("SELECT * FROM basic_speed_result WHERE runId = :runId")
    suspend fun byId(runId: String): BasicSpeedResultEntity?
}

@Dao
interface NetworkComprehensiveResultDao {
    @Insert
    suspend fun insert(result: NetworkComprehensiveResultEntity)

    @Query("SELECT * FROM network_comprehensive_result ORDER BY startedAtEpochMs DESC")
    suspend fun all(): List<NetworkComprehensiveResultEntity>

    @Query("SELECT * FROM network_comprehensive_result WHERE runId = :runId")
    suspend fun byId(runId: String): NetworkComprehensiveResultEntity?
}

@Dao
interface TokenSimulationResultDao {
    @Insert
    suspend fun insert(result: TokenSimulationResultEntity)

    @Query("SELECT * FROM token_simulation_result ORDER BY startedAtEpochMs DESC")
    suspend fun all(): List<TokenSimulationResultEntity>

    @Query("SELECT * FROM token_simulation_result WHERE runId = :runId")
    suspend fun byId(runId: String): TokenSimulationResultEntity?
}

@Dao
interface RealtimeSimulationResultDao {
    @Insert
    suspend fun insert(result: RealtimeSimulationResultEntity)

    @Query("SELECT * FROM realtime_simulation_result ORDER BY startedAtEpochMs DESC")
    suspend fun all(): List<RealtimeSimulationResultEntity>

    @Query("SELECT * FROM realtime_simulation_result WHERE runId = :runId")
    suspend fun byId(runId: String): RealtimeSimulationResultEntity?
}

@Dao
interface ResultEnvelopeDao {
    @Insert
    suspend fun insert(result: ResultEnvelopeEntity)

    @Query("SELECT * FROM result_envelope ORDER BY startedAtEpochMs DESC")
    suspend fun all(): List<ResultEnvelopeEntity>

    @Query("SELECT * FROM result_envelope WHERE runId = :runId")
    suspend fun byId(runId: String): ResultEnvelopeEntity?
}

@Dao
interface ReportBodyDao {
    @Insert
    suspend fun insert(body: ReportBodyEntity)

    @Query("SELECT * FROM report_body WHERE runId = :runId")
    suspend fun forRun(runId: String): ReportBodyEntity?
}

@Dao
interface TokenEventDao {
    /** 阶段 1 起：phase 结束后一次性批量事务写入（读循环内禁逐条写，R-16/§4.10）。 */
    @Insert
    suspend fun insertAll(events: List<TokenEventEntity>)

    @Query("SELECT * FROM token_event WHERE runId = :runId ORDER BY seq")
    suspend fun forRun(runId: String): List<TokenEventEntity>

    @Query("SELECT COUNT(*) FROM token_event WHERE runId = :runId")
    suspend fun countForRun(runId: String): Long
}

@Dao
interface ScenarioResultDao {
    @Insert
    suspend fun insert(result: ScenarioResultEntity): Long

    @Query("SELECT * FROM scenario_result WHERE runId = :runId ORDER BY orderIndex")
    suspend fun forRun(runId: String): List<ScenarioResultEntity>

    @Query("SELECT * FROM scenario_result ORDER BY runId, orderIndex")
    suspend fun all(): List<ScenarioResultEntity>
}

@Dao
interface EchoSampleDao {
    /** clock_sync phase 结束后批量落库（R-16：循环内禁逐条写） */
    @Insert
    suspend fun insertAll(samples: List<EchoSampleEntity>)

    @Query("SELECT * FROM echo_sample WHERE runId = :runId ORDER BY id")
    suspend fun forRun(runId: String): List<EchoSampleEntity>
}

@Dao
interface ContinuityResultDao {
    @Insert
    suspend fun insert(result: ContinuityResultEntity)

    @Query("SELECT * FROM continuity_result WHERE runId = :runId")
    suspend fun forRun(runId: String): ContinuityResultEntity?

    @Query("SELECT * FROM continuity_result ORDER BY startedAtEpochMs DESC")
    suspend fun all(): List<ContinuityResultEntity>

    /** AQS v0.2 数据可用性查询（阶段2 C03 接线）：最近窗口内的 continuity 结果，新→旧 */
    @Query("SELECT * FROM continuity_result WHERE startedAtEpochMs >= :sinceEpochMs ORDER BY startedAtEpochMs DESC")
    suspend fun since(sinceEpochMs: Long): List<ContinuityResultEntity>
}

@Dao
interface ApiProbeResultDao {
    @Insert
    suspend fun insert(result: ApiProbeResultEntity): Long

    @Query("SELECT * FROM api_probe_result ORDER BY startedAtEpochMs DESC")
    suspend fun all(): List<ApiProbeResultEntity>

    @Query("SELECT * FROM api_probe_result ORDER BY startedAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ApiProbeResultEntity>
}

@Dao
interface AbResultDao {
    /** run 结束一次性批量写入（读循环内禁逐条写，R-16） */
    @Insert
    suspend fun insertAll(results: List<AbResultEntity>)

    @Query("SELECT * FROM ab_result WHERE runId = :runId ORDER BY sampleIndex")
    suspend fun forRun(runId: String): List<AbResultEntity>

    @Query("SELECT * FROM ab_result ORDER BY startedAtEpochMs DESC, sampleIndex")
    suspend fun all(): List<AbResultEntity>
}

@Dao
interface EnvEventDao {
    /** 环境事件低频（热/省电/切卡/路径），可逐条写；批量接口供 phase 末统一落库 */
    @Insert
    suspend fun insert(event: EnvEventEntity)

    @Insert
    suspend fun insertAll(events: List<EnvEventEntity>)

    @Query("SELECT * FROM env_event WHERE runId = :runId ORDER BY tsNanos")
    suspend fun forRun(runId: String): List<EnvEventEntity>
}

@Dao
interface RadioSampleDao {
    /** 1Hz 采样统一批量落库（读/采样循环内禁逐条写，R-16/§4.10） */
    @Insert
    suspend fun insertAll(samples: List<RadioSampleEntity>)

    @Query("SELECT * FROM radio_sample WHERE runId = :runId ORDER BY tsNanos")
    suspend fun forRun(runId: String): List<RadioSampleEntity>

    @Query("SELECT COUNT(*) FROM radio_sample WHERE runId = :runId AND stale = 1")
    suspend fun staleCountForRun(runId: String): Long

    @Query("SELECT * FROM radio_sample WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY tsNanos")
    suspend fun withCoordinates(): List<RadioSampleEntity>
}
