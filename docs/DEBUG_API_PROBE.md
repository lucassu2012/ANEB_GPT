# Debug-only 真实 API 诊断入口

## 产品边界

- 正式 ANEB App 只执行自建节点上的可控业务仿真；真实第三方 API 不进入正式导航、Profile 或评分。
- Release 合并清单不包含真实 API 诊断 Activity，App 也不再保存第三方 API key。
- 诊断核心代码保留用于开发对照，但唯一启动组件位于 `src/debug`。

## 调用边界

Debug 组件为：

```text
com.aneb.probe.codex/com.aneb.probe.debug.ApiProbeDebugActivity
```

它没有 Launcher、Deep link 或 intent filter。组件虽为 `exported=true`，但要求平台签名权限
`android.permission.DUMP`；ADB shell 可调用，普通第三方 App 不能调用。

参数：

| extra | 必需 | 说明 |
|---|---:|---|
| `apiprobe_autorun` | 是 | 必须为 `true` |
| `apiprobe_server` | 是 | API base URL |
| `apiprobe_key` | 是 | 本次临时 key；不会持久化 |
| `apiprobe_provider` | 否 | `openai_compat`（默认）或 `anthropic` |
| `apiprobe_model` | 否 | 缺省时使用协议族默认模型 |

命令模板（不要把真实 key 写入脚本、Git 或共享终端历史）：

```powershell
adb shell am start `
  -n com.aneb.probe.codex/com.aneb.probe.debug.ApiProbeDebugActivity `
  --ez apiprobe_autorun true `
  --es apiprobe_server https://api.example.invalid/v1 `
  --es apiprobe_key $env:ANEB_API_KEY `
  --es apiprobe_provider openai_compat `
  --es apiprobe_model example-model
```

只使用受限、可撤销的开发 key。命令行参数会在 ADB 调用期间短暂经过主机与 Android Activity
Manager；组件读取后立即擦除全部 `apiprobe_*` extras，日志、Room 和导出仍经过 key 脱敏。

## 一次性语义

- 仅首次 `onCreate` 可运行；Activity 重建拒绝重放。
- 进程内原子单飞；第二次 Intent 在首个任务完成前被拒绝。
- `onNewIntent` 只擦除参数，不触发第二次请求。
- 完成或失败后立即退出并移除独立任务。

## 自动验收

`scripts/quality_gate.ps1` 同时生成 Debug/Release 合并清单，并调用
`scripts/verify_release_boundary.ps1` 验证：

1. Debug 组件存在且受 `android.permission.DUMP` 保护；
2. Debug 组件没有 intent filter；
3. Release 不含该组件；
4. `MainActivity` 不再解析真实 API extras；
5. 正式 API Key/UI 文件不存在。

