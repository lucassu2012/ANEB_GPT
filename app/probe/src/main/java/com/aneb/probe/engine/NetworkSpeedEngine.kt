package com.aneb.probe.engine

import android.content.Context
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.BasicSpeedResultEntity
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.BoundNetwork
import com.aneb.probe.net.GuardException
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.ReachabilityProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

enum class AnebTestMode(val label: String) {
    NETWORK_BASIC("基本测速"),
    TOKEN_SIMULATION("Token 仿真"),
    AI_REALTIME_SIMULATION("AI 实时"),
    TOKEN_EXPERIENCE("Agent 取证"),
}

enum class BasicSpeedPhase { IDLE, PREPARING, LATENCY, DOWNLOAD, UPLOAD, FINALIZING, COMPLETE, FAILED }

data class BasicSpeedTelemetry(
    val phase: BasicSpeedPhase = BasicSpeedPhase.IDLE,
    val currentMbps: Double? = null,
    val phaseAverageMbps: Double? = null,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    /** 应用层 echo 请求失败率，不是 IP 层丢包。 */
    val requestLossRate: Double? = null,
    val progress: Double = 0.0,
    val historyMbps: List<Double> = emptyList(),
)

data class BasicSpeedResult(
    val runId: String,
    val startedAtEpochMs: Long,
    val serverBase: String,
    val profileVersion: String,
    val status: String,
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val pingMs: Double?,
    val jitterMs: Double?,
    val requestLossRate: Double?,
    val postLoadPingMs: Double?,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val transferErrors: List<String>,
)

/** SpeedTest 同项目的基本网络性能引擎；与 Token/AQS 引擎独立，避免口径串扰。 */
class NetworkSpeedEngine(private val context: Context) {
    data class Config(
        val serverBase: String,
        val transport: TestEngine.TransportMode = TestEngine.TransportMode.AUTO,
    )

    private val _telemetry = MutableStateFlow(BasicSpeedTelemetry())
    val telemetry: StateFlow<BasicSpeedTelemetry> = _telemetry.asStateFlow()

    private val _result = MutableStateFlow<BasicSpeedResult?>(null)
    val result: StateFlow<BasicSpeedResult?> = _result.asStateFlow()

    fun run(config: Config): Flow<String> = channelFlow {
        val log: suspend (String) -> Unit = { send(it) }
        val runId = TestEngine.newRunId()
        val startedAtEpochMs = System.currentTimeMillis()
        val configuredBase = config.serverBase.trim().trimEnd('/')
        val transport = config.transport.name.lowercase()
        _result.value = null
        _telemetry.value = BasicSpeedTelemetry(phase = BasicSpeedPhase.PREPARING)
        log("BASIC_START run_id=$runId transport=$transport server=$configuredBase")

        val guard = NetGuard.guardCheck(context)
        if (!guard.ok) {
            val reason = guard.reasons.joinToString(",")
            _telemetry.value = BasicSpeedTelemetry(phase = BasicSpeedPhase.FAILED)
            publishResult(BasicSpeedResult(
                runId, startedAtEpochMs, configuredBase, "unknown", "guard_rejected:$reason",
                null, null, null, null, null, null, 0L, 0L, emptyList(),
            ), log)
            log("BASIC_END run_id=$runId status=guard_rejected reasons=$reason")
            return@channelFlow
        }

        var bound: BoundNetwork? = null
        try {
            bound = when (config.transport) {
                TestEngine.TransportMode.AUTO -> null
                TestEngine.TransportMode.WIFI -> NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_WIFI)
                TestEngine.TransportMode.CELLULAR -> NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        } catch (e: GuardException) {
            _telemetry.value = BasicSpeedTelemetry(phase = BasicSpeedPhase.FAILED)
            publishResult(BasicSpeedResult(
                runId, startedAtEpochMs, configuredBase, "unknown", "bind_failed",
                null, null, null, null, null, null, 0L, 0L, listOf(e.toString()),
            ), log)
            log("BASIC_END run_id=$runId status=bind_failed")
            return@channelFlow
        }

        try {
            val client = AnebClient(bound)
            var reach: ReachabilityProbe.DualReach? = null
            ReachabilityProbe.deriveE01Pair(configuredBase)?.let { (sniBase, ipBase) ->
                reach = runCatching { ReachabilityProbe(bound).probeDual(sniBase, ipBase) }.getOrNull()
            }
            val measureBase = ReachabilityProbe.preferredMeasureBase(configuredBase, reach)
            if (measureBase != configuredBase) {
                log("BASIC_REACH_SWITCH from=sni_host to=bare_ip reason=sni_rst_ip_ok base=$measureBase")
            }

            val loaded = ProfileRepository(context).load(client, measureBase)
            val profile = loaded.profiles[PROFILE_ID]
                ?: throw IllegalStateException("missing_profile:$PROFILE_ID")
            require(profile.modeId == ScenarioProfile.MODE_NETWORK_BASIC) {
                "profile_mode_mismatch:${profile.modeId}"
            }
            log(
                "BASIC_PROFILE id=${profile.profileId} version=${profile.version} " +
                    "source=${loaded.source} live_metric=${profile.presentation.liveMetricId}"
            )

            val phases = profile.phases
            val baselinePhase = phases.firstOrNull { it.type == ProfilePhase.TYPE_CLOCK_SYNC }
                ?: throw IllegalStateException("missing_phase:clock_sync")
            val downloadPhase = phases.firstOrNull { it.type == ProfilePhase.TYPE_DOWNLOAD_THROUGHPUT }
                ?: throw IllegalStateException("missing_phase:download_throughput")
            val uploadPhase = phases.firstOrNull { it.type == ProfilePhase.TYPE_UPLOAD_THROUGHPUT }
                ?: throw IllegalStateException("missing_phase:upload_throughput")
            val postPhase = phases.lastOrNull { it.type == ProfilePhase.TYPE_CLOCK_SYNC } ?: baselinePhase

            _telemetry.value = _telemetry.value.copy(phase = BasicSpeedPhase.LATENCY, progress = 0.03)
            log("BASIC_PHASE run_id=$runId phase=latency samples=${baselinePhase.samples}")
            val baseline = measureEcho(client, measureBase, baselinePhase.samples) { index, summary ->
                _telemetry.value = _telemetry.value.copy(
                    pingMs = summary.rttP50Ms,
                    jitterMs = summary.jitterMs,
                    requestLossRate = summary.requestLossRate,
                    progress = 0.03 + 0.14 * index / baselinePhase.samples.coerceAtLeast(1),
                )
            }

            log("BASIC_PHASE run_id=$runId phase=download")
            val download = runTransferPhase(
                client = client,
                base = measureBase,
                runId = runId,
                phase = downloadPhase,
                direction = TransferDirection.DOWNLOAD,
                progressStart = 0.18,
                progressEnd = 0.54,
            )

            log("BASIC_PHASE run_id=$runId phase=upload")
            val upload = runTransferPhase(
                client = client,
                base = measureBase,
                runId = runId,
                phase = uploadPhase,
                direction = TransferDirection.UPLOAD,
                progressStart = 0.56,
                progressEnd = 0.90,
            )

            _telemetry.value = _telemetry.value.copy(
                phase = BasicSpeedPhase.FINALIZING,
                currentMbps = null,
                progress = 0.92,
            )
            log("BASIC_PHASE run_id=$runId phase=post_latency samples=${postPhase.samples}")
            val post = measureEcho(client, measureBase, postPhase.samples) { index, _ ->
                _telemetry.value = _telemetry.value.copy(
                    progress = 0.92 + 0.07 * index / postPhase.samples.coerceAtLeast(1),
                )
            }

            val errors = download.errors + upload.errors
            val status = when {
                download.averageMbps == null && upload.averageMbps == null -> "failed"
                download.averageMbps == null || upload.averageMbps == null -> "partial"
                else -> "completed"
            }
            val finalResult = BasicSpeedResult(
                runId = runId,
                startedAtEpochMs = startedAtEpochMs,
                serverBase = measureBase,
                profileVersion = profile.version,
                status = status,
                downloadMbps = download.averageMbps,
                uploadMbps = upload.averageMbps,
                pingMs = baseline.rttP50Ms,
                jitterMs = baseline.jitterMs,
                requestLossRate = baseline.requestLossRate,
                postLoadPingMs = post.rttP50Ms,
                downloadBytes = download.totalBytes,
                uploadBytes = upload.totalBytes,
                transferErrors = errors,
            )
            publishResult(finalResult, log)
            _telemetry.value = _telemetry.value.copy(
                phase = if (status == "failed") BasicSpeedPhase.FAILED else BasicSpeedPhase.COMPLETE,
                currentMbps = null,
                phaseAverageMbps = null,
                progress = 1.0,
            )
            log(
                "BASIC_RESULT run_id=$runId status=$status " +
                    "down_mbps=${download.averageMbps ?: "null"} up_mbps=${upload.averageMbps ?: "null"} " +
                    "ping_ms=${baseline.rttP50Ms ?: "null"} jitter_ms=${baseline.jitterMs ?: "null"} " +
                    "request_loss=${baseline.requestLossRate ?: "null"} errors=${errors.size}"
            )
            log("BASIC_END run_id=$runId status=$status")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _telemetry.value = _telemetry.value.copy(phase = BasicSpeedPhase.FAILED, currentMbps = null)
            publishResult(BasicSpeedResult(
                runId, startedAtEpochMs, configuredBase, "unknown", "error:${e.javaClass.simpleName}",
                null, null, null, null, null, null, 0L, 0L, listOf(e.toString()),
            ), log)
            log("BASIC_FAILED run_id=$runId error=${e.toString().replace(' ', '_')}")
            log("BASIC_END run_id=$runId status=error")
        } finally {
            bound?.release()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun measureEcho(
        client: AnebClient,
        base: String,
        samples: Int,
        onProgress: (Int, BasicSpeedMath.EchoSummary) -> Unit,
    ): BasicSpeedMath.EchoSummary {
        val results = ArrayList<Double?>(samples)
        repeat(samples.coerceAtLeast(1)) { index ->
            val echo = client.echo("$base/api/v1/echo")
            results += echo.rttUs?.takeIf { echo.error == null }?.div(1_000.0)
            onProgress(index + 1, BasicSpeedMath.summarizeEcho(results))
            if (index + 1 < samples) delay(ECHO_GAP_MS)
        }
        return BasicSpeedMath.summarizeEcho(results)
    }

    private enum class TransferDirection { DOWNLOAD, UPLOAD }

    private data class TransferSummary(
        val averageMbps: Double?,
        val totalBytes: Long,
        val errors: List<String>,
    )

    private suspend fun publishResult(
        result: BasicSpeedResult,
        log: suspend (String) -> Unit,
    ) {
        _result.value = result
        val write = runCatching {
            AnebDatabase.get(context).basicSpeedResultDao().insert(
                BasicSpeedResultEntity(
                    runId = result.runId,
                    startedAtEpochMs = result.startedAtEpochMs,
                    serverBase = result.serverBase,
                    claimScope = BASIC_CLAIM_SCOPE,
                    profileId = PROFILE_ID,
                    profileVersion = result.profileVersion,
                    conclusionPolicyId = "network-basic-conclusions-v1",
                    status = result.status,
                    downloadMbps = result.downloadMbps,
                    uploadMbps = result.uploadMbps,
                    pingMs = result.pingMs,
                    jitterMs = result.jitterMs,
                    requestLossRate = result.requestLossRate,
                    postLoadPingMs = result.postLoadPingMs,
                    downloadBytes = result.downloadBytes,
                    uploadBytes = result.uploadBytes,
                    transferErrors = result.transferErrors.joinToString("\n") { it.replace("\u0000", "") },
                ),
            )
        }
        log(
            if (write.isSuccess) {
                "BASIC_DB_WRITE run_id=${result.runId} ok=true"
            } else {
                "BASIC_DB_WRITE run_id=${result.runId} ok=false error=${write.exceptionOrNull()?.javaClass?.simpleName}"
            },
        )
    }

    private suspend fun runTransferPhase(
        client: AnebClient,
        base: String,
        runId: String,
        phase: ProfilePhase,
        direction: TransferDirection,
        progressStart: Double,
        progressEnd: Double,
    ): TransferSummary = coroutineScope {
        val durationMs = phase.durationMs.coerceAtLeast(1_000)
        val parallel = phase.parallel.coerceIn(1, MAX_PARALLEL)
        val transferBytes = phase.bytes.coerceAtLeast(1L)
        val chunkBytes = (phase.chunkKb.coerceAtLeast(16) * 1024).coerceAtMost(1 shl 20)
        val totalBytes = LongAdder()
        val completedTransfers = AtomicInteger(0)
        val errors = ConcurrentLinkedQueue<String>()
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val deadlineNanos = startNanos + durationMs * 1_000_000L
        val rateWindow = ByteRateWindow()
        val history = ArrayDeque<Double>()
        val uiPhase = if (direction == TransferDirection.DOWNLOAD) BasicSpeedPhase.DOWNLOAD else BasicSpeedPhase.UPLOAD

        val sampler: Job = launch(Dispatchers.Default) {
            while (isActive) {
                val now = SystemClock.elapsedRealtimeNanos()
                val bytes = totalBytes.sum()
                val live = rateWindow.add(now, bytes)
                if (live != null) {
                    history.addLast(live)
                    while (history.size > HISTORY_POINTS) history.removeFirst()
                }
                val phaseProgress = ((now - startNanos).toDouble() / (durationMs * 1_000_000.0)).coerceIn(0.0, 1.0)
                _telemetry.value = _telemetry.value.copy(
                    phase = uiPhase,
                    currentMbps = live,
                    phaseAverageMbps = BasicSpeedMath.mbps(bytes, now - startNanos),
                    progress = progressStart + (progressEnd - progressStart) * phaseProgress,
                    historyMbps = history.toList(),
                )
                delay(TELEMETRY_MS)
            }
        }

        val workers = List(parallel) { workerIndex ->
            launch(Dispatchers.IO) {
                while (isActive && SystemClock.elapsedRealtimeNanos() < deadlineNanos) {
                    val result = when (direction) {
                        TransferDirection.DOWNLOAD -> client.downloadThroughput(
                            "$base/api/v1/download?bytes=$transferBytes&chunk_kb=${phase.chunkKb}",
                        ) { count, _ -> totalBytes.add(count.toLong()) }
                        TransferDirection.UPLOAD -> client.uploadThroughput(
                            "$base/api/v1/upload?run=$runId-basic-$workerIndex",
                            totalBytes = transferBytes,
                            chunkBytes = chunkBytes,
                        ) { count, _ -> totalBytes.add(count.toLong()) }
                    }
                    if (result.error == null) {
                        completedTransfers.incrementAndGet()
                    } else {
                        errors.add(result.error)
                        break
                    }
                }
            }
        }

        delay(durationMs.toLong())
        workers.forEach { it.cancel() }
        workers.forEach { it.cancelAndJoin() }
        sampler.cancelAndJoin()

        val endNanos = SystemClock.elapsedRealtimeNanos()
        val bytes = totalBytes.sum()
        // 至少收到/写入一个字节才有 goodput；0 表示失败/未测到，必须返回 null（R-10）。
        val average = BasicSpeedMath.mbps(bytes, endNanos - startNanos)
        if (bytes > 0L && completedTransfers.get() == 0) {
            errors.add("no_transfer_completed_before_phase_deadline")
        }
        TransferSummary(average, bytes, errors.toList())
    }

    companion object {
        const val PROFILE_ID = "basic_network"
        const val BASIC_CLAIM_SCOPE = "application_end_to_end_to_probe_node"
        private const val ECHO_GAP_MS = 75L
        private const val TELEMETRY_MS = 100L
        private const val HISTORY_POINTS = 40
        private const val MAX_PARALLEL = 8
    }
}

/** 基本测速纯计算口径，JVM 可单测。 */
object BasicSpeedMath {
    data class EchoSummary(
        val rttP50Ms: Double?,
        val jitterMs: Double?,
        val requestLossRate: Double?,
    )

    fun summarizeEcho(samples: List<Double?>): EchoSummary {
        if (samples.isEmpty()) return EchoSummary(null, null, null)
        val valid = samples.filterNotNull()
        val jitter = if (valid.size >= 2) median(valid.zipWithNext { a, b -> kotlin.math.abs(b - a) }) else null
        return EchoSummary(
            rttP50Ms = median(valid),
            jitterMs = jitter,
            requestLossRate = (samples.size - valid.size).toDouble() / samples.size,
        )
    }

    fun mbps(bytes: Long, elapsedNanos: Long): Double? =
        if (bytes <= 0L || elapsedNanos <= 0L) null else bytes * 8.0 / (elapsedNanos / 1e9) / 1e6

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }
}

/** 单协程累计字节滑窗：热路径只 LongAdder.add，速率计算不在网络回调线程。 */
class ByteRateWindow(private val windowNanos: Long = 1_000_000_000L) {
    private data class Sample(val atNanos: Long, val totalBytes: Long)
    private val samples = ArrayDeque<Sample>()

    fun add(nowNanos: Long, totalBytes: Long): Double? {
        samples.addLast(Sample(nowNanos, totalBytes))
        while (samples.isNotEmpty() && samples.first().atNanos < nowNanos - windowNanos) {
            samples.removeFirst()
        }
        val first = samples.firstOrNull() ?: return null
        val last = samples.lastOrNull() ?: return null
        val elapsed = last.atNanos - first.atNanos
        if (elapsed < LiveStreamWindow.MIN_SAMPLE_NANOS) return null
        return BasicSpeedMath.mbps(last.totalBytes - first.totalBytes, elapsed)
    }
}
