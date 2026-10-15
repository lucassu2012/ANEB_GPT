package com.aneb.probe.research.engineering

/** Debug-only fresh per-call owner scope; raw identity is decided by the unchanged real lifecycle. */
class QualificationOwnerProbeScope(
    private val transport: Transport,
    private val clock: TongyiEngineeringRunner.MonotonicClock,
    private val presence: Presence = Presence.WHOLE_LIST,
) {
    enum class Presence { WHOLE_LIST, OWNED_PID_POSITIVE }

    /** External OS/time boundary only. No Native glue or identity cache across calls. */
    interface Transport : OwnedProcessLifecycle.Shell {
        /** Null means capability absent only; failed supported groups must return failure or throw. */
        fun executeOwnerGroup(pid: String, deadlineNanos: Long): List<OwnedProcessLifecycle.ShellResult>? = null
        /** A distinct positive-known-PID query; never relabeled as whole-list evidence. */
        fun executeOwnedPidPositiveGroup(pid: String, deadlineNanos: Long): List<OwnedProcessLifecycle.ShellResult>? = null
    }

    /** Same-call scalar diagnostic only; no Frame, stage, or draft ownership authority. */
    data class TargetCurrentRejection(
        val expectedPackage: String,
        val expectedPid: String,
        val currentPackage: String?,
        val pidofAttempted: Boolean,
        val rawPidof: String?,
    )

    /** Debug-only guard over the already-returned package and original short-circuited pidof query. */
    fun requireTargetCurrent(
        expectedPackage: String,
        expectedPid: String,
        currentPackage: String?,
        readPidof: () -> String,
        retainRejection: (TargetCurrentRejection) -> Unit,
    ) {
        var rawPidof: String? = null
        val matches = currentPackage == expectedPackage &&
            readPidof().also { rawPidof = it }.trim() == expectedPid
        if (!matches) {
            val primary = IllegalStateException("QUALIFICATION_TARGET_NOT_CURRENT")
            try {
                retainRejection(TargetCurrentRejection(expectedPackage, expectedPid, currentPackage,
                    currentPackage == expectedPackage, rawPidof))
            } catch (secondary: Throwable) {
                if (secondary !== primary) primary.addSuppressed(secondary)
            }
            throw primary
        }
    }

    fun requireAlive(owner: OwnedProcessLifecycle.Owner, deadlineNanos: Long) {
        check(owner.pid.matches(Regex("[1-9][0-9]*")) && clock.nowNanos() < deadlineNanos) {
            "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED"
        }
        val positive = presence == Presence.OWNED_PID_POSITIVE
        val group = if (positive) try {
            transport.executeOwnedPidPositiveGroup(owner.pid, deadlineNanos)?.toList()
        } catch (failure: Exception) {
            throw IllegalStateException("OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED", failure)
        } else transport.executeOwnerGroup(owner.pid, deadlineNanos)?.toList()
        if (group == null) {
            check(!positive) { "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED" }
            OwnedProcessLifecycle(transport, clock).requireAlive(owner, deadlineNanos)
            return // Only absent capability retains serial; supported failures never fall back.
        }
        val presenceCommand = if (positive) "ps -p ${owner.pid} -o PID" else "ps -A -o PID"
        val commands = listOf(presenceCommand, "cat /proc/${owner.pid}/stat", "cat /proc/${owner.pid}/status",
            "cat /proc/${owner.pid}/cmdline", "cat /proc/${owner.pid}/stat")
        check(clock.nowNanos() < deadlineNanos && group.size == commands.size &&
            group.all { it.completed && it.exitCode == 0 && it.stderr.isEmpty() }) {
            "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED"
        }
        var next = 0 // Local to this one fresh group; never retained by the scope.
        val acquired = object : OwnedProcessLifecycle.Shell {
            override fun execute(command: String, until: Long): OwnedProcessLifecycle.ShellResult {
                check(until == deadlineNanos && clock.nowNanos() < until && next < commands.size &&
                    command == commands[next]) { "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED" }
                return group[next++] // Unmodified raw fields, including all cmdline NULs.
            }
            override fun sleepMillis(milliseconds: Long) = error("OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED")
        }
        val lifecycle = OwnedProcessLifecycle(acquired, clock)
        if (positive) lifecycle.requireOwnedPidAlive(owner, deadlineNanos) else lifecycle.requireAlive(owner, deadlineNanos)
        check(next == commands.size && clock.nowNanos() <= deadlineNanos) {
            "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED"
        }
    }
}
