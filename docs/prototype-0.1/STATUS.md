# ANEB Prototype 0.1 — Program Status

> Post-release update, 2026-09-11: the unchanged Prototype 0.1 is published and P01 has completed an actual Quick3/Acceptance9 trial. N3 is the bounded Chinese-flow/Windows-guidance follow-up under [#61](https://github.com/lucassu2012/ANEB_GPT/issues/61), with a [separate acceptance checklist](../pilot-readiness/N3_ACCEPTANCE.md). It has **no new signed release or device PASS yet**. The release identity and G0–G6 receipts below remain historical facts for 03675a9, not evidence for a new N3 binary. This update ships with the product change, not a standalone per-merge status PR.

Last PMO update: **2026-09-09 (Asia/Shanghai)**. Program issue: **#13**. Product Owner: **Lucas**.

**Release-ready: G0–G5 complete on the fixed 03675a9 candidate; PO release acceptance recorded.** Remaining PMO action is publication of the unchanged verified ZIP and its two external receipts, followed by safe closure. No implementation or device test is active. This is the single final release handoff, not a per-merge status PR.

## Gate board

| Gate | State | Authority |
|---|---|---|
| G0 — specification | PASS | #14 closed; PR #23/#26 merged; frozen contracts unchanged |
| G1 — deterministic Core | PASS | #15 closed; PR #27 merged |
| G2 — Android | PASS | G2-A/B/C PR #51/#52/#53; bounded publication/recovery fixes #57/#58/#59; final source 03675a9, full quality/signing gates and CI below |
| G3 — fixed Windows runtime | PASS, same 03675a9 | Original ordinary-user BAT, Chinese/space extraction, READY and normal Q/RC0; package/admission verification; P001/P002 and idempotent/conflicting publication receipts below |
| G4 — evidence/report | PASS, same 03675a9 | [Original nine vectors and actual symlink rejection](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5588636391), plus all four new native-LAN canonical campaigns |
| G5 — P40 acceptance | PASS, same 03675a9 | [Consolidated items 1–10](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5589818454): controlled migration, normal LAN, Quick3, Acceptance9, negatives, no stale reuse and cleanup |
| G6 — Product Owner | AUTHORIZED | PO explicitly accepted publication, safe completion and retaining the complete verified ZIP filename. No gate waiver or new scope. Publication URL/status is recorded in the existing #13 control-plane comment. |

## Immutable release identity

- ZIP: **ANEB-Prototype-0.1-rc-20260909-03675a9-windows-x64.zip**, 38,889,601 bytes.
- Actual artifact source: **03675a93b9b39c122680204256814740f7f6e178**.
- Artifact source tree: **fcf5a5db530a9eefc5a67a8d69d64c5f3342b360**.
- [PR #59](https://github.com/lucassu2012/ANEB_GPT/pull/59) merge **90602c712e7a9c9e8fcc1b68a782ba3d4f7e52d9** has the identical tree.
- APK: `com.aneb.probe`, version `0.2.0`, code `20`; product title remains **ANEB Prototype 0.1**.
- Approved signer certificate SHA-256: `b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003`.
- A later documentation commit is **not** the source identity of these binaries. No rename, repack, mixed-source receipt or old-candidate device PASS inheritance.

| Artifact | SHA-256 |
|---|---|
| ZIP | `ec1bd2c406ce2610b915396cba0de614281df58c81a16800ea8f34158545f3ca` |
| APK | `93d56810103875f395d3a8493f6eff7dac747f6f6e4a90b7bf95f2095407eda4` |
| Server | `d70646452fb3be39b9be29c2b4358aa87db7dd82c6b47ef7f618ebbe074474bf` |
| Verifier executable | `70b3ab929b860c219736733a45f7399b918648ca7f166fb7c2484d0c8fe5cd20` |
| Verifier runtime tree | `54d3ce885a2b788396d7be8826a661ae83b81e85270761be0f7f22e6ee11cb3d` |
| External admission receipt | `f82a2d1542048d8f4d7f8d2491270a511a81f265c1b8c62480db46c513c8690e` |
| Artifact build receipt | `c1786b46ffc930d9d77b79100907969aebbbb72d2b6f8cdb18a3eaceaef97138` |

## Build and actual acceptance evidence

The original complete quality gate passed: Android **791 tests, 0 failures, 0 errors, 2 existing ignored**, lint and assemble; fresh uninstrumented Go tests passed with GOFLAGS empty. The isolated signed variant passed the same Android count, signing/source checks, lint and assemble. Independent signature/provenance and original package/admission checks passed. [Review CI 34250702541](https://github.com/lucassu2012/ANEB_GPT/actions/runs/34250702541) and [post-merge CI 34251411228](https://github.com/lucassu2012/ANEB_GPT/actions/runs/34251411228) each passed all three jobs. No diagnostic overlay was released.

| Actual case | Evidence and boundary |
|---|---|
| Controlled migration / Saved navigation | [5588651776](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5588651776): same-signer install without data clearing; old cancelled ID/node/null RPI preserved and reopened twice. This is not a fresh uninstall or reclassified old measurement. |
| Native-LAN Quick | [5589362457](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5589362457): `04e11584-ac6b-4c1b-ae27-674fdcccc9b7`, 3/3, each 120 content events plus valid terminal; canonical seven files; packaged verifier RC0; Export saved acknowledgement. |
| Native-LAN Acceptance / Share | [5589508670](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5589508670): `dd888c1c-9624-4e46-9427-3ce03ea61670`, 9/9 and packaged verifier RC0; Export saved; prior Quick's native share chooser independently observed, no recipient/send or Quick rerun. |
| P006 / Cancel / P008 recovery | [5589718698](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5589718698): wrong URL rejected before campaign; cancellation retained 55 events and two not-started runs; stream interruption retained 27 events without terminal and null RPI. Same-node restart and explicit saved Retry recovered publication without converting failure into success. Both new bundles verified RC0; prior 14 positive file hashes unchanged. |
| P007 | [5589784072](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5589784072): exact new APK rejected the original mismatch vector, GET1/POST0 and disabled run buttons. **Engineering adapter/USB-only negative**, not normal-LAN G5 or packaged-server evidence. |
| P002 unwritable results | [5589583184](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5589583184): original launcher rejected before campaign; exact ACL restored and write probe passed. |
| P001 tampered artifact | [5589859944](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5589859944): isolated README byte change rejected as package integrity mismatch; original package untouched. |
| Duplicate / conflict | [5589918604](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5589918604): original BAT; direct-loopback requests 200/200/500-P018; identical receipt and unchanged canonical seven files; one 1,052-byte digest-only diagnostic; normal Q/actual RC0 and no owned listener. Synthetic host case, not phone measurement. |

Canonical manifest SHA-256 values are recorded verbatim in the linked Quick and Acceptance receipts. The full canonical directories, not isolated report screenshots, remain the evidence authority.

## Runtime, cleanup and limitations

Normal product runtime used original packaged files under an ordinary user, without developer toolchains or Internet. First LAN access on this host required legitimate, temporary administrator configuration restricted to the exact program, peer and TCP port; this was separate from runtime. Windows remained Public, protection was not disabled, and original TCP/UDP block rules were restored. All owned allow rules, helpers, servers, forwarding and app test resources were removed/stopped; P40 returned to Launcher then asleep. No E-01/Aliyun change.

Device Export/Share remains an explicitly **unverified five-file backup**. Canonical node results have seven files. Local Prototype evidence publication is not cloud upload; legacy cloud results upload remains disabled. Missing/failed evidence is null, not zero. RPI is application-layer synthetic comparison, not MOS, RAN/IP-layer measurement, third-party App/model quality, operator rating or SLA.

Historical 799 cancellation P018 and one earlier synthetic Go500 remain recorded with unknown cause. The replacement fixes Saved navigation; it does not claim to explain those historical failures. The first host conflict request returned proxy-path HTTP502; it was preserved, then the explicitly scoped localhost invocation passed. No blind retry, proxy/security setting change or product-code patch was used.

## Ownership and closure

#14/#15 complete; #16 APP, #17 RELEASE and #18 QA delivery evidence complete and idle pending administrative closure. #13 alone publishes the accepted artifacts and closes the program. #19 remains independent/non-blocking; no real-App research is folded into this release. #21/#22 remain closed; #25, v1/cloud/Relay and old verifier lines remain frozen.

See [final installation/rollback/maintenance handoff](RELEASE_HANDOFF.md) and the existing [#13 release record](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5572409181). No workload, RPI, evidence schema, claim or G2–G5 criterion was changed. No further campaigns, defensive matrices, LifecycleOnly retries or per-merge status PRs are authorized by this handoff.
