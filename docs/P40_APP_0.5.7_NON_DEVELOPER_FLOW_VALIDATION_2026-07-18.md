# P40 Pro · ANEB App 0.5.7 非开发者开测路径验证

> 日期：2026-07-18（Asia/Shanghai）
> 设备：Huawei P40 Pro（Android 公开 API，1200×2640）
> 包：`com.aneb.probe.codex`，`0.5.7-codex`，versionCode 39
> APK SHA-256：`D276D7C52F3549E52194B9E90C5C45EBB8969FD441FB09DA9154B7302A6BFF33`

## 1. 反方观点与本轮边界

- ［KNOWN｜HIGH］这不是正式发布验收：安装的是 Debug 包，且使用 `adb install -r`，没有仓库外正式签名密钥，也没有验证应用商店分发。
- ［KNOWN｜HIGH］这不是全新设备清数据首装：为保留既有测量证据，使用系统权限撤销来进入与首装相同的“无线权限未授予”分支。
- ［KNOWN｜HIGH］本轮只证明 M4 的“开测前自救 + 正常测量不回归”切片；历史导出、分享与统一信封已有 0.5.3–0.5.6 独立证据，本轮不重复冒充新证据。

## 2. 变更

1. 所有四种 App 测试类型在无线证据不完整时，开测前统一说明电话/精确位置权限用途；允许取消、授权或低置信继续。
2. 权限只用于读取当前数据卡、小区与信号；对话明确说明不读取通话、联系人或 IMSI。拒绝无线权限不阻断业务测量，只把无线归因标为证据不足。
3. 开测前统一校验活动网络和节点根地址。节点只接受 HTTP(S) 根地址；正式包禁止 HTTP，并拒绝账号密码、查询参数、片段、API 子路径和非法端口。
4. 设置页沿用 `ANEB_UI` 的“高级/自定义服务器地址”位置，在原输入框内显示可操作错误，不增加新的视觉体系。

## 3. 自动化验证

- ［COMPUTED｜HIGH］Android JVM：531 tests，0 failures，0 errors，0 skipped。
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

## 5. 资源释放

- ［KNOWN｜HIGH］每段真机操作结束均返回华为桌面并强制停止 Codex 包。
- ［KNOWN｜HIGH］最终 `com.aneb.probe` 与 `com.aneb.probe.codex` 均无 PID、无 Service；前台为 Huawei Launcher。

## 6. 剩余 M4 门槛

- ［KNOWN｜HIGH］正式签名密钥、签名证书指纹和可分发 Release APK 仍是 Product Owner 的仓库外资产；缺失时构建继续 fail closed。
- ［KNOWN｜HIGH］尚需在一个不依赖 ADB 的分发通道上完成“下载 APK → 系统安装 → 首次启动 → 测试 → 导出/分享”的整链验收。
- ［INFERRED｜MED］设置页仍偏向专业用户；公开发布前应补充极短的节点/测试类型说明，但不得用新手引导遮挡 `ANEB_UI` 的主测试视觉。
