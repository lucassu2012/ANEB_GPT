package com.aneb.probe.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReportBodyDaoChunkedReadTest {

    @Test
    fun `forRun safely reads oversized 3_2MB report body via chunked substr without CursorWindow crash`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AnebDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            val dao = db.reportBodyDao()
            assertNull(dao.forRun("missing-run"))

            val smallBody = """{"runId":"small-run","ok":true}"""
            dao.insert(ReportBodyEntity("small-run", smallBody))
            val loadedSmall = dao.forRun("small-run")
            assertNotNull(loadedSmall)
            assertEquals(smallBody, loadedSmall!!.body)

            // 3.2 MB JSON body exceeds Android's 2 MB CursorWindow hard row limit.
            val chunk = """{"atUs":1234567890,"totalBytes":1048576,"requestId":"download-0-0"},"""
            val hugeBody = buildString(3_300_000) {
                append("""{"runId":"huge-run","items":[""")
                repeat(48_000) { append(chunk) }
                append("""{"atUs":1234567891,"totalBytes":99999999}]}""")
            }
            assert(hugeBody.length > 3_000_000) { "expected >3MB body, got ${hugeBody.length}" }

            dao.insert(ReportBodyEntity("huge-run", hugeBody))
            val loadedHuge = dao.forRun("huge-run")
            assertNotNull(loadedHuge)
            assertEquals("huge-run", loadedHuge!!.runId)
            assertEquals(hugeBody.length, loadedHuge.body.length)
            assertEquals(hugeBody, loadedHuge.body)
        } finally {
            db.close()
        }
    }
}
