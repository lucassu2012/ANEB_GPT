package com.aneb.probe.ui

import com.aneb.probe.engine.BasicSpeedResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicSpeedConclusionsTest {
    @Test
    fun `complete healthy result gives scenario conclusions`() {
        val items = BasicSpeedConclusions.build(result())

        assertEquals("基础测速已完成", items.first().title)
        assertTrue(items.any { it.title == "4K 视频：适合" })
        assertTrue(items.any { it.title == "视频会议：适合" })
        assertTrue(items.any { it.title == "大文件上传：适合" })
    }

    @Test
    fun `missing values stay unavailable instead of becoming zero`() {
        val items = BasicSpeedConclusions.build(
            result(status = "partial", download = null, upload = null, ping = null, jitter = null, loss = null),
        )

        assertEquals("仅完成部分测速", items.first().title)
        assertTrue(items.any { it.title == "4K 视频：证据不足" })
        assertTrue(items.any { it.title == "视频会议：证据不足" })
        assertTrue(items.any { it.title == "大文件上传：证据不足" })
    }

    @Test
    fun `weak upload is named and limits upload use case`() {
        val items = BasicSpeedConclusions.build(result(upload = 1.5))

        assertTrue(items.any { it.title == "主要短板：上传带宽" })
        assertTrue(items.any { it.title == "大文件上传：受限" })
    }

    private fun result(
        status: String = "completed",
        download: Double? = 120.0,
        upload: Double? = 30.0,
        ping: Double? = 25.0,
        jitter: Double? = 4.0,
        loss: Double? = 0.0,
    ) = BasicSpeedResult(
        runId = "r1",
        startedAtEpochMs = 1L,
        serverBase = "https://example.test",
        profileVersion = "0.1.0",
        status = status,
        downloadMbps = download,
        uploadMbps = upload,
        pingMs = ping,
        jitterMs = jitter,
        requestLossRate = loss,
        postLoadPingMs = 35.0,
        downloadBytes = 1L,
        uploadBytes = 1L,
        transferErrors = emptyList(),
    )
}
