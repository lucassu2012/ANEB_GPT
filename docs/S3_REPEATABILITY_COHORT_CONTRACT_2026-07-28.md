# S3/M1 strict-v2 重复性 Cohort 合同（2026-07-28）

## 1. 结论先行

- ［KNOWN｜HIGH］`scripts/analyze_repeatability_cohort.py` 已实现三族共用的 strict-v2 cohort 身份、完整性和诊断层，输出合同为 `aneb-repeatability-cohort-v1`。
- ［KNOWN｜HIGH］只有 Token `TOK-B04` 端到端 TTFT 调用既有 D-58 判据并产生 `pass/fail`；AI 实时与网络综合固定返回 `policy_pending + diagnostic_only`，不继承 D-58 的 CV≤10%、5-run 或 30 分钟门限。
- ［KNOWN｜HIGH］分析器固定输出 `formal_baseline_eligible=false` 和 `single_run_confidence_unchanged=true`。一次 Quick、一个诊断 CV 或一次 CLI 成功都不能提升原结果置信度，也不能生成正式体验基线。
- ［KNOWN｜HIGH］2026-07-28 已用同一候选完成 P40 三族各 5 次、共 15 次业务运行；但 15/15 无线状态均为 `permission_denied`、样本数为 0，严格导出器据此拒绝，未形成合格的 strict-v2 三族 cohort。无线 cadence 正式判据、非开发者连续执行和真机导出后独立复算验收门仍未关闭，详见 `S3_M1_REPEATABILITY_ENGINEERING_VALIDATION_2026-07-28.md`。

## 2. 输入与 fail-closed 门

分析器只接受通过 Draft 2020-12 `aneb-result-v2` 完整校验的结果。以下任一情况直接 `invalid`，不以 0、空数组或默认身份补齐：

1. 非 strict-v2、重复 JSON key、`NaN/Infinity`、空输入或非对象行；
2. run 非 `completed + valid`、评估 verdict 为 `invalid`、run ID 重复；
3. device/network 未观测、活动承载不是 Wi-Fi/蜂窝、VPN 不为明确 `false`；
4. producer、Profile/运行包哈希、claim、设备、节点、承载或算法版本在 cohort 内漂移；
5. 选定诊断指标缺失、非 `observed`、值非有限数/为负数或样本计数无效。

同质身份包含 producer 版本、Profile 身份和摘要、完整 claim、设备、节点、活动网络上下文及除 `finalized_at_epoch_ms` 外的算法版本。无线 RSRP/SINR 等数值不是 cohort 的“必须逐字节相等”身份字段；它们只能作为每 run 的环境协变量。

## 3. 三族诊断集合与权限边界

| 测试族 | 诊断指标 | 当前权限 |
|---|---|---|
| Token | `TOK-B04` 端到端 TTFT P95 | D-58：同任务对齐、至少 5 run、30 分钟内、任务 CV 中位数≤10% 才判 PASS；最大 CV 仅诊断 |
| AI 实时 | `LIVE-B05` 音频准时帧率、`LIVE-N02` 会话内 RTT P95、`LIVE-B08` 打断响应 P95 | `policy_pending / diagnostic_only`；没有质量 PASS/FAIL |
| 网络综合 | `NET-B01` 下载持续有效速率 P05、`NET-B02` 上传持续有效速率 P05、`NET-B04` 负载中 RTT P95 | `policy_pending / diagnostic_only`；没有质量 PASS/FAIL |

每个诊断保留各 run 原值、样本数、最低样本数、均值、中位数、样本标准差和样本 CV。均值为 0 时 CV 必须为 `null + undefined_zero_mean`，不能伪造为 0；最低样本不足会原样记录，不能被 cohort 规模掩盖。

## 4. 无线序列与冻结导出

`radio_integrity` 对每个 run 执行以下机械审计：

1. `/context/radio` 必须是 `collected`，且内联 `samples` 至少 2 条；`sample_count` 必须与数组长度完全一致；
2. `elapsed_realtime_nanos` 必须严格递增，不能用重复或乱序时间戳制造连续曲线；
3. 输出跨度、最小/中位/P95/最大采样间隔、由中位间隔换算的观测频率、stale 数和 `sub_switched` 数；
4. nominal frequency 只记录实现声明的 `1.0Hz`。当前 `cadence_verdict=null`、`policy_mode=diagnostic_only`，在真实分布审计和 Product Owner 裁定前不擅自发明 gap/stale 合格门限。

`scripts/export_repeatability_cohort.py` 从冻结的 Room v19 数据库导出选定 run：

- 复制数据库及现有 WAL/SHM 到临时快照，只读 `quick_check` 并核对 Room identity；
- 逐 run 核对 `result_envelope` 的 run/schema/type/canonical SHA 绑定与完整 strict-v2 Schema；
- 逐条比较 `radio_sample` 和信封内 `context.radio.samples`，任何时间戳或无线字段漂移均拒绝；
- JSONL 直接写入 Room 已冻结的 `bodyJson` 原字节，不重建结果、不重算评分；输出 create-once SHA 回执。

## 5. CLI 与机器退出码

```powershell
python -m scripts.analyze_repeatability_cohort `
  --root "E:\G Project\ANEB\DevSpace\aneb-probe-codex-v0.2.0" `
  --output cohort-report.json `
  run-1.jsonl run-2.jsonl run-3.jsonl
```

```powershell
python -m scripts.export_repeatability_cohort `
  aneb-probe.db cohort.jsonl `
  <run-id-1> <run-id-2> <run-id-3> <run-id-4> <run-id-5>
```

| 退出码 | 含义 |
|---|---|
| `0` | 输入与 cohort 合同有效；Token D-58 PASS，或 Realtime/Network 成功生成 `policy_pending` 诊断 |
| `1` | 只有已授权的 Token D-58 判定 FAIL |
| `2` | 输入、Schema、身份、原始/指标一致性或 cohort 完整性无效 |

因此 `exit=0 + policy_pending` 只表示“诊断成功生成”，不表示体验质量 PASS。

## 6. 已验证与未完成

- ［KNOWN｜HIGH］TDD 首轮 RED 为生产模块缺失；实现后夹具暴露 `not_collected` 与错误 family payload，生产门未放宽，改用三族 schema-valid、observed-context 夹具后 7/7 GREEN。
- ［KNOWN｜HIGH］JSONL/CLI 独立 RED→GREEN 5/5；四模块交叉回归（新增 cohort/CLI、既有 D-58、result-v2 validator）24/24 PASS。
- ［KNOWN｜HIGH］无线结构/频率诊断与 Room 原字节导出器按行为切片完成 RED→GREEN；聚焦组合现为 29/29 PASS，包含无线缺内联样本、非单调时间戳和 Room/信封无线漂移拒绝。
- ［KNOWN｜HIGH］新增切片后的稳定工作树完整 `scripts/quality_gate.ps1` 单次运行 exit=0：主 Python 863/863（skip 16）、附加 Python 44/44、Android、Go、release boundary、secret scan、APK packaging、spec catalog 与 result schemas 全部 PASS；post-scan 重型进程残留为 0。stdout/stderr/status SHA-256 分别为 `E9E339ECCE3C66BFD630016CF9B6FD77AB33A15EF8A2F784C3E423541E045156`、`DFAF7B97B7236EBD1E55EECF85BE06630ECF9022648F9C809150AFB601C6D653`、`13BF7B3039C63BF5A50491FA3CFD8EB4E699D1BA1436315AEF9CBE5711530354`。
- ［KNOWN｜HIGH］稳定工作树完整 `scripts/quality_gate.ps1` 单次运行 exit=0、耗时 1,175,158ms：主 Python 858/858（skip 16）、附加 Python 44/44、Android、Go、release boundary、secret scan、APK packaging、spec catalog 与 result schemas 全部 PASS；运行后重型进程残留为 0。原始 stdout/stderr/status SHA-256 分别为 `D6B83E54451BF129E3BE4F69AF0B4FB29B4E092D2F3F17A93441D924B3A61F05`、`11FA501D358206B36B93B09E63E6DFB9CA5FDA8E259667A9CC56A489EEE53056`、`3BAA54E67B38FB13BA31BA1C6238B54B0377E96E7BC937082A35F7147183D2D8`。
- ［KNOWN｜HIGH］冻结生产提交为 `3a35236`；GitHub Actions run `30320671090` 的 7 个 job 全部成功，覆盖 dedicated gateway、AI behavior model、Windows evidence、tracked-source credential scan、Profile/result/packaging、Go server 与 Android candidate build。
- ［KNOWN｜HIGH］后续 P40 工程采样以 source `5b968bfffdb81451c80b2fd86064c06b35da925b`、CI run `30323979935`、APK SHA-256=`09db3b4a3f137cd98c2346c55f5dadf3f9e797367327e0c5e7b986de525ce8b4` 完成三族各 5 次业务运行。Token D-58 业务子门为 PASS；Realtime/Network 只保留诊断分布；15/15 无线权限拒绝使严格 cohort 不成立，不能写成正式重复性通过。
- ［KNOWN｜HIGH］临时诊断直接连接冻结 Room 源并触发 WAL checkpoint，原 DB/WAL/SHM 三件套被转换为单 DB；该事件已固定为 `engineering_diagnostic_only`，严格导出器本身不是根因。新增失败路径回归要求导出拒绝前后源三件套逐字节一致。
- ［KNOWN｜HIGH］runner 现会在首个业务 run 前生成 `aneb-repeatability-radio-permissions-v1` create-once 回执；任何必需权限缺失、重复或无法唯一解析均 fail closed，且拒绝事实仍被保留。该机械修复不改变 Profile、KPI、AQS 或评分语义。
- ［KNOWN｜HIGH］未修改任何 Profile、KPI、质量目标、AQS 权重、结果置信度或历史真机证据。
- ［KNOWN｜HIGH］下一步是在同一 exact App/server/设备/承载下，先以机器回执证明三项无线权限全部生效，再重新采集三族各 5 次；只对冻结快照/副本运行本导出器并由 cohort 分析器独立复算。Realtime/Network 的正式重复性阈值以及无线 gap/stale 正式门限，必须由 Product Owner 单独批准并写入新的全局 Decision。
