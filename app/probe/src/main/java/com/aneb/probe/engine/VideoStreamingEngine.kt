package com.aneb.probe.engine

import com.aneb.probe.net.AndroidMonotonicNanosClock
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.MonotonicNanosClock
import com.aneb.probe.net.ReachabilityProbe
import com.aneb.probe.ui.BusinessExperienceEvaluator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Phase B：真实链路驱动的短视频流媒体体验压测引擎（ABR + 缓冲 + 真实下载吞吐门控）。
 *
 * 工作原理与口径边界：
 * - 并发向当前 ANEB 节点发起真实 `/api/v1/download` 分片拉流与 `/api/v1/echo` 负载时延探测；
 * - 播放器缓冲池（`bufferMs`）严格由真实到达的字节数按当前清晰度码率换算入池，并按真实墙钟消耗；
 * - 当真实下行低于当前码率导致缓冲归零时，引擎发出 `REBUFFERING` 状态并物理暂停视频播放、
 *   累计卡顿次数与时长、自动触发 ABR 降档；
 * - 测量范围限定为 ANEB 应用到所选测试节点的真实链路承载能力，与抖音 1080P 实机校准拐点同口径对照，
 *   非第三方商业 App 内部流。
 */
class VideoStreamingEngine internal constructor(
    private val client: AnebClient,
    private val clock: MonotonicNanosClock = AndroidMonotonicNanosClock,
) {
    constructor() : this(AnebClient(), AndroidMonotonicNanosClock)

    enum class AbrTier(
        val id: String,
        val label: String,
        val shortLabel: String,
        val bitrateMbps: Double,
    ) {
        UHD_1080P60("1080p60", "1080P 60fps 蓝光", "1080P60", 4.5),
        HD_1080P("1080p", "1080P 高清", "1080P", 2.5),
        SD_720P("720p", "720P 标清", "720P", 1.5),
        LOW_480P("480p", "480P 省流", "480P", 0.8),
    }

    enum class PlaybackState {
        COLD_START,
        PLAYING,
        REBUFFERING,
        COMPLETED,
    }

    data class LiveSnapshot(
        val state: PlaybackState,
        val activeTier: AbrTier,
        val videoChapterIndex: Int,
        val elapsedMs: Long,
        val plannedDurationMs: Long,
        val liveMbps: Double?,
        val avgMbps: Double?,
        val rttMs: Double?,
        val jitterMs: Double?,
        val lossRate: Double,
        val bufferMs: Double,
        val maxBufferMs: Double = MAX_BUFFER_MS,
        val firstFrameMs: Double?,
        val latestSwipeTtffMs: Double?,
        val swipeCount: Int,
        val stallCount: Int,
        val totalStallMs: Long,
        val totalBytes: Long,
        val speedHistoryMbps: List<Double>,
        val rttHistoryMs: List<Double>,
        val summary: VideoStreamSummary? = null,
    )

    data class VideoStreamSummary(
        val avgMbps: Double?,
        val peakMbps: Double?,
        val p50RttMs: Double?,
        val jitterMs: Double?,
        val lossRate: Double,
        val firstFrameMs: Double?,
        val avgSwipeTtffMs: Double?,
        val stallCount: Int,
        val totalStallMs: Long,
        val dominantTier: AbrTier,
        val totalBytes: Long,
        val douyinVerdict: BusinessExperienceEvaluator.Verdict,
    )

    /**
     * 纯状态机内核（JVM 可单测）：按真实到达字节、瞬时带宽和耗时推进缓冲池、ABR 档位与卡顿计数。
     */
    class AbrBufferStateMachine(
        initialTier: AbrTier = AbrTier.HD_1080P,
    ) {
        var state: PlaybackState = PlaybackState.COLD_START
            private set
        var tier: AbrTier = initialTier
            private set
        var bufferMs: Double = 0.0
            private set
        var firstFrameMs: Double? = null
            private set
        var latestSwipeTtffMs: Double? = null
            private set
        var swipeCount: Int = 0
            private set
        var stallCount: Int = 0
            private set
        var totalStallMs: Long = 0L
            private set

        private var chapterStartElapsedMs: Long = 0L
        private val swipeTtffs = ArrayList<Double>()
        private val tierTicks = mutableMapOf<AbrTier, Int>()

        fun triggerSwipe(currentElapsedMs: Long) {
            swipeCount++
            bufferMs = 0.0
            chapterStartElapsedMs = currentElapsedMs
            state = PlaybackState.COLD_START
        }

        fun step(deltaTimeMs: Double, deltaBytes: Long, liveMbps: Double?, elapsedMs: Long) {
            if (deltaTimeMs <= 0.0) return
            // 1) 按当前码率将真实网络到达字节折算为可播放缓冲时长（ms）
            if (deltaBytes > 0L) {
                val bits = deltaBytes * 8.0
                val bps = tier.bitrateMbps * 1_000_000.0
                val addedMs = (bits / bps) * 1_000.0
                bufferMs = (bufferMs + addedMs).coerceAtMost(MAX_BUFFER_MS)
            }

            // 2) 推进状态机：起播 → 播放消耗 → 饿死重缓冲
            when (state) {
                PlaybackState.COLD_START -> {
                    if (bufferMs >= STARTUP_BUFFER_MS) {
                        val ttff = (elapsedMs - chapterStartElapsedMs).coerceAtLeast(1L).toDouble()
                        if (firstFrameMs == null) firstFrameMs = ttff
                        if (swipeCount > 0) {
                            latestSwipeTtffMs = ttff
                            swipeTtffs.add(ttff)
                        }
                        state = PlaybackState.PLAYING
                    }
                }
                PlaybackState.PLAYING -> {
                    bufferMs = (bufferMs - deltaTimeMs).coerceAtLeast(0.0)
                    if (bufferMs <= 0.0) {
                        state = PlaybackState.REBUFFERING
                        stallCount++
                        totalStallMs += deltaTimeMs.toLong()
                        tier = downgradeTier(tier)
                    }
                }
                PlaybackState.REBUFFERING -> {
                    totalStallMs += deltaTimeMs.toLong()
                    if (bufferMs >= REBUFFER_RESUME_MS) {
                        state = PlaybackState.PLAYING
                    }
                }
                PlaybackState.COMPLETED -> Unit
            }

            // 3) 自适应码率阶梯（ABR）：结合实测吞吐与缓冲水位平滑升/降档
            if (liveMbps != null && liveMbps.isFinite()) {
                tier = chooseTier(current = tier, liveMbps = liveMbps, bufferMs = bufferMs)
            }
            tierTicks[tier] = (tierTicks[tier] ?: 0) + 1
        }

        fun avgSwipeTtffMs(): Double? = swipeTtffs.takeIf { it.isNotEmpty() }?.average()

        fun dominantTier(): AbrTier = tierTicks.maxByOrNull { it.value }?.key ?: tier

        companion object {
            fun chooseTier(current: AbrTier, liveMbps: Double, bufferMs: Double): AbrTier {
                // 缓冲充裕且吞吐高时升档；吞吐跌破当前码率或缓冲低位时果断降档保流畅
                val desired = when {
                    liveMbps >= 5.2 && bufferMs >= 2_500.0 -> AbrTier.UHD_1080P60
                    liveMbps >= 2.8 && bufferMs >= 1_600.0 -> AbrTier.HD_1080P
                    liveMbps >= 1.6 -> AbrTier.SD_720P
                    else -> AbrTier.LOW_480P
                }
                return when {
                    desired.ordinal < current.ordinal && bufferMs >= 2_200.0 -> AbrTier.entries[current.ordinal - 1]
                    desired.ordinal > current.ordinal && (bufferMs < 1_800.0 || liveMbps < current.bitrateMbps * 0.85) -> desired
                    else -> current
                }
            }

            private fun downgradeTier(current: AbrTier): AbrTier =
                AbrTier.entries[(current.ordinal + 1).coerceAtMost(AbrTier.entries.lastIndex)]
        }
    }

    private val swipeRequested = AtomicBoolean(false)

    /** 运行中由用户点击“模拟上滑切视频”触发真实冷启动重缓冲与首帧计时。 */
    fun requestSwipeNext() {
        swipeRequested.set(true)
    }

    fun run(
        serverBase: String,
        runId: String,
        durationMs: Long = DEFAULT_DURATION_MS,
    ): Flow<LiveSnapshot> = channelFlow {
        swipeRequested.set(false)
        val base = ReachabilityProbe.deriveE01Pair(serverBase.trim().trimEnd('/'))?.second
            ?: serverBase.trim().trimEnd('/')
        val totalBytes = AtomicLong(0L)
        val totalRequests = AtomicInteger(0)
        val failedRequests = AtomicInteger(0)
        val rttLock = Any()
        val rtts = ArrayList<Double>()

        val startNs = clock.now()
        val stateMachine = AbrBufferStateMachine()
        val speedHist = ArrayDeque<Double>(48)
        val byteWindow = ArrayDeque<Pair<Long, Long>>(32) // (tsNs, totalBytes)

        // 并发 1：持续拉取真实分片流（/api/v1/download），供给缓冲池与瞬时速率
        val downloadJob: Job = launch(Dispatchers.IO) {
            var segIdx = 0
            while (isActive) {
                totalRequests.incrementAndGet()
                val res = try {
                    client.downloadThroughput(
                        "$base/api/v1/download?bytes=$SEGMENT_BYTES&run=$runId&seg=${segIdx++}",
                    ) { chunkBytes, _ ->
                        totalBytes.addAndGet(chunkBytes.toLong())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (res == null || res.error != null || (res.httpCode != null && res.httpCode !in 200..299)) {
                    failedRequests.incrementAndGet()
                    delay(180L)
                }
            }
        }

        // 并发 2：周期性负载 Echo 探测（/api/v1/echo），提供视频播放期间的真实 RTT 与抖动
        val echoJob: Job = launch(Dispatchers.IO) {
            while (isActive) {
                val r = try {
                    client.echo("$base/api/v1/echo")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (r != null && r.error == null && r.rttUs != null) {
                    synchronized(rttLock) {
                        rtts.add(r.rttUs / 1_000.0)
                    }
                }
                delay(ECHO_INTERVAL_MS)
            }
        }

        var prevTickNs = startNs
        var prevBytes = 0L
        var peakMbps: Double? = null

        try {
            while (isActive) {
                val nowNs = clock.now()
                val elapsedMs = ((nowNs - startNs) / 1_000_000L).coerceAtLeast(0L)
                if (elapsedMs >= durationMs) break

                if (swipeRequested.compareAndSet(true, false)) {
                    stateMachine.triggerSwipe(elapsedMs)
                }

                val curBytes = totalBytes.get()
                val deltaBytes = (curBytes - prevBytes).coerceAtLeast(0L)
                val deltaMs = ((nowNs - prevTickNs) / 1_000_000.0).coerceAtLeast(1.0)
                prevBytes = curBytes
                prevTickNs = nowNs

                // 300ms 短窗瞬时速率（Mbps），确保波形灵敏反映真实网络吞吐起伏
                byteWindow.addLast(nowNs to curBytes)
                while (byteWindow.size > 1 && nowNs - byteWindow.first().first > 300_000_000L) {
                    byteWindow.removeFirst()
                }
                val liveMbps: Double? = if (byteWindow.size >= 2) {
                    val (t0, b0) = byteWindow.first()
                    val dtSec = (nowNs - t0) / 1e9
                    if (dtSec >= 0.08) {
                        val v = ((curBytes - b0).coerceAtLeast(0L) * 8.0) / dtSec / 1e6
                        if (v.isFinite()) v else null
                    } else null
                } else null

                if (liveMbps != null) {
                    if (peakMbps == null || liveMbps > peakMbps) peakMbps = liveMbps
                    speedHist.addLast(liveMbps)
                    while (speedHist.size > 48) speedHist.removeFirst()
                }

                stateMachine.step(deltaMs, deltaBytes, liveMbps, elapsedMs)

                val avgMbps = if (elapsedMs >= 300L && curBytes > 0L) {
                    (curBytes * 8.0) / (elapsedMs / 1000.0) / 1e6
                } else null
                val rttSnap = synchronized(rttLock) { rtts.toList() }
                val rttMed = median(rttSnap)
                val jit = jitter(rttSnap)
                val reqs = totalRequests.get().coerceAtLeast(1)
                val lossRate = (failedRequests.get().toDouble() / reqs).coerceIn(0.0, 1.0)

                trySend(
                    LiveSnapshot(
                        state = stateMachine.state,
                        activeTier = stateMachine.tier,
                        videoChapterIndex = stateMachine.swipeCount % 3,
                        elapsedMs = elapsedMs,
                        plannedDurationMs = durationMs,
                        liveMbps = liveMbps,
                        avgMbps = avgMbps,
                        rttMs = rttMed,
                        jitterMs = jit,
                        lossRate = lossRate,
                        bufferMs = stateMachine.bufferMs,
                        firstFrameMs = stateMachine.firstFrameMs,
                        latestSwipeTtffMs = stateMachine.latestSwipeTtffMs,
                        swipeCount = stateMachine.swipeCount,
                        stallCount = stateMachine.stallCount,
                        totalStallMs = stateMachine.totalStallMs,
                        totalBytes = curBytes,
                        speedHistoryMbps = speedHist.toList(),
                        rttHistoryMs = rttSnap.takeLast(48),
                    ),
                )
                delay(TICK_MS)
            }
        } finally {
            downloadJob.cancel()
            echoJob.cancel()
        }

        val finalElapsedMs = ((clock.now() - startNs) / 1_000_000L).coerceAtLeast(1L)
        val finalBytes = totalBytes.get()
        val finalAvgMbps = if (finalBytes > 0L) (finalBytes * 8.0) / (finalElapsedMs / 1000.0) / 1e6 else null
        val finalRtts = synchronized(rttLock) { rtts.toList() }
        val finalRttP50 = median(finalRtts)
        val finalJit = jitter(finalRtts)
        val finalLoss = (failedRequests.get().toDouble() / totalRequests.get().coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val verdict = BusinessExperienceEvaluator.evaluateDouyinMetrics(
            downMbps = finalAvgMbps,
            rttMs = finalRttP50,
            lossRate = finalLoss,
        )
        val summary = VideoStreamSummary(
            avgMbps = finalAvgMbps,
            peakMbps = peakMbps,
            p50RttMs = finalRttP50,
            jitterMs = finalJit,
            lossRate = finalLoss,
            firstFrameMs = stateMachine.firstFrameMs,
            avgSwipeTtffMs = stateMachine.avgSwipeTtffMs(),
            stallCount = stateMachine.stallCount,
            totalStallMs = stateMachine.totalStallMs,
            dominantTier = stateMachine.dominantTier(),
            totalBytes = finalBytes,
            douyinVerdict = verdict,
        )
        send(
            LiveSnapshot(
                state = PlaybackState.COMPLETED,
                activeTier = summary.dominantTier,
                videoChapterIndex = stateMachine.swipeCount % 3,
                elapsedMs = durationMs,
                plannedDurationMs = durationMs,
                liveMbps = finalAvgMbps,
                avgMbps = finalAvgMbps,
                rttMs = finalRttP50,
                jitterMs = finalJit,
                lossRate = finalLoss,
                bufferMs = stateMachine.bufferMs,
                firstFrameMs = summary.firstFrameMs,
                latestSwipeTtffMs = stateMachine.latestSwipeTtffMs,
                swipeCount = stateMachine.swipeCount,
                stallCount = summary.stallCount,
                totalStallMs = summary.totalStallMs,
                totalBytes = finalBytes,
                speedHistoryMbps = speedHist.toList(),
                rttHistoryMs = finalRtts.takeLast(48),
                summary = summary,
            ),
        )
    }.flowOn(Dispatchers.Default)

    private fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun jitter(xs: List<Double>): Double? {
        if (xs.size < 2) return null
        val diffs = xs.zipWithNext { a, b -> abs(b - a) }
        return median(diffs)
    }

    companion object {
        const val DEFAULT_DURATION_MS = 20_000L
        const val MAX_BUFFER_MS = 8_000.0
        const val STARTUP_BUFFER_MS = 800.0
        const val REBUFFER_RESUME_MS = 1_200.0
        private const val TICK_MS = 50L
        private const val ECHO_INTERVAL_MS = 450L
        private const val SEGMENT_BYTES = 1_048_576
    }
}
