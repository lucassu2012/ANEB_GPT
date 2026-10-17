package com.aneb.probe.research.engineering

import java.io.File
import java.io.FileOutputStream

/** A capability-limited draft qualification, not a collection/SEND/recording interface. */
class DeepSeekDraftRoleQualification(private val android: Android, private val clock: TongyiEngineeringRunner.MonotonicClock) {
    data class Readback(val packageName: String, val owner: String, val windowId: Int, val parentWindowId: Int,
        val sameEditorAndParent: Boolean, val text: String, val showingHint: Boolean, val focused: Boolean)
    interface Android {
        fun prepareEmpty(deadlineNanos: Long)
        fun enterExactAndReadback(text: String): Readback
        fun retainCurrent(label: String)
        fun retainRejectedCached(): Boolean
        fun cleanupOwned(): Boolean
    }
    data class Result(val state: String, val inputReadbackConfirmed: Boolean, val retainedStages: List<String>,
        val failure: String?, val originalStack: String?, val ownedCleanup: Boolean,
        val rejectedRetained: Boolean, val rejectedRetentionFailure: String?)

    fun runOnce(runId: String, root: File): Result {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        check(root.mkdir()) { "OPPORTUNITY_ALREADY_CONSUMED_NO_RETRY" }
        val deadline = clock.nowNanos() + TongyiEngineeringRunner.PREP_NANOS
        fun gate() { check(clock.nowNanos() < deadline) { "PREPARATION_180_EXPIRED" } }
        val retained = ArrayList<String>()
        var readbackConfirmed = false
        var failure: Exception? = null
        var cleaned = false
        try {
            gate()
            android.prepareEmpty(deadline)
            gate()
            android.retainCurrent("new-empty")
            retained += "new-empty"
            gate()
            val inputStarted = clock.nowNanos()
            val actual = android.enterExactAndReadback(TongyiEngineeringRunner.PAYLOAD)
            check(clock.nowNanos() - inputStarted <= TongyiEngineeringRunner.INPUT_NANOS) { "INPUT_FIVE_SECOND_LIMIT" }
            check(actual.packageName == EngineeringTarget.DEEPSEEK.packageName && actual.owner.isNotBlank() &&
                actual.windowId >= 0 && actual.windowId == actual.parentWindowId && actual.sameEditorAndParent &&
                actual.focused && !actual.showingHint && actual.text == TongyiEngineeringRunner.PAYLOAD &&
                actual.text.toByteArray(Charsets.UTF_8).size == 239) { "DEEPSEEK_QUALIFICATION_EXACT_READBACK_REQUIRED" }
            readbackConfirmed = true
            gate()
            android.retainCurrent("owned-draft")
            retained += "owned-draft"
            gate()
        } catch (original: Exception) {
            failure = original
        } finally {
            cleaned = try { android.cleanupOwned() } catch (original: Exception) {
                if (failure == null) failure = original
                false
            }
        }
        var rejected = false
        var rejectionFailure: String? = null
        if (failure != null || !cleaned) try { rejected = android.retainRejectedCached() }
        catch (retention: Exception) { rejectionFailure = retention.message ?: retention.javaClass.simpleName }
        return Result(if (failure == null && readbackConfirmed && cleaned) "DRAFT_ROLE_QUALIFICATION_CLOSED" else "DRAFT_ROLE_QUALIFICATION_UNKNOWN",
            readbackConfirmed, retained.toList(), failure?.let { it.message ?: it.javaClass.simpleName }
                ?: if (!cleaned) "OWNED_CLEANUP_UNKNOWN" else null,
            failure?.stackTraceToString()?.take(16_384), cleaned, rejected, rejectionFailure)
    }
}

/** One current public-API source, with no preparation/input/action capability. */
class DeepSeekPublicUiObservation(private val android: Android, private val clock: TongyiEngineeringRunner.MonotonicClock) {
    interface Android {
        fun launchOwned(deadlineNanos: Long)
        fun readCurrent(): ByteArray
        fun cleanupOwned(): Boolean
    }
    data class Result(val state: String, val sourceRetained: Boolean, val original: File?,
        val failure: String?, val originalStack: String?, val ownedCleanup: Boolean,
        val preparationDeadlineNanos: Long? = null)

    fun runOnce(runId: String, root: File): Result {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        check(root.mkdir()) { "OPPORTUNITY_ALREADY_CONSUMED_NO_RETRY" }
        val deadline = clock.nowNanos() + TongyiEngineeringRunner.PREP_NANOS
        fun gate() { check(clock.nowNanos() < deadline) { "PREPARATION_180_EXPIRED" } }
        var original: File? = null
        var failure: Exception? = null
        var cleaned = false
        try {
            gate()
            android.launchOwned(deadline)
            gate()
            val bytes = android.readCurrent().copyOf() // Exactly one observation acquisition; no action capability.
            gate()
            check(bytes.isNotEmpty()) { "DEEPSEEK_PUBLIC_UI_SOURCE_EMPTY" }
            val file = File(root, "current-public-ui-nodes.json")
            check(file.createNewFile()) { "PUBLIC_UI_SOURCE_ALREADY_RETAINED" }
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
            original = file
            gate()
        } catch (caught: Exception) {
            failure = caught
        } finally {
            cleaned = try { android.cleanupOwned() } catch (caught: Exception) {
                if (failure == null) failure = caught
                false
            }
        }
        return Result(if (failure == null && original != null && cleaned) "PUBLIC_UI_OBSERVATION_RETAINED" else "PUBLIC_UI_OBSERVATION_UNKNOWN",
            original != null, original, failure?.let { it.message ?: it.javaClass.simpleName }
                ?: if (!cleaned) "OWNED_CLEANUP_UNKNOWN" else null,
            failure?.stackTraceToString()?.take(16_384), cleaned, deadline)
    }
}
