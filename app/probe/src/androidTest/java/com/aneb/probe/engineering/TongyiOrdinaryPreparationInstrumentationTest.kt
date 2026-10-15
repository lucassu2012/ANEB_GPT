package com.aneb.probe.engineering

import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.aneb.probe.research.engineering.TongyiEngineeringRunner
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android preparation only: the wrapper cannot invoke recording or SEND. */
@RunWith(AndroidJUnit4::class)
class TongyiOrdinaryPreparationInstrumentationTest {
    @Test fun realPreparationReadsBackExactDraftBeforeUnboundSendIsDeclined() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("feature_release") == "tongyi-engineering-807") { "NEW_FEATURE_RELEASE_REQUIRED" }
        val payloadBytes = TongyiEngineeringRunner.PAYLOAD.toByteArray(Charsets.UTF_8)
        val payloadSha = MessageDigest.getInstance("SHA-256").digest(payloadBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val expectedPayloadSha = "f1e312c9123478fcf64c9ad056a436a493f6bd5dde09af029fc8ad62186efac5"
        check(args.getString("payload_sha256") == expectedPayloadSha && payloadSha == expectedPayloadSha && payloadBytes.size == 239) {
            "FIXED_PAYLOAD_NOT_BOUND"
        }
        val runId = checkNotNull(args.getString("run_id"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val condition = checkNotNull(args.getString("network_condition"))
        require(condition in setOf("W1", "C1"))
        val ui = JSONObject(String(Base64.decode(checkNotNull(args.getString("ui_contract_b64")), Base64.DEFAULT), Charsets.UTF_8))
        check(ui.getString("binding_scope") == "root_current_807_ui_frame") { "CURRENT_FRAME_CONTRACT_REQUIRED" }
        check(ui.getJSONObject("menu").length() == 0 && ui.getJSONObject("focus_hint").length() == 0 && !ui.has("send")) {
            "TRACER_UNBOUND_ACTION_CONTRACT_REQUIRED"
        }
        val parent = File(instrumentation.targetContext.getExternalFilesDir(null), "tongyi-engineering")
        check(parent.exists() || parent.mkdirs())
        val root = File(parent, runId) // Only Core.runOnce may create this consumed opportunity.
        val real = TongyiWholeEngineeringInstrumentation().ordinaryPhoneForPreparationTracer(root, condition, ui)
        val snapshots = ArrayList<Pair<String, TongyiEngineeringRunner.Snapshot>>()
        var forbiddenRecordingAttempts = 0
        var forbiddenSendAttempts = 0
        val observed = object : TongyiEngineeringRunner.Phone by real {
            override fun prepare(deadlineNanos: Long): TongyiEngineeringRunner.Snapshot =
                real.prepare(deadlineNanos).also { snapshots.add("prepare_return" to it.copy()) }

            override fun snapshot(): TongyiEngineeringRunner.Snapshot =
                real.snapshot().also { snapshots.add("snapshot_return" to it.copy()) }

            override fun startRecording(readyDeadlineNanos: Long) {
                forbiddenRecordingAttempts++
                error("TRACER_RECORDING_CAPABILITY_NOT_ADMITTED") // Never delegate to the real operation.
            }

            override fun sendOnce() {
                forbiddenSendAttempts++
                error("TRACER_SEND_CAPABILITY_NOT_ADMITTED") // Never delegate to the real operation.
            }
        }
        val result = TongyiEngineeringRunner(observed, SystemClock::elapsedRealtimeNanos).runOnce(runId, condition, root)

        // Core.runOnce has returned through its real finally/cleanup; no new UI or clock read follows.
        val report = JSONObject().put("kind", "operational_real_android_preparation_tracer_not_formal_terminal")
            .put("actual_result", JSONObject().put("state", result.state).put("send_calls", result.sendCalls)
                .put("owned_cleanup", result.ownedCleanup).put("reason", result.reason ?: JSONObject.NULL)
                .put("operational_events", JSONArray().apply {
                    result.operationalEvents.forEach { (name, nanos) ->
                        put(JSONObject().put("event", name).put("elapsed_realtime_nanos", nanos.toString()))
                    }
                }))
            .put("copied_snapshots", JSONArray().apply {
                snapshots.forEach { (source, value) ->
                    put(JSONObject().put("source", source).put("package_name", value.packageName)
                        .put("owned_lifecycle", value.ownedLifecycle).put("ordinary_text_visible", value.ordinaryTextVisible)
                        .put("no_attachments", value.noAttachments).put("empty_new_session", value.emptyNewSession)
                        .put("composer_text", value.composerText).put("composer_focused", value.composerFocused)
                        .put("unique_send", value.uniqueSend).put("network_condition", value.networkCondition)
                        .put("not_vpn", value.notVpn))
                }
            })
            .put("forbidden_capability_attempts", JSONObject().put("start_recording", forbiddenRecordingAttempts)
                .put("send_once", forbiddenSendAttempts))
        val reportFile = File(root, "ORDINARY_PREPARATION_TRACER_OBSERVATION.json")
        check(reportFile.createNewFile()) { "TRACER_REPORT_ALREADY_EXISTS_NO_RETRY" }
        FileOutputStream(reportFile).use {
            it.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }

        // Primary behavioral oracle: a real post-input snapshot, not merely a refusal reason or a no-op.
        assertTrue("real ordinary preparation must return a focused owned exact239B draft snapshot; actual reason=${result.reason}",
            snapshots.any { (source, value) ->
                source == "snapshot_return" && value.packageName == TongyiEngineeringRunner.PACKAGE && value.ownedLifecycle &&
                    value.composerFocused && value.composerText == TongyiEngineeringRunner.PAYLOAD &&
                    value.composerText.toByteArray(Charsets.UTF_8).size == 239
            })
        assertEquals("recording must be declined before any real capability invocation", 0, forbiddenRecordingAttempts)
        assertEquals("SEND must be declined before any real capability invocation", 0, forbiddenSendAttempts)
        assertEquals("0", result.sendCalls)
        assertFalse(File(root, "SEND_INTENT.json").exists())
        assertFalse(File(root, "video.mp4").exists())
        assertFalse(File(root, "OWNED_CAPTURE.json").exists())
        assertFalse(File(root, "capture.pid").exists())
        assertEquals("OBSERVATION_UNKNOWN", result.state)
        assertEquals("COMPLETE_DRAFT_AND_UNIQUE_SEND_REQUIRED", result.reason)
        assertTrue("only actual real Native cleanup may satisfy closure", result.ownedCleanup)
    }
}
