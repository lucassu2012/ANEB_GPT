package com.aneb.probe.net

import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer

/*
 * /api/v1/realtime-sim 的 wire 合同（aneb-realtime-session-v1；权威=Codex 树
 * server/handlers_realtime_sim.go + TEST_SERVER_CAPABILITIES §2；D-38）。
 *
 * 合同要点（客户端实现据此，勿凭记忆改）：
 *  - 握手：GET 升级，无 subprotocol、**不得带 Origin 头**（服务端 CheckOrigin 要求无 Origin，
 *    OkHttp 默认不发）；可选 query `controlled_disconnect_after_turn=N`（0≤N<32）。
 *  - 升级后第一条消息=TEXT 完整计划 JSON；服务端双向 DisallowUnknownFields——DTO 字段
 *    必须与合同一字不差，多一个字段整个计划被拒。
 *  - 上行帧 "ANEU"(4B)+u16 BE turn(4:6)+u32 BE seq(6:10)+payload（恰好 uplink_frame_bytes）；
 *    seq 从 0 严格连续，长度不符即致命 "invalid uplink frame"。无逐帧 ack。
 *  - 下行帧 "ANED"(4B)+u16 turn+u32 seq+**u64 BE sched_us(10:18 服务端单调微秒调度戳)**+payload。
 *  - 控制（TEXT）：turn_start/speech_commit/barge_in/ping ↔ session_ready/pong/turn_summary/
 *    session_summary/error。服务端时戳同一单调基准（nowMicros），两两相减免钟偏。
 *  - 受控中断：turn_index==N 轮 summary 发出后服务端裸关 TCP（无 close 帧）→ 客户端走 onFailure。
 */

// ─────────────────────────────────────────────────────────────────────────────
//  纯 JVM wire 编解码 + DTO（无 Android 依赖，可直接单测）
// ─────────────────────────────────────────────────────────────────────────────

object RealtimeWire {

    const val CONTRACT_VERSION = "aneb-realtime-session-v1"

    private const val UP_HEADER = 10 // "ANEU"+u16+u32
    private const val DOWN_HEADER = 18 // "ANED"+u16+u32+u64 sched_us

    /** 上行帧编码（BE；payload 长度须==计划 uplink_frame_bytes，服务端精确校验）。 */
    fun encodeUplink(turnIndex: Int, seq: Int, payload: ByteArray): ByteString =
        ByteBuffer.allocate(UP_HEADER + payload.size)
            .put(byteArrayOf(0x41, 0x4E, 0x45, 0x55)) // "ANEU"
            .putShort(turnIndex.toShort())
            .putInt(seq)
            .put(payload)
            .array().toByteString()

    /** 一个已解码下行帧（arrivalUs=客户端到达打戳，由调用方在回调入口就地采集）。 */
    data class DownFrame(
        val turnIndex: Int,
        val seq: Long,
        /** 服务端单调微秒调度戳（帧头 10:18）——差分可精确剥离服务端调度误差（M2'） */
        val schedUs: Long,
        val payloadLen: Int,
        val arrivalUs: Long,
    )

    /** 下行帧解码；非 ANED/长度非法返回 null（R-10：坏帧不入统计，由调用方记协议错误）。 */
    fun decodeDownlink(bytes: ByteString, arrivalUs: Long): DownFrame? {
        if (bytes.size < DOWN_HEADER) return null
        if (bytes[0] != 0x41.toByte() || bytes[1] != 0x4E.toByte() ||
            bytes[2] != 0x45.toByte() || bytes[3] != 0x44.toByte()
        ) return null // "ANED"
        val buf = bytes.asByteBuffer()
        return DownFrame(
            turnIndex = buf.getShort(4).toInt() and 0xFFFF,
            seq = buf.getInt(6).toLong() and 0xFFFFFFFFL,
            schedUs = buf.getLong(10),
            payloadLen = bytes.size - DOWN_HEADER,
            arrivalUs = arrivalUs,
        )
    }

    // ---- 计划 DTO（字段=合同全集；encodeDefaults 确保零值也落 wire）----

    @Serializable
    data class SessionPlan(
        @SerialName("contract_version") val contractVersion: String = CONTRACT_VERSION,
        @SerialName("session_id") val sessionId: String,
        val seed: Long,
        @SerialName("setup_ms") val setupMs: Double,
        @SerialName("frame_ms") val frameMs: Int,
        val turns: List<TurnPlan>,
    )

    @Serializable
    data class TurnPlan(
        @SerialName("turn_id") val turnId: String,
        @SerialName("turn_index") val turnIndex: Int,
        @SerialName("start_after_previous_ms") val startAfterPreviousMs: Int,
        @SerialName("uplink_frames") val uplinkFrames: Int,
        @SerialName("uplink_frame_bytes") val uplinkFrameBytes: Int,
        @SerialName("response_wait_ms") val responseWaitMs: Int,
        @SerialName("planned_downlink_frames") val plannedDownlinkFrames: Int,
        @SerialName("downlink_frame_bytes") val downlinkFrameBytes: Int,
        val interrupted: Boolean,
        @SerialName("barge_in_after_frames") val bargeInAfterFrames: Int? = null,
        @SerialName("expected_stop_within_ms") val expectedStopWithinMs: Int? = null,
    )

    // ---- 出站控制 DTO ----

    @Serializable
    data class TurnStart(
        val type: String = "turn_start",
        @SerialName("turn_id") val turnId: String,
        @SerialName("turn_index") val turnIndex: Int,
    )

    @Serializable
    data class SpeechCommit(val type: String = "speech_commit", @SerialName("turn_id") val turnId: String)

    @Serializable
    data class BargeIn(val type: String = "barge_in", @SerialName("turn_id") val turnId: String)

    @Serializable
    data class Ping(
        val type: String = "ping",
        @SerialName("ping_id") val pingId: Long,
        @SerialName("client_mono_us") val clientMonoUs: Long,
    )

    // ---- 入站控制 DTO（ignoreUnknownKeys 前向兼容）----

    @Serializable
    data class InboundControl(
        val type: String = "",
        @SerialName("session_id") val sessionId: String? = null,
        @SerialName("ready_us") val readyUs: Long? = null,
        val observed: String? = null,
        val message: String? = null,
        // pong
        @SerialName("ping_id") val pingId: Long? = null,
        @SerialName("client_mono_us") val clientMonoUs: Long? = null,
        @SerialName("t1_us") val t1Us: Long? = null,
        @SerialName("t2_us") val t2Us: Long? = null,
        // turn_summary
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("turn_index") val turnIndex: Int? = null,
        @SerialName("uplink_frames_expected") val uplinkFramesExpected: Int? = null,
        @SerialName("uplink_frames_received") val uplinkFramesReceived: Int? = null,
        @SerialName("downlink_frames_planned") val downlinkFramesPlanned: Int? = null,
        @SerialName("downlink_frames_emitted") val downlinkFramesEmitted: Int? = null,
        @SerialName("commit_recv_us") val commitRecvUs: Long? = null,
        @SerialName("first_downlink_sched_us") val firstDownlinkSchedUs: Long? = null,
        @SerialName("first_downlink_pre_write_us") val firstDownlinkPreWriteUs: Long? = null,
        @SerialName("barge_in_received") val bargeInReceived: Boolean? = null,
        @SerialName("barge_in_recv_us") val bargeInRecvUs: Long? = null,
        @SerialName("stop_ack_us") val stopAckUs: Long? = null,
        @SerialName("protocol_ok") val protocolOk: Boolean? = null,
        // session_summary
        val turns: Int? = null,
        @SerialName("complete_us") val completeUs: Long? = null,
    )

    /** 出站编码：encodeDefaults 确保 type 常量落 wire（服务端按 type 分发）。 */
    val jsonOut = Json { encodeDefaults = true }

    /** 入站解析：ignoreUnknownKeys（服务端未来加字段不破）。 */
    val jsonIn = Json { ignoreUnknownKeys = true }
}

// ─────────────────────────────────────────────────────────────────────────────
//  WebSocket 会话（OkHttp；复用 AnebClient 的 OkHttpClient 继承 NO_PROXY/绑定网/不重试红线）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一次 /realtime-sim 会话的传输壳：回调入口就地打戳（R-07 同轴），入站统一进无界
 * channel（服务端写超时 10s——消费侧不得在收帧循环做重活）；编排/KPI 在 VoiceRunner。
 * 经 [AnebClient.realtimeSim] 构造以复用同一 OkHttpClient（D-16 NO_PROXY / R-01 绑定网 /
 * retryOnConnectionFailure(false) 三红线自动继承，禁止自建 client）。
 */
class RealtimeSimSession internal constructor(
    private val client: OkHttpClient,
    private val url: String,
    private val planJson: String,
) {
    sealed class In {
        data class Text(val arrivalUs: Long, val text: String) : In()
        data class Frame(val arrivalUs: Long, val bytes: ByteString) : In()
    }

    /** 传输层终结（受控中断/网络错误经 onFailure 落此；正常 close 也收口到 channel 关闭）。 */
    class TransportClosed(val atUs: Long, cause: Throwable?) : Exception(cause?.toString() ?: "closed", cause)

    val inbound = Channel<In>(Channel.UNLIMITED)

    /** X-Aneb-Server 指纹（onOpen 采集；缺失/不符 → 调用方 fail-closed 作废本 run）。 */
    @Volatile
    var serverFingerprint: String? = null
        private set

    @Volatile
    var openedAtUs: Long? = null
        private set

    private var ws: WebSocket? = null

    private fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000

    fun connect() {
        // 合同三不要：不加 Origin 头（CheckOrigin 要求无 Origin）、无 subprotocol、
        // 不开 okhttp pingInterval（心跳走 app 层 ping/pong，其时戳参与钟偏映射）。
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedAtUs = nowUs()
                serverFingerprint = response.header("X-Aneb-Server")
                webSocket.send(planJson) // 升级后第一条消息=TEXT 计划
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                inbound.trySend(In.Text(nowUs(), text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbound.trySend(In.Frame(nowUs(), bytes))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 受控中断（服务端裸关 TCP、无 close 帧）与网络错误统一走这里
                inbound.close(TransportClosed(nowUs(), t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbound.close(TransportClosed(nowUs(), null))
            }
        })
    }

    fun sendText(text: String): Boolean = ws?.send(text) == true

    fun sendFrame(bytes: ByteString): Boolean = ws?.send(bytes) == true

    /** OkHttp 出站队列积压字节（>0 时本轮上行打戳含背压，调用方记 lowConfidence）。 */
    fun queueSize(): Long = ws?.queueSize() ?: 0L

    fun cancel() {
        ws?.cancel()
        inbound.close()
    }
}
