# 真机 bare-IP 蜂窝取证数据集（ANEB 首份真实移动网络测量）

> 设备：华为 P40 Pro（ELS-AN00，HarmonyOS 4.2 / Android 12 / API 31），中国电信 5G SA（n78）
> 日期：2026-07-13 ｜ 数据源：evidence/phase3/realdevice_data/aneb-probe-cellular.db（Room DB 二进制安全回收）
> 通道：bare-IP + IP-SAN 自签证书（D-22），绕过电信 NR-SA 的 SNI-keyed TLS RST（R-33）

## 1. REACH 矩阵——SNI-RST 定量实证（headline）

所有 5 个带 REACH 探测的 run 一致记录：

| 通道 | WiFi | 蜂窝（NR-SA） |
|---|---|---|
| SNI 主机名（120-79-148-0.sslip.io） | **rst** | **rst** |
| bare-IP（120.79.148.0，IP-SAN 证书） | ok（91ms） | **ok（103–113ms）** |

结论：电信 NR-SA 对 `sslip.io` 主机名注入 SNI-keyed TLS RST（WiFi 侧同样对该主机名 RST——说明是 DNS/SNI 内容触发而非纯蜂窝特性），bare-IP 无 SNI 触发词则 TLS 握手正常完成。**SNI-RST 从"测量障碍"转为量化维度**（REACH KPI 候选，KPI 文档 5.5）。首次战役经 sslip.io 主机名的蜂窝 run（019f59b0/b2/b4/b7）全部 INVALID，即此机制所致。

## 2. 首个真实蜂窝 AQS

| run | 模式 | 通道 | AQS | 状态 |
|---|---|---|---|---|
| 019f59f1 | quick | WiFi（bare-IP） | **92.62** | completed |
| 019f59f6 | quick | **蜂窝 NR-SA（bare-IP）** | **89.24** | completed |
| 019f59f9 | forensic | 蜂窝 NR-SA（bare-IP） | 91.33 | aborted:default_network_changed（PathMonitor fail-closed 正确触发；中止前已采 3 遍拉丁方大部分场景，AQS 为中止前值） |

均 low_confidence=true（快测样本量语义），veto=false。**89.24 是本项目首个真实蜂窝 AQS**。

## 3. 真实蜂窝 KPI（run 019f59f6，5G SA n78）

| 场景 | validity | T1 TTFT | T2 ITL P95 | T3 stall | N1 RTT P50 | N2 抖动 |
|---|---|---|---|---|---|---|
| S1 对话流 | valid_low_conf | 38.1ms（优） | 32.3ms（优） | 0（优） | 49.8ms（良） | 14.5ms（良） |
| S2 编码 Agent | valid_low_conf | 35.4ms（优） | 13.5ms（优） | 0（优） | 44.4ms（良） | 8.4ms（优） |
| S3 多模态 | valid_low_conf | 34.2ms（优） | 31.5ms（优） | 0（优） | 47.6ms（良） | 8.8ms（优） |

真实 NR-SA 上传全部成功（bare-IP）：S2 512KB@8.93Mbps、S3 2×1MB@12.5/13.5Mbps，全 http=200。

## 4. 真实无线层协变量（671 样本，1Hz，requestCellInfoUpdate 主动刷新）

- 制式：dataNetworkType=NR + override=none + nrState=nsa_unknown + rat=NR ⇒ **真 5G Standalone（SA，非 NSA）**——nsa_unknown 正是 SA 的正确签名（巴龙基带首次实测判别，R-15 三元组设计验证成功）
- RSRP：n=671，min −107 / avg **−93.1** / max −88 dBm
- SINR：min 0 / avg **8.5** / max 11 dB
- RSRQ：avg **−11.2** dB
- 小区：PCI 317、TAC 7699983、NRARFCN 627264（n78）；单小区稳定，无 CELL_CHANGE/RAT_CHANGE

## 5. 三方对照（首次）

| 指标 | 固网直连→深圳 | WiFi 真机（bare-IP） | 真实蜂窝 5G SA（bare-IP） |
|---|---|---|---|
| N1 RTT P50 | 28.1ms | 37–40ms | 44–50ms |
| T1 TTFT | 31.5ms | 20–31ms | 34–38ms |
| T2 ITL P95 | ~29ms | 15–35ms | 13–32ms |
| AQS | — | 92.6 | 89.2 |

解读：良好覆盖（RSRP −93dBm、SINR 8.5dB）的电信 5G SA 下，仿真节点端到端体验仅比 WiFi 低约 3.4 AQS 分，差异主要在 RTT（+7–10ms）与 TTFT（+10ms）——**5G SA 承载 Agent 仿真流量的体验接近固网**。这是有界时延维度上的正面结论；真正的劣化风险在弱覆盖/切换/中间盒（SNI-RST 已实证），需更多样本剖面覆盖。

## 6. Kimi 真机蜂窝探针（token 级未获，原因明确）

真机 3 次调用全失败：id1 HTTP 404（baseUrl 含 /v1 致 /v1/v1 双拼）、id2/id3 HTTP 400（temperature 非 1）——**均为修复前 APK 的两个兼容 bug**（本轮 impl 已修复：OpenAiSseAdapter 去硬编码 temperature、baseUrl 规范化）。真机跑的是旧 APK 故未受益。TLS 到 api.moonshot.cn 在 NR-SA 上握手正常（无 SNI-RST，真实生产域名），路径可达性已证。token 级 TTFT/ITL 已在模拟器用修复版拿到（2.4–2.6s / 190–230ms，见 kimi_api_probe_retest）；真机蜂窝 token 级留待新 APK 重测。

## 7. 设备现场

测后已恢复：wifi_on=1（原值）、stay_on_while_plugged_in=0（原值）；App 保留供手测。蜂窝流量实际用量估计 ~45–55MB（quick 一遍 + forensic 大部分三遍的真实上传/流），在 60MB 红线内。
