package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadedResponsivenessV2Test {
    @Test
    fun `normal phase deadline with successful download headers and drained cancellation admits overlap without EOF`() {
        val report = LoadedResponsiveness.decode(normalDeadlineDownload())

        val summary = LoadedResponsiveness.summarize(report)

        assertEquals(12.0, summary.downloadP95Ms!!, 1e-9)
        assertEquals(1, summary.stages.single().validSampleCount)
    }

    @Test
    fun `byte growth before successful body start cannot certify a download echo`() {
        val raw = normalDeadlineDownload().replace(
            "\"headersUs\":1000010,\"httpCode\":200,\"bodyStartedUs\":1000010",
            "\"headersUs\":1120000,\"httpCode\":200,\"bodyStartedUs\":1120000",
        )

        val summary = LoadedResponsiveness.summarize(LoadedResponsiveness.decode(raw))

        assertNull(summary.downloadP95Ms)
    }

    private fun normalDeadlineDownload() = """
        {
          "runId":"v2-normal-deadline", "serverBase":"https://node.example", "transport":"wifi",
          "status":"completed", "methodVersion":"loaded-responsiveness-v2",
          "clockSource":"android.os.SystemClock.elapsedRealtimeNanos", "clockUnit":"us",
          "sourceProfileId":"basic_network", "sourceProfileVersion":"0.1.0",
          "stages":[{
            "stage":"download", "startUs":1000000, "endUs":2000000, "transferBytes":4096,
            "errors":[],
            "samples":[{"stage":"download","sequence":0,"t0Us":1100000,"t1Us":4000000,
              "t2Us":4000000,"t3Us":1112000,"httpCode":200,"error":null,"bytesBefore":100,"bytesAfter":200}],
            "byteObservations":[{"atUs":1000000,"totalBytes":0},{"atUs":1099999,"totalBytes":100,"requestId":"download-0-0"},
              {"atUs":1112000,"totalBytes":200,"requestId":"download-0-0"},{"atUs":2000000,"totalBytes":4096,"requestId":"download-0-0"}],
            "executionV2":{
              "plannedEndUs":2000000,"stopRequestedUs":2000000,"stopReason":"phase_deadline","snapshotUs":2000100,
              "requests":[{
                "requestId":"download-0-0","workerId":0,"direction":"download","requestedBytes":33554432,
                "startUs":1000000,"headersUs":1000010,"httpCode":200,"bodyStartedUs":1000010,
                "bodyCompletedUs":null,"cancelRequestedUs":2000000,"cancelCause":"phase_deadline",
                "cancelInvokedUs":2000000,"cancelReturnedUs":2000001,"callbackFinishedUs":2000050,
                "outcome":"cancelled","failureUs":null,"failure":null,"ioAfterCancel":"socket closed",
                "ioAfterCancelUs":2000020,"ioAfterCancelSawCancelled":true,
                "observedBytes":4096
              }]
            }
          }]
        }
    """.trimIndent()
}
