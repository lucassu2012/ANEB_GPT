package com.aneb.probe.privacy

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface ExactNetlogDeletionResult {
    data class Cleared(val fileCount: Int) : ExactNetlogDeletionResult
    data object AlreadyAbsent : ExactNetlogDeletionResult
    data class ResidueConfirmed(val fileCount: Int) : ExactNetlogDeletionResult
    data object StateUnavailable : ExactNetlogDeletionResult
}

internal class InternalNetlogDeletionPort internal constructor(
    private val internalCacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        internalCacheDir = context.applicationContext.cacheDir,
        ioDispatcher = ioDispatcher,
    )

    suspend fun deleteOwnedNetlog(): ExactNetlogDeletionResult = deleteExactOwnedNetlogs(
        roots = listOf(internalCacheDir),
        ioDispatcher = ioDispatcher,
    )
}

internal class ExternalNetlogDeletionPort internal constructor(
    private val externalCacheDirs: List<File?>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        externalCacheDirs = context.applicationContext.externalCacheDirs.toList(),
        ioDispatcher = ioDispatcher,
    )

    suspend fun deleteOwnedNetlogs(): ExactNetlogDeletionResult = deleteExactOwnedNetlogs(
        roots = externalCacheDirs,
        ioDispatcher = ioDispatcher,
    )
}

internal class ExactNetlogDeletionPort internal constructor(
    private val internalCacheDir: File,
    private val externalCacheDirs: List<File?>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        internalCacheDir = context.applicationContext.cacheDir,
        externalCacheDirs = context.applicationContext.externalCacheDirs.toList(),
        ioDispatcher = ioDispatcher,
    )

    suspend fun deleteOwnedNetlogs(): ExactNetlogDeletionResult = deleteExactOwnedNetlogs(
        roots = listOf<File?>(internalCacheDir) + externalCacheDirs,
        ioDispatcher = ioDispatcher,
    )
}

private const val OWNED_NETLOG_NAME = "ab_netlog_b.json"

private suspend fun deleteExactOwnedNetlogs(
    roots: List<File?>,
    ioDispatcher: CoroutineDispatcher,
): ExactNetlogDeletionResult = withContext(ioDispatcher) {
    val hasUnavailableRoot = roots.any { it == null || !it.exists() || !it.isDirectory }
    val targets = roots.filterNotNull()
        .filter { root -> root.exists() && root.isDirectory }
        .map { root -> root.toPath().toAbsolutePath().normalize().resolve(OWNED_NETLOG_NAME) }
        .distinct()
    val hasUnavailableTarget = targets.any { target -> target.toFile().isDirectory }
    val deletableTargets = targets.filterNot { target -> target.toFile().isDirectory }
    val existingBefore = deletableTargets.count { target -> target.toFile().exists() }
    try {
        deletableTargets.forEach { target ->
            val file = target.toFile()
            if (file.exists() && !file.delete()) {
                return@withContext ExactNetlogDeletionResult.ResidueConfirmed(
                    deletableTargets.count { it.toFile().exists() },
                )
            }
        }
    } catch (_: SecurityException) {
        return@withContext ExactNetlogDeletionResult.StateUnavailable
    }
    val remaining = deletableTargets.count { target -> target.toFile().exists() }
    when {
        hasUnavailableRoot || hasUnavailableTarget -> ExactNetlogDeletionResult.StateUnavailable
        remaining != 0 -> ExactNetlogDeletionResult.ResidueConfirmed(remaining)
        existingBefore == 0 -> ExactNetlogDeletionResult.AlreadyAbsent
        else -> ExactNetlogDeletionResult.Cleared(existingBefore)
    }
}
