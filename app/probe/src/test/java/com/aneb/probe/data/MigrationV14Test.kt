package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationV14Test {
    @Test
    fun `v14 migration is additive realtime result table`() {
        assertEquals(13, AnebDatabase.MIGRATION_13_14.startVersion)
        assertEquals(14, AnebDatabase.MIGRATION_13_14.endVersion)
        assertEquals(2, AnebDatabase.MIGRATION_13_14_SQL.size)
        val create = AnebDatabase.MIGRATION_13_14_SQL.first()
        assertTrue(create.startsWith("CREATE TABLE IF NOT EXISTS `realtime_simulation_result`"))
        listOf("totalScore", "grade", "capReason").forEach { nullable ->
            assertTrue(create.contains("`$nullable`"))
            assertFalse(create.contains("`$nullable` REAL NOT NULL"))
            assertFalse(create.contains("`$nullable` TEXT NOT NULL"))
        }
        AnebDatabase.MIGRATION_13_14_SQL.forEach { sql ->
            val normalized = sql.uppercase()
            assertFalse(normalized.contains("DROP "))
            assertFalse(normalized.contains("DELETE "))
        }
    }
}
