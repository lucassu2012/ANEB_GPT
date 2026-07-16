package com.aneb.probe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPermissionStateTest {
    @Test
    fun `电话与精确位置同时授予才有完整无线证据`() {
        assertTrue(RadioPermissionState(true, true, true).hasFullRadioEvidence)
        assertFalse(RadioPermissionState(true, true, false).hasFullRadioEvidence)
        assertFalse(RadioPermissionState(false, true, true).hasFullRadioEvidence)
    }

    @Test
    fun `只授予大致位置时给出明确原因`() {
        val state = RadioPermissionState(
            phoneStateGranted = true,
            coarseLocationGranted = true,
            fineLocationGranted = false,
        )
        assertEquals("当前仅授予了大致位置", state.deniedSummary)
    }

    @Test
    fun `电话和精确位置都缺失时不隐藏任一缺口`() {
        val state = RadioPermissionState(false, false, false)
        assertEquals("电话与精确位置权限均未完整授予", state.deniedSummary)
    }
}
