package com.aneb.probe.ui.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class PrototypeCampaignResultRouteTest {
    @Test
    fun `only working action states block another result action`() {
        val workingStates = setOf(
            PrototypeCampaignResultActionState.Exporting,
            PrototypeCampaignResultActionState.PreparingShare,
        )

        PrototypeCampaignResultActionState.entries.forEach { state ->
            if (state in workingStates) {
                assertFalse(state.canStartResultAction())
            } else {
                assertTrue(state.canStartResultAction())
            }
        }
    }

    @Test
    fun `activity owns one exporter and route binds campaign actions to keyed composition scope`() {
        val route = normalizedSource(
            "app/probe/src/main/java/com/aneb/probe/ui/prototype/PrototypeCampaignResultRoute.kt",
            "src/main/java/com/aneb/probe/ui/prototype/PrototypeCampaignResultRoute.kt",
            "../../app/probe/src/main/java/com/aneb/probe/ui/prototype/PrototypeCampaignResultRoute.kt",
        )
        val activity = normalizedSource(
            "app/probe/src/main/java/com/aneb/probe/ui/MainActivity.kt",
            "src/main/java/com/aneb/probe/ui/MainActivity.kt",
            "../../app/probe/src/main/java/com/aneb/probe/ui/MainActivity.kt",
        )

        assertTrue(route.contains("@Composable internal fun PrototypeCampaignResultRoute("))
        assertTrue(route.contains("key(campaignId) {"))
        assertTrue(
            route.contains(
                "remember(campaignId) { mutableStateOf(PrototypeCampaignResultActionState.Idle) }",
            ),
        )
        assertTrue(route.contains("val scope = rememberCoroutineScope()"))
        assertTrue(
            route.contains(
                "val onExport: () -> Unit = { " +
                    "if (actionState.canStartResultAction()) { actionState = " +
                    "PrototypeCampaignResultActionState.Exporting scope.launch { " +
                    "actionState = coordinator.export(campaignId) } }",
            ),
        )
        assertTrue(
            route.contains(
                "val onShare: () -> Unit = { " +
                    "if (actionState.canStartResultAction()) { actionState = " +
                    "PrototypeCampaignResultActionState.PreparingShare scope.launch { " +
                    "actionState = coordinator.share(campaignId) } }",
            ),
        )
        assertTrue(route.contains("content(loadState, onBack, actionState, onExport, onShare)"))
        assertFalse(route.contains("catch"))
        assertFalse(route.contains("lifecycleScope"))
        assertFalse(route.contains("PrototypeCampaignResultActionState.Failed"))
        assertFalse(route.contains("presentation.status"))
        assertFalse(route.contains("Complete"))
        assertFalse(route.contains("Partial"))

        assertEquals(1, activity.countOccurrences("PrototypeDeviceFallbackExporter("))
        assertTrue(
            activity.contains(
                "loadSnapshot = prototypeCampaignResultRepository::loadExportSnapshot",
            ),
        )
        assertTrue(
            activity.contains(
                "Exporter.exportToDownloads(applicationContext, fileName, mimeType, writer)",
            ),
        )
        assertEquals(1, activity.countOccurrences("PrototypeCampaignResultActionCoordinator("))
        assertTrue(
            activity.contains(
                "PrototypeZipShareLauncher.open(applicationContext, publishedUri)",
            ),
        )
        assertTrue(activity.contains("ioDispatcher = Dispatchers.IO"))
        assertEquals(1, activity.countOccurrences("PrototypeCampaignResultRoute("))
        assertTrue(activity.contains("campaignId = s.campaignId"))
        assertEquals(1, activity.countOccurrences("PrototypeCampaignResultScreen("))
        assertTrue(activity.contains("content = { routeLoadState,"))
        assertTrue(activity.contains("loadState = routeLoadState"))
        assertTrue(activity.contains("actionState = routeActionState"))
        assertTrue(activity.contains("onExport = onExport"))
        assertTrue(activity.contains("onShare = onShare"))
    }

    private fun normalizedSource(vararg candidates: String): String {
        val path = candidates
            .map(Path::of)
            .firstOrNull(Files::isRegularFile)
            ?: error("source fixture was not found: ${candidates.first()}")
        return Files.readAllBytes(path)
            .toString(UTF_8)
            .replace(Regex("\\s+"), " ")
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length)
        .count { it == value }
}
