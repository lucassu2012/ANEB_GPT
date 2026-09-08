# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-09-08**
Program issue: **#13**. Product Owner: **Lucas**.
Primary deliverable: **one fixed Windows package producing a verifiable report from a real Android campaign**.
Active WIP: **one original-contract publication retry/conflict repair and replacement RC**. PR #56 completed the required Android P006/P007/P008 guidance. Its signed `27ced1c` package passed ordinary-user launch/admission, but remote phone reachability stopped before any campaign. Original host acceptance then found identical uploads rejected and conflicting uploads lacking a server-retained diagnostic. PMO owns only this minimal repair; the device and acceptance host are clean/standby. The 24-hour autonomous window does not waive G6 PO review.

## Gate board

G0 and G1 remain complete. G2-A/B/C are merged; do not reopen them as implementation projects.

| Gate | State | Evidence / remaining requirement |
|---|---|---|
| G0 — specification | PASS | #14 closed; PR #23/#26 merged; frozen contracts unchanged |
| G1 — deterministic Core | PASS | #15 closed; PR #27 merged |
| G2 — Android | PASS, implementation | PR #51 `3700caafc2211167061f653c3c5977df6d7f3294`; #52 `4756c1a3d412f6fc6c7fcc5e6494e20a7beb546e`; #53 `44b0e57ce509f80218ac2899e6f765beb41c35cd`; post-merge CI `33456258350` success. #16 administrative closure follows reconciliation. |
| G3 — fixed Windows runtime | `27ced1c` ordinary-entry PASS; replacement pending | Original BAT/no arguments, ordinary-user Chinese/space path, READY then normal Q/RC0 passed. Exact-source admission/signature checks passed. A source repair requires new bound artifacts; no result inheritance. |
| G4 — evidence/report | HOLD, publication repair and actual symlink case | Original host test: first upload200; identical retry500/P018; conflicting upload500/P018 kept seven files unchanged but no server diagnostic. Source repair now preserves the original receipt on identical retries and rejects conflicts with bounded external diagnostics. Actual symlink creation was denied before verification: UNEXECUTED, not PASS. |
| G5 — P40 acceptance | HOLD, remote LAN reachability | `27ced1c` installed without deleting phone data, but connection check ended P006 before Quick/Acceptance. Host loopback and hotspot IPv4 probes returned200; they do not prove remote ingress. Hotspot Public profile and exact-program Block are observed, not an exclusive packet-drop verdict. Device/server/hotspot/owned ACL changes cleaned and restored. |
| G6 — PO acceptance | PENDING | PO must accept the fixed candidate, real report, instructions and G5 evidence. No final release/tag from build or engineering smoke alone. |

## Fixed artifact handoff

Last tested candidate: **rc-20260908-27ced1c**.
Disposition: **retained unchanged; G4/G5 HOLD and no automatic device retry**. PR #56 merged at `45c40f7af57c0b51c6f2d1fa48e5b1286af303ae`, with the same tree as the artifact source. Full Android/Go gate and CI passed; original BAT/normal-Q acceptance passed. The publication repair requires newly source-bound artifacts and appropriate same-candidate acceptance. No hand-edited ZIP, mixed-source receipt or status-only PR.
Artifact source: **27ced1cd5e8a8cd84a578139b3119ff73414fa47**.
Artifact source tree: **f1cf4a7fc7c60cebaa551f89a4d5e0257ce4096b**.
A later PMO documentation commit is not the source identity of these binaries.

| Artifact | SHA-256 |
|---|---|
| ANEB-Prototype-0.1-rc-20260908-27ced1c-windows-x64.zip | `a4da8dbd61def8b7994d24cf5b9bebd26cc198afefe44c7a1489beff2ca52cf3` |
| android/aneb-prototype-0.1.apk | `7a728d5b89494bcc1bdc11e8fc74259055c9e4003f2a5cc3fdecb0ffb40d4d07` |
| bin/aneb-server.exe | `436f30591da0c299884c235be9206bc072f0b12e808c9284d051b6c8c2f082af` |
| bin/evidence/aneb-evidence.exe | `101bdb8ee9e8d212e2a97b882edd9209d7a3db72a4f5c7492746d94c93ea68d3` |
| External admission receipt | `55224a212a1551d28c49f56b047513c85a1206531c600ab47dfdd334d6442777` |

The local RC location is in #13/#17 handoffs, not embedded as a runtime path. APK identity: `com.aneb.probe`, version `0.2.0`, code `20`; approved signer certificate SHA-256 `b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003`. Keys remain outside Git and release artifacts.
APK/server/verifier/ZIP/receipt hashes were independently recomputed on 2026-09-08 and match. Historical same-source Android, Go, signing and admission receipts remain build evidence, not substitutes for replacement G3/G5 observations. Development-only runtimes used for HTTP regression are not RC artifacts.

## Actual result path

1. Android records client events and receipts and saves the local result.
2. `PrototypeCampaignPublishingResultStore` publishes through `POST /api/v1/prototype/campaigns/evidence`.
3. The Go node invokes bundled `aneb-evidence` to recompute, validate and atomically publish.
4. The final directory contains exactly `meta.json`, `events.jsonl`, `runs.csv`, `summary.csv`, `report.html`, `run.log`, `manifest.json`.
5. The user opens `results/<campaign_id>/report.html`; packaged offline verification checks that directory.

An identical retry compares the four decoded original text payloads, not outer JSON layout or regenerated timestamps, and returns the original manifest-bound receipt without rewriting files. A conflict remains P018 and retains at most one digest-only diagnostic per campaign (2 KiB maximum) under `results/.publication diagnostics/`, outside all valid campaign-ID names. Corrupt/incomplete existing publications are not repaired or accepted. No global cross-campaign run registry or new evidence/receipt schema is introduced.

Local repair checks: publication regression11 PASS, frozen contract24 PASS, existing Android/Go full quality gate PASS (unchanged Android tasks reused cached results; Go reran with the developer-runtime HTTP smoke). A real name-collision regression failed before the disjoint diagnostic name fixed it. CI, clean-commit artifacts and same-candidate host/device acceptance remain separate required evidence.

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

All bounded support owners returned receipts. Actual latest evidence is in [PMO #13](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5572409181), [new-candidate phone safe stop](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5577852864), [PC IPv4 probe](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5577992935), [host P002/upload and cleanup](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5578040869) and [offline negatives](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5577795592). These receipts, not task-title/status inference, determine completion.

## Device, privacy and parallel-work boundary

The initial C-tree hold was cleared by PO and fresh live checks. The user confirmed the upstream Wi-Fi is public; it was not relabelled trusted, and no new firewall rule was added. #18 alone used a temporary native PC hotspot with protected original-config backup and rollback, then completed normal Wi-Fi device runs. Final cleanup restored the original phone network and hotspot configuration, left hotspot off/client count zero, stopped owned app/server/listeners, removed temporary forwarding/capture/test state and returned to Launcher (then asleep). No unknown session, business data or unrelated sharing service was stopped. Full stable device identifiers and credentials must not enter public receipts. Future device work requires a new live preflight.
ANEB_CC has separately recorded research activity. Its natural observations, shaping status and corpus qualification remain separate from GPT gates. This update neither revokes its existing authorization nor grants new device/driver/shaping/server/cross-repository changes. It is not a G3/G4 dependency. No whole-repository merge, second writer/controller, Bridge or Relay is opened.

## Next checkpoint

Finish this single publication retry/conflict repair, regression tests, full quality gate and product PR. Rebuild APK/server/verifier from one clean fix commit, issue new ZIP/admission hashes, then reassign only original same-candidate G3-G5 acceptance and missing section-8 checks. Remote LAN permission and actual symlink capability remain unresolved; do not disable protections or change a public network's trust label to manufacture PASS. Preserve `8fe6cc4` real reports and `27ced1c` host/device receipts as history. No measurement, null-reason or P018 error shape is changed. Never combine versions into PASS. G6 remains a PO decision.
No workload, scoring, evidence schema, claim or G2–G5 criterion changes. No new defensive audit program, LifecycleOnly retry or separate status PR. Historical details remain in Git and linked issue evidence.
