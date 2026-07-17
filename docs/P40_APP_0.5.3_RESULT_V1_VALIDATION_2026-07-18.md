# ANEB App 0.5.3 统一结果与 JSONL 真机验收

> 验收日期：2026-07-18（Asia/Shanghai）
>
> 设备：Huawei P40 Pro / ELS-AN00
>
> 包名：`com.aneb.probe.codex`
>
> App：`0.5.3-codex` / versionCode 35 / Room v19
>
> APK SHA-256：`5174d6f263d33f1241e388cb1b1f8c05cf030b69afcfa81a3ecd4e260b10540c`

## 1. 先讲限制

- ［KNOWN｜HIGH］本次验证证明 AI 实时与网络综合两条引擎能在这台设备、这个节点和对应 Quick Profile 下生成、持久化、导出统一结果；它不证明长期网络稳定性，也不代表第三方 AI 服务或运营商全网表现。
- ［KNOWN｜HIGH］Quick 的高分不能覆盖证据覆盖率门控；两次测试都正确保持 `LOW/INCONCLUSIVE`。
- ［KNOWN｜HIGH］三类正式新引擎的 RadioCollector 接线仍未完成；结果信封只能诚实记录无线未采集，不能把 RSRP/SINR 补成 0 或良好。

## 2. 自动质量门

- ［COMPUTED｜HIGH］Android JVM：84 suites、514 tests、0 failures、0 errors、0 skipped。
- ［COMPUTED｜HIGH］Android Lint：0 errors、11 warnings；Release API 入口边界通过。
- ［COMPUTED｜HIGH］`spec/catalog.json`：3 个 Schema、2 个 Profile 家族、16 个 Profile、6 个运行包、6 个内嵌网络 Profile 通过一致性校验。
- ［COMPUTED｜HIGH］`aneb-result-v1` Draft 2020-12 正例、反例与 null/radio 不变量通过。
- ［COMPUTED｜HIGH］行为模型 21 tests 通过；Go server 与 gateway 全量测试通过。

## 3. 真机执行结果

| 测试 | run id | 结果 | 关键观测 |
|---|---|---|---|
| AI 实时 Quick | `019f714a-b54f-787a-a992-2f0254417568` | ［KNOWN｜HIGH］100.0/A，`LOW/INCONCLUSIVE` | ［COMPUTED｜HIGH］2 秒音频准时帧率 100.0%（676 帧）；会话 RTT P95 43.3ms；通话负载 RTT P95 55.0ms；打断响应 P95 43.5ms。 |
| 网络综合 Quick | `019f714b-ca9d-7aed-a669-533f4ff4a500` | ［KNOWN｜HIGH］64.5/C，`LOW/INCONCLUSIVE` | ［COMPUTED｜HIGH］下载 P5 30.7Mbps、上传 P5 9.2Mbps、空闲 RTT P95 82.6ms、负载 RTT P95 972.0ms；`NET-B04` 达标率 6.7% 是本次主要瓶颈。 |

## 4. Room 与统一结果合同

- ［COMPUTED｜HIGH］数据库 `user_version=19`。
- ［COMPUTED｜HIGH］两个 run 在各自类型化结果表均恰有 1 行，在 `result_envelope` 均恰有 1 行；没有“结果页有数据但统一证据缺失”的分叉。
- ［COMPUTED｜HIGH］AI 实时信封 `test_type=ai_realtime_simulation`，网络信封 `test_type=network_comprehensive`；两者 `schema_version=aneb-result-v1`。
- ［COMPUTED｜HIGH］两个数据库信封的 Draft 2020-12 错误数均为 0，按 `canonical-json-sha256-v1` 重算后的 SHA-256 均与冻结摘要一致。
- ［COMPUTED｜HIGH］AI 实时导出包含 Profile 的全部 21 项指标：19 项 `observed`、2 项显式 `missing`；网络综合包含全部 13 项：10 项 `observed`、3 项显式 `missing`。未发出的测量不会从结果中静默消失。

## 5. 用户可见 JSONL 与分享

- ［KNOWN｜HIGH］网络结果页显示“可审计结果 / 保存 JSONL / 分享证据”，保存后界面明确回显系统下载文件名。
- ［COMPUTED｜HIGH］网络文件 `aneb_result_network_comprehensive_019f714b_20260718_022000.jsonl` 为 15,785 字节；从系统下载目录读回并校验，Schema 错误 0。
- ［KNOWN｜HIGH］点击“分享证据”后生成第二份相同语义文件并实际拉起 Huawei `HwChooserActivity`；返回后 App 保持正常。
- ［COMPUTED｜HIGH］AI 实时文件 `aneb_result_ai_realtime_simulation_019f714a_20260718_022923.jsonl` 为 35,591 字节；读回后 Schema 错误 0。
- ［KNOWN｜HIGH］导出器只校验 run/test/schema 身份与冻结摘要，并原样输出 `bodyJson`；完整性校验失败会停止导出，不在 UI 层重算评分或结论。

## 6. 资源释放

- ［KNOWN｜HIGH］测试前 P40 Pro 前台为 Huawei Launcher，Claude/Codex 两包均无 PID 与服务。
- ［KNOWN｜HIGH］所有验证结束后已执行 HOME 并只强停 `com.aneb.probe.codex`；最终前台为 Huawei Launcher，`com.aneb.probe` 与 `com.aneb.probe.codex` 均无 PID，服务数均为 0。

## 7. 验收结论

- ［KNOWN｜HIGH］App 0.5.3 已关闭 D-55 中 AI 实时/网络综合结果信封和用户可见 JSONL 导出/分享三个缺口。
- ［KNOWN｜HIGH］M1 剩余主要缺口是三类正式引擎 RadioCollector、同条件 TTFT 重复性复测和非开发者正式发布验收；不能把本次单设备单节点 Quick 验收写成 M1 全部完成。
