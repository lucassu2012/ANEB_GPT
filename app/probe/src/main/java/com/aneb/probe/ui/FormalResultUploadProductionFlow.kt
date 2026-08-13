package com.aneb.probe.ui

import com.aneb.probe.data.ResultEnvelopeEntity
import com.aneb.probe.engine.AnebResultJsonlExporter
import com.aneb.probe.engine.FormalResultEnvelopeSource
import com.aneb.probe.engine.FormalResultUploadCredential
import com.aneb.probe.engine.FormalResultUploadCredentialSource
import com.aneb.probe.engine.FormalResultUploadOutcome
import com.aneb.probe.engine.FormalResultUploadOwner
import com.aneb.probe.engine.FormalResultUploadReceiptStore
import com.aneb.probe.engine.FormalResultUploadSubmitter
import com.aneb.probe.engine.ResultUploadAttemptController
import com.aneb.probe.engine.ResultUploadConsentMutationResult
import com.aneb.probe.privacy.LocalPrivacyOwnedMutationBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class FormalResultUploadConfirmation internal constructor(
    internal val capability: Any,
)

internal sealed interface FormalResultUploadPreparation {
    data class Ready(
        val prompt: String,
        val confirmation: FormalResultUploadConfirmation,
    ) : FormalResultUploadPreparation

    data object Disabled : FormalResultUploadPreparation
    data object ResultNotFound : FormalResultUploadPreparation
    data object ResultUnverifiable : FormalResultUploadPreparation
    data object StateUnavailable : FormalResultUploadPreparation
}

internal sealed interface FormalResultUploadUiReceipt {
    data class Accepted(val httpCode: Int, val message: String) : FormalResultUploadUiReceipt
    data class Rejected(val httpCode: Int, val message: String) : FormalResultUploadUiReceipt
    data class Unavailable(val message: String) : FormalResultUploadUiReceipt
    data object ConfirmationExpired : FormalResultUploadUiReceipt
    data object AlreadyHandled : FormalResultUploadUiReceipt
    data object DeliveryUncertain : FormalResultUploadUiReceipt
}

/**
 * User-confirmed production boundary for one verified result. Preparing is read-only; confirming
 * consumes an opaque capability, freezes the selected envelope and destination, grants only that
 * result ID, and always retires the grant before returning.
 */
internal class FormalResultUploadProductionFlow(
    private val envelopeSource: FormalResultEnvelopeSource,
    private val credentialSource: FormalResultUploadCredentialSource,
    private val attemptController: ResultUploadAttemptController,
    private val submitter: FormalResultUploadSubmitter,
    private val receiptStore: FormalResultUploadReceiptStore,
    private val mutationBoundary: LocalPrivacyOwnedMutationBoundary,
) {
    private val monitor = Any()
    private val pending = mutableMapOf<Any, Snapshot>()

    suspend fun prepare(resultId: String): FormalResultUploadPreparation {
        val credential = readCredential() ?: return FormalResultUploadPreparation.Disabled
        val envelope = readEnvelope(resultId) ?: return FormalResultUploadPreparation.ResultNotFound
        if (envelope.runId != resultId || !isVerifiable(envelope)) {
            return FormalResultUploadPreparation.ResultUnverifiable
        }
        val capability = Any()
        synchronized(monitor) {
            pending[capability] = Snapshot.from(envelope, credential)
        }
        return FormalResultUploadPreparation.Ready(
            prompt = "仅提交这条已验证结果。确认后将发送到 ANEB 服务。",
            confirmation = FormalResultUploadConfirmation(capability),
        )
    }

    fun dismiss(confirmation: FormalResultUploadConfirmation) {
        synchronized(monitor) { pending.remove(confirmation.capability) }
    }

    suspend fun confirm(
        confirmation: FormalResultUploadConfirmation,
    ): FormalResultUploadUiReceipt {
        val snapshot = synchronized(monitor) {
            pending.remove(confirmation.capability)
        } ?: return FormalResultUploadUiReceipt.AlreadyHandled

        val credential = readCredential() ?: return FormalResultUploadUiReceipt.ConfirmationExpired
        val envelope = readEnvelope(snapshot.resultId)
            ?: return FormalResultUploadUiReceipt.ConfirmationExpired
        if (!snapshot.matches(envelope, credential) || !isVerifiable(envelope)) {
            return FormalResultUploadUiReceipt.ConfirmationExpired
        }

        if (attemptController.grantForResult(snapshot.resultId, credential.scope) !=
            ResultUploadConsentMutationResult.COMMITTED
        ) {
            return FormalResultUploadUiReceipt.Unavailable("无法保存本次授权，请稍后重试。")
        }

        val owner = FormalResultUploadOwner(
            envelopeSource = FormalResultEnvelopeSource { requestedId ->
                envelope.takeIf { requestedId == snapshot.resultId }
            },
            credentialSource = FormalResultUploadCredentialSource { credential },
            attemptController = attemptController,
            submitter = submitter,
            receiptStore = receiptStore,
            mutationBoundary = mutationBoundary,
        )
        var cancelled: CancellationException? = null
        val outcome = try {
            owner.upload(snapshot.resultId)
        } catch (caught: CancellationException) {
            cancelled = caught
            null
        } finally {
            withContext(NonCancellable) {
                attemptController.revoke(credential.scope)
            }
        }
        if (cancelled != null) return FormalResultUploadUiReceipt.DeliveryUncertain
        return FormalResultUploadUiMapper.map(checkNotNull(outcome))
    }

    private suspend fun readCredential(): FormalResultUploadCredential? = try {
        credentialSource.current()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun readEnvelope(resultId: String): ResultEnvelopeEntity? = try {
        envelopeSource.byId(resultId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun isVerifiable(envelope: ResultEnvelopeEntity): Boolean = try {
        AnebResultJsonlExporter.verifiedBody(envelope)
        true
    } catch (_: AnebResultJsonlExporter.VerificationException) {
        false
    } catch (_: Exception) {
        false
    }

    private data class Snapshot(
        val resultId: String,
        val canonicalSha256: String,
        val endpoint: String,
        val recipient: String,
        val purpose: String,
        val policyVersion: String,
    ) {
        fun matches(
            envelope: ResultEnvelopeEntity,
            credential: FormalResultUploadCredential,
        ): Boolean = envelope.runId == resultId &&
            envelope.canonicalSha256 == canonicalSha256 &&
            credential.endpoint == endpoint &&
            credential.scope.recipient == recipient &&
            credential.scope.purpose.name == purpose &&
            credential.scope.policyVersion == policyVersion

        companion object {
            fun from(
                envelope: ResultEnvelopeEntity,
                credential: FormalResultUploadCredential,
            ) = Snapshot(
                resultId = envelope.runId,
                canonicalSha256 = envelope.canonicalSha256,
                endpoint = credential.endpoint,
                recipient = credential.scope.recipient,
                purpose = credential.scope.purpose.name,
                policyVersion = credential.scope.policyVersion,
            )
        }
    }
}

internal object FormalResultUploadUiMapper {
    fun map(outcome: FormalResultUploadOutcome): FormalResultUploadUiReceipt = when (outcome) {
        is FormalResultUploadOutcome.Uploaded -> FormalResultUploadUiReceipt.Accepted(
            httpCode = outcome.httpCode,
            message = "已提交：服务器接受了这次请求。",
        )

        is FormalResultUploadOutcome.Rejected -> FormalResultUploadUiReceipt.Rejected(
            httpCode = outcome.httpCode,
            message = "服务器未接受这次请求，请检查授权后再试。",
        )

        FormalResultUploadOutcome.CancelledMayHaveStarted,
        is FormalResultUploadOutcome.ReceiptNotCommitted,
        -> FormalResultUploadUiReceipt.DeliveryUncertain

        FormalResultUploadOutcome.AlreadyClaimed -> FormalResultUploadUiReceipt.AlreadyHandled

        FormalResultUploadOutcome.AuthorizationChangedBeforeStart,
        FormalResultUploadOutcome.DeletionInProgress,
        FormalResultUploadOutcome.DeliveryFailed,
        FormalResultUploadOutcome.Disabled,
        FormalResultUploadOutcome.NotConsented,
        FormalResultUploadOutcome.ResultNotFound,
        FormalResultUploadOutcome.StateUnavailable,
        is FormalResultUploadOutcome.Unverifiable,
        -> FormalResultUploadUiReceipt.Unavailable("暂时无法提交这条结果，请稍后重试。")
    }
}
