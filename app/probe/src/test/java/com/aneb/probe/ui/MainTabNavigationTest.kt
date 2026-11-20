package com.aneb.probe.ui

import com.aneb.probe.ui.components.MainTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class MainTabNavigationTest {

    @Test
    fun `MainTab contains exactly 4 tabs without Probe`() {
        assertEquals(
            listOf(MainTab.Test, MainTab.Results, MainTab.Map, MainTab.Settings),
            MainTab.entries,
        )
        assertEquals(
            listOf("测试", "结果", "地图", "设置"),
            MainTab.entries.map { it.label },
        )
        assertFalse(MainTab.entries.any { it.name == "Probe" || it.label == "探针" })
    }

    @Test
    fun `MainActivity SettingsScreen and ApiProbeScreen satisfy Issue #1 navigation contract`() {
        val mainActivity = normalizedSource(
            "app/probe/src/main/java/com/aneb/probe/ui/MainActivity.kt",
            "src/main/java/com/aneb/probe/ui/MainActivity.kt",
            "../../app/probe/src/main/java/com/aneb/probe/ui/MainActivity.kt",
        )
        val settingsScreen = normalizedSource(
            "app/probe/src/main/java/com/aneb/probe/ui/SettingsScreen.kt",
            "src/main/java/com/aneb/probe/ui/SettingsScreen.kt",
            "../../app/probe/src/main/java/com/aneb/probe/ui/SettingsScreen.kt",
        )
        val apiProbeScreen = normalizedSource(
            "app/probe/src/main/java/com/aneb/probe/ui/ApiProbeScreen.kt",
            "src/main/java/com/aneb/probe/ui/ApiProbeScreen.kt",
            "../../app/probe/src/main/java/com/aneb/probe/ui/ApiProbeScreen.kt",
        )

        assertFalse(mainActivity.contains("MainTab.Probe"))
        assertTrue(mainActivity.contains("onOpenApiProbe = { screen = Screen.ApiProbe }"))
        assertTrue(mainActivity.contains("is Screen.ApiProbe -> ApiProbeRoute("))
        assertTrue(mainActivity.contains("onBack = { screen = Screen.Home }"))
        assertTrue(mainActivity.contains("showBack = true"))

        val advancedIdx = settingsScreen.indexOf("AnebSectionTitle( \"高级\"")
            .takeIf { it >= 0 }
            ?: settingsScreen.indexOf("AnebSectionTitle(\"高级\"")
        val apiProbeIdx = settingsScreen.indexOf("title = \"API 探针\"", startIndex = advancedIdx.coerceAtLeast(0))
        val customServerIdx = settingsScreen.indexOf(
            "Text(\"自定义服务器地址\")",
            startIndex = advancedIdx.coerceAtLeast(0),
        )
        assertTrue("高级 section must exist in SettingsRootScreen", advancedIdx >= 0)
        assertTrue("API 探针 must appear under 高级", apiProbeIdx > advancedIdx)
        assertTrue("API 探针 must appear above 自定义服务器地址", customServerIdx > apiProbeIdx)

        assertTrue(apiProbeScreen.contains("BackHandler(enabled = showBack, onBack = onBack)"))
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
}
