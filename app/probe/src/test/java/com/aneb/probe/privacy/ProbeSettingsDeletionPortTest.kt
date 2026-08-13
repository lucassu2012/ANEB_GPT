package com.aneb.probe.privacy

import android.content.Context
import com.aneb.probe.ui.ProbeSettings
import com.aneb.probe.ui.ProbeSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProbeSettingsDeletionPortTest {
    @Test
    fun `clears the entire owned settings domain and reconstructed store uses defaults`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("probe_settings_v1", Context.MODE_PRIVATE)
        check(
            prefs.edit()
                .putString("server_url", "https://private.example")
                .putString("mode", "FORENSIC")
                .putString("test_mode", "NETWORK_BASIC")
                .putString("transport", "CELLULAR")
                .putBoolean("drive_test", true)
                .putBoolean("server_default_v2_migrated", true)
                .putString("future_owned_key", "private")
                .commit(),
        )

        val result = AndroidProbeSettingsDeletionPort(context).deleteOwnedSettings()

        assertEquals(ProbeSettingsDeletionResult.Cleared, result)
        assertTrue(prefs.all.isEmpty())
        assertEquals(ProbeSettings(), ProbeSettingsStore(context).load())
    }

    @Test
    fun `failed settings commit is state unavailable and never success`() = runBlocking {
        val persistence = FakeProbeSettingsDeletionPersistence(
            emptyBefore = false,
            commitSucceeds = false,
        )

        val result = AndroidProbeSettingsDeletionPort(
            persistence = persistence,
        ).deleteOwnedSettings()

        assertEquals(ProbeSettingsDeletionResult.StateUnavailable, result)
        assertEquals(1, persistence.clearCalls)
    }

    @Test
    fun `settings persistence cancellation propagates the exact cancellation object`() {
        val marker = CancellationException("settings_delete_cancel_marker")
        val persistence = object : ProbeSettingsDeletionPersistence {
            override fun isEmpty(): Boolean = false

            override fun clearAndCommit(): Boolean = throw marker
        }

        val caught = assertThrows(CancellationException::class.java) {
            runBlocking {
                AndroidProbeSettingsDeletionPort(persistence).deleteOwnedSettings()
            }
        }

        assertTrue(caught === marker)
    }

    private class FakeProbeSettingsDeletionPersistence(
        private var emptyBefore: Boolean,
        private val commitSucceeds: Boolean,
    ) : ProbeSettingsDeletionPersistence {
        var clearCalls = 0
            private set

        override fun isEmpty(): Boolean = emptyBefore

        override fun clearAndCommit(): Boolean {
            clearCalls += 1
            if (commitSucceeds) emptyBefore = true
            return commitSucceeds
        }
    }
}
