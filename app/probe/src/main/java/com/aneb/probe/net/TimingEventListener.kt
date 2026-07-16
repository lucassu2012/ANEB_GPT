package com.aneb.probe.net

import android.os.SystemClock
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 单次 HTTP call 的传输层计时点集合。
 * 全部用 [SystemClock.elapsedRealtimeNanos]（设计文档 §4.1），未发生的事件保持 null
 * （失败语义 R-10：绝不写 0 或哨兵值）。
 */
data class TimingRecord(
    var callStartNs: Long? = null,
    var dnsStartNs: Long? = null,
    var dnsEndNs: Long? = null,
    var connectStartNs: Long? = null,
    var secureConnectStartNs: Long? = null,
    var secureConnectEndNs: Long? = null,
    var connectEndNs: Long? = null,
    var requestHeadersEndNs: Long? = null,
    var requestBodyEndNs: Long? = null,
    var responseHeadersStartNs: Long? = null,
    var responseHeadersEndNs: Long? = null,
) {
    /** 以 callStart 为原点的毫秒偏移摘要，供屏幕日志输出。 */
    fun summarize(): String {
        val origin = callStartNs ?: return "timing: callStart missing"
        fun rel(ns: Long?): String = if (ns == null) "null" else "%.2fms".format((ns - origin) / 1e6)
        return "timing(rel callStart): dnsStart=${rel(dnsStartNs)} dnsEnd=${rel(dnsEndNs)} " +
            "connStart=${rel(connectStartNs)} tlsStart=${rel(secureConnectStartNs)} " +
            "tlsEnd=${rel(secureConnectEndNs)} connEnd=${rel(connectEndNs)} " +
            "reqHdrEnd=${rel(requestHeadersEndNs)} reqBodyEnd=${rel(requestBodyEndNs)} " +
            "respHdrStart=${rel(responseHeadersStartNs)} respHdrEnd=${rel(responseHeadersEndNs)}"
    }
}

/**
 * OkHttp EventListener：在回调线程就地打戳（不经主线程），写入本 call 专属的 [TimingRecord]。
 */
class TimingEventListener(private val record: TimingRecord) : EventListener() {

    private fun now(): Long = SystemClock.elapsedRealtimeNanos()

    override fun callStart(call: Call) {
        record.callStartNs = now()
    }

    override fun dnsStart(call: Call, domainName: String) {
        record.dnsStartNs = now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        record.dnsEndNs = now()
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        record.connectStartNs = now()
    }

    override fun secureConnectStart(call: Call) {
        record.secureConnectStartNs = now()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        record.secureConnectEndNs = now()
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        record.connectEndNs = now()
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        record.requestHeadersEndNs = now()
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        record.requestBodyEndNs = now()
    }

    override fun responseHeadersStart(call: Call) {
        record.responseHeadersStartNs = now()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        record.responseHeadersEndNs = now()
    }

    /**
     * 每个 call 一份 TimingRecord；调用方在 call 结束后用 [recordFor] 取走（取走即移除，防泄漏）。
     */
    class Factory : EventListener.Factory {
        private val records = ConcurrentHashMap<Call, TimingRecord>()

        override fun create(call: Call): EventListener {
            val record = TimingRecord()
            records[call] = record
            return TimingEventListener(record)
        }

        fun recordFor(call: Call): TimingRecord? = records.remove(call)
    }
}
