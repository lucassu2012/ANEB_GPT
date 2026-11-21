package com.aneb.probe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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
interface ReportBodyDao {
    @Insert
    suspend fun insert(body: ReportBodyEntity)

    @Query("SELECT * FROM report_body WHERE runId = :runId")
    suspend fun forRunRaw(runId: String): ReportBodyEntity?

    @Query("SELECT length(body) FROM report_body WHERE runId = :runId")
    suspend fun bodyLength(runId: String): Int?

    @Query("SELECT substr(body, :startOneBased, :chunkLength) FROM report_body WHERE runId = :runId")
    suspend fun bodySlice(runId: String, startOneBased: Int, chunkLength: Int): String?

    /**
     * 安全读取报告体：Android SQLite CursorWindow 单行上限约 2 MB，超长 report_body 直接
     * SELECT * 会抛 SQLiteBlobTooBigException。先查字符长度，超阈值或捕获异常时自动按
     * 250K 字符分片拼接，不改 Room v15 schema 且可无损读取既有超大历史记录。
     */
    suspend fun forRun(runId: String): ReportBodyEntity? {
        val len = bodyLength(runId) ?: return null
        if (len <= SAFE_DIRECT_READ_CHARS) {
            try {
                return forRunRaw(runId)
            } catch (_: android.database.sqlite.SQLiteBlobTooBigException) {
                // Multi-byte UTF-8 body exceeded 2 MB CursorWindow despite char count <= threshold.
            }
        }
        val sb = StringBuilder(len.coerceAtLeast(0))
        var offset = 1
        while (offset <= len) {
            val slice = bodySlice(runId, offset, CHUNK_READ_CHARS) ?: break
            sb.append(slice)
            if (slice.length < CHUNK_READ_CHARS) break
            offset += slice.length
        }
        return ReportBodyEntity(runId = runId, body = sb.toString())
    }

    companion object {
        private const val SAFE_DIRECT_READ_CHARS = 400_000
        private const val CHUNK_READ_CHARS = 250_000
    }
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

@Dao
interface PrototypeCampaignDao {
    @Query("SELECT campaignId, nodeBaseUrl FROM prototype_campaign ORDER BY campaignId")
    suspend fun savedCampaigns(): List<PrototypeSavedCampaignReference>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCampaign(campaign: PrototypeCampaignEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRuns(runs: List<PrototypeRunEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvidenceEvents(events: List<PrototypeEvidenceEventEntity>)

    @Query("SELECT * FROM prototype_campaign WHERE campaignId = :campaignId")
    suspend fun campaign(campaignId: String): PrototypeCampaignEntity?

    @Query("SELECT * FROM prototype_run WHERE campaignId = :campaignId ORDER BY runIndex")
    suspend fun runs(campaignId: String): List<PrototypeRunEntity>

    @Query(
        "SELECT * FROM prototype_evidence_event " +
            "WHERE campaignId = :campaignId AND runId = :runId ORDER BY eventOrdinal",
    )
    suspend fun evidenceEvents(
        campaignId: String,
        runId: String,
    ): List<PrototypeEvidenceEventEntity>
}

@Dao
interface VoiceResultDao {
    @Insert
    suspend fun insert(result: VoiceResultEntity): Long

    @Query("SELECT * FROM voice_result ORDER BY tsEpochMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<VoiceResultEntity>
}
