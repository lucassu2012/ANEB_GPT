package com.aneb.probe.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/**
 * 场景启动门：消除「launch 场景 Job」与「currentScenario.set(job)」之间的竞态窗口。
 *
 * 竞态背景（评审发现 3）：invalidate() 的语义是
 * `invalidReason.compareAndSet(null, reason)` 成功后 `currentScenario.get()?.cancel()`。
 * 若场景 Job 已经 start 但尚未 set 进 currentScenario，这个窄窗口内发生的 invalidate
 * 只置了原因、cancel 打在 null 上——场景带病跑完全程，事后才被追溯打标，违背
 * fail-closed（R-01/R-10：失效必须即时中止场景）。
 *
 * 无竞态协议（三步顺序即正确性证明）：
 * 1. 以 [CoroutineStart.LAZY] 创建 Job——注册前绝不开始执行；
 * 2. 先注册进 [currentScenario]——此后任何 invalidate 的 cancel 都能命中本 Job
 *    （cancel 未 start 的 LAZY Job 使其直接进入 Cancelled，join 立即返回）；
 * 3. 再检查 [invalidReason]：非空 → cancel（覆盖步骤 2 之前就已 invalidate 的情形，
 *    AtomicReference 的可见性保证读到）；空 → start。
 * 两侧任意交错下，场景要么被取消、要么在 invalidate 时已可被 cancel 命中——不存在
 * 「置了原因却没人被 cancel」的窗口。
 */
internal object ScenarioGate {

    /**
     * 按上述协议启动场景 Job。
     *
     * @param afterRegister 时序注入钩子（仅测试用，默认空）：在步骤 2（注册）之后、
     *   步骤 3（检查）之前调用，用于单测钉住"注册与检查之间发生 invalidate"的窄窗口语义。
     */
    fun launchGuarded(
        scope: CoroutineScope,
        invalidReason: AtomicReference<String?>,
        currentScenario: AtomicReference<Job?>,
        afterRegister: () -> Unit = {},
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block) // 1. LAZY：注册前不执行
        currentScenario.set(job) // 2. 先注册：此后 invalidate 的 cancel 必命中本 Job
        afterRegister()
        if (invalidReason.get() != null) job.cancel() else job.start() // 3. 后检查
        return job
    }
}
