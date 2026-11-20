package com.aneb.probe.engineering

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.aneb.probe.BuildConfig
import com.aneb.probe.data.VoiceResultRepository
import com.aneb.probe.engine.VoiceRunner
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.BoundNetwork
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.PathMonitor
import com.aneb.probe.scoring.AqsScorer
import com.aneb.probe.ui.VoiceFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-only Profile 4 synthetic voice correlation instrumentation (F10a).
 * Executes [VoiceRunner.runSim] directly (fail-closed, never `runWithFallbackAndPersist`)
 * over explicit `BoundNetwork` (`wlan0`, `!VPN`, `VALIDATED`, `NO_PROXY`) + [PathMonitor]
 * against pinned `miw2` (`https://120.77.174.207:8443`) with a 105s phase deadline.
 * Zero Room v15 writes and zero changes to `VoiceRunner.kt` / `AqsScorer.kt`.
 */
@RunWith(AndroidJUnit4::class)
class Profile4VoiceCorrelationInstrumentation {

    @Test
    fun runVoiceSimPhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        require(BuildConfig.DEBUG) { "DEBUG_REQUIRED" }
        require(!arguments.containsKey("url")) { "ARBITRARY_URL_FORBIDDEN" }
        val input = Profile4VoiceCorrelationContract.KEYS.associateWith { arguments.getString(it) }
        val spec = Profile4VoiceCorrelationContract.parse(input)

        val context = instrumentation.targetContext
        val parent = File(checkNotNull(context.getExternalFilesDir(null)), "voice-correlation")
        check(parent.isDirectory || parent.mkdirs()) { "OUTPUT_PARENT_UNAVAILABLE" }
        val root = File(parent, spec.runId)
        check(root.mkdir()) { "VOICE_CORRELATION_RUN_ALREADY_CONSUMED" }

        val startedNanos = SystemClock.elapsedRealtimeNanos()
        val deadlineNanos = startedNanos + Profile4VoiceCorrelationContract.PHASE_DEADLINE_NANOS
        val invalid = AtomicReference<String?>(null)
        val activeJob = AtomicReference<Job?>(null)
        val events = ArrayList<Profile4VoiceCorrelationContract.PhaseEvent>()

        var bound: BoundNetwork? = null
        var monitor: PathMonitor? = null
        var client: AnebClient? = null
        var doneSample: VoiceRunner.Sample? = null
        var termination = "NOT_DISPATCHED"
        var failure: String? = null

        var targetApkSha: String? = null
        var testApkSha: String? = null
        var packageName: String? = null
        var versionCode: Long? = null
        var versionName: String? = null
        var guardMeta: Map<String, String> = emptyMap()
        var guardReasons: List<String> = emptyList()
        var networkHandle: String? = null
        var networkId: String? = null
        var interfaceName: String? = null
        var localAddresses: List<String> = emptyList()
        var postNetworkJson: JsonObject? = null

        val cleanupRequested = LinkedHashMap<String, Boolean>()
        val cleanupErrors = ArrayList<String>()

        fun recordEvent(name: String, detail: String = "") = synchronized(events) {
            if (events.size < 128) {
                events.add(
                    Profile4VoiceCorrelationContract.PhaseEvent(
                        name = name,
                        nanos = SystemClock.elapsedRealtimeNanos(),
                        detail = detail.take(512),
                    ),
                )
            }
        }

        fun checkAdmitted() {
            invalid.get()?.let { throw Stop("PATH_INVALID:$it") }
            if (SystemClock.elapsedRealtimeNanos() >= deadlineNanos) throw Stop("TIME_LIMIT")
        }

        try {
            targetApkSha = sha256(File(context.applicationInfo.sourceDir))
            testApkSha = sha256(File(instrumentation.context.applicationInfo.sourceDir))
            check(targetApkSha == spec.targetSha && testApkSha == spec.testSha) { "APK_PIN_MISMATCH" }
            checkAdmitted()

            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            packageName = context.packageName
            versionCode = info.longVersionCode
            versionName = info.versionName

            val cm = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
            rejectUnknownOrVpnNetworks(cm)
            checkAdmitted()

            val guard = NetGuard.guardCheck(context)
            guardMeta = guard.metadata
            guardReasons = guard.reasons
            check(guard.ok) { "NETWORK_GUARD_REJECTED:${guard.reasons.joinToString(",")}" }
            checkAdmitted()

            val acquireTimeoutMs = minOf(
                15_000L,
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - SystemClock.elapsedRealtimeNanos()),
            ).coerceAtLeast(1L)
            val acquired = runBlocking {
                NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_WIFI, acquireTimeoutMs)
            }
            bound = acquired
            checkAdmitted()

            val link = checkNotNull(cm.getLinkProperties(acquired.network))
            val ips = link.linkAddresses.map { it.address.hostAddress.orEmpty() }
            localAddresses = ips
            interfaceName = link.interfaceName
            networkHandle = acquired.network.networkHandle.toString()
            networkId = acquired.network.toString()
            check(cm.activeNetwork == acquired.network && link.interfaceName == "wlan0" && spec.phoneIp in ips) {
                "WIFI_IDENTITY_MISMATCH"
            }
            val caps = checkNotNull(cm.getNetworkCapabilities(acquired.network))
            check(
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            ) { "WIFI_NOT_READY" }
            check(link.httpProxy == null && cm.defaultProxy == null) { "PROXY_PRESENT" }

            val pathMonitor = PathMonitor(
                context = context,
                bound = acquired,
                onEvent = { recordEvent("PATH_${it.type.name}", "${it.tsNanos}:${it.detail}") },
                onInvalidate = { reason ->
                    invalid.compareAndSet(null, reason)
                    activeJob.get()?.cancel(CancellationException("PATH_INVALID:$reason"))
                },
            )
            monitor = pathMonitor
            pathMonitor.start()
            checkAdmitted()

            val anebClient = AnebClient(acquired)
            client = anebClient
            val runner = VoiceRunner(anebClient)

            termination = "DISPATCHED"
            recordEvent("VOICE_SIM_START", Profile4VoiceCorrelationContract.MIW2_BASE_URL)
            var lastPhase: VoiceRunner.Phase? = null
            var lastTurnsOk = -1
            var lastSample: VoiceRunner.Sample? = null

            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(1L)
            runBlocking {
                withTimeout(remainingMs) {
                    val job = launch {
                        runner.runSim(Profile4VoiceCorrelationContract.MIW2_BASE_URL).collect { sample ->
                            lastSample = sample
                            if (sample.phase != lastPhase || sample.turnsOk != lastTurnsOk) {
                                lastPhase = sample.phase
                                lastTurnsOk = sample.turnsOk
                                recordEvent(
                                    "PHASE_${sample.phase.name.uppercase()}",
                                    "turnsOk=${sample.turnsOk},sent=${sample.framesSent},recv=${sample.framesRecv},lowConf=${sample.lowConfidence}",
                                )
                            }
                        }
                    }
                    activeJob.set(job)
                    job.join()
                }
            }
            checkAdmitted()

            val finalSample = lastSample
            check(
                finalSample != null &&
                    finalSample.phase == VoiceRunner.Phase.Done &&
                    finalSample.caliber == VoiceRunner.SIM_CALIBER &&
                    finalSample.turnsOk == Profile4VoiceCorrelationContract.EXPECTED_TURNS_OK,
            ) {
                "VOICE_SIM_INCOMPLETE:phase=${finalSample?.phase},caliber=${finalSample?.caliber},turnsOk=${finalSample?.turnsOk}"
            }
            doneSample = finalSample

            rejectUnknownOrVpnNetworks(cm)
            val postLink = checkNotNull(cm.getLinkProperties(acquired.network)) { "POST_LINK_MISSING" }
            val postCaps = checkNotNull(cm.getNetworkCapabilities(acquired.network)) { "POST_CAPABILITIES_MISSING" }
            val postAddresses = postLink.linkAddresses.map { it.address.hostAddress.orEmpty() }
            postNetworkJson = buildJsonObject {
                put("observed_nanos", SystemClock.elapsedRealtimeNanos())
                put("network_handle", acquired.network.networkHandle.toString())
                put("default_network_id", cm.activeNetwork?.toString() ?: "")
                put("interface", postLink.interfaceName ?: "")
                put("local_addresses", buildJsonArray { postAddresses.forEach { add(JsonPrimitive(it)) } })
                put("wifi", postCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                put("validated", postCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                put("not_suspended", postCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
                put("vpn", postCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                put("proxy_present", postLink.httpProxy != null || cm.defaultProxy != null)
            }
            check(
                cm.activeNetwork == acquired.network &&
                    postLink.interfaceName == "wlan0" &&
                    spec.phoneIp in postAddresses &&
                    postCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    postCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    postCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) &&
                    !postCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    postLink.httpProxy == null &&
                    cm.defaultProxy == null &&
                    NetGuard.guardCheck(context).ok &&
                    !pathMonitor.invalidated,
            ) { "POST_PATH_INVALID" }
            termination = "VOICE_SIM_DONE"
        } catch (error: Throwable) {
            termination = if (error is Stop) {
                error.kind
            } else if (invalid.get() != null) {
                "PATH_INVALID"
            } else {
                "ERROR"
            }
            failure = (error.javaClass.name + ":" + error.message.orEmpty()).take(2048)
        } finally {
            fun cleanupStep(name: String, action: () -> Unit) {
                cleanupRequested[name + "_requested"] = true
                runCatching(action).onFailure { cleanupErrors.add(name + ":" + it.toString().take(512)) }
            }
            activeJob.get()?.let { job -> cleanupStep("job_cancel") { if (job.isActive) job.cancel() } }
            client?.let { c -> cleanupStep("pool_evict") { c.evictConnections() } }
            monitor?.let { m -> cleanupStep("monitor_stop") { m.stop() } }
            bound?.let { b -> cleanupStep("network_release") { b.release() } }

            val cleanupJson = buildJsonObject {
                for ((k, v) in cleanupRequested) put(k, v)
                put("errors", buildJsonArray { cleanupErrors.forEach { add(JsonPrimitive(it)) } })
            }
            val finishedNanos = SystemClock.elapsedRealtimeNanos()
            val rawText = Profile4VoiceCorrelationContract.buildRawPayloadJson(
                spec = spec,
                startedNanos = startedNanos,
                finishedNanos = finishedNanos,
                deadlineNanos = deadlineNanos,
                tsEpochMs = System.currentTimeMillis(),
                deviceModel = Build.MODEL,
                androidApi = Build.VERSION.SDK_INT,
                packageName = packageName,
                versionCode = versionCode,
                versionName = versionName,
                targetApkSha256 = targetApkSha,
                testApkSha256 = testApkSha,
                guardMetadata = guardMeta,
                guardReasons = guardReasons,
                networkHandle = networkHandle,
                networkId = networkId,
                interfaceName = interfaceName,
                localAddresses = localAddresses,
                postNetwork = postNetworkJson,
                phaseEvents = synchronized(events) { events.toList() },
                cleanup = cleanupJson,
                termination = termination,
                failure = failure,
                pathInvalidReason = invalid.get(),
                doneSample = doneSample,
            )
            val file = File(root, "raw.json")
            check(file.createNewFile()) { "RAW_ALREADY_EXISTS" }
            FileOutputStream(file).use {
                it.write(rawText.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        }
        check(
            termination == "VOICE_SIM_DONE" &&
                failure == null &&
                invalid.get() == null &&
                cleanupErrors.isEmpty(),
        ) {
            "VOICE_CORRELATION_PHASE_INVALID:$termination:$failure"
        }
    }

    /** Offline argument, contract, and serialization self-test without network or output directory. */
    @Test
    fun validateOnly() {
        val valid = mapOf(
            "feature_release" to Profile4VoiceCorrelationContract.FEATURE_RELEASE,
            "live_authorized" to "true",
            "condition_id" to "B1",
            "run_id" to "offline-validate",
            "expected_phone_ipv4" to "192.168.137.113",
            "card_sha256" to "a".repeat(64),
            "source_commit" to "b".repeat(40),
            "target_apk_sha256" to "c".repeat(64),
            "test_apk_sha256" to "d".repeat(64),
        )
        val parsed = Profile4VoiceCorrelationContract.parse(valid)
        check(parsed.conditionId == "B1")
        check(parsed.remoteIp == Profile4VoiceCorrelationContract.MIW2_HOST)
        reject("ARBITRARY_URL_FORBIDDEN") {
            Profile4VoiceCorrelationContract.parse(valid + ("url" to "https://example.invalid/"))
        }
        reject("MIW2_HOST_PIN_MISMATCH") {
            Profile4VoiceCorrelationContract.parse(valid + ("fixed_remote_ipv4" to "1.1.1.1"))
        }
        reject("CONDITION_INVALID") {
            Profile4VoiceCorrelationContract.parse(valid + ("condition_id" to "X1"))
        }

        val sample = VoiceRunner.Sample(
            phase = VoiceRunner.Phase.Done,
            rttMs = 45.0,
            jitterMs = 3.0,
            upFrameJitterMs = 6.0,
            downFrameJitterMs = null,
            mouthEarBudgetMs = null,
            framesSent = 150,
            framesRecv = 800,
            progress = 1f,
            ttfbP50Ms = 120.0,
            ttfbP95Ms = 190.0,
            downNetJitterMs = 5.0,
            mouthEarProxyMs = 450.0,
            turnSwitchP50Ms = 150.0,
            bargeStopMaxMs = 95.0,
            turnsOk = 8,
            caliber = VoiceRunner.SIM_CALIBER,
            lowConfidence = false,
            m7MaxFrameGapMs = 40.0,
            voiceNearZeroArrivalRatio = 0.02,
        )
        val scored = checkNotNull(VoiceFormat.scoreSample(sample))
        check(scored.vetoApplied && scored.score != null && scored.score!! <= AqsScorer.T4_VETO_CAP)
        val entity = checkNotNull(VoiceResultRepository.toEntity(sample, 1_784_000_000_000L))
        val exportJson = VoiceFormat.buildJson(listOf(entity))
        check(exportJson.contains("voice-export-1.0"))
        check(exportJson.contains("\"down_frame_jitter_ms\":null"))
    }

    private class Stop(val kind: String) : IOException(kind)

    private fun reject(fragment: String, block: () -> Unit) {
        val err = runCatching(block).exceptionOrNull()
        check(err != null && err.message.orEmpty().contains(fragment)) {
            "EXPECTED_REJECTION:$fragment:$err"
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun rejectUnknownOrVpnNetworks(cm: ConnectivityManager) {
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        check(networks.isNotEmpty()) { "NETWORK_ENUMERATION_EMPTY" }
        networks.forEach {
            check(!checkNotNull(cm.getNetworkCapabilities(it)).hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                "VPN_PRESENT"
            }
        }
    }
}
