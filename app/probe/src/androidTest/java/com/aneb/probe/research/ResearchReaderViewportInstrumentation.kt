package com.aneb.probe.research

import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real reader UI geometry only. Requires the Huawei Launcher in front and an
 * existing nonempty saved-source fixture; this test never creates or imports it.
 * Root owns device admission and cleanup. No permissions, SEND or recording.
 */
@RunWith(AndroidJUnit4::class)
class ResearchReaderViewportInstrumentation {
    @Test
    fun savedSourceViewportDoesNotExtendIntoMainNavigationHitRegion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val targetPackage = context.packageName
        val device = UiDevice.getInstance(instrumentation)
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "PMO827 reader viewport: $message\n")
        })

        // Field 22 is process start time; the command in parentheses may contain spaces.
        val stat = File("/proc/self/stat").readText()
        val startTicks = stat.substringAfterLast(") ").split(Regex("\\s+"))[19]
        report("owned_process package=$targetPackage pid=${Process.myPid()} uid=${Process.myUid()} start_ticks=$startTicks")
        check(device.currentPackageName == "com.huawei.android.launcher") {
            "PRECONDITION_HUAWEI_LAUNCHER_REQUIRED actual=${device.currentPackageName}"
        }
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(targetPackage)) {
            "PRECONDITION_TARGET_LAUNCH_INTENT_MISSING package=$targetPackage"
        }
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(device.wait(Until.hasObject(By.pkg(targetPackage)), 10_000)) {
            "PRECONDITION_TARGET_UI_NOT_VISIBLE package=$targetPackage actual=${device.currentPackageName}"
        }
        device.waitForIdle()

        fun currentLabel(text: String): UiObject2 {
            check(device.currentPackageName == targetPackage) {
                "PRECONDITION_UNEXPECTED_FOREGROUND before=$text actual=${device.currentPackageName}"
            }
            val labels = device.wait(Until.findObjects(By.pkg(targetPackage).text(text)), 10_000)
            check(labels != null && labels.size == 1) {
                "PRECONDITION_UNIQUE_LABEL_REQUIRED label=$text count=${labels?.size ?: 0}"
            }
            return labels.single()
        }

        fun clickableAncestor(label: UiObject2): UiObject2 {
            var node: UiObject2? = label
            while (node != null) {
                if (node.isClickable && node.isEnabled && !node.visibleBounds.isEmpty) return node
                node = node.parent
            }
            error("PRECONDITION_CLICKABLE_ANCESTOR_MISSING")
        }

        clickableAncestor(currentLabel("结果")).click()
        device.waitForIdle()
        clickableAncestor(currentLabel("App 研究记录 · 人工来源")).click()
        check(device.wait(Until.hasObject(By.pkg(targetPackage).text("研究记录与分析")), 10_000)) {
            "PRECONDITION_RESEARCH_SOURCE_LIST_NOT_VISIBLE actual=${device.currentPackageName}"
        }
        device.waitForIdle()
        check(device.currentPackageName == targetPackage) {
            "PRECONDITION_RESEARCH_FOREGROUND_LOST actual=${device.currentPackageName}"
        }
        val scrollables = device.wait(Until.findObjects(By.pkg(targetPackage).scrollable(true)), 10_000)
        check(scrollables != null && scrollables.size == 1) {
            "PRECONDITION_ONE_SOURCE_LIST_SCROLLABLE_REQUIRED count=${scrollables?.size ?: 0}"
        }
        val reader = scrollables.single()
        val sourceTextNodes = reader.findObjects(By.text(Pattern.compile(".+")))
        check(sourceTextNodes.isNotEmpty() && sourceTextNodes.none {
            it.text.orEmpty().startsWith("暂无研究记录。")
        }) { "PRECONDITION_EXISTING_NONEMPTY_SAVED_SOURCE_FIXTURE_REQUIRED" }
        val readerBounds = reader.visibleBounds
        check(!readerBounds.isEmpty) { "PRECONDITION_READER_VIEWPORT_EMPTY" }

        val testLabels = device.findObjects(By.pkg(targetPackage).text("测试"))
        check(testLabels.size <= 1) { "PRECONDITION_AMBIGUOUS_MAIN_NAVIGATION_LABEL count=${testLabels.size}" }
        val navigation = testLabels.singleOrNull()?.let(::clickableAncestor)
        val navigationBounds = navigation?.visibleBounds
        report("observed_ui package=${device.currentPackageName} research_source_list=true " +
            "existing_source_text_nodes=${sourceTextNodes.size} scrollable_count=${scrollables.size} " +
            "reader_rect=${readerBounds.toShortString()} nav_present=${navigation != null} " +
            "nav_hit_rect=${navigationBounds?.toShortString() ?: "absent"}")
        assertTrue(
            "READER_VIEWPORT_OVERLAPS_MAIN_NAVIGATION reader=${readerBounds.toShortString()} " +
                "nav_hit=${navigationBounds?.toShortString() ?: "absent"}",
            navigationBounds == null || readerBounds.bottom <= navigationBounds.top,
        )
    }
}
