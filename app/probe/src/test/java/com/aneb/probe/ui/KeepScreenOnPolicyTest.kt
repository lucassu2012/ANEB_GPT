package com.aneb.probe.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeepScreenOnPolicy] 纯逻辑单测（T25，D-427）。
 */
class KeepScreenOnPolicyTest {

    @Test
    fun autorun_holdsScreenOn() {
        val p = KeepScreenOnPolicy()
        p.onCreate(autorun = true)
        assertTrue(p.held)
    }

    @Test
    fun manual_neverHoldsScreenOn() {
        val p = KeepScreenOnPolicy()
        p.onCreate(autorun = false)
        assertFalse(p.held)
    }

    @Test
    fun onDestroy_releasesAfterAutorunHold() {
        val p = KeepScreenOnPolicy()
        p.onCreate(autorun = true)
        assertTrue(p.held)
        p.onDestroy()
        assertFalse(p.held)
    }

    @Test
    fun onDestroy_withoutPriorHold_isSafeNoop() {
        val p = KeepScreenOnPolicy()
        p.onCreate(autorun = false)
        p.onDestroy()
        assertFalse(p.held)
    }

    @Test
    fun secondOnCreate_reevaluatesFromCurrentAutorunFlag() {
        val p = KeepScreenOnPolicy()
        p.onCreate(autorun = true)
        assertTrue(p.held)
        p.onDestroy()
        p.onCreate(autorun = false)
        assertFalse(p.held)
        p.onCreate(autorun = true)
        assertTrue(p.held)
    }
}
