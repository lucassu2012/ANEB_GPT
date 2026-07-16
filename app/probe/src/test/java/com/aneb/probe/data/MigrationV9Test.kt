package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB v9 迁移 SQL 存在性单测（阶段3 GPS 路测）。
 *
 * 取舍同 [MigrationV8Test]：不引 androidTest 基建，JVM 层锚定迁移合同——
 * 版本号 8→9、additive-only、radio_sample 新增 lat/lon/accuracyM 三列且全部
 * 可空无默认值（历史行 NULL＝当时未开路测，R-10 语义）。
 */
class MigrationV9Test {

    @Test
    fun migrationVersionsAre8To9() {
        assertEquals(8, AnebDatabase.MIGRATION_8_9.startVersion)
        assertEquals(9, AnebDatabase.MIGRATION_8_9.endVersion)
    }

    @Test
    fun allStatementsAreAdditiveAlterTableAddColumn() {
        assertTrue(AnebDatabase.MIGRATION_8_9_SQL.isNotEmpty())
        AnebDatabase.MIGRATION_8_9_SQL.forEach { sql ->
            assertTrue("非 additive 语句: $sql", sql.startsWith("ALTER TABLE `radio_sample` ADD COLUMN `"))
            val upper = sql.uppercase()
            assertTrue(
                "破坏性语句: $sql",
                !upper.contains("DROP TABLE") && !upper.contains("DROP COLUMN") &&
                    !upper.contains("DELETE FROM") && !upper.contains("CREATE TABLE"),
            )
        }
    }

    @Test
    fun radioSampleGainsAllGpsColumns() {
        val expected = mapOf(
            "lat" to "REAL",
            "lon" to "REAL",
            "accuracyM" to "REAL",
        )
        expected.forEach { (col, affinity) ->
            assertTrue(
                "缺少 radio_sample 列 $col",
                AnebDatabase.MIGRATION_8_9_SQL.contains(
                    "ALTER TABLE `radio_sample` ADD COLUMN `$col` $affinity"
                ),
            )
        }
        // 语句总数 = 3，防静默增删
        assertEquals(3, AnebDatabase.MIGRATION_8_9_SQL.size)
    }

    @Test
    fun newColumnsAreNullableWithoutDefaults() {
        AnebDatabase.MIGRATION_8_9_SQL.forEach { sql ->
            val upper = sql.uppercase()
            assertTrue("新列必须可空: $sql", !upper.contains("NOT NULL"))
            assertTrue("新列不得带默认值: $sql", !upper.contains("DEFAULT"))
        }
    }
}
