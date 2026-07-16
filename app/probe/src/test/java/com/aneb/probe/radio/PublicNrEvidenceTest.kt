package com.aneb.probe.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicNrEvidenceTest {
    @Test
    fun `一致的 NR 协商态和注册小区才记 connected`() {
        assertEquals("connected", PublicNrEvidence.derive("NR", "none", "NR"))
    }

    @Test
    fun `一致的 LTE 证据且无 5G 图标才记 none`() {
        assertEquals("none", PublicNrEvidence.derive("LTE", "none", "LTE"))
    }

    @Test
    fun `只有 5G 图标不构成 NR 承载证据`() {
        assertEquals("nsa_unknown", PublicNrEvidence.derive("LTE", "nr_nsa", "LTE"))
        assertEquals("nsa_unknown", PublicNrEvidence.derive("LTE", "nr_advanced", "LTE"))
    }

    @Test
    fun `协商态和注册小区冲突时保守降级`() {
        assertEquals("nsa_unknown", PublicNrEvidence.derive("LTE", "nr_nsa", "NR"))
        assertEquals("nsa_unknown", PublicNrEvidence.derive("NR", "none", "LTE"))
    }

    @Test
    fun `任一关键证据缺失时保守降级`() {
        assertEquals("nsa_unknown", PublicNrEvidence.derive("unknown", null, null))
        assertEquals("nsa_unknown", PublicNrEvidence.derive("NR", "unavailable_below_api31", null))
        assertEquals("nsa_unknown", PublicNrEvidence.derive("LTE", "unavailable_below_api31", "LTE"))
        assertEquals("nsa_unknown", PublicNrEvidence.derive("LTE", "none_reported_yet", "LTE"))
    }
}
