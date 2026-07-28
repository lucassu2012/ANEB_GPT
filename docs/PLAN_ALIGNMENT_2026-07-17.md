# ANEB Codex 进展对齐报告——按《ANEB 系统开发计划 v1.0》映射

> 更新日期：2026-07-28。
> 架构基线：产品负责人提供的《ANEB 系统开发计划 v1.0》——“P1 手机端 + P2 服务器端 + P3 标准/业务模型 + Profile 横切机制”。
> 对照输入：Claude 侧 `E:\C Project\ANEB\docs\PLAN_ALIGNMENT_2026-07-17.md`。
> 当前事实基线：App 0.5.14-codex / code 46 / Room v19；E-01 运行 `aneb-server/0.8.2`，binary SHA-256=`62ff966bf396abe836c6179053ee549110e41e16af569cdeadc97535bc64c96e`；behavior model 0.3.2；Profile catalog 1.8.0。M0-EC1 Token Quick、M0-EC2 AI 实时 Quick 与 M0-EC3 Network Quick 均已完成正负 READY，D-109/S2 已关闭。S3/M1 已用 source `5b968bfffdb81451c80b2fd86064c06b35da925b` / CI `30323979935` / APK SHA-256=`09db3b4a3f137cd98c2346c55f5dadf3f9e797367327e0c5e7b986de525ce8b4` 完成三族各 5 次工程业务采样；15/15 无线权限拒绝使 strict cohort 未形成，正式重复性矩阵仍未完成。真实授权数据、三级节点、正式签名发布和 M2/M3 外部依赖继续列为缺口。
> M0-EC1 边界：Token Quick 1.2.1 已建立 P1 0.5.12 / P2 0.8.0 / P3 0.3.1 精确执行合同、真实 1MiB 返回附件、同-run服务端审计以及 D-82/D-86/D-87 正负 READY。正向 run `019f95f9-a317-7766-9725-243b9660b9f1` 和负向 run `019f99c7-5b40-75ba-ad58-b5b522e9abf9` 已完成，窄切片可以结案；正负使用不同 CI APK，因此不能冒充同二进制性能 A/B，且不能扩大为全部 Profile 已统一。
> 逐门里程碑账本：`docs/MILESTONE_LEDGER_2026-07-23.md`。后续任务必须回填实际 commit/run/APK/READY 身份，不能只更新叙述性进度。
> 协同规则：2026-07-19 起，`SHARED_TEST_STATUS.md`、lease、待交接和自动 `Verifier` 退役。P40 改为“实时只读现场检查 → 干净则直接测试 → 停止本轮全部 App/VPN/抓包/临时规则并恢复设置 → Huawei Launcher → 即时复核”；无法安全归属的既有会话不得擅自清理。E-01/阿里云继续执行独立预检、远端 `flock`、受限变更、原子回滚和验后检查。

## 0. 先讲偏差与裁定

- ［KNOWN｜HIGH］当前采用 monorepo，但目录数量不等于计划仓库数量：`app/` 同时承载 P1a/P1b，`server/` + `gateway/` 属于 P2，`profiles/` + `tools/aneb-ai-behavior-model/` 承载 P3 与 Profile 横切资产，Profile 3 独立适配器模块尚未建立。
- ［INFERRED｜HIGH］现阶段保持逻辑隔离更合适；出现独立发布节奏或独立负责人后再物理拆仓。
- ［KNOWN｜HIGH］“Profile 即数据”目前只完成了一半：业务参数、质量目标、动态指标和运行计划已数据化；新增一种全新的传输原语、采样语义或评分算法仍然需要代码。可执行的铁律应是：**已有原语内的业务变化只改 Profile；新增原语先升 spec/contract，再改 P1/P2 引擎。**
- ［KNOWN｜HIGH］JSON 与 YAML 都能承载声明式合同。当前产物已统一为可校验 JSON；为了形式改成 YAML没有业务价值，后续重点是单一 schema、兼容区间和消费者一致性。
- ［KNOWN｜HIGH］现有 Profile 有两族合同：服务端根 Profile（4 个相位 Profile）和 App 发布 Profile v2（12 个业务/测量 Profile）。M0-EC1 只让 `token_multimodal_quick@1.2.1` 成为 P1/P2/P3 可同时证明的首个窄切片；其余 Published Profile 与根 Profile 家族尚未收敛成通用端到端合同，仍是 M0 的治理欠账。
- ［KNOWN｜HIGH］真实第三方 App 适配器尚未进入主 App。依照此前“ANEB App 只做自建节点仿真”的产品边界，未来 Profile 3 应放在独立 `aneb-adapters` 模块，不得把真实 API、账号或脆弱自动化混入 P1b 核心评分链。

## 1. 一页结论

| 计划单元 | Codex 当前状态 | 结论 |
|---|---|---|
| **P1a 前台 UI** | **0.5.10 产品化大部完成；开测、导出和统一结论已闭环** | ［KNOWN｜HIGH］原生 Compose 已覆盖测试发起、三类动态测试、Profile 目录、历史、结果、报告、设置、节点与体验地图外壳；三类结果页均可保存/分享经摘要校验的单条 JSONL，设置页可把全部独立验真的 v1/v2 历史按时间导出，并分别提示格式不支持与完整性异常。0.5.9 直接展示评分器冻结的完成性、Profile 业务行为、门限与瓶颈；0.5.10 对下载目录的创建、写入、完成和失败清理逐阶段验真，禁止半成品或误报成功。视觉按 `ANEB_UI` 原生实现，并已有新 App 图标。真实 API Probe 已从正式 UI/Release 组件移除，只保留受保护 Debug/ADB 诊断组件。 |
| **P1b 测量引擎** | **三族 Quick 单节点跨端闭环；已做 15 次工程重复采样，正式矩阵仍待完成** | ［KNOWN｜HIGH］Token 多模态、AI 实时双工、网络综合、合成弱网、恢复与专用网关控制均已成独立引擎，由前台 Service 持有；三类正式结果先落 Room 再发布，并冻结 `aneb-result-v2`、1Hz 无线样本、环境事件、稳定结论 ID 与证据依据。三族 Quick 正负 READY 已完成；S3/M1 又完成三族各 5 次业务运行，但 15/15 无线权限拒绝、严格导出被拒绝。这证明业务编排可连续运行，不证明 Standard/Recovery、无线完整性或正式基线完成。 |
| **P2 服务器侧** | **0.8.2 已部署；Token/Realtime 跨端窄切片完成，Network 服务端门完成** | ［KNOWN｜HIGH］E-01 当前运行 `aneb-server/0.8.2`，覆盖 Token、上传、下载、工具循环、WebSocket 实时双工、测速、ANEB2 UDP、结果与逐 run 合成弱网，并提供三类 Quick 的 Profile 白名单能力回执和同-run request-entry 审计。受锁部署验后确认共享主机指纹不变、临时残留为 0。对照原计划仍缺 RTP/WebRTC 语音回环、通用 1GiB 上传档位、全端点统一时戳/序号和同城/区域/中心三级实例。 |
| **P3 标准与业务模型** | **0.3.2 本地候选；真实画像仍未完成外部签名校准** | ［KNOWN｜HIGH］授权统计白名单、HMAC 主体隔离训练/留出、固定误差门限、候选/报告/数据摘要绑定和 validated 发布复算继续保持；0.3.2 已进入 Token/Realtime/Network 三类 Quick 的合同消费区间。现有 4 个模型仍为 `hypothesis`；A6 私有校准链已形成 first-50 选择，但独立外部 reviewer 签名与正式 qualification 尚未闭合，不能声称代表 Kimi/DeepSeek/千问真实性能。 |
| **横切 Profile 体系** | **1.8.0；三类 Quick 精确执行合同及正负 READY 均已建立** | ［KNOWN｜HIGH］`spec/catalog.json` 机器索引 8 个 Schema、2 个 Profile 家族、16 个 Profile、7 个 runtime bundle、4 个模型资产和 3 个 execution evidence contract；Token 1.2.1、AI 实时 1.1.1、Network 1.2.0 均已形成 P1/P2/Profile 共同消费并真机验证的精确合同。其余 Published Profile 仍保持兼容，不能扩大为全部 Profile 已完成端到端验收。 |
| **里程碑位置** | **S2 三族 M0 收敛已关闭；S3 M1 首轮真机工程采样已完成但严格门失败** | ［KNOWN｜HIGH］D-109/S2 已由 commit `95aaaf0` 与 clean CI `30313367261` 关闭。S3 cohort/导出离线合同已完成；首轮 P40 三族各 5 次业务运行完成，Token D-58 子门 PASS，但无线权限 15/15 拒绝且严格导出不成立。当前下一门是带权限机器回执的重采、无线完整性与独立复算，不是把这批诊断结果提升为正式基线。 |

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
- ［KNOWN｜HIGH］App 0.5.9 把三类字符串结论升级为评分器冻结的语义项：稳定 `conclusion_id`、准确 `info/recommendation/warning/failure`、原始文本和指标/证据 basis；导出器不再按条目位置猜级别，结果页不再另写行为特征。正常、必需指标缺失、无效证据及默认网络变化共现均有回归；计划受控中断单独按恢复任务完成性评价，Token 缺指标时也不再吞掉已观察到的任务完成事实。12 个 Profile 与对应结论策略已升小版本，6 个 Token/AI 实时运行包重新哈希绑定。545 项 JVM 测试、Lint 零 error、Schema/catalog、测量分析、行为模型及 Go 门禁通过。P40 蜂窝 Quick run `019f7377-9a61-7db5-a8c4-1ac57de1a486` 在紧邻 0.5.9 候选上完成 3/3 轮、99.8/A、LOW/INCONCLUSIVE；动态 UI、12 条冻结结论和系统下载目录 strict-v2 JSONL 均复验通过。实测后重构的精确 0.5.9 APK 未另行安装，随后由 0.5.10 云端候选取代；0.5.10 证据不倒写为该精确 0.5.9 二进制证据。酒店 Wi-Fi 的前序失败发生在门户未认证时，不作为网络质量 A/B。见 `APP_0.5.9_SEMANTIC_CONCLUSION_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］App 0.5.10 将所有系统下载导出收紧为“创建 pending → 写入全部 UTF-8 字节 → 完成标记成功”后才返回成功；打开、写入或完成失败均尝试删除半成品，清理失败则保留 URI 并显式报告。6 个故障注入测试覆盖成功、创建失败、打开失败、磁盘写入失败、完成失败和清理失败；全量现为 90 suites / 551 tests，Lint 0 error / 11 notices，其他门禁均通过。精确云端候选已在 P40 完成跨 Debug 签名数据保全：Room v19 integrity OK，保留 36 条信封/10 条 `test_run`，安全偏好/API key 未恢复。批量导出 32/36 条通过离线验证（v1=27、v2=5、唯一 run=32、重复=0），4 条完整性异常透明拒绝；单条 v2 与批次对应行逐字节一致，两条 MediaStore 记录均为 `is_pending=0`。该变化不改结果正文、摘要、Schema、Profile 或评分；成功路径真机证据不冒充失败分支真机覆盖。见 `APP_0.5.10_EXPORT_RELIABILITY_VALIDATION_2026-07-18.md`。
- ［KNOWN｜HIGH］云端 Debug 交付流水线已完成安全门后的真实闭环：commit `51fdd7c81f1f63a7202dd40d8ce86f5931d0d1a2` 触发 GitHub run `29635434193`，凭据扫描、合同、Go 服务端、网关控制面/竞争/构建、行为模型和 Android 候选六个 job 成功；工件 `8427011992` 名称绑定同一 commit，ZIP 摘要 `ffef2b3f0c3177e3ac81794b3d7ced536eee3afae71f5927e6a43fd6db3cccb0`。云端 APK 身份为 `com.aneb.probe.codex` / `0.5.10-codex` / code 42，SHA-256 `49244B3157FCC47D54EDA61A51EAF4B69A71BD2B95314BAE54E327CE8B0F6D85`；attestation `35945988` / Rekor `2193995642` 已离线验证。外部固定 CA 的隔离 TLS/netem 命名空间步骤因无叶证书 secrets 明确跳过，不折算为 PASS。见 `CLOUD_DEBUG_CANDIDATE_DELIVERY_2026-07-18.md`。
- ［KNOWN｜HIGH］软件弱网可控制带宽、应用时延、抖动和短时不可用；真实 RSRP/SINR 仍需屏蔽箱、衰减器或基站模拟器。

## 4. P2 服务器侧

### 已完成

- ［KNOWN｜HIGH］P2 当前实现与部署技术栈是 Go。
- ［INFERRED｜HIGH］为了匹配旧计划而改写为 FastAPI不会增加测量能力，当前不应重写。
- ［KNOWN｜HIGH］E-01 当前运行 `aneb-server/0.8.0`，端点包括 `/profiles`、`/echo`、`/stream`、`/token-sim`、`/realtime-sim`、`/upload`、`/download`、`/toolloop`、`/results`、`/serverinfo`、`/impairments` 与 UDP/8443；`serverinfo` 还提供 Quick 能力回执，入口审计按 D-81/D-82 只证明 request entry。
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
| **M0 契约冻结** | `spec/catalog.json` 索引 8 Schema/2 家族/16 Profile/6 运行包；兼容 v1/严格 v2 结果、授权观测、校准数据集和留出报告合同已有正反例校验；Token Quick 1.2.1 已形成 P1 0.5.12 / P2 0.8.0 / P3 0.3.1 共用的精确执行合同、真实 download 和同-run审计；E-01 已部署 0.8.0；D-82 正向与 D-86 `receipt_missing` 负向均已在 P40/E-01 生成 D-87 READY 并由独立消费者复核 | ［KNOWN｜HIGH］**首个 Token Quick 窄切片正负跨端闭环，M0 总体仍部分完成且通用执行合同仍分叉**；正负使用不同 CI APK，只支持各自合同闭环，不是严格同二进制性能 A/B；不能把一个 Quick 切片扩大为全部 Profile 已统一。 |
| **M1 核心闭环** | Kotlin 引擎 + Go 单节点 + 三类仿真轨、Room v19 统一信封、UI JSONL 与正式三引擎 radio_ctx 已跑通；strict-v2 cohort、无线结构诊断与冻结 Room 原字节导出/绑定层已实现；首轮新 P40 三族各 5 次业务运行完成，Token D-58 子门 PASS，Realtime/Network policy pending | ［KNOWN｜HIGH］**M1 仍为部分完成**：首轮新样本的无线权限全部拒绝，strict cohort 未形成；无线 cadence 正式判据、非开发者连续运行、真机导出独立复算和族专属正式门限尚未验收。 |
| **M2 外场 MVP** | 无 6–8 点位 × 忙闲 × 双运营商活动，无三级实例与正式热力报告 | ［KNOWN｜HIGH］**未启动**。 |
| **M3 真实业务与语音** | AI 实时 WS 仿真/打断/恢复已完成；真实画像、Profile 3 适配器、RTP/WebRTC 回环与逐帧打点验收未做 | ［KNOWN｜HIGH］**仅 WebSocket 仿真轨完成**；其余验收没有客观完成比例。 |
| **M4 产品化** | Compose UI、动态测试、历史/结果/报告/分享已完成大部；正式发布边界与生命周期加固中 | ［KNOWN｜HIGH］**部分超前**；非开发者验收、签名 Release 与公开发布仍未完成。 |

## 8. 当前自主执行顺序

1. ［KNOWN｜HIGH］M0-EC1/EC2/EC3 与 D-109/S2 已结案，不重复冻结真机证据来证明纯离线重构。S3/M1 已完成 cohort/无线结构/冻结导出合同，并完成首轮 P40 三族各 5 次工程业务采样；因 15/15 无线权限拒绝，下一门改为“权限回执先行 → 三族重采 → 冻结快照导出 → 独立复算”，见 `S3_REPEATABILITY_COHORT_CONTRACT_2026-07-28.md` 与 `S3_M1_REPEATABILITY_ENGINEERING_VALIDATION_2026-07-28.md`。
2. ［KNOWN｜HIGH］P1 发布边界、AI 实时生命周期修复、`spec/` 目录与统一结果 Schema 已完成并有自动校验。
3. ［KNOWN｜HIGH］AI 实时/网络综合 Room v19 结果信封、用户可见 JSONL 与 P40 真机回归已完成。
4. ［KNOWN｜HIGH］三个正式新引擎的 RadioCollector 与活动承载/蜂窝协变量分轨已实现；首轮新样本证明业务轨能连续完成，也反证“安装命令成功即无线权限已生效”的假设。只有 Token TTFT 的 D-58 子门具有授权判据；Realtime/Network 当前仍只有 diagnostic-only 能力，不能写成正式重复性通过。
5. ［KNOWN｜HIGH］P3“授权观测 JSONL → 校准模型 → 留出验证 → validated 发布”流水线已实现；没有真实授权数据时仍不生成 calibrated/validated 正式资产。
6. ［KNOWN｜HIGH］M4 下载导出失败清理和云端 Debug 候选打包已在 0.5.10 完成本地故障注入、全量门禁、真实 GitHub Actions 工件、来源证明和 P40 精确候选验收；混合批量 32/36 条及单条 v2 已通过离线验证，两条成功导出的 MediaStore 行均完成。下一步是不依赖 ADB 的终端用户整链与正式签名 Release；签名密钥仍服从仓库外 Product Owner 所有权边界。
7. ［KNOWN｜HIGH］M4 高置信凭据扫描已接入本地质量门和独立 GitHub security job；工作区与暂存区双读、日志脱敏及 6 项定向测试通过，run `29635434193` 的 `Tracked-source credential scan` 与其后 Android 候选 job 均成功。已经披露的凭据仍必须撤销，扫描结果不能替代供应商审计。
8. ［KNOWN｜HIGH］M2 三级部署、专用网关最终 TLS/P40 硬件验收和真实射频弱网继续保持 `BLOCKED_EXTERNAL`，不拿单节点或软件损伤冒充。

## 9. 原计划待拍板项回写

- ［KNOWN｜HIGH］P1 技术栈已由实现事实关闭：Android 原生 Kotlin + Compose。
- ［KNOWN｜HIGH］P2 技术栈已由实现事实关闭：Go。
- ［KNOWN｜HIGH］云区域、同城/区域/中心三级部署和首批试点城市尚未落实。
- ［KNOWN｜HIGH］Profile 3 首批 App 已因后续“主 App 只做自建节点仿真”的裁定延期并转为独立模块议题，不再是 P1b 当前决策。

## 10. 与 Claude 侧对齐原则

- ［KNOWN｜HIGH］双方不再读取或写入 `SHARED_TEST_STATUS.md` 领取权限；只依据 P40 实时现场决定是否开始。任何无法安全归属的活动会话都不得被另一方擅自停止或覆盖。
- ［KNOWN｜HIGH］E-01 部署仍由 Codex 单点执行，权威合同为 `docs/TEST_SERVER_CAPABILITIES.md`；Claude 提需求/补丁，不直接覆盖共享服务器。
- ［KNOWN｜HIGH］两侧都按 P1a/P1b/P2/P3/Profile/M0–M4 更新进展，版本、测试数字、真机 run 和部署状态必须能回指仓库或原始证据。
- ［KNOWN｜HIGH］“已实现”“已部署”“已真机验证”“已达到目标”是四个不同状态，后续报告不得混写。
- ［INFERRED｜HIGH］两套客户端的价值是互相复核测量语义与结果，而不是靠功能数量竞赛；发现分歧时先对齐 spec，再比较实现。

## 11. 2026-07-27：M0-EC2 AI 实时 Quick 结案

- ［KNOWN｜HIGH］App `0.5.13-codex` / server `0.8.1` / AI 实时 Quick `1.1.1` 已在 source `fe60c1c`、CI run `30215857444` 的 exact APK 上完成 P40 正负 READY；正向 run `019fa00a-3e17-7c9d-959b-50aab47c1b91`，负向 run `019fa00d-17f3-71d3-b2d9-af2e9271c96d`。
- ［KNOWN｜HIGH］正向业务真实完成；负向在能力 receipt 缺失时业务前拒绝，服务端 realtime business count=0，客户端 score/grade=null、业务产物为零。两个 READY 均由独立 release verifier 重算通过。
- ［KNOWN｜HIGH］最终 P40 回到 Huawei Launcher，相关 PID/service/accessibility/VPN/tun=0，ADB reverse empty，Wi-Fi on，stayon=7；E-01 锁释放，PID/二进制/Docker/eth0/firewall 指纹与进入前一致。
- ［KNOWN｜HIGH］正向 `100/A` 只对应一次 Quick，最终 verdict 仍为 `INCONCLUSIVE/LOW`、coverage 0.1；不得冒充正式无线体验基线。
- ［INFERRED｜HIGH］计划架构的下一最小闭环为 M0-EC3 网络综合 Quick，复用 EC1/EC2 已验证的 provenance、正负 READY、同-run 审计和精确清理机制。

完整证据见 `M0_EC2_REALTIME_QUICK_READY_VALIDATION_2026-07-27.md`。

## 12. 2026-07-27：M0-EC3 Network Quick 结案

- ［KNOWN｜HIGH］App `0.5.14-codex` / server `0.8.2` / Network Quick `1.2.0` 已在 source `ea9de17c2acea763513b144b4fb9942a3d54c5c6`、CI run `30266912724` 的同一 exact APK 上完成 P40 正负 READY。
- ［KNOWN｜HIGH］负向 run `019fa3a7-b34a-7a0c-b45f-3e6e3d7b0d8c` / READY `7fa7fb24…0878` 在 `receipt_missing` 时业务前拒绝，客户端与服务端业务产物均为零；权威正向 run `019fa3d7-8ab2-76eb-90bd-182a482b3c7f` / READY `e153ee46…c3837` 完成 44/44 应用请求、50/50 UDP 返回，两个独立 release verifier 均 PASS。首份正向本地 bundle 因事后 SQLite checkpoint 污染而废弃，不再用于验收。
- ［KNOWN｜HIGH］权威正向 `79/B` 的最终 verdict 是 `INCONCLUSIVE/LOW`、coverage 0.5；容量达标但负载 RTT 和负载时延增量是主要瓶颈，只能形成当前路径的方向性工程结论。
- ［KNOWN｜HIGH］最终 PhoneGuard、ADB reverse、采集器/代理进程与远端锁均清理；P40 回到 Huawei Launcher，相关 PID/service/accessibility/VPN/tun=0，stayon=7，E-01 身份和共享指纹稳定。
- ［INFERRED｜HIGH］下一最小阶段为 S2 三族 M0 收敛：通过等价性与故障注入抽取共用机械组件，不重跑或改写三族已经冻结的真机业务证据。

完整证据见 `M0_EC3_NETWORK_QUICK_READY_VALIDATION_2026-07-27.md`。

## 13. 2026-07-28：S3/M1 三族无线重复性工程闭环

- ［KNOWN｜HIGH］source `8a834d49dd61a1f544dc5ea10991e50929f85a3e` / CI `30349786024` 的 exact APK 在 P40 完成 Token、AI 实时、Network 各 5 次；业务前权限回执证明三项无线权限均 granted，15/15 run completed。
- ［KNOWN｜HIGH］冻结 Room 副本严格导出三组各 5 条 strict-v2 envelope，原 DB/WAL/SHM 分析前后逐字一致；Token/Realtime/Network 无线样本总数分别为 641/135/100，约 1 Hz，stale 与订阅切换均为 0。
- ［COMPUTED｜HIGH］Token D-58 任务 TTFT CV 中位数 `1.71%`，授权结论 `PASS`；Realtime/Network 只有 diagnostic distribution，正式重复性判据仍为 policy pending。
- ［KNOWN｜HIGH］所有 formal baseline eligibility 继续为 false：无线 cadence/gap/stale 没有批准阈值，Realtime/Network 没有族专属门，部分 run 级业务指标也未达到 Profile 声明的最低样本数。
- ［KNOWN｜HIGH］runner finally、原 APK/Room 恢复、独立 PhoneGuard T+0/T+2、E-01 六项共享指纹与 flock release 全部闭合；P40/E-01 已释放。
- ［INFERRED｜HIGH］下一最小阶段是 S3/M2 门限提案与更长样本计划：先根据本批真实分布提出 2–3 套候选门限及成本，再由 Product Owner 决策；不得从 5 次工程样本自动生成正式判据。

完整证据见 `S3_M1_REPEATABILITY_RADIO_VALIDATION_2026-07-28.md`。
