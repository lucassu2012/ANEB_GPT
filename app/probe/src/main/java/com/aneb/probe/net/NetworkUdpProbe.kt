package com.aneb.probe.net

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NetworkUdpProbeResult(
    val packetsSent: Int,
    val receivedSeqs: List<Int>,
    val rttMs: List<Double>,
    val error: String?,
)

/** 带序号的 UDP 应用探针；0 回包报告不可达，不冒充 IP 层丢包率。 */
class NetworkUdpProbe(private val bound: BoundNetwork?) {
    suspend fun run(
        serverBase: String,
        runId: String,
        packets: Int,
        packetBytes: Int,
        ratePerSecond: Int,
    ): NetworkUdpProbeResult =
        withContext(Dispatchers.IO) {
            UUID.fromString(runId)
            require(packets > 0 && packetBytes in MIN_PACKET_BYTES..MAX_PACKET_BYTES && ratePerSecond in 1..200)
            execute(serverBase, runId, packets, packetBytes, ratePerSecond)
        }

    suspend fun runLegacy(
        serverBase: String,
        packets: Int,
        packetBytes: Int,
        ratePerSecond: Int,
    ): NetworkUdpProbeResult = withContext(Dispatchers.IO) {
        require(packets > 0 && packetBytes in LEGACY_MIN_PACKET_BYTES..MAX_PACKET_BYTES && ratePerSecond in 1..200)
        execute(serverBase, null, packets, packetBytes, ratePerSecond)
    }

    private suspend fun execute(
        serverBase: String,
        runId: String?,
        packets: Int,
        packetBytes: Int,
        ratePerSecond: Int,
    ): NetworkUdpProbeResult {
        val uri = URI(serverBase)
        val host = requireNotNull(uri.host) { "udp_host_missing" }
        val address = resolve(host)
        val socket = DatagramSocket(null)
        return try {
            bound?.network?.bindSocket(socket)
            socket.bind(InetSocketAddress(0))
            socket.connect(InetSocketAddress(address, UDP_PORT))
            socket.soTimeout = RECEIVE_POLL_MS
            exchange(socket, runId, packets, packetBytes, ratePerSecond)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkUdpProbeResult(0, emptyList(), emptyList(), "${e.javaClass.simpleName}:${e.message.orEmpty()}")
        } finally {
            socket.close()
        }
    }

    private suspend fun exchange(
        socket: DatagramSocket,
        runId: String?,
        packets: Int,
        packetBytes: Int,
        ratePerSecond: Int,
    ): NetworkUdpProbeResult = coroutineScope {
        val sentAt = ConcurrentHashMap<Int, Long>()
        val received = ConcurrentLinkedQueue<Int>()
        val rtts = ConcurrentLinkedQueue<Double>()
        val receiver = launch(Dispatchers.IO) {
            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (isActive && !socket.isClosed) {
                try {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    socket.receive(datagram)
                    val seq = if (runId == null) {
                        NetworkUdpPacket.decodeLegacy(datagram.data, datagram.length)?.seq
                    } else {
                        NetworkUdpPacket.decode(datagram.data, datagram.length)
                            ?.takeIf { it.runId == runId }
                            ?.seq
                    } ?: continue
                    val sent = sentAt[seq] ?: continue
                    received.add(seq)
                    rtts.add((SystemClock.elapsedRealtimeNanos() - sent) / 1e6)
                } catch (_: SocketTimeoutException) {
                    // Periodic cancellation/deadline check.
                } catch (e: SocketException) {
                    if (!socket.isClosed) throw e
                    break
                }
            }
        }
        val intervalMs = (1_000.0 / ratePerSecond).toLong().coerceAtLeast(1L)
        var sent = 0
        try {
            repeat(packets) { seq ->
                val at = SystemClock.elapsedRealtimeNanos()
                val bytes = if (runId == null) {
                    NetworkUdpPacket.encodeLegacy(seq, at, packetBytes)
                } else {
                    NetworkUdpPacket.encode(runId, seq, at, packetBytes)
                }
                sentAt[seq] = at
                socket.send(DatagramPacket(bytes, bytes.size))
                sent++
                if (seq + 1 < packets) delay(intervalMs)
            }
            delay(RECEIVE_GRACE_MS)
        } finally {
            socket.close()
            receiver.join()
        }
        val seqs = received.toList()
        NetworkUdpProbeResult(
            packetsSent = sent,
            receivedSeqs = seqs,
            rttMs = rtts.toList(),
            error = if (sent > 0 && seqs.isEmpty()) "no_response_udp_$UDP_PORT" else null,
        )
    }

    private fun resolve(host: String): InetAddress =
        bound?.network?.getAllByName(host)?.firstOrNull() ?: InetAddress.getAllByName(host).first()

    companion object {
        const val UDP_PORT = 8443
        private const val LEGACY_MIN_PACKET_BYTES = 17
        private const val MIN_PACKET_BYTES = 33
        private const val MAX_PACKET_BYTES = 512
        private const val RECEIVE_POLL_MS = 200
        private const val RECEIVE_GRACE_MS = 1_500L
    }
}

internal object NetworkUdpPacket {
    private val magic = byteArrayOf('A'.code.toByte(), 'N'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), '2'.code.toByte())
    private val legacyMagic = byteArrayOf('A'.code.toByte(), 'N'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())

    data class Decoded(val runId: String, val seq: Int, val sentAtNanos: Long)
    data class LegacyDecoded(val seq: Int, val sentAtNanos: Long)

    fun encodeLegacy(seq: Int, sentAtNanos: Long, size: Int): ByteArray {
        require(size >= 17)
        val bytes = ByteArray(size) { index -> ((seq * 31 + index * 17) and 0xff).toByte() }
        legacyMagic.copyInto(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            position(5)
            putInt(seq)
            putLong(sentAtNanos)
        }
        return bytes
    }

    fun encode(runId: String, seq: Int, sentAtNanos: Long, size: Int): ByteArray {
        require(size >= 33)
        val runUuid = UUID.fromString(runId)
        val bytes = ByteArray(size) { index -> ((seq * 31 + index * 17) and 0xff).toByte() }
        magic.copyInto(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            position(5)
            putLong(runUuid.mostSignificantBits)
            putLong(runUuid.leastSignificantBits)
            putInt(seq)
            putLong(sentAtNanos)
        }
        return bytes
    }

    fun decode(bytes: ByteArray, length: Int): Decoded? {
        if (length < 33 || magic.indices.any { bytes[it] != magic[it] }) return null
        return ByteBuffer.wrap(bytes, 5, 28).order(ByteOrder.BIG_ENDIAN).let {
            val runId = UUID(it.long, it.long).toString()
            Decoded(runId, it.int, it.long)
        }
    }

    fun decodeLegacy(bytes: ByteArray, length: Int): LegacyDecoded? {
        if (length < 17 || legacyMagic.indices.any { bytes[it] != legacyMagic[it] }) return null
        return ByteBuffer.wrap(bytes, 5, 12).order(ByteOrder.BIG_ENDIAN).let {
            LegacyDecoded(it.int, it.long)
        }
    }
}
