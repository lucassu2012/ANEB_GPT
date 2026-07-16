package com.aneb.probe.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference

/**
 * GPS 路测打点（阶段3 设计项：路测轨迹支持；设计文档 §9.1 隐私边界）。
 *
 * - 平台 [LocationManager] + GPS_PROVIDER，1Hz 请求——**不引入 GMS/FusedLocation
 *   依赖**（供应链纪律，与 Cronet 例外的评审口径一致；模拟器 geo fix 也走 GPS_PROVIDER）。
 * - [current] 返回最近 fix；权限缺失 / 定位服务未开启 / 尚无 fix / fix 过期
 *   （> [FIX_MAX_AGE_NS]）一律 null——R-10 语义：证据缺失记 null，绝不拿陈旧坐标
 *   伪装当前位置。
 * - **隐私边界（§9.1）**：坐标只经 RadioSample 入本地 Room 与本地导出（轨迹 CSV），
 *   绝不进 /results 上报体（ResultReporter 无坐标字段，单测锚定）；路测开关默认关，
 *   开启需用户在 UI 显式操作并有显著提示。
 * - 注册失败/Provider 异常静默降级为"无 fix"（探针不因定位子系统崩溃）。
 */
class LocationTagger(private val context: Context) {

    private val lastFix = AtomicReference<GeoFix?>(null)
    private var listener: LocationListener? = null

    /** 是否成功注册了 GPS 更新（权限/Provider 检查通过且 requestLocationUpdates 未抛） */
    @Volatile
    var active: Boolean = false
        private set

    @SuppressLint("MissingPermission") // 下方显式检查 ACCESS_FINE_LOCATION
    fun start() {
        if (listener != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // 权限缺失：active=false，current() 恒 null（R-10）
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val cb = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastFix.set(
                    GeoFix(
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                        elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                    ),
                )
            }

            // API 29 仍可能回调的遗留接口，空实现防 AbstractMethodError
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                lastFix.set(null) // 定位被关：立刻回到"无 fix"，不残留旧坐标
            }
        }
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                0f,
                cb,
                Looper.getMainLooper(),
            )
            listener = cb
            active = true
        } catch (t: Throwable) {
            // Provider 异常/ROM 差异：静默降级，样本坐标列保持 null
            active = false
        }
    }

    fun stop() {
        val cb = listener ?: return
        listener = null
        active = false
        lastFix.set(null)
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        try {
            lm.removeUpdates(cb)
        } catch (t: Throwable) {
            // 忽略：重复注销/进程退出
        }
    }

    /** 最近有效 fix；无/过期 null（调用方按 1Hz 采样节奏取用） */
    fun current(): GeoFix? {
        val fix = lastFix.get() ?: return null
        val age = SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos
        return fix.takeIf { age in 0..FIX_MAX_AGE_NS }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1_000L // 1Hz，与 RadioCollector 同拍
        /** fix 过期窗口：路测 1Hz 场景下 30s 无新 fix 视为失锁，坐标列回 null */
        private const val FIX_MAX_AGE_NS = 30_000_000_000L
    }
}

/** 单个 GPS fix（运行期模型；坐标绝不进上报体，见 [LocationTagger] KDoc） */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val elapsedRealtimeNanos: Long,
)

/**
 * 轨迹纯函数（无 Android 依赖，JVM 单测直测）：Haversine 距离 / 场景窗口轨迹摘要 /
 * 本地轨迹 CSV（隐私边界内的"本地导出"，与上报体无关）。
 */
object GeoTrack {

    /** 轨迹点（从 RadioSampleEntity 的可空坐标列投影而来） */
    data class Point(val tsNanos: Long, val lat: Double?, val lon: Double?, val accuracyM: Double?)

    /** 场景窗口轨迹摘要：有效打点数与起终点直线距离（<2 点时距离 null，R-10） */
    data class Summary(val points: Int, val startEndMeters: Double?)

    private const val EARTH_RADIUS_M = 6_371_000.8

    /** 大圆距离（Haversine，米）。 */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    /**
     * 场景窗口 [startNanos], [endNanos]（endNanos=null 视为开区间到末尾）内的轨迹摘要。
     * 只统计 lat/lon 均非 null 的点；不足 2 点时 startEndMeters=null（不出 0 顶替）。
     */
    fun summarize(points: List<Point>, startNanos: Long, endNanos: Long?): Summary {
        val inWindow = points.asSequence()
            .filter { it.lat != null && it.lon != null }
            .filter { it.tsNanos >= startNanos && (endNanos == null || it.tsNanos <= endNanos) }
            .sortedBy { it.tsNanos }
            .toList()
        if (inWindow.size < 2) return Summary(inWindow.size, null)
        val first = inWindow.first()
        val last = inWindow.last()
        return Summary(
            points = inWindow.size,
            startEndMeters = haversineMeters(first.lat!!, first.lon!!, last.lat!!, last.lon!!),
        )
    }

    /**
     * 轨迹 CSV（本地导出专用；表头固定）。只输出有坐标的行——无坐标样本不属于轨迹。
     * 坐标仅入本地 Downloads，导出动作由用户显式触发（§9.1）。
     */
    fun buildTrackCsv(points: List<Point>): String {
        val sb = StringBuilder("ts_nanos,lat,lon,accuracy_m\n")
        points.asSequence()
            .filter { it.lat != null && it.lon != null }
            .sortedBy { it.tsNanos }
            .forEach { p ->
                sb.append(p.tsNanos).append(',')
                    .append(p.lat).append(',')
                    .append(p.lon).append(',')
                    .append(p.accuracyM?.toString() ?: "").append('\n')
            }
        return sb.toString()
    }
}
