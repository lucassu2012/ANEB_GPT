# P3 行为模型授权校准流水线 v0.2.0

> 日期：2026-07-18（Asia/Shanghai）
>
> 范围：`tools/aneb-ai-behavior-model/`

## 1. 先讲边界

- ［KNOWN｜HIGH］仓库当前没有获授权的真实业务观测数据，也没有 calibrated/validated 正式模型；4 个已索引模型仍全部是 `hypothesis`。
- ［KNOWN｜HIGH］本阶段完成的是可执行、可审计的治理与算法流水线，不是 Kimi、DeepSeek、千问或任何厂商真实画像的校准结果。
- ［KNOWN｜HIGH］`validated` 只表示“获授权派生统计在主体隔离留出集上达到版本化误差门限”，仍不等于厂商官方基准、生产 SLA 或网络质量结论。

## 2. 关闭的旧漏洞

旧 `fit-token` 可直接把任意 session JSONL 写成 `calibrated`，缺少四个关键证明：数据是否获授权、训练/留出是否泄漏、输入是否夹带原始内容、报告是否绑定到确切模型和数据。

v0.2.0 删除这条公开捷径，改为三段式命令：

1. `prepare-token-dataset`：严格白名单字段，核验授权用途，规范化训练/留出文件并冻结摘要；
2. `calibrate-token`：只用 training 拟合，只用 holdout 验证，输出 calibrated 候选和独立报告；
3. `promote-token`：重新读取数据集、复算完整报告、核对候选摘要后才生成 validated 模型。

［KNOWN｜HIGH］validated 模型在 `build` 或 `publish-runtime` 时仍必须重新提供报告和数据集 manifest；缺任一证据、候选/报告/数据被改动或复算不一致都会拒绝发布。calibrated 候选永远不能直接发布 runtime。

## 3. 三个新合同

| 合同 | 作用 | 关键红线 |
|---|---|---|
| `aneb-token-observation-v1` | 单 session 派生行为统计 | `additionalProperties=false`；不允许 prompt/content/account/key；主体只允许数据集专用 HMAC-SHA256 |
| `aneb-calibration-dataset-v1` | 授权、范围、训练/留出与摘要 manifest | 用途必须包含 `behavior_model_calibration`；`content_retained=false`；observation 与 subject 两级零重叠 |
| `aneb-model-validation-v1` | 候选模型的留出验证报告 | 固定 policy、候选摘要、数据集摘要、逐 workload 检查和失败项 |

［KNOWN｜HIGH］普通 `SHA256(account)` 不构成足够的去标识化。`subject_group_id` 必须使用数据集专用秘密密钥的 HMAC-SHA256；密钥不写入数据包或仓库，不同数据集更换密钥，既能检查同数据集泄漏，也降低跨数据集关联风险。

## 4. 首版验证算法

每个模板 workload 至少需要 training 20 个、holdout 10 个 session。`token-holdout-validation-v1` 分 workload 比较：

- payload bytes、处理等待、输出 Token 数、Token 间隔的 P50/P95 相对误差均 `<=20%`；
- `PAUSE (>200ms)` 占比绝对误差 `<=0.05`；
- FAST/NORMAL/PAUSE Markov 转移矩阵每行总变差距离 `<=0.15`。

任一检查失败则报告为 FAIL，不能 promote。该门限只约束业务行为模型，不进入 ANEB App 网络评分。

## 5. 证据绑定

- 训练和留出 JSONL 使用 UTF-8、键排序、紧凑分隔符、每行换行的规范化语义 SHA-256；格式变化不改变语义，内容变化必然改变摘要。
- calibrated 模型冻结 dataset ID/version、manifest 摘要、训练摘要/样本数、授权基础，并明确 `content_retained=false`。
- validation 报告冻结 calibrated 模型摘要、数据集 manifest 摘要和两个分区的摘要/计数。
- validated 模型冻结报告摘要、calibrated 前身摘要和留出摘要；发布时从原 manifest/holdout 重新复算，不信任报告里的 `status=pass` 单字段。

## 6. 自动化验收

［COMPUTED｜HIGH］行为模型 31 项测试通过，覆盖完整 CLI 链和以下反例：未授权元数据、未知内容字段、训练/留出主体重叠、分区摘要篡改、样本覆盖不足、劣化留出 FAIL、候选摘要篡改、报告内容篡改，以及 validated runtime 缺证据拒绝发布。

［COMPUTED｜HIGH］`spec/catalog.json` 当前索引 6 个 Schema、2 个 Profile 家族、16 个 Profile、6 个运行包、6 个内嵌网络 Profile 和 4 个 hypothesis 模型；未索引 Schema 或版本漂移继续 fail-closed。

## 7. 下一真实输入

要产生第一个 validated 模型，Product Owner 需要提供或批准一批合法来源的**派生 session 统计**，并明确授权基础、用途、时间窗、地域桶、设备类别与采集方法。原始 prompt、文档、图片、视频、回复内容、账号和 API key 均不需要，也不应交给该流水线。

在真实数据到位前，继续用 hypothesis Profile 做网络测量是允许的，但对外只能称“产品假设驱动的可重复仿真”。
