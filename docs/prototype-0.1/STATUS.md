# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-09-01**
Program state: **G2 — ANDROID VERTICAL SLICE (ACTIVE)**
Active WIP: **G2-B — Quick persistence, results and device fallback export (REVIEW CANDIDATE)**
Product Owner: Lucas
Program issue: #13

## Executive status

The product direction has been narrowed and approved. A single product baseline, integration branch, issue hierarchy, workstream allocation, frozen G0 specification set and merged G1 core implementation now exist.

G0 passed through PR #23 and merge commit `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`; the narrow PR #26 erratum merged at `f30829f44026f79ab2077d0c355501034156acae`, and issue #14 is closed. G1 passed through PR #27 and merge commit `9b968378ab7fbc8ae6c69838f6d165d8377f07c2`; issue #15 is closed. Issue #16 has merged the exact Prototype projector through PR #29, the real server `done` SSE fixture plus strict Android single-frame decoder through PR #30, and the bounded single-run stream adapter through PR #32 at `837839417b9b1b3be8b4590c313dc99435936036`. PR #35 merged the Prototype integration-branch CI bootstrap at `aee4d34f6a03c3f427cc546b4b4086748d7c56b4`; its Android, Go and AI checks, and the post-merge integration-branch run, were GREEN. PR #34 then merged the real `AnebClient` raw POST/SSE seam at `3d2c0e497986db750b98d85f93a463697f38a050`. PR #37 merged the `AnebClient`-backed `PrototypeRawPostTransport` bridge at `b4e762c57752b1fb5b666981a2341f0979ede492`; PR #38 merged strict 122-frame outer topology at `4bead54cba5fd153b9985cdd55cfab9cc05e351c`; PR #39 merged strict received-order content sequence validation at `a94a2d734b47ea8d445c3548ca8751e5856277b9`; and PR #41 merged content-event campaign/run identity validation at `747bb5146aa3051c50d46646b1b600a7a51bd535`, tree `70fb63b90080a1e0fa7be641903485903e33a36b`. PR #42 integrated the corresponding PMO status at `5a86af13a797ef3af48523bb5873bf30c9eb0f55`; PR #43 merged content-arrival chronology at `6ba9e7e283e7ae41d61a9d93aa8b3ed06572305d`; PR #44 merged terminal-receipt campaign/run identity at `b31d48aec8674bb2c54def9e6f283672b63d6759`; PR #45 merged the run-started payload event-type gate at `14e4ffc52b489b2918f7c7d07a21f5f351bebfbd`; PR #46 merged terminal completion facts at `897c766b3772c3357e8da66c24e8229574a82225`; and PR #47 merged outgoing-request to accepted-stream campaign/run binding at `ac6cb3c94545613e61ead326d78c8bb9615b6bce`. PR #48 integrated the PMO status through PR #47 at `e45f3623f2a031e013ed8b1b11975dc0592d0608`; PR #49 then merged request-to-stream condition identity at `61d7b680abc4e3f50b586fd8964318be5fa980f6`. Android, Go and AI CI passed for each product PR through PR #49. G2-A Integrated Quick Campaign merged through PR #51 at `3700caafc2211167061f653c3c5977df6d7f3294`.

Issue #16 remains open and G2 remains active. G2-B is now the sole active WIP on branch `codex/issue-16-g2-b` from that accepted product base. The current review candidate adds Room campaign/run persistence, foreground campaign ownership, the exact-seven result projection and result UI, plus atomic `device_fallback_unverified` ZIP export and Android sharing. Its focused result/export/share route is 33/33 GREEN with two independent reviews; the full non-device gate reports 80 suites and 678 tests with 0 failures, 0 errors and 2 skipped; Android lint reports 0 errors and 10 warnings. The engineering APK is `com.aneb.probe.prototype` version `0.2.0-prototype-engineering` (`20`), SHA-256 `AD35B014ECD68469E447B93299BCB7983E42008681FCBF951DC04684584F2D37`, signed with a debug certificate. These are moving-candidate receipts, not Gate evidence until the product PR is reviewed and merged. G2 remains `IN_PROGRESS`: G2-C Acceptance 9-run, complete negative-result mapping and cancellation/background/recovery acceptance still remain. P40, E-01 and Aliyun were not touched. Issue #17 remains blocked from claiming a canonical package/report and issue #18 remains blocked from P40 acceptance until a fixed post-G2-C candidate exists. G3 through G6 remain pending.

## Gate board

| Gate | State | Evidence | Exit condition |
|---|---|---|---|
| G0 — Specification freeze | `PASS` | PR #23 merge `a3e9e066db39a4869db8a40bd2b59cd43fc456b7`; PR #26 erratum merge `f30829f44026f79ab2077d0c355501034156acae`; issue #14 closed | frozen specification and contract evidence integrated |
| G1 — Core deterministic contract | `PASS` | PR #27; merge `9b968378ab7fbc8ae6c69838f6d165d8377f07c2`; issue #15 | tested workload/conditions/capability/receipts integrated |
| G2 — Android vertical slice | `IN_PROGRESS` | Product PRs through #49; G2-A PR #51 merge `3700caafc2211167061f653c3c5977df6d7f3294`; G2-B result/persistence/fallback-export candidate on `codex/issue-16-g2-b`; focused 33/33 and non-device 678-test moving receipts | merge reviewed G2-B, then pass G2-C Acceptance, negative-path and cancellation/background/recovery gates |
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
| #16 | ANEB_GPT Android | `IN_PROGRESS` | G2-A merged by PR #51 at `3700caafc2211167061f653c3c5977df6d7f3294`; G2-B review candidate contains campaign/run persistence, foreground ownership, exact-seven result UI and atomic `device_fallback_unverified` ZIP export/share; focused 33/33 and non-device 678-test gates are locally GREEN | review and merge G2-B; then complete G2-C Acceptance 9-run, negative-result mapping and cancellation/background/recovery; do not mark G2 PASS from G2-B |
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

`G2` remains active and issue #16 remains open. G2-A merged through PR #51 at `3700caafc2211167061f653c3c5977df6d7f3294`. The sole active WIP is G2-B on `codex/issue-16-g2-b`: persistence, foreground ownership, result UI and device-fallback ZIP export/share are implemented and locally verified, but the candidate is not yet immutable PR/merge evidence. Its engineering APK uses a debug certificate and is not a fixed release candidate.

After G2-B review and merge, G2-C must still close Acceptance 9-run, complete negative-result mapping and cancellation/background/recovery acceptance. Therefore G2-B must not be reported as G2 PASS, and neither the engineering APK nor its device-fallback bundle can satisfy G4/G5. P40, E-01 and Aliyun have not been touched.

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

1. freeze and independently review the exact G2-B Git objects, then open and merge one product PR containing implementation, tests and this status update;
2. do not create a status-only PR or resume parser-only atom PRs;
3. after merge, start G2-C fresh from the new `product/prototype-0.1` head and close Acceptance 9-run, negative-result mapping and cancellation/background/recovery;
4. keep issue #17 blocked from claiming canonical package/report completion: `device_fallback_unverified` is not G4 evidence;
5. keep issue #18 and P40 idle until #16 produces the fixed post-G2-C candidate;
6. do not promote local focused tests, an engineering APK or an unmerged review candidate to G2/G4/G5 Gate evidence.

## Progress update rule

This board changes only from GitHub issue/PR/evidence updates. Chat-only completion claims are not promoted to a gate state.
