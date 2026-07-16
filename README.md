# ANEB — Agent Network Experience Benchmark

研究智能体互联网时代移动通信网络新型性能与体验诉求，并提供配套测量工具 **ANEB Probe**。

## 仓库结构

- `docs/` — 研究文档
  - 《智能体互联网时代（Agentic Internet）移动通信网络的新型网络性能与体验诉求》：诉求分析 + Agent-QoE KPI 体系（agent-qoe-kpi v0.2.1：指标/门限/测量方法/声明边界 + 首轮实测锚点修注，第五部分）
  - 《ANEB Probe 开发设计文档》（v0.3，as-built）：测试工具的架构、技术选型、分阶段实现计划与实际完成状态、红队修订
  - 《测量红队清单》：32 项经多代理对抗验证的测量失真风险与闭环计划（10 项 high）
  - 《DECISION_LOG》：决策日志（D-xx）、否决记录、外部依赖清单（E-xx）
  - 《参考_ChatGPT侧ANEB_AndroidEcho方案与进展》：并行姊妹项目制度借鉴（只读参考）
- `profiles/` — 版本化测试场景配置 v0.2.0（客户端/服务端共享，发布即冻结，改动须升版本）
  - `basic_network.json` 网络基本性能（下载、上传、时延、抖动、应用层请求失败率）
  - `s1_chat.json` 对话流（对照组）
  - `s2_coding_agent.json` 编码 Agent 流（主场景）
  - `s3_multimodal.json` 多模态流
- `evidence/` — 验收证据目录（四态证据制，规则见其 README）
- `app/` — Android 客户端（Kotlin，minSdk 29；Compose + OkHttp/Cronet + Room）
- `server/` — Go 仿真服务器（SSE token 发生器 / 上行汇 / 结果落盘；标准库 + quic-go 专项，E-01 已部署）

**命名消歧**：本项目对外称 **ANEB Probe**；并行姊妹项目（Application Echo RTT 垂直切片）称 **ANEB Android Echo 切片**，两者同属 ANEB 研究计划、范围互补。

## 当前状态（2026-07-16，0.2.0）

- Android 客户端已按 `ANEB_UI` 重构，并提供“网络基本性能 / Token 体验”双模式、SpeedTest 风格真实动态仪表、业务结论、统一历史、真实 GPS 地图、Profile Registry 和新 App 图标。
- E-01 已部署 4 个 Profile；P40 Pro 真机已完成 Basic 与 Token 动态、结果、历史回看、地图和 SNI-RST 自动旁路验收。
- 主测试、Continuity 与 Protocol A/B 均由前台 Service 持有；Room 已到 v12；Release 网络安全和签名门禁已完成。
- 最终质量门：387 JVM tests、0 failures；Android Lint 0 errors；Go tests PASS；Debug APK 已生成。
- 完整交接、真机证据、APK 哈希和维护入口见 `docs/CODEX_V0.2.0_HANDOFF_2026-07-16.md`。

0.2.0 之后的外部扩展项：**E-06** 公共域名/公共 CA + UDP 8443（公网 Cronet QUIC A/B）、**E-04** 海外节点、**E-05** CAMARA QoD、**E-03** 真实 LLM API key。发布签名密钥由 Product Owner 在仓库外创建和保管，见 `docs/RELEASE_BUILD.md`。
