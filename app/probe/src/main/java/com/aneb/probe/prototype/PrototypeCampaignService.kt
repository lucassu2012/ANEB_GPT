package com.aneb.probe.prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aneb.probe.R
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.engine.ProbeExecutionLease
import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object PrototypeCampaignCancelIntent {
    internal const val ACTION_CANCEL = "com.aneb.probe.action.CANCEL_PROTOTYPE_CAMPAIGN"
    internal const val EXTRA_CAMPAIGN_ID = "prototype_campaign_id"
    private const val SCHEME = "aneb"
    private const val AUTHORITY = "prototype-campaign-cancel"

    fun create(context: Context, campaignId: String): Intent {
        require(campaignId.isNotBlank()) { "prototype campaign id must not be blank" }
        return Intent(context, PrototypeCampaignService::class.java)
            .setAction(ACTION_CANCEL)
            .setData(dataFor(campaignId))
            .putExtra(EXTRA_CAMPAIGN_ID, campaignId)
    }

    fun campaignIdOrNull(intent: Intent): String? {
        if (intent.action != ACTION_CANCEL) return null
        val campaignId = intent.getStringExtra(EXTRA_CAMPAIGN_ID)
            ?.takeIf(String::isNotBlank)
            ?: return null
        if (intent.data != dataFor(campaignId)) return null
        return campaignId
    }

    fun pendingIntent(context: Context, campaignId: String): PendingIntent = PendingIntent.getService(
        context,
        0,
        create(context, campaignId),
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun dataFor(campaignId: String): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .appendPath(campaignId)
        .build()
}

internal fun buildPrototypeCampaignNotification(
    context: Context,
    campaignId: String?,
    title: CharSequence = context.getString(R.string.probe_run_notification_title),
    cancelLabel: CharSequence = context.getString(R.string.probe_run_cancel),
): Notification {
    val builder = NotificationCompat.Builder(context, "prototype_campaign")
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText("Prototype Quick 正在运行")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    if (!campaignId.isNullOrBlank()) {
        builder.addAction(
            0,
            cancelLabel,
            PrototypeCampaignCancelIntent.pendingIntent(context, campaignId),
        )
    }
    return builder.build()
}

internal fun runPrototypeCampaignNotificationUpdate(update: () -> Unit) {
    try {
        update()
    } catch (_: Exception) {
        // The foreground notification already exists; a refresh must not break run ownership.
    }
}

internal data class PrototypeCampaignServiceHandoff(
    val token: String,
    val campaignId: String,
    val nodeBaseUrl: String,
    val runUrl: String,
    val capabilityUrl: String,
)

internal fun interface PrototypeCampaignResultStore {
    suspend fun save(
        config: PrototypeCampaignConfig,
        result: PrototypeQuickCampaignRunner.CampaignResult,
    )
}

internal class PersistingPrototypeCampaignExecutor(
    private val delegate: PrototypeCampaignExecutor,
    private val store: PrototypeCampaignResultStore,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val publishProgress: (PrototypeCampaignProgress) -> Unit = {},
) : PrototypeCampaignExecutor {
    override suspend fun execute(
        config: PrototypeCampaignConfig,
    ): PrototypeQuickCampaignRunner.CampaignResult = withContext(backgroundDispatcher) {
        val result = delegate.execute(config)
        publishProgress(
            PrototypeCampaignProgress.Saving(
                campaignId = config.campaignId,
                processedRuns = result.summary.attemptedRuns,
                totalRuns = result.summary.plannedRuns,
            ),
        )
        store.save(config, result)
        result
    }
}

/** Single-use, process-local transfer of an already validated immutable ticket. */
internal class PrototypeCampaignServiceHandoffRegistry(
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val pending = ConcurrentHashMap<String, PrototypeCampaignConfig>()

    fun register(config: PrototypeCampaignConfig): PrototypeCampaignServiceHandoff {
        require(config.campaignId.isNotBlank()) { "prototype campaign id is blank" }
        var token: String
        do {
            token = tokenFactory()
            require(token.isNotBlank()) { "prototype campaign handoff token is blank" }
        } while (pending.putIfAbsent(token, config) != null)
        return PrototypeCampaignServiceHandoff(
            token = token,
            campaignId = config.campaignId,
            nodeBaseUrl = config.nodeTicket.nodeBaseUrl,
            runUrl = config.nodeTicket.runUrl,
            capabilityUrl = config.nodeTicket.capabilityUrl,
        )
    }

    fun consume(handoff: PrototypeCampaignServiceHandoff): PrototypeCampaignConfig? {
        val config = pending.remove(handoff.token) ?: return null
        val ticket = config.nodeTicket
        val endpoint = runCatching { PrototypeNodeEndpoint.parse(ticket.nodeBaseUrl) }.getOrNull()
            ?: return null
        return config.takeIf {
            handoff.campaignId == config.campaignId &&
                handoff.nodeBaseUrl == ticket.nodeBaseUrl &&
                handoff.runUrl == ticket.runUrl &&
                handoff.capabilityUrl == ticket.capabilityUrl &&
                endpoint.baseUrl == ticket.nodeBaseUrl &&
                endpoint.runUrl == ticket.runUrl &&
                endpoint.capabilityUrl == ticket.capabilityUrl
        }
    }

    fun revoke(token: String): Boolean = pending.remove(token) != null
}

/** Rejects callbacks from a Service instance after a replacement instance owns the session. */
internal class PrototypeCampaignServiceLease {
    private val nextGeneration = AtomicLong(0L)
    private val lock = Any()
    private var currentGeneration = 0L
    private var ownedCampaignId: String? = null
    private var progressOpen = false
    private val _session =
        MutableStateFlow<PrototypeCampaignSession>(PrototypeCampaignSession.Idle)
    private val _progress = MutableStateFlow<PrototypeCampaignProgress?>(null)

    val session: StateFlow<PrototypeCampaignSession> = _session.asStateFlow()
    val progress: StateFlow<PrototypeCampaignProgress?> = _progress.asStateFlow()

    fun acquire(): Long = synchronized(lock) {
        nextGeneration.incrementAndGet().also {
            currentGeneration = it
            ownedCampaignId = null
            progressOpen = false
            _progress.value = null
        }
    }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        currentGeneration == generation
    }

    fun publishIfCurrent(generation: Long, mutation: () -> Unit): Boolean = synchronized(lock) {
        if (currentGeneration != generation) return@synchronized false
        mutation()
        true
    }

    fun runIfCurrent(generation: Long, action: () -> Unit): Boolean =
        publishIfCurrent(generation, action)

    fun publishSession(
        generation: Long,
        session: PrototypeCampaignSession,
        onPublished: () -> Unit = {},
    ): Boolean {
        var accepted = false
        publishIfCurrent(generation) {
            val campaignId = session.campaignIdOrNull()
            val ownsCampaign = when (session) {
                PrototypeCampaignSession.Idle -> ownedCampaignId == null
                is PrototypeCampaignSession.Running ->
                    ownedCampaignId == null || ownedCampaignId == campaignId
                is PrototypeCampaignSession.Failed ->
                    ownedCampaignId == null || ownedCampaignId == campaignId
                is PrototypeCampaignSession.Cancelling,
                is PrototypeCampaignSession.Finished,
                is PrototypeCampaignSession.Cancelled,
                -> ownedCampaignId == campaignId
            }
            if (!ownsCampaign) return@publishIfCurrent

            when (session) {
                PrototypeCampaignSession.Idle -> {
                    ownedCampaignId = null
                    progressOpen = false
                    _progress.value = null
                }

                is PrototypeCampaignSession.Running -> {
                    ownedCampaignId = campaignId
                    progressOpen = true
                    if (_progress.value?.campaignId != campaignId) _progress.value = null
                }

                is PrototypeCampaignSession.Cancelling,
                is PrototypeCampaignSession.Finished,
                is PrototypeCampaignSession.Failed,
                is PrototypeCampaignSession.Cancelled,
                -> {
                    if (ownedCampaignId == null) ownedCampaignId = campaignId
                    progressOpen = false
                    _progress.value = null
                }
            }
            _session.value = session
            accepted = true
            onPublished()
        }
        return accepted
    }

    fun publishProgress(
        generation: Long,
        progress: PrototypeCampaignProgress,
    ): Boolean {
        var accepted = false
        publishIfCurrent(generation) {
            val running = _session.value as? PrototypeCampaignSession.Running
            if (
                progressOpen &&
                running?.config?.campaignId == ownedCampaignId &&
                progress.campaignId == ownedCampaignId
            ) {
                _progress.value = progress
                accepted = true
            }
        }
        return accepted
    }

    fun release(generation: Long) {
        synchronized(lock) {
            if (currentGeneration == generation) {
                currentGeneration = 0L
                ownedCampaignId = null
                progressOpen = false
                _progress.value = null
            }
        }
    }

    private fun PrototypeCampaignSession.campaignIdOrNull(): String? = when (this) {
        PrototypeCampaignSession.Idle -> null
        is PrototypeCampaignSession.Running -> config.campaignId
        is PrototypeCampaignSession.Cancelling -> config.campaignId
        is PrototypeCampaignSession.Finished -> config.campaignId
        is PrototypeCampaignSession.Failed -> config.campaignId
        is PrototypeCampaignSession.Cancelled -> config.campaignId
    }
}

internal sealed interface PrototypeCampaignStartResult {
    data object Started : PrototypeCampaignStartResult

    data object HostRejected : PrototypeCampaignStartResult

    data class ProcessLeaseRejected(
        val config: PrototypeCampaignConfig,
    ) : PrototypeCampaignStartResult
}

/** Pure lifecycle seam used by the Android foreground-service shell. */
internal class PrototypeCampaignServiceHost(
    beginForeground: () -> Unit,
    private val startOwned: (PrototypeCampaignConfig) -> Boolean,
    private val cancelOwned: () -> Boolean,
    private val publish: (PrototypeCampaignSession) -> Unit,
    private val finishTerminal: () -> Unit,
    private val executionLease: ProbeExecutionLease = ProbeExecutionLease(),
) {
    private val lock = Any()
    private var active = false
    private var finishing = false
    private var destroyed = false
    private var activeConfig: PrototypeCampaignConfig? = null
    private var executionToken: ProbeExecutionLease.Token? = null
    private var cancelDispatched = false
    private var cancellationObserved = false

    init {
        beginForeground()
    }

    fun start(config: PrototypeCampaignConfig): PrototypeCampaignStartResult {
        val token = synchronized(lock) {
            if (active || finishing || destroyed) {
                return PrototypeCampaignStartResult.HostRejected
            }
            val acquired = executionLease.tryAcquire()
                ?: return PrototypeCampaignStartResult.ProcessLeaseRejected(config)
            active = true
            activeConfig = config
            executionToken = acquired
            cancelDispatched = false
            cancellationObserved = false
            acquired
        }
        val accepted = try {
            startOwned(config)
        } catch (failure: Throwable) {
            abandonStart(token)
            throw failure
        }
        if (!accepted) {
            abandonStart(token)
            return PrototypeCampaignStartResult.HostRejected
        }
        return PrototypeCampaignStartResult.Started
    }

    fun cancel(expectedCampaignId: String?): Boolean {
        val campaignId = expectedCampaignId?.takeIf(String::isNotBlank) ?: return false
        val shouldDispatch = synchronized(lock) {
            if (
                !active ||
                finishing ||
                destroyed ||
                cancelDispatched ||
                cancellationObserved ||
                activeConfig?.campaignId != campaignId
            ) {
                false
            } else {
                cancelDispatched = true
                true
            }
        }
        if (!shouldDispatch) return false
        return try {
            cancelOwned().also { accepted ->
                if (!accepted) rollbackCancelDispatch(campaignId)
            }
        } catch (failure: Throwable) {
            rollbackCancelDispatch(campaignId)
            throw failure
        }
    }

    fun onOwnerSession(session: PrototypeCampaignSession) {
        val terminal = session is PrototypeCampaignSession.Finished ||
            session is PrototypeCampaignSession.Failed ||
            session is PrototypeCampaignSession.Cancelled
        var tokenToRelease: ProbeExecutionLease.Token? = null
        var shouldPublish = false
        var shouldFinish = false
        synchronized(lock) {
            if (session is PrototypeCampaignSession.Cancelling) {
                cancellationObserved = true
            }
            if (terminal) {
                tokenToRelease = executionToken
                executionToken = null
            }
            if (!destroyed && !(terminal && finishing)) {
                active = session is PrototypeCampaignSession.Running ||
                    session is PrototypeCampaignSession.Cancelling
                if (terminal) {
                    finishing = true
                    activeConfig = null
                }
                shouldPublish = true
                shouldFinish = terminal
            }
        }
        try {
            if (shouldPublish) {
                publish(session)
                if (shouldFinish) finishTerminal()
            }
        } finally {
            tokenToRelease?.let(executionLease::release)
        }
    }

    fun finishIfIdle(): Boolean {
        val shouldFinish = synchronized(lock) {
            if (active || finishing || destroyed) return false
            finishing = true
            true
        }
        if (shouldFinish) finishTerminal()
        return true
    }

    /** Finishes a rejected START without stopping an already owned active run. */
    fun finishRejectedStart(): Boolean {
        synchronized(lock) {
            if (active || destroyed) return false
            if (!finishing) finishing = true
        }
        finishTerminal()
        return true
    }

    fun finishProcessLeaseRejected(
        rejection: PrototypeCampaignStartResult.ProcessLeaseRejected,
    ): Boolean {
        val failed = synchronized(lock) {
            if (
                active ||
                finishing ||
                destroyed ||
                activeConfig != null ||
                executionToken != null
            ) {
                return false
            }
            finishing = true
            PrototypeCampaignSession.Failed(
                config = rejection.config,
                message = "The previous Quick campaign is still finishing. Please try again shortly.",
            )
        }
        try {
            publish(failed)
        } finally {
            finishTerminal()
        }
        return true
    }

    fun destroy() {
        val failed = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            val config = activeConfig
            active = false
            activeConfig = null
            config?.takeUnless { finishing }?.let {
                PrototypeCampaignSession.Failed(
                    config = it,
                    message = "prototype campaign service was destroyed",
                )
            }
        }
        if (failed != null) publish(failed)
    }

    private fun abandonStart(token: ProbeExecutionLease.Token) {
        val shouldRelease = synchronized(lock) {
            if (executionToken !== token) return@synchronized false
            active = false
            activeConfig = null
            executionToken = null
            true
        }
        if (shouldRelease) executionLease.release(token)
    }

    private fun rollbackCancelDispatch(expectedCampaignId: String) {
        synchronized(lock) {
            if (
                active &&
                !finishing &&
                !destroyed &&
                !cancellationObserved &&
                activeConfig?.campaignId == expectedCampaignId
            ) {
                cancelDispatched = false
            }
        }
    }
}

/** Foreground owner for one process-local Prototype Quick campaign. */
class PrototypeCampaignService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var owner: PrototypeCampaignJobOwner
    private lateinit var host: PrototypeCampaignServiceHost
    private var generation = 0L
    private var latestStartId = 0
    private var foregroundRemoved = false

    override fun onCreate() {
        super.onCreate()
        generation = serviceLease.acquire()
        createNotificationChannel()
        val publishProgress: (PrototypeCampaignProgress) -> Unit = { progress ->
            serviceLease.publishProgress(generation, progress)
            Unit
        }
        host = PrototypeCampaignServiceHost(
            beginForeground = {
                startForeground(
                    NOTIFICATION_ID,
                    buildPrototypeCampaignNotification(this, campaignId = null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            },
            startOwned = { config -> owner.start(config) },
            cancelOwned = { owner.cancel() },
            publish = { session ->
                serviceLease.publishSession(generation, session) {
                    when (session) {
                        is PrototypeCampaignSession.Running ->
                            updateNotificationSafely(session.config.campaignId)
                        is PrototypeCampaignSession.Cancelling ->
                            updateNotificationSafely(campaignId = null)
                        else -> Unit
                    }
                }
            },
            finishTerminal = {
                serviceLease.runIfCurrent(generation) { finishService() }
            },
            executionLease = executionLease,
        )
        val ticketTransport = AnebClientPrototypeRawPostTransport(AnebClient())
        val repository = PrototypeCampaignRoomRepository(AnebDatabase.get(applicationContext))
        owner = PrototypeCampaignJobOwner(
            scope = serviceScope,
            executor = PersistingPrototypeCampaignExecutor(
                delegate = PrototypeCampaignExecutor { config ->
                    val streamAdapter = PrototypeRunStreamAdapter(
                        ticketTransport.forTicket(config.nodeTicket),
                    )
                    PrototypeQuickCampaignRunner(
                        streamAdapter = streamAdapter,
                        publishProgress = publishProgress,
                    ).run(
                        endpoint = config.nodeTicket.runUrl,
                        campaignId = config.campaignId,
                    )
                },
                store = PrototypeCampaignResultStore { config, result ->
                    repository.save(config, result)
                },
                backgroundDispatcher = Dispatchers.IO,
                publishProgress = publishProgress,
            ),
            publish = host::onOwnerSession,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> startCampaign(intent)
            ACTION_CANCEL -> {
                val campaignId = PrototypeCampaignCancelIntent.campaignIdOrNull(intent)
                if (campaignId == null || !host.cancel(campaignId)) {
                    host.finishRejectedStart()
                }
            }
            else -> host.finishRejectedStart()
        }
        return START_NOT_STICKY
    }

    private fun startCampaign(intent: Intent) {
        val handoff = intent.handoffOrNull()
        val config = handoff?.let(handoffRegistry::consume)
        if (config == null) {
            host.finishRejectedStart()
            return
        }
        when (val result = host.start(config)) {
            PrototypeCampaignStartResult.Started -> Unit
            PrototypeCampaignStartResult.HostRejected -> host.finishRejectedStart()
            is PrototypeCampaignStartResult.ProcessLeaseRejected -> {
                host.finishProcessLeaseRejected(result)
            }
        }
    }

    private fun finishService() {
        if (!foregroundRemoved) {
            foregroundRemoved = true
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelfResult(latestStartId)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.probe_run_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.probe_run_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun updateNotification(campaignId: String?) {
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            buildPrototypeCampaignNotification(this, campaignId),
        )
    }

    private fun updateNotificationSafely(campaignId: String?) {
        runPrototypeCampaignNotificationUpdate {
            updateNotification(campaignId)
        }
    }

    override fun onDestroy() {
        host.destroy()
        serviceLease.release(generation)
        serviceScope.cancel(CancellationException("prototype campaign service destroyed"))
        super.onDestroy()
    }

    private fun Intent.handoffOrNull(): PrototypeCampaignServiceHandoff? {
        val token = getStringExtra(EXTRA_HANDOFF_TOKEN)?.takeIf(String::isNotBlank) ?: return null
        val campaignId = getStringExtra(EXTRA_CAMPAIGN_ID)?.takeIf(String::isNotBlank) ?: return null
        val nodeBaseUrl = getStringExtra(EXTRA_NODE_BASE_URL)?.takeIf(String::isNotBlank) ?: return null
        val runUrl = getStringExtra(EXTRA_RUN_URL)?.takeIf(String::isNotBlank) ?: return null
        val capabilityUrl = getStringExtra(EXTRA_CAPABILITY_URL)?.takeIf(String::isNotBlank) ?: return null
        return PrototypeCampaignServiceHandoff(
            token = token,
            campaignId = campaignId,
            nodeBaseUrl = nodeBaseUrl,
            runUrl = runUrl,
            capabilityUrl = capabilityUrl,
        )
    }

    companion object {
        private const val ACTION_START = "com.aneb.probe.action.START_PROTOTYPE_CAMPAIGN"
        private const val ACTION_CANCEL = PrototypeCampaignCancelIntent.ACTION_CANCEL
        private const val EXTRA_HANDOFF_TOKEN = "prototype_handoff_token"
        private const val EXTRA_CAMPAIGN_ID = PrototypeCampaignCancelIntent.EXTRA_CAMPAIGN_ID
        private const val EXTRA_NODE_BASE_URL = "prototype_node_base_url"
        private const val EXTRA_RUN_URL = "prototype_run_url"
        private const val EXTRA_CAPABILITY_URL = "prototype_capability_url"
        private const val CHANNEL_ID = "prototype_campaign"
        private const val NOTIFICATION_ID = 4103

        private val handoffRegistry = PrototypeCampaignServiceHandoffRegistry()
        private val serviceLease = PrototypeCampaignServiceLease()
        private val executionLease = ProbeExecutionLease.process
        internal val session = serviceLease.session
        internal val progress = serviceLease.progress

        internal fun start(context: Context, config: PrototypeCampaignConfig) {
            val handoff = handoffRegistry.register(config)
            val intent = Intent(context, PrototypeCampaignService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HANDOFF_TOKEN, handoff.token)
                .putExtra(EXTRA_CAMPAIGN_ID, handoff.campaignId)
                .putExtra(EXTRA_NODE_BASE_URL, handoff.nodeBaseUrl)
                .putExtra(EXTRA_RUN_URL, handoff.runUrl)
                .putExtra(EXTRA_CAPABILITY_URL, handoff.capabilityUrl)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (failure: Exception) {
                handoffRegistry.revoke(handoff.token)
                throw failure
            }
        }

        fun cancel(context: Context, campaignId: String) {
            context.startService(PrototypeCampaignCancelIntent.create(context, campaignId))
        }
    }
}
