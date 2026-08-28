# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-08-28**  
Program state: **G0 — SPEC REVIEW**  
Product Owner: Lucas  
Program issue: #13

## Executive status

The product direction has been narrowed and approved. A single product baseline, integration branch, issue hierarchy, workstream allocation and complete first-pass specification set now exist.

No Prototype 0.1 implementation gate has passed yet. Existing ANEB code remains valuable baseline capability, but it is not counted as Prototype 0.1 completion until it satisfies the frozen end-to-end release contract.

## Gate board

| Gate | State | Evidence | Exit condition |
|---|---|---|---|
| G0 — Specification freeze | `REVIEW` | `product/prototype-0.1-specs`; issue #14 | spec approval, decision-log entries, specs merged to integration branch |
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
| #14 | Agentic网络体验标准 | `READY` | review packet prepared | conversation dispatch/spec review pending |
| #15 | ANEB_GPT core | `BACKLOG` | implementation contract prepared | waits for #14 freeze |
| #16 | ANEB_GPT Android | `BACKLOG` | implementation contract prepared | waits for #14 and #15 interface |
| #17 | release/packaging | `BACKLOG` | package/evidence contract prepared | can prototype verifier; fixed candidate waits for #15/#16 |
| #18 | ANEB_CC QA/reuse | `READY` for reuse review; `BACKLOG` for device acceptance | selective-backport and P40 protocol prepared | acceptance waits for fixed candidate |
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

`G0` cannot pass until the issue #14 conversation reviews the draft contracts and the Product Owner decisions are appended to `docs/DECISION_LOG.md`.

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

1. route the dispatch packet to each existing ANEB conversation;
2. collect #14 spec verdicts and resolve only release-blocking contradictions;
3. append approved scope/score/governance decisions to the decision log;
4. merge the specs PR to `product/prototype-0.1`;
5. mark #15 and the independent #18 reuse review READY/IN_PROGRESS.

## Progress update rule

This board changes only from GitHub issue/PR/evidence updates. Chat-only completion claims are not promoted to a gate state.
