# ANEB E-01 测试服务器能力与部署合同

> 这是 Codex 与 Claude 共用的 E-01 权威说明。每次服务端二进制、Profile、证书、端口、资源限额或测试语义变化时，必须由 Codex 在同一提交中更新本文，并在部署后回写验证时间与结果。

## 1. 当前部署基线

| 项目 | 当前合同 |
|---|---|
| 节点 | E-01，深圳，公网 `120.79.148.0:8443` |
| 服务端 | `aneb-server/0.5.1`，Linux/amd64 |
| 主通道 | `https://120.79.148.0:8443`；项目 App 使用自有 IP-SAN 信任锚 |
| SNI 通道 | `https://120-79-148-0.sslip.io:8443`；部分蜂窝网络已观察到 SNI-keyed RST，只用于 REACH 对照，不作为强制主通道 |
| 协议 | TCP/TLS（HTTP/1.1、HTTP/2）+ UDP/8443 HTTP/3 + 同端口 `ANEB1` 带序号 UDP 应用探针 |
| 服务隔离 | systemd 用户 `aneb`；`MemoryMax=384M`、`CPUQuota=120%`、`TasksMax=256` |
| 部署所有权 | **仅 Codex 部署**。Claude 提交需求或补丁，但不直接改 E-01，避免共享资源互相覆盖 |
| 最近验证 | 2026-07-17 12:03 CST；节点本机与公网 smoke 均通过 |

所有 HTTP 响应应带 `X-Aneb-Server: aneb-server/0.5.1`。`GET /api/v1/serverinfo` 的 `h3_enabled=true` 只表示服务端启用了 H3；某次请求是否真的走 H3，必须看该次协商记录/`X-Aneb-Proto`，不得推断。

## 2. 已部署端点

| 方法与路径 | 用途 | 主要合同/限额 |
|---|---|---|
| `GET /api/v1/profiles` | 下发根 Profile | 返回 4 个根 Profile；服务端启动时解析失败会整体拒绝启动 |
| `POST /api/v1/echo` | RTT、时钟偏移和源地址对账 | 请求体不超过 100B；返回 `t1_us/t2_us/observed` |
| `GET /api/v1/stream` | 旧版 Token SSE | 支持 Profile+stream 序号或显式 token 参数；生产部署关闭 `inject` |
| `POST /api/v1/token-sim` | Profile v2 Token 多模态行为仿真 | 单计划 ≤1MiB、单任务上行 ≤128MiB、≤10000 Token、单 Token ≤4096B |
| `GET /api/v1/realtime-sim` | AI 实时语音 WebSocket 仿真 | 单计划 ≤1MiB、≤32 轮、单方向每轮 ≤10000 帧、单帧 ≤4096B；支持连接级受控中断 |
| `POST /api/v1/upload` | 上行突发 | 请求体 ≤64MiB；服务端返回 64KiB 读块到达时间 |
| `GET /api/v1/download` | 下行大对象 | `bytes` 1B–1GiB；`chunk_kb` 1–1024，默认 64MiB/256KiB；固定长度、禁缓存/压缩 |
| `POST /api/v1/toolloop` | 工具调用往返 | 上行 ≤64MiB；处理等待 0–60000ms；下行 0–16MiB |
| `POST /api/v1/results` | 旧版结果合同落盘 | JSON ≤1MiB；`claim_scope` 固定为 `application_end_to_end_to_probe_node`；schema `1.0` |
| `GET /api/v1/serverinfo` | 节点版本和运行时快照 | 版本、uptime、H3 开关、TCP slow-start-after-idle、拥塞控制 |
| UDP `:8443` | 网络综合带序号应用探针 | `ANEB1 + seq + monotonic timestamp` 魔数分流；与 H3 共端口 |

## 3. 根 Profile 清单

| Profile | 版本 | 说明 |
|---|---:|---|
| `s1_chat` | 0.2.1 | 小文本上行 + 稳态 Token 流 |
| `s2_coding_agent` | 0.2.1 | 较大 prompt、思考停顿、工具循环和突发 Token 簇 |
| `s3_multimodal` | **0.3.0** | 基于 0.2.1，在两段 Token 流之后各增加一次 12MiB 下行大对象 |
| `basic_network` | 0.1.0 | 基本下载、上传、时延与抖动 |

`s3_multimodal@0.3.0` 的 10 个阶段必须严格为：

```text
clock_sync → upload_burst(1MiB) → think_pause(2.5s) → token_stream(200@40tps)
→ download_burst(12MiB, 256KiB) → upload_burst(1MiB) → think_pause(2.5s)
→ token_stream(200@40tps) → download_burst(12MiB, 256KiB) → clock_sync
```

Claude 客户端可在 `download_burst` 直接调用：

```text
GET /api/v1/download?bytes=12582912&chunk_kb=256
```

D1 的终点是响应体最后一字节排空；非 2xx、截断或字节数不匹配必须记 `null`，不得记 0。Codex 0.4.6+ 同样执行，并在机器日志中输出原始 D1；旧版 Room 结果和 token-experience AQS **不新增 D1 字段、不改写评分**。Claude 客户端按自己的已冻结结果合同落 D1。跨版本比较必须按 `profile_id@version` 分组。

`profiles/published/` 下的 Profile v2 与哈希绑定运行计划随 App 发布；它们不是 `/api/v1/profiles` 当前返回的 4 个根 Profile。

## 4. 部署与共享主机纪律

唯一部署入口：`scripts/deploy_server.ps1`。部署前后必须检查 P40 Pro 上 `com.aneb.probe` 与 `com.aneb.probe.codex` 均未在测试；重启窗口通常少于 2 秒，但仍不得在任何客户端 run 期间操作。

禁止事项：

- 禁止对共享主机设置全局 `tc/netem`、限速、丢包、时延或修改防火墙。
- 禁止修改 `chrony`、系统墙钟纪律、现有 node/mongod/python 服务。
- 禁止把故障注入参数开启在生产/取证服务上。
- 不得只替换 Profile 而跳过合同测试和公网 smoke。

每次部署至少验证：Go 全量测试、4 个根 Profile、s3 精确版本/阶段/字节、echo、1MiB download 精确字节、UDP 回显、`serverinfo` 版本和 H3 开关。失败时不得把文档标成已部署。

## 5. 弱网测试边界

商业 P40 Pro 上的真实 RSRP、RSRQ、SINR 由基站、频段、距离、遮挡和射频链路决定，普通 ADB/App 不能可信地“设置”为某个数值。需要真实可控无线指标时，必须使用屏蔽箱 + 可编程衰减器或基站模拟器。

软件可控的是**合成网络损伤**：带宽上限、附加时延、抖动、应用层未返回和短时中断。它只能用于验证 ANEB 对弱网的灵敏度和结论逻辑，结果必须标记 `synthetic_impairment=true`，并把手机实际采集的 RSRP/SINR 当作协变量，不得伪装成无线指标被改变。

E-01 当前未启用弱网整机整端口整形。推荐实现为“单 run、单连接、固定版本 Profile”的隔离损伤合同；在该能力上线并完成并发隔离测试前，不得在共享节点运行全局 `netem`。

## 6. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-17 | 建立权威能力文档；Codex 接管唯一部署权；在 0.2.1 基础上合并 `s3_multimodal@0.3.0` 的两段 `download_burst`，并补客户端兼容执行与 fail-closed 字节校验。 |
