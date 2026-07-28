# S3/M2 重复性门限与长样本计划提案（2026-07-28）

## 1. 反方结论先行

- ［KNOWN｜HIGH］当前三族各 5 次的 P40 数据不能直接生成正式门限。Realtime 的 `LIVE-N02`、`LIVE-B08` 和 Network 的 `NET-B01`、`NET-B02` 均未达到 Profile 声明的 run 级最低样本数；Network 下载 CV `24.07%` 还同时混合了真实 Wi-Fi 波动与测量链波动。
- ［KNOWN｜HIGH］重复性与体验质量是两道门。重复性回答“同条件重测是否稳定”，Profile 质量目标回答“体验是否足够好”；任何方案都不得用稳定但很差的结果冒充高质量，也不得用一次高分结果替代重复性。
- ［KNOWN｜HIGH］本文件保留三套候选及成本推导的提案原貌；Product Owner 的既有“按推荐方案、后续自主决策”授权已由 D-110 正式选择方案 B。D-110 不修改 D-58、Profile 质量目标、KPI、AQS、结果置信度或 `formal_baseline_eligible=false`。

NIST 把 repeatability 限定为同方法、同仪器、同地点、短时间内的连续测量，并建议用离散度量化；IETF IPPM 也要求在尽量相同的网络条件下比较统计性质，同时指出无线网络的条件控制尤其困难。因此本计划把“同条件短期重复性”和“跨承载/跨时段可复现性”拆成两个阶段，而不是混成一个 CV。

参考：

- NIST TN 1297，repeatability terminology：<https://www.nist.gov/pml/nist-technical-note-1297/nist-tn-1297-appendix-d1-terminology>
- IETF BCP 176 / RFC 6576，metric verification：<https://www.rfc-editor.org/rfc/rfc6576.html>
- IETF RFC 7312，wireless sampling/repeatability：<https://www.rfc-editor.org/rfc/rfc7312.html>

## 2. 所有候选共同保留的硬门

以下不是新质量阈值，而是现有 strict-v2/证据合同的前置有效性条件：

1. 每个 cohort 的 source、CI APK、签名、Profile/运行计划、设备、服务节点、活动承载、VPN 状态和算法身份完全一致；Wi-Fi 与蜂窝永不混池。
2. 所有 run 必须 `completed + valid`，原始 Room 三件套经冻结副本导出，严格 Schema/摘要/无线逐条绑定通过。
3. 所有参与正式判定的指标必须在每个 run 达到 Profile 的 `minimum_sample_count`；不足时只能输出 diagnostic。
4. 无线样本必须 collected、计数一致、单调时钟严格递增；无法解释的订阅切换会拆分或拒绝 cohort。
5. 先过重复性门，再单独检查 Profile 质量目标；两者都通过才允许进入后续正式基线资格审计。

## 3. 三套候选方案

| 项目 | A：保守正式 | B：平衡正式（推荐） | C：快速工程 |
|---|---|---|---|
| 定位 | 测量学优先，成本最高 | 满足正式判定所需信息量，控制现场成本 | 仅回归/诊断，不关闭正式里程碑 |
| 每族 run 数 | 10 次 Standard | 10 次 qualification，连续 2×5 | 5 次 Quick |
| Token | 10 次 Standard 超出 D-58 的 30 分钟短时窗；需另批 S3 长时窗 Token 门后才可实施 | 两个 5-run 子批各自通过不变的 D-58；再通过 10-run S3 pooled 漂移门 | 保持 3 任务；只能沿用 D-58 子门，run 级样本门不满足 |
| Realtime ratio | `LIVE-B05` 十次极差 ≤0.005 | `LIVE-B05` 十次极差 ≤0.01 | 极差 ≤0.02，仅诊断 |
| Realtime latency | `LIVE-N02`、`LIVE-B08` 各自 CV ≤10% | 两指标各自 CV ≤15% | CV ≤25%，仅诊断 |
| Network throughput | `NET-B01/B02` 各自 CV ≤15% | 两指标各自 CV ≤20% | CV ≤25%，仅诊断 |
| Network loaded RTT | `NET-B04` CV ≤10% | CV ≤15% | CV ≤25%，仅诊断 |
| 无线 cadence | 每 run P95 gap ≤1.10 s、max gap <1.50 s、stale=0 | P95 gap ≤1.25 s、max gap <1.50 s、stale=0 | P95 gap ≤1.50 s、只输出诊断 |
| 订阅切换 | 0；否则拆 cohort | 0；否则拆 cohort | 记录但不做正式判定 |
| 单承载预计时长 | 约 7.6 小时 | 约 1.8 小时 | 约 14 分钟 |
| 能否产生正式 repeatability verdict | 补批长时窗 Token 门后才可以 | 可以 | 不可以 |

门限采用指标类型感知规则：接近 0/1 的比例用绝对极差，不使用会在均值接近零时失真的 CV；严格为正的时延、速率使用样本 CV。任何均值为 0 的 CV 继续保持 `undefined_zero_mean`，不得补零。

## 4. 推荐 B 的 qualification Profile 轮廓

### 4.1 Token qualification

- 从 Standard 的冻结任务中选 10 个代表任务：4 个文本、3 个文档、3 个图片；同一 cohort 的任务 ID、上传量、响应工件量、期望 Token 和顺序全部冻结。
- 初始候选为文本 `task-0001/0003/0006/0008`、文档 `task-0010/0011/0012`、图片 `task-0015/0016/0017`。现有 deterministic plan 的任务时长合计 `423.096 s`；计入任务间隔后约 7.2 分钟。正式生成器仍须重新计算并绑定完整 runtime hash。
- 每个网络条件执行两个连续 5-run 子批。每个子批必须分别满足 D-58 冻结的 `5 run + 起始跨度 ≤30 分钟 + 任务对齐 TTFT CV 中位数 ≤10%`，不得把 10 条一次性交给 D-58，也不得放宽 D-58 的时间窗。
- 两个子批均通过后，再由新增的 S3/M2 上层门对完整 10 run 重算任务对齐 TTFT CV：中位数 ≤10%，最大任务 CV 仍只作诊断；完整 10-run 起始跨度提议冻结为 ≤90 分钟。该门是新的 S3/M2 pooled 漂移门，不冒充 D-58，用于拒绝“前 5 次稳定、后 5 次也稳定，但两批中心值明显漂移”的假通过。
- ［COMPUTED｜HIGH］按当前候选 run `431.596 s` 计算，一个 5-run 子批的理论最小起始跨度为 `4 × 431.596 = 1,726.384 s`，即 `28.773 分钟`，仅余 `73.616 s` 给四次 run 间切换。正式生成器必须在采样前证明包含启动/清理开销后仍能落在 30 分钟内；证明不了就缩短 qualification 任务，不得扩大 D-58。

### 4.2 AI Realtime qualification

- 每 run 至少 12–16 turn、≥20 个会话 RTT 样本、≥2 次打断，并保持 ≥500 个音频帧。初始候选复用 Standard 的 `session-0002`：12 turn、4 次打断、1,820 个上行帧、计划时长 `86.1 s`；生成后必须先用离线引擎证明实际指标样本数达到合同，不能只凭 turn 数推断。
- `LIVE-B05` 使用十次 run 值极差；`LIVE-N02` 和 `LIVE-B08` 分别使用十次 run 值的样本 CV，不合成一个平均数掩盖单指标漂移。

### 4.3 Network qualification

- 直接使用现有 Standard 的 42 秒运行计划，因为它已声明下载/上传各 ≥10 个样本、loaded RTT ≥20 个样本。
- `NET-B01`、`NET-B02`、`NET-B04` 三项必须分别通过；不采用“2/3 即通过”，避免某一方向明显不稳定时仍放行。

### 4.4 执行分层

1. **Stage Q1：同条件 Wi-Fi repeatability**：同设备、同点位、同节点、同承载，三族各 10 run；Token 还必须冻结 `batch-A=5 + batch-B=5` 身份、顺序和各自时间窗。目标是关闭测量链的短期重复性。
2. **Stage Q2：同条件蜂窝 repeatability**：独立 cohort 重复 Q1；不得与 Wi-Fi 合并。只在 Q1 通过后执行。
3. **Stage R1：跨时段 reproducibility**：在另一天/忙闲时段重复 5 run 子集，只报告相对偏移和环境协变量，不回写 Q1 阈值。
4. **Stage F：外场矩阵**：进入 S4/M2 后再按点位、运营商、时段分层；任何跨条件聚合必须保留 cell 级结论。

## 5. 把 2026-07-28 五次工程样本代入候选 B

| 门 | 当前结果 | 候选 B 判定 |
|---|---|---|
| Token D-58 | 任务 TTFT CV 中位数 `1.71%` | 重复性子门 PASS |
| Token run 样本数 | `TOK-B04` 每 run 3，最低要求 10 | 不具正式资格 |
| Realtime ratio | `LIVE-B05` 5 次均为 1.0 | 候选门 PASS |
| Realtime latency | `LIVE-N02` CV `8.98%`；`LIVE-B08` CV `7.03%` | 候选门 PASS，但两项 run 样本数不足，仍不具资格 |
| Network throughput | 下载 CV `24.07%`；上传 CV `7.22%` | 下载 FAIL、上传 PASS；两项 run 样本数也不足 |
| Network loaded RTT | CV `14.53%` | 候选门 PASS，且 run 样本数满足 |
| 无线 cadence | 所有 P95 gap ≤`1.0068 s`，max gap ≤`1.0090 s`，stale/switch=0 | 候选门 PASS |

［KNOWN｜HIGH］该代入只是对候选规则的回放，不是正式结果。它说明 B 不会机械放行当前样本：Network 下载波动和四个 run 级样本门仍会明确阻断。

## 6. 成本与风险

- ［COMPUTED｜HIGH］A 按现有 Standard duration 计算：`(1380.1 + 1306.9 + 42) × 10 = 27,290 s`，即单承载约 7.58 小时；Wi-Fi+蜂窝约 15.16 小时，尚未计入预检、冷却与失败重试。
- ［COMPUTED｜MED］B 按 10-task Token 约 7.5–8 分钟、Realtime 约 1.5–2.5 分钟、Network 42 秒估算，单承载 10 run 约 1.7–1.9 小时。实际时长需由生成后的 deterministic runtime plan 精确计算。
- ［COMPUTED｜HIGH］当前 Token 候选的 10-run 理论最小起始跨度约为 `9 × 431.596 = 3,884.364 s`，即 `64.739 分钟`，因此不可能作为一个 D-58 cohort；拆成两个 5-run D-58 子批是保持 D-58 不变的必要条件，不是可选优化。
- ［INFERRED｜MED］10-run pooled 起始跨度上限 `90 分钟` 是 D-110 批准的工程控制门，不是由当前 5 样本估计出来的统计边界；它为约 64.7 分钟的理论执行时长保留约 25 分钟受控开销，同时防止把相隔过久的数据伪装成短期重复性。
- ［INFERRED｜HIGH］A 的成本会鼓励跳过复测或把不同时间段拼池；B 更可能被持续执行，并且能满足现有最低样本合同。
- ［INFERRED｜HIGH］B 的 15%/20% 门限仍是产品工程门，不是从本次 5 样本估计出的统计置信区间。批准后应先运行一轮 Q1，再做一次冻结回放审计；若失败，只能调整 Profile/环境或提出新的 Decision，不能在结果出来后放宽阈值。

## 7. Product Owner 决策结果

［KNOWN｜HIGH］D-110 已原子批准 **B：平衡正式** 及以下四项：

1. 新增三族 `repeatability_qualification` Profile/运行计划，不改现有 Quick/Standard；
2. 批准 B 表中的指标类型、阈值、10-run 和无线 cadence 门；
3. 批准 Token 的 `两个独立 5-run D-58 子批 + 10-run pooled TTFT CV 中位数 ≤10% + 完整起始跨度 ≤90 分钟` 上层门；任一子批超出 30 分钟即失败，不放宽 D-58；
4. 先只执行 Q1 Wi-Fi；Q1 通过后再单独领取 Q2 蜂窝窗口，避免一次授权扩大到外场或弱网。

D-110 之前的实现保持 `policy_pending/diagnostic_only`；D-110 之后只能从版本化政策读取方案 B，禁止把候选 A/C 常数预埋到生产路径。

## 8. 实施落点审计（决策后执行）

［KNOWN｜HIGH］当前仓库没有可以直接承载候选 B 的完整执行路径；以下边界必须作为同一实现批次闭合，不能只改分析器或只新增 JSON：

1. **版本化政策**：新增独立、可哈希的 repeatability qualification policy 与 Schema，由 `spec/catalog.json` 建索引；阈值、样本数、Token 2×5 批次、90 分钟 pooled 门和 transport 隔离都来自冻结政策，不把常数散落在代码中。
2. **分析器**：扩展 `scripts/analyze_repeatability_cohort.py`，保留 D-58 原实现不变；新增政策加载、两个 5-run 子批验证、10-run pooled 漂移门、Realtime/Network 样本下限与离散度门、radio cadence、订阅切换和 Wi-Fi/蜂窝隔离。输出必须区分 `diagnostic_only`、`repeatability_passed` 与后续质量目标，不得把单次高分升级成正式基线。
3. **Profile 与运行计划**：在 `profiles/published/` 新增三族确定性的 `repeatability_qualification` bundle，并由行为模型生成器冻结 Token 任务、Realtime session 和 Network Standard phase；每个 bundle 都必须有 `profile.json`、`runtime_plan.json`、`manifest.sha256` 及完整 `execution_requirements`。
4. **Android 执行合同**：为三族增加显式 qualification runtime binding、Profile allowlist 与 execution gate；不得把 qualification 偷映射成 Quick/Standard，也不得仅因 qualification 单 run 完成就提高结果置信度。首版可作为 campaign-only 入口，不必先污染公开 UI。
5. **服务端能力回执**：扩展 `server/execution_capabilities.go`、候选构建与受保护部署映射，使 E-01 能对三族 qualification Profile 返回精确能力回执；版本和二进制身份必须经独立部署决策推进，不能沿用 Quick 回执冒充。
6. **Campaign 控制器**：扩展 `scripts/run_repeatability_campaign.py`，绑定政策 SHA、Profile/runtime/manifest SHA、固定运行顺序、Token batch-A/batch-B 和 transport；每次失败先完成 finally 清理并保留证据，禁止用任意 Quick 启动参数替代 qualification。
7. **红绿回归与门禁**：先增加政策篡改、5-run 超时、批间漂移、样本不足、radio cadence、订阅切换、transport 混池、能力回执不匹配和单-run 置信度不升级等 RED；再实现 GREEN，依次运行聚焦、跨族、静态、Android/Go 与全仓质量门，最后才申请 P40 Q1 窗口。

［KNOWN｜HIGH］方案 A 当前不是可立即施工的完整方案：10 次 Standard 的 Token 长度无法落入 D-58 30 分钟时窗。若 Product Owner 选择 A，必须先另行批准一个长时窗 S3 Token policy；不能把“D-58 不变”写成 A 已可执行。
