package com.aneb.probe.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ScenarioGate 无竞态协议单测（评审发现 3）。
 *
 * 被钉住的语义：invalidate =「compareAndSet(null, reason) 成功后 cancel
 * currentScenario」；无论 invalidate 发生在注册前、注册与检查之间的窄窗口内、
 * 还是场景运行中，场景 block 都绝不能"带病跑完"——要么根本不开始，要么被即时取消。
 * 窄窗口用 afterRegister 时序注入钩子确定性复现（不靠碰运气的多线程调度）。
 */
class ScenarioGateTest {

    private val invalidReason = AtomicReference<String?>(null)
    private val currentScenario = AtomicReference<Job?>(null)

    /** 与 TestEngine.invalidate 相同的语义（首事件获胜 + cancel 当前场景）。 */
    private fun invalidate(reason: String) {
        if (invalidReason.compareAndSet(null, reason)) {
            currentScenario.get()?.cancel(CancellationException("invalidated:$reason"))
        }
    }

    @Test
    fun `未失效时场景正常执行`() = runBlocking {
        val ran = AtomicBoolean(false)
        val job = ScenarioGate.launchGuarded(this, invalidReason, currentScenario) { ran.set(true) }
        job.join()
        assertTrue(ran.get())
        assertFalse(job.isCancelled)
    }

    @Test
    fun `注册前已失效则场景不执行`() = runBlocking {
        invalidate("pre_existing")
        val ran = AtomicBoolean(false)
        val job = ScenarioGate.launchGuarded(this, invalidReason, currentScenario) { ran.set(true) }
        job.join()
        assertFalse("已失效场景绝不能开始执行", ran.get())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `窄窗口-注册与检查之间发生invalidate则场景不执行`() = runBlocking {
        // 旧代码的竞态窗口：job 已 launch（非 LAZY 即已在跑）但检查不到刚置的原因。
        // 新协议下该窗口内 invalidate：cancel 命中已注册的 LAZY job（尚未 start），
        // 或被步骤 3 的检查兜住——两条路都到 Cancelled。
        val ran = AtomicBoolean(false)
        val job = ScenarioGate.launchGuarded(
            scope = this,
            invalidReason = invalidReason,
            currentScenario = currentScenario,
            afterRegister = { invalidate("window_race") },
        ) { ran.set(true) }
        job.join()
        assertFalse("窄窗口内失效的场景绝不能带病跑完", ran.get())
        assertTrue(job.isCancelled)
        assertEquals("window_race", invalidReason.get())
    }

    @Test
    fun `运行中invalidate即时取消场景`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val completedHealthy = AtomicBoolean(false)
        val job = ScenarioGate.launchGuarded(this, invalidReason, currentScenario) {
            started.complete(Unit)
            CompletableDeferred<Unit>().await() // 模拟 in-flight 场景（永不自行完成）
            completedHealthy.set(true)
        }
        started.await()
        assertNotNull("start 后 currentScenario 必须已注册", currentScenario.get())
        invalidate("mid_run")
        job.join()
        assertTrue(job.isCancelled)
        assertFalse("invalidate 后场景不得继续执行到健康完成", completedHealthy.get())
    }
}
