# ANEB 测试模式与 Profile 设计（2026-07-15）

## 1. 结论先行

ANEB 采用“独立测量引擎 + 共用 Profile 外壳 + 共用动态仪表”的模式架构：

- `network_basic`：复现 SpeedTest 的基本网络性能项目，测下载、上传、时延、抖动、应用层请求丢失；
- `token_experience`：保留现有 AI 智能体体验测量，测首字、流式间隔、卡顿、中断、上传和工具调用；
- 新业务优先通过新增 Profile 扩展；只有出现新的 phase 类型或新的测量语义时才增加客户端/服务端代码。

两个模式禁止共用一个综合分。`token_experience` 继续使用版本化 AQS；`network_basic` 首版只给逐项结果、最弱项和应用场景结论，不发明一个不可解释的“网速总分”。

## 2. Profile 合同

每个 Profile 必须包含：

```json
{
  "profile_id": "basic_network",
  "version": "0.1.0",
  "mode_id": "network_basic",
  "kpi_set": "basic-network-kpi-v0.1",
  "description": "业务与负载说明",
  "est_duration_s": 28,
  "presentation": {
    "live_metric_id": "phase_throughput_mbps",
    "live_metric_label": "实时速率",
    "live_metric_unit": "Mbps",
    "live_window_ms": 1000,
    "ui_refresh_ms": 250,
    "metric_ids": ["D1", "U1", "N1", "N2", "L1"],
    "conclusion_policy_id": "network-basic-conclusions-v1"
  },
  "phases": []
}
```

约束：

1. `profile_id + version` 发布后冻结；负载或呈现合同变化必须升版。
2. Profile 只引用 `conclusion_policy_id`，不下发任意公式或文案脚本；防止服务端静默改变结论。
3. `live_metric_id` 是当前模式的业务主指标，不等于最终综合结论。
4. 缺失样本保持 `null`；Profile 不得定义“失败时以 0 代替”。
5. UI 刷新间隔首版固定 250ms，测量采样可更快；动画不得反压测量线程。

## 3. 模式一：网络基本性能

### 3.1 使用场景

用户想先回答“这条网络本身快不快、稳不稳”，不涉及模型、Token 或智能体任务。

### 3.2 Profile：`basic_network@0.1.0`

| 阶段 | 业务 | 测量 | 实时动态 |
|---|---|---|---|
| `clock_sync` | 空闲网络基线 | RTT P50、相邻 RTT 差中位数、请求失败率 | 顶部 Ping/抖动逐样本刷新 |
| `download_throughput` | 大对象下载 | 10 秒应用层下载 goodput | 中心仪表实时 Mbps + 1 秒滑窗曲线 |
| `upload_throughput` | 大对象上传 | 10 秒应用层上传 goodput | 中心仪表实时 Mbps + 1 秒滑窗曲线 |
| `clock_sync` | 测后基线 | RTT/抖动/失败率复核 | 顶部指标收束 |

首版 KPI：

- `D1`：应用层下载 goodput（Mbps）；
- `U1`：应用层上传 goodput（Mbps）；
- `N1`：应用层 echo RTT P50（ms）；
- `N2`：相邻 RTT 差中位数（ms）；
- `L1`：应用层 echo 请求失败率（ratio），不得标成 IP 层丢包。

实时主指标：当前吞吐阶段的 Mbps。下载阶段显示下载 Mbps，上传阶段显示上传 Mbps；其余阶段显示测试进度，不以 0 冒充速率。

每次测试必须输出三层结论：

1. 完成性：成功、部分完成、失败及具体失败阶段；
2. 主要瓶颈：下载、上传、时延、抖动或请求失败率中最弱一项；
3. 场景适配：网页/视频、视频通话、云备份与大文件上传分别给“适合/勉强/不适合”，并显示命中的版本化门限。

### 3.3 口径边界

基本测速仍是“终端到指定 ANEB 节点的应用层路径”，不是无线层、运营商全网、SLA 或 IP 层结论。多连接数、阶段时长、预热剔除规则必须进入 Profile 版本和结果元数据。

## 4. 模式二：Token 体验

### 4.1 使用场景

用户想回答“网络是否适合 AI 对话、编码 Agent、长回答与多模态上传”。

### 4.2 Profile 组

| Profile | 业务 | 关键指标 | 实时主指标 | 结论重点 |
|---|---|---|---|---|
| `s1_chat` | 普通 AI 对话 | N1/N2、T1/T2/T3/T4 | 流式到达事件/秒 | 首字与对话顺滑度 |
| `s2_coding_agent` | 编码 Agent 长任务 | T1/T2/T3/T4/T5、U2 | 流式到达事件/秒 | 任务完成、卡顿、中断、工具往返 |
| `s3_multimodal` | 图片/文件 + AI 输出 | U1、T1/T2/T3 | 流式到达事件/秒 | 上传瓶颈与生成稳定性 |

实时主指标首版定义为最近 1 秒完整 SSE event 边界到达速率，每 250ms 刷新。它是仿真流式到达代理量，不是模型计费 Token；最终有效 token 数仍以 EOF 后协议解析和服务端 usage 为准。

每次测试必须输出：

1. 编码任务是否完成；
2. 首字、卡顿、上传中的主要瓶颈；
3. Token 影响：仅当有连续性中断率或真实 API usage 前后对照时输出估算/实测；否则明确“不能计算”。

## 5. 动态视觉状态机

所有模式共用以下视觉节奏：

1. `idle`：开始环低频呼吸和渐变旋转；
2. `preparing`：进度弧，不显示 0 速率；
3. `measuring`：真实主指标驱动指针、数字、弧和波动曲线；
4. `phase_transition`：上一阶段数据收束，标签和单位切换；
5. `complete`：指针平滑回落，结果卡依次出现；
6. `failed`：停在最后有效样本，显示失败阶段与可行动建议。

开启系统“减少动态效果”时，保留状态变化和数字刷新，关闭呼吸、旋转和弹性过冲。

## 6. 开发与实机迭代 Loop

每轮按同一闭环执行：

1. 开发一个可独立验证的纵向切片；
2. Android/Go 单测、构建、Lint；
3. 检查 P40 Pro 前台互斥状态；
4. 安装 `com.aneb.probe.codex`；
5. 真机跑完整测试并录屏/截图/日志；
6. 主动按 Home 退出并复核桌面；
7. 对照 ANEB_UI 与 SpeedTest 动效目标优化；
8. 只有证据通过后进入下一切片。

## 7. 分期

- A：动态视觉底座、Token 模式闭环、Profile 合同、网络基本性能模式与真机多轮优化；
- B：历史/专业报告/地图/API 探针/节点和多 Profile 运营能力统一；
- C：生产化部署、发布配置、完整文档、兼容性与最终验收。
