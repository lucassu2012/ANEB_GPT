package com.aneb.probe.research

import android.content.Context
import java.io.File
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ManualResearchDraftCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unfinishedDraftSurvivesReopenAndFailedSaveThenClearsOnlyAfterRecordIsSaved() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("manual-test", Context.MODE_PRIVATE)
        val draft = ManualResearchDraft(appName = "TEST ONLY unfinished", attempts = List(3) { ManualResearchAttempt(reason = "尚未确认") })
        val cache = ManualResearchDraftCache(preferences)
        cache.save(draft)
        val reopened = ManualResearchDraftCache(preferences)
        assertEquals(draft, reopened.load())
        val records = ResearchRecordStore(temporary.newFolder())
        assertThrows(ResearchImportException::class.java) { reopened.saveRecord(records) }
        assertEquals(draft, reopened.load())
        assertTrue(records.list().isEmpty())
        val ready = draft.copy(recordKind = "SAMPLE", attempts = List(3) { ManualResearchAttempt(executed = false, reason = "TEST ONLY 未执行") })
        reopened.save(ready)
        val blockedDirectory = File(temporary.root, "blocked").apply { writeText("not a directory") }
        assertThrows(IllegalStateException::class.java) { reopened.saveRecord(ResearchRecordStore(blockedDirectory)) }
        assertEquals(ready, reopened.load())
        val saved = reopened.saveRecord(records)
        assertNull(ManualResearchDraftCache(preferences).load())
        assertArrayEquals(ready.toRecordBytes(), ByteArrayOutputStream().also { records.export(saved.id, it) }.toByteArray())
        assertEquals(3, records.open(saved.id).records.size)
    }
}
