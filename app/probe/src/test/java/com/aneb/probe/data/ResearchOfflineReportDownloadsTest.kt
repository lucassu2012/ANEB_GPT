package com.aneb.probe.data

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.aneb.probe.research.ResearchAnalysisStore
import com.aneb.probe.research.ResearchConclusionStore
import com.aneb.probe.research.ResearchOfflineReportExporter
import com.aneb.probe.research.ResearchRecordStore
import com.aneb.probe.ui.research.ResearchOfflineReportConfirmation
import com.aneb.probe.ui.research.ResearchOfflineReportTarget
import com.aneb.probe.ui.research.ResearchOfflineReportCopy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ResearchOfflineReportDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedReportIsFinishedAndClosedBeforeDownloadsPublishesIt() {
        val saved = saveResearch()
        val gateway = DownloadsBoundary()

        val result = Exporter.exportToDownloads(gateway, "ANEB-private-research.zip", "application/zip") {
            saved.writeTo(it)
        }

        assertTrue(result.ok)
        assertTrue(gateway.closed)
        assertFalse(gateway.pending)
        assertFalse(gateway.deleted)
        val archive = temporary.newFile("published.zip").also { it.writeBytes(checkNotNull(gateway.published)) }
        ZipFile(archive, Charsets.UTF_8).use { zip ->
            assertArrayEquals(saved.sourceBytes, zip.getInputStream(zip.getEntry("source.json")).use { it.readBytes() })
            assertTrue(zip.getEntry("index.html") != null)
            assertTrue(zip.getEntry("SHA256SUMS.txt") != null)
        }
        saved.assertOriginalsUnchanged()
    }

    @Test fun outputWriteFinishAndCloseFailuresNeverPublishOrChangeSavedResearch() {
        val saved = saveResearch()
        listOf("write", "finish", "close").forEach { phase ->
            val gateway = DownloadsBoundary(failurePhase = phase)

            val result = Exporter.exportToDownloads(gateway, "ANEB-private-research.zip", "application/zip") {
                saved.writeTo(it)
            }

            assertFalse(phase, result.ok)
            assertNull(result.uri)
            assertTrue(phase, result.error?.contains(phase + " sentinel") == true)
            assertTrue(phase, gateway.deleted)
            assertFalse(gateway.pending)
            assertNull(gateway.published)
            saved.assertOriginalsUnchanged()
        }
    }

    @Test fun cancelledOutputRemovesPendingZipAndKeepsEveryOriginal() {
        val saved = saveResearch()
        val gateway = DownloadsBoundary(cancelOnWrite = true)

        val cancelled = assertThrows(CancellationException::class.java) {
            Exporter.exportToDownloads(gateway, "ANEB-private-research.zip", "application/zip") {
                saved.writeTo(it)
            }
        }

        org.junit.Assert.assertEquals("cancel export sentinel", cancelled.message)
        assertTrue(gateway.deleted)
        assertFalse(gateway.pending)
        assertNull(gateway.published)
        saved.assertOriginalsUnchanged()
    }

    @Test fun selectingReportDoesNotWriteUntilTheMatchingCardIsConfirmed() {
        val saved = saveResearch()
        val gateway = DownloadsBoundary()
        val selected = ResearchOfflineReportConfirmation().select(saved.target)
        assertNull(gateway.published)
        assertFalse(gateway.pending)

        val confirmed = selected.confirm(saved.target) {
            val result = Exporter.exportToDownloads(gateway, "ANEB-private-research.zip", "application/zip", saved.writeTo)
            assertTrue(result.ok)
        }

        assertTrue("explicit confirmation did not publish the selected report", gateway.published != null)
        assertNull(confirmed.pending)
        saved.assertOriginalsUnchanged()
    }

    @Test fun cancelledChangedOrConsumedConfirmationCannotCreateAnExtraDownload() {
        val saved = saveResearch()
        val gateway = DownloadsBoundary()
        val consent = ResearchOfflineReportCopy.confirmation(saved.target)
        listOf(saved.target.sourceId, saved.target.analysisId, saved.target.conclusionId,
            "整批", "筛选", "私密", "本机", "不含截图", "不上传", "不分享")
            .forEach { assertTrue(it, consent.contains(it)) }
        val publish: (ResearchOfflineReportTarget) -> Unit = { target ->
            org.junit.Assert.assertEquals(saved.target, target)
            assertTrue(Exporter.exportToDownloads(gateway, "ANEB-private-research.zip", "application/zip", saved.writeTo).ok)
        }
        var state = ResearchOfflineReportConfirmation().select(saved.target).cancel()
        state = state.confirm(saved.target, publish)
        org.junit.Assert.assertEquals(0, gateway.createdRows)
        assertNull(gateway.published)

        state = state.select(saved.target).confirm(saved.target.copy(analysisId = "0".repeat(64)), publish)
        org.junit.Assert.assertEquals(0, gateway.createdRows)
        assertNull(state.pending)

        state = state.select(saved.target).confirm(saved.target, publish)
        state = state.confirm(saved.target, publish)
        org.junit.Assert.assertEquals(1, gateway.createdRows)
        assertNull(state.pending)
        saved.assertOriginalsUnchanged()
    }

    private fun saveResearch(): SavedResearch {
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val conclusions = ResearchConclusionStore(temporary.newFolder())
        val sourceBytes = resource("r1-sample.json")
        val analysisBytes = resource("r1-analysis-sample.json")
        val source = records.save(sourceBytes)
        val analysis = analyses.save(source, analysisBytes)
        val cardBytes = ("# 私有人工结论\r\n分析 SHA-256：" + analysis.id + "\r\n不作网络归因。\r\n")
            .toByteArray(Charsets.UTF_8)
        val card = conclusions.save(source, analysis, cardBytes)
        return SavedResearch(
            sourceBytes,
            ResearchOfflineReportTarget(source.id, analysis.id, card.id),
            { output -> ResearchOfflineReportExporter(records, analyses, conclusions).writeTo(source.id, analysis.id, card.id, output) },
            {
                assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { records.export(source.id, it) }.toByteArray())
                assertArrayEquals(analysisBytes, ByteArrayOutputStream().also { analyses.export(source, analysis.id, it) }.toByteArray())
                assertArrayEquals(cardBytes, ByteArrayOutputStream().also { conclusions.export(source, analysis, card.id, it) }.toByteArray())
            },
        )
    }

    private data class SavedResearch(
        val sourceBytes: ByteArray,
        val target: ResearchOfflineReportTarget,
        val writeTo: (OutputStream) -> Unit,
        val assertOriginalsUnchanged: () -> Unit,
    )

    /** OS Downloads boundary only; all research/export code is real. */
    private class DownloadsBoundary(
        private val failurePhase: String? = null,
        private val cancelOnWrite: Boolean = false,
    ) : PendingDownloadsGateway {
        private val uri = Uri.parse("content://media/external/downloads/849")
        var pending = false
        var createdRows = 0
        var closed = false
        var deleted = false
        var published: ByteArray? = null
        private val output = object : ByteArrayOutputStream() {
            private var signature = 0
            private var cancellationInjected = false
            override fun write(value: Int) {
                if (cancelOnWrite && !cancellationInjected && size() >= 8) {
                    cancellationInjected = true
                    throw CancellationException("cancel export sentinel")
                }
                if (failurePhase == "write" && size() >= 8) throw IOException("write sentinel")
                signature = (signature shl 8) or (value and 255)
                // ZIP central-directory header: output failure specifically during finish.
                if (failurePhase == "finish" && signature == 0x504b0102) throw IOException("finish sentinel")
                super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                for (index in offset until offset + length) write(bytes[index].toInt() and 255)
            }
            override fun close() {
                closed = true
                if (failurePhase == "close") throw IOException("close sentinel")
                super.close()
            }
        }
        override fun insert(collection: Uri, values: ContentValues): Uri {
            createdRows++
            check(values.getAsInteger(MediaStore.Downloads.IS_PENDING) == 1)
            pending = true
            return uri
        }
        override fun openOutputStream(uri: Uri): OutputStream = output
        override fun update(uri: Uri, values: ContentValues): Int {
            check(closed)
            check(values.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0)
            published = output.toByteArray()
            pending = false
            return 1
        }
        override fun delete(uri: Uri): Int {
            deleted = true
            pending = false
            published = null
            return 1
        }
    }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/research/$name")).use {
        it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
    }
}
