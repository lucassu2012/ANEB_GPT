package com.aneb.probe.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TestRunDaoReportStatusTest {
    private lateinit var db: AnebDatabase

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AnebDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun `report status update returns exact row count and persists the safe status`() = runBlocking {
        val dao = db.testRunDao()
        dao.insert(run(runId = "run-existing", reportStatus = "upload_authorized_local"))

        assertEquals(1, dao.updateReportStatus("run-existing", "upload_rejected"))
        assertEquals("upload_rejected", dao.byId("run-existing")?.reportStatus)
        assertEquals(0, dao.updateReportStatus("run-missing", "uploaded"))
    }

    private fun run(runId: String, reportStatus: String) = TestRun(
        runId = runId,
        startedAtEpochMs = 1L,
        serverBase = "https://example.invalid",
        mode = "quick",
        scenarioOrder = "s1_chat,s2_coding_agent,s3_multimodal",
        transport = "auto",
        kpiSet = "agent-qoe-kpi-v0.2",
        aqsVersion = "aqs-v0.2",
        profileVersions = "1.0.0",
        schemaVersion = "11",
        profileSource = "assets_fallback",
        appVersionName = "test",
        appVersionCode = 1L,
        guardMetadata = null,
        aqsScore = null,
        aqsLowConfidence = null,
        aqsVetoApplied = null,
        aqsNotComputableReason = "test",
        status = "completed",
        reportStatus = reportStatus,
    )
}
