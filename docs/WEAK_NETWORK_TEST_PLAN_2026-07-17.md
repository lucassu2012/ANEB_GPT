# P40 Pro 弱网测试方案

## 结论先行

ANEB 可以稳定、可重复地模拟“低速率、高时延、抖动、短时中断”等弱网结果，但不能由普通 App/ADB 可信地把 P40 Pro 的真实 RSRP 或 SINR 改成指定值。两类证据必须分开：

1. **合成网络损伤**：用于验证动态界面、评分、结论和恢复算法是否对弱网敏感。
2. **真实无线弱覆盖**：用于研究 RSRP/SINR 与业务质量的关系，需要射频设备或可控现场。

## 推荐实施顺序

### A. MVP：逐 run 隔离的合成弱网（容量/时延与恢复 Profile 均已上线）

在 E-01 增加独立的用户态损伤适配路径，而不是给整机网卡挂全局 `tc/netem`。普通 8443 路径保持原样；只有明确选择弱网 Profile 的请求进入适配器。每次 run 固定 `impairment_profile_id/version/seed`，服务端回显实际生效参数，App 结果冻结：

- `synthetic_impairment=true`
- `impairment_profile_id@version`
- 目标与实测的上/下行速率、附加 RTT、抖动、应用未返回率、受控中断次数
- 同期手机实际 RSRP/RSRQ/SINR、RAT、频段、小区，只作为协变量

首批拆成两个 Profile，不混测：

| Profile | 目的 | 合成条件（初版） |
|---|---|---|
| `network_comprehensive_weak_capacity_latency@1.1.0` | **已上线**；验证容量、响应性和稳定性评分，使用语义结论策略 v2 | 下行 3Mbps、上行 1Mbps、附加 RTT 120ms、抖动 30ms；不主动断线 |
| `network_comprehensive_weak_recovery@1.1.0` | **已上线**；验证短时中断与恢复，使用语义结论策略 v2 | 下行 5Mbps、上行 2Mbps、附加 RTT 80ms、抖动 20ms；每 run 独立注入一次 2s 应用请求中断 |

初版数值是测试刺激，不是运营商 SLA，也不是“弱网”的行业唯一标准。每个 Profile 的正常网络对照 run 必须使用同一设备、同一节点、相邻时间窗口；评分算法应能预测方向：TTFT/loaded RTT 上升、吞吐下降、任务超时与重试 Token 开销上升。若没有按预期变化，先判测试机制或算法不敏感，不能反过来宣称网络良好。

已实现合同：

- 正常路径仍为 `/api/v1/*`；只有 `/synthetic/weak-capacity-latency-v1/api/v1/{echo,download,upload}` 进入整形。
- 每个请求必须带 `impair_run/impair_seed/impair_seq`；同一 run 的并发连接共享聚合上/下行限速器。
- 服务端回传 Profile 与参数头；App 逐请求核验，缺回执或错回执将整次证据判为 `INVALID`。
- 初版不整形 DNS/TCP/TLS/UDP/RSRP/SINR，不注入 IP 丢包；这些字段必须继续标为现场协变量。
- Room v16 永久冻结 Profile id/version、目标容量/时延/抖动、排除项和服务器确认状态；历史记录明确显示“合成弱网”。
- 上行采用 128KiB 双连接请求，并只按服务端确认收到的字节计量；本机 socket 写入量不进入弱网 goodput。
- 恢复路由只对触发它的 run 返回带 `X-Aneb-Synthetic-Outage: active` 的 503；其他 run 和正常路由保持 200，2 秒后自动恢复；重复触发不能延长窗口。
- Recovery 使用独立 `network-recovery-score-v1`，必需指标为中断已观察、触发回执至首个成功请求 ≤3000ms、恢复后请求成功率 ≥95%、恢复后 RTT ≤300ms 达标比例 ≥95%；单次事件固定 `LOW/INCONCLUSIVE`。

2026-07-17 P40 Pro 相邻实测：正常 Standard run `019f6e96-c278-7b68-87f6-7b2c61d58af0` 对比合成弱网 run `019f6e93-6b32-7776-9b3d-a5433814e0dd`，下载 P5 `17.66→2.80Mbps`、上传 P5 `15.91→1.12Mbps`、空闲 RTT P95 `109.80→228.03ms`、综合分 `51.2→32.0`。弱网上传 15 秒获服务端确认 1,835,008B，阶段平均约 0.98Mbps。loaded RTT 没有单调上升（正常 548.74ms、弱网 365.43ms），因此只按实际证据陈述，不把用户态整形等同于真实无线排队。

### B. 可重复网络层实验：独立网关（软件发布候选完成，现场叶证书与 P40 硬件闭环待执行）

让 P40 Pro 接入专用 Wi-Fi 测试网关（Linux 小主机/树莓派/独立 VM 网关），在独立 network namespace 中用 `tc netem + tbf/ifb` 控制双向带宽、时延、抖动和 IP 丢包。它比用户态适配器更接近真实网络层，也不会影响 E-01 其他用户。每次实验保存网关配置、接口计数器和前后基线。

已完成：`aneb-gateway/0.2.0`、固定 Debug CA/逐启动证书链核验、版本化白名单、双向 WAN/IFB 整形、全主路由旁路拒绝、严格资源所有权、清理失败锁闩与重试、一键安装/回滚/卸载安全回归，以及 App 0.5.0 的 `gateway_loss/gateway_recovery` 异常清理和 Room v18 证据冻结。加固前数据面复验为 `0.067ms → 97.753ms → 0.051ms`、100% 双向中断后自动恢复、10 条操作事件和宿主 qdisc 不变；它不替代最终固定 CA 正向生命周期。仓库无 CA 私钥和现场叶证书，也没有 P40 独占双网口/AP；两项当前均明确记为 `BLOCKED_EXTERNAL`，详见 `DEDICATED_GATEWAY_PLAN_AND_VALIDATION_2026-07-17.md`。

### C. 真实 RSRP/SINR：射频实验

使用屏蔽箱 + 可编程衰减器，或 4G/5G 基站模拟器，逐档改变下行/上行衰减和干扰。RSRP/SINR 的目标值必须由手机采集值和仪表读数共同确认。只在这个层级允许写“RSRP 被调到 −110dBm”“SINR 被调到 0dB”之类结论。

## 明确禁止

- 不把服务器延时写成“手机 RTT 被硬件调整”。
- 不把应用层未返回率写成 IP 丢包率。
- 不用假 RSRP/SINR 覆盖手机采集值。
- 不在共享 E-01 的物理网卡、8443 正常路径或其他租户上做全局限速/netem。
- 不把中断恢复 Profile 的分数与无故障 Standard 混成一个总分。
- 不把命名空间或 PC 集成结果写成 P40 真机网络层实测；没有独占网关时不得启动网关 Profile。

## 验收门槛

容量/时延与恢复 Profile 均已满足：正常路由独立；同 run 并发只能共享容量、不同 run 独立；seed 可复现；App 启动确认、实时页、结果页和历史均标注“合成弱网”；原始证据冻结目标、回执、排除项与实测指标；取消后不残留系统级损伤；失败样本保持 null/失败事件。Recovery 已验证同 run 503、其他 run/正常路由 200、窗口后自动 200、动态恢复计时与 Room v17 落库。这里的“中断”严格指应用请求不可用窗口，不得写成合成 IP 断网或真实无线断网。
