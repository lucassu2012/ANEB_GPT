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

批准记录见 `docs/DECISION_LOG.md` 的 D-36～D-41；完整指标、目标、评分与结论合同见
`docs/PROFILE_CONTRACT_V2_PROPOSAL_2026-07-16.md`。

## 2. 已完成

- SpeedTest 级视觉框架、动态仪表、基础网络测速、结果结论和新版图标已在 Android 工程内。
- 独立 `tools/aneb-ai-behavior-model` 已实现确定性 PCG32、Token/实时语音假设模型、轨迹生成、拟合、Schema 和测试。
- Token Quick/Standard Profile 已发布为 `profile.json + runtime_plan.json + SHA-256 manifest`；模型只描述业务行为，不注入 RTT、丢包或速率结果。
- Android 已实现 Profile v2 fail-closed 校验、计划哈希验证、真实上传/SSE Token 流、250ms 动态仪表、Token Simulation Score v1、Room v13 独立结果表及历史详情页。
- E-01 已部署 `POST /api/v1/token-sim`，严格执行上传接收、处理基线和逐 Token 绝对时序；服务端不合成网络损伤。
- AI 实时交互 Quick/Standard Profile 已发布为哈希绑定的运行计划；Android 已实现 20ms 双向音频帧、时钟同步、动态准时帧仪表、打断、独立评分、Room v14 结果冻结和历史详情页。
- E-01 已升级到 `aneb-server/0.3.0`，新增严格的 `/api/v1/realtime-sim` WebSocket 合同；节点只按计划调度帧，不合成网络结果。
- P40 Pro 已完成两次 Quick 端到端验收：3/3 任务和 1080/1080 Token 完成，动态 Token/s、RTT、上行速率、准时率、评分、结论与落库均通过；测试后已退出到华为桌面。
- `scripts/quality_gate.ps1` 已覆盖 Android 单测/Lint/APK、行为模型 12 项测试和 Go 服务端测试，并隔离并行开发时的 KSP 缓存竞争。
- P40 Pro 已完成两次 AI 实时交互 Quick 端到端验收：1/1 会话、3/3 轮次、动态准时帧率/播放余量/RTT/双向速率/首帧响应、结果落库及进程重启后的历史回看均通过；两次均保持 `LOW/INCONCLUSIVE`，测试后已主动退出到华为桌面。
- 网络综合 Profile 草案位于 `profiles/drafts/network_comprehensive_standard.json`。

## 3. 下一阶段（按顺序）

1. 补 AI 实时交互 Standard 长时稳定性、取消/断网/切后台恢复和打断边缘条件回归。
2. 增加独立 Token Stress Profile，覆盖 100MiB 视频上传和大文件下行；不得把 stress 时长与样本混入 Standard 高置信评分。
3. 升级网络综合引擎：loaded RTT、1s goodput 窗口、UDP echo 未返回/乱序、恢复 RTT 和独立评分。
4. 正式导航下线真实 API 探针：底部“探针”页改为 Profile/业务测试目录；保留仅限 ADB 的开发诊断路径。
5. 三类结果统一生成完成性、业务行为特征、瓶颈和逐项 95% 网络建议；因果措辞必须服从证据范围。
6. 完成 Standard/Stress 的长时真机稳定性、取消/切后台/断网恢复和 Android 16 回归，再进入 release 签名候选。

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
