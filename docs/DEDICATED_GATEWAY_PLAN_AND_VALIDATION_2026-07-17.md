# ANEB 专用网络层弱网网关：实现与验证

## 结论

- ［KNOWN｜HIGH］`gateway/` 已实现可部署的 Linux 双向弱网网关；它控制 P40 Pro 经网关转发的真实 IP 流量，不修改 E-01，也不使用 E-01 全局 `netem`。
- ［KNOWN｜HIGH］App 0.4.9 Debug 已接入 `gateway_loss` 与 `gateway_recovery` 两个 ADB-only 模式，Room v18 冻结实验编号、Profile 指纹、网络层声明、激活/清理回执和旁路检测结果。
- ［KNOWN｜HIGH］Linux 网络命名空间端到端验证通过：基线 RTT `0.061ms`，受控双向时延后 `105.260ms`，清理后 `0.054ms`；100% 双向网络层中断与自动恢复通过，产生 8 条审计事件，宿主默认 qdisc 保持不变。
- ［KNOWN｜HIGH］E-01 在验证后仍为 `aneb-server/0.7.0`，默认接口仍为 `fq_codel`，无残留命名空间或临时文件。
- ［KNOWN｜HIGH］当前还没有可供 P40 Pro 独占的双网口 Linux/AP 网关，因此没有把命名空间结果冒充为 P40 真机结果；P40 网络层实测状态为 `BLOCKED_EXTERNAL`。

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
| `ip_outage_recovery@1.0.0` | 500ms 后激活；双向 100% 丢包 2000ms；自动清理 | `network_comprehensive_gateway_recovery@1.0.0` | `208f2acd…73d8` |
| `ip_handover_gap@1.0.0` | 800ms 双向转发空窗 | 尚未接入正式测试 | `6e816ce6…6f6f` |

`ip_handover_gap` 只能称为“类切换转发空窗”，不能称为真实蜂窝/Wi-Fi 切换。

## 3. 安全与失败关闭

1. 非 dry-run 仅支持 Linux，并强制读取 `aneb-dedicated-gateway-v1` 专用设备证明文件。
2. API 只能选择仓库内版本化 Profile，不能传入任意 `tc` 命令或参数。
3. 同时只允许一个实验；每个实验有自动结束时间，启动、结束、取消、进程退出和 systemd `ExecStopPost` 都执行清理。
4. 清理后重新查询 WAN qdisc 与 IFB；仍有 `netem/ingress` 或 IFB 残留时返回失败。
5. 管理面默认强制 TLS 和至少 32 字符 Bearer token；明文只允许 loopback 命名空间测试。
6. App 必须同时核验 `run_id`、Profile ref、SHA-256 指纹、`ip_forwarding`、claim scope、激活状态和清理状态；任一不匹配即 `INVALID` 且不评分。
7. 恢复实验中，若网关仍报告 `active` 而 E-01 echo 成功，记录 `gateway_bypass_observed=true` 并判证据无效。
8. 令牌不写 Room、不写 APP 日志；Activity 读取后立即移除 Intent extra，Activity→Service 只通过进程内一次性内存槽交接。

## 4. App 动态与结论

- 网络层容量/丢包测试继续以 loaded RTT 为动态主指标，100ms 刷新速率指针，展示网关目标速率、时延、丢包及“RSRP/SINR 不变”。
- 网络层恢复测试以“网关激活确认到首个成功 E-01 echo”为动态主指标，100ms 更新恢复时间、失败探针和网络层中断状态。
- 恢复门限保持：恢复 ≤3000ms、恢复后请求成功率 ≥95%、恢复后 RTT ≤300ms 达标比例 ≥95%。单次事件仍固定 `LOW/INCONCLUSIVE`，不能形成长期可靠性承诺。
- 网关实验和 E-01 用户态合成实验使用不同 Profile、评分策略、结论策略和证据字段，不混分。

## 5. 部署与启动

网关必须是独占双网口 Linux 设备或专用 VM/AP；不得部署到 E-01，不得挂到开发 PC、家庭路由器或 Claude 正在使用的共享网络接口。详细安装文件：`gateway/README.md`、`gateway/systemd/aneb-gateway.service`、`gateway/scripts/aneb-gateway-clear`。

Debug App 调用示例（令牌用部署现场值替换，命令不得进入公开日志）：

```text
adb shell am start -n com.aneb.probe.codex/com.aneb.probe.ui.MainActivity \
  --es server https://120.79.148.0:8443 --ez autorun true \
  --es test_mode network_basic --es mode gateway_recovery --es transport wifi \
  --es gateway_base https://192.168.77.1:9444 --es gateway_token <32+字符令牌>
```

正式 release 不暴露网关模式；没有专用硬件身份与现场操作规程前，不把实验室能力放进普通导航。

## 6. P40 真机验收门

硬件具备后自动按以下顺序执行：确认 Claude 未在测试 → 手机仅连接专用 AP → 普通 Standard 基线 → `gateway_loss` → `gateway_recovery` 至少 4 次 → 导出 Room/界面/网关审计 → 验证 qdisc/IFB 清理 → HOME → 强停 Codex 包 → 确认 Codex/Claude 两包均无 PID 和前台服务。

只有上述闭环完成后，才能把 P40 网络层测试从 `BLOCKED_EXTERNAL` 改为 `PASS`。
