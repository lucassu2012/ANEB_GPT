package com.aneb.probe.data

import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationV17Test {
    @Test fun recoveryColumnsAreAdditiveAndPreserveHistoricalRows() {
        val sql = AnebDatabase.MIGRATION_16_17_SQL
        assertTrue(sql.any { it.contains("`impairmentOutageDurationMs` INTEGER") })
        assertTrue(sql.any { it.contains("`recoveryTimeMs` REAL") })
        assertTrue(sql.any { it.contains("`recoveryFailureCount` INTEGER NOT NULL DEFAULT 0") })
        assertTrue(sql.any { it.contains("`postRecoverySuccessRatio` REAL") })
        assertTrue(sql.all { it.startsWith("ALTER TABLE `network_comprehensive_result` ADD COLUMN") })
    }
}
