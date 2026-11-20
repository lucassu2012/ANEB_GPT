package com.aneb.probe.research

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BusinessVideoRawExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedCollectorExportsOriginalBytesAndHonestOfflineReadoutWithoutLegacyAnalysis() {
        // Fabricated original-shaped fixture, not live media or a measured result.
        val bytes = """{
          "format":"business-video-raw-1","runId":"TEST-RAW-EXPORT",
          "profileSha256":"${"a".repeat(64)}","conditionId":"TEST-R","clockId":"TEST-elapsed",
          "state":"CAPTURED_AWAITING_REVIEW","category":"OBSERVATION_CAPTURED","reason":null,
          "swipeCalls":"1","ownedCleanup":true,
          "events":[{"name":"swipe_returned","atNs":400}],
          "frames":[{"sequence":0,"requestedNs":500,"returnedNs":600,"writtenNs":700,
            "relativePath":"frames/frame-0000.png","sha256":"${"b".repeat(64)}","bytes":123}],
          "firstObservedPlaybackWaitMs":null,"playerFirstFrameMs":null,"stallStatus":"not_computed"
        }
        """.toByteArray(Charsets.UTF_8)
        val store = ResearchRecordStore(temporary.newFolder())
        val source = store.save(bytes)
        val output = ByteArrayOutputStream()
        ResearchVideoOfflineReportExporter(store, ResearchAnalysisStore(temporary.newFolder()))
            .writeRawTo(source.id, output)
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray()), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(files.containsKey(entry.name))
                files[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("source.json", "index.html", "SHA256SUMS.txt"), files.keys)
        assertArrayEquals(bytes, files.getValue("source.json"))
        val page = files.getValue("index.html").toString(Charsets.UTF_8)
        listOf("business-video-raw-1", "TEST-RAW-EXPORT", "未核验", "不是旧 V0/VF", "不附媒体",
            "NA", "not_computed", source.id, "frames/frame-0000.png").forEach {
            assertTrue("missing boundary: $it", page.contains(it))
        }
        assertFalse(page.contains("analysis.json"))
        assertFalse(page.contains("<script"))
        val hashes = files.getValue("SHA256SUMS.txt").toString(Charsets.UTF_8).lines()
        files.filterKeys { it != "SHA256SUMS.txt" }.forEach { (name, content) ->
            val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertTrue("checksum missing: $name", "$sha  $name" in hashes)
        }
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(source.id, it) }.toByteArray())
    }
}
