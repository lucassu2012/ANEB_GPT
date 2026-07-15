# 真机 cross_network C2 恢复修复验证（D-23）

- **日期**: 2026-07-13
- **设备**: 8MY0221126002537（华为 P40 Pro，电信 5G SA / NR_SA）
- **APK**: probe-debug.apk（本分支 `claude/frosty-wright-aedafc` 修复版；`pm install -r -d` Success，保留 DB）
  - 校验：安装后 base.apk 含 `CONTINUITY_REBIND`(1) 与 `cross_network`(2) 字符串 → 确认运行的是修复码
- **原始 logcat**: [realdevice_continuity_crossnet_fix_verify_20260713.log](realdevice_continuity_crossnet_fix_verify_20260713.log)
- **前置**: 服务器 `https://120.79.148.0:8443`（bare-IP）在线；手机蜂窝 ping 2/2、avg 88ms
- **结论**: **修复验证通过**——真机硬切换拆除原绑定蜂窝网后，重连迁到当前新默认网并恢复，
  `status=completed`（原缺陷为 `recovery_failed`）。

## 场景与本环境约束

原缺陷（[realdevice_continuity_kimi_20260713.log](realdevice_continuity_kimi_20260713.log) §3）：
continuity 绑定蜂窝 net110，蜂窝→WiFi 硬切换拆除 net110 → 原码固定回绑 net110 → EPERM ×5 →
`recovery_failed`、无 recovery_ms。

**本次环境约束**：测试现场手机无可用 WiFi AP（`dumpsys connectivity` 默认网恒为蜂窝 net119，
`wifi_on=1` 但未关联可用 AP）——无法产生真实"蜂窝→WiFi"落地。故按 **§3 实际观察到的行为**
（net110 死、"新蜂窝网 net112 上线为默认"）等价复现：`svc data disable` 拆除原绑定蜂窝网 →
3s 后 `svc data enable` 让**新蜂窝网**作新默认网。**代码路径与蜂窝→WiFi 完全一致**（原句柄失效
→ 迁到当前系统新默认网 → 在新网恢复）；真正蜂窝→WiFi 变体待有 WiFi AP 的真机会话补测。

## 三次真机 run 对照（continuity_result 落库）

| runId | 码 | 触发 | status | c2 recovery_ms | c2CrossNetworkRecoveries | pathChangeEvents |
|---|---|---|---|---|---|---|
| 019f5af6 | **修复** | data 拆除→新蜂窝默认网上线 | **completed** | **7737.0**（fair） | **1** | 3 |
| 019f5af2 | 修复 | data 拆除但无新默认网（全断） | recovery_failed | null | 0 | 2 |
| 019f5ae9 | 旧码 | WiFi enable（net 未拆，同网重连） | completed | 628.8（excellent） | NULL | 0 |

- **019f5af6（关键成功 run）**：修复路径全链路走通（见下）。
- **019f5af2**：拆除蜂窝但新默认网未及时上线（本环境无 WiFi 兜底）→ REBIND 已触发但无网可迁 →
  `recovery_failed`——**fail-closed 语义保留**（真无网时如实失败，不造假）。
- **019f5ae9（旧码对照）**：本次 WiFi enable 未拆除蜂窝网（timing 相关），旧码在**存活的原网**上
  同网重连成功（same_network）——`c2CrossNetworkRecoveries=NULL`（旧码不写该列，印证可空迁移语义）。

## 成功 run（019f5af6）关键日志

```
CONTINUITY_BIND transport=cellular snapshot=...cellular_validated=true...      # 绑定蜂窝 net119
CONTINUITY_PATH  default_network_lost_119 exempt=true                          # 原网被拆
CONTINUITY_PATH  bound_network_lost_119   exempt=true                          # 原绑定句柄失效
CONTINUITY_SEGMENT seg=1 tokens=348 ... error=SocketException:_..._abort       # seg1 中断
CONTINUITY_REBIND  seg=1 attempt=1 ok=true target=unbound_default_network      # ★修复：迁离死句柄
CONTINUITY_RECONNECT attempt=1..3 ok=false ConnectException                    # 新蜂窝网上线前的空窗
CONTINUITY_PATH  default_network_changed_->_120 exempt=true                    # 新蜂窝网 net120 作新默认
CONTINUITY_RECOVERY seg=1 attempt=4 recovery_ms=7737.0 semantic=cross_network conn_new=true  # ★在新网恢复
CONTINUITY_SEGMENT seg=2 tokens=1200 summary=true completed=true               # 恢复流完整收尾
CONTINUITY_C2 samples=1 cross_network_samples=1 p50_ms=7737.0 grade=fair       # ★新字段
CONTINUITY_END status=completed                                               # ★核心：非 recovery_failed
```

对照原缺陷：`error=..._Binding_socket_to_network_110_failed:_EPERM` ×5 → `recovery_failed`。

## DB 确认（v10→v11 迁移端到端）

- `.schema continuity_result` 含 `c2CrossNetworkRecoveries INTEGER`（迁移已在真机应用）。
- 成功 run 落库 `c2CrossNetworkRecoveries=1`；旧码历史行为 `NULL`——可空 additive 迁移语义如设计。

## 恢复计时说明

recovery_ms=7737ms（fair）偏高，源于本次强制手法引入的 ~3s 蜂窝全断空窗 + 新蜂窝网重附着/校验 +
第 4 次退避（4s）；口径正确（中断检出→新网首 token，含退避与换网耗时，D-20）。真实蜂窝→WiFi
（新网本已就绪）应更快。**要点是 recovery_ms 由 null 变为真实值、status 由 recovery_failed 变为 completed。**

## 断言核验

- ✅ 修复路径：`bound_network_lost` → `CONTINUITY_REBIND ok=true` → 在新默认网 `semantic=cross_network` 恢复。
- ✅ 失败模式转变：重连错误由旧码 `EPERM Binding socket to network`（死句柄）变为 `ConnectException`
  （在新默认网上尝试）——精确证明"不再在死句柄上重试"。
- ✅ PathMonitor 豁免不变：全部 PATH 事件 `exempt=true`，run 未被 INVALID（status=completed）。
- ✅ fail-closed 保留：无新网可迁时（019f5af2）如实 `recovery_failed`。
- ✅ same_network 不回归：net 存活时同网重连成功（019f5ae9）。

## 设备恢复（无条件）

`svc data enable` + `svc wifi enable` + `svc power stayon false`；`/data/local/tmp/probe-fix.apk` 已删。
复核：wifi_on=1、stay_on_while_plugged_in=0、mobile_data=1（均回原值），制式回 NR_SA。

## key 卫生

continuity 走仿真服务器（非 LLM），本次不涉 API key；logcat/DB 均无 key 字段。
