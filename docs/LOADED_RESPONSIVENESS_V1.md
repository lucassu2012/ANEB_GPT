# 负载下响应性纵切 v1

2026-10-24，PO 已批准。产品基线 b69e2f0；独立模式，不替代旧 Basic、Token 或 Prototype。

## 查重与复用

ANEB 历史网络产品已经实现并发 echo、合并 loaded P95 和导出：
`aneb-release-sprint-r1@ca027fd5` 的 NetworkSpeedEngine / NetworkComprehensiveScorer，
及 M0_EC3_NETWORK_QUICK_READY_VALIDATION_2026-07-27.md。
它是历史 E-01 验收，不是本轮本地节点验收。两方向仅在内存分开，原始导出没有
逐 echo 的阶段边界/字节重叠依据。本轮不整体合并历史产品树，不重写综合评分。

当前产品已有 AnebClient.echo/downloadThroughput/uploadThroughput、NetworkSpeedEngine、
Room report_body、Exporter。只增加显式“负载响应性”模式及其旁列证据。
旧 postLoadPingMs 是负载后测量，绝不改称 loaded RTT。

## 固定范围

- 沿用 Basic 既有本地节点协议、网络绑定、吞吐工作负载；旧模式默认完全不增加 echo。
- 独立模式依次保留 idle、download、upload 公开应用层 echo。负载方向不合并评分。
- idle 用有效 RTT P50；每个负载方向用有效 RTT P95（排序后的线性插值），
  增量=对应 P95−idle P50，可为负，不截成零；没有足够事实则 null。
- raw 保留 run、节点、transport、方法版本、Android elapsed 时间域、phase 起终点、
  每次 echo 的 t0/t1/t2/t3（微秒）、HTTP/error、前后实际观测字节数。
- 字节回调另存单调时间和累计值；按 RTT 的 t0/t3 界定重叠，而不是整个 HTTP 调用返回时间。
  取消排空期间的阶段外记录保留在 raw，不用于阶段内派生。负载 echo 间隔250ms、单次上限2秒。
- 只有完整落在该阶段内、四戳有效且探测期间可见传输字节增加的 echo 才作为
  “观测到传输重叠”的 loaded 样本；其余保留但不入聚合。该保守条件不证明线路饱和。
- 上传字节是客户端写入回调，不声称服务端接收/物理链路发送时刻；结果明确解释。
- 原始样本、排除原因、有效数量、方向统计和缺失理由同包保存；复用 Room 和 MediaStore 导出。
- 不增加 AQS/RPI/综合分、门限、网络因果结论、UDP/Recovery、第三方 App 操作或云端改动。
- 本地验收仅 Debug 专属客户端允许固定私有节点的 echo/profiles/download/upload 路径；
  不放宽旧客户端/Prototype/Release 明文策略。新增数据复用现有 report_body，不升级 Room schema。

## 验收标准

1. 负载失败但 echo 成功：loaded 结果仍 null，失败/原始 echo 不丢失。
2. 两方向具有不同样本时分别计算，不混合；跨阶段、无字节重叠及失败 echo 不混入。
3. UI→保存→重开→导出保持原始证据与统计一致；可从 raw 独立复算。
4. 旧 Basic/Token/Prototype 的行为、指标、评分、冻结合同与旧资料保持不变。
5. 取消后无自有残留请求/服务；失败不改成零或成功。
6. 固定候选后一次完整质量门；明确 APK SHA，再只做一次 P40/本地节点验收。
   现场按 AGENTS 新鲜预检；未知会话不接管；只清理本轮自有资源并恢复临时设置。

## 交付边界

这是 App→指定节点、在所记录工作负载中的应用层响应性测量，不是第三方 App 的
完整回答耗时、运营商评级、无线时延或拥塞根因。历史数据/失败/NA 不变。
千问采集不恢复。本次不重新制作旧研究包，不使用生产 signer，不改变上传关闭状态。
