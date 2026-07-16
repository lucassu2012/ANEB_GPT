package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationV13Test {
    @Test
    fun `v13 migration is additive token result table`() {
        assertEquals(12, AnebDatabase.MIGRATION_12_13.startVersion)
        assertEquals(13, AnebDatabase.MIGRATION_12_13.endVersion)
        assertEquals(2, AnebDatabase.MIGRATION_12_13_SQL.size)
        val create = AnebDatabase.MIGRATION_12_13_SQL.first()
        assertTrue(create.startsWith("CREATE TABLE IF NOT EXISTS `token_simulation_result`"))
        listOf("totalScore", "grade", "capReason").forEach { nullable ->
            assertTrue(create.contains("`$nullable`"))
            assertFalse(create.contains("`$nullable` REAL NOT NULL"))
            assertFalse(create.contains("`$nullable` TEXT NOT NULL"))
        }
        AnebDatabase.MIGRATION_12_13_SQL.forEach { sql ->
            val normalized = sql.uppercase()
            assertFalse(normalized.contains("DROP "))
            assertFalse(normalized.contains("DELETE "))
            assertFalse(normalized.contains("UPDATE "))
        }
    }
}
