package com.aneb.probe.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPrivacyDeletionProductionWiringTest {
    @Test
    fun `confirmed privacy deletion drops the activity owned gateway credential first`() {
        val activity = readMainSource("com/aneb/probe/ui/MainActivity.kt")
        val confirmation = activity.blockStartingAt("onConfirmPrivacyDeletion = {")

        assertEquals(1, confirmation.occurrences("intentGatewayToken = null"))
        assertEquals(1, confirmation.occurrences("privacyDeletionOwner.requestConfirmedDeletion()"))
        assertTrue(
            "the deletion request started before the activity credential was dropped",
            confirmation.indexOf("intentGatewayToken = null") <
                confirmation.indexOf("privacyDeletionOwner.requestConfirmedDeletion()"),
        )
    }

    @Test
    fun `completed settings deletion resets hoisted values without recreating preferences`() {
        val activity = readMainSource("com/aneb/probe/ui/MainActivity.kt")
        val resetEffect = activity.blockStartingAt("LaunchedEffect(privacyDeletionState) {")

        assertEquals(1, resetEffect.occurrences("privacyDeletionOwner.claimSettingsReset()"))
        assertEquals(1, resetEffect.occurrences("val defaults = ProbeSettings()"))
        assertEquals(1, resetEffect.occurrences("serverUrl = defaults.serverUrl"))
        assertEquals(1, resetEffect.occurrences("testMode = defaults.testMode"))
        assertEquals(1, resetEffect.occurrences("mode = defaults.mode"))
        assertEquals(1, resetEffect.occurrences("transport = defaults.transport"))
        assertEquals(1, resetEffect.occurrences("driveTest = defaults.driveTest"))
        assertEquals(0, resetEffect.occurrences("settingsStore."))

        val claimIndex = resetEffect.indexOf("privacyDeletionOwner.claimSettingsReset()")
        listOf(
            "serverUrl = defaults.serverUrl",
            "testMode = defaults.testMode",
            "mode = defaults.mode",
            "transport = defaults.transport",
            "driveTest = defaults.driveTest",
        ).forEach { assignment ->
            assertTrue("setting reset happened before the exact claim: $assignment", resetEffect.indexOf(assignment) > claimIndex)
        }
    }

    @Test
    fun `settings confirmation invokes the application owned deletion operation`() {
        val settings = readMainSource("com/aneb/probe/ui/SettingsScreen.kt")
        val activity = readMainSource("com/aneb/probe/ui/MainActivity.kt")

        assertEquals(3, settings.occurrences("PrivacyDeletionSettingsRow("))
        assertTrue(settings.contains("清除本机 ANEB 数据"))
        assertTrue(settings.contains("结果上传授权、本机测试数据库、本机设置、内部与外部诊断日志、"))
        assertTrue(settings.contains("网关凭据、主测试运行状态和专项测试运行状态"))
        assertTrue(settings.contains("系统下载目录中的导出文件、相册分享图和服务器副本不在本次范围内"))
        assertTrue(settings.contains("privacyDeletionRunning"))
        assertTrue(settings.contains("privacyDeletionStatus"))
        assertTrue(settings.contains("privacyDeletionIssueLines"))
        assertTrue(settings.contains("onConfirmPrivacyDeletion"))

        assertTrue(activity.contains("AnebProbeApplication"))
        assertTrue(activity.contains("localPrivacyDeletionOperationOwner"))
        assertTrue(activity.contains("privacyDeletionOwner.state.collectAsStateWithLifecycle()"))
        assertTrue(activity.contains("privacyDeletionOwner.requestConfirmedDeletion()"))
    }

    private fun readMainSource(relative: String): String {
        val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates = generateSequence(start) { it.parent }
            .flatMap { root ->
                sequenceOf(
                    root.resolve("src/main/java").resolve(relative),
                    root.resolve("app/probe/src/main/java").resolve(relative),
                )
            }
        val path = candidates.firstOrNull(Files::isRegularFile)
            ?: error("main source not found: $relative")
        return Files.readAllBytes(path).toString(Charsets.UTF_8)
    }

    private fun String.occurrences(token: String): Int =
        windowed(token.length).count { it == token }

    private fun String.blockStartingAt(marker: String): String {
        val markerIndex = indexOf(marker)
        assertTrue("source block not found: $marker", markerIndex >= 0)
        val openingBrace = indexOf('{', markerIndex)
        var depth = 0
        for (index in openingBrace until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(markerIndex, index + 1)
                }
            }
        }
        throw AssertionError("source block was not balanced: $marker")
    }
}
