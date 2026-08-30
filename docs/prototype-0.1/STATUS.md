# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-08-31**
Program state: **G2 — ANDROID VERTICAL SLICE (ACTIVE)**
Active WIP: **G2-A — Integrated Quick Campaign**
Product Owner: Lucas
Program issue: #13

## Executive status

The product direction has been narrowed and approved. A single product baseline, integration branch, issue hierarchy, workstream allocation, frozen G0 specification set and merged G1 core implementation now exist.

G0 passed through PR #23 and merge commit `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`; the narrow PR #26 erratum merged at `f30829f44026f79ab2077d0c355501034156acae`, and issue #14 is closed. G1 passed through PR #27 and merge commit `9b968378ab7fbc8ae6c69838f6d165d8377f07c2`; issue #15 is closed. Issue #16 has merged the exact Prototype projector through PR #29, the real server `done` SSE fixture plus strict Android single-frame decoder through PR #30, and the bounded single-run stream adapter through PR #32 at `837839417b9b1b3be8b4590c313dc99435936036`. PR #35 merged the Prototype integration-branch CI bootstrap at `aee4d34f6a03c3f427cc546b4b4086748d7c56b4`; its Android, Go and AI checks, and the post-merge integration-branch run, were GREEN. PR #34 then merged the real `AnebClient` raw POST/SSE seam at `3d2c0e497986db750b98d85f93a463697f38a050`. PR #37 merged the `AnebClient`-backed `PrototypeRawPostTransport` bridge at `b4e762c57752b1fb5b666981a2341f0979ede492`; PR #38 merged strict 122-frame outer topology at `4bead54cba5fd153b9985cdd55cfab9cc05e351c`; PR #39 merged strict received-order content sequence validation at `a94a2d734b47ea8d445c3548ca8751e5856277b9`; and PR #41 merged content-event campaign/run identity validation at `747bb5146aa3051c50d46646b1b600a7a51bd535`, tree `70fb63b90080a1e0fa7be641903485903e33a36b`. PR #42 integrated the corresponding PMO status at `5a86af13a797ef3af48523bb5873bf30c9eb0f55`; PR #43 merged content-arrival chronology at `6ba9e7e283e7ae41d61a9d93aa8b3ed06572305d`; PR #44 merged terminal-receipt campaign/run identity at `b31d48aec8674bb2c54def9e6f283672b63d6759`; PR #45 merged the run-started payload event-type gate at `14e4ffc52b489b2918f7c7d07a21f5f351bebfbd`; PR #46 merged terminal completion facts at `897c766b3772c3357e8da66c24e8229574a82225`; and PR #47 merged outgoing-request to accepted-stream campaign/run binding at `ac6cb3c94545613e61ead326d78c8bb9615b6bce`. PR #48 integrated the PMO status through PR #47 at `e45f3623f2a031e013ed8b1b11975dc0592d0608`; PR #49 then merged request-to-stream condition identity at `61d7b680abc4e3f50b586fd8964318be5fa980f6`. Android, Go and AI CI passed for each product PR through PR #49. Issue #16 remains open and G2 remains active. G2-A is the sole active WIP on branch `codex/issue-16-g2-a-integrated-quick-campaign` from accepted base `29a15df19364de21fbc2f128a8619ce2f07f728c`. Its uncommitted review candidate now runs Baseline, Slow and Unstable in order and projects TTFT, completion, event rate, stall metrics, success rate, RPI-0.1 and Campaign Summary. The current candidate fails closed instead of publishing a schema-invalid interrupted row when the first validated content timestamp equals `t0`. Its forced focused Android gate is 123/123 GREEN; the repository quality gate passed Android unit/lint/assemble and Go, and the AI behavior-model gate is 7/7 GREEN. These moving-worktree receipts are not Gate evidence until the PR review and merge finish. Issue #17 is `TEST_INFRA_BLOCKED` and `WAITING_APP_ARTIFACT`; issue #18 is `WAITING_G2_ENGINEERING_SMOKE`. G3 through G6 remain pending.

## Gate board

| Gate | State | Evidence | Exit condition |
|---|---|---|---|
| G0 — Specification freeze | `PASS` | PR #23 merge `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`; PR #26 erratum merge `f30829f44026f79ab2077d0c355501034156acae`; issue #14 closed | frozen specification and contract evidence integrated |
| G1 — Core deterministic contract | `PASS` | PR #27; merge `9b968378ab7fbc8ae6c69838f6d165d8377f07c2`; issue #15 | tested workload/conditions/capability/receipts integrated |
| G2 — Android vertical slice | `IN_PROGRESS` | PR #29; PR #30; PR #32 merge `837839417b9b1b3be8b4590c313dc99435936036`; PR #35 CI bootstrap merge `aee4d34f6a03c3f427cc546b4b4086748d7c56b4`; PR #34 raw POST/SSE seam merge `3d2c0e497986db750b98d85f93a463697f38a050`; PR #37 bridge merge `b4e762c57752b1fb5b666981a2341f0979ede492`; PR #38 topology merge `4bead54cba5fd153b9985cdd55cfab9cc05e351c`; PR #39 sequence merge `a94a2d734b47ea8d445c3548ca8751e5856277b9`; PR #41 content identity merge `747bb5146aa3051c50d46646b1b600a7a51bd535`; PR #43 chronology merge `6ba9e7e283e7ae41d61a9d93aa8b3ed06572305d`; PR #44 terminal identity merge `b31d48aec8674bb2c54def9e6f283672b63d6759`; PR #45 run-started event-type merge `14e4ffc52b489b2918f7c7d07a21f5f351bebfbd`; PR #46 terminal completion merge `897c766b3772c3357e8da66c24e8229574a82225`; PR #47 request/run identity merge `ac6cb3c94545613e61ead326d78c8bb9615b6bce`; PR #49 condition identity merge `61d7b680abc4e3f50b586fd8964318be5fa980f6`; remote Android/Go/AI checks GREEN; issue #16 remains open | Quick + Acceptance campaign and metric gates pass |
| G3 — Fixed Windows package | `PENDING` | issue #17 `TEST_INFRA_BLOCKED / WAITING_APP_ARTIFACT`; no product artifact is available for this gate | self-contained candidate launches from fresh directory |
| G4 — Evidence consistency | `PENDING` | issue #17 `TEST_INFRA_BLOCKED / WAITING_APP_ARTIFACT` | automated bundle/report verification passes |
| G5 — P40 acceptance | `PENDING` | issue #18 | exact candidate passes fresh-install and negative tests |
| G6 — Product Owner acceptance | `PENDING` | issue #13 | fixed release accepted and tagged |

## Workstream board

| Issue | Conversation | State | Current output | Dependency / blocker |
|---:|---|---|---|---|
| #13 | current PMO conversation | `IN_PROGRESS` | G0/G1 integrated; Android vertical-slice PRs through #49 and release P0 work coordinated | G2 exit evidence in progress; G3–G6 pending |
| #14 | `20260710_智能体网络诉求` | `COMPLETE` | issue closed; frozen docs/contracts and machine vectors merged by PR #23, with the narrow PR #26 erratum integrated | none |
| #15 | ANEB_GPT core | `COMPLETE` | deterministic core merged by PR #27 at `9b968378ab7fbc8ae6c69838f6d165d8377f07c2` | none |
| #16 | ANEB_GPT Android | `IN_PROGRESS` | merged evidence through PR #49 remains authoritative; the uncommitted G2-A candidate from base `29a15df19364de21fbc2f128a8619ce2f07f728c` contains the integrated three-condition Runner, metrics, RPI-0.1 and Campaign Summary; focused Android 123/123, repository quality gate and AI 7/7 are locally GREEN | finish independent review and merge; keep the complete negative-result mapping and acceptance (`incompatible`, `server_rejected`, `invalid_sequence`, cancellation, timeout and clock invalidation) in G2-C |
| #17 | release/packaging | `TEST_INFRA_BLOCKED` | `WAITING_APP_ARTIFACT`; no G2 application artifact is available for packaging or release verification | wait for the reviewed G2 application artifact from #16 |
| #18 | `20260801_ANEB_WP2设备运行时` | `WAITING_G2_ENGINEERING_SMOKE` | selective-backport and P40 protocol prepared | wait for G2 engineering smoke before any device action |
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

`G2` is active and issue #16 remains open. The sole active WIP is G2-A Integrated
Quick Campaign from accepted base
`29a15df19364de21fbc2f128a8619ce2f07f728c`. The moving candidate now contains
the complete Baseline/Slow/Unstable Runner integration, the required metrics,
RPI-0.1 and Campaign Summary. The current candidate also rejects the
same-tick first-content case before it can publish a schema-invalid partial
record. Its forced focused Android gate is 123/123 GREEN, the repository
quality gate passed Android unit/lint/assemble and Go, and the AI behavior
model is 7/7 GREEN. The remaining blocker is independent review and merge;
uncommitted local GREEN is not Gate evidence. Full negative-result mapping and
acceptance remain explicitly scheduled for G2-C rather than being pulled into
this G2-A PR. Issue #17 remains
`TEST_INFRA_BLOCKED / WAITING_APP_ARTIFACT`, and issue #18 remains
`WAITING_G2_ENGINEERING_SMOKE`.

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

1. finish the complete preflight and independent review for the G2-A candidate on `codex/issue-16-g2-a-integrated-quick-campaign`, then carry the Capability Preflight, profile/schedule/clock/terminal-receipt binding, real three-condition Runner, metrics, RPI-0.1 and Campaign Summary in one reviewed PR;
2. keep issue #17 at `TEST_INFRA_BLOCKED / WAITING_APP_ARTIFACT` until #16 supplies the reviewed application artifact;
3. keep issue #18 at `WAITING_G2_ENGINEERING_SMOKE` and do not start device work before G2 engineering smoke is available;
4. do not create parser-only atom PRs or status-only PRs; this status update belongs to the same G2-A implementation PR;
5. do not count natural REDs, focused GREENs or uncommitted tests as Gate progress; append only approved binding decisions and evidence-backed Gate changes through PMO.

## Progress update rule

This board changes only from GitHub issue/PR/evidence updates. Chat-only completion claims are not promoted to a gate state.
