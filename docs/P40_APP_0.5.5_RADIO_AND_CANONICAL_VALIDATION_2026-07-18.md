# P40 Pro：App 0.5.5 无线证据与跨语言摘要验收

> 验收日期：2026-07-18（Asia/Shanghai）  
> 设备：Huawei P40 Pro  
> App：`com.aneb.probe.codex`，`0.5.5-codex`，versionCode 37，Room v19  
> APK SHA-256：`DDE1489FB219C4B1A5F7440A6E365C57003E09F3F023B38BBC0B16AF3E7CE770`

## 先讲限制

- ［KNOWN｜HIGH］三个最终 run 的活动承载都是 Wi-Fi。Android 返回的蜂窝 RSRP/RSRQ/SINR 只是同期环境协变量，不能归因于 Wi-Fi 数据路径，也不能据此评价蜂窝业务质量。
- ［KNOWN｜HIGH］三个 run 都是 Quick Profile，覆盖率不足以支撑稳定性或 95% 达标率结论；即使分数较高，verdict 仍为 `INCONCLUSIVE`、confidence 仍为 `LOW`。
- ［KNOWN｜HIGH］本次验证的是 ANEB 自建节点仿真，不是真实 Kimi、DeepSeek、千问或其他第三方 AI API，不构成厂商体验结论。
- ［KNOWN｜HIGH］真实射频弱网仍需屏蔽箱、衰减器或基站模拟器；本次未人为改变 RSRP/SINR。

## 0.5.4 被否决的摘要证据

0.5.4 首次把 1Hz 无线证据接入三类正式引擎。Room 事务、Schema 和无线样本本身均通过，但独立 Python 复算发现 Token 与 AI 实时信封的 `canonical-json-sha256-v1` 不匹配；网络综合恰好匹配，不能据此判定实现正确。

根因是 Kotlin/Java 对很小浮点数使用 `E-5`，而冻结的 Python 合同使用 `e-05`。两者数值相同，但规范化 JSON 字节不同。0.5.4 的 Token/AI 实时摘要证据因此明确否决，不进入最终验收。

0.5.5 将数字规范化固定为与 Python `json.dumps(ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)` 一致的词法边界，并加入指数阈值、负零、整数浮点和本次残差向量的冻结测试。

## 最终三类真机结果

| 类型 | run id | 分数/等级 | verdict/confidence | 无线样本 | 无线范围 | Python 独立摘要 |
|---|---|---:|---|---:|---|---|
| Token Quick | `019f71a6-bbf0-7c71-b8b8-b8338297c6e0` | 98.5 / A | INCONCLUSIVE / LOW | 119 | RSRP -105 dBm；RSRQ -11 dB；SINR 8–10 dB | `sha256:b0bcd90bd476c6bc6fb4f736a0a52e64e431623c95866eb3a3fa8c3b4c622e13` |
| AI 实时 Quick | `019f71a9-191f-7fe3-9995-d4765ed6652f` | 100 / A | INCONCLUSIVE / LOW | 26 | RSRP -105～-104 dBm；RSRQ -11 dB；SINR 8–10 dB | `sha256:1437865f22710be3073f9edb60f8d0b009f11641a00e78bcf794ee91fe53646c` |
| 网络综合 Quick | `019f71aa-f127-7db3-a4d0-651e57e6a955` | 72.0 / B | INCONCLUSIVE / LOW | 18 | RSRP -105～-104 dBm；RSRQ -11 dB；SINR 8–10 dB | `sha256:de5ce6fa75864eb756c611f3d1c976ddb5e4f379d7080c8822baeb3e7e062c6b` |

三条 run 的 stale 样本均为 0。Token、AI 实时、网络综合的 R01 无线指标分别以 119、26、18 个样本写为 `state=observed`、`value=null`，并引用 `radio-context` 时间序列证据；没有把时间序列伪造成单个标量。

## 一致性与隐私验收

- ［COMPUTED｜HIGH］每条 run 都恰好有 1 条类型化结果和 1 条 `aneb-result-v1` 信封；结果、信封、无线样本与环境事件在同一 Room 事务提交。
- ［COMPUTED｜HIGH］三条信封经 Draft 2020-12 Schema 验证均为 0 错误，独立 Python 规范化摘要全部与 Room 冻结摘要一致。
- ［COMPUTED｜HIGH］`/context/radio` 均为 `collected`，没有被列入 completeness missing；Room 无线样本数与信封样本数逐条相等。
- ［COMPUTED｜HIGH］可分享信封不含 `lat`、`lon`、`latitude`、`longitude` 或 `location` 字段；位置被明确标为 `location_removed`。本地原始权限/不可用事件仍可审计，但不会混入共享无线序列。
- ［KNOWN｜HIGH］权限拒绝、设备不可用和未采集分别使用 `permission_denied`、`unavailable`、`not_collected`，不得补 0 或伪造良好值。

## 动态界面观察

- Token 测试以约 250ms 刷新 Token/s、RTT、上行速率和准时率。
- AI 实时测试动态刷新准时帧率、播放余量、RTT 与双向 kbps。
- 网络综合测试动态刷新 Mbps 与 loaded RTT；本轮曾显示约 29.3Mbps，同时 loaded RTT 达 918.2ms、相对空闲增加约 871.8ms，证明关键业务动态指标不能只看带宽。
- 缺失值显示“—”，不会让指针或文本把未测值显示为 0。

## 资源释放

最终数据库审计完成后已返回华为桌面并强制停止 `com.aneb.probe.codex`。复核时 `com.aneb.probe` 与 `com.aneb.probe.codex` 均无 PID、无运行服务，前台为 `com.huawei.android.launcher/.unihome.UniHomeLauncher`，P40 Pro 已释放给 Claude。

