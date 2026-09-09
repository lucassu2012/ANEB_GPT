# ANEB 首个试点准备包

日期：2026-09-09。协调：[Issue #61](https://github.com/lucassu2012/ANEB_GPT/issues/61)。

［KNOWN｜HIGH］Prototype 0.1 已发布，#13–#18 已关闭。本目录是发布后的试点文档，不是新 APK、评分标准或第二产品线。真实客户试点尚未执行；客户、账号、购买意愿和结果均不得代填。

## 本轮实际交付

1. [试点范围与交付卡](PILOT_BRIEF.md)：使用原包能够交付什么、不能承诺什么、服务计价单位建议。
2. [操作与反馈表](OPERATOR_CHECKLIST.md)：第二位操作者按图索骥执行，保留未执行/失败/缺失，不要求理解源码。
3. [现有 #19 人工真实 App 协议草案](https://github.com/lucassu2012/ANEB_GPT/issues/19#issuecomment-5593694794)：独立待审，不进入合成 RPI，不授权本轮执行 App。

发布入口：[ANEB Prototype 0.1](https://github.com/lucassu2012/ANEB_GPT/releases/tag/prototype-0.1)。制品源码 `03675a93b9b39c122680204256814740f7f6e178`，ZIP SHA256 `ec1bd2c406ce2610b915396cba0de614281df58c81a16800ea8f34158545f3ca`。不修改已发布 ZIP、标签、回执、workload、RPI-0.1、证据 schema、claim 或 G2–G5 标准。

## 顺序与停止条件

［INFERRED｜MED］先验证现有产品能否让一名独立用户得到可理解、可复核的报告，再决定最小产品改动。当前不投入订阅/支付 UI、云端、自动真实 App 测试或新评分。

| 阶段 | 当前状态 | 可核验出口 |
|---|---|---|
| N0 范围 | 已开放准备工作 | 单一原包、单一试点问题、明确不承诺项 |
| N1 交付文档 | 试点交付卡/操作表已完成一次独立读穿；#19 仍待审 | 本目录与 #61 实际复核记录；不等于客户已完成 |
| N2 首位用户 | NOT_RUN / 缺真实参与方 | 自愿参与者、场地/设备授权、数据交接约定；一次有记录的操作与反馈 |
| N3 产品改进 | 未开放实现 | 真实阻塞证据、最小修复、测试与必要时新固定候选，不修审计完备性问题 |

［INFERRED｜MED］建议以参与方确认之日为 D1，最多七个工作日完成一轮：D1 确认问题和边界；D2–3 指导一次运行；D4–5 第二位操作者复核；D6 报告用途与购买障碍访谈；D7 交付/拒绝记录及下一步决定。没有客户不虚构日期或启动记录；截止仍无参与方就报告未验证，不自动扩大功能。

外部执行前最少需要：一名真实自愿参与者及独立复核者、获准使用的 Windows/P40/局域网、报告接收人与数据保管约定。没有这些信息不联系陌生客户、不使用账号、不录屏、不操作设备或云端。方法设计和文档准备继续进行。

只有范围、评分、证据 schema、claim、release gate 或明确的对外服务承诺需要 PO 决策；日常编排由 PMO 执行。旧 v1/cloud/Relay、#25 和堆叠分支保持冻结；CORE/APP 只在明确产品缺口出现后接单。

## 本轮复核记录

［KNOWN｜HIGH］[Pilot 文案](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5593705718)与 [WP2 操作清单](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5593702104)已由 PMO 整合。一次独立读穿后补齐 Acceptance、每个结果 Export ZIP 的保存确认、操作员与参与者分工，以及 P40 执行前后清场规则。仅改文档，未运行设备。

[SPEC 对 #19 的三项文字复核](https://github.com/lucassu2012/ANEB_GPT/issues/61#issuecomment-5593717010)分别针对完成时间的起止、不可观察区间、SEND/正文锚点。#19 的 App 选择和观察参数没有在本交付中冻结；草案与实测必须分开。
