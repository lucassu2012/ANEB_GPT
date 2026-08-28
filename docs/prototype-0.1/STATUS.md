# ANEB Prototype 0.1 — Program Status

- Last PMO update: **2026-08-28**
- Program state: **G0 — BLOCKED (SPEC REJECTED)**
- Product Owner: Lucas
- Program issue: #13

## Executive status

The product direction has been narrowed and approved. A single product baseline, integration branch, issue hierarchy, workstream allocation and complete first-pass specification set now exist.

The Master PMO dispatch has been delivered to issues #14–#18 through their existing Codex tasks, and every active workstream has posted a durable first acknowledgement to its GitHub issue. The legacy strategy task is frozen. The exact legacy `GitHub Mention: Cloud continuation...` task was not found in the active or archived task index, so no task-to-task delivery is claimed for it; its old scope remains frozen by the Prototype 0.1 governance boundary.

Issue #14 has completed its independent review of frozen PR #20 head `f23d9ca`. The overall verdict is `REJECT`: three files are approved, six require changes and eight are rejected. G0 is blocked pending five Product Owner decisions and a new exact review head with positive and negative contract evidence.

No Prototype 0.1 implementation gate has passed yet. Existing ANEB code remains valuable baseline capability, but it is not counted as Prototype 0.1 completion until it satisfies the frozen end-to-end release contract.

## Gate board

| Gate | State | Evidence | Exit condition |
|---|---|---|---|
| G0 — Specification freeze | `BLOCKED` | [#14 final review](https://github.com/lucassu2012/ANEB_GPT/issues/14#issuecomment-5447769967); PR #20 at `f23d9ca` | PO decisions DN-1–DN-5, revised exact head, passing positive/negative review, decision-log entries, specs merged |
| G1 — Core deterministic contract | `BACKLOG` | issue #15 | tested workload/conditions/capability/receipts |
| G2 — Android vertical slice | `BACKLOG` | issue #16 | Quick + Acceptance campaign and metric gates pass |
| G3 — Fixed Windows package | `BACKLOG` | issue #17 | self-contained candidate launches from fresh directory |
| G4 — Evidence consistency | `BACKLOG` | issue #17 | automated bundle/report verification passes |
| G5 — P40 acceptance | `BACKLOG` | issue #18 | exact candidate passes fresh-install and negative tests |
| G6 — Product Owner acceptance | `BACKLOG` | issue #13 | fixed release accepted and tagged |

## Workstream board

| Issue | Conversation | State | Current output | Dependency / blocker |
|---:|---|---|---|---|
| #13 | `20260803_ANEB开发策略与质量_v2` | `IN_PROGRESS` | dispatch receipt, status PR and [PO decision packet](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5447786590) recorded | waits for DN-1–DN-5 |
| #14 | `20260710_智能体网络诉求` | `BLOCKED` | 17-file review complete: 3 approve / 6 changes / 8 reject | waits for PO decisions and revised exact head |
| #15 | `20260715_开发ANEB系统` | `WAITING_G0` | read-only gap analysis and six-part test plan complete | waits for #14 contract freeze; implementation forbidden before G0 |
| #16 | `20260714_设计ANEB 手机APP界面` | `WAITING_ON_14_15` | dispatch acknowledged; read-only gap/vector mapping only | waits for #14 freeze and #15 versioned interface |
| #17 | `20260804_ANEB_试点产品体验与上线缺口` | `IN_PROGRESS_SKELETON` | offline verifier/finalizer/launcher/report skeleton started | final ZIP waits for fixed #15 server and #16 APK |
| #18 | `20260801_ANEB_WP2设备运行时` | `COMPLETE_PROVISIONAL` | [selective BACKPORT/REJECT table published](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5447816886); `CANDIDATE=NONE`, `DEVICE=NOT_TOUCHED` | recommendations remain provisional; P40 waits for new G0 head and one signed RC |
| #19 | Prototype 0.2 real-App protocol | `FROZEN_POST_0_1` | issue remains non-blocking research only | no work may consume Prototype 0.1 critical-path capacity |

## Locked product decisions

- One product repository: `ANEB_GPT`.
- One integration branch: `product/prototype-0.1`.
- `ANEB_CC` is reference/QA only.
- Native Android app remains the primary UI.
- Existing Go node is extended; no second controller stack.
- One deterministic streaming workload and three conditions only.
- Synthetic impairment occurs at the application layer.
- Normal connection is local LAN with manual node URL entry.
- QR pairing is optional and non-blocking.
- Runtime does not require source/build tools, administrator rights, hotspot, netem, PCAP or cloud services.
- Third-party App testing belongs to Prototype 0.2 and does not block 0.1.

## Critical path

```text
#14 spec approval
  -> #15 server contract
  -> #16 Android campaign
  -> #17 fixed package + verifier/report
  -> #18 P40 acceptance
  -> #13 PO release acceptance
```

## Current primary blocker

`G0` cannot pass because issue #14 rejected the current exact review head. The three schedule hashes recompute correctly, but the contracts do not yet force independent implementations to produce the same capability set, run eligibility, evidence chain or RPI.

The deterministic defects include incomplete canonical schedule-byte rules, capability-condition uniqueness, run-mode/index and score-eligibility constraints, clock/hash/null semantics, raw-event-to-report recomputation, and a release layout that names a nonexistent contract file.

Five remaining choices alter measurement/evidence schema, scoring or the release gate and therefore require Product Owner decisions: Android monotonic clock boundaries, even-sample median, canonical hash identity, RPI null-reason representation/verifier authority, and the packaged contract set. The [decision packet](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5447786590) contains 2–3 options, a recommendation and schedule impact for each.

There is no current hardware, administrator-rights, cloud-node, PCAP or third-party-App blocker for Prototype 0.1.

## Scope risk watchlist

| Risk | Control |
|---|---|
| Different conversations re-expand the full ANEB standard | issue-specific scope and change-control rule |
| ANEB_CC becomes a competing product again | #18 limits it to selective reuse and acceptance |
| Synthetic conditions are mistaken for packet loss/RAN | mandatory claim/evidence labels and forbidden wording |
| Implementation starts from unrelated long-running branches | all Prototype PRs target the clean integration branch |
| Report disagrees with raw data | canonical server finalizer plus automated recomputation/verifier |
| Old APK/data contaminates P40 evidence | fresh-install/fresh-result acceptance procedure |
| Runtime depends on developer machine | immutable ZIP and fresh Windows directory gate |

## Next PMO actions

1. obtain Product Owner decisions DN-1–DN-5; PMO recommends A/A/A/A/A;
2. keep #15 and #16 waiting until the binding contracts are frozen;
3. have SPEC revise only the 17 binding files, publish an exact change map and rerun the required positive/negative vectors;
4. track #17 skeleton output and #18 selective-reuse evidence without allowing fabricated artifacts, device work or a second product line;
5. append only approved scope/score/evidence/claim/governance decisions to the decision log;
6. merge PR #20 to `product/prototype-0.1` only after the new exact head passes #14 re-review;
7. advance G1–G6 only from issue-bound evidence and exact candidate receipts.

## Dispatch receipt — 2026-08-28

| Issue | Task-to-task delivery | First durable acknowledgement |
|---:|---|---|
| #14 | accepted | [SPEC IN_PROGRESS](https://github.com/lucassu2012/ANEB_GPT/issues/14#issuecomment-5447694146) |
| #15 | accepted | [CORE WAITING_G0](https://github.com/lucassu2012/ANEB_GPT/issues/15#issuecomment-5447727252) |
| #16 | accepted | [APP WAITING ON #14/#15](https://github.com/lucassu2012/ANEB_GPT/issues/16#issuecomment-5447702497) |
| #17 | accepted | [RELEASE skeleton started](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5447705827) |
| #18 | accepted | [QA/REUSE read-only review started](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5447705802) |

The authoritative PMO dispatch summary is [issue #13 comment 5447736109](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5447736109).

## Progress update rule

This board changes only from GitHub issue/PR/evidence updates. Chat-only completion claims are not promoted to a gate state.
