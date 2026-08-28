# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-08-28**
Program state: **G1 — CORE DETERMINISTIC CONTRACT (ACTIVE)**
Product Owner: Lucas
Program issue: #13

## Executive status

The product direction has been narrowed and approved. A single product baseline, integration branch, issue hierarchy, workstream allocation and frozen G0 specification set now exist.

G0 passed through PR #23 and merge commit `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`. G1 core implementation is now active. Existing ANEB code remains valuable baseline capability, but it is not counted as Prototype 0.1 completion until it satisfies the frozen end-to-end release contract.

## Gate board

| Gate | State | Evidence | Exit condition |
|---|---|---|---|
| G0 — Specification freeze | `PASS` | PR #23; merge `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`; issue #14 | frozen specification and contract evidence integrated |
| G1 — Core deterministic contract | `IN_PROGRESS` | issue #15 | tested workload/conditions/capability/receipts |
| G2 — Android vertical slice | `BACKLOG` | issue #16 | Quick + Acceptance campaign and metric gates pass |
| G3 — Fixed Windows package | `BACKLOG` | issue #17 | self-contained candidate launches from fresh directory |
| G4 — Evidence consistency | `BACKLOG` | issue #17 | automated bundle/report verification passes |
| G5 — P40 acceptance | `BACKLOG` | issue #18 | exact candidate passes fresh-install and negative tests |
| G6 — Product Owner acceptance | `BACKLOG` | issue #13 | fixed release accepted and tagged |

## Workstream board

| Issue | Conversation | State | Current output | Dependency / blocker |
|---:|---|---|---|---|
| #13 | current PMO conversation | `IN_PROGRESS` | G0 integrated; G1 execution coordinated | G1 core contract active |
| #14 | `20260710_智能体网络诉求` | `COMPLETE` | frozen docs/contracts and machine vectors merged by PR #23 | none |
| #15 | ANEB_GPT core | `IN_PROGRESS` | G0 contracts available for implementation | G1 exit evidence pending |
| #16 | ANEB_GPT Android | `BLOCKED` | implementation contract prepared | waits for #15 versioned interface |
| #17 | release/packaging | `IN_PROGRESS` | verifier/launcher/offline-report skeleton allowed | fixed package waits for #15 core and #16 app |
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
#14 spec approval
  -> #15 server contract
  -> #16 Android campaign
  -> #17 fixed package + verifier/report
  -> #18 P40 acceptance
  -> #13 PO release acceptance
```

## Current primary blocker

`G1` is active. The current critical dependency is the versioned core interface
and its tested workload, conditions, capability and terminal-receipt evidence
from issue #15.

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

1. execute issue #15 against the frozen G0 contracts and collect G1 exit evidence;
2. keep #16 blocked until #15 exposes the versioned interface;
3. allow #17 to continue only its verifier, launcher and offline-report skeleton until fixed core and app artifacts exist;
4. keep #18 in `WAITING_RC` and do not start device work before a fixed signed candidate exists;
5. append only approved binding decisions and evidence-backed gate changes through PMO.

## Progress update rule

This board changes only from GitHub issue/PR/evidence updates. Chat-only completion claims are not promoted to a gate state.
