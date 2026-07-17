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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Activity 可重建、应用切后台时仍可观察的主测量会话状态。 */
internal sealed interface ProbeRunSession {
    data object Idle : ProbeRunSession
    data class Running(
        val autorun: Boolean,
        val runId: String? = null,
        val testMode: AnebTestMode = AnebTestMode.TOKEN_SIMULATION,
    ) : ProbeRunSession
    data class Completed(
        val autorun: Boolean,
        val runId: String,
        val testMode: AnebTestMode = AnebTestMode.TOKEN_SIMULATION,
    ) : ProbeRunSession
    data class Failed(
        val autorun: Boolean,
        val message: String,
        val testMode: AnebTestMode = AnebTestMode.TOKEN_SIMULATION,
    ) : ProbeRunSession
    data class Cancelled(
        val autorun: Boolean,
        val testMode: AnebTestMode = AnebTestMode.TOKEN_SIMULATION,
    ) : ProbeRunSession
}

/** 只解析既有日志合同，不新增或改写任何测量日志 KEY。 */
internal object ProbeRunLogParser {
    private val runId = Regex("(?:^|\\s)run_id=(\\S+)")

    fun runId(line: String): String? =
        if (line.startsWith("RUN_START ") || line.startsWith("BASIC_START ") || line.startsWith("NET_V1_START ") || line.startsWith("TOKEN_V2_START ") || line.startsWith("REALTIME_V1_START ")) {
            runId.find(line)?.groupValues?.get(1)
        } else {
            null
        }

    fun progressText(line: String): String? = when {
        line.startsWith("RUN_START ") -> "正在验证环境和测试节点"
        line.startsWith("SCENARIO_START ") -> "正在执行网络场景"
        line.startsWith("SCENARIO_KPI ") -> "正在整理场景指标"
        line.startsWith("AQS ") -> "正在生成体验结果"
        line.startsWith("BASIC_START ") -> "正在准备基本网络测速"
        line.startsWith("BASIC_PHASE ") && line.contains("phase=latency") -> "正在测量时延与抖动"
        line.startsWith("BASIC_PHASE ") && line.contains("phase=download") -> "正在测量下载速度"
        line.startsWith("BASIC_PHASE ") && line.contains("phase=upload") -> "正在测量上传速度"
        line.startsWith("BASIC_RESULT ") -> "正在生成基本测速结论"
        line.startsWith("NET_V1_START ") -> "正在校验网络综合 Profile"
        line.startsWith("NET_V1_PHASE ") && line.contains("phase=handshake") -> "正在测量 DNS/TCP/TLS 握手"
        line.startsWith("NET_V1_PHASE ") && line.contains("phase=idle_latency") -> "正在测量空闲响应性"
        line.startsWith("NET_V1_PHASE ") && line.contains("phase=download_loaded") -> "正在测量下载容量与负载 RTT"
        line.startsWith("NET_V1_PHASE ") && line.contains("phase=upload_loaded") -> "正在测量上传容量与负载 RTT"
        line.startsWith("NET_V1_PHASE ") && line.contains("phase=udp") -> "正在测量 UDP 应用探针"
        line.startsWith("NET_V1_RESULT ") -> "正在生成网络综合结论"
        line.startsWith("TOKEN_V2_START ") -> "正在校验 Token 行为模型"
        line.startsWith("TOKEN_V2_TASK_START ") -> "正在模拟多模态 AI 任务"
        line.startsWith("TOKEN_V2_RESULT ") -> "正在生成 Token 质量结论"
        line.startsWith("REALTIME_V1_START ") -> "正在校验实时交互模型"
        line.startsWith("REALTIME_V1_SESSION_START ") -> "正在模拟双工语音会话"
        line.startsWith("REALTIME_V1_RESULT ") -> "正在生成实时交互结论"
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
        val testMode: AnebTestMode = AnebTestMode.TOKEN_SIMULATION,
        val mode: TestEngine.Mode,
        val transport: TestEngine.TransportMode,
        val inject: String?,
        val driveTest: Boolean,
        val gatewayBase: String? = null,
        val gatewayToken: String? = null,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null
    private var telemetryJob: Job? = null
    private var resultJob: Job? = null
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
        val gatewayCredentialHandle = intent.getStringExtra(EXTRA_GATEWAY_CREDENTIAL_HANDLE)
        val gatewayToken = GatewayCredentialVault.take(gatewayCredentialHandle)
        intent.removeExtra(EXTRA_GATEWAY_CREDENTIAL_HANDLE)
        if (runJob?.isActive == true) return
        val autorun = intent.getBooleanExtra(EXTRA_AUTORUN, false)
        val testMode = enumValueOrDefault(
            intent.getStringExtra(EXTRA_TEST_MODE),
            AnebTestMode.TOKEN_SIMULATION,
        )
        val config = Config(
            serverBase = intent.getStringExtra(EXTRA_SERVER)
                ?.takeIf { it.isNotBlank() }
                ?: return failBeforeRun(autorun, "测试节点地址为空。请在设置中选择节点。", testMode),
            testMode = testMode,
            mode = enumValueOrDefault(intent.getStringExtra(EXTRA_MODE), TestEngine.Mode.QUICK),
            transport = enumValueOrDefault(
                intent.getStringExtra(EXTRA_TRANSPORT),
                TestEngine.TransportMode.AUTO,
            ),
            inject = intent.getStringExtra(EXTRA_INJECT).takeIf { BuildConfig.DEBUG },
            driveTest = intent.getBooleanExtra(EXTRA_DRIVE_TEST, false),
            gatewayBase = intent.getStringExtra(EXTRA_GATEWAY_BASE).takeIf { BuildConfig.DEBUG },
            gatewayToken = gatewayToken.takeIf { BuildConfig.DEBUG },
        )

        startForeground(
            NOTIFICATION_ID,
            buildNotification("正在准备测试", ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        cancelRequested = false
        _logs.value = emptyList()
        _telemetry.value = LiveTelemetry()
        _basicTelemetry.value = BasicSpeedTelemetry()
        _basicResult.value = null
        _tokenSimulationTelemetry.value = TokenSimulationTelemetry()
        _tokenSimulationResult.value = null
        _realtimeSimulationTelemetry.value = RealtimeSimulationTelemetry()
        _realtimeSimulationResult.value = null
        _session.value = ProbeRunSession.Running(autorun, testMode = config.testMode)

        runJob = serviceScope.launch {
            var runId: String? = null
            try {
                val lines: Flow<String> = when (config.testMode) {
                    AnebTestMode.TOKEN_EXPERIENCE -> {
                        val engine = TestEngine(applicationContext)
                        telemetryJob = serviceScope.launch {
                            engine.telemetry.collect { _telemetry.value = it }
                        }
                        engine.run(
                            TestEngine.RunConfig(
                                serverBase = config.serverBase,
                                mode = config.mode,
                                transport = config.transport,
                                inject = config.inject,
                                driveTest = config.driveTest,
                            ),
                        )
                    }
                    AnebTestMode.NETWORK_BASIC -> {
                        val engine = NetworkSpeedEngine(applicationContext)
                        telemetryJob = serviceScope.launch {
                            engine.telemetry.collect { _basicTelemetry.value = it }
                        }
                        resultJob = serviceScope.launch {
                            engine.result.collect { _basicResult.value = it }
                        }
                        engine.run(
                            NetworkSpeedEngine.Config(
                                serverBase = config.serverBase,
                                variant = when (config.mode) {
                                    TestEngine.Mode.QUICK -> "quick"
                                    TestEngine.Mode.FORENSIC -> "standard"
                                    TestEngine.Mode.STRESS -> "weak_capacity_latency"
                                    TestEngine.Mode.NETWORK_RECOVERY -> "weak_recovery"
                                    TestEngine.Mode.GATEWAY_LOSS -> "gateway_loss"
                                    TestEngine.Mode.GATEWAY_RECOVERY -> "gateway_recovery"
                                },
                                transport = config.transport,
                                gatewayBase = config.gatewayBase,
                                gatewayToken = config.gatewayToken,
                            ),
                        )
                    }
                    AnebTestMode.TOKEN_SIMULATION -> {
                        val engine = TokenSimulationEngine(applicationContext)
                        telemetryJob = serviceScope.launch {
                            engine.telemetry.collect { _tokenSimulationTelemetry.value = it }
                        }
                        resultJob = serviceScope.launch {
                            engine.result.collect { _tokenSimulationResult.value = it }
                        }
                        engine.run(
                            TokenSimulationEngine.Config(
                                serverBase = config.serverBase,
                                variant = when (config.mode) {
                                    TestEngine.Mode.QUICK -> "quick"
                                    TestEngine.Mode.FORENSIC -> "standard"
                                    TestEngine.Mode.STRESS -> "stress"
                                    TestEngine.Mode.NETWORK_RECOVERY, TestEngine.Mode.GATEWAY_LOSS, TestEngine.Mode.GATEWAY_RECOVERY ->
                                        error("network_lab_mode_requires_network_test")
                                },
                                transport = config.transport,
                            ),
                        )
                    }
                    AnebTestMode.AI_REALTIME_SIMULATION -> {
                        val engine = RealtimeSimulationEngine(applicationContext)
                        telemetryJob = serviceScope.launch {
                            engine.telemetry.collect { _realtimeSimulationTelemetry.value = it }
                        }
                        resultJob = serviceScope.launch {
                            engine.result.collect { _realtimeSimulationResult.value = it }
                        }
                        engine.run(
                            RealtimeSimulationEngine.Config(
                                serverBase = config.serverBase,
                                variant = when (config.mode) {
                                    TestEngine.Mode.QUICK -> "quick"
                                    TestEngine.Mode.FORENSIC -> "standard"
                                    TestEngine.Mode.STRESS -> "recovery"
                                    TestEngine.Mode.NETWORK_RECOVERY, TestEngine.Mode.GATEWAY_LOSS, TestEngine.Mode.GATEWAY_RECOVERY ->
                                        error("network_lab_mode_requires_network_test")
                                },
                                transport = config.transport,
                            ),
                        )
                    }
                }
                lines.collect { line ->
                    addLog(line)
                    ProbeRunLogParser.runId(line)?.let { id ->
                        runId = id
                        _session.value = ProbeRunSession.Running(autorun, id, config.testMode)
                    }
                    ProbeRunLogParser.progressText(line)?.let(::updateNotification)
                }
                val completedId = runId
                _session.value = if (completedId != null) {
                    ProbeRunSession.Completed(autorun, completedId, config.testMode)
                } else {
                    ProbeRunSession.Failed(autorun, "测试未生成结果，请重试。", config.testMode)
                }
            } catch (e: CancellationException) {
                if (cancelRequested) {
                    _session.value = ProbeRunSession.Cancelled(autorun, config.testMode)
                }
                throw e
            } catch (e: Exception) {
                addLog("RUN_FAILED error=$e")
                _session.value = ProbeRunSession.Failed(autorun, RunFailureMessage.forError(e), config.testMode)
            } finally {
                telemetryJob?.cancel()
                telemetryJob = null
                resultJob?.cancel()
                resultJob = null
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

    private fun failBeforeRun(autorun: Boolean, message: String, testMode: AnebTestMode) {
        _session.value = ProbeRunSession.Failed(autorun, message, testMode)
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
            _session.value = ProbeRunSession.Failed(
                running.autorun,
                "测试服务被系统停止，请重新测试。",
                running.testMode,
            )
        }
        GatewayCredentialVault.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.aneb.probe.action.START_RUN"
        private const val ACTION_CANCEL = "com.aneb.probe.action.CANCEL_RUN"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_TEST_MODE = "test_mode"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_INJECT = "inject"
        private const val EXTRA_DRIVE_TEST = "drive_test"
        private const val EXTRA_AUTORUN = "autorun"
        private const val EXTRA_GATEWAY_BASE = "gateway_base"
        private const val EXTRA_GATEWAY_CREDENTIAL_HANDLE = "gateway_credential_handle"
        private const val CHANNEL_ID = "probe_measurement"
        private const val NOTIFICATION_ID = 4101

        private val _session = MutableStateFlow<ProbeRunSession>(ProbeRunSession.Idle)
        internal val session: StateFlow<ProbeRunSession> = _session.asStateFlow()
        private val _logs = MutableStateFlow<List<String>>(emptyList())
        internal val logs: StateFlow<List<String>> = _logs.asStateFlow()
        private val _telemetry = MutableStateFlow(LiveTelemetry())
        internal val telemetry: StateFlow<LiveTelemetry> = _telemetry.asStateFlow()
        private val _basicTelemetry = MutableStateFlow(BasicSpeedTelemetry())
        internal val basicTelemetry: StateFlow<BasicSpeedTelemetry> = _basicTelemetry.asStateFlow()
        private val _basicResult = MutableStateFlow<BasicSpeedResult?>(null)
        internal val basicResult: StateFlow<BasicSpeedResult?> = _basicResult.asStateFlow()
        private val _tokenSimulationTelemetry = MutableStateFlow(TokenSimulationTelemetry())
        internal val tokenSimulationTelemetry: StateFlow<TokenSimulationTelemetry> = _tokenSimulationTelemetry.asStateFlow()
        private val _tokenSimulationResult = MutableStateFlow<TokenSimulationResult?>(null)
        internal val tokenSimulationResult: StateFlow<TokenSimulationResult?> = _tokenSimulationResult.asStateFlow()
        private val _realtimeSimulationTelemetry = MutableStateFlow(RealtimeSimulationTelemetry())
        internal val realtimeSimulationTelemetry: StateFlow<RealtimeSimulationTelemetry> = _realtimeSimulationTelemetry.asStateFlow()
        private val _realtimeSimulationResult = MutableStateFlow<RealtimeSimulationResult?>(null)
        internal val realtimeSimulationResult: StateFlow<RealtimeSimulationResult?> = _realtimeSimulationResult.asStateFlow()
        internal fun start(context: Context, config: Config, autorun: Boolean) {
            val gatewayCredentialHandle = config.gatewayToken?.let(GatewayCredentialVault::put)
            val intent = Intent(context, ProbeRunService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SERVER, config.serverBase)
                .putExtra(EXTRA_TEST_MODE, config.testMode.name)
                .putExtra(EXTRA_MODE, config.mode.name)
                .putExtra(EXTRA_TRANSPORT, config.transport.name)
                .putExtra(EXTRA_INJECT, config.inject)
                .putExtra(EXTRA_DRIVE_TEST, config.driveTest)
                .putExtra(EXTRA_AUTORUN, autorun)
                .putExtra(EXTRA_GATEWAY_BASE, config.gatewayBase)
                .putExtra(EXTRA_GATEWAY_CREDENTIAL_HANDLE, gatewayCredentialHandle)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                GatewayCredentialVault.discard(gatewayCredentialHandle)
                throw error
            }
        }

        internal fun cancel(context: Context) {
            context.startService(Intent(context, ProbeRunService::class.java).setAction(ACTION_CANCEL))
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}
