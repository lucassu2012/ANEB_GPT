package com.aneb.probe.ui

import com.aneb.probe.net.ReachabilityProbe
import java.net.URI

/** 只登记已经真实部署并有证据的 Probe 节点；禁止把设计稿示例节点带进产品。 */
internal object ProbeNodeCatalog {
    data class Node(
        val id: String,
        val displayName: String,
        val provider: String,
        val defaultUrl: String,
    )

    const val MIW2_IP = "120.77.174.207"

    val e01 = Node(
        id = "E-01",
        displayName = "Node-E01 · 阿里云 (miw2)",
        provider = "阿里云 · 国内云 VM（公网 IP 证书）",
        defaultUrl = ProbeSettings.DEFAULT_SERVER_URL,
    )

    fun nodeForUrl(url: String): Node? {
        val host = runCatching { URI(url.trim()).host }.getOrNull()
        return if (host == MIW2_IP || ReachabilityProbe.deriveE01Pair(url) != null) e01 else null
    }

    fun labelForUrl(url: String): String = nodeForUrl(url)?.displayName ?: customLabel(url)

    fun customLabel(url: String): String {
        val host = runCatching { URI(url.trim()).host }.getOrNull()
        return host?.takeIf { it.isNotBlank() }?.let { "自定义节点 · $it" } ?: "自定义节点"
    }
}
