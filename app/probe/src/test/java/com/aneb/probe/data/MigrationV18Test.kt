package com.aneb.probe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationV18Test {
    @Test fun gatewayEvidenceColumnsAreAdditiveAndFailClosedByDefault() {
        val sql = AnebDatabase.MIGRATION_17_18_SQL
        assertEquals(12, sql.size)
        assertTrue(sql.all { it.startsWith("ALTER TABLE `network_comprehensive_result` ADD COLUMN") })
        assertTrue(sql.any { it.contains("`gatewayImpairment` INTEGER NOT NULL DEFAULT 0") })
        assertTrue(sql.any { it.contains("`gatewayCleanupAcknowledged` INTEGER NOT NULL DEFAULT 0") })
        assertTrue(sql.any { it.contains("`gatewayBypassObserved` INTEGER NOT NULL DEFAULT 0") })
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) })
    }
}
