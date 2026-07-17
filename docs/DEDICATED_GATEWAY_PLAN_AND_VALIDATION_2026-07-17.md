# ANEB 专用网络层弱网网关：实现与验证

## 结论

- ［KNOWN｜HIGH］`aneb-gateway/0.2.0` 已形成发布候选级 Linux 双向弱网设备软件；它控制 P40 Pro 经网关转发的真实 IP 流量，不修改 E-01，也不使用 E-01 全局 `netem`。
- ［KNOWN｜HIGH］App 0.5.0 Debug 已接入 `gateway_loss` 与 `gateway_recovery` 两个 ADB-only 模式，Room v18 冻结实验编号、Profile 指纹、网络层声明、激活/清理回执和旁路检测结果。
- ［KNOWN｜HIGH］2026-07-17 19:10 CST 的加固前数据面命名空间验收通过：基线 RTT `0.067ms`，受控双向时延后 `97.753ms`，清理后 `0.051ms`；100% 双向网络层中断与自动恢复通过，产生 10 条同步操作事件，宿主默认 qdisc 保持不变。该记录只证明当时的数据面集成，不等同于当前固定 CA 代码的最终正向生命周期。
- ［KNOWN｜HIGH］发布复审已关闭 9 项 P1，包括精确 IFB/filter/ingress 归属、清理失败后可重试、第三出口/隧道旁路、真实转发与回程、固定 CA 逐启动核验、状态目录与 Token/TLS key 安全，以及 App 启停不确定状态对账与“明确 HTTP 拒绝绝不重试”。Go 定向测试重复 20 轮通过；E-01 Linux root 隔离安装安全回归通过。
- ［KNOWN｜HIGH］2026-07-17 21:09 CST 复核 E-01 仍为 `aneb-server/0.7.0` 且 `active`，网关服务为 `inactive`，默认接口仍是原始 `fq_codel`，无残留命名空间。
- ［KNOWN｜HIGH］仓库不持有固定 CA 私钥，当前没有由离线 CA 签发的现场叶证书，也没有可供 P40 Pro 独占的双网口 Linux/AP 网关；因此最终 TLS 正向生命周期和 P40 网络层真机均为 `BLOCKED_EXTERNAL`，不能用早期命名空间结果替代。

## 1. 架构与证据边界

```text
P40 Pro ── 专用 Wi-Fi/LAN ── Linux 网关 ── WAN ── E-01
                               │
                               ├─ WAN egress netem：上行
                               └─ WAN ingress → IFB netem：下行
```

网关管理面固定使用 LAN 地址 `https://192.168.77.1:9444`，不经过 WAN/IFB 损伤，因此 100% 丢包时 App 仍能读取实验状态并验证自动清理。可证明的范围是 `dedicated_gateway_ip_forwarding`；不可证明或不可伪造的项目包括 RSRP、RSRQ、SINR、基站调度和真实切网。

## 2. 白名单 Profile

| Profile | 网络层条件 | App Profile | 指纹 |
|---|---|---|---|
| `ip_loss_latency@1.0.0` | 60s；下行 5Mbps、上行 2Mbps；双向单程 `50±10ms`；双向 1% 随机丢包 | `network_comprehensive_gateway_loss@1.0.0` | `91bd6b10…d7984` |
| `ip_outage_recovery@1.0.0` | 500ms 后激活；双向 100% 丢包 2000ms；自动清理 | `network_comprehensive_gateway_recovery@1.1.0` | `208f2acd…73d8` |
| `ip_handover_gap@1.0.0` | 800ms 双向转发空窗 | 尚未接入正式测试 | `6e816ce6…6f6f` |

`ip_handover_gap` 只能称为“类切换转发空窗”，不能称为真实蜂窝/Wi-Fi 切换。

## 3. 安全与失败关闭

1. 非 dry-run 仅支持 Linux，并强制把 `aneb-dedicated-gateway-v1` 证明绑定到实时双网口、管理 IP、私网客户端子网、标准策略规则、真实转发/回程、全部 main-table 单播路由和 IPv4 forwarding；除精确客户端路由外，第三出口、VPN/VRF、目标专用旁路均拒绝启动。
2. API 只能选择仓库内版本化 Profile，不能传入任意 `tc` 命令或参数。
3. Debug App 与网关必须携带字节一致的固定 CA；非 dry-run 每次 Go 启动都核验固定指纹、CA/叶证书权限、证书链、`CA:FALSE`、`ServerAuth`、`iPAddress:192.168.77.1`、有效期和私钥匹配，全部通过后才监听或进行网络操作。
4. Token 与 TLS key 来源必须是安全父目录中的 root-owned 0600 普通文件并满足长度限制；旧 Token 不静默复用。状态目录拒绝 symlink、mountpoint、错误 owner/group/mode，安装器不得偷偷修正现有目录元数据。
5. 变更前持久化 WAN qdisc 安全基线和所有权；只接受类型为 IFB 且 alias 精确匹配的设备，以及精确 ingress/filter 所有权。空 ingress、clsact、未知 filter/IFB 一律失败关闭。
6. 清理在严格验证所有权后删除 ingress qdisc（连同 filter）并复验，再删除 IFB；任一步失败都保留可重试证据。只有精确恢复基线才返回 `cleanup_verified=true`；否则进入 `cleanup_failed` 锁闩、health 503、拒绝新实验，且不能写伪造的 `cleared_at`。
7. 管理面强制 TLS 1.2+、管理 IP SAN、32 随机字节的 64 hex Token，并限制来源到独占客户端子网；明文只允许显式 dry-run loopback。
8. App 必须同时核验 `run_id`、Profile ref、SHA-256 指纹、`ip_forwarding`、claim scope、激活状态和清理状态；任一不匹配即 `INVALID` 且不评分。
9. 恢复实验中，若网关仍报告 `active` 而 E-01 echo 成功，记录 `gateway_bypass_observed=true` 并判证据无效。
10. 令牌不写 Room、不写 APP 日志；Activity 读取后立即移除 Intent extra，Activity→Service 使用带随机句柄、60 秒过期、一次消费即删除的进程内凭据库，并发启动不会互换 Token。
11. APP 启动阶段只有在连接中断或 202 响应无法解析、即“提交结果未知”时，才先按同 run/profile 对账、最多一次幂等 POST、再做最终对账；明确 409/423/其他 4xx 立即原样失败，不查询状态、不重发 POST。取消只轮询状态且绝不新建实验。DELETE 回包不确定时按状态对账并做有界幂等重试；失败/取消先做有界清理，再冻结证据。

## 4. App 动态与结论

- 网络层容量/丢包测试继续以 loaded RTT 为动态主指标，100ms 刷新速率指针，展示网关目标速率、时延、丢包及“RSRP/SINR 不变”。
- 网络层恢复测试以“APP 收到网关 active 确认到首个 E-01 echo 完成”为动态主指标，echo 完成时立即截取单调时钟，不再把 echo 后的控制面 GET 混入；100ms 更新恢复时间、失败探针和网络层中断状态。
- 网关仍 active 却 echo 成功，或整个 2 秒窗口没有观察到失败探针，都判 `INVALID` 并抑制评分；后者不是“网络质量差”的分数。
- 恢复门限保持：恢复 ≤3000ms、恢复后请求成功率 ≥95%、恢复后 RTT ≤300ms 达标比例 ≥95%。单次事件仍固定 `LOW/INCONCLUSIVE`，不能形成长期可靠性承诺。
- 网关实验和 E-01 用户态合成实验使用不同 Profile、评分策略、结论策略和证据字段，不混分。

## 5. 部署与启动

网关必须是独占双网口 Linux 设备或专用 VM/AP；不得部署到 E-01，不得挂到开发 PC、家庭路由器或 Claude 正在使用的共享网络接口。部署前必须在离线环境用固定 CA 为 `192.168.77.1` 签发 `CA:FALSE + ServerAuth + IP SAN` 的现场叶证书，CA 私钥不入 Git、不进 App、不留网关。`gateway/scripts/install_gateway.sh` 提供带全局锁、只读预检、原子 staging、TLS health 验证和失败回滚的一键安装；`preflight_gateway.sh` 复检，`uninstall_gateway.sh` 先验证清理再卸载，默认保留日志。完整合同见 `gateway/README.md`。

Debug App 调用示例（令牌用部署现场值替换，命令不得进入公开日志）：

```text
adb shell am start -n com.aneb.probe.codex/com.aneb.probe.ui.MainActivity \
  --es server https://120.79.148.0:8443 --ez autorun true \
  --es test_mode network_basic --es mode gateway_recovery --es transport wifi \
  --es gateway_base https://192.168.77.1:9444 --es gateway_token <64位hex令牌>
```

正式 release 不暴露网关模式；没有专用硬件身份与现场操作规程前，不把实验室能力放进普通导航。

## 6. P40 真机验收门

现场叶证书与硬件具备后自动按以下顺序执行：确认 Claude 未在测试 → 安装器/预检/任意自签证书拒绝/固定 CA TLS 正向生命周期 → 手机仅连接专用 AP → 普通 Standard 基线 → `gateway_loss` → `gateway_recovery` 至少 4 次 → 导出 Room/界面/网关审计 → 验证 qdisc/IFB 清理 → HOME → 强停 Codex 包 → 确认 Codex/Claude 两包均无 PID 和前台服务。

只有上述闭环完成后，才能把 P40 网络层测试从 `BLOCKED_EXTERNAL` 改为 `PASS`。
