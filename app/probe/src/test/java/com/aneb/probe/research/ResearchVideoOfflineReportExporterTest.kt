package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchVideoOfflineReportExporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedSampleVideoExportsReadableZipWithBothOriginalsAndChecksums() {
        // Existing synthetic docs sample plus existing offline CLI output; not observed playback.
        val sourceBytes = resource("video-source.sample.json")
        val analysisBytes = resource("video-analysis.sample.json")
        val sourceDirectory = temporary.newFolder("sources")
        val analysisDirectory = temporary.newFolder("analyses")
        val source = ResearchRecordStore(sourceDirectory).save(sourceBytes)
        val analysis = ResearchAnalysisStore(analysisDirectory).save(source, analysisBytes)
        assertTrue(source.isVideo && analysis.isVideo)
        val archive = ByteArrayOutputStream().also { output ->
            ResearchVideoOfflineReportExporter(
                ResearchRecordStore(sourceDirectory), ResearchAnalysisStore(analysisDirectory),
            ).writeTo(source.id, analysis.id, output)
        }.toByteArray()
        val archiveFile = temporary.newFile("sample-report.zip").apply { writeBytes(archive) }
        val files = linkedMapOf<String, ByteArray>()
        // ZipFile also requires a finished central directory, not merely readable local entries.
        ZipFile(archiveFile, Charsets.UTF_8).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                assertTrue("duplicate ZIP entry", entry.name !in files)
                files[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        assertEquals(setOf("index.html", "source.json", "analysis.json", "SHA256SUMS.txt"), files.keys)
        assertArrayEquals(sourceBytes, files.getValue("source.json"))
        assertArrayEquals(analysisBytes, files.getValue("analysis.json"))
        val page = files.getValue("index.html").toString(Charsets.UTF_8)
        listOf("charset=\"UTF-8\"", "SAMPLE", "合成演示", "SAMPLE-R1", "整批", "未独立核验",
            "可见打开至首画面", "[2.2, 2.5] 秒", "30秒观察窗", "非实测终点", "不计播放失败",
            source.id, analysis.id).forEach { assertTrue("missing readable video semantics: $it", page.contains(it)) }
        val checksums = files.getValue("SHA256SUMS.txt").toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        assertEquals(3, checksums.size)
        files.filterKeys { it != "SHA256SUMS.txt" }.forEach { (name, bytes) ->
            assertTrue("missing ZIP entry checksum: $name", sha256(bytes) + "  " + name in checksums)
        }
        assertArrayEquals(sourceBytes, ByteArrayOutputStream().also {
            ResearchRecordStore(sourceDirectory).export(source.id, it)
        }.toByteArray())
        assertArrayEquals(analysisBytes, ByteArrayOutputStream().also {
            ResearchAnalysisStore(analysisDirectory).export(ResearchRecordStore(sourceDirectory).open(source.id), analysis.id, it)
        }.toByteArray())
        // Optional private evidence only; normal tests do not write outside TemporaryFolder.
        System.getenv("ANEB_RESEARCH_VIDEO_SAMPLE_ZIP")?.takeIf { it.isNotBlank() }?.let { destination ->
            val path = Path.of(destination)
            require(path.isAbsolute && Files.isDirectory(path.parent))
            Files.write(path, archive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            println("VIDEO_SAMPLE_ZIP_ARTIFACT path=" + path + " bytes=" + archive.size + " sha256=" + sha256(archive))
        }
    }

    @Test fun changedOriginalContextCannotBeExportedEvenWhenSourceHashAndSlotsStillMatch() {
        val sourceBytes = resource("video-source.sample.json")
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val source = records.save(sourceBytes)
        val template = Json.parseToJsonElement(resource("video-analysis.sample.json").toString(Charsets.UTF_8)).jsonObject
        val context = template["input_record"]!!.jsonObject
        val row = context["attempts"]!!.jsonArray.single().jsonObject
        val changed = JsonObject(row.toMutableMap().apply {
            put("V0", buildJsonObject { put("clock_id", "SAMPLE-original-pts"); put("pts_s", buildJsonArray { add(99); add(99.1) }) })
        })
        val analysisBytes = JsonObject(template.toMutableMap().apply {
            put("input_record", JsonObject(context.toMutableMap().apply { put("attempts", JsonArray(listOf(changed))) }))
        }).toString().toByteArray(Charsets.UTF_8)
        val analysis = analyses.save(source, analysisBytes)
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            ResearchVideoOfflineReportExporter(records, analyses).writeTo(source.id, analysis.id, output)
        }
        assertEquals(0, output.size())
        assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { records.export(source.id, it) }.toByteArray())
        assertArrayEquals(analysisBytes, ByteArrayOutputStream().also { analyses.export(source, analysis.id, it) }.toByteArray())
    }

    @Test fun equivalentContextKeyOrderWhitespaceAndCliNumericSpellingKeepOriginalBytes() {
        val sourceText = resource("video-source.sample.json").toString(Charsets.UTF_8)
        val numericSource = sourceText.replace("\"pts_s\": [10, 10.1]", "\"pts_s\": [1e1, 1.01e1]")
        assertTrue("sample numeric replacement must be effective", numericSource != sourceText)
        val sourceBytes = ("\n " + numericSource).toByteArray(Charsets.UTF_8)
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val source = records.save(sourceBytes)
        val template = Json.parseToJsonElement(resource("video-analysis.sample.json").toString(Charsets.UTF_8)).jsonObject
        val context = template["input_record"]!!.jsonObject
        val row = context["attempts"]!!.jsonArray.single().jsonObject
        val changedRow = JsonObject(row.toMutableMap().apply {
            put("V0", buildJsonObject { put("pts_s", buildJsonArray { add(10.0); add(10.1) }); put("clock_id", "SAMPLE-original-pts") })
        })
        val changedContext = JsonObject(context.toMutableMap().apply { put("attempts", JsonArray(listOf(changedRow))) })
        val changedRoot = template.toMutableMap().apply {
            put("source_sha256", JsonPrimitive(source.id))
            put("input_record", JsonObject(changedContext.entries.reversed().associate { it.key to it.value }))
        }
        val analysisBytes = (" \n" + JsonObject(changedRoot.entries.reversed().associate { it.key to it.value }) + "\t\n")
            .toByteArray(Charsets.UTF_8)
        val analysis = analyses.save(source, analysisBytes)
        val archive = ByteArrayOutputStream().also {
            ResearchVideoOfflineReportExporter(records, analyses).writeTo(source.id, analysis.id, it)
        }.toByteArray()
        val files = readArchive(archive)
        assertArrayEquals(sourceBytes, files.getValue("source.json"))
        assertArrayEquals(analysisBytes, files.getValue("analysis.json"))
    }

    @Test fun analysisSlotOrderAndDeclaredInventoryMustMatchTheSavedWholeBatch() {
        val original = Json.parseToJsonElement(resource("video-source.sample.json").toString(Charsets.UTF_8)).jsonObject
        val first = original["attempts"]!!.jsonArray.single().jsonObject
        val second = JsonObject(first.toMutableMap().apply { put("slot", JsonPrimitive("SAMPLE-R2")) })
        val sourceBytes = JsonObject(original.toMutableMap().apply { put("attempts", JsonArray(listOf(first, second))) })
            .toString().toByteArray(Charsets.UTF_8)
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val source = records.save(sourceBytes)
        val template = Json.parseToJsonElement(resource("video-analysis.sample.json").toString(Charsets.UTF_8)).jsonObject
        val row = template["attempts"]!!.jsonArray.single().jsonObject
        val row2 = JsonObject(row.toMutableMap().apply { put("slot", JsonPrimitive("SAMPLE-R2")) })
        val matching = template.toMutableMap().apply {
            put("source_sha256", JsonPrimitive(source.id)); put("input_record", source.root)
            put("attempts", JsonArray(listOf(row, row2)))
            put("counts", buildJsonObject { put("planned", 2); put("executed", 2); put("not_run", 0) })
        }
        val variants = listOf(
            "wrong declared count" to JsonObject(matching.toMutableMap().apply {
                put("counts", buildJsonObject { put("planned", 3); put("executed", 2); put("not_run", 0) })
            }),
            "reordered slots" to JsonObject(matching.toMutableMap().apply { put("attempts", JsonArray(listOf(row2, row))) }),
        )
        val accepted = mutableListOf<String>()
        variants.forEach { (label, root) ->
            val analysisBytes = root.toString().toByteArray(Charsets.UTF_8)
            val analysis = analyses.save(source, analysisBytes)
            val output = ByteArrayOutputStream()
            val failure = runCatching { ResearchVideoOfflineReportExporter(records, analyses).writeTo(source.id, analysis.id, output) }.exceptionOrNull()
            if (failure == null) accepted += label else {
                assertTrue(label, failure is IllegalArgumentException)
                assertEquals(0, output.size())
            }
            assertArrayEquals(analysisBytes, ByteArrayOutputStream().also { analyses.export(source, analysis.id, it) }.toByteArray())
        }
        assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { records.export(source.id, it) }.toByteArray())
        assertTrue("inventory drift was accepted: $accepted", accepted.isEmpty())
    }

    @Test fun mismatchedOrDamagedSavedVideoAnalysisCannotWriteOrRepairOriginals() {
        val sourceBytes = resource("video-source.sample.json")
        val analysisBytes = resource("video-analysis.sample.json")
        val records = ResearchRecordStore(temporary.newFolder())
        val analysisDirectory = temporary.newFolder()
        val analyses = ResearchAnalysisStore(analysisDirectory)
        val source = records.save(sourceBytes)
        val analysis = analyses.save(source, analysisBytes)
        val otherSource = records.save(byteArrayOf(10) + sourceBytes)
        val exporter = ResearchVideoOfflineReportExporter(records, analyses)
        val wrongPairOutput = ByteArrayOutputStream()
        assertThrows(Exception::class.java) { exporter.writeTo(otherSource.id, analysis.id, wrongPairOutput) }
        assertEquals(0, wrongPairOutput.size())
        assertArrayEquals(analysisBytes, ByteArrayOutputStream().also { analyses.export(source, analysis.id, it) }.toByteArray())
        // Storage fault injection is confined to TemporaryFolder; export must not repair/delete it.
        val damaged = "SAMPLE damaged saved analysis".toByteArray(Charsets.UTF_8)
        val savedFile = java.io.File(java.io.File(analysisDirectory, source.id), analysis.id + ".json")
        savedFile.writeBytes(damaged)
        val damagedOutput = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { exporter.writeTo(source.id, analysis.id, damagedOutput) }
        assertEquals(0, damagedOutput.size())
        assertArrayEquals(damaged, savedFile.readBytes())
        assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { records.export(source.id, it) }.toByteArray())
        assertArrayEquals(byteArrayOf(10) + sourceBytes, ByteArrayOutputStream().also { records.export(otherSource.id, it) }.toByteArray())
    }

    @Test fun importedUncertaintyAndSyntheticBlockedSlotsKeepUnavailableAndNotRunSemantics() {
        // Fabricated presentation/blocked compatibility carriers only, never real OBSERVED playback.
        val templateSource = Json.parseToJsonElement(resource("video-source.sample.json").toString(Charsets.UTF_8)).jsonObject
        val templateAnalysis = Json.parseToJsonElement(resource("video-analysis.sample.json").toString(Charsets.UTF_8)).jsonObject
        val firstSource = templateSource["attempts"]!!.jsonArray.single()
        val firstResult = templateAnalysis["attempts"]!!.jsonArray.single().jsonObject
        fun notRun(slot: String) = buildJsonObject {
            put("slot", slot); put("status", "NOT_RUN")
            put("V0", JsonNull); put("VF", JsonNull); put("window_end", JsonNull)
            put("visible_target_playback", "not_observed"); put("window_complete", "not_started")
            put("reason", "SAMPLE ONLY synthetic unexecuted slot")
        }
        fun notRunResult(slot: String) = buildJsonObject {
            put("slot", slot); put("status", "NOT_RUN"); put("reason", "SAMPLE ONLY not executed")
            put("first_frame_wait", buildJsonObject { put("status", "NA"); put("interval_s", JsonNull); put("reason", "NOT_RUN") })
            put("window", buildJsonObject { put("status", "not_started"); put("planned_end_pts_s", JsonNull) })
            put("buffering_pause", buildJsonObject { put("status", "not_computed"); put("count", JsonNull); put("duration_s", JsonNull) })
        }
        val mixedSource = JsonObject(templateSource.toMutableMap().apply {
            put("attempts", JsonArray(listOf(firstSource, notRun("SAMPLE-NOT-RUN"))))
        })
        val uncertain = JsonObject(firstResult.toMutableMap().apply {
            put("first_frame_wait", buildJsonObject { put("status", "uncertain"); put("interval_s", JsonNull); put("reason", "SAMPLE_future_reason") })
        })
        val blockedSource = JsonObject(templateSource.toMutableMap().apply {
            put("record_kind", JsonPrimitive("video_observation_preflight_blocked"))
            put("test_fixture", JsonPrimitive("SAMPLE ONLY fabricated blocked shape"))
            put("actual_measured_opens", JsonPrimitive(0)); put("planned_slots", JsonPrimitive(3)); put("not_run", JsonPrimitive(3))
            put("attempts", JsonArray((1..3).map { notRun("SAMPLE-BLOCKED-$it") }))
        })
        listOf(
            Triple(mixedSource, listOf(uncertain, notRunResult("SAMPLE-NOT-RUN")), "SAMPLE"),
            Triple(blockedSource, (1..3).map { notRunResult("SAMPLE-BLOCKED-$it") }, "OBSERVED"),
        ).forEachIndexed { index, (sourceRoot, results, kind) ->
            val records = ResearchRecordStore(temporary.newFolder())
            val analyses = ResearchAnalysisStore(temporary.newFolder())
            val sourceBytes = sourceRoot.toString().toByteArray(Charsets.UTF_8)
            val source = records.save(sourceBytes)
            val analysisBytes = JsonObject(templateAnalysis.toMutableMap().apply {
                put("record_kind", JsonPrimitive(kind)); put("input_record", source.root); put("source_sha256", JsonPrimitive(source.id))
                put("attempts", JsonArray(results))
                put("counts", buildJsonObject { put("planned", if (index == 0) 2 else 3); put("executed", if (index == 0) 1 else 0); put("not_run", if (index == 0) 1 else 3) })
            }).toString().toByteArray(Charsets.UTF_8)
            val analysis = analyses.save(source, analysisBytes)
            val files = readArchive(ByteArrayOutputStream().also {
                ResearchVideoOfflineReportExporter(records, analyses).writeTo(source.id, analysis.id, it)
            }.toByteArray())
            val page = files.getValue("index.html").toString(Charsets.UTF_8)
            listOf("SAMPLE", "NA", "NOT_RUN", "不计播放失败", "未计算（null，不是 0）").forEach { assertTrue(it, page.contains(it)) }
            if (index == 0) assertTrue(page.contains("uncertain") && page.contains("SAMPLE_future_reason"))
            else assertTrue(page.contains("计划：3；已执行：0；未执行：3"))
            source.records.forEach { assertTrue(page.contains(it.attemptId)) }
            assertArrayEquals(sourceBytes, files.getValue("source.json"))
            assertArrayEquals(analysisBytes, files.getValue("analysis.json"))
        }
    }

    @Test fun existingCliFloatRoundTripIsAcceptedWithoutRewritingEitherOriginal() {
        // Analysis is the byte-pinned output of unchanged research_video.py, not a Kotlin calculation.
        val text = resource("video-source.sample.json").toString(Charsets.UTF_8)
        val token = "\"pts_s\": [10, 10.1]"
        assertEquals(2, text.split(token).size)
        val sourceBytes = text.replace(token, "\"pts_s\": [10.0000000000000001, 10.1]").toByteArray(Charsets.UTF_8)
        val analysisBytes = resource("video-analysis-float-roundtrip.sample.json")
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val source = records.save(sourceBytes)
        val analysis = analyses.save(source, analysisBytes)
        val files = readArchive(ByteArrayOutputStream().also {
            ResearchVideoOfflineReportExporter(records, analyses).writeTo(source.id, analysis.id, it)
        }.toByteArray())
        assertArrayEquals(sourceBytes, files.getValue("source.json"))
        assertArrayEquals(analysisBytes, files.getValue("analysis.json"))
    }

    @Test fun foreignVideoTextIsEscapedAndMediaReferencesNeverAddArchiveContent() {
        val marker = "SAMPLE_PRIVATE_MEDIA_BYTES"
        val media = temporary.newFile("sample-media.bin").apply { writeText(marker) }
        val sourceTemplate = Json.parseToJsonElement(resource("video-source.sample.json").toString(Charsets.UTF_8)).jsonObject
        val foreign = "SAMPLE <img src=x onerror='alert(1)'> & \"中文\""
        val note = "<script>alert(1)</script> https://example.invalid/ file:///private.mp4"
        val row = sourceTemplate["attempts"]!!.jsonArray.single().jsonObject
        val sourceBytes = JsonObject(sourceTemplate.toMutableMap().apply {
            put("app", JsonPrimitive(foreign))
            put("evidence", buildJsonArray { add(buildJsonObject { put("reference", media.absolutePath) }); add(buildJsonObject { put("reference", "../../SAMPLE-NONEXISTENT.mp4") }) })
            put("attempts", JsonArray(listOf(JsonObject(row.toMutableMap().apply { put("reason", JsonPrimitive(note)) }))))
        }).toString().toByteArray(Charsets.UTF_8)
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val source = records.save(sourceBytes)
        val template = Json.parseToJsonElement(resource("video-analysis.sample.json").toString(Charsets.UTF_8)).jsonObject
        val resultRow = template["attempts"]!!.jsonArray.single().jsonObject
        val analysisBytes = JsonObject(template.toMutableMap().apply {
            put("input_record", source.root); put("source_sha256", JsonPrimitive(source.id))
            put("attempts", JsonArray(listOf(JsonObject(resultRow.toMutableMap().apply { put("reason", JsonPrimitive(note)) }))))
        }).toString().toByteArray(Charsets.UTF_8)
        val analysis = analyses.save(source, analysisBytes)
        val files = readArchive(ByteArrayOutputStream().also {
            ResearchVideoOfflineReportExporter(records, analyses).writeTo(source.id, analysis.id, it)
        }.toByteArray())
        assertEquals(setOf("index.html", "source.json", "analysis.json", "SHA256SUMS.txt"), files.keys)
        val page = files.getValue("index.html").toString(Charsets.UTF_8)
        assertTrue(page.contains("&lt;img src=x onerror=&#39;alert(1)&#39;&gt; &amp; &quot;中文&quot;"))
        assertTrue(page.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(page.contains("<script", ignoreCase = true))
        assertFalse(page.contains("<img", ignoreCase = true))
        assertEquals(setOf("source.json", "analysis.json", "SHA256SUMS.txt"),
            Regex("<a href=\"([^\"]+)\"").findAll(page).map { it.groupValues[1] }.toSet())
        files.values.forEach { assertFalse(it.toString(Charsets.UTF_8).contains(marker)) }
        assertEquals(marker, media.readText())
        assertArrayEquals(sourceBytes, files.getValue("source.json"))
        assertArrayEquals(analysisBytes, files.getValue("analysis.json"))
    }

    @Test fun integerBindingsPreserveLargeDifferencesAndAcceptEquivalentIntegerZero() {
        val records = ResearchRecordStore(temporary.newFolder())
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val baseBytes = resource("video-source.sample.json")
        val baseRoot = Json.parseToJsonElement(baseBytes.toString(Charsets.UTF_8)).jsonObject
        val templateBytes = resource("video-analysis.sample.json")
        val template = Json.parseToJsonElement(templateBytes.toString(Charsets.UTF_8)).jsonObject
        // SAMPLE metadata, not a timestamp/metric: these adjacent integers must not collapse to Double.
        val largeRoot = JsonObject(baseRoot.toMutableMap().apply { put("sample_integer", JsonPrimitive(9007199254740993L)) })
        val largeBytes = largeRoot.toString().toByteArray(Charsets.UTF_8)
        val source = records.save(largeBytes)
        val wrongBytes = JsonObject(template.toMutableMap().apply {
            put("source_sha256", JsonPrimitive(source.id))
            put("input_record", JsonObject(largeRoot.toMutableMap().apply { put("sample_integer", JsonPrimitive(9007199254740992L)) }))
        }).toString().toByteArray(Charsets.UTF_8)
        val wrong = analyses.save(source, wrongBytes)
        val rejected = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { ResearchVideoOfflineReportExporter(records, analyses).writeTo(source.id, wrong.id, rejected) }
        assertEquals(0, rejected.size())

        // Desktop json.loads treats the valid integer token -0 as integer zero, not a float.
        val original = records.save(baseBytes)
        val zeroText = templateBytes.toString(Charsets.UTF_8).replace("\"not_run\": 0", "\"not_run\": -0")
        assertTrue(zeroText != templateBytes.toString(Charsets.UTF_8))
        val zeroBytes = zeroText.toByteArray(Charsets.UTF_8)
        val zero = analyses.save(original, zeroBytes)
        val files = readArchive(ByteArrayOutputStream().also {
            ResearchVideoOfflineReportExporter(records, analyses).writeTo(original.id, zero.id, it)
        }.toByteArray())
        assertArrayEquals(baseBytes, files.getValue("source.json"))
        assertArrayEquals(zeroBytes, files.getValue("analysis.json"))
        assertArrayEquals(largeBytes, ByteArrayOutputStream().also { records.export(source.id, it) }.toByteArray())
        assertArrayEquals(wrongBytes, ByteArrayOutputStream().also { analyses.export(source, wrong.id, it) }.toByteArray())
    }

    private fun readArchive(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertTrue("duplicate ZIP entry", entry.name !in files)
                files[entry.name] = zip.readBytes()
            }
        }
        return files
    }

    // Normalize only Git test resources; saved/user originals must remain byte-exact.
    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/research/$name")).use {
        it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
