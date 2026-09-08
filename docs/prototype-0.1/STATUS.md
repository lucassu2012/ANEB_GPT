# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-09-09 (Asia/Shanghai)**
Program issue: **#13**. Product Owner: **Lucas**.
Primary deliverable: **one fixed Windows package producing a verifiable report from a real Android campaign**.
Active WIP: **one Android saved-result navigation repair and replacement RC**. PR #58 added the saved-evidence publication action. Candidate `799661d` completed normal-LAN Quick/Acceptance, but a real cancelled campaign retained P018 and could not be reopened through normal UI after leaving its result. The bounded repair adds an ID/original-node chooser and reuses the validated result/Retry path; it does not change workload, metrics, evidence, scoring or publication rules. The phone and runtime host are clean/standby. PO acceptance and the decision to retain the verified full ZIP filename are recorded; technical release gates are not waived.

The navigation failure is recorded in [#18](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5587660300): Retry0, newcampaign0, original seven staging hashes unchanged. Original publication cause remains unknown. Repository, navigation and recovery focused tests now pass46/46; independent source review closed stale-load, same-ID reopen and active-session boundaries. The original complete quality gate passed: Android791 tests/0 failures/0 errors/2 existing ignored, lint and assemble; the final uninstrumented gate reused those unchanged Android outputs and passed fresh Go23.745s/RC0. Its earlier single synthetic Go publication500 failure is retained with unknown cause; no server change or acceptance waiver. CI and a new signed candidate remain required. No old binary is patched or relabeled, and no old candidate's device PASS is inherited.

## Gate board

G0 and G1 remain complete. G2-A/B/C are merged; do not reopen them as implementation projects.

| Gate | State | Evidence / remaining requirement |
|---|---|---|
| G0 — specification | PASS | #14 closed; PR #23/#26 merged; frozen contracts unchanged |
| G1 — deterministic Core | PASS | #15 closed; PR #27 merged |
| G2 — Android | PASS, implementation | PR #51 `3700caafc2211167061f653c3c5977df6d7f3294`; #52 `4756c1a3d412f6fc6c7fcc5e6494e20a7beb546e`; #53 `44b0e57ce509f80218ac2899e6f765beb41c35cd`; post-merge CI `33456258350` success. #16 administrative closure follows reconciliation. |
| G3 — fixed Windows runtime | `799661d` ordinary-entry PASS; navigation replacement pending | Original BAT/no arguments, ordinary-user Chinese/space path, READY then normal Q/RC0; same-candidate host P001/P002/retry/conflict checks passed. New Android source requires a newly bound package. |
| G4 — evidence/report | `799661d` offline checks complete; replacement pending | Original vectors9/9 and [actual symlink rejection](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5587319354) passed. UAC was used only to prepare one isolated symlink fixture; original verifier ran unprivileged. No new candidate receipt yet. |
| G5 — P40 acceptance | HOLD, saved-result navigation and remaining same-candidate checks | [Actual normal-LAN Quick3/Acceptance9](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5586834082), native lan/true and original verifier RC0, completed on799. First-slot cancellation preserved59 events/not_started tail/null RPI but publication failed. P008 normal-LAN case remains unexercised. Phone and owned host resources are clean; no security workaround. |
| G6 — PO acceptance | PO acceptance recorded; technical closure pending | PO explicitly accepted publication and the verified full filename. No final release/tag until the open P0 and replacement-candidate gates close. |

## Fixed artifact handoff

Last tested candidate: **rc-20260908-799661d**.
Disposition: **retained unchanged; G5 HOLD and no automatic device retry**. PR #58 merged at `216ecc7fc4576bf894ca0c59dfda877e31d543eb`, with the same tree as the artifact source. Full Android/Go gate and review/post-merge CI passed for that source. The navigation repair branch `codex/prototype-saved-results-0908` requires its own complete gate, product PR and new source-bound artifacts. No hand-edited ZIP, mixed-source receipt or status-only PR.
Artifact source: **799661d808d8b842effae1aabb07c70af0b22197**.
Artifact source tree: **9c708e4dc2b09e8387c7909d8c57df5cbac3fdf8**.
A later PMO documentation commit is not the source identity of these binaries.

| Artifact | SHA-256 |
|---|---|
| ANEB-Prototype-0.1-rc-20260908-799661d-windows-x64.zip | `ca5207b596319bc96e3ecaacb37a71c203e613dcc821bd2a461860677f6f975a` |
| android/aneb-prototype-0.1.apk | `8b4b3a669a536759dae79c87bf371968fdd3686276e9750a8d94979cd2797092` |
| bin/aneb-server.exe | `f5ccc22c5acb4a923102eb3c6b4f1ca351058717bcbf44c1ce00de7e58414b54` |
| bin/evidence/aneb-evidence.exe | `43c17a0ef807c03977db0c28cbda0f9ad545845f7856f3811700bc89d0a41d7f` |
| External admission receipt | `84104206de7ffebc435a4dcb9c2bb0ba0e67955c09999cc7dff36fbc79755196` |

The local RC location is in #13/#17 handoffs, not embedded as a runtime path. APK identity: `com.aneb.probe`, version `0.2.0`, code `20`; approved signer certificate SHA-256 `b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003`. Keys remain outside Git and release artifacts.
APK/server/verifier/ZIP/receipt hashes were independently recomputed on 2026-09-08 and match. Historical same-source Android, Go, signing and admission receipts remain build evidence, not substitutes for replacement G3/G5 observations. Development-only runtimes used for HTTP regression are not RC artifacts.

## Actual result path

1. Android records client events and receipts and saves the local result.
2. `PrototypeCampaignPublishingResultStore` publishes through `POST /api/v1/prototype/campaigns/evidence`.
3. The Go node invokes bundled `aneb-evidence` to recompute, validate and atomically publish.
4. The final directory contains exactly `meta.json`, `events.jsonl`, `runs.csv`, `summary.csv`, `report.html`, `run.log`, `manifest.json`.
5. The user opens `results/<campaign_id>/report.html`; packaged offline verification checks that directory.

An identical retry compares the four decoded original text payloads, not outer JSON layout or regenerated timestamps, and returns the original manifest-bound receipt without rewriting files. A conflict remains P018 and retains at most one digest-only diagnostic per campaign (2 KiB maximum) under `results/.publication diagnostics/`, outside all valid campaign-ID names. Corrupt/incomplete existing publications are not repaired or accepted. No global cross-campaign run registry or new evidence/receipt schema is introduced.

PR #57 checks: publication regression11 PASS, frozen contract24 PASS, Android/Go full gate and CI PASS. A real name-collision regression failed before the disjoint diagnostic name fixed it. Those receipts do not cover this Android recovery change.

Current recovery implementation reads the saved Room snapshot and original node, reuses the existing renderer/publisher, and exposes `Retry evidence publication` on every ready result. It does not capture a new runtime identity, run a new campaign or write back measurements. Missing saved authority fails closed. Cancellation propagates. Acknowledgement belongs only to the current result view; subsequent Export/Share does not resurrect its warning, but reopening does not infer persisted confirmation. P008 and metrics remain intact. Device ZIP remains five-file/unverified even after node confirmation. Focused recovery/result checks passed38/38. The original full quality gate passed: Android786 tests/0 failures/0 errors/2 existing ignored, lintDebug and assembleDebug PASS, Go fresh tests PASS23.904s. Independent source/UI review found no concrete new P0. CI, immutable source-bound RC and same-candidate acceptance remain separate required evidence; local tests are not device receipts.

Source-root VERSION.json is a build template; the fixed package is RELEASE_CANDIDATE / REAL_ARTIFACTS_BOUND. The template does not prove missing artifacts. Legacy `/api/v1/results` upload is disabled in Prototype mode. Local Prototype evidence submission is the frozen node publication path, not cloud upload. Device-fallback exports remain unverified; characterization fixtures are not device evidence.

## Workstream ownership

| Owner | State | Bounded next output |
|---|---|---|
| #13 PMO | ACTIVE, sole release-source writer | One product PR for existing release implementation and status, actual dispatch/acknowledgements; no status-only PR |
| #14 SPEC | COMPLETE, support only | Explain concrete integration contradictions; no new standard research on the release path |
| #15 CORE | COMPLETE; support only | Explain a concrete node integration issue if needed; no second server |
| #16 APP | READ-ONLY SUPPORT COMPLETE | Recovery wiring/claim/cancellation review returned no concrete new P0; boundary tests/full gate still required. No separate writer/runner/parser-only PR |
| #17 RELEASE | EVIDENCE MAPPING, replacement pending | Existing host negative coverage and five-step handoff index; no redundant old-candidate matrix or parallel source edits |
| #18 QA | CLEAN/STANDBY | Preserve actual positive/negative evidence; resume only after replacement admission and fresh live preflight |
| #19 real-App research | NON-BLOCKING | Existing separately authorized work; no Prototype 0.1 dependency |

All bounded support owners returned receipts. Actual latest evidence is in [PMO #13](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5572409181), [4a host acceptance](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5578565218), [offline9/9](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5578742862), [USB Quick](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5579443637), [USB Acceptance9](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5579533135), [cancellation](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5579594200) and [actual P008/publication HOLD](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5579750626). These receipts, not task-title/status inference, determine completion.

## Device, privacy and parallel-work boundary

The initial C-tree hold was cleared by PO and fresh live checks. The user confirmed the upstream Wi-Fi is public; it was not relabelled trusted, and no new firewall rule was added. Historical `8fe6cc4` normal Wi-Fi runs used a temporary native PC hotspot with protected rollback; they cannot prove `4a30461` normal-LAN acceptance. Current `4a30461` engineering checks used only an owned loopback server and USB reverse, recorded natively as non-acceptance. Final cleanup stopped owned app/server/listeners, removed the owned mapping and returned to Launcher (then asleep). The app still retains the loopback node; without a USB reverse it is not reachable. Visible editor restoration was not claimed as persisted configuration. No unknown session, business data or unrelated sharing service was stopped. Full stable device identifiers and credentials must not enter public receipts. Future device work requires a new live preflight.
ANEB_CC has separately recorded research activity. Its natural observations, shaping status and corpus qualification remain separate from GPT gates. This update neither revokes its existing authorization nor grants new device/driver/shaping/server/cross-repository changes. It is not a G3/G4 dependency. No whole-repository merge, second writer/controller, Bridge or Relay is opened.

## Next checkpoint

Close this single saved-result navigation repair as one product PR after the completed local quality gate, then obtain CI and rebuild APK/server/verifier from one clean fix commit. Issue new ZIP/admission hashes and reassign only original same-candidate acceptance and recovery checks. Normal LAN and actual symlink rejection were observed on799, not on the replacement candidate; do not disable protections or change network trust labels to manufacture PASS. Preserve every prior candidate/report/receipt unchanged. Re-publishing saved old-candidate evidence is not a new-candidate G5 measurement. No measurement, null-reason or P018 error shape is changed. Never combine versions into PASS. Actual PO release acceptance is recorded; the replacement technical gates remain open.
No workload, scoring, evidence schema, claim or G2–G5 criterion changes. No new defensive audit program, LifecycleOnly retry or separate status PR. Historical details remain in Git and linked issue evidence.
