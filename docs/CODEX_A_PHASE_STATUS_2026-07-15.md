# Codex A 阶段状态（2026-07-15，历史快照）

> 本文记录 A 阶段当日中间状态。其“尚未判定通过”项目已在 2026-07-16 的 B/C 阶段真机验收中关闭；最终状态以 `CODEX_V0.2.0_HANDOFF_2026-07-16.md` 为准。

## 已由代码与自动化验证

- 首次运行加入无线/定位权限用途说明、拒绝后的明确反馈和“低置信继续”路径。
- UI 不再把公开 Android API 的 NR/LTE 证据猜成“5G SA/NSA”；证据不足或冲突会直说。
- 测试页支持取消，二次运行清空旧日志；离线、DNS、连接失败和超时使用可行动中文提示。
- 手动设置跨进程重启持久化；ADB autorun 仍从确定性默认值起步，避免测试被人工设置污染。
- 新增真实节点页：只展示已部署并留有证据的深圳 E-01，可刷新 SNI/bare-IP 双通道可达性。
- 主测量改由 `dataSync` 前台 Service 持有；Activity 配置重建或切后台不再取消 run，通知可取消，
  回到 App 可恢复测试中、结果或失败状态。
- Codex Debug 包名为 `com.aneb.probe.codex`；Release 仍为 `com.aneb.probe`，用于同机并行比较。
- 已按 `ANEB_UI` `2026.07.15-2` 重构原生 Compose 视觉：深色令牌、五栏导航、首页/测试中/
  历史/结果/API 探针/地图/设置/节点/分享预览，并新增自适应矢量 App 图标；演示假数据未进入 APK。
- 真机回归发现华为三键导航的底部 Insets 会导致“画面可见但点击区被裁切”；已将系统栏改为
  edge-to-edge，并把底部导航 Insets 局部收敛到 Scaffold bottomBar，待设备空闲后复验完整命中区。

质量门结果：367 tests、0 failures、Android Lint 0 errors / 4 tracked warnings、Debug APK 构建通过、Go tests 通过。

## 当日尚未判定、后续已关闭

- 最新底栏 Insets 修正版随后已在 P40 Pro 安装并完成五栏命中、动态测试、结果和历史回看；每轮测试后均退出到华为桌面。
- 模拟器：现有 x86_64 AVD 在当前宿主机的 WHPX 路径停在 offline；清数据、重启 ADB 无效，
  软件加速 AVD 无法启动。该结论是测试基础设施故障，不是 App PASS。
- 新图标、底栏命中区和完整 Home → Testing → Result → History 已完成真机复验；证据见最终交接文档。

## 证据边界

本文只记录已执行结果。物理设备或模拟器未跑通的项目保持未通过，不用单测或桌面构建替代。
