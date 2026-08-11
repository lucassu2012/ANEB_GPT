package com.aneb.probe.ui

internal data class PublicReleaseLaunch(
    val settings: ProbeSettings,
    val startRequested: Boolean,
)

internal fun resolvePublicReleaseLaunch(
    saved: ProbeSettings,
    hasFullRadioEvidence: Boolean,
    scrubExternalLaunch: () -> Unit,
): PublicReleaseLaunch {
    scrubExternalLaunch()
    return PublicReleaseLaunch(
        settings = saved.copy(driveTest = saved.driveTest && hasFullRadioEvidence),
        startRequested = false,
    )
}
