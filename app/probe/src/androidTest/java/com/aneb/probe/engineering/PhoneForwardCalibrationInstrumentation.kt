package com.aneb.probe.engineering

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.aneb.probe.BuildConfig
import com.aneb.probe.net.BoundNetwork
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.PathMonitor
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Private receiver only: no business App, upload, scoring, retry, or network-setting operation. */
@RunWith(AndroidJUnit4::class)
class PhoneForwardCalibrationInstrumentation {
    @Test fun runSinglePhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val input = KEYS.associateWith { arguments.getString(it) }
        require(BuildConfig.DEBUG) { "DEBUG_REQUIRED" }
        require(!arguments.containsKey("url")) { "ARBITRARY_URL_FORBIDDEN" }
        val spec = parse(input)
        val context = instrumentation.targetContext
        val parent = File(checkNotNull(context.getExternalFilesDir(null)), "phone-forward-calibration")
        check(parent.isDirectory || parent.mkdirs()) { "OUTPUT_PARENT_UNAVAILABLE" }
        val root = File(parent, spec.runId)
        check(root.mkdir()) { "CALIBRATION_RUN_ALREADY_CONSUMED" }
        val started = SystemClock.elapsedRealtimeNanos()
        val deadline = started + 30_000_000_000L
        val state = ReadState()
        val invalid = AtomicReference<String?>(null)
        val currentCall = AtomicReference<Call?>(null)
        val currentDns = AtomicReference<DnsAttempt?>(null)
        val socket = AtomicReference<JSONObject?>(null)
        val events = ArrayList<JSONObject>()
        val connects = AtomicInteger()
        val requests = AtomicInteger()
        var bound: BoundNetwork? = null
        var monitor: PathMonitor? = null
        var client: OkHttpClient? = null
        var response: Response? = null
        var failure: String? = null
        var termination = "NOT_DISPATCHED"
        val cleanup = JSONObject()
        val cleanupErrors = ArrayList<String>()
        val raw = JSONObject().put("format", "phone-forward-calibration-raw-1")
            .put("run_id", spec.runId).put("phase", spec.phase).put("url", URL)
            .put("declared_card_sha256", spec.cardSha).put("declared_source_commit", spec.sourceCommit)
            .put("clock", "android_elapsed_realtime_nanos").put("started_nanos", started)
            .put("byte_limit", BYTES).put("absolute_deadline_nanos", deadline)
            .put("device_model", Build.MODEL).put("android_api", Build.VERSION.SDK_INT)
            .put("business_sample", false).put("network_qualified", false)
        fun event(name: String, detail: String = "") = synchronized(events) {
            if (events.size >= 64) { invalid.compareAndSet(null, "EVENT_LIMIT"); currentCall.get()?.cancel() }
            else events.add(JSONObject().put("name", name).put("nanos", SystemClock.elapsedRealtimeNanos()).put("detail", detail.take(1024)))
        }
        fun admitted() {
            invalid.get()?.let { throw Stop("PATH_INVALID:$it") }
            if (SystemClock.elapsedRealtimeNanos() >= deadline) throw Stop("TIME_LIMIT")
        }
        try {
            val actualTargetSha = sha(File(context.applicationInfo.sourceDir))
            val actualTestSha = sha(File(instrumentation.context.applicationInfo.sourceDir))
            raw.put("target_apk_sha256", actualTargetSha).put("test_apk_sha256", actualTestSha)
            check(actualTargetSha == spec.targetSha && actualTestSha == spec.testSha) { "APK_PIN_MISMATCH" }
            admitted()
            @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName, 0)
            raw.put("package", context.packageName).put("version_code", info.longVersionCode).put("version_name", info.versionName)
            val cm = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
            rejectUnknownOrVpnNetworks(cm)
            admitted()
            val guard = NetGuard.guardCheck(context)
            raw.put("guard", JSONObject(guard.metadata)).put("guard_reasons", JSONArray(guard.reasons))
            check(guard.ok) { "NETWORK_GUARD_REJECTED" }
            admitted()
            val acquired = runBlocking { NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_WIFI,
                minOf(15_000L, TimeUnit.NANOSECONDS.toMillis(deadline - SystemClock.elapsedRealtimeNanos())).coerceAtLeast(1)) }
            bound = acquired
            admitted()
            val link = checkNotNull(cm.getLinkProperties(acquired.network))
            val localIps = link.linkAddresses.map { it.address.hostAddress.orEmpty() }
            check(cm.activeNetwork == acquired.network && link.interfaceName == "wlan0" && spec.phoneIp in localIps) { "WIFI_IDENTITY_MISMATCH" }
            val caps = checkNotNull(cm.getNetworkCapabilities(acquired.network))
            check(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { "WIFI_NOT_READY" }
            check(link.httpProxy == null && cm.defaultProxy == null) { "PROXY_PRESENT" }
            raw.put("network_handle", acquired.network.networkHandle.toString()).put("network_id", acquired.network.toString())
                .put("interface", link.interfaceName).put("local_addresses", JSONArray(localIps))
            monitor = PathMonitor(context, acquired, onEvent = { event(it.type.name, "${it.tsNanos}:${it.detail}") },
                onInvalidate = { reason -> invalid.compareAndSet(null, reason); currentDns.get()?.cancel("PATH_INVALID:$reason"); currentCall.get()?.cancel() })
            monitor.start()
            admitted()
            val remote = if (spec.phase == "reference") {
                val dns = DnsAttempt()
                currentDns.set(dns)
                admitted()
                event("BOUND_DNS_START")
                val answers = dns.resolve(acquired.network, deadline)
                raw.put("reference_ipv4_answers", JSONArray(answers.take(16).map { it.hostAddress }))
                event("BOUND_DNS_END")
                admitted()
                checkNotNull(answers.firstOrNull()) { "NO_IPV4_ANSWER" }
            } else ipv4(checkNotNull(spec.remoteIp))
            check(!remote.isAnyLocalAddress && !remote.isLoopbackAddress && !remote.isLinkLocalAddress &&
                !remote.isSiteLocalAddress && !remote.isMulticastAddress) { "REMOTE_NOT_PUBLIC_IPV4" }
            val remoteIp = checkNotNull(remote.hostAddress)
            raw.put("fixed_remote_ipv4", remoteIp).put("remote_origin", if (spec.phase == "reference") "BOUND_DNS_FIRST_IPV4" else "REFERENCE_CARD_PIN")
            client = OkHttpClient.Builder().socketFactory(acquired.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        check(hostname == HOST) { "UNEXPECTED_DNS_HOST" }
                        return listOf(remote)
                    }
                })
                .proxy(Proxy.NO_PROXY).protocols(listOf(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .callTimeout(maxOf(1, TimeUnit.NANOSECONDS.toMillis(deadline - SystemClock.elapsedRealtimeNanos())), TimeUnit.MILLISECONDS)
                .addNetworkInterceptor { chain ->
                    if (requests.incrementAndGet() != 1) throw Stop("MULTIPLE_REQUESTS_FORBIDDEN")
                    event("HTTP_REQUEST_PERMITTED")
                    val reply = chain.proceed(chain.request())
                    raw.put("http_code", reply.code).put("http_protocol", reply.protocol.toString())
                        .put("content_length", reply.body?.contentLength() ?: JSONObject.NULL)
                        .put("content_encoding", reply.header("Content-Encoding") ?: JSONObject.NULL)
                        .put("edge_colo", reply.header("CF-Ray")?.substringAfterLast('-', "")
                            ?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: JSONObject.NULL)
                    // Reject before OkHttp can interpret e.g.503/Retry-After as a follow-up.
                    if (reply.code != 200) { chain.call().cancel(); reply.close(); throw Stop("HTTP_STATUS_${reply.code}") }
                    reply
                }
                .eventListener(object : EventListener() {
                    override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {
                        event("CONNECT_START", address.toString())
                        if (connects.incrementAndGet() != 1) { invalid.compareAndSet(null, "MULTIPLE_CONNECTS"); call.cancel() }
                    }
                    override fun connectionAcquired(call: Call, connection: Connection) {
                        val s = connection.socket()
                        val local = s.localAddress?.hostAddress
                        val peer = s.inetAddress?.hostAddress
                        socket.set(JSONObject().put("local_ip", local).put("local_port", s.localPort).put("remote_ip", peer).put("remote_port", s.port))
                        event("CONNECTION_ACQUIRED")
                        if (local != spec.phoneIp || peer != remoteIp || s.inetAddress !is Inet4Address || s.port != 443) {
                            invalid.compareAndSet(null, "SOCKET_IDENTITY_MISMATCH"); call.cancel()
                        }
                    }
                    override fun responseHeadersEnd(call: Call, response: Response) { event("RESPONSE_HEADERS_END") }
                    override fun callEnd(call: Call) { event("CALL_END") }
                    override fun callFailed(call: Call, ioe: IOException) { event("CALL_FAILED", ioe.toString()) }
                }).build()
            admitted()
            val call = client.newCall(Request.Builder().url(URL).header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache, no-store").get().build())
            currentCall.set(call)
            admitted()
            event("DISPATCH")
            termination = "DISPATCHED"
            val received = call.execute()
            response = received
            raw.put("http_code", received.code).put("http_protocol", received.protocol.toString())
                .put("content_length", received.body?.contentLength() ?: JSONObject.NULL)
                .put("content_encoding", received.header("Content-Encoding") ?: JSONObject.NULL)
            admitted()
            check(socket.get() != null && connects.get() == 1) { "SOCKET_EVIDENCE_MISSING" }
            check(received.code == 200 && received.request.url.toString() == URL && received.body?.contentLength() == BYTES.toLong()) { "HTTP_CONTRACT_MISMATCH" }
            check(received.header("Content-Encoding").let { it == null || it.equals("identity", true) }) { "COMPRESSED_RESPONSE" }
            readBounded(checkNotNull(received.body).source(), state, deadline, SystemClock::elapsedRealtimeNanos, ::admitted)
            admitted()
            rejectUnknownOrVpnNetworks(cm)
            val postLink = checkNotNull(cm.getLinkProperties(acquired.network)) { "POST_LINK_MISSING" }
            val postCaps = checkNotNull(cm.getNetworkCapabilities(acquired.network)) { "POST_CAPABILITIES_MISSING" }
            val postAddresses = postLink.linkAddresses.map { it.address.hostAddress.orEmpty() }
            raw.put("post_network", JSONObject().put("observed_nanos", SystemClock.elapsedRealtimeNanos())
                .put("network_handle", acquired.network.networkHandle.toString()).put("default_network_id", cm.activeNetwork?.toString() ?: JSONObject.NULL)
                .put("interface", postLink.interfaceName).put("local_addresses", JSONArray(postAddresses))
                .put("wifi", postCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .put("validated", postCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                .put("not_suspended", postCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
                .put("vpn", postCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))
            check(cm.activeNetwork == acquired.network && postLink.interfaceName == "wlan0" && spec.phoneIp in postAddresses &&
                postCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && postCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                postCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) && !postCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                postLink.httpProxy == null && NetGuard.guardCheck(context).ok && !monitor.invalidated) { "POST_PATH_INVALID" }
            admitted()
            termination = "EXPECTED_LENGTH_RECEIVED"
        } catch (error: Throwable) {
            termination = if (error is Stop) error.kind else if (invalid.get() != null) "PATH_INVALID" else "ERROR"
            failure = (error.javaClass.name + ":" + error.message.orEmpty()).take(2048)
        } finally {
            fun cleanupStep(name: String, action: () -> Unit) {
                cleanup.put(name + "_requested", true)
                runCatching(action).onFailure { cleanupErrors.add(name + ":" + it.toString().take(512)) }
            }
            currentCall.get()?.let { cleanupStep("call_cancel") { it.cancel() } }
            currentDns.get()?.let { dns -> cleanupStep("dns_executor_stop") {
                if (!dns.close(deadline)) cleanupErrors.add("dns_executor_not_terminated_at_original_deadline")
            } }
            response?.let { cleanupStep("response_close") { it.close() } }
            client?.let { cleanupStep("pool_evict") { it.connectionPool.evictAll() } }
            monitor?.let { cleanupStep("monitor_stop") { it.stop() } }
            bound?.let { cleanupStep("network_release") { it.release() } }
            cleanup.put("callback_unregistration_observed", JSONObject.NULL).put("errors", JSONArray(cleanupErrors))
            state.last?.let { if (state.points.lastOrNull()?.optLong("bytes") != state.bytes)
                state.points.add(JSONObject().put("nanos", it).put("bytes", state.bytes)) }
            raw.put("termination", termination).put("failure", failure ?: JSONObject.NULL).put("path_invalid_reason", invalid.get() ?: JSONObject.NULL)
                .put("body_bytes", state.bytes).put("first_body_read_nanos", state.first ?: JSONObject.NULL)
                .put("last_body_read_nanos", state.last ?: JSONObject.NULL).put("points", JSONArray(state.points))
                .put("socket", socket.get() ?: JSONObject.NULL).put("connect_attempts", connects.get())
                .put("bound_dns", currentDns.get()?.evidence() ?: JSONObject.NULL)
                .put("http_request_permit_attempts", requests.get())
                .put("events", synchronized(events) { JSONArray(events.toList()) }).put("cleanup", cleanup)
                .put("finished_nanos", SystemClock.elapsedRealtimeNanos())
            val valid = termination == "EXPECTED_LENGTH_RECEIVED" && failure == null && invalid.get() == null && cleanupErrors.isEmpty()
            val duration = state.last?.let { last -> state.first?.let { first -> last - first } }
            raw.put("receiver_body_window_bytes_per_second", if (valid && duration != null && duration > 0 && state.firstBytes != null)
                (state.bytes - checkNotNull(state.firstBytes)) * 1_000_000_000.0 / duration else JSONObject.NULL)
            val file = File(root, "raw.json")
            check(file.createNewFile()) { "RAW_ALREADY_EXISTS" }
            FileOutputStream(file).use { it.write((raw.toString(2) + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
        check(termination == "EXPECTED_LENGTH_RECEIVED" && failure == null && invalid.get() == null && cleanupErrors.isEmpty()) { "CALIBRATION_PHASE_INVALID:$termination:$failure" }
    }

    /** No network request, DNS, real Call, network binding or output directory is created. */
    @Test fun validateOnly() {
        val valid = mapOf("feature_release" to RELEASE, "live_authorized" to "true", "phase" to "reference", "run_id" to "offline",
            "expected_phone_ipv4" to "192.168.137.113", "card_sha256" to "a".repeat(64), "source_commit" to "b".repeat(40),
            "target_apk_sha256" to "c".repeat(64), "test_apk_sha256" to "d".repeat(64))
        check(parse(valid).phase == "reference")
        reject("RELEASE") { parse(valid + ("live_authorized" to "false")) }
        reject("PHASE") { parse(valid + ("phase" to "upload")) }
        reject("REMOTE") { parse(valid + ("phase" to "limited")) }
        reject("IPV4") { parse(valid + ("expected_phone_ipv4" to "localhost")) }
        reject("RUN_ID") { parse(valid + ("run_id" to "..")) }
        reject("ARBITRARY_URL") { parse(valid + ("url" to "https://example.invalid/")) }
        val extra = Buffer().write(ByteArray(BYTES + 16)); val exact = ReadState(); var clock = 1L
        readBounded(extra, exact, Long.MAX_VALUE, { clock++ })
        check(exact.bytes == BYTES.toLong() && extra.size == 16L)
        val short = ReadState()
        reject("TRUNCATED") { readBounded(Buffer().writeByte(1), short, Long.MAX_VALUE, { clock++ }) }
        check(short.bytes == 1L)
        val partial = ReadState()
        val throwing = object : Source {
            var total = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (total >= 65_536) throw IOException("TEST_SOURCE_ERROR")
                val n = minOf(byteCount, 8192L, 65_536 - total)
                sink.write(ByteArray(n.toInt())); total += n; return n
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        reject("TEST_SOURCE_ERROR") { readBounded(throwing, partial, Long.MAX_VALUE, { clock++ }) }
        check(partial.bytes == 65_536L)
        val timed = ReadState(); val times = listOf(1L, 4L).iterator()
        reject("TIME_LIMIT") { readBounded(Buffer().write(ByteArray(8192)), timed, 3L, { times.next() }) }
        check(timed.bytes == 8192L)
    }

    /** One public asynchronous A query. The only waiter uses the original phase deadline. */
    private class DnsAttempt {
        private data class Result(val addresses: List<InetAddress>, val rcode: Int?, val errorCode: Int?, val error: String?, val callbackNanos: Long)
        private val result = AtomicReference<Result?>(null)
        private val cancellation = CancellationSignal()
        private val cancelReason = AtomicReference<String?>(null)
        private val cancelError = AtomicReference<String?>(null)
        private val latch = CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ANEB-calibration-DNS").apply { isDaemon = true }
        }
        private var invoked = false
        private var started: Long? = null
        private var waitReturned: Boolean? = null
        private var executorStopped: Boolean? = null
        fun cancel(reason: String) {
            cancelReason.compareAndSet(null, reason)
            try { cancellation.cancel() }
            catch (error: Throwable) { cancelError.compareAndSet(null, error.toString().take(1024)) }
            finally { latch.countDown() }
        }
        fun resolve(network: Network, deadline: Long): List<Inet4Address> {
            if (cancelReason.get() != null) throw Stop("DNS_CANCELLED")
            if (SystemClock.elapsedRealtimeNanos() >= deadline) throw Stop("TIME_LIMIT")
            started = SystemClock.elapsedRealtimeNanos()
            invoked = true
            DnsResolver.getInstance().query(network, HOST, DnsResolver.TYPE_A, DnsResolver.FLAG_EMPTY,
                executor, cancellation, object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        result.compareAndSet(null, Result(answer.toList(), rcode, null, null, SystemClock.elapsedRealtimeNanos()))
                        latch.countDown()
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        result.compareAndSet(null, Result(emptyList(), null, error.code, error.toString().take(1024), SystemClock.elapsedRealtimeNanos()))
                        latch.countDown()
                    }
                })
            val remaining = deadline - SystemClock.elapsedRealtimeNanos()
            waitReturned = remaining > 0 && latch.await(remaining, TimeUnit.NANOSECONDS)
            if (waitReturned != true || SystemClock.elapsedRealtimeNanos() >= deadline) {
                cancel("TIME_LIMIT"); throw Stop("TIME_LIMIT")
            }
            if (cancelReason.get() != null) throw Stop("DNS_CANCELLED")
            val answer = checkNotNull(result.get()) { "DNS_CALLBACK_MISSING" }
            if (answer.error != null) throw Stop("DNS_ERROR:${answer.errorCode}:${answer.error}")
            if (answer.rcode != 0) throw Stop("DNS_RCODE:${answer.rcode}")
            return answer.addresses.filterIsInstance<Inet4Address>().also { if (it.isEmpty()) throw Stop("DNS_EMPTY_IPV4") }
        }
        fun close(deadline: Long): Boolean {
            if (result.get() == null) cancel("CLEANUP")
            executor.shutdownNow()
            executorStopped = executor.awaitTermination(maxOf(0L, deadline - SystemClock.elapsedRealtimeNanos()), TimeUnit.NANOSECONDS)
            return executorStopped == true
        }
        fun evidence(): JSONObject {
            val answer = result.get()
            return JSONObject().put("api", "android_dnsresolver_query_A").put("hostname", HOST)
                .put("type", DnsResolver.TYPE_A).put("flags", DnsResolver.FLAG_EMPTY).put("query_invoked", invoked)
                .put("started_nanos", started ?: JSONObject.NULL).put("wait_returned", waitReturned ?: JSONObject.NULL)
                .put("callback_nanos", answer?.callbackNanos ?: JSONObject.NULL).put("rcode", answer?.rcode ?: JSONObject.NULL)
                .put("error_code", answer?.errorCode ?: JSONObject.NULL).put("error", answer?.error ?: JSONObject.NULL)
                .put("answers", if (answer == null) JSONObject.NULL else JSONArray(answer.addresses.take(16).map { it.hostAddress }))
                .put("answer_count", answer?.addresses?.size ?: JSONObject.NULL)
                .put("cancel_requested", cancelReason.get() != null).put("cancel_reason", cancelReason.get() ?: JSONObject.NULL)
                .put("cancel_error", cancelError.get() ?: JSONObject.NULL)
                .put("signal_is_cancelled", cancellation.isCanceled).put("os_cancel_observed", JSONObject.NULL)
                .put("executor_terminated_observed", executorStopped ?: JSONObject.NULL)
        }
    }
    private data class Spec(val runId: String, val phase: String, val phoneIp: String, val remoteIp: String?,
        val cardSha: String, val sourceCommit: String, val targetSha: String, val testSha: String)
    private class Stop(val kind: String) : IOException(kind)
    private class ReadState {
        var bytes = 0L; var first: Long? = null; var firstBytes: Long? = null; var last: Long? = null
        var milestone = 65_536L
        val points = ArrayList<JSONObject>()
        fun observed(n: Long, at: Long) {
            bytes += n
            if (first == null) { first = at; firstBytes = bytes; points.add(JSONObject().put("nanos", at).put("bytes", bytes)) }
            val previous = last; last = at
            if (bytes >= milestone || bytes == BYTES.toLong()) {
                points.add(JSONObject().put("nanos", at).put("bytes", bytes)); milestone = (bytes / 65_536 + 1) * 65_536
            }
            if (at <= 0 || (previous != null && at < previous)) throw Stop("CLOCK_INVALID")
        }
    }
    private fun readBounded(source: BufferedSource, state: ReadState, deadline: Long, now: () -> Long, admitted: () -> Unit = {}) {
        val discard = Buffer()
        while (state.bytes < BYTES) {
            admitted()
            if (now() >= deadline) throw Stop("TIME_LIMIT")
            val n = source.read(discard, minOf(8192L, BYTES - state.bytes))
            if (n < 0) throw Stop("TRUNCATED")
            if (n == 0L) throw Stop("ZERO_LENGTH_READ")
            discard.skip(n); state.observed(n, now())
            if (checkNotNull(state.last) >= deadline) throw Stop("TIME_LIMIT")
        }
    }
    private fun parse(a: Map<String, String?>): Spec {
        require(!a.containsKey("url")) { "ARBITRARY_URL_FORBIDDEN" }
        require(a["feature_release"] == RELEASE && a["live_authorized"] == "true") { "RELEASE_REQUIRED" }
        val phase = a["phase"]; require(phase in setOf("reference", "limited", "recovery")) { "PHASE_INVALID" }
        val run = a["run_id"]; require(run != null && run.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "RUN_ID_INVALID" }
        val phone = a["expected_phone_ipv4"]; require(phone != null) { "IPV4_REQUIRED" }; ipv4(phone)
        val remote = a["fixed_remote_ipv4"]
        require(if (phase == "reference") remote == null else remote != null) { "REMOTE_PIN_REQUIRED_OR_REFERENCE_CONFLICT" }
        remote?.let { ipv4(it) }
        fun hex(key: String, size: Int): String { val value = a[key]; require(value != null && value.matches(Regex("[0-9a-f]{$size}"))) { "${key}_INVALID" }; return value }
        return Spec(run, checkNotNull(phase), phone, remote, hex("card_sha256", 64), hex("source_commit", 40), hex("target_apk_sha256", 64), hex("test_apk_sha256", 64))
    }
    private fun ipv4(raw: String): Inet4Address {
        require(raw.matches(Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))) { "IPV4_LITERAL_REQUIRED" }
        val bytes = raw.split('.').map { it.toInt().also { n -> require(n in 0..255) { "IPV4_RANGE" } }.toByte() }.toByteArray()
        return InetAddress.getByAddress(bytes) as Inet4Address // Never invokes DNS.
    }
    private fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65_536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun rejectUnknownOrVpnNetworks(cm: ConnectivityManager) {
        @Suppress("DEPRECATION") val networks = cm.allNetworks // Read failures propagate; no empty-list fallback.
        check(networks.isNotEmpty()) { "NETWORK_ENUMERATION_EMPTY" }
        networks.forEach { check(!checkNotNull(cm.getNetworkCapabilities(it)).hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { "VPN_PRESENT" } }
    }
    private fun reject(fragment: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull(); check(error != null && error.message.orEmpty().contains(fragment)) { "EXPECTED_REJECTION:$fragment:$error" }
    }
    companion object {
        private const val RELEASE = "phone-forward-calibration-1"
        private const val HOST = "speed.cloudflare.com"
        private const val BYTES = 2_097_152
        private const val URL = "https://speed.cloudflare.com/__down?bytes=2097152"
        private val KEYS = listOf("feature_release", "live_authorized", "phase", "run_id", "expected_phone_ipv4",
            "fixed_remote_ipv4", "card_sha256", "source_commit", "target_apk_sha256", "test_apk_sha256")
    }
}
