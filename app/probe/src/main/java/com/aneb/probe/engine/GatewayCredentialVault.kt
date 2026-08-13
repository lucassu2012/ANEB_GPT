package com.aneb.probe.engine

import java.util.UUID

/** Process-memory, one-time handoff for debug lab credentials. Handles, never tokens, cross an Intent. */
internal object GatewayCredentialVault {
    private const val MAX_ENTRIES = 8
    private const val MAX_AGE_NANOS = 60_000_000_000L

    private data class Entry(
        val token: String,
        val createdNanos: Long,
        val reservationHandle: String?,
    )
    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun put(token: String, nowNanos: Long = System.nanoTime()): String {
        return put(token, reservationHandle = null, nowNanos = nowNanos)
    }

    @Synchronized
    fun putForReservation(
        token: String,
        reservationHandle: String,
        nowNanos: Long = System.nanoTime(),
    ): String {
        require(reservationHandle.isNotBlank()) { "reservation_handle_required" }
        return put(token, reservationHandle, nowNanos)
    }

    private fun put(token: String, reservationHandle: String?, nowNanos: Long): String {
        require(token.matches(Regex("^[A-Fa-f0-9]{64}$"))) { "gateway_token_must_be_64_hex" }
        purge(nowNanos)
        while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        val handle = UUID.randomUUID().toString()
        entries[handle] = Entry(token, nowNanos, reservationHandle)
        return handle
    }

    @Synchronized
    fun take(handle: String?, nowNanos: Long = System.nanoTime()): String? {
        return take(handle, reservationHandle = null, nowNanos = nowNanos)
    }

    @Synchronized
    fun takeForReservation(
        handle: String?,
        reservationHandle: String,
        nowNanos: Long = System.nanoTime(),
    ): String? = take(handle, reservationHandle, nowNanos)

    private fun take(handle: String?, reservationHandle: String?, nowNanos: Long): String? {
        purge(nowNanos)
        if (handle == null) return null
        val entry = entries[handle] ?: return null
        if (entry.reservationHandle != reservationHandle) return null
        entries.remove(handle)
        return entry.token
    }

    @Synchronized
    fun discard(handle: String?) {
        if (handle != null) entries.remove(handle)
    }

    @Synchronized
    fun discardForReservation(handle: String?, reservationHandle: String?) {
        if (handle == null) return
        val entry = entries[handle] ?: return
        if (entry.reservationHandle == null || entry.reservationHandle == reservationHandle) {
            entries.remove(handle)
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun clearForPrivacyDeletion(): Int = entries.size.also { entries.clear() }

    @Synchronized
    fun ownedReferenceCount(): Int = entries.size

    @Synchronized
    private fun purge(nowNanos: Long) {
        entries.entries.removeAll { nowNanos - it.value.createdNanos !in 0..MAX_AGE_NANOS }
    }
}
