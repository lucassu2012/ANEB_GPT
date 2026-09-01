package com.aneb.probe.prototype

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Process-local authority that distinguishes an explicit Prototype user cancel from host teardown. */
internal class PrototypeUserCancellationAuthority :
    AbstractCoroutineContextElement(PrototypeUserCancellationAuthority) {
    companion object Key : CoroutineContext.Key<PrototypeUserCancellationAuthority>

    private val requested = AtomicBoolean(false)

    fun request(): Boolean = requested.compareAndSet(false, true)

    fun isRequested(): Boolean = requested.get()
}

/** Carries the validated stream prefix observed when an authorized user cancellation arrived. */
internal class PrototypeRunCancellationObservation(
    val evidence: PrototypeInterruptedStreamEvidence?,
    cause: CancellationException,
) : CancellationException(cause.message) {
    init {
        initCause(cause)
    }
}

/** Carries a canonical partial campaign from the runner to the persistence boundary. */
internal class PrototypeCampaignCancelledWithResult(
    val result: PrototypeQuickCampaignRunner.CampaignResult,
    cause: CancellationException,
) : RuntimeException(cause.message, cause)

/** Confirms that the cancellation result was durably stored before the owner publishes terminal UI. */
internal class PrototypeCampaignCancellationPersisted(
    val result: PrototypeQuickCampaignRunner.CampaignResult,
    cause: PrototypeCampaignCancelledWithResult,
) : RuntimeException(cause.message, cause)
