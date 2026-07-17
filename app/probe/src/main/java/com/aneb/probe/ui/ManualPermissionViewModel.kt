package com.aneb.probe.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PendingSystemPermission { RADIO, NOTIFICATION }

internal sealed interface ManualPermissionEvent {
    val id: Long

    data class RequestNotification(override val id: Long) : ManualPermissionEvent
    data class StartTest(override val id: Long) : ManualPermissionEvent
    data class EnableDriveTest(override val id: Long) : ManualPermissionEvent
}

internal data class ManualPermissionFlowState(
    val pendingAction: RadioPermissionPurpose? = null,
    val awaitingSystemPermission: PendingSystemPermission? = null,
    val prompt: RadioPermissionPrompt? = null,
    val event: ManualPermissionEvent? = null,
)

/**
 * Retains only permission-flow values across Activity and process recreation. Activity and Compose
 * callbacks stay in the current Activity; terminal work is delivered as an explicitly claimed
 * StateFlow event.
 */
internal class ManualPermissionViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val lock = Any()
    private var nextEventId: Long = savedStateHandle[KEY_NEXT_EVENT_ID] ?: FIRST_EVENT_ID
    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<ManualPermissionFlowState> = _state.asStateFlow()

    init {
        persistState(_state.value)
    }

    fun beginStartTest(): Boolean = synchronized(lock) {
        if (!_state.value.isIdle()) return@synchronized false
        setState(ManualPermissionFlowState(pendingAction = RadioPermissionPurpose.START_TEST))
        true
    }

    fun showRadioRationale(
        purpose: RadioPermissionPurpose,
        radioState: RadioPermissionState,
    ): Boolean = synchronized(lock) {
        if (!_state.value.isIdle()) return@synchronized false
        setState(ManualPermissionFlowState(
            pendingAction = purpose,
            prompt = RadioPermissionPrompt(
                purpose = purpose,
                stage = RadioPermissionStage.RATIONALE,
                state = radioState,
            ),
        ))
        true
    }

    fun beginRadioRequest(): Boolean = synchronized(lock) {
        val current = _state.value
        val purpose = current.pendingAction ?: return@synchronized false
        if (
            current.awaitingSystemPermission != null ||
            current.event != null ||
            current.prompt?.purpose != purpose
        ) {
            return@synchronized false
        }
        setState(current.copy(
            awaitingSystemPermission = PendingSystemPermission.RADIO,
            prompt = null,
        ))
        true
    }

    fun onRadioResult(radioState: RadioPermissionState): Boolean = synchronized(lock) {
        val current = _state.value
        val purpose = current.pendingAction ?: return@synchronized false
        if (current.awaitingSystemPermission != PendingSystemPermission.RADIO) {
            return@synchronized false
        }
        setState(if (radioState.hasFullRadioEvidence) {
            current.copy(
                awaitingSystemPermission = null,
                prompt = null,
                event = when (purpose) {
                    RadioPermissionPurpose.START_TEST ->
                        ManualPermissionEvent.RequestNotification(allocateEventId())
                    RadioPermissionPurpose.DRIVE_TEST ->
                        ManualPermissionEvent.EnableDriveTest(allocateEventId())
                },
            )
        } else {
            current.copy(
                awaitingSystemPermission = null,
                prompt = RadioPermissionPrompt(
                    purpose = purpose,
                    stage = RadioPermissionStage.DENIED,
                    state = radioState,
                ),
            )
        })
        true
    }

    fun continueLimitedStart(): Boolean = synchronized(lock) {
        val current = _state.value
        if (
            current.pendingAction != RadioPermissionPurpose.START_TEST ||
            current.awaitingSystemPermission != null ||
            current.event != null ||
            current.prompt?.purpose != RadioPermissionPurpose.START_TEST
        ) {
            return@synchronized false
        }
        setState(current.copy(
            prompt = null,
            event = ManualPermissionEvent.RequestNotification(allocateEventId()),
        ))
        true
    }

    fun beginNotificationRequest(): Boolean = synchronized(lock) {
        val current = _state.value
        if (!current.canContinueStart()) return@synchronized false
        setState(current.copy(
            awaitingSystemPermission = PendingSystemPermission.NOTIFICATION,
        ))
        true
    }

    fun notificationPermissionNotNeeded(): Boolean = synchronized(lock) {
        val current = _state.value
        if (!current.canContinueStart()) return@synchronized false
        setState(current.copy(event = ManualPermissionEvent.StartTest(allocateEventId())))
        true
    }

    /** Notification denial intentionally completes the chain: it never blocks the measurement. */
    fun onNotificationResult(@Suppress("UNUSED_PARAMETER") granted: Boolean): Boolean = synchronized(lock) {
        val current = _state.value
        if (
            current.pendingAction != RadioPermissionPurpose.START_TEST ||
            current.awaitingSystemPermission != PendingSystemPermission.NOTIFICATION ||
            current.event != null
        ) {
            return@synchronized false
        }
        setState(current.copy(
            awaitingSystemPermission = null,
            event = ManualPermissionEvent.StartTest(allocateEventId()),
        ))
        true
    }

    /** Atomically claims an event so recreated or duplicate collectors cannot repeat terminal work. */
    fun takeEvent(id: Long): ManualPermissionEvent? = synchronized(lock) {
        val current = _state.value
        val event = current.event?.takeIf { it.id == id } ?: return@synchronized null
        setState(when (event) {
            is ManualPermissionEvent.RequestNotification -> current.copy(event = null)
            is ManualPermissionEvent.StartTest,
            is ManualPermissionEvent.EnableDriveTest,
            -> ManualPermissionFlowState()
        })
        event
    }

    fun cancelPending(): Boolean = synchronized(lock) {
        if (_state.value.isIdle()) return@synchronized false
        setState(ManualPermissionFlowState())
        true
    }

    private fun allocateEventId(): Long = nextEventId.also {
        nextEventId += 1
        savedStateHandle[KEY_NEXT_EVENT_ID] = nextEventId
    }

    private fun setState(value: ManualPermissionFlowState) {
        persistState(value)
        _state.value = value
    }

    private fun restoreState(): ManualPermissionFlowState {
        val pendingAction = savedStateHandle.enumValueOrNull<RadioPermissionPurpose>(
            KEY_PENDING_ACTION,
        )
        val awaitingSystemPermission =
            savedStateHandle.enumValueOrNull<PendingSystemPermission>(KEY_AWAITING_PERMISSION)
        val promptPurpose =
            savedStateHandle.enumValueOrNull<RadioPermissionPurpose>(KEY_PROMPT_PURPOSE)
        val promptStage = savedStateHandle.enumValueOrNull<RadioPermissionStage>(KEY_PROMPT_STAGE)
        val promptPhone = savedStateHandle.get<Boolean>(KEY_PROMPT_PHONE)
        val promptCoarse = savedStateHandle.get<Boolean>(KEY_PROMPT_COARSE)
        val promptFine = savedStateHandle.get<Boolean>(KEY_PROMPT_FINE)
        val prompt = if (
            promptPurpose != null &&
            promptStage != null &&
            promptPhone != null &&
            promptCoarse != null &&
            promptFine != null
        ) {
            RadioPermissionPrompt(
                purpose = promptPurpose,
                stage = promptStage,
                state = RadioPermissionState(
                    phoneStateGranted = promptPhone,
                    coarseLocationGranted = promptCoarse,
                    fineLocationGranted = promptFine,
                ),
            )
        } else {
            null
        }
        val eventId = savedStateHandle.get<Long>(KEY_EVENT_ID)
        val event = if (eventId == null) {
            null
        } else {
            when (savedStateHandle.get<String>(KEY_EVENT_TYPE)) {
                EVENT_REQUEST_NOTIFICATION -> ManualPermissionEvent.RequestNotification(eventId)
                EVENT_START_TEST -> ManualPermissionEvent.StartTest(eventId)
                EVENT_ENABLE_DRIVE_TEST -> ManualPermissionEvent.EnableDriveTest(eventId)
                else -> null
            }
        }
        return ManualPermissionFlowState(
            pendingAction = pendingAction,
            awaitingSystemPermission = awaitingSystemPermission,
            prompt = prompt,
            event = event,
        )
    }

    private fun persistState(value: ManualPermissionFlowState) {
        savedStateHandle.putOrRemove(KEY_PENDING_ACTION, value.pendingAction?.name)
        savedStateHandle.putOrRemove(
            KEY_AWAITING_PERMISSION,
            value.awaitingSystemPermission?.name,
        )
        savedStateHandle.putOrRemove(KEY_PROMPT_PURPOSE, value.prompt?.purpose?.name)
        savedStateHandle.putOrRemove(KEY_PROMPT_STAGE, value.prompt?.stage?.name)
        savedStateHandle.putOrRemove(KEY_PROMPT_PHONE, value.prompt?.state?.phoneStateGranted)
        savedStateHandle.putOrRemove(KEY_PROMPT_COARSE, value.prompt?.state?.coarseLocationGranted)
        savedStateHandle.putOrRemove(KEY_PROMPT_FINE, value.prompt?.state?.fineLocationGranted)
        savedStateHandle.putOrRemove(
            KEY_EVENT_TYPE,
            when (value.event) {
                is ManualPermissionEvent.RequestNotification -> EVENT_REQUEST_NOTIFICATION
                is ManualPermissionEvent.StartTest -> EVENT_START_TEST
                is ManualPermissionEvent.EnableDriveTest -> EVENT_ENABLE_DRIVE_TEST
                null -> null
            },
        )
        savedStateHandle.putOrRemove(KEY_EVENT_ID, value.event?.id)
        savedStateHandle[KEY_NEXT_EVENT_ID] = nextEventId
    }

    private inline fun <reified T : Enum<T>> SavedStateHandle.enumValueOrNull(key: String): T? =
        get<String>(key)?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } }

    private fun <T> SavedStateHandle.putOrRemove(key: String, value: T?) {
        if (value == null) remove<T>(key) else set(key, value)
    }

    private fun ManualPermissionFlowState.isIdle(): Boolean =
        pendingAction == null && awaitingSystemPermission == null && prompt == null && event == null

    private fun ManualPermissionFlowState.canContinueStart(): Boolean =
        pendingAction == RadioPermissionPurpose.START_TEST &&
            awaitingSystemPermission == null &&
            prompt == null &&
            event == null

    private companion object {
        const val FIRST_EVENT_ID = 1L
        const val KEY_PENDING_ACTION = "pending_action"
        const val KEY_AWAITING_PERMISSION = "awaiting_permission"
        const val KEY_PROMPT_PURPOSE = "prompt_purpose"
        const val KEY_PROMPT_STAGE = "prompt_stage"
        const val KEY_PROMPT_PHONE = "prompt_phone"
        const val KEY_PROMPT_COARSE = "prompt_coarse"
        const val KEY_PROMPT_FINE = "prompt_fine"
        const val KEY_EVENT_TYPE = "event_type"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_NEXT_EVENT_ID = "next_event_id"
        const val EVENT_REQUEST_NOTIFICATION = "request_notification"
        const val EVENT_START_TEST = "start_test"
        const val EVENT_ENABLE_DRIVE_TEST = "enable_drive_test"
    }
}
