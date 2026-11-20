package com.aneb.probe.engine

import com.aneb.probe.net.RealtimeWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Profile 4 执行计划 spec↔runtime 对拍——铁律 1 客户端落地的第二道闸门（承 D-390 §5.1）。
 *
 * 形态与 `SpecScoringParityTest`、`radio_bands.yaml`（D-367）相同：**导出 + 对拍**。
 * spec 文件**没有生产码读者**，引擎仍从 [VoiceRunner] companion 读常量；
 * 本测试的唯一职责是让两侧不许分叉。
 *
 * ## 为什么执行计划不并入 `client_profiles.json`
 * `TestModeProfileLoader` 用严格模式 `Json`（未知键即抛，见其 KDoc 与
 * `private val json = Json`）。往 `voice_realtime` 补子对象会让运行时解析失败、
 * 回退硬编码兜底，且 `ClientProfileDataParityTest` 用例 1 同时红——
 * 那需要改 DTO，属 `:probe` 生产码改动。故另立文件。
 *
 * ## ⚠ 这道闸门在发布门里【不执行】——别被它的绿骗到
 * 两条**实测**结论，不是推断：
 * 1. `scripts/verify_all.ps1` 只跑 `:probe:assembleDebug`，**不跑 `testDebugUnitTest`**
 *    （该脚本 L90-91 的注释早已为 `AdapterSpecTest` 记下同一缺口）。
 * 2. 即便手工跑，Gradle 在**只有模块外文件变化**时把 `testDebugUnitTest` 判为
 *    `UP-TO-DATE` 而整个跳过。实测：对 spec 做三处突变（打断轮 3,6→2,5；
 *    派生码率 64→40；上行帧数 200→199）**全部存活**，任务行是
 *    `> Task :probe:testDebugUnitTest UP-TO-DATE`——测试一次都没跑。
 *
 * **真正把关的是 `scripts/validate_voice_plan.py`**（verify_all 步骤 `voice-plan-parity`）：
 * 它读 Kotlin 源取常量做同一份对拍，双向突变审计 **10/10 咬住**（含「改常量名让正则
 * 失效」也必须响）。本测试保留，因为它比 Python 那份**多一层**：它比对
 * [VoiceRunner.defaultSimPlan] **实际生成**的计划，而不只是源码里的字面量——
 * 生成逻辑改了而字面量没动时，只有它能看见。
 *
 * ## 这道闸门能不能失败（在它真被执行时）
 * 每条断言都直接落在数字上（不是「字段存在」）：改 spec 任一值、或改
 * [VoiceRunner] 任一常量而不同步另一侧，本测试即红。`derived_nominal_kbps`
 * 由本测试**重算**而非比对字面量——手填的派生值会漂，重算的不会。
 * 要让它真跑，用 `gradlew :probe:testDebugUnitTest --rerun-tasks`。
 */
class VoiceExecutionPlanParityTest {

    private val specRel = "spec/profiles/client/voice_realtime_plan.json"

    /** 从模块工作目录（app/probe）向上找仓库根相对路径（同 ClientProfileDataParityTest 惯例）。 */
    private fun repoFile(relFromRepoRoot: String): File {
        var cur: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (cur != null) {
            val cand = File(cur, relFromRepoRoot)
            if (cand.isFile) return cand
            cur = cur.parentFile
        }
        error("找不到 $relFromRepoRoot（从 user.dir 向上未命中）")
    }

    private fun spec(): JsonObject =
        Json.parseToJsonElement(repoFile(specRel).readText(Charsets.UTF_8)).jsonObject

    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject
    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.dbl(key: String): Double = this[key]!!.jsonPrimitive.content.toDouble()
    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.ints(key: String): List<Int> =
        this[key]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }

    // ---------- 帧节奏与帧大小 ----------

    @Test
    fun `frame cadence and size match VoiceRunner companion`() {
        val f = spec().obj("frame")
        assertEquals("frame.interval_ms", VoiceRunner.FRAME_INTERVAL_MS, f.long("interval_ms"))
        assertEquals("frame.bytes", VoiceRunner.FRAME_BYTES, f.int("bytes"))
    }

    /**
     * 派生值必须由本测试重算，不比对字面量——手填的派生值会随基数漂移而不被察觉。
     * 这里同时钉住 §5.7(c) 那条差集的事实基础：实现是 64kbps，而
     * `PROFILE_FRAMEWORK §4.1` 的业务模型写的是 Opus ~24–40kbps。
     */
    @Test
    fun `derived nominal bitrate is recomputed not transcribed`() {
        val f = spec().obj("frame")
        val recomputed =
            VoiceRunner.FRAME_BYTES * 8.0 / (VoiceRunner.FRAME_INTERVAL_MS / 1000.0) / 1000.0
        assertEquals(
            "frame.derived_nominal_kbps 必须等于由帧参数重算的值",
            recomputed, f.dbl("derived_nominal_kbps"), 1e-9
        )
        assertEquals("重算值本身（防两侧一起改错）", 64.0, recomputed, 1e-9)
    }

    // ---------- v1 paced-proxy ----------

    @Test
    fun `v1 paced proxy frame counts match`() {
        val v1 = spec().obj("v1_paced_proxy")
        assertEquals("v1.uplink_frames", VoiceRunner.UPLINK_FRAMES, v1.int("uplink_frames"))
        assertEquals("v1.downlink_frames", VoiceRunner.DOWNLINK_FRAMES, v1.int("downlink_frames"))
        assertTrue(
            "v1 的 caliber 必须是 JSON null（v1 样本不带 SIM_CALIBER 标注）",
            v1["caliber"] is JsonNull
        )
    }

    // ---------- v2 server-sim：常量 ----------

    @Test
    fun `v2 caliber and m3 frame count match`() {
        val v2 = spec().obj("v2_server_sim")
        assertEquals("v2.caliber", VoiceRunner.SIM_CALIBER, v2.str("caliber"))
        assertEquals("v2.m3_frames", VoiceRunner.SIM_M3_FRAMES, v2.int("m3_frames"))
    }

    // ---------- v2 server-sim：默认 8 轮计划（钉住生成出来的计划，不只是常量） ----------

    /**
     * 最强的一条：不比常量，比 [VoiceRunner.defaultSimPlan] **实际生成**的计划。
     * 常量对得上而生成逻辑改了（比如打断轮从 3/6 改成 2/5），只有这条能咬住。
     */
    @Test
    fun `default sim plan as generated matches the spec export`() {
        val p = spec().obj("v2_server_sim").obj("default_plan")
        val plan: RealtimeWire.SessionPlan = VoiceRunner.defaultSimPlan(seed = 1L)

        assertTrue("session_id 前缀", plan.sessionId.startsWith(p.str("session_id_prefix")))
        assertEquals("setup_ms", p.dbl("setup_ms"), plan.setupMs, 1e-9)
        assertEquals("frame_ms", p.int("frame_ms"), plan.frameMs)
        assertEquals("turns", p.int("turns"), plan.turns.size)

        val expectedInterrupted = p.ints("interrupted_turn_indices").toSet()
        plan.turns.forEachIndexed { i, t ->
            assertEquals("turn[$i].turnIndex", i, t.turnIndex)
            assertEquals(
                "turn[$i].startAfterPreviousMs",
                p.int("start_after_previous_ms"), t.startAfterPreviousMs
            )
            assertEquals("turn[$i].uplinkFrames", p.int("uplink_frames_per_turn"), t.uplinkFrames)
            assertEquals(
                "turn[$i].uplinkFrameBytes",
                p.int("uplink_frame_bytes"), t.uplinkFrameBytes
            )
            assertEquals("turn[$i].responseWaitMs", p.int("response_wait_ms"), t.responseWaitMs)
            assertEquals(
                "turn[$i].plannedDownlinkFrames",
                p.int("planned_downlink_frames_per_turn"), t.plannedDownlinkFrames
            )
            assertEquals(
                "turn[$i].downlinkFrameBytes",
                p.int("downlink_frame_bytes"), t.downlinkFrameBytes
            )

            val shouldInterrupt = i in expectedInterrupted
            assertEquals(
                "turn[$i].interrupted（打断轮位置是 spec 点名的那两轮）",
                shouldInterrupt, t.interrupted
            )
            if (shouldInterrupt) {
                assertEquals(
                    "turn[$i].bargeInAfterFrames",
                    p.int("barge_in_after_frames"), t.bargeInAfterFrames
                )
                assertEquals(
                    "turn[$i].expectedStopWithinMs",
                    p.int("expected_stop_within_ms"), t.expectedStopWithinMs
                )
            } else {
                assertNull("非打断轮的 bargeInAfterFrames 必须缺席", t.bargeInAfterFrames)
                assertNull("非打断轮的 expectedStopWithinMs 必须缺席", t.expectedStopWithinMs)
            }
        }
    }

    // ---------- v2 server-sim：连续性 mini-run 计划 ----------

    @Test
    fun `continuity sim plan as generated matches the spec export`() {
        val c = spec().obj("v2_server_sim").obj("continuity_plan")
        val plan = VoiceRunner.continuitySimPlan(seed = 1L)

        assertTrue("session_id 前缀", plan.sessionId.startsWith(c.str("session_id_prefix")))
        assertEquals("setup_ms", c.dbl("setup_ms"), plan.setupMs, 1e-9)
        assertEquals("frame_ms", c.int("frame_ms"), plan.frameMs)
        assertEquals("turns", c.int("turns"), plan.turns.size)
        assertEquals(
            "disconnect_after_turn",
            VoiceRunner.CONT_DISCONNECT_AFTER_TURN, c.int("disconnect_after_turn")
        )
        assertEquals(
            "uplink_frames_per_turn",
            VoiceRunner.CONT_UPLINK_FRAMES, c.int("uplink_frames_per_turn")
        )
        assertEquals(
            "downlink_frames_per_turn",
            VoiceRunner.CONT_DOWNLINK_FRAMES, c.int("downlink_frames_per_turn")
        )

        plan.turns.forEachIndexed { i, t ->
            assertEquals(
                "cont turn[$i].uplinkFrames",
                c.int("uplink_frames_per_turn"), t.uplinkFrames
            )
            assertEquals(
                "cont turn[$i].plannedDownlinkFrames",
                c.int("downlink_frames_per_turn"), t.plannedDownlinkFrames
            )
            assertEquals("cont turn[$i].responseWaitMs", c.int("response_wait_ms"), t.responseWaitMs)
            assertEquals(
                "cont turn[$i].interrupted（连续性 mini-run 全轮非中断）",
                false, t.interrupted
            )
        }
        assertTrue(
            "断连轮必须落在计划内且非末轮（否则断连点即计划末尾，测不出重建）",
            VoiceRunner.CONT_DISCONNECT_AFTER_TURN < plan.turns.size - 1
        )
    }

    // ---------- 合同限额 ----------

    /**
     * 限额本身是 wire 合同的数，不是 [VoiceRunner] 的常量；这里钉的是
     * **两个计划都在界内**——KDoc 声称「字段逐项过合同限额」，这条让那句话有东西核对。
     */
    @Test
    fun `both plans stay inside the declared wire limits`() {
        val w = spec().obj("wire_limits")
        val maxTurns = w.int("max_turns")
        val range = w.ints("frame_ms_range")

        listOf(
            "defaultSimPlan" to VoiceRunner.defaultSimPlan(seed = 1L),
            "continuitySimPlan" to VoiceRunner.continuitySimPlan(seed = 1L),
        ).forEach { (name, plan) ->
            assertTrue(
                "$name.turns=${plan.turns.size} 必须 ≤ $maxTurns",
                plan.turns.size <= maxTurns
            )
            assertTrue(
                "$name.frameMs=${plan.frameMs} 必须落在 $range 内",
                plan.frameMs >= range[0] && plan.frameMs <= range[1]
            )
        }
    }

    // ---------- 导出的完整性：spec 不许悄悄少一段 ----------

    /**
     * 覆盖类守卫：顶层段落一旦被删，上面每条用例都会因 NPE 而红，但**报出来的原因
     * 会是「空指针」而不是「spec 少了一段」**。这条让缺段以它本来的名字失败。
     */
    @Test
    fun `spec keeps every top level section the parity tests rely on`() {
        val s = spec()
        listOf("frame", "v1_paced_proxy", "v2_server_sim", "wire_limits").forEach {
            assertNotNull("spec 顶层缺段：$it", s[it])
        }
        val v2 = s.obj("v2_server_sim")
        listOf("default_plan", "continuity_plan").forEach {
            assertNotNull("spec v2_server_sim 缺段：$it", v2[it])
        }
        assertEquals("schema_version", "1.0.0", s.str("schema_version"))
        assertEquals("profile_id", "voice_realtime", s.str("profile_id"))
    }
}
