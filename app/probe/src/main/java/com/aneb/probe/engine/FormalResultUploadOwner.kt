package com.aneb.probe.engine

import com.aneb.probe.data.ResultEnvelopeEntity
import com.aneb.probe.net.AnebClient
import com.aneb.probe.privacy.LocalPrivacyOwnedMutationBoundary
import com.aneb.probe.privacy.LocalPrivacyOwnedMutationResult
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun interface FormalResultEnvelopeSource {
    suspend fun byId(resultId: String): ResultEnvelopeEntity?
}

internal fun interface FormalResultUploadCredentialSource {
    suspend fun current(): FormalResultUploadCredential?
}

internal fun interface FormalResultUploadSubmitter {
    suspend fun submit(request: FormalResultUploadRequest): FormalResultDelivery
}

internal fun interface FormalResultUploadReceiptStore {
    suspend fun commit(receipt: FormalResultUploadReceipt): Boolean
}

internal class FormalResultUploadCredential(
    endpoint: String,
    internal val bearerToken: String,
    val scope: ResultUploadConsentScope,
) {
    val endpoint: String

    init {
        val parsed = endpoint.toHttpUrlOrNull()
        require(parsed != null) { "formal_result_upload_endpoint_invalid" }
        require(parsed.scheme == "https") { "formal_result_upload_endpoint_not_https" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "formal_result_upload_endpoint_userinfo_forbidden"
        }
        require(parsed.encodedPath == "/api/v1/results") {
            "formal_result_upload_endpoint_path_invalid"
        }
        require(parsed.query == null && parsed.fragment == null) {
            "formal_result_upload_endpoint_metadata_forbidden"
        }
        require(BEARER.matches(bearerToken)) { "formal_result_upload_bearer_invalid" }
        this.endpoint = parsed.toString()
    }

    override fun toString(): String = "FormalResultUploadCredential(redacted)"

    private companion object {
        val BEARER = Regex("^[A-Fa-f0-9]{64}$")
    }
}

internal class FormalResultUploadRequest(
    val endpoint: String,
    val bodyJson: String,
    internal val bearerToken: String,
    val idempotencyKey: String,
) {
    init {
        require(RESULT_ID.matches(idempotencyKey)) { "formal_result_upload_idempotency_key_invalid" }
    }

    override fun toString(): String = "FormalResultUploadRequest(redacted)"

    private companion object {
        val RESULT_ID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[47][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
    }
}

internal sealed interface FormalResultDelivery {
    data class Accepted(val httpCode: Int) : FormalResultDelivery {
        init {
            require(httpCode in 200..299) { "formal_result_upload_accepted_code_invalid" }
        }
    }

    data class Rejected(val httpCode: Int) : FormalResultDelivery {
        init {
            require(httpCode in 300..599) { "formal_result_upload_rejected_code_invalid" }
        }
    }

    data object Failed : FormalResultDelivery
}

internal enum class FormalResultUploadReceiptTerminal {
    ACCEPTED,
    REJECTED,
    FAILED,
}

internal data class FormalResultUploadReceipt(
    val resultId: String,
    val terminal: FormalResultUploadReceiptTerminal,
    val httpCode: Int?,
)

internal sealed interface FormalResultUploadOutcome {
    data object Disabled : FormalResultUploadOutcome
    data object DeletionInProgress : FormalResultUploadOutcome
    data object ResultNotFound : FormalResultUploadOutcome
    data class Unverifiable(val kind: AnebResultJsonlExporter.RejectionKind) : FormalResultUploadOutcome
    data object NotConsented : FormalResultUploadOutcome
    data object AlreadyClaimed : FormalResultUploadOutcome
    data object AuthorizationChangedBeforeStart : FormalResultUploadOutcome
    data object StateUnavailable : FormalResultUploadOutcome
    data object CancelledMayHaveStarted : FormalResultUploadOutcome
    data class Uploaded(val httpCode: Int) : FormalResultUploadOutcome
    data class Rejected(val httpCode: Int) : FormalResultUploadOutcome
    data object DeliveryFailed : FormalResultUploadOutcome
    data class ReceiptNotCommitted(val delivery: FormalResultDelivery) : FormalResultUploadOutcome
}

internal class AnebClientFormalResultUploadSubmitter(
    private val client: AnebClient,
) : FormalResultUploadSubmitter {
    override suspend fun submit(request: FormalResultUploadRequest): FormalResultDelivery {
        val response = client.submitResult(
            url = request.endpoint,
            jsonBody = request.bodyJson,
            bearerToken = request.bearerToken,
            idempotencyKey = request.idempotencyKey,
        )
        val code = response.httpCode ?: return FormalResultDelivery.Failed
        return if (code in 200..299) {
            FormalResultDelivery.Accepted(code)
        } else {
            FormalResultDelivery.Rejected(code)
        }
    }
}

internal class FormalResultUploadOwner(
    private val envelopeSource: FormalResultEnvelopeSource,
    private val attemptController: ResultUploadAttemptController,
    private val submitter: FormalResultUploadSubmitter,
    private val receiptStore: FormalResultUploadReceiptStore,
    private val mutationBoundary: LocalPrivacyOwnedMutationBoundary,
    private val credentialSource: FormalResultUploadCredentialSource =
        FormalResultUploadCredentialSource { null },
) {
    suspend fun upload(resultId: String): FormalResultUploadOutcome =
        when (val execution = mutationBoundary.executeSuspending { uploadOwned(resultId) }) {
            is LocalPrivacyOwnedMutationResult.Completed -> execution.value
            LocalPrivacyOwnedMutationResult.DeletionInProgress ->
                FormalResultUploadOutcome.DeletionInProgress
        }

    private suspend fun uploadOwned(resultId: String): FormalResultUploadOutcome {
        val credential = try {
            credentialSource.current()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return FormalResultUploadOutcome.StateUnavailable
        } ?: return FormalResultUploadOutcome.Disabled

        val envelope = try {
            envelopeSource.byId(resultId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return FormalResultUploadOutcome.StateUnavailable
        } ?: return FormalResultUploadOutcome.ResultNotFound
        if (envelope.runId != resultId) {
            return FormalResultUploadOutcome.Unverifiable(
                AnebResultJsonlExporter.RejectionKind.INTEGRITY,
            )
        }

        val bodyJson = try {
            AnebResultJsonlExporter.verifiedBody(envelope)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: AnebResultJsonlExporter.VerificationException) {
            return FormalResultUploadOutcome.Unverifiable(error.kind)
        } catch (_: Exception) {
            return FormalResultUploadOutcome.StateUnavailable
        }
        val request = FormalResultUploadRequest(
            endpoint = credential.endpoint,
            bodyJson = bodyJson,
            bearerToken = credential.bearerToken,
            idempotencyKey = envelope.runId,
        )
        val attempt = attemptController.runAttempt(
            resultId = envelope.runId,
            scope = credential.scope,
            prepare = {},
            execute = {
                try {
                    submitter.submit(request)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    FormalResultDelivery.Failed
                }
            },
            finalize = { delivery ->
                if (receiptStore.commit(delivery.toReceipt(envelope.runId))) {
                    ResultUploadFinalStatusCommit.COMMITTED
                } else {
                    ResultUploadFinalStatusCommit.NOT_COMMITTED
                }
            },
        )
        return attempt.toOutcome()
    }

    private fun FormalResultDelivery.toReceipt(resultId: String): FormalResultUploadReceipt =
        when (this) {
            is FormalResultDelivery.Accepted -> FormalResultUploadReceipt(
                resultId = resultId,
                terminal = FormalResultUploadReceiptTerminal.ACCEPTED,
                httpCode = httpCode,
            )

            is FormalResultDelivery.Rejected -> FormalResultUploadReceipt(
                resultId = resultId,
                terminal = FormalResultUploadReceiptTerminal.REJECTED,
                httpCode = httpCode,
            )

            FormalResultDelivery.Failed -> FormalResultUploadReceipt(
                resultId = resultId,
                terminal = FormalResultUploadReceiptTerminal.FAILED,
                httpCode = null,
            )
        }

    private fun ResultUploadAttemptResult<FormalResultDelivery>.toOutcome(): FormalResultUploadOutcome =
        when (this) {
            is ResultUploadAttemptResult.Completed -> value.toOutcome()
            is ResultUploadAttemptResult.FinalStatusPersistenceFailed ->
                FormalResultUploadOutcome.ReceiptNotCommitted(value)

            is ResultUploadAttemptResult.NotAdmitted -> when (decision) {
                ResultUploadAdmissionDecision.NotConsented -> FormalResultUploadOutcome.NotConsented
                ResultUploadAdmissionDecision.AlreadyClaimed -> FormalResultUploadOutcome.AlreadyClaimed
                ResultUploadAdmissionDecision.AuthorizationChanged ->
                    FormalResultUploadOutcome.AuthorizationChangedBeforeStart

                ResultUploadAdmissionDecision.NotConfigured,
                ResultUploadAdmissionDecision.PersistenceFailed,
                is ResultUploadAdmissionDecision.Permitted,
                -> FormalResultUploadOutcome.StateUnavailable
            }

            ResultUploadAttemptResult.RevokedBeforeStart,
            ResultUploadAttemptResult.AuthorizationChangedBeforeStart,
            -> FormalResultUploadOutcome.AuthorizationChangedBeforeStart

            ResultUploadAttemptResult.CancelledMayHaveStarted ->
                FormalResultUploadOutcome.CancelledMayHaveStarted

            ResultUploadAttemptResult.UploadStateUnavailable ->
                FormalResultUploadOutcome.StateUnavailable

            else -> error("formal_result_upload_attempt_result_invalid")
        }

    private fun FormalResultDelivery.toOutcome(): FormalResultUploadOutcome = when (this) {
        is FormalResultDelivery.Accepted -> FormalResultUploadOutcome.Uploaded(httpCode)
        is FormalResultDelivery.Rejected -> FormalResultUploadOutcome.Rejected(httpCode)
        FormalResultDelivery.Failed -> FormalResultUploadOutcome.DeliveryFailed
    }
}
