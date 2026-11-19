package com.aneb.probe.research.business

import com.aneb.probe.research.engineering.OwnedProcessLifecycle
import com.aneb.probe.research.engineering.TongyiEngineeringRunner

/**
 * Bounded post-force-stop exit confirmation for Douyin (`com.ss.android.ugc.aweme`).
 * Issues at most one `am force-stop`, never uses point `ps -p` as absence proof,
 * immediately rejects PID replacement (`startTicks`/identity drift), and resolves
 * the transient `ps -A -o PID` -> `/proc/<pid>/stat` ENOENT teardown race via a
 * fresh complete full-list witness within the caller's existing cleanup deadline.
 */
internal object VideoOwnedStopConfirmation {
    const val PACKAGE = "com.ss.android.ugc.aweme"
    private val PID = Regex("[1-9][0-9]*")

    data class Outcome(val stopped: Boolean, val reason: String)

    fun requirePackageAbsentOnce(
        shell: OwnedProcessLifecycle.Shell,
        nowNs: () -> Long,
        readersHealthy: () -> Boolean,
        packageName: String,
        deadlineNanos: Long,
    ) {
        require(packageName == PACKAGE) { "VIDEO_ONLY_DOUYIN_PACKAGE_ADMITTED" }
        check(packagePresentCount(shell, nowNs, readersHealthy, deadlineNanos) == 0) {
            "VIDEO_PACKAGE_STILL_PRESENT"
        }
    }

    fun stopOwnedPackage(
        shell: OwnedProcessLifecycle.Shell,
        lifecycle: OwnedProcessLifecycle,
        nowNs: () -> Long,
        readersHealthy: () -> Boolean,
        owner: OwnedProcessLifecycle.Owner,
        packageName: String,
        deadlineNanos: Long,
    ): Outcome {
        require(packageName == PACKAGE) { "VIDEO_ONLY_DOUYIN_PACKAGE_ADMITTED" }
        check(PID.matches(owner.pid) && owner.uid >= 0 && owner.commandLine == listOf(PACKAGE)) {
            "VIDEO_STOP_OWNER_NOT_ADMITTED"
        }
        return try {
            when (val current = lifecycle.observe(owner.pid, deadlineNanos)) {
                OwnedProcessLifecycle.Observation.Absent -> {
                    requirePackageAbsentOnce(shell, nowNs, readersHealthy, packageName, deadlineNanos)
                    Outcome(true, "MAIN_AND_PACKAGE_ALREADY_ABSENT_NO_STOP")
                }
                is OwnedProcessLifecycle.Observation.Unknown ->
                    Outcome(false, "OWNER_UNKNOWN_NOT_STOPPED")
                is OwnedProcessLifecycle.Observation.Alive -> {
                    if (current.owner != owner) {
                        Outcome(false, "OWNER_REPLACED_NOT_STOPPED")
                    } else {
                        val stopResult = shell.execute("am force-stop $PACKAGE", deadlineNanos)
                        requireComplete(stopResult, nowNs, readersHealthy, deadlineNanos)
                        check(stopResult.exitCode == 0 && stopResult.stdout.isBlank()) {
                            "VIDEO_FORCE_STOP_RESULT_UNKNOWN"
                        }
                        confirmMainPidExited(shell, lifecycle, nowNs, readersHealthy, owner, deadlineNanos)
                        confirmAllPackageProcessesExited(shell, nowNs, readersHealthy, deadlineNanos)
                        Outcome(true, "EXACT_OWNER_PRECHECK_AND_PACKAGE_ABSENCE_OBSERVED")
                    }
                }
            }
        } catch (error: Exception) {
            Outcome(false, error.javaClass.simpleName + ":" + error.message.orEmpty())
        }
    }

    private fun confirmMainPidExited(
        shell: OwnedProcessLifecycle.Shell,
        lifecycle: OwnedProcessLifecycle,
        nowNs: () -> Long,
        readersHealthy: () -> Boolean,
        owner: OwnedProcessLifecycle.Owner,
        deadlineNanos: Long,
    ) {
        while (true) {
            when (val post = lifecycle.observe(owner.pid, deadlineNanos)) {
                OwnedProcessLifecycle.Observation.Absent -> return
                is OwnedProcessLifecycle.Observation.Alive -> {
                    check(post.owner == owner) { "VIDEO_STOP_OWNER_REPLACED_NOT_STOPPED" }
                    check(nowNs() < deadlineNanos) { "VIDEO_STOP_MAIN_EXIT_UNKNOWN_OR_REPLACED_NO_RETRY" }
                    shell.sleepMillis(50L)
                }
                is OwnedProcessLifecycle.Observation.Unknown -> {
                    check(freshFullPidListExcludes(shell, nowNs, readersHealthy, owner.pid, deadlineNanos)) {
                        "VIDEO_STOP_MAIN_EXIT_UNKNOWN_OR_REPLACED_NO_RETRY"
                    }
                    return
                }
            }
        }
    }

    private fun freshFullPidListExcludes(
        shell: OwnedProcessLifecycle.Shell,
        nowNs: () -> Long,
        readersHealthy: () -> Boolean,
        pid: String,
        deadlineNanos: Long,
    ): Boolean {
        if (nowNs() >= deadlineNanos) return false
        val result = shell.execute("ps -A -o PID", deadlineNanos)
        if (!result.completed || result.exitCode != 0 || result.stderr.isNotEmpty() ||
            nowNs() > deadlineNanos || !readersHealthy()
        ) {
            return false
        }
        val lines = result.stdout.trim().lines().map(String::trim)
        if (lines.size < 2 || lines.first() != "PID" || !lines.drop(1).all(PID::matches)) {
            return false
        }
        return pid !in lines.drop(1)
    }

    private fun confirmAllPackageProcessesExited(
        shell: OwnedProcessLifecycle.Shell,
        nowNs: () -> Long,
        readersHealthy: () -> Boolean,
        deadlineNanos: Long,
    ) {
        while (true) {
            if (packagePresentCount(shell, nowNs, readersHealthy, deadlineNanos) == 0) return
            check(nowNs() < deadlineNanos) { "VIDEO_PACKAGE_STILL_PRESENT" }
            shell.sleepMillis(50L)
        }
    }

    private fun packagePresentCount(
        shell: OwnedProcessLifecycle.Shell,
        nowNs: () -> Long,
        readersHealthy: () -> Boolean,
        deadlineNanos: Long,
    ): Int {
        val result = shell.execute("ps -A -o PID,NAME", deadlineNanos)
        requireComplete(result, nowNs, readersHealthy, deadlineNanos)
        check(result.exitCode == 0) { "VIDEO_PACKAGE_PROCESS_LIST_UNKNOWN" }
        val lines = result.stdout.trim().lines().map(String::trim)
        check(lines.size >= 2 && lines.first().split(Regex("\\s+")) == listOf("PID", "NAME")) {
            "VIDEO_PACKAGE_PROCESS_LIST_UNKNOWN"
        }
        val rows = lines.drop(1).map { line ->
            line.split(Regex("\\s+"), limit = 2).also {
                check(it.size == 2 && PID.matches(it[0]) && it[1].isNotEmpty()) {
                    "VIDEO_PACKAGE_PROCESS_LIST_UNKNOWN"
                }
            }
        }
        check(rows.map { it[0] }.distinct().size == rows.size) { "VIDEO_PACKAGE_PROCESS_LIST_UNKNOWN" }
        return rows.count { it[1] == PACKAGE || it[1].startsWith("$PACKAGE:") }
    }

    private fun requireComplete(
        result: OwnedProcessLifecycle.ShellResult,
        nowNs: () -> Long,
        readersHealthy: () -> Boolean,
        deadlineNanos: Long,
    ) {
        check(result.completed && result.stderr.isEmpty() && nowNs() <= deadlineNanos && readersHealthy()) {
            "VIDEO_SHELL_RESULT_UNKNOWN"
        }
    }

    fun newLifecycle(shell: OwnedProcessLifecycle.Shell, nowNs: () -> Long): OwnedProcessLifecycle =
        OwnedProcessLifecycle(shell, TongyiEngineeringRunner.MonotonicClock { nowNs() })
}
