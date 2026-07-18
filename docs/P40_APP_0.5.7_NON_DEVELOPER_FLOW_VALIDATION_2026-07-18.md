# P40 Pro · ANEB App 0.5.7 非开发者开测路径验证

> 日期：2026-07-18（Asia/Shanghai）
> 设备：Huawei P40 Pro（Android 公开 API，1200×2640）
> 包：`com.aneb.probe.codex`，`0.5.7-codex`，versionCode 39
> APK SHA-256：`0940BA48682FE00D7E464E61BA154532D4BACEF39BB98AACC667D6CEEDFC642A`

## 1. 反方观点与本轮边界

- ［KNOWN｜HIGH］这不是正式发布验收：安装的是 Debug 包，且使用 `adb install -r`，没有仓库外正式签名密钥，也没有验证应用商店分发。
- ［KNOWN｜HIGH］这不是全新设备清数据首装：为保留既有测量证据，使用系统权限撤销来进入与首装相同的“无线权限未授予”分支。
- ［KNOWN｜HIGH］本轮证明 M4 的“开测前自救 + 正常测量不回归 + 历史批量导出”切片；安装仍通过 ADB，不能冒充公开分发整链证据。

## 2. 变更

1. 所有四种 App 测试类型在无线证据不完整时，开测前统一说明电话/精确位置权限用途；允许取消、授权或低置信继续。
2. 权限只用于读取当前数据卡、小区与信号；对话明确说明不读取通话、联系人或 IMSI。拒绝无线权限不阻断业务测量，只把无线归因标为证据不足。
3. 开测前统一校验活动网络和节点根地址。节点只接受 HTTP(S) 根地址；正式包禁止 HTTP，并拒绝账号密码、查询参数、片段、API 子路径和非法端口。
4. 设置页沿用 `ANEB_UI` 的“高级/自定义服务器地址”位置，在原输入框内显示可操作错误，不增加新的视觉体系。
5. 设置页“数据与隐私”新增“导出可验证结果”：逐条校验冻结信封，按时间顺序输出原始 JSONL；单条异常严格拒绝，但不会永久锁死其余合法历史，跳过数量必须对用户可见。

## 3. 自动化验证

- ［COMPUTED｜HIGH］Android JVM：539 tests，0 failures，0 errors，0 skipped。
- ［COMPUTED｜HIGH］Android Lint：0 errors，11 warnings。
- ［KNOWN｜HIGH］Debug APK 构建成功；版本、包名与 SHA-256 如本文开头。
- ［KNOWN｜HIGH］新增反例覆盖：无 scheme、无 host、HTTP 正式包、账号密码、query、fragment、API path、非法端口及离线优先提示。
- ［KNOWN｜HIGH］新增穷举测试锁定所有 `AnebTestMode`：无线证据不完整必须先出现用途说明，完整时不得重复打扰。
- ［KNOWN｜HIGH］全仓质量门 PASS：Release 入口边界、6 Schema/catalog、统一结果 Schema、TTFT 分析器 5 tests、行为模型 31 tests、server/gateway Go tests 全部通过。

## 4. P40 用户路径实测

### 4.1 无效节点在开测前拦截

以一次性启动参数把节点设为 `bad`，在首页点击“开始”：

- ［KNOWN｜HIGH］首页显示“节点地址必须以 https:// 开头。请在‘设置 > 高级’中修正”。
- ［KNOWN｜HIGH］`ProbeRunService` 数量为 0，没有启动测量、没有生成伪结果。

### 4.2 首装无线权限说明

撤销 `READ_PHONE_STATE`、粗略位置与精确位置权限，选择“网络综合”并点击“开始”：

- ［KNOWN｜HIGH］系统权限框之前先出现 ANEB 用途说明。
- ［KNOWN｜HIGH］说明明确覆盖“三类正式结果”、不读取通话/联系人/IMSI，并提供“低置信继续 / 取消 / 授权”三条路径。
- ［KNOWN｜HIGH］用户未选择前服务数量为 0；不会因为误触首页直接开始后台流量。
- ［KNOWN｜HIGH］验证后恢复三项权限，没有改变 Claude 正式包状态。

### 4.3 正常网络快测不回归

恢复权限后，使用 E-01 默认节点、AUTO 承载、Network Quick 完成 run：

- run id：`019f7209-e89c-7adc-8238-83f9847acdc5`
- ［KNOWN｜HIGH］SNI 通道不可用时自动选择同节点 bare-IP 通道；测试依次完成握手、空闲 RTT、下载负载、上传负载和 UDP。
- ［KNOWN｜HIGH］动态页实时刷新下载速率、loaded RTT、时延增量、低速窗口、曲线和指针。
- ［KNOWN｜HIGH］结果先落 Room：`NET_V1_DB_WRITE ok=true`；无线证据 `status=collected samples=18 raw_samples=18 events=0`。
- ［KNOWN｜HIGH］终态 `completed`，57.6/C，verdict `INCONCLUSIVE`、confidence `LOW`。下载 P5 25.4Mbps、上传 P5 9.7Mbps、空闲 RTT P95 82.2ms、loaded RTT P95 1016.2ms、UDP 未返回 0%。
- ［KNOWN｜HIGH］Quick 样本不足以证明 95% 长期稳定性，结果页保持低置信，没有把 57.6 分外推为运营商总体质量。

### 4.4 切后台、通知与回到结果

Network Quick run `019f7211-0c5d-723d-a84f-49115ddd48da` 在测量过程中执行 Home → 后台等待 → 通知栏 → 回到 App：

- ［KNOWN｜HIGH］按 Home 前后 `ProbeRunService` 均为 1，前台切到 Huawei Launcher 后测量没有被 Activity 生命周期取消。
- ［KNOWN｜HIGH］通知显示“ANEB 正在测试网络”、当前阶段“正在测量上传容量与负载 RTT”，并提供“取消测试”动作。
- ［KNOWN｜HIGH］热启动回到 App 后自动显示完成结果，不要求用户重新选择测试或查找历史。
- ［KNOWN｜HIGH］Room 中 run 状态为 `completed`，分数 66.3/C，统一信封状态 `completed + valid`；无线样本 18、环境事件 1。该 Quick 结论仍为 LOW/INCONCLUSIVE。

### 4.5 主动取消与审计保留

Network Quick run `019f7212-0268-7280-9fa6-385b32a8fed1` 在下载阶段点击左上角取消：

- ［KNOWN｜HIGH］Service 从 1 降为 0，首页提示“测试已取消，未生成成绩”，没有跳转到正常结果页。
- ［KNOWN｜HIGH］Room 保留一条 `status=invalid` 的审计记录与 `aneb-result-v1` 信封；信封 run 状态为 `cancelled`、validity 为 `invalid`、reason 为 `cancelled`。
- ［KNOWN｜HIGH］取消记录的 score 为 `suppressed_invalid`，value/grade 均为 null；不会用未完成的下载片段计算成绩。
- ［KNOWN｜HIGH］取消前已采到的 5 个无线样本作为原始证据保留，业务指标全部为 missing，结论只写“测试未完成：cancelled”。这是“保留失效证据但抑制评分”，不是删除历史痕迹。

### 4.6 历史批量导出与旧摘要异常隔离

在设置页点击“导出可验证结果”，对本机 22 条 `result_envelope` 历史逐条校验：

- ［KNOWN｜HIGH］首次全有或全无实现真实暴露了旧历史摘要异常，并安全拒绝生成文件；没有绕过校验或重算历史评分。
- ［COMPUTED｜HIGH］4 条 0.5.3/0.5.4 旧记录为 `digest_mismatch`：`019f715d-eec5-7e29-92a4-7d78511cb37c`、`019f7192-aa56-7954-a4c7-db6428e5bae7`、`019f7194-46a2-7118-8228-6ccb13c4b052`、`019f7195-b2be-7a9d-af72-230c87a69578`。它们与 D-57 已否决的旧规范化摘要证据处于同一版本区间；本轮只报告事实，不改写旧摘要。
- ［KNOWN｜HIGH］首个修正版独立校验每条记录，生成 `aneb_results_18_of_22_20260718_063121.jsonl`；文件 979,886 bytes，SHA-256 `B4F7E00B64B57D3405CB5E6DA5E6CB576AC1F627BB29F0C9D022A8CFBA1618C9`。
- ［COMPUTED｜HIGH］文件含 18 行、18 个唯一 run id，按 `started_at_epoch_ms` 非递减排列；覆盖 Token、AI 实时、网络综合，包含 `completed` 与 `cancelled` 状态。
- ［COMPUTED｜HIGH］18 条 canonical JSON 摘要与 Room `canonicalSha256` 全部一致；4 个拒绝 run id 在文件中出现 0 次。
- ［KNOWN｜HIGH］真机界面在同一导出卡片内显示“已导出 18/22 条；4 条完整性异常已跳过”，没有被底部导航遮挡，也没有静默丢弃异常记录。
- ［COMPUTED｜HIGH］最终 APK 加入后续 3 条 AI 实时 run 后再次导出 25 条历史：生成 `aneb_results_21_of_25_20260718_065342.jsonl`，1,081,397 bytes，SHA-256 `25B4BE43C5AABF688C71966E2AD104F2ABEEA9255F87CCFEE24FF51469B8F649`；21 行/21 唯一 run、时间有序、21/21 canonical digest 匹配，4 个旧拒绝 id 混入 0 次，并包含本轮真实断网结果。
- ［COMPUTED｜HIGH］默认网络事件终验后导出 `aneb_results_27_of_31_20260718_100304.jsonl`：1,319,277 bytes，SHA-256 `86B7C1CBDD125120C2D1926828B9C121D17DAEA48B206A7C77DA0712DB4603E6`；27 行/27 唯一 run、时间有序、27/27 Room canonical digest 匹配，4 个旧摘要异常 id 混入 0 次，最终断网 run 出现 1 次。
- ［KNOWN｜HIGH］“可验证结果”当前在设备侧严格指受支持的 `schema_version` 身份、test type、run identity 与 canonical digest 完整性，不等同于用当前仓库 Schema 重新解释全部历史。独立离线审计发现 27 条完整性合格记录中有 3 条 0.5.2/0.5.5 Token 历史缺少后来加入当前 v1 Schema 的 `task_id/server_processing_ms/ttft_ms`；最终断网 run 自身 Schema 错误为 0。旧记录保持原样，未回填或重算。

### 4.7 AI 实时后台与真实网络中断

先执行一条只切后台、不改变网络的 AI 实时 Quick，再执行一条“进入会话 → Home 后台 → Wi-Fi 关闭 6 秒 → 蜂窝接管 → 恢复 Wi-Fi”的真实中断切片：

- 正常后台 run `019f7238-d040-71a0-b874-6c211f051e0d`：［KNOWN｜HIGH］3/3 轮成功、音频帧未返回率 0%、会话中断率 0%，100/A；Quick 仍为 LOW/INCONCLUSIVE，不因高分外推。统一信封 `completed + valid`、26 个无线样本、独立 canonical digest 匹配。
- 断网 run `019f7240-bf42-7a48-b23b-3235286da018`：［KNOWN｜HIGH］Service 在后台完成收口并落库，没有卡死；Wi-Fi 最终恢复为已连接，Codex Service 归零。
- ［COMPUTED｜HIGH］断网 run 观察到 1/1 个会话意外中断、2/3 轮未完成，应用音频帧未返回率 48.2%（返回率 51.8%）、会话中断率 100%；totalScore/grade 均为 null，verdict `INCONCLUSIVE`、confidence `LOW`，canonical digest 匹配，15 个无线样本保留。
- ［KNOWN｜HIGH］结果页首屏现在先写“业务任务受损”，随后给出长连接目标“会话中断率 ≤1%、应用音频帧返回率 ≥99%”及本次差距；不再只显示“必需指标缺失”。
- ［KNOWN｜HIGH］run 生命周期仍为 `completed + valid`，表示测量流程已完成且证据可用；业务任务失败、分数不可计算由评估层单独表达。两者不得混为 App 崩溃或伪造 `failed` 生命周期。
- ［KNOWN｜HIGH］该旧 run 当时尚未冻结系统默认网络事件，只能列候选原因；下一节用最终 APK 补齐同设备 PATH_CHANGE 纵向证据，旧结论不追溯改写。

### 4.8 默认网络 PATH_CHANGE 冻结与关联结论

最终 APK 为三类正式测试共用的 run 级证据收集器接入 Android 默认网络 callback；平台 `Network` 句柄不进入结果，只保存每次 run 内的 `path-1/path-2` 别名、承载类型和变化语义。稳定回放不制造变化事件；丢失、切换、恢复、验证丢失/恢复、暂停/恢复均去重记录。

- 最终 run：`019f72f5-557c-71b0-a7d9-b462055f0545`。
- ［COMPUTED｜HIGH］进入 AI 实时会话 4 秒后关闭 Wi-Fi 6 秒再恢复；结果冻结 1 条 `PATH_CHANGE`：`default_network_lost path=path-1 transport=wifi`，并保留 5 个无线样本。
- ［KNOWN｜HIGH］业务侧冻结 1/1 会话意外中断、3/3 轮未完成、会话中断率 100%、应用音频帧返回率 0%；缺少 LIVE-B04/B08/N03 时 totalScore/grade 保持 null，verdict `INCONCLUSIVE`、confidence `LOW`。
- ［KNOWN｜HIGH］结果页把原始技术事件翻译为“默认 Wi-Fi 网络丢失”，写成“同一测试窗口内共现，支持关联定位，但不能单独证明因果”；没有把共现升级为网络单因归因。
- ［COMPUTED｜HIGH］Room 的类型化结论、`env_event` 与统一信封三者同 run 一致；统一信封 Draft 2020-12 Schema 错误 0，独立 canonical digest 为 `sha256:17a5c5e2b8dc29e269827380fbbe78e2bba3a943cba93dfd7d44b2c0f320e9d6`，JSONL 中该 run 出现 1 次。

## 5. 资源释放

- ［KNOWN｜HIGH］每段真机操作结束均返回华为桌面并强制停止 Codex 包。
- ［KNOWN｜HIGH］最终 Wi-Fi 已恢复开启且连接；`com.aneb.probe` 与 `com.aneb.probe.codex` 均无 PID、Codex 无 Service；前台为 Huawei Launcher。

## 6. 剩余 M4 门槛

- ［KNOWN｜HIGH］正式签名密钥、签名证书指纹和可分发 Release APK 仍是 Product Owner 的仓库外资产；缺失时构建继续 fail closed。
- ［KNOWN｜HIGH］尚需在一个不依赖 ADB 的分发通道上完成“下载 APK → 系统安装 → 首次启动 → 测试 → 导出/分享”的整链验收。
- ［INFERRED｜MED］设置页仍偏向专业用户；公开发布前应补充极短的节点/测试类型说明，但不得用新手引导遮挡 `ANEB_UI` 的主测试视觉。
