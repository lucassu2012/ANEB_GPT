package com.aneb.probe.ui

internal fun <T> dispatchReleaseNewIntent(
    launch: T,
    scrubExternalLaunch: (T) -> Unit,
    dispatchToFramework: (T) -> Unit,
) {
    scrubExternalLaunch(launch)
    dispatchToFramework(launch)
}

internal fun <T> dispatchDebugNewIntent(
    launch: T,
    dispatchToFramework: (T) -> Unit,
) {
    dispatchToFramework(launch)
}
