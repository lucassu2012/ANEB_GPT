# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-09-08**
Program issue: **#13**. Product Owner: **Lucas**.
Primary deliverable: **one fixed Windows package producing a verifiable report from a real Android campaign**.
Active WIP: **one Android saved-evidence publication recovery action and replacement RC**. PR #57 fixed server identical-retry/conflict handling. The unchanged signed `4a30461` candidate then completed USB Quick, Acceptance9 and cancellation engineering checks. An actual stream interruption preserved P008 evidence but exposed that phone Export only creates an unverified backup; it cannot retry canonical publication. PMO owns this existing-contract recovery gap. The phone and runtime host are clean/standby; no repeated fault campaign or network-security workaround is active. The 24-hour autonomous window does not waive G6 PO review.

## Gate board

G0 and G1 remain complete. G2-A/B/C are merged; do not reopen them as implementation projects.

| Gate | State | Evidence / remaining requirement |
|---|---|---|
| G0 — specification | PASS | #14 closed; PR #23/#26 merged; frozen contracts unchanged |
| G1 — deterministic Core | PASS | #15 closed; PR #27 merged |
| G2 — Android | PASS, implementation | PR #51 `3700caafc2211167061f653c3c5977df6d7f3294`; #52 `4756c1a3d412f6fc6c7fcc5e6494e20a7beb546e`; #53 `44b0e57ce509f80218ac2899e6f765beb41c35cd`; post-merge CI `33456258350` success. #16 administrative closure follows reconciliation. |
| G3 — fixed Windows runtime | `4a30461` ordinary-entry PASS; recovery replacement pending | Original BAT/no arguments, ordinary-user Chinese/space path, READY then normal Q/RC0, admission/signature and first/retry/conflict HTTP checks passed. A source repair requires new bound artifacts; no result inheritance. |
| G4 — evidence/report | HOLD, recovery and actual symlink case | Original packaged offline vectors9/9 passed. USB Quick/Acceptance/cancellation produced canonical seven-file reports. Actual P008 retained only the five-file unverified device fallback after node recovery; new app publication action is not yet a device receipt. Symlink creation was denied before verification: UNEXECUTED, not PASS. |
| G5 — P40 acceptance | HOLD, normal LAN and remaining same-candidate checks | `4a30461` USB engineering Quick3/Acceptance9/cancel passed; P008 actually hit, publication recovery HOLD. Native `adb_reverse` / `acceptance_path=false` cannot satisfy normal-LAN G5. Public-network trust/firewall unchanged; no current prompt proves the historical cause. Phone and owned host resources are clean. |
| G6 — PO acceptance | PENDING | PO must accept the fixed candidate, real report, instructions and G5 evidence. No final release/tag from build or engineering smoke alone. |

## Fixed artifact handoff

Last tested candidate: **rc-20260908-4a30461**.
Disposition: **retained unchanged; G4/G5 HOLD and no automatic device retry**. PR #57 merged at `243965774e55f7859173c3bc0d10d8571d57166d`, with the same tree as the artifact source. Full Android/Go gate and review/post-merge CI passed. The recovery branch `codex/prototype-publication-recovery-0908` is not a fixed candidate; it requires its own complete gate, product PR and new source-bound artifacts. No hand-edited ZIP, mixed-source receipt or status-only PR.
Artifact source: **4a30461abf968affb2100d7a246b6ea94b98e895**.
Artifact source tree: **7cab18c79b355e81ceb36903c4fa7260f638eef8**.
A later PMO documentation commit is not the source identity of these binaries.

| Artifact | SHA-256 |
|---|---|
| ANEB-Prototype-0.1-rc-20260908-4a30461-windows-x64.zip | `553a3a6dc15446bf59c2eedc53a1dd07329e429614f169fe3a978866fca3bb5a` |
| android/aneb-prototype-0.1.apk | `4c3967856683ed4d3e43ffbca167c3ac4594e085228194cdd6f7d3ae38b75d4b` |
| bin/aneb-server.exe | `06f5f7309434a9f4962c05f31e70cabfaa7a6fa0d49f96aa85f2034ffda77ee3` |
| bin/evidence/aneb-evidence.exe | `b2212959e070b3abdcc121b7ddb6e1460b340734cc721eb0239bae7d54e3edfa` |
| External admission receipt | `be53bba959d992a605b61088742af6e24e704ed190c7098a9b77cc0bdbabd30b` |

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

Finish this single saved-evidence recovery action, boundary regressions, full quality gate and product PR. Rebuild APK/server/verifier from one clean fix commit, issue new ZIP/admission hashes, then reassign only original same-candidate acceptance and recovery checks. Remote LAN and actual symlink capability remain unresolved; do not disable protections or change a public network's trust label to manufacture PASS. Preserve `8fe6cc4`, `27ced1c` and `4a30461` reports/receipts unchanged. Re-publishing saved old-candidate evidence is not a new-candidate G5 measurement. No measurement, null-reason or P018 error shape is changed. Never combine versions into PASS. G6 remains a PO decision.
No workload, scoring, evidence schema, claim or G2–G5 criterion changes. No new defensive audit program, LifecycleOnly retry or separate status PR. Historical details remain in Git and linked issue evidence.
