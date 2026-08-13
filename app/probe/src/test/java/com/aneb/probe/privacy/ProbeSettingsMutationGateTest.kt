package com.aneb.probe.privacy

import android.content.Context
import com.aneb.probe.ui.ProbeSettingsSaveResult
import com.aneb.probe.ui.ProbeSettings
import com.aneb.probe.ui.ProbeSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProbeSettingsMutationGateTest {
    @Test
    fun `settings save is rejected without writing while privacy deletion owns the gate`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val gate = LocalPrivacyOperationGate()
        val deletion = gate.tryAcquireDeletion() as LocalPrivacyDeletionAdmission.Acquired
        val store = ProbeSettingsStore(
            context = context,
            ownedMutationBoundary = LocalPrivacyOwnedMutationBoundary(gate),
        )

        val result = store.saveServerUrl("https://should-not-persist.invalid")

        assertEquals(ProbeSettingsSaveResult.DeletionInProgress, result)
        assertTrue(
            context.getSharedPreferences(ProbeSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
                .all.isEmpty(),
        )
        deletion.lease.close()
    }

    @Test
    fun `settings load during privacy deletion returns defaults without recreating migration state`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val prefs = context.getSharedPreferences(ProbeSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val gate = LocalPrivacyOperationGate()
        val deletion = gate.tryAcquireDeletion() as LocalPrivacyDeletionAdmission.Acquired
        val store = ProbeSettingsStore(
            context = context,
            ownedMutationBoundary = LocalPrivacyOwnedMutationBoundary(gate),
        )

        val loaded = store.load()

        assertEquals(ProbeSettings(), loaded)
        assertTrue(prefs.all.isEmpty())
        deletion.lease.close()
    }
}
