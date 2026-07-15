# 阶段 3 netem 双实验取证（阶段1灵敏度验收补登 + E-04 跨境剖面本地替代对照组）

日期: 2026-07-13（宿主时刻 08:3x–09:0x；guest logcat 时间轴 07-13 00:40–01:03，guest 时区不同不影响相对时序）
执行: netem 实验子代理 (Claude Code)
目的:
1. **实验一**：补登阶段 1 漏登的 netem 灵敏度验收——注入已知损伤（delay 100ms + loss 1%），断言 KPI 按预期方向恶化、且弱网样本**不被**批化自检/守卫误判 INVALID（R-05/R-06 弱网幸存核对）。
2. **实验二**：阶段 3 E-04 的本地替代——模拟中美西跨境路径（delay 150ms ± 10ms normal + loss 0.5%），产出"模拟跨境 vs 本地直连"KPI 对照表。

## 0. 隔离方案（共用资源红线合规）

不在 E-01 上做任何全局 netem。本机 Docker 起隔离容器，tc/netem 仅存在于**容器自身 netns 的 eth0**（egress qdisc），宿主与其他容器零影响；实验结束容器整体销毁。

```
容器:   docker run -d --name aneb-netem --cap-add NET_ADMIN -p 127.0.0.1:18445:8443 alpine:latest sleep infinity
工具:   apk add iproute2 curl（tc utility, iproute2-v7.0.0；normal.dist 分布表自带）
服务端: GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build（主工作树 server/ 当前代码，11,957,783 bytes）
        docker cp 二进制 + profiles/ 进容器；/aneb-server -addr :8443 -profiles /profiles -data /data
        （plaintext dev 模式，与阶段1接线验收同口径；容器内仅本实验流量）
冒烟:   容器内 curl POST /api/v1/echo → 200；宿主 curl --noproxy '*' 127.0.0.1:18445 → 200 (observed=172.17.0.1)
        profiles 加载: s1_chat@0.2.0 / s2_coding_agent@0.2.0 / s3_multimodal@0.2.0（与既有验收一致）
客户端: 专属 AVD aneb-p3b（android-35 google_apis x86_64, pixel_6, port 5592, emulator-5592）
        启动前清空全部 proxy 环境变量（D-16 直连红线，含 -http_proxy 继承坑）
        probe-debug.apk 以主工作树当前代码 assembleDebug 构建（含接线批次已合入的 BUFFERING 展示）
驱动:   am start -S -n com.aneb.probe/.ui.MainActivity --es server "http://10.0.2.2:18445" --ez autorun true --es mode quick
        （10.0.2.2 = 模拟器到宿主 loopback，经 docker-proxy 进入容器 eth0，双向穿过 netem）
netem 生效性核对（sibling 容器 ping 172.17.0.2）:
  基线(无 netem):       RTT 0.067–0.088 ms
  实验一(100ms/1%):     RTT ≈100.1–100.2 ms（首包 200ms 为 ARP）
  实验二(150ms±10ms):   RTT 131.3–162.0 ms（抖动可见），qdisc: delay 150ms 10ms loss 0.5%（distribution normal 表加载成功）
暖机:   注入前先跑 1 次干净暖机 run（019f58eb-2ba5, AQS=95.9, 全绿），不计入任何组样本。
```

## 1. 实验一：阶段 1 netem 灵敏度验收（delay 100ms + loss 1%）

`tc qdisc replace dev eth0 root netem delay 100ms loss 1%`；注入组 quick run ×2 → `tc qdisc del` → 对照组 quick run ×2。全部 run REPORT http=200。

### 1.1 原始 KPI（S1/S2/S3 逐场景；单位 ms，U1 Mbps；(lc)=lowConfidence）

| run | 组 | 场景 | T1 | T2 | T3 | T4 | T5 | N1 | N2 | U1 | U2 | validity |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 019f58ef (注入r1) | 注入 | S1 | 127.18(lc) | 35.02 | 0 | 0 | – | 105.06 | 1.04 | 0.148(lc) | – | VALID_LOW_CONFIDENCE |
| | | S2 | 103.43(lc) | 26.34 | 0 | 0 | 742.89 | 104.92 | 1.49 | 5.78(lc) | 418.43 | VALID_LOW_CONFIDENCE |
| | | S3 | 103.76(lc) | 26.15 | 0 | 0 | – | 104.54 | 305.84 | 11.87(lc)/excl 6.45 | – | VALID_LOW_CONFIDENCE |
| 019f58f2 (注入r2) | 注入 | S1 | 104.34(lc) | 25.52 | 0 | 0 | – | 105.13 | 2.21 | 0.135(lc) | – | VALID_LOW_CONFIDENCE |
| | | S2 | 104.57(lc) | 10.66 | 0.001 | 0 | 748.32 | 104.74 | 0.41 | 5.94(lc) | 207.18 | VALID_LOW_CONFIDENCE |
| | | S3 | 104.16(lc) | 25.51 | 0 | 0 | – | 104.67 | 2.12 | 5.21(lc)/excl 8.27 | – | VALID_LOW_CONFIDENCE |
| 019f58f4 (对照r1) | 对照 | S1 | 18.20(lc) | 25.46 | 0 | 0 | – | 4.71 | 1.66 | 1.08(lc) | – | VALID_LOW_CONFIDENCE |
| | | S2 | 3.33(lc) | 10.58 | 0.001 | 0 | 756.52 | 4.52 | 0.54 | 20.98(lc) | 21.13 | VALID_LOW_CONFIDENCE |
| | | S3 | 3.45(lc) | 25.45 | 0 | 0 | – | 4.13 | 0.45 | 23.38(lc) | – | VALID_LOW_CONFIDENCE |
| 019f58f7 (对照r2) | 对照 | S1 | 7.33(lc) | 25.42 | 0 | 0 | – | 4.92 | 0.83 | 2.09(lc) | – | VALID_LOW_CONFIDENCE |
| | | S2 | 3.46(lc) | 10.46 | 0 | 0 | 747.76 | 4.52 | 0.86 | 22.76(lc) | 12.45 | VALID_LOW_CONFIDENCE |
| | | S3 | 3.26(lc) | 25.47 | 0 | 0 | – | 4.33 | 0.37 | 23.75(lc) | – | VALID_LOW_CONFIDENCE |

run 级 AQS（aqs-v0.1，UI 分档 ≥85 优 / ≥70 良 / ≥55 可）:

| run | AQS | 子分 T1/T2/T3/U1/U2/N1/N2 |
|---|---|---|
| 注入 r1 | **86.0** | 92.2 / 96.0 / 100.0 / 76.9 / 64.1 / 53.6 / 98.4 |
| 注入 r2 | **86.1** | 92.2 / 98.4 / 97.1 / 70.2 / 79.3 / 53.6 / 96.7 |
| 对照 r1 | **96.3** | 99.8 / 98.4 / 97.1 / 85.6 / 97.9 / 97.6 / 97.5 |
| 对照 r2 | **97.1** | 99.7 / 98.4 / 100.0 / 85.7 / 98.8 / 97.5 / 98.8 |

### 1.2 断言结论

**断言 1（KPI 按预期方向显著恶化 + 掉档）——判定: PASS（附口径说明）**
- **T1 (TTFT)**: 3.3–18.2ms → 103.4–127.2ms（+100ms ≈ 注入单程时延，方向与量级均符合预期）。
- **N1 (RTT)**: 4.1–5.0ms → 104.5–105.1ms（+100ms 精确复现注入值）；**四级分级掉档 优→差**（6/6 场景，jsonl `n1_grade` excellent→poor），N1 子分 97.5→53.6。
- **U1 (上行 goodput)**: S3 23.4–23.8 → 5.2–11.9Mbps（loss 1% 触发拥塞窗口收缩）；分级 优→良/差。
- **U2 (tool_loop 往返)**: 12.5–21.1ms → 207.2–418.4ms；分级 优→可/良；子分 97.9/98.8→64.1/79.3。
- **T2 (ITL P50)**: 部分场景恶化（注入 r1 S1 35.02 vs 对照 25.46；r1 S2 26.34 vs 10.58），部分持平（r2 S1 25.52）——SSE 25ms 步进节拍下，纯平坦时延不移动 ITL 分布、仅丢包重传随机拉高，**方向正确但非稳定显著**，属注入类型（平坦 delay）的物理口径而非检测缺陷。
- **T3 (stall)/T4 (事件缺失率)**: 基本不动（T3≤0.001，T4=0）。**如实记录**: netem 丢包在 TCP 层被重传修复，应用层 SSE 事件序列无缺号，T4=0 是正确测量而非灵敏度不足（T4 移动需 /stream 注入 truncate/dupseq，已由 P1-C09 三种注入实验覆盖并全部正确判定）。T3 在实验二的抖动剖面下按预期方向移动（见 §2）。
- **AQS**: 96.3/97.1 → 86.0/86.1（−10.2/−11.0 分）。**如实记录**: run 级 AQS 分档（≥85 优）注入组以 1.0–1.1 分之差仍留在优档——run 级未掉档；掉档在 KPI 分级层（N1 优→差、U2 优→可/良、U1 优→良/差）与子分层（N1 −43.9 分）明确成立。灵敏度验收的实质（已知损伤→分数显著、方向正确地下降）成立。

**断言 2（弱网幸存，R-05/R-06）——判定: PASS**
- 注入组 6 个场景 validity 全部 = VALID_LOW_CONFIDENCE，invalid_reasons=none，**0 个 INVALID**；REPORT 2/2 http=200。
- VALID_LOW_CONFIDENCE 与对照组一致，由快测模式样本量门槛触发（C01 既定三态语义），非弱网误杀。批化自检/守卫未把 100ms+1% 弱网样本误判为 INVALID/作废。
- 全部 12 场景 SKEW offset_suspect=false（drift −37～+68ppm 正常区间）。

**断言 3（BUFFERING attribution 不误报 MIDDLEBOX）——判定: 存在误报，如实记录（affects_validity=false，不作废任何样本）**

| 组 | attribution=middlebox_suspect | 明细 (score) |
|---|---|---|
| 对照 ×2 (6 场景) | **0/6** | 全部 none，score 0.005–0.207 |
| 注入 ×2 (6 场景) | **3/6** | S2r1 0.410 / S3r1 0.274 / S2r2 0.258（其余 none, 0.201–0.247） |

- 纯时延+丢包（非缓冲）触发了 3/6 middlebox_suspect **误报**。特征拆解显示主因是丢包重传造成的到达批化（S2r1: near_zero=0.250, batch_count=75, sawtooth=0.309），与 nginx proxy_buffering 的批化签名同形。
- 与 P1-C08 标定遗留（"autocorr 分量在真实路径饱和待重加权"）同根因，供检测器迭代：需在 middlebox 判据中引入丢包/重传共变量（如 near_zero 批的间隔分布 vs 缓冲批的周期性 grid）以区分"重传批化"与"中间盒缓冲批化"。
- 全部 affects_validity=false：误报不进 validity、不改分数，仅 attribution 字段观测性输出——现有守卫边界设计使误报无实害。

## 2. 实验二：跨境剖面对照组（E-04 本地替代；delay 150ms ± 10ms distribution normal + loss 0.5%）

`tc qdisc replace dev eth0 root netem delay 150ms 10ms distribution normal loss 0.5%`（模拟中美西太平洋路径 RTT ≈150ms + 抖动）；quick run ×2（019f58fa AQS=80.0、019f58fd-74b9 AQS=80.9），REPORT 2/2 http=200，validity 12/12 场景 VALID_LOW_CONFIDENCE、0 INVALID。

### 2.1 模拟跨境 vs 本地直连 KPI 对照表

（本地直连 = 实验一对照组同环境同批次；表值为两 run 均值，区间为 min–max；S1 为 TTFT/ITL 主口径场景）

| KPI | 本地直连 | 模拟跨境 | 变化 |
|---|---|---|---|
| TTFT T1 S1 (ms) | 12.8 (7.3–18.2) | 159.2 (154.0–164.4) | **×12.5**（≈+150ms 单程时延） |
| TTFT T1 S2/S3 (ms) | 3.3–3.5 | 148.4–170.6 | ≈+150ms |
| ITL T2 S1 P50 (ms) | 25.4 | 52.5 (50.0–55.1) | **×2.1**（±10ms 抖动打散 25ms 节拍） |
| ITL T2 S2 P50 (ms) | 10.5 | 43.7 (41.4–46.0) | ×4.2 |
| stall T3 S1 | 0 | 0.005–0.010 | 抖动致失速出现（分级 优→良） |
| N1 RTT (ms) | 4.1–4.9 | 153.0–159.4 | ≈+150ms；分级 优→差 |
| N2 抖动 (ms) | 0.37–1.66 | 13.1–28.6（S3r2 尾部 353.4） | ×10–×20（注入 σ=10ms 显形） |
| U1 S3 (Mbps) | 23.4–23.8 | 8.2–8.5 | **−65%**（高 BDP+丢包下窗口受限） |
| U2 tool_loop (ms) | 12.5–21.1 | 281.4–308.2 | ≈+2×150ms 往返叠加 |
| **AQS** | **96.3 / 97.1（优）** | **80.0 / 80.9（良）** | **run 级掉档 优→良**（−16 分） |

跨境组子分（两 run）: T1 87.2/88.6, T2 93.1/93.8, T3 91.3/94.2, U1 73.5/73.2, U2 69.6/71.9, **N1 40.4/39.1**, N2 82.7/81.5。

### 2.2 附带观测（如实记录）

1. **clock_sync 在抖动下 offset_suspect 正确点亮**: 3/6 场景 SKEW offset_suspect=true（S3r1 drift +256.4ppm、S1r2 −159.8ppm、S3r2 −211.2ppm）。±10ms 抖动使 20 探针的 offset 估计方差放大，漂移率越界被守卫如实标记（不影响 validity）——量表按设计工作；跨境真实测量（E-04 恢复后）应加大 clock_sync 探针数或以 err_us 加权。
2. **BUFFERING 误报在抖动剖面下加剧**: 6/6 场景 middlebox_suspect（score 0.354–0.627，S2 两 run 0.61–0.63 最高；near_zero 至 0.475、batch_count 至 218）。抖动重排+重传批化是比平坦时延更强的伪缓冲签名——与实验一断言 3 同一迭代方向，样本已入库供重加权。
3. 快测 S1 上行样本过小（2KB）使 S1 的 u1 无参考价值（lc 标记正确），AQS 的 U1 取 S3 1MB 口径不受影响（AQS_INPUT_MAP 落盘可核）。

## 3. 样本账本与一则并行干扰记录

- 本实验共发起 7 次 run（1 暖机 + 实验一 4 + 实验二 2），客户端 Room DB test_run=7 行、report_body=7 行、场景结果 7×3 行，与 6 个正式样本 + 1 暖机完全闭环（run_id 逐一匹配 logcat 与服务端 jsonl）。
- 服务端 jsonl 共 8 行，多出的 `019f58f1-27b4`（AQS=76.1）**不属于本实验设备**（不在本模拟器 Room DB/logcat 中）。溯源: 并行 p3:gps 代理首次误用 :18445 端口（其证据 `gps_drive_mode_20260713.log` 已注明"该端口上的 run 019f58f1-* 证据作废不采信，改用 :18446 重跑"），其流量恰好穿过本容器 netem 故分数同样劣化。**双方账本一致作废该行，不计入任何组**；本实验样本以 run_id 三方匹配（logcat/Room DB/jsonl）为准，无污染。

## 4. 证据文件

- 本报告: `evidence/phase3/netem_experiments_20260713.md`
- 原始 logcat 附录（6 个正式 run 全量 AnebProbe 行）: `evidence/phase3/netem_experiments_20260713_logcat.log`
- 服务端落盘原文（8 行含作废行，未删改）: `evidence/phase3/netem_server_results_20260713.jsonl`

## 5. 清理

- `docker rm -f aneb-netem`（容器连同 netns/qdisc/数据整体销毁；宿主 18445 释放）
- `adb -s emulator-5592 emu kill` + `avdmanager delete avd -n aneb-p3b`（专属 AVD 删除）
- 无 E-01 触碰、无宿主/其他容器网络配置变更；aneb-server-linux 临时产物位于 %TEMP% 随清理删除。
