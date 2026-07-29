# ANEB Spec 逻辑入口

`spec/` 是 P1 手机端、P2 测试服务器、P3 业务行为模型和 Profile 横切机制的
**治理入口**，不是第三份资产副本。现有 Profile、Schema、行为模型和运行计划仍保留在
原目录，避免打断 Android、Go 和 Python 的构建；`catalog.json` 只提供机器可校验的索引、
版本边界和消费者关系。

## 当前仓库候选边界

| 单元 | 仓库候选版本 | 对目录资产的职责 |
|---|---:|---|
| P1 / ANEB Probe Android | 0.5.14（code 46，Room v19） | ［KNOWN｜HIGH］消费两族 Profile；对 Token、AI 实时和 Network Quick 运行包执行合同与跨语言规范化哈希校验；三种 Quick 均在首个业务请求前校验 P1 引擎、P2 能力回执和精确 Profile 身份，并为同一 run 的控制/业务请求附加脱敏、分族审计 ID；Room 版本、指标、门限和评分均不变 |
| P2 / aneb-server | 0.8.2 | ［KNOWN｜HIGH］解析并下发 4 个服务端根 Profile；启动时校验已发布 Token、AI realtime 与 Network Quick 包，并通过 `/api/v1/serverinfo` 提供版本化能力回执；0.8.2 是 M0-EC3 离线候选，E-01 是否切换必须由受保护部署和验后证据确认 |
| P3 / aneb-ai-behavior-model | 0.3.3 | ［KNOWN｜HIGH］维护 Profile/trace/授权观测/校准数据集/留出验证 Schema；三族 D-110 qualification runtime 由统一 CLI 确定性发布并绑定 approved policy SHA；保留绑定留出报告的 validated 门禁 |
| Profile 横切机制 | 1.11.0 | ［KNOWN｜HIGH］索引全部正式资产，分别冻结三类 Quick 执行合同、D-110 重复性资格政策、三族 qualification 运行包和资格报告 Schema，继续治理执行要求、兼容范围、消费者、完整性和发布方式 |

目录合同使用半开 SemVer 区间：当前消费者声明接受 `>=1.0.0,<2.0.0` 的 catalog。
［KNOWN｜HIGH］M0-EC1/M0-EC2/M0-EC3 为 `token_multimodal_quick@1.2.1`、
`ai_realtime_voice_quick@1.1.1` 与 `network_comprehensive_quick@1.2.0` 接通执行能力握手；三族 qualification Profile 也声明同类受限执行要求但尚未接入 Android/server；其余 9 个 Published Profile 没有
`execution_requirements`，继续走原有兼容路径。任何不兼容字段或语义变化
仍必须先升级 catalog/contract 主版本，再升级消费者。

## 两族 Profile 与三条 v2 校验路径

### 1. 服务端根 Profile

- 资产：`profiles/*.json`，当前 4 个。
- 消费者：P1、P2、Profile。
- 合同：当前 Go/Kotlin 共享的 legacy inline-phase 结构，资产内没有 `contract_version`，也没有独立 JSON Schema。
- 校验：必需字段、`profile_id/version`、版本范围、mode 和非空内嵌 phases。
- `runtime_plan.json` 与 `manifest.sha256` **禁止出现**。

### 2. Published Profile v2

- 资产：`profiles/published/*/profile.json`，当前 15 个。
- 消费者：P1、P2、P3、Profile；P2 当前只消费声明了执行要求的 Quick 子集。
- 合同：`aneb-profile-v2`，引用现有 JSON Schema。
- 该族内部必须按执行语义分成两组，不能用同一个“文件存在即可”的校验代替：

| v2 校验组 | 数量 | mode | 运行资产策略 |
|---|---:|---|---|
| `behavior_runtime_bound` | 8 | Token 4 + AI realtime 4 | 必须同目录包含 `profile.json`、`runtime_plan.json`、`manifest.sha256`；模型、seed、variant、runtime contract 和规范化哈希必须交叉一致；qualification 还必须绑定 approved D-110 policy |
| `network_runtime_bound` | 2 | Network Quick + qualification | 必须同目录包含三件套；无行为模型，但必须冻结 deterministic runtime plan、四项受限原语、seed、variant 和规范化哈希；qualification 还必须绑定 approved D-110 policy |
| `network_embedded_phases` | 5 | 其余 network comprehensive | phases 全部内嵌在 `profile.json`；禁止 `execution_plan`、`behavior_trace`、`runtime_plan.json` 和 `manifest.sha256` |

Network Quick 的运行计划是确定性测量编排，不是 AI 行为模型；其余网络综合 Profile 仍没有独立运行计划，给它们补空 manifest 会制造错误的完整性语义。反过来，任何 runtime-bound Profile 如果缺 manifest，P1 就无法证明 Profile 与运行计划绑定，必须
fail closed。

## Token Quick 执行能力握手（M0-EC1）

- ［KNOWN｜HIGH］`token_multimodal_quick@1.2.1` 是当前唯一必须携带
  `aneb-execution-requirements@1.0.0` 的 Profile；规范化 Profile SHA-256 固定为
  `caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773`。其模型派生运行计划
  固定包含 task-0006 的 1MiB 返回附件，确保声明的 download 在 Quick 中真实执行。
- ［KNOWN｜HIGH］P1 引擎身份固定为 `aneb-token-simulation-engine@1.0.0`；P2 回执合同固定为
  `aneb-server-capability-receipt@1.0.0`。双方均按 Profile 声明的半开 SemVer 范围校验。
- ［KNOWN｜HIGH］Quick 精确要求三项白名单原语：`echo/aneb-echo-v1`、
  `token_sim/aneb-token-task-v1`、`download/aneb-download-v1`。Profile 不能携带任意 URL、
  命令或脚本。
- ［KNOWN｜HIGH］P1 可先请求 `/api/v1/serverinfo` 获取能力回执，但必须在第一个 echo、
  token-sim 或 download 业务请求前完成校验。合同版本、Profile ID/版本/哈希、必需原语或线路
  合同任一缺失/冲突时 fail closed。
- ［KNOWN｜HIGH］P1 可以忽略服务器声明的未知额外 capability；同一回执中的重复原语 ID 必须
  拒绝，避免顺序或覆盖规则产生歧义。
- ［KNOWN｜HIGH］该切片不修改业务指标、质量目标、门限、AQS 权重、结论算法、Room schema
  或结果合同。

## Token Quick request-entry 精确计数证据合同

- ［KNOWN｜HIGH］`spec/execution-contracts/token_multimodal_quick-1.2.1.request-entry.json`
  是 `aneb-token-quick-request-entry-counts@1.0.0` 的唯一机器定义；仅适用于客户端已完成的正向 run。
- ［KNOWN｜HIGH］20 次 echo 来自 `aneb-token-simulation-engine@1.0.0` 的生产常量，3 次
  token-sim 和 1 次 download 来自 Quick 1.2.1 冻结运行计划。合同同时绑定 Engine 身份、Profile/runtime
  规范化摘要和精确计数；Kotlin 回归交叉核对生产常量、任务数和附件任务数。
- ［KNOWN｜HIGH］catalog 1.6.0 登记该合同并冻结其规范化 SHA-256；目录清单拒绝未索引、缺失、重复或
  身份/摘要漂移。服务端审计判定器只从该合同读取计数，缺失或畸形时 fail closed。
- ［KNOWN｜HIGH］request-entry 计数只证明请求进入服务端审计边界，不证明响应成功、客户端接收或评分；
  正式结论仍须和同 run 客户端冻结结果及 D-82 原始来源/新鲜度证据组合。

## Network Quick 执行与 request-entry 证据合同

- ［KNOWN｜HIGH］`spec/execution-contracts/network_comprehensive_quick-1.2.0.protocol.json`
  冻结 `aneb-network-quick-protocol-bounds@1.0.0`：Profile/runtime 摘要、P1 Engine 身份、
  四个业务原语和 `aneb-udp-echo-v2` 线协议必须同时匹配。
- ［KNOWN｜HIGH］正向服务端窗口要求 capability 先于业务；HTTP 至少进入 18 次 echo、4 次
  download、2 次 upload，UDP 必须恰好进入 50 个 256-byte 数据报且序号为 0..49。负向窗口
  只允许 capability control，全部 HTTP/UDP 业务为零。
- ［KNOWN｜HIGH］HTTP 与 UDP 共用进程级审计实例、FIFO 序号和 drop 可见性；旧 `ANEB1` 只保留
  无归属回显兼容，不能成为 Network Quick 同-run 证据。判定仍只证明 request entry，必须与冻结
  Room 结果、APK/E-01 provenance 共同使用，应用数据报未返回不得表述为 IP 层丢包。

## 哈希合同

`canonical-json-sha256-v1` 的输入不是 JSON 文件原始字节，而是：

1. 按 UTF-8 解析 JSON，拒绝重复 key 和非有限数；
2. 对 object key 排序，使用紧凑分隔符，Unicode 不转义为 ASCII；
3. 对规范化后的 UTF-8 字节计算 SHA-256，输出小写十六进制；
4. manifest 行固定为 `<digest><两个空格><basename>`；Profile 内引用固定加 `sha256:` 前缀。

所以只改变缩进或换行不会改变语义哈希。`manifest.sha256` 必须且只能列出同目录的
`profile.json` 和 `runtime_plan.json`。行为模型引用也必须等于 catalog 中对应
`model_id + model_version` 文件的同一种规范化哈希。

## 已索引 Schema 与已知缺口

- `aneb-profile-v2.schema.json`：供 P1、P3、Profile 使用。
- `aneb-behavior-trace-v1.schema.json`：供 P3、Profile 使用。
- `spec/schemas/aneb-result-core-v1.schema.json`：只供版本化结果 Schema 复用的内部结构核心，不是可由 App 发出的公开版本。
- `spec/schemas/aneb-result-v1.schema.json`：已发布 v1 的兼容验证器；允许 0.5.6 之前没有任务对齐 TTFT 三字段的历史 Token 任务，也允许后来同名 v1 生产者发出的可选三字段。
- `spec/schemas/aneb-result-v2.schema.json`：当前三类正式测试结果合同；Token 任务强制包含 `task_id/server_processing_ms/ttft_ms`，并继续冻结 run/verdict、缺失/invalid/无线未采集语义。
- `aneb-token-observation-v1.schema.json`：只允许 P3 Token 校准所需的派生 session 统计，未知字段拒绝。
- `aneb-calibration-dataset-v1.schema.json`：冻结授权、观测范围、主体隔离训练/留出分区和规范化摘要。
- `aneb-model-validation-v1.schema.json`：冻结候选模型、数据集和逐 workload 留出门限结果。
- `aneb-token-runtime-plan-v1` 与 `aneb-realtime-runtime-plan-v1` 已有合同 ID、Kotlin/Python
  实现和本目录的结构校验，但还没有独立 JSON Schema；catalog 明确将
  `standalone_schema_path` 设为 `null`，不把实现类冒充 Schema。
- `aneb-token-quick-request-entry-counts@1.0.0` 是 catalog 登记的执行证据合同，不是运行计划或结果
  Schema；它的规范化摘要、Profile/runtime 绑定与 Engine 身份由独立门禁校验。
- 服务端根 Profile 仍是 legacy 合同。这一入口先把分叉和消费者写清楚，不在 M0 第一刀移动
  文件或假装两族已经统一。
- `app/probe/schemas/` 是 Room 数据库迁移快照，Android `AndroidManifest.xml` 是组件清单；
  它们都不是 P1/P2/P3 共享的业务合同或运行包 manifest，因此不进入本 catalog。

## 校验

从仓库根目录运行：

```powershell
python scripts/verify_spec_catalog.py
python scripts/verify_result_schema.py
python scripts/verify_result_jsonl.py <export.jsonl>
python -m unittest tools/aneb-ai-behavior-model/tests/test_spec_catalog.py -v
python -m unittest tools/aneb-ai-behavior-model/tests/test_result_schema.py -v
```

catalog 校验器只使用 Python 标准库；结果 Schema/JSONL 校验器使用 `jsonschema` 与 `referencing`。所有路径都采取 fail-closed 策略：

- catalog 或任何 JSON 不能解析、存在重复 key 或非有限数时失败；
- execution evidence contract 缺失、未索引、重复、身份/规范化摘要或 Profile/runtime/Engine 绑定漂移时失败；
- 引用缺失、越出仓库、ID/版本不一致、重复 Profile ID 时失败；
- Schema、模型、根 Profile、published Profile、runtime plan 或 manifest 有未索引文件时失败；
- Token/AI realtime 的 runtime contract、模型、seed、variant、条目计数或语义哈希不一致时失败；
- Network Quick 的 deterministic runtime contract、seed、variant、阶段、四项原语或语义哈希不一致时失败；其余 Network Profile 声明 runtime manifest 时失败；
- catalog 出现当前校验器不认识的字段时失败，要求先同步升级校验器。

catalog 校验器负责目录完整性、交叉引用和当前合同不变量；`verify_result_schema.py` 使用
`jsonschema` 的 Draft 2020-12 同时校验共享核心、兼容 v1、严格 v2、三个类别样例和跨版本冻结任务向量；
`verify_result_jsonl.py` 按每条记录自身的 `schema_version` 路由验证器，版本不支持与结构损坏分开报告。P3 的
Schema 级生成测试和 P1 的 capability/integrity/结果信封测试仍须保留，各层不能互相替代。

## 版本化结果合同当前接线状态

- ［KNOWN｜HIGH］Token、AI 实时和网络综合的新 run 均在 Room v19 中把类型化结果与统一结果信封同事务落库；
  Profile/运行计划指纹（Network Quick 适用，其余网络综合为不适用）、评分审计字段与原始证据在引擎最终化时冻结。
- ［KNOWN｜HIGH］App 0.5.8 的三类新 run 统一发出 `aneb-result-v2`；v1 兼容验证器保留已发布历史，v2 对 Token 任务对齐字段执行严格必填。共享核心没有公开版本身份，App 不得发出它。
- ［KNOWN｜HIGH］JSONL 核心按开始时间/run id 确定排序，支持 v1/v2，核验 run/test/schema 身份和规范化 SHA-256 后原样输出 `bodyJson`，不解析重算；未来版本暂不支持与摘要/身份异常使用不同错误类型和用户提示。
- ［KNOWN｜HIGH］三类结果页均可将单条冻结信封保存为 JSONL 或以文件分享；旧记录没有信封时明确禁用，不做历史补算。
- ［KNOWN｜HIGH］App 0.5.6 的三个正式新引擎均以每 run 独立 RadioCollector 采集 1Hz Android 公开无线环境证据，并把类型化结果、信封、无线样本和环境事件同事务冻结；R01 使用 `observed + null scalar + sample_count + radio-context evidence ref` 引用时间序列，不虚构单值。
- ［KNOWN｜HIGH］权限拒绝、设备不可用和未采集分别写 `permission_denied`、`unavailable`、`not_collected`；可分享信封移除经纬度。0.5.5 的规范化数字词法已用 Python 冻结向量锁定，避免 JVM/Python 指数表示差异造成摘要漂移。
- ［KNOWN｜HIGH］Token 原始任务证据自 0.5.6 起冻结稳定 `task_id`、服务端处理时延和“节点确认上传完成→App 收到首 Token”的端到端 TTFT；B03/B04 由同一冻结证据生成。`scripts/analyze_ttft_repeatability.py` 接受单一 v1 或单一 v2 的同 Profile/运行包/App/设备/节点/承载相邻 cohort，但拒绝混合版本，以至少 5 个 run 的任务对齐样本 CV 中位数执行 ≤10% 的 fail-closed 复现性判据。

## P3 校准发布闸门

- ［KNOWN｜HIGH］P3 v0.3.3 保留 v0.2.0 的校准门禁：只接受获授权、派生统计字段白名单的 Token session；训练与留出 observation ID、HMAC subject group 必须同时零重叠；0.3.1 保证 Token Quick 覆盖 download，0.3.2 为 AI realtime Quick 冻结单一 realtime 原语与精确帧签名合同，0.3.3 新增三族 D-110 qualification 确定性发布与严格 policy binding。
- ［KNOWN｜HIGH］calibrated 候选只由 training 拟合，holdout 报告按 `token-holdout-validation-v1` 独立生成；任一 P50/P95、pause 或转移矩阵门限失败即禁止 promote。
- ［KNOWN｜HIGH］validated 模型 build/runtime 发布必须携带原报告和 dataset manifest，并现场重算报告与 promoted 模型；单独篡改 `status=pass` 无效。
- ［KNOWN｜HIGH］当前 4 个 catalog 模型仍全部为 hypothesis；Schema 和流水线存在不等于真实业务校准已经完成。

## 新增或升级资产

1. 先判断是已有原语内的 Profile 变体，还是新增 phase/测量语义；后者必须先升级合同和消费者。
2. 发布即冻结；修改既有正式 Profile 必须升级 `version`。
3. Token/AI realtime 与 Network Quick 必须生成完整三件套并重算规范化 manifest；其余 Network Profile 必须保持单文件内嵌 phases。
4. 在 `catalog.json` 登记 ID、版本、路径、验证组与消费者；禁止只把文件丢进目录。
5. 串行通过 catalog 校验、P3 测试、P1 integrity/capability 测试和 P2 对应原语测试后再发布。
