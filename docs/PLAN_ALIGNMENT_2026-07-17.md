# ANEB Codex 进展对齐报告——按《ANEB 系统开发计划 v1.0》映射

> 更新日期：2026-07-18。
> 架构基线：产品负责人提供的《ANEB 系统开发计划 v1.0》——“P1 手机端 + P2 服务器端 + P3 标准/业务模型 + Profile 横切机制”。
> 对照输入：Claude 侧 `E:\C Project\ANEB\docs\PLAN_ALIGNMENT_2026-07-17.md`。
> 当前事实基线：App 0.5.10 / Room v19、server 0.7.0、gateway 0.2.0、behavior model 0.2.0、Profile catalog 1.3.1；551 项 JVM 测试、Lint 零 error、8 Schema/catalog、12 项测量/结果测试 + 6 项候选打包测试、31 项行为模型与 Go 门禁通过。三类统一结果、语义结论项、正式 RadioCollector、默认网络 PATH_CHANGE、TTFT 重复性、版本化结果合同、P3 校准发布流水线和 M4 开测前自救/批量导出/AI 实时后台断网切片已闭环；0.5.10 已加固系统下载导出的失败清理边界，并建立云端可下载、可核验的 Debug 候选流水线。0.5.9 P40 蜂窝 Quick 已完成动态 UI 与 strict-v2 JSONL 纵向复验，0.5.10 精确 APK 真机仍待共享手机明确交还。真实授权数据、三级节点、正式签名发布和 M2/M3 外部依赖仍明确列为缺口。

## 0. 先讲偏差与裁定

- ［KNOWN｜HIGH］当前采用 monorepo，但目录数量不等于计划仓库数量：`app/` 同时承载 P1a/P1b，`server/` + `gateway/` 属于 P2，`profiles/` + `tools/aneb-ai-behavior-model/` 承载 P3 与 Profile 横切资产，Profile 3 独立适配器模块尚未建立。
- ［INFERRED｜HIGH］现阶段保持逻辑隔离更合适；出现独立发布节奏或独立负责人后再物理拆仓。
- ［KNOWN｜HIGH］“Profile 即数据”目前只完成了一半：业务参数、质量目标、动态指标和运行计划已数据化；新增一种全新的传输原语、采样语义或评分算法仍然需要代码。可执行的铁律应是：**已有原语内的业务变化只改 Profile；新增原语先升 spec/contract，再改 P1/P2 引擎。**
- ［KNOWN｜HIGH］JSON 与 YAML 都能承载声明式合同。当前产物已统一为可校验 JSON；为了形式改成 YAML没有业务价值，后续重点是单一 schema、兼容区间和消费者一致性。
- ［KNOWN｜HIGH］现有 Profile 有两族合同：服务端根 Profile（4 个相位 Profile）和 App 发布 Profile v2（12 个业务/测量 Profile）。两族尚未收敛成 P1/P2 同时解释的一个端到端合同，这是 M0 的真实治理欠账。
- ［KNOWN｜HIGH］真实第三方 App 适配器尚未进入主 App。依照此前“ANEB App 只做自建节点仿真”的产品边界，未来 Profile 3 应放在独立 `aneb-adapters` 模块，不得把真实 API、账号或脆弱自动化混入 P1b 核心评分链。

## 1. 一页结论

| 计划单元 | Codex 当前状态 | 结论 |
|---|---|---|
| **P1a 前台 UI** | **0.5.10 产品化大部完成；开测、导出和统一结论已闭环** | ［KNOWN｜HIGH］原生 Compose 已覆盖测试发起、三类动态测试、Profile 目录、历史、结果、报告、设置、节点与体验地图外壳；三类结果页均可保存/分享经摘要校验的单条 JSONL，设置页可把全部独立验真的 v1/v2 历史按时间导出，并分别提示格式不支持与完整性异常。0.5.9 直接展示评分器冻结的完成性、Profile 业务行为、门限与瓶颈；0.5.10 对下载目录的创建、写入、完成和失败清理逐阶段验真，禁止半成品或误报成功。视觉按 `ANEB_UI` 原生实现，并已有新 App 图标。真实 API Probe 已从正式 UI/Release 组件移除，只保留受保护 Debug/ADB 诊断组件。 |
| **P1b 测量引擎** | **M1 单节点验收切片闭环** | ［KNOWN｜HIGH］Token 多模态、AI 实时双工、网络综合、合成弱网、恢复与专用网关控制均已成独立引擎，由前台 Service 持有；三类正式结果均先落 Room 再发布，0.5.8 起在同一事务冻结 `aneb-result-v2`、1Hz 无线样本与环境事件，0.5.9 起每条结论冻结稳定 ID、严重级别及指标/证据依据，兼容 v1 保留历史验证。0.5.6 的 5-run TTFT 任务对齐 CV 中位数 1.425%、最大值 4.986%，通过 ≤10% 门限。 |
| **P2 服务器侧** | **当前 App 所需单节点矩阵完成；原计划 P2 部分完成** | ［KNOWN｜HIGH］E-01 运行 `aneb-server/0.7.0`，已覆盖当前 App 使用的 Token、上传、下载、工具循环、WebSocket 实时双工、测速、UDP、结果与逐 run 合成弱网；对照原计划仍缺 RTP/WebRTC 语音回环、通用 100MiB/1GiB 上传档位、全端点统一时戳/序号和同城/区域/中心三级实例。 |
| **P3 标准与业务模型** | **0.2.0 校准流水线闭环；真实画像未校准** | ［KNOWN｜HIGH］除确定性 Token/Stress/AI 实时/Recovery 生成外，现已实现授权统计白名单、HMAC 主体隔离训练/留出、固定误差门限、候选/报告/数据摘要绑定和 validated 发布复算；现有 4 个模型仍明确为 `hypothesis`，没有获准观测数据，不能声称代表 Kimi/DeepSeek/千问真实性能。 |
| **横切 Profile 体系** | **1.3.1 目录与版本化结果/结论/校准合同已冻结，执行合同仍分叉** | ［KNOWN｜HIGH］`spec/catalog.json` 已机器索引 8 个 Schema、2 个 Profile 家族、16 个 Profile、6 个运行包及消费者边界；12 个正式 Profile 已随结论策略升级小版本，Token/AI 实时运行计划重新绑定规范化哈希。1.3.1 只同步 P1 0.5.10 消费者版本，不改 Profile 内容。结果合同分为内部共享 core、兼容 v1 与严格 v2，另含 Token 观测、校准数据集和留出验证合同。P1/P2 尚未共同解释同一份 v2 descriptor。 |
| **里程碑位置** | **M1 单节点验收切片通过、三级节点未完成；M0 治理部分完成；M3 仅 WebSocket 仿真轨完成；M4 开测自救切片通过；M2 未启动** | ［KNOWN｜HIGH］详见 §7；不以单节点重复性冒充跨节点、外场或真实业务画像完成度。 |

## 2. P1a 手机端前台 UI

### 已完成

- ［KNOWN｜HIGH］单 Activity + 原生 Compose 五栏外壳，未用 WebView 承载 `ANEB_UI`。
- ［KNOWN｜HIGH］Token 多模态测试实时呈现仿真 Token/s、RTT、上行速率和准时到达率；AI 实时测试呈现 2 秒准时音频帧率、播放余量、RTT 与双向速率；网络综合测试呈现负载 RTT、上下行速率和低速窗口。
- ［KNOWN｜HIGH］测试结果包含独立分数、等级、置信度、业务行为特征、逐项达标情况和网络建议；缺失值显示不可用，不伪装成 0。
- ［KNOWN｜HIGH］历史记录、结果详情、专业报告、分享卡、Profile 目录、设置、节点页和自适应图标已实现。
- ［KNOWN｜HIGH］UI 只观察 Service/Engine 状态，不在 Composable 内执行测量与评分主逻辑。

### 近期完成

- ［KNOWN｜HIGH］正式 UI 的真实付费 API Probe 死路由、Key 存储页和导出页已删除；Debug 诊断入口是受 `android.permission.DUMP` 保护的一次性组件，普通 App 不能触发。
- ［KNOWN｜HIGH］普通 ADB autorun 已改为 Debug 首次创建即消费，避免屏幕旋转或系统重建后重复发起测试。

### 缺口

- ［KNOWN｜HIGH］体验地图目前是产品外壳，不是 M2 的 6–8 点位热力聚合与三级归因地图。
- ［KNOWN｜HIGH］0.5.7 已完成“开测前节点/网络检查 → 权限用途说明 → 正常测试 → 切后台/通知/回到结果 → 主动取消 → 历史批量导出”真机切片；取消 run 保留 invalid 审计信封但 score/grade 为 null，批量导出逐条隔离旧摘要异常。尚未完成不依赖 ADB 的“下载/系统安装 → 首次启动 → 测试 → 导出/分享”整链正式可用性验收，Release 签名材料也不在仓库中。
- ［INFERRED｜MED］视觉已接近目标方向，但“达到 SpeedTest 级”仍需要真机逐屏动效、弱机帧率、无障碍和长文本回归，不能仅由代码完成度判定。

## 3. P1b 手机端测量引擎

### 计划模块映射

| 计划模块 | Codex 状态 | 实现事实 |
|---|---|---|
| Profile 解释器 | 🟡 两族合同均可读 | ［KNOWN｜HIGH］旧根 Profile 驱动传统 Token 场景；Profile v2 + runtime plan 驱动 Token/AI 实时/网络综合独立引擎；尚未统一为一个解释器。 |
| 传输探针 | ✅ | ［KNOWN｜HIGH］OkHttp 事件计时、NO_PROXY、显式 Wi-Fi/蜂窝绑定、Cronet A/B、WebSocket、UDP ANEB1 和专用网关 HTTPS 控制均已实现。 |
| SSE/Token 解析 | ✅ | ［KNOWN｜HIGH］逐事件 TTFT、TPS、ITL、完整率、卡顿、服务端节奏剥离与批化诊断已实现。 |
| 上传/下载 | ✅ | ［KNOWN｜HIGH］KB/MB/100MiB 档位、服务端确认字节、并发负载 echo 和精确下载排空均有执行路径。 |
| Agent 步进 | ✅ | ［KNOWN｜HIGH］`/toolloop` 串行工具调用往返已接入传统场景。 |
| AI 实时双工 | ✅，可靠性加固中 | ［KNOWN｜HIGH］20ms 双向帧、等待、播放、打断、受控断连与恢复均已实现；本阶段修复取消/持久化/网络重绑 P1。 |
| 无线上下文 | ✅ 采集完成，归因分轨 | ［KNOWN｜HIGH］Token/AI 实时/网络综合三个正式新引擎已按设备公开能力采集 1Hz RSRP、RSRQ/SINR 与环境事件，并同事务冻结；默认网络丢失、切换、恢复、验证与暂停状态使用 run 内匿名路径别名冻结为 PATH_CHANGE。权限拒绝、不可用、未采集保持显式状态，共享信封移除位置。活动承载、默认网络事件与蜂窝无线协变量分开记录，App 不声称能人为设置射频指标或把时间窗共现写成单因因果。 |
| 会话记录 | ✅ 三类版本化统一信封 | ［KNOWN｜HIGH］Room v19 为 Token、AI 实时和网络综合新 run 在同一事务冻结类型化结果与版本化 JSON、身份字段及规范化 SHA-256；0.5.8 起发 `aneb-result-v2`，历史 v1 保持原样可验证；三类结果页的确定性 JSONL 保存/分享已真机验收。 |
| Profile 3 真实 App 适配器宿主 | 不适用（独立模块未启动） | ［KNOWN｜HIGH］后续产品裁定已覆盖原计划中的 P1b 适配器宿主设计；真实第三方 App 适配器若重启，应作为独立易耗模块建设，当前未启动，不计为主 App M1 缺口。 |

### 原计划“铁律 3”符合度

- ［KNOWN｜HIGH］客户端计分侧基本符合：评分主指标使用客户端单调时钟可观测时刻，服务端计划/调度时戳只用于剥离服务端节奏和诊断。
- ［KNOWN｜HIGH］服务端配合侧部分符合：`echo`、`upload`、`toolloop`、`stream`、`realtime` 的诊断信息粒度不一，`download` 没有统一服务端时间序列，UDP 序号由客户端报文携带；因此不能声称“全端点时戳与序号已完成”。

### 已验证能力

- ［KNOWN｜HIGH］0.5.0 基线质量门为 468 个 JVM 测试零失败、Android Lint 零 error、行为模型 14 项测试通过、server/gateway Go 测试通过。
- ［KNOWN｜HIGH］P40 Pro 已完成 Token 100MiB Stress、网络综合正常/合成弱网相邻对照、逐 run 恢复四轮、无线协变量和 UI 动态证据；这些结论只适用于对应设备、节点和合同，不外推为运营商整体表现。
- ［KNOWN｜HIGH］App 0.5.2 的 Token Quick 结果信封已完成独立 P40 纵向验收：run `019f70ed-ed0a-7897-b019-eff5a9a26dda` 在 Room v19 同事务留下类型化结果与 `aneb-result-v1`，Schema 错误 0、摘要匹配；98.4/A 仍按覆盖率 0.15 保持 LOW/INCONCLUSIVE。AI 实时/网络综合不借用这条证据，见 `P40_APP_0.5.2_RESULT_V1_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］App 0.5.3 的 AI 实时 Quick run `019f714a-b54f-787a-a992-2f0254417568` 与网络综合 Quick run `019f714b-ca9d-7aed-a669-533f4ff4a500` 已各自完成 P40 纵向验收：类型化行/信封一一对应、Schema 错误 0、摘要匹配；两类 JSONL 均从系统下载目录读回校验，分享选择器实际拉起。见 `P40_APP_0.5.3_RESULT_V1_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］App 0.5.5 的 Token、AI 实时、网络综合 Quick runs `019f71a6-bbf0-7c71-b8b8-b8338297c6e0` / `019f71a9-191f-7fe3-9995-d4765ed6652f` / `019f71aa-f127-7db3-a4d0-651e57e6a955` 已完成 P40 无线证据和跨语言摘要终验：无线样本分别为 119/26/18，Schema 错误 0，独立 Python 摘要全部匹配。0.5.4 摘要证据因指数词法差异被否决。见 `P40_APP_0.5.5_RADIO_AND_CANONICAL_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］App 0.5.6 的 P40 Token Quick 5-run 同条件 cohort 已完成：稳定任务 ID 对齐后，TTFT CV 中位数 1.425%、最大值 4.986%，通过 ≤10% 门限；5 条信封均 Schema/摘要/类型化核心字段匹配，每条 Room/信封无线样本均为 119。该结论只适用于 P40 + Wi-Fi + E-01 + Quick，单 run 继续为 LOW/INCONCLUSIVE。见 `P40_APP_0.5.6_TTFT_REPEATABILITY_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］App 0.5.7 已在 P40 验证无效节点启动前拦截、所有正式模式的无线权限用途说明、完整 Network Quick、切后台通知/回到结果和主动取消。正常 runs `019f7209-e89c-7adc-8238-83f9847acdc5` / `019f7211-0c5d-723d-a84f-49115ddd48da` 均完成落库并各采 18 个无线样本；取消 run `019f7212-0268-7280-9fa6-385b32a8fed1` 保留 cancelled/invalid 信封、分数与等级为 null。见 `P40_APP_0.5.7_NON_DEVELOPER_FLOW_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］同一 0.5.7 P40 基线的历史批量导出从 22 条信封中独立验真 18 条，并隔离 4 条 0.5.3/0.5.4 digest mismatch；输出 18 行/18 唯一 run、时间有序、canonical digest 18/18 匹配，拒绝 id 混入 0 次。旧记录没有被重算或改写。
- ［KNOWN｜HIGH］AI 实时后台正常 Quick run `019f7238-d040-71a0-b874-6c211f051e0d` 完成 3/3 轮；后台真实 Wi-Fi 中断 run `019f7240-bf42-7a48-b23b-3235286da018` 观察到 1/1 会话中断、2/3 轮失败和 51.8% 帧返回率，分数/等级为 null。结果首屏先报告业务任务受损，再给出 ≤1% 会话中断率与 ≥99% 帧返回率目标，同时禁止缺少 PATH_CHANGE 证据时单因归因。两条信封摘要均匹配。
- ［KNOWN｜HIGH］最终 PATH_CHANGE run `019f72f5-557c-71b0-a7d9-b462055f0545` 在真实 Wi-Fi 关闭窗口冻结 `default_network_lost path=path-1 transport=wifi`、5 个无线样本、1/1 会话中断与 3/3 轮失败；结果页以“默认 Wi-Fi 网络丢失”展示同窗关联，并明确不能单独证明因果。该信封当前 Schema 错误 0、摘要匹配。最终批量 JSONL 导出 27/31 条，27/27 摘要匹配且 4 个旧摘要异常 id 未混入。
- ［KNOWN｜HIGH］App 0.5.8 已落实结果版本边界：27 条不可变历史在兼容 v1 下 27/27 结构通过；三类新生产者改发严格 v2。P40 Token Quick run `019f730f-a0d5-7417-9e01-0866bacdfc57` 为 v2、3/3 任务对齐字段完整、120 条无线样本、Schema 零错误且独立摘要匹配，97.0/A 仍保持 LOW/INCONCLUSIVE。见 `P40_APP_0.5.8_RESULT_VERSIONING_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］App 0.5.9 把三类字符串结论升级为评分器冻结的语义项：稳定 `conclusion_id`、准确 `info/recommendation/warning/failure`、原始文本和指标/证据 basis；导出器不再按条目位置猜级别，结果页不再另写行为特征。正常、必需指标缺失、无效证据及默认网络变化共现均有回归；计划受控中断单独按恢复任务完成性评价，Token 缺指标时也不再吞掉已观察到的任务完成事实。12 个 Profile 与对应结论策略已升小版本，6 个 Token/AI 实时运行包重新哈希绑定。545 项 JVM 测试、Lint 零 error、Schema/catalog、测量分析、行为模型及 Go 门禁通过。P40 蜂窝 Quick run `019f7377-9a61-7db5-a8c4-1ac57de1a486` 在紧邻 0.5.9 候选上完成 3/3 轮、99.8/A、LOW/INCONCLUSIVE；动态 UI、12 条冻结结论和系统下载目录 strict-v2 JSONL 均复验通过。当前精确 APK 只在其后调整失败门限 basis 与 Token 缺指标结论，已自动化验证但尚待共享手机释放后做同二进制安装确认。酒店 Wi-Fi 的前序失败发生在门户未认证时，不作为网络质量 A/B。见 `APP_0.5.9_SEMANTIC_CONCLUSION_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］App 0.5.10 将所有系统下载导出收紧为“创建 pending → 写入全部 UTF-8 字节 → 完成标记成功”后才返回成功；打开、写入或完成失败均尝试删除半成品，清理失败则保留 URI 并显式报告。6 个故障注入测试覆盖成功、创建失败、打开失败、磁盘写入失败、完成失败和清理失败；全量现为 90 suites / 551 tests，Lint 0 error / 11 notices，其他门禁均通过。该变化不改结果正文、摘要、Schema、Profile 或评分。精确 APK 真机导出验收待 Experience Lab 明确释放共享 P40。见 `APP_0.5.10_EXPORT_RELIABILITY_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］云端 Debug 交付流水线已完成真实闭环：Codex 分支触发 GitHub run `29633753923`，合同、Go 服务端、网关控制面/竞争/构建、行为模型和 Android 候选五个 job 成功；工件 `8426436270` 精确包含 APK、机器清单、三份 SHA-256 和中文安装说明。云端 APK 身份为 `com.aneb.probe.codex` / `0.5.10-codex` / code 42，SHA-256 `2C05E347E66CC2049292452745DD68B6EDF2CECE2CB8501D509C4B9A6653DED1`；attestation `35942948` 已离线验证。外部固定 CA 的隔离 TLS/netem 命名空间步骤因无叶证书 secrets 明确未执行，不折算为 PASS。见 `CLOUD_DEBUG_CANDIDATE_DELIVERY_2026-07-18.md`。
- ［KNOWN｜HIGH］软件弱网可控制带宽、应用时延、抖动和短时不可用；真实 RSRP/SINR 仍需屏蔽箱、衰减器或基站模拟器。

## 4. P2 服务器侧

### 已完成

- ［KNOWN｜HIGH］P2 当前实现与部署技术栈是 Go。
- ［INFERRED｜HIGH］为了匹配旧计划而改写为 FastAPI不会增加测量能力，当前不应重写。
- ［KNOWN｜HIGH］E-01 当前运行 `aneb-server/0.7.0`，端点包括 `/profiles`、`/echo`、`/stream`、`/token-sim`、`/realtime-sim`、`/upload`、`/download`、`/toolloop`、`/results`、`/serverinfo`、`/impairments` 与 UDP/8443。
- ［KNOWN｜HIGH］`s3_multimodal@0.3.0` 已包含两段 12MiB `download_burst`；端点响应带版本、序号和服务端诊断时间信息。
- ［KNOWN｜HIGH］E-01 有两个逐 run 隔离的用户态合成弱网合同；共享主机未使用全局 netem。
- ［KNOWN｜HIGH］独立 `aneb-gateway/0.2.0` 已实现 IP 层双向整形、固定 Debug CA、严格双网口/路由预检、资源所有权和失败清理；它没有部署到 E-01。

### 缺口与阻塞

- ［KNOWN｜HIGH］只有 E-01 单节点，没有同城/区域/中心三级镜像，因此 M2 的地域/层级差分不能开始。
- ［KNOWN｜HIGH］专用网关最终固定 CA 正向生命周期缺离线 CA 签发的现场叶证书；P40 网络层真机验收缺独占双网口 Linux/AP，均为 `BLOCKED_EXTERNAL`。
- ［KNOWN｜HIGH］当前 `/upload` 单请求上限为 64MiB，而 Token Stress 通过 `/token-sim` 支持 100MiB 业务负载；如要把通用 `/upload` 扩到 100MiB/1GiB，必须先确定外场流量与服务器资源预算。

## 5. P3 ANEB 标准与业务模型

### 已完成

- ［KNOWN｜HIGH］`tools/aneb-ai-behavior-model/` 是与 App 分离的可运行工程，当前版本 `v0.2.0`。
- ［KNOWN｜HIGH］已提供 Profile/trace/Token 观测/授权数据集/留出验证共 5 个 P3 Schema、PCG32 确定性生成与规范化语义哈希；31 项测试覆盖完整 CLI 链和授权、内容、泄漏、摘要、样本、FAIL 报告与发布证据反例。结构验证和流水线可用不等于真实业务校准完成。
- ［KNOWN｜HIGH］已发布 Token Standard/Quick/Stress 与 AI 实时 Standard/Quick/Recovery 运行计划；网络综合另有 Standard/Quick/合成弱网/恢复/专用网关系列 Profile。
- ［KNOWN｜HIGH］Profile v2 逐项声明业务类型、行为特征、全量业务/网络/无线指标、质量目标、95% 达标口径、动态主/辅指标、必需指标、权重、门控、结论策略、claim scope 与模型来源。
- ［KNOWN｜HIGH］评分策略彼此独立：Token、AI 实时和网络综合不混分；缺必需指标时 score 为 null，invalid 保留原始证据但抑制评分。

### 最大缺口

- ［KNOWN｜HIGH］四个行为模型均为 `hypothesis`；没有授权观测数据集、留出集或跨版本校准报告。当前只能称“产品假设驱动的可重复仿真”，不能称“真实 Kimi/DeepSeek/千问模型”。
- ［KNOWN｜HIGH］真实数据到位后的技术路径已闭环：calibrated 只能使用获授权 training 拟合，validated 必须在 subject-disjoint holdout 达标，并在每次构建/runtime 发布前重新复算报告。当前缺口是合法数据输入，而不是再写一条无约束拟合命令。
- ［KNOWN｜HIGH］结果合同现由只含兼容公共约束的内部 core、兼容 `aneb-result-v1` 和严格 `aneb-result-v2` 组成，并通过 Draft 2020-12 正/反例与跨版本冻结向量；27 条不可变历史全部按 v1 通过。未来任何新增 required、范围收窄或语义变化只能进入新 wrapper，不得借修改 core 追溯收紧旧版本。
- ［KNOWN｜HIGH］跨 Profile 共用的指标定义/质量目标仍嵌在各 Profile 中，尚未抽成独立版本包。
- ［KNOWN｜HIGH］Profile 3 的业务画像采集、包时序拟合、PoP/IP 清单和真实 App 适配器均未开始。
- ［INFERRED｜HIGH］P3 是下一轮最能提升商业可信度的部分；继续增加假设 Profile 的边际价值低于获得首批合法、可追溯的业务画像数据。

### 原计划目标业务清单 v1 状态

| 目标业务 | 当前状态 |
|---|---|
| Kimi / DeepSeek / 千问 Token/API 原型 | ［KNOWN｜HIGH］只有未校准的通用 Token archetype，没有厂商画像校准，不得声称复现任一厂商真实业务。 |
| Claude Code / Coding Agent | ［KNOWN｜HIGH］只有通用 `s2_coding_agent` 根 Profile 与工具循环测量原语，没有 Claude Code 厂商画像校准。 |
| 豆包 / 元宝 / 千问 / DeepSeek / Kimi 真实 App 轨 | ［KNOWN｜HIGH］未启动；主 App 当前只做自建节点仿真。 |
| GPT-Live-like 实时语音 | ［KNOWN｜HIGH］WebSocket 双工仿真、打断与受控恢复已完成。 |
| 豆包真实语音 / RTP / WebRTC | ［KNOWN｜HIGH］未启动，没有 RTP/WebRTC 回环与逐帧对照验收。 |

## 6. 横切机制：Profile 体系

### 当前资产

- ［KNOWN｜HIGH］服务端根 Profile：`s1_chat`、`s2_coding_agent`、`s3_multimodal`、`basic_network`。
- ［KNOWN｜HIGH］App 发布 Profile 共 12 个：Token 3 个、AI 实时 3 个、网络综合 6 个。
- ［KNOWN｜HIGH］Token 与 AI 实时共 6 个发布 Profile 使用 `profile + runtime_plan + manifest`，App 启动前校验合同、模型和规范化哈希；网络综合 6 个 Profile 采用内嵌 phase 合同与客户端 capability 校验，目前没有独立 runtime manifest。两条路径都不接受节点下发任意脚本。
- ［KNOWN｜HIGH］新增既有模式的 Quick/Standard/Stress/Recovery 变体主要通过数据完成；新增全新 phase 或测量语义仍需引擎适配并升合同版本。

### 下一步收敛顺序

1. ［KNOWN｜HIGH］`spec/` 逻辑根、机器目录校验与版本化结果合同（core + compatible v1 + strict v2）已完成，不移动现有 P1/P2/P3 资产。
2. ［KNOWN｜HIGH］AI 实时、网络综合 Room v19 信封与用户可见 JSONL 已完成；导出只封装冻结 JSON，不另造语义。
3. ［KNOWN｜HIGH］三个正式新引擎的 RadioCollector 快照语义、权限拒绝/设备不可用显式状态及共享位置移除已完成并经 P40 验收。
4. ［FRAME｜LOW］把两个 Profile 家族的公共头、端点能力和兼容区间统一；P2 不必执行 UI/评分字段，但必须验证自己声明支持的 phase 与 server contract。
5. ［FRAME｜LOW］将重复的指标/目标/评分策略提取为版本化引用，同时保留发布包展开后的自包含快照，保证离线可审计。

## 7. 里程碑映射

| 里程碑 | Codex 现状 | 验收判断 |
|---|---|---|
| **M0 契约冻结** | `spec/catalog.json` 索引 8 Schema/2 家族/16 Profile/6 运行包；兼容 v1/严格 v2 结果、授权观测、校准数据集和留出报告合同已有正反例校验；执行 Profile 合同仍分叉 | ［KNOWN｜HIGH］**治理骨架增强、跨端执行合同未闭环**；不能把目录治理等同于 P1/P2 已共用解释器。 |
| **M1 核心闭环** | Kotlin 引擎 + Go 单节点 + 三类仿真轨、Room v19 统一信封、UI JSONL 与正式三引擎 radio_ctx 已跑通；P40 同点位 5-run TTFT CV 中位数 1.425%、最大值 4.986% | ［KNOWN｜HIGH］**原计划单节点验收切片通过；内容项中的同城/区域/中心三级部署未完成，因此 M1 整体仍为部分完成**。 |
| **M2 外场 MVP** | 无 6–8 点位 × 忙闲 × 双运营商活动，无三级实例与正式热力报告 | ［KNOWN｜HIGH］**未启动**。 |
| **M3 真实业务与语音** | AI 实时 WS 仿真/打断/恢复已完成；真实画像、Profile 3 适配器、RTP/WebRTC 回环与逐帧打点验收未做 | ［KNOWN｜HIGH］**仅 WebSocket 仿真轨完成**；其余验收没有客观完成比例。 |
| **M4 产品化** | Compose UI、动态测试、历史/结果/报告/分享已完成大部；正式发布边界与生命周期加固中 | ［KNOWN｜HIGH］**部分超前**；非开发者验收、签名 Release 与公开发布仍未完成。 |

## 8. 当前自主执行顺序

1. ［KNOWN｜HIGH］P1 发布边界、AI 实时生命周期修复、`spec/` 目录与统一结果 Schema 已完成并有自动校验。
2. ［KNOWN｜HIGH］AI 实时/网络综合 Room v19 结果信封、用户可见 JSONL 与 P40 真机回归已完成。
3. ［KNOWN｜HIGH］三个正式新引擎的 RadioCollector、活动承载/蜂窝协变量分轨和 TTFT 同条件重复性复测已经完成并有 P40 可复算证据。
4. ［KNOWN｜HIGH］P3“授权观测 JSONL → 校准模型 → 留出验证 → validated 发布”流水线已实现；没有真实授权数据时仍不生成 calibrated/validated 正式资产。
5. ［KNOWN｜HIGH］M4 下载导出失败清理和云端 Debug 候选打包已在 0.5.10 完成本地故障注入、全量门禁、真实 GitHub Actions 工件和来源证明核验；下一步只在共享 P40 明确交还后，安装云端 SHA `2C05E347E66CC2049292452745DD68B6EDF2CECE2CB8501D509C4B9A6653DED1`，验证首次启动、单条/混合批量导出并主动退出。签名密钥仍服从仓库外 Product Owner 所有权边界。
6. ［KNOWN｜HIGH］M2 三级部署、专用网关最终 TLS/P40 硬件验收和真实射频弱网继续保持 `BLOCKED_EXTERNAL`，不拿单节点或软件损伤冒充。

## 9. 原计划待拍板项回写

- ［KNOWN｜HIGH］P1 技术栈已由实现事实关闭：Android 原生 Kotlin + Compose。
- ［KNOWN｜HIGH］P2 技术栈已由实现事实关闭：Go。
- ［KNOWN｜HIGH］云区域、同城/区域/中心三级部署和首批试点城市尚未落实。
- ［KNOWN｜HIGH］Profile 3 首批 App 已因后续“主 App 只做自建节点仿真”的裁定延期并转为独立模块议题，不再是 P1b 当前决策。

## 10. 与 Claude 侧对齐原则

- ［KNOWN｜HIGH］E-01 部署仍由 Codex 单点执行，权威合同为 `docs/TEST_SERVER_CAPABILITIES.md`；Claude 提需求/补丁，不直接覆盖共享服务器。
- ［KNOWN｜HIGH］两侧都按 P1a/P1b/P2/P3/Profile/M0–M4 更新进展，版本、测试数字、真机 run 和部署状态必须能回指仓库或原始证据。
- ［KNOWN｜HIGH］“已实现”“已部署”“已真机验证”“已达到目标”是四个不同状态，后续报告不得混写。
- ［INFERRED｜HIGH］两套客户端的价值是互相复核测量语义与结果，而不是靠功能数量竞赛；发现分歧时先对齐 spec，再比较实现。
