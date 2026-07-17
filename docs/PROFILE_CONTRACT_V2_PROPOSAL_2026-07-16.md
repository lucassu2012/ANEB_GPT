# ANEB Profile Contract v2 与独立 AI 业务行为模型提案

> 日期：2026-07-16  
> 状态：**Product Owner 已批准“ANEB App 只做可控仿真、真实业务行为建模独立并行”边界，并于 D-37 批准 provisional v1 门限、95% 达标口径、权重和门控作为实验性实施基线；运行时实施与实机验收进行中。**  
> 适用范围：ANEB Probe Android App、自建 ANEB 仿真节点、独立 `aneb-ai-behavior-model` 工程。  
> 不适用：第三方 LLM API 性能评测、第三方网页自动化、第三方模型质量排名。

## 1. 结论先行

ANEB 后续拆成两个相互独立、通过版本化产物连接的系统：

1. **ANEB Probe App**：只执行自建节点上的可控仿真 Profile，负责精确采样、动态展示、评分和结论；不调用 Kimi、DeepSeek、千问等真实 API。
2. **AI 业务行为模型系统**：从获准的业务观测数据或显式假设中拟合业务行为，生成冻结的模型、合成事件轨迹和候选 Profile；不进入 App 的测量热路径。

两者唯一连接面：

```text
观测数据/产品假设
        │
        ▼
AI 业务行为模型系统
  model.json + validation.json + golden_trace.jsonl
        │  生成并冻结
        ▼
ANEB Profile Contract v2
        │  版本、哈希、模型来源进入每次结果
        ▼
ANEB App + 自建仿真节点
```

“Kimi 类 / DeepSeek 类 / 千问类”只表示**业务交互原型**。模型未经真实数据校准时必须标 `hypothesis`，不得写成这些产品的真实性能或真实流量画像。

## 2. 为什么不把真实 API 放进主测量

- 真实 API 的排队、模型推理、版本切换和限流不可由 ANEB 控制，会把服务端波动混进网络评分。
- 第三方通常不暴露“AI 完整收到”“启动分析”等内部时间戳；客户端只能看到请求发出、响应头和首个流事件。
- Token 计费量只有服务端返回 usage 时才是准确值；字符数换算只能是估算。
- 网页 UI 自动化还叠加登录、页面版本、浏览器调度和反自动化控制，不能作为可复现评分主链路。

因此，ANEB App 内的“AI 收到”“开始处理”“首 Token”必须明确指向**自建仿真节点的阶段事件**，而不是第三方 AI 的内部事实。

## 3. Profile Contract v2

### 3.1 Profile 的四个必备部分

每个可执行 Profile 必须完整包含：

1. `business`：业务类型、工作负载、行为特征、模型来源和校准状态；
2. `measurements`：全量业务指标、网络指标、计算口径、可度量等级、质量目标和最小样本量；
3. `live_presentation`：一个动态主指标、辅助指标、窗口、刷新频率和无数据行为；
4. `evaluation`：版本化评分策略、门控、置信度规则和结论合同。

### 3.2 建议 Schema

```json
{
  "contract_version": "aneb-profile-v2",
  "profile_id": "token_multimodal_standard",
  "version": "1.0.0",
  "mode_id": "token_simulation",
  "execution_target": "aneb_probe_simulator",
  "claim_scope": "application_end_to_end_to_probe_node",
  "business": {
    "category_id": "token_multimodal",
    "label": "多模态 Token 业务",
    "archetype_labels": ["kimi_like", "deepseek_like", "qwen_like"],
    "behavior_model_id": "token-multimodal-behavior-v0.1",
    "behavior_model_hash": "sha256:...",
    "calibration_status": "hypothesis",
    "workloads": [],
    "behavior_feature_ids": [
      "uplink_burst",
      "low_latency_start",
      "stream_continuity",
      "large_downlink_optional"
    ]
  },
  "measurements": [],
  "live_presentation": {
    "primary_metric_id": "SIM_TPS_LIVE",
    "secondary_metric_ids": ["RTT_LIVE", "UP_LIVE", "ON_TIME_RATIO_LIVE"],
    "window_ms": 1000,
    "ui_refresh_ms": 250,
    "stale_after_ms": 1500,
    "missing_behavior": "show_unavailable_never_zero"
  },
  "evaluation": {
    "target_set_id": "token-sim-targets-v1",
    "score_policy_id": "token-sim-score-v1",
    "conclusion_policy_id": "token-sim-conclusions-v1",
    "required_metric_ids": [],
    "guardrail_metric_ids": [],
    "grade_bands": {"excellent": 85, "good": 70, "fair": 55},
    "missing_required_metric": "score_null",
    "invalid_run": "retain_raw_suppress_score"
  },
  "phases": []
}
```

### 3.3 单项指标合同

```json
{
  "metric_id": "TOK_TTFT_EXCESS_P95",
  "label": "首 Token 超额时延 P95",
  "domain": "business",
  "unit": "ms",
  "measurement_level": "exact",
  "source_event_ids": ["upload_received", "sim_processing_scheduled", "token_arrived"],
  "formula_id": "ttft_excess-v1",
  "aggregation": "p95",
  "direction": "lower_is_better",
  "required_for_score": true,
  "minimum_sample_count": 10,
  "quality_target": {
    "operator": "lte",
    "value": 200,
    "required_compliance_ratio": 0.95,
    "provenance": "aneb_product_provisional_v1"
  }
}
```

`measurement_level` 只允许：

- `exact`：Profile 和自建节点事件可直接确定；
- `derived`：由多个精确事件计算，公式版本化；
- `proxy`：只能代理真实业务事实，UI 和导出必须显示“代理量”；
- `unsupported`：本 Profile 无法度量，结果必须为 `null`。

### 3.4 95% 达标口径

每条建议都必须来自样本分布，不能拿单次平均值冒充稳定性：

- 低者优指标：同时报告 `P95` 与 `count(x <= target) / valid_count`；
- 高者优指标：同时报告 `P5` 与 `count(x >= target) / valid_count`；
- 比率指标：报告分子、分母和 Wilson 置信区间；
- 样本不足：照常报告观察值，但置信度降级，不输出“稳定达到 95%”。

## 4. Token 类仿真 Profile

### 4.1 业务类型和工作负载

建议拆成两个不可混分的 Profile：

| Profile | 默认负载 | 用途 |
|---|---|---|
| `token_multimodal_standard@1.0.0` | 8KB 文本、5MiB 文档、10MiB 图片，多轮 Token 流和可选返回文件 | 日常测试 |
| `token_multimodal_stress@1.0.0` | 单独执行 100MiB 视频上传、仿真 Token 流和 100MiB 大对象返回 | 用户明确启动的压力测试 |

100MiB 视频不进入默认快测。它会显著增加流量、测试时长和发热；单独 Profile 才能保证不同 run 的负载可比。

每个工作负载包含以下阶段：

1. 用户动作事件；
2. 分块上行；
3. 仿真节点完整接收确认；
4. 版本化的模拟处理延迟；
5. 首 Token 与流式 Token 事件；
6. 可选文档/图片/视频返回；
7. 可控的中断、重连、断点续传与重复 Token 行为。

### 4.2 全量业务指标

| ID | 指标 | 口径 | 可度量性 | 建议目标（草案） |
|---|---|---|---|---|
| TOK-B01 | 任务成功率 | 通过协议完整性与确定性校验器的任务数 / 尝试数 | exact | ≥99%，标准测试至少 20 个逻辑任务 |
| TOK-B02 | 点击至节点完整接收 | `user_action` → `upload_received` | exact | 文本 P95≤1s；5MiB 文档≤6s；10MiB 图片≤10s；100MiB 视频≤60s |
| TOK-B03 | 仿真处理时延 | `processing_start` → `processing_end` | exact，但属于模型设定 | 不评分；用于从端到端 TTFT 中剥离业务模型基线 |
| TOK-B04 | 端到端 TTFT | 请求/上传完成 → 首个仿真 Token 到达 App | exact | `模型处理 P95 + 200ms` 内达到 95% |
| TOK-B05 | 首 Token 超额时延 | 端到端 TTFT − Profile 计划处理时延 | derived | P95≤200ms |
| TOK-B06 | 仿真 Token 到达速率 | 最近 1s 完整 Token 事件数 | exact | 动态指标；最终以计划速率保持率评分 |
| TOK-B07 | Token 准时到达率 | 到达时刻不晚于计划时刻+200ms 的 Token / 应到 Token | derived | ≥95% |
| TOK-B08 | ITL 残差 P95 | 到达间隔 − 节点计划发出间隔，按 seq join | derived | ≤100ms |
| TOK-B09 | 卡顿率 | ITL 残差 >200ms 的占比 | derived | ≤2% |
| TOK-B10 | 严重卡顿率 | ITL 残差 >1s 的占比 | derived | 0；>1% 触发评分封顶 |
| TOK-B11 | 流完整率 | 唯一 seq 到达数 / 计划 seq 数 | exact | ≥99% |
| TOK-B12 | 返回文件完成时延 | `artifact_first_byte` → 完整校验通过 | exact | 按文件大小和下行目标派生 |
| TOK-B13 | 重连恢复时延 | 传输中断检出 → 恢复后首个新 Token | exact | P95≤3s |
| TOK-B14 | 仿真 Token 传输冗余 | `(全部尝试传输 Token−唯一有效 Token)/唯一有效 Token` | derived | ≤5% |
| TOK-B15 | 仿真 Token 计划量/传输量/有效量 | 三个计数分别报告 | exact | 描述性指标，不设质量门限 |

`TOK-B14` 不是第三方计费 Token。只有发生可归因的重试、重连或重复发送时，结论才允许写“重试使仿真 Token 传输量增加 x%”。

### 4.3 全量网络指标

| ID | 指标 | 口径 | 建议目标（草案） |
|---|---|---|---|
| TOK-N01 | DNS 时延 | 解析开始→结束；bare-IP 时为 `null` | P95≤500ms |
| TOK-N02 | TCP/TLS 建连时延 | 连接与握手分别报告 | TCP P95≤500ms；TLS P95≤1000ms |
| TOK-N03 | 应用层 RTT | 自建 echo 往返 | P95≤100ms，达标比例≥95% |
| TOK-N04 | RTT 变化 | RTT P95−P50 | ≤30ms |
| TOK-N05 | 应用请求失败率 | 失败请求 / 全部请求；不得称 IP 丢包 | ≤2% |
| TOK-N06 | 上行有效速率 | 节点完整接收确认口径 | 文本≥1Mbps；文档≥10Mbps；图片≥12Mbps；视频≥20Mbps，均要求 P5 达标 |
| TOK-N07 | 下行有效速率 | 完整校验后的有效字节 / 时间 | 大对象返回 P5≥25Mbps |
| TOK-N08 | 负载中 RTT | 上传/下载同时运行的 echo P95 | ≤200ms |
| TOK-N09 | 负载时延增量 | loaded RTT P95−idle RTT P50 | ≤100ms |
| TOK-N10 | TCP 重传协变量 | 自建节点 TCP_INFO，可得时报告 | 归因协变量，不直接称链路丢包 |
| TOK-R01 | 无线层协变量 | RSRP/RSRQ/SINR/制式/小区事件 | 不评分，只作时间重合分析 |

### 4.4 动态主指标

- 主指标：`SIM_TPS_LIVE`，最近 1s **仿真 Token 到达速率**，250ms 刷新；
- 辅助：实时 RTT、当前上行/下行 Mbps、Token 准时到达率；
- 动效：真实事件驱动指针、弧线、粒子流和波形；没有样本时显示“建立窗口”，不得用 0 驱动几何；
- UI 必须带“仿真 Token”标签，不得简称为第三方模型 TPS。

### 4.5 Token Simulation Score v1（草案）

| 组 | 权重 | 指标 |
|---|---:|---|
| 任务完成 | 25% | 成功率 15%，流完整率 10% |
| 交互响应 | 30% | 首 Token 超额时延 15%，准时 Token 率 10%，卡顿率 5% |
| 多模态传输 | 25% | 上行期限/速率 15%，下行返回 10% |
| 网络稳定 | 15% | RTT 5%，RTT 变化 5%，应用请求失败率 5% |
| 传输效率 | 5% | 仿真 Token 传输冗余 5% |

门控：

- invalid run：保留原始证据，分数为 `null`；
- 任一必需指标缺失：不重分权，分数为 `null`；
- 任务成功率 <80% 或严重卡顿率 >1%：总分封顶 54；
- 100MiB stress 与 standard 独立出分，禁止横向混排。

### 4.6 Token Stress Score v1（已冻结）

Stress 只评价一次明确的大对象容量与负载响应性任务，使用独立的 `token-stress-score-v1`：

| 组 | 权重 | 必需指标 |
|---|---:|---|
| 任务完成 | 20% | TOK-B01、TOK-B11、TOK-N05 |
| 上行容量 | 30% | TOK-B02、TOK-N06 |
| 下行容量 | 25% | TOK-N07 |
| 负载响应性 | 25% | TOK-N08、TOK-N09 |

- 必需指标：`TOK-B01/B02/B11/N05/N06/N07/N08/N09`；负载 RTT 至少 20 次尝试；
- 100MiB 任务或 Token 流不完整时判 `FAIL` 且总分封顶 54；
- 单次任务即使完整也固定为 `LOW/INCONCLUSIVE`，不得声称已证明 95% 长期稳定性；
- 动态主指标按阶段切换为上行 Mbps、仿真 Token/s、下行 Mbps，负载 RTT 并发刷新；缺样本显示“—”，不得补 0；
- 启动前必须提示约 200MiB 流量与发热风险，Stress 选项只在 Token 类测试出现。

## 5. AI 实时交互仿真 Profile

### 5.1 业务类型

`ai_realtime_voice_standard@1.0.0` 模拟 GPT-Live 类全双工语音业务：

- 一条持久双向连接；
- 20ms 音频帧连续上行；
- VAD/手动提交两种轮次；
- 节点按模型参数产生响应等待、首音频帧和连续下行音频；
- 12–20 轮会话，含至少 2 次用户打断；默认计划不主动制造网络故障，自然中断发生后由后续会话测量重连和恢复；
- 首版使用自建 WebSocket 仿真；WebRTC/RTP 作为后续独立 Profile，禁止与 WebSocket 分数混合。

### 5.2 全量业务指标

| ID | 指标 | 口径 | 建议目标（草案） |
|---|---|---|---|
| LIVE-B01 | 会话建立成功率 | 成功进入可收发状态 / 尝试数 | ≥99% |
| LIVE-B02 | 会话建立时延 | 开始连接→session ready | P95≤2s |
| LIVE-B03 | 轮次响应时延 | 用户结束说话/commit→首个下行音频帧可播放 | `模型等待 P95+200ms` |
| LIVE-B04 | 响应超额时延 | 轮次响应时延−Profile 计划模型等待 | P95≤200ms |
| LIVE-B05 | 音频准时帧率 | 在播放期限前到达的帧 / 应到帧 | ≥99% |
| LIVE-B06 | 音频卡顿率 | 播放时间中无可用帧的时长 / 总播放时长 | ≤1% |
| LIVE-B07 | 音频掩盖样本率 | 本地补偿/静音样本 / 总播放样本 | ≤1% |
| LIVE-B08 | 打断响应时延 | 用户打断事件→旧响应停止输出 | P95≤300ms |
| LIVE-B09 | 轮次成功率 | 每轮协议和输出完整性校验通过 | ≥99% |
| LIVE-B10 | 会话中断率 | 异常断开 / 会话数 | ≤1% |
| LIVE-B11 | 恢复时延 | 中断检出→恢复后的首个有效音频帧 | P95≤3s |
| LIVE-B12 | 对讲重叠率 | 非计划相邻轮次时间重叠事件 / 可判断轮次边界 | ≤1% |

### 5.3 全量网络指标

| ID | 指标 | 建议目标（草案） |
|---|---|
| LIVE-N01 | WebSocket 握手时延 P95≤1s |
| LIVE-N02 | 会话内 ping/pong RTT P95≤100ms，达标比例≥95% |
| LIVE-N03 | 帧到达抖动 P95−P50≤30ms |
| LIVE-N04 | 带序号应用音频帧未返回率≤1%；不得直接称 IP 丢包 |
| LIVE-N05 | 连续丢帧最大长度≤3 帧（20ms/帧） |
| LIVE-N06 | 上下行持续有效速率 P5 各≥0.5Mbps（provisional v1，可选且不入分；与当前模型 0.256/0.384Mbps 净荷基线冲突，待升 target-set 版本修订） |
| LIVE-N07 | loaded RTT P95≤150ms |
| LIVE-N08 | 重连尝试次数和连接迁移事件完整报告，不设质量目标 |
| LIVE-R01 | 无线层公开 API 协变量；不可用时为 `null`，不评分 |

### 5.4 动态主指标

- 主指标：`AUDIO_ON_TIME_RATIO_2S`，最近 2s 音频准时帧率；
- 辅助：播放缓冲余量、RTT、帧到达变化、上下行 kbps、上一轮响应时延；
- 动效：音频帧驱动环形脉冲和波形，打断时立即换色并反向收束；
- 好网络下指标可能接近 100%，视觉动态来自真实帧节奏，不添加随机抖动造假。

### 5.5 Realtime Interaction Score v1（草案）

| 组 | 权重 | 指标 |
|---|---:|---|
| 对话响应 | 35% | 响应超额时延 20%，打断响应 10%，建连时延 5% |
| 播放连续 | 35% | 准时帧率 15%，卡顿率 15%，掩盖样本率 5% |
| 会话可靠 | 20% | 轮次成功率 8%，会话不中断率 8%，会话建立成功率 4%；恢复时延作为可选诊断，不在 v1 混入权重 |
| 网络就绪 | 10% | RTT 4%，抖动 3%，帧未返回率 3% |

会话无法建立时分数为 `null` 并输出失败原因；轮次成功率 <80% 或音频卡顿率 >5% 时总分封顶 54。

### 5.6 实现与真机证据（2026-07-17）

- Android 0.4.1 的结果合同会冻结本节全部 21 个指标；13 个必需指标决定 v1 分数，8 个可选/诊断指标缺失时不重分权；
- `LIVE-N07` 在实际语音上下行期间每 250ms 并发应用层 echo，既驱动动态 RTT，也保留失败尝试；
- `LIVE-B11` 的恢复终点是下一次成功会话的首个有效下行音频帧，不是 WebSocket open 或 session ready；没有中断时值为 `null`、样本数为 0；
- Standard 未达到所有必需指标最低样本量时最多给出 `MEDIUM/INCONCLUSIVE`，只有完整覆盖才允许 `HIGH/PASS|FAIL`；
- P40 Pro Quick run `019f6bbd-e628-76bb-b88e-26edf9f502b8` 冻结 21 项指标和 79 次 loaded RTT 尝试：会话 RTT P95 42.8ms、loaded RTT P95 54.2ms、上/下行 P05 0.257/0.383Mbps。Quick 仍固定 `LOW/INCONCLUSIVE`，不形成 95% 长期稳定性承诺。

### 5.7 受控恢复 Profile 与 Score v2（已实现）

`ai_realtime_voice_recovery@1.2.0` 与 Standard 隔离，claim scope 为 `controlled_server_disconnect_recovery_to_probe_node`：

- 4 个短会话中安排 2 次受控服务端连接中断；节点先完整提交指定轮次证据，再只关闭该 WebSocket；
- 两个恢复会话的首轮都使用行为模型中同一份最小合法刺激：1.2s 上行语音 + 350ms 模型等待，禁止不同说话时长污染恢复比较；
- 恢复时延仍按“客户端检出中断→下一成功会话首个有效音频帧”计时，并同时披露 1550ms 业务计划基线；
- `realtime-recovery-score-v2` 只用恢复后会话计算必需的 LIVE-B05/B09/N02，最低样本为 400 帧、6 轮、10 个 RTT；LIVE-B11 必须覆盖 2 次恢复尝试，失败尝试按未达标计入而不是丢弃；
- 权重：恢复路径 65%（中断观察 20%、恢复完成 30%、恢复时延 50%），恢复后质量 35%（准时帧 60%、轮次成功 25%、RTT 15%）；未观察全部中断或未全部恢复时总分封顶 54；
- P40 Pro run `019f6df2-adf6-7e63-bf4d-7db123d8e58a`：2/2 中断观察并恢复，原始恢复 2468.4/2814.1ms、P95 2796.8ms，恢复后 467/467 帧准时、6/6 轮成功、10/10 RTT≤100ms，100/A、`HIGH/PASS`；该结果不得外推为蜂窝断网、跨网迁移或第三方 AI 服务可用性。

## 6. 网络综合性能 Profile

### 6.1 业务类型和阶段

`network_comprehensive_standard@1.0.0` 回答“当前终端到指定 ANEB 节点的应用层路径，容量、响应性和稳定性是否同时满足 AI 业务”。它不是运营商全网评级。

冻结阶段：

1. DNS/TCP/TLS/协议协商；
2. 空闲 RTT 与请求成功率；
3. 下载 goodput + 同时测 loaded RTT；
4. 上传 goodput + 同时测 loaded RTT；
5. 带序号 UDP echo（若节点支持），测未返回、乱序和变化；
6. 测后空闲 RTT，观察恢复和缓冲膨胀消退。

### 6.2 全量指标与目标

| ID | 指标 | 口径 | provisional v1 目标 |
|---|---|---|---|
| NET-B01 | 下载持续有效速率 | 1s 窗口 goodput | P5≥25Mbps，达标比例≥95% |
| NET-B02 | 上传持续有效速率 | 1s 窗口 goodput | P5≥10Mbps，达标比例≥95% |
| NET-B03 | 空闲 RTT | echo RTT | P95≤100ms，达标比例≥95% |
| NET-B04 | loaded RTT | 吞吐测试并发 echo | P95≤200ms |
| NET-B05 | 负载时延增量 | loaded P95−idle P50 | ≤100ms |
| NET-B06 | RTT 变化 | idle RTT P95−P50 | ≤30ms |
| NET-B07 | 吞吐稳定性 | 1s goodput 的 robust CV | ≤20% |
| NET-B08 | 低速窗口率 | 低于目标的窗口 / 有效窗口 | ≤5% |
| NET-B09 | 应用请求失败率 | HTTP 失败 / 尝试数 | ≤2% |
| NET-B10 | UDP 数据报未返回率 | 带 seq UDP 请求未收到匹配回包 / 发送数 | ≤1%；明确是至节点的 UDP 应用探针 |
| NET-B11 | UDP 乱序率 | 乱序回包 / 收到回包 | ≤0.5% |
| NET-B12 | DNS/TCP/TLS 时延 | 分阶段 P95 | DNS≤500ms，TCP≤500ms，TLS≤1000ms |
| NET-R01 | 无线层协变量 | 设备公开 API 报告值 | 不评分 |

### 6.3 动态主指标

网络综合测试采用“一个业务主指标 + 阶段辅指标”：

- 主指标：`LOADED_RTT_LIVE`，负载中的交互响应性；
- 辅助：下载/上传实时 Mbps、空闲 RTT、RTT 变化、低速窗口率；
- 仪表中心在吞吐阶段仍可显示 Mbps，但顶部主状态始终展示 loaded RTT，避免只追峰值带宽。

### 6.4 Network Comprehensive Score v1

| 组 | 权重 | 指标 |
|---|---:|---|
| 响应性 | 30% | 空闲 RTT 10%，loaded RTT 10%，RTT 变化 5%，握手 5% |
| 容量 | 35% | 下载 20%，上传 15% |
| 稳定性 | 35% | UDP 未返回 10%，应用失败 5%，吞吐稳定 10%，负载时延增量 10% |

UDP 被网络阻断时报告“UDP 应用探针不可用”，不得伪装成精确 IP 丢包率。NET-B10 是必需评分项：探针不可用时该指标和总分均为 `null`，不重分配权重；原始错误证据仍冻结入库。

## 7. 统一评分算法

### 7.1 单指标分数

每个指标使用五个版本化锚点：

```text
excellent -> 100
target    -> 85
acceptable-> 70
minimum   -> 55
failure   -> 0
```

锚点之间分段线性插值，端点外截断。高者优和低者优分别按单调方向排列。所有锚点必须进入 `target_set_id`，调整数字必须升版本并记 Decision Log。

### 7.2 聚合、缺失与门控

```text
group_score = Σ(metric_score × metric_weight_in_group)
total_score = Σ(group_score × group_weight)
```

- 必需指标缺失：`total_score=null`，不得把剩余权重重新分配；
- 可选指标缺失：不影响分数，但在完整性中标注；
- invalid：保留原始事件，抑制 KPI 聚合与分数；
- 门控只允许“封顶”或“不可计算”，不得把失败样本填成 0ms/0Mbps；
- standard、stress、WebSocket、WebRTC、不同 claim scope 禁止混分。

### 7.3 置信度

| 等级 | 条件 |
|---|---|
| high | 所有必需指标达到最小样本量，路径/设备/服务端守卫无污染，重复覆盖完整 |
| medium | run 有效，但一个或多个指标仅达到 50%–99% 最小样本量，或只有单遍快测 |
| low | 观察值存在但不足以给 95% 稳定性结论；只允许描述，不给强建议 |
| invalid | 触发 fail-closed；原始证据保留，评分抑制 |

## 8. 统一结论算法

结论分四层，顺序固定：

1. **完成性**：完成、部分完成、失败；失败阶段与证据；
2. **业务行为特征**：从 Profile 声明读取，不从一次测量猜测；
3. **实测瓶颈**：取最低的必需指标子分，平手按业务影响排序；
4. **网络建议**：逐项输出目标、实测达标比例、样本数和置信度。

示例：

```text
本次多模态 Token 仿真完成 19/20 个任务。
业务特征：上行突发、首响应低时延、持续小包下行、流式稳定性敏感。
主要瓶颈：10MiB 图片上传，1s 窗口速率≥12Mbps 的比例为 71%（目标 95%）。
建议：上行速率≥12Mbps 达标比例应提高到 95%；RTT≤100ms 当前为 97%；
Token 准时到达率当前为 92%，未达 95% 目标。
2 次重试使仿真 Token 传输量增加 8.4%。
```

因果措辞规则：

- 有明确传输超时/复位、节点健康、客户端无污染：可写“任务因传输超时失败”；
- 只有时间相关：写“与 RTT/卡顿恶化同时出现”，不得写“运营商导致”；
- Token 冗余只从 retry/resume 事件计算；无重试证据时不得输出“Token 增加”；
- 无法测量的指标显示 `—` 和原因，不得显示 0。

## 9. 独立 AI 业务行为模型系统

### 9.1 产物

每个模型发布包必须包含：

- `model.json`：工作负载分布、状态转移、参数、种子策略和来源；
- `golden_trace.jsonl`：同模型同 seed 必须生成完全一致的事件序列；
- `validation.json`：训练/留出样本、分位数误差、状态转移误差和接受判据；
- `profile.json`：候选 ANEB Profile v2；
- `manifest.sha256`：上述产物的 SHA-256；JSON/JSONL 采用 UTF-8、键序与分隔符规范化后的语义哈希，排版变化不影响绑定，因此不等同于 pretty-printed 文件的原始字节哈希。

### 9.2 模型状态

| 状态 | 含义 | 是否允许用产品品牌标签 |
|---|---|---|
| hypothesis | 只来自产品需求/专家假设 | 只能写“某类交互原型”，不可声称真实 |
| calibrated | 已拟合一批获准观测数据 | 可写“基于样本的 Kimi-like”等，并附数据范围 |
| validated | 留出集达到版本化误差门限 | 可作为正式 Profile 候选，仍不等于厂商官方基准 |
| retired | 模型过期或被新版本替代 | 历史结果可复现，不再用于新 run |

### 9.3 v0.1 建模算法

优先使用可审计的经验分布，不先假设所有指标服从正态/对数正态：

1. 负载大小：经验分位数 + 分层抽样；
2. Token 事件间隔：`FAST / NORMAL / PAUSE` 三状态 Markov 模型；
3. 簇长度：经验分布或截断几何分布，拟合优度不足时退回经验抽样；
4. 模拟处理时延：按业务类型分层的经验分布；
5. 多轮会话：轮次数、输入/输出比、工具调用次数的联合抽样；
6. 实时语音：说话时长、静音时长、20ms 帧节奏、响应等待、打断概率和回复音频时长；
7. 所有随机生成使用显式 seed，并将 PRNG 算法版本写入模型。

模型只生成**业务侧计划时间表和字节/Token 事件**。网络时延、抖动、失败和重连由 ANEB 执行时真实测得，不得在业务模型中偷偷叠加“假网络劣化”。

### 9.4 校准与验证

建议的留出验证指标：

- payload P50/P95 相对误差；
- TTFT/处理等待 P50/P95 相对误差；
- Token TPS P50/P95 相对误差；
- pause 占比、簇长度和状态转移矩阵差异；
- 上下行字节比；
- 任务时长与轮次数分布；
- 合成 trace 的自相关和 burstiness 与留出集差异。

首版接受线建议为主要 P50/P95 相对误差≤20%，状态转移每行总变差距离≤0.15；这只是建模系统自己的发布门，不进入 App 网络评分。

#### 9.4.1 v0.2.0 as-built（2026-07-18）

- ［KNOWN｜HIGH］已实现 `aneb-token-observation-v1`、`aneb-calibration-dataset-v1` 和 `aneb-model-validation-v1` 三个 Draft 2020-12 合同，并纳入 `spec/catalog.json`。
- ［KNOWN｜HIGH］观测只允许 payload/处理等待/输出 Token 数/Token 间隔/返回字节等派生统计；未知字段 fail-closed，原始内容、账号和 key 不会被静默忽略后继续拟合。
- ［KNOWN｜HIGH］训练/留出同时检查 observation ID 与 dataset-specific HMAC subject group 零重叠；普通 SHA256(account) 不被接受。
- ［KNOWN｜HIGH］每 workload 的训练/留出最小 session 数为 20/10；payload、处理等待、输出 Token 数和 Token 间隔 P50/P95 相对误差门限 20%，pause 占比绝对误差 0.05，转移矩阵逐行 TVD 0.15。
- ［KNOWN｜HIGH］`promote-token` 与 validated runtime 发布都会从冻结 manifest/holdout 复算报告并核对模型摘要，不能只靠可编辑的 PASS 字段晋级。
- ［KNOWN｜HIGH］当前没有真实授权数据或 validated 模型，4 个正式模型继续保持 hypothesis。

## 10. 实施顺序

1. 先实现独立模型工程的 schema、确定性生成器、校验器和三个 hypothesis 原型；
2. 在 ANEB App 中新增 Profile v2 解析与目录展示，但不改变现有 AQS；
3. 新增 Token standard/stress 执行引擎和结论，实机闭环；（已完成）
4. 新增实时语音 WebSocket 仿真引擎和动态仪表；
5. 把 basic network 升为 comprehensive，增加 loaded RTT、窗口达标率和 UDP 探针；
6. Product Owner 批准新门限/权重后，追加 Decision Log、冻结 policy 版本，再让新分数进入正式结果。

## 11. Product Owner 裁定

Product Owner 于 2026-07-16 回复“按推荐方案”，裁定：

1. 批准本文三套 provisional 质量目标与分组权重作为实验性 v1 实施基线；
2. 真实 API 探针从正式产品入口隐藏，代码仅保留为开发诊断能力，不进入新 Profile 或评分。

后续实现裁定见 D-43：Stress 采用独立权重、固定低置信单次结论，并已完成 P40 Pro 100MiB 双向真机闭环。
