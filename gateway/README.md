# ANEB Dedicated Gateway 0.2.0

`aneb-gateway` 只部署在**专用双网口 Linux 转发设备**上，对 P40 Pro 实际穿越该设备的 IP 流量施加可重复的双向网络损伤。它与 E-01 的逐 run 应用层合成弱网完全分离，禁止安装到 E-01、开发 PC、家庭主路由器或任何共享接口。

## 证据边界

- 可以证明：流量确实穿越专用网关时的双向容量、附加时延、抖动、随机 IP 丢包和短时转发空窗，以及清理后 WAN 队列恢复到安装前记录的安全基线。
- 不能证明：真实 RSRP/RSRQ/SINR 改变、基站调度、蜂窝重选、真实 Wi-Fi/蜂窝切换。
- `ip_handover_gap` 只代表“类切换转发空窗”，claim scope 固定为 `not_radio_handover`。

## 数据路径

```text
P40 Pro ── 专用 LAN/AP ── aneb-gateway ── WAN ── E-01
                              │
                              ├─ WAN egress netem：上行
                              └─ WAN ingress → IFB netem：下行
```

管理 API 固定监听 LAN 的 `192.168.77.1`（端口可配置），客户端子网固定为 `192.168.77.0/24`，且不经过 WAN/IFB 损伤；因此 WAN 100% loss 时 App 仍能读取实验状态和清理回执。该地址合同与 App 内置网关 CA 和网络安全策略一致，不能在现场任意替换成其他私网地址。

## Debug-only 信任边界

- 网关模式只供 ANEB **Debug APK + ADB-only 实验流程**使用。Release APK 不内置该网关 CA，并且必须拒绝连接专用网关；这不是面向最终用户的生产控制面。
- Debug App 与网关发布物必须携带字节完全一致的 `aneb_gateway_ca.pem`。其 DER SHA-256 固定为 `2089A92C77B04FA392E24D1D71819EF1AC3D86B5131B0C6064BD6B092F5AD361`，安装器和预检都会失败关闭地核验此值。
- 固定 CA 的私钥是**仓库和网关设备之外的离线前提**：不得提交 Git、不得复制到 App、不得留在网关。现场只携带由该离线 CA 签发的 `CA:FALSE` 叶证书及叶私钥，叶证书必须具有 `serverAuth` 用途和 `iPAddress:192.168.77.1` SAN。
- 安装器拒绝现场自签叶证书或其他 CA 签发的证书。通过校验后，固定公开 CA 安装为 `/etc/aneb-gateway/tls/app-ca.pem`；启动后的 `/healthz` 也只用该 CA 建立信任，不把叶证书当作信任锚。
- 非 dry-run 服务每次启动都必须显式接收 `-tls-ca`，并在监听或任何网络变更前用 Go `crypto/x509` 重新核验固定 CA 指纹、root-owned 不可写权限、叶证书链、`CA:FALSE`、`ServerAuth`、固定 IP、有效期及私钥匹配。`-cleanup-only` 不读取 CA、TLS、attestation 或 Profile，避免损坏的控制面材料阻断紧急队列清理。

## 0.2.0 失败关闭合同

1. 每次启动都核验实际双网口状态、管理 IP 归属、私有 `/24`～`/30` 独占子网、IPv4 forwarding、默认路由经 WAN、客户端路由经管理口；wildcard、loopback、监听到 WAN 或接口漂移都拒绝启动。
2. 证书、私钥权限、64 位十六进制随机 Token、Profile、拓扑和监听端口全部验证并预占成功后，才允许检查或恢复 ANEB 队列；前置失败不修改 qdisc/IFB。
3. 只执行 `profiles/` 中的版本化白名单，不接收任意 `tc` 参数；同时最多一个实验，每个 Profile 最长 120 秒。
4. 变更前把 WAN 安全基线写入持久所有权文件；ANEB root handle 固定为 `1a1e:`，IFB 设置所有权 alias。清理只处理与状态文件、handle、filter 和 alias 同时匹配的资源；未知 qdisc、外来 ingress/filter/IFB 一律拒绝触碰。
5. 清理后必须精确比对 WAN 基线。失败时实验进入 `cleanup_failed` 锁闩、`cleared_at=null`、`cleanup_verified=false`，`/healthz` 返回 503，且拒绝下一实验；再次 DELETE 只用于重试并验证清理。
6. systemd 正常停止和失败停止都调用同一个 `-cleanup-only` 所有权验证器；该清理路径只依赖持久所有权状态、WAN/IFB 标识和实时资源匹配，不依赖可能损坏或缺失的 attestation/TLS/Profile，不再使用通用 `tc qdisc del` 脚本。
7. 管理面强制 TLS 1.2+、管理 IP SAN、32 随机字节的 64 hex Token，并把所有来源限制到证明文件声明的独占客户端子网。明文仅允许显式 `dry-run + loopback`。
8. 状态转换先写并 `fsync` 本地 JSONL 操作日志再改变网络；单文件达到 64MiB 时拒绝新实验，systemd 部署附带 8MiB/12 代轮转。该文件是本地操作日志，不宣称防篡改取证。

## API

- `GET /healthz`：ready 时 200；清理锁闩时 503/`degraded_cleanup_failed`；明确返回 `radio_impairment=false`。
- `GET /v1/profiles`：Bearer 鉴权后的白名单 Profile。
- `POST /v1/experiments`：`{"run_id":"...","profile_ref":"...@..."}`，返回 202。
- `GET /v1/experiments/{id}`：`scheduled → active → clearing → completed`；执行或清理失败为 `failed/cleanup_failed`。
- `DELETE /v1/experiments/{id}`：取消活动实验；若已锁闩则重试所有权清理。
- `GET /v1/status`：当前活动或锁闩实验；安全释放后才为 `null`。

每个实验响应都包含 `profile_fingerprint`、`claim_scope`、`impairment_layer`、时间戳和 `cleanup_verified`。App 必须逐项核验，不能只信 HTTP 状态。

## 设备前提

- 两个独立且已启用的接口：LAN/管理口和 WAN；生产合同强制 LAN `192.168.77.0/24`、网关 `192.168.77.1`，仅管理 API 的 TCP 端口可配置。
- 设备已由管理员配置 IP forwarding、WAN 上网、NAT/防火墙、DHCP/Wi-Fi AP；安装器**只读核验**这些条件，绝不静默改路由、NAT、DHCP 或防火墙。
- WAN 当前 root qdisc 必须是单一 `noqueue`、`fq` 或 `fq_codel`；多队列/自定义 QoS 设备先单独设计，不允许安装器覆盖。
- Linux 需提供 systemd、iproute2、IFB、curl、OpenSSL、Python 3 和 logrotate（轮转缺失不影响启动，但不应长期运行）。

## 一键安装

先在离线 CA 环境签发 `CA:FALSE`、`serverAuth`、且 `iPAddress` SAN 包含 `192.168.77.1` 的现场叶证书。只把叶证书和叶私钥安全带到专用网关；离线 CA 私钥、叶私钥和 Token 都不入 Git：

```bash
cd gateway
CGO_ENABLED=0 go build -o aneb-gateway-linux ./cmd/aneb-gateway

sudo ./scripts/install_gateway.sh \
  --binary "$PWD/aneb-gateway-linux" \
  --wan eth0 --management eth1 \
  --listen 192.168.77.1:9444 \
  --client-subnet 192.168.77.0/24 \
  --tls-cert /secure/cert.pem \
  --tls-key /secure/key.pem \
  --accept-dedicated-appliance
```

安装器先核验仓库固定 CA 的 DER 指纹，再用 `openssl verify -CAfile ... -purpose sslserver -verify_ip 192.168.77.1` 核验现场叶证书，并在 staging 后重复核验。未提供 `--token-file` 时安装器用 CSPRNG 生成 32 字节 Token，保存为 `/etc/aneb-gateway/token`（0600，只输出路径、不打印值）。安装流程使用 root-only 生命周期锁、严格解析而不执行 `gateway.env`、原子 staging 和基于 `/etc/aneb-gateway/tls/app-ca.pem` 的启动后 TLS health 验证。旧服务停止前已经注册 EXIT 事务；只有服务确认停止且所有权清理验证成功，才会恢复旧程序、配置、unit、原启用状态和原运行状态。停止或清理无法验证时会失败关闭，保留新清理工具、staging 与备份路径供恢复，不删除恢复资产、不重启旧版。

已安装设备的只读复检：

```bash
sudo systemctl stop aneb-gateway
sudo ./scripts/preflight_gateway.sh
sudo systemctl start aneb-gateway
```

安全卸载默认保留 `/var/lib/aneb-gateway` 操作日志；只有明确参数才清除：

```bash
sudo ./scripts/uninstall_gateway.sh
# 确认不再需要日志与状态后：
sudo ./scripts/uninstall_gateway.sh --purge-data=YES
```

卸载会先严格验证 root-owned 二进制与 `gateway.env`，再停止服务并完成 `-cleanup-only` 验证；二进制/配置缺失、部分安装不可信或清理失败时保留全部文件并退出，不能报告 PASS。通过清理后才事务删除运行文件；默认保留操作日志，`--purge-data=YES` 还会拒绝符号链接或挂载点，避免误删外部数据。仅在运行态已完全不存在时允许幂等 PASS。

## 隔离验收

```bash
cd gateway
go test -count=1 ./...
sudo ./scripts/install_security_test.sh
CGO_ENABLED=0 go build -o aneb-gateway-linux ./cmd/aneb-gateway
sudo \
  ANEB_GATEWAY_BINARY="$PWD/aneb-gateway-linux" \
  ANEB_GATEWAY_TEST_CA="$PWD/trust/aneb_gateway_ca.pem" \
  ANEB_GATEWAY_TEST_CERT=/secure/test-field-leaf.pem \
  ANEB_GATEWAY_TEST_KEY=/secure/test-field-leaf-key.pem \
  ./scripts/namespace_integration_test.sh
```

`install_security_test.sh` 只在独立 `/tmp` 目录调用安装器的纯校验函数，负向覆盖 Token/TLS key 权限、危险父目录、状态目录 symlink/mountpoint/错误元数据和旧 Token 复用；它不安装服务、不修改 qdisc、路由或防火墙。

`ANEB_GATEWAY_TEST_CERT/KEY` 必须由固定离线 CA 预先签发；仓库不提供 CA 私钥，缺少外部叶证书时脚本明确返回 `BLOCKED_EXTERNAL`，不会增加普通运行时绕过开关。命名空间测试建立 client→gateway→server 三节点 TLS 拓扑，先用同 IP、`CA:FALSE`、`ServerAuth` 的任意自签叶证书证明前置失败且 qdisc/IFB 零变更，再验证真实双向 netem、100% 网络层中断与恢复、持久日志、所有权状态删除、WAN 基线恢复、宿主默认 qdisc 不变和临时命名空间全回收。
