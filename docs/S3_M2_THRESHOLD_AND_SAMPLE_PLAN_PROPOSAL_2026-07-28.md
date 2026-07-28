# S3/M2 重复性门限与长样本计划提案（2026-07-28）

## 1. 反方结论先行

- ［KNOWN｜HIGH］当前三族各 5 次的 P40 数据不能直接生成正式门限。Realtime 的 `LIVE-N02`、`LIVE-B08` 和 Network 的 `NET-B01`、`NET-B02` 均未达到 Profile 声明的 run 级最低样本数；Network 下载 CV `24.07%` 还同时混合了真实 Wi-Fi 波动与测量链波动。
- ［KNOWN｜HIGH］重复性与体验质量是两道门。重复性回答“同条件重测是否稳定”，Profile 质量目标回答“体验是否足够好”；任何方案都不得用稳定但很差的结果冒充高质量，也不得用一次高分结果替代重复性。
- ［KNOWN｜HIGH］本文件是待 Product Owner 选择的提案，不是 Decision，不修改 D-58、Profile、KPI、AQS、结果置信度或 `formal_baseline_eligible=false`。

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
| Token | D-58 不变；每 run ≥10 个 B04 样本 | D-58 不变；每 run 10 个冻结代表任务 | 保持 3 任务；只能沿用 D-58 子门，run 级样本门不满足 |
| Realtime ratio | `LIVE-B05` 十次极差 ≤0.005 | `LIVE-B05` 十次极差 ≤0.01 | 极差 ≤0.02，仅诊断 |
| Realtime latency | `LIVE-N02`、`LIVE-B08` 各自 CV ≤10% | 两指标各自 CV ≤15% | CV ≤25%，仅诊断 |
| Network throughput | `NET-B01/B02` 各自 CV ≤15% | 两指标各自 CV ≤20% | CV ≤25%，仅诊断 |
| Network loaded RTT | `NET-B04` CV ≤10% | CV ≤15% | CV ≤25%，仅诊断 |
| 无线 cadence | 每 run P95 gap ≤1.10 s、max gap <1.50 s、stale=0 | P95 gap ≤1.25 s、max gap <1.50 s、stale=0 | P95 gap ≤1.50 s、只输出诊断 |
| 订阅切换 | 0；否则拆 cohort | 0；否则拆 cohort | 记录但不做正式判定 |
| 单承载预计时长 | 约 7.6 小时 | 约 1.8 小时 | 约 14 分钟 |
| 能否产生正式 repeatability verdict | 可以 | 可以 | 不可以 |

门限采用指标类型感知规则：接近 0/1 的比例用绝对极差，不使用会在均值接近零时失真的 CV；严格为正的时延、速率使用样本 CV。任何均值为 0 的 CV 继续保持 `undefined_zero_mean`，不得补零。

## 4. 推荐 B 的 qualification Profile 轮廓

### 4.1 Token qualification

- 从 Standard 的冻结任务中选 10 个代表任务：4 个文本、3 个文档、3 个图片；同一 cohort 的任务 ID、上传量、响应工件量、期望 Token 和顺序全部冻结。
- 初始候选为文本 `task-0001/0003/0006/0008`、文档 `task-0010/0011/0012`、图片 `task-0015/0016/0017`。现有 deterministic plan 的任务时长合计 `423.096 s`；计入任务间隔后约 7.2 分钟。正式生成器仍须重新计算并绑定完整 runtime hash。
- 每个网络条件连续执行 10 run；D-58 仍按任务对齐 TTFT CV 中位数 ≤10% 判定，最大任务 CV 只作诊断。

### 4.2 AI Realtime qualification

- 每 run 至少 12–16 turn、≥20 个会话 RTT 样本、≥2 次打断，并保持 ≥500 个音频帧。初始候选复用 Standard 的 `session-0002`：12 turn、4 次打断、1,820 个上行帧、计划时长 `86.1 s`；生成后必须先用离线引擎证明实际指标样本数达到合同，不能只凭 turn 数推断。
- `LIVE-B05` 使用十次 run 值极差；`LIVE-N02` 和 `LIVE-B08` 分别使用十次 run 值的样本 CV，不合成一个平均数掩盖单指标漂移。

### 4.3 Network qualification

- 直接使用现有 Standard 的 42 秒运行计划，因为它已声明下载/上传各 ≥10 个样本、loaded RTT ≥20 个样本。
- `NET-B01`、`NET-B02`、`NET-B04` 三项必须分别通过；不采用“2/3 即通过”，避免某一方向明显不稳定时仍放行。

### 4.4 执行分层

1. **Stage Q1：同条件 Wi-Fi repeatability**：同设备、同点位、同节点、同承载，三族各 10 run；目标是关闭测量链的短期重复性。
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
- ［INFERRED｜HIGH］A 的成本会鼓励跳过复测或把不同时间段拼池；B 更可能被持续执行，并且能满足现有最低样本合同。
- ［INFERRED｜HIGH］B 的 15%/20% 门限仍是产品工程门，不是从本次 5 样本估计出的统计置信区间。批准后应先运行一轮 Q1，再做一次冻结回放审计；若失败，只能调整 Profile/环境或提出新的 Decision，不能在结果出来后放宽阈值。

## 7. Product Owner 决策项

推荐选择 **B：平衡正式**，并批准以下三件事作为一个原子 Decision：

1. 新增三族 `repeatability_qualification` Profile/运行计划，不改现有 Quick/Standard；
2. 批准 B 表中的指标类型、阈值、10-run 和无线 cadence 门；
3. 先只执行 Q1 Wi-Fi；Q1 通过后再单独领取 Q2 蜂窝窗口，避免一次授权扩大到外场或弱网。

在 Product Owner 选择前，代码保持 `policy_pending/diagnostic_only`，不实现或预埋任一候选阈值。
