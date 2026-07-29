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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.aneb.probe.apiprobe.ProviderPresets
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.NetworkComprehensiveResultEntity
import com.aneb.probe.data.RealtimeSimulationResultEntity
import com.aneb.probe.data.TokenSimulationResultEntity
import com.aneb.probe.data.Exporter
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.engine.AbRunner
import com.aneb.probe.engine.AnebResultJsonlExporter
import com.aneb.probe.engine.AnebTestMode
import com.aneb.probe.engine.ContinuityRunner
import com.aneb.probe.engine.ProbeRunService
import com.aneb.probe.engine.ProbeRunSession
import com.aneb.probe.engine.ProbeSpecialRunService
import com.aneb.probe.engine.ProfileRepository
import com.aneb.probe.engine.RepeatabilityQualificationActivityHandoff
import com.aneb.probe.engine.RepeatabilityQualificationLaunchExtras
import com.aneb.probe.engine.ScenarioProfile
import com.aneb.probe.engine.SpecialRunSession
import com.aneb.probe.engine.TestEngine
import com.aneb.probe.net.AnebClient
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
 *   History / Settings。
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

    private lateinit var radioCollector: RadioCollector
    private lateinit var db: AnebDatabase
    private lateinit var settingsStore: ProbeSettingsStore

    private var intentServer: String? = null
    private var intentAutorun: Boolean = false
    private var intentModeOverride: TestEngine.Mode? = null
    private var intentTestModeOverride: AnebTestMode? = null
    private var intentTransportOverride: TestEngine.TransportMode? = null
    private var intentInject: String? = null
    private var intentDriveTestOverride: Boolean? = null
    private var intentGatewayBase: String? = null
    private var intentGatewayToken: String? = null
    private val intentQualificationHandoff = RepeatabilityQualificationActivityHandoff()

    private var intentContinuity: Boolean = false
    private var intentCTokens: Int = ContinuityRunner.DEFAULT_TOKENS
    private var intentC3IdleS: List<Int> = ContinuityRunner.DEFAULT_C3_IDLE_S

    private var intentAb: Boolean = false
    private var intentAbPairs: Int = AbRunner.DEFAULT_PAIRS
    private var intentAbNetlog: Boolean = false

    private val manualPermissionViewModel: ManualPermissionViewModel by viewModels()
    private val radioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            when (manualPermissionViewModel.state.value.awaitingSystemPermission) {
                PendingSystemPermission.RADIO ->
                    manualPermissionViewModel.onRadioResult(radioPermissionState())
                PendingSystemPermission.NOTIFICATION ->
                    manualPermissionViewModel.onNotificationResult(
                        granted = result[Manifest.permission.POST_NOTIFICATIONS] == true,
                    )
                null -> Unit
            }
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

    private fun requestRadioPermissions() {
        if (!manualPermissionViewModel.beginRadioRequest()) return
        radioPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }

    private fun requestRunNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            manualPermissionViewModel.notificationPermissionNotNeeded()
            return
        }
        if (manualPermissionViewModel.beginNotificationRequest()) {
            radioPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
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
        data object BasicTesting : Screen
        data object TokenSimulationTesting : Screen
        data class TokenSimulationResult(val runId: String) : Screen
        data object RealtimeSimulationTesting : Screen
        data class RealtimeSimulationResult(val runId: String) : Screen
        data class BasicResult(val runId: String) : Screen
        data class Result(val runId: String, val fromHistory: Boolean) : Screen
        data object ReachBoard : Screen
        data object Profiles : Screen
        data object Servers : Screen
        data object Report : Screen
        data class Share(val model: ShareCard.Model, val returnTo: Result) : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(1, 2, 7)),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        radioCollector = RadioCollector(this)
        db = AnebDatabase.get(applicationContext)
        settingsStore = ProbeSettingsStore(applicationContext)
        intentServer = intent?.getStringExtra("server")
        intentQualificationHandoff.replace(
            RepeatabilityQualificationLaunchExtras.readAndRemoveForActivity(
                isFirstCreation = savedInstanceState == null,
                enabled = BuildConfig.DEBUG,
                getBoolean = { key, default -> intent?.getBooleanExtra(key, default) ?: default },
                getString = { key -> intent?.getStringExtra(key) },
                remove = { key -> intent?.removeExtra(key) },
            ),
        )
        intentAutorun = consumeAutorunOnce(
            isFirstCreation = savedInstanceState == null,
            enabled = BuildConfig.DEBUG,
            readAutorun = { intent?.getBooleanExtra("autorun", false) == true },
            removeAutorun = { intent?.removeExtra("autorun") },
        )
        intentModeOverride = when (intent?.getStringExtra("mode")?.lowercase()) {
            "quick" -> TestEngine.Mode.QUICK
            "forensic" -> TestEngine.Mode.FORENSIC
            "stress" -> TestEngine.Mode.STRESS
            "recovery" -> TestEngine.Mode.STRESS
            "network_recovery", "weak_recovery" -> TestEngine.Mode.NETWORK_RECOVERY
            "gateway_loss" -> TestEngine.Mode.GATEWAY_LOSS
            "gateway_recovery" -> TestEngine.Mode.GATEWAY_RECOVERY
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
        intentTestModeOverride = when (intent?.getStringExtra("test_mode")?.lowercase()) {
            "network_basic", "basic" -> AnebTestMode.NETWORK_BASIC
            "token_simulation", "token" -> AnebTestMode.TOKEN_SIMULATION
            "ai_realtime_simulation", "realtime", "live" -> AnebTestMode.AI_REALTIME_SIMULATION
            "token_experience", "legacy_token" -> AnebTestMode.TOKEN_EXPERIENCE
            else -> null
        }
        intentInject = if (BuildConfig.DEBUG) intent?.getStringExtra("inject") else null
        intentGatewayBase = if (BuildConfig.DEBUG) intent?.getStringExtra("gateway_base") else null
        intentGatewayToken = if (BuildConfig.DEBUG) intent?.getStringExtra("gateway_token") else null
        intent?.removeExtra("gateway_token")
        intentDriveTestOverride = if (intent?.hasExtra("drive_test") == true) {
            intent.getBooleanExtra("drive_test", false)
        } else {
            null
        }
        val launchSettings = resolveLaunchSettings(
            saved = settingsStore.load(),
            overrides = ProbeLaunchOverrides(
                serverUrl = intentServer,
                testMode = intentTestModeOverride,
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
                // edge-to-edge 会让 Activity 覆盖系统导航区；顶部和底部都必须显式消费。
                // P40 Pro 三键导航真机验证：不消费底部 inset 时，五栏只露图标上缘且不可点击。
                Surface(
                    color = AnebTheme.colors.background,
                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                ) {
                    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                    // 底部 5-tab 外壳选中态（默认测试）；下钻只在 Home 哨兵下按 tab 决定根，
                    // 故切 tab 只发生在各 tab 根（切换前后 screen 均为 Home），子状态天然互不串扰。
                    var tab by rememberSaveable { mutableStateOf(MainTab.Test) }
                    var serverUrl by rememberSaveable {
                        mutableStateOf(launchSettings.serverUrl)
                    }
                    var mode by rememberSaveable {
                        mutableStateOf(
                            if (
                                (launchSettings.mode == TestEngine.Mode.STRESS &&
                                launchSettings.testMode !in setOf(
                                    AnebTestMode.NETWORK_BASIC,
                                    AnebTestMode.TOKEN_SIMULATION,
                                    AnebTestMode.AI_REALTIME_SIMULATION,
                                )) || (launchSettings.mode in setOf(
                                    TestEngine.Mode.NETWORK_RECOVERY,
                                    TestEngine.Mode.GATEWAY_LOSS,
                                    TestEngine.Mode.GATEWAY_RECOVERY,
                                ) &&
                                    launchSettings.testMode != AnebTestMode.NETWORK_BASIC)
                            ) {
                                TestEngine.Mode.QUICK
                            } else {
                                launchSettings.mode
                            },
                        )
                    }
                    var testMode by rememberSaveable { mutableStateOf(launchSettings.testMode) }
                    var transport by rememberSaveable { mutableStateOf(launchSettings.transport) }
                    var driveTest by rememberSaveable { mutableStateOf(launchSettings.driveTest) }
                    val runSession by ProbeRunService.session.collectAsStateWithLifecycle()
                    val serviceLogs by ProbeRunService.logs.collectAsStateWithLifecycle()
                    val serviceTelemetry by ProbeRunService.telemetry.collectAsStateWithLifecycle()
                    val basicTelemetry by ProbeRunService.basicTelemetry.collectAsStateWithLifecycle()
                    val tokenSimulationTelemetry by ProbeRunService.tokenSimulationTelemetry.collectAsStateWithLifecycle()
                    val tokenSimulationResult by ProbeRunService.tokenSimulationResult.collectAsStateWithLifecycle()
                    val realtimeSimulationTelemetry by ProbeRunService.realtimeSimulationTelemetry.collectAsStateWithLifecycle()
                    val realtimeSimulationResult by ProbeRunService.realtimeSimulationResult.collectAsStateWithLifecycle()
                    val specialRunSession by ProbeSpecialRunService.session.collectAsStateWithLifecycle()
                    val manualPermissionState by
                        manualPermissionViewModel.state.collectAsStateWithLifecycle()
                    val auxiliaryRunning = specialRunSession is SpecialRunSession.Running
                    val running = runSession is ProbeRunSession.Running || auxiliaryRunning
                    var acceptManualSessions by remember { mutableStateOf(!launchRequestedAutorun) }
                    var homeNotice by rememberSaveable { mutableStateOf<String?>(null) }
                    var bulkExportRunning by remember { mutableStateOf(false) }
                    var bulkExportStatus by remember { mutableStateOf<String?>(null) }
                    var radioEvidenceLimited by remember { mutableStateOf(false) }
                    var nodeReach by remember { mutableStateOf<ReachabilityProbe.DualReach?>(null) }
                    var nodeReachRefreshing by remember { mutableStateOf(false) }
                    var nodeReachError by remember { mutableStateOf<String?>(null) }

                    // ---- 主 run 由前台 Service 持有；Activity 只发配置并观察状态 ----
                    fun startRun(fromAutorun: Boolean) {
                        if (running) return
                        homeNotice = null
                        radioEvidenceLimited = !radioPermissionState().hasFullRadioEvidence
                        if (!fromAutorun) {
                            acceptManualSessions = true
                            screen = when (testMode) {
                                AnebTestMode.NETWORK_BASIC -> Screen.BasicTesting
                                AnebTestMode.TOKEN_SIMULATION -> Screen.TokenSimulationTesting
                                AnebTestMode.AI_REALTIME_SIMULATION -> Screen.RealtimeSimulationTesting
                                AnebTestMode.TOKEN_EXPERIENCE -> Screen.Testing
                            }
                        }
                        val oneTimeGatewayToken = intentGatewayToken
                        intentGatewayToken = null
                        val oneTimeQualificationWire = intentQualificationHandoff.take()
                        ProbeRunService.start(
                            context = applicationContext,
                            config = ProbeRunService.Config(
                                serverBase = serverUrl,
                                testMode = testMode,
                                mode = mode,
                                transport = transport,
                                inject = intentInject,
                                driveTest = driveTest,
                                gatewayBase = intentGatewayBase,
                                gatewayToken = oneTimeGatewayToken,
                                qualificationWire = oneTimeQualificationWire,
                            ),
                            autorun = fromAutorun,
                        )
                    }

                    fun requestManualRun() {
                        ManualRunReadiness.blocker(
                            hasActiveNetwork = hasActiveNetwork(),
                            serverUrl = serverUrl,
                            allowCleartext = BuildConfig.DEBUG,
                        )?.let {
                            homeNotice = it
                            return
                        }
                        val state = radioPermissionState()
                        if (!requiresRadioEvidenceRationale(testMode, state)) {
                            if (manualPermissionViewModel.beginStartTest()) {
                                requestRunNotificationPermission()
                            }
                        } else {
                            manualPermissionViewModel.showRadioRationale(
                                purpose = RadioPermissionPurpose.START_TEST,
                                radioState = state,
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
                        ProbeSpecialRunService.startContinuity(
                            context = applicationContext,
                            server = serverUrl,
                            transport = transport,
                            tokens = intentCTokens,
                            c3IdleSeconds = intentC3IdleS,
                        )
                    }

                    fun startAbRun() {
                        if (running) return
                        ProbeSpecialRunService.startAb(
                            context = applicationContext,
                            server = serverUrl,
                            pairs = intentAbPairs,
                            netlog = intentAbNetlog,
                        )
                    }

                    LaunchedEffect(manualPermissionState.event?.id) {
                        val pendingEvent = manualPermissionState.event ?: return@LaunchedEffect
                        when (
                            val event = manualPermissionViewModel.takeEvent(pendingEvent.id)
                                ?: return@LaunchedEffect
                        ) {
                            is ManualPermissionEvent.RequestNotification ->
                                requestRunNotificationPermission()
                            is ManualPermissionEvent.StartTest ->
                                startRun(fromAutorun = false)
                            is ManualPermissionEvent.EnableDriveTest -> {
                                driveTest = true
                                settingsStore.saveDriveTest(true)
                                android.util.Log.i("AnebProbe", "DRIVE_TEST_TOGGLE enabled=true")
                            }
                        }
                    }

                    LaunchedEffect(runSession) {
                        when (val session = runSession) {
                            ProbeRunSession.Idle -> Unit
                            is ProbeRunSession.Running -> {
                                if (acceptManualSessions && !session.autorun) {
                                    radioEvidenceLimited = !radioPermissionState().hasFullRadioEvidence
                                    screen = when (session.testMode) {
                                        AnebTestMode.NETWORK_BASIC -> Screen.BasicTesting
                                        AnebTestMode.TOKEN_SIMULATION -> Screen.TokenSimulationTesting
                                        AnebTestMode.AI_REALTIME_SIMULATION -> Screen.RealtimeSimulationTesting
                                        AnebTestMode.TOKEN_EXPERIENCE -> Screen.Testing
                                    }
                                }
                            }
                            is ProbeRunSession.Completed -> {
                                if (acceptManualSessions && !session.autorun) {
                                    screen = when (session.testMode) {
                                        AnebTestMode.NETWORK_BASIC -> Screen.BasicResult(session.runId)
                                        AnebTestMode.TOKEN_SIMULATION -> Screen.TokenSimulationResult(session.runId)
                                        AnebTestMode.AI_REALTIME_SIMULATION -> Screen.RealtimeSimulationResult(session.runId)
                                        AnebTestMode.TOKEN_EXPERIENCE -> Screen.Result(session.runId, fromHistory = false)
                                    }
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

                    // ---- ANEB_UI 五栏外壳（测试 / 探针 / 结果 / 地图 / 设置）----
                    // 底栏仅在各 tab 根（screen==Home）显示；下钻屏（Testing/Result/
                    // ReachBoard/Report）隐底栏、Testing 运行中保持全屏专注。contentWindowInsets 置 0：
                    // Surface 已消费顶部状态栏，Scaffold 不再重复消费系统 Insets。
                    val atRoot = screen is Screen.Home
                    val showMainNav = atRoot || screen is Screen.Servers
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = AnebTheme.colors.background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    ) { innerPadding ->
                        // 根页面主动为五栏导航留白；首页/地图的抽屉则刻意延伸到导航背后，
                        // 对齐 HTML 原型的覆盖关系，因此这里不消费 Scaffold bottom padding。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .consumeWindowInsets(innerPadding),
                        ) {
                            when (val s = screen) {
                                // ---- 各 tab 根（显底栏）：测试=Home / 历史=History / 设置=Settings ----
                                is Screen.Home -> when (tab) {
                                    MainTab.Test -> HomeRoute(
                                        running = running,
                                        testMode = testMode,
                                        mode = mode,
                                        onTestModeChange = {
                                            testMode = it
                                            settingsStore.saveTestMode(it)
                                            if (
                                                it !in setOf(
                                                    AnebTestMode.NETWORK_BASIC,
                                                    AnebTestMode.TOKEN_SIMULATION,
                                                    AnebTestMode.AI_REALTIME_SIMULATION,
                                                ) && mode == TestEngine.Mode.STRESS ||
                                                mode == TestEngine.Mode.NETWORK_RECOVERY && it != AnebTestMode.NETWORK_BASIC
                                            ) {
                                                mode = TestEngine.Mode.QUICK
                                                settingsStore.saveMode(mode)
                                            }
                                        },
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
                                        onOpenBasicResult = { runId ->
                                            screen = Screen.BasicResult(runId)
                                        },
                                    )
                                    MainTab.Probe -> ProfileCatalogRoute(
                                        serverUrl = serverUrl,
                                        onBack = { tab = MainTab.Test },
                                        showBack = false,
                                    )
                                    MainTab.Results -> HistoryRoute(
                                        onOpen = { runId ->
                                            screen = Screen.Result(runId, fromHistory = true)
                                        },
                                        onOpenBasic = { runId ->
                                            screen = Screen.BasicResult(runId)
                                        },
                                        onOpenTokenSimulation = { runId ->
                                            screen = Screen.TokenSimulationResult(runId)
                                        },
                                        onOpenRealtimeSimulation = { runId ->
                                            screen = Screen.RealtimeSimulationResult(runId)
                                        },
                                        onGenerateReport = { screen = Screen.Report },
                                        onBack = { tab = MainTab.Test },
                                        showBack = false,
                                    )
                                    MainTab.Map -> ExperienceMapRoute()
                                    MainTab.Settings -> SettingsScreen(
                                        serverUrl = serverUrl,
                                        onServerUrlChange = {
                                            serverUrl = it
                                            settingsStore.saveServerUrl(it)
                                        },
                                        testMode = testMode,
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
                                                    manualPermissionViewModel.showRadioRationale(
                                                        purpose = RadioPermissionPurpose.DRIVE_TEST,
                                                        radioState = state,
                                                    )
                                                }
                                            }
                                            android.util.Log.i("AnebProbe", "DRIVE_TEST_TOGGLE enabled=$driveTest")
                                        },
                                        injectActive = intentInject,
                                        exportRunning = bulkExportRunning,
                                        exportStatus = bulkExportStatus,
                                        onExportAllResults = {
                                            if (!bulkExportRunning) {
                                                bulkExportRunning = true
                                                bulkExportStatus = "正在校验并导出…"
                                                doExportAllUnifiedResults { status ->
                                                    bulkExportStatus = status
                                                    bulkExportRunning = false
                                                }
                                            }
                                        },
                                        onOpenServer = ::openServerScreen,
                                        // 可达性看板已降为设置二级入口（下钻屏）。
                                        onOpenReachBoard = { screen = Screen.ReachBoard },
                                        onOpenProfiles = { screen = Screen.Profiles },
                                        onBack = { tab = MainTab.Test },
                                        showBack = false,
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
                                is Screen.BasicTesting -> BasicSpeedTestingScreen(
                                    telemetry = basicTelemetry,
                                    nodeLabel = ProbeNodeCatalog.labelForUrl(serverUrl),
                                    onCancel = ::cancelManualRun,
                                )
                                is Screen.TokenSimulationTesting -> TokenSimulationTestingScreen(
                                    telemetry = tokenSimulationTelemetry,
                                    nodeLabel = ProbeNodeCatalog.labelForUrl(serverUrl),
                                    onCancel = ::cancelManualRun,
                                )
                                is Screen.TokenSimulationResult -> {
                                    TokenSimulationResultRoute(
                                        runId = s.runId,
                                        liveResult = tokenSimulationResult,
                                        onBack = { screen = Screen.Home },
                                    )
                                }
                                is Screen.RealtimeSimulationTesting -> RealtimeSimulationTestingScreen(
                                    telemetry = realtimeSimulationTelemetry,
                                    nodeLabel = ProbeNodeCatalog.labelForUrl(serverUrl),
                                    onCancel = ::cancelManualRun,
                                )
                                is Screen.RealtimeSimulationResult -> {
                                    RealtimeSimulationResultRoute(
                                        runId = s.runId,
                                        liveResult = realtimeSimulationResult,
                                        onBack = { screen = Screen.Home },
                                    )
                                }
                                is Screen.BasicResult -> {
                                    BasicResultRoute(runId = s.runId, onBack = { screen = Screen.Home })
                                }
                                is Screen.Report -> ReportRoute(onBack = { screen = Screen.Home })
                                is Screen.Result -> ResultRoute(
                                    runId = s.runId,
                                    // 回根：tab 已记住来路（测试 手动测/上次结果 或 历史 tab 下钻），
                                    // 回到 Home 哨兵即落回当前 tab 根。
                                    onBack = { screen = Screen.Home },
                                    onOpenShare = { model -> screen = Screen.Share(model, s) },
                                )
                                is Screen.ReachBoard -> ReachBoardRoute(onBack = { screen = Screen.Home })
                                is Screen.Profiles -> ProfileCatalogRoute(
                                    serverUrl = serverUrl,
                                    onBack = { screen = Screen.Home },
                                    showBack = true,
                                )
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
                                is Screen.Share -> SharePreviewScreen(
                                    model = s.model,
                                    onBack = { screen = s.returnTo },
                                    onSave = {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            ShareCard.renderAndSave(applicationContext, s.model)
                                        }
                                    },
                                    onShare = {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            val uri = ShareCard.renderAndSave(applicationContext, s.model)
                                            withContext(Dispatchers.Main) {
                                                ShareCard.launchShare(applicationContext, uri)
                                            }
                                        }
                                    },
                                )
                            }
                            if (showMainNav) {
                                AnebTabBar(
                                    current = tab,
                                    onSelect = {
                                        tab = it
                                        if (screen !is Screen.Home) screen = Screen.Home
                                    },
                                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                                )
                            }
                        }
                    }

                    manualPermissionState.prompt?.let { prompt ->
                        RadioPermissionDialog(
                            prompt = prompt,
                            onRequest = ::requestRadioPermissions,
                            onContinueLimited = { manualPermissionViewModel.continueLimitedStart() },
                            onOpenSettings = {
                                manualPermissionViewModel.cancelPending()
                                openAppPermissionSettings()
                            },
                            onDismiss = { manualPermissionViewModel.cancelPending() },
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
        testMode: AnebTestMode,
        mode: TestEngine.Mode,
        onTestModeChange: (AnebTestMode) -> Unit,
        notice: String?,
        connectionLabel: String,
        nodeLabel: String,
        onStart: () -> Unit,
        onOpenServer: () -> Unit,
        onOpenResult: (String) -> Unit,
        onOpenBasicResult: (String) -> Unit,
    ) {
        // 最近一次 run（run 结束 running→false 时刷新，带出上次结果 chip）
        val lastRun by produceState<TestRun?>(initialValue = null, running) {
            value = withContext(Dispatchers.IO) {
                db.testRunDao().all().maxByOrNull { it.startedAtEpochMs }
            }
        }
        val lastBasicRun by produceState<NetworkComprehensiveResultEntity?>(initialValue = null, running) {
            value = withContext(Dispatchers.IO) {
                db.networkComprehensiveResultDao().all().maxByOrNull { it.startedAtEpochMs }
            }
        }
        HomeScreen(
            lastRun = lastRun,
            lastBasicRun = lastBasicRun,
            testMode = testMode,
            mode = mode,
            onTestModeChange = onTestModeChange,
            running = running,
            notice = notice,
            connectionLabel = connectionLabel,
            nodeLabel = nodeLabel,
            onStart = onStart,
            onOpenServer = onOpenServer,
            onOpenLastResult = onOpenResult,
            onOpenLastBasicResult = onOpenBasicResult,
        )
    }

    @Composable
    private fun BasicResultRoute(runId: String, onBack: () -> Unit) {
        val result by produceState<com.aneb.probe.engine.BasicSpeedResult?>(initialValue = null, runId) {
            value = withContext(Dispatchers.IO) { db.networkComprehensiveResultDao().byId(runId)?.toDomain() }
        }
        val exportAvailable by produceState(initialValue = false, runId) {
            value = withContext(Dispatchers.IO) { db.resultEnvelopeDao().byId(runId) != null }
        }
        var exportStatus by remember(runId) { mutableStateOf<String?>(null) }
        val loaded = result
        if (loaded != null) {
            BasicSpeedResultScreen(
                result = loaded,
                onBack = onBack,
                exportAvailable = exportAvailable,
                exportStatus = exportStatus,
                onExportJsonl = { doExportUnifiedResult(runId, share = false) { exportStatus = it } },
                onShareJsonl = { doExportUnifiedResult(runId, share = true) { exportStatus = it } },
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("未找到网络综合记录", color = AnebTheme.colors.muted)
            }
        }
    }

    @Composable
    private fun TokenSimulationResultRoute(
        runId: String,
        liveResult: com.aneb.probe.engine.TokenSimulationResult?,
        onBack: () -> Unit,
    ) {
        val stored by produceState<TokenSimulationResultEntity?>(initialValue = null, runId) {
            value = withContext(Dispatchers.IO) { db.tokenSimulationResultDao().byId(runId) }
        }
        val exportAvailable by produceState(initialValue = false, runId) {
            value = withContext(Dispatchers.IO) { db.resultEnvelopeDao().byId(runId) != null }
        }
        var exportStatus by remember(runId) { mutableStateOf<String?>(null) }
        val export = { doExportUnifiedResult(runId, share = false) { exportStatus = it } }
        val share = { doExportUnifiedResult(runId, share = true) { exportStatus = it } }
        when {
            liveResult?.runId == runId -> TokenSimulationResultScreen(
                result = liveResult,
                onBack = onBack,
                exportAvailable = exportAvailable,
                exportStatus = exportStatus,
                onExportJsonl = export,
                onShareJsonl = share,
            )
            stored != null -> TokenSimulationStoredResultScreen(
                result = checkNotNull(stored),
                onBack = onBack,
                exportAvailable = exportAvailable,
                exportStatus = exportStatus,
                onExportJsonl = export,
                onShareJsonl = share,
            )
            else -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("未找到 Token 仿真记录", color = AnebTheme.colors.muted)
            }
        }
    }

    @Composable
    private fun RealtimeSimulationResultRoute(
        runId: String,
        liveResult: com.aneb.probe.engine.RealtimeSimulationResult?,
        onBack: () -> Unit,
    ) {
        val stored by produceState<RealtimeSimulationResultEntity?>(initialValue = null, runId) {
            value = withContext(Dispatchers.IO) { db.realtimeSimulationResultDao().byId(runId) }
        }
        val exportAvailable by produceState(initialValue = false, runId) {
            value = withContext(Dispatchers.IO) { db.resultEnvelopeDao().byId(runId) != null }
        }
        var exportStatus by remember(runId) { mutableStateOf<String?>(null) }
        val export = { doExportUnifiedResult(runId, share = false) { exportStatus = it } }
        val share = { doExportUnifiedResult(runId, share = true) { exportStatus = it } }
        when {
            liveResult?.runId == runId -> RealtimeSimulationResultScreen(
                result = liveResult,
                onBack = onBack,
                exportAvailable = exportAvailable,
                exportStatus = exportStatus,
                onExportJsonl = export,
                onShareJsonl = share,
            )
            stored != null -> RealtimeSimulationStoredResultScreen(
                result = checkNotNull(stored),
                onBack = onBack,
                exportAvailable = exportAvailable,
                exportStatus = exportStatus,
                onExportJsonl = export,
                onShareJsonl = share,
            )
            else -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("未找到 AI 实时交互记录", color = AnebTheme.colors.muted)
            }
        }
    }

    @Composable
    private fun HistoryRoute(
        onOpen: (String) -> Unit,
        onOpenBasic: (String) -> Unit,
        onOpenTokenSimulation: (String) -> Unit,
        onOpenRealtimeSimulation: (String) -> Unit,
        onGenerateReport: () -> Unit,
        onBack: () -> Unit,
        showBack: Boolean = true,
    ) {
        val history by produceState(initialValue = HistoryData()) {
            value = withContext(Dispatchers.IO) {
                HistoryData(
                    tokenRuns = db.testRunDao().all(),
                    basicRuns = db.networkComprehensiveResultDao().all(),
                    tokenSimulationRuns = db.tokenSimulationResultDao().all(),
                    realtimeSimulationRuns = db.realtimeSimulationResultDao().all(),
                )
            }
        }
        HistoryScreen(
            runs = history.tokenRuns,
            basicRuns = history.basicRuns,
            tokenSimulationRuns = history.tokenSimulationRuns,
            realtimeSimulationRuns = history.realtimeSimulationRuns,
            onOpen = onOpen,
            onOpenBasic = onOpenBasic,
            onOpenTokenSimulation = onOpenTokenSimulation,
            onOpenRealtimeSimulation = onOpenRealtimeSimulation,
            onGenerateReport = onGenerateReport,
            onBack = onBack,
            showBack = showBack,
        )
    }

    private data class HistoryData(
        val tokenRuns: List<TestRun> = emptyList(),
        val basicRuns: List<NetworkComprehensiveResultEntity> = emptyList(),
        val tokenSimulationRuns: List<TokenSimulationResultEntity> = emptyList(),
        val realtimeSimulationRuns: List<RealtimeSimulationResultEntity> = emptyList(),
    )

    @Composable
    private fun ExperienceMapRoute() {
        val points by produceState(initialValue = emptyList<ExperienceMapPoint>()) {
            value = withContext(Dispatchers.IO) {
                val runs = db.testRunDao().all().associateBy { it.runId }
                val rttByRun = db.scenarioResultDao().all()
                    .groupBy { it.runId }
                    .mapValues { (_, rows) -> rows.mapNotNull { it.n1RttP50Ms }.takeIf { it.isNotEmpty() }?.average() }
                db.radioSampleDao().withCoordinates().mapNotNull { sample ->
                    val runId = sample.runId ?: return@mapNotNull null
                    val lat = sample.lat ?: return@mapNotNull null
                    val lon = sample.lon ?: return@mapNotNull null
                    ExperienceMapPoint(
                        runId = runId,
                        tsNanos = sample.tsNanos,
                        lat = lat,
                        lon = lon,
                        accuracyM = sample.accuracyM,
                        aqsScore = runs[runId]?.aqsScore,
                        rttMs = rttByRun[runId],
                    )
                }
            }
        }
        ExperienceMapScreen(points)
    }

    private data class ProfileCatalogData(
        val profiles: List<ScenarioProfile> = emptyList(),
        val source: String? = null,
        val warnings: List<String> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    @Composable
    private fun ProfileCatalogRoute(serverUrl: String, onBack: () -> Unit, showBack: Boolean) {
        var refreshTick by rememberSaveable { mutableIntStateOf(0) }
        val catalog by produceState(
            initialValue = ProfileCatalogData(),
            serverUrl,
            refreshTick,
        ) {
            value = ProfileCatalogData(loading = true)
            value = withContext(Dispatchers.IO) {
                try {
                    // Profile 目录属于配置读取，不参与测量或评分，但仍需复用 E-01 的
                    // SNI-RST 双通道选路。否则测速能够自动走 bare-IP，目录页却会误退回
                    // APK 副本，给用户造成“节点配置未核验”的假象。
                    val pair = ReachabilityProbe.deriveE01Pair(serverUrl)
                    val reach = if (pair == null) {
                        null
                    } else {
                        try {
                            ReachabilityProbe().probeDual(pair.first, pair.second)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val catalogBase = ReachabilityProbe.preferredMeasureBase(serverUrl, reach)
                    if (catalogBase.trimEnd('/') != serverUrl.trim().trimEnd('/')) {
                        android.util.Log.i(
                            "AnebProbe",
                            "PROFILE_CATALOG_ROUTE route=bare_ip reason=sni_rst_ip_ok",
                        )
                    }
                    val loaded = ProfileRepository(applicationContext).load(AnebClient(), catalogBase)
                    ProfileCatalogData(
                        profiles = loaded.profiles.values.sortedWith(compareBy({ it.modeId }, { it.profileId })),
                        source = loaded.source,
                        warnings = loaded.warnings,
                        loading = false,
                    )
                } catch (_: Exception) {
                    ProfileCatalogData(
                        loading = false,
                        error = "Profile 目录读取失败，请检查节点或重新安装 APK。",
                    )
                }
            }
        }
        ProfileCatalogScreen(
            profiles = catalog.profiles,
            source = catalog.source,
            warnings = catalog.warnings,
            loading = catalog.loading,
            error = catalog.error,
            onRefresh = { refreshTick += 1 },
            onBack = onBack,
            showBack = showBack,
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
    private fun ResultRoute(
        runId: String,
        onBack: () -> Unit,
        onOpenShare: (ShareCard.Model) -> Unit,
    ) {
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
            onShare = onOpenShare,
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

    /** Exports one immutable supported result envelope; sharing never rebuilds or rescales it. */
    private fun doExportUnifiedResult(
        runId: String,
        share: Boolean,
        onStatus: (String) -> Unit,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val record = db.resultEnvelopeDao().byId(runId)
            if (record == null) {
                android.util.Log.w("AnebProbe", "RESULT_EXPORT run_id=$runId status=missing")
                withContext(Dispatchers.Main) { onStatus("无法导出：这条记录没有统一结果信封。") }
                return@launch
            }
            val content = runCatching { AnebResultJsonlExporter.export(listOf(record)) }
                .getOrElse { error ->
                    val unsupported = (error as? AnebResultJsonlExporter.VerificationException)?.kind ==
                        AnebResultJsonlExporter.RejectionKind.UNSUPPORTED_SCHEMA
                    android.util.Log.e(
                        "AnebProbe",
                        "RESULT_EXPORT run_id=$runId status=${if (unsupported) "schema_unsupported" else "integrity_fail"}",
                        error,
                    )
                    withContext(Dispatchers.Main) {
                        onStatus(
                            if (unsupported) {
                                "无法导出：这条结果的格式版本暂不受支持，原记录未损坏。"
                            } else {
                                "失败：结果完整性校验未通过。"
                            },
                        )
                    }
                    return@launch
                }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeType = record.testType.replace(Regex("[^a-z0-9_-]"), "_")
            val fileName = "aneb_result_${safeType}_${runId.take(8)}_$ts.jsonl"
            val outcome = Exporter.exportToDownloads(
                applicationContext,
                fileName,
                "application/x-ndjson",
                content,
            )
            val line =
                "RESULT_EXPORT run_id=$runId schema=${record.schemaVersion} type=${record.testType} " +
                    "share=$share file=$fileName " +
                    "bytes=${outcome.bytes} status=${if (outcome.ok) "ok" else "fail"} " +
                    "uri=${outcome.uri ?: "null"} error=${outcome.error?.replace(' ', '_') ?: "none"}"
            android.util.Log.i("AnebProbe", line)
            withContext(Dispatchers.Main) {
                if (!outcome.ok || outcome.uri == null) {
                    onStatus("失败：无法写入系统下载目录。")
                    return@withContext
                }
                onStatus(if (share) "证据文件已生成，请选择分享目标。" else "已保存到系统下载目录：$fileName")
                if (share) {
                    val uri = Uri.parse(outcome.uri)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/x-ndjson"
                        putExtra(Intent.EXTRA_SUBJECT, "ANEB 可审计测试结果")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("ANEB JSONL", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "分享 ANEB 测试证据"))
                }
            }
        }
    }

    /** Exports every independently verified immutable result envelope in chronological JSONL order. */
    private fun doExportAllUnifiedResults(onStatus: (String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val records = db.resultEnvelopeDao().all()
            if (records.isEmpty()) {
                android.util.Log.i("AnebProbe", "RESULT_BULK_EXPORT status=empty")
                withContext(Dispatchers.Main) { onStatus("暂无可导出的正式测试结果。") }
                return@launch
            }
            val selection = runCatching { AnebResultJsonlExporter.selectVerifiable(records) }
                .getOrElse { error ->
                    android.util.Log.e(
                        "AnebProbe",
                        "RESULT_BULK_EXPORT status=selection_fail records=${records.size}",
                        error,
                    )
                    withContext(Dispatchers.Main) { onStatus("失败：结果记录存在重复标识，未生成文件。") }
                    return@launch
                }
            if (selection.accepted.isEmpty()) {
                val unsupportedCount = selection.rejected.count {
                    it.kind == AnebResultJsonlExporter.RejectionKind.UNSUPPORTED_SCHEMA
                }
                android.util.Log.e(
                    "AnebProbe",
                    "RESULT_BULK_EXPORT status=no_verifiable_record records=${records.size} " +
                        "rejected_ids=${selection.rejected.joinToString(",") { it.runId }}",
                )
                withContext(Dispatchers.Main) {
                    onStatus(
                        if (unsupportedCount == selection.rejected.size) {
                            "未生成文件：现有结果均为暂不支持的格式版本，原记录未损坏。"
                        } else {
                            "失败：没有通过完整性校验的结果，未生成文件。"
                        },
                    )
                }
                return@launch
            }
            val content = AnebResultJsonlExporter.export(selection.accepted)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "aneb_results_${selection.accepted.size}_of_${records.size}_$ts.jsonl"
            val outcome = Exporter.exportToDownloads(
                applicationContext,
                fileName,
                "application/x-ndjson",
                content,
            )
            android.util.Log.i(
                "AnebProbe",
                "RESULT_BULK_EXPORT records=${records.size} accepted=${selection.accepted.size} " +
                    "rejected=${selection.rejected.size} " +
                    "rejected_ids=${selection.rejected.joinToString(",") { it.runId }} " +
                    "file=$fileName bytes=${outcome.bytes} " +
                    "status=${if (outcome.ok) "ok" else "fail"} " +
                    "uri=${outcome.uri ?: "null"} error=${outcome.error?.replace(' ', '_') ?: "none"}",
            )
            withContext(Dispatchers.Main) {
                onStatus(
                    if (outcome.ok) {
                        if (selection.rejected.isEmpty()) {
                            "已导出 ${selection.accepted.size} 条可验证结果。文件已保存到系统下载目录。"
                        } else {
                            val unsupportedCount = selection.rejected.count {
                                it.kind == AnebResultJsonlExporter.RejectionKind.UNSUPPORTED_SCHEMA
                            }
                            val integrityCount = selection.rejected.size - unsupportedCount
                            "已导出 ${selection.accepted.size}/${records.size} 条；" +
                                "$unsupportedCount 条格式暂不支持，$integrityCount 条完整性异常。"
                        }
                    } else {
                        "失败：无法写入系统下载目录。"
                    },
                )
            }
        }
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

}
