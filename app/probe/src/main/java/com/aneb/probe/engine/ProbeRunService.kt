package com.aneb.probe.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aneb.probe.BuildConfig
import com.aneb.probe.R
import com.aneb.probe.ui.MainActivity
import com.aneb.probe.ui.RunFailureMessage
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

/** Activity 可重建、应用切后台时仍可观察的主测量会话状态。 */
internal sealed interface ProbeRunSession {
    data object Idle : ProbeRunSession
    data class Running(val autorun: Boolean, val runId: String? = null) : ProbeRunSession
    data class Completed(val autorun: Boolean, val runId: String) : ProbeRunSession
    data class Failed(val autorun: Boolean, val message: String) : ProbeRunSession
    data class Cancelled(val autorun: Boolean) : ProbeRunSession
}

/** 只解析既有日志合同，不新增或改写任何测量日志 KEY。 */
internal object ProbeRunLogParser {
    private val runId = Regex("(?:^|\\s)run_id=(\\S+)")

    fun runId(line: String): String? =
        if (line.startsWith("RUN_START ")) runId.find(line)?.groupValues?.get(1) else null

    fun progressText(line: String): String? = when {
        line.startsWith("RUN_START ") -> "正在验证环境和测试节点"
        line.startsWith("SCENARIO_START ") -> "正在执行网络场景"
        line.startsWith("SCENARIO_KPI ") -> "正在整理场景指标"
        line.startsWith("AQS ") -> "正在生成体验结果"
        else -> null
    }
}

/**
 * V1 主测量前台 Service。它拥有 [TestEngine] 与协程作用域；Activity 只订阅 StateFlow，
 * 因此切后台和配置重建不会取消 run。通知提供明确的“取消测试”动作。
 */
class ProbeRunService : Service() {
    internal data class Config(
        val serverBase: String,
        val mode: TestEngine.Mode,
        val transport: TestEngine.TransportMode,
        val inject: String?,
        val driveTest: Boolean,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null
    private var telemetryJob: Job? = null
    private var cancelRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRun(intent)
            ACTION_CANCEL -> cancelRun()
        }
        return START_NOT_STICKY
    }

    private fun startRun(intent: Intent) {
        if (runJob?.isActive == true) return
        val autorun = intent.getBooleanExtra(EXTRA_AUTORUN, false)
        val config = Config(
            serverBase = intent.getStringExtra(EXTRA_SERVER)
                ?.takeIf { it.isNotBlank() }
                ?: return failBeforeRun(autorun, "测试节点地址为空。请在设置中选择节点。"),
            mode = enumValueOrDefault(intent.getStringExtra(EXTRA_MODE), TestEngine.Mode.QUICK),
            transport = enumValueOrDefault(
                intent.getStringExtra(EXTRA_TRANSPORT),
                TestEngine.TransportMode.AUTO,
            ),
            inject = intent.getStringExtra(EXTRA_INJECT).takeIf { BuildConfig.DEBUG },
            driveTest = intent.getBooleanExtra(EXTRA_DRIVE_TEST, false),
        )

        startForeground(
            NOTIFICATION_ID,
            buildNotification("正在准备测试", ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        cancelRequested = false
        _logs.value = emptyList()
        _telemetry.value = LiveTelemetry()
        _session.value = ProbeRunSession.Running(autorun)

        val engine = TestEngine(applicationContext)
        telemetryJob = serviceScope.launch {
            engine.telemetry.collect { _telemetry.value = it }
        }
        runJob = serviceScope.launch {
            var runId: String? = null
            try {
                engine.run(
                    TestEngine.RunConfig(
                        serverBase = config.serverBase,
                        mode = config.mode,
                        transport = config.transport,
                        inject = config.inject,
                        driveTest = config.driveTest,
                    ),
                ).collect { line ->
                    addLog(line)
                    ProbeRunLogParser.runId(line)?.let { id ->
                        runId = id
                        _session.value = ProbeRunSession.Running(autorun, id)
                    }
                    ProbeRunLogParser.progressText(line)?.let(::updateNotification)
                }
                val completedId = runId
                _session.value = if (completedId != null) {
                    ProbeRunSession.Completed(autorun, completedId)
                } else {
                    ProbeRunSession.Failed(autorun, "测试未生成结果，请重试。")
                }
            } catch (e: CancellationException) {
                if (cancelRequested) {
                    _session.value = ProbeRunSession.Cancelled(autorun)
                }
                throw e
            } catch (e: Exception) {
                addLog("RUN_FAILED error=$e")
                _session.value = ProbeRunSession.Failed(autorun, RunFailureMessage.forError(e))
            } finally {
                telemetryJob?.cancel()
                telemetryJob = null
                runJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelRun() {
        cancelRequested = true
        runJob?.cancel(CancellationException("user_cancelled")) ?: stopSelf()
    }

    private fun failBeforeRun(autorun: Boolean, message: String) {
        _session.value = ProbeRunSession.Failed(autorun, message)
        stopSelf()
    }

    private fun addLog(line: String) {
        Log.i("AnebProbe", line)
        _logs.value = _logs.value + line
    }

    private fun updateNotification(content: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(content, ongoing = true))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
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

    private fun buildNotification(content: String, ongoing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, ProbeRunService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.probe_run_notification_title))
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, getString(R.string.probe_run_cancel), cancel)
            .build()
    }

    override fun onDestroy() {
        if (_session.value is ProbeRunSession.Running && !cancelRequested) {
            val running = _session.value as ProbeRunSession.Running
            _session.value = ProbeRunSession.Failed(running.autorun, "测试服务被系统停止，请重新测试。")
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.aneb.probe.action.START_RUN"
        private const val ACTION_CANCEL = "com.aneb.probe.action.CANCEL_RUN"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_INJECT = "inject"
        private const val EXTRA_DRIVE_TEST = "drive_test"
        private const val EXTRA_AUTORUN = "autorun"
        private const val CHANNEL_ID = "probe_measurement"
        private const val NOTIFICATION_ID = 4101

        private val _session = MutableStateFlow<ProbeRunSession>(ProbeRunSession.Idle)
        internal val session: StateFlow<ProbeRunSession> = _session.asStateFlow()
        private val _logs = MutableStateFlow<List<String>>(emptyList())
        internal val logs: StateFlow<List<String>> = _logs.asStateFlow()
        private val _telemetry = MutableStateFlow(LiveTelemetry())
        internal val telemetry: StateFlow<LiveTelemetry> = _telemetry.asStateFlow()

        internal fun start(context: Context, config: Config, autorun: Boolean) {
            val intent = Intent(context, ProbeRunService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SERVER, config.serverBase)
                .putExtra(EXTRA_MODE, config.mode.name)
                .putExtra(EXTRA_TRANSPORT, config.transport.name)
                .putExtra(EXTRA_INJECT, config.inject)
                .putExtra(EXTRA_DRIVE_TEST, config.driveTest)
                .putExtra(EXTRA_AUTORUN, autorun)
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun cancel(context: Context) {
            context.startService(Intent(context, ProbeRunService::class.java).setAction(ACTION_CANCEL))
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}
