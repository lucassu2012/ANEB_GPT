# P40 Pro · App 0.5.2 / `aneb-result-v1` 真机验证

> 验证时间：2026-07-18（Asia/Shanghai）  
> 设备：Huawei P40 Pro  
> 测试包：`com.aneb.probe.codex`  
> 测试范围：Token Quick 的 Room v19 迁移、持久化顺序与统一结果信封；不代表 AI 实时或网络综合已接入统一信封。

## 1. 发布物身份

| 字段 | 实测值 |
|---|---|
| App versionName | `0.5.2-codex` |
| versionCode | `34` |
| APK 字节数 | `57,628,634` |
| APK SHA-256 | `91261db156129cc81c40ee91e10bf8a151d95988e435e66363ec7628270a45bd` |
| Room user_version | `19` |

［KNOWN｜HIGH］以上身份由最终 Debug APK、安装后包信息和停止应用后的 Room 数据库副本交叉核验。

## 2. 设备协作与执行边界

1. ［KNOWN｜HIGH］开始前检查 `com.aneb.probe`（Claude 包）和 `com.aneb.probe.codex`（Codex 包）。Claude 包最初仍有进程/最近任务时未抢占手机；待两个包均无 PID 且前台回到华为桌面后才安装和运行 Codex 包。
2. ［KNOWN｜HIGH］第一次覆盖安装后的 autorun 只创建了前台 Service，没有出现 `TOKEN_V2_START`，数据库 WAL 也未产生新结果。该尝试缺少引擎启动和持久化证据，按无效尝试丢弃，不计为测试 run。
3. ［KNOWN｜HIGH］只停止 Codex 包后执行干净重试；没有停止、清理或修改 Claude 包。
4. ［KNOWN｜HIGH］有效 run 完成后发送 HOME、强制停止且仅停止 `com.aneb.probe.codex`，随后确认两个包 PID 均为空、两个包均无 `ServiceRecord`、前台为华为桌面。

## 3. 有效测试

| 字段 | 实测值 |
|---|---|
| run id | `019f70ed-ed0a-7897-b019-eff5a9a26dda` |
| Profile | `token_multimodal_quick@1.0.0` |
| 行为模型 | `token-multimodal-behavior-v0.1@0.1.0` |
| 模型状态 | `hypothesis` |
| 计划任务 | 3 |
| 最终状态 | `completed` / `valid` |
| 分数 / 等级 | `98.4` / `A` |
| 结论 / 置信度 | `inconclusive` / `low` |

［KNOWN｜HIGH］日志出现 `TOKEN_V2_START`、逐任务完成、`TOKEN_V2_DB_WRITE ... ok=true`、`TOKEN_V2_RESULT` 和 `TOKEN_V2_END ... status=completed`。最后一个任务完成 300/300 个仿真 Token；App 没有调用 Kimi、DeepSeek、Qianwen 或其他真实 AI API。

［COMPUTED｜HIGH］`confidence_basis.coverage_ratio=0.15` 且 `minimum_sample_satisfied=false`，因此 98.4/A 不能升级成 PASS 或 95% 长期稳定性结论；`inconclusive/low` 与 Quick Profile 的证据边界一致。

## 4. 停止应用后的数据库审计

审计通过 `run-as` 只读复制主库、WAL 和 SHM 后执行；审计副本验证完成即删除，未重新启动 App。

| 检查 | 结果 |
|---|---|
| `token_simulation_result` 同 run 行 | 1 |
| `result_envelope` 同 run 行 | 1 |
| Draft 2020-12 Schema 错误 | 0 |
| 规范化 JSON SHA-256 | 匹配 |
| 信封摘要 | `sha256:e4e23ec4e5f5fe6fcc12c050e1ee4cef8087ae63655a3557124d7c79e9fe95fb` |
| 无线采集状态 | `not_collected` |
| RSRP / SINR | `null` / `null` |

［COMPUTED｜HIGH］类型化结果和统一信封在同一 run 中同时存在，且信封通过 Schema 和摘要校验，证明本次 Token 结果在发布前已形成可验证的冻结记录。

［KNOWN｜HIGH］正式 Token 引擎尚未接入 RadioCollector，所以无线状态明确记录为 `not_collected`，RSRP/SINR 为 `null`；不得用 0、系统瞬时值或推测值补齐。

## 5. 验收结论与剩余缺口

- ［KNOWN｜HIGH］App 0.5.2 的 Token Quick 纵向链路已在 P40 Pro 验证：运行 → 同事务写入类型化结果和 `aneb-result-v1` → 停止应用后可独立校验。
- ［KNOWN｜HIGH］AI 实时和网络综合当前仍只有各自类型化 Room 结果，尚未写入 `result_envelope`；用户可见 JSONL 分享也尚未接线。
- ［INFERRED｜MED］下一步复制 Token 样板时，最高风险不是 JSON 拼装，而是三类引擎对“取消、无效、缺失指标、低置信度”的语义不一致；应先共用生命周期/评估语义，再接 UI 导出。

