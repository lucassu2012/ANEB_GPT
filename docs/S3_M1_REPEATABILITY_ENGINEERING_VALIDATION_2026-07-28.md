# S3/M1 三族重复性工程验证记录（2026-07-28）

## 1. 结论先行

- ［KNOWN｜HIGH］P40 已用同一 CI 候选连续完成 Token、AI 实时、Network 三族各 5 次，共 15 次 `completed` 业务运行；这证明三族工程采集编排可以连续完成，不再是“尚未采样”。
- ［KNOWN｜HIGH］本轮 **没有关闭 S3/M1 正式重复性门**：15/15 结果的无线状态均为 `permission_denied`、样本数为 0，严格导出器分别在三族首个 run 以 `radio_envelope_binding_mismatch` 拒绝，因而没有形成合格的 strict-v2 三族 cohort。
- ［KNOWN｜HIGH］Token 业务子集按既有 D-58 判据为 `pass`，任务对齐 TTFT CV 中位数为 `0.02608730926728453`（2.61%）；该结论只覆盖 D-58 授权的 Token TTFT，不覆盖无线完整性、全量 `TOK-B04` 最低样本数或整个 S3/M1。
- ［KNOWN｜HIGH］Realtime 与 Network 只生成 `diagnostic_only / policy_pending` 分布，不产生质量 PASS/FAIL；全部单 run 分数仍为 `INCONCLUSIVE/LOW`。
- ［KNOWN｜HIGH］一次临时 SQLite 诊断直接连接了冻结 Room 源，触发 WAL checkpoint，使 DB/WAL/SHM 原字节三件套发生不可逆转换。业务 canonical 行仍可查询，但该源只能用于工程诊断，正式基线资格固定为 false。

## 2. 冻结身份与原始回执

| 项目 | 冻结事实 |
|---|---|
| source commit | `5b968bfffdb81451c80b2fd86064c06b35da925b` |
| GitHub Actions | run `30323979935`，provenance 复核通过 |
| APK | `ANEB-Probe-0.5.14-codex-debug.apk`，version code 46，SHA-256=`09db3b4a3f137cd98c2346c55f5dadf3f9e797367327e0c5e7b986de525ce8b4` |
| Android 包 | `com.aneb.probe.codex` |
| E-01 | `aneb-server/0.8.2`，binary SHA-256=`62ff966bf396abe836c6179053ee549110e41e16af569cdeadc97535bc64c96e` |
| campaign 回执 | `campaign-runs.json` SHA-256=`eb7d1756550ba5f85b8e6df6d518ae26b959bfbe2eaf54d0d97692fbf0af3dd8` |
| 业务诊断 | `business-repeatability-diagnostic.json` SHA-256=`33383e01bb4deeae4cde1d99dd7cef584785e8d6a0d48f2aabda34bde3a9913c` |
| 源转换事件 | `diagnostic-source-transformation-incident.json` SHA-256=`2594ef2e2be16bb5c1d81a90257ec3652753389aecb2c43dbdcc8bf7667fa888` |
| PhoneGuard 恢复复核 | `phone-postflight-recovery.json` SHA-256=`48884ca5f7b91df309111e205b91c548cac6afe94a7f76add2bf36026f2c621a`；内部 receipt=`098e5593118728538eae1a54b7736a8694a0220ecb6244876ca250ad04aa4bcd`；stable state=`277824515c65d20e6db2f3874ed4f938160dffde51dd7aac1bff148041c21198` |

本地受控分析副本位于 `C:\tmp\ANEB-S3-M1\s3-m1-repeatability-20260728T031044Z-db75fb692529`。原始 evidence 路径仅作事件记录；后续分析不得再次直接连接原始 Room 文件。

## 3. 15 次业务运行

| 测试族 | run IDs |
|---|---|
| Token | `019fa6b4-af41-7ac7-95d4-e3942a2075e0`、`019fa6b6-b1ba-7a30-b2ba-e48675e8700c`、`019fa6b8-b42f-75f8-9a82-8154c85da448`、`019fa6ba-b763-7320-b6d7-c17aa3995d67`、`019fa6bc-d6bb-75ff-812d-e287580509d6` |
| AI 实时 | `019fa6be-d865-7a12-ac01-e52bb004d1b9`、`019fa6bf-5105-751c-8af8-c0c5acc3485e`、`019fa6bf-c9f9-79e8-8ccf-ba4aad13ce70`、`019fa6c0-41fa-7619-a6ad-d93af50c30db`、`019fa6c0-b96d-7358-af14-2471c49c0a98` |
| Network | `019fa6c1-31a7-78a5-b6fb-f0a0b93d397c`、`019fa6c1-8de6-7e4e-b417-a67c5e3542e9`、`019fa6c1-eaa6-7a59-afab-0d4d109e0036`、`019fa6c2-48bc-77a1-afea-fea8af4b7fe2`、`019fa6c2-a4c3-7ea7-a6ed-995e663e5bfd` |

## 4. 业务诊断结果

### 4.1 Token

- ［COMPUTED｜HIGH］D-58：5 run、3 个任务、15 个 TTFT 样本，采集跨度 534,395ms；任务 TTFT CV 中位数 2.61%，最大值 19.47%，授权结论为 `pass`。
- ［COMPUTED｜HIGH］每 run `TOK-B04` 均只有 3 个样本，低于指标声明的 10 个最低样本；五次 run 均值为 3466.92ms、样本 CV 为 1.05%。因此它只能保留为业务诊断，不能借 D-58 的任务对齐判据掩盖样本数不足。
- ［KNOWN｜HIGH］五次分数为 `86.4/A`、`85.1/A`、`87.5/A`、`74.4/B`、`88.1/A`，全部为 `INCONCLUSIVE/LOW`。

### 4.2 AI 实时

| 指标 | 五次均值 | 样本 CV | 最低样本门 |
|---|---:|---:|---|
| `LIVE-B05` 音频准时帧率 | 0.998816568 | 0.00264937 | 5/5 满足（每 run 676 ≥ 500） |
| `LIVE-N02` 会话 RTT P95 | 79.8278ms | 0.0629259 | 0/5 满足（每 run 5 < 20） |
| `LIVE-B08` 打断响应 P95 | 77.1169ms | 0.0573257 | 0/5 满足（每 run 1 < 2） |

［KNOWN｜HIGH］五次分数为 `88.6/A`、`100/A`、`100/A`、`100/A`、`100/A`，全部为 `INCONCLUSIVE/LOW`。没有获批的 Realtime 重复性门限，不能把高分或低 CV 写成正式 PASS。

### 4.3 Network

| 指标 | 五次均值 | 样本 CV | 最低样本门 |
|---|---:|---:|---|
| `NET-B01` 下载持续有效速率 P05 | 26.5342Mbps | 1.07095 | 0/5 满足（5–6 < 10） |
| `NET-B02` 上传持续有效速率 P05 | 10.2360Mbps | 0.979227 | 0/5 满足（5–6 < 10） |
| `NET-B04` 负载中 RTT P95 | 1260.8762ms | 0.798109 | 仅前 2/5 满足（21、23、16、3、12；门限 20） |

［KNOWN｜HIGH］五次分数为 `60.2/C`、`64/C`、`57.9/C`、`36.3/D`、`39.1/D`，全部为 `INCONCLUSIVE/LOW`。该离散程度是后续门限设计输入，不是已批准的失败判据。

## 5. 严格无线门为什么失败

1. ［KNOWN｜HIGH］15/15 结果均记录 `collection_status=permission_denied`、`sample_count=0`、`unavailable_reason=android_radio_permissions_denied`。
2. ［KNOWN｜HIGH］严格导出器逐条绑定 Room `radio_sample` 与信封内 `context.radio.samples`；三族首个 run 均因无线绑定不成立而拒绝，不会用空数组、默认值或业务指标替代无线证据。
3. ［INFERRED｜HIGH］CI Debug 签名候选每次换装会重置 Android runtime permission 状态；仅执行 `pm grant` 而不留下精确 `dumpsys package` 回执，无法证明业务启动前权限真实生效。下一轮必须以机器回执为硬门，而不是根据安装命令返回值推定成功。

本轮代码已补充 `aneb-repeatability-radio-permissions-v1` create-once 回执：记录 `READ_PHONE_STATE`、`ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION` 三项权限的唯一 `granted=true/false` 行、package dump SHA、拒绝列表，并在任何业务 run 前 fail closed。该修复不改变 Profile、KPI、AQS、质量目标或评分语义。

## 6. 证据源转换事件

- ［KNOWN｜HIGH］事件根因是临时诊断通过 `sqlite3.connect` 直接打开冻结源，不是严格导出器。
- ［KNOWN｜HIGH］转换前：DB=`3cb7b671ef852919061cfedc506021232f043dfee572951724d49b9ce7756314`（1,277,952 bytes）、SHM=`596b44b79a656656d2e3af1e3576842fc337f7a169eabd83405f4823290efb94`、WAL=`d90443646d8af4db38c4d1c5e9345c51b816052a8c8cb0a0db2e13875d7f44b3`。
- ［KNOWN｜HIGH］转换后：单 DB=`9455ef215496bc82f60d60c331d1775dca7cb98c8950b4446afd3c26c492a86c`（1,347,584 bytes），WAL/SHM 消失。
- ［KNOWN｜HIGH］业务 canonical 行仍可查询，但“原始三件套逐字节未变”已被反证。本记录只使用受控副本做诊断，不把转换后的单 DB 冒充原始快照。

严格导出器现新增失败路径回归：源连接保持 WAL 模式，主动制造绑定漂移，确认导出拒绝前后 DB/WAL/SHM 三件套逐字节一致且不生成输出。直接脚本入口也已纳入回归，避免 `python scripts/export_repeatability_cohort.py` 因导入路径失效。

## 7. 清理边界

- ［KNOWN｜HIGH］campaign 主流程业务 15/15 完成，但 `final-status.json` 明确记录两项 cleanup error：`restore_mkdir_failed rc=1` 和 `CleanupReceipt` 序列化接口不匹配；当时 `original_install_restored=false`。不得把 campaign 自身写成“自动清理全绿”。
- ［KNOWN｜HIGH］后续受控恢复产生独立 PhoneGuard 双快照，Huawei Launcher、相关 PID/service/accessibility/VPN/tun、Wi-Fi 与 `stayon=7` 回到稳定干净状态；E-01 remote lock receipt 为 `LOCK_RELEASED`。这证明现场后来已清理释放，但不改写 campaign 当时失败的 cleanup 回执。
- ［KNOWN｜HIGH］runner 已分别修正 `exec-in run-as` 恢复路径与 cleanup receipt 字节适配；本轮新增测试只覆盖权限回执和导出源保护，完整真机恢复仍必须在下一轮 finally 中再次实证。

## 8. 当前完成门与下一步

- ［KNOWN｜HIGH］本轮代码聚焦交叉回归 39/39 PASS，直接 exporter CLI `--help` exit=0，相关模块 `py_compile` PASS。
- ［KNOWN｜HIGH］冻结当前改动后的完整 `scripts/quality_gate.ps1` 单次运行 exit=0、耗时 1,395.9 秒：主 Python 873/873（skip 16）、附加 Python 44/44、Android 单测/lint/assemble、Go server/gateway、release boundary、699 个 tracked 文件凭据扫描、Profile/Schema/打包检查全部 PASS；本地 Debug APK SHA-256=`00c0033743f768752b676c1c6056963863a092d2bb2ef3d4e99e150b583c1b5e`。运行后没有 Python/Java/Gradle/Go 测试残留；既有 ADB server 进程不是本轮启动或测试证据。
- ［KNOWN｜HIGH］生产修复提交 `4184065889833f70e4853f241c1d1dc39e2593de` 已推送；GitHub Actions run `30348907807` 的 7 个 job 全部成功，覆盖 credential scan、AI model、Windows evidence、Go server、dedicated gateway、Profile/result/packaging 与 Android candidate/provenance。
- ［KNOWN｜HIGH］S3/M1 当前状态是：**业务重复采样已发生；严格无线 cohort 未形成；正式重复性矩阵仍未完成。**
- ［KNOWN｜HIGH］下一轮必须先取得三项无线权限全为 granted 的 create-once 回执，再跑三族各 5 次；任一权限、样本、绑定、PhoneGuard 或 E-01 基线门失败即停止，不消耗后续 run。
- ［KNOWN｜HIGH］下一轮只对冻结快照/副本运行 exporter 和分析器，禁止任何 ad-hoc 诊断直接连接原始 DB/WAL/SHM。
- ［KNOWN｜HIGH］Realtime/Network 正式重复性阈值以及无线 cadence/gap/stale 门限仍需 Product Owner 单独批准并写入新的 Decision；在此之前所有结果继续为 diagnostic-only、formal baseline false。
