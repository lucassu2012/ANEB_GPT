package com.aneb.probe.engine

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.aneb.probe.privacy.LocalPrivacyDeletionAdmission
import com.aneb.probe.privacy.LocalPrivacyOperationGate
import com.aneb.probe.privacy.LocalPrivacyProcessOperations
import com.aneb.probe.privacy.LocalPrivacyRunReservationResult
import com.aneb.probe.privacy.LocalPrivacyRunStartBoundary
import com.aneb.probe.privacy.ProbeOwnedRun
import java.util.UUID
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProbeServicePrivacyGateTest {
    @Test
    fun `primary start without a gateway token omits the credential extra and reaches normal handoff`() {
        ProbeRunService.clearRetainedStateForPrivacyDeletion()
        val context = CapturingStartContext(RuntimeEnvironment.getApplication())
        ProbeRunService.start(
            context = context,
            config = ProbeRunService.Config(
                serverBase = "",
                mode = TestEngine.Mode.QUICK,
                transport = TestEngine.TransportMode.AUTO,
                inject = null,
                driveTest = false,
                gatewayToken = null,
            ),
            autorun = false,
        )

        assertFalse(context.capturedIntent.hasExtra("gateway_credential_handle"))
        val controller = Robolectric.buildService(ProbeRunService::class.java)
        val service = controller.get()
        try {
            service.onStartCommand(context.capturedIntent, 0, 1)

            assertTrue(ProbeRunService.session.value is ProbeRunSession.Failed)
            assertNull(ReflectionHelpers.getField<Job?>(service, "runJob"))
            val afterDelivery = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
            assertTrue(afterDelivery is LocalPrivacyDeletionAdmission.Acquired)
            (afterDelivery as LocalPrivacyDeletionAdmission.Acquired).lease.close()
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `explicit missing primary credential rejects the handoff without run mutation`() {
        ProbeRunService.clearRetainedStateForPrivacyDeletion()
        GatewayCredentialVault.clear()
        val context = CapturingStartContext(RuntimeEnvironment.getApplication())
        ProbeRunService.start(
            context = context,
            config = ProbeRunService.Config(
                serverBase = "",
                mode = TestEngine.Mode.QUICK,
                transport = TestEngine.TransportMode.AUTO,
                inject = null,
                driveTest = false,
                gatewayToken = "a".repeat(64),
            ),
            autorun = false,
        )
        val delivery = Intent(context.capturedIntent)
        val credentialHandle = delivery.getStringExtra("gateway_credential_handle")
            ?: error("missing_credential_handle")
        val reservationHandle = delivery.getStringExtra("privacy_run_reservation_handle")
            ?: error("missing_reservation_handle")
        GatewayCredentialVault.discardForReservation(credentialHandle, reservationHandle)
        val foreignCredential = GatewayCredentialVault.put("b".repeat(64))
        val sessionBefore = ProbeRunService.session.value
        val logsBefore = ProbeRunService.logs.value
        val telemetryBefore = ProbeRunService.telemetry.value
        val controller = Robolectric.buildService(ProbeRunService::class.java)
        val service = controller.get()
        val shadow = Shadows.shadowOf(service)

        try {
            service.onStartCommand(delivery, 0, 1)

            assertEquals(0, shadow.lastForegroundNotificationId)
            assertNull(ReflectionHelpers.getField<Job?>(service, "runJob"))
            assertEquals(sessionBefore, ProbeRunService.session.value)
            assertEquals(logsBefore, ProbeRunService.logs.value)
            assertEquals(telemetryBefore, ProbeRunService.telemetry.value)
            assertTrue(ProbeRunService.retainedStateIsPrivacyDefault())
            val afterRejectedHandoff = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
            assertTrue(afterRejectedHandoff is LocalPrivacyDeletionAdmission.Acquired)
            (afterRejectedHandoff as LocalPrivacyDeletionAdmission.Acquired).lease.close()

            service.onStartCommand(Intent(context.capturedIntent), 0, 2)
            assertNull(ReflectionHelpers.getField<Job?>(service, "runJob"))
            assertEquals(sessionBefore, ProbeRunService.session.value)
            assertTrue(ProbeRunService.retainedStateIsPrivacyDefault())
            assertEquals("b".repeat(64), GatewayCredentialVault.take(foreignCredential))
        } finally {
            GatewayCredentialVault.discard(foreignCredential)
            LocalPrivacyProcessOperations.runStartBoundary.abandon(reservationHandle)
            controller.destroy()
        }
    }

    @Test
    fun `continuity delivery runs once and replay cannot consume a newer reservation`() {
        val firstContext = CapturingStartContext(RuntimeEnvironment.getApplication())
        ProbeSpecialRunService.startContinuity(
            context = firstContext,
            server = "",
            transport = TestEngine.TransportMode.AUTO,
            tokens = 1,
            c3IdleSeconds = listOf(1),
        )
        val firstDelivery = Intent(firstContext.capturedIntent)
        val replayDelivery = Intent(firstContext.capturedIntent)
        val firstController = Robolectric.buildService(ProbeSpecialRunService::class.java)
        val firstService = firstController.get()

        firstService.onStartCommand(firstDelivery, 0, 1)

        val sessionAfterFirst = ProbeSpecialRunService.session.value
        assertTrue(sessionAfterFirst is SpecialRunSession.Failed)
        assertTrue(Shadows.shadowOf(firstService).isStoppedBySelf)
        assertNull(ReflectionHelpers.getField<Job?>(firstService, "job"))
        val afterFirst = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
        assertTrue(afterFirst is LocalPrivacyDeletionAdmission.Acquired)
        (afterFirst as LocalPrivacyDeletionAdmission.Acquired).lease.close()

        val nextContext = CapturingStartContext(RuntimeEnvironment.getApplication())
        ProbeSpecialRunService.startContinuity(
            context = nextContext,
            server = "",
            transport = TestEngine.TransportMode.AUTO,
            tokens = 1,
            c3IdleSeconds = listOf(1),
        )
        val replayController = Robolectric.buildService(ProbeSpecialRunService::class.java)
        val replayService = replayController.get()
        try {
            replayService.onStartCommand(replayDelivery, 0, 2)

            assertEquals(sessionAfterFirst, ProbeSpecialRunService.session.value)
            assertFalse(Shadows.shadowOf(replayService).isStoppedBySelf)
            assertNull(ReflectionHelpers.getField<Job?>(replayService, "job"))
            assertTrue(
                LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion() is
                    LocalPrivacyDeletionAdmission.BlockedByActiveRun,
            )
        } finally {
            val nextController = Robolectric.buildService(ProbeSpecialRunService::class.java)
            nextController.get().onStartCommand(nextContext.capturedIntent, 0, 3)
            nextController.destroy()
            replayController.destroy()
            firstController.destroy()
        }

        val afterNextDelivery = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
        assertTrue(afterNextDelivery is LocalPrivacyDeletionAdmission.Acquired)
        (afterNextDelivery as LocalPrivacyDeletionAdmission.Acquired).lease.close()
    }

    @Test
    fun `protocol AB action cannot consume a pending continuity reservation`() {
        val context = CapturingStartContext(RuntimeEnvironment.getApplication())
        ProbeSpecialRunService.startContinuity(
            context = context,
            server = "",
            transport = TestEngine.TransportMode.AUTO,
            tokens = 1,
            c3IdleSeconds = listOf(1),
        )
        val continuityIntent = Intent(context.capturedIntent)
        val wrongKindIntent = Intent(continuityIntent)
            .setAction("com.aneb.probe.action.START_PROTOCOL_AB")
        val sessionBefore = ProbeSpecialRunService.session.value
        val logsBefore = ProbeSpecialRunService.logs.value
        val controller = Robolectric.buildService(ProbeSpecialRunService::class.java)
        val service = controller.get()

        try {
            service.onStartCommand(wrongKindIntent, 0, 1)

            assertEquals(sessionBefore, ProbeSpecialRunService.session.value)
            assertEquals(logsBefore, ProbeSpecialRunService.logs.value)
            assertTrue(
                LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion() is
                    LocalPrivacyDeletionAdmission.BlockedByActiveRun,
            )
        } finally {
            service.onStartCommand(continuityIntent, 0, 2)
            controller.destroy()
        }

        val afterValidDelivery = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
        assertTrue(afterValidDelivery is LocalPrivacyDeletionAdmission.Acquired)
        (afterValidDelivery as LocalPrivacyDeletionAdmission.Acquired).lease.close()
    }

    @Test
    fun `destroying an old primary service cannot clear a newer pending credential`() {
        GatewayCredentialVault.clear()
        val oldService = Robolectric.buildService(ProbeRunService::class.java).create()
        val context = CapturingStartContext(RuntimeEnvironment.getApplication())
        val token = "f".repeat(64)

        ProbeRunService.start(
            context = context,
            config = ProbeRunService.Config(
                serverBase = "",
                mode = TestEngine.Mode.QUICK,
                transport = TestEngine.TransportMode.AUTO,
                inject = null,
                driveTest = false,
                gatewayToken = token,
            ),
            autorun = false,
        )
        val credentialHandle = context.capturedIntent
            .getStringExtra("gateway_credential_handle")
        val reservationHandle = context.capturedIntent
            .getStringExtra("privacy_run_reservation_handle")
            ?: error("missing_reservation_handle")

        try {
            val beforeDestroy = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
            assertTrue(beforeDestroy is LocalPrivacyDeletionAdmission.BlockedByActiveRun)

            oldService.destroy()

            assertEquals(
                token,
                GatewayCredentialVault.takeForReservation(
                    credentialHandle,
                    reservationHandle,
                ),
            )
            val afterDestroy = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
            assertTrue(afterDestroy is LocalPrivacyDeletionAdmission.BlockedByActiveRun)
        } finally {
            LocalPrivacyProcessOperations.runStartBoundary.abandon(reservationHandle)
            GatewayCredentialVault.discardForReservation(
                credentialHandle,
                reservationHandle,
            )
        }
        val afterAbandon = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
        assertTrue(afterAbandon is LocalPrivacyDeletionAdmission.Acquired)
        (afterAbandon as LocalPrivacyDeletionAdmission.Acquired).lease.close()
    }

    @Test
    fun `invalid primary service intent cannot discard a real pending credential or reservation`() {
        val invalidReservationHandles = buildList<String?> {
            add(null)
            add(UUID.randomUUID().toString())
            val foreignBoundary = LocalPrivacyRunStartBoundary(LocalPrivacyOperationGate())
            val foreignReservation = foreignBoundary.reserve(ProbeOwnedRun.PRIMARY)
                as LocalPrivacyRunReservationResult.Reserved
            add(foreignReservation.handle)
            assertTrue(foreignBoundary.abandon(foreignReservation.handle))
        }

        invalidReservationHandles.forEachIndexed { index, invalidReservationHandle ->
            GatewayCredentialVault.clear()
            val context = CapturingStartContext(RuntimeEnvironment.getApplication())
            ProbeRunService.start(
                context = context,
                config = ProbeRunService.Config(
                    serverBase = "",
                    mode = TestEngine.Mode.QUICK,
                    transport = TestEngine.TransportMode.AUTO,
                    inject = null,
                    driveTest = false,
                    gatewayToken = "e".repeat(64),
                ),
                autorun = false,
            )
            val validIntent = Intent(context.capturedIntent)
            val invalidIntent = Intent(validIntent).apply {
                if (invalidReservationHandle == null) {
                    removeExtra("privacy_run_reservation_handle")
                } else {
                    putExtra("privacy_run_reservation_handle", invalidReservationHandle)
                }
            }
            val controller = Robolectric.buildService(ProbeRunService::class.java)
            val service = controller.get()

            service.onStartCommand(invalidIntent, 0, index + 1)

            assertEquals(1, GatewayCredentialVault.ownedReferenceCount())
            assertTrue(
                LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion() is
                    LocalPrivacyDeletionAdmission.BlockedByActiveRun,
            )

            service.onStartCommand(validIntent, 0, index + 10)
            controller.destroy()
            val deletion = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
            assertTrue(deletion is LocalPrivacyDeletionAdmission.Acquired)
            (deletion as LocalPrivacyDeletionAdmission.Acquired).lease.close()
            assertEquals(0, GatewayCredentialVault.ownedReferenceCount())
        }
    }

    @Test
    fun `accepted but undelivered primary start keeps privacy deletion blocked`() {
        val context = CapturingStartContext(RuntimeEnvironment.getApplication())
        ProbeRunService.start(
            context = context,
            config = ProbeRunService.Config(
                serverBase = "",
                mode = TestEngine.Mode.QUICK,
                transport = TestEngine.TransportMode.AUTO,
                inject = null,
                driveTest = false,
                gatewayToken = "a".repeat(64),
            ),
            autorun = false,
        )

        val deletion = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
        try {
            assertTrue(deletion is LocalPrivacyDeletionAdmission.BlockedByActiveRun)
        } finally {
            if (deletion is LocalPrivacyDeletionAdmission.Acquired) deletion.lease.close()
            val serviceController = Robolectric.buildService(ProbeRunService::class.java)
            serviceController.get().onStartCommand(context.capturedIntent, 0, 1)
            serviceController.destroy()
        }

        val afterDelivery = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
        assertTrue(afterDelivery is LocalPrivacyDeletionAdmission.Acquired)
        (afterDelivery as LocalPrivacyDeletionAdmission.Acquired).lease.close()
        assertFalse(context.capturedIntent.hasExtra("gateway_credential_handle"))
    }

    @Test
    fun `accepted primary launch binds credential cleanup to reservation retirement`() {
        GatewayCredentialVault.clear()
        val context = CapturingStartContext(RuntimeEnvironment.getApplication())
        ProbeRunService.start(
            context = context,
            config = ProbeRunService.Config(
                serverBase = "",
                mode = TestEngine.Mode.QUICK,
                transport = TestEngine.TransportMode.AUTO,
                inject = null,
                driveTest = false,
                gatewayToken = "f".repeat(64),
            ),
            autorun = false,
        )
        val reservationHandle = context.capturedIntent
            .getStringExtra("privacy_run_reservation_handle")
            ?: error("missing_reservation_handle")
        assertEquals(1, GatewayCredentialVault.ownedReferenceCount())

        try {
            assertTrue(
                LocalPrivacyProcessOperations.runStartBoundary.abandon(reservationHandle),
            )
            assertEquals(0, GatewayCredentialVault.ownedReferenceCount())
            val deletion = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
            assertTrue(deletion is LocalPrivacyDeletionAdmission.Acquired)
            (deletion as LocalPrivacyDeletionAdmission.Acquired).lease.close()
        } finally {
            LocalPrivacyProcessOperations.runStartBoundary.abandon(reservationHandle)
            GatewayCredentialVault.clear()
        }
    }

    @Test
    fun `accepted but undelivered special start keeps privacy deletion blocked`() {
        val context = CapturingStartContext(RuntimeEnvironment.getApplication())
        ProbeSpecialRunService.startContinuity(
            context = context,
            server = "",
            transport = TestEngine.TransportMode.AUTO,
            tokens = 1,
            c3IdleSeconds = listOf(1),
        )

        val deletion = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
        try {
            assertTrue(deletion is LocalPrivacyDeletionAdmission.BlockedByActiveRun)
        } finally {
            if (deletion is LocalPrivacyDeletionAdmission.Acquired) deletion.lease.close()
            val controller = Robolectric.buildService(ProbeSpecialRunService::class.java)
            controller.get().onStartCommand(context.capturedIntent, 0, 1)
            controller.destroy()
        }

        val afterDelivery = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
        assertTrue(afterDelivery is LocalPrivacyDeletionAdmission.Acquired)
        (afterDelivery as LocalPrivacyDeletionAdmission.Acquired).lease.close()
    }

    @Test
    fun `null primary launch result abandons reservation and credential`() {
        val context = NullStartContext(RuntimeEnvironment.getApplication())

        ProbeRunService.start(
            context = context,
            config = ProbeRunService.Config(
                serverBase = "https://measurement.invalid",
                mode = TestEngine.Mode.QUICK,
                transport = TestEngine.TransportMode.AUTO,
                inject = null,
                driveTest = false,
                gatewayToken = "b".repeat(64),
            ),
            autorun = false,
        )

        val deletion = LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
        try {
            assertTrue(deletion is LocalPrivacyDeletionAdmission.Acquired)
            assertEquals(0, GatewayCredentialVault.ownedReferenceCount())
        } finally {
            if (deletion is LocalPrivacyDeletionAdmission.Acquired) deletion.lease.close()
            if (deletion !is LocalPrivacyDeletionAdmission.Acquired) {
                val controller = Robolectric.buildService(ProbeRunService::class.java)
                context.capturedIntent.putExtra("server", "")
                controller.get().onStartCommand(context.capturedIntent, 0, 1)
                controller.destroy()
            }
        }
    }

    @Test
    fun `stale primary reservation delivery discards credential and does not start`() {
        val reservation = LocalPrivacyProcessOperations.runStartBoundary.reserve(
            com.aneb.probe.privacy.ProbeOwnedRun.PRIMARY,
        ) as com.aneb.probe.privacy.LocalPrivacyRunReservationResult.Reserved
        assertTrue(LocalPrivacyProcessOperations.runStartBoundary.abandon(reservation.handle))
        val credential = GatewayCredentialVault.put("c".repeat(64))
        val serviceController = Robolectric.buildService(ProbeRunService::class.java)
        val service = serviceController.get()
        val sessionBefore = ProbeRunService.session.value
        val intent = Intent(service, ProbeRunService::class.java)
            .setAction("com.aneb.probe.action.START_RUN")
            .putExtra("server", "https://measurement.invalid")
            .putExtra("privacy_run_reservation_handle", reservation.handle)
            .putExtra("gateway_credential_handle", credential)

        service.onStartCommand(intent, 0, 1)

        assertFalse(intent.hasExtra("gateway_credential_handle"))
        assertNull(GatewayCredentialVault.take(credential))
        assertEquals(sessionBefore, ProbeRunService.session.value)
        assertNull(ReflectionHelpers.getField<Job?>(service, "runJob"))
        serviceController.destroy()
    }

    @Test
    fun `unreserved primary service intent is rejected before any run mutation`() {
        val credential = GatewayCredentialVault.put("d".repeat(64))
        val serviceController = Robolectric.buildService(ProbeRunService::class.java)
        val service = serviceController.get()
        val sessionBefore = ProbeRunService.session.value
        val intent = Intent(service, ProbeRunService::class.java)
            .setAction("com.aneb.probe.action.START_RUN")
            .putExtra("server", "https://measurement.invalid")
            .putExtra("gateway_credential_handle", credential)

        service.onStartCommand(intent, 0, 1)

        assertFalse(intent.hasExtra("gateway_credential_handle"))
        assertNull(GatewayCredentialVault.take(credential))
        assertEquals(sessionBefore, ProbeRunService.session.value)
        assertNull(ReflectionHelpers.getField<Job?>(service, "runJob"))
        serviceController.destroy()
    }

    @Test
    fun `primary start blocked by deletion leaves retained state unchanged`() {
        val deletionAdmission =
            LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
                as LocalPrivacyDeletionAdmission.Acquired
        val sessionBefore = ProbeRunService.session.value
        val logsBefore = ProbeRunService.logs.value
        val serviceController = Robolectric.buildService(ProbeRunService::class.java)
        val service = serviceController.get()
        val intent = Intent(service, ProbeRunService::class.java)
            .setAction("com.aneb.probe.action.START_RUN")
            .putExtra("server", "")

        try {
            service.onStartCommand(intent, 0, 1)

            assertEquals(sessionBefore, ProbeRunService.session.value)
            assertEquals(logsBefore, ProbeRunService.logs.value)
        } finally {
            deletionAdmission.lease.close()
        }
    }

    @Test
    fun `special start blocked by deletion leaves retained state unchanged`() {
        val deletionAdmission =
            LocalPrivacyProcessOperations.operationGate.tryAcquireDeletion()
                as LocalPrivacyDeletionAdmission.Acquired
        val sessionBefore = ProbeSpecialRunService.session.value
        val logsBefore = ProbeSpecialRunService.logs.value
        val serviceController = Robolectric.buildService(ProbeSpecialRunService::class.java)
        val service = serviceController.get()
        val intent = Intent(service, ProbeSpecialRunService::class.java)
            .setAction("com.aneb.probe.action.START_CONTINUITY")
            .putExtra("server", "")

        try {
            service.onStartCommand(intent, 0, 1)

            assertEquals(sessionBefore, ProbeSpecialRunService.session.value)
            assertEquals(logsBefore, ProbeSpecialRunService.logs.value)
        } finally {
            deletionAdmission.lease.close()
        }
    }

    @Test
    fun `duplicate primary start discards its unused gateway credential`() {
        val service = Robolectric.buildService(ProbeRunService::class.java).get()
        ReflectionHelpers.setField(service, "runJob", Job())
        val handle = GatewayCredentialVault.put("a".repeat(64))
        val intent = Intent(service, ProbeRunService::class.java)
            .setAction("com.aneb.probe.action.START_RUN")
            .putExtra("gateway_credential_handle", handle)

        service.onStartCommand(intent, 0, 1)

        assertFalse(intent.hasExtra("gateway_credential_handle"))
        assertNull(GatewayCredentialVault.take(handle))
    }

    private class CapturingStartContext(base: Context) : ContextWrapper(base) {
        lateinit var capturedIntent: Intent

        override fun startForegroundService(service: Intent): ComponentName {
            capturedIntent = Intent(service)
            return ComponentName(packageName, ProbeRunService::class.java.name)
        }
    }

    private class NullStartContext(base: Context) : ContextWrapper(base) {
        lateinit var capturedIntent: Intent

        override fun startForegroundService(service: Intent): ComponentName? {
            capturedIntent = Intent(service)
            return null
        }
    }
}
