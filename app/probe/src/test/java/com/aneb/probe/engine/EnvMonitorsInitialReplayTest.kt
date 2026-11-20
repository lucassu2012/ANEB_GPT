package com.aneb.probe.engine

import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import com.aneb.probe.data.EnvEventType
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.Executor

/**
 * initial 事件必须到得了晚挂的订阅者（先 start 后 collect）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EnvMonitorsInitialReplayTest {

    @Implements(PowerManager::class)
    class FailingThermalPowerManagerShadow : ShadowPowerManager() {
        @Implementation
        protected fun addThermalStatusListener(
            executor: Executor,
            listener: PowerManager.OnThermalStatusChangedListener,
        ) {
            throw SecurityException("test_listener_registration_failed")
        }

        @Implementation
        override fun getCurrentThermalStatus(): Int {
            throw IllegalStateException("test_status_read_failed")
        }
    }

    @Test
    fun initialEventsReachALateSubscriber() {
        val ctx = RuntimeEnvironment.getApplication()
        val em = EnvMonitors(ctx)
        em.start()                       // TestEngine 的真实时序：先 start……
        try {
            val got = runBlocking {
                withTimeout(5_000) {
                    em.events.take(4).toList()
                }
            }
            val initials = got.filter { it.detail.startsWith("initial") }
            assertTrue(
                "晚挂订阅者应收到 initial 事件（实收: ${got.map { it.type to it.detail }}）",
                initials.size >= 2,
            )
            val types = initials.map { it.type }.toSet()
            assertTrue(
                "initial 应覆盖 POWER_SAVE 与 DOZE（实收: $types）",
                EnvEventType.POWER_SAVE in types && EnvEventType.DOZE in types,
            )
            assertTrue(
                "initial 事件的 detail 必须以 \"initial\" 开头（TestEngine 豁免判据所依赖）",
                initials.all { it.detail.startsWith("initial") },
            )
        } finally {
            em.stop()
        }
    }

    @Test
    @Config(sdk = [35], manifest = Config.NONE, shadows = [FailingThermalPowerManagerShadow::class])
    fun lateSubscriberRetainsAllFiveStartupEventsWhenRegistrationsFail() {
        val baseCtx = RuntimeEnvironment.getApplication()
        val failingCtx = object : ContextWrapper(baseCtx) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
                throw SecurityException("test_receiver_registration_failed")
            }
        }
        val em = EnvMonitors(failingCtx)
        em.start()
        try {
            val got = runBlocking {
                withTimeout(5_000) {
                    em.events.take(5).toList()
                }
            }
            assertEquals(5, got.size)
            assertTrue(got[0].type == EnvEventType.THERMAL && got[0].detail.startsWith("listener_registration_failed:"))
            assertTrue(got[1].type == EnvEventType.THERMAL && got[1].detail == "initial_unknown: status_read_failed")
            assertTrue(got[2].type == EnvEventType.POWER_SAVE && got[2].detail.startsWith("initial power_save="))
            assertTrue(got[3].type == EnvEventType.DOZE && got[3].detail.startsWith("initial device_idle="))
            assertTrue(got[4].type == EnvEventType.DOZE && got[4].detail.startsWith("receiver_registration_failed:"))
        } finally {
            em.stop()
        }
    }
}
