# ANEB E-01 测试服务器能力与部署合同

> 这是 Codex 与 Claude 共用的 E-01 权威说明。每次服务端二进制、Profile、证书、端口、资源限额或测试语义变化时，必须由 Codex 在同一提交中更新本文，并在部署后回写验证时间与结果。

## 1. 当前部署基线

| 项目 | 当前合同 |
|---|---|
| 节点 | E-01，深圳，公网 `120.79.148.0:8443` |
| 服务端 | `aneb-server/0.8.2`，Linux/amd64 |
| 主通道 | `https://120.79.148.0:8443`；项目 App 使用自有 IP-SAN 信任锚 |
| SNI 通道 | `https://120-79-148-0.sslip.io:8443`；部分蜂窝网络已观察到 SNI-keyed RST，只用于 REACH 对照，不作为强制主通道 |
| 协议 | TCP/TLS（HTTP/1.1、HTTP/2）+ UDP/8443 HTTP/3 + 同端口 `ANEB2`（run UUID + 序号 + 单调时间）网络综合应用探针；继续接收 `ANEB1` 仅用于旧客户端兼容 |
| 服务隔离 | systemd 用户 `aneb`；`MemoryMax=384M`、`CPUQuota=120%`、`TasksMax=256` |
| 部署所有权 | **仅 Codex 部署**。Claude 提交需求或补丁，但不直接改 E-01，避免共享资源互相覆盖 |
| 最近验证 | ［KNOWN｜HIGH］2026-07-27 CST；受保护部署与独立锁内验后检查确认 E-01 为 `aneb-server/0.8.2`、`active`、H3=true，PID=`1295423`，InvocationID=`d975f7c374aa4ef3a490210d0a495e53`，live binary SHA-256=`62ff966bf396abe836c6179053ee549110e41e16af569cdeadc97535bc64c96e`；stage 与 owned `.new/.restore/.absent` 残留均为 0，watchdog timer/service 为 `not-found/inactive`，远端 flock 已释放。 |

> ［KNOWN｜HIGH］2026-07-18 的 0.7.0 六项指纹仍是本次切换前冻结的历史回滚基线；当时的
> 自动状态释放流程已经退役，不构成当前操作授权。当前规则见 D-80 和下述切换门禁。

［KNOWN｜HIGH］当前部署要求所有 HTTP 响应带 `X-Aneb-Server: aneb-server/0.8.2`。`GET /api/v1/serverinfo` 的 `h3_enabled=true` 只表示服务端启用了 H3；某次请求是否真的走 H3，必须看该次协商记录/`X-Aneb-Proto`，不得推断。

### 1.1 `aneb-server/0.8.2` M0-EC3 当前部署

- ［KNOWN｜HIGH］0.8.2 把 `network_comprehensive_quick@1.2.0` 加入第三个精确校验的运行包；Profile 规范化 SHA-256 为 `15ae5187fac72d86b78ff89ad44d5a51706dc7c4e4cf01432f367acd9ed082cc`，runtime plan 规范化 SHA-256 为 `8981267030abd4cd95dabe3e3bff8d2af4b7de6b8659cc8c267c97f519cf2603`。
- ［KNOWN｜HIGH］当前能力回执包含六项原语，并新增 `upload/aneb-upload-v1` 与 `udp_echo/aneb-udp-echo-v2`；Network Quick 要求 `download`、`echo`、`upload`、`udp_echo` 四项原语全部精确匹配，并在首个业务请求前完成 P1/P2/Profile 对账。
- ［KNOWN｜HIGH］`ANEB2` UDP 数据报固定绑定当前 run UUID、序号和发送时间；服务端只做同长度原样回显，不提供任意转发，也不放大流量。0.8.2 保留接收 `ANEB1` 仅用于旧客户端兼容，但 0.5.14 Network Quick 只发送并接受同 run 的 `ANEB2`。
- ［KNOWN｜HIGH］服务启动时若发布 Network Quick，TCP 与 UDP 必须配置为相同端口；生产固定 8443，loopback staged candidate 可使用同一个随机 TCP/UDP 端口。UDP 缺失或端口不一致时服务拒绝启动。
- ［KNOWN｜HIGH］本次部署来源 commit=`33434dc4006cc4dfb41835f88e052dd57840bebd`，deployment ID=`20260727080647-d93d8d65d9704e688e6c0f1accbb93f0`，artifact manifest SHA-256=`8a525aa279c6c5030a1f2fc48bfebfc95a9618fe4c82c2b83a1977996bc3f9e0`，staged/live receipt SHA-256=`8229e5c039eb03f694bb404824ff499de6562e2bee822ab6c335d44e7b12f987`。受保护切换在锁内验证三份运行包、六项能力、HTTP/UDP smoke、共享主机指纹与完整 0.8.1 回滚面后才提交成功。
- ［KNOWN｜HIGH］切换前后共享指纹一致：Docker=`2175cb68233763ebc1724fc37126d43d8a8372ab4f3324a53a348c2194b86c27`、eth0 qdisc=`e9455ff1a3a44f3b5979ee068f8c4e3fe90aa0ebdd30e89add8299403958cbac`、IPv4=`4580dc17c402f6cbe6730cd0e2b8ee4518abe4328e9dad5ba8f394952d60fd95`、IPv6=`192a359dda179d478c0e99eb3b0817894794ce62495afd489ed12a5e433c395e`、nft=`3a941c6d9776ff39234a1918654eafce570d22410f6c428fc4c5bfe61a1608eb`、full firewall=`dc8cc094543ae04272ed13837a9afc0e1f62e6661d4b0715062ef134ffd569f3`。

### 1.2 `aneb-server/0.8.0` 历史部署记录

> ［KNOWN｜HIGH］本小节保留 2026-07-19 的 0.8.0 历史切换证据，不描述当前 E-01 现态；当前现态以上表 0.8.1 为准。

> ［KNOWN｜HIGH］2026-07-18 首次 0.8.0 切换尝试中，候选能力回执和全量旧端点 smoke
> 均通过；旧部署器随后因把 `iptables-save`/`ip6tables-save` 每次运行产生的
> `Generated/Completed` 时间写入全量哈希而误报共享防火墙变化，并自动回滚到 0.7.0。
> 该尝试不能写成“0.8.0 已部署”。［COMPUTED｜HIGH］事故后两秒配对样本的逐行差异仅出现在
> `Generated/Completed` 时间字段，移除这些字段后该样本的 v4/v6 指纹一致；三次事故后规范化组件
> 复核也稳定。由于旧部署器未保留
> 可用于事后语义对比的切换前 raw 快照，这些证据仍不能追溯证明切换窗口绝无并发语义变更；
> 因此当时选择先完成独立交接复核，而不是直接解除门禁；该历史复核已于 22:01 完成。当前重试
> 不再使用共享状态或 lease，而须遵循 D-80 的实时干净桌面和远端内核互斥锁规则。

- ［KNOWN｜HIGH］0.8.0 在启动时读取受控的已发布 Profile 目录，只为
  `token_multimodal_quick@1.2.1` 验证 `profile.json`、`runtime_plan.json` 与
  `manifest.sha256` 的完整性；Profile 规范化 SHA-256 必须为
  `caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773`。该计划从模型与
  seed 可复现派生，并以 task-0006 的 1MiB 返回附件真实覆盖 download 原语。
- ［KNOWN｜HIGH］任一 Profile 身份、执行要求、manifest 或白名单原语校验失败时，0.8.0
  拒绝启动，不能发出“支持 Quick”的回执。
- ［KNOWN｜HIGH］成功启动后，`GET /api/v1/serverinfo` 新增
  `execution_capabilities`，合同为 `aneb-server-capability-receipt@1.0.0`，应包含以下精确
  Quick 能力；原有 serverinfo 字段和 0.7.0 业务端点语义不因此改变。

```json
{
  "execution_capabilities": {
    "contract_id": "aneb-server-capability-receipt",
    "contract_version": "1.0.0",
    "primitives": [
      {"primitive_id": "download", "wire_contract_id": "aneb-download-v1"},
      {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
      {"primitive_id": "token_sim", "wire_contract_id": "aneb-token-task-v1"}
    ],
    "validated_profiles": [
      {
        "profile_id": "token_multimodal_quick",
        "profile_version": "1.2.1",
        "profile_sha256": "sha256:caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773"
      }
    ]
  }
}
```

- ［KNOWN｜HIGH］P1 0.5.12 可忽略回执中未知的额外 capability，但必须拒绝重复原语 ID、
  缺失的必需原语、线路合同冲突、合同版本不兼容或 Quick Profile 身份/哈希不一致；拒绝必须
  发生在首个 echo、token-sim 或 download 业务请求之前。`/serverinfo` 本身是预检请求，不计作
  Quick 业务流量。
- ［KNOWN｜HIGH］0.8.0 在最外层入口异步记录隐私有界的 request-entry 审计。run ID 只接受规范
  小写 UUID；`X-Aneb-Audit-Role` 只归一为 `reachability/capability/window_start/window_end/none/other`。
  每个进程有独立 `instance_id`，唯一 worker 为 AUDIT/DROP 写连续 `seq`；Token Quick 只保留 direct
  serverinfo/echo/token-sim/download 路径，其余 `/api/v1/*` 和全部 `/synthetic/*` 统一记为
  `/api/v1/other`，query/body/原始未知 header 不入日志。该审计在 handler 前发生，只证明请求进入，
  不证明响应或客户端下载成功；D-81/D-82 双 barrier、新鲜度 receipt 和客户端结果缺一不可。
- ［KNOWN｜HIGH］其余 11 个 Published Profile 没有 `execution_requirements`，继续走 0.5.10
  既有兼容路径；本版本不修改任何指标、质量目标、门限或评分。

#### 本次 0.7.0→0.8.0 切换门禁与未来部署原则

1. ［KNOWN｜HIGH］`SHARED_TEST_STATUS.md`、lease、待交接与自动 `Verifier` 已于 2026-07-19 退役，
   不再构成 P40、E-01 或阿里云的操作授权，也不得被更新为当前流程的一部分。
2. ［KNOWN｜HIGH］P40 开测前只读确认设备在线、Huawei Launcher 前台，且 Claude/Codex ANEB、
   本轮目标业务 App、VPN/tun 与抓包进程/服务均未活动；现场干净即可直接开始。若存在无法安全归属的
   会话，不得停止、覆盖或清理，必须先协调。
3. ［KNOWN｜HIGH］实际部署来源 commit `49095c0` 已通过 P2 Go、P3/catalog、Android/Release、
   部署安全与 GitHub CI 六个 job；watchdog 误报修复 commit `d0a904d` 又通过 GitHub run
   `29661388755` 的六个 job。未来任何 live 变更仍须先形成新的 clean commit，由本地门禁、
   凭据扫描和 CI 独立复现；任何一项失败都不得部署。
   候选构建与部署前 Go 测试必须额外固定并记录 `GOFIPS140=off`；宿主 `latest` 污染未被覆盖时即使
   其余 flags 相同也不得构建、上传或写来源证明。
4. ［KNOWN｜HIGH］E-01/阿里云使用独立保护链，不从 P40 现场或退役状态文件取得授权。部署脚本不再
   接收 `-LeaseId`，也不读取共享状态文件。远端必须在任何 live 变更前
   非阻塞取得 `/run/lock/aneb-deploy.lock` 的内核 `flock`，锁忙或锁能力不可用即失败；该锁覆盖预检
   证据、备份、替换、回滚、最终证据和清理。本次切换前必须同时验证 0.7.0 的 `serverinfo.version` 与
   `X-Aneb-Server`，并跑通与回滚相同的 Profile/echo/1MiB
   download/impairment/recovery/UDP smoke；只有健康的 0.7.0 才能作为回滚基线。随后冻结二进制
   SHA、Profile/service 以及 Docker iptables-save、`eth0` qdisc、全防火墙规则指纹；PID 因
   升级/回滚重启可变化，不作为不变量。最终修复提交 `4030179` 只删除 iptables-save 每次运行生成的
   `Generated/Completed` 时间，工具版本、backend、warning、链、policy、规则、规则注释和 nft
   语义全部保留；冻结时须在同一组连续采集两份规范化快照并要求完全相同，同时记录
   v4/v6/nft/Docker/full 分项指纹。采集失败、字段异常、两次快照不稳定或任一语义分项变化都须
   fail closed，不得用宽松过滤绕过共享主机门禁。
5. ［KNOWN｜HIGH］切换后必须同时核对 `X-Aneb-Server: aneb-server/0.8.0`、上述完整回执、
   manifest 精确哈希、既有 TCP/UDP 8443 与合成弱网 smoke；终态 success 证据和独立锁内验后检查
   闭合只代表“E-01 服务端切换子阶段通过”。完整 M0-EC1 验收仍须随后完成 P40 正/负向 run、同-run审计证据、
   清理并恢复实时干净桌面；现场审计还须保存 pre-start cursor/boot/invocation/MainPID、原始 journald
   JSON、双 barrier 响应、v2 判定报告及全证据 manifest，并在窗口内持有与部署互斥的审计锁。在此之前
   不得写“跨端部署验收完成”。

#### 本次切换回滚门禁

1. ［KNOWN｜HIGH］启动失败、回执不精确、既有端点回归或共享主机基线变化任一发生时，部署
   脚本必须恢复冻结的 0.7.0 二进制和 service 配置，不得让 0.8.0 以降级能力继续运行。
2. ［KNOWN｜HIGH］回滚后必须重新确认 0.7.0 的 header/body 身份、4 个根 Profile、echo、
   1MiB download、impairment 目录/容量路由、恢复同 run 503/隔离/恢复、UDP 回显均通过，并精确
   匹配冻结的 ANEB 文件及 Docker、`eth0`、全防火墙指纹；任一失败必须明确非零退出。
3. ［KNOWN｜HIGH］备份裁剪发生在成功验收并解除回滚保险丝之后；裁剪失败只报告维护 warning，
   不得回滚已验收部署，也不得把部署结果伪报为失败。
4. ［KNOWN｜HIGH］真机任务结束时，执行者必须停止本任务启动的 ANEB、业务、VPN 和抓包 App，清除
   本任务创建的临时网络条件，恢复 `stayon` 等临时设置，返回 Huawei Launcher，并立即确认无活动
   VPN/tun/相关进程。任一清理
   项无法确认时必须停止后续测试并报告实际残留；不得仅因屏幕显示桌面就声称现场干净。

［KNOWN｜HIGH］22:01 的受限 `Verifier` 放行属于历史证据；该机制现已退役。当前顺序固定为：
完成 rc=99 误报的本地修复与门禁 → 实时确认 P40 干净桌面并只读复核 E-01 仍为上述精确 0.8.0 →
执行 P40 Token Quick 正/负向 run 与同-run审计 → 完整清理。服务器未变化时不得仅为追求 rc=0
重复部署；也不得把公网 smoke 或历史负向真机结果替代正向跨端验收。

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
| `GET /api/v1/serverinfo` | 节点版本、运行时快照与执行能力回执 | 版本、uptime、H3 开关、TCP slow-start-after-idle、拥塞控制及 `execution_capabilities`；审计只证明 request entry，不证明 handler/客户端下载成功 |
| `GET /api/v1/impairments` | 已部署合成弱网合同目录 | 返回 `network_comprehensive_weak_capacity_latency@1.1.0` 与 `network_comprehensive_weak_recovery@1.1.0` |
| `/synthetic/weak-capacity-latency-v1/api/v1/{echo,download,upload}` | 逐 run 隔离的用户态弱网路径 | 必须携带 `impair_run/impair_seed/impair_seq`；只支持这 3 个端点；正常 `/api/v1/*` 路径不整形 |
| `/synthetic/weak-recovery-v1/api/v1/{echo,download,upload}` | 逐 run 隔离的恢复测试数据路径 | 基线 ↓5/↑2Mbps、附加 RTT `80±20ms`；只有同 run 已触发的窗口返回带确认头的 503 |
| `POST /synthetic/weak-recovery-v1/api/v1/recovery` | 触发该 run 的一次性 2 秒请求中断 | 必须携带 `impair_run/impair_seed/impair_seq`；首次与重复触发均返回 202，`armed` 表示是否首次武装 |
| UDP `:8443` | 网络综合带序号应用探针 | 当前 Network Quick 使用 `ANEB2 + run UUID + seq + monotonic timestamp`；服务端仅同长度原样回显。`ANEB1` 只保留旧客户端兼容；两者与 H3 共端口 |

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

唯一部署入口：`scripts/deploy_server.ps1`。部署前后必须检查 P40 Pro 上
`com.aneb.probe` 与 `com.aneb.probe.codex` 均未在测试；重启窗口通常少于 2 秒，但仍不得在任何
客户端 run 期间操作。仓库中若出现 IP-SAN 证书/私钥，默认拒绝部署；只有额外显式启用证书替换，
并同时提供证书与私钥各自的 SHA-256 pin，且本地和远端暂存文件均匹配时才进入证书门禁。远端还
必须确认二者公钥匹配、证书当前处于有效期内，且 SAN 精确包含 `IP:120.79.148.0`，才允许替换。

禁止事项：

- 禁止对共享主机设置全局 `tc/netem`、限速、丢包、时延或修改防火墙。
- 禁止修改 `chrony`、系统墙钟纪律、现有 node/mongod/python 服务。
- 禁止把故障注入参数开启在生产/取证服务上。
- 不得只替换 Profile 而跳过合同测试和公网 smoke。

专用网络层弱网能力位于仓库 `gateway/`，不是 E-01 服务端功能，也不得安装到 E-01。当前仓库基线为 `aneb-gateway/0.2.0`：固定 Debug CA 与逐启动证书链核验、全主路由旁路拒绝、严格 IFB/filter/ingress 所有权、可重试清理、Token/TLS key/状态目录安全，以及带回滚的一键安装和安全卸载。App 对应 0.5.0，网关恢复 Profile 为 `network_comprehensive_gateway_recovery@1.1.0` / `network-gateway-recovery-score-v2`。最终固定 CA 正向生命周期需要离线 CA 签发的现场叶证书，当前为 `BLOCKED_EXTERNAL`；早期命名空间数据面结果不能替代。权威边界和验证记录见 `docs/DEDICATED_GATEWAY_PLAN_AND_VALIDATION_2026-07-17.md`；部署命令见 `gateway/README.md`。Claude 可读取这两份文档了解网关 API/Profile，但 E-01 部署合同与版本不因此改变。

每次部署至少验证：Go 全量测试、4 个根 Profile、s3 精确版本/阶段/字节、echo、1MiB download 精确字节、两个合成弱网合同的目录/回执/精确字节、恢复触发后同 run 503/其他 run 200/正常路由 200/窗口后同 run 200、UDP 回显、`serverinfo` 版本和 H3 开关。非零退出必须原样保留，不能写成部署命令成功；只有终态 success 证据已提交、当前 live 身份精确匹配，且独立锁内验后确认共享主机基线不变和临时残留为 0 时，才能另行记录实际部署状态，仍不得扩大为 P40 跨端验收完成。

## 5. 弱网测试边界

商业 P40 Pro 上的真实 RSRP、RSRQ、SINR 由基站、频段、距离、遮挡和射频链路决定，普通 ADB/App 不能可信地“设置”为某个数值。需要真实可控无线指标时，必须使用屏蔽箱 + 可编程衰减器或基站模拟器。

软件可控的是**合成网络损伤**：带宽上限、附加时延、抖动、应用层未返回和短时中断。它只能用于验证 ANEB 对弱网的灵敏度和结论逻辑，结果必须标记 `synthetic_impairment=true`，并把手机实际采集的 RSRP/SINR 当作协变量，不得伪装成无线指标被改变。

E-01 已启用两个**用户态、逐 run 隔离**弱网合同；仍未、也不得启用整机/整端口全局 `netem`：

- Profile：`network_comprehensive_weak_capacity_latency@1.1.0`；路由 `weak-capacity-latency-v1`；结论策略 `network-comprehensive-conclusions-v2`。
- 合成条件：聚合下行 3Mbps、聚合上行 1Mbps、每个 HTTP 请求附加 RTT `120±30ms`；抖动由 `run+seed+seq` 确定性生成。
- 作用范围：HTTP 请求等待、请求体、响应体。并发连接共享同一 run 的上/下行限速器，不能靠增加并发绕过容量上限；run 状态 15 分钟无活动后回收，最多 4096 个活跃 run。
- 明确排除：DNS、TCP、TLS、UDP、RSRP、SINR；初版不注入 IP 丢包或断线。UDP 结果仍是未整形现场协变量。
- 防伪：成功响应带 `X-Aneb-Synthetic-Impairment: network_comprehensive_weak_capacity_latency@1.1.0` 与参数头；App 必须核对回执，否则结果 `INVALID`、分数抑制。
- 上行计量：弱网 Profile 使用双连接 128KiB 分块；客户端只累计服务端 `/upload` 回执确认的字节，不把本机 socket 写入量当成线上 goodput。

以下 P40 Pro 0.4.7 相邻对照属于历史 `1.0.0` 证据（同设备、同节点、同一 `AUTO` 承载，弱网后紧接正常 Standard），不得冒充 1.1.0 实测：

| 指标 | 正常 Standard `019f6e96…` | 合成弱网 `019f6e93…` | 方向 |
|---|---:|---:|---|
| 下载 P5 | 17.66Mbps | 2.80Mbps | 降低 84.2% |
| 上传 P5 | 15.91Mbps | 1.12Mbps | 降低 92.9% |
| 空闲 RTT P95 | 109.80ms | 228.03ms | 增加 118.24ms，接近声明的 +120ms |
| 网络综合分 | 51.2/D/FAIL | 32.0/D/FAIL | 弱网进一步下降 19.2 分 |
| UDP 未返回率 | 0% | 0% | 未整形，不据此声称模拟丢包 |

正常 run 的 loaded RTT P95 为 548.74ms，反而高于弱网 run 的 365.43ms；这是实际路径满载排队与用户态整形位置不同造成的观测，证明“合成时延更高”不能被外推为所有 loaded RTT 都必然单调上升。首个 Profile 的结论只适用于容量/应用时延敏感性验证。

独立恢复合同不得和容量/时延分数混算：

- Profile：`network_comprehensive_weak_recovery@1.1.0`；路由 `weak-recovery-v1`；独立评分 `network-recovery-score-v1`；结论策略 `network-recovery-conclusions-v2`。
- 合成条件：聚合下行 5Mbps、上行 2Mbps、每请求附加 RTT `80±20ms`；每个 run 最多武装一次 2000ms 应用请求不可用窗口。
- 中断证据：只有该 run 窗口内的 echo/download/upload 返回 503 并带 `X-Aneb-Synthetic-Outage: active` 才计为受控中断；其他 run 与正常路由始终旁路。中断不是 IP 丢包、无线断网或切网。
- 恢复终点：触发 202 回执至同 run 首个成功 echo；质量目标为恢复 ≤3000ms、恢复后 12 个请求成功率 ≥95%、恢复后 12 个 RTT 中至少 95% ≤300ms。未观察到中断、未恢复或缺必需样本时分数抑制/硬失败。
- 动态主指标：恢复用时；辅指标为服务器确认的中断状态、失败探针数与恢复后 RTT。单次事件即使达标也固定 `LOW/INCONCLUSIVE`，不能据此宣称长期 95% 恢复可靠性。

以下 P40 Pro 0.4.8 四个独立 run 属于历史 `1.0.0` 证据，不得冒充 1.1.0 实测：

| run | 恢复用时 | 中断失败 | 恢复后成功率 | 分数/结论 |
|---|---:|---:|---:|---|
| `019f6ee9-8c0f-7b82-99b3-040730f3e84b` | 2155.9ms | 8 | 100% | 100/A，LOW/INCONCLUSIVE |
| `019f6eed-0a93-7943-a235-94d4363c32e6` | 2084.4ms | 8 | 100% | 100/A，LOW/INCONCLUSIVE |
| `019f6eee-0d8a-748f-8e70-c5fef9d0a0b1` | 2119.5ms | 8 | 100% | 100/A，LOW/INCONCLUSIVE |
| `019f6ef0-1699-7f9c-8b6e-d299b0bf6328` | 2227.3ms | 9 | 100% | 99.8/A，LOW/INCONCLUSIVE |

末次 run 恢复后 RTT P95 为 152.6ms；动态画面捕获 1063.2ms 恢复计时、5 次服务器确认失败与“中断”状态。结果只证明 E-01 声明的应用请求窗口及 App 恢复算法，不代表真实蜂窝断网、RSRP/SINR 弱化或 IP 层丢包。

## 6. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-27 | ［KNOWN｜HIGH］从 commit `33434dc4006cc4dfb41835f88e052dd57840bebd` 受锁切换 E-01 到 `aneb-server/0.8.2`。live binary SHA-256=`62ff966bf396abe836c6179053ee549110e41e16af569cdeadc97535bc64c96e`，PID=`1295423`，InvocationID=`d975f7c374aa4ef3a490210d0a495e53`；三份 Quick 运行包、六项原语、HTTP/UDP smoke 与完整 0.8.1 回滚面通过。独立锁内验后确认 Docker/eth0 qdisc/IPv4/IPv6/nft/full firewall 六项共享指纹与切换前一致，stage/owned/watchdog 残留为 0，flock 已释放。该服务器切换事实不冒充尚未完成的 P40 Network Quick 正负 READY。 |
| 2026-07-19 | ［KNOWN｜HIGH］从 commit `49095c0314ac3900b6ed0c306d2eeaafc2edd87f` 受锁切换 E-01 到 `aneb-server/0.8.0`，live binary SHA-256=`fad6fdd53ebb73c63b2bf3b9f03106f1348626853cb344d72c3f6d08511fdce7`。能力回执、旧端点、合成弱网与 UDP smoke 通过，终态 success 证据逐文件摘要闭合；独立锁内验后确认共享主机五组防火墙/Docker 指纹及 eth0 qdisc 与切换前相同，临时残留为 0。原部署进程因 transient watchdog collect/stop 竞态返回 rc=99，故不写“部署命令成功”；服务器当前部署状态与 P40 跨端验收状态分开记录。 |
| 2026-07-19 | ［KNOWN｜HIGH］在该次候选更新时，仅更新尚未部署的 0.8.0 候选：加入规范 UUID/固定 role、进程实例与连续序号、全 API/synthetic 外层归一审计；D-81/D-82 将其限定为带新鲜度来源绑定的 request-entry 证据，不冒充请求完成。构建/部署来源证明新增 `GOFIPS140=off` 冻结；当时 E-01 仍为 0.7.0。 |
| 2026-07-19 | ［KNOWN｜HIGH］按 D-80 退役共享状态、lease、待交接和受限 Verifier 流程；部署入口移除 `-LeaseId`，保留 E-01 远端内核互斥锁、预检、快照、强回滚和证据门禁。P40 改为开测前实时干净桌面检查与结束后现场清理复核。 |
| 2026-07-18 | ［KNOWN｜HIGH］22:01 受限 `Verifier` 完成 T+0/T+10秒 两轮只读复核并自动释放共享状态为“空闲”；完整记录 0.7 binary/qdisc/firewall full/v4/v6/nft 六项 SHA-256。该复核不等于 0.8 已部署；同时纠正证据边界：当时探针只能证明 Phase 0 运行态无残留，旧 Quick 负向包只能独立证明客户端 fail closed 与无客户端业务产物。 |
| 2026-07-18 | ［KNOWN｜HIGH］首次 0.8.0 切换的候选回执和旧端点 smoke 通过，但旧脚本把 iptables-save/ip6tables-save 运行时间纳入 raw 防火墙哈希，触发误报并自动回滚至 0.7.0；不得记为已部署。［INFERRED,post-hoc｜MED］事故后两秒 raw/规范化配对和三次分项复核支持“采集时间字段解释了已观察漂移、当前 0.7.0/共享主机稳定”，但不能预测或事后证明切换窗口绝无并发语义变化。提交 `4030179` 仅规范化运行时间，新增同组双快照、v4/v6/nft/Docker/full 分项指纹、严格 wrapper/tool 校验及采集失败闭锁；当时 GitHub CI 6/6、本地全仓质量门和 87 项脚本测试通过。独立交接已于 22:01 完成；这些都是历史事实，当前重试改按 D-80 执行。 |
| 2026-07-18 | ［KNOWN｜HIGH］登记 `aneb-server/0.8.0` 执行能力候选及切换/回滚门禁；该候选尚未部署到 E-01，当前部署基线继续为 0.7.0。 |
| 2026-07-18 | 保持 `aneb-server/0.7.0` 二进制能力，部署弱网 Profile 1.1.0：仅升级可审计语义结论策略；根 Profile、`download_burst`、逐 run 弱网回执、恢复隔离、本机及公网 TCP/UDP 8443 smoke 通过。 |
| 2026-07-17 | 部署 `aneb-server/0.7.0`：新增逐 run 一次性 `weak-recovery-v1` 2 秒请求中断、同 run/其他 run/正常路由隔离 smoke；App 0.4.8/Room v17 增加独立 Recovery Profile、动态恢复仪表、独立评分与 P40 四次真机证据。 |
| 2026-07-17 | 部署 `aneb-server/0.6.0`：新增逐 run 聚合限速的 `weak-capacity-latency-v1` 用户态适配器、目录与回执头；正常路由不整形。App 0.4.7/Room v16 完成 P40 正常/弱网相邻对照，上行改为服务器确认字节口径。 |
| 2026-07-17 | 建立权威能力文档；Codex 接管唯一部署权；在 0.2.1 基础上合并 `s3_multimodal@0.3.0` 的两段 `download_burst`，并补客户端兼容执行与 fail-closed 字节校验。 |
