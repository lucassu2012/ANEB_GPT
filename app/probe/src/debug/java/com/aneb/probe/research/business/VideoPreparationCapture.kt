package com.aneb.probe.research.business

/** Source-only UI evidence is not permission to start a projection session. */
object VideoPreparationCapture {
    fun requireSourceOpen(nowNs: Long, deadlineNs: Long) {
        check(nowNs < deadlineNs) { "VIDEO_SOURCE_PREPARATION_DEADLINE" }
    }

    /** Preserve a completed/late result before refusing any following source work. */
    fun <T> readSource(nowNs: () -> Long, deadlineNs: Long, read: () -> T, retain: (T) -> Unit): T {
        requireSourceOpen(nowNs(), deadlineNs)
        val value = read()
        retain(value)
        requireSourceOpen(nowNs(), deadlineNs)
        return value
    }

    fun requestIfRequired(mode: String, requestSystemConsent: () -> Unit) {
        when (mode) {
            "SOURCE_PREPARATION" -> Unit
            "REFERENCE_QUALIFICATION" -> requestSystemConsent()
            else -> throw IllegalArgumentException("VIDEO_MODE_UNSUPPORTED")
        }
    }
}
