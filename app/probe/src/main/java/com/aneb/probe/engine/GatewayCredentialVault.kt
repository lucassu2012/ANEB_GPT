package com.aneb.probe.engine

import java.util.UUID

/** Process-memory, one-time handoff for debug lab credentials. Handles, never tokens, cross an Intent. */
internal object GatewayCredentialVault {
    private const val MAX_ENTRIES = 8
    private const val MAX_AGE_NANOS = 60_000_000_000L

    private data class Entry(val token: String, val createdNanos: Long)
    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun put(token: String, nowNanos: Long = System.nanoTime()): String {
        require(token.matches(Regex("^[A-Fa-f0-9]{64}$"))) { "gateway_token_must_be_64_hex" }
        purge(nowNanos)
        while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        val handle = UUID.randomUUID().toString()
        entries[handle] = Entry(token, nowNanos)
        return handle
    }

    @Synchronized
    fun take(handle: String?, nowNanos: Long = System.nanoTime()): String? {
        purge(nowNanos)
        if (handle == null) return null
        return entries.remove(handle)?.token
    }

    @Synchronized
    fun discard(handle: String?) {
        if (handle != null) entries.remove(handle)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    private fun purge(nowNanos: Long) {
        entries.entries.removeAll { nowNanos - it.value.createdNanos !in 0..MAX_AGE_NANOS }
    }
}
