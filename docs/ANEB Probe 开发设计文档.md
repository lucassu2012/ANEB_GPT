# ANEB Probe 开发设计文档

> Agent Network Experience Benchmark Probe——面向智能体业务的移动网络体验测试工具
>
> 版本 v0.3（as-built：阶段 0–3 实测成果回写，含各阶段完成状态与证据账本指针）｜ 2026-07-13 ｜ 状态：as-built 基线
>
> 版本沿革：v0.2 = 设计基线 + 红队修订与制度对齐（2026-07-12）；v0.3 仅做实施状态标注与下一步清单更新，架构与口径不变。
>
> 需求输入：《智能体互联网时代（Agentic Internet）移动通信网络的新型网络性能与体验诉求》第五部分（agent-qoe-kpi v0.2.2；v0.2.2 为修注版，新增候选指标 REACH 与真实蜂窝/SNI-RST 实测锚点，既有指标定义/门限/统计口径不变）
>
> 配套：《测量红队清单》（32 项经对抗验证的测量失真风险及闭环计划 + 实测增补 R-33，v0.2 修订的直接依据）；《参考_ChatGPT侧ANEB_AndroidEcho方案与进展_2026-07-11》（并行项目制度借鉴来源）；《DECISION_LOG》（决策日志与外部依赖清单）

---

## 1. 背景与定位

编码类/多轮对话类移动端智能体（Claude Code、Kimi Claw 等）对移动网络的本质诉求是"有界时延 + 会话持续性 + 小包可靠性 + 上下行对称化"，而非峰值带宽。现有测速工具（SpeedTest 类）测的是吞吐与单点 ping，**测不出 TTFT、token 间卡顿、上行大 prompt 突发、长会话中断**这些 Agent 体验的决定性维度。

**ANEB Probe 的定位：研究/取证工具。** 由研究者本人及小范围团队使用，在真实移动网络（4G/5G）上采集 Agent-QoE 数据，建立测量基线，支撑后续向运营商与标准组织展示证据。设计取舍原则：**数据质量与可重复性 > 功能覆盖 > UI 打磨**。

**核心方法论（访谈已确认的决策）：**

| 决策点 | 结论 |
|---|---|
| 测试对端 | 自建"Agent 流量仿真服务器"为主（网络贡献可精确归因），真实 LLM API 探针为辅（阶段二，端到端对照） |
| 平台 | Android 原生（Kotlin），因为只有 Android 能拿到无线层信息与精细网络回调；iOS 按需再说 |
| 服务器 | 国内云 VM 单节点起步（裸 VM，自控协议栈与时间戳），后续加海外节点对照 |
| MVP 指标 | 流式体验（T 组）+ 上行突发（U 组）+ 无线层快照（R 组）；连续性（C 组）与 QUIC/TCP A/B 放阶段二 |
| 流量模型 | 3 个版本化预置场景：S1 对话流 / S2 编码 Agent 流 / S3 多模态流 |
| 评分 | 四级门限 + AQS 0–100 综合分（aqs v0.1，见 KPI 文档 5.4） |
| 数据 | 手机端 Room 全量明细 + 测试结束上报服务端落 JSONL，双写 |
| 技术栈 | 客户端 Kotlin + OkHttp（EventListener 精细计时）；服务端 Go（阶段二经 quic-go 提供 HTTP/3） |
| 仓库 | 本 monorepo：`docs/`、`app/`、`server/`、`profiles/`、`evidence/` |

**命名消歧与姊妹项目**：本项目对外统一称 **ANEB Probe**（Agent Network Experience Benchmark）；并行开发的姊妹项目（Application Echo RTT 垂直切片）对外称 **ANEB Android Echo 切片**，两者同属 ANEB 研究计划、指标与范围互补。其工程制度（四态证据、claim boundary、fail-closed、红队闭环、供应链纪律）已吸收进本文档 v0.2；与本项目决策冲突之处（Cronet 逐请求绑定、M2 里程碑制、功能范围冻结、单指标 const 锁死、最小权限清单、invalid 全抑制到数据层、设备矩阵前置）经评估**不予照搬**，理由记录于 [DECISION_LOG](DECISION_LOG.md)。

---

## 2. 总体架构

```
┌─────────────────────────────┐        HTTPS (H2+SSE) / 阶段二 H3(QUIC)
│  Android App (ANEB Probe)   │◄──────────────────────────────┐
│                             │                               │
│  ┌───────────┐ ┌──────────┐ │   ┌───────────────────────────┴─┐
│  │ TestEngine │ │ Radio    │ │   │  Go 仿真服务器 (aneb-server) │
│  │ 场景状态机  │ │ Collector│ │   │  /echo 时钟同步&RTT          │
│  └─────┬─────┘ └────┬─────┘ │   │  /stream SSE token 发生器    │
│  ┌─────┴─────┐ ┌────┴─────┐ │   │  /upload 上行突发汇          │
│  │ NetProbe  │ │ Room DB  │ │   │  /toolloop 工具循环回显      │
│  │ (OkHttp)  │ │ +导出    │ │   │  /results JSONL 落盘         │
│  └───────────┘ └──────────┘ │   │  profiles/ 场景配置(共享)    │
│  KPI 计算 + AQS 评分 + UI    │   └─────────────────────────────┘
└─────────────────────────────┘
```

两端共享 `profiles/` 目录中的场景定义与 KPI 门限（客户端打包内置 + 启动时从服务端拉取校验版本一致性，不一致以服务端为准并告警）。

---

## 3. 测试场景与流量模型

场景以版本化 JSON profile 定义（见 `profiles/`），由服务端解释执行、客户端按 profile 校验预期。**profile 一旦发布即冻结，修改必须升版本号**，保证跨时间/地点/设备的结果可横向对比。

### 3.1 三个预置场景

| | S1 对话流（对照组） | S2 编码 Agent 流（主场景） | S3 多模态流 |
|---|---|---|---|
| 对标 | 普通 chat 应用 | Claude Code / Kimi Claw 编码任务 | 截图转代码类视觉 agentic |
| 上行 | 2KB 小 prompt | 512KB 大 prompt 突发 ×1 + 工具循环上行 8KB×8 | 1MB 图片上传 ×2 |
| 下行 | 600 token 稳态流（40 tok/s） | 300 token 思考流 + 8 轮工具循环 + 800 token 代码流（60 tok/s，含突发簇） | 400 token 流（40 tok/s） |
| 节奏特征 | 均匀 | 突发-静默交替（思考停顿 2–5s）| 大包上行与 token 流交替 |
| 时长 | ≈60s | ≈90s | ≈75s |
| 覆盖 KPI | N1/N2、T1–T4 | 全部 T/U 组（U2 仅此场景） | T 组 + U1（大文件口径） |

token 大小分布取自实测文献锚点（单 token 事件 payload 约 50–300 字节，对数正态分布）；S2 的突发簇模拟自回归生成的"快速吐 token ↔ 注意力停顿"交替（burst 长度几何分布，簇内 100 tok/s、簇间停顿 300–800ms）。

### 3.2 Profile Schema（v0.1）

```json
{
  "profile_id": "s2_coding_agent",
  "version": "0.1.0",
  "kpi_set": "agent-qoe-kpi-v0.1",
  "phases": [
    {"type": "clock_sync", "samples": 20},
    {"type": "upload_burst", "bytes": 524288, "chunk_kb": 64},
    {"type": "think_pause", "duration_ms": 3000},
    {"type": "token_stream", "tokens": 300, "rate_tps": 60,
     "token_bytes": {"dist": "lognormal", "median": 120, "sigma": 0.6},
     "burst": {"cluster_tps": 100, "pause_ms": [300, 800], "cluster_geom_p": 0.05},
     "seed": 42},
    {"type": "tool_loop", "rounds": 8, "up_bytes": 8192, "down_bytes": 2048, "server_proc_ms": 200},
    {"type": "token_stream", "tokens": 800, "rate_tps": 60, "...": "同上"}
  ]
}
```

关键点：**`seed` 固定随机序列**——同一 profile 版本在任何时间生成完全相同的 token 大小/节奏序列，消除随机性对横比的干扰。

---

## 4. 测量方法学

（完整定义见 KPI 文档 5.3，此处为实现要点。）

1. **计时**：全部用 `SystemClock.elapsedRealtimeNanos()`；在 OkHttp EventListener（`dnsStart/connectStart/secureConnectEnd/requestBodyEnd/responseHeadersStart` 等）与 SSE 读线程回调内就地记录，不经主线程。
2. **时钟同步**：每场景前 `/echo` 4 时间戳握手 ×20，取最小 RTT 样本估计 offset（Cristian 算法），用于上/下行归因，误差 ±RTT/2 随结果存档。
3. **服务端节奏剥离**：每个 SSE event 携带 `{seq, srv_ts}`；网络抖动 = (客户端到达间隔) − (服务端发出间隔) 的逐序号对齐差。服务端同时自检发送误差（实际 flush 时刻 vs profile 期望时刻），误差 P99 >5ms 的运行标记为服务端失真样本。
4. **反缓冲自检**：客户端检测 token 到达的批化特征（如 >30% 的 ITL≈0 且随后跟长间隔），触发"链路存在缓冲，样本无效"告警。服务端直连裸端口（默认 8443），不挂 CDN/反代。
5. **无线层打点**：`TelephonyCallback`（SignalStrength/CellInfo/DisplayInfo）1Hz 采样 + 事件驱动记录（小区变更、制式变更），与 KPI 事件按单调时间轴对齐。
6. **有效性守卫（三态 Gate + fail-closed）**：测前检查（前台 Service、屏幕常亮、非省电模式、目标网络就绪）升级为**贯穿测试全程的持续监控**，输出三态 `valid / valid_low_confidence / invalid`。invalid 触发条件（首事件获胜状态机）：绑定网络丢失或默认网络切换、`NET_CAPABILITY_VALIDATED` 丢失、网络监控回调注册失败、批化自检（残差域）触发、服务端失真自检触发、测中 Doze/省电状态变化——立即中止当前场景、取消 in-flight 请求、**抑制该场景 KPI 与 AQS 输出**；抑制只作用于聚合层，原始事件仍全量入库并记失效原因码（取证需要分析失效原因）。证据缺失（如无线层采样被拒）判 `valid_low_confidence` 而非隐式健康。阶段二 C 组切换实验以路径迁移为测量对象，显式豁免路径类 fail-closed。
7. **网络绑定与路径对账**：无论 WiFi 还是蜂窝，一律 `requestNetwork(指定 transport)` 获取 `Network`，OkHttpClient 同时绑定 `network.socketFactory` 与 `Dns`（`network::getAllByName`——否则域名解析仍走默认网络的 DNS，解析与承载路径分裂）；等到 capabilities 同时含目标 transport + `VALIDATED` + `NOT_SUSPENDED` 才放行，15s 超时即 fail-closed 报"环境不就绪"（禁止超时放行）。守卫硬拒测项：存在 VPN（`TRANSPORT_VPN`）、WiFi/全局 HTTP 代理非空；Private DNS 记入元数据。**绑定证据双端对账**：服务端每场景回显观察到的客户端源 IP:port，客户端与声称 transport 核对（蜂窝应为运营商地址段），不符判 invalid——客户端拿到 network handle 不等于流量真走了该网。同 run 内出口 IP 漂移打 `nat_path_shift` 标并禁止跨场景对照结论。
8. **事件配对与解析健壮性**：KPI 计算强制以 event 内嵌 `seq` 做 join，禁止数组位置配对（丢/错切一个 event 即整段静默错位）；检测 seq 缺号/重号/回退，gap>0 打降级标、gap 超 token 总数 1% 判 invalid；payload 用长度前缀或 base64 编码，杜绝随机字节与 SSE 分隔符（`\n\n`）冲突的解析歧义。
9. **失败样本语义**：失败/超时样本的时延值一律记 `null`，绝不记 0 或超时上限值；流式异常中断时"最后间隔"不入 ITL/stall 统计、改计会话中断事件；`successful` = 2xx + 无传输错误 + 计时值非空。
10. **客户端自监控（与服务端发送自检对称）**：SSE 读线程只做「read → 打戳 → 写预分配环形数组」，解析、实体构造、Room 落库全部推迟到 phase 结束后一次性批量事务；读线程 `THREAD_PRIORITY_URGENT_AUDIO`；10ms 哨兵线程检测进程级停顿（>30ms 记 `app_jank` 事件，与 token 时间轴对齐后重叠 ITL 样本标 `app_contaminated`）；`addThermalStatusListener` 记录热状态迁移（SEVERE 以上打污染标）+ 1Hz CPU 频率采样，识别热节流导致的打点滞后。
11. **红队闭环制度**：以上规则的完整依据是 [测量红队清单](测量红队清单.md)——32 项经多代理对抗验证的测量失真风险（10 项 high），每项标注闭环阶段；写码前逐项对照，闭环证据落 `evidence/`。

---

## 5. 客户端设计（app/，Kotlin）

**构建目标**：minSdk 29（CellInfoNr/5G API 需要）、targetSdk 35、Jetpack Compose UI、单 module 起步（`app`），按包分层：

```
com.aneb.probe
├── engine/        TestEngine：场景状态机（读 profile → 逐 phase 执行 → 产出事件流）
│                  PhaseRunner: ClockSyncRunner / UploadRunner / StreamRunner / ToolLoopRunner
├── net/           AnebClient（OkHttp 单例，禁连接池复用跨场景）、TimingEventListener、SseReader（逐字节读，避免 BufferedReader 攒行）
├── radio/         RadioCollector（TelephonyCallback 1Hz + 事件流）、NetGuard（网络绑定与前置检查）
├── data/          Room：TestRun / ScenarioResult / TokenEvent / RadioSample / KpiValue
│                  Exporter（JSON/CSV 到 Downloads）、Uploader（POST /results）
├── scoring/       KpiCalculator（T/U/N 组逐项计算）、AqsScorer（aqs v0.1 锚点插值+加权）
└── ui/            HomeScreen（一键快测/取证模式）、LiveScreen（实时 token 瀑布+信号曲线）、
                   ResultScreen（AQS 头条 + 四级色条 KPI 表）、HistoryScreen
```

**关键实现约束：**
- SSE 解析自实现（约 100 行）而非 okhttp-sse，因为需要在**读到 event 首字节的时刻**打戳，okhttp-sse 回调在完整 event 解析后才触发，会引入解析偏差。
- OkHttp 配置：`retryOnConnectionFailure(false)`（重试会掩盖网络问题）、每场景新建连接（消除连接复用导致的 TTFT 不可比）、`connectTimeout 10s / readTimeout 30s`。
- 上行突发用 `RequestBody.writeTo` 手动分块写并逐块打戳，得到上行吞吐时间序列而不只是总耗时。
- 前台 Service（`dataSync` 类型）承载测试执行，防止息屏/切后台被杀。
- 读线程零分配打戳 + 事后批量落库、哨兵线程、热状态监听（见 §4 第 10 条）；取证模式 LiveScreen 降为 1–2Hz 摘要刷新，实时 token 瀑布只保留在快测模式（防渲染争抢 CPU 污染打点，阶段 1 验收有开/关对照项）。
- 阶段 1 前配置 release `signingConfig`（自管 keystore，密钥不入库）；上报体记录 versionName/versionCode 与签名证书指纹，保证每份取证数据可溯源到具体 APK。

## 6. 服务端设计（server/，Go）

单二进制 `aneb-server`，无外部依赖（标准库 + 后续 quic-go），systemd 托管，监听 `:8443`（自签或 Let's Encrypt TLS）。

| 端点 | 方法 | 职责 |
|---|---|---|
| `/api/v1/echo` | POST | 4 时间戳时钟同步 + RTT 采样（body <100B） |
| `/api/v1/profiles` | GET | 下发场景 profile 与 KPI 门限（含版本号） |
| `/api/v1/stream?profile=&phase=&run=` | GET | SSE token 发生器：按 profile 节奏逐 event Flush，event 含 `{seq, srv_ts, payload}` |
| `/api/v1/upload?run=` | POST | 上行汇：丢弃 body 但记录逐块到达时间，响应返回服务端视角的接收时间序列 |
| `/api/v1/toolloop?run=` | POST | 收 8KB → 定时 200ms → 回 2KB，附服务端时间戳 |
| `/api/v1/results` | POST | 接收结果摘要+明细，按日落 `data/results/YYYYMMDD.jsonl` |

**实现要点**（红队修订后）：
- pacing 用绝对时刻表（`time.Until(startTime + expected[i])`）而非累加 sleep，防漂移累积；每 event `http.Flusher.Flush()`；Go 默认 TCP_NODELAY 已开。
- 每 event 同时携带**期望发出时刻**（profile 时刻表）与**实际 flush 时刻**两个戳；`srv_ts` 一律以进程启动锚点的单调差序列化（墙钟仅流首发一次锚点映射）——防云 VM NTP 步进制造伪 >1s 卡顿；VM 的 chrony 禁 makestep（只允许 slew）。
- pacing 误差**拆两类分别记录**：timer 迟到（调度问题→判服务端失真）与 flush 阻塞时长（TCP 回压→网络证据，**绝不判失真**、随流返回参与归因）——否则弱网最恶劣样本会被自检系统性误删（红队高危项）。
- 响应头写出后先 flush 一个 `: prelude {srv_ts}` SSE 注释帧再按节奏发首 token，把 T1 中的服务端 dwell 从"网络分量"剥离。
- `/echo`：前 2–3 个请求丢弃预热、样本间 100–300ms 随机间隔去相关；响应回显客户端源 IP:port（路径对账）；handler 极薄（固定二进制 body、零日志），t2−t1 随样本返回入库、P99>1ms 告警。
- `/upload` 返回的逐块到达序列是上行节奏的**权威序列**；U1 计时终点 = 客户端收到 2xx 响应头（服务端已读完 body），客户端 `writeTo` 本地写序列仅作辅助诊断（其 claim scope 为"写入本地协议栈"，防把内存拷贝测成千兆假吞吐）。
- VM 基线钉死并快照入每次运行元数据：`net.ipv4.tcp_slow_start_after_idle=0`、拥塞算法固定（cubic）、clocksource、steal% 采样；每秒采样 TCP_INFO——**实现中**（并行修复分支，尚未合并）：范围已由原 notsent bytes（`egress_uncertain` 标）扩展为**含 retrans 共变量**，用于区分"丢包重传批化"与"中间盒缓冲批化"（P3-C05 已知缺陷的修复方向，见 evidence/phase3/netem_experiments_20260713.md）。
- `/results` 按 JSON Schema 校验拒收不合规上报：`claim_scope`（const 锁定 `application_end_to_end_to_probe_node`）、kpi_set / aqs / profile / schema 版本必填。
- **弱网剖面**：VM 预置 `tc netem` 脚本（`scripts/netem.sh 100ms 1%`），用于工具灵敏度验证与红队闭环实验。

## 7. 数据模型（双写口径）

- **TestRun**：run_id(UUIDv7)、时间、模式（快测/取证）、profile 版本集、AQS 版本、设备型号/系统/运营商/APN、污染标志。
- **ScenarioResult**：场景 ID、各 KPI 值与分级、有效性标志。
- **TokenEvent**（仅本地 Room 全量，服务端只收聚合直方图）：seq、srv_ts、client_ts、payload_bytes。
- **RadioSample**：ts、制式、NSA/SA、PCI、频段、RSRP/RSRQ/SINR。
- **EchoSample**（红队修订新增，仅本地全量）：每次 /echo 的 t0/t1/t2/t3 与 RTT 原始序列，供 offset 质量事后审计。
- **EnvEvent（事件时间轴，新增）**：热状态迁移、省电/Doze 变化、`app_jank`、路径/网络事件、NAT 出口漂移——与 KPI 事件共用单调时间轴，是"设备侧冻结 vs 链路缓冲"归因的关键证据。
- ScenarioResult 增加：三态有效性（valid/valid_low_confidence/invalid）+ 失效原因码 + **每场景网络快照**（transport、capabilities、接口名、服务端观察到的出口 IP:port）——TestRun 级一次性元数据无法发现测中网络漂移。
- TestRun 增加：服务端环境快照（sysctl 关键项、拥塞算法、clocksource、chrony 状态）+ APK versionName/versionCode/签名指纹。
- 时延类字段一律**可空**（失败记 null，禁 0/哨兵值）。
- 上报体 = TestRun + ScenarioResult + ITL 直方图（对数分桶）+ RadioSample 抽样，单次 <200KB；按 JSON Schema 校验（claim_scope 与版本字段 const/枚举锁定）。invalid 场景照常上报原始摘要与原因码，仅不进 KPI/AQS 聚合。

## 8. 分阶段实现计划

**as-built 完成状态（2026-07-13，四态证据账本为准）：**

- **阶段 0 — 已完成**（2026-07-13 收口）：17 PASS + 1 FAIL（P0-C14 字面判据 FAIL 与 D-18 修订判据 PASS 并列留档），账本 `evidence/phase0/STATUS.json`。
- **阶段 1 — 已完成**（2026-07-13 收口）：9/9 PASS（含 TestEngine 全接线、批化三签名标定、防御路径端到端触发），账本 `evidence/phase1/STATUS.json`。
- **阶段 2 — 本地可完成部分已完成**（2026-07-13）：5 PASS + 1 BLOCKED_EXTERNAL（P2-C06 E-01 TLS/H3 切换与公网 QUIC A/B，待 UDP 8443 放行 + E-06 域名/公共证书），账本 `evidence/phase2/STATUS.json`。
- **阶段 3 — 本地可完成部分已完成**（2026-07-13）：5 PASS + 1 FAIL（P3-C05 批化检测器 middlebox 误报如实入册，affects_validity=false 无实害）+ 3 BLOCKED_EXTERNAL（P3-C07 海外节点/E-04、P3-C08 QoD/E-05、P3-C09 真机规模化回流/E-02），账本 `evidence/phase3/STATUS.json`。

以下为原设计计划（保留作对照，验收判据的修订以 DECISION_LOG 与账本 note 为准，如 D-18 对 P0-C14 判据的修订）：

**阶段 0：骨架与计时联调（第 1–2 周）**
- monorepo 脚手架 + **供应链钉死**：Gradle wrapper 提交并写 `distributionSha256Sum`、依赖经 `libs.versions.toml` 全部固定精确版本（禁动态版本）、Go 提交 go.mod/go.sum；若配置 CI 必须经 `./gradlew`（wrapper）而非系统 gradle。
- Go 服务端 `/echo` + 最小 `/stream`（固定 100 token）；Android 空壳 App 跑通一次 S1 并打印全部时间戳；`scripts/verify_all` 一键验证链（编译 + 单测 + 联调 + 自动生成 sha256 清单——清单禁止手动维护）。
- 验收结果按四态（PASS/FAIL/NOT_EXECUTED/BLOCKED_EXTERNAL）写入 `evidence/phase0/STATUS.json`（规则见 evidence/README.md）；**第一条证据必须是成功主路径完整实跑**，不允许只留 fail-closed 防御分支证据（参考项目坑 1）。
- **验收**：①同一 WiFi 连测 10 次，客户端 ITL 序列与服务端发出序列对齐差 P95 <10ms，EventListener 计时点齐全；②服务端 tcpdump 抓包时戳 vs srv_ts 逐 event 对齐 P95 <2ms，**均匀流与 burst 段各验一组**（红队：Flush≠发出）；③环回跑 profile，客户端解析层自身耗时 P99 <1ms（红队：解析开销计入 ITL）；④服务端注入畸形/截断 event，客户端正确跳过并计 gap 或判 invalid，绝不静默错位（红队：seq join）；⑤U1（2xx 响应头口径）与 iperf3 同向吞吐偏差 <20%（红队：写缓冲假吞吐）；⑥人工制造一次服务端墙钟步进，验证 srv_ts 单调锚点序列化免疫（红队：NTP 步进伪卡顿）。

**阶段 1：MVP（第 3–6 周）**
- 三场景完整实现（含 seed 固定随机流 + 场景首尾双 clock_sync 与 skew 插值）、T/U/N 组 KPI 计算（含 coalesced 双口径、resume_latency 单列）、无线层打点、Room 存储与导出、结果上报双写、AQS 评分与结果页、快测/取证双模式、三态 Gate 有效性守卫全接线、批化检测（残差域、分级 buffering_score）、取证模式场景顺序拉丁方轮转。
- **验收**：①稳定 WiFi 环境连续 5 次快测，AQS 波动 ≤±5 分；②netem 注入 100ms/1% loss 后，T2/T3/T4 按预期方向显著恶化且 AQS 掉档，**同时核对注入劣化未导致样本被误判无效**（红队：弱网样本幸存者偏差）；③蜂窝下完整跑通并在结果中看到无线层曲线与路径对账记录；④导出文件可直接用 pandas 读取；⑤**防御路径端到端触发**——在 App 真实运行路径上至少各触发一次 invalid 判定（经带缓冲反代触发批化自检、测中关 WiFi 触发路径 fail-closed），证明守卫/评分逻辑已接进 TestEngine 而非只有单测（参考项目坑 1/坑 2）；⑥LiveScreen 开/关两状态同环境 ITL P95 差 <5ms（红队：工具自身干扰）；⑦release 签名就绪，上报体含 APK 版本与签名指纹。

**阶段 2：连续性 + 协议对比 + 真实 API 探针（第 6–10 周）**
- C 组实验：`ConnectivityManager.requestNetwork` 强制 WiFi↔蜂窝迁移中断流式阶段，测 C1/C2；NAT 阶梯探测 C3。
- 客户端引入 Cronet，服务端启用 quic-go HTTP/3：同 profile 背靠背 TCP vs QUIC A/B（含迁移场景）。硬性规则：逐样本记录实际协商协议（ALPN/negotiatedProtocol）落盘——**配置启用 QUIC ≠ 协商到 h3**，未协商 h3 的样本不计入 QUIC 组统计（单列 fallback），A/B 结论必须附两组协议协商证据（参考项目红队项）。
- 真实 API 探针：Anthropic / Kimi 流式接口（用户自填 key），固定 prompt 测端到端 TTFT/ITL 作对照列。
- AQS 升级至含 C 组权重版本（aqs v0.2）。

**阶段 3：规模化与网络能力对接（第 11 周起，按需）**
- 海外第二节点（跨境路径对照组）；多设备汇总看板（届时再评估独立后端）；CAMARA QoD API 试点（申请低时延 profile 前后 A/B）；GPS 打点支持路测轨迹。

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| 中间盒/运营商代理缓冲 SSE，token 批化到达 | 4.4 的批化自检判无效；非标端口 8443 直连；必要时加 UDP 对照探针 |
| 云厂商 VM 时钟/调度抖动污染服务端时间戳 | 服务端发送误差自监控（P99>5ms 标失真）；选独享型实例 |
| 厂商 ROM 省电策略杀测试进程 | 前台 Service + 屏幕常亮 + 测前守卫检查；文档记录各厂商设置项 |
| 蜂窝测试时流量走 WiFi | NetGuard 显式 `requestNetwork(CELLULAR)` 绑定 socket |
| 单节点 RTT 基线无法代表全网 | 结果只声明"至该节点路径"；阶段三多节点扩展 |
| 真实 API 探针烧钱且波动大 | 仅取证模式可选开启；固定短 prompt；结果单独归类不进 AQS |
| KPI 门限主观性引发质疑 | 全部标"实验性"；数据回流后用分布分位数重标定 |

> 以上为工程/资源类风险。**测量效度类风险**共 32 项（10 项 high）已经多代理红队对抗验证并入 §4–§8 的设计条款，完整清单与逐项验证结论见 [测量红队清单](测量红队清单.md)。

### 9.1 隐私与权限边界

与参考项目的最小权限集（仅 INTERNET + ACCESS_NETWORK_STATE、不采集小区/位置类信息）不同，本工具的 R 组无线层归因**必须**采集 PCI/小区/信号强度，因此申请 `ACCESS_FINE_LOCATION` + `READ_PHONE_STATE`——研究自用工具，被试即研究者本人，差异合理但须显式声明。承诺边界：不上传 GPS 坐标原始值、不采集 IMSI/广告 ID/用户内容；`allowBackup=false`；Release 禁明文流量。

**GPS 路测（阶段 3 已实现，P3-C06）**：路测模式以 LocationManager 1Hz 采集轨迹（无 GMS 依赖），lat/lon/accuracy 存 RadioSample 可空列，**坐标只存本机**（本地 CSV 导出）；上报体无任何坐标字段，隐私边界三重锚定——ResultReporter 无字段 + JVM 键级正则单测 + E2E 服务端落盘 grep 零命中（evidence/phase3/gps_drive_mode_20260713.log）。

## 10. 下一步（as-built，2026-07-13 更新；原阶段 0 开工清单已全部完成并落账）

本地可完成的开发与验收已全部收口（§8 as-built 状态）。当前推进全部悬于外部依赖，逐项列出依赖与解锁内容：

1. **E-02 Android 真机 + 蜂窝 SIM（4G/5G）**——解锁：蜂窝测量证据（模拟器只构成功能/fail-closed 证据）、无线层 R 组真实数据与批化检测器 AIRLINK 分支、C2/C3 的真机蜂窝口径、GPS 真机路测采集，以及 **P3-C09 规模化数据回流**（门限重标定、检测器重加权、AQS 权重迭代的数据前提——看板与分析链已就绪待数据）。
2. **E-06 域名 + 公共 CA 证书（Let's Encrypt，绑定 E-01）+ 用户在阿里云控制台放行 UDP 8443**——解锁：**P2-C06** E-01 TLS/H3 协同切换（D-19）与公网 Cronet QUIC A/B 实采（D-21：Cronet QUIC 强制公共已知根，自签不可行，B 组 h3 样本现为 0）。
3. **E-04 海外第二节点**——解锁：**P3-C07** 真实跨境路径对照（netem 本地替代已完成，P3-C04）。
4. **E-05 CAMARA QoD 试点（运营商合作）**——解锁：**P3-C08** 低时延 profile 前后 A/B；无本地替代。
5. **E-03 真实 LLM API key**——解锁：P2-C04 探针的真实端点实测对照列（探针机制已完成，mock E2E 通过）。

内部待办（不依赖外部）：**P3-C05 修复**——BufferingDetector 引入 TCP_INFO retrans 共变量区分重传批化与中间盒缓冲（并行分支实现中，见 §6）；autocorr 分量重加权与判无效阈值定版合并等待 E-02 真机数据。

制度延续：[DECISION_LOG](DECISION_LOG.md) 决策 D-xx 追加不覆盖；外部依赖缺位的检查一律记 `BLOCKED_EXTERNAL`，绝不折算成 PASS。

## 11. 阶段3+：VpnService 流量观测（设计，未实施）

> 状态：**仅设计，未实施**（决策 [D-24](DECISION_LOG.md)）。本节把"要不要建、怎么分期"想清楚，落为可审计的设计基线；本轮不写任何代码。诚实标注**能做与不能做**是本节第一原则——涉及加密流的一切结论一律为**流级推断（近似）**，与仿真节点/LLM API 探针的精确口径分开。

### 11.1 动机与定位：填补"封闭 App 真实会话体验"这一格

ANEB 要测"手机端常用 AI App 的真实网络体验"。但豆包/Kimi 客户端、飞书智能伙伴、蚂蚁阿福等**封闭消费级 App** 既无公开流式 chat-completions API（见 `ProviderPresets.kt` 的"明确排除"清单），也拿不到内部 token 流——**API 探针测的是"研究者用官方 API"、不是"用户用官方 App"**，干净路径仿真更测不到真实 App 的加密流。

VpnService 流量观测的定位：ANEB 作**本地 VpnService**，路由用户所选 App 的流量，**从加密流的时序（不解密内容）**推断真实会话的网络体验。这是三种 App 测法里最重、最敏感的一种，因此先出设计再决定建不建。

### 11.2 架构与工作量级（全项目最大的一块）

```
用户选定 App(豆包/Kimi 客户端…) ──产生流量──┐
                                          ▼
   ┌──────────────────────────────────────────────────────┐
   │ ANEB VpnService（本地）                                │
   │  ① TUN 虚接口：系统把选定 App 的 IP 包投递到 tun fd    │
   │  ② per-UID 过滤：PackageManager 取目标 App 的 UID，     │
   │     ConnectivityManager.getConnectionOwnerUid() 逐流认领 │
   │     （Android 10+ allowed/disallowed application 白名单）│
   │  ③ 用户态协议栈：解析 IP/TCP/UDP，做流重组             │
   │     （TUN 只给 IP 层，必须自建 TCP 状态机或走 tun2socks）│
   │  ④ 转发：把重组后的流量经保护 socket 再发往真实目的地    │
   │     （VpnService.protect() 防自环），双向搬运字节         │
   │  ⑤ 逐包时序记录：SYN/SYN-ACK、ClientHello、DNS、        │
   │     上/下行包到达时刻与字节量 → 只记元数据时序          │
   └──────────────────────────────────────────────────────┘
                                          │ 观测元数据（无 payload）
                                          ▼
                        本机 Room（新表，只存本机，不上报）
```

**工作量级诚实评估**：这是**全项目工程量最大的一块**——TUN 建立 + IP/TCP/UDP 包解析 + 用户态 TCP 流重组 + 双向转发（tun2socks 思路）+ per-UID 认领 + 逐包时序打点，量级**远超**现有 OkHttp/EventListener + 自实现 SSE 的测量栈。用户态 TCP 转发若自研，需实现拥塞/重传/窗口语义；若引用成熟 tun2socks，则引入新的原生依赖与供应链纪律成本（与 §6"标准库 + quic-go 专项"原则需再决策）。这是"建不建"决策必须先算清的成本项。

### 11.3 加密流能推断什么 vs 不能——边界钉死

不解密内容、只看时序与包头明文，能拿到的与拿不到的**必须分清并逐条标注"流级推断，近似"**：

| 观测量 | 能/不能 | 从哪来（不解密） | 口径与近似度 |
|---|---|---|---|
| 连接建立 RTT | ✅ 能 | SYN → SYN-ACK 时间差 | 连接层，**确定可靠**（非近似）；与 N1 语义相近但对象是真实 App 目的地 |
| TLS 握手成功/失败 | ✅ 能 | ClientHello 发出 → 是否收到 RST/完成握手 | 连接层，**确定可靠**；**中途双向 RST 即 SNI-RST 类**，与 [R-33](测量红队清单.md)/[D-22](DECISION_LOG.md) 同源、复用同一 ClientHello 观测面 |
| SNI 主机名 | ✅ 能 | ClientHello 明文 SNI 字段 | 明文可读（除非 ECH）；用于按目的地分组、识别 App 连了哪些域名 |
| DNS 查询时延 | ✅ 能 | DNS 请求 → 响应时间差（明文 DNS）| 确定可靠；DoH/DoT 下退化为对 DoH 服务器的连接层观测 |
| 上行请求突发 | ✅ 能 | 上行方向包的字节量与时刻 | 字节量/时刻确定；**"这是一次 prompt 提交"是流级推断** |
| 下行响应节奏 | ⚠️ 近似 | 请求突发 → 首个响应包（TTFT 代理）；下行包间隔停顿（ITL/stall 代理）| **流级近似**：首字节延迟≈TTFT、下行包间隔停顿≈卡顿，但受 TLS 合帧/分片影响 |
| 字节总量 | ✅ 能 | 双向字节计数 | 确定可靠 |
| **内容 / 精确 token 边界 / token vs 控制帧** | ❌ 不能 | —— | TLS record 合帧/分片使"逐 token"**只能是流级近似**、非应用层精确；无法区分一个 record 里是 token 还是控制帧；**绝不解密** |

**核心诚实声明**：下行"逐 token 节奏"在本模式下**永远是流级近似**——一个 TLS record 可能合并多个 token 或拆分一个 token，ANEB 只看到密文 record 的到达节奏，不是应用层 token 边界。故 TTFT/ITL/stall 在此模式下均为**代理量（proxy）**，与仿真节点/SSE 的精确 T1–T4 **不可同口径互比**。

### 11.4 隐私与同意模型

严格对齐 §9.1 的隐私纪律，并因 VpnService 的敏感性再收紧：

- **只观测本机**：用户自己设备、用户**显式选定**的 App（白名单，未选的 App 不路由不观测）。
- **不解密内容**：不装证书、不 MITM、不看 payload。
- **不留存 payload**：只留**元数据时序**（包头字段、到达时刻、字节量），密文正文搬运即弃、绝不落盘。
- **数据只存本机**：新观测表只入本地 Room、**不进上报体**（呼应 §9.1"GPS 坐标只存本机"的三重锚定：Reporter 无字段 + 键级正则单测 + E2E 落盘零命中）。
- **系统强制可见性**：VpnService 会由 Android 强制显示**持续通知 + 状态栏 VPN 图标**（不可隐藏），用户始终知道观测在进行。
- **首次知情同意界面**：明确说明**抓什么**（连接时序/握手/SNI/DNS/字节量）、**不抓什么**（内容/账号/token 明文）、**仅在观测期**、**随时可停**（一键停止 + 撤销 VpnService 授权）。

### 11.5 模式互斥与状态机（与 D-16/R-03 的关系）

ANEB-as-VPN 是**独立模式**，与 ANEB 的**干净路径测量（仿真节点/API 探针）互斥**：

- **为什么互斥**：VpnService 的用户态转发天然引入批化、并把所有流量套进本地隧道——这**正是 [R-03](测量红队清单.md)（本地 VPN 批化被误记成运营商中间盒证据）与 [D-16](DECISION_LOG.md)（本机测量必须绕开代理并检测代理存在）判定为"污染路径、不可作运营商证据"的场景**。因此 VPN 开启期间，干净路径测量（S1/S2/S3、探针、C 组）**必须禁用**；反之，干净路径测量期间 VPN 观测必须关。
- **守卫协同**：干净路径测量的 NetGuard 硬拒测（存在 `TRANSPORT_VPN` 即 fail-closed，§4 第 7 条 / R-03）**保持不变**——它恰好保证了"误开 VPN 时干净路径测量不会出数"。VPN 观测模式是**显式的、另一条互斥通路**，不是绕过该守卫。

**模式状态机（顶层互斥）：**

```
        ┌────────────┐  用户选"干净路径测量"   ┌──────────────────────┐
        │  IDLE      │ ──────────────────────► │ CLEAN_PATH_MEASURING  │
        │（未测量）  │ ◄────────────────────── │（NetGuard 硬拒 VPN）   │
        └────┬───────┘        测量结束          └──────────────────────┘
             │ 用户选"VPN 流量观测"+ 同意 + 选 App
             ▼
   ┌───────────────────────────┐  停止/撤销授权   （二者不可同时 active，
   │ VPN_OBSERVING             │ ───────────────►  互斥由状态机保证：进入任一
   │（VpnService active，       │                   模式前先确认另一模式已 IDLE）
   │  干净路径测量被禁用）      │
   └───────────────────────────┘
```

### 11.6 claim scope

新 claim_scope：**`application_flow_observation_no_decrypt`**，写入该模式产物的 schema 并 const 锁定，与既有两档明确分开：

| 模式 | claim_scope | 精度 | 进 AQS |
|---|---|---|---|
| 仿真节点端到端 | `application_end_to_end_to_probe_node` | 应用层精确 | 是（aqs v0.1/v0.2）|
| LLM API 探针 | `application_end_to_end_to_llm_api` | 应用层精确（逐 token）| 否 |
| **VPN 流量观测** | **`application_flow_observation_no_decrypt`** | **流级推断（近似）** | **否** |

VPN 观测结论一律标注"流级推断"，**不进 AQS**（近似量不与精确量混入同一综合分），与 REACH（候选、亦不进 AQS）并列作观测维度。

### 11.7 正当性边界

- **是**：自有设备 + 用户自选 App + 不解密 + 只存本机的**授权网络性能观测**。观测对象是用户自己 App 的加密流时序，用于量化真实会话的网络体验（连接建立、握手、节奏）。
- **不是 / 明确不做**：**非中间人**（不解密 TLS、不注入证书、不 MITM）、**非抓他人流量**（只本机、只选定 App）、**非规避**（bare-IP/SNI 观测与 R-33/D-22 一致，目的是量化中间盒干预维度而非绕过审查）。
- 与 R-33/D-22 的正当性表述一脉相承：SNI-RST 等连接层干预是**可量化的测量维度**，本模式把它从"看不见的封闭 App 黑箱"转成"可观测的流级证据"，不越界到内容。

### 11.8 三档 App 测法覆盖对比表

VPN 观测填补的正是"**真实封闭 App 会话体验**"这一格——另两档都够不到：

| 维度 | ① REACH 可达性（已规划，候选指标 5.5）| ② API 探针（已有，阶段 2）| ③ VPN 流量观测（本节，设计未实施）|
|---|---|---|---|
| 测什么 | 连接建立可达性 / TLS 握手成功率 / SNI-RST | 端到端 TTFT/ITL/字节（逐 token SSE）| 真实 App 加密流时序：SYN-RTT / 握手 / SNI / DNS / 下行节奏 |
| 精度 | 连接层**精确** | 应用层**精确**（逐 token）| **流级近似**（下行节奏为代理量）|
| 前提 | 无（对任意目的地发起握手）| 该服务有**公开流式 API** + 用户自填 key | 用户装 VPN + 选定 App + 知情同意 |
| 覆盖对象 | 任意目的地的连接层 | 有开放 API 的 AI 服务（豆包/Kimi/DeepSeek 等 7 家 verified）| **封闭 App 真实会话**（飞书/阿福/豆包客户端…）|
| 测不到 | 不测流内节奏（TTFT/ITL）| **封闭 App**（无 API 即无对象）| 内容 / 精确 token 边界 / token vs 控制帧 |
| claim_scope | `application_reachability_to_probe_node` | `application_end_to_end_to_llm_api` | `application_flow_observation_no_decrypt` |
| 进 AQS | 否（候选）| 否 | 否 |

一句话：**API 探针测"有开放 API 的服务"、REACH 测"连接层"，唯有 VPN 观测能看到"用户用封闭 App 的真实加密会话"的网络节奏**——但代价是流级近似 + 最大工程量 + 最敏感权限。

### 11.9 建 or 不建的建议 + 分期

**推荐：本轮先出此设计文档，暂不实施。** 理由——工程量为全项目之最（§11.2）、下行节奏为流级近似需先验证（§11.3）、权限最敏感需最强同意模型（§11.4）；在现有仿真节点/探针/REACH 三条精确通路尚有真机数据待回流（E-02）时，不宜先投最重最不确定的一块。

**若将来建，分两期：**

- **一期（MVP）——只做连接层确定可靠量**：SYN-RTT + TLS 握手成功率/SNI-RST + DNS 时延 + 字节量。这些**无需解密即确定可靠**，且与 REACH（5.5）/R-33 口径自然衔接——等于把 REACH 从"我方服务器"扩展到"用户封闭 App 的真实目的地"，价值确定、近似风险低。
- **二期——下行节奏 → TTFT/ITL 推断（近似量，需先验证再启用）**：因 TLS 合帧/分片使流级节奏与应用层 token 边界存在系统性偏差，**上线前必须先做交叉验证**：用一个**同时有开放 API 又有封闭 App 的服务**（如 Kimi / DeepSeek——探针已知逐 token 真值），同机同网下同时跑 API 探针（真值）与 VPN 观测（流级代理），标定"流级 TTFT/ITL 代理 vs 真值"的偏差分布；偏差可接受才把代理量作为封闭 App 的 TTFT/ITL 结论输出，且永远带"流级近似"标注、不进 AQS。

**可选最小 spike（本轮不实施，仅备将来验证可行性用）**：写一个最小 VpnService，只对**单个用户选定 App** 建 TUN + per-UID 过滤 + **纯转发（不重组不推断）**，验证三件事——(a) per-UID 路由能干净隔离目标 App、(b) `protect()` 转发不自环且 App 功能不受损、(c) 能从密文流里稳定识别 SYN/SYN-ACK/ClientHello/DNS 四类事件的时刻。这三点通过，才谈得上一期 MVP；下行节奏推断（二期）在 spike 阶段**不碰**。
