# ANEB Probe 0.2.0 Codex 交接（2026-07-16）

## 1. 交付结论

［KNOWN｜HIGH］Codex 路线的 A→B→C 产品代码、E-01 服务端、Debug APK、自动化质量门和 P40 Pro 真机回归均已完成。应用已经不是演示稿：两种测试模式使用真实请求与真实到达事件驱动动态界面，结果、历史、GPS 轨迹和 Profile 合同均来自实际数据源。

［KNOWN｜HIGH］唯一尚未生成的发布物是 **Product Owner 签名的 Release APK/AAB**。仓库已具备 fail-closed 签名门禁，但不会生成、保存或代管发布私钥；签名资产的创建与备份属于 Product Owner 边界。

## 2. Version 0.2.0 已交付范围

### 测试与动态视觉

- **网络基本性能**：应用层下载、上传、Ping、抖动、请求失败率与负载后 Ping。
  - 1 秒实时吞吐窗口；UI 状态 250 ms 投影；指针用 100 ms 动画插值连续刷新。
  - 270° 仪表、动态指针、实时曲线、阶段轨道和当前/阶段平均/进度。
  - 独立结果页，不与 Token AQS 混分。
- **Token 体验**：保留 S1/S2/S3 与 AQS 体系。
  - 实时关键指标为最近 1 秒的 **AI 流式到达速率（事件/秒）**，250 ms 刷新。
  - 该值是完整 SSE event 的到达代理量，不伪称计费 token。
- **结论**：每次测试均输出可行动结论；基本测速覆盖 4K 视频、视频会议和大文件上传，Token 体验覆盖任务完成、重试/Token 成本、卡顿和长对话等业务影响。

### 产品界面

- 严格以 `E:\G Project\ANEB\ANEB_UI` 的 `2026.07.15-2` 交付为视觉基线。
- 五栏导航：测试、探针、结果、地图、设置。
- SpeedTest 风格的开始环、动态测速仪表、抽屉式节点卡、深海军蓝/青色视觉。
- 新自适应矢量 App 图标，支持 Android 13 monochrome 图标。
- 设置页、节点页、API 探针、Profile Registry、历史、结果、报告、地图和分享卡均已重构。

### 数据、后台与扩展性

- Room v12 统一保存 Token 与 Basic 历史；Basic 结果可以从历史重新打开。
- GPS 坐标只存本机；地图只画真实轨迹和真实测试点，不再画演示道路或虚构热区。
- 主测试、Continuity 与 Protocol A/B 均由 `dataSync` 前台 Service 持有，Activity 重建或切后台不再取消测试。
- 4 个版本化 Profile：`basic_network`、`s1_chat`、`s2_coding_agent`、`s3_multimodal`。
- Profile Registry 展示业务说明、实时指标、窗口、刷新频率、输出指标、结论策略、阶段和当前引擎兼容性。
- 未知 mode/phase 不会静默宣称可执行；不允许节点下发任意脚本。

### 安全与发布

- Release 全局禁止明文流量；E-01 bare-IP 仅信任项目自有 IP-SAN CA 公钥。
- Debug 明文例外仅限本地白名单。
- API key 只允许加密保存；Keystore 不可用时拒绝保存并清理旧明文 fallback。
- Release 签名缺失时 `assembleRelease`、`bundleRelease`、`installRelease` fail-closed。
- AGP 8.13.0、Gradle 8.13，wrapper SHA-256 钉死；`compileSdk/targetSdk=35`。

## 3. 最终真机证据

设备：HUAWEI P40 Pro / ELS-AN00；Codex 包名 `com.aneb.probe.codex`，与 Claude/正式包 `com.aneb.probe` 隔离。

| 验收项 | 结果 | 证据 |
|---|---|---|
| Profile 节点合同 | E-01 通过 bare-IP 自动旁路读取；“节点已核验”；4 个 Profile | `evidence/ui/aneb_codex_c_profiles_server.png`；`PROFILE_CATALOG_ROUTE route=bare_ip reason=sni_rst_ip_ok` |
| Basic 动态过程 | 上传阶段实时 59.7 Mbps；Ping 26.5 ms；抖动 3.6 ms；失败率 0%；指针与曲线刷新 | `evidence/ui/aneb_codex_c_basic_running.png` |
| Basic 最终结果 | 下载 223.4 Mbps；上传 64.8 Mbps；Ping 26.5 ms；抖动 3.6 ms；失败率 0%；场景结论完整 | `evidence/ui/aneb_codex_c_basic_result.png` |
| Room v12 持久化 | 历史首项显示本轮 Basic；与 Token 记录混排 | `evidence/ui/aneb_codex_c_history.png` |
| 历史回看 | 点击 Basic 历史记录重新打开同一结果与结论 | `evidence/ui/aneb_codex_c_basic_reopened.png` |
| 真实地图 | 本机 211 个 GPS 样本、4 次测试；仅绘制真实轨迹 | `evidence/ui/aneb_codex_c_map.png` |
| Token 动态过程 | 真机观察到约 40 event/s 的突发与约 8 event/s 的稳态变化 | `evidence/ui/aneb_codex_token_burst_round2.png`、`aneb_codex_token_running_round2.png` |
| Token 结果与结论 | AQS 91.1，低置信度边界和业务结论均有显示 | `evidence/ui/aneb_codex_token_result_round2.png`、`aneb_codex_token_conclusions_round2.png` |
| 设备释放 | 测试结束后前台为 `com.huawei.android.launcher`，无 Codex 前台测试服务 | ADB 最终状态检查 |

［KNOWN｜HIGH］上述数值是该时刻“P40 Pro 到 E-01 探针节点的应用层路径”测量结果，不代表运营商全网、无线空口峰值或行业 SLA。

## 4. 最终质量门

- `scripts/quality_gate.ps1`：PASS。
- JVM：53 suites，387 tests，0 failures，0 errors，0 skipped。
- Android Lint：0 errors，9 warnings。
  - `OldTargetApi=1`：API 36 留待 Android 16 专项行为回归后升级（D-34）。
  - `AndroidGradlePluginVersion=1`、`GradleDependency=1`、`NewerVersionAvailable=6`：精确版本升级留到 0.2.1 独立依赖批次。
- Go server tests：PASS。
- Debug 与 Release Kotlin 编译：PASS。
- Release 无签名凭据时门禁拒绝构建：符合预期。

最终 Debug APK：

- 路径：`app/probe/build/outputs/apk/debug/probe-debug.apk`
- 包名：`com.aneb.probe.codex`
- 大小：58,414,380 bytes
- SHA-256：`43B5F31F381B8F6663CDD5B731F430B812C5B0A95DBDEABF610A6862001064E4`

## 5. 构建、安装与复验

```powershell
cd "E:\G Project\ANEB\DevSpace\aneb-probe-codex-v0.2.0"
powershell -ExecutionPolicy Bypass -File scripts/quality_gate.ps1
adb -s 8MY0221126002537 install -r app/probe/build/outputs/apk/debug/probe-debug.apk
```

Release 构建前先按 `docs/RELEASE_BUILD.md` 提供四项 `ANEB_RELEASE_*` 凭据，然后：

```powershell
cd app
.\gradlew.bat :probe:verifyReleaseSigning :probe:bundleRelease
```

## 6. 维护入口

- 新增/升级测试合同：`profiles/*.json`，修改现有发布 Profile 时必须升版本。
- Profile 数据模型与解析：`app/probe/src/main/java/com/aneb/probe/engine/ProfileModels.kt`。
- 引擎能力审计：`ProfileCapability.kt`。
- Basic 测量：`NetworkSpeedEngine.kt`；Basic 结论：`BasicSpeedConclusions.kt`。
- Token 实时遥测：`LiveTelemetry.kt`；Token 结论：`OutcomeConclusions.kt`。
- UI 视觉组件：`ui/components/AnebVisualKit.kt`、`SpeedTestComponents.kt`。
- 数据库：`data/AnebDatabase.kt`、`Entities.kt`、`Daos.kt`；每次 schema 变化必须新增 migration 并导出 schema。
- E-01 部署：`scripts/deploy_server.ps1`；部署后必须验证 `/api/v1/profiles`、`/api/v1/echo` 和 `/api/v1/download`。
- 关键产品与测量决定：`docs/DECISION_LOG.md`。

## 7. 后续版本而非 0.2.0 缺陷

- Android 16 / targetSdk 36 专项回归后再升级 target。
- E-06 公共域名、公共 CA 与 UDP 8443，用于公网 Cronet QUIC A/B；自签 CA 不能替代 Cronet known-root 要求。
- E-03 真实 LLM API key，用于供应商 API 对照；主线仿真测试不依赖它。
- E-04 海外独立节点，用于跨境真实路径对照。
- VpnService 封闭 App 流量观测仍按 D-24 暂不实施，未经 Product Owner 新裁定不进入开发。

