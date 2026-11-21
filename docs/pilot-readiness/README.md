# ANEB 试点准备与交付中心（Pilot v0.2 / Prototype 0.1）

协调：[Issue #61](https://github.com/lucassu2012/ANEB_GPT/issues/61) · 基线报告：[Issue #19](https://github.com/lucassu2012/ANEB_GPT/issues/19)。

［KNOWN｜HIGH］**执行状态**：`Pilot v0.2` 文档、中文上手卡、离线校验工具与外部反馈模板已就绪；**外部真实试点招募与现场执行仍为 `NOT_RUN`**。首位 Prototype 0.1 试用者 P01 的 Quick 3 / Acceptance 9 反馈仅用于记录历史基线，不等于 Pilot v0.2 外部客户验证或独立上手通过，也不得代填购买意愿。

## Pilot v0.2 交付与反馈文档

1. [Pilot v0.2 中文快速上手手册（《5 分钟上手卡》）](PILOT_V02_QUICKSTART_ZH.md)：覆盖收包离线校验（`verify-pilot-v0.2-kit.ps1`）、双正式签名 APK（`probe-release.apk` 公网 `miw2` HTTPS 与 `probe-prototypeRelease.apk` 本地局域网 Prototype）安装切换、Stage 2 UI 流程、Windows 便携启动器操作、结果边界声明与退出清场。
2. [Pilot v0.2 外部试用反馈表（Markdown）](PILOT_V02_FEEDBACK_TEMPLATE.md) & [机器可读 JSON 模板（`aneb-pilot-v0.2-feedback-0.1`）](PILOT_V02_FEEDBACK_TEMPLATE.json)：供操作员、独立复核者与外部试用者按步骤填写状态、耗时、原话反馈与数据保管确认。
3. [ANEB AI 网络体验基线综合报告 v0.2](../research/ANEB_AI_NETWORK_EXPERIENCE_BASELINE_REPORT_V02.md)：整合 Douyin `410101`（10 卡 / 20 相位）、Doubao `com.larus.nova` / KimiChat、外场 `W1` Wi-Fi vs `C1` 5G NR 配对、Profile 4 语音相关性及 Stage 0–3 工程基线。

## 保留的历史 Prototype 0.1 / N3 文档（原样保留以兼容链接）

1. [试点范围与交付卡](PILOT_BRIEF.md)：使用 Prototype 0.1 原包能够交付什么、不能承诺什么、服务计价单位建议。
2. [操作与反馈表](OPERATOR_CHECKLIST.md)：Prototype 0.1 第二位操作者执行清单（保留 `NOT_RUN` 与 `AGENTS.md` 现场清场要求）。
3. [邀请与五分钟筛选稿](INVITATION_SCREENING.md)：可供获准联系人使用；尚未发送，五分钟不包含实际安装/运行/复核。
4. [一次候选验收清单](N3_ACCEPTANCE.md)：Prototype 0.1 中文流程与 Windows 引导验收条目。
5. [#19 人工真实 App 方法候选](../real-app-manual/METHOD_CANDIDATE.md)：Kimi / 既有未改网络 C0×3；一次文字复核已整合，未执行真实 App，不进入合成 RPI，也不成为本试点服务承诺。

Prototype 0.1 发布入口：[ANEB Prototype 0.1](https://github.com/lucassu2012/ANEB_GPT/releases/tag/prototype-0.1)。制品源码 `03675a93b9b39c122680204256814740f7f6e178`，ZIP SHA256 `ec1bd2c406ce2610b915396cba0de614281df58c81a16800ea8f34158545f3ca`。不修改已发布 Prototype 0.1 ZIP、标签、回执、workload、RPI-0.1、证据 schema、claim 或 G2–G5 标准。

## 顺序与停止条件

［INFERRED｜MED］先验证现有产品能否让一名独立用户得到可理解、可复核的报告，再决定最小产品改动。当前不投入订阅/支付 UI、云端、自动真实 App 测试或新评分。

| 阶段 | 当前状态 | 可核验出口 |
|---|---|---|
| N0 范围 | 已开放准备工作 | 单一原包、单一试点问题、明确不承诺项 |
| N1 交付文档 | 交付卡/操作表/未发送邀请稿及 #19 方法候选完成一次独立读穿 | 本目录与 #61/#19 实际复核记录；设计完成不等于客户已完成或方法效度已验证 |
| N2 首位用户 | 运行/导出核实、Saved 重开由用户确认；现场清理完成 | [P01 收据](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5604555174)：Quick 3/3、Acceptance 9/9，原节点证据各自验证通过；仍有连接呈现和理解问题 |
| N3 产品改进 | 中文流程与启动指导实现/验证中，未发布新包 | [一次候选验收清单](N3_ACCEPTANCE.md)；不把旧版本的设备 PASS 或代码测试外推成新用户体验 PASS |

［INFERRED｜MED］建议以参与方确认之日为 D1，最多七个工作日完成一轮：D1 确认问题和边界；D2–3 指导一次运行；D4–5 第二位操作者复核；D6 报告用途与购买障碍访谈；D7 交付/拒绝记录及下一步决定。没有客户不虚构日期或启动记录；截止仍无参与方就报告未验证，不自动扩大功能。

外部执行前最少需要：一名真实自愿参与者及独立复核者、获准使用的 Windows/P40/局域网、报告接收人与数据保管约定。没有这些信息不联系陌生客户、不使用账号、不录屏、不操作设备或云端。方法设计和文档准备继续进行。

只有范围、评分、证据 schema、claim、release gate 或明确的对外服务承诺需要 PO 决策；日常编排由 PMO 执行。旧 v1/cloud/Relay、#25 和堆叠分支保持冻结；CORE/APP 只在明确产品缺口出现后接单。

## 本轮复核记录

［KNOWN｜HIGH］[Pilot 文案](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5593705718)与 [WP2 操作清单](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5593702104)已由 PMO 整合。一次独立读穿后补齐 Acceptance、每个结果 Export ZIP 的保存确认、操作员与参与者分工，以及 P40 执行前后清场规则。仅改文档，未运行设备。

[初次 SPEC 意见](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5593717010)针对完成时间、不可观察区间、SEND/正文锚点。[本轮自主推进](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5593988858)由 PMO 定下 Kimi / C0×3 及人工 2 s/3 s 候选操作约定；[作者修订稿](https://github.com/lucassu2012/ANEB_GPT/issues/19#issuecomment-5593972074)已合入[唯一一次 SPEC 读穿](https://github.com/lucassu2012/ANEB_GPT/issues/19#issuecomment-5594008293)，并收录为独立方法文档。这不是修改冻结标准或证明网络阈值；真实运行仍 NOT_RUN。

[邀请原稿](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5593977093)经[WP2 普通人读穿](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5594008481)，明确筛选与实际运行另约、授权且可互访的局域网、数据接收和保管边界。邀请未发送，没有代填客户或购买意愿。
