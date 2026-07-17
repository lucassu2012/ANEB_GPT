package com.aneb.probe.net

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

@Serializable
data class RealtimeSessionWirePlan(
    @SerialName("contract_version") val contractVersion: String = "aneb-realtime-session-v1",
    @SerialName("session_id") val sessionId: String,
    val seed: Long,
    @SerialName("setup_ms") val setupMs: Double,
    @SerialName("frame_ms") val frameMs: Int,
    val turns: List<RealtimeTurnWirePlan>,
)

@Serializable
data class RealtimeTurnWirePlan(
    @SerialName("turn_id") val turnId: String,
    @SerialName("turn_index") val turnIndex: Int,
    @SerialName("start_after_previous_ms") val startAfterPreviousMs: Double,
    @SerialName("uplink_frames") val uplinkFrames: Int,
    @SerialName("uplink_frame_bytes") val uplinkFrameBytes: Int,
    @SerialName("response_wait_ms") val responseWaitMs: Double,
    @SerialName("planned_downlink_frames") val plannedDownlinkFrames: Int,
    @SerialName("downlink_frame_bytes") val downlinkFrameBytes: Int,
    val interrupted: Boolean,
    @SerialName("barge_in_after_frames") val bargeInAfterFrames: Int? = null,
    @SerialName("expected_stop_within_ms") val expectedStopWithinMs: Int? = null,
)

@Serializable
private data class RealtimeControlWire(
    val type: String,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("turn_index") val turnIndex: Int = 0,
    @SerialName("ping_id") val pingId: Long = 0,
    @SerialName("client_mono_us") val clientMonoUs: Long = 0,
)

@Serializable
data class RealtimeReadyWire(
    val type: String,
    @SerialName("contract_version") val contractVersion: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("ready_us") val readyUs: Long,
    val observed: String,
)

@Serializable
private data class RealtimePongWire(
    val type: String,
    @SerialName("ping_id") val pingId: Long,
    @SerialName("client_mono_us") val clientMonoUs: Long,
    @SerialName("t1_us") val t1Us: Long,
    @SerialName("t2_us") val t2Us: Long,
)

@Serializable
data class RealtimeTurnSummaryWire(
    val type: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("turn_index") val turnIndex: Int,
    @SerialName("uplink_frames_expected") val uplinkFramesExpected: Int,
    @SerialName("uplink_frames_received") val uplinkFramesReceived: Int,
    @SerialName("downlink_frames_planned") val downlinkFramesPlanned: Int,
    @SerialName("downlink_frames_emitted") val downlinkFramesEmitted: Int,
    @SerialName("commit_recv_us") val commitRecvUs: Long,
    @SerialName("first_downlink_sched_us") val firstDownlinkSchedUs: Long,
    @SerialName("first_downlink_pre_write_us") val firstDownlinkPreWriteUs: Long,
    @SerialName("barge_in_received") val bargeInReceived: Boolean,
    @SerialName("barge_in_recv_us") val bargeInRecvUs: Long = 0,
    @SerialName("stop_ack_us") val stopAckUs: Long = 0,
    @SerialName("protocol_ok") val protocolOk: Boolean,
)

@Serializable
data class RealtimeSessionSummaryWire(
    val type: String,
    @SerialName("session_id") val sessionId: String,
    val turns: Int,
    @SerialName("protocol_ok") val protocolOk: Boolean,
    @SerialName("complete_us") val completeUs: Long,
)

data class RealtimeRttSample(
    val rttUs: Long,
    val offsetUs: Long,
)

data class RealtimeDownlinkFrame(
    val turnIndex: Int,
    val seq: Int,
    val schedUs: Long,
    val payloadBytes: Int,
    val arrivalNanos: Long,
)

data class RealtimeTurnWireResult(
    val plan: RealtimeTurnWirePlan,
    val commitSentNanos: Long,
    val uplinkBytesAccepted: Long,
    val uplinkStartNanos: Long?,
    val uplinkEndNanos: Long?,
    val downlinkFrames: List<RealtimeDownlinkFrame>,
    val bargeSentNanos: Long?,
    val summary: RealtimeTurnSummaryWire?,
    val summaryArrivalNanos: Long?,
)

data class RealtimeSessionWireResult(
    val connectStartNanos: Long,
    val openNanos: Long?,
    val ready: RealtimeReadyWire?,
    val readyArrivalNanos: Long?,
    val rttSamples: List<RealtimeRttSample>,
    val turns: List<RealtimeTurnWireResult>,
    val summary: RealtimeSessionSummaryWire?,
    val error: String?,
    val endNanos: Long,
)

data class RealtimeWireCallbacks(
    val onClockSync: (samples: List<RealtimeRttSample>) -> Unit = {},
    val onUplink: (turnIndex: Int, bytes: Long, nowNanos: Long) -> Unit = { _, _, _ -> },
    val onTurnCommitted: (turnIndex: Int, nowNanos: Long) -> Unit = { _, _ -> },
    val onDownlink: (frame: RealtimeDownlinkFrame) -> Unit = {},
    val onBargeIn: (turnIndex: Int, nowNanos: Long) -> Unit = { _, _ -> },
)

class RealtimeSimulationWire(private val client: AnebClient) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun runSession(
        url: String,
        plan: RealtimeSessionWirePlan,
        startAfterPreviousMs: Double,
        callbacks: RealtimeWireCallbacks = RealtimeWireCallbacks(),
    ): RealtimeSessionWireResult {
        if (startAfterPreviousMs > 0) delay(startAfterPreviousMs.toLong())
        val events = Channel<WireEvent>(Channel.UNLIMITED)
        val connectStart = SystemClock.elapsedRealtimeNanos()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.trySend(WireEvent.Open(SystemClock.elapsedRealtimeNanos()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(WireEvent.Text(text, SystemClock.elapsedRealtimeNanos()))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.trySend(WireEvent.Binary(bytes, SystemClock.elapsedRealtimeNanos()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(WireEvent.Failure("${t.javaClass.simpleName}:${t.message}", response?.code))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(WireEvent.Closed(code, reason))
            }
        }
        var socket: WebSocket? = null
        var openNanos: Long? = null
        var ready: RealtimeReadyWire? = null
        var readyArrival: Long? = null
        val rtt = mutableListOf<RealtimeRttSample>()
        val turnResults = mutableListOf<RealtimeTurnWireResult>()
        var sessionSummary: RealtimeSessionSummaryWire? = null
        return try {
            val activeSocket = client.openWebSocket(url, listener)
            socket = activeSocket
            openNanos = when (val event = receiveEvent(events)) {
                is WireEvent.Open -> event.atNanos
                is WireEvent.Failure -> return failed(connectStart, event.error)
                else -> return failed(connectStart, "websocket_open_protocol_error")
            }
            check(activeSocket.send(json.encodeToString(RealtimeSessionWirePlan.serializer(), plan))) { "session_plan_send_failed" }
            val readyEvent = awaitTextType(events, "session_ready")
            ready = json.decodeFromString(RealtimeReadyWire.serializer(), readyEvent.text)
            readyArrival = readyEvent.atNanos
            require(ready.contractVersion == "aneb-realtime-session-v1" && ready.sessionId == plan.sessionId) { "session_ready_identity_mismatch" }

            repeat(INITIAL_PINGS) { index ->
                val t0Us = SystemClock.elapsedRealtimeNanos() / 1_000L
                check(activeSocket.send(json.encodeToString(RealtimeControlWire.serializer(), RealtimeControlWire("ping", pingId = index.toLong(), clientMonoUs = t0Us)))) { "ping_send_failed" }
                val pongEvent = awaitTextType(events, "pong")
                val t3Us = pongEvent.atNanos / 1_000L
                val pong = json.decodeFromString(RealtimePongWire.serializer(), pongEvent.text)
                if (pong.pingId == index.toLong() && pong.clientMonoUs == t0Us) {
                    rtt += RealtimeRttSample(
                        rttUs = (t3Us - t0Us) - (pong.t2Us - pong.t1Us),
                        offsetUs = ((pong.t1Us - t0Us) + (pong.t2Us - t3Us)) / 2,
                    )
                }
            }
            callbacks.onClockSync(rtt.toList())

            plan.turns.forEach { turn ->
                if (turn.startAfterPreviousMs > 0) delay(turn.startAfterPreviousMs.toLong())
                check(activeSocket.send(controlJson("turn_start", turn))) { "turn_start_send_failed" }
                val uplinkStart = SystemClock.elapsedRealtimeNanos()
                var acceptedBytes = 0L
                repeat(turn.uplinkFrames) { seq ->
                    paceFrom(uplinkStart, seq.toLong() * plan.frameMs * 1_000_000L)
                    val frame = encodeUplink(turn.turnIndex, seq, turn.uplinkFrameBytes)
                    check(activeSocket.send(frame.toByteString())) { "uplink_frame_send_failed" }
                    acceptedBytes += turn.uplinkFrameBytes
                    callbacks.onUplink(turn.turnIndex, acceptedBytes, SystemClock.elapsedRealtimeNanos())
                }
                val uplinkEnd = SystemClock.elapsedRealtimeNanos()
                check(activeSocket.send(controlJson("speech_commit", turn))) { "speech_commit_send_failed" }
                val commitSent = SystemClock.elapsedRealtimeNanos()
                callbacks.onTurnCommitted(turn.turnIndex, commitSent)
                val downlink = mutableListOf<RealtimeDownlinkFrame>()
                var bargeSent: Long? = null
                var turnSummary: RealtimeTurnSummaryWire? = null
                var summaryArrival: Long? = null
                while (turnSummary == null) {
                    when (val event = receiveEvent(events)) {
                        is WireEvent.Binary -> {
                            val frame = decodeDownlink(event.bytes, event.atNanos)
                            require(frame.turnIndex == turn.turnIndex) { "downlink_turn_mismatch" }
                            downlink += frame
                            callbacks.onDownlink(frame)
                            val bargeAt = turn.bargeInAfterFrames
                            if (turn.interrupted && bargeSent == null && bargeAt != null && downlink.size >= bargeAt) {
                                check(activeSocket.send(controlJson("barge_in", turn))) { "barge_in_send_failed" }
                                bargeSent = SystemClock.elapsedRealtimeNanos()
                                callbacks.onBargeIn(turn.turnIndex, checkNotNull(bargeSent))
                            }
                        }
                        is WireEvent.Text -> when (textType(event.text)) {
                            "turn_summary" -> {
                                turnSummary = json.decodeFromString(RealtimeTurnSummaryWire.serializer(), event.text)
                                summaryArrival = event.atNanos
                            }
                            "error" -> error("realtime_node_error:${event.text}")
                            else -> error("unexpected_realtime_text:${textType(event.text)}")
                        }
                        is WireEvent.Failure -> error(event.error)
                        is WireEvent.Closed -> error("websocket_closed:${event.code}:${event.reason}")
                        is WireEvent.Open -> error("duplicate_websocket_open")
                    }
                }
                turnResults += RealtimeTurnWireResult(
                    turn, commitSent, acceptedBytes, uplinkStart, uplinkEnd,
                    downlink, bargeSent, turnSummary, summaryArrival,
                )
            }
            val summaryEvent = awaitTextType(events, "session_summary")
            sessionSummary = json.decodeFromString(RealtimeSessionSummaryWire.serializer(), summaryEvent.text)
            activeSocket.close(1000, "complete")
            RealtimeSessionWireResult(
                connectStart, openNanos, ready, readyArrival, rtt, turnResults, sessionSummary, null,
                SystemClock.elapsedRealtimeNanos(),
            )
        } catch (e: CancellationException) {
            socket?.cancel()
            throw e
        } catch (e: Exception) {
            socket?.cancel()
            RealtimeSessionWireResult(
                connectStart, openNanos, ready, readyArrival, rtt, turnResults, sessionSummary, e.toString(),
                SystemClock.elapsedRealtimeNanos(),
            )
        } finally {
            events.close()
        }
    }

    private suspend fun awaitTextType(events: Channel<WireEvent>, expected: String): WireEvent.Text {
        while (true) {
            when (val event = receiveEvent(events)) {
                is WireEvent.Text -> {
                    val type = textType(event.text)
                    if (type == expected) return event
                    if (type == "error") error("realtime_node_error:${event.text}")
                    error("unexpected_realtime_text:$type")
                }
                is WireEvent.Failure -> error(event.error)
                is WireEvent.Closed -> error("websocket_closed:${event.code}:${event.reason}")
                else -> error("unexpected_realtime_event")
            }
        }
    }

    private fun controlJson(type: String, turn: RealtimeTurnWirePlan): String =
        json.encodeToString(
            RealtimeControlWire.serializer(),
            RealtimeControlWire(type, turnId = turn.turnId, turnIndex = turn.turnIndex),
        )

    private suspend fun receiveEvent(events: Channel<WireEvent>): WireEvent =
        withTimeout(EVENT_TIMEOUT_MS) { events.receive() }

    private fun textType(text: String): String =
        json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content

    private fun encodeUplink(turn: Int, seq: Int, payloadBytes: Int): ByteArray =
        ByteBuffer.allocate(10 + payloadBytes).order(ByteOrder.BIG_ENDIAN)
            .put("ANEU".toByteArray(Charsets.US_ASCII))
            .putShort(turn.toShort())
            .putInt(seq)
            .put(ByteArray(payloadBytes) { index -> ((turn * 31 + seq * 17 + index) and 0xff).toByte() })
            .array()

    private fun decodeDownlink(bytes: ByteString, arrivalNanos: Long): RealtimeDownlinkFrame {
        val raw = bytes.toByteArray()
        require(raw.size >= 18 && raw.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "ANED") { "bad_downlink_frame" }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        return RealtimeDownlinkFrame(
            turnIndex = buffer.short.toInt() and 0xffff,
            seq = buffer.int,
            schedUs = buffer.long,
            payloadBytes = raw.size - 18,
            arrivalNanos = arrivalNanos,
        )
    }

    private suspend fun paceFrom(startNanos: Long, offsetNanos: Long) {
        val remaining = startNanos + offsetNanos - SystemClock.elapsedRealtimeNanos()
        if (remaining > 0) delay((remaining + 999_999L) / 1_000_000L)
    }

    private fun failed(start: Long, error: String) = RealtimeSessionWireResult(
        start, null, null, null, emptyList(), emptyList(), null, error, SystemClock.elapsedRealtimeNanos(),
    )

    private sealed interface WireEvent {
        data class Open(val atNanos: Long) : WireEvent
        data class Text(val text: String, val atNanos: Long) : WireEvent
        data class Binary(val bytes: ByteString, val atNanos: Long) : WireEvent
        data class Failure(val error: String, val code: Int?) : WireEvent
        data class Closed(val code: Int, val reason: String) : WireEvent
    }

    private companion object {
        const val INITIAL_PINGS = 5
        const val EVENT_TIMEOUT_MS = 30_000L
    }
}
