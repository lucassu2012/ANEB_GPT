package com.aneb.probe.ui.prototype

import com.aneb.probe.prototype.PrototypeDeviceFallbackExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors

class PrototypeCampaignResultActionCoordinatorTest {
    @Test
    fun `export runs on io and success becomes saved`() {
        val ioDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "prototype-result-export-io")
        }.asCoroutineDispatcher()
        try {
            var exportThreadName: String? = null
            val coordinator = PrototypeCampaignResultActionCoordinator(
                exportCampaign = { campaignId ->
                    assertEquals("campaign-export", campaignId)
                    exportThreadName = Thread.currentThread().name
                    PrototypeDeviceFallbackExporter.Outcome.Success(
                        uri = "content://downloads/aneb-export",
                        bytes = 7_001,
                    )
                },
                openShare = { error("share must not open during export") },
                ioDispatcher = ioDispatcher,
            )

            val result = runBlocking { coordinator.export("campaign-export") }

            assertSame(PrototypeCampaignResultActionState.Saved, result)
            assertTrue(exportThreadName?.startsWith("prototype-result-export-io") == true)
        } finally {
            ioDispatcher.close()
        }
    }

    @Test
    fun `export preserves working for busy and collapses unavailable or failed`() = runBlocking {
        val outcomes = ArrayDeque<PrototypeDeviceFallbackExporter.Outcome>().apply {
            add(PrototypeDeviceFallbackExporter.Outcome.Unavailable)
            add(PrototypeDeviceFallbackExporter.Outcome.Failed)
            add(PrototypeDeviceFallbackExporter.Outcome.Busy)
        }
        val coordinator = PrototypeCampaignResultActionCoordinator(
            exportCampaign = { outcomes.removeFirst() },
            openShare = { error("share must not open during export") },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertSame(
            PrototypeCampaignResultActionState.Failed,
            coordinator.export("campaign-unavailable"),
        )
        assertSame(
            PrototypeCampaignResultActionState.Failed,
            coordinator.export("campaign-failed"),
        )
        assertSame(
            PrototypeCampaignResultActionState.Exporting,
            coordinator.export("campaign-busy"),
        )
    }

    @Test
    fun `share exports on io then opens the exact uri on the caller dispatcher`() {
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "prototype-result-caller")
        }.asCoroutineDispatcher()
        val ioDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "prototype-result-share-io")
        }.asCoroutineDispatcher()
        try {
            val publishedUri = "content://downloads/aneb-share?opaque=7"
            var exportThreadName: String? = null
            var shareThreadName: String? = null
            var openedUri: String? = null
            val coordinator = PrototypeCampaignResultActionCoordinator(
                exportCampaign = { campaignId ->
                    assertEquals("campaign-share", campaignId)
                    exportThreadName = Thread.currentThread().name
                    PrototypeDeviceFallbackExporter.Outcome.Success(
                        uri = publishedUri,
                        bytes = 8_002,
                    )
                },
                openShare = { uri ->
                    openedUri = uri
                    shareThreadName = Thread.currentThread().name
                    true
                },
                ioDispatcher = ioDispatcher,
            )

            val result = runBlocking(callerDispatcher) {
                coordinator.share("campaign-share")
            }

            assertSame(PrototypeCampaignResultActionState.ShareOpened, result)
            assertEquals(publishedUri, openedUri)
            assertTrue(exportThreadName?.startsWith("prototype-result-share-io") == true)
            assertTrue(shareThreadName?.startsWith("prototype-result-caller") == true)
        } finally {
            callerDispatcher.close()
            ioDispatcher.close()
        }
    }

    @Test
    fun `share reports unavailable and does not open non-success outcomes`() = runBlocking {
        val publishedUri = "content://downloads/aneb-unavailable-share"
        val outcomes = ArrayDeque<PrototypeDeviceFallbackExporter.Outcome>().apply {
            add(PrototypeDeviceFallbackExporter.Outcome.Success(publishedUri, 9_003))
            add(PrototypeDeviceFallbackExporter.Outcome.Unavailable)
            add(PrototypeDeviceFallbackExporter.Outcome.Failed)
            add(PrototypeDeviceFallbackExporter.Outcome.Busy)
        }
        val openedUris = mutableListOf<String>()
        val coordinator = PrototypeCampaignResultActionCoordinator(
            exportCampaign = { outcomes.removeFirst() },
            openShare = { uri ->
                openedUris += uri
                false
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertSame(
            PrototypeCampaignResultActionState.ShareUnavailable,
            coordinator.share("campaign-share-unavailable"),
        )
        assertSame(
            PrototypeCampaignResultActionState.Failed,
            coordinator.share("campaign-export-unavailable"),
        )
        assertSame(
            PrototypeCampaignResultActionState.Failed,
            coordinator.share("campaign-export-failed"),
        )
        assertSame(
            PrototypeCampaignResultActionState.PreparingShare,
            coordinator.share("campaign-export-busy"),
        )
        assertEquals(listOf(publishedUri), openedUris)
    }

    @Test
    fun `dependency exceptions cancellation and errors propagate unchanged`() = runBlocking {
        val ordinary = IOException("export storage failed")
        val ordinaryCoordinator = PrototypeCampaignResultActionCoordinator(
            exportCampaign = { throw ordinary },
            openShare = { error("share must not open") },
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertSame(
            ordinary,
            runCatching { ordinaryCoordinator.export("campaign-ordinary") }.exceptionOrNull(),
        )

        val cancelled = CancellationException("result route left composition")
        val cancellationCoordinator = PrototypeCampaignResultActionCoordinator(
            exportCampaign = { throw cancelled },
            openShare = { error("share must not open") },
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertSame(
            cancelled,
            runCatching { cancellationCoordinator.share("campaign-cancelled") }.exceptionOrNull(),
        )
        assertTrue(requireNotNull(currentCoroutineContext()[Job]).isActive)

        val fatal = AssertionError("share boundary invariant failed")
        val fatalCoordinator = PrototypeCampaignResultActionCoordinator(
            exportCampaign = {
                PrototypeDeviceFallbackExporter.Outcome.Success(
                    uri = "content://downloads/aneb-fatal-share",
                    bytes = 10_004,
                )
            },
            openShare = { throw fatal },
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertSame(
            fatal,
            runCatching { fatalCoordinator.share("campaign-fatal") }.exceptionOrNull(),
        )
    }
}
