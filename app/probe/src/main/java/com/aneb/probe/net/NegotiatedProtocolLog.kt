package com.aneb.probe.net

/**
 * 逐样本协商协议账本（A-8⑦／REVIEW §7.1「`AnebClient` 逐样本记 `response.protocol` 与
 * `X-Aneb-Proto` 上报（additive）」；D-806）。
 *
 * 线程安全：OkHttp 的响应回调跑在 dispatcher 线程上，多条并发响应会同时写这里。
 */
class NegotiatedProtocolLog {

    private val lock = Any()
    private val byProtocol = HashMap<String, Long>()
    private val byProtoHeader = HashMap<String, Long>()
    private var headerAbsent: Long = 0L
    private var total: Long = 0L

    /**
     * 记一条响应。
     *
     * @param protocol OkHttp 协商到的协议（`Response.protocol.toString()`，如 `http/1.1`、`h2`）。
     * @param protoHeader 服务端 `X-Aneb-Proto` 头（缺席传 null，不要传空串）。
     */
    fun observe(protocol: String, protoHeader: String?) {
        synchronized(lock) {
            total++
            byProtocol[protocol] = (byProtocol[protocol] ?: 0L) + 1L
            if (protoHeader == null) {
                headerAbsent++
            } else {
                byProtoHeader[protoHeader] = (byProtoHeader[protoHeader] ?: 0L) + 1L
            }
        }
    }

    /**
     * 取一份不可变快照。按键排序，保证同样的数据产出一致的 JSON。
     */
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            samples = total,
            byProtocol = byProtocol.toSortedMap(),
            byProtoHeader = byProtoHeader.toSortedMap(),
            headerAbsent = headerAbsent,
        )
    }

    data class Snapshot(
        val samples: Long,
        val byProtocol: Map<String, Long>,
        val byProtoHeader: Map<String, Long>,
        val headerAbsent: Long,
    )
}
