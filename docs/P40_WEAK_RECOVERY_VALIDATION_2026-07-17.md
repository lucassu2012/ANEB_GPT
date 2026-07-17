# P40 Pro 合成弱网恢复验收

## 结论

- ［KNOWN｜HIGH］App 0.4.8 在 P40 Pro 上完成 4 次独立 `network_comprehensive_weak_recovery@1.0.0` run，均观察到 E-01 明确回执的 2 秒应用请求中断并自动恢复。
- ［COMPUTED｜HIGH］4 次恢复用时范围为 2084.4–2227.3ms；恢复后请求成功率均为 100%，均满足 provisional v1 的 ≤3000ms 与 ≥95% 目标。
- ［KNOWN｜HIGH］单次事件仍按 `network-recovery-score-v1` 固定为 `LOW/INCONCLUSIVE`；分数和 A 级不能被解释为长期 95% 恢复可靠性。
- ［KNOWN｜HIGH］本证据不包含真实 RSRP/SINR 改变、IP 丢包、蜂窝断网或切网，只覆盖 E-01 逐 run 的应用请求不可用窗口与 ANEB 恢复算法。

## 被测版本与隔离合同

| 项目 | 值 |
|---|---|
| App | `0.4.8`（versionCode 30），调试包 `com.aneb.probe.codex` |
| APK SHA-256 | `63A5300744DE3EB84E1E0CA6A310C9E04274234B60A5EA74F11EBAED3FFDB317` |
| Room | v17 |
| E-01 | `aneb-server/0.7.0` |
| Profile | `network_comprehensive_weak_recovery@1.0.0` |
| 路由 | `weak-recovery-v1` |
| 声明刺激 | ↓5Mbps、↑2Mbps、附加 RTT `80±20ms`、一次 2000ms 应用请求中断 |
| 明确排除 | DNS、TCP、TLS、UDP、IP 丢包、切网、RSRP、SINR 整形 |

服务端部署 smoke 已验证：首次 trigger 202；同 run 在窗口内返回带 `X-Aneb-Synthetic-Outage: active` 的 503；其他 run 和正常 `/api/v1/*` 路由保持 200；窗口后原 run 自动恢复 200。重复 trigger 不延长或重置窗口。

## 真机结果

| run | 恢复用时 | 中断失败 | 恢复后成功率 | 综合输出 |
|---|---:|---:|---:|---|
| `019f6ee9-8c0f-7b82-99b3-040730f3e84b` | 2155.9ms | 8 | 100% | 100/A，LOW/INCONCLUSIVE |
| `019f6eed-0a93-7943-a235-94d4363c32e6` | 2084.4ms | 8 | 100% | 100/A，LOW/INCONCLUSIVE |
| `019f6eee-0d8a-748f-8e70-c5fef9d0a0b1` | 2119.5ms | 8 | 100% | 100/A，LOW/INCONCLUSIVE |
| `019f6ef0-1699-7f9c-8b6e-d299b0bf6328` | 2227.3ms | 9 | 100% | 99.8/A，LOW/INCONCLUSIVE |

［KNOWN｜HIGH］末次 run 的恢复后 RTT P95 为 152.6ms，12/12 个恢复后请求成功。99.8 分来自逐样本评分，不应四舍五入成 100。

## 动态与原始证据

- `evidence/ui/aneb_recovery_seq_0.4.8/8.png`：恢复阶段动态画面，捕获 1063.2ms 实时计时、5 次服务器确认的中断失败、粉色指针和“中断”状态。
- `evidence/ui/aneb_recovery_seq_0.4.8/12.png`：末次结果页，显示 2227.3ms、9 次中断失败、恢复后成功率 100%、RTT P95 152.6ms 与 `LOW/INCONCLUSIVE`。
- `evidence/ui/aneb_recovery_confirm_0.4.8.png`：开始前合成弱网边界确认。
- `evidence/device/aneb_recovery_0.4.8.db*`：4 次 run 的 Room v17 原始数据库、WAL 与 SHM。

设备没有可用的系统 `screenrecord` 命令，因此本轮保留连续截图序列，不声称已产出视频证据。

## 退出与共享设备状态

［KNOWN｜HIGH］验收结束后已返回华为桌面并强制停止 `com.aneb.probe.codex`。最终检查为官方包和 Codex 包均无 PID、前台服务计数均为 0，P40 Pro 已释放给 Claude 使用。
