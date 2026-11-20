package com.aneb.probe.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [NegotiatedProtocolLog] 纯 JVM 守卫。
 */
class NegotiatedProtocolLogTest {

    @Test
    fun `同一协议多次观测 → 计数累加，总数与分表一致`() {
        val log = NegotiatedProtocolLog()
        repeat(3) { log.observe("http/1.1", "HTTP/1.1;via=tcp") }
        val s = log.snapshot()
        assertEquals(3L, s.samples)
        assertEquals(mapOf("http/1.1" to 3L), s.byProtocol)
        assertEquals(mapOf("HTTP/1.1;via=tcp" to 3L), s.byProtoHeader)
        assertEquals(0L, s.headerAbsent)
    }

    @Test
    fun `混入一条 h2 → 分表出现第二个键，证明判法有区分能力`() {
        val log = NegotiatedProtocolLog()
        repeat(5) { log.observe("http/1.1", "HTTP/1.1;via=tcp") }
        log.observe("h2", "HTTP/2.0;via=tcp")
        val s = log.snapshot()
        assertEquals(setOf("http/1.1", "h2"), s.byProtocol.keys)
        assertEquals(6L, s.samples)
    }

    @Test
    fun `头缺席与空串是两个状态_不得合并`() {
        val log = NegotiatedProtocolLog()
        log.observe("http/1.1", null)
        log.observe("http/1.1", "")
        val s = log.snapshot()
        assertEquals("缺席只计 headerAbsent", 1L, s.headerAbsent)
        assertEquals("空串是一个真实的值，要进分表", mapOf("" to 1L), s.byProtoHeader)
        assertEquals(2L, s.samples)
    }

    @Test
    fun `零观测 → samples 为 0 且两张分表为空`() {
        val s = NegotiatedProtocolLog().snapshot()
        assertEquals(0L, s.samples)
        assertEquals(emptyMap<String, Long>(), s.byProtocol)
        assertEquals(0L, s.headerAbsent)
    }

    @Test
    fun `快照按键排序_同样的数据必须产出同样的顺序`() {
        val log = NegotiatedProtocolLog()
        listOf("h3", "http/1.1", "h2").forEach { log.observe(it, null) }
        assertEquals(listOf("h2", "h3", "http/1.1"), log.snapshot().byProtocol.keys.toList())
    }

    @Test
    fun `并发观测不丢数`() {
        val log = NegotiatedProtocolLog()
        val threads = 4
        val each = 250
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(each) { log.observe(if (t % 2 == 0) "http/1.1" else "h2", null) }
                done.countDown()
            }
        }
        start.countDown()
        assertEquals(true, done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        val s = log.snapshot()
        assertEquals((threads * each).toLong(), s.samples)
        assertEquals((threads * each).toLong(), s.byProtocol.values.sum())
    }

    @Test
    fun `并发读写压力下每份中间快照都保持严格原子不变量`() {
        val log = NegotiatedProtocolLog()
        val writers = 8
        val iterations = 500
        val pool = Executors.newFixedThreadPool(writers + 1)
        val start = CountDownLatch(1)
        val writersDone = CountDownLatch(writers)
        val violations = java.util.concurrent.CopyOnWriteArrayList<String>()

        repeat(writers) { w ->
            pool.execute {
                start.await()
                repeat(iterations) { i ->
                    val proto = if ((w + i) % 2 == 0) "http/1.1" else "h2"
                    val header = when (i % 3) {
                        0 -> null
                        1 -> "HTTP/1.1;via=tcp"
                        else -> "HTTP/2.0;via=tcp"
                    }
                    log.observe(proto, header)
                }
                writersDone.countDown()
            }
        }
        val readerFuture = pool.submit {
            start.await()
            while (writersDone.count > 0) {
                val s = log.snapshot()
                val protoSum = s.byProtocol.values.sum()
                val headerSum = s.headerAbsent + s.byProtoHeader.values.sum()
                if (s.samples != protoSum || s.samples != headerSum) {
                    violations += "samples=${s.samples}, protoSum=$protoSum, headerSum=$headerSum"
                }
            }
        }

        start.countDown()
        assertEquals(true, writersDone.await(30, TimeUnit.SECONDS))
        readerFuture.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(emptyList<String>(), violations)
        val finalSnap = log.snapshot()
        assertEquals((writers * iterations).toLong(), finalSnap.samples)
        assertEquals(finalSnap.samples, finalSnap.byProtocol.values.sum())
        assertEquals(finalSnap.samples, finalSnap.headerAbsent + finalSnap.byProtoHeader.values.sum())
    }
}
