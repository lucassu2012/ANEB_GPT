package com.aneb.probe.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventType
import com.aneb.probe.data.RadioSampleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * 无线层采集（阶段 1 升级，R-02/R-13/R-15，设计文档 §4.5）。
 *
 * - [start]：1Hz Flow<RadioSample>。每秒 requestCellInfoUpdate 主动刷新（禁被动读
 *   modem 节流缓存，R-02）；超时退回 allCellInfo 缓存并标 stale=true；样本同时落
 *   采样打点时刻与 CellInfo 自带时间戳（信息时刻，同 elapsedRealtime 轴），两者差
 *   >2s 亦标 stale。
 * - 制式三元组（R-15）：network_type（dataNetworkType，协商态）/ override_type
 *   （TelephonyDisplayInfo，运营商图标显示策略，API 31+ 监听、以下记 unavailable）/
 *   nr_state（ServiceState 反射 + toString 兜底，失败静默降级 nsa_unknown）三列
 *   显式分开——5G 图标 ≠ 数据承载，禁止合并为单值。
 * - 双卡（R-13）：绑定 SubscriptionManager.getDefaultDataSubscriptionId 对应的
 *   TelephonyManager（createForSubscriptionId）；每 tick 复查 subId，变化即记
 *   SUB_SWITCH EnvEvent、重绑监听并在该秒样本标 sub_switched。
 * - 小区变更（PCI/TAC）与制式三元组变更输出为 [events] 上的 EnvEvent。
 * - 权限缺失不抛异常：样本 networkType 记 permission_denied、字段全 null
 *   （valid_low_confidence 语义，§4.6：证据缺失 ≠ 隐式健康）。
 *
 * - GPS 路测接点（阶段3）：可选 [locationProvider]（通常为 LocationTagger::current）——
 *   每个 1Hz 样本附带最近 fix 的 lat/lon/accuracy；provider 为 null（路测开关关）或
 *   无 fix 时坐标列 null（R-10）。坐标只入本地 Room 与本地导出，绝不进上报体（§9.1）。
 *
 * 全部计时 SystemClock.elapsedRealtimeNanos。
 */
class RadioCollector(
    private val context: Context,
    private val locationProvider: (() -> GeoFix?)? = null,
) {

    /** requestCellInfoUpdate / TelephonyCallback 回调极薄，直接在 binder 线程跑 */
    private val directExecutor = Executor { it.run() }

    private val _events = MutableSharedFlow<EnvEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** SUB_SWITCH / CELL_CHANGE / RAT_CHANGE 事件（统一 EnvEvent 单调时间轴） */
    val events: SharedFlow<EnvEvent> = _events

    // ------------------------------------------------------------------
    // 1Hz 采样流
    // ------------------------------------------------------------------

    fun start(scope: CoroutineScope): Flow<RadioSample> =
        samplerFlow().shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    private fun samplerFlow(): Flow<RadioSample> = flow {
        val baseTm = context.getSystemService(TelephonyManager::class.java)

        var subId = SubscriptionManager.getDefaultDataSubscriptionId()
        var tm = baseTm?.let { tmForSub(it, subId) }
        val display = DisplayState()
        var displayCb: TelephonyCallback? = null

        var lastPci: Int? = null
        var lastTac: Int? = null
        var lastTriple: Triple<String, String?, String>? = null

        // 1Hz 绝对时刻表（防累加漂移，同服务端 pacing 原则 §6）
        val startNs = SystemClock.elapsedRealtimeNanos()
        var tick = 0L
        try {
            while (true) {
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val hasPerms = granted(Manifest.permission.READ_PHONE_STATE) &&
                    granted(Manifest.permission.ACCESS_FINE_LOCATION)

                val sample: RadioSample
                if (tm == null || !hasPerms) {
                    // 证据缺失显式落 permission_denied，不伪造健康值（R-10 精神）
                    sample = degradedSample(
                        nowNs,
                        if (tm == null) "telephony_unavailable" else "permission_denied",
                    )
                } else {
                    // R-15 override 监听：首次/权限迟到/切卡后（重）注册
                    if (displayCb == null) displayCb = registerDisplayListener(tm, display)

                    // ---- R-13 双卡：defaultDataSubId 变化 → 事件 + 重绑 ----
                    var subSwitched = false
                    val cur = SubscriptionManager.getDefaultDataSubscriptionId()
                    if (cur != subId) {
                        subSwitched = true
                        _events.tryEmit(
                            EnvEvent(nowNs, EnvEventType.SUB_SWITCH, "defaultDataSubId $subId -> $cur"),
                        )
                        unregisterDisplayListener(tm, displayCb)
                        subId = cur
                        // else 分支蕴含 tm != null，进而 baseTm != null
                        tm = tmForSub(baseTm!!, subId)
                        display.overrideType = null
                        displayCb = registerDisplayListener(tm, display)
                    }

                    // ---- R-02 主动刷新，超时退缓存标 stale ----
                    val (cellInfos, requestStale) = requestCellInfo(tm, CELL_INFO_TIMEOUT_MS)
                    sample = buildSample(tm, cellInfos, requestStale, subId, subSwitched, display.overrideType, nowNs)

                    // ---- 事件：小区变更（PCI/TAC）与制式三元组变更 ----
                    if (sample.pci != null && lastPci != null &&
                        (sample.pci != lastPci || sample.tac != lastTac)
                    ) {
                        _events.tryEmit(
                            EnvEvent(
                                sample.tsNanos,
                                EnvEventType.CELL_CHANGE,
                                "pci $lastPci->${sample.pci} tac $lastTac->${sample.tac} " +
                                    "stale=${sample.stale} cellTs=${sample.cellTsNanos}", // stale 样本仅可做疑似相关归因（R-02 归因窗口）
                            ),
                        )
                    }
                    if (sample.pci != null) {
                        lastPci = sample.pci
                        lastTac = sample.tac
                    }
                    val triple = Triple(sample.networkType, sample.overrideType, sample.nrState)
                    val prev = lastTriple
                    if (prev != null && triple != prev) {
                        _events.tryEmit(
                            EnvEvent(
                                sample.tsNanos,
                                EnvEventType.RAT_CHANGE,
                                "network_type ${prev.first}->${triple.first} " +
                                    "override ${prev.second}->${triple.second} " +
                                    "nr_state ${prev.third}->${triple.third}",
                            ),
                        )
                    }
                    lastTriple = triple
                }

                // GPS 路测打点（开关关/无 fix → null；独立于电话权限分支，degraded 样本亦可带坐标）
                val fix = locationProvider?.invoke()
                emit(
                    if (fix == null) {
                        sample
                    } else {
                        sample.copy(lat = fix.lat, lon = fix.lon, accuracyM = fix.accuracyM)
                    },
                )

                tick++
                val nextNs = startNs + tick * SAMPLE_PERIOD_NS
                val sleepMs = (nextNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                if (sleepMs > 0) delay(sleepMs)
            }
        } finally {
            tm?.let { unregisterDisplayListener(it, displayCb) }
        }
    }.flowOn(Dispatchers.Default)

    // ------------------------------------------------------------------
    // requestCellInfoUpdate（API 29+，minSdk 29 直接用）
    // ------------------------------------------------------------------

    /** @return (cellInfos, stale)。超时/失败退回 allCellInfo 缓存并 stale=true（R-02）。 */
    @SuppressLint("MissingPermission") // 调用方已确认 READ_PHONE_STATE + ACCESS_FINE_LOCATION
    private suspend fun requestCellInfo(
        tm: TelephonyManager,
        timeoutMs: Long,
    ): Pair<List<CellInfo>, Boolean> {
        val fresh: List<CellInfo>? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                try {
                    tm.requestCellInfoUpdate(
                        directExecutor,
                        object : TelephonyManager.CellInfoCallback() {
                            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                if (cont.isActive) cont.resume(cellInfo.toList())
                            }

                            override fun onError(errorCode: Int, detail: Throwable?) {
                                if (cont.isActive) cont.resume(emptyList())
                            }
                        },
                    )
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
        }
        if (!fresh.isNullOrEmpty()) return fresh to false
        val cached = try {
            tm.allCellInfo ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        return cached to true
    }

    // ------------------------------------------------------------------
    // 样本组装
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun buildSample(
        tm: TelephonyManager,
        cellInfos: List<CellInfo>,
        requestStale: Boolean,
        subId: Int,
        subSwitched: Boolean,
        overrideType: String?,
        nowNs: Long,
    ): RadioSample {
        val networkType = networkTypeName(
            try {
                tm.dataNetworkType
            } catch (t: Throwable) {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            },
        )
        val nrState = readNrState(tm)
        val operator = try {
            tm.networkOperatorName?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }

        var cellTs: Long? = null
        var stale = requestStale
        var rat: String? = null
        var pci: Int? = null
        var tac: Int? = null
        var arfcn: Int? = null
        var rsrp: Int? = null
        var rsrq: Int? = null
        var sinr: Int? = null

        val reg = pickCell(cellInfos)
        if (reg != null) {
            cellTs = cellTimestampNanos(reg)
            if (nowNs - cellTs > STALE_NS) stale = true // R-02: modem 时戳距采样 >2s
            when (reg) {
                is CellInfoNr -> {
                    rat = "NR"
                    (reg.cellIdentity as? CellIdentityNr)?.let { id ->
                        pci = clean(id.pci)
                        tac = clean(id.tac)
                        arfcn = clean(id.nrarfcn)
                    }
                    (reg.cellSignalStrength as? CellSignalStrengthNr)?.let { sig ->
                        rsrp = clean(sig.ssRsrp)
                        rsrq = clean(sig.ssRsrq)
                        sinr = clean(sig.ssSinr)
                    }
                }
                is CellInfoLte -> {
                    rat = "LTE"
                    pci = clean(reg.cellIdentity.pci)
                    tac = clean(reg.cellIdentity.tac)
                    arfcn = clean(reg.cellIdentity.earfcn)
                    rsrp = clean(reg.cellSignalStrength.rsrp)
                    rsrq = clean(reg.cellSignalStrength.rsrq)
                    sinr = clean(reg.cellSignalStrength.rssnr)
                }
            }
        }

        return RadioSample(
            tsNanos = nowNs,
            cellTsNanos = cellTs,
            stale = stale,
            subId = subId,
            subSwitched = subSwitched,
            networkType = networkType,
            overrideType = overrideType ?: defaultOverrideLabel(),
            nrState = nrState,
            rat = rat,
            pci = pci,
            tac = tac,
            arfcn = arfcn,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            operatorName = operator,
        )
    }

    private fun degradedSample(nowNs: Long, reason: String) = RadioSample(
        tsNanos = nowNs,
        cellTsNanos = null,
        stale = true,
        subId = -1,
        subSwitched = false,
        networkType = reason,
        overrideType = null,
        nrState = "nsa_unknown",
        rat = null,
        pci = null,
        tac = null,
        arfcn = null,
        rsrp = null,
        rsrq = null,
        sinr = null,
        operatorName = null,
    )

    /** registered NR → registered LTE → 任一 NR/LTE（未注册小区仅兜底展示，不用于归因） */
    private fun pickCell(cellInfos: List<CellInfo>): CellInfo? =
        cellInfos.firstOrNull { it.isRegistered && it is CellInfoNr }
            ?: cellInfos.firstOrNull { it.isRegistered && it is CellInfoLte }
            ?: cellInfos.firstOrNull { it is CellInfoNr || it is CellInfoLte }

    private fun cellTimestampNanos(info: CellInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.timestampMillis * 1_000_000L
        } else {
            @Suppress("DEPRECATION") // API 29 无 timestampMillis；getTimeStamp() 即 elapsedRealtime 纳秒轴
            info.timeStamp
        }

    /** CellInfo.UNAVAILABLE（Int.MAX_VALUE）等哨兵值一律转 null（R-10：禁哨兵值入库） */
    private fun clean(v: Int): Int? =
        v.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE && it != Int.MIN_VALUE }

    // ------------------------------------------------------------------
    // R-15：nrState 反射兜底（隐藏 API，失败静默降级 nsa_unknown）
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun readNrState(tm: TelephonyManager): String {
        val ss = try {
            tm.serviceState
        } catch (t: Throwable) {
            null
        } ?: return "nsa_unknown"
        // 1) 反射隐藏 API ServiceState#getNrState（greylist/blocklist 随版本变化，失败即降级）
        try {
            val m = ss.javaClass.getDeclaredMethod("getNrState")
            m.isAccessible = true
            (m.invoke(ss) as? Int)?.let { return nrStateName(it) }
        } catch (t: Throwable) {
            // 静默降级 → toString 兜底
        }
        // 2) toString 解析兜底（NetworkRegistrationInfo dump 含 nrState=XXX）
        return try {
            Regex("nrState=([A-Z_]+)").find(ss.toString())
                ?.groupValues?.get(1)?.lowercase()
                ?: "nsa_unknown"
        } catch (t: Throwable) {
            "nsa_unknown"
        }
    }

    private fun nrStateName(v: Int): String = when (v) {
        0 -> "none"           // NetworkRegistrationInfo.NR_STATE_NONE
        1 -> "restricted"
        2 -> "not_restricted" // NSA 锚定但 SCG 未必激活——图标可显 5G，数据仍可能全走 LTE
        3 -> "connected"      // NR SCG 已连接
        else -> "nsa_unknown"
    }

    // ------------------------------------------------------------------
    // R-15：TelephonyDisplayInfo override 监听（API 31+；以下保守降级）
    // ------------------------------------------------------------------

    private class DisplayState {
        @Volatile
        var overrideType: String? = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class DisplayInfoCallback(
        private val state: DisplayState,
    ) : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            state.overrideType = overrideName(telephonyDisplayInfo.overrideNetworkType)
        }
    }

    private fun registerDisplayListener(tm: TelephonyManager, state: DisplayState): TelephonyCallback? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null // API 29/30：override 列记 unavailable
        return try {
            val cb = DisplayInfoCallback(state)
            tm.registerTelephonyCallback(directExecutor, cb)
            cb
        } catch (t: Throwable) {
            null // 权限/ROM 异常 → override 列保持 unavailable，不影响其余两列
        }
    }

    private fun unregisterDisplayListener(tm: TelephonyManager, cb: TelephonyCallback?) {
        if (cb == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            tm.unregisterTelephonyCallback(cb)
        } catch (t: Throwable) {
            // 忽略：进程退出/重复注销
        }
    }

    private fun defaultOverrideLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "none_reported_yet" else "unavailable_below_api31"

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private fun tmForSub(base: TelephonyManager, subId: Int): TelephonyManager =
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            base.createForSubscriptionId(subId)
        } else {
            base
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "NR"       // 仅 SA；NSA 下 dataNetworkType 恒为 LTE（R-15）
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
        else -> "other($type)"
    }

    // ------------------------------------------------------------------
    // 阶段 0 遗留：一次性快照（MainActivity 调试按钮仍在用）
    // ------------------------------------------------------------------

    fun snapshot(): String {
        val phoneStateOk = granted(Manifest.permission.READ_PHONE_STATE)
        val fineLocOk = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!phoneStateOk || !fineLocOk) {
            val missing = buildList {
                if (!phoneStateOk) add("READ_PHONE_STATE")
                if (!fineLocOk) add("ACCESS_FINE_LOCATION")
            }
            return "radio: permission denied (${missing.joinToString(",")}) -> valid_low_confidence"
        }
        val tm = context.getSystemService(TelephonyManager::class.java)
            ?: return "radio: TelephonyManager unavailable -> valid_low_confidence"
        return try {
            readSnapshot(tmForSub(tm, SubscriptionManager.getDefaultDataSubscriptionId()))
        } catch (e: SecurityException) {
            "radio: SecurityException ${e.message} -> valid_low_confidence"
        }
    }

    @SuppressLint("MissingPermission") // 上方已显式检查两项权限
    private fun readSnapshot(tm: TelephonyManager): String {
        val networkType = networkTypeName(tm.dataNetworkType)
        val operator = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
        val nrState = readNrState(tm)
        val cellInfos: List<CellInfo> = tm.allCellInfo ?: emptyList()
        val cellPart = describeFirstCell(cellInfos)
        return "radio: type=$networkType nrState=$nrState operator=$operator $cellPart"
    }

    private fun describeFirstCell(cellInfos: List<CellInfo>): String {
        // 定位服务总开关关闭时 allCellInfo 返回空（R-02 提示），显式区分于权限拒绝
        if (cellInfos.isEmpty()) return "cell=none (empty CellInfo; location service off or no coverage)"
        val cell = pickCell(cellInfos) ?: return "cell=no LTE/NR entry (${cellInfos.size} other cells)"
        return when (cell) {
            is CellInfoNr -> {
                val sig = cell.cellSignalStrength as? CellSignalStrengthNr
                val id = cell.cellIdentity as? CellIdentityNr
                "cell=NR pci=${fmt(id?.pci)} ssRsrp=${fmt(sig?.ssRsrp)}dBm ssSinr=${fmt(sig?.ssSinr)}dB registered=${cell.isRegistered}"
            }
            is CellInfoLte ->
                "cell=LTE pci=${fmt(cell.cellIdentity.pci)} rsrp=${fmt(cell.cellSignalStrength.rsrp)}dBm " +
                    "rssnr=${fmt(cell.cellSignalStrength.rssnr)}dB registered=${cell.isRegistered}"
            else -> "cell=unexpected ${cell.javaClass.simpleName}"
        }
    }

    private fun fmt(v: Int?): String = v?.let { clean(it)?.toString() } ?: "n/a"

    companion object {
        private const val SAMPLE_PERIOD_NS = 1_000_000_000L // 1Hz
        private const val CELL_INFO_TIMEOUT_MS = 800L       // 超时退缓存标 stale（R-02）
        private const val STALE_NS = 2_000_000_000L         // modem 时戳距采样 >2s 判陈旧（R-02）

        /** TelephonyDisplayInfo override 常量名（API 30 类；仅在 31+ 路径调用） */
        @Suppress("DEPRECATION") // NR_NSA_MMWAVE 在 API 33 弃用并归并 NR_ADVANCED，仍需识别老版本值
        private fun overrideName(v: Int): String = when (v) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "none"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "lte_ca"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "lte_advanced_pro"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "nr_nsa"          // 图标 5G ≠ 承载 5G（R-15）
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> "nr_nsa_mmwave"
            5 -> "nr_advanced" // TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED（API 31 常量，字面量防低版本 lint）
            else -> "override($v)"
        }
    }
}

/**
 * 单条 1Hz 无线层样本（运行期模型；落库经 [toEntity]）。
 * 字段语义见 [RadioSampleEntity]。
 */
data class RadioSample(
    val tsNanos: Long,
    val cellTsNanos: Long?,
    val stale: Boolean,
    val subId: Int,
    val subSwitched: Boolean,
    val networkType: String,
    val overrideType: String?,
    val nrState: String,
    val rat: String?,
    val pci: Int?,
    val tac: Int?,
    val arfcn: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val operatorName: String?,
    // GPS 路测（阶段3）：开关关/无 fix 时 null；坐标绝不进上报体（§9.1）
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Double? = null,
) {
    fun toEntity(runId: String?) = RadioSampleEntity(
        runId = runId,
        tsNanos = tsNanos,
        cellTsNanos = cellTsNanos,
        stale = stale,
        subId = subId,
        subSwitched = subSwitched,
        networkType = networkType,
        overrideType = overrideType,
        nrState = nrState,
        rat = rat,
        pci = pci,
        tac = tac,
        arfcn = arfcn,
        rsrp = rsrp,
        rsrq = rsrq,
        sinr = sinr,
        operatorName = operatorName,
        lat = lat,
        lon = lon,
        accuracyM = accuracyM,
    )
}
