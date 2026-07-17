# P40 Pro App 0.5.1 真机验证

> 日期：2026-07-17（Asia/Shanghai）
> 设备：Huawei P40 Pro（ELS-AN00）
> 包名：`com.aneb.probe.codex`

## 验证边界

- ［KNOWN｜HIGH］本轮验证 App 0.5.1 的安装/启动、AI 实时 Quick 的持久化终态，以及 Debug-only API 诊断组件的安全拒绝。
- ［KNOWN｜HIGH］本轮没有调用任何 Kimi、DeepSeek、千问或其他真实付费 API。
- ［KNOWN｜HIGH］本轮没有修改、启动或强停 Claude 包 `com.aneb.probe`。

## 构建身份

- ［COMPUTED｜HIGH］APK：`versionCode=33`，`versionName=0.5.1-codex`。
- ［COMPUTED｜HIGH］APK 大小：57,497,562 bytes。
- ［COMPUTED｜HIGH］SHA-256：`099C9180D7EFE5FDEAF1F4A96FA9D74E5ED0B59ABE5141676128CEE6A170426A`。
- ［KNOWN｜HIGH］ADB streamed install 返回 `Success`；MainActivity 启动后无 FATAL/AndroidRuntime 崩溃。

## AI 实时 Quick

- ［KNOWN｜HIGH］run id：`019f709d-33bf-7dbf-a732-35e28a71b447`。
- ［KNOWN｜HIGH］Profile：`ai_realtime_voice_quick@1.0.0`；行为模型：`ai-realtime-voice-behavior-v0.2@0.2.0`。
- ［KNOWN｜HIGH］1/1 会话完成，3 轮完成；`REALTIME_V1_SESSION_END success=true error=none`。
- ［KNOWN｜HIGH］`REALTIME_V1_DB_WRITE ok=true` 先于 `REALTIME_V1_RESULT`；结果为 98.6/A、`LOW/INCONCLUSIVE`，最终 `REALTIME_V1_END status=completed`。
- ［KNOWN｜HIGH］`LOW/INCONCLUSIVE` 是 Quick Profile 的证据强度约束，不代表运行失败。
- ［KNOWN｜HIGH］测试结束后 `ProbeRunService` 无残留，Room WAL 更新时间与本次 run 一致；原始结果保留在设备本地数据库。

## Debug API 入口边界

- ［KNOWN｜HIGH］ADB shell 能显式启动 `com.aneb.probe.debug.ApiProbeDebugActivity`，证明 `android.permission.DUMP` 的 shell 调试路径可用。
- ［KNOWN｜HIGH］只传 `apiprobe_autorun=true`、不传 server/key 时，组件输出 `APIPROBE_DEBUG_REJECT reason=server_missing` 并自行关闭。
- ［KNOWN｜HIGH］该路径没有发出真实 API 请求；Release 不含该 Activity 的事实由 `scripts/verify_release_boundary.ps1` 和合并清单验证。

## 无效证据与退出状态

- ［KNOWN｜HIGH］安装后首次截图是全黑帧，随后 ADB 短暂离线；该截图不能证明 UI 正常或异常，未纳入视觉验收。
- ［KNOWN｜HIGH］设备重新在线时已位于华为桌面，两个 ANEB 包均无 PID/服务；完成上述两项验证后再次执行 HOME + force-stop Codex 包。
- ［KNOWN｜HIGH］最终状态：华为桌面前台；`com.aneb.probe` PID 空、`com.aneb.probe.codex` PID 空；两个包均无 ServiceRecord。

## 结论

- ［KNOWN｜HIGH］App 0.5.1 的安装、普通启动、AI 实时 Quick 持久化闭环和 Debug API 安全拒绝通过。
- ［KNOWN｜HIGH］本轮不包含有效 UI 截图、屏幕旋转、真实断网或切后台真机证据；这些项目不得据此写成通过。
