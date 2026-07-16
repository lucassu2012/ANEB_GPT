package com.aneb.probe.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 网络守卫（R-01/R-03/R-14，设计文档 §4.6/§4.7）。
 *
 * 三大职责：
 * 1. [guardCheck]：测前硬拒测项——VPN（任一 Network 带 TRANSPORT_VPN）、HTTP 代理
 *    （LinkProperties.httpProxy / ConnectivityManager.defaultProxy 非空）；Private DNS 只记
 *    元数据不拒测（claim scope 标注为「至 DoT 服务器」，R-03）。
 * 2. [acquireNetwork]：无论 WiFi 还是蜂窝一律 requestNetwork(指定 transport) 显式绑定
 *    （R-14 对称性），挂起等待 capabilities 同时含 transport + VALIDATED + NOT_SUSPENDED
 *    才放行；15s 超时 fail-closed 抛 [GuardException]——禁止超时放行（R-01）。
 *    返回的 [BoundNetwork] 同时提供 socketFactory 与 Dns（network::getAllByName 包装，
 *    否则域名解析仍走默认网络 DNS，解析与承载路径分裂，R-01 缺口 1）。
 * 3. [PathMonitor]：registerDefaultNetworkCallback + 绑定网络 callback 贯穿测试全程，
 *    任一路径事件（网络丢失/默认网切换/VALIDATED 丢失/回调注册失败）触发首事件获胜
 *    状态机 → invalidate(reason) 一次性回调，供 TestEngine 中止场景（fail-closed）。
 *    阶段二 C 组切换实验以路径迁移为测量对象，经 exemptPathChanges 显式豁免。
 *
 * 全部打点用 SystemClock.elapsedRealtimeNanos（单调时间轴）。
 */
object NetGuard {

    const val DEFAULT_ACQUIRE_TIMEOUT_MS = 15_000L

    // ------------------------------------------------------------------
    // 1. 测前守卫检查
    // ------------------------------------------------------------------

    fun guardCheck(context: Context): GuardResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return GuardResult(false, listOf("connectivity_manager_unavailable"), emptyMap())

        val reasons = mutableListOf<String>()
        val metadata = linkedMapOf<String, String>()

        // ---- R-03: VPN —— 任一 Network 带 TRANSPORT_VPN 即拒测 ----
        // （requestNetwork(CELLULAR) 会绕过 VPN 而默认网不绕过，造成场景间隐性不对称，必须拒测）
        @Suppress("DEPRECATION") // allNetworks 足够且 minSdk 29 可用；替代 API 需要常驻回调
        val networks: Array<Network> = try {
            cm.allNetworks
        } catch (t: Throwable) {
            emptyArray()
        }
        var vpnCount = 0
        for (n in networks) {
            val caps = try {
                cm.getNetworkCapabilities(n)
            } catch (t: Throwable) {
                null
            }
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) vpnCount++
        }
        if (vpnCount > 0) reasons += "vpn_active(networks=$vpnCount)"

        val active = try {
            cm.activeNetwork
        } catch (t: Throwable) {
            null
        }
        val activeCaps = active?.let {
            try {
                cm.getNetworkCapabilities(it)
            } catch (t: Throwable) {
                null
            }
        }
        // 双保险：allNetworks 枚举失败时至少查默认网
        if (vpnCount == 0 && activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            reasons += "vpn_active(default_network)"
        }

        // ---- R-03: HTTP 代理 —— 链路级与全局默认代理任一非空即拒测 ----
        val lp = active?.let {
            try {
                cm.getLinkProperties(it)
            } catch (t: Throwable) {
                null
            }
        }
        lp?.httpProxy?.let { p ->
            reasons += "http_proxy_link(${p.host}:${p.port})"
        }
        val defaultProxy = try {
            cm.defaultProxy
        } catch (t: Throwable) {
            null
        }
        defaultProxy?.let { p ->
            reasons += "http_proxy_default(${p.host}:${p.port})"
        }

        // ---- R-03: Private DNS 状态只记元数据（DoT 不拒测，但 dns 计时 claim scope 受限） ----
        if (lp != null) {
            metadata["private_dns_active"] = lp.isPrivateDnsActive.toString()
            lp.privateDnsServerName?.let { metadata["private_dns_server"] = it }
        } else {
            metadata["private_dns_active"] = "unknown(no_active_link_properties)"
        }
        metadata["active_transports"] = activeCaps?.let { transportNames(it) } ?: "none"
        metadata["active_validated"] =
            (activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true).toString()
        metadata["checked_at_nanos"] = SystemClock.elapsedRealtimeNanos().toString()

        return GuardResult(ok = reasons.isEmpty(), reasons = reasons, metadata = metadata)
    }

    // ------------------------------------------------------------------
    // 2. 网络绑定获取（fail-closed）
    // ------------------------------------------------------------------

    /**
     * @param transport NetworkCapabilities.TRANSPORT_CELLULAR / TRANSPORT_WIFI
     * @throws GuardException 超时或不可用——环境不就绪，禁超时放行（R-01）
     */
    suspend fun acquireNetwork(
        context: Context,
        transport: Int,
        timeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
    ): BoundNetwork {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: throw GuardException("connectivity_manager_unavailable")

        // 注意：NET_CAPABILITY_VALIDATED 不能进 NetworkRequest（系统禁止），
        // 必须在 onCapabilitiesChanged 里等——这正是「拿到 onAvailable ≠ 就绪」的原因。
        val request = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val resumed = AtomicBoolean(false)
        var callback: ConnectivityManager.NetworkCallback? = null
        var success = false
        try {
            val bound = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<BoundNetwork> { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(
                            network: Network,
                            caps: NetworkCapabilities,
                        ) {
                            // 三条件同时满足才放行（R-01：VALIDATED 前的样本 TTFT/RTT 系统性偏高）
                            if (!caps.hasTransport(transport)) return
                            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) return
                            if (!resumed.compareAndSet(false, true)) return
                            val lp = try {
                                cm.getLinkProperties(network)
                            } catch (t: Throwable) {
                                null
                            }
                            val snapshot = NetworkSnapshot(
                                transport = transportName(transport),
                                capabilities = capsSummary(caps),
                                interfaceName = lp?.interfaceName,
                                acquiredAtNanos = SystemClock.elapsedRealtimeNanos(),
                            )
                            if (cont.isActive) {
                                cont.resume(
                                    BoundNetwork(
                                        network = network,
                                        transport = transport,
                                        socketFactory = network.socketFactory,
                                        dns = NetworkDns(network),
                                        snapshot = snapshot,
                                        cm = cm,
                                        callback = this,
                                    ),
                                )
                            }
                        }

                        override fun onUnavailable() {
                            if (resumed.compareAndSet(false, true) && cont.isActive) {
                                cont.resumeWithException(
                                    GuardException("network_unavailable(transport=${transportName(transport)})"),
                                )
                            }
                        }
                    }
                    callback = cb
                    try {
                        // 需要 CHANGE_NETWORK_STATE（normal 权限，已入 manifest）
                        cm.requestNetwork(request, cb)
                    } catch (t: Throwable) {
                        if (resumed.compareAndSet(false, true) && cont.isActive) {
                            cont.resumeWithException(GuardException("requestNetwork_failed: $t"))
                        }
                    }
                }
            } ?: throw GuardException(
                "network_not_ready_within_${timeoutMs}ms(transport=${transportName(transport)}) " +
                    "fail-closed：环境不就绪，禁止超时放行（R-01/§4.7）",
            )
            success = true
            return bound
        } finally {
            // 失败路径必须撤销 request；成功路径保持注册（requestNetwork 维持网络存活，
            // 例如 WiFi 在场时的蜂窝），由 BoundNetwork.release() 显式释放。
            if (!success) {
                callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具函数
    // ------------------------------------------------------------------

    fun transportName(transport: Int): String = when (transport) {
        NetworkCapabilities.TRANSPORT_CELLULAR -> "cellular"
        NetworkCapabilities.TRANSPORT_WIFI -> "wifi"
        NetworkCapabilities.TRANSPORT_ETHERNET -> "ethernet"
        NetworkCapabilities.TRANSPORT_VPN -> "vpn"
        NetworkCapabilities.TRANSPORT_BLUETOOTH -> "bluetooth"
        else -> "transport($transport)"
    }

    private fun transportNames(caps: NetworkCapabilities): String {
        val known = intArrayOf(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
        )
        val names = known.filter { caps.hasTransport(it) }.map { transportName(it) }
        return if (names.isEmpty()) "unknown" else names.joinToString("+")
    }

    private fun capsSummary(caps: NetworkCapabilities): String = buildString {
        append("transports=")
        append(transportNames(caps))
        append(" validated=")
        append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        append(" not_suspended=")
        append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
        append(" not_metered=")
        append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        append(" down_kbps=")
        append(caps.linkDownstreamBandwidthKbps)
        append(" up_kbps=")
        append(caps.linkUpstreamBandwidthKbps)
    }
}

/** 守卫失败（fail-closed）——环境不就绪或硬拒测项命中，上层必须中止而非降级继续。 */
class GuardException(message: String) : Exception(message)

/**
 * @param ok       false = 命中硬拒测项，拒绝开测
 * @param reasons  拒测原因码列表（vpn_active / http_proxy_*）
 * @param metadata 元数据（Private DNS 状态、默认网 transport 等），随 TestRun 存档
 */
data class GuardResult(
    val ok: Boolean,
    val reasons: List<String>,
    val metadata: Map<String, String>,
)

/** 每场景网络快照（R-14：ScenarioResult 级，而非 TestRun 级一次性元数据） */
data class NetworkSnapshot(
    val transport: String,
    val capabilities: String,
    val interfaceName: String?,
    val acquiredAtNanos: Long,
)

/**
 * 绑定网络句柄：OkHttpClient 必须同时使用 [socketFactory] 与 [dns]（R-01）。
 * 持有底层 requestNetwork callback 以维持网络存活；测试结束调 [release]。
 */
class BoundNetwork internal constructor(
    val network: Network,
    val transport: Int,
    val socketFactory: SocketFactory,
    val dns: Dns,
    val snapshot: NetworkSnapshot,
    private val cm: ConnectivityManager,
    private val callback: ConnectivityManager.NetworkCallback,
) {
    /** 释放 requestNetwork 请求（此后系统可拆除该网络，绑定即作废） */
    fun release() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}

/** DNS 解析强制走绑定网络（network::getAllByName 包装），防解析/承载路径分裂（R-01）。 */
private class NetworkDns(private val network: Network) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = try {
        network.getAllByName(hostname).toList()
    } catch (e: UnknownHostException) {
        throw e
    } catch (t: Throwable) {
        // OkHttp Dns 合同只允许 UnknownHostException
        throw UnknownHostException("bound-network dns lookup failed for $hostname: $t")
    }
}

/**
 * 测中路径监控（R-01/R-14）：默认网络 + 绑定网络双 callback，首事件获胜状态机。
 *
 * - 默认网络切换/丢失、绑定网络丢失、VALIDATED 丢失、SUSPENDED → PATH_CHANGE 事件；
 *   非豁免模式下同时触发一次性 [onInvalidate]（TestEngine 据此中止场景、取消 in-flight 请求）。
 * - 回调注册失败本身即 invalid 触发条件（R-01），且不受豁免影响——豁免只豁免路径迁移类事件。
 * - [exemptPathChanges]=true 供阶段二 C 组切换实验使用：路径事件仍全量记时间轴，仅不触发中止。
 */
class PathMonitor(
    private val context: Context,
    private val bound: BoundNetwork,
    private val exemptPathChanges: Boolean = false,
    private val onEvent: ((EnvEvent) -> Unit)? = null,
    private val onInvalidate: (String) -> Unit,
) {
    private val tripped = AtomicBoolean(false)
    private val defaultBaseline = AtomicReference<Network?>(null)
    private var defaultCb: ConnectivityManager.NetworkCallback? = null
    private var boundCb: ConnectivityManager.NetworkCallback? = null

    val invalidated: Boolean get() = tripped.get()

    fun start() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            invalidate("connectivity_manager_unavailable")
            return
        }

        val dcb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // registerDefaultNetworkCallback 注册时立即回放当前默认网 → 作为基线
                if (defaultBaseline.compareAndSet(null, network)) return
                if (defaultBaseline.get() != network) {
                    defaultBaseline.set(network)
                    pathEvent("default_network_changed", "-> $network")
                }
            }

            override fun onLost(network: Network) {
                if (network == defaultBaseline.get()) {
                    pathEvent("default_network_lost", "$network")
                }
            }
        }
        val bcb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == bound.network) pathEvent("bound_network_lost", "$network")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network != bound.network) return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    pathEvent("bound_validated_lost", "$network")
                } else if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
                    pathEvent("bound_suspended", "$network")
                }
            }
        }

        try {
            cm.registerDefaultNetworkCallback(dcb)
            defaultCb = dcb
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(bound.transport).build(),
                bcb,
            )
            boundCb = bcb
        } catch (t: Throwable) {
            // R-01：注册失败无法证明测中路径稳定 → fail-closed（豁免不适用）
            onEvent?.invoke(
                EnvEvent(
                    SystemClock.elapsedRealtimeNanos(),
                    EnvEventType.PATH_CHANGE,
                    "monitor_registration_failed: ${t.javaClass.simpleName}: ${t.message}",
                ),
            )
            invalidate("path_monitor_registration_failed")
        }
    }

    fun stop() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        defaultCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        defaultCb = null
        boundCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        boundCb = null
    }

    /** 一次性失效（首事件获胜）；TestEngine 也可显式调用（如服务端对账不符）。 */
    fun invalidate(reason: String) {
        if (tripped.compareAndSet(false, true)) onInvalidate(reason)
    }

    private fun pathEvent(reason: String, detail: String) {
        onEvent?.invoke(
            EnvEvent(
                SystemClock.elapsedRealtimeNanos(),
                EnvEventType.PATH_CHANGE,
                "$reason $detail exempt=$exemptPathChanges",
            ),
        )
        if (!exemptPathChanges) invalidate(reason)
    }
}
