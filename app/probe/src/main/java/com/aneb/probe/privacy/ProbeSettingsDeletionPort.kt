package com.aneb.probe.privacy

import android.content.Context
import com.aneb.probe.ui.ProbeSettingsProcessState
import com.aneb.probe.ui.ProbeSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface ProbeSettingsDeletionResult {
    data object Cleared : ProbeSettingsDeletionResult
    data object AlreadyAbsent : ProbeSettingsDeletionResult
    data object ResidueConfirmed : ProbeSettingsDeletionResult
    data object StateUnavailable : ProbeSettingsDeletionResult
}

internal fun interface ProbeSettingsDeletionPort {
    suspend fun deleteOwnedSettings(): ProbeSettingsDeletionResult
}

internal interface ProbeSettingsDeletionPersistence {
    fun isEmpty(): Boolean
    fun clearAndCommit(): Boolean
}

internal class AndroidProbeSettingsDeletionPort internal constructor(
    private val persistence: ProbeSettingsDeletionPersistence,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProbeSettingsDeletionPort {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        persistence = SharedPreferencesProbeSettingsDeletionPersistence(context),
        ioDispatcher = ioDispatcher,
    )

    override suspend fun deleteOwnedSettings(): ProbeSettingsDeletionResult =
        withContext(ioDispatcher) {
            runCatching {
                synchronized(ProbeSettingsProcessState.lock) {
                    val wasEmpty = persistence.isEmpty()
                    if (!persistence.clearAndCommit()) {
                        ProbeSettingsDeletionResult.StateUnavailable
                    } else if (!persistence.isEmpty()) {
                        ProbeSettingsDeletionResult.ResidueConfirmed
                    } else if (wasEmpty) {
                        ProbeSettingsDeletionResult.AlreadyAbsent
                    } else {
                        ProbeSettingsDeletionResult.Cleared
                    }
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                if (error is CancellationException) throw error
                ProbeSettingsDeletionResult.StateUnavailable
            },
        )

    private class SharedPreferencesProbeSettingsDeletionPersistence(context: Context) :
        ProbeSettingsDeletionPersistence {
        private val prefs = context.applicationContext.getSharedPreferences(
            ProbeSettingsStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )

        override fun isEmpty(): Boolean = prefs.all.isEmpty()

        override fun clearAndCommit(): Boolean = prefs.edit().clear().commit()
    }
}
