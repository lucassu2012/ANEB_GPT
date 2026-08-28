# 09 — Actual Codex Task Routing

Status: **Active routing correction based on Product Owner's Codex task list, 2026-08-28**

## 1. Correction

The earlier governance document used semantic role names such as “Agentic网络体验标准 conversation” and “ANEB_GPT Android conversation”. Those were workstream labels, not guaranteed literal task names in the Product Owner's Codex sidebar.

The actual visible Codex tasks are mapped below. This table supersedes the conversational labels in `07_PROGRAM_GOVERNANCE.md`; GitHub issues and specs remain the binding contracts.

## 2. Actual task map

| Actual Codex task | Prototype role | GitHub issue | State | Instruction |
|---|---|---:|---|---|
| `20260803_ANEB开发策略与质量_v2` | **Master PMO / orchestrator** | #13 | IN_PROGRESS | Own G0–G6 board, send assignments to the other named tasks, reconcile issue/PR evidence, escalate Product Owner decisions only. Do not build a parallel product branch. |
| `20260710_智能体网络诉求` | **SPEC and measurement contract** | #14 | IN_PROGRESS | Review/freeze workload, metric clocks, stall semantics, `rpi-0.1`, evidence null behavior and claim boundary from the exact rejected head and rework commit. |
| `20260715_开发ANEB系统` | **Go Core / server implementation** | #15 | BLOCKED | Implement deterministic stream workload, three application-layer conditions, capability contract, schedule hashes and terminal receipts after G0. |
| `20260714_设计ANEB手机APP界面` | **Android Prototype Mode** | #16 | BLOCKED | Implement native Compose campaign flow, metrics, RPI, evidence capture and result/error UI after #14/#15 contracts. |
| `20260804_ANEB_试点产品体验与上线缺口` | **Release/package/report** | #17 | READY | Build Windows launcher, package verifier, canonical evidence finalizer, offline report and five-step guide after the fixed contracts. |
| `20260801_ANEB_WP2设备运行时` | **ANEB_CC selective reuse + P40 QA** | #18 | READY | Remain idle on release tree until a fixed RC arrives; inspect only small backport candidates and later run fresh P40 acceptance. |
| `AI应用网络要求测试` | **Prototype 0.2 real-App manual protocol** | #19 | READY | Produce the bounded manual Doubao/Kimi protocol; it does not change Prototype 0.1 code or gates. |
| `20260801_ANEB开发策略与质量_v1` | **Superseded historical coordinator** | none | REJECTED | Read-only source. Do not issue new implementation instructions or make release decisions. Forward any unique unresolved evidence to the v2 PMO task. |
| `GitHub Mention: Cloud continuation 1: Profile...` | **Legacy cloud task linked to old issue/PR line** | historical #1/#2 | REJECTED | Do not continue the old scope unless the v2 PMO explicitly identifies a required, compatible backport. |

## 3. Master-task delegation instruction

The Product Owner should send the following once to `20260803_ANEB开发策略与质量_v2`. The master task should use Codex task-to-task delegation where available and GitHub issues as the durable control plane.

```text
[PO AUTHORIZATION — ANEB PROTOTYPE 0.1 PROGRAM CONTROL]

你是 ANEB Prototype 0.1 的唯一 Master PMO，对应 ANEB_GPT issue #13。
读取 PR #20 的 docs/prototype-0.1/、contracts/prototype-0.1/ 和 09_CODEX_TASK_ROUTING.md。GitHub issue/PR/commit/evidence 是跨任务事实源。

立即向以下已有 Codex 任务发送分工，不要求 Product Owner 逐个复制：

1. 20260710_智能体网络诉求 -> issue #14 SPEC review；
2. 20260715_开发ANEB系统 -> issue #15 CORE，G0 前只能做差距/测试计划；
3. 20260714_设计ANEB手机APP界面 -> issue #16 APP，等待 #14/#15 合同；
4. 20260804_ANEB_试点产品体验与上线缺口 -> issue #17 RELEASE，可先做 verifier/launcher/report skeleton；
5. 20260801_ANEB_WP2设备运行时 -> issue #18 QA/REUSE，先做 selective backport 表，固定 RC 到达前保持 release tree idle；
6. 20260801_ANEB开发策略与质量_v1 和 GitHub Mention 旧任务 -> REJECTED，只读，不再并行发号施令。

每个任务必须按其 GitHub issue 的验收标准工作，并回报：
STATE / COMPLETED / EVIDENCE / BLOCKER / NEXT / DECISION_NEEDED；
EVIDENCE 必须包含本次审查或实现的精确 head commit SHA；代码任务另加
COMMIT_OR_PR / TESTS / REGRESSION；设备任务另加 CANDIDATE / DEVICE / VERDICT。

你负责：
- 发送和追踪这些任务；
- 把各任务回执写入对应 issue；
- 更新 docs/prototype-0.1/STATUS.md；
- 阻止 scope creep、第二产品线、旧堆叠分支和无证据 DONE；
- 只有涉及产品范围、指标/评分、证据 schema、claim 或 release gate 时才向 Product Owner 提交 2–3 个选项和推荐。

当前第一动作：派发 #14 SPEC 审查、#17 skeleton、#18 selective reuse review；#15/#16 标记等待上游合同。完成派发后在 #13 汇报实际发送到哪些任务，以及每个任务的首次确认状态。
```

## 4. Communication boundary

Codex tasks can be orchestrated inside the Codex product when the relevant task-control feature and permissions are available. This GitHub document does not itself send messages. It provides the exact task identifiers and durable contract that the master Codex task must use.

A task-to-task message is a dispatch event, not completion evidence. Completion still requires the linked GitHub artifact.
