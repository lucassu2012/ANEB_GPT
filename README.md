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
  - `s1_chat.json` 对话流（对照组）
  - `s2_coding_agent.json` 编码 Agent 流（主场景）
  - `s3_multimodal.json` 多模态流
- `evidence/` — 验收证据目录（四态证据制，规则见其 README）
- `app/` — Android 客户端（Kotlin，minSdk 29；Compose + OkHttp/Cronet + Room）
- `server/` — Go 仿真服务器（SSE token 发生器 / 上行汇 / 结果落盘；标准库 + quic-go 专项，E-01 已部署）

**命名消歧**：本项目对外称 **ANEB Probe**；并行姊妹项目（Application Echo RTT 垂直切片）称 **ANEB Android Echo 切片**，两者同属 ANEB 研究计划、范围互补。

## 当前状态（2026-07-13，as-built）

阶段 0–3 本地可完成部分全部收口，四态证据账本：

- 阶段 0（骨架与计时联调）：17 PASS + 1 FAIL（D-18 判据修订后带内 PASS，并列留档）— `evidence/phase0/STATUS.json`
- 阶段 1（MVP：三场景/KPI/AQS/守卫全接线）：9/9 PASS — `evidence/phase1/STATUS.json`
- 阶段 2（连续性/H3/Cronet A/B/API 探针）：5 PASS + 1 BLOCKED_EXTERNAL — `evidence/phase2/STATUS.json`
- 阶段 3（看板/netem 灵敏度与跨境剖面/GPS 路测）：5 PASS + 1 FAIL（P3-C05 检测器误报如实入册）+ 3 BLOCKED_EXTERNAL — `evidence/phase3/STATUS.json`

后续推进悬于外部依赖（解锁清单见设计文档 §10）：**E-02** 真机+SIM（蜂窝测量证据与规模化数据回流）、**E-06**+UDP 8443（E-01 TLS/H3 切换与公网 QUIC A/B）、**E-04** 海外节点、**E-05** CAMARA QoD、**E-03** LLM API key。首轮实测锚点已回写 KPI 文档 5.2（agent-qoe-kpi v0.2.1 修注版）。
