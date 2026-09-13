# 视频观察记录 → 中文离线页（research-video-1）

本工具是本地研究首切，不是正式 evidence schema、评分器或媒体分析器。
不修改文本分析器，不把 VF 填进文本 first_content，不套用 TTFC/T3。
仅消费人工记录中的区间/声明；不打开录像、图片或记录内的任何引用，不联网。

## 可运行示例

从仓库根目录运行，输出文件必须不存在，父目录必须存在：

```powershell
python -B scripts/research_video.py docs/real-app-research/samples/video-observation.sample.json --output-json C:/local/new-video-analysis.json --output-html C:/local/new-video-report.html
```

双击 HTML，无需服务或网络。两个文件输出成功时 RC0，stdout/stderr 为空。
`VIDEO_OUTPUT_EXISTS_OR_COLLISION` 拒绝覆盖或输出同路径；非法输入返回非0与
`VIDEO_INPUT_INVALID`。先解析/计算/渲染，再排他创建两个文件；磁盘故障可能留部分输出，
不是事务，不自动删除/重试。不要让另一写入者修改同一路径。

## 最小内部输入（非正式合同）

完整 SAMPLE 见同目录 `samples/video-observation.sample.json`，所有事实均为合成。

|字段|含义|
|---|---|
|format|`research-video-1`|
|record_kind|`SAMPLE` 或 `OBSERVED`；后者仅表示原记录声明，非真实性认证|
|app|非空 App 名称；不隐式跨App分组/汇总|
|evidence|可选的原引用/sha256/文字，只保留不追读，不认证被引用字节|
|attempts|非空数组，每条非空且不重复的 slot；不强制扩成三槽|
|status|`EXECUTED` 或 `NOT_RUN`，不是成功/失败判定|
|V0 / VF / window_end|`null` 或 `{clock_id, pts_s:[lo,hi]}`；秒、同一原录像PTS域，非墙钟/帧号|
|visible_target_playback|已执行为 `yes/no/uncertain`，NOT_RUN 为 `not_observed`|
|window_complete|已执行为 `yes/no/uncertain`；完整表示原标注覆盖声明，NOT_RUN 为 `not_started`|
|reason|原始异常/缺失/操作说明，页面转义展示|

V0 是固定打开/播放操作首次产生可见生效反馈；不是触屏、HTTP或App冷启动时刻。
VF 是目标首个可辨播放画面，原标注须由后续运动/进度确认；静态封面/广告不算。
端点时间必须为有限、非负 JSON 数字且 lo<=hi；禁止bool/字符串代替时间。
clock_id 必须非空、非UNKNOWN，两个事件必须逐字相同；**同名只是声明一致，不是软件验证了录像时钟**。
坏端点记 NA，非法JSON（包括NaN/Infinity）整体拒绝。UTF-8输入；原字节SHA256保留，
`input_record`保留解析后原记录，包括说明与引用；它不是原字节副本，请另存原文件。

## 结果语义

- 可见打开至首画面：`[VF.lo - V0.hi, VF.hi - V0.lo]`，显示秒，不取中点、不夹到0。
- 缺端点/非法端点/确定倒序：NA/null+reason；跨时钟/顺序重叠/目标播放未确认：uncertain/null+reason。
- 30秒计划窗从V0开始，计划终点区间是 `[V0.lo+30,V0.hi+30]`，包括首次等待，
  不是播放完成、有效播放量或实测窗口终点；缺VF绝不以30秒补值。
- 窗口终点同钟且其下界覆盖计划上界、原标注window_complete=yes，才显示“据标注已观察完整”。
  提前终止可显示部分；边界重叠/声明冲突/覆盖不确定仍不确定；缺V0/终点或倒序仍NA。
  原始gap/内容结束说明保留，不自动识别gap，不通过终点推断中间连续播放。
- NOT_RUN必须三端点都null，窗口not_started、播放not_observed；冲突拒绝。
  计数只给计划槽/执行/未执行，不给成功率/播放失败率。
- 缓冲与暂停次数/时长均未计算、null；不默认0。不推网络因果、不计算视频RPI、不做排名。

支持一个窄兼容入口：`record_kind=video_observation_preflight_blocked` 原始记录，
仅全部NOT_RUN且 `actual_measured_opens=0`、planned_slots/not_run与槽数一致可读取。
输出标OBSERVED原记录声明，并保留原输入；不能将该入口用于已执行视频事件。
实际受工具策略阻断的输入只能展示未执行，工具不绕过限制、不生成V0/VF。

## 验证与范围

```powershell
python -B -m unittest discover -s tests -p test_research_video.py -q
python -B -m unittest discover -s tests -p 'test_*research*.py' -q
```

仅host研究回归；完整项目门由指定责任人执行。本工具不读媒体、不发设备/云端操作。
真实输入、账号、录像、私密准备画面不得进Git；原JSON/页面可能含本地研究说明与引用，
只供授权本地使用。SAMPLE 页面与真实NOT_RUN页面必须分开交付，不能互换或拼成实测。
