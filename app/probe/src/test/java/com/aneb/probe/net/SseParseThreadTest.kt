package com.aneb.probe.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * R-04/设计文档 §4 第 10 条收口单测：EOF 后解析的执行线程为专用解析线程
 * （读线程只保留 read→打戳→缓冲职责），且异常合同不变（原类型透传）。
 * JVM 环境无 android.os.Process，优先级提升静默降级——线程语义仍可测。
 */
class SseParseThreadTest {

    @Test
    fun executeRunsBlockOnDedicatedParseThread() {
        val callerThread = Thread.currentThread().name
        val parseThread = SseParseThread.execute { Thread.currentThread().name }
        assertEquals(SseParseThread.THREAD_NAME, parseThread)
        assertNotEquals(callerThread, parseThread)
        // 调用线程（读线程角色）本身不被切换
        assertEquals(callerThread, Thread.currentThread().name)
    }

    @Test
    fun executeReturnsValueSynchronously() {
        assertEquals(42, SseParseThread.execute { 21 * 2 })
        // 单线程 executor：顺序语义（后提交的任务看到前一个的副作用）
        val sb = StringBuilder()
        SseParseThread.execute { sb.append("a") }
        SseParseThread.execute { sb.append("b") }
        assertEquals("ab", sb.toString())
    }

    @Test
    fun executePropagatesExceptionWithOriginalType() {
        // readStream 既有异常合同：解析异常原类型透传给上层 catch（不包壳）
        try {
            SseParseThread.execute<Unit> { throw IllegalStateException("parse boom") }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("parse boom", e.message)
        }
        // 抛异常后线程仍可用（executor 不因单任务失败而失活）
        assertEquals("ok", SseParseThread.execute { "ok" })
    }
}
