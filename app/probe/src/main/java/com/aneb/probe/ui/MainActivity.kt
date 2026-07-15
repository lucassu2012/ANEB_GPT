package com.aneb.probe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.aneb.probe.engine.TestEngine
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
 * 测量语义、adb 自动化、logcat 合同全部不动——run 编排（engine.run 收集、autorun、
 * 各 KEY 日志）与阶段 1 逐字一致，仅展示层从"日志控制台"重构为设计稿界面。
 *
 * adb 自动化（不改测量语义）：
 *   am start ... --es server <url> --ez autorun true [--es mode quick|forensic|continuity|ab]
 *   [--es transport auto|wifi|cellular] [--es inject truncate:50]
 * C07：手动 run 结束自动跳结果页；autorun 不跳（保持 logcat 自动化验收流程不变）。
 */
class MainActivity : ComponentActivity() {

    private lateinit var engine: TestEngine
    private lateinit var continuityRunner: ContinuityRunner
    private lateinit var abRunner: AbRunner
    private lateinit var radioCollector: RadioCollector
    private lateinit var db: AnebDatabase

    private var intentServer: String? = null
    private var intentAutorun: Boolean = false
    private var intentMode: TestEngine.Mode = TestEngine.Mode.QUICK
    private var intentTransport: TestEngine.TransportMode = TestEngine.TransportMode.AUTO
    private var intentInject: String? = null
    private var intentDriveTest: Boolean = false

    private var intentContinuity: Boolean = false
    private var intentCTokens: Int = ContinuityRunner.DEFAULT_TOKENS
    private var intentC3IdleS: List<Int> = ContinuityRunner.DEFAULT_C3_IDLE_S

    private var intentAb: Boolean = false
    private var intentAbPairs: Int = AbRunner.DEFAULT_PAIRS
    private var intentAbNetlog: Boolean = false

    private val radioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

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
        data object Report : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = TestEngine(applicationContext)
        continuityRunner = ContinuityRunner(applicationContext)
        abRunner = AbRunner(applicationContext)
        radioCollector = RadioCollector(this)
        db = AnebDatabase.get(applicationContext)
        intentServer = intent?.getStringExtra("server")
        intentAutorun = intent?.getBooleanExtra("autorun", false) == true
        intentMode = when (intent?.getStringExtra("mode")?.lowercase()) {
            "forensic" -> TestEngine.Mode.FORENSIC
            else -> TestEngine.Mode.QUICK
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
        intentTransport = when (intent?.getStringExtra("transport")?.lowercase()) {
            "wifi" -> TestEngine.TransportMode.WIFI
            "cellular" -> TestEngine.TransportMode.CELLULAR
            else -> TestEngine.TransportMode.AUTO
        }
        intentInject = if (BuildConfig.DEBUG) intent?.getStringExtra("inject") else null
        intentDriveTest = intent?.getBooleanExtra("drive_test", false) == true
        maybeApiProbeAutorun()

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
                        mutableStateOf(intentServer ?: "https://120-79-148-0.sslip.io:8443")
                    }
                    var mode by rememberSaveable { mutableStateOf(intentMode) }
                    var transport by rememberSaveable { mutableStateOf(intentTransport) }
                    var driveTest by rememberSaveable { mutableStateOf(intentDriveTest) }
                    var running by remember { mutableStateOf(false) }
                    val logs = remember { mutableStateListOf<String>() }

                    fun addLog(line: String) {
                        android.util.Log.i("AnebProbe", line)
                        logs.add(line)
                    }

                    // ---- run 编排（与阶段 1 逐字一致；仅把导航接到新界面）----
                    fun startRun(fromAutorun: Boolean) {
                        if (running) return
                        running = true
                        if (!fromAutorun) screen = Screen.Testing
                        addLog(">>> RUN mode=${mode.name.lowercase()} transport=${transport.name.lowercase()} -> $serverUrl")
                        lifecycleScope.launch {
                            var runId: String? = null
                            var navigated = false
                            fun jumpToResult() {
                                val id = runId
                                if (!fromAutorun && !navigated && id != null) {
                                    navigated = true
                                    screen = Screen.Result(id, fromHistory = false)
                                }
                            }
                            try {
                                engine.run(
                                    TestEngine.RunConfig(
                                        serverBase = serverUrl,
                                        mode = mode,
                                        transport = transport,
                                        inject = intentInject,
                                        driveTest = driveTest,
                                    )
                                ).collect { line ->
                                    addLog(line)
                                    if (runId == null && line.startsWith("RUN_START ")) {
                                        runId = Regex("run_id=(\\S+)").find(line)?.groupValues?.get(1)
                                    }
                                    if (line.startsWith("RUN_END ")) jumpToResult()
                                }
                                jumpToResult()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                addLog("RUN_FAILED error=$e")
                                if (!fromAutorun) screen = Screen.Home
                            } finally {
                                running = false
                            }
                        }
                    }

                    fun startContinuityRun() {
                        if (running) return
                        running = true
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
                                running = false
                            }
                        }
                    }

                    fun startAbRun() {
                        if (running) return
                        running = true
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
                                running = false
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
                                        onStart = { startRun(fromAutorun = false) },
                                        onOpenSettings = { tab = MainTab.Settings },
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
                                        onServerUrlChange = { serverUrl = it },
                                        mode = mode,
                                        onModeChange = { mode = it },
                                        transport = transport,
                                        onTransportChange = { transport = it },
                                        driveTest = driveTest,
                                        onDriveTestChange = { turningOn ->
                                            driveTest = turningOn
                                            if (turningOn &&
                                                ContextCompat.checkSelfPermission(
                                                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION,
                                                ) != PackageManager.PERMISSION_GRANTED
                                            ) {
                                                radioPermissionLauncher.launch(
                                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                                )
                                            }
                                            android.util.Log.i("AnebProbe", "DRIVE_TEST_TOGGLE enabled=$turningOn")
                                        },
                                        injectActive = intentInject,
                                        onOpenApiProbe = { screen = Screen.ApiProbe },
                                        // 可达性看板已降为设置二级入口（下钻屏）。
                                        onOpenReachBoard = { screen = Screen.ReachBoard },
                                        onBack = { tab = MainTab.Test },
                                    )
                                }
                                // ---- 下钻屏（隐底栏；各自返回键回当前 tab 根）----
                                is Screen.Testing -> {
                                    // TestEngine.telemetry 只读观测通道 → collectAsStateWithLifecycle（后台自动停收，
                                    // 绝不回压测量热路径；StateFlow 有初值，无闪烁）。
                                    val telemetry by engine.telemetry.collectAsStateWithLifecycle()
                                    TestingScreen(logs = logs, telemetry = telemetry)
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
                            }
                        }
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
        onStart: () -> Unit,
        onOpenSettings: () -> Unit,
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
            onStart = onStart,
            onOpenSettings = onOpenSettings,
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
