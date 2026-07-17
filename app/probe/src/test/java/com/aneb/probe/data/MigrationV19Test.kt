package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationV19Test {
    @Test fun resultEnvelopeTableIsAdditiveAndIndexed() {
        val sql = AnebDatabase.MIGRATION_18_19_SQL
        assertEquals(18, AnebDatabase.MIGRATION_18_19.startVersion)
        assertEquals(19, AnebDatabase.MIGRATION_18_19.endVersion)
        assertEquals(3, sql.size)
        assertTrue(sql.first().startsWith("CREATE TABLE IF NOT EXISTS `result_envelope`"))
        assertTrue(sql.first().contains("`canonicalSha256` TEXT NOT NULL"))
        assertTrue(sql.first().contains("`bodyJson` TEXT NOT NULL"))
        assertTrue(sql.drop(1).all { it.startsWith("CREATE INDEX IF NOT EXISTS") })
        assertTrue(sql.none { it.contains("DROP", true) || it.contains("DELETE", true) })
    }
}
