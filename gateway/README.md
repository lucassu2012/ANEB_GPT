# ANEB Dedicated Gateway

`aneb-gateway` 在**专用 Linux 转发网关**上对 P40 Pro 的真实 IP 转发流量施加可重复损伤。它与 E-01 应用层合成弱网严格分离。

## 证据边界

- 能证明：流量实际穿越专用网关时的网络层双向容量、时延、抖动、随机丢包及短时转发空窗。
- 不能证明：真实 RSRP/RSRQ/SINR 改变、基站调度、蜂窝重选或真正的 Wi-Fi/蜂窝切换。
- `ip_handover_gap` 只模拟“类切换转发空窗”，Profile 的 claim scope 明确写为 `not_radio_handover`。

## 安全模型

1. 非 dry-run 只允许 Linux、具备 `CAP_NET_ADMIN` 的服务，并强制读取 `aneb-dedicated-gateway-v1` 证明文件。
2. 只执行 `profiles/` 中的版本化白名单，不接收任意 `tc` 参数。
3. 同时最多一个实验；每个 Profile 有 120 秒硬上限和自动清理。
4. 进程启动、实验结束、SIGTERM 与 systemd `ExecStopPost` 都会清除 qdisc；清理后再次查询 qdisc/IFB，残留即失败。
5. 管理 API 默认只允许 TLS + 32 字符以上 Bearer token；明文只允许 loopback 网络命名空间测试。
6. 每次状态转换写入 JSONL 审计。无法写入首条审计时拒绝启动实验。

## 数据路径

```text
P40 Pro ── 专用 LAN/AP ── aneb-gateway ── WAN ── E-01
                              │
                              ├─ WAN egress netem：上行
                              └─ WAN ingress → IFB netem：下行
```

管理 API 走网关 LAN 本地地址，不经过 WAN/IFB 损伤，因此 App 可以在 WAN 100% loss 时继续读取实验状态并确认自动清理。

## API

- `GET /healthz`：无鉴权，只返回版本和 `radio_impairment=false`。
- `GET /v1/profiles`：白名单 Profile。
- `POST /v1/experiments`：`{"run_id":"...","profile_ref":"...@..."}`，返回 202/scheduled。
- `GET /v1/experiments/{id}`：`scheduled → active → clearing → completed/failed`。
- `DELETE /v1/experiments/{id}`：提前停止并清理。
- `GET /v1/status`：当前活动实验；无实验时为 `null`。

## 部署前提

- 两个独立接口：管理/LAN 与 WAN，不得在 E-01 或开发 PC 的共享联网接口上安装。
- P40 Pro 必须只通过专用 LAN/AP 上网，默认建议 `192.168.77.0/24`、网关 `192.168.77.1`。
- Linux `iproute2`、`ifb`、IP forwarding、NAT/防火墙和 DHCP/AP 由硬件部署脚本配置；网关服务本身不静默修改路由或防火墙。
- TLS 证书必须含管理 IP SAN；私钥与 Bearer token 不入 Git。

## 验证

```bash
cd gateway
go test -count=1 ./...
CGO_ENABLED=0 go build -o aneb-gateway-linux ./cmd/aneb-gateway
sudo ANEB_GATEWAY_BINARY="$PWD/aneb-gateway-linux" ./scripts/namespace_integration_test.sh
```

命名空间测试建立 client→gateway→server 三节点拓扑，验证基线 RTT、双向 netem RTT 增量、100% 网络层中断、自动恢复、审计事件和 qdisc 回收，全程不触碰宿主机默认接口。
