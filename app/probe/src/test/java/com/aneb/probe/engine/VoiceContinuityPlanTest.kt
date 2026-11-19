package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音连续性 mini-run 计划形状锚定（D-41 预定；合同=aneb-realtime-session-v1，
 * TEST_SERVER_CAPABILITIES §2）：3 轮断连计划与 1 轮重建计划的 turn_index 连续性、
 * 非中断轮 barge 字段 null、字段限额合规（服务端 DisallowUnknownFields + 限额校验，
 * 越界整计划被拒）。镜像 [RealtimeWireTest] 的合同锚定风格。
 */
class VoiceContinuityPlanTest {

    @Test
    fun `断连计划3轮_turn_index等于下标_turn_id对应`() {
        val plan = VoiceRunner.continuitySimPlan(seed = 0xABCL)
        assertEquals(3, plan.turns.size)
        plan.turns.forEachIndexed { i, t ->
            assertEquals(i, t.turnIndex)
            assertEquals("t$i", t.turnId)
        }
        assertTrue(plan.sessionId.startsWith("voice-cont-"))
        assertEquals(0xABCL, plan.seed)
    }

    @Test
    fun `全轮非中断_barge字段一律null`() {
        val plans = listOf(VoiceRunner.continuitySimPlan(1L), VoiceRunner.continuityResumePlan(2L))
        plans.flatMap { it.turns }.forEach { t ->
            assertFalse(t.interrupted)
            assertNull(t.bargeInAfterFrames) // interrupted=false → 合同要求缺省/null
            assertNull(t.expectedStopWithinMs)
        }
    }

    @Test
    fun `字段限额合规_轮数帧数字节等待均在合同界内`() {
        for (plan in listOf(VoiceRunner.continuitySimPlan(1L), VoiceRunner.continuityResumePlan(2L))) {
            assertTrue(plan.turns.size in 1..32) // 合同 ≤32 轮
            assertTrue(plan.frameMs in 10..100) // frame_ms∈[10,100]
            assertTrue(plan.setupMs >= 0.0)
            plan.turns.forEach { t ->
                assertTrue(t.uplinkFrames in 1..10_000) // 单方向每轮 ≤10000 帧
                assertTrue(t.plannedDownlinkFrames in 1..10_000)
                assertTrue(t.uplinkFrameBytes in 1..4096) // 单帧 ≤4096B
                assertTrue(t.downlinkFrameBytes in 1..4096)
                assertTrue(t.responseWaitMs >= 0)
                assertTrue(t.startAfterPreviousMs >= 0)
            }
        }
    }

    @Test
    fun `断连轮在合同界内_且非断连计划末轮`() {
        // 合同：controlled_disconnect_after_turn=N（0≤N<32）；轮 2 仅保证断连点非末轮
        assertTrue(VoiceRunner.CONT_DISCONNECT_AFTER_TURN in 0 until 32)
        assertTrue(VoiceRunner.CONT_DISCONNECT_AFTER_TURN < VoiceRunner.continuitySimPlan(1L).turns.size - 1)
    }

    @Test
    fun `重建计划为独立新会话_1轮_session_id与断连计划不同`() {
        val resume = VoiceRunner.continuityResumePlan(seed = 7L)
        assertEquals(1, resume.turns.size)
        assertEquals(0, resume.turns.first().turnIndex)
        assertNotEquals(VoiceRunner.continuitySimPlan(seed = 7L).sessionId, resume.sessionId)
    }
}
