# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-08-29**
Program state: **G2 — ANDROID VERTICAL SLICE (ACTIVE)**
Product Owner: Lucas
Program issue: #13

## Executive status

The product direction has been narrowed and approved. A single product baseline, integration branch, issue hierarchy, workstream allocation, frozen G0 specification set and merged G1 core implementation now exist.

G0 passed through PR #23 and merge commit `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`; the narrow PR #26 erratum merged at `f30829f44026f79ab2077d0c355501034156acae`, and issue #14 is closed. G1 passed through PR #27 and merge commit `9b968378ab7fbc8ae6c69838f6d165d8377f07c2`; issue #15 is closed. Issue #16 has merged the exact Prototype projector through PR #29, the real server `done` SSE fixture plus strict Android single-frame decoder through PR #30, and the bounded single-run stream adapter through PR #32 at `837839417b9b1b3be8b4590c313dc99435936036`. PR #35 merged the Prototype integration-branch CI bootstrap at `aee4d34f6a03c3f427cc546b4b4086748d7c56b4`; its Android, Go and AI checks, and the post-merge integration-branch run, were GREEN. PR #34 then merged the real `AnebClient` raw POST/SSE seam at `3d2c0e497986db750b98d85f93a463697f38a050`. PR #37 merged the `AnebClient`-backed `PrototypeRawPostTransport` bridge at `b4e762c57752b1fb5b666981a2341f0979ede492`; PR #38 merged strict 122-frame outer topology at `4bead54cba5fd153b9985cdd55cfab9cc05e351c`; PR #39 merged strict received-order content sequence validation at `a94a2d734b47ea8d445c3548ca8751e5856277b9`; and PR #41 merged content-event campaign/run identity validation at `747bb5146aa3051c50d46646b1b600a7a51bd535`, tree `70fb63b90080a1e0fa7be641903485903e33a36b`. Android, Go and AI CI passed for each of PR #37 through PR #41. Issue #16 remains open and G2 remains active; the next bounded atom is `PROTOTYPE_ANDROID_CONTENT_ARRIVAL_CHRONOLOGY_UNBOUND`. Clock, terminal receipt, metrics, Room, UI and campaign orchestration remain pending. Issue #17's release skeleton remains on fixture hold (`HOLD_P0/FIXTURE_BLOCKED`): its latest LifecycleOnly attempt stopped before helper entry with zero requests and is not product RED evidence. G3 through G6 remain pending.

## Gate board

| Gate | State | Evidence | Exit condition |
|---|---|---|---|
| G0 — Specification freeze | `PASS` | PR #23 merge `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`; PR #26 erratum merge `f30829f44026f79ab2077d0c355501034156acae`; issue #14 closed | frozen specification and contract evidence integrated |
| G1 — Core deterministic contract | `PASS` | PR #27; merge `9b968378ab7fbc8ae6c69838f6d165d8377f07c2`; issue #15 | tested workload/conditions/capability/receipts integrated |
| G2 — Android vertical slice | `IN_PROGRESS` | PR #29; PR #30; PR #32 merge `837839417b9b1b3be8b4590c313dc99435936036`; PR #35 CI bootstrap merge `aee4d34f6a03c3f427cc546b4b4086748d7c56b4`; PR #34 raw POST/SSE seam merge `3d2c0e497986db750b98d85f93a463697f38a050`; PR #37 bridge merge `b4e762c57752b1fb5b666981a2341f0979ede492`; PR #38 topology merge `4bead54cba5fd153b9985cdd55cfab9cc05e351c`; PR #39 sequence merge `a94a2d734b47ea8d445c3548ca8751e5856277b9`; PR #41 content identity merge `747bb5146aa3051c50d46646b1b600a7a51bd535`, tree `70fb63b90080a1e0fa7be641903485903e33a36b`; remote Android/Go/AI checks GREEN; issue #16 remains open | Quick + Acceptance campaign and metric gates pass |
| G3 — Fixed Windows package | `PENDING` | issue #17 `HOLD_P0/FIXTURE_BLOCKED`; LifecycleOnly stopped before helper entry with `request_count=0`, so no product RED was established | self-contained candidate launches from fresh directory |
| G4 — Evidence consistency | `PENDING` | issue #17 P0 HOLD | automated bundle/report verification passes |
| G5 — P40 acceptance | `PENDING` | issue #18 | exact candidate passes fresh-install and negative tests |
| G6 — Product Owner acceptance | `PENDING` | issue #13 | fixed release accepted and tagged |

## Workstream board

| Issue | Conversation | State | Current output | Dependency / blocker |
|---:|---|---|---|---|
| #13 | current PMO conversation | `IN_PROGRESS` | G0/G1 integrated; PR #29/#30/#32/#34/#35/#37/#38/#39/#41 and release P0 work coordinated | G2 exit evidence in progress; G3–G6 pending |
| #14 | `20260710_智能体网络诉求` | `COMPLETE` | issue closed; frozen docs/contracts and machine vectors merged by PR #23, with the narrow PR #26 erratum integrated | none |
| #15 | ANEB_GPT core | `COMPLETE` | deterministic core merged by PR #27 at `9b968378ab7fbc8ae6c69838f6d165d8377f07c2` | none |
| #16 | ANEB_GPT Android | `IN_PROGRESS` | exact projector merged by PR #29; `done` fixture/decoder by PR #30; stream adapter by PR #32; CI bootstrap by PR #35; raw POST/SSE seam by PR #34; transport bridge by PR #37; 122-frame topology by PR #38; content sequence by PR #39; content campaign/run identity by PR #41 at `747bb5146aa3051c50d46646b1b600a7a51bd535`; remote Android/Go/AI checks GREEN | `PROTOTYPE_ANDROID_CONTENT_ARRIVAL_CHRONOLOGY_UNBOUND` is next; clock, terminal receipt, metrics, Room, UI, campaign orchestration and G2 exit evidence pending |
| #17 | release/packaging | `HOLD_P0` | release skeleton is `FIXTURE_BLOCKED`; HEAD `1cfc376a`, production `e62b83a`; the only LifecycleOnly attempt timed out before helper entry with `request_count=0`, so it is not product RED/GREEN evidence | use a different observable seam or execution host before retrying launcher lifecycle/TOCTOU; #16 app artifact also required |
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

`G2` is active and issue #16 remains open. The projector, single-terminal-frame
boundary, bounded single-run stream adapter, real `AnebClient` raw POST/SSE seam,
transport bridge, strict 122-frame topology, content sequence and content-event
campaign/run identity validation are merged. The next bounded atom is
`PROTOTYPE_ANDROID_CONTENT_ARRIVAL_CHRONOLOGY_UNBOUND`; clock,
terminal receipt, metrics, Room, UI and campaign orchestration have not yet
produced G2 exit evidence. In parallel, issue #17 is `FIXTURE_BLOCKED`: its
LifecycleOnly attempt stopped before helper entry with zero requests, so it did
not establish a product RED. Owned-server lifecycle and execution-identity TOCTOU
therefore remain open before the release skeleton can contribute G3/G4 evidence.

There is no current hardware, administrator-rights, cloud-node, PCAP or third-party-App blocker for Prototype 0.1.
P40, E-01 and Aliyun have not been touched during this work.

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

1. continue issue #16 from PR #41 merge `747bb5146aa3051c50d46646b1b600a7a51bd535` with a fresh natural RED for `PROTOTYPE_ANDROID_CONTENT_ARRIVAL_CHRONOLOGY_UNBOUND`, then proceed in dependency order to clock and terminal receipt before metrics, Room, UI or campaign orchestration;
2. keep issue #17 and PR #25 in P0 HOLD; do not retry the current fixture, and require a different observable seam or execution host before drawing any product lifecycle/TOCTOU conclusion;
3. admit the fixed Android artifact into issue #17 only after #16 completes its bounded implementation and review;
4. keep #18 in `WAITING_RC` and do not start device work before a fixed signed candidate exists;
5. append only approved binding decisions and evidence-backed gate changes through PMO.

## Progress update rule

This board changes only from GitHub issue/PR/evidence updates. Chat-only completion claims are not promoted to a gate state.
