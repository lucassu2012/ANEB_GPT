package com.aneb.probe.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aneb.probe.BuildConfig
import com.aneb.probe.apiprobe.AiReachabilityProbe
import com.aneb.probe.apiprobe.ApiKeyStore
import com.aneb.probe.apiprobe.ApiProbe
import com.aneb.probe.apiprobe.ApiProbeReport
import com.aneb.probe.apiprobe.LlmProvider
import com.aneb.probe.apiprobe.ProviderPresets
import com.aneb.probe.apiprobe.toLlmProvider
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.Exporter
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.engine.AbRunner
import com.aneb.probe.engine.ContinuityRunner
import com.aneb.probe.engine.ProbeRunService
import com.aneb.probe.engine.ProbeRunSession
import com.aneb.probe.engine.TestEngine
import com.aneb.probe.net.ReachabilityProbe
import com.aneb.probe.radio.GeoTrack
import com.aneb.probe.radio.RadioCollector
import com.aneb.probe.ui.components.AnebTabBar
import com.aneb.probe.ui.components.MainTab
import com.aneb.probe.ui.theme.AnebTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单 Activity 状态切换导航（UI 重设计）：
 *   Home（GO 大按钮 + 上次结果）/ Testing（脉冲环实时进度）/ Result（双视图）/
 *   History / Settings / ApiProbe。
 *
 * 测量语义、adb 自动化、logcat 合同全部不动——主 run 由 [ProbeRunService] 持有，
 * Activity 负责配置、导航与投影；continuity/AB 专项仍保留原自动化入口。
 *
 * adb 自动化（不改测量语义）：
 *   am start ... --es server <url> --ez autorun true [--es mode quick|forensic|continuity|ab]
 *   [--es transport auto|wifi|cellular] [--es inject truncate:50]
 * C07：手动 run 结束自动跳结果页；autorun 不跳（保持 logcat 自动化验收流程不变）。
 */
class MainActivity : ComponentActivity() {

    private lateinit var continuityRunner: ContinuityRunner
    private lateinit var abRunner: AbRunner
    private lateinit var radioCollector: RadioCollector
    private lateinit var db: AnebDatabase
    private lateinit var settingsStore: ProbeSettingsStore

    private var intentServer: String? = null
    private var intentAutorun: Boolean = false
    private var intentModeOverride: TestEngine.Mode? = null
    private var intentTransportOverride: TestEngine.TransportMode? = null
    private var intentInject: String? = null
    private var intentDriveTestOverride: Boolean? = null

    private var intentContinuity: Boolean = false
    private var intentCTokens: Int = ContinuityRunner.DEFAULT_TOKENS
    private var intentC3IdleS: List<Int> = ContinuityRunner.DEFAULT_C3_IDLE_S

    private var intentAb: Boolean = false
    private var intentAbPairs: Int = AbRunner.DEFAULT_PAIRS
    private var intentAbNetlog: Boolean = false

    private var radioPermissionResultCallback: ((RadioPermissionState) -> Unit)? = null
    private val radioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val callback = radioPermissionResultCallback
            radioPermissionResultCallback = null
            callback?.invoke(radioPermissionState())
        }

    private fun radioPermissionState() = RadioPermissionState(
        phoneStateGranted = hasPermission(Manifest.permission.READ_PHONE_STATE),
        coarseLocationGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        fineLocationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
    )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasActiveNetwork(): Boolean =
        getSystemService(ConnectivityManager::class.java)?.activeNetwork != null

    private fun requestRadioPermissions(onResult: (RadioPermissionState) -> Unit) {
        radioPermissionResultCallback = onResult
        radioPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }

    private fun requestRunNotificationPermission(onComplete: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            onComplete()
            return
        }
        radioPermissionResultCallback = { onComplete() }
        radioPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun openAppPermissionSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            },
        )
    }

    /**
     * 下钻子状态机（SpeedTest 式外壳）：Home=当前 tab 的根哨兵（此时显底栏 [AnebTabBar]），
     * 其余为下钻屏（隐底栏、靠各自返回键回根）。底部 3-tab 测试/历史/设置见 [MainTab]，均是
     * Home 哨兵下按 tab 选根，不再是 Screen 值。可达性看板 [ReachBoard] 已从顶级 tab 降为设置里
     * 的二级下钻入口。Result.fromHistory 仅为兼容 startRun 逐字构造保留（导航现由 tab 决定回根）。
     */
    private sealed interface Screen {
        data object Home : Screen
        data object Testing : Screen
        data class Result(val runId: String, val fromHistory: Boolean) : Screen
        data object ApiProbe : Screen
        data object ReachBoard : Screen
        data object Servers : Screen
        data object Report : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        continuityRunner = ContinuityRunner(applicationContext)
        abRunner = AbRunner(applicationContext)
        radioCollector = RadioCollector(this)
        db = AnebDatabase.get(applicationContext)
        settingsStore = ProbeSettingsStore(applicationContext)
        intentServer = intent?.getStringExtra("server")
        intentAutorun = intent?.getBooleanExtra("autorun", false) == true
        intentModeOverride = when (intent?.getStringExtra("mode")?.lowercase()) {
            "quick" -> TestEngine.Mode.QUICK
            "forensic" -> TestEngine.Mode.FORENSIC
            else -> null
        }
        intentContinuity = intent?.getStringExtra("mode")?.lowercase() == "continuity"
        intentCTokens = intent?.getIntExtra("c_tokens", ContinuityRunner.DEFAULT_TOKENS)
            ?.takeIf { it > 0 } ?: ContinuityRunner.DEFAULT_TOKENS
        intentC3IdleS = intent?.getStringExtra("c3_idle")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { v -> v > 0 } }
            ?.takeIf { it.isNotEmpty() } ?: ContinuityRunner.DEFAULT_C3_IDLE_S
        intentAb = intent?.getStringExtra("mode")?.lowercase() == "ab"
        intentAbPairs = intent?.getIntExtra("ab_pairs", AbRunner.DEFAULT_PAIRS)
            ?.takeIf { it > 0 } ?: AbRunner.DEFAULT_PAIRS
        intentAbNetlog = BuildConfig.DEBUG && intent?.getBooleanExtra("ab_netlog", false) == true
        intentTransportOverride = when (intent?.getStringExtra("transport")?.lowercase()) {
            "auto" -> TestEngine.TransportMode.AUTO
            "wifi" -> TestEngine.TransportMode.WIFI
            "cellular" -> TestEngine.TransportMode.CELLULAR
            else -> null
        }
        intentInject = if (BuildConfig.DEBUG) intent?.getStringExtra("inject") else null
        intentDriveTestOverride = if (intent?.hasExtra("drive_test") == true) {
            intent.getBooleanExtra("drive_test", false)
        } else {
            null
        }
        maybeApiProbeAutorun()

        val launchSettings = resolveLaunchSettings(
            saved = settingsStore.load(),
            overrides = ProbeLaunchOverrides(
                serverUrl = intentServer,
                mode = intentModeOverride,
                transport = intentTransportOverride,
                driveTest = intentDriveTestOverride,
            ),
            autorun = intentAutorun,
            hasFullRadioEvidence = radioPermissionState().hasFullRadioEvidence,
        )
        val launchRequestedAutorun = intentAutorun

        setContent {
            AnebTheme {
                // iOS chrome 接入点：应用底用 OLED 背景（--a #000 / 浅色 #F2F2F7），safe-area
                // 内衬；各屏顶/底毛玻璃 chrome 由 GlassChrome 承载（内容留待下一阶段）。
                Surface(
                    color = AnebTheme.colors.background,
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                ) {
                    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                    // 底部 3-tab 外壳选中态（默认 Speed）；下钻只在 Home 哨兵下按 tab 决定根，
                    // 故切 tab 只发生在各 tab 根（切换前后 screen 均为 Home），子状态天然互不串扰。
                    var tab by rememberSaveable { mutableStateOf(MainTab.Test) }
                    var serverUrl by rememberSaveable {
                        mutableStateOf(launchSettings.serverUrl)
                    }
                    var mode by rememberSaveable { mutableStateOf(launchSettings.mode) }
                    var transport by rememberSaveable { mutableStateOf(launchSettings.transport) }
                    var driveTest by rememberSaveable { mutableStateOf(launchSettings.driveTest) }
                    val runSession by ProbeRunService.session.collectAsStateWithLifecycle()
                    val serviceLogs by ProbeRunService.logs.collectAsStateWithLifecycle()
                    val serviceTelemetry by ProbeRunService.telemetry.collectAsStateWithLifecycle()
                    var auxiliaryRunning by remember { mutableStateOf(false) }
                    val running = runSession is ProbeRunSession.Running || auxiliaryRunning
                    val auxiliaryLogs = remember { mutableStateListOf<String>() }
                    var acceptManualSessions by remember { mutableStateOf(!launchRequestedAutorun) }
                    var homeNotice by rememberSaveable { mutableStateOf<String?>(null) }
                    var radioEvidenceLimited by remember { mutableStateOf(false) }
                    var permissionPrompt by remember { mutableStateOf<RadioPermissionPrompt?>(null) }
                    var nodeReach by remember { mutableStateOf<ReachabilityProbe.DualReach?>(null) }
                    var nodeReachRefreshing by remember { mutableStateOf(false) }
                    var nodeReachError by remember { mutableStateOf<String?>(null) }

                    fun addLog(line: String) {
                        android.util.Log.i("AnebProbe", line)
                        auxiliaryLogs.add(line)
                    }

                    // ---- 主 run 由前台 Service 持有；Activity 只发配置并观察状态 ----
                    fun startRun(fromAutorun: Boolean) {
                        if (running) return
                        homeNotice = null
                        radioEvidenceLimited = !radioPermissionState().hasFullRadioEvidence
                        if (!fromAutorun) {
                            acceptManualSessions = true
                            screen = Screen.Testing
                        }
                        ProbeRunService.start(
                            context = applicationContext,
                            config = ProbeRunService.Config(
                                serverBase = serverUrl,
                                mode = mode,
                                transport = transport,
                                inject = intentInject,
                                driveTest = driveTest,
                            ),
                            autorun = fromAutorun,
                        )
                    }

                    fun requestManualRun() {
                        if (!hasActiveNetwork()) {
                            homeNotice = "当前没有可用网络。连接 WiFi 或蜂窝网络后再试。"
                            return
                        }
                        val state = radioPermissionState()
                        if (state.hasFullRadioEvidence) {
                            requestRunNotificationPermission { startRun(fromAutorun = false) }
                        } else {
                            permissionPrompt = RadioPermissionPrompt(
                                purpose = RadioPermissionPurpose.START_TEST,
                                stage = RadioPermissionStage.RATIONALE,
                                state = state,
                            )
                        }
                    }

                    fun cancelManualRun() {
                        if (runSession !is ProbeRunSession.Running) return
                        homeNotice = "测试已取消，未生成成绩。"
                        screen = Screen.Home
                        ProbeRunService.cancel(applicationContext)
                    }

                    fun refreshNodeReachability() {
                        if (nodeReachRefreshing) return
                        val pair = ReachabilityProbe.deriveE01Pair(serverUrl)
                        if (pair == null) {
                            nodeReach = null
                            nodeReachError = "自定义节点将在正式测试开始时验证；双通道检测仅适用于 E-01。"
                            return
                        }
                        nodeReachRefreshing = true
                        nodeReachError = null
                        lifecycleScope.launch {
                            try {
                                nodeReach = ReachabilityProbe().probeDual(pair.first, pair.second)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                nodeReachError = "检测失败，请检查当前网络后重试。"
                            } finally {
                                nodeReachRefreshing = false
                            }
                        }
                    }

                    fun openServerScreen() {
                        screen = Screen.Servers
                        refreshNodeReachability()
                    }

                    fun startContinuityRun() {
                        if (running) return
                        auxiliaryRunning = true
                        addLog(">>> CONTINUITY transport=${transport.name.lowercase()} -> $serverUrl")
                        lifecycleScope.launch {
                            try {
                                continuityRunner.run(
                                    ContinuityRunner.Config(
                                        serverBase = serverUrl,
                                        transport = transport,
                                        tokens = intentCTokens,
                                        c3IdleSeconds = intentC3IdleS,
                                    )
                                ).collect { line -> addLog(line) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                addLog("CONTINUITY_FAILED error=$e")
                            } finally {
                                auxiliaryRunning = false
                            }
                        }
                    }

                    fun startAbRun() {
                        if (running) return
                        auxiliaryRunning = true
                        addLog(">>> AB pairs=$intentAbPairs -> $serverUrl")
                        lifecycleScope.launch {
                            try {
                                abRunner.run(
                                    AbRunner.Config(
                                        serverBase = serverUrl,
                                        pairs = intentAbPairs,
                                        netlog = intentAbNetlog,
                                    )
                                ).collect { line -> addLog(line) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                addLog("AB_FAILED error=$e")
                            } finally {
                                auxiliaryRunning = false
                            }
                        }
                    }

                    LaunchedEffect(runSession) {
                        when (val session = runSession) {
                            ProbeRunSession.Idle -> Unit
                            is ProbeRunSession.Running -> {
                                if (acceptManualSessions && !session.autorun) {
                                    radioEvidenceLimited = !radioPermissionState().hasFullRadioEvidence
                                    screen = Screen.Testing
                                }
                            }
                            is ProbeRunSession.Completed -> {
                                if (acceptManualSessions && !session.autorun) {
                                    screen = Screen.Result(session.runId, fromHistory = false)
                                }
                            }
                            is ProbeRunSession.Failed -> {
                                if (acceptManualSessions && !session.autorun) {
                                    homeNotice = session.message
                                    screen = Screen.Home
                                }
                            }
                            is ProbeRunSession.Cancelled -> {
                                if (acceptManualSessions && !session.autorun) {
                                    homeNotice = "测试已取消，未生成成绩。"
                                    screen = Screen.Home
                                }
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        if (intentAutorun) {
                            intentAutorun = false
                            when {
                                intentAb -> startAbRun()
                                intentContinuity -> startContinuityRun()
                                else -> startRun(fromAutorun = true)
                            }
                        }
                    }

                    // ---- SpeedTest 式底部 3-tab 外壳（测试 GO 凸起 / 历史 / 设置，[AnebTabBar]）----
                    // 底栏仅在各 tab 根（screen==Home）显示；下钻屏（Testing/Result/ApiProbe/
                    // ReachBoard/Report）隐底栏、Testing 运行中保持全屏专注。contentWindowInsets 置 0：
                    // Surface 已 safeDrawingPadding 统一吃系统条，避免二次内衬。
                    val atRoot = screen is Screen.Home
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = AnebTheme.colors.background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            if (atRoot) {
                                AnebTabBar(current = tab, onSelect = { tab = it })
                            }
                        },
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            when (val s = screen) {
                                // ---- 各 tab 根（显底栏）：测试=Home / 历史=History / 设置=Settings ----
                                is Screen.Home -> when (tab) {
                                    MainTab.Test -> HomeRoute(
                                        running = running,
                                        notice = homeNotice,
                                        connectionLabel = when (transport) {
                                            TestEngine.TransportMode.AUTO -> "自动选择网络"
                                            TestEngine.TransportMode.WIFI -> "WiFi 网络"
                                            TestEngine.TransportMode.CELLULAR -> "蜂窝网络"
                                        },
                                        nodeLabel = ProbeNodeCatalog.labelForUrl(serverUrl),
                                        onStart = ::requestManualRun,
                                        onOpenServer = ::openServerScreen,
                                        onOpenResult = { runId ->
                                            screen = Screen.Result(runId, fromHistory = false)
                                        },
                                    )
                                    MainTab.History -> HistoryRoute(
                                        onOpen = { runId ->
                                            screen = Screen.Result(runId, fromHistory = true)
                                        },
                                        onGenerateReport = { screen = Screen.Report },
                                        onBack = { tab = MainTab.Test },
                                    )
                                    MainTab.Settings -> SettingsScreen(
                                        serverUrl = serverUrl,
                                        onServerUrlChange = {
                                            serverUrl = it
                                            settingsStore.saveServerUrl(it)
                                        },
                                        mode = mode,
                                        onModeChange = {
                                            mode = it
                                            settingsStore.saveMode(it)
                                        },
                                        transport = transport,
                                        onTransportChange = {
                                            transport = it
                                            settingsStore.saveTransport(it)
                                        },
                                        driveTest = driveTest,
                                        onDriveTestChange = { turningOn ->
                                            if (!turningOn) {
                                                driveTest = false
                                                settingsStore.saveDriveTest(false)
                                            } else {
                                                val state = radioPermissionState()
                                                if (state.hasFullRadioEvidence) {
                                                    driveTest = true
                                                    settingsStore.saveDriveTest(true)
                                                } else {
                                                    driveTest = false
                                                    settingsStore.saveDriveTest(false)
                                                    permissionPrompt = RadioPermissionPrompt(
                                                        purpose = RadioPermissionPurpose.DRIVE_TEST,
                                                        stage = RadioPermissionStage.RATIONALE,
                                                        state = state,
                                                    )
                                                }
                                            }
                                            android.util.Log.i("AnebProbe", "DRIVE_TEST_TOGGLE enabled=$driveTest")
                                        },
                                        injectActive = intentInject,
                                        onOpenServer = ::openServerScreen,
                                        onOpenApiProbe = { screen = Screen.ApiProbe },
                                        // 可达性看板已降为设置二级入口（下钻屏）。
                                        onOpenReachBoard = { screen = Screen.ReachBoard },
                                        onBack = { tab = MainTab.Test },
                                    )
                                }
                                // ---- 下钻屏（隐底栏；各自返回键回当前 tab 根）----
                                is Screen.Testing -> {
                                    TestingScreen(
                                        logs = serviceLogs,
                                        telemetry = serviceTelemetry,
                                        nodeLabel = ProbeNodeCatalog.nodeForUrl(serverUrl)?.id ?: "自定义",
                                        radioEvidenceLimited = radioEvidenceLimited,
                                        onCancel = ::cancelManualRun,
                                    )
                                }
                                is Screen.Report -> ReportRoute(onBack = { screen = Screen.Home })
                                is Screen.Result -> ResultRoute(
                                    runId = s.runId,
                                    // 回根：tab 已记住来路（测试 手动测/上次结果 或 历史 tab 下钻），
                                    // 回到 Home 哨兵即落回当前 tab 根。
                                    onBack = { screen = Screen.Home },
                                )
                                is Screen.ApiProbe -> ApiProbeRoute(
                                    // 从设置根下钻而来：回 Home 哨兵即落回设置 tab 根。
                                    onBack = { screen = Screen.Home },
                                    onOpenReachBoard = { screen = Screen.ReachBoard },
                                )
                                is Screen.ReachBoard -> ReachBoardRoute(onBack = { screen = Screen.Home })
                                is Screen.Servers -> ServerScreen(
                                    currentUrl = serverUrl,
                                    reach = nodeReach,
                                    refreshing = nodeReachRefreshing,
                                    error = nodeReachError,
                                    onSelectE01 = {
                                        serverUrl = ProbeSettings.DEFAULT_SERVER_URL
                                        settingsStore.saveServerUrl(serverUrl)
                                        refreshNodeReachability()
                                    },
                                    onRefresh = ::refreshNodeReachability,
                                    onOpenSettings = {
                                        screen = Screen.Home
                                        tab = MainTab.Settings
                                    },
                                    onBack = { screen = Screen.Home },
                                )
                            }
                        }
                    }

                    permissionPrompt?.let { prompt ->
                        RadioPermissionDialog(
                            prompt = prompt,
                            onRequest = {
                                requestRadioPermissions { state ->
                                    if (state.hasFullRadioEvidence) {
                                        permissionPrompt = null
                                        when (prompt.purpose) {
                                            RadioPermissionPurpose.START_TEST ->
                                                requestRunNotificationPermission { startRun(fromAutorun = false) }
                                            RadioPermissionPurpose.DRIVE_TEST -> {
                                                driveTest = true
                                                settingsStore.saveDriveTest(true)
                                            }
                                        }
                                    } else {
                                        permissionPrompt = prompt.copy(
                                            stage = RadioPermissionStage.DENIED,
                                            state = state,
                                        )
                                    }
                                }
                            },
                            onContinueLimited = {
                                permissionPrompt = null
                                if (prompt.purpose == RadioPermissionPurpose.START_TEST) {
                                    requestRunNotificationPermission { startRun(fromAutorun = false) }
                                }
                            },
                            onOpenSettings = {
                                permissionPrompt = null
                                openAppPermissionSettings()
                            },
                            onDismiss = { permissionPrompt = null },
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Home / History / Result 路由（Room 加载）
    // ------------------------------------------------------------------

    @Composable
    private fun HomeRoute(
        running: Boolean,
        notice: String?,
        connectionLabel: String,
        nodeLabel: String,
        onStart: () -> Unit,
        onOpenServer: () -> Unit,
        onOpenResult: (String) -> Unit,
    ) {
        // 最近一次 run（run 结束 running→false 时刷新，带出上次结果 chip）
        val lastRun by produceState<TestRun?>(initialValue = null, running) {
            value = withContext(Dispatchers.IO) {
                db.testRunDao().all().maxByOrNull { it.startedAtEpochMs }
            }
        }
        HomeScreen(
            lastRun = lastRun,
            running = running,
            notice = notice,
            connectionLabel = connectionLabel,
            nodeLabel = nodeLabel,
            onStart = onStart,
            onOpenServer = onOpenServer,
            onOpenLastResult = onOpenResult,
        )
    }

    @Composable
    private fun HistoryRoute(
        onOpen: (String) -> Unit,
        onGenerateReport: () -> Unit,
        onBack: () -> Unit,
    ) {
        val runs by produceState(initialValue = emptyList<TestRun>()) {
            value = withContext(Dispatchers.IO) { db.testRunDao().all() }
        }
        HistoryScreen(
            runs = runs,
            onOpen = onOpen,
            onGenerateReport = onGenerateReport,
            onBack = onBack,
        )
    }

    // ------------------------------------------------------------------
    // 敏感度报告路由（analysis layer ③：多次 run → ReportMapper → ReportAnalyzer → ReportScreen）
    // ------------------------------------------------------------------

    @Composable
    private fun ReportRoute(onBack: () -> Unit) {
        val analysis by produceState<com.aneb.probe.scoring.ReportAnalyzer.ReportAnalysis?>(
            initialValue = null,
        ) {
            value = withContext(Dispatchers.IO) {
                val runs = db.testRunDao().all()
                val withScenarios = runs.map { run ->
                    run to db.scenarioResultDao().forRun(run.runId)
                }
                val summaries = ReportMapper.toRunSummaries(withScenarios)
                // 会话中断率：取有 C1 实测的 run 的中位数（真实测量，供上行重发投影；无则 null）
                val dropRates = runs.mapNotNull { it.aqsV02C1DropRate }.sorted()
                val sessionDrop = if (dropRates.isEmpty()) null else dropRates[dropRates.size / 2]
                com.aneb.probe.scoring.ReportAnalyzer.analyze(summaries, sessionDrop)
            }
        }
        var exportStatus by remember { mutableStateOf<String?>(null) }
        val a = analysis
        ReportScreen(
            analysis = a,
            exportStatus = exportStatus,
            onExportMarkdown = {
                if (a != null) {
                    doExportReport("md", "text/markdown", ReportFormat.buildMarkdown(a)) { exportStatus = it }
                }
            },
            onExportJson = {
                if (a != null) {
                    doExportReport("json", "application/json", ReportFormat.buildJson(a)) { exportStatus = it }
                }
            },
            onShare = {
                if (a != null) {
                    val body = ReportFormat.buildMarkdown(a)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "ANEB 分层测试敏感度报告")
                        putExtra(android.content.Intent.EXTRA_TEXT, body)
                    }
                    android.util.Log.i("AnebProbe", "REPORT_SHARE chars=${body.length}")
                    startActivity(android.content.Intent.createChooser(send, "分享报告"))
                }
            },
            onBack = onBack,
        )
    }

    private fun doExportReport(
        format: String,
        mime: String,
        content: String,
        onStatus: (String) -> Unit,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "aneb_report_$ts.$format"
            val outcome = Exporter.exportToDownloads(applicationContext, fileName, mime, content)
            val line =
                "REPORT_EXPORT format=$format file=$fileName bytes=${outcome.bytes} " +
                    "status=${if (outcome.ok) "ok" else "fail"} " +
                    "uri=${outcome.uri ?: "null"} error=${outcome.error?.replace(' ', '_') ?: "none"}"
            android.util.Log.i("AnebProbe", line)
            withContext(Dispatchers.Main) { onStatus(line) }
        }
    }

    private data class ResultData(
        val run: TestRun?,
        val scenarios: List<ScenarioResultEntity>,
        val reportJson: String?,
        val trackPoints: List<GeoTrack.Point>,
        val loaded: Boolean,
    )

    @Composable
    private fun ResultRoute(runId: String, onBack: () -> Unit) {
        val data by produceState(
            initialValue = ResultData(null, emptyList(), null, emptyList(), loaded = false),
            runId,
        ) {
            value = withContext(Dispatchers.IO) {
                ResultData(
                    run = db.testRunDao().byId(runId),
                    scenarios = db.scenarioResultDao().forRun(runId),
                    reportJson = db.reportBodyDao().forRun(runId)?.body,
                    trackPoints = db.radioSampleDao().forRun(runId)
                        .filter { it.lat != null && it.lon != null }
                        .map { GeoTrack.Point(it.tsNanos, it.lat, it.lon, it.accuracyM) },
                    loaded = true,
                )
            }
        }
        var exportStatus by remember(runId) { mutableStateOf<String?>(null) }

        if (!data.loaded) {
            Text("加载中…", modifier = Modifier.padding(16.dp))
            return
        }
        val trackSummaries: Map<Long, GeoTrack.Summary> =
            if (data.trackPoints.isEmpty()) {
                emptyMap()
            } else {
                data.scenarios.associate { s ->
                    s.id to GeoTrack.summarize(data.trackPoints, s.startedAtNanos, s.endedAtNanos)
                }
            }
        ResultScreen(
            run = data.run,
            scenarios = data.scenarios,
            hasReportJson = data.reportJson != null,
            exportStatus = exportStatus,
            onExportJson = {
                val body = data.reportJson ?: return@ResultScreen
                doExport(runId, "json", "application/json", body) { exportStatus = it }
            },
            onExportCsv = {
                val run = data.run ?: return@ResultScreen
                doExport(runId, "csv", "text/csv", ResultFormat.buildCsv(run, data.scenarios)) {
                    exportStatus = it
                }
            },
            onBack = onBack,
            trackSummaries = trackSummaries,
            hasTrack = data.trackPoints.isNotEmpty(),
            onExportTrack = {
                doExport(runId, "track.csv", "text/csv", GeoTrack.buildTrackCsv(data.trackPoints)) {
                    exportStatus = it
                }
            },
            onShare = { model ->
                // 分享成图：离屏 Canvas 渲染 + MediaStore 写盘属重 IO，必须离开主线程（与 doExport 同款）；
                // 仅 startActivity 回主线程。KEY=SHARE。
                lifecycleScope.launch(Dispatchers.IO) {
                    val uri = ShareCard.renderAndSave(applicationContext, model)
                    withContext(Dispatchers.Main) { ShareCard.launchShare(this@MainActivity, uri) }
                }
            },
        )
    }

    private fun doExport(
        runId: String,
        format: String,
        mime: String,
        content: String,
        onStatus: (String) -> Unit,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "aneb_${runId.take(8)}_$ts.$format"
            val outcome = Exporter.exportToDownloads(applicationContext, fileName, mime, content)
            val line =
                "EXPORT run_id=$runId format=$format file=$fileName bytes=${outcome.bytes} " +
                    "status=${if (outcome.ok) "ok" else "fail"} " +
                    "uri=${outcome.uri ?: "null"} error=${outcome.error?.replace(' ', '_') ?: "none"}"
            android.util.Log.i("AnebProbe", line)
            withContext(Dispatchers.Main) { onStatus(line) }
        }
    }

    // ------------------------------------------------------------------
    // API Probe 路由（阶段 2：真实 API 探针，独立入口）
    // ------------------------------------------------------------------

    @Composable
    private fun ApiProbeRoute(onBack: () -> Unit, onOpenReachBoard: () -> Unit) {
        val keyStore = remember { ApiKeyStore(applicationContext) }
        var provider by rememberSaveable { mutableStateOf(keyStore.provider) }
        var baseUrl by rememberSaveable { mutableStateOf(keyStore.effectiveBaseUrl()) }
        var model by rememberSaveable { mutableStateOf(keyStore.effectiveModel()) }
        var selectedPresetId by rememberSaveable { mutableStateOf<String?>(null) }
        var keyInput by remember { mutableStateOf("") }
        var hasStoredKey by remember { mutableStateOf(keyStore.hasKey()) }
        var running by remember { mutableStateOf(false) }
        var exportStatus by remember { mutableStateOf<String?>(null) }
        val logs = remember { mutableStateListOf<String>() }
        var results by remember { mutableStateOf(emptyList<com.aneb.probe.data.ApiProbeResultEntity>()) }
        var resultsVersion by remember { mutableStateOf(0) }

        LaunchedEffect(resultsVersion) {
            results = withContext(Dispatchers.IO) { db.apiProbeResultDao().recent(20) }
        }

        fun addLog(line: String) {
            android.util.Log.i("AnebProbe", line)
            logs.add(line)
        }

        ApiProbeScreen(
            provider = provider,
            onProviderChange = { p ->
                provider = p
                if (baseUrl == LlmProvider.ANTHROPIC.defaultBaseUrl ||
                    baseUrl == LlmProvider.OPENAI_COMPAT.defaultBaseUrl
                ) {
                    baseUrl = p.defaultBaseUrl
                }
                if (model == LlmProvider.ANTHROPIC.defaultModel ||
                    model == LlmProvider.OPENAI_COMPAT.defaultModel
                ) {
                    model = p.defaultModel
                }
            },
            baseUrl = baseUrl,
            onBaseUrlChange = { baseUrl = it },
            model = model,
            onModelChange = { model = it },
            keyInput = keyInput,
            onKeyInputChange = { keyInput = it },
            hasStoredKey = hasStoredKey,
            keyStoreEncrypted = keyStore.encrypted,
            onSaveConfig = {
                keyStore.provider = provider
                keyStore.baseUrlOverride = baseUrl.takeIf { it != provider.defaultBaseUrl }
                keyStore.modelOverride = model.takeIf { it != provider.defaultModel }
                if (keyInput.isNotBlank()) {
                    keyStore.setApiKey(keyInput)
                    keyInput = ""
                }
                hasStoredKey = keyStore.hasKey()
                addLog("APIPROBE_CONFIG saved provider=${provider.id} key_present=$hasStoredKey")
            },
            onClearKey = {
                keyStore.setApiKey(null)
                hasStoredKey = false
                addLog("APIPROBE_CONFIG key_cleared")
            },
            running = running,
            onRun = {
                val key = keyStore.apiKey()
                if (key == null) {
                    addLog("APIPROBE_SKIP reason=E-03_no_key")
                } else if (!running) {
                    running = true
                    lifecycleScope.launch {
                        try {
                            ApiProbe(applicationContext).run(
                                ApiProbe.Config(provider, baseUrl, model, key)
                            ) { line -> withContext(Dispatchers.Main) { addLog(line) } }
                            resultsVersion++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            addLog("APIPROBE_FAILED error=${e.javaClass.simpleName}")
                        } finally {
                            running = false
                        }
                    }
                }
            },
            logs = logs,
            results = results,
            exportStatus = exportStatus,
            onExport = {
                lifecycleScope.launch(Dispatchers.IO) {
                    val all = db.apiProbeResultDao().all()
                    val body = ApiProbeReport.buildJson(all, keyStore.apiKey())
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fileName = "aneb_apiprobe_$ts.json"
                    val outcome = Exporter.exportToDownloads(
                        applicationContext, fileName, "application/json", body,
                    )
                    val line =
                        "APIPROBE_EXPORT file=$fileName bytes=${outcome.bytes} " +
                            "status=${if (outcome.ok) "ok" else "fail"} " +
                            "claim_scope=${ApiProbeReport.CLAIM_SCOPE}"
                    android.util.Log.i("AnebProbe", line)
                    withContext(Dispatchers.Main) { exportStatus = line }
                }
            },
            // 预置接入（mode②）：选中预置自动填 provider/base/model；key 处理逐字不变。
            presets = ProviderPresets.all,
            selectedPresetId = selectedPresetId,
            onSelectPreset = { p ->
                selectedPresetId = p.id
                provider = p.toLlmProvider()
                baseUrl = p.baseUrl
                model = p.defaultModel
            },
            onOpenReachBoard = onOpenReachBoard,
            onBack = onBack,
        )
    }

    // ------------------------------------------------------------------
    // 可达性看板路由（mode①：AiReachabilityProbe 无 key 连接层探测，best-effort、不进 AQS）
    // ------------------------------------------------------------------

    @Composable
    private fun ReachBoardRoute(onBack: () -> Unit) {
        var rows by remember { mutableStateOf(emptyList<AiReachabilityProbe.Result>()) }
        var running by remember { mutableStateOf(false) }
        var lastRunLabel by remember { mutableStateOf<String?>(null) }
        ReachabilityBoardScreen(
            rows = rows,
            running = running,
            onRun = {
                if (!running) {
                    running = true
                    // 起跑先把全部预置播种为 UNPROBED，随 onResult 逐条就地更新（看板逐条亮起，不再像卡死）
                    rows = ProviderPresets.all.map { p ->
                        AiReachabilityProbe.Result(
                            presetId = p.id,
                            displayName = p.displayName,
                            host = runCatching { java.net.URI(p.baseUrl).host }.getOrNull() ?: p.baseUrl,
                            status = AiReachabilityProbe.Status.UNPROBED,
                            tlsHandshakeMs = null,
                            connectMs = null,
                            httpCode = null,
                            verified = p.verified,
                            note = null,
                        )
                    }
                    lifecycleScope.launch {
                        try {
                            val probed = withContext(Dispatchers.IO) {
                                AiReachabilityProbe().probeAll(ProviderPresets.all) { r ->
                                    withContext(Dispatchers.Main) {
                                        rows = rows.map { if (it.presetId == r.presetId) r else it }
                                    }
                                }
                            }
                            val ok = probed.count { it.status == AiReachabilityProbe.Status.OK }
                            lastRunLabel = "刚刚 · ${probed.size} 家 · $ok 通"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.i(
                                "AnebProbe",
                                "AIREACH_FAILED error=${e.javaClass.simpleName}",
                            )
                        } finally {
                            running = false
                        }
                    }
                }
            },
            onBack = onBack,
            lastRunLabel = lastRunLabel,
            claimScopeNote =
                "连接层口径（${AiReachabilityProbe.CLAIM_SCOPE}）：仅判定能否完成 TLS 握手" +
                    "（拿到任意 HTTP 响应即通），不测 TTFT、不进 AQS，不看 2xx/4xx 语义。",
        )
    }

    /**
     * API 探针 adb 自动化（模拟器 E2E 验收；仅 debug 构建生效）。结果只看 logcat 的
     * APIPROBE_RESULT 行（tag=AnebProbe），不落 UI。
     */
    private fun maybeApiProbeAutorun() {
        if (!BuildConfig.DEBUG) return
        if (intent?.getBooleanExtra("apiprobe_autorun", false) != true) return
        val server = intent?.getStringExtra("apiprobe_server") ?: return
        val key = intent?.getStringExtra("apiprobe_key") ?: return
        val provider = when (intent?.getStringExtra("apiprobe_provider")?.lowercase()) {
            "anthropic" -> LlmProvider.ANTHROPIC
            else -> LlmProvider.OPENAI_COMPAT
        }
        val model = intent?.getStringExtra("apiprobe_model") ?: provider.defaultModel
        lifecycleScope.launch {
            try {
                ApiProbe(applicationContext).run(
                    ApiProbe.Config(provider, server, model, key)
                ) { line -> android.util.Log.i("AnebProbe", line) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.i("AnebProbe", "APIPROBE_FAILED error=${e.javaClass.simpleName}")
            }
        }
    }
}
