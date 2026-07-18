package com.aneb.probe.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterTest {
    @Test fun successfulExportWritesUtf8AndFinalizesExactlyOnce() {
        val sink = FakeSink()

        val result = Exporter.exportWithSink("中文\n", sink)

        assertTrue(result.ok)
        assertEquals("content://downloads/1", result.uri)
        assertEquals("中文\n".toByteArray(Charsets.UTF_8).size, result.bytes)
        assertNull(result.error)
        assertArrayEquals("中文\n".toByteArray(Charsets.UTF_8), sink.output.toByteArray())
        assertEquals(1, sink.finalizeCalls)
        assertEquals(0, sink.deleteCalls)
    }

    @Test fun missingOutputStreamDeletesPendingRow() {
        val sink = FakeSink(openResult = null)

        val result = Exporter.exportWithSink("evidence", sink)

        assertFalse(result.ok)
        assertNull(result.uri)
        assertEquals("open_output_stream_null", result.error)
        assertEquals(1, sink.deleteCalls)
        assertEquals(0, sink.finalizeCalls)
    }

    @Test fun writeFailureDeletesPendingRow() {
        val sink = FakeSink(
            openResult = object : OutputStream() {
                override fun write(value: Int) = throw IOException("disk_full")
            },
        )

        val result = Exporter.exportWithSink("evidence", sink)

        assertFalse(result.ok)
        assertNull(result.uri)
        assertTrue(result.error!!.startsWith("IOException:disk_full"))
        assertEquals(1, sink.deleteCalls)
        assertEquals(0, sink.finalizeCalls)
    }

    @Test fun finalizeFailureIsNotReportedAsSuccessAndDeletesPendingRow() {
        val sink = FakeSink(finalizeResult = false)

        val result = Exporter.exportWithSink("evidence", sink)

        assertFalse(result.ok)
        assertNull(result.uri)
        assertEquals("mediastore_finalize_failed", result.error)
        assertEquals(1, sink.finalizeCalls)
        assertEquals(1, sink.deleteCalls)
    }

    @Test fun cleanupFailureRetainsUriForDiagnosis() {
        val sink = FakeSink(openResult = null, deleteResult = false)

        val result = Exporter.exportWithSink("evidence", sink)

        assertFalse(result.ok)
        assertEquals("content://downloads/1", result.uri)
        assertEquals("open_output_stream_null;cleanup_failed", result.error)
    }

    @Test fun insertFailureDoesNotAttemptCleanup() {
        val sink = FakeSink(createResult = null)

        val result = Exporter.exportWithSink("evidence", sink)

        assertFalse(result.ok)
        assertNull(result.uri)
        assertEquals("mediastore_insert_null", result.error)
        assertEquals(0, sink.deleteCalls)
    }

    private class FakeSink(
        private val createResult: String? = "content://downloads/1",
        openResult: OutputStream? = ByteArrayOutputStream(),
        private val finalizeResult: Boolean = true,
        private val deleteResult: Boolean = true,
    ) : Exporter.ExportSink {
        val output = (openResult as? ByteArrayOutputStream) ?: ByteArrayOutputStream()
        private val stream = openResult
        var finalizeCalls = 0
        var deleteCalls = 0

        override fun create(): String? = createResult
        override fun open(uri: String): OutputStream? = stream
        override fun finalize(uri: String): Boolean {
            finalizeCalls += 1
            return finalizeResult
        }
        override fun delete(uri: String): Boolean {
            deleteCalls += 1
            return deleteResult
        }
    }
}
