package com.aneb.probe.engine

/**
 * Keeps a session-scoped client tied to a renewable transport lease.
 *
 * Fixed WIFI/CELLULAR measurements must never silently fall back to the default network after
 * Android invalidates the originally bound [lease].  The caller marks that lease unusable, and
 * the next session acquires the same requested transport before creating a fresh client.
 * AUTO mode uses [refreshEnabled] = false and a null lease, so Android remains responsible for
 * routing and this helper never invents a binding.
 */
internal class RefreshingSessionResource<L : Any, C : Any>(
    initialLease: L?,
    private val refreshEnabled: Boolean,
    private val isUsable: (L) -> Boolean,
    private val acquire: suspend () -> L,
    private val release: (L) -> Unit,
    private val create: (L?) -> C,
) {
    data class Resolution<C>(
        val resource: C,
        val generation: Int,
        val refreshed: Boolean,
    )

    private var lease: L? = initialLease
    private var resource: C = create(initialLease)
    private var closed = false
    private var generation = if (initialLease == null) 0 else 1

    suspend fun forSession(): Resolution<C> {
        check(!closed) { "session_resource_closed" }
        val current = lease
        if (refreshEnabled && (current == null || !isUsable(current))) {
            if (current != null) {
                release(current)
                lease = null
            }
            val replacement = acquire()
            lease = replacement
            resource = create(replacement)
            generation += 1
            return Resolution(resource, generation, refreshed = true)
        }
        return Resolution(resource, generation, refreshed = false)
    }

    fun close() {
        if (closed) return
        closed = true
        lease?.let(release)
        lease = null
    }
}
