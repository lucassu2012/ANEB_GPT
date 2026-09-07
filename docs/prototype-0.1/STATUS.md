# ANEB Prototype 0.1 — Program Status

Last PMO update: **2026-09-07**
Program issue: **#13**. Product Owner: **Lucas**.
Primary deliverable: **one fixed Windows package producing a verifiable report from a real Android campaign**.
Active WIP: **#17 default-launcher P0 repair and replacement RC**. PO released P40; G5 reached a reproducible original-entry failure and is stopped pending a newly bound candidate.

## Gate board

G0 and G1 remain complete. G2-A/B/C are merged; do not reopen them as implementation projects.

| Gate | State | Evidence / remaining requirement |
|---|---|---|
| G0 — specification | PASS | #14 closed; PR #23/#26 merged; frozen contracts unchanged |
| G1 — deterministic Core | PASS | #15 closed; PR #27 merged |
| G2 — Android | PASS, implementation | PR #51 `3700caafc2211167061f653c3c5977df6d7f3294`; #52 `4756c1a3d412f6fc6c7fcc5e6494e20a7beb546e`; #53 `44b0e57ce509f80218ac2899e6f765beb41c35cd`; post-merge CI `33456258350` success. #16 administrative closure follows reconciliation. |
| G3 — fixed Windows runtime | HOLD, launcher P0 | On 2026-09-07 the original START_ANEB.bat failed without -Root under Windows PowerShell. Prior explicit-root READY/cleanup tests did not cover this user entry. Repair resolves the default inside the script body and adds a real BAT/no-Root regression; a replacement candidate is required. |
| G4 — evidence/report | IMPLEMENTED, VERIFYING | Android capture/publish, node publication and packaged verifier exist. Characterization is a fixture; the real same-candidate campaign/report remains outstanding. |
| G5 — P40 acceptance | HOLD, candidate P0 | PO released C-line activity and the subsequent narrow live check found no conflicting session. Exact installed APK matched, but no migration, data-clear or campaign ran before the launcher failed. Own app was stopped, Launcher restored and no server/rule/forwarding remained. Resume original normal-LAN Quick3, Acceptance9 and negative cases only on the replacement candidate. |
| G6 — PO acceptance | PENDING | PO must accept the fixed candidate, real report, instructions and G5 evidence. No final release/tag from build or engineering smoke alone. |

## Fixed artifact handoff

Candidate: **rc-20260901-78de945**.
Disposition: **retained unchanged for evidence; not admitted for further G5 after default-entry failure**. PR #54 merged at `97d02b171c33ecc0fd5ac91202023a5d5b3c6231`; review and merge CI both passed. Fresh Windows explicit-root checks and offline fixture publication passed, but do not overrule the actual original-entry failure. Replacement hashes will be recorded in #13/#17 after rebuilding all source-bound artifacts; no hand-edited ZIP or mixed-source receipt.
Artifact source: **78de945d7a0b44495893e7f578c1c3b557bc7767**.
Artifact source tree: **d13e8e3519fe50a19f6760c93648c7ed16cbb837**.
A later PMO documentation commit is not the source identity of these binaries.

| Artifact | SHA-256 |
|---|---|
| ANEB-Prototype-0.1-rc-20260901-78de945-windows-x64.zip | `55486441a458b53fe13d0c1889f965b541a6207754dba0061ad8e34bb81b6eca` |
| android/aneb-prototype-0.1.apk | `eb5cc0d2b829c661784c193cb9d3d9d96597d8369e0b88523a7e0911704e9cd4` |
| bin/aneb-server.exe | `ea66edcc01e53a0bd0ebae733f7d205d14f33e6e4d613e4a05f13b3333507058` |
| bin/evidence/aneb-evidence.exe | `d6828bf1845f8001245888b8008643ed89cad8bc9fb478f4efd568da883743b2` |
| External admission receipt | `b73818b6cf04b1549016445665b3b97c8cc7af12da8ff46d9df8acb555624f16` |

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
| #15 CORE | COMPLETE; handoff requested | Exact node publication entry and runtime artifacts to #17; no second server |
| #16 APP | IMPLEMENTATION COMPLETE; handoff requested | Candidate UI/publication operation map to #17/#18; no new runner/parser-only PR |
| #17 RELEASE | ACTIVE, P0 REPLACEMENT | PMO sole source writer repairs default launch; existing RELEASE task supplies handoff support, no parallel source edits |
| #18 QA | P0 HANDOFF, DEVICE CLEANED | Preserve failure evidence; no more G5 on the failed candidate; resume after replacement admission and fresh live preflight |
| #19 real-App research | NON-BLOCKING | Existing separately authorized work; no Prototype 0.1 dependency |

Four handoff messages to CORE/APP/RELEASE/QA were accepted by the sending tool. Acceptance is not acknowledgement: #18 has a durable ACK in [comment 5572308605](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5572308605), #17 in [comment 5572336126](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5572336126), and #16 in [comment 5572320540](https://github.com/lucassu2012/ANEB_GPT/issues/16#issuecomment-5572320540). CORE acknowledgement remains pending; the release and APP reviews independently confirm its publication interface. #13 records remaining confirmations as they arrive. Status queries returned queued/lookup errors; do not infer completion from the UI.

## Device, privacy and parallel-work boundary

The initial 2026-09-07 C-tree session hold was later cleared by PO and fresh live checks. Ordinary non-target messaging background services were preserved after narrow checks found no active foreground service/call/capture conflict; no business data was inspected or removed. The 9/1 PMO-owned temporary firewall rule was removed with zero remaining matches; unrelated rules/processes were untouched. After the launcher failure #18 stopped its own app and verified Launcher/no ANEB service/no forwarding; no new rule or server was created. Full stable device identifiers must not enter public issues/screenshots/receipts. The identified #18 public serial was redacted with other evidence preserved.
ANEB_CC has separately recorded research activity. Its natural observations, shaping status and corpus qualification remain separate from GPT gates. This update neither revokes its existing authorization nor grants new device/driver/shaping/server/cross-repository changes. It is not a G3/G4 dependency. No whole-repository merge, second writer/controller, Bridge or Relay is opened.

## Next checkpoint

Finish the single default-launcher repair, full quality gate and product PR. Rebuild APK/server/verifier from the one clean fix commit, issue a new ZIP/admission receipt, and test the original BAT without -Root from a new Chinese/space path. Then recheck live P40 state and obtain a real Quick report, followed by original same-candidate Acceptance9 and negative cases. Failure receipt: [#18 comment 5572728340](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5572728340). Never combine versions into PASS. G6 remains a PO decision.
No workload, scoring, evidence schema, claim or G2–G5 criterion changes. No new defensive audit program, LifecycleOnly retry or separate status PR. Historical details remain in Git and linked issue evidence.
