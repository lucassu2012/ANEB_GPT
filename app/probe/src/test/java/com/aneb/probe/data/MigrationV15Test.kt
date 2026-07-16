package com.aneb.probe.data

import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationV15Test {
    @Test fun migrationCreatesIndependentNetworkComprehensiveEvidenceTable() {
        val sql = AnebDatabase.MIGRATION_14_15_SQL.joinToString("\n")
        assertTrue(sql.contains("network_comprehensive_result"))
        assertTrue(sql.contains("`loadedRttMs` REAL"))
        assertTrue(sql.contains("`udpNonReturnRate` REAL"))
        assertTrue(sql.contains("`evidenceJson` TEXT NOT NULL"))
    }
}
