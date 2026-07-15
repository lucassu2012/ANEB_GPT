package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB v11 迁移 SQL 存在性单测（阶段3 真机跨网迁移修复，D-23）。
 *
 * 取舍同 [MigrationV10Test]：不引 androidTest 基建，JVM 层锚定迁移合同——版本号 10→11、
 * additive-only、continuity_result 新增 c2CrossNetworkRecoveries 列且可空无默认值
 * （历史行/实验未进重连 NULL，R-10 语义）。
 */
class MigrationV11Test {

    @Test
    fun migrationVersionsAre10To11() {
        assertEquals(10, AnebDatabase.MIGRATION_10_11.startVersion)
        assertEquals(11, AnebDatabase.MIGRATION_10_11.endVersion)
    }

    @Test
    fun allStatementsAreAdditiveAlterTableAddColumn() {
        assertTrue(AnebDatabase.MIGRATION_10_11_SQL.isNotEmpty())
        AnebDatabase.MIGRATION_10_11_SQL.forEach { sql ->
            assertTrue("非 additive 语句: $sql", sql.startsWith("ALTER TABLE `continuity_result` ADD COLUMN `"))
            val upper = sql.uppercase()
            assertTrue(
                "破坏性语句: $sql",
                !upper.contains("DROP TABLE") && !upper.contains("DROP COLUMN") &&
                    !upper.contains("DELETE FROM") && !upper.contains("CREATE TABLE"),
            )
        }
    }

    @Test
    fun continuityResultGainsCrossNetworkColumn() {
        assertTrue(
            "缺少 continuity_result 列 c2CrossNetworkRecoveries",
            AnebDatabase.MIGRATION_10_11_SQL.contains(
                "ALTER TABLE `continuity_result` ADD COLUMN `c2CrossNetworkRecoveries` INTEGER"
            ),
        )
        assertEquals(1, AnebDatabase.MIGRATION_10_11_SQL.size)
    }

    @Test
    fun newColumnsAreNullableWithoutDefaults() {
        AnebDatabase.MIGRATION_10_11_SQL.forEach { sql ->
            val upper = sql.uppercase()
            assertTrue("新列必须可空: $sql", !upper.contains("NOT NULL"))
            assertTrue("新列不得带默认值: $sql", !upper.contains("DEFAULT"))
        }
    }
}
