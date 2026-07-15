# Codex A 阶段状态（2026-07-15）

## 已由代码与自动化验证

- 首次运行加入无线/定位权限用途说明、拒绝后的明确反馈和“低置信继续”路径。
- UI 不再把公开 Android API 的 NR/LTE 证据猜成“5G SA/NSA”；证据不足或冲突会直说。
- 测试页支持取消，二次运行清空旧日志；离线、DNS、连接失败和超时使用可行动中文提示。
- 手动设置跨进程重启持久化；ADB autorun 仍从确定性默认值起步，避免测试被人工设置污染。
- 新增真实节点页：只展示已部署并留有证据的深圳 E-01，可刷新 SNI/bare-IP 双通道可达性。
- Codex Debug 包名为 `com.aneb.probe.codex`；Release 仍为 `com.aneb.probe`，用于同机并行比较。

质量门结果：365 tests、0 failures、Android Lint 0 errors / 9 tracked warnings、Debug APK 构建通过、Go tests 通过。

## 尚未判定通过

- 真机首次安装与权限交互：华为应用市场在 PC 工具安装链路增加人工验证码；旧正式包未被覆盖，
  本轮验证码会话已取消，需用最新 APK 重新安装后验收。
- 模拟器：现有 x86_64 AVD 在当前宿主机的 WHPX 路径停在 offline；清数据、重启 ADB 无效，
  软件加速 AVD 无法启动。该结论是测试基础设施故障，不是 App PASS。
- 375×667 短屏视觉回归、后台/恢复架构和完整 Home → Testing → Result → History 真机证据仍待完成。

## 证据边界

本文只记录已执行结果。物理设备或模拟器未跑通的项目保持未通过，不用单测或桌面构建替代。
