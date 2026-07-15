package com.aneb.probe.net

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * SNI 双通道连接可达性探测（阶段3）。
 *
 * 研究背景（见 MEMORY『研究背景』）：电信 NR-SA 对 sslip.io 主机名注入 SNI-keyed
 * TLS RST，而 bare-IP 路径 TLS 可完成。本探针在正式测量前对同一 E-01 分别用
 * {带 SNI 主机名, bare-IP} 各发 1 次 GET /api/v1/serverinfo，把 SNI-RST 变成
 * 可量化维度（带 SNI vs bare-IP 的连接成功率），落 TestRun 元数据。
 *
 * 结果分类（只看 TLS 握手/连接层面，不看 HTTP 语义）：
 *  - [OK]：拿到任何 HTTP 响应（TLS 握手已完成，连接可达）；
 *  - [RST]：SSLHandshakeException 或连接被重置（"Connection reset"）——SNI-RST 特征；
 *  - [TIMEOUT]：SocketTimeoutException / 连接超时；
 *  - [ERROR] + 摘要：其它 IOException（DNS 失败、证书不受信等）。
 *
 * 边界：additive、best-effort——探测本身绝不抛进测量流程（调用方 runCatching 兜底），
 * 用短超时（connect/read 各 6s）避免拖慢 run 前置。绑定网络（bound）非 null 时复用
 * 同一 socketFactory/DNS，保证探测与承载路径同网（R-01）。
 */
class ReachabilityProbe(bound: BoundNetwork? = null) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .proxy(java.net.Proxy.NO_PROXY) // D-16：探测同样直连，禁走系统代理
        .apply {
            if (bound != null) {
                socketFactory(bound.socketFactory)
                dns(bound.dns)
            }
        }
        .build()

    /** 单通道探测结果：status ∈ {ok, rst, timeout, error:*}，elapsedMs 失败时 null（R-10 禁 0）。 */
    data class Reach(val status: String, val elapsedMs: Long?)

    /** 一次 run 前的双通道探测结果（sni=带主机名通道，ip=bare-IP 通道）。 */
    data class DualReach(val sni: Reach, val ip: Reach)

    /**
     * 对给定的两个基址各发一次 /api/v1/serverinfo 并分类。
     * sniBase 期望带 SNI 主机名（sslip.io），ipBase 期望 bare-IP。两次串行，
     * 顺序 sni→ip（先探被劫持的路径，与真机首测口径一致）。
     */
    suspend fun probeDual(sniBase: String, ipBase: String): DualReach {
        val sni = probeOne(sniBase)
        val ip = probeOne(ipBase)
        return DualReach(sni, ip)
    }

    /** 对单个基址发 GET /api/v1/serverinfo，分类 TLS/连接层结果。 */
    suspend fun probeOne(base: String): Reach = withContext(Dispatchers.IO) {
        val url = base.trimEnd('/') + "/api/v1/serverinfo"
        val startNs = SystemClock.elapsedRealtimeNanos()
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                resp.body?.close()
                val ms = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L
                // 拿到任意 HTTP 响应＝TLS 握手完成、路径可达（不看 2xx/4xx 语义）。
                Reach("ok", ms)
            }
        } catch (e: CancellationException) {
            throw e // fail-closed：不吞取消
        } catch (e: SSLHandshakeException) {
            Reach("rst", null) // SNI-keyed TLS RST 常表现为握手异常
        } catch (e: SocketTimeoutException) {
            Reach("timeout", null)
        } catch (e: IOException) {
            val msg = e.message ?: ""
            when {
                msg.contains("reset", ignoreCase = true) -> Reach("rst", null)
                msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("timed out", ignoreCase = true) -> Reach("timeout", null)
                else -> Reach("error:${e.javaClass.simpleName}", null)
            }
        }
    }

    companion object {
        /** E-01 SNI 主机名（LE 公共证书对应；sslip.io 把点分 IP 编进域名）。 */
        const val E01_SNI_HOST = "120-79-148-0.sslip.io"

        /** E-01 bare-IP（自签 IP-SAN 证书对应）。 */
        const val E01_IP = "120.79.148.0"

        /**
         * 由配置基址推导 (带 SNI 主机名, bare-IP) 两个探测基址。仅当基址 host 是
         * E-01 的 sslip 主机名或 bare-IP 时返回一对（同 scheme/port）；否则返回 null
         * （本地/非 E-01 目标不做双通道探测，字段保持未探测 null）。
         */
        fun deriveE01Pair(base: String): Pair<String, String>? {
            val trimmed = base.trim().trimEnd('/')
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
            if (scheme.isEmpty()) return null
            val rest = trimmed.substringAfter("://")
            val hostPort = rest.substringBefore('/')
            val host = hostPort.substringBeforeLast(':', missingDelimiterValue = hostPort)
            val port = hostPort.substringAfterLast(':', missingDelimiterValue = "")
            val portSuffix = if (port.isNotEmpty() && port != host) ":$port" else ""
            return when (host) {
                E01_SNI_HOST, E01_IP ->
                    "$scheme://$E01_SNI_HOST$portSuffix" to "$scheme://$E01_IP$portSuffix"
                else -> null
            }
        }

        /**
         * 测量端点选路（D-25，SNI-RST 自动旁路）：当配置端点是 E-01 sslip 主机名、且 run 前
         * 双通道探测显示 **SNI 通道被 RST 而 bare-IP 通道可达** 时，返回 bare-IP 等价基址——
         * 同一节点、同一物理路径，仅换 SNI/证书以绕过 DPI 的 SNI-keyed RST（R-33/D-22），
         * claim_scope（application_end_to_end_to_probe_node）**不变**、无测量偏差。
         *
         * 其余情形一律返回原基址不变：reach 未探测（null）、非 E-01 目标（deriveE01Pair 返 null）、
         * SNI 本就可达（不必旁路，保留观测 sslip 真实路径）、已在 bare-IP（无需再切）。
         * 纯函数，供 [TestEngine] run 前选路与单测锚定。
         */
        fun preferredMeasureBase(configuredBase: String, reach: DualReach?): String {
            if (reach == null) return configuredBase
            val ipBase = deriveE01Pair(configuredBase)?.second ?: return configuredBase
            val trimmed = configuredBase.trim().trimEnd('/')
            return if (reach.sni.status == "rst" && reach.ip.status == "ok" && trimmed != ipBase) {
                ipBase
            } else {
                configuredBase
            }
        }
    }
}
