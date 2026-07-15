# ANEB UI → Claude Code 集成交接

## 目标

把本包中的最终 UI 设计集成到正在开发的 ANEB 手机 App，并用真实后台、网络探测服务和持久化数据替换当前原型中的模拟数据。

视觉决策见 `DESIGN_DECISIONS.md`。页面和文件角色见 `UI_MANIFEST.json`。

## 先做的事

1. 先检查目标 App 使用的技术栈、现有导航、状态管理、网络层和数据模型，不要假设是 WebView。
2. 将这些 HTML 页面作为视觉与交互基准；如果目标是原生 iOS、Android、Flutter 或 React Native，应翻译成该框架的组件，不应直接套一层 WebView。
3. 复用视觉令牌和交互层级，不要重新引入旧版 `tokens.css`、`aneb.js` 或 `reference/` 目录。
4. 先接入测试状态机，再接入 Probe、History、Map、Share 和 Settings。

## 最终 UI 文件

### 核心产品页

| 路由建议 | 原型文件 | 用途 |
|---|---|---|
| `/test` | `screens/home.html` | 首页；idle / connecting / running / result 四状态 |
| `/test/live` | `screens/testing.html` | 独立测试中详情页 |
| `/probe` | `screens/probe.html` | API 端点探针 |
| `/map` | `screens/map.html` | 网络体验地图 |
| `/results` | `screens/history.html` | 历史结果与趋势 |
| `/results/:id` | `screens/result-simple.html` | 普通结果 |
| `/results/:id/wifi` | `screens/result-light.html` | Wi-Fi 结果示例；仍使用统一深色主题 |
| `/results/:id/pro` | `screens/result-dev.html` | 专业指标与导出 |
| `/servers` | `screens/server.html` | 测试节点选择 |
| `/settings` | `screens/settings.html` | 设置 |
| `/results/:id/share` | `screens/share.html` | 分享卡预览 |

### 设计规范页

- `screens/foundations.html`：颜色、字体、圆角和文案层级参考，不需要作为正式产品路由发布。
- `screens/motion.html`：按压、持续状态、拖拽和减弱动效参考，不需要作为正式产品路由发布。

## 共享资源

- `screens/home.css` + `screens/home.js`：首页状态机、实时曲线、仪表盘、节点弹窗和拖拽抽屉。
- `screens/future.css` + `screens/future.js`：Probe 和 Map 页面。
- `screens/suite.css` + `screens/suite.js`：其余 10 个页面的共享设计系统和交互。

不要把三个脚本中的模拟定时器当成真实业务逻辑。它们只用于展示设计状态。

## 字体与可读性基线

- 系统字族优先顺序：SF Pro Text / SF Pro Display → Segoe UI Variable / Segoe UI → 苹方 / 微软雅黑 → 系统无衬线字体。
- 中文列表标题和正文按 12px 视觉级别实现，辅助信息按 10–11px，实现时应使用目标平台的等效字号，不要机械照搬 Web 像素。
- 导航和上眉标签按 9px 视觉级别实现；8–8.5px 只允许用于品牌角标、仪表刻度、方向/节点图标和地图地名。
- 深色背景上的次级文字应保持足够对比度；不要把辅助正文降到近似不可见的灰度。
- 数字指标启用等宽数字特性，中文正文不要使用过大的字间距。

## 后台数据契约

### 网络测试会话

```ts
type TestPhase = "idle" | "connecting" | "response" | "stream" | "upload" | "complete" | "failed" | "cancelled";

interface NetworkTestSession {
  id: string;
  phase: TestPhase;
  progress: number;            // 0..100
  startedAt: string;
  completedAt?: string;
  node: TestNode;
  connection: {
    type: "wifi" | "4g" | "5g-nsa" | "5g-sa" | "ethernet";
    carrier?: string;
    deviceLabel?: string;
  };
  metrics: {
    pingMs?: number;
    jitterMs?: number;
    packetLossPct?: number;
    ttftSeconds?: number;
    tokenPerSecond?: number;
    uploadMbps?: number;
    stalls?: number;
    score?: number;             // 0..100
    grade?: "excellent" | "good" | "fair" | "poor";
  };
  samples: Array<{
    timestampMs: number;
    pingMs?: number;
    jitterMs?: number;
    packetLossPct?: number;
    tokenPerSecond?: number;
    uploadMbps?: number;
  }>;
  error?: { code: string; userMessage: string };
}

interface TestNode {
  id: string;
  name: string;
  city: string;
  carrier: string;
  latencyMs?: number;
  region?: string;
  isAutoSelected?: boolean;
}
```

### API 探针

```ts
interface ApiProbeResult {
  id: string;
  name: string;
  host: string;
  status: "healthy" | "degraded" | "timeout" | "unreachable";
  latencyMs?: number;
  checkedAt: string;
  samples: Array<{ timestampMs: number; latencyMs?: number; ok: boolean }>;
}
```

### 历史记录与地图

```ts
interface TestHistoryItem {
  id: string;
  score: number;
  grade: "excellent" | "good" | "fair" | "poor";
  testedAt: string;
  node: TestNode;
  connectionLabel: string;
  summary: Pick<NetworkTestSession["metrics"], "ttftSeconds" | "tokenPerSecond" | "uploadMbps" | "stalls">;
}

interface MapQualityPoint {
  id: string;
  latitude: number;
  longitude: number;
  score: number;
  latencyMs: number;
  sampleCount: number;
  updatedAt: string;
}
```

## 事件映射

| UI 操作 | 后台/应用动作 |
|---|---|
| 开始 / 重新测试 | 创建测试会话，进入 connecting |
| 关闭测试 | 取消当前会话并释放探测资源 |
| 实时曲线 | 订阅测试样本流，最多保留 UI 所需窗口 |
| 更换测试点 | 拉取节点并保存用户选择 |
| API 探针 GO | 并发探测已配置端点，支持单端点超时 |
| History | 从本地数据库分页读取结果 |
| Map 图层切换 | 切换评分/延迟聚合数据，不重新创建地图实例 |
| 分享 / 保存图片 | 使用原生截图与系统分享面板 |
| JSON / CSV | 从真实结果模型导出，不从 DOM 抓取 |

## 必须保留的 UI 行为

- 所有主仪表盘与结果圆环必须保持严格 `1:1`，不能随父容器拉伸。
- 测试前不永久显示专业指标；connecting 后才展开 Ping、抖动、丢包和曲线。
- 主测试阶段用青绿，上行阶段用紫色；异常使用暖黄/红色。
- 节点选择弹窗和网络抽屉必须支持键盘、触摸和取消。
- 所有测试失败、超时、权限拒绝和离线状态必须有用户可读提示。
- 遵循系统“减弱动效”设置。

## 建议实施顺序

1. 建立共享 Design Tokens 和组件：AppShell、Wordmark、BottomNav、Gauge、MetricCell、Sheet、Modal、Toast。
2. 用真实状态机重做 Home 和 Testing。
3. 接入节点选择、结果保存、History 和结果详情。
4. 接入 API Probe 和 Map 数据源。
5. 接入分享、导出和设置持久化。
6. 做视觉回归、弱网、离线、取消、后台切换和短屏测试。

## 验收基线

- 已验证原型尺寸：375×667、375×750、626×1278。
- 圆环宽高比：`1.000`。
- 浏览器控制台错误：`0`。
- 手机页面无横向溢出。
- 13 个界面已完成字体、换行、截断和横向溢出检查；产品正文无低于 9px 的字号。
- 关键交互：测试、拖拽抽屉、节点单选、设置开关、Probe 扫描、Map 图层切换均可运行。

## 建议 Claude Code 使用的工作方式

- 先做一次代码库与架构盘点，确认现有模块边界。
- 为后台状态机和数据适配器使用 TDD。
- 遇到真实设备或性能问题时使用系统化诊断流程。
- 每完成一个页面，和本包的 HTML 原型做一次视觉对照，再继续下一个页面。
