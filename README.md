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
  - `network_comprehensive_quick/standard`：容量、loaded RTT、稳定性与 UDP 应用探针
- `evidence/` — 验收证据目录（四态证据制，规则见其 README）
- `app/` — Android 客户端（Kotlin，minSdk 29；Compose + OkHttp/Cronet + Room）
- `server/` — Go 仿真服务器（SSE token 发生器 / 上行汇 / 结果落盘；标准库 + quic-go 专项，E-01 已部署）

**命名消歧**：本项目对外称 **ANEB Probe**；并行姊妹项目（Application Echo RTT 垂直切片）称 **ANEB Android Echo 切片**，两者同属 ANEB 研究计划、范围互补。

## 当前状态（2026-07-17，0.4.5）

- Android 客户端已按 `ANEB_UI` 重构，并提供“网络综合 / Token 仿真 / AI 实时”三类正式测试、SpeedTest 风格动态仪表、独立评分与结论、统一历史、真实 GPS 地图和新版图标。
- App 只在自建 ANEB 节点上模拟 AI 应用行为，不调用 Kimi、DeepSeek、千问等真实 API；行为模型在 `tools/aneb-ai-behavior-model/` 独立生成可审计运行计划。
- E-01 已部署 `aneb-server/0.5.1`：Token SSE（单任务上传硬上限 128MiB）、实时交互 WebSocket、连接级受控中断、HTTP/3 和与 H3 共用 UDP/8443 的带序号应用探针。
- Token、AI 实时和网络综合均已在 P40 Pro 完成 Quick 端到端验收；Token Stress 完成了 100MiB 上下行真机闭环。AI 实时结果冻结全部 21 项指标，并新增独立 Recovery Profile：2 次受控中断、固定模型派生恢复刺激、只用恢复后样本评分；Wi-Fi Standard 已完成 10/10 会话、160/160 轮真机长测；Room 已到 v15。
- 最终质量门：429 JVM tests、0 failures（64 suites，按 XML suite header 复算）；Android Lint 0 errors；行为模型 14 tests PASS；Go tests PASS；0.4.5 Debug APK 已生成。
- 当前续开发状态、真机 run 与维护入口见 `docs/CLOUD_CONTINUATION_2026-07-16.md`；版本化测量裁定见 `docs/DECISION_LOG.md` D-36～D-46。

后续扩展项包括三类 Standard 长时回归、Stress 的取消/断网/切后台恢复、`agent_control` / `background_continuity` / `realtime_visual` Profile、海外节点和 CAMARA QoD。发布签名密钥由 Product Owner 在仓库外创建和保管，见 `docs/RELEASE_BUILD.md`。
