package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB v10 迁移 SQL 存在性单测（阶段3 SNI 双通道连接可达性）。
 *
 * 取舍同 [MigrationV9Test]：不引 androidTest 基建，JVM 层锚定迁移合同——
 * 版本号 9→10、additive-only、test_run 新增 sniReachable/sniReachMs/
 * ipReachable/ipReachMs 四列且全部可空无默认值（历史行/未探测 NULL，R-10 语义）。
 */
class MigrationV10Test {

    @Test
    fun migrationVersionsAre9To10() {
        assertEquals(9, AnebDatabase.MIGRATION_9_10.startVersion)
        assertEquals(10, AnebDatabase.MIGRATION_9_10.endVersion)
    }

    @Test
    fun allStatementsAreAdditiveAlterTableAddColumn() {
        assertTrue(AnebDatabase.MIGRATION_9_10_SQL.isNotEmpty())
        AnebDatabase.MIGRATION_9_10_SQL.forEach { sql ->
            assertTrue("非 additive 语句: $sql", sql.startsWith("ALTER TABLE `test_run` ADD COLUMN `"))
            val upper = sql.uppercase()
            assertTrue(
                "破坏性语句: $sql",
                !upper.contains("DROP TABLE") && !upper.contains("DROP COLUMN") &&
                    !upper.contains("DELETE FROM") && !upper.contains("CREATE TABLE"),
            )
        }
    }

    @Test
    fun testRunGainsAllReachabilityColumns() {
        val expected = mapOf(
            "sniReachable" to "TEXT",
            "sniReachMs" to "INTEGER",
            "ipReachable" to "TEXT",
            "ipReachMs" to "INTEGER",
        )
        expected.forEach { (col, affinity) ->
            assertTrue(
                "缺少 test_run 列 $col",
                AnebDatabase.MIGRATION_9_10_SQL.contains(
                    "ALTER TABLE `test_run` ADD COLUMN `$col` $affinity"
                ),
            )
        }
        assertEquals(4, AnebDatabase.MIGRATION_9_10_SQL.size)
    }

    @Test
    fun newColumnsAreNullableWithoutDefaults() {
        AnebDatabase.MIGRATION_9_10_SQL.forEach { sql ->
            val upper = sql.uppercase()
            assertTrue("新列必须可空: $sql", !upper.contains("NOT NULL"))
            assertTrue("新列不得带默认值: $sql", !upper.contains("DEFAULT"))
        }
    }
}
