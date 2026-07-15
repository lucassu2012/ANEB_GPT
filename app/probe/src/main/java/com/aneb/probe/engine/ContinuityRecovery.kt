package com.aneb.probe.engine

/**
 * 阶段 2 C 组 C2 恢复重连编排（纯 JVM、无 Android 依赖，可直接单测）。
 *
 * 背景（真机取证 evidence/phase3/realdevice_continuity_kimi_20260713.log §3；决策 D-23）：
 * 绑定蜂窝网的 continuity run 在真机 蜂窝→WiFi **硬切换**下，系统拆除原蜂窝网句柄，
 * 而重连固定回绑原句柄 → EPERM 全败（status=recovery_failed，无 recovery_ms）。模拟器是
 * "平滑网络替换、原句柄可回绑"，故测得 508ms 掩盖了此缺陷。本编排把重连**决策**与两处
 * Android 副作用边界（"原绑定句柄是否已失效"、"迁到当前可用新默认网"）解耦——决策逻辑
 * （何时换网、何时判恢复/失败、恢复计时与 same/cross 语义）在此纯 JVM 覆盖。
 *
 * 两种 C2 语义（KPI 文档 §5.1/§5.2 注）：
 * - **same_network 重连恢复**：原绑定网仍在（或 AUTO 未绑定，切换由系统透明迁移），重连
 *   复用同一 client——模拟器 508ms 基线即此类。
 * - **cross_network 迁移恢复**：原绑定句柄被硬切换拆除，显式迁到当前系统新默认网后首
 *   token 到达——真实移动性场景，Agent 长会话的核心诉求（QUIC 连接迁移的应用层对应）。
 */
object ContinuityRecovery {

    /** 恢复编排结果。 */
    sealed interface Outcome<out A> {
        /**
         * @param result       恢复成功那次尝试的完整流结果（回到段循环作下一段）
         * @param recoveryMs   恢复时间＝首 token 到达 − 中断检出时刻（含退避与换网耗时，D-20）
         * @param attempt      第几次尝试恢复成功（1 起）
         * @param crossNetwork 是否经历跨网迁移（原句柄失效→换新默认网）；否＝same_network
         */
        data class Recovered<A>(
            val result: A,
            val recoveryMs: Double,
            val attempt: Int,
            val crossNetwork: Boolean,
        ) : Outcome<A>

        /** 所有尝试失败（含换网后仍无 token）。 */
        data class Failed(val attempts: Int) : Outcome<Nothing>
    }

    /**
     * 重连恢复循环（纯逻辑，副作用经函数参数注入）。
     *
     * 每次尝试：退避挂起 → 若原句柄已失效且尚未迁移则先迁到当前可用新默认网 → 发一次流；
     * 取到首 token 即 [Outcome.Recovered]（recovery_ms 含退避与换网耗时，覆盖"切到新网后首
     * token 到达"的 C2 口径），否则记失败续试；[maxAttempts] 次仍无 token → [Outcome.Failed]。
     *
     * @param interruptNanos         中断检出时刻（C2 计时起点，单调纳秒）
     * @param maxAttempts            最大重连次数（≤0 直接 Failed）
     * @param firstTokenNanosOf      从流结果取首 token 到达时刻（null＝该次仍无 token）
     * @param errorOf                从流结果取错误摘要（供失败日志）
     * @param delayBeforeAttempt     第 n 次尝试前的退避挂起（注入指数退避 delay）
     * @param boundNetworkLost       原绑定句柄是否已失效（AUTO/未绑定恒 false → 永不换网）
     * @param rebindToCurrentNetwork 迁到当前可用新默认网；成功返回非 null 描述（失败 null，下次再试）
     * @param attemptStream          发一次流（换网后自动经新 client）
     * @param onAttemptFailed        每次未取到 token 的失败日志回调
     * @param onRebind               每次换网尝试日志回调（detail=null 即失败）
     */
    suspend fun <A> recover(
        interruptNanos: Long,
        maxAttempts: Int,
        firstTokenNanosOf: (A) -> Long?,
        errorOf: (A) -> String?,
        delayBeforeAttempt: suspend (attempt: Int) -> Unit,
        boundNetworkLost: () -> Boolean,
        rebindToCurrentNetwork: suspend () -> String?,
        attemptStream: suspend () -> A,
        onAttemptFailed: suspend (attempt: Int, error: String?) -> Unit = { _, _ -> },
        onRebind: suspend (attempt: Int, detail: String?) -> Unit = { _, _ -> },
    ): Outcome<A> {
        var crossNetwork = false
        for (attempt in 1..maxAttempts) {
            delayBeforeAttempt(attempt)
            // 真机硬切换：原句柄失效 → 先迁到当前可用新默认网，避免在死句柄上 EPERM 空转。
            // crossNetwork 置位后不再重复迁移（一次成功迁移即锁定）；迁移失败下一次尝试再试。
            if (!crossNetwork && boundNetworkLost()) {
                val detail = rebindToCurrentNetwork()
                onRebind(attempt, detail)
                if (detail != null) crossNetwork = true
            }
            val a = attemptStream()
            val firstToken = firstTokenNanosOf(a)
            if (firstToken != null) {
                val recoveryMs = (firstToken - interruptNanos) / 1e6
                return Outcome.Recovered(a, recoveryMs, attempt, crossNetwork)
            }
            onAttemptFailed(attempt, errorOf(a))
        }
        return Outcome.Failed(maxAttempts)
    }

    /**
     * 重连错误是否表明"原绑定网络句柄已失效"——真机硬切换后回绑已拆除网的 socket。
     * 命中即触发迁到当前可用新默认网。取自真机取证（§3）：
     * "SocketException: Binding socket to network 110 failed: EPERM (Operation not permitted)"。
     */
    fun isBoundHandleDeadError(error: String?): Boolean {
        if (error == null) return false
        return error.contains("EPERM") ||
            error.contains("Binding socket to network") ||
            error.contains("ENETUNREACH")
    }
}
