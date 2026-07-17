package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationV16Test {
    @Test fun migrationFreezesSyntheticImpairmentWithoutRewritingHistory() {
        val sql = AnebDatabase.MIGRATION_15_16_SQL
        assertEquals(9, sql.size)
        assertTrue(sql.all { it.startsWith("ALTER TABLE `network_comprehensive_result` ADD COLUMN") })
        assertTrue(sql.any { it.contains("`syntheticImpairment` INTEGER NOT NULL DEFAULT 0") })
        assertTrue(sql.any { it.contains("`impairmentAcknowledged` INTEGER NOT NULL DEFAULT 0") })
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) })
    }
}
