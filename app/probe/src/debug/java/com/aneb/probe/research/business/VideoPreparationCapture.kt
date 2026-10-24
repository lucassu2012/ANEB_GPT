package com.aneb.probe.research.business

/** Source-only UI evidence is not permission to start a projection session. */
object VideoPreparationCapture {
    fun requestIfRequired(mode: String, requestSystemConsent: () -> Unit) {
        when (mode) {
            "SOURCE_PREPARATION" -> Unit
            "REFERENCE_QUALIFICATION" -> requestSystemConsent()
            else -> throw IllegalArgumentException("VIDEO_MODE_UNSUPPORTED")
        }
    }
}
