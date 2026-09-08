# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-09-08**
Program issue: **#13**. Product Owner: **Lucas**.
Primary deliverable: **one fixed Windows package producing a verifiable report from a real Android campaign**.
Active WIP: **one required Android error-guidance repair and replacement RC**. Actual Quick3, Acceptance9 and cancellation passed on the launcher-fixed `8fe6cc4` candidate. G5 remains HOLD because the required P006/P007/P008 user-facing mappings were absent. PMO owns the minimal repair; the device is clean and waiting for the next fixed candidate. The 24-hour autonomous window does not waive G6 PO review.

## Gate board

G0 and G1 remain complete. G2-A/B/C are merged; do not reopen them as implementation projects.

| Gate | State | Evidence / remaining requirement |
|---|---|---|
| G0 — specification | PASS | #14 closed; PR #23/#26 merged; frozen contracts unchanged |
| G1 — deterministic Core | PASS | #15 closed; PR #27 merged |
| G2 — Android | PASS, implementation | PR #51 `3700caafc2211167061f653c3c5977df6d7f3294`; #52 `4756c1a3d412f6fc6c7fcc5e6494e20a7beb546e`; #53 `44b0e57ce509f80218ac2899e6f765beb41c35cd`; post-merge CI `33456258350` success. #16 administrative closure follows reconciliation. |
| G3 — fixed Windows runtime | `8fe6cc4` PASS; replacement pending | PR #55 repaired the real BAT/no-Root entry. New fixed package, ordinary-user Chinese/space path, READY then normal Q/RC0 passed. These results are source-bound and will not be inherited by the next candidate. |
| G4 — evidence/report | Real bundles verified; negative coverage reconciliation open | Same-candidate actual Quick3/Acceptance9/cancel exact-seven bundles passed the packaged verifier. #17 mapped remaining section-8 host evidence gaps; old-candidate receipts and source-only checks are not executed negative tests. |
| G5 — P40 acceptance | HOLD, required error presentation | Controlled install-over migration, normal Wi-Fi via the owned PC hotspot, Quick3, Acceptance9 and cancellation completed. Stream interruption retained partial evidence, no success and null RPI, but the page lacked P008; unavailable/incompatible node cards also lacked P006/P007. Machine reasons and independent P018 publication warning remain intact. Device/server/hotspot were cleaned and restored. |
| G6 — PO acceptance | PENDING | PO must accept the fixed candidate, real report, instructions and G5 evidence. No final release/tag from build or engineering smoke alone. |

## Fixed artifact handoff

Last tested candidate: **rc-20260907-8fe6cc4**.
Disposition: **retained unchanged with real device evidence; G5 HOLD and no further device runs on this candidate**. PR #55 merged at `bcd02d71b4f1e90d37beb35124f9e97b3e70a27b`, with the same tree as the artifact source. Full Android/Go gate and CI passed; original BAT/normal-Q acceptance passed. This product error-guidance repair requires newly source-bound artifacts and appropriate same-candidate acceptance. No hand-edited ZIP, mixed-source receipt or status-only PR.
Artifact source: **8fe6cc499a2f09ae0d10290cba2e1abc1fa12090**.
Artifact source tree: **1e95a832ca0db54cf2656d8cbe7c0de7c6fc0c67**.
A later PMO documentation commit is not the source identity of these binaries.

| Artifact | SHA-256 |
|---|---|
| ANEB-Prototype-0.1-rc-20260907-8fe6cc4-windows-x64.zip | `47567061b32c44e5637d988193028d7afd95f2df45c56d0ffbeba2668fea7d23` |
| android/aneb-prototype-0.1.apk | `cb142bc9b28fd1d40c9ad483ba020824409fb5eeca177f400edfd39f50da6138` |
| bin/aneb-server.exe | `7d936a5ec391663d6a9fdf7b5a6c84c291f4a22237ec848b01064b32b820043d` |
| bin/evidence/aneb-evidence.exe | `e2a6ebbc4fc5b432634140b2128d6d9cb314814fa9fba975dd053bfd0cb87f53` |
| External admission receipt | `b8180e89bd8c21f4bc3a898ecc64441547eec307640c37cf75097d666dc99973` |

The local RC location is in #13/#17 handoffs, not embedded as a runtime path. APK identity: `com.aneb.probe`, version `0.2.0`, code `20`; approved signer certificate SHA-256 `b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003`. Keys remain outside Git and release artifacts.
APK/server/verifier/ZIP/receipt hashes were independently recomputed on 2026-09-07 and match. Historical same-source Android, Go, signing and admission receipts remain build evidence, not substitutes for current G3/G5 observations. Unchanged hashes do not require a rebuild.

## Actual result path

1. Android records client events and receipts and saves the local result.
2. `PrototypeCampaignPublishingResultStore` publishes through `POST /api/v1/prototype/campaigns/evidence`.
3. The Go node invokes bundled `aneb-evidence` to recompute, validate and atomically publish.
4. The final directory contains exactly `meta.json`, `events.jsonl`, `runs.csv`, `summary.csv`, `report.html`, `run.log`, `manifest.json`.
5. The user opens `results/<campaign_id>/report.html`; packaged offline verification checks that directory.

Source-root VERSION.json is a build template; the fixed package is RELEASE_CANDIDATE / REAL_ARTIFACTS_BOUND. The template does not prove missing artifacts. Legacy `/api/v1/results` upload is disabled in Prototype mode. Local Prototype evidence submission is the frozen node publication path, not cloud upload. Device-fallback exports remain unverified; characterization fixtures are not device evidence.

## Workstream ownership

| Owner | State | Bounded next output |
|---|---|---|
| #13 PMO | ACTIVE, sole release-source writer | One product PR for existing release implementation and status, actual dispatch/acknowledgements; no status-only PR |
| #14 SPEC | COMPLETE, support only | Explain concrete integration contradictions; no new standard research on the release path |
| #15 CORE | COMPLETE; support only | Explain a concrete node integration issue if needed; no second server |
| #16 APP | READ-ONLY SUPPORT COMPLETE | P006/P007/P008 mapping findings handed to PMO; no separate writer/runner/parser-only PR |
| #17 RELEASE | EVIDENCE MAPPING, replacement pending | Existing host negative coverage and five-step handoff index; no redundant old-candidate matrix or parallel source edits |
| #18 QA | CLEAN/STANDBY | Preserve actual positive/negative evidence; resume only after replacement admission and fresh live preflight |
| #19 real-App research | NON-BLOCKING | Existing separately authorized work; no Prototype 0.1 dependency |

All bounded support owners returned receipts. Actual latest evidence is in [PMO #13](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5572409181), [QA safe stop](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5577245585), [APP mapping](https://github.com/lucassu2012/ANEB_GPT/issues/16#issuecomment-5577273087), [host coverage map](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5577245672) and [handoff guide](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5573222363). These receipts, not task-title/status inference, determine completion.

## Device, privacy and parallel-work boundary

The initial C-tree hold was cleared by PO and fresh live checks. The user confirmed the upstream Wi-Fi is public; it was not relabelled trusted, and no new firewall rule was added. #18 alone used a temporary native PC hotspot with protected original-config backup and rollback, then completed normal Wi-Fi device runs. Final cleanup restored the original phone network and hotspot configuration, left hotspot off/client count zero, stopped owned app/server/listeners, removed temporary forwarding/capture/test state and returned to Launcher (then asleep). No unknown session, business data or unrelated sharing service was stopped. Full stable device identifiers and credentials must not enter public receipts. Future device work requires a new live preflight.
ANEB_CC has separately recorded research activity. Its natural observations, shaping status and corpus qualification remain separate from GPT gates. This update neither revokes its existing authorization nor grants new device/driver/shaping/server/cross-repository changes. It is not a G3/G4 dependency. No whole-repository merge, second writer/controller, Bridge or Relay is opened.

## Next checkpoint

Finish the single P006/P007/P008 presentation repair, its regression tests, full quality gate and product PR. Rebuild APK/server/verifier from one clean fix commit, issue new ZIP/admission hashes, then execute the original same-candidate G3-G5 acceptance and missing section-8 checks once. Preserve the actual `8fe6cc4` Quick, Acceptance and cancellation reports as historical evidence, not replacement PASS. No measurement, null-reason or P018 publication semantics are changed. Never combine versions into PASS. G6 remains a PO decision.
No workload, scoring, evidence schema, claim or G2–G5 criterion changes. No new defensive audit program, LifecycleOnly retry or separate status PR. Historical details remain in Git and linked issue evidence.
