package com.aneb.probe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeNodeCatalogTest {
    @Test
    fun `both verified E01 routes map to one node`() {
        assertEquals(ProbeNodeCatalog.e01, ProbeNodeCatalog.nodeForUrl(ProbeSettings.DEFAULT_SERVER_URL))
        assertEquals(ProbeNodeCatalog.e01, ProbeNodeCatalog.nodeForUrl("https://120.79.148.0:8443"))
    }

    @Test
    fun `custom endpoint is never mislabeled as deployed E01`() {
        val custom = "https://probe.example.cn:9443"

        assertNull(ProbeNodeCatalog.nodeForUrl(custom))
        assertEquals("自定义节点 · probe.example.cn", ProbeNodeCatalog.labelForUrl(custom))
    }
}
