package com.aneb.probe.research.engineering

import java.io.File

/** One same-operation diagnostic, with no input, SEND, recording or network capability. */
class DeepSeekModeOperationDiagnostic(private val android: Android,
    private val clock: TongyiEngineeringRunner.MonotonicClock) {
    interface Android {
        fun diagnoseFirstMode(deadlineNanos: Long): DeepSeekUiAdapter.ModeGate
        fun cleanupOwned(): Boolean
    }
    data class Result(val gate: DeepSeekUiAdapter.ModeGate?, val failure: Exception?,
        val ownedCleanup: Boolean, val cleanupFailure: Exception?, val preparationDeadlineNanos: Long)

    fun runOnce(runId: String, root: File): Result {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        check(root.mkdir()) { "OPPORTUNITY_ALREADY_CONSUMED_NO_RETRY" }
        val deadline = clock.nowNanos() + TongyiEngineeringRunner.PREP_NANOS
        var gate: DeepSeekUiAdapter.ModeGate? = null
        var failure: Exception? = null
        var cleanupFailure: Exception? = null
        var cleaned = false
        try {
            check(clock.nowNanos() < deadline) { "PREPARATION_180_EXPIRED" }
            gate = android.diagnoseFirstMode(deadline)
        } catch (original: Exception) { failure = original }
        finally {
            try { cleaned = android.cleanupOwned() }
            catch (original: Exception) { cleanupFailure = original }
        }
        return Result(gate, failure, cleaned, cleanupFailure, deadline)
    }
}
