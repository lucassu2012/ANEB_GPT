package com.aneb.probe.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPermissionViewModelTest {
    private val granted = RadioPermissionState(
        phoneStateGranted = true,
        coarseLocationGranted = true,
        fineLocationGranted = true,
    )
    private val denied = RadioPermissionState(
        phoneStateGranted = false,
        coarseLocationGranted = false,
        fineLocationGranted = false,
    )

    @Test
    fun `start test survives radio result and chains notification before exactly one start`() {
        val model = model()
        assertTrue(model.showRadioRationale(RadioPermissionPurpose.START_TEST, denied))
        assertTrue(model.beginRadioRequest())

        assertTrue(model.onRadioResult(granted))
        val requestNotification = model.state.value.event as ManualPermissionEvent.RequestNotification
        assertEquals(requestNotification, model.takeEvent(requestNotification.id))
        assertNull(model.takeEvent(requestNotification.id))

        assertTrue(model.beginNotificationRequest())
        assertTrue(model.onNotificationResult(granted = false))
        val start = model.state.value.event as ManualPermissionEvent.StartTest
        assertEquals(start, model.takeEvent(start.id))
        assertNull(model.takeEvent(start.id))
        assertFalse(model.onNotificationResult(granted = true))
        assertEquals(ManualPermissionFlowState(), model.state.value)
    }

    @Test
    fun `drive test radio result emits exactly one enable event`() {
        val model = model()
        assertTrue(model.showRadioRationale(RadioPermissionPurpose.DRIVE_TEST, denied))
        assertTrue(model.beginRadioRequest())

        assertTrue(model.onRadioResult(granted))
        val enable = model.state.value.event as ManualPermissionEvent.EnableDriveTest
        assertEquals(enable, model.takeEvent(enable.id))
        assertNull(model.takeEvent(enable.id))
        assertFalse(model.onRadioResult(granted))
        assertEquals(ManualPermissionFlowState(), model.state.value)
    }

    @Test
    fun `denied radio result restores persistent prompt without terminal event`() {
        var handle = SavedStateHandle()
        var model = model(handle)
        assertTrue(model.showRadioRationale(RadioPermissionPurpose.START_TEST, denied))
        assertTrue(model.beginRadioRequest())

        assertTrue(model.onRadioResult(denied))
        handle = copyHandle(handle)
        model = model(handle)

        assertEquals(RadioPermissionStage.DENIED, model.state.value.prompt?.stage)
        assertEquals(RadioPermissionPurpose.START_TEST, model.state.value.pendingAction)
        assertEquals(denied, model.state.value.prompt?.state)
        assertNull(model.state.value.event)
        assertFalse(model.onRadioResult(granted))
    }

    @Test
    fun `limited start still chains notification and denial does not block measurement`() {
        val model = model()
        assertTrue(model.showRadioRationale(RadioPermissionPurpose.START_TEST, denied))
        assertTrue(model.continueLimitedStart())
        val requestNotification = model.state.value.event as ManualPermissionEvent.RequestNotification
        assertEquals(requestNotification, model.takeEvent(requestNotification.id))

        assertTrue(model.beginNotificationRequest())
        assertTrue(model.onNotificationResult(granted = false))
        assertTrue(model.state.value.event is ManualPermissionEvent.StartTest)
    }

    @Test
    fun `direct start without notification dialog emits one start`() {
        val model = model()
        assertTrue(model.beginStartTest())
        assertTrue(model.notificationPermissionNotNeeded())
        val start = model.state.value.event as ManualPermissionEvent.StartTest

        assertEquals(start, model.takeEvent(start.id))
        assertNull(model.takeEvent(start.id))
        assertFalse(model.notificationPermissionNotNeeded())
    }

    @Test
    fun `saved state recreation continues the full start chain exactly once`() {
        var handle = SavedStateHandle()
        var model = model(handle)
        assertTrue(model.showRadioRationale(RadioPermissionPurpose.START_TEST, denied))
        assertTrue(model.beginRadioRequest())

        handle = copyHandle(handle)
        model = model(handle)
        assertEquals(RadioPermissionPurpose.START_TEST, model.state.value.pendingAction)
        assertEquals(PendingSystemPermission.RADIO, model.state.value.awaitingSystemPermission)

        assertTrue(model.onRadioResult(granted))
        handle = copyHandle(handle)
        model = model(handle)
        val requestNotification =
            model.state.value.event as ManualPermissionEvent.RequestNotification
        assertEquals(requestNotification, model.takeEvent(requestNotification.id))
        assertNull(model.takeEvent(requestNotification.id))
        assertTrue(model.beginNotificationRequest())

        handle = copyHandle(handle)
        model = model(handle)
        assertEquals(
            PendingSystemPermission.NOTIFICATION,
            model.state.value.awaitingSystemPermission,
        )
        assertTrue(model.onNotificationResult(granted = false))

        handle = copyHandle(handle)
        model = model(handle)
        val start = model.state.value.event as ManualPermissionEvent.StartTest
        assertEquals(start, model.takeEvent(start.id))
        assertNull(model.takeEvent(start.id))
        assertFalse(model.onNotificationResult(granted = true))

        handle = copyHandle(handle)
        model = model(handle)
        assertEquals(ManualPermissionFlowState(), model.state.value)
        assertTrue(model.beginStartTest())
        assertTrue(model.notificationPermissionNotNeeded())
        val nextStart = model.state.value.event as ManualPermissionEvent.StartTest
        assertTrue(nextStart.id > start.id)
    }

    @Test
    fun `duplicate flow requests cannot replace an in flight action`() {
        val model = model()
        assertTrue(model.showRadioRationale(RadioPermissionPurpose.START_TEST, denied))
        assertFalse(model.showRadioRationale(RadioPermissionPurpose.DRIVE_TEST, denied))
        assertFalse(model.beginStartTest())
        assertEquals(RadioPermissionPurpose.START_TEST, model.state.value.pendingAction)
    }

    private fun model(handle: SavedStateHandle = SavedStateHandle()) =
        ManualPermissionViewModel(handle)

    private fun copyHandle(source: SavedStateHandle): SavedStateHandle = SavedStateHandle(
        source.keys().associateWith { key -> source.get<Any?>(key) },
    )
}
