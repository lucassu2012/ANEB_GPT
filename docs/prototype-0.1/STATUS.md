# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-08-28**  
Program state: **G0 — SPEC REWORK (HOLD)**
Product Owner: Lucas  
Program issue: #13

## Executive status

The product direction has been narrowed and approved. A single product baseline, integration branch, issue hierarchy, workstream allocation and complete first-pass specification set now exist.

No Prototype 0.1 implementation gate has passed yet. Existing ANEB code remains valuable baseline capability, but it is not counted as Prototype 0.1 completion until it satisfies the frozen end-to-end release contract.

## Gate board

| Gate | State | Evidence | Exit condition |
|---|---|---|---|
| G0 — Specification freeze | `REVIEW` | isolated issue #14 rework head (HOLD) | per-file verdict, exact vectors and PO binding receipt reviewed; no gate advance in this task |
| G1 — Core deterministic contract | `BACKLOG` | issue #15 | tested workload/conditions/capability/receipts |
| G2 — Android vertical slice | `BACKLOG` | issue #16 | Quick + Acceptance campaign and metric gates pass |
| G3 — Fixed Windows package | `BACKLOG` | issue #17 | self-contained candidate launches from fresh directory |
| G4 — Evidence consistency | `BACKLOG` | issue #17 | automated bundle/report verification passes |
| G5 — P40 acceptance | `BACKLOG` | issue #18 | exact candidate passes fresh-install and negative tests |
| G6 — Product Owner acceptance | `BACKLOG` | issue #13 | fixed release accepted and tagged |

## Workstream board

| Issue | Conversation | State | Current output | Dependency / blocker |
|---:|---|---|---|---|
| #13 | current PMO conversation | `IN_PROGRESS` | scope, branches, issues, specs and control model created | G0 review pending |
| #14 | `20260710_智能体网络诉求` | `IN_PROGRESS` | binding docs/contracts rework and machine vectors | exact new head awaits independent review; G0 remains HOLD |
| #15 | ANEB_GPT core | `BACKLOG` | implementation contract prepared | waits for #14 freeze |
| #16 | ANEB_GPT Android | `BACKLOG` | implementation contract prepared | waits for #14 and #15 interface |
| #17 | release/packaging | `BACKLOG` | package/evidence contract prepared | can prototype verifier; fixed candidate waits for #15/#16 |
| #18 | `20260801_ANEB_WP2设备运行时` | `READY` | selective-backport and P40 protocol prepared | acceptance waits for fixed candidate |
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

`G0` is HOLD until the issue #14 conversation reviews the rework exact head,
recomputes the raw evidence vectors and records the final per-file verdict. This
task does not advance G0 or merge the specs.

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

1. have the issue #14 reviewer inspect the exact rework commit and validation output;
2. keep #15/#16 blocked until #14 review is complete;
3. append only the approved binding decisions to the decision log through PMO;
4. let PMO decide whether to merge the specs to `product/prototype-0.1`;
5. keep #18 READY for reuse review until a fixed candidate exists.

## Progress update rule

This board changes only from GitHub issue/PR/evidence updates. Chat-only completion claims are not promoted to a gate state.
