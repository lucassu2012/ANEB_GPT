# ANEB — Agent Network Experience Benchmark

研究智能体互联网时代移动通信网络新型性能与体验诉求，并提供配套测量工具 **ANEB Probe**。

## 仓库结构

- `docs/` — 研究文档
  - 《智能体互联网时代（Agentic Internet）移动通信网络的新型网络性能与体验诉求》：诉求分析 + Agent-QoE KPI 体系（agent-qoe-kpi v0.2.1：指标/门限/测量方法/声明边界 + 首轮实测锚点修注，第五部分）
  - 《ANEB Probe 开发设计文档》（v0.3，as-built）：测试工具的架构、技术选型、分阶段实现计划与实际完成状态、红队修订
  - 《测量红队清单》：32 项经多代理对抗验证的测量失真风险与闭环计划（10 项 high）
  - 《DECISION_LOG》：决策日志（D-xx）、否决记录、外部依赖清单（E-xx）
  - 《参考_ChatGPT侧ANEB_AndroidEcho方案与进展》：并行姊妹项目制度借鉴（只读参考）
- `profiles/` — Profile Contract v2（客户端/服务端共享，发布即冻结，改动须升版本）
  - `token_multimodal_quick/standard/stress`：多模态 Token 行为仿真与 100MiB 大对象压力测试
  - `ai_realtime_voice_quick/standard/recovery`：20ms 双向帧 AI 实时交互仿真与受控恢复
  - `network_comprehensive_quick/standard/weak_capacity_latency/weak_recovery/gateway_loss/gateway_recovery`：容量、loaded RTT、稳定性、UDP 应用探针、隔离式合成弱网以及专用网关网络层实验
- `evidence/` — 验收证据目录（四态证据制，规则见其 README）
- `app/` — Android 客户端（Kotlin，minSdk 29；Compose + OkHttp/Cronet + Room）
- `server/` — Go 仿真服务器（SSE token 发生器 / 上行汇 / 结果落盘；标准库 + quic-go 专项，E-01 已部署）
- `gateway/` — 专用 Linux IP 转发弱网网关（netem + IFB、TLS 控制面、白名单 Profile、自动清理与审计；不得部署到共享 E-01）

**命名消歧**：本项目对外称 **ANEB Probe**；并行姊妹项目（Application Echo RTT 垂直切片）称 **ANEB Android Echo 切片**，两者同属 ANEB 研究计划、范围互补。

## 当前状态（2026-07-27，三族 Quick 正负 READY / E-01 0.8.2）

- Android 客户端已按 `ANEB_UI` 重构，并提供“网络综合 / Token 仿真 / AI 实时”三类正式测试、SpeedTest 风格动态仪表、独立评分与结论、统一历史、真实 GPS 地图和新版图标。
- App 只在自建 ANEB 节点上模拟 AI 应用行为，不调用 Kimi、DeepSeek、千问等真实 API；行为模型在 `tools/aneb-ai-behavior-model/` 独立生成可审计运行计划。
- P3 行为模型已升级到 0.3.2：授权派生统计、主体隔离训练/留出、固定误差门限、候选/报告/数据摘要绑定和 validated 发布复算均已成为硬门。仓库仍没有真实授权数据，4 个模型保持 hypothesis，不能声称厂商画像已校准。
- 正式 App 已删除真实付费 API 探针导航、Key 存储和自动化入口；仅 Debug 变体保留受 `android.permission.DUMP` 保护、无 intent-filter、一次一任务的 ADB 诊断组件，Release 合并清单由质量门自动验证不含该组件。
- AI 实时测试已收口取消/落库一致性：结果先持久化再发布，取消与最终提交碰撞时以持久结果为准；每会话 loaded RTT 监控必定回收，固定 Wi-Fi/蜂窝失效后只重获同一承载、不静默降级。权限弹窗与待执行动作可跨 Activity/进程重建恢复，ADB autorun 仅 Debug 首次创建消费一次。
- M0 已建立 `spec/` 逻辑根和机器目录：索引 8 个 Schema、2 个 Profile 家族、16 个 Profile 与 7 个哈希绑定运行包。结果合同分为兼容 v1、严格 v2 与共享核心；Room v19 为 Token、AI 实时和网络综合新 run 同事务保存类型化行、统一结果信封、1Hz 无线样本与环境事件，三类结果页均可保存/分享原样 JSONL。R01 通过证据引用表达时间序列，权限拒绝/不可用/未采集保持显式状态，共享信封不含位置。
- M0-EC1 把 `token_multimodal_quick@1.2.1` 接到 P1、P2 0.8.0 与 P3 0.3.1 的同一执行合同：APK 在首个 echo/Token/download 业务请求前核对本地 manifest、精确 Profile 摘要和节点能力回执，不兼容时 fail closed 并抑制评分；Quick 运行计划真实执行模型派生的 1MiB 返回附件。正向 source `10927c1` / CI `30124854408` / run `019f95f9-a317-7766-9725-243b9660b9f1` 已冻结 20 echo、3 token-sim、1 download 与客户端结果；负向 source `67eb66d` / CI `30162011890` / run `019f99c7-5b40-75ba-ad58-b5b522e9abf9` 已冻结 `receipt_missing`、业务入口与客户端任务/KPI/评分产物为零。两个 READY 均由独立消费者复核并完成 P40/E-01 清理，详见 `docs/M0_TOKEN_QUICK_READY_VALIDATION_2026-07-25.md`。正负使用不同 CI APK，只支持各自合同闭环，不是严格同二进制性能 A/B；服务端 request-entry coverage 仍必须与同 run 客户端冻结结果结合。
- M0-EC2 AI 实时 Quick 与 M0-EC3 Network Quick 已继续完成各自正负 READY。EC3 最终正负严格使用同一 source `ea9de17c` / CI `30266912724` / APK SHA-256 `e1af670c…db0e` / signer `0936cdcf…f1f3` / server 0.8.2；正向 run `019fa3aa-c0d5-7586-b736-ae2fe0a35c78` 完成四原语，负向 run `019fa3a7-b34a-7a0c-b45f-3e6e3d7b0d8c` 在 `receipt_missing` 时两端业务为零，两个独立 consumer 均 PASS。正向 `77/B` 仍为 `INCONCLUSIVE/LOW`，不冒充正式网络质量基线，详见 `docs/M0_EC3_NETWORK_QUICK_READY_VALIDATION_2026-07-27.md`。
- E-01 当前已部署 `aneb-server/0.8.2`：live binary SHA-256=`62ff966bf396abe836c6179053ee549110e41e16af569cdeadc97535bc64c96e`，PID `1295423`，InvocationID `d975f7c374aa4ef3a490210d0a495e53`。能力回执、三族 Quick、既有端点、合成弱网、upload 与 ANEB2 UDP smoke 通过，锁内验后确认共享主机指纹不变、临时残留为 0、flock 已释放。权威能力与部署纪律见 `docs/TEST_SERVER_CAPABILITIES.md`。
- 2026-07-19 起，`SHARED_TEST_STATUS.md`、lease、待交接与自动 `Verifier` 仅保留为历史机制，不再提供 P40/E-01 操作授权。P40 测试以实时现场为准：启动前只读确认设备在线、Huawei Launcher 前台且无冲突 App/服务/VPN/tun；现场干净即可直接测试，来源不明的会话不得擅自停止。结束后必须停止本轮全部 App、VPN、抓包和临时网络规则，恢复临时设置、返回 Launcher 并立即复核。E-01/阿里云变更仍单独执行受保护预检、远端 `flock`、受限变更、原子回滚与验后检查。
- `aneb-gateway/0.2.0` 已补齐固定 Debug CA、每次启动证书链核验、严格双网口/全主路由预检、持久 qdisc 所有权、可重试清理锁闩，以及一键安装/失败回滚/安全卸载；App 0.5.1 Debug 以一次性句柄交接 Token，并对启动/清理的回包不确定状态做同 run 对账。早期隔离命名空间已验证双向时延、100% IP 中断、自动恢复和宿主零影响；最终固定 CA 正向生命周期仍需离线 CA 签发的现场叶证书，P40 网络层验收还需独占双网口 Linux/AP，均明确为 `BLOCKED_EXTERNAL`，见 `docs/DEDICATED_GATEWAY_PLAN_AND_VALIDATION_2026-07-17.md`。
- Token、AI 实时和网络综合均已在 P40 Pro 完成端到端验收；Token Stress 完成 100MiB 上下行，AI 实时 Standard/Recovery 已完成长测。网络合成恢复完成 4 次独立 run：恢复 2084.4–2227.3ms、恢复后请求成功率均为 100%，并捕获真实动态恢复计时与中断指针；Room 已到 v17。
- 0.5.7 Android 验证：531 JVM tests、0 failures/0 skipped；Android Lint 0 errors（11 项依赖/SDK/API 版本提示）；Debug APK 包名 `com.aneb.probe.codex`、versionCode 39，SHA-256 为 `d276d7c52f3549e52194b9e90c5c45ebb8969fd441fb09da9154b7302a6bff33`。全仓质量门还覆盖 TTFT 重复性分析器 5 tests、P3 行为模型 31 tests、Go 服务端/网关 tests 与 Debug/Release API 入口边界。
- App 0.5.2 已在 P40 Pro 完成独立 Token Quick 验收，run `019f70ed-ed0a-7897-b019-eff5a9a26dda`：Room v19 同 run 各有 1 条类型化结果与统一信封，Draft 2020-12 校验错误 0、规范化摘要匹配，98.4/A 但按覆盖率 0.15 保持 `LOW/INCONCLUSIVE`；无线字段诚实记录 `not_collected/null`。验证结束后已返回桌面并确认 Codex/Claude 两包均无 PID 或服务，详见 `docs/P40_APP_0.5.2_RESULT_V1_VALIDATION_2026-07-18.md`。
- App 0.5.3 已在 P40 Pro 完成 AI 实时与网络综合 Quick 统一信封、JSONL 保存及系统分享验收：两个 run 的类型化行/信封一一对应，Schema 错误 0、规范化摘要匹配；AI 实时 21 项、网络综合 13 项 Profile 指标均完整出现，未测项显式 `missing`。详见 `docs/P40_APP_0.5.3_RESULT_V1_VALIDATION_2026-07-18.md`。
- App 0.5.5 已在 P40 Pro 完成 Token、AI 实时和网络综合三类无线证据与跨语言摘要终验：三条 run 分别冻结 119/26/18 个无线样本，Schema 错误 0，独立 Python 摘要全部匹配；0.5.4 的指数词法缺陷已被识别、否决并以冻结向量修复。活动承载均为 Wi-Fi，无线信号只作环境协变量，Quick 结论保持 LOW/INCONCLUSIVE。详见 `docs/P40_APP_0.5.5_RADIO_AND_CANONICAL_VALIDATION_2026-07-18.md`。
- App 0.5.6 为每个 Token 任务冻结稳定 `task_id`、节点处理时延 B03 和端到端 TTFT B04，并提供任务对齐的 fail-closed 重复性审计。P40 Pro 同设备/同 Wi-Fi/同节点连续 5 次 Quick 的 3 个任务 TTFT CV 中位数为 1.425%、最大值 4.986%，达到 ≤10% 的原计划 M1 复测门限；5 条 Schema、摘要、类型化结果与 119×5 无线样本证据链均通过。单 run 仍因 Quick 样本量保持 LOW/INCONCLUSIVE，详见 `docs/P40_APP_0.5.6_TTFT_REPEATABILITY_VALIDATION_2026-07-18.md`。
- App 0.5.7 开始 M4 非开发者路径收口：所有正式测试首次开测先解释无线证据权限并允许低置信继续；无网络或非法节点地址在启动 Service 前给出自救提示。P40 已验证非法地址零服务启动、无线用途说明和完整 Network Quick 正常落库/18 个无线样本，详见 `docs/P40_APP_0.5.7_NON_DEVELOPER_FLOW_VALIDATION_2026-07-18.md`。
- App 0.5.9 将三类结论统一为评分器冻结的稳定 ID、严重级别、文本和依据，Profile catalog 升至 1.3.0；0.5.10 进一步把系统下载导出改成失败可清理的事务生命周期，避免半成品文件或完成失败误报成功。当前 90 个 JVM suites / 551 tests 零失败，Lint 0 error / 11 notices，全仓质量门通过。云端 0.5.10 Debug 候选已在 P40 完成跨 Debug 签名的数据保全、单条 v2 与 v1+v2 混合批量导出验收：Room v19 integrity OK，36 条信封中 32 条完整性合格记录通过离线验证，4 条历史摘要异常被透明拒绝；这不是原位升级，也不证明非 ADB 安装链。
- 云端 CI 不再只“编译但不交付”：`main` 与 `codex/**` 分支通过高置信凭据扫描、Profile/结果合同、脚本测试、服务器、网关、行为模型、Android 与 Release 边界后，才生成带 APK 身份、签名、SHA-256、中文安装说明的 Debug 候选。M0-EC1 负向最终来源 commit `67eb66d3632f39f1445bb8785c047cc080f624a6` 的 run [`30162011890`](https://github.com/lucassu2012/ANEB_GPT/actions/runs/30162011890) 7 个 job 全绿，APK SHA-256=`f86aef14c086acb0dc0343b6aec05239af9e40efdaa1bfabee2b8cacb6b5c26e` 已独立核验。外部固定 CA 的网关隔离 TLS/netem 步骤因未配置叶证书密钥而明确跳过，不折算为 PASS；Debug 候选不冒充正式签名 Release。
- M4 公开仓凭据安全门已加入本地质量门和独立云端 job：只扫描 Git 跟踪源码中的高置信 Token/云访问密钥/私钥头，命中日志只报规则、文件和行号；上述真实云端 run 的 `Tracked-source credential scan` 已成功，Android 候选等待该 job 后才构建。披露过的凭据仍必须撤销，扫描通过不能恢复其安全性，见 `SECURITY.md` 与 `docs/M4_CREDENTIAL_SAFETY_VALIDATION_2026-07-18.md`。
- “3 个子项目 + 1 个横切机制”的事实进度与 M0～M4 验收差距见 `docs/PLAN_ALIGNMENT_2026-07-17.md`；当前续开发状态、真机 run 与维护入口见 `docs/CLOUD_CONTINUATION_2026-07-16.md`；版本化测量与产品可靠性裁定见 `docs/DECISION_LOG.md`。

后续扩展项包括三类 Standard 长时回归、Stress 的取消/断网/切后台恢复、`agent_control` / `background_continuity` / `realtime_visual` Profile、海外节点和 CAMARA QoD。发布签名密钥由 Product Owner 在仓库外创建和保管，见 `docs/RELEASE_BUILD.md`。
