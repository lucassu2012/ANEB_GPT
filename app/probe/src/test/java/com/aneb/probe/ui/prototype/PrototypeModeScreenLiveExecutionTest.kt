package com.aneb.probe.ui.prototype

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeModeScreenLiveExecutionTest {
    @Test
    fun modeScreenRendersTheLifecycleBackedLiveExecutionCard() {
        val screen = source("ui/prototype/PrototypeModeScreen.kt")
        val activity = source("ui/MainActivity.kt")

        assertTrue(screen.contains("liveExecution: PrototypeCampaignLiveExecutionPresentation?"))
        assertTrue(screen.contains("liveExecution?.let { live ->"))
        assertTrue(screen.contains("live.currentRunLabel"))
        assertTrue(screen.contains("live.completedRunsLabel"))
        assertTrue(screen.contains("live.phaseLabel"))
        assertTrue(screen.contains("live.ttftLabel"))
        assertTrue(screen.contains("live.eventRateLabel"))
        assertTrue(screen.contains("live.stallDetected"))
        assertTrue(screen.contains("if (quickStatusMessage != null && liveExecution == null)"))
        assertTrue(activity.contains("liveExecution = prototypePresentation.liveExecution"))
        assertTrue(activity.contains("PrototypeCampaignService.progress.collectAsStateWithLifecycle()"))
    }

    private fun source(relativePath: String): String {
        val path = listOf(
            Path.of("app/probe/src/main/java/com/aneb/probe/$relativePath"),
            Path.of("src/main/java/com/aneb/probe/$relativePath"),
            Path.of("../../app/probe/src/main/java/com/aneb/probe/$relativePath"),
        ).firstOrNull(Files::isRegularFile)
            ?: error("source fixture was not found: $relativePath")
        return Files.readAllBytes(path).toString(UTF_8)
    }
}
