package com.aneb.probe.research.engineering

/** Debug-only scoped idle policy; only the external Configurator accessors are replaceable. */
class PublicUiIdleTimeoutScope(
    private val readTimeoutMillis: () -> Long,
    private val writeTimeoutMillis: (Long) -> Unit,
) {
    private val lock = Any()

    fun <T> run(body: () -> T): T = synchronized(lock) {
        val original = readTimeoutMillis()
        var primary: Throwable? = null
        try {
            writeTimeoutMillis(0L)
            body()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                writeTimeoutMillis(original)
            } catch (restoration: Throwable) {
                val rejected = primary
                if (rejected == null) throw restoration // A returned body cannot conceal failed restoration.
                if (restoration !== rejected) rejected.addSuppressed(restoration)
            }
        }
    }
}
