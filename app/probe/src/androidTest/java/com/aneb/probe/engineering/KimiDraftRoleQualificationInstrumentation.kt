package com.aneb.probe.engineering

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit no-SEND KimiChat qualification, outside the ordinary HOST collector. */
@RunWith(AndroidJUnit4::class)
class KimiDraftRoleQualificationInstrumentation {
    @Test fun qualifyCurrentKimiDraftWithoutSendOrRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val allowed = setOf("class", "debug", "feature_release", "target_profile", "target_package", "payload_sha256", "run_id")
        check(args.keySet().all { it in allowed }) { "KIMI_QUALIFICATION_ARGUMENT_NOT_ALLOWED" }
        check(args.getString("feature_release") == "kimi-no-send-qualification-813") { "EXPLICIT_KIMI_NO_SEND_QUALIFICATION_REQUIRED" }
        check(args.getString("target_profile") == "kimi" && args.getString("target_package") == "com.moonshot.kimichat") {
            "FIXED_KIMICHAT_TARGET_REQUIRED"
        }
        check(args.getString("payload_sha256") == "f1e312c9123478fcf64c9ad056a436a493f6bd5dde09af029fc8ad62186efac5") {
            "FIXED_PAYLOAD_NOT_BOUND"
        }
        val runId = checkNotNull(args.getString("run_id"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        // All arguments are fixed/validated before constructing NativePhone or launching an App.
        val parent = File(instrumentation.targetContext.getExternalFilesDir(null), "tongyi-engineering")
        check(parent.exists() || parent.mkdirs())
        val result = TongyiWholeEngineeringInstrumentation().runKimiDraftRoleQualificationOnce(runId, File(parent, runId))
        runCatching { instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "PMO813 KimiChat no-SEND qualification only: state=${result.state}; readback=${result.inputReadbackConfirmed}; cleanup=${result.ownedCleanup}; SEND0\n")
        }) }
        assertTrue("real exact239B Kimi readback required: ${result.failure}", result.inputReadbackConfirmed)
        assertTrue("actual own cleanup required: ${result.failure}", result.ownedCleanup)
        assertEquals("required originals must be retained: ${result.failure}", "DRAFT_ROLE_QUALIFICATION_CLOSED", result.state)
    }
}
