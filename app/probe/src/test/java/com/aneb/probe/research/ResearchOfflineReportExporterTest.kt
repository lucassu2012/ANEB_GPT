package com.aneb.probe.research

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchOfflineReportExporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedTextResearchExportsReadableZipWithOriginalBytesAndChecksums() {
        // Committed SAMPLE carrier, not observed data or a new scientific analysis.
        val sourceBytes = resource("r1-sample.json")
        val analysisBytes = resource("r1-analysis-sample.json")
        val sourceDirectory = temporary.newFolder("sources")
        val analysisDirectory = temporary.newFolder("analyses")
        val conclusionDirectory = temporary.newFolder("conclusions")
        val source = ResearchRecordStore(sourceDirectory).save(sourceBytes)
        val analysis = ResearchAnalysisStore(analysisDirectory).save(source, analysisBytes)
        val conclusionBytes = ("# Kimi 样例人工结论\r\n分析 SHA-256：" + analysis.id +
            "\r\n仅供私有研究；未知仍为 UNKNOWN，不作网络归因。\r\n").toByteArray(Charsets.UTF_8)
        val conclusion = ResearchConclusionStore(conclusionDirectory).save(source, analysis, conclusionBytes)
        val destination = ByteArrayOutputStream()

        // Reopen actual stores; export must use saved files, not caller-held documents.
        ResearchOfflineReportExporter(
            ResearchRecordStore(sourceDirectory),
            ResearchAnalysisStore(analysisDirectory),
            ResearchConclusionStore(conclusionDirectory),
        ).writeTo(source.id, analysis.id, conclusion.id, destination)

        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(destination.toByteArray()), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertTrue("duplicate ZIP entry", entry.name !in entries)
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("index.html", "source.json", "analysis.json", "conclusion.md", "SHA256SUMS.txt"), entries.keys)
        assertArrayEquals(sourceBytes, entries.getValue("source.json"))
        assertArrayEquals(analysisBytes, entries.getValue("analysis.json"))
        assertArrayEquals(conclusionBytes, entries.getValue("conclusion.md"))
        val page = entries.getValue("index.html").toString(Charsets.UTF_8)
        listOf("charset=\"UTF-8\"", "Kimi", "SAMPLE", "整批", "人工结论", "不作网络归因",
            "SAMPLE-KIMI-R1", "SAMPLE-KIMI-R2", "SAMPLE-KIMI-R3", source.id, analysis.id, conclusion.id)
            .forEach { assertTrue("missing readable report content: $it", page.contains(it)) }
        val checksums = entries.getValue("SHA256SUMS.txt").toString(Charsets.UTF_8)
        entries.filterKeys { it != "SHA256SUMS.txt" }.forEach { (name, bytes) ->
            assertTrue("missing original/generated entry SHA: $name", checksums.lines().contains(sha256(bytes) + "  " + name))
        }
        assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { ResearchRecordStore(sourceDirectory).export(source.id, it) }.toByteArray())
        assertArrayEquals(analysisBytes, ByteArrayOutputStream().also { ResearchAnalysisStore(analysisDirectory).export(source, analysis.id, it) }.toByteArray())
        assertEquals(conclusion.text, ResearchConclusionStore(conclusionDirectory).open(source, analysis, conclusion.id).text)
    }

    @Test fun readableReportKeepsUnavailableValuesOriginalOutcomesVersionsAndImportedGroups() {
        // Fabricated SAMPLE presentation values, not a new analysis or observed measurement.
        val sourceRoot = kotlinx.serialization.json.Json.parseToJsonElement(resource("r1-sample.json").toString(Charsets.UTF_8)).jsonObject
        val sourceRows = sourceRoot["records"]!!.jsonArray.mapIndexed { index, item ->
            if (index != 0) item else JsonObject(item.jsonObject.toMutableMap().apply {
                put("outcome", JsonObject(item.jsonObject["outcome"]!!.jsonObject.toMutableMap().apply {
                    put("instruction_following", JsonPrimitive("no"))
                }))
            })
        }
        val sourceBytes = JsonObject(sourceRoot.toMutableMap().apply { put("records", JsonArray(sourceRows)) }).toString().toByteArray(Charsets.UTF_8)
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val conclusions = ResearchConclusionStore(temporary.newFolder())
        val source = records.save(sourceBytes)
        val template = kotlinx.serialization.json.Json.parseToJsonElement(resource("r1-analysis-sample.json").toString(Charsets.UTF_8)).jsonObject
        val rows = template["records"]!!.jsonArray.mapIndexed { index, item ->
            JsonObject(item.jsonObject.toMutableMap().apply {
                put("input_record", source.records[index].raw)
                if (index == 0) put("ttfr", buildJsonObject {
                    put("status", "uncertain"); put("interval_s", JsonNull); put("reason", "event_order_uncertain")
                })
            })
        }
        val analysisBytes = JsonObject(template.toMutableMap().apply {
            put("source_sha256", JsonPrimitive(source.id))
            put("records", JsonArray(rows))
            put("groups", buildJsonArray {
                add(buildJsonObject {
                    put("group_id", "SAMPLE-render-group")
                    put("comparability_key", buildJsonArray { add("SAMPLE only") })
                    put("metrics", buildJsonObject {})
                })
            })
        }).toString().toByteArray(Charsets.UTF_8)
        val analysis = analyses.save(source, analysisBytes)
        val cardBytes = ("# 合成 SAMPLE 人工结论\n分析 SHA-256：" + analysis.id +
            "\n仅验证离线呈现，不是观察结果或科学结论。\n").toByteArray(Charsets.UTF_8)
        val card = conclusions.save(source, analysis, cardBytes)
        val archive = ByteArrayOutputStream().also {
            ResearchOfflineReportExporter(records, analyses, conclusions).writeTo(source.id, analysis.id, card.id, it)
        }.toByteArray()
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
            }
        }
        val page = files.getValue("index.html").toString(Charsets.UTF_8)
        listOf("版本：UNKNOWN", "可能尚未完成，或完成证据不足；不表示 App 失败。",
            "visible_completion：yes", "instruction_following：no", "不同原标注",
            "uncertain", "Completion：NA", "not_computed", "not_run", "SAMPLE-render-group")
            .forEach { assertTrue("missing original presentation: $it", page.contains(it)) }
        assertArrayEquals(sourceBytes, files.getValue("source.json"))
        assertArrayEquals(analysisBytes, files.getValue("analysis.json"))
        assertArrayEquals(cardBytes, files.getValue("conclusion.md"))
        // Optional private evidence destination. Normal tests/CI never write outside TemporaryFolder.
        System.getenv("ANEB_RESEARCH_SAMPLE_ZIP")?.takeIf { it.isNotBlank() }?.let { destination ->
            val path = java.nio.file.Path.of(destination)
            require(path.isAbsolute && java.nio.file.Files.isDirectory(path.parent))
            java.nio.file.Files.write(path, archive, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
            println("SAMPLE_ZIP_ARTIFACT path=" + path + " bytes=" + archive.size + " sha256=" + sha256(archive))
        }
    }

    @Test fun foreignTextStaysTextAndMediaReferencesNeverBecomeArchiveContent() {
        // SAMPLE text and local sentinels only; no real research or media is read.
        val foreignAppName = "SAMPLE <img src=x onerror='alert(1)'> & \"中文\""
        val mediaMarker = "SAMPLE_MEDIA_BYTES_NEVER_INCLUDED"
        val existingMedia = temporary.newFile("sample-private-media.bin").apply { writeText(mediaMarker) }
        val sourceRoot = Json.parseToJsonElement(resource("r1-sample.json").toString(Charsets.UTF_8)).jsonObject
        val sourceRows = sourceRoot["records"]!!.jsonArray.mapIndexed { index, item ->
            JsonObject(item.jsonObject.toMutableMap().apply {
                put("app", JsonObject(item.jsonObject["app"]!!.jsonObject.toMutableMap().apply {
                    put("name", JsonPrimitive(foreignAppName))
                }))
                put("evidence", buildJsonObject {
                    put("exists", false)
                    put("local_ref", if (index == 0) existingMedia.absolutePath else "../../SAMPLE-NONEXISTENT/private.mp4")
                    put("sha256", JsonNull)
                })
            })
        }
        val sourceBytes = JsonObject(sourceRoot.toMutableMap().apply { put("records", JsonArray(sourceRows)) })
            .toString().toByteArray(Charsets.UTF_8)
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val conclusions = ResearchConclusionStore(temporary.newFolder())
        val source = records.save(sourceBytes)
        val template = Json.parseToJsonElement(resource("r1-analysis-sample.json").toString(Charsets.UTF_8)).jsonObject
        val analysisBytes = JsonObject(template.toMutableMap().apply {
            put("source_sha256", JsonPrimitive(source.id))
            put("records", JsonArray(template["records"]!!.jsonArray.mapIndexed { index, item ->
                JsonObject(item.jsonObject.toMutableMap().apply { put("input_record", source.records[index].raw) })
            }))
        }).toString().toByteArray(Charsets.UTF_8)
        val analysis = analyses.save(source, analysisBytes)
        val cardBytes = ("# SAMPLE <script>alert(1)</script> & 中文\r\n" + analysis.id +
            "\r\n![text only](file:///private.mp4) [not a live link](https://example.invalid/)\r\n")
            .toByteArray(Charsets.UTF_8)
        val card = conclusions.save(source, analysis, cardBytes)
        val archive = ByteArrayOutputStream().also {
            ResearchOfflineReportExporter(records, analyses, conclusions).writeTo(source.id, analysis.id, card.id, it)
        }.toByteArray()
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertTrue("duplicate archive path", entry.name !in files)
                files[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("index.html", "source.json", "analysis.json", "conclusion.md", "SHA256SUMS.txt"), files.keys)
        assertArrayEquals(sourceBytes, files.getValue("source.json"))
        assertArrayEquals(analysisBytes, files.getValue("analysis.json"))
        assertArrayEquals(cardBytes, files.getValue("conclusion.md"))
        val page = files.getValue("index.html").toString(Charsets.UTF_8)
        assertTrue(page.contains("&lt;img src=x onerror=&#39;alert(1)&#39;&gt; &amp; &quot;中文&quot;"))
        assertTrue(page.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(page.contains("<script", ignoreCase = true))
        assertFalse(page.contains("<img", ignoreCase = true))
        assertEquals(setOf("source.json", "analysis.json", "conclusion.md", "SHA256SUMS.txt"),
            Regex("<a href=\"([^\"]+)\"").findAll(page).map { it.groupValues[1] }.toSet())
        files.values.forEach { assertFalse(it.toString(Charsets.UTF_8).contains(mediaMarker)) }
        assertEquals(mediaMarker, existingMedia.readText())
    }

    @Test fun mismatchedOrDamagedSavedConclusionCannotStartAnArchiveOrRewriteInputs() {
        val sourceBytes = resource("r1-sample.json")
        val analysisBytes = resource("r1-analysis-sample.json")
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val conclusionDirectory = temporary.newFolder()
        val conclusions = ResearchConclusionStore(conclusionDirectory)
        val source = records.save(sourceBytes)
        val analysis = analyses.save(source, analysisBytes)
        val cardBytes = ("# SAMPLE original card\r\n" + analysis.id + "\r\n原件不得自动修复或覆盖。\r\n")
            .toByteArray(Charsets.UTF_8)
        val card = conclusions.save(source, analysis, cardBytes)
        // A different saved byte representation is another analysis ID, even with identical JSON values.
        val otherAnalysis = analyses.save(source, byteArrayOf(10) + analysisBytes)
        assertFalse(analysis.id == otherAnalysis.id)
        val exporter = ResearchOfflineReportExporter(records, analyses, conclusions)
        val mismatchedOutput = ByteArrayOutputStream()
        assertTrue("mismatched conclusion was accepted", runCatching {
            exporter.writeTo(source.id, otherAnalysis.id, card.id, mismatchedOutput)
        }.isFailure)
        assertEquals(0, mismatchedOutput.size())
        assertArrayEquals(cardBytes, ByteArrayOutputStream().also {
            conclusions.export(source, analysis, card.id, it)
        }.toByteArray())

        // Inject a storage fault only in this TemporaryFolder. Refusal must not repair/delete it.
        val damagedBytes = "SAMPLE injected storage corruption".toByteArray(Charsets.UTF_8)
        val savedCard = java.io.File(java.io.File(java.io.File(conclusionDirectory, source.id), analysis.id), card.id + ".md")
        savedCard.writeBytes(damagedBytes)
        val damagedOutput = ByteArrayOutputStream()
        assertTrue("damaged conclusion was accepted", runCatching {
            exporter.writeTo(source.id, analysis.id, card.id, damagedOutput)
        }.isFailure)
        assertEquals(0, damagedOutput.size())
        assertArrayEquals(damagedBytes, savedCard.readBytes())
        assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { records.export(source.id, it) }.toByteArray())
        assertArrayEquals(analysisBytes, ByteArrayOutputStream().also { analyses.export(source, analysis.id, it) }.toByteArray())
        assertArrayEquals(byteArrayOf(10) + analysisBytes, ByteArrayOutputStream().also {
            analyses.export(source, otherAnalysis.id, it)
        }.toByteArray())
    }

    // Git fixture LF normalization only; production/user documents must remain byte-exact.
    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/research/$name")).use {
        it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
