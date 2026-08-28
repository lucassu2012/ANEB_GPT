# ANEB Prototype 0.1 — Program Status

- Last PMO update: **2026-08-28**
- Program state: **G0 — SECOND REWORK (HOLD)**
- Product Owner: Lucas
- Program issue: #13

## Executive status

The product direction has been narrowed and approved. A single product baseline, integration branch, issue hierarchy, workstream allocation and complete first-pass specification set now exist.

The Master PMO dispatch has been delivered to issues #14–#18 through their existing Codex tasks, and every active workstream has posted a durable first acknowledgement to its GitHub issue. The legacy strategy task is frozen. The exact legacy `GitHub Mention: Cloud continuation...` task was not found in the active or archived task index, so no task-to-task delivery is claimed for it; its old scope remains frozen by the Prototype 0.1 governance boundary.

Issue #14 has produced two bounded rework commits after the rejected PR #20 head `f23d9ca`. The second exact head, `49fb3e12a156592816f34dfec05cfb1053b56777`, closes the original B/S/U cycle, clock-domain, null-matrix, manifest-oracle and basic evidence-chain defects, but independent review still reproduces failure-state and cross-file evidence-authority survivors. G0 therefore remains unpassed. The Product Owner-approved DN-1A through DN-5A remain the only binding choices; no new decision is needed.

No Prototype 0.1 implementation gate has passed yet. Existing ANEB code remains valuable baseline capability, but it is not counted as Prototype 0.1 completion until it satisfies the frozen end-to-end release contract.

## Gate board

| Gate | State | Evidence | Exit condition |
|---|---|---|---|
| G0 — Specification freeze | `REWORK_HOLD` | [PO approval](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5449398997); [49fb independent HOLD](https://github.com/lucassu2012/ANEB_GPT/issues/14#issuecomment-5451262282) | new exact head rejects the published remaining survivors; decision-log entries; specs merged |
| G1 — Core deterministic contract | `BACKLOG` | issue #15 | tested workload/conditions/capability/receipts |
| G2 — Android vertical slice | `BACKLOG` | issue #16 | Quick + Acceptance campaign and metric gates pass |
| G3 — Fixed Windows package | `BACKLOG` | issue #17 | self-contained candidate launches from fresh directory |
| G4 — Evidence consistency | `BACKLOG` | issue #17 | automated bundle/report verification passes |
| G5 — P40 acceptance | `BACKLOG` | issue #18 | exact candidate passes fresh-install and negative tests |
| G6 — Product Owner acceptance | `BACKLOG` | issue #13 | fixed release accepted and tagged |

## Workstream board

| Issue | Conversation | State | Current output | Dependency / blocker |
|---:|---|---|---|---|
| #13 | `20260803_ANEB开发策略与质量_v2` | `IN_PROGRESS` | dispatch receipt, status PR and [DN-1A–DN-5A approval](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5449398997) recorded | waits for revised G0 head |
| #14 | `20260710_智能体网络诉求` | `IN_PROGRESS_REWORK` | second exact head `49fb3e12` independently reviewed; remaining one-fault repros published | must close failure-state topology and raw/cross-file authority survivors; cannot self-advance G0 |
| #15 | `20260715_开发ANEB系统` | `WAITING_G0` | read-only contract vectors plus provisional `49fb3e12` interface/test delta map complete | waits for #14 contract freeze; implementation forbidden before G0 |
| #16 | `20260714_设计ANEB 手机APP界面` | `WAITING_ON_14_15` | Android conformance vectors plus provisional field-to-layer/fixture map complete | waits for #14 freeze and #15 versioned interface |
| #17 | `20260804_ANEB_试点产品体验与上线缺口` | `HOLD_REBASE_AFTER_G0` | PR #22 preserved as an unmergeable carrier; clean-replay map complete | must replay only applicable mechanical content from an approved G0 head; never merge the rejected `f23d9ca` chain |
| #18 | `20260801_ANEB_WP2设备运行时` | `COMPLETE_PROVISIONAL` | selective reuse table and provisional P40 evidence template complete; `CANDIDATE=NONE`, `DEVICE=NOT_TOUCHED` | recommendations remain provisional; P40 waits for one exact signed RC |
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

`G0` cannot pass because independent review of exact head `49fb3e12a156592816f34dfec05cfb1053b56777` still finds valid instances with mismatched `cancelled`/`ttft_timeout`/`server_rejected`/`incompatible` status and reason topology. Its evidence verifier also accepts a forged single-event campaign id, an unknown event type, a split terminal clock domain, a forged run-record schema version and a coordinated downstream campaign rewrite that disagrees with raw events.

The earlier schedule-byte, capability-order, B/S/U index, complete/eligibility, clock-domain field, partial/null and manifest-oracle defects are closed at `49fb3e12`; the remaining blocker is the narrower failure-state and cross-file raw-authority proof above.

The Product Owner approved option A for all five choices: Android `elapsedRealtimeNanos()` boundaries, arithmetic even-sample median, bare lowercase canonical hash identity, primary plus ordered full null reasons with raw-event verifier authority, and packaging the four existing machine contracts. The [binding receipt](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5449398997) creates no sixth decision or additional scope.

The remaining blocker is proof: the revised 17-file contract set must have one exact commit, a complete change map, regenerated schedule hashes, and passing positive/negative vectors for the published schema, clock, stall, median, null, evidence-chain and release-layout failures.

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

1. keep #15 and #16 waiting until the binding contracts are frozen;
2. have SPEC minimally close only the published `49fb3e12` survivors, publish a new exact commit and rerun focused vectors;
3. keep PR #22 unmerged; use its clean-replay map only after G0 passes, while #18 remains device-blocked until an exact signed RC;
4. append the approved DN-1A–DN-5A decisions to the decision log with their exact evidence links;
5. merge PR #20 to `product/prototype-0.1` only after the new exact head passes #14 re-review;
6. advance G1–G6 only from issue-bound evidence and exact candidate receipts.

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
