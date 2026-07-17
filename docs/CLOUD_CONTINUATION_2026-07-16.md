# ANEB 云端续开发 Checkpoint

> 时间：2026-07-17（Asia/Shanghai）
> GitHub：`lucassu2012/ANEB_GPT`
> 发布策略：公开仓只包含源码、设计文档和可复现测试；本机 `evidence/`、设备数据库、日志、密钥、APK、构建缓存不发布。

## 1. 当前产品边界

- ANEB App 只在自建 ANEB 节点上模拟 AI 应用行为，不调用 Kimi、DeepSeek、千问等真实 API。
- 真实 API 探针只作为 Debug/ADB 开发诊断能力：正式产品导航、Key 存储和自动入口均已删除；Release 不含可调用组件。
- AI 真实业务行为模型独立开发，通过冻结的 Profile v2、模型哈希和确定性轨迹与 App 对接。
- Token、AI 实时交互、网络综合性能三类测试独立评分，禁止混入原有 AQS。
- 缺失测量值必须为 `null`/“—”，不得填 0；95% 达标结论必须带有效样本数和置信度。

批准记录见 `docs/DECISION_LOG.md` 的 D-36～D-60；完整指标、目标、评分与结论合同见
`docs/PROFILE_CONTRACT_V2_PROPOSAL_2026-07-16.md`。

## 2. 已完成

- SpeedTest 级视觉框架、动态仪表、基础网络测速、结果结论和新版图标已在 Android 工程内。
- 独立 `tools/aneb-ai-behavior-model` 0.2.0 已实现确定性 PCG32、Token/实时语音假设模型、轨迹生成，以及“授权派生统计 → 主体隔离训练/留出 → 校准 → 留出验证 → validated 发布复算”的 fail-closed 流水线。当前没有真实授权数据，4 个模型仍全部为 hypothesis。
- Token Quick/Standard/Stress Profile 已发布为 `profile.json + runtime_plan.json + SHA-256 manifest`；模型只描述业务行为，不注入 RTT、丢包或速率结果。
- Android 已实现 Profile v2 fail-closed 校验、计划哈希验证、真实上传/SSE Token 流、250ms 动态仪表、Token Simulation Score v1、Room v13 独立结果表及历史详情页。
- E-01 已部署 `POST /api/v1/token-sim`，严格执行上传接收、处理基线和逐 Token 绝对时序；服务端不合成网络损伤。
- AI 实时交互 Quick/Standard/Recovery Profile 已发布为哈希绑定的运行计划；Android 已实现 20ms 双向音频帧、时钟同步、动态准时帧仪表、打断、独立评分、Room v14 结果冻结和历史详情页。
- E-01 已升级到 `aneb-server/0.7.0`，支持最大 128MiB 的受控 Token 上传、Token SSE、实时交互 WebSocket、连接级受控中断、HTTP/3、同端口带序号 UDP 探针，以及逐 run 隔离的容量/应用时延与一次性 2 秒请求中断恢复路径。
- P40 Pro 已完成两次 Quick 端到端验收：3/3 任务和 1080/1080 Token 完成，动态 Token/s、RTT、上行速率、准时率、评分、结论与落库均通过；测试后已退出到华为桌面。
- `scripts/quality_gate.ps1` 已覆盖 Android 单测/Lint/APK、Debug/Release API 入口边界、6 个 Schema/catalog、TTFT 重复性分析器、行为模型 31 项测试和 Go 服务端/网关测试；Room 校验器使用每次构建独立的 SQLite 临时目录，避免 Codex/Claude 并行构建争抢 DLL。本轮 531 项 JVM 测试、0 failures/0 skipped、Lint 0 errors（11 warnings）、5 项重复性测试、Go/行为模型测试和 0.5.7 APK 构建通过；最终 Debug APK SHA-256 为 `D276D7C52F3549E52194B9E90C5C45EBB8969FD441FB09DA9154B7302A6BFF33`。
- App 0.5.5 / Room v19 已使 Token、AI 实时和网络综合新 run 在同一事务冻结类型化行、`aneb-result-v1`、1Hz 无线样本和环境事件；三类结果页可保存或分享经身份/摘要校验的原样 JSONL，Profile 未测指标显式 `missing`，无线时间序列由 R01 证据引用。P40 最终 runs `019f71a6-bbf0-7c71-b8b8-b8338297c6e0` / `019f71a9-191f-7fe3-9995-d4765ed6652f` / `019f71aa-f127-7db3-a4d0-651e57e6a955` 的 Schema 均为零错误、无线样本数一一相符，独立 Python 摘要全部匹配；0.5.4 的 JVM/Python 指数词法缺陷已否决并修复。详见 `docs/P40_APP_0.5.5_RADIO_AND_CANONICAL_VALIDATION_2026-07-18.md`。
- App 0.5.6 已冻结 Token 原始任务的稳定 ID、节点处理时延和端到端 TTFT，并加入严格同条件、任务对齐的重复性分析器。P40 5-run cohort 的 TTFT CV 中位数 1.425%、最大值 4.986%，通过 ≤10% 门限；5 条信封均为 completed/valid，Schema、独立摘要、类型化核心字段和 Room/信封无线样本 119/119 一致。Quick 单 run 仍保持 LOW/INCONCLUSIVE。详见 `docs/P40_APP_0.5.6_TTFT_REPEATABILITY_VALIDATION_2026-07-18.md`。
- App 0.5.7 开始收口 M4 非开发者路径：所有测试类型在无线证据不完整时先说明用途并允许低置信继续；无网络或非法节点在 Service 启动前给出可操作提示。P40 已验证非法地址零服务启动、Network 模式权限说明、完整 Quick、后台通知/回到结果和主动取消。正常 runs `019f7209-e89c-7adc-8238-83f9847acdc5` / `019f7211-0c5d-723d-a84f-49115ddd48da` 各采 18 个无线样本；取消 run `019f7212-0268-7280-9fa6-385b32a8fed1` 保留 cancelled/invalid 信封并抑制分数/等级。详见 `docs/P40_APP_0.5.7_NON_DEVELOPER_FLOW_VALIDATION_2026-07-18.md`。
- App 0.5.1 已删除正式 API Probe UI/Key 存储，改为 Debug-only、`android.permission.DUMP` 保护且无 intent-filter 的一次性 ADB 诊断 Activity；Release 合并清单自动验收不含该组件。普通 autorun 仅 Debug 首次创建消费并立即清除，避免 Activity 重建重复测试。
- AI 实时结果采用 `ensureActive → NonCancellable(Room insert + publish)` 最终提交边界；DB 失败不发布，取消与提交碰撞时 durable result 优先。每会话 loaded RTT 监控在 `finally` 中 `cancelAndJoin`；固定 Wi-Fi/蜂窝绑定失效后在下一会话按同承载重获，失败不降级。手动权限流程由 `SavedStateHandle` 恢复，不持有 Activity/Composable/lambda。
- P40 Pro 0.5.1 真机 run `019f709d-33bf-7dbf-a732-35e28a71b447` 完成 AI 实时 Quick：1/1 会话、3 轮、Room 写入 `ok=true`、98.6/A、`LOW/INCONCLUSIVE`、终态 completed；Debug API 缺参安全拒绝通过且未发出真实 API 请求。测试后已回桌面并强停 Codex，两套 ANEB 均无 PID/服务。首次截图因 ADB 短暂离线为全黑无效帧，不计入 UI 验收；详见 `docs/P40_APP_0.5.1_VALIDATION_2026-07-17.md`。
- P40 Pro 已完成两次 AI 实时交互 Quick 端到端验收：1/1 会话、3/3 轮次、动态准时帧率/播放余量/RTT/双向速率/首帧响应、结果落库及进程重启后的历史回看均通过；两次均保持 `LOW/INCONCLUSIVE`，测试后已主动退出到华为桌面。
- 网络综合 Quick/Standard Profile 已发布；Android 已实现 loaded RTT 并发刷新、100ms 吞吐仪表、1s goodput 窗口、握手分解、带序号 UDP 探针、测后恢复 RTT、独立评分、Room v15 结果冻结和历史详情页。
- P40 Pro 网络综合 Quick 端到端验收通过。最终 0.3.0 手动用户路径 run `019f6b6f-d3d8-7063-b301-90ec8be6fa5e` 在下载/上传阶段正确显示动态 Mbps 指针、loaded RTT、曲线和阶段进度，结果 60.7/C、UDP 50/50 返回并自动跳转结果页；按快测规则保持 `LOW/INCONCLUSIVE`。前序 run 还测得负载 RTT P95 1257.8ms，验证了 loaded RTT 能揭示单看带宽无法发现的排队时延。所有测试后均已退出到华为桌面且无残留前台服务。
- Token Stress 已完成 P40 Pro 手动用户路径验收：启动前显示约 200MiB 流量/发热确认；run `019f6b94-e09a-770a-83a2-6cb5dc6ee38d` 完成 100MiB 上行、100MiB 下行和 300/300 Token，动态上传/下载指针与 loaded RTT 并发刷新，采集 95 个 loaded RTT 样本。结果 50.5/D、`LOW/INCONCLUSIVE`：上行 29.9Mbps 达标，下行 18.2Mbps 未达 25Mbps，loaded RTT P95 1026.7ms、增量 965.6ms 未达目标。测试后已退出到华为桌面、强制停止 Codex 包且两个 ANEB 包均无服务。
- AI 实时指标闭环已从 13 项扩展到 Profile 声明的全部 21 项：新增冻结原始轮次响应、最大连续未返回帧、双向 P05 净荷速率、通话 loaded RTT、非计划重叠、重连尝试与恢复时延；没有触发的恢复和无线协变量保持 `null/0 样本`。P40 Pro 手动 run `019f6bbd-e628-76bb-b88e-26edf9f502b8` 动态显示准时帧、播放余量、loaded RTT、双向 kbps 和首帧响应；数据库含 21 个指标、79 次 loaded RTT 尝试，会话 RTT P95 42.8ms、loaded RTT P95 54.2ms、上/下行 P05 0.257/0.383Mbps，结果 100/A 但按 Quick 规则保持 `LOW/INCONCLUSIVE`。测试后已退出华为桌面并停止 Codex 包，两套 ANEB 均无服务。
- 独立 AI 实时 Recovery Profile `1.2.0` 已完成 P40 Pro 闭环。节点仅对本测试连接执行 2 次受控中断；两次恢复使用同一模型派生刺激（1.2s 语音 + 350ms 模型等待），评分只读取恢复后会话。最终 run `019f6df2-adf6-7e63-bf4d-7db123d8e58a` 观察到 2/2 中断并恢复，恢复时延 2468.4/2814.1ms、P95 2796.8ms，恢复后 467/467 帧准时、6/6 轮成功、10/10 RTT≤100ms，结果 100/A、`HIGH/PASS`。该结论只代表受控服务端中断恢复，不代表蜂窝断网或跨网迁移。测试后已主动退出且两套 ANEB 均无进程/服务。
- AI 实时 Standard 已完成 P40 Pro Wi-Fi 长时闭环。最终 run `019f6e12-b462-7cd9-9ff7-713e8a6c6df7` 完成 10/10 会话、160/160 轮、26518 帧和 4237 次 loaded RTT 尝试，结果 92.1/A、`HIGH/FAIL`：音频准时帧率 99.68%、会话 RTT P95 71.6ms、loaded RTT P95 89.2ms 均达标；`LIVE-B04` 响应超额时延 P95 247.7ms，≤200ms 达标率仅 85.625%（门限 95%），因此硬门限优先判 FAIL。0.4.5 结果结论会逐项点名未达门限及差距，并解释高分/高等级不能覆盖硬门限。承载为 `Huawei-Guest` Wi-Fi，不外推为蜂窝结论；本地证据为 `evidence/device/aneb_realtime_standard_0.4.4.db*`。测试后已强停 Codex 并移除 ADB autorun 最近任务，两套 ANEB 均无进程/服务。
- E-01 部署权已由 Product Owner 裁定归 Codex。2026-07-17 12:03 CST 已在 `aneb-server/0.5.1` 上将 `s3_multimodal` 从 0.2.1 升至 0.3.0：两段 Token 流后分别增加 12MiB `download_burst`，共 10 阶段；节点本机合同/echo/download/UDP smoke 与公网 Profile/12MiB 精确字节验证均通过。0.4.6 App 能执行该 phase，并只在响应体精确排空时向机器日志输出 D1 原始 goodput；旧版 Room/AQS 不加字段、不变分。共享能力说明固定维护在 `docs/TEST_SERVER_CAPABILITIES.md`。
- 网络 Recovery 独立 Profile 已完成 E-01 与 P40 闭环。`aneb-server/0.7.0` 只对触发 run 武装一次 2000ms 应用请求中断，其他 run 与正常路由旁路；App 0.4.8 动态显示恢复计时、服务器确认中断、失败探针与恢复后 RTT，并由 Room v17 冻结。4 次 P40 run 恢复 2084.4–2227.3ms、恢复后成功率均 100%，结论仍按单事件证据保持 `LOW/INCONCLUSIVE`。测试后两套 ANEB 均无 PID/服务。
- 专用 IP 层网关已生产化到 `aneb-gateway/0.2.0`：固定 Debug CA 与逐启动证书链核验、真实转发/回程和全部 main-table 路由预检、严格 IFB/filter/ingress 所有权、可重试失败清理、状态目录防 symlink/mount、严格 Token/TLS key 来源、同步审计，以及带回滚的一键安装/预检/安全卸载均已实现。App 0.5.0 仅对“提交结果未知”执行同 run 对账和一次幂等 POST；明确 409/4xx 拒绝立即失败且绝不重试。一次性 Token 只经随机句柄交接，失败/取消先做有界清理再冻结证据。发布复审关闭 9 项 P1；Go 定向测试重复 20 轮及 E-01 隔离安装安全回归通过。`0.067ms → 97.753ms → 0.051ms` 是固定 CA 加固前的数据面集成证据，不冒充最终发布证据；最终 TLS 正向生命周期缺离线 CA 签发的现场叶证书，P40 网络层真机还缺独占双网口 Linux/AP，二者均为 `BLOCKED_EXTERNAL`。

## 3. 下一阶段（按顺序）

1. `aneb-result-v1`、spec 逻辑目录、三引擎结果信封、JSONL、正式 RadioCollector 和 TTFT 同条件重复性复测已完成。
2. P3 v0.2.0 的授权观测、训练/留出隔离、校准验证和 validated 发布复算流水线已完成；真实授权数据仍是外部输入，到位前保持 hypothesis。
3. M4 的开测前自救切片已通过；下一项继续做不依赖 ADB 的安装/首次启动/导出整链与发布候选可用性。签名密钥仍由 Product Owner 在仓库外保管，缺密钥不能冒充正式 Release。
4. 继续补 AI 实时真实断网、切后台恢复和打断边缘条件真机回归；真实网络事件不得混用受控服务端恢复分数。
5. 完成 Token/网络综合 Standard 长时回归，以及 Stress 的取消、断网、切后台恢复和重复测试分布；单次 Stress 继续保持低置信。
6. 三类结果统一生成完成性、业务行为特征、瓶颈和逐项 95% 网络建议；因果措辞必须服从证据范围。
7. 专用网关 0.2.0 软件、安装安全与 App 0.5.1 异常闭环已完成；先由离线 CA 为 `192.168.77.1` 签发现场叶证书，再在独占硬件上执行最终 TLS/安装生命周期和 P40 网络层容量/丢包/恢复真机闭环。真实 RSRP/SINR 仍必须由屏蔽箱、衰减器或基站模拟器产生，三条证据链不得混分。

## 4. 关键目录

- Android/Go 主工程：仓库根目录
- Profile：`profiles/`
- Android App：`app/probe/`
- 自建节点：`server/`
- 专用网络层弱网网关：`gateway/`
- 独立行为模型：`tools/aneb-ai-behavior-model/`
- UI 设计来源（仅本机，未发布）：`E:\G Project\ANEB\ANEB_UI`
- Claude 参考工程（只读参考，未发布）：`E:\C Project\ANEB`

## 5. 云端恢复命令

```powershell
git clone https://github.com/lucassu2012/ANEB_GPT.git
cd ANEB_GPT
.\scripts\quality_gate.ps1
```

Android 构建依赖和签名流程见 `docs/RELEASE_BUILD.md`。不要把 `local.properties`、keystore、API key、设备数据库或证据日志提交到公开仓。
