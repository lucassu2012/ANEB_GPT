# P1-C08 BufferingDetector 标定实验报告（2026-07-13）

依据：KPI 文档 5.3.3（判无效阈值待阶段一用签名样本标定，标定数据存 evidence/）、红队 R-05。

## 1. 实验设置

- **采集器**：`server/tools/capture/main.go`（Go，独立 main，不入主二进制；`go vet`/`go build` 通过）。
  SSE 客户端逐 token event 记录 `{seq, sched_us, pre_flush_us, arrival_us}`，`arrival_us` 为客户端单调时钟
  （`time.Now()` monotonic 分量），EOF 后输出 JSONL。默认 `http.Transport{Proxy: nil}` 强制直连（D-16），
  `-proxy` 显式给定时才走代理。
- **流参数**：三组统一 600 token @ 40 tps（`?tokens=600&rate_tps=40`，token 尺寸默认 median 120B/σ0.6），每组 2 次。
- **残差口径**（KPI 5.3.4）：逐 seq join 后 `residual = 到达间隔 − 发出间隔`，发出取服务端 `pre_flush_us`
  （实际写出前时刻），服务端调度误差由此剥离；残差含负不 clamp。
- **三组路径**：
  | 组 | 路径 | 说明 |
  |---|---|---|
  | clean | 采集器 → 127.0.0.1:8443 本机 aneb-server 直连 | 无中间盒基线 |
  | nginx | 采集器 → 127.0.0.1:18081 docker `nginx:alpine`（容器名 aneb-nginx）反代 → host.docker.internal:8443 | 缓冲中间盒（conf 见 §3，存档 `calibration/aneb-nginx.conf`） |
  | proxied | 采集器 -proxy http://127.0.0.1:33210 → E-01 公网 120.79.148.0:8443 | 真实代理中转（R-03 实证同源），首选代理即成功，未动用 7897 备选 |
- **夹具**：`evidence/phase1/calibration/`
  `clean_run{1,2}.jsonl`、`nginx_run{1,2}.jsonl`、`proxied_run{1,2}.jsonl`、
  `nginx_nobuf_run{1,2}.jsonl`（对照证据，见 §3）、`aneb-nginx.conf`。
- **分析载体**：`app/probe/src/test/java/com/aneb/probe/scoring/CalibrationFixtureTest.kt`
  （从仓库 evidence 目录自动定位夹具，可 `-Daneb.calibration.dir=` 覆盖；夹具缺失时 Assume 跳过，CI 不红）。

## 2. 三组签名特征值表（BufferingDetector 现行阈值，n=599 残差样本/run）

| run | score | 归因 | sawtooth | posSpike | negCluster | negResid | r1 | autocorrComp | nearZero | batches | 批间隔中位 | 网格 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| clean_run1 | 0.2005 | NONE | 0 | 0 | 0.0033 | 0.4775 | +0.3266 | 1.0000 | 0.0017 | 0 | — | 无命中 |
| clean_run2 | 0.2005 | NONE | 0 | 0 | 0.0033 | 0.3973 | +0.4881 | 1.0000 | 0.0017 | 0 | — | 无命中 |
| nginx_run1 | 0.9008 | MIDDLEBOX_SUSPECT | 0.8097 | 0.0067 | 0.9933 | 0.9933 | −0.0052 | 0.9896 | 0.9933 | 4 | 2.400s | 无命中（连续分布） |
| nginx_run2 | 0.9007 | MIDDLEBOX_SUSPECT | 0.8097 | 0.0067 | 0.9933 | 0.9933 | −0.0053 | 0.9893 | 0.9933 | 4 | 2.400s | 无命中（连续分布） |
| proxied_run1 | 0.1222 | NONE | 0.0083 | 0.0050 | 0.0117 | 0.5042 | −0.2073 | 0.5854 | 0.0033 | 1 | — | 无命中 |
| proxied_run2 | 0.0875 | NONE | 0.0484 | 0.0250 | 0.0518 | 0.4908 | −0.3455 | 0.3091 | 0.0050 | 2 | 12.01s | 无命中 |
| （对照）nginx_nobuf_run1 | 0.2005 | NONE | 0 | 0 | 0.0033 | 0.5225 | +0.1744 | 1.0000 | 0.0017 | 0 | — | 无命中 |
| （对照）nginx_nobuf_run2 | 0.2005 | NONE | 0 | 0 | 0.0050 | 0.4674 | +0.1068 | 1.0000 | 0.0017 | 0 | — | 无命中 |

**方向性断言（标定合同）全部通过**：clean 最大 score 0.2005 < nginx 最小 score 0.9007；clean 两 run 归因 NONE；
nginx 两 run 归因 MIDDLEBOX_SUSPECT（批间隔 1.0–4.5s 连续分布、不合 8/10/20/40ms 网格——正确落入中间盒假设分支）。
proxied 组如实记录：score 0.09–0.12、归因 NONE——该代理对 SSE 逐 event 转发、无输出侧累积，形态接近 clean 属实。

## 3. 实验中的关键实证发现（比"全绿"更重要）

1. **纯 `proxy_buffering on` 在快速本地客户端下不攒批**（nginx_nobuf_run{1,2} 对照组）：
   proxy_buffering 只解耦上游读与客户端写；客户端不慢时 nginx 逐 chunk 转发，签名与 clean 完全无差别
   （nearZero 0.17%、无批起点）。任务设计原假定"proxy_buffering 默认 on 即签名来源"，实测不成立——
   真实中间盒的"攒-放"需要**输出侧累积环节**。已加对照测试 `calibration_nginxWithoutAccumulation_looksClean` 固化该事实。
2. **签名来源改为 nginx gzip 过滤器**（现网 SSE 被中间盒攒批的高频真实成因）：gzip 不对每 chunk 做
   Z_SYNC_FLUSH，压缩输出攒满缓冲才吐块。且发现：默认 `gzip_buffers 32 4k`（128KB）大于整条 600-token 流的
   压缩总量（~100KB），会把**整流扣到 EOF 一次放出**（probe 实测全流仅 2 大批）；收窄为 `gzip_buffers 4 4k`
   （16KB）后呈周期性攒-放（每 run 4 个批起点，批间隔 1.0–4.5s）。另需 `proxy_ignore_headers X-Accel-Buffering`
   ——aneb-server 响应自带 `X-Accel-Buffering: no`，不忽略则 nginx 会尊重该头逐响应关缓冲。conf 全文存档于
   `calibration/aneb-nginx.conf`。
3. **autocorr 分量在真实路径上饱和，区分力存疑**：干净真实流的残差 r1 实测为**正**
   （clean +0.33/+0.49，nobuf +0.11/+0.17），并非理论 MA(1) 基线 −0.5（该理论假设逐 token 时延噪声独立；
   本机路径噪声呈正相关，推测为收发两侧调度量化的共模成分）。归一化 `|r1−(−0.5)|/0.5` 对任何 r1≥0 都钳到 1.0，
   于是 clean 的 autocorrComp=1.0 与 nginx 的 0.99 **无区分力**，且给 clean 白送 0.2 分——clean 总分 0.2005
   距活跃线 SCORE_ACTIVE_THRESHOLD=0.25 仅剩 0.05 余量。有趣的是 proxied（真实 WAN）r1=−0.21/−0.35 反而更接近
   理论基线。**本次不改代码**（方向性未失败，改权重超出本批次标定授权），原始值已按 R-05 全量透出，
   列入遗留项待真机样本回流后重新加权。

## 4. 阈值调整记录

**无需调整。** 现行全部 experimental 常量（POS_SPIKE_US=8ms、NEG_CLUSTER_US=−4ms、NEAR_ZERO_ARRIVAL_US=1ms、
BATCH_START_GAP_US=5ms、GRID_HIT_THRESHOLD=0.7、MIN_BATCH_COUNT=4、SCORE_ACTIVE_THRESHOLD=0.25、
权重 0.5/0.3/0.2）下三组方向性断言全部通过。两点边际观察随档：
- nginx 组批起点数恰为 4 = MIN_BATCH_COUNT，归因分支达标无余量（更长流/更小 gzip 缓冲会增加批数）；
- clean 总分 0.2005 距活跃线余量 0.05，主要被饱和的 autocorr 分量吃掉（见 §3.3）。

## 5. 回归验证

`:probe:testDebugUnitTest` 全量：**11 个 suite、123 个测试、0 失败、0 错误、0 跳过**
（含既有 scoring/engine 测试与新增 CalibrationFixtureTest 2 个用例；标定夹具在本机存在故未触发 Assume 跳过）。

## 6. 遗留

- **真实蜂窝空口签名（TTI/C-DRX 微批 + 弱信号联动）待 E-02 真机采集**——AIRLINK_SUSPECT 分支与
  8/10/20/40ms 网格命中率阈值（GRID_HIT_THRESHOLD/GRID_TOLERANCE_US）本次无真实样本，仍为纯合成标定。
- autocorr 分量基线（CLEAN_LAG1_BASELINE=−0.5）与权重 0.2 需在真机样本回流后重标定（§3.3）。
- proxied 组只覆盖"逐 event 转发型"代理；带累积的真实代理（企业网关/CDN）签名待补。

## 7. 清理记录

- docker 容器 `aneb-nginx`：`docker stop`（`--rm` 自动删除），`docker ps -a --filter name=aneb` 确认为空；
  未触碰其他项目容器。
- 自起进程：本地标定用 aneb-server（先 127.0.0.1:8443 后 :8443，pid 24800/8376）均已 Stop-Process 确认退出；
  E-01 公网服务器为常驻部署，未做任何变更。
- 采集器与标定 server 二进制均在 %TEMP%，不入库。
