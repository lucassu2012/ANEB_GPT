package com.aneb.probe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TestRun::class,
        TokenEventEntity::class,
        ScenarioResultEntity::class,
        EchoSampleEntity::class,
        EnvEventEntity::class,
        RadioSampleEntity::class,
        ReportBodyEntity::class,
        ContinuityResultEntity::class,
        ApiProbeResultEntity::class,
        AbResultEntity::class,
        BasicSpeedResultEntity::class,
        PrototypeCampaignEntity::class,
        PrototypeRunEntity::class,
        PrototypeEvidenceEventEntity::class,
    ],
    // v3：P1-C05/C06 接线——TestRun 扩 run 级字段；新增 scenario_result / echo_sample；
    // token_event 增 scenarioKey/streamIndex 维度
    // v4：P1-C07——scenario_result 增 lowConfidenceKpis；新增 report_body（导出 JSON 源）
    // v5：阶段 2 C 组——新增 continuity_result（连续性实验汇总，additive）
    // v6：阶段 2 合并——新增 api_probe_result（真实 API 探针，claim scope 独立不进 AQS）
    // v7：P2-C05——新增 ab_result（Cronet TCP vs QUIC(h3) A/B 逐样本，stack=cronet，additive）
    // v8：阶段3 遗留接线——scenario_result 增 buffering* 标注列（P1-C08，R-05 不改 validity）；
    //     test_run 增 aqsV02* 并列出分列（阶段2 C03，无 C 数据时全 null=v0.1 语义不变）
    // v9：阶段3 GPS 路测——radio_sample 增 lat/lon/accuracyM 可空列（坐标只入本地，
    //     绝不进上报体；§9.1 隐私边界，路测开关默认关）
    // v10：阶段3 SNI 双通道——test_run 增 sniReachable/sniReachMs/ipReachable/ipReachMs
    //      可空列（run 前连接可达性探测：带 SNI vs bare-IP 的 TLS 握手结果+耗时，additive）
    // v11：阶段3 真机跨网迁移修复——continuity_result 增 c2CrossNetworkRecoveries 可空列
    //      （真机硬切换拆除原绑定网后迁到新默认网恢复的样本数，两种 C2 语义，D-23，additive）
    // v12：B 阶段——新增 basic_speed_result 独立表；不并入 TestRun/AQS。
    // v13：Prototype 0.1——新增 normalized campaign/run/evidence exact3 表。
    version = 13,
    exportSchema = true,
)
abstract class AnebDatabase : RoomDatabase() {
    abstract fun testRunDao(): TestRunDao
    abstract fun tokenEventDao(): TokenEventDao
    abstract fun scenarioResultDao(): ScenarioResultDao
    abstract fun echoSampleDao(): EchoSampleDao
    abstract fun envEventDao(): EnvEventDao
    abstract fun radioSampleDao(): RadioSampleDao
    abstract fun reportBodyDao(): ReportBodyDao
    abstract fun continuityResultDao(): ContinuityResultDao
    abstract fun apiProbeResultDao(): ApiProbeResultDao
    abstract fun abResultDao(): AbResultDao
    abstract fun basicSpeedResultDao(): BasicSpeedResultDao
    abstract fun prototypeCampaignDao(): PrototypeCampaignDao

    companion object {
        @Volatile
        private var instance: AnebDatabase? = null

        /**
         * v6 → v7（P2-C05，additive）：新增 ab_result 表 + runId 索引，不触碰既有表——
         * 已落库的历史取证数据（v6 含 api_probe_result 及之前全部表）原样保留。
         *
         * SQL 与 KSP 生成的 AnebDatabase_Impl.createAllTables 严格一致（列序/affinity/
         * NOT NULL/AUTOINCREMENT/索引名 index_ab_result_runId）：Room 迁移后会按 @Entity
         * 期望 schema 逐列校验，任何偏差 fail-fast 抛 IllegalStateException，不会静默错表。
         *
         * 验证：room-testing 的 MigrationTestHelper 需要 instrumentation（androidTest），
         * JVM 单测覆盖不到；本项目不为此引入 androidTest 基建。人工验证步骤：
         *  1. 安装 db v6 的旧版 APK 并跑一次场景（产生历史 run 数据）；
         *  2. 覆盖安装本版本，启动 app：既有 run/结果仍可见（未被毁库重建）；
         *  3. adb shell "run-as com.aneb.probe sqlite3 databases/aneb-probe.db '.schema ab_result'"
         *     输出与本迁移 CREATE 语句一致；
         *  4. 跑一次 A/B（AB_DB_WRITE 落库成功），logcat 无
         *     "Migration didn't properly handle" 异常。
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ab_result` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`runId` TEXT NOT NULL, " +
                        "`startedAtEpochMs` INTEGER NOT NULL, " +
                        "`serverBase` TEXT NOT NULL, " +
                        "`stack` TEXT NOT NULL, " +
                        "`claimScope` TEXT NOT NULL, " +
                        "`profileId` TEXT NOT NULL, " +
                        "`phaseIndex` INTEGER NOT NULL, " +
                        "`sampleIndex` INTEGER NOT NULL, " +
                        "`groupLabel` TEXT NOT NULL, " +
                        "`bin` TEXT NOT NULL, " +
                        "`negotiatedProtocol` TEXT, " +
                        "`httpCode` INTEGER, " +
                        "`error` TEXT, " +
                        "`ttftMs` REAL, " +
                        "`itlP50Ms` REAL, " +
                        "`itlP95Ms` REAL, " +
                        "`itlSampleCount` INTEGER NOT NULL, " +
                        "`stallCount` INTEGER, " +
                        "`stallRate` REAL, " +
                        "`gapCount` INTEGER NOT NULL, " +
                        "`dupCount` INTEGER NOT NULL, " +
                        "`tokenEventCount` INTEGER NOT NULL, " +
                        "`truncatedEarly` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ab_result_runId` ON `ab_result` (`runId`)"
                )
            }
        }

        /**
         * v7 → v8 的全部语句（阶段3 遗留接线，additive；JVM 单测锚定存在性与 additive-only）：
         *  - scenario_result 增 9 个 buffering* 标注列（P1-C08；R-05 分数只作标注不改 validity）；
         *  - test_run 增 8 个 aqsV02* 并列出分列（阶段2 C03）。
         * 全部为**可空列、无默认值**（新列 Kotlin 侧默认 null）：ALTER TABLE ADD COLUMN 后
         * Room 按 @Entity 期望 schema 逐列校验（列名/affinity/notNull），偏差 fail-fast——
         * 做法与 [MIGRATION_6_7] 一致（列名=字段名，affinity：Double→REAL、
         * Int/Long/Boolean→INTEGER、String→TEXT，与 KSP 生成的期望 schema 一致）。
         * 历史行新列值为 NULL＝"当时未检测/无 v0.2 分支"，与 R-10 null 语义一致。
         */
        internal val MIGRATION_7_8_SQL: List<String> = listOf(
            "ALTER TABLE `scenario_result` ADD COLUMN `bufferingScore` REAL",
            "ALTER TABLE `scenario_result` ADD COLUMN `bufferingAttribution` TEXT",
            "ALTER TABLE `scenario_result` ADD COLUMN `bufferingSampleCount` INTEGER",
            "ALTER TABLE `scenario_result` ADD COLUMN `bufferingSawtoothRatio` REAL",
            "ALTER TABLE `scenario_result` ADD COLUMN `bufferingNearZeroRatio` REAL",
            "ALTER TABLE `scenario_result` ADD COLUMN `bufferingLag1Autocorr` REAL",
            "ALTER TABLE `scenario_result` ADD COLUMN `bufferingBatchCount` INTEGER",
            "ALTER TABLE `scenario_result` ADD COLUMN `bufferingBestGridUs` INTEGER",
            "ALTER TABLE `scenario_result` ADD COLUMN `bufferingJankOverlapRatio` REAL",
            "ALTER TABLE `test_run` ADD COLUMN `aqsV02Score` REAL",
            "ALTER TABLE `test_run` ADD COLUMN `aqsV02LowConfidence` INTEGER",
            "ALTER TABLE `test_run` ADD COLUMN `aqsV02VetoApplied` INTEGER",
            "ALTER TABLE `test_run` ADD COLUMN `aqsV02NotComputableReason` TEXT",
            "ALTER TABLE `test_run` ADD COLUMN `aqsV02ContinuityRunId` TEXT",
            "ALTER TABLE `test_run` ADD COLUMN `aqsV02ContinuityStartedAtEpochMs` INTEGER",
            "ALTER TABLE `test_run` ADD COLUMN `aqsV02C1DropRate` REAL",
            "ALTER TABLE `test_run` ADD COLUMN `aqsV02C2RecoveryMs` REAL",
        )

        /**
         * v7 → v8（阶段3 遗留接线，additive）：只加列不动数据——已落库的历史取证数据
         * （v7 含 ab_result 及之前全部表）原样保留。人工验证步骤同 [MIGRATION_6_7] KDoc
         * （覆盖安装后既有 run 可见、.schema 输出含新列、logcat 无 Migration 异常）。
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_7_8_SQL.forEach(db::execSQL)
            }
        }

        /**
         * v8 → v9 的全部语句（阶段3 GPS 路测，additive；JVM 单测锚定存在性与 additive-only）：
         * radio_sample 增 lat / lon / accuracyM 三个**可空、无默认值**坐标列（Double→REAL，
         * 与 KSP 期望 schema 一致，做法同 [MIGRATION_7_8]）。历史行新列值为 NULL＝
         * "当时未开路测/无 fix"，与 R-10 null 语义一致。隐私边界（设计文档 §9.1）：
         * 坐标只入本地 Room 与本地轨迹导出，绝不进 /results 上报体。
         */
        internal val MIGRATION_8_9_SQL: List<String> = listOf(
            "ALTER TABLE `radio_sample` ADD COLUMN `lat` REAL",
            "ALTER TABLE `radio_sample` ADD COLUMN `lon` REAL",
            "ALTER TABLE `radio_sample` ADD COLUMN `accuracyM` REAL",
        )

        /**
         * v8 → v9（阶段3 GPS 路测，additive）：只加列不动数据。人工验证步骤同
         * [MIGRATION_6_7] KDoc（覆盖安装后既有 run 可见、.schema 输出含新列、
         * logcat 无 Migration 异常）。
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_8_9_SQL.forEach(db::execSQL)
            }
        }

        /**
         * v9 → v10 的全部语句（阶段3 SNI 双通道，additive；JVM 单测锚定存在性与 additive-only）：
         * test_run 增 4 个连接可达性列——sniReachable/ipReachable（TEXT，TLS 握手结果
         * ok/rst/timeout/error:*）与 sniReachMs/ipReachMs（INTEGER，探测耗时 ms）。全部
         * **可空、无默认值**（新列 Kotlin 侧默认 null），做法同 [MIGRATION_8_9]。历史行
         * 与未探测（如 WiFi 路径）新列值 NULL＝"当时未探测"，与 R-10 null 语义一致。
         */
        internal val MIGRATION_9_10_SQL: List<String> = listOf(
            "ALTER TABLE `test_run` ADD COLUMN `sniReachable` TEXT",
            "ALTER TABLE `test_run` ADD COLUMN `sniReachMs` INTEGER",
            "ALTER TABLE `test_run` ADD COLUMN `ipReachable` TEXT",
            "ALTER TABLE `test_run` ADD COLUMN `ipReachMs` INTEGER",
        )

        /**
         * v9 → v10（阶段3 SNI 双通道，additive）：只加列不动数据。人工验证步骤同
         * [MIGRATION_6_7] KDoc（覆盖安装后既有 run 可见、.schema 输出含新列、
         * logcat 无 Migration 异常）。
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_9_10_SQL.forEach(db::execSQL)
            }
        }

        /**
         * v10 → v11 的全部语句（阶段3 真机跨网迁移修复，additive；JVM 单测锚定存在性与
         * additive-only）：continuity_result 增 1 个**可空、无默认值**列
         * c2CrossNetworkRecoveries（Int?→INTEGER，与 KSP 期望 schema 一致，做法同
         * [MIGRATION_9_10]）。历史行（v10 及之前，含模拟器 508ms 基线 run）新列值为 NULL＝
         * "当时未区分 same/cross 语义"，与 R-10 null 语义一致；真机硬切换恢复的 run 记实际
         * 跨网迁移样本数（D-23，两种 C2 语义见 KPI 文档 §5.1）。
         */
        internal val MIGRATION_10_11_SQL: List<String> = listOf(
            "ALTER TABLE `continuity_result` ADD COLUMN `c2CrossNetworkRecoveries` INTEGER",
        )

        /**
         * v10 → v11（阶段3 真机跨网迁移修复，additive）：只加列不动数据——已落库的历史取证
         * 数据（v10 含 continuity_result 及之前全部表）原样保留。人工验证步骤同 [MIGRATION_6_7]
         * KDoc（覆盖安装后既有 run 可见、.schema continuity_result 输出含新列、logcat 无
         * Migration 异常）。
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_10_11_SQL.forEach(db::execSQL)
            }
        }

        /** v11→v12：只新增基础测速结果表与时间索引，既有取证数据原样保留。 */
        internal val MIGRATION_11_12_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `basic_speed_result` (" +
                "`runId` TEXT NOT NULL, " +
                "`startedAtEpochMs` INTEGER NOT NULL, " +
                "`serverBase` TEXT NOT NULL, " +
                "`claimScope` TEXT NOT NULL, " +
                "`profileId` TEXT NOT NULL, " +
                "`profileVersion` TEXT NOT NULL, " +
                "`conclusionPolicyId` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`downloadMbps` REAL, " +
                "`uploadMbps` REAL, " +
                "`pingMs` REAL, " +
                "`jitterMs` REAL, " +
                "`requestLossRate` REAL, " +
                "`postLoadPingMs` REAL, " +
                "`downloadBytes` INTEGER NOT NULL, " +
                "`uploadBytes` INTEGER NOT NULL, " +
                "`transferErrors` TEXT NOT NULL, " +
                "PRIMARY KEY(`runId`))",
            "CREATE INDEX IF NOT EXISTS `index_basic_speed_result_startedAtEpochMs` " +
                "ON `basic_speed_result` (`startedAtEpochMs`)",
        )

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_11_12_SQL.forEach(db::execSQL)
            }
        }

        /** v12→v13: add only the normalized Prototype campaign graph and its run coordinate. */
        internal val MIGRATION_12_13_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `prototype_campaign` (" +
                "`campaignId` TEXT NOT NULL, " +
                "`nodeBaseUrl` TEXT NOT NULL, " +
                "`runUrl` TEXT NOT NULL, " +
                "`capabilityUrl` TEXT NOT NULL, " +
                "`rawCapabilityBody` TEXT NOT NULL, " +
                "`capabilityIdentityJson` TEXT NOT NULL, " +
                "`summaryJson` TEXT NOT NULL, " +
                "PRIMARY KEY(`campaignId`))",
            "CREATE TABLE IF NOT EXISTS `prototype_run` (" +
                "`campaignId` TEXT NOT NULL, " +
                "`runId` TEXT NOT NULL, " +
                "`runIndex` INTEGER NOT NULL, " +
                "`conditionId` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`taskSuccess` INTEGER NOT NULL, " +
                "`scoreEligible` INTEGER NOT NULL, " +
                "`eventsExpected` INTEGER NOT NULL, " +
                "`eventsReceived` INTEGER NOT NULL, " +
                "`failureReason` TEXT, " +
                "`terminalReceiptValid` INTEGER, " +
                "`metricsJson` TEXT, " +
                "PRIMARY KEY(`campaignId`, `runId`), " +
                "FOREIGN KEY(`campaignId`) REFERENCES `prototype_campaign`(`campaignId`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION)",
            "CREATE TABLE IF NOT EXISTS `prototype_evidence_event` (" +
                "`campaignId` TEXT NOT NULL, " +
                "`runId` TEXT NOT NULL, " +
                "`eventOrdinal` INTEGER NOT NULL, " +
                "`eventJson` TEXT NOT NULL, " +
                "PRIMARY KEY(`campaignId`, `runId`, `eventOrdinal`), " +
                "FOREIGN KEY(`campaignId`, `runId`) " +
                "REFERENCES `prototype_run`(`campaignId`, `runId`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_prototype_run_campaignId_runIndex` " +
                "ON `prototype_run` (`campaignId`, `runIndex`)",
        )

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_12_13_SQL.forEach(db::execSQL)
            }
        }

        private val PRODUCTION_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
        )

        private val LEGACY_DESTRUCTIVE_FROM = intArrayOf(1, 2, 3, 4, 5)

        private fun productionBuilder(
            context: Context,
            databaseName: String,
        ): RoomDatabase.Builder<AnebDatabase> = Room.databaseBuilder(
            context.applicationContext,
            AnebDatabase::class.java,
            databaseName,
        )
            .addMigrations(*PRODUCTION_MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(*LEGACY_DESTRUCTIVE_FROM)

        internal fun openUncached(
            context: Context,
            databaseName: String,
        ): AnebDatabase = productionBuilder(context, databaseName).build()

        fun get(context: Context): AnebDatabase =
            instance ?: synchronized(this) {
                instance ?: productionBuilder(context, "aneb-probe.db")
                    .build()
                    .also { instance = it }
            }
    }
}
