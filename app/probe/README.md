# ANEB Probe Android 模块

发布包名为 `com.aneb.probe`，Codex 调试包名为 `com.aneb.probe.codex`。当前版本
`0.5.7`（versionCode 39），minSdk 29、targetSdk 35，Room schema v19。

## 正式产品能力

- 网络综合：握手、空闲 RTT、上下行容量、并发 loaded RTT、1 秒 goodput 窗口、
  UDP 应用探针与测后恢复 RTT；动态主指标是 loaded RTT。
- Token 仿真：按冻结运行计划执行文本、文档、图片和视频类上行及 SSE Token 下行；
  不调用第三方 AI API。
- AI 实时：固定 20ms 双向音频帧，测量限时送达率、首帧响应、卡顿、打断、RTT
  与会话连续性。
- 三类测试使用独立 Profile、独立评分和独立 Room 结果；缺失必需指标时分数为
  `null`，Quick 只给 `LOW/INCONCLUSIVE` 方向性结论。
- 单条结果可保存/分享经身份与摘要校验的原样 JSONL；设置页可按时间导出全部
  独立验真的历史记录，并明确报告被完整性校验拒绝的旧记录数量。

## 构建与验证

在仓库根目录执行：

```powershell
.\scripts\quality_gate.ps1
```

该门禁覆盖 Android JVM 单测、Lint、Debug APK、独立行为模型测试和 Go 服务端测试。
最终 APK 位于 `app/probe/build/outputs/apk/debug/probe-debug.apk`。

## 关键代码

| 目录 | 职责 |
|---|---|
| `engine/` | 三类执行引擎、Profile fail-closed 校验、评分和结论 |
| `net/` | HTTPS/WebSocket/HTTP3 客户端、路径绑定、UDP 探针 |
| `data/` | Room 实体、DAO 与版本迁移；当前 schema v19 |
| `ui/` | ANEB_UI 风格首页、动态仪表、历史和结果页 |
| `radio/` | 公开 Android API 可得的无线与小区协变量 |

产品边界、门限与评分版本见 `docs/PROFILE_CONTRACT_V2_PROPOSAL_2026-07-16.md` 和
`docs/DECISION_LOG.md`。发布签名与网络信任边界见 `docs/RELEASE_BUILD.md`。
