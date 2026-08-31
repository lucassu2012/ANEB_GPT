package com.aneb.probe.prototype

import com.aneb.probe.data.Exporter
import com.aneb.probe.data.PrototypeCampaignRoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class PrototypeDeviceFallbackExporter(
    private val loadSnapshot: suspend (campaignId: String) ->
        PrototypeCampaignRoomRepository.ExportSnapshot?,
    private val publish: (
        fileName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ) -> Exporter.ExportOutcome,
    private val fileNameFactory: () -> String = ::defaultFileName,
) {
    sealed interface Outcome {
        data class Success(
            val uri: String,
            val bytes: Int,
        ) : Outcome

        data object Failed : Outcome
        data object Unavailable : Outcome
        data object Busy : Outcome
    }

    private val exportInFlight = AtomicBoolean(false)

    suspend fun export(campaignId: String): Outcome {
        if (!exportInFlight.compareAndSet(false, true)) return Outcome.Busy
        return try {
            val callerContext = currentCoroutineContext()
            val source = loadSnapshot(campaignId)
            callerContext.ensureActive()
            if (source == null) return Outcome.Unavailable
            val bundle = source.toBundleSnapshot()
            val outcome = publish(fileNameFactory(), ZIP_MIME_TYPE) { destination ->
                PrototypeDeviceFallbackBundleWriter.write(bundle, destination)
                callerContext.ensureActive()
            }
            callerContext.ensureActive()
            val uri = outcome.uri
            if (outcome.ok && uri != null) {
                Outcome.Success(uri = uri, bytes = outcome.bytes)
            } else {
                Outcome.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Outcome.Failed
        } finally {
            exportInFlight.set(false)
        }
    }

    private fun PrototypeCampaignRoomRepository.ExportSnapshot.toBundleSnapshot() =
        PrototypeDeviceFallbackBundleWriter.Snapshot(
            summary = campaign.summary,
            runs = campaign.runs.map { run ->
                PrototypeDeviceFallbackBundleWriter.Run(
                    runIndex = run.runIndex,
                    runId = run.runId,
                    conditionId = run.conditionId,
                    status = run.status,
                    taskSuccess = run.taskSuccess,
                    scoreEligible = run.scoreEligible,
                    eventsExpected = run.eventsExpected,
                    eventsReceived = run.eventsReceived,
                    failureReason = run.failureReason,
                    terminalReceiptValid = run.terminalReceiptValid,
                    metrics = run.metrics,
                )
            },
            capabilityResponseUtf8 = rawCapabilityBody.toByteArray(Charsets.UTF_8),
            eventJsonUtf8Records = lexicalEvidence.map { evidence ->
                evidence.eventJson.toByteArray(Charsets.UTF_8)
            },
        )

    private companion object {
        const val ZIP_MIME_TYPE = "application/zip"

        fun defaultFileName(): String =
            "ANEB-Prototype-device-fallback-${UUID.randomUUID()}.zip"
    }
}
