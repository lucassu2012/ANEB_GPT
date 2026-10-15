# 私有工程采集原件阅读器

同一 Android App：结果历史 → 研究记录与分析 → **工程采集原件 · 截图与采屏回看**。
这不是另一个测量系统、报告网站、SDK 格式转换器或正式 evidence schema。

## 一条完整使用路径

1. 将一个 HOST 输出目录复制到手机可选择的位置。目录直接包含原始 `PAIR_RESULT.json`，每轮原件位于该清单实际引用的 `run_id/originals/文件名`。
2. 在现有研究阅读器选择该目录。此时只预览清单，尚未保存、未认证原件，也不打开外链。
3. 明确点击“确认复制全部原件到本机”。流式复制清单引用的全部文件，大小和 SHA-256 全部吻合、轮次终态对应后，一次提交本机副本。
4. 从“本机工程列表”重新打开。重新读取本机清单和全部原件，不再依赖外部目录或临时 URI。
5. 选择对应 W1/C1 的截图、原文/XML 或采屏录像；关闭或让页面进入后台时停止自有播放器。后台会撤销本次播放并清空媒体源，返回前台不自动重播；需要关闭原件查看并重新选择录像。

没有导入自动保存、后台采集、发送、上传或自动播放网络地址。不会执行复制来的 `.sh` 或任何脚本。R1 文本、research-video-1 与私有 SDK 历史观察入口及格式保持原样。

## 原字节和私有本机关联

- 输入就是 HOST 原始 `PAIR_RESULT.json`；不添加伪 SDK getter，不将聊天采屏改写成目标视频播放体验。
- 仅接收 `claim_scope=private_engineering_observation`、`formal_new_slots=0`、`rpi=not_computed`；不是增加正式 claim。
- 轮次、condition、state、send_calls、owned_cleanup 展示为 HOST 输入摘要。SEND 的整数 0/1 或字符串 UNKNOWN 保持原型；HOLD/UNKNOWN/NOT_RUN 不转换成成功或归零。Native 原值由其 TERMINAL.json 另外查看；HOST 清场包含额外后置检查，不强制与 Native 同值。
- 完整清单原字节的 SHA-256 是本机条目 ID。所有文件只从明确选中的目录按相对清单路径打开；禁止绝对路径、越界、重复、错轮文件名及大小/SHA 不符。
- 本机目录为 `noBackupFilesDir/engineering-observations/<pair-sha>.pair`。暂存目录不出现在列表；失败不替换旧条目。相同条目已存在时重核，不修补损坏副本冒充成功。
- `PAIR_RESULT.json` 不重序列化。原 PNG/XML/JSON/MP4/其他清单文件均保持原字节。导入完整性不是源真认证。

## 资源边界

这是本地实现预算，不是测量合同：清单最多 2 MiB；至多 6000 原件，总计 128 MiB，单件 64 MiB。不会将整段 MP4 载入 ByteArray。

原图显示另限 8 MiB／每边 4096／400 万像素，按比例显示并标原尺寸；不能显示的原件仍保留。原文字面查看最多展示前 32000 字符，完整原文件不截断。MP4 在明确点击后由 Android 本机播放器打开，设备不支持时显示具体未播放状态，不借用旧录像或外链兜底。

## 不可升级的边界

TTFR、Completion、Stall、RPI 全部 NA／未计算，`metrics=null`，正式新增 0，reference 未接受。不得用媒体 PTS、播放器进度、原始 operational 时钟、回复画面或文件数推算科学指标、成功率、网络因果或后台工具/模型行为。

“全部文件 SHA 匹配”“播放器 prepared”“播放器报告首帧”“人工实际看到画面”和“完整语义覆盖”是不同事实；不互相代替。原始 HOST 清场/Reader 恢复与当前 Reader 导入、重开、媒体查看也分别验收。

## 验证范围

`EngineeringObservationReaderFlowTest` 通过真实 controller、store、本机临时目录和 Android PNG codec 验证预览→明确保存→移除外部来源→新实例重开→取得对应原字节。匿名 MP4 fixture 是 32×32 合成色块，不含研究或账号数据，非正式观察。

`EngineeringCapturePlaybackTest` 使用实际 UI 共用的 View/Lifecycle 绑定和平台 shadow 回调，验证停止后重新进入前台，即使到达先前排队的 prepared 回调也不得重播；不把它当作真机 Surface 或 MP4 解码证明。

该 JVM/Robolectric 测试不证明 SAF 真机选择器、Compose 屏幕、实际 Android MP4 渲染或跨进程恢复。完整交付还须固定 APK 后，在 P40 上用本次已闭合工程原件走同一条导入、重开和实际回看路径；未走过则标 NOT_TESTED，不以软件 RC0 代替。
