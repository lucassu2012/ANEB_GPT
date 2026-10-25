package com.aneb.probe.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadedResponsivenessTest {
    @Test
    fun `successful echo without observed transfer bytes is not loaded latency`() {
        val echo = echo("download", 0, 10_000L)
        val stage = stage("download", listOf(echo)).copy(
            transferBytes = 0L,
            errors = listOf("download_failed"),
        )
        val report = report(listOf(stage))

        val summary = LoadedResponsiveness.summarize(report)

        assertNull(summary.downloadP95Ms)
        assertNull(summary.downloadDeltaMs)
        assertEquals(0, summary.stages.single { it.stage == "download" }.validSampleCount)
        assertEquals("no_transfer_bytes", summary.stages.single { it.stage == "download" }.missingReason)
        assertEquals(listOf(echo), report.stages.single().samples)
        assertEquals(listOf("download_failed"), report.stages.single().errors)
    }

    @Test
    fun `directions use their own P95 and retain a negative delta from idle P50`() {
        val report = report(
            listOf(
                stage("idle", listOf(echo("idle", 0, 10_000L), echo("idle", 1, 30_000L)))
                    .copy(transferBytes = 0L),
                stage("download", listOf(echo("download", 0, 50_000L), echo("download", 1, 100_000L))),
                stage("upload", listOf(echo("upload", 0, 5_000L), echo("upload", 1, 15_000L))),
            ),
        )

        val summary = LoadedResponsiveness.summarize(report)

        assertEquals(20.0, summary.idleP50Ms!!, 1e-9)
        assertEquals(97.5, summary.downloadP95Ms!!, 1e-9)
        assertEquals(14.5, summary.uploadP95Ms!!, 1e-9)
        assertEquals(77.5, summary.downloadDeltaMs!!, 1e-9)
        assertEquals(-5.5, summary.uploadDeltaMs!!, 1e-9)
        assertEquals(listOf(2, 2, 2), summary.stages.map { it.validSampleCount })
    }

    @Test
    fun `raw failed cross stage and nonoverlap echoes remain visible but are excluded`() {
        val samples = listOf(
            echo("download", 0, 12_000L),
            echo("download", 1, 20_000L).copy(error = "socket_failed", t3Us = null),
            echo("download", 2, 20_000L).copy(httpCode = 503),
            echo("upload", 3, 20_000L),
            echo("download", 4, 20_000L).copy(bytesAfter = 100L),
            echo("download", 5, 20_000L).copy(t3Us = 2_000_001L),
            echo("download", 6, 20_000L).copy(t1Us = null),
            echo("download", 7, 20_000L).copy(t2Us = 3_999_999L),
            echo("download", 8, 20_000L).copy(t2Us = 4_030_000L),
        )
        val raw = report(
            listOf(stage("download", samples).copy(
                byteObservations = listOf(
                    ResponsivenessByteObservation(1_099_999L, 100L),
                    ResponsivenessByteObservation(1_112_000L, 200L),
                ),
            )),
        )

        val result = LoadedResponsiveness.summarize(raw).stages.single()

        assertEquals(12.0, result.valueMs!!, 1e-9)
        assertEquals(1, result.validSampleCount)
        assertEquals(samples.size, result.totalSampleCount)
        assertEquals(
            listOf(null, "request_error", "http_not_successful", "stage_mismatch",
                "no_observed_byte_overlap", "outside_stage", "missing_timestamp",
                "invalid_timestamp_order", "server_processing_exceeds_elapsed"),
            result.sampleResults.map { it.exclusionReason },
        )
        assertEquals(samples, raw.stages.single().samples)
    }

    @Test
    fun `incomplete runs preserve raw samples but never yield aggregate latency`() {
        val stages = listOf(
            stage("idle", listOf(echo("idle", 0, 10_000L))),
            stage("download", listOf(echo("download", 0, 20_000L))),
            stage("upload", listOf(echo("upload", 0, 30_000L))),
        )
        for (status in listOf("partial", "cancelled", "guard_rejected", "error")) {
            val raw = report(stages).copy(status = status)
            val summary = LoadedResponsiveness.summarize(raw)
            assertNull(summary.idleP50Ms)
            assertNull(summary.downloadP95Ms)
            assertNull(summary.uploadP95Ms)
            assertNull(summary.downloadDeltaMs)
            assertNull(summary.uploadDeltaMs)
            assertTrue(summary.stages.all { it.validSampleCount == 0 && it.missingReason == "run_not_completed:$status" })
            assertEquals(stages, raw.stages)
        }
    }

    @Test
    fun `JSON round trip preserves raw identity failures and independently recomputable summary`() {
        val raw = report(
            listOf(
                stage("idle", listOf(echo("idle", 0, 10_000L))).copy(transferBytes = 0L),
                stage(
                    "download",
                    listOf(
                        echo("download", 0, 12_000L),
                        echo("download", 1, 20_000L).copy(error = "socket_failed", t3Us = null),
                    ),
                ).copy(errors = listOf("保留原始失败\nnot a zero")),
            ),
        ).copy(sourceProfileId = "basic_network", sourceProfileVersion = "0.1.0")

        val encoded = LoadedResponsiveness.encode(raw)
        val decoded = LoadedResponsiveness.decode(encoded)

        assertEquals(raw, decoded)
        assertEquals(LoadedResponsiveness.summarize(raw), LoadedResponsiveness.summarize(decoded))
        assertTrue(Json.parseToJsonElement(encoded).jsonObject.containsKey("derived_summary"))
        assertEquals("保留原始失败\nnot a zero", decoded.stages[1].errors.single())
        assertNull(decoded.stages[1].samples[1].t3Us)
    }

    @Test
    fun `byte growth after echo response timestamp cannot certify loaded overlap`() {
        val echo = echo("download", 0, 12_000L)
        val raw = report(
            listOf(stage("download", listOf(echo)).copy(
                byteObservations = listOf(
                    ResponsivenessByteObservation(1_099_999L, 100L),
                    ResponsivenessByteObservation(1_112_001L, 200L),
                ),
            )),
        )

        val result = LoadedResponsiveness.summarize(raw).stages.single()

        assertNull(result.valueMs)
        assertEquals(0, result.validSampleCount)
        assertEquals("byte_observation_mismatch", result.sampleResults.single().exclusionReason)
        assertEquals(echo, raw.stages.single().samples.single())
        assertEquals(200L, raw.stages.single().byteObservations.last().totalBytes)
    }

    @Test
    fun `partial bytes without any completed transfer never qualify the loaded direction`() {
        val raw = report(
            listOf(stage("download", listOf(echo("download", 0, 12_000L))).copy(
                errors = listOf("no_transfer_completed_before_phase_deadline"),
            )),
        )

        val result = LoadedResponsiveness.summarize(raw).stages.single()

        assertNull(result.valueMs)
        assertEquals(0, result.validSampleCount)
        assertEquals("no_completed_transfer", result.missingReason)
        assertEquals(4096L, raw.stages.single().transferBytes)
        assertEquals(listOf("no_transfer_completed_before_phase_deadline"), raw.stages.single().errors)
    }

    @Test
    fun `late cancellation drain byte observations stay raw without invalidating in stage overlap`() {
        val late = ResponsivenessByteObservation(2_000_001L, 400L)
        val stage = stage("download", listOf(echo("download", 0, 12_000L)))
        val raw = report(listOf(stage.copy(byteObservations = stage.byteObservations + late)))

        val result = LoadedResponsiveness.summarize(raw).stages.single()

        assertEquals(12.0, result.valueMs!!, 1e-9)
        assertEquals(1, result.validSampleCount)
        assertNull(result.missingReason)
        assertEquals(late, raw.stages.single().byteObservations.last())
    }

    private fun report(stages: List<ResponsivenessStage>) = LoadedResponsivenessReport(
        runId = "run-loaded-1",
        serverBase = "https://node.example",
        transport = "wifi",
        status = "completed",
        stages = stages,
    )

    private fun stage(name: String, samples: List<ResponsivenessEcho>) = ResponsivenessStage(
        stage = name,
        startUs = 1_000_000L,
        endUs = 2_000_000L,
        transferBytes = 4096L,
        samples = samples,
        byteObservations = samples.flatMap { sample ->
            listOfNotNull(
                ResponsivenessByteObservation(sample.t0Us - 1L, sample.bytesBefore),
                sample.t3Us?.let { ResponsivenessByteObservation(it, sample.bytesAfter) },
            )
        }.sortedBy { it.atUs },
    )

    private fun echo(name: String, sequence: Int, rttUs: Long) = ResponsivenessEcho(
        stage = name,
        sequence = sequence,
        t0Us = 1_100_000L + sequence * 100_000L,
        t1Us = 4_000_000L,
        t2Us = 4_000_000L,
        t3Us = 1_100_000L + sequence * 100_000L + rttUs,
        httpCode = 200,
        error = null,
        bytesBefore = 100L + sequence * 100L,
        bytesAfter = 200L + sequence * 100L,
    )
}
