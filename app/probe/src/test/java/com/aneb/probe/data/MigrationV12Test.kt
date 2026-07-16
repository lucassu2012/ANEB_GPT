package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationV12Test {
    @Test
    fun migrationVersionsAre11To12() {
        assertEquals(11, AnebDatabase.MIGRATION_11_12.startVersion)
        assertEquals(12, AnebDatabase.MIGRATION_11_12.endVersion)
    }

    @Test
    fun createsOnlyNewBasicResultTableAndIndex() {
        assertEquals(2, AnebDatabase.MIGRATION_11_12_SQL.size)
        assertTrue(AnebDatabase.MIGRATION_11_12_SQL[0].startsWith("CREATE TABLE IF NOT EXISTS `basic_speed_result`"))
        assertTrue(AnebDatabase.MIGRATION_11_12_SQL[1].startsWith("CREATE INDEX IF NOT EXISTS `index_basic_speed_result_startedAtEpochMs`"))
        AnebDatabase.MIGRATION_11_12_SQL.forEach { sql ->
            val upper = sql.uppercase()
            assertTrue(!upper.contains("DROP "))
            assertTrue(!upper.contains("DELETE FROM"))
            assertTrue(!upper.contains("ALTER TABLE `TEST_RUN`"))
        }
    }

    @Test
    fun nullableMetricsHaveNoDefaults() {
        val create = AnebDatabase.MIGRATION_11_12_SQL.first()
        listOf("downloadMbps", "uploadMbps", "pingMs", "jitterMs", "requestLossRate", "postLoadPingMs")
            .forEach { column ->
                assertTrue(create.contains("`$column` REAL"))
                assertTrue(!create.contains("`$column` REAL NOT NULL"))
            }
    }
}
