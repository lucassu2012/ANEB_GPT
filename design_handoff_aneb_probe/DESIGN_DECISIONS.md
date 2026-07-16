# ANEB UI 设计决策

## 已确认方向

- Product Owner 已确认采用方案 A：经典中心仪表与一键测试。
- 视觉与动效参考用户提供的 SpeedTest 主界面、连接态、弹窗、拖拽抽屉、API 状态和地图 GIF。
- 首页服务普通 AI 用户；待测试状态保持克制，专业指标只在测试进行时展开。
- 当前版本不加入付费入口。

## 当前交付范围

- `screens/home.html`：待测试、连接中、测试中和结果四个状态。
- 测试中顶部持续刷新 Token 流速、上行、Ping、抖动、丢包与三条波动曲线。
- 中央视图在测试中切换为 270° 仪表盘，分别使用青绿色和紫色表达主测试、上行阶段。
- 首页网络信息使用三档吸附抽屉；点击或拖拽把手可展开，节点选择使用带背景模糊的居中弹窗。
- 结果页“重新测试”按钮与首页青绿圆环共用同一视觉语言。
- `screens/probe.html`：可操作的 API 探针未来界面，可模拟扫描 4 个 AI/API 服务端点。
- `screens/map.html`：可操作的网络体验地图未来界面，可切换 AI 体验和延迟图层并展开地图详情。
- `screens/history.html`：SpeedTest 风格的体验趋势与测试记录入口。
- `screens/result-simple.html`、`result-light.html`、`result-dev.html`：普通、Wi-Fi 与专业结果层级。
- `screens/server.html`、`settings.html`、`share.html`、`testing.html`：节点、设置、分享和独立测试流程。
- `screens/foundations.html`、`motion.html`：与产品页面同框呈现的视觉基础和动效规范。
- 上述 10 个扩展页面共用 `screens/suite.css` 与 `screens/suite.js`，避免页面各自维护不同风格。

## 设计原则

- 极深海军蓝背景、弱化品牌字标、单一主要操作。
- 图标统一为细线描边；字体采用 iOS、Windows 与 Android 可回退的系统字族链，数字使用等宽特性。
- 中文列表标题与正文以 12px 为主，辅助信息为 10–11px，导航和上眉标签为 9px；8–8.5px 仅保留给品牌角标、仪表刻度、方向图标和地图地名。
- 深色背景上的辅助文字提高到 56%–68% 不透明度，弱提示不低于 46%，避免中文细笔画发灰或粘连。
- 测试开始前不永久展示 Ping、抖动、丢包，避免普通用户面对无意义的仪表盘噪声。
- 测试中才提高信息密度；测试结束后收束为首字响应、Token 流速和卡顿三个可理解结论。
- 所有主仪表盘与结果圆环使用强制 `1:1` 宽高比；桌面预览和手机断点均不允许拉伸为椭圆。
- 未来页目前只验证产品形态和交互，不伪装为已经接入真实 API 或地图数据。

## 开源进度仪表参考

参考 `sharmanirudh/speedtest.net-progress-bar` 的动画节奏，但没有复制其位图或引入旧版 Android 依赖。本 HTML 原型使用 SVG、CSS 和原生 JavaScript 实现可缩放仪表盘、波形与状态切换。

## 本地预览

在 `design_handoff_aneb_probe` 目录运行：

```powershell
python -m http.server 8765 --bind 127.0.0.1
```

页面：

- `http://127.0.0.1:8765/screens/home.html`
- `http://127.0.0.1:8765/screens/probe.html`
- `http://127.0.0.1:8765/screens/map.html`
- `http://127.0.0.1:8765/screens/history.html`
- `http://127.0.0.1:8765/screens/settings.html`
