package com.aneb.probe.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface DurableResultStore<T> {
    suspend fun insert(result: T)
}

/** Publishes a terminal result only after its durable store has accepted it. */
internal class DurableResultCommitter<T>(
    private val store: DurableResultStore<T>,
    private val publish: (T) -> Unit,
) {
    suspend fun commit(result: T) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                store.insert(result)
            } catch (error: CancellationException) {
                throw error
            }
            publish(result)
        }
    }
}
