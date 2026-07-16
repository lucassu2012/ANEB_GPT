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

`v0.1.0` 可运行纵向切片：

- PCG32 跨语言确定性随机数；
- Token `FAST / NORMAL / PAUSE` 三状态 Markov + 状态内经验分布；
- 多模态上传、模拟处理、Token 流和返回文件金轨迹；
- 独立 Token Stress 运行计划：100MiB 视频上传 + 100MiB 大对象返回，不与 Standard 混分；
- 实时语音 20ms 双向帧、轮次、等待和打断金轨迹；
- session JSONL → calibrated Token 模型拟合；
- Profile v2、验证报告和 SHA-256 manifest 导出；
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
```

输出目录固定包含：`model.json`、`golden_trace.jsonl`、`profile.json`、
`validation.json`、`manifest.sha256`；Token 模型另含 `runtime_plan.json`。

## Token 观测输入

`fit-token` 接受 session 级 JSONL；只读取以下统计字段，未知字段和原始内容不会进入模型：

```json
{
  "workload_kind": "document",
  "payload_bytes": 5242880,
  "processing_delay_ms": 1430,
  "output_token_count": 812,
  "token_intervals_ms": [31, 28, 45, 310, 36]
}
```

```powershell
python -m aneb_behavior_model.cli fit-token `
  --template models\token_multimodal_hypothesis_v0.1.json `
  --observations datasets\authorized_token_sessions.jsonl `
  --out models\token_multimodal_calibrated_v0.1.json
```

拟合后状态为 `calibrated`；只有再通过留出集验证并形成独立证据，才可升为
`validated`。

## 红线

- 未校准模型不得声称是任何厂商产品的真实表现；
- 业务模型不注入假网络劣化；网络影响由 ANEB 实测；
- 相同模型版本、seed 和参数必须生成字节级一致的事件轨迹；
- 原始业务内容、账号、API key 不进入模型产物或 Git；
- 每个正式模型发布包必须附哈希和验证报告。
