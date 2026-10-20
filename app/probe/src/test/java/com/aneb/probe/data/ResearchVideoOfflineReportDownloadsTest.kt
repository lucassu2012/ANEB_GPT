package com.aneb.probe.data

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.aneb.probe.research.ResearchAnalysisStore
import com.aneb.probe.research.ResearchRecordStore
import com.aneb.probe.research.ResearchVideoOfflineReportExporter
import com.aneb.probe.ui.research.ResearchVideoOfflineReportConfirmation
import com.aneb.probe.ui.research.ResearchVideoOfflineReportTarget
import com.aneb.probe.ui.research.ResearchVideoOfflineReportCopy
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.IOException
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ResearchVideoOfflineReportDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completeVideoZipIsClosedBeforeDownloadsPublishesIt() {
        val saved = saveVideo()
        val gateway = DownloadsBoundary()
        val result = Exporter.exportToDownloads(gateway, "ANEB-private-video.zip", "application/zip", saved.writeTo)
        assertTrue(result.ok)
        assertTrue(gateway.closed)
        assertFalse(gateway.pending)
        assertFalse(gateway.deleted)
        val archive = temporary.newFile("published-video.zip").apply { writeBytes(checkNotNull(gateway.published)) }
        ZipFile(archive, Charsets.UTF_8).use { zip ->
            assertEquals(setOf("index.html", "source.json", "analysis.json", "SHA256SUMS.txt"), zip.entries().asSequence().map { it.name }.toSet())
            assertArrayEquals(saved.sourceBytes, zip.getInputStream(zip.getEntry("source.json")).use { it.readBytes() })
            assertArrayEquals(saved.analysisBytes, zip.getInputStream(zip.getEntry("analysis.json")).use { it.readBytes() })
        }
        saved.assertOriginalsUnchanged()
    }

    @Test fun writeFinishAndCloseFailureNeverPublishesOrChangesSavedVideo() {
        val saved = saveVideo()
        listOf("write", "finish", "close").forEach { phase ->
            val gateway = DownloadsBoundary(failurePhase = phase)
            val result = Exporter.exportToDownloads(gateway, "ANEB-private-video.zip", "application/zip", saved.writeTo)
            assertFalse(phase, result.ok)
            assertNull(result.uri)
            assertTrue(phase, result.error?.contains(phase + " sentinel") == true)
            assertTrue(gateway.deleted)
            assertFalse(gateway.pending)
            assertNull(gateway.published)
            saved.assertOriginalsUnchanged()
        }
    }

    @Test fun cancelledVideoWriteRemovesPendingDownloadAndKeepsBothOriginals() {
        val saved = saveVideo()
        val gateway = DownloadsBoundary(cancelOnWrite = true)
        val failure = assertThrows(CancellationException::class.java) {
            Exporter.exportToDownloads(gateway, "ANEB-private-video.zip", "application/zip", saved.writeTo)
        }
        assertEquals("cancel video export sentinel", failure.message)
        assertTrue(gateway.deleted)
        assertFalse(gateway.pending)
        assertNull(gateway.published)
        saved.assertOriginalsUnchanged()
    }

    @Test fun selectingVideoReportDoesNotWriteUntilTheMatchingSavedPairIsConfirmed() {
        val saved = saveVideo()
        val gateway = DownloadsBoundary()
        val target = ResearchVideoOfflineReportTarget(saved.sourceId, saved.analysisId)
        val selected = ResearchVideoOfflineReportConfirmation().select(target)
        assertNull(gateway.published)
        assertFalse(gateway.pending)
        val confirmed = selected.confirm(target) {
            assertEquals(target, it)
            assertTrue(Exporter.exportToDownloads(gateway, "ANEB-private-video.zip", "application/zip", saved.writeTo).ok)
        }
        assertNotNull("explicit video confirmation did not publish the selected report", gateway.published)
        assertNull(confirmed.pending)
        saved.assertOriginalsUnchanged()
    }

    @Test fun cancelledStaleOrConsumedVideoConsentCannotCreateAnotherDownload() {
        val saved = saveVideo()
        val gateway = DownloadsBoundary()
        val target = ResearchVideoOfflineReportTarget(saved.sourceId, saved.analysisId)
        val consent = ResearchVideoOfflineReportCopy.confirmation(target)
        listOf(target.sourceId, target.analysisId, "整批", "筛选", "私密", "本机", "不上传", "不分享", "不含截图", "不需要人工结论", "NOT_RUN")
            .forEach { assertTrue(it, consent.contains(it)) }
        val publish: (ResearchVideoOfflineReportTarget) -> Unit = {
            assertEquals(target, it)
            assertTrue(Exporter.exportToDownloads(gateway, "ANEB-private-video.zip", "application/zip", saved.writeTo).ok)
        }
        var state = ResearchVideoOfflineReportConfirmation().select(target).cancel().confirm(target, publish)
        assertEquals(0, gateway.createdRows)
        assertNull(gateway.published)
        listOf(target.copy(sourceId = "0".repeat(64)), target.copy(analysisId = "0".repeat(64))).forEach { changed ->
            state = state.select(target).confirm(changed, publish)
            assertEquals(0, gateway.createdRows)
            assertNull(state.pending)
        }
        state = state.select(target).confirm(target, publish)
        state = state.confirm(target, publish)
        assertEquals(1, gateway.createdRows)
        assertNull(state.pending)
        saved.assertOriginalsUnchanged()
    }

    private fun saveVideo(): SavedVideo {
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val sourceBytes = resource("video-source.sample.json")
        val analysisBytes = resource("video-analysis.sample.json")
        val source = records.save(sourceBytes)
        val analysis = analyses.save(source, analysisBytes)
        return SavedVideo(sourceBytes, analysisBytes, source.id, analysis.id,
            { output -> ResearchVideoOfflineReportExporter(records, analyses).writeTo(source.id, analysis.id, output) },
            {
                assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { records.export(source.id, it) }.toByteArray())
                assertArrayEquals(analysisBytes, ByteArrayOutputStream().also { analyses.export(source, analysis.id, it) }.toByteArray())
            },
        )
    }

    private data class SavedVideo(
        val sourceBytes: ByteArray,
        val analysisBytes: ByteArray,
        val sourceId: String,
        val analysisId: String,
        val writeTo: (OutputStream) -> Unit,
        val assertOriginalsUnchanged: () -> Unit,
    )

    /** Only the OS Downloads boundary is fake; stores, writer, ZIP and publisher are real. */
    private class DownloadsBoundary(private val failurePhase: String? = null, private val cancelOnWrite: Boolean = false) : PendingDownloadsGateway {
        private val uri = Uri.parse("content://media/external/downloads/855")
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
                    throw CancellationException("cancel video export sentinel")
                }
                if (failurePhase == "write" && size() >= 8) throw IOException("write sentinel")
                signature = (signature shl 8) or (value and 255)
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
            deleted = true; pending = false; published = null
            return 1
        }
    }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/research/$name")).use {
        it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
    }
}
