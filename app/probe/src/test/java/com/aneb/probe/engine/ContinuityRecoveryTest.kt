package com.aneb.probe.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 2 C 组 C2 恢复重连编排纯 JVM 单测（真机跨网迁移修复 D-23）。
 *
 * 覆盖任务书要求的"原句柄失效→回绑新网"路径，并锚定 same_network 决策不回归：
 * - same_network：原绑定网仍在（AUTO/未绑定）→ **绝不换网**（模拟器 508ms 路径护栏）；
 * - cross_network：原句柄失效（真机硬切换 EPERM/bound_network_lost）→ 迁到当前新默认网恢复；
 * - 迁移重试直到有网、全失败语义、恢复计时口径、死句柄错误识别、一次迁移不重复换网。
 *
 * 注入的 delayBeforeAttempt 为空实现 → 退避不占真实时间，runBlocking 即时完成。
 */
class ContinuityRecoveryTest {

    private data class FakeAttempt(val firstTokenNanos: Long?, val error: String?)

    private fun attempt(firstTokenNanos: Long? = null, error: String? = null) =
        FakeAttempt(firstTokenNanos, error)

    /** 按次序返回预设流结果；耗尽后重复返回最后一个。 */
    private fun streamer(vararg results: FakeAttempt): suspend () -> FakeAttempt {
        var i = 0
        return {
            val r = results[minOf(i, results.size - 1)]
            i++
            r
        }
    }

    /** 按次序返回预设换网结果（null=换网失败）；耗尽后重复返回最后一个。 */
    private fun rebinder(vararg details: String?): suspend () -> String? {
        var i = 0
        return {
            val r = details[minOf(i, details.size - 1)]
            i++
            r
        }
    }

    private fun <A> recovered(o: ContinuityRecovery.Outcome<A>): ContinuityRecovery.Outcome.Recovered<A> {
        assertTrue("expected Recovered but was $o", o is ContinuityRecovery.Outcome.Recovered)
        @Suppress("UNCHECKED_CAST")
        return o as ContinuityRecovery.Outcome.Recovered<A>
    }

    // ---------- same_network：原句柄不失效，绝不换网（模拟器 508ms 路径不回归） ----------

    @Test
    fun `same network recovers without any rebind`() = runBlocking {
        var rebindCalls = 0
        val interrupt = 1_000_000_000L
        val outcome = ContinuityRecovery.recover(
            interruptNanos = interrupt,
            maxAttempts = 5,
            firstTokenNanosOf = { it.firstTokenNanos },
            errorOf = { it.error },
            delayBeforeAttempt = { },
            boundNetworkLost = { false }, // AUTO/绑定网仍在
            rebindToCurrentNetwork = { rebindCalls++; "should_not_happen" },
            attemptStream = streamer(attempt(firstTokenNanos = interrupt + 508_000_000L)),
        )
        val rec = recovered(outcome)
        assertEquals(0, rebindCalls) // 关键回归护栏：same-network 决策从不换网
        assertFalse(rec.crossNetwork)
        assertEquals(1, rec.attempt)
        assertEquals(508.0, rec.recoveryMs, 1e-9)
    }

    // ---------- cross_network：原句柄失效 → 迁到当前新默认网后恢复 ----------

    @Test
    fun `cross network rebinds to new default net when bound handle dead`() = runBlocking {
        var rebindCalls = 0
        val interrupt = 2_000_000_000L
        val outcome = ContinuityRecovery.recover(
            interruptNanos = interrupt,
            maxAttempts = 5,
            firstTokenNanosOf = { it.firstTokenNanos },
            errorOf = { it.error },
            delayBeforeAttempt = { },
            boundNetworkLost = { true }, // 真机硬切换：bound_network_lost 已置位
            rebindToCurrentNetwork = { rebindCalls++; "unbound_default_network" },
            // 迁到新默认网后首 token 到达（interrupt 起算 800ms）
            attemptStream = streamer(attempt(firstTokenNanos = interrupt + 800_000_000L)),
        )
        val rec = recovered(outcome)
        assertEquals(1, rebindCalls) // 迁移恰好一次
        assertTrue(rec.crossNetwork)
        assertEquals(1, rec.attempt)
        assertEquals(800.0, rec.recoveryMs, 1e-9)
    }

    @Test
    fun `dead handle retries rebind until a network becomes available`() = runBlocking {
        var failCount = 0
        val interrupt = 3_000_000_000L
        val rebind = rebinder(null, null, "unbound_default_network") // 前两次无网，第三次成功
        var rebindCalls = 0
        val outcome = ContinuityRecovery.recover(
            interruptNanos = interrupt,
            maxAttempts = 5,
            firstTokenNanosOf = { it.firstTokenNanos },
            errorOf = { it.error },
            delayBeforeAttempt = { },
            boundNetworkLost = { true },
            rebindToCurrentNetwork = { rebindCalls++; rebind() },
            // 前两次仍在死句柄上无 token，第三次迁网成功后首 token 到达
            attemptStream = streamer(
                attempt(error = "EPERM"),
                attempt(error = "EPERM"),
                attempt(firstTokenNanos = interrupt + 1_200_000_000L),
            ),
            onAttemptFailed = { _, _ -> failCount++ },
        )
        val rec = recovered(outcome)
        assertEquals(3, rebindCalls) // 每次尝试都重试换网，直到第三次成功
        assertEquals(2, failCount) // 前两次尝试失败
        assertTrue(rec.crossNetwork)
        assertEquals(3, rec.attempt)
        assertEquals(1200.0, rec.recoveryMs, 1e-9)
    }

    @Test
    fun `once migrated does not rebind again`() = runBlocking {
        var rebindCalls = 0
        val interrupt = 4_000_000_000L
        val outcome = ContinuityRecovery.recover(
            interruptNanos = interrupt,
            maxAttempts = 5,
            firstTokenNanosOf = { it.firstTokenNanos },
            errorOf = { it.error },
            delayBeforeAttempt = { },
            boundNetworkLost = { true }, // 恒 true，但迁移成功后不应再触发
            rebindToCurrentNetwork = { rebindCalls++; "unbound_default_network" },
            // 迁网后第一次仍无 token（新连接建立中），第二次到达
            attemptStream = streamer(
                attempt(error = "read timeout"),
                attempt(firstTokenNanos = interrupt + 900_000_000L),
            ),
        )
        val rec = recovered(outcome)
        assertEquals(1, rebindCalls) // crossNetwork 置位后不再重复换网
        assertTrue(rec.crossNetwork)
        assertEquals(2, rec.attempt)
    }

    // ---------- 全失败语义（R-10：无样本，status=recovery_failed） ----------

    @Test
    fun `all attempts failing returns Failed with attempt count`() = runBlocking {
        var failCount = 0
        val outcome = ContinuityRecovery.recover(
            interruptNanos = 0L,
            maxAttempts = 5,
            firstTokenNanosOf = { it.firstTokenNanos },
            errorOf = { it.error },
            delayBeforeAttempt = { },
            boundNetworkLost = { false },
            rebindToCurrentNetwork = { "unused" },
            attemptStream = streamer(attempt(error = "no_token")),
            onAttemptFailed = { _, _ -> failCount++ },
        )
        assertTrue(outcome is ContinuityRecovery.Outcome.Failed)
        assertEquals(5, (outcome as ContinuityRecovery.Outcome.Failed).attempts)
        assertEquals(5, failCount)
    }

    @Test
    fun `zero max attempts returns Failed without streaming`() = runBlocking {
        var streamCalls = 0
        val outcome = ContinuityRecovery.recover(
            interruptNanos = 0L,
            maxAttempts = 0,
            firstTokenNanosOf = { it.firstTokenNanos },
            errorOf = { it.error },
            delayBeforeAttempt = { },
            boundNetworkLost = { false },
            rebindToCurrentNetwork = { "unused" },
            attemptStream = { streamCalls++; attempt() },
        )
        assertTrue(outcome is ContinuityRecovery.Outcome.Failed)
        assertEquals(0, streamCalls)
    }

    // ---------- 恢复计时口径（含退避与换网耗时，D-20；覆盖切到新网后首 token） ----------

    @Test
    fun `recovery ms is first token arrival minus interrupt`() = runBlocking {
        val interrupt = 10_000_000_000L
        val outcome = ContinuityRecovery.recover(
            interruptNanos = interrupt,
            maxAttempts = 3,
            firstTokenNanosOf = { it.firstTokenNanos },
            errorOf = { it.error },
            delayBeforeAttempt = { },
            boundNetworkLost = { false },
            rebindToCurrentNetwork = { "unused" },
            // 第一次无 token，第二次于 interrupt+1508ms 到达（含退避）
            attemptStream = streamer(
                attempt(error = "no_token"),
                attempt(firstTokenNanos = interrupt + 1_508_000_000L),
            ),
        )
        val rec = recovered(outcome)
        assertEquals(2, rec.attempt)
        assertEquals(1508.0, rec.recoveryMs, 1e-9)
    }

    // ---------- 死句柄错误识别（触发迁到新默认网） ----------

    @Test
    fun `dead handle error detection matches real device EPERM evidence`() {
        assertTrue(
            ContinuityRecovery.isBoundHandleDeadError(
                "java.net.SocketException: Binding socket to network 110 failed: " +
                    "EPERM (Operation not permitted)",
            ),
        )
        assertTrue(ContinuityRecovery.isBoundHandleDeadError("libcore ... EPERM ..."))
        assertTrue(ContinuityRecovery.isBoundHandleDeadError("connect failed: ENETUNREACH"))
    }

    @Test
    fun `non dead handle errors do not trigger migration`() {
        assertFalse(ContinuityRecovery.isBoundHandleDeadError(null))
        assertFalse(ContinuityRecovery.isBoundHandleDeadError("http 500"))
        assertFalse(
            ContinuityRecovery.isBoundHandleDeadError("java.net.SocketTimeoutException: timeout"),
        )
    }
}
