package com.aneb.probe.engine

import kotlinx.coroutines.CancellationException

internal data class ResultSubmissionRequest(
    val resultId: String,
    val scope: ResultUploadConsentScope,
    val jsonBody: String,
)

internal fun interface ResultSubmissionTransport {
    suspend fun submit(request: ResultSubmissionRequest): ResultUploadTransportOutcome
}

internal data class ResultSubmissionAdapter(
    val requestFactory: () -> ResultSubmissionRequest,
    val transportFactory: () -> ResultSubmissionTransport,
)

internal sealed interface ResultUploadTransportOutcome {
    data class Accepted(val statusCode: Int) : ResultUploadTransportOutcome {
        init {
            require(statusCode in 200..299) { "result_upload_status_not_2xx" }
        }
    }

    data class Rejected4xx(val statusCode: Int) : ResultUploadTransportOutcome {
        init {
            require(statusCode in 400..499) { "result_upload_status_not_4xx" }
        }
    }

    data class Rejected5xx(val statusCode: Int) : ResultUploadTransportOutcome {
        init {
            require(statusCode in 500..599) { "result_upload_status_not_5xx" }
        }
    }

    data object IoFailure : ResultUploadTransportOutcome
}

internal sealed interface ResultSubmissionOutcome {
    val reportStatus: String

    data object NotConsented : ResultSubmissionOutcome {
        override val reportStatus: String = "not_consented"
    }

    data object LocalPersistenceFailed : ResultSubmissionOutcome {
        override val reportStatus: String = "local_persistence_failed"
    }

    data object NotConfigured : ResultSubmissionOutcome {
        override val reportStatus: String = "upload_not_configured"
    }

    data object AlreadyClaimed : ResultSubmissionOutcome {
        override val reportStatus: String = "upload_already_claimed"
    }

    data object AdmissionPersistenceFailed : ResultSubmissionOutcome {
        override val reportStatus: String = "upload_admission_persistence_failed"
    }

    data object FinalStatusPersistenceFailed : ResultSubmissionOutcome {
        override val reportStatus: String = "local_status_update_failed"
    }

    data class Uploaded(val httpCode: Int) : ResultSubmissionOutcome {
        override val reportStatus: String = "uploaded"
    }

    data class Rejected(val httpCode: Int) : ResultSubmissionOutcome {
        override val reportStatus: String = "upload_rejected"
    }

    data object TransportFailed : ResultSubmissionOutcome {
        override val reportStatus: String = "upload_failed"
    }

    data object AdapterFailed : ResultSubmissionOutcome {
        override val reportStatus: String = "upload_adapter_failed"
    }

    data object ScopeMismatch : ResultSubmissionOutcome {
        override val reportStatus: String = "upload_scope_mismatch"
    }
}

internal class ResultSubmissionCoordinator(
    private val admission: ResultUploadAdmission? = null,
) {
    suspend fun submit(
        localCommit: suspend (reportStatus: String) -> Unit,
        finalStatusCommit: suspend (reportStatus: String) -> Unit,
        adapter: ResultSubmissionAdapter?,
    ): ResultSubmissionOutcome {
        val admissionDecision = admission?.decide(adapterConfigured = adapter != null)
            ?: ResultUploadAdmissionDecision.NotConsented
        val permit = when (admissionDecision) {
            ResultUploadAdmissionDecision.NotConsented ->
                return commitTerminalLocalOutcome(ResultSubmissionOutcome.NotConsented, localCommit)

            ResultUploadAdmissionDecision.NotConfigured ->
                return commitTerminalLocalOutcome(ResultSubmissionOutcome.NotConfigured, localCommit)

            ResultUploadAdmissionDecision.AlreadyClaimed ->
                return ResultSubmissionOutcome.AlreadyClaimed

            ResultUploadAdmissionDecision.PersistenceFailed ->
                return commitTerminalLocalOutcome(
                    ResultSubmissionOutcome.AdmissionPersistenceFailed,
                    localCommit,
                )

            is ResultUploadAdmissionDecision.Permitted -> admissionDecision.permit
        }

        try {
            localCommit("upload_authorized_local")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ResultSubmissionOutcome.LocalPersistenceFailed
        }

        val configuredAdapter = requireNotNull(adapter)
        val attemptedOutcome = try {
            permit.attempt {
                val request = try {
                    configuredAdapter.requestFactory()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@attempt ResultSubmissionOutcome.AdapterFailed
                }
                if (request.resultId != permit.resultId || request.scope != permit.scope) {
                    return@attempt ResultSubmissionOutcome.ScopeMismatch
                }
                val transport = try {
                    configuredAdapter.transportFactory()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@attempt ResultSubmissionOutcome.AdapterFailed
                }
                val transportOutcome = try {
                    transport.submit(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@attempt ResultSubmissionOutcome.TransportFailed
                }
                when (transportOutcome) {
                    is ResultUploadTransportOutcome.Accepted ->
                        ResultSubmissionOutcome.Uploaded(transportOutcome.statusCode)

                    is ResultUploadTransportOutcome.Rejected4xx ->
                        ResultSubmissionOutcome.Rejected(transportOutcome.statusCode)

                    is ResultUploadTransportOutcome.Rejected5xx ->
                        ResultSubmissionOutcome.Rejected(transportOutcome.statusCode)

                    ResultUploadTransportOutcome.IoFailure ->
                        ResultSubmissionOutcome.TransportFailed
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ResultSubmissionOutcome.AdapterFailed
        }

        return try {
            finalStatusCommit(attemptedOutcome.reportStatus)
            attemptedOutcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ResultSubmissionOutcome.FinalStatusPersistenceFailed
        }
    }

    suspend fun submit(
        localCommit: suspend (reportStatus: String) -> Unit,
        finalStatusCommit: suspend (reportStatus: String) -> Unit,
        requestFactory: () -> ResultSubmissionRequest,
        transportFactory: () -> ResultSubmissionTransport,
    ): ResultSubmissionOutcome = submit(
        localCommit = localCommit,
        finalStatusCommit = finalStatusCommit,
        adapter = ResultSubmissionAdapter(
            requestFactory = requestFactory,
            transportFactory = transportFactory,
        ),
    )

    private suspend fun commitTerminalLocalOutcome(
        outcome: ResultSubmissionOutcome,
        localCommit: suspend (reportStatus: String) -> Unit,
    ): ResultSubmissionOutcome {
        try {
            localCommit(outcome.reportStatus)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ResultSubmissionOutcome.LocalPersistenceFailed
        }
        return outcome
    }
}
