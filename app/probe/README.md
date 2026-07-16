# :probe 模块（Release: com.aneb.probe；Debug: com.aneb.probe.codex）

阶段 0 实现范围：S1 单场景（clock_sync ×20 → upload_burst 2KB → token_stream 600/100 token），
全部时间戳经 `TestEngine` 的 `Flow<String>` 打到屏幕日志。

## 构建

```
# 工程根 app/ 下（先按 app/README.md 补齐 wrapper）
.\gradlew :probe:assembleDebug
```

## 包结构

| 包 | 内容 | 对应红队项 |
|---|---|---|
| `net/TimingEventListener` | OkHttp EventListener，elapsedRealtimeNanos 就地打戳全部传输事件 | — |
| `net/SseReader` | 按可用量批读（8192B/次）+ `\n\n` 边界切 event + sameReadBatch 标记 | R-04 |
| `net/AnebClient` | echo（Cristian offset±RTT/2）/ runS1Stream（seq join 校验 gap）/ uploadBurst（逐块打戳，终点=2xx 响应头） | R-07 / R-08 |
| `engine/TestEngine` | S1 phase 顺序执行 + 汇总（TTFT、ITL 中位/P95 剔除伪 0、stall、gap、offset） | R-09（口径 TODO）/ R-10 |
| `radio/RadioCollector` | 权限检查后 TelephonyManager 快照；无权限返回 valid_low_confidence 降级串 | R-02（TODO） |
| `data/` | Room 骨架：TestRun / TokenEventEntity（时延字段全可空，失败记 null） | R-10 |
| `ui/MainActivity` | Compose 单屏：地址输入 + Run S1 + Radio snapshot + 滚动日志 | — |

## 服务端 wire 约定（阶段 0，供 server/ 联调）

- `POST /api/v1/echo`：任意小 body；响应 JSON `{"t1_us":<收到时刻>,"t2_us":<发出时刻>}`，
  服务端进程启动锚点单调微秒（防 NTP 步进，R-24）。
- `GET /api/v1/stream?profile=s1_chat&run=&tokens=N`：SSE：
  - 首帧注释：`: prelude {"srv_ts_us":...}\n\n`（R-20）
  - token：`event: token\ndata: {"seq":N,"sched_us":...,"pre_flush_us":...,"payload":"<base64>"}\n\n`
    （payload base64 编码，杜绝随机字节与 `\n\n` 冲突，R-08）
  - 结尾：`event: summary\ndata: {...}\n\n`
- `POST /api/v1/upload?run=`：丢弃 body，2xx 响应即"服务端已读完"（U1 终点口径，R-07）。

## 已知 TODO（阶段 1）

- [ ] SSE 解析移出读线程；读线程 THREAD_PRIORITY_URGENT_AUDIO + 哨兵线程（§4.10 / R-16）
- [ ] NetGuard：requestNetwork 绑定 socketFactory + Dns=network::getAllByName、
      VALIDATED/NOT_SUSPENDED 就绪守卫、VPN/代理硬拒测、双端源 IP 对账（R-01 / R-03）
- [ ] T2/T3/T4 改为 seq 对齐的网络贡献残差口径（到达间隔 − sched_us 间隔）（R-09）；
      纯本地回环金样本断言 T3≈0
- [ ] 三态 Gate（valid / valid_low_confidence / invalid）全程监控接线 + 首事件获胜状态机（R-12）
- [ ] 批化检测残差域 buffering_score（R-05）
- [ ] RadioCollector：TelephonyCallback 1Hz、requestCellInfoUpdate、getTimestampNanos 对齐、
      subId 绑定、制式三元组（R-02 / R-13 / R-15）
- [ ] 前台 dataSync Service + 屏幕常亮；取证模式低频日志刷新
- [ ] Room 全接线（EchoSample / RadioSample / EnvEvent / ScenarioResult）+ 批量事务落库 + 导出/上报
- [ ] echo 前的 warmup 丢弃已实现，clock_sync 场景尾部第二轮（skew 插值）未实现（R-22）
- [ ] profile JSON 拉取与版本校验（当前 S1 参数硬编码，与 profiles/s1_chat.json v0.2.0 对齐）
