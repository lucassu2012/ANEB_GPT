# ANEB 云端续开发 Checkpoint

> 时间：2026-07-16（Asia/Shanghai）  
> GitHub：`lucassu2012/ANEB_GPT`  
> 发布策略：公开仓只包含源码、设计文档和可复现测试；本机 `evidence/`、设备数据库、日志、密钥、APK、构建缓存不发布。

## 1. 当前产品边界

- ANEB App 只在自建 ANEB 节点上模拟 AI 应用行为，不调用 Kimi、DeepSeek、千问等真实 API。
- 现有真实 API 探针代码保留为开发诊断能力，但必须从正式产品导航隐藏。
- AI 真实业务行为模型独立开发，通过冻结的 Profile v2、模型哈希和确定性轨迹与 App 对接。
- Token、AI 实时交互、网络综合性能三类测试独立评分，禁止混入原有 AQS。
- 缺失测量值必须为 `null`/“—”，不得填 0；95% 达标结论必须带有效样本数和置信度。

批准记录见 `docs/DECISION_LOG.md` 的 D-36～D-43；完整指标、目标、评分与结论合同见
`docs/PROFILE_CONTRACT_V2_PROPOSAL_2026-07-16.md`。

## 2. 已完成

- SpeedTest 级视觉框架、动态仪表、基础网络测速、结果结论和新版图标已在 Android 工程内。
- 独立 `tools/aneb-ai-behavior-model` 已实现确定性 PCG32、Token/实时语音假设模型、轨迹生成、拟合、Schema 和测试。
- Token Quick/Standard/Stress Profile 已发布为 `profile.json + runtime_plan.json + SHA-256 manifest`；模型只描述业务行为，不注入 RTT、丢包或速率结果。
- Android 已实现 Profile v2 fail-closed 校验、计划哈希验证、真实上传/SSE Token 流、250ms 动态仪表、Token Simulation Score v1、Room v13 独立结果表及历史详情页。
- E-01 已部署 `POST /api/v1/token-sim`，严格执行上传接收、处理基线和逐 Token 绝对时序；服务端不合成网络损伤。
- AI 实时交互 Quick/Standard Profile 已发布为哈希绑定的运行计划；Android 已实现 20ms 双向音频帧、时钟同步、动态准时帧仪表、打断、独立评分、Room v14 结果冻结和历史详情页。
- E-01 已升级到 `aneb-server/0.5.0`，支持最大 128MiB 的受控 Token 上传、Token SSE、实时交互 WebSocket、HTTP/3 和与 HTTP/3 共用 UDP/8443 的带序号 `ANEB1` 应用探针。
- P40 Pro 已完成两次 Quick 端到端验收：3/3 任务和 1080/1080 Token 完成，动态 Token/s、RTT、上行速率、准时率、评分、结论与落库均通过；测试后已退出到华为桌面。
- `scripts/quality_gate.ps1` 已覆盖 Android 单测/Lint/APK、行为模型 13 项测试和 Go 服务端测试，并隔离并行开发时的 KSP 缓存竞争。本轮 Android 436 项 JVM 测试、Lint 和 0.4.0 APK 构建通过。
- P40 Pro 已完成两次 AI 实时交互 Quick 端到端验收：1/1 会话、3/3 轮次、动态准时帧率/播放余量/RTT/双向速率/首帧响应、结果落库及进程重启后的历史回看均通过；两次均保持 `LOW/INCONCLUSIVE`，测试后已主动退出到华为桌面。
- 网络综合 Quick/Standard Profile 已发布；Android 已实现 loaded RTT 并发刷新、100ms 吞吐仪表、1s goodput 窗口、握手分解、带序号 UDP 探针、测后恢复 RTT、独立评分、Room v15 结果冻结和历史详情页。
- P40 Pro 网络综合 Quick 端到端验收通过。最终 0.3.0 手动用户路径 run `019f6b6f-d3d8-7063-b301-90ec8be6fa5e` 在下载/上传阶段正确显示动态 Mbps 指针、loaded RTT、曲线和阶段进度，结果 60.7/C、UDP 50/50 返回并自动跳转结果页；按快测规则保持 `LOW/INCONCLUSIVE`。前序 run 还测得负载 RTT P95 1257.8ms，验证了 loaded RTT 能揭示单看带宽无法发现的排队时延。所有测试后均已退出到华为桌面且无残留前台服务。
- Token Stress 已完成 P40 Pro 手动用户路径验收：启动前显示约 200MiB 流量/发热确认；run `019f6b94-e09a-770a-83a2-6cb5dc6ee38d` 完成 100MiB 上行、100MiB 下行和 300/300 Token，动态上传/下载指针与 loaded RTT 并发刷新，采集 95 个 loaded RTT 样本。结果 50.5/D、`LOW/INCONCLUSIVE`：上行 29.9Mbps 达标，下行 18.2Mbps 未达 25Mbps，loaded RTT P95 1026.7ms、增量 965.6ms 未达目标。测试后已退出到华为桌面、强制停止 Codex 包且两个 ANEB 包均无服务。

## 3. 下一阶段（按顺序）

1. 补 AI 实时交互 Standard 长时稳定性、取消/断网/切后台恢复和打断边缘条件回归。
2. 完成 Token Standard 长时回归，以及 Stress 的取消、断网、切后台恢复和重复测试分布；单次 Stress 继续保持低置信。
3. 正式导航下线真实 API 探针：底部“探针”页改为 Profile/业务测试目录；保留仅限 ADB 的开发诊断路径。
4. 三类结果统一生成完成性、业务行为特征、瓶颈和逐项 95% 网络建议；因果措辞必须服从证据范围。
5. 完成 Standard 长时真机稳定性与 Android 16 回归，再进入 release 签名候选。

## 4. 关键目录

- Android/Go 主工程：仓库根目录
- Profile：`profiles/`
- Android App：`app/probe/`
- 自建节点：`server/`
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
