# ANEB AI Behavior Model

独立的 AI 真实业务行为建模与 ANEB Profile 生成工程。

本工程不属于 ANEB Android App，也不调用 Kimi、DeepSeek、千问等真实 API。它负责把获准的业务观测数据或显式产品假设转成：

- 版本化 `model.json`；
- 可复现 `golden_trace.jsonl`；
- 供 App 精确执行、经哈希绑定的紧凑 `runtime_plan.json`；
- 模型校准/验证报告；
- 候选 ANEB Profile Contract v2。

ANEB App 只消费冻结后的 Profile，不执行拟合，不把第三方服务波动带入网络评分。

## 状态

`v0.3.1` 可运行纵向切片（保留 v0.2.0 校准闸门、v0.3.0 跨端执行要求，并让 Token Quick 真实覆盖声明的下载原语）：

- PCG32 跨语言确定性随机数；
- Token `FAST / NORMAL / PAUSE` 三状态 Markov + 状态内经验分布；
- 多模态上传、模拟处理、Token 流和返回文件金轨迹；
- 独立 Token Stress 运行计划：100MiB 视频上传 + 100MiB 大对象返回，不与 Standard 混分；
- 实时语音 20ms 双向帧、轮次、等待和打断金轨迹；
- 独立实时语音 Recovery 运行计划：固定模型派生恢复刺激 + 2 次可审计受控中断，不污染 Standard；
- 授权、隐私最小化的 Token 观测 JSONL → subject-disjoint 训练/留出数据包；
- 训练集拟合、留出集独立验证和摘要绑定的 `calibrated → validated` 发布闸门；
- Profile v2、验证报告和 SHA-256 manifest 导出；JSON/JSONL 条目使用 UTF-8 规范化语义哈希，排版变化不影响绑定，不等同于 pretty-printed 文件的原始字节哈希；
- Token Quick Profile 发布固定版本化 `execution_requirements`，只声明 `echo`、`token_sim`、`download` 三项白名单原语及对应线路合同，不允许任意 URL 或脚本；
- 业务轨迹禁止包含网络时延、丢包和实测 RTT。

详细合同见：

- `../aneb-probe-codex-v0.2.0/docs/PROFILE_CONTRACT_V2_PROPOSAL_2026-07-16.md`

## 计划目录

```text
schemas/          模型、事件轨迹和 Profile schema
src/              拟合、生成、验证和导出实现
models/           已发布模型；hypothesis/calibrated/validated 状态明确
examples/         不含敏感内容的示例输入与输出
tests/            确定性、schema、统计方向和回归测试
```

## 运行

无需第三方 Python 包：

```powershell
$env:PYTHONPATH='src'
python -m unittest discover -s tests -v

python -m aneb_behavior_model.cli build `
  --model models\token_multimodal_hypothesis_v0.1.json `
  --seed 20260716 `
  --out build\token

python -m aneb_behavior_model.cli build `
  --model models\ai_realtime_voice_hypothesis_v0.1.json `
  --seed 20260716 `
  --out build\realtime

# 只发布 App 运行所需的小型、可追踪产物
python -m aneb_behavior_model.cli publish-runtime `
  --model models\token_multimodal_hypothesis_v0.1.json `
  --seed 20260716 `
  --out ..\..\profiles\published\token_multimodal_standard

python -m aneb_behavior_model.cli publish-runtime `
  --model models\token_multimodal_stress_hypothesis_v0.1.json `
  --variant stress `
  --seed 20260716 `
  --out ..\..\profiles\published\token_multimodal_stress

python -m aneb_behavior_model.cli publish-runtime `
  --model models\ai_realtime_voice_hypothesis_v0.2.json `
  --variant recovery `
  --seed 20260716 `
  --out ..\..\profiles\published\ai_realtime_voice_recovery
```

普通 hypothesis 构建目录包含：`model.json`、`golden_trace.jsonl`、`profile.json`、
结构检查 `validation.json`、`manifest.sha256`；Token 模型另含 `runtime_plan.json`。
validated 模型构建时还必须显式提供原数据集 manifest 和留出报告，输出
`holdout_validation.json` 并纳入 manifest。

## Token 校准流水线

原始内容、账号、API key 不属于合同。每条 session 只允许以下派生统计字段；任何未知字段都会 fail-closed 拒绝，而不是静默忽略：

```json
{
  "observation_contract_version": "aneb-token-observation-v1",
  "observation_id": "obs-session-0001",
  "subject_group_id": "hmac-sha256:<dataset-secret-derived-64-hex>",
  "workload_kind": "document",
  "payload_bytes": 5242880,
  "processing_delay_ms": 1430,
  "output_token_count": 812,
  "token_intervals_ms": [31, 28, 45, 310, 36],
  "response_artifact_bytes": 0
}
```

`subject_group_id` 必须使用数据集专用密钥生成 HMAC-SHA256；密钥不得写入数据包或 Git。同一主体的所有 session 使用同一 HMAC，以便检查训练/留出泄漏，但不同数据集使用不同密钥，避免跨数据集关联。

第一步由研究者在本地预先分配训练/留出集，并提供真实授权元数据。流水线会重新校验主体零重叠、白名单字段、授权用途和规范化摘要：

```powershell
python -m aneb_behavior_model.cli prepare-token-dataset `
  --training datasets\source\training.jsonl `
  --holdout datasets\source\holdout.jsonl `
  --metadata examples\calibration_metadata.pending.json `
  --dataset-id authorized-token-cohort `
  --dataset-version 1.0.0 `
  --out datasets\authorized-token-cohort

python -m aneb_behavior_model.cli calibrate-token `
  --template models\token_multimodal_hypothesis_v0.1.json `
  --dataset-manifest datasets\authorized-token-cohort\dataset_manifest.json `
  --candidate-version 0.2.0 `
  --out build\token-candidate

python -m aneb_behavior_model.cli promote-token `
  --model build\token-candidate\calibrated_model.json `
  --validation build\token-candidate\validation.json `
  --dataset-manifest datasets\authorized-token-cohort\dataset_manifest.json `
  --out models\token_multimodal_validated_v0.2.json
```

`examples/calibration_metadata.pending.json` 故意是 `pending`，不能直接通过；只有存在真实授权记录时，才能在本地副本中改为 `authorized`。训练/留出对模板的每个 workload 分别至少需要 20/10 个 session。

验证策略 `token-holdout-validation-v1` 要求 payload、处理等待、输出 Token 数和 Token 间隔的 P50/P95 相对误差均 ≤20%，pause 占比绝对误差 ≤0.05，FAST/NORMAL/PAUSE 转移矩阵每行总变差距离 ≤0.15。报告为 FAIL 时不能 promote；报告、候选模型或数据集任一字节语义改变，也不能 promote 或发布 runtime。

validated runtime 发布还必须重新提供留出报告和数据集 manifest，工具会现场复算报告及 promoted 模型：

```powershell
python -m aneb_behavior_model.cli publish-runtime `
  --model models\token_multimodal_validated_v0.2.json `
  --validation build\token-candidate\validation.json `
  --dataset-manifest datasets\authorized-token-cohort\dataset_manifest.json `
  --variant standard `
  --seed 20260716 `
  --out ..\..\profiles\published\token_multimodal_standard
```

当前仓库没有任何授权真实数据集或 validated 模型；4 个已发布模型仍全部是 `hypothesis`。

## 红线

- 未校准模型不得声称是任何厂商产品的真实表现；
- 业务模型不注入假网络劣化；网络影响由 ANEB 实测；
- 相同模型版本、seed 和参数必须生成字节级一致的事件轨迹；
- 原始业务内容、账号、API key 不进入模型产物或 Git；
- 普通 SHA256(account) 不能充当主体匿名化；必须使用数据集专用密钥的 HMAC-SHA256，且密钥不留存；
- 训练/留出 observation_id 与 subject_group_id 必须同时零重叠；
- 每个正式模型发布包必须附哈希和验证报告。
