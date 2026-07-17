package com.aneb.probe.ui

/**
 * Consumes the ordinary autorun flag only for a brand-new Activity instance.
 *
 * The callbacks keep this gate free of Android dependencies so recreation and repeated-consumption
 * behavior can be locked down by local JVM tests.
 */
internal fun consumeAutorunOnce(
    isFirstCreation: Boolean,
    enabled: Boolean,
    readAutorun: () -> Boolean,
    removeAutorun: () -> Unit,
): Boolean {
    if (!isFirstCreation) return false
    val requested = enabled && readAutorun()
    removeAutorun()
    return requested
}
