package com.aneb.probe.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P3-C05：SseStreamResult 透传 summary 的 retrans_total（服务端 TCP_INFO
 * tcpi_total_retrans）。字段缺省（非 Linux 服务端/h3）或 summary 整体缺失
 * （truncate 注入/流中断）→ null（R-10 无值不造值）。
 */
class SseSummaryRetransTest {

    private fun result(summaryRaw: String?) = SseStreamResult(
        prelude = null,
        events = emptyList(),
        summaryRaw = summaryRaw,
        readCount = 1,
        totalBytes = 10L,
        parseErrors = 0,
        truncatedTail = false,
        eofNanos = 1_000L,
        parseEndNanos = 2_000L,
    )

    @Test
    fun retransTotalParsedFromSummary() {
        val summary = """{"tokens":100,"stream_start_us":123,"flush_return_us":[1,2],""" +
            """"timer_late_us":[0,0],"flush_block_us":[0,0],"carryover_us":[0,0],"retrans_total":7}"""
        assertEquals(7L, result(summary).summaryRetransTotal)
    }

    @Test
    fun retransTotalZeroIsZeroNotNull() {
        // 干净路径 retrans_total=0：有数据的 0 与"无数据 null"语义必须区分
        assertEquals(0L, result("""{"tokens":5,"retrans_total":0}""").summaryRetransTotal)
    }

    @Test
    fun missingFieldYieldsNull() {
        // 非 Linux 服务端/h3：summary 存在但字段缺省 → null（n/a 回退）
        val summary = """{"tokens":100,"stream_start_us":123,"flush_return_us":[1,2],""" +
            """"timer_late_us":[0,0],"flush_block_us":[0,0],"carryover_us":[0,0]}"""
        assertNull(result(summary).summaryRetransTotal)
    }

    @Test
    fun missingSummaryYieldsNull() {
        // truncate 注入/流中断：无 summary → null
        assertNull(result(null).summaryRetransTotal)
    }
}
