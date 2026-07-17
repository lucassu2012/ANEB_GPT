# ANEB Spec 逻辑入口

`spec/` 是 P1 手机端、P2 测试服务器、P3 业务行为模型和 Profile 横切机制的
**治理入口**，不是第三份资产副本。现有 Profile、Schema、行为模型和运行计划仍保留在
原目录，避免打断 Android、Go 和 Python 的构建；`catalog.json` 只提供机器可校验的索引、
版本边界和消费者关系。

## 当前边界

| 单元 | 当前版本 | 对目录资产的职责 |
|---|---:|---|
| P1 / ANEB Probe Android | 0.5.5 | 消费两族 Profile；对 Token/AI 实时运行包执行合同与跨语言规范化哈希校验；三类新 run 同事务发出统一结果信封、1Hz 公开 Android 无线环境证据并支持 JSONL 导出/分享 |
| P2 / aneb-server | 0.7.0 | 解析并下发 4 个服务端根 Profile；为 v2 Profile 提供白名单测量原语，但当前不解析整份 v2 Profile |
| P3 / aneb-ai-behavior-model | 0.1.0 | 维护现有 Schema 和 hypothesis 模型；生成带运行计划的 v2 发布包 |
| Profile 横切机制 | 1.0.0 | 索引全部正式资产，约束兼容范围、消费者、完整性和发布方式 |

目录合同使用半开 SemVer 区间：当前消费者声明接受 `>=1.0.0,<2.0.0` 的 catalog。
这是一项治理声明，不代表现有 P1/P2 已实现远端版本协商；任何不兼容字段或语义变化都必须
先升级 catalog/contract 主版本，再升级消费者。

## 两族 Profile 与两条 v2 校验路径

### 1. 服务端根 Profile

- 资产：`profiles/*.json`，当前 4 个。
- 消费者：P1、P2、Profile。
- 合同：当前 Go/Kotlin 共享的 legacy inline-phase 结构，资产内没有 `contract_version`，也没有独立 JSON Schema。
- 校验：必需字段、`profile_id/version`、版本范围、mode 和非空内嵌 phases。
- `runtime_plan.json` 与 `manifest.sha256` **禁止出现**。

### 2. Published Profile v2

- 资产：`profiles/published/*/profile.json`，当前 12 个。
- 消费者：P1、P3、Profile。
- 合同：`aneb-profile-v2`，引用现有 JSON Schema。
- 该族内部必须按执行语义分成两组，不能用同一个“文件存在即可”的校验代替：

| v2 校验组 | 数量 | mode | 运行资产策略 |
|---|---:|---|---|
| `behavior_runtime_bound` | 6 | Token 3 + AI realtime 3 | 必须同目录包含 `profile.json`、`runtime_plan.json`、`manifest.sha256`；模型、seed、variant、runtime contract 和规范化哈希必须交叉一致 |
| `network_embedded_phases` | 6 | network comprehensive | phases 全部内嵌在 `profile.json`；禁止 `execution_plan`、`behavior_trace`、`runtime_plan.json` 和 `manifest.sha256` |

网络综合 Profile 没有外部行为模型运行计划，因此给它补一个空 manifest 会制造错误的完整性
语义。反过来，Token/AI realtime 如果缺 manifest，P1 就无法证明 Profile 与运行计划绑定，必须
fail closed。

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
- `spec/schemas/aneb-result-v1.schema.json`：P1/P3/Profile 共用的三类正式测试结果合同；
  明确区分 run 状态与评估 verdict，冻结缺失/invalid/无线未采集语义，并禁止导出阶段重算。
- `aneb-token-runtime-plan-v1` 与 `aneb-realtime-runtime-plan-v1` 已有合同 ID、Kotlin/Python
  实现和本目录的结构校验，但还没有独立 JSON Schema；catalog 明确将
  `standalone_schema_path` 设为 `null`，不把实现类冒充 Schema。
- 服务端根 Profile 仍是 legacy 合同。这一入口先把分叉和消费者写清楚，不在 M0 第一刀移动
  文件或假装两族已经统一。
- `app/probe/schemas/` 是 Room 数据库迁移快照，Android `AndroidManifest.xml` 是组件清单；
  它们都不是 P1/P2/P3 共享的业务合同或运行包 manifest，因此不进入本 catalog。

## 校验

从仓库根目录运行：

```powershell
python scripts/verify_spec_catalog.py
python scripts/verify_result_schema.py
python -m unittest tools/aneb-ai-behavior-model/tests/test_spec_catalog.py -v
python -m unittest tools/aneb-ai-behavior-model/tests/test_result_schema.py -v
```

校验器只使用 Python 标准库，并采取 fail-closed 策略：

- catalog 或任何 JSON 不能解析、存在重复 key 或非有限数时失败；
- 引用缺失、越出仓库、ID/版本不一致、重复 Profile ID 时失败；
- Schema、模型、根 Profile、published Profile、runtime plan 或 manifest 有未索引文件时失败；
- Token/AI realtime 的 runtime contract、模型、seed、variant、条目计数或语义哈希不一致时失败；
- Network Profile 声明或出现 runtime manifest 时失败；
- catalog 出现当前校验器不认识的字段时失败，要求先同步升级校验器。

catalog 校验器负责目录完整性、交叉引用和当前合同不变量；`verify_result_schema.py` 使用
`jsonschema` 的 Draft 2020-12 实现校验三个合法样例、非法运行与无线/缺失值反例。P3 的
Schema 级生成测试和 P1 的 capability/integrity/结果信封测试仍须保留，各层不能互相替代。

## `aneb-result-v1` 当前接线状态

- ［KNOWN｜HIGH］Token、AI 实时和网络综合的新 run 均在 Room v19 中把类型化结果与统一结果信封同事务落库；
  Profile/运行计划指纹（网络综合为不适用）、评分审计字段与原始证据在引擎最终化时冻结。
- ［KNOWN｜HIGH］JSONL 核心按开始时间/run id 确定排序，核验 run/test/schema 身份和规范化
  SHA-256 后原样输出 `bodyJson`，不解析重算。
- ［KNOWN｜HIGH］三类结果页均可将单条冻结信封保存为 JSONL 或以文件分享；旧记录没有信封时明确禁用，不做历史补算。
- ［KNOWN｜HIGH］App 0.5.5 的三个正式新引擎均以每 run 独立 RadioCollector 采集 1Hz Android 公开无线环境证据，并把类型化结果、信封、无线样本和环境事件同事务冻结；R01 使用 `observed + null scalar + sample_count + radio-context evidence ref` 引用时间序列，不虚构单值。
- ［KNOWN｜HIGH］权限拒绝、设备不可用和未采集分别写 `permission_denied`、`unavailable`、`not_collected`；可分享信封移除经纬度。0.5.5 的规范化数字词法已用 Python 冻结向量锁定，避免 JVM/Python 指数表示差异造成摘要漂移。

## 新增或升级资产

1. 先判断是已有原语内的 Profile 变体，还是新增 phase/测量语义；后者必须先升级合同和消费者。
2. 发布即冻结；修改既有正式 Profile 必须升级 `version`。
3. Token/AI realtime 必须生成完整三件套并重算规范化 manifest；Network 必须保持单文件内嵌 phases。
4. 在 `catalog.json` 登记 ID、版本、路径、验证组与消费者；禁止只把文件丢进目录。
5. 串行通过 catalog 校验、P3 测试、P1 integrity/capability 测试和 P2 对应原语测试后再发布。
