package com.aneb.probe.ui

import android.content.Intent
import com.aneb.probe.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ReleaseSafeComponentActivityReleaseBehaviorTest {
    @Test
    fun `release scrubs hostile extras before one framework delivery`() {
        assertFalse(BuildConfig.DEBUG)

        Robolectric.buildActivity(ProbeActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            val hostile = Intent()
                .putExtra("server", "https://attacker.example")
                .putExtra("autorun", true)
                .putExtra("gateway_token", "secret")
                .putExtra("future_unknown", "secret")
            var frameworkDeliveries = 0

            activity.addOnNewIntentListener { observed ->
                frameworkDeliveries += 1
                assertSame(hostile, observed)
                assertTrue(observed.extras?.keySet().orEmpty().isEmpty())
            }

            controller.newIntent(hostile)

            assertEquals(1, frameworkDeliveries)
            assertTrue(hostile.extras?.keySet().orEmpty().isEmpty())
        }
    }

    class ProbeActivity : ReleaseSafeComponentActivity()
}
