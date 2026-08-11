package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.CancellationException

internal data class ResultSubmissionRequest(
    val url: String,
    val jsonBody: String,
)

internal fun interface ResultSubmissionTransport {
    suspend fun submit(request: ResultSubmissionRequest): AnebClient.HttpTextResult
}

internal sealed interface ResultSubmissionOutcome {
    val reportStatus: String

    data object NotConsented : ResultSubmissionOutcome {
        override val reportStatus: String = "not_consented"
    }

    data object LocalPersistenceFailed : ResultSubmissionOutcome {
        override val reportStatus: String = "local_persistence_failed"
    }
}

internal class ResultSubmissionCoordinator {
    suspend fun submit(
        localCommit: suspend (reportStatus: String) -> Unit,
        requestFactory: () -> ResultSubmissionRequest,
        transportFactory: () -> ResultSubmissionTransport,
    ): ResultSubmissionOutcome {
        val outcome = ResultSubmissionOutcome.NotConsented
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
