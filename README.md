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

## 当前状态（2026-07-17，0.4.9）

- Android 客户端已按 `ANEB_UI` 重构，并提供“网络综合 / Token 仿真 / AI 实时”三类正式测试、SpeedTest 风格动态仪表、独立评分与结论、统一历史、真实 GPS 地图和新版图标。
- App 只在自建 ANEB 节点上模拟 AI 应用行为，不调用 Kimi、DeepSeek、千问等真实 API；行为模型在 `tools/aneb-ai-behavior-model/` 独立生成可审计运行计划。
- E-01 已部署 `aneb-server/0.7.0`：保留 Token/实时交互/H3/UDP 全部能力；容量/时延弱网与 2 秒请求中断恢复均逐 run 隔离，正常路由和其他 run 不受影响。DNS/TCP/TLS/UDP/IP 丢包/切网/RSRP/SINR 不伪造。权威能力与部署纪律见 `docs/TEST_SERVER_CAPABILITIES.md`。
- 专用网络层网关软件、白名单 Profile、TLS 管理面、故障清理与 App 0.4.9 Debug 接入已完成；隔离命名空间验证证明双向时延、100% IP 中断、自动恢复与宿主零影响。真实 P40 网络层验收仍等待独占双网口 Linux/AP 硬件，见 `docs/DEDICATED_GATEWAY_PLAN_AND_VALIDATION_2026-07-17.md`。
- Token、AI 实时和网络综合均已在 P40 Pro 完成端到端验收；Token Stress 完成 100MiB 上下行，AI 实时 Standard/Recovery 已完成长测。网络合成恢复完成 4 次独立 run：恢复 2084.4–2227.3ms、恢复后请求成功率均为 100%，并捕获真实动态恢复计时与中断指针；Room 已到 v17。
- 最终质量门：442 JVM tests、0 failures；Android Lint 0 errors；行为模型 14 tests PASS；Go 服务端/网关 tests PASS；0.4.9 Debug APK 已生成，SHA-256 为 `B0661B6F7FCC6E5C6F1F631C65D3CF5028EA311CC23661089707E325C94205D2`。因没有独占专用网关，本轮未占用或安装到 P40 Pro。
- 当前续开发状态、真机 run 与维护入口见 `docs/CLOUD_CONTINUATION_2026-07-16.md`；版本化测量裁定见 `docs/DECISION_LOG.md` D-36～D-49。

后续扩展项包括三类 Standard 长时回归、Stress 的取消/断网/切后台恢复、`agent_control` / `background_continuity` / `realtime_visual` Profile、海外节点和 CAMARA QoD。发布签名密钥由 Product Owner 在仓库外创建和保管，见 `docs/RELEASE_BUILD.md`。
