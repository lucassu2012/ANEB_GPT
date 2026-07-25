# 共享资源自动释放复核证据（2026-07-18）

> ［KNOWN｜HIGH］本文件仅保留 2026-07-18 的历史验证证据。Product Owner 已于 2026-07-19
> 退役 `SHARED_TEST_STATUS.md`、lease、交接状态机和受限 `Verifier`；同名脚本现为固定非零退出且
> 不执行 ADB、SSH 或状态写入的兼容薄壳。下文不得作为当前操作授权或流程说明。

## 1. 结论

- ［KNOWN｜HIGH］2026-07-18 22:01 CST，受限 `Verifier` 完成 T+0/T+10秒 两轮只读复核，并把共享状态从“待交接”自动转换为“空闲”。旧 lease `21abc40c00c41e756d4f484956aca522` 已结束，后续不得复用。
- ［KNOWN｜HIGH］2026-07-18 23:12 CST，受限 `Verifier` 又为 Claude lease
  `0e5e65c70ae3cffa1bad659a8bad1868` 完成 T+0/T+10秒 两轮复核并自动释放。该轮确认 P40 清理、
  E-01 0.7.0 六项冻结指纹及全部 Phase 0 运行态/持久态残留为 0；lease 随释放结束，不得复用。
- ［KNOWN｜HIGH］23:12 复核启动时绑定的“待交接”原始文件 SHA-256 为
  `78AE0573E7F09DAC004CE1E93460E759F9B01C05EC277C73D589EB167F340C9A`；命令原始输出为
  `OK verification=passed transition=handoff->idle`。原子释放后的状态文件 SHA-256 为
  `88BA85ACA36E052F5926076A5B4C03BAD8EFF894CA8AEC5243859E54DA690735`，状态为“空闲”。
- ［KNOWN｜HIGH］两轮均确认 E-01 为 `aneb-server/0.7.0` 且服务为 `active`；本次没有部署 `aneb-server/0.8.0`，也没有完成 0.8.0 的 P40 Token Quick 正向验收。
- ［KNOWN｜HIGH］P40 两轮均匹配精确设备，Codex/Claude ANEB、PCAPdroid 主包与 MITM、WireGuard 共 5 个包均无 PID 或活动 `ServiceRecord`；ANEB AccessibilityService 未启用、未绑定，`tun0` 与活动 VPN 为 0，Launcher 在前台，`stayon=0`，Wi-Fi 为 `CONNECTED+VALIDATED`。
- ［KNOWN｜HIGH］22:01 handoff 的占用资源精确为 `P40 Pro、ANEB Codex、E-01`；23:12
  handoff 的占用资源精确为 `P40 Pro、ANEB Claude、E-01`。两次均由手机探针覆盖 P40/对应 ANEB
  包，由服务器探针覆盖 E-01。当时的加固 Profile 只允许 `P40 Pro`、`ANEB Codex`、`ANEB Claude`、
  `E-01` 四种精确 token；当时遇到未覆盖资源会在探针前拒绝并保持“待交接”。该规则已由 D-80
  取代，不能作为当前流程继续执行。

## 2. E-01 0.7 冻结指纹

［KNOWN｜HIGH］以下值是 22:01 最终两轮只读复核实际匹配的完整 SHA-256。不得用前缀、推测值或首次 0.8 候选值替代。

| 证据项 | 完整 SHA-256 |
|---|---|
| 当前 0.7 Linux 二进制 | `9208aba26f18ea00d18d1bbcf3f1c6f7042e66b341675a58048894b168ba6b5b` |
| `eth0` qdisc | `e9455ff1a3a44f3b5979ee068f8c4e3fe90aa0ebdd30e89add8299403958cbac` |
| 防火墙 full | `08e3d3dfeb9f3e4ddc69ba440c5af7697536b0d45c3016068b33cb9d36ab75dd` |
| 防火墙 IPv4 | `66b46a501b972e9b8d3d7fa0ab38e9e2b0fb24f5e521f4c5ca11ef60a53a0100` |
| 防火墙 IPv6 | `192a359dda179d478c0e99eb3b0817894794ce62495afd489ed12a5e433c395e` |
| 防火墙 nft | `dd5369267b8eb08ddfdfde3a0e1c57f034951d608c45ac1409ccdafc77024657` |

［KNOWN｜HIGH］当时的探针确认 `wg-aneb-lab`、`ifb-aneb-lab`、`ANEB_LAB` 规则与 UDP 51820 监听均不存在。这只能作为运行态无残留证据；该版本探针没有逐项复核 systemd enabled 状态及 Phase 0 精确持久路径，因此不能单靠这份复核记录声称持久残留绝对为 0。本次工作树中的 v2 探针已补入 unit、配置/密钥、`/opt`、`/var/lib` 与 `/run` 精确路径检查，但这不能追溯升级 22:01 的历史证据。

## 3. 本轮发现并修复的错误类别

- ［KNOWN｜HIGH］Windows 默认 CP936 解码会破坏 ADB/SSH 的 UTF-8 输出；命令执行层改为捕获原始字节并按 UTF-8 严格解码。
- ［KNOWN｜HIGH］华为 Accessibility 输出的 CRLF、多用户段和 client 列表使旧解析器把 Kimi 的绑定误归到 ANEB；解析器现按用户段、字段边界和精确组件名 fail closed。
- ［KNOWN｜HIGH］P40 拒绝 `ip link` 命令；只读接口枚举改用 `/sys/class/net`，并严格校验接口名称与精确 `tun0`。
- ［KNOWN｜HIGH］E-01 的 `iptables-save`/`ip6tables-save` 是逐表生成多组 `Generated/Completed` wrapper；规范化器现逐组验证并只移除各组时间戳。
- ［KNOWN｜HIGH］交接资源必须使用当前复核 Profile 的精确 token；Claude 首次写入泛称 `ANEB` 时，
  Verifier 在任何 ADB/SSH 探针前以 `handoff_resource_unsupported` 拒绝，原执行方更正为
  `ANEB Claude` 后才允许继续。
- ［KNOWN｜HIGH］Codex 首次重跑时误把完整服务器身份参数 `aneb-server/0.7.0` 写成短值
  `0.7.0`，导致 `e01_version_mismatch` 错误锁定。该结论不是服务器变化证据；原执行方保留同一
  lease 受控恢复为待交接。当时加固后的 Verifier 要求 `--expected-version` 符合完整
  `aneb-server/<semver>` 形式，并在任何探针前拒绝短值；该实现现仅保留为退役历史。［RULES I BROKE］我没有在首次执行前核对
  CLI 参数的完整身份语义，造成一次可避免的错误锁定；已增加零探针回归测试防止复发。
- ［KNOWN｜HIGH］使用正确参数后，v2 探针实际发现 `/run/aneb-experience-lab` 空目录和
  `/run/lock/aneb-experience-lab.lock` 0 字节锁文件。Claude 只读确认它们均为 root 自有、非链接、
  无占用，且其他 Phase 0 unit/接口/规则/端口/密钥/配置及 `/opt`、`/var/lib` 路径均不存在；仅删除
  这两项后，连续两轮服务器探针与最终 T+0/T+10秒 复核全部通过。

## 4. 证据边界

- ［KNOWN｜HIGH］当时联调现场把目标记录为 0.7；该现场产生的两条旧 Quick retained result 本身能证明客户端结果为 `INVALID`、score/grade 为 null，任务/KPI 字段为 0，且没有客户端业务产物。
- ［KNOWN｜HIGH］这些 retained result 没有持久化 `endpoint.server_version`，证据包也没有原始 `/serverinfo`、同 run 服务端 access log/计数或 PCAP；机器可读 `reason_code=receipt_missing` 也未持久化。因此不能仅靠这两条记录独立证明目标当时必为 0.7，或服务端绝对没有收到任何业务 HTTP 请求。
- ［KNOWN｜HIGH］22:01 的服务器身份与六项指纹来自独立只读探针；它们不能倒推首次 0.8 切换窗口中的候选二进制 SHA 或当时的全部网络语义。

## 5. 下一次 0.8 staging 的强制证据

［KNOWN｜HIGH］本节是 E-01 自身的部署证据合同，继续有效；它不依赖也不复活已退役的共享状态、
lease、待交接或自动 `Verifier`。现行部署还必须使用远端内核 `flock`、受限变更、原子回滚和验后检查。

［KNOWN｜HIGH］正式 staging/部署一开始就必须持久化以下字段，缺一项不得宣布部署完成：

1. ［KNOWN｜HIGH］实际 Linux 候选二进制完整 SHA-256。
2. ［KNOWN｜HIGH］Go toolchain 精确版本、源码 commit、可复现构建参数与构建环境标识。
3. ［KNOWN｜HIGH］staged receipt：候选版本、能力合同、Profile/manifest 身份和 staged 文件路径/摘要。
4. ［KNOWN｜HIGH］切换前后的原始 `/api/v1/serverinfo` 与响应头。
5. ［KNOWN｜HIGH］正向及负向 run 的机器可读 `reason_code`、与 run ID 绑定的客户端冻结结果，以及
   D-81 定义的服务端 request-entry 审计。审计窗口必须使用与目标 run 两两不同的规范 UUID 发送唯一
   `window_start`/`window_end` `/serverinfo` barrier，并要求同一 `instance_id`、严格连续 `seq`、恰好一个
   先于业务的 `capability`、窗口内无 drop、重启、缺号、并发窗口或未归因业务。判定器 PASS 只证明
   request-entry coverage；它不证明 handler/响应/客户端下载成功，也不能脱离客户端结果单独推出正向
   成功。负向零业务同样要求完整双边界与客户端 fail-closed 结果；任一证据缺失只能记“证据不完整”。
6. ［KNOWN｜HIGH］部署前后完整六项冻结指纹；不得只记前缀或“稳定”。

## 6. 2026-07-19 起的现行 P40 现场规则

- ［KNOWN｜HIGH］开测前只读确认设备在线、Huawei Launcher 前台，且无冲突 ANEB/目标业务 App、
  VPN/tun 或抓包进程/服务；干净即可直接测试，不再 claim、handoff 或等待另一方释放。
- ［KNOWN｜HIGH］无法安全归属的既有会话不得擅自停止、覆盖或清理，必须先协调。
- ［KNOWN｜HIGH］结束后停止本轮全部相关 App、VPN、抓包与临时网络规则，恢复临时设置、返回
  Launcher 并立即复核；Launcher 可见但后台仍有残留时不能称为干净。
