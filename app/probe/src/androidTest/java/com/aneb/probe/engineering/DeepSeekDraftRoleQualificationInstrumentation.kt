package com.aneb.probe.engineering

import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Not in the HOST-selected whole class; no SEND/recording/network capability. */
@RunWith(AndroidJUnit4::class)
class DeepSeekDraftRoleQualificationInstrumentation {
    @Test fun observeCurrentPublicUiWithoutPreparationOrActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("feature_release") == "deepseek-public-ui-observation-811") { "EXPLICIT_PUBLIC_UI_OBSERVATION_REQUIRED" }
        val allowed = setOf("class", "debug", "feature_release", "target_profile", "target_package", "run_id")
        check(args.keySet().all { it in allowed }) { "PUBLIC_UI_OBSERVATION_ARGUMENT_NOT_ALLOWED" }
        check(args.getString("target_profile") == "deepseek" && args.getString("target_package") == "com.deepseek.chat") {
            "FIXED_DEEPSEEK_TARGET_REQUIRED"
        }
        val runId = checkNotNull(args.getString("run_id"))
        check(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val parent = File(instrumentation.targetContext.getExternalFilesDir(null), "tongyi-engineering")
        check(parent.exists() || parent.mkdirs())
        val result = TongyiWholeEngineeringInstrumentation().runDeepSeekPublicUiObservationOnce(runId, File(parent, runId))
        runCatching { instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "PMO811 operational public-API observation only: state=${result.state}; retained=${result.sourceRetained}; cleanup=${result.ownedCleanup}\n")
        }) }
        assertTrue("one real current public API source must be retained: ${result.failure}", result.sourceRetained)
        assertTrue("actual own cleanup required: ${result.failure}", result.ownedCleanup)
        assertEquals("observation only, not input qualification: ${result.failure}", "PUBLIC_UI_OBSERVATION_RETAINED", result.state)
    }

    @Test fun qualifyCurrentDraftAndPublicActionsWithoutSendOrRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("feature_release") == "deepseek-no-send-qualification-811") { "EXPLICIT_NO_SEND_QUALIFICATION_REQUIRED" }
        check(args.getString("payload_sha256") == "f1e312c9123478fcf64c9ad056a436a493f6bd5dde09af029fc8ad62186efac5") { "FIXED_PAYLOAD_NOT_BOUND" }
        check(args.getString("target_profile") == "deepseek" && args.getString("target_package") == "com.deepseek.chat") { "FIXED_DEEPSEEK_TARGET_REQUIRED" }
        check(!args.containsKey("network_condition") && !args.containsKey("execution_mode")) { "QUALIFICATION_NOT_COLLECTION_OR_NETWORK_TRANSITION" }
        val runId = checkNotNull(args.getString("run_id"))
        check(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val ui = JSONObject(String(Base64.decode(checkNotNull(args.getString("ui_contract_b64")), Base64.DEFAULT), Charsets.UTF_8))
        val parent = File(instrumentation.targetContext.getExternalFilesDir(null), "tongyi-engineering")
        check(parent.exists() || parent.mkdirs())
        val result = TongyiWholeEngineeringInstrumentation().runDeepSeekDraftRoleQualificationOnce(runId, File(parent, runId), ui)
        runCatching { instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "PMO811 operational no-SEND qualification only: state=${result.state}; readback=${result.inputReadbackConfirmed}; cleanup=${result.ownedCleanup}\n")
        }) }
        assertTrue("real exact239B readback required: ${result.failure}", result.inputReadbackConfirmed)
        assertTrue("actual own cleanup required: ${result.failure}", result.ownedCleanup)
        assertEquals("all required observations must be retained: ${result.failure}", "DRAFT_ROLE_QUALIFICATION_CLOSED", result.state)
    }
}
