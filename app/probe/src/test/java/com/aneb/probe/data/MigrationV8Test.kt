package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB v8 迁移 SQL 存在性单测（阶段3 遗留接线）。
 *
 * room-testing 的 MigrationTestHelper 需要 instrumentation（androidTest），本项目
 * 不为此引入 androidTest 基建（与 MIGRATION_6_7 同款取舍，人工验证步骤见其 KDoc）；
 * 本测在 JVM 层锚定迁移合同：版本号 7→8、语句 additive-only（只 ALTER TABLE ADD
 * COLUMN，绝无 DROP/DELETE/CREATE 触碰既有取证数据）、新列与 @Entity 新字段一一对应。
 */
class MigrationV8Test {

    @Test
    fun migrationVersionsAre7To8() {
        assertEquals(7, AnebDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, AnebDatabase.MIGRATION_7_8.endVersion)
    }

    @Test
    fun allStatementsAreAdditiveAlterTableAddColumn() {
        assertTrue(AnebDatabase.MIGRATION_7_8_SQL.isNotEmpty())
        AnebDatabase.MIGRATION_7_8_SQL.forEach { sql ->
            assertTrue("非 additive 语句: $sql", sql.startsWith("ALTER TABLE `"))
            assertTrue("非 ADD COLUMN 语句: $sql", " ADD COLUMN `" in sql)
            // 历史数据是取证资产：绝不允许破坏性语句混入（按 SQL 关键词短语匹配，
            // 避免误中列名子串——如 aqsV02C1DropRate 含 "DROP"）
            val upper = sql.uppercase()
            assertTrue(
                "破坏性语句: $sql",
                !upper.contains("DROP TABLE") && !upper.contains("DROP COLUMN") &&
                    !upper.contains("DELETE FROM") && !upper.contains("CREATE TABLE"),
            )
        }
    }

    @Test
    fun scenarioResultGainsAllBufferingColumns() {
        val expected = mapOf(
            "bufferingScore" to "REAL",
            "bufferingAttribution" to "TEXT",
            "bufferingSampleCount" to "INTEGER",
            "bufferingSawtoothRatio" to "REAL",
            "bufferingNearZeroRatio" to "REAL",
            "bufferingLag1Autocorr" to "REAL",
            "bufferingBatchCount" to "INTEGER",
            "bufferingBestGridUs" to "INTEGER",
            "bufferingJankOverlapRatio" to "REAL",
        )
        expected.forEach { (col, affinity) ->
            assertTrue(
                "缺少 scenario_result 列 $col",
                AnebDatabase.MIGRATION_7_8_SQL.contains(
                    "ALTER TABLE `scenario_result` ADD COLUMN `$col` $affinity"
                ),
            )
        }
    }

    @Test
    fun testRunGainsAllAqsV02Columns() {
        val expected = mapOf(
            "aqsV02Score" to "REAL",
            "aqsV02LowConfidence" to "INTEGER",
            "aqsV02VetoApplied" to "INTEGER",
            "aqsV02NotComputableReason" to "TEXT",
            "aqsV02ContinuityRunId" to "TEXT",
            "aqsV02ContinuityStartedAtEpochMs" to "INTEGER",
            "aqsV02C1DropRate" to "REAL",
            "aqsV02C2RecoveryMs" to "REAL",
        )
        expected.forEach { (col, affinity) ->
            assertTrue(
                "缺少 test_run 列 $col",
                AnebDatabase.MIGRATION_7_8_SQL.contains(
                    "ALTER TABLE `test_run` ADD COLUMN `$col` $affinity"
                ),
            )
        }
        // 语句总数 = 9（buffering）+ 8（aqsV02），防止静默增删
        assertEquals(17, AnebDatabase.MIGRATION_7_8_SQL.size)
    }

    @Test
    fun newColumnsAreNullableWithoutDefaults() {
        // 全部可空列、无默认值（新列 Kotlin 侧默认 null，R-10：历史行 NULL=当时未检测）
        AnebDatabase.MIGRATION_7_8_SQL.forEach { sql ->
            val upper = sql.uppercase()
            assertTrue("新列必须可空: $sql", !upper.contains("NOT NULL"))
            assertTrue("新列不得带默认值: $sql", !upper.contains("DEFAULT"))
        }
    }
}
