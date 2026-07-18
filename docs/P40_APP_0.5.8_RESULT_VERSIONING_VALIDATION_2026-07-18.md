# P40 Pro · App 0.5.8 结果版本演进验收

> 日期：2026-07-18（Asia/Shanghai）
>
> 设备：Huawei P40 Pro / ELS-AN00
>
> Debug 包：`com.aneb.probe.codex` / `0.5.8-codex` / versionCode 40
>
> 最终 APK：61,754,256 bytes / SHA-256 `B857A8AD2E6CA443CC6B0B60162DE7E5D73A7E4532D9E27F2A92808A83F8DAF0`

## 1. 先讲反方观点与边界

- ［KNOWN｜HIGH］同名 `aneb-result-v1` 曾在发布后新增 3 个 Token 必填字段，因此“只保留最新版 v1 Schema”会错误否决不可变历史；这不是历史数据损坏，而是合同版本治理错误。
- ［KNOWN｜HIGH］兼容验证器只能恢复已发布 v1 两种任务形状的结构可验证性，不能补造 0.5.6 之前不存在的任务 ID、节点处理时延或端到端 TTFT，也没有改写任何历史信封。
- ［KNOWN｜HIGH］本次 Token Quick 只证明单台 P40、当前 Wi-Fi、自建节点的 v2 纵向链路；97.0/A 不覆盖 Quick 证据覆盖率门控，正式结论仍为 `LOW/INCONCLUSIVE`。

## 2. 合同修复

- ［KNOWN｜HIGH］`aneb-result-core-v1` 只承载三类结果共享结构，没有公开生产版本身份，App 不得发出它。
- ［KNOWN｜HIGH］`aneb-result-v1` 恢复为兼容验证器：原始任务字段仍必填，后来加入的 `task_id/server_processing_ms/ttft_ms` 为可选；因此发布早期和后期的不可变 v1 都可按同一兼容边界验证。
- ［KNOWN｜HIGH］`aneb-result-v2` 是当前生产合同：Token 每个任务强制包含上述 3 个对齐字段；AI 实时与网络综合继续复用共享核心。
- ［KNOWN｜HIGH］三类 Android 正式引擎从 0.5.8 起只产生 v2。JSONL 导出器仍原样支持 v1/v2，并把“未来格式暂不支持”与摘要/身份完整性异常分成不同错误类型和中文提示。

## 3. 自动验证

- ［COMPUTED｜HIGH］Android：88 suites、541 tests、0 failures、0 errors、0 skipped；Lint 0 errors / 11 warnings；Debug APK、Release 边界通过。
- ［COMPUTED｜HIGH］Spec catalog：8 schemas、2 个 Profile 家族、16 个 Profile、6 个运行包、6 个内嵌网络 Profile、4 个行为模型通过。
- ［COMPUTED｜HIGH］兼容 v1、严格 v2、共享核心、3 个类别样例、v1 legacy/v2 aligned 冻结任务向量和 null/radio 反例均通过 Draft 2020-12。
- ［COMPUTED｜HIGH］12 个测量分析测试（含空文件与跨版本重复 run 拒绝）、31 个行为模型测试以及 Go server/gateway 全部通过。

## 4. 不可变历史复核

- ［COMPUTED｜HIGH］重新读取最终批量文件 `aneb_results_27_of_31_20260718_100304.jsonl`：27 行、27 个唯一 run，全部标识为 v1。
- ［COMPUTED｜HIGH］按每条记录的 `schema_version` 路由到兼容 v1 后为 27/27 通过、0 个结构错误；此前缺少三字段的 3 条历史不再被最新版错误门限否决。
- ［KNOWN｜HIGH］该离线 Schema 复核不替代设备完整性导出。设备已独立证明这 27 条摘要匹配；4 条历史摘要异常仍不在文件中，兼容 Schema 不会让摘要损坏记录重新获得资格。

## 5. P40 新 v2 纵向结果

| 项目 | 结果 |
|---|---|
| run id | `019f730f-a0d5-7417-9e01-0866bacdfc57`（最终 APK 前的同源候选；最终 APK 另补混合导出验收） |
| 测试 | Token Simulation Quick / `token_multimodal_quick@1.0.0` |
| 业务结果 | 3/3 任务完成；97.0/A；`LOW/INCONCLUSIVE` |
| 结果合同 | `aneb-result-v2`；严格 v2 Schema 错误 0 |
| v2 任务约束 | 3/3 任务均含 `task_id/server_processing_ms/ttft_ms` |
| 无线证据 | 120 条 1Hz 样本；环境变化事件 0 |
| 冻结摘要 | `sha256:bf6bbbbdc6d7d914f1e06384433d52b8cbaa81696e4fda20cea016271117f8b3` |
| 独立摘要 | 与 Room 冻结摘要完全一致 |

## 6. 设备协作与剩余真机项

- ［KNOWN｜HIGH］测试前两包均无 PID/服务，前台为 Huawei Launcher，Wi-Fi 开启。
- ［KNOWN｜HIGH］Token run 完成后已先 HOME，再只强停 `com.aneb.probe.codex`；复核两包均无 PID、Codex 服务为 0、Wi-Fi 仍开启、前台为 Huawei Launcher。
- ［KNOWN｜HIGH］准备验证 v1+v2 混合批量导出时，先检测到 Claude 正在前台运行 `com.aneb.probe`，因此等待；之后两包无 PID且回到 Launcher，才安装最终 APK 并进入 Codex 设置页。
- ［KNOWN｜HIGH］滚动设置页期间设备被另一会话切换到千问，说明发生中途接管；该次尝试立即作废，没有点击导出，也不计为验收。随后只强停 Codex 包，不触碰千问或正式 ANEB。最终复核 Codex 无 PID/服务、Wi-Fi 开启、前台回到 Launcher，但正式包 `com.aneb.probe` 已出现后台 PID，设备继续让给 Claude。

## 7. 当前结论

- ［KNOWN｜HIGH］D-64 的历史可验证性与新结果升版本已完成：不可变 v1 恢复可验证，新生产者发 v2，新增 required 字段不会再追溯改变 v1 含义。
- ［INFERRED｜MED］共享核心可降低 v1/v2 大合同复制漂移，但它仍需要 catalog 与跨版本冻结向量约束；如果未来绕过 wrapper 直接修改核心 required，仍可能重新破坏兼容性。
- ［KNOWN｜HIGH］混合 v1/v2 导出已由 JVM 测试证明原样排序与身份/摘要校验；P40 用户路径因共享设备中途接管主动作废，待设备稳定释放后补齐。
