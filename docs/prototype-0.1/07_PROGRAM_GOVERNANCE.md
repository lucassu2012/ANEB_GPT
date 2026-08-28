# 07 — Program Governance and Conversation Routing

Status: **Active (Product Owner approved 2026-08-28)**
Program issue: #13

## 1. Control model

GitHub is the cross-conversation control plane because separate ChatGPT conversations cannot reliably see one another's uncommitted chat state.

A workstream update is considered visible to the program only when at least one of these exists:

- issue comment with the required status template;
- commit or pull request linked to the issue;
- versioned document/evidence path linked from the issue.

A claim such as “done” in one conversation is not a program completion signal without the corresponding GitHub evidence.

## 2. Conversation allocation

| Existing ANEB conversation | Binding assignment | Issue | May change code? | Blocks Prototype 0.1? |
|---|---|---:|---:|---:|
| `20260803_ANEB开发策略与质量_v2` | PMO, scope control, dependency management, release integration and PO decisions | #13; coordinates #17 | only coordination/spec/release integration as needed | yes |
| `20260710_智能体网络诉求` | review/freeze workload, metric, score and claim contracts | #14 | documentation/tests only unless explicitly routed | yes, G0 only |
| `20260715_开发ANEB系统` | deterministic workload, condition engine, capability and receipts | #15 | yes, isolated branch | yes |
| `20260714_设计ANEB手机APP界面` | Prototype Mode, campaign runner, metrics, local evidence and result UI | #16 | yes, isolated branch | yes |
| `20260804_ANEB_试点产品体验与上线缺口` | Windows launcher, fixed package, finalizer, report and verifier | #17 | yes, isolated branch | yes |
| `20260801_ANEB_WP2设备运行时` | selective reuse review plus P40 acceptance; no competing product development | #18 | only approved small backports/fixes | yes, G5 only |
| `AI应用网络要求测试` | manual Doubao/Kimi protocol for Prototype 0.2 | #19 | no Prototype 0.1 code | no |

If only one ANEB_GPT implementation conversation exists, it executes #15 then #16 as separate branches/PRs. It must not combine them into an unreviewable mega-change.

## 3. Dispatch packets

Paste the matching packet once at the start of each existing conversation. The issue/spec links are the actual contract.

### 3.1 Agentic network-experience standards conversation

```text
你现在负责 ANEB Prototype 0.1 的 SPEC 工作流，对应 ANEB_GPT issue #14。
先读取 docs/prototype-0.1/ 全部规格，重点审查 02_WORKLOAD_IMPAIRMENT_SPEC.md、03_METRICS_SCORING_SPEC.md、04_EVIDENCE_REPORT_SPEC.md。
你的任务不是扩展完整标准，而是冻结 0.1 可实现合同：逐项给出 APPROVE / APPROVE_WITH_CHANGES / REJECT，修复单位、时钟边界、null 语义、RPI-0.1 和 claim 边界中的矛盾。禁止加入真实 App 自动化、720 次矩阵或行业阈值。
所有结论必须提交到 issue #14，并按 STATE / COMPLETED / EVIDENCE / BLOCKER / NEXT / DECISION_NEEDED 汇报。
```

### 3.2 ANEB_GPT server/core conversation

```text
你现在负责 ANEB Prototype 0.1 的 CORE 工作流，对应 issue #15。
基线只能是 ANEB_GPT main；目标分支 prototype-0.1/core-issue-15，PR 目标 product/prototype-0.1。
严格实现 docs/prototype-0.1/02_WORKLOAD_IMPAIRMENT_SPEC.md：一个确定性 workload、Baseline/Slow/Unstable 三种应用层条件、capability、schedule hash、terminal receipt 和测试。禁止 netem、热点、PCAP、第三方 LLM API、AQS 修改或第二套 controller。
先输出实施差距清单，再写测试和代码。每个完成声明必须附 commit/PR 和测试证据，并更新 issue #15。
```

### 3.3 ANEB_GPT Android/app conversation

```text
你现在负责 ANEB Prototype 0.1 的 APP 工作流，对应 issue #16。
目标分支 prototype-0.1/app-issue-16，PR 目标 product/prototype-0.1；依赖 #14/#15 的冻结合同。
复用现有 Kotlin/Compose/Room/前台服务，只新增隔离的 Prototype Mode：节点合同校验、Quick 3-run、Acceptance 9-run、单调时钟指标、stall 检测、RPI-0.1、取消/后台恢复、结果与证据导出。手动 URL 必须可用，二维码不阻塞。禁止修改 AQS、真实 API 探针或第三方 App 自动化。
先补 metric/score/null 测试，再实现 UI 与端到端流程。所有进展更新 issue #16。
```

### 3.4 Packaging/release conversation

```text
你现在负责 ANEB Prototype 0.1 的 RELEASE 工作流，对应 issue #17。
目标分支 prototype-0.1/release-issue-17，PR 目标 product/prototype-0.1。
按 04_EVIDENCE_REPORT_SPEC.md、05_UX_ERROR_SPEC.md、06_RELEASE_ACCEPTANCE_SPEC.md 交付固定 Windows ZIP：START_ANEB.bat、aneb-server.exe、APK、合同、离线 report、manifest/verifier 和五步 README。运行时不能依赖 Python/Node/Gradle/Go/ADB/管理员权限，也不能出现本机绝对路径。
先实现 verifier 和失败路径，再完成打包。每个候选必须记录完整 hash，并更新 issue #17。
```

### 3.5 ANEB_CC conversation

```text
你现在负责 ANEB Prototype 0.1 的 QA/REUSE 工作流，对应 ANEB_GPT issue #18。
ANEB_CC 从现在起不是竞争产品线。先形成小型 backport 表，只审查 APK/build identity、preflight、device diagnostics、atomic evidence、manifest hash 和友好错误分类；逐项 BACKPORT 或 REJECT，禁止整体合并、submodule 或引入第二套评分/运行时。
待 #15/#16/#17 给出固定候选后，在 P40 Pro 做 fresh-install/fresh-run 验收，执行 Quick、9-run Acceptance 和负例，输出精确 hash、日志、截图、manifest 验证及 PASS/FAIL/BLOCKED_EXTERNAL。所有结论更新 issue #18。
```

### 3.6 Real-App requirements conversation

```text
你现在负责 ANEB Prototype 0.2 的 REAL-APP 预研，对应 issue #19；它不阻塞 0.1。
从豆包和 Kimi 中选择一个首测 App，只设计人工可复现协议：一个固定流式文本任务、最多三种条件、每种三次、屏幕录像和人工事件标注、TTFR/TTFC/可见 stall/成功率、不确定度和结果卡。禁止 UI 自动化、Accessibility、VPN 抓流、TLS 解密、凭据收集和 720 次矩阵。
输出可由第二个人独立执行的一页协议，并更新 issue #19。
```

## 4. Workstream state machine

Allowed states:

- `BACKLOG` — accepted but not ready due to dependencies;
- `READY` — inputs frozen and work may start;
- `IN_PROGRESS` — concrete work and evidence exist;
- `BLOCKED` — one explicit blocker prevents the next acceptance step;
- `REVIEW` — implementation complete; awaiting independent validation;
- `DONE` — issue acceptance criteria verified;
- `REJECTED` — work is outside scope or superseded.

`DONE` is not equivalent to merged/released unless the issue acceptance criteria explicitly include merge or release.

## 5. Mandatory status template

Every meaningful checkpoint uses:

```text
STATE: BACKLOG | READY | IN_PROGRESS | BLOCKED | REVIEW | DONE | REJECTED
COMPLETED:
- concrete artifact or test
EVIDENCE:
- issue/commit/PR/path/hash
BLOCKER:
- NONE, or exactly one primary blocker
NEXT:
- next verifiable action
DECISION_NEEDED:
- NONE, or 2–3 options + recommendation + scope/schedule impact
```

Code work additionally includes:

```text
TESTS:
- command and exact pass/fail counts
REGRESSION:
- existing gates run and result
COMMIT_OR_PR:
- exact branch and commit SHA, or PR URL and head SHA
```

Device work additionally includes:

```text
DEVICE:
- model / OS / transport mode
CANDIDATE:
- APK/server/release hashes
VERDICT:
- PASS | FAIL | BLOCKED_EXTERNAL
```

## 6. PMO responsibilities

The current program conversation owns:

- scope and dependency board;
- issue state reconciliation;
- conflict detection between specs and implementation;
- prevention of duplicate product lines;
- Product Owner decision packets;
- G0–G6 gate status;
- final release candidate recommendation.

It does not accept “mostly done” as PASS. It may mark optional work deferred without asking the Product Owner when the binding product outcome is unchanged.

## 7. Decision protocol

A workstream escalates only decisions that change:

- must-have user outcome;
- metric or score semantics;
- evidence schema;
- claim scope/disclosure;
- runtime prerequisite;
- release gate;
- repository/product baseline.

A decision request contains:

1. problem;
2. option A;
3. option B;
4. optional option C;
5. recommendation;
6. impact on Prototype 0.1;
7. whether current work can continue safely.

Implementation stops only at the affected boundary; unrelated work continues.

## 8. Change control

No workstream may add these to Prototype 0.1 without a new Product Owner decision:

- real third-party App execution;
- new workload family;
- new condition beyond the three frozen ones;
- IP-layer impairment;
- industry threshold or letter grade;
- cloud account/backend;
- second Android app/controller/repository;
- commercial features.

Bug fixes, test coverage, clearer errors and implementation simplification that preserve contracts do not require a scope decision.

## 9. Completion and integration rules

A workstream is DONE only when:

- acceptance criteria are satisfied;
- exact artifacts and validation are linked;
- no P0 defect is open;
- downstream interface is documented;
- the PR targets `product/prototype-0.1` from a branch based on the accepted integration history;
- unrelated long-running branch history is absent.

Final integration to `main` occurs only after G5 and Product Owner G6 acceptance.

## 10. Progress review

`docs/prototype-0.1/STATUS.md` is the human-readable board. GitHub issues/PRs are authoritative for details. The PMO updates the board whenever it reviews a new workstream status, candidate, blocker or decision.
