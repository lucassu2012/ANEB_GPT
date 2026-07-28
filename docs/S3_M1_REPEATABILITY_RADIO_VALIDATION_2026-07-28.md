# S3/M1 三族无线重复性工程验证（2026-07-28）

## 1. 结论先行

- ［KNOWN｜HIGH］P40 使用 source `8a834d49dd61a1f544dc5ea10991e50929f85a3e`、GitHub Actions run `30349786024` 的 exact CI APK 完成 Token、AI 实时、Network 各 5 次，共 15/15 次 `completed`。
- ［KNOWN｜HIGH］安装后、业务前的 `aneb-repeatability-radio-permissions-v1` 回执证明 `READ_PHONE_STATE`、`ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION` 三项均为 `granted=true`，拒绝列表为空。
- ［KNOWN｜HIGH］严格导出器从冻结 Room 副本导出三组各 5 条 strict-v2 envelope；三族全部拥有内联约 1 Hz 无线序列，样本总数分别为 Token `641`、Realtime `135`、Network `100`，stale 与订阅切换样本均为 0。
- ［COMPUTED｜HIGH］Token 按唯一已批准的 D-58 判据为 `PASS`：15 个任务对齐 TTFT 样本，任务 TTFT CV 中位数 `1.714958989%`；Realtime 与 Network 继续为 `policy_pending / diagnostic_only`。
- ［KNOWN｜HIGH］本轮仍不是正式体验基线：无线 cadence/gap/stale 没有获批质量阈值，Realtime/Network 没有族专属重复性门，多个业务指标未满足 Profile 声明的 run 级最低样本数，所有 `formal_baseline_eligible` 继续为 `false`。

## 2. 冻结身份与回执

| 项目 | 冻结事实 |
|---|---|
| source | `8a834d49dd61a1f544dc5ea10991e50929f85a3e` |
| GitHub Actions | run `30349786024`，7/7 jobs success |
| APK | `ANEB-Probe-0.5.14-codex-debug.apk`，SHA-256=`6b45ddd94ea0621fba09dfbb2eb31596a9c6ed4bb1212c7011018ec75a307ba3` |
| signer | SHA-256=`80d376a1b8fbf9d0fb9e28170ceec088a1ff206c622f076b857abac475927556` |
| P40 package | `com.aneb.probe.codex`，version code 46 |
| E-01 | `aneb-server/0.8.2`，binary SHA-256=`62ff966bf396abe836c6179053ee549110e41e16af569cdeadc97535bc64c96e` |
| campaign evidence | `C:\Users\lucas\AppData\Local\ANEB\ValidationEvidence\s3-m1-repeatability-20260728T104101Z-b7afab29a7ff` |
| final status | SHA-256=`a905dfd51e28ebb1d67c4580a5c47c41814e113d4705fcd74573530ac3dfb865`；`campaign_complete=true`、`run_count=15`、`cleanup_errors=[]`、`original_install_restored=true` |
| permission receipt | SHA-256=`66118a4cf587abe5b98021bb43d82d2cec9fc36662f444821aea853e7c139842`；三项权限全 granted |
| campaign run receipt | SHA-256=`81292875797bc0e5444f146a9cb561eee1e4d00fe221791d71f230c2805a2fa8` |

## 3. 原始 Room 保护与严格导出

原始 `campaign-room` 三件套在复制前和全部导出、分析完成后保持以下 SHA-256：

| 文件 | bytes | SHA-256 |
|---|---:|---|
| `aneb-probe.db` | 1,511,424 | `320030748ea00300ae95fa175a0a175674abe235b34f1937a262afc4777359f5` |
| `aneb-probe.db-wal` | 374,952 | `cca7f26579e1b5dd2d54bc9268da2444ba9f13f9703bdc21eb120bd5fbf10bf2` |
| `aneb-probe.db-shm` | 32,768 | `9ae58360eda254ca15a99969d0dcb37727734ef235a3f4abb5abbc0970f60030` |

［KNOWN｜HIGH］SQLite、exporter 和 analyzer 只打开 `analysis-frozen-copy`；原始三件套未被 SQLite 连接、checkpoint 或重建。严格导出没有重算结果或评分。

| 族 | strict-v2 JSONL SHA-256 | repeatability report SHA-256 |
|---|---|---|
| Token | `4c523a0d79f9e793fb7a0a593e34d07519adb8dc8961310f3bcc5c31af28d8e2` | `2eb3088fcaad97ea3448a17690ef316650b292b95eb62b1a798206015db0bad3` |
| Realtime | `4c9058f5b9038cd03a7557ae7934da56c578529dbd035c97dab550708b0e1292` | `840b7e1fa2af75a71e948686d4df50b5efa98f33d76491169366cdea146adbf2` |
| Network | `2e8c17536ef3ffc34a78391a86fce3e78ee8581b4920880721c094476fcb477a` | `a5eda96a3abdc6ee9f777f0422befb7019221fb6e3527bc732713062f70fc601` |

## 4. 重复性与无线结构诊断

### 4.1 Token

- ［COMPUTED｜HIGH］D-58：5 run、3 个任务、15 个 TTFT 样本；任务 TTFT CV 中位数 `1.714958989%`、最大值 `18.388328083%`，授权结论 `PASS`。
- ［COMPUTED｜HIGH］`TOK-B04` 五次值为 `3443.899`、`3503.525`、`3429.765`、`3481.900`、`3425.346 ms`，样本 CV `0.991578466%`；每 run 只有 3 个样本，低于声明的 10 个最低样本，因此该指标的 run 级最低样本门为 false。
- ［KNOWN｜HIGH］无线样本数为 `128/130/128/128/127`；每 run 中位频率约 `1 Hz`，stale=0、订阅切换=0。

### 4.2 AI 实时

| 指标 | 五次中位数 | 样本 CV | 最低样本门 |
|---|---:|---:|---|
| `LIVE-B05` 音频准时帧率 | `1.0` | `0%` | 5/5 run 满足 |
| `LIVE-N02` 会话 RTT P95 | `80.7692 ms` | `8.984642345%` | 0/5 run 满足 |
| `LIVE-B08` 打断响应 P95 | `76.886458 ms` | `7.031368108%` | 0/5 run 满足 |

［KNOWN｜HIGH］无线样本数为 `27/27/27/27/27`；中位频率约 `1 Hz`，stale=0、订阅切换=0。因为没有获批 Realtime 重复性判据，结果只能标记 `policy_pending`。

### 4.3 Network

| 指标 | 五次中位数 | 样本 CV | 最低样本门 |
|---|---:|---:|---|
| `NET-B01` 下载持续有效速率 P05 | `51.544162 Mbps` | `24.066817407%` | 0/5 run 满足 |
| `NET-B02` 上传持续有效速率 P05 | `34.905299 Mbps` | `7.220385468%` | 0/5 run 满足 |
| `NET-B04` 负载中 RTT P95 | `484.4135 ms` | `14.533684074%` | 5/5 run 满足 |

［KNOWN｜HIGH］无线样本数为 `20/20/20/20/20`；中位频率约 `1 Hz`，stale=0、订阅切换=0。下载离散度和高负载 RTT 是后续门限设计输入，不是已经批准的失败结论。

## 5. 本轮暴露并修复的三个机械缺口

1. ［KNOWN｜HIGH］华为 Android 在 runtime permission 已授予且 flags 为空时可输出行尾 `granted=true`。权限解析器现同时接受唯一的 `granted=true` 行尾和 `granted=true, flags=…`，仍拒绝缺失、重复或 false 行。
2. ［KNOWN｜HIGH］`analyze_repeatability_cohort.py` 直接脚本入口此前无法找到仓库包；现与模块入口使用同一受支持 import path，并有真实 `--help` 子进程回归。
3. ［KNOWN｜HIGH］Android `NetworkCapabilities` 的 `up_kbps/down_kbps` 是每 run 可能变化的链路估算，不应属于 cohort 身份。分析器只从身份中排除这两个动态字段，仍保留 requested/active transport、interface、validated、not-suspended、metered、VPN、Private DNS 等隔离条件；非带宽能力漂移继续 fail-closed。

上述修复不改变 Profile、KPI、AQS、质量目标、D-58 或评分算法。

## 6. 现场清理与共享主机边界

- ［KNOWN｜HIGH］E-01 前后 PID=`1295423`、InvocationID=`d975f7c374aa4ef3a490210d0a495e53`、binary SHA 及 Docker/eth0/v4/v6/nft/full 六项指纹逐字一致；remote lock nonce `0187b8f976c44b1b867a64d09cc86eff` 已 `LOCK_RELEASED`。
- ［KNOWN｜HIGH］runner finally 后又独立执行 revision-4 PhoneGuard T+0/T+2：receipt=`53bb96b690e7119d0a37dce6954103992f00f5610122baa68391945f803621f5`，stable state=`277824515c65d20e6db2f3874ed4f938160dffde51dd7aac1bff148041c21198`；Huawei Launcher、12 个相关包 PID/service、accessibility、VPN/tun 均为 0，Wi-Fi on，`stayon=7`。
- ［KNOWN｜HIGH］本轮未修改 E-01 服务、防火墙、qdisc、Docker 或阿里云规则；P40/E-01 已精确释放。

## 7. 当前完成门与下一步

- ［KNOWN｜HIGH］S3/M1 的“权限机器回执 → 三族重采 → 冻结副本严格导出 → 独立复算”工程闭环已经完成。
- ［KNOWN｜HIGH］冻结本轮源码/文档后的完整 `scripts/quality_gate.ps1` 单次运行 exit=0、耗时 1,564.8 秒：主 Python 877/877（skip 16）、附加 Python 44/44、Android 单测/lint/assemble、Go server/gateway、release boundary、700 个 tracked 文件凭据扫描、Profile/Schema/打包检查全部 PASS；post-scan 重型测试进程为 0。
- ［KNOWN｜HIGH］M1 仍为部分完成：Realtime/Network 族专属重复性判据、无线 cadence/gap/stale 质量门、非开发者连续运行与正式签名 Release 尚未验收。
- ［KNOWN｜HIGH］下一步先完成当前代码的全仓门禁与 clean CI；随后进入 S3/M2 的门限提案和更长样本计划，但任何新门限必须由 Product Owner 批准并写入 Decision，不能从这 5 次样本自动升级。
