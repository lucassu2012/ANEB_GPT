# 单次视频执行器：使用与原件复核

状态：开发候选，尚无本版本实机播放或网络基线验收。此说明不是一张已准入的实机执行卡。

## 复用边界

仅 Debug instrumentation 的 `com.aneb.probe.research.business.NativeVideoScenarioInstrumentation#runOneProfile`。
不改生产 App 路由，不向聊天 SEND 白名单添加视频 App，不运行旧 M6/campaign 或旧耗尽机会。
现有 `TongyiProjectionCaptureService.Session` 只提供可复用录制边界，类名不是本次研究 App 身份。

安装前须记录：源 commit、Debug APK/测试 APK SHA-256、应用 ID/签名、原已装 APK 及可恢复路径。
完整质量门只构建 instrumentation，不自动运行 `connectedAndroidTest`；真实运行必须由总控另行固定执行卡。

## 两种明确入口

| 入口 | 业务动作 | 产物与边界 |
|---|---|---|
| `SOURCE_PREPARATION` | 启动本轮自有抖音；0 手势 | 当前公开页面树、截图和 App 身份。不是参考样本，不产生指标，不自动认定内容或账号合格 |
| `REFERENCE_QUALIFICATION` | 仅一次固定上划，观察30秒 | 原始 MP4、带手机时钟的 PNG、操作/界面/网络事实、结果及自有清理；仍待独立内容与媒体复核 |

共同必需参数：`feature_release=video-business-profile-1`、`live_authorized=true`、新 `run_id`、
`execution_mode`、精确 `profile_sha256`、实际正整数 `app_version_code`。
`max_gestures` 必须与入口严格一致（0 或1）。路径 create-once；同目录不能覆盖，失败不自动改名重跑。

资格入口另需：`ui_binding_b64`、原 UTF-8 字节对应的 `ui_binding_sha256`、本次新鲜的 `network_key`。
UI binding 必须从同一 App 版本的真实公开来源解析并固定：显示宽高/旋转、唯一 feed anchor、
gesture viewport及其精确 `viewport_bounds`。容器可能包括覆盖控件，不能把整个容器叫作纯视频区域；本次手势路径须避开已见控件。
首个资格的 `admission_scope=KNOWN_RECOMMENDATION_FEED_ONLY`、`blocker_coverage=NON_EXHAUSTIVE` 必须明确。
登录/付费/协议/广告/业务错误只有已有真实来源时才加入公开选择器；空数组必须逐类登记在 `uncovered_blocker_kinds`，表示未覆盖，绝不是“已证明没有”。
不得为填表编造ID，也不为了本次资格先点击登录/付费/协议页。正向锚点不匹配仍停；广告可能保留推荐锚点，因此 FEED_READY 只表示已知页面/手势容器匹配，不证明无广告、账号状态或正在播放。
内容是否合格继续交独立原件复核；不因正向页绑定而自动成为播放成功或有效基线。
该版本会在手势前做全树检查，输入后可采用相同公开选择器有界查询；均非原子页面事务或私有状态证明。

不得复制占位参数直接运行。总控须先核验设备在线、Huawei Launcher 前台、冲突进程/服务、
活动 VPN/隧道和实际网络；来源不明的会话不结束。Source 准备同样需要该前置条件。

资格入口在准备期请求正常系统录屏许可。仅在执行卡已明确允许该候选本次录制、平台允许且当前Android确认页的归属/范围已核实后，才可通过正常可见界面处理；不预授予、不用appops或隐藏设置绕过拒绝/安全策略。Windows隐私/安全弹窗不能由Windows自动化代点，权限不足时停在原边界。
准备360秒、录制就绪20秒（受剩余准备预算限制）、自有清理210秒；不因失败自动追加预算。
收到 `CANCEL` 时不开始新输入；输入已开始则尝试 ACTION_CANCEL，状态保留取消或不明，不自动补手势。

## 新旧时间和指标分开

- `gesture-native.json` 记录本轮原生输入尝试的 elapsed 起止、MotionEvent uptime、绑定界面和 owner；
  输入返回 true 不是播放成功。核心 `swipe_invocation_started` 还包括 Native 内部检查耗时，不能替代真实输入尝试起点。
- `frame-source-N.json` 与 `result.json.frames` 记录截图 API 请求/返回/写入的同机 elapsed 时间及原文件 SHA/大小。
  写入耗时不是播放耗时；实际采样空隙保留，500ms计划间隔不等于每500ms一定取得画面。
- 本方法拟复核“首次被本采集器观察到可辨播放”的等待；截图不是物理呈现时间，也不是播放器内部首帧。
  只有独立复核能辨明新目标内容、可辨动态和时钟来源后，才可生成新方法的派生结果。
  自动输出的数值始终为 null，stall 为 `not_computed`。
- MP4 尚无经过验证的 elapsed↔PTS 桥。禁止把 recorder-ready、文件存在、录制开始或 host 时间当 PTS=0。
  旧 V0/VF 和 Completion 不受本方法修改，缺失仍 NA；不得把截图结果填入旧指标。
- 同一原片内的可见feed切换开始与新目标可辨内在动态可另标实际帧PTS区间，生成独立 `visible_switch_wait`。这不需要elapsed桥，但必须有原始帧PTS/时间单位、帧与事件的对应、身份与运动判据；无源时NA，不用名义FPS或best-effort替代。
  该指标是“记录中可见切换等待”，不是物理输入→画面、内部首帧或网络耗时，不回填原raw/旧指标；原30秒操作窗不改，精确跨时钟窗边界未证明时继续单独说明。
- `CAPTURED_AWAITING_REVIEW` 仅表示本次采集及自有清理完成，不等于内容合格、播放成功、30秒每瞬间可见或网络达到要求。

独立复核顺序：binding/profile/App身份 → SWIPE_INTENT/原生手势 → 原始PNG/MP4及逐帧来源 →
公开UI与网络状态 → 实际覆盖/失败 → 自有清理。保留原件、部分产物、错误和不确定性，不改写 raw result 来补值。
再使用已有 Reader/导出入口保存**明确命名的新方法**复核结果；不能把原件hash核对等同媒体语义复核。

时间来源依据：[Android 样本呈现时间](https://developer.android.com/reference/android/media/MediaExtractor#getSampleTime())、
[FFmpeg ffprobe 帧字段源码](https://github.com/FFmpeg/FFmpeg/blob/master/fftools/ffprobe.c)。实际分析仍须核对本次原片中的帧PTS与stream time_base，而非只引用文档。

## 清理与恢复

只停止本轮已识别 owner 的目标 App、本轮录制和自己的 shell 资源；读—比较—停止并非原子 CAS。
拒绝未知前台时不发送 HOME。仅在本轮自有前台/目标 App 可归属时恢复 Launcher。
`native-cleanup.json` 只声明自有资源结果；总控仍须立即做全局现场后检，发现未知残留时不擅自杀进程。
本实现不改手机网络/系统持久设置、不清缓存/数据、不登录、不接受协议、不触发赞/关注/评论/购买。

## 网络条件尚未就绪

［KNOWN｜HIGH］当前电脑 Intel I219-V 有线口未连接，WLAN 正在使用；ICS 已运行但归属未确认。
不得将其停止或改造成“本轮自有服务”。本轮没有启用热点、安装驱动或改变手机网络。

［INFERRED｜HIGH］可验证的候选拓扑为“有线上游→Windows→本轮 Wi-Fi 热点→P40”。
先需接好网线并核实上网、实际共享绑定、手机默认路径与恢复；拓扑成立不等于整形成立。
Windows 主机 QoS 规则成功不证明覆盖 ICS/NAT 手机下行；ANEB 节点 netem 不覆盖第三方视频 CDN。
要新增转发层整形，须先锁定具体工具/版本/签名/hash、手机接口/地址范围、方向、时间窗、恢复和实测校准来源。
本候选不带驱动安装、热点/路由修改或任意 VPN 功能，不用关闭安全功能换通过。

一手边界来源：
- [Microsoft：Ethernet通过移动热点共享](https://support.microsoft.com/en-us/windows/experience/connectivity-networking/use-your-windows-device-as-a-mobile-hotspot)
- [Microsoft：New-NetQosPolicy](https://learn.microsoft.com/en-us/powershell/module/netqos/new-netqospolicy)
- [WinDivert：NETWORK_FORWARD与驱动权限](https://github.com/basil00/WinDivert/blob/master/doc/windivert.html)
