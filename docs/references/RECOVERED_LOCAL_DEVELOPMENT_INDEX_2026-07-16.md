# ANEB 历史本地开发资产恢复索引

> 恢复日期：2026-07-16
>
> 状态：`NON_AUTHORITATIVE_HISTORICAL_REFERENCE`
>
> 目的：登记此前本地开发资产的来源、完整性、验证边界与可复用经验，避免历史成果丢失或被误当成当前产品事实。

## 1. 使用边界

这些材料来自此前的 ANEB 协议仿真与 Android Echo/M2 工作线，不是
`ANEB_GPT` 当前 Android/Kotlin + Go 产品线的直接 Git 前身，也不是可直接升级的旧版本。

当前事实优先级仍为：

1. 根目录 `AGENTS.md` 规定的测量与声明纪律；
2. `docs/CODEX_BASELINE.md`、`docs/DECISION_LOG.md`，尤其 D-36、D-37；
3. 当前代码、自动化测试和与当前 commit 绑定的证据；
4. 本索引及其历史快照，仅用于方法参考和来源追踪。

旧材料中的“单一事实源”、评分、门限、Profile、成熟度或测试结论均不能覆盖当前基线。
任何数值语义若要进入当前产品，必须按当前合同重新实现、验证，并在需要时追加 Product
Owner 决策记录。

## 2. 已找回资产与处置

| 资产 | SHA-256 | 处置 |
|---|---|---|
| `ANEB_1.2.0rc1_工程验证RC完整开发包.zip` | `749f6b4cec0aeca436a0d3e64d7596e43849dec032937d6ced88f669c242a020` | `HISTORICAL_REFERENCE / NOT_IMPORTED`；只审计，不把旧 Python/FastAPI 源码、配置或证据并入当前实现 |
| `ANEB_validation_summary_v1.2.0rc1.json` | `fefd4fda4f95415ea13a7eec74830f223dbcb533dd13a45eb964784bff575c22` | 安全的精简历史快照已保存到 `snapshots/` |
| `ANEB_工程开发与模拟验证报告_v1.2.0rc1.md` | `98880b9ee74e918cf307931f25f1225eb46fc31ccb2aea6fe7df18fbb0408014` | `INDEXED_ONLY`；结论在本索引摘要，不复制全文 |
| `ANEB_开发设计文档_v3.1_工程验证RC版.md` | `5b57906079511b9fcc64354167f56c0041645fb8e0c391c1e6e3cfe25e5bcb9b` | `INDEXED_ONLY`；历史设计，不作为当前规范 |
| `ANEB_开发设计与Claude_Code交接_v3.0.md` | `f79a249d63b37d5859ccc63a6d278b9fe141a4a94f94b6ecc97a25767d458bf7` | `INDEXED_ONLY`；保留失败到修复的版本脉络 |
| `ANEB_M2_Android_Echo_ClaudeCode_Handoff_v0.1.0-h1.zip` | `db9635888f94f34b038c8408895dc89e3d3371637924f5a4fd14666a63488bec` | `REFERENCE_ONLY / CONCEPT_ONLY`；许可未覆盖对外产品复制，只登记可重写经验 |
| `ANEB_M2_MV_Source_v0.5.2.zip` | `6ff3fcc070ab091a0868024632a497d6f9220b6571635107fa4cc3bd5f99a8a4` | `QUARANTINED_REFERENCE / DO_NOT_IMPORT`；含真实实验签名密钥库及硬编码签名凭据 |
| `ANEB_M2_MV_STATUS_v0.5.2.json` | `af40fb030d931922eea5b914ee4f769936ea32f60c9e9e5dddc60c337bfbd2f0` | `INDEXED_ONLY`；历史状态为 `NOT_VALIDATED` |
| `ANEB_M2_MV_EXECUTION_REPORT_v0.5.2_CN.md` | `1188d1f82ca5debc183885dea87a1dcbd9d573da16ec2f8ecaf52663c3216fec` | `INDEXED_ONLY`；只保留 Gate 结论，不复制 APK、设备或运行证据 |

恢复审计确认：RC 包没有路径穿越或符号链接，包内 `MANIFEST.sha256` 校验全部通过；
独立找回的 v3.1、验证报告和验证摘要与包内副本逐字节一致。该 manifest 没有签名，归档也
不含 `.git`，因此它证明包内一致性，但不能独立证明来源身份或把文档中记录的 commit 重新
绑定到源码历史。

## 3. 历史版本关系

### 3.1 v3.0 / 实现 1.1.0

- 文档日期：2026-07-11；历史代码快照：`d25ddaae3b9cd5cacdf6f7efc0d80ba4f0b29b91`。
- 状态为 `M1 Candidate`：已尝试 32 项，29 项通过，3 项 Lifecycle Error；完整矩阵和
  Release QA 尚未通过。
- 价值在于记录连接生命周期、状态传播、Session、故障域和相对基线校准等问题如何被发现；
  它已被 v3.1/1.2.0rc1 的历史结果取代。

### 3.2 v3.1 / ANEB 1.2.0rc1

- 类型：Python/httpx 终端模拟器 + FastAPI Probe 的协议级参考实现，状态
  `M1-SV-RC / PASS_WITH_SCOPE_LIMITATIONS`。
- 验证摘要记录 delivery commit `4a382b9737d7c0abc2b0da7b88d5c32d006c8a79`、
  validated code commit `e217e38`，验证后产品代码改动数为 0。
- 历史 Gate：25/25 隔离测试批次、79/79 自动化测试、9/9 故障矩阵、重复性、并发压力、
  干净 Wheel 安装和 Release QA 均为 PASS。
- “30 轮 / 31 轮”不是测试矛盾：报告描述 30 轮产品工程闭环；机器摘要的第 31 次交付
  迭代只对齐设计、交接和证据路径，没有修改已验证产品代码。

已验证范围仅包括协议模拟中的 CLI、Probe、Synthetic Agent、Application Echo、TTFA、
Streaming、Upload/Download、Session Resume、确定性故障、指标/质量 Gate、诊断、报告、
生命周期、并发和安装流程。

未验证范围包括物理 Android 无线行为、真实 4G/5G/Wi-Fi、物理设备上的 H3/QUIC 迁移、
多地域节点、真实端侧 LLM，以及 AES 主观研究或正式标准资格。因此历史的单机时延、吞吐、
故障矩阵分数不能成为当前 Android/Go 或真机基线。

机器可读原始摘要见
[`snapshots/ANEB_validation_summary_v1.2.0rc1.json`](snapshots/ANEB_validation_summary_v1.2.0rc1.json)。

### 3.3 Android Echo / M2

Android Echo 交接包是一条单指标 Application Echo RTT 垂直切片。它可供当前产品重写借鉴的
是设备证据采集、机器可读设备矩阵、Manifest/权限静态门禁和“schema + golden”合同验证模式；
旧 `app_echo_rtt_ms` 合同、质量门、逐请求网络绑定、Cronet 客户端、手写 JSON、mock server
和历史 evidence 均与当前多 KPI/AQS 实现不兼容，不应复制。

该交接包的许可说明没有授予对外产品复制许可；在 Product Owner 完成权属/许可确认前，
只能借鉴概念并基于当前合同重新实现。

M2 v0.5.2 的真实 Android Debug/Release 构建、R8、签名审计和 Cronet 四 ABI 打包曾通过，
但 ADB 安装因受限模拟器资源未完成，Android H2/H3、Wi-Fi 未执行，LTE/5G、真实设备矩阵、
netem 和多地域 Probe 均被外部条件阻塞，所以总 Gate 明确为 `NOT_VALIDATED`。

M2 源码 ZIP 内含真实实验 PrivateKeyEntry 及硬编码签名凭据，应视为已暴露的实验签名材料；
它不得用于当前发布、不得改名复用，也不得以 ZIP 形式进入公开仓库。当前工程采用仓库外密钥
与 release fail-closed 门禁的做法必须保留。

## 4. 明确未导入的内容

- 26 MB RC ZIP、Python/FastAPI 源码、旧 OpenAPI、Threshold/Scenario/Workload 配置；
- 旧评分权重、门限、Profile 和 Network/Device/Task 三轴结果语义；
- Android Echo/M2 旧测量代码、合同、质量门、mock server 与伪静态编译脚本；
- Wheel、APK/AAB、JKS/keystore、TLS/API/signing key、`.env` 与 `local.properties`；
- JUnit、原始矩阵/压力报告、PID、日志、数据库、设备标识、GPS/小区信息和真机证据；
- DOCX/PDF、渲染 PNG/JPG、构建缓存、`.egg-info` 及其他生成物。

这样处理既防止秘密或个人/设备数据进入公开仓库，也避免第二套接口和评分语义形成平行事实源。

## 5. 可在当前产品中重新实现的经验

以下内容可作为后续独立工作包，必须按当前 Kotlin/Go/Profile v2 合同重新实现并重新验证：

1. 构建身份、Profile/合同哈希和机器可读验证摘要；
2. 有界 Session、TTL、幂等、上传/结果写入限额与资源回收；
3. 有限流 drain/close、进程隔离、超时和异常退出清理；
4. 执行前参数边界拒绝、HTML/JSON 转义、证据逐文件哈希与篡改测试；
5. 绝对时间表 pacing、相对基线 + 服务端注入证据、确定性场景预言机；
6. 重复性、并发压力、FD/RSS/Session 泄漏和干净安装 Gate；
7. 面向当前 `com.aneb.probe` 与当前结果合同的脱敏设备证据采集器；
8. Android Manifest/网络安全静态门禁，以及当前 schema 的 golden 双端验证。

这些历史资料不改变 D-36/D-37：正式 App 只运行自建节点上的可控仿真；既有 AQS 与 Token、
实时交互、网络综合三套 Profile v2 评分相互独立，缺失必需指标时保持 `null`，不得重分权或混分。
