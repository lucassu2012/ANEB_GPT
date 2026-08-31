package com.aneb.probe.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aneb.probe.BuildConfig
import com.aneb.probe.R
import com.aneb.probe.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SpecialRunKind { CONTINUITY, PROTOCOL_AB }

internal sealed interface SpecialRunSession {
    data object Idle : SpecialRunSession
    data class Running(val kind: SpecialRunKind) : SpecialRunSession
    data class Completed(val kind: SpecialRunKind) : SpecialRunSession
    data class Failed(val kind: SpecialRunKind, val message: String) : SpecialRunSession
    data class Cancelled(val kind: SpecialRunKind) : SpecialRunSession
}

internal class ProbeSpecialRunServiceExecutionHost(
    beginForeground: (SpecialRunKind) -> Unit,
    publish: (SpecialRunSession) -> Unit,
    finishRejected: () -> Unit,
    executionLease: ProbeExecutionLease = ProbeExecutionLease.process,
) {
    private val leaseHost = ProbeExecutionLeaseHost(
        beginForeground = beginForeground,
        publishBusy = { kind ->
            publish(SpecialRunSession.Failed(kind, BUSY_MESSAGE))
        },
        finishRejected = finishRejected,
        executionLease = executionLease,
    )

    fun start(
        kind: SpecialRunKind,
        startOwned: (ProbeExecutionLease.Token) -> Boolean,
    ): ProbeExecutionStartResult = leaseHost.start(kind, startOwned)

    fun finish(token: ProbeExecutionLease.Token): Boolean = leaseHost.finish(token)

    private companion object {
        const val BUSY_MESSAGE = "另一项测试仍在结束处理中，请稍后重试。"
    }
}

/**
 * Continuity 与 Cronet A/B 专项的前台执行宿主。专项测量算法和日志合同仍由原 Runner 持有；
 * 本服务只把协程所有权从 Activity 迁出，避免配置重建或切后台取消正在进行的取证。
 */
class ProbeSpecialRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var cancelRequested = false
    private lateinit var executionHost: ProbeSpecialRunServiceExecutionHost

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        executionHost = ProbeSpecialRunServiceExecutionHost(
            beginForeground = { kind ->
                val text = when (kind) {
                    SpecialRunKind.CONTINUITY -> "正在执行连续性测试"
                    SpecialRunKind.PROTOCOL_AB -> "正在执行协议 A/B 测试"
                }
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            },
            publish = { _session.value = it },
            finishRejected = {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONTINUITY -> startContinuity(intent)
            ACTION_AB -> startAb(intent)
            ACTION_CANCEL -> cancelRun()
        }
        return START_NOT_STICKY
    }

    private fun startContinuity(intent: Intent) {
        if (job?.isActive == true) return
        val server = intent.getStringExtra(EXTRA_SERVER)?.takeIf { it.isNotBlank() }
            ?: return failBeforeRun(SpecialRunKind.CONTINUITY, "测试节点地址为空。")
        val transport = enumValueOrDefault(
            intent.getStringExtra(EXTRA_TRANSPORT),
            TestEngine.TransportMode.AUTO,
        )
        val tokens = intent.getIntExtra(EXTRA_TOKENS, ContinuityRunner.DEFAULT_TOKENS).coerceAtLeast(1)
        val idle = intent.getIntArrayExtra(EXTRA_C3_IDLE)?.toList().orEmpty()
            .filter { it > 0 }.ifEmpty { ContinuityRunner.DEFAULT_C3_IDLE_S }
        startOwnedRun(SpecialRunKind.CONTINUITY) {
            emitLog(">>> CONTINUITY transport=${transport.name.lowercase()} -> $server")
            ContinuityRunner(applicationContext).run(
                ContinuityRunner.Config(
                    serverBase = server,
                    transport = transport,
                    tokens = tokens,
                    c3IdleSeconds = idle,
                ),
            ).collect(::emitLog)
        }
    }

    private fun startAb(intent: Intent) {
        if (job?.isActive == true) return
        val server = intent.getStringExtra(EXTRA_SERVER)?.takeIf { it.isNotBlank() }
            ?: return failBeforeRun(SpecialRunKind.PROTOCOL_AB, "测试节点地址为空。")
        val pairs = intent.getIntExtra(EXTRA_PAIRS, AbRunner.DEFAULT_PAIRS).coerceAtLeast(1)
        val netlog = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_NETLOG, false)
        startOwnedRun(SpecialRunKind.PROTOCOL_AB) {
            emitLog(">>> AB pairs=$pairs -> $server")
            AbRunner(applicationContext).run(
                AbRunner.Config(
                    serverBase = server,
                    pairs = pairs,
                    netlog = netlog,
                ),
            ).collect(::emitLog)
        }
    }

    private fun startOwnedRun(kind: SpecialRunKind, block: suspend () -> Unit) {
        executionHost.start(kind) { executionToken ->
            cancelRequested = false
            _logs.value = emptyList()
            _session.value = SpecialRunSession.Running(kind)
            job = scope.launch {
                try {
                    block()
                    _session.value = SpecialRunSession.Completed(kind)
                } catch (e: CancellationException) {
                    if (cancelRequested) _session.value = SpecialRunSession.Cancelled(kind)
                    throw e
                } catch (e: Exception) {
                    val prefix = if (kind == SpecialRunKind.CONTINUITY) {
                        "CONTINUITY_FAILED"
                    } else {
                        "AB_FAILED"
                    }
                    emitLog("$prefix error=${e.javaClass.simpleName}")
                    _session.value = SpecialRunSession.Failed(
                        kind,
                        "专项测试执行失败，请检查日志。",
                    )
                } finally {
                    job = null
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } finally {
                        executionHost.finish(executionToken)
                    }
                }
            }
            true
        }
    }

    private fun cancelRun() {
        cancelRequested = true
        job?.cancel(CancellationException("user_cancelled")) ?: stopSelf()
    }

    private fun failBeforeRun(kind: SpecialRunKind, message: String) {
        _session.value = SpecialRunSession.Failed(kind, message)
        stopSelf()
    }

    private fun emitLog(line: String) {
        Log.i("AnebProbe", line)
        _logs.value = _logs.value + line
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

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, ProbeSpecialRunService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.probe_run_notification_title))
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, getString(R.string.probe_run_cancel), cancel)
            .build()
    }

    override fun onDestroy() {
        val running = _session.value as? SpecialRunSession.Running
        if (running != null && !cancelRequested) {
            _session.value = SpecialRunSession.Failed(running.kind, "专项测试服务被系统停止，请重试。")
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_CONTINUITY = "com.aneb.probe.action.START_CONTINUITY"
        private const val ACTION_AB = "com.aneb.probe.action.START_PROTOCOL_AB"
        private const val ACTION_CANCEL = "com.aneb.probe.action.CANCEL_SPECIAL_RUN"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_TOKENS = "tokens"
        private const val EXTRA_C3_IDLE = "c3_idle"
        private const val EXTRA_PAIRS = "pairs"
        private const val EXTRA_NETLOG = "netlog"
        private const val CHANNEL_ID = "probe_special_measurement"
        private const val NOTIFICATION_ID = 4102

        private val _session = MutableStateFlow<SpecialRunSession>(SpecialRunSession.Idle)
        internal val session: StateFlow<SpecialRunSession> = _session.asStateFlow()
        private val _logs = MutableStateFlow<List<String>>(emptyList())
        internal val logs: StateFlow<List<String>> = _logs.asStateFlow()

        internal fun startContinuity(
            context: Context,
            server: String,
            transport: TestEngine.TransportMode,
            tokens: Int,
            c3IdleSeconds: List<Int>,
        ) {
            val intent = Intent(context, ProbeSpecialRunService::class.java)
                .setAction(ACTION_CONTINUITY)
                .putExtra(EXTRA_SERVER, server)
                .putExtra(EXTRA_TRANSPORT, transport.name)
                .putExtra(EXTRA_TOKENS, tokens)
                .putExtra(EXTRA_C3_IDLE, c3IdleSeconds.toIntArray())
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun startAb(context: Context, server: String, pairs: Int, netlog: Boolean) {
            val intent = Intent(context, ProbeSpecialRunService::class.java)
                .setAction(ACTION_AB)
                .putExtra(EXTRA_SERVER, server)
                .putExtra(EXTRA_PAIRS, pairs)
                .putExtra(EXTRA_NETLOG, netlog)
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun cancel(context: Context) {
            context.startService(Intent(context, ProbeSpecialRunService::class.java).setAction(ACTION_CANCEL))
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}
