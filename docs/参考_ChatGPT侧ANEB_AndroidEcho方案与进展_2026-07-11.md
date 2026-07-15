# 参考：ChatGPT 侧 ANEB（Android Application Echo）方案与进展

> 来源：`E:\G Project\ANEB`（ChatGPT/Codex 设计开发）｜快照日期 2026-07-11
> 用途：供本仓库（ANEB Probe — Agent Network Experience Benchmark，"20260711_ANEB测试方案"）参考。两个项目同属 ANEB 研究计划、并行开发，本文总结对方的设计决策、当前进展、工程纪律与教训。
> 本文档为只读参考，不构成本仓库的需求或约束。

---

## 1. 两个项目的关系与差异

| 维度 | ChatGPT 侧（E:\G Project\ANEB） | 本仓库（E:\C Project\ANEB） |
|---|---|---|
| 定位 | Android 原生 **Application Echo RTT** 测量垂直切片，走正式里程碑（M1→M2 Measurement Validated） | **Agent-QoE** 研究/取证工具（TTFT、token 间卡顿、上行突发、无线层快照、AQS 评分） |
| 指标 | 单一指标 `app_echo_rtt_ms`（p90，claim scope = `application_end_to_end`） | T/U/R 组多维 KPI + AQS 0–100 综合分 |
| 传输栈 | Embedded Cronet 143.7445.0（HTTP/2 + QUIC，逐请求 `bindToNetwork`） | OkHttp（EventListener 精细计时），阶段二经 quic-go 提供 H3 |
| 对端 | ANEB Probe 节点（HTTPS echo，节点证据响应头），本地开发用 Python mock probe（HTTP/1.1） | 自建 Go 仿真服务器（/echo /stream /upload /toolloop /results） |
| 明确排除 | 评分、TTFA、Streaming、上传下载基准、Resume、UDP、遥测 SDK（范围冻结至 M2 完成） | 正是要做流式/上行/连续性——两个项目互补而非重复 |
| 状态 | M2-A01..A03 PASS，A04 本地部分通过，A05/A06 外部阻塞，**M2 = NO** | 设计基线 v0.1，阶段 0 骨架待建 |

简言之：对方项目把「一个指标」做到了极端严谨（测量语义、证据链、声明边界），本仓库要做「多维体验指标」。对方的**测量纪律和工程制度**是本仓库最有借鉴价值的部分；对方明确不做的流式/上行维度正是本仓库的主战场。

## 2. 对方方案概要

**架构**：单 Activity Kotlin App（minSdk 26 / target 36）→ Embedded Cronet（H2+QUIC+Brotli、禁缓存）→ 对指定 Probe 端点执行 3 次 warmup + 30 次正式 Echo 采样 → 纯 Kotlin `measurement-core` 模块做统计（最近秩百分位）与三态质量 Gate → 输出 Test Result v2 JSON（schema 固定 `2.0.0`）经 ACTION_SEND 分享。

**核心测量语义（已冻结，全部落地在代码里）**：

1. 外部单调时钟（`SystemClock.elapsedRealtimeNanos()`）包住**完整有限响应体**——从发起请求到 body 读完才结束计时；
2. 3 次 warmup 全部丢弃，统计只来自 30 个正式样本；
3. 失败/超时样本 RTT 记 `null`，**绝不记 0**；`successful` 要求 2xx + 无错误码 + rtt 非空；
4. 质量 Gate 三态：`valid` / `valid_low_confidence` / `invalid`；invalid 时**抑制指标输出**（metrics 数组为空），阈值：30 样本、≥20 成功、成功率 ≥90%、节点质量 ≥0.90、调度误差 ≤50ms；
5. **QUIC 已启用 ≠ 协商了 HTTP/3**——只记录 Cronet 实际 `negotiatedProtocol`；
6. 逐请求 `Network` 绑定（`ExperimentalUrlRequest.Builder.bindToNetwork`），禁止进程级绑定；绑定不支持直接失败（fail-closed）；
7. 路径完整性 fail-closed：采样期间网络路径变化/丢失/未验证/监控失败 → 立即中止采样、取消 in-flight 请求、抑制指标（"首事件获胜"状态机）；
8. 节点证据缺失 = 显式低置信度，不是隐式健康；
9. 声明边界：指标只能叫 `app_echo_rtt_ms` / `application_end_to_end`，禁止称为 radio RTT、IP RTT、RAN 时延、丢包、MOS 或 SLA 认证。

**隐私边界**：仅 `INTERNET` + `ACCESS_NETWORK_STATE` 权限，不收集 GPS/SSID/BSSID/Cell ID/IMSI/广告 ID/用户内容，`allowBackup=false`，Release 禁明文。

**关键架构决策（ADR-001）**：选 Embedded Cronet 而非 Play Services Cronet，理由是非 GMS 设备覆盖；代价是 APK 增大（Debug 27MB）和公开分发前的 ABI/许可证义务。

## 3. 里程碑与当前进展（2026-07-11）

| 工作包 | 内容 | 状态 |
|---|---|---|
| M1（ANEB 1.2.0rc1） | 协议仿真基线，79/79 测试 | ✅ 前序完成 |
| M2-A01 | Android 工具链 / 可复现构建 / Lint | ✅ PASS |
| M2-A02 | 合同与测试加固（10 个 JVM 测试 + schema 校验） | ✅ PASS |
| M2-A03 | 设备证据采集脚本框架 | ✅ PASS |
| M2-A04 | 物理网络绑定 + 真实 h2/h3 证据 | ⚠️ 本地 fail-closed 通过；物理部分外部阻塞 |
| M2-A05 | 设备矩阵（Pixel/Samsung/Xiaomi-Redmi/OPPO/vivo × 2 个 Android 大版本 × Wi-Fi/LTE/5G，同条件 median CV≤8%） | ⛔ BLOCKED_EXTERNAL |
| M2-A06 | 双 Probe 节点校准（偏差≤10%）+ 授权 CAP_NET_ADMIN 实验室双向 netem | ⛔ BLOCKED_EXTERNAL |

**官方判定 `M2 Measurement Validated = false`**（对方状态文件如实标注，未夸大）。

已可证明的产物：Debug APK 已构建并 v2 签名（sha256 `d6b6f18e…`）；measurement-core JVM 测试 10/10；Lint 0 错误 30 警告；API 36 模拟器 instrumentation 2/2 通过（但只命中 fail-closed 分支——模拟器网络无 `NET_CAPABILITY_VALIDATED`，0 样本、无指标，属兼容性证据而非测量证据）。

下一阶段规划 M2-V01..V08：V01 可复现 Release + SBOM/许可证（本地可做）；V02 真机安装/生命周期；V03 公网 h2/h3 Probe 与客户端-服务端对账；V04 物理 Wi-Fi/蜂窝绑定（服务端源路径证据强制——仅客户端 network handle 不足以证明绑定）；V05 设备矩阵；V06 多地域（EU/US/Asia）Probe；V07 双向 netem/IFB；V08 最终 M2 决策报告。V02–V07 全部依赖外部资源，且卡在 5 项 Product Owner 未决策（设备族、版本矩阵、Probe 区域、实验室伙伴、公测传输治理）。

## 4. 对本仓库最有借鉴价值的制度设计

1. **四态证据制度**：任何检查只允许 `PASS / FAIL / NOT_EXECUTED / BLOCKED_EXTERNAL` 四种状态；每个 PASS 必须有命令 + 原始输出 + 产物落盘（`evidence/` 目录 + SHA-256 清单）。缺 SDK/缺设备/缺端点绝不折算成 PASS。→ 本仓库从阶段 0 起就建 `evidence/` 目录成本很低，事后补很贵。
2. **声明边界（claim boundary）前置**：指标名和 claim scope 写进 JSON schema 用 `const` 锁死，从合同层杜绝"测的是应用层却宣传成网络层"。→ 本仓库的 TTFT/卡顿/AQS 同样面临"App 层测量 vs 网络归因"的边界问题，建议在 profiles/KPI 文档里同样显式声明每个指标的 claim scope（尤其 AQS 综合分对外表述时）。
3. **fail-closed 优于 fail-open**：路径污染、绑定不支持、监控注册失败——一切不确定都导向"不出指标"，而不是出一个可能失真的指标。→ 对研究/取证工具，一条被污染的数据比缺一条数据危害大得多。
4. **红队闭环**：对方在写代码前列了 14 项"测量会怎么骗人"的设计风险并逐项闭环（QUIC 误报 h3、墙钟跳变、warmup 污染统计、失败样本记 0、缺失节点证据默认健康、超时死锁、进程级绑定副作用等）。→ 本仓库的 SSE 流式测量同样值得做一轮：如 TTFT 里混入 DNS/TLS 握手是否归因清楚、token 间隔受 Nagle/缓冲影响、服务端时间戳与客户端时钟对齐、后台 Doze 对长会话的影响等。
5. **决策日志 + PO 决策分离**：工程决策（DECISION_LOG）与留给产品负责人的决策（PO-01..05）分列，避免工程侧越权拍板设备清单/区域/许可证。
6. **供应链纪律**：Gradle wrapper 带 SHA-256 溯源、依赖版本全部钉死、Cronet 升级需专项变更+回归证据。→ 本仓库 Kotlin/OkHttp/Go 依赖同样建议第一天就 pin。

## 5. 对方踩过的坑（本仓库应避免）

以下问题是 2026-07-11 深度审计交叉核对确认的，对新项目是现成的避坑清单：

1. **测量成功主路径从未实跑**：instrumentation 测试通过 ≠ 主路径验证过——对方模拟器实跑只命中了 fail-closed 分支（0 样本），"30 样本 → VALID"主干从未有运行时证据。→ 本仓库阶段 0 联调时，确保**成功路径**在模拟器/真机上至少完整跑通一次并留证据，不要只测防御分支。
2. **core 能力与 app 接线断层**：对方 `NodeEvidenceAccumulator`（节点身份冲突检测）在核心模块实现且有单测，但 app 运行路径没接，真机上节点冲突不会触发 invalid。→ 纯逻辑模块与 App 集成之间要有端到端测试兜底。
3. **`**/build/` 排除规则误伤证据目录**：对方 `.gitignore` 和 SHA-256 清单的 `**/build/` 规则把 `evidence/build/`（最关键的构建证据）整体排除在版本控制和完整性保护之外。→ 命名证据目录时避开 `build`，或写精确排除规则。
4. **证据清单不自动化就会过期**：对方 sha256-manifest 在交付时点就已漂移 13 个文件；状态文件里还有一条 PASS 找不到对应工件。→ 清单生成脚本化并挂进验证链，别靠手动。
5. **Release 签名要早规划**：对方 release buildType 无任何 signingConfig，`assembleRelease` 只能出未签名 APK，成为里程碑收口的具体缺口。
6. **CI 要用 wrapper**：对方 GitHub Actions 用了 `gradle` 而非 `./gradlew`，绕过了自己强调的 wrapper 供应链校验，且仓库无 remote，CI 从未运行。
7. **失败日志要保原始输出**：对方一次 verify 失败的日志被 PowerShell 重定向成 UTF-16 且截断，根因只能靠文件名推断，削弱失败-修复链的可审计性。（Windows 下 `Out-File` 记得 `-Encoding utf8`。）

## 6. 可直接参考/复用的资产（路径均在 E:\G Project\ANEB\aneb-android-echo-v0.1.0\）

| 资产 | 路径 | 对本仓库的参考点 |
|---|---|---|
| 质量 Gate + 统计（纯 Kotlin，无 Android 依赖） | `measurement-core/src/main/kotlin/com/aneb/core/`（QualityGate.kt、Statistics.kt、NetworkPathTracker.kt） | 三态 Gate、最近秩百分位、路径污染状态机可平移到本仓库 KPI 计算层 |
| 结果合同 schema | `contracts/test-result-v2.schema.json` + `contracts/README.md`（7 条测量规则） | `const` 锁定指标语义的做法；本仓库 JSONL 结果落盘可参考 |
| 逐请求网络绑定 + 单调计时 | `app/src/main/kotlin/com/aneb/echo/CronetEchoClient.kt`、`NetworkSelector.kt` | 即使本仓库用 OkHttp，`NET_CAPABILITY_VALIDATED` 筛网、路径监控回调、fail-closed 语义可照搬 |
| 红队闭环记录 | `docs/RED_TEAM_CLOSURE.md` | 14 项测量失真风险清单，做本仓库流式测量红队的起点 |
| 设备测试计划 / 实验室要求 | `docs/M2_DEVICE_TEST_PLAN.md`、`docs/M2_LAB_REQUIREMENTS.md` | 设备矩阵、CV≤8% 复现性判据、节点独立性判定（同 CDN 边缘不算独立）——本仓库外场测试可直接借用框架 |
| 验证链脚本 | `scripts/verify_all.sh` 等 | 串联合同/静态/单测/mock 对端的一键验证思路 |
| 交接制度 | `CLAUDE.md`、`handoff/HANDOFF_STATUS.json`、`docs/KNOWN_GAPS.md`、`handoff/DECISION_LOG.md` | 状态机式交接、缺口显式编号（G-xx）、决策留痕格式 |

## 7. 潜在协同点

- **对端节点复用**：对方 M2-V03/V06 要部署公网 h2/h3 Probe 节点（HTTPS + UDP443），本仓库阶段二也要海外节点对照——可考虑共用 VM/节点预算与域名规划（对方留给 PO 的 PO-03 决策）。
- **Echo 指标互校**：对方的 `app_echo_rtt_ms`（Cronet）与本仓库 `/echo` RTT（OkHttp）在同设备同网络下可互为对照组，帮助分离传输栈差异与网络差异。
- **命名冲突提示**：两个仓库都叫 ANEB 但展开不同（对方未展开缩写、本仓库为 Agent Network Experience Benchmark），对外文档引用时注意区分「ANEB Android Echo 切片」与「ANEB Probe」。
