# P40 Pro：App 0.5.6 Token TTFT 同条件重复性验收

> 日期：2026-07-18（Asia/Shanghai）
>
> App：`com.aneb.probe.codex`，`0.5.6-codex`，versionCode 38，Room v19
>
> 节点：`https://120.79.148.0:8443`
>
> Profile：`token_multimodal_quick@1.0.0`，请求承载 `auto`，实际承载 `wifi`，VPN=false

## 1. 先讲边界

- ［KNOWN｜HIGH］这是同一台 P40 Pro、同一 Wi-Fi、同一自建节点、同一 Quick Profile 在 10 分 29.666 秒内完成的 5 次相邻复测，只回答该条件下 ANEB Token TTFT 测量是否可重复。
- ［KNOWN｜HIGH］本证据不代表蜂窝网络、运营商全网、Kimi/DeepSeek/千问真实业务，也不验证跨设备、跨节点或跨时段一致性。
- ［KNOWN｜HIGH］实际业务承载为 Wi-Fi；同时采到的 NR RSRP/RSRQ/SINR 只是蜂窝调制解调器环境协变量，不得用来解释本次 TTFT 的因果。
- ［KNOWN｜HIGH］每个 Quick run 只有 3 个任务，低于 B04 的单 run 建议样本数 10，因此各 run 继续保持 `LOW/INCONCLUSIVE`；5-run 重复性判定是独立的测量稳定性审计，不提升单 run 业务结论置信度。

## 2. 0.5.6 测量语义

- `TOK-B03`：节点单调时钟上的“完整收到上传 → 首个计划 Token”处理时延，只描述仿真业务模型设定，不进入评分。
- `TOK-B04`：节点确认完整收到上传后，App 单调时钟观察到首 Token 的端到端 TTFT；目标为不超过同任务 B03 + 200ms。
- `TOK-B05`：已存在的首 Token 超额时延，用时钟映射剥离计划时刻，继续作为评分指标。
- 每条原始任务证据新增稳定 `task_id`、`server_processing_ms` 和 `ttft_ms`。结果导出器只封装冻结值，不在导出时重算指标。

## 3. 五次真机结果

| run_id | B03 P95 ms | B04 P95 ms | B05 P95 ms | 分数 | 结论/置信度 | 任务 | Radio |
|---|---:|---:|---:|---:|---|---:|---:|
| `019f71c8-d473-7a88-818c-e3cd5d894264` | 3370.000 | 3402.097 | 112.674 | 98.5 | INCONCLUSIVE / LOW | 3 | 119 |
| `019f71cb-4416-7269-8824-0f9582b808bd` | 3370.000 | 3458.679 | 106.160 | 98.5 | INCONCLUSIVE / LOW | 3 | 119 |
| `019f71cd-adf2-7c3c-abdd-850e61d84cbe` | 3370.000 | 3444.859 | 125.212 | 91.7 | INCONCLUSIVE / LOW | 3 | 119 |
| `019f71cf-ac18-7e04-bddd-8dfafaa9dd56` | 3370.000 | 3446.196 | 105.148 | 98.5 | INCONCLUSIVE / LOW | 3 | 119 |
| `019f71d2-7013-7d9f-be65-c6dd27e644eb` | 3370.000 | 3428.733 | 100.862 | 98.5 | INCONCLUSIVE / LOW | 3 | 119 |

［COMPUTED｜HIGH］5 条类型化结果与 5 条 `aneb-result-v1` 信封严格 1:1；每条信封均通过 Draft 2020-12 校验，独立 Python 规范化 SHA-256 与 Room 冻结摘要一致。每个 run 的 Room/信封/信封数组无线样本均为 119/119/119，陈旧样本 0，可分享结果位置键 0。

## 4. 重复性算法与结果

`scripts/analyze_ttft_repeatability.py` 采用 fail-closed cohort：

1. 至少 5 个 completed + valid run，最长跨度 30 分钟；
2. Schema/test/claim、Profile ID/version/fingerprint、运行包哈希、App/设备、节点、请求/实际承载和 VPN 状态必须完全一致；
3. 每个 run 必须具有相同且非空的任务身份集合，B04 冻结 P95 与原始任务 TTFT 重新核算必须一致；
4. 按 `task_id` 对齐 5 次样本，计算样本标准差 / 均值；以所有任务 CV 的中位数作为正式判据，`median CV <= 10%` 为 PASS，最大任务 CV 作为诊断值。

| task_id | 业务 | 五次 TTFT ms | 均值 ms | CV |
|---|---|---|---:|---:|
| `task-0003` | 8KiB 文本 | 813.090 / 800.363 / 758.622 / 807.968 / 722.458 | 780.500 | 4.986% |
| `task-0010` | 5MiB 文档 | 2308.944 / 2306.804 / 2331.507 / 2243.990 / 2306.129 | 2299.475 | 1.425% |
| `task-0016` | 10MiB 图片 | 3523.559 / 3586.665 / 3568.564 / 3579.775 / 3553.467 | 3562.406 | 0.704% |

**［COMPUTED｜HIGH］正式结果：PASS。任务 CV 中位数 1.425%，最大值 4.986%，均低于 10% 门限；15 个 TTFT 样本完整。**

［INFERRED｜MED］小文本任务的相对波动最高，符合固定 700ms 处理基线下网络/终端固定开销占比更大的现象；本次数据只能提示优先关注短交互稳定性，不能单凭相关性归因到无线、节点或终端调度。

## 5. 本地证据与构建

本地证据目录为 `evidence/device/app_0.5.6_ttft_repeatability/`，按仓库政策不提交：

| 证据 | SHA-256 |
|---|---|
| `aneb-probe.db` | `6F851B3A8784331C036CA3C70CAF37B918A152B8F99453DC33EF150C285F6B6E` |
| `token_quick_5run_cohort.jsonl` | `1D630ABFA90FD8A35B69966AADD403081841D3A063AB3149F19330325A87CDB2` |
| `ttft_repeatability_report.json` | `726C444631E00EF7128FE1DD8C7AE0F16F0B00516B30B095A1DDF4A8075CD313` |
| `room_result_integrity_audit.json` | `8DCB4DFD5FA0D19C05C9A4702AEB096A4FEC5C4E68EDB021604C9F901443781C` |

- ［COMPUTED｜HIGH］完整质量门：525 JVM tests、0 failures/0 skipped；Android Lint 0 errors（11 项依赖/SDK/API 提示）；结果 Schema、spec catalog、5 项重复性分析器测试、行为模型 21 tests、Go server/gateway tests 全部通过。
- ［COMPUTED｜HIGH］Debug APK：57,939,930 bytes，SHA-256 `B2C56295DA3565B06D71C19523C5685DBD1514C2B67E66C88C86D005565F6E57`。

## 6. 设备释放

［COMPUTED｜HIGH］五次测量结束后已返回华为桌面并强制停止 `com.aneb.probe.codex`。离线取库前后均核对：`com.aneb.probe`（Claude）和 `com.aneb.probe.codex` 无 PID、无 ServiceRecord，前台为 `com.huawei.android.launcher/.unihome.UniHomeLauncher`。

## 7. 里程碑判断

- ［KNOWN｜HIGH］原计划 M1 的“同点位复测 TTFT 变异系数 ≤10%”验收项已经获得可复算真机证据。
- ［KNOWN｜HIGH］M1 的单节点 Profile 1/2、JSONL + radio_ctx 验收切片已闭环；原计划内容中同城/区域/中心三级部署仍未完成，因此不把整项 M1 写成无条件全部完成。
- ［KNOWN｜HIGH］M4 的非开发者全流程验收与正式签名发布是独立缺口，本次 Debug/ADB 取证不能替代。
