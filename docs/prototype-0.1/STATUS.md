# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-08-29**
Program state: **G2 — ANDROID VERTICAL SLICE (ACTIVE)**
Product Owner: Lucas
Program issue: #13

## Executive status

The product direction has been narrowed and approved. A single product baseline, integration branch, issue hierarchy, workstream allocation, frozen G0 specification set and merged G1 core implementation now exist.

G0 passed through PR #23 and merge commit `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`; issue #14 is closed. G1 passed through PR #27 and merge commit `9b968378ab7fbc8ae6c69838f6d165d8377f07c2`; issue #15 is closed. Issue #16 has merged the exact Prototype projector through PR #29 and the real server `done` SSE fixture plus strict Android single-frame decoder through PR #30 at `2d057580a9a4ee0a086a606153399d2c11d1e674`. The Android stream runner, campaign orchestration, persistence and UI remain open. Issue #17 remains in P0 HOLD: capability-reported server identity mismatch has a focused 26-case GREEN candidate, but execution-identity binding, package-side lifecycle and remaining release gates are not yet closed. G2 is active; G3 through G6 remain pending.

## Gate board

| Gate | State | Evidence | Exit condition |
|---|---|---|---|
| G0 — Specification freeze | `PASS` | PR #23; merge `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`; issue #14 closed | frozen specification and contract evidence integrated |
| G1 — Core deterministic contract | `PASS` | PR #27; merge `9b968378ab7fbc8ae6c69838f6d165d8377f07c2`; issue #15 | tested workload/conditions/capability/receipts integrated |
| G2 — Android vertical slice | `IN_PROGRESS` | PR #29; PR #30; issue #16 remains open | Quick + Acceptance campaign and metric gates pass |
| G3 — Fixed Windows package | `PENDING` | issue #17 P0 HOLD; capability-reported identity mismatch focused candidate GREEN but uncommitted; execution-identity binding remains HOLD | self-contained candidate launches from fresh directory |
| G4 — Evidence consistency | `PENDING` | issue #17 P0 HOLD | automated bundle/report verification passes |
| G5 — P40 acceptance | `PENDING` | issue #18 | exact candidate passes fresh-install and negative tests |
| G6 — Product Owner acceptance | `PENDING` | issue #13 | fixed release accepted and tagged |

## Workstream board

| Issue | Conversation | State | Current output | Dependency / blocker |
|---:|---|---|---|---|
| #13 | current PMO conversation | `IN_PROGRESS` | G0/G1 integrated; PR #29/#30 and release P0 work coordinated | G2–G6 pending |
| #14 | `20260710_智能体网络诉求` | `COMPLETE` | issue closed; frozen docs/contracts and machine vectors merged by PR #23 | none |
| #15 | ANEB_GPT core | `COMPLETE` | deterministic core merged by PR #27 at `9b968378ab7fbc8ae6c69838f6d165d8377f07c2` | none |
| #16 | ANEB_GPT Android | `IN_PROGRESS` | exact projector merged by PR #29; real `done` SSE fixture and strict single-frame decoder merged by PR #30 | stream runner, Quick/Acceptance orchestration, persistence, UI and G2 exit evidence pending |
| #17 | release/packaging | `HOLD_P0` | capability-reported identity mismatch has a focused 26-case GREEN candidate; execution-identity TOCTOU and launcher lifecycle remain HOLD; PR #25 remains HOLD and no release commit is admitted yet | launcher lifecycle/remaining P0 gates and #16 app artifact required |
| #18 | `20260801_ANEB_WP2设备运行时` | `WAITING_RC` | selective-backport and P40 protocol prepared | no device action until fixed signed RC |
| #19 | AI应用网络要求测试 | `READY` | non-blocking Prototype 0.2 protocol task prepared | no 0.1 dependency |

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
#14 / G0 closed
  -> #15 / G1 merged
  -> #16 Android campaign (active)
  -> #17 P0 closure + fixed package + verifier/report
  -> #18 P40 acceptance
  -> #13 PO release acceptance
```

## Current primary blocker

`G2` is active. The projector and single-terminal-frame boundary are merged, but
the Android stream runner, campaign orchestration, persistence and UI have not
yet produced G2 exit evidence. In parallel, issue #17 must close its launcher
lifecycle and remaining P0 fail-closed defects before its release skeleton can
leave HOLD or contribute G3/G4 evidence.

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

1. continue issue #16 from merge `2d057580a9a4ee0a086a606153399d2c11d1e674` with one bounded stream-runner vertical before UI/persistence expansion;
2. keep issue #17 and PR #25 in P0 HOLD until package identity, owned-server lifecycle and all recorded fail-closed defects are GREEN on one revised reviewed head;
3. admit the fixed Android artifact into issue #17 only after #16 completes its bounded implementation and review;
4. keep #18 in `WAITING_RC` and do not start device work before a fixed signed candidate exists;
5. append only approved binding decisions and evidence-backed gate changes through PMO.

## Progress update rule

This board changes only from GitHub issue/PR/evidence updates. Chat-only completion claims are not promoted to a gate state.
