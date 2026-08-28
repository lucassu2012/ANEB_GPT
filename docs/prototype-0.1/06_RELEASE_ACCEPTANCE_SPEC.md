# 06 — Release and Acceptance Specification

Status: **G0 rework — reviewable exact head**
Primary issues: #17 and #18

## 1. Release unit

The accepted product is one immutable ZIP directory, not a source checkout, local build folder, loose APK or development branch.

Recommended candidate naming:

```text
ANEB-Prototype-0.1-rc.<n>-windows-x64.zip
```

Final naming:

```text
ANEB-Prototype-0.1-windows-x64.zip
```

Every candidate is bound to exact app, server, profile, condition, evidence-schema
and score-policy versions. The contract package contains exactly the four
existing machine contracts listed below; no `evidence-schema.json` artifact is
invented for Prototype 0.1.

## 2. Required package layout

```text
ANEB-Prototype-0.1/
├── START_ANEB.bat
├── README_FIRST.md
├── VERSION.json
├── SHA256SUMS.txt
├── bin/
│   └── aneb-server.exe
├── android/
│   └── aneb-prototype-0.1.apk
├── contracts/
│   ├── profile-manifest.json
│   ├── capabilities.schema.json
│   ├── run-record.schema.json
│   └── score-policy.json
├── static/
│   └── report assets embedded or local
├── tools/
│   └── verify-package executable or script using built-in Windows facilities
└── results/
```

No absolute developer paths may appear in launchers, configs, manifests, reports or instructions.

## 3. Runtime dependency policy

Normal runtime must not require:

- Python;
- Node/npm;
- Java/Gradle;
- Go toolchain;
- Android SDK/ADB;
- Docker/WSL;
- administrator rights;
- an internet connection.

The user installs the APK manually from the package and connects over the local network. ADB tooling may be documented separately for developers but is not a release dependency.

The launcher may use standard Windows `cmd.exe` and PowerShell capabilities already present on supported Windows versions. Any PowerShell use must avoid external module installation.

## 4. `VERSION.json`

Required fields:

```json
{
  "product_version": "prototype-0.1",
  "release_candidate": "rc.1",
  "source_commit": "<40-hex>",
  "built_at_utc": "<RFC3339>",
  "server_version": "<string>",
  "android_version_name": "<string>",
  "android_version_code": 1,
  "workload_id": "streaming_text_reference_v0.1",
  "condition_versions": [
    "baseline_v0.1",
    "slow_v0.1",
    "unstable_v0.1"
  ],
  "contract_files": [
    "profile-manifest.json",
    "capabilities.schema.json",
    "run-record.schema.json",
    "score-policy.json"
  ],
  "contract_hashes": {
    "profile-manifest.json": "<bare lowercase 64-hex>",
    "capabilities.schema.json": "<bare lowercase 64-hex>",
    "run-record.schema.json": "<bare lowercase 64-hex>",
    "score-policy.json": "<bare lowercase 64-hex>"
  },
  "schedule_hashes": {
    "baseline_v0.1": "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
    "slow_v0.1": "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
    "unstable_v0.1": "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58"
  },
  "evidence_schema": "aneb-prototype-evidence-0.1",
  "score_policy": "rpi-0.1"
}
```

## 5. Quality gates

### G0 — Specification freeze

PASS when:

- all Prototype spec files have no release-blocking `TBD`;
- issue #14 records an approval verdict;
- Product Owner decisions are appended to `docs/DECISION_LOG.md`;
- specs PR is merged into `product/prototype-0.1`.
- the exact schedule-byte vectors, profile-manifest binding and four-contract
  package fixture contents are verified; the actual packaged ZIP/layout remains
  a G3 acceptance responsibility. G0 remains HOLD until the issue #14 reviewer
  records this exact head.

### G1 — Core contract

PASS when:

- deterministic schedule hashes are stable and reproduce from the published
  canonical bytes (UTF-8/no BOM/LF/final LF/120 content rows/terminal excluded);
- capabilities and run-record positive/negative fixtures reject duplicate
  conditions, prefixed hashes, invalid indexes and null-success counterexamples;
- Quick indexes 1..3 and Acceptance indexes 1..9 occur exactly once in the
  frozen B/S/U plan; summary counts and success rates stay within that plan;
- all three condition plans pass unit/integration tests;
- unknown contracts fail closed;
- server emits valid terminal receipts;
- existing server tests pass;
- exact commit/PR is recorded in #15.

### G2 — Android vertical slice

PASS when:

- Prototype Mode validates capability, runs Quick and Acceptance plans and persists evidence;
- metrics and RPI test vectors pass, including arithmetic even median, strict
  stall equality/`+1ns`, UTC independence, clock-domain invalidation, null-not-
  zero and RPI/AQS independence;
- cancellation/background/resume and incompatible-node paths pass;
- Android unit tests, lint and Debug assemble pass;
- exact APK hash is recorded in #16.

### G3 — Packaged runtime

PASS when:

- fixed ZIP contains all required files;
- package and manifest verification pass;
- launcher works from a path containing spaces and non-ASCII characters;
- runtime uses no developer-local dependency;
- local server becomes READY and shuts down cleanly;
- report generation works offline.

### G4 — Evidence consistency

PASS when an automated verifier confirms:

- all mandatory files exist;
- hashes and sizes match;
- campaign/run/condition identities agree across files;
- every `runs.csv` row reconstructs and passes the formal
  `run-record.schema.json` contract before recomputation;
- summaries recompute from run records;
- report values agree with canonical data;
- raw Android events plus the matching terminal receipt recompute per-run
  metrics before `runs.csv`, summaries/RPI and report values are accepted;
- raw event `schema_version`/clock unit, terminal receipt version/counts,
  run-start/terminal timestamps and per-run scoring-event order are validated;
- profile/condition/schedule/policy hashes, clock source/epoch/domain/t0 and
  ordered null reasons are exact;
- partial/failed campaigns cannot publish non-null RPI;
- secrets/forbidden identifiers are absent.

### G5 — Huawei P40 Pro acceptance

PASS only on the exact G3/G4 candidate:

1. remove prior Prototype app/data or document a controlled migration;
2. install exact packaged APK;
3. launch server from a fresh extracted directory;
4. connect through the normal LAN path;
5. complete one Quick campaign;
6. complete one Acceptance campaign;
7. verify all evidence artifacts;
8. execute server-unreachable, cancellation and interrupted-stream negative paths;
9. confirm no stale campaign data is reused;
10. publish issue #18 evidence and `PASS` verdict.

### G6 — Product Owner acceptance

PASS when the Product Owner reviews:

- fixed release candidate and hashes;
- five-step installation guide;
- P40 evidence;
- sample report;
- known limitations;
- no open P0 blocker.

Only G6 authorizes the final `prototype-0.1` tag/release.

## 6. Repository quality gate

Before any implementation handoff, run the repository's existing quality gate:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/quality_gate.ps1
```

Prototype work must add focused tests without weakening existing tests, lint, release/spec/result or secret gates.

If a platform-specific gate cannot run in one environment, it remains unverified there and must run in an authoritative environment before the relevant release gate can pass. It is never converted to PASS by documentation.

## 7. Fresh-environment matrix

| Environment | Requirement |
|---|---|
| Windows path | new directory with spaces and Chinese characters |
| Windows privileges | standard user |
| Network | PC and P40 on same LAN; internet not required |
| Build tools | absent/not used |
| P40 app state | fresh install or documented clean migration |
| Result state | empty result directory before run |
| Runtime | only packaged files |

## 8. Mandatory negative acceptance cases

| Case | Expected result |
|---|---|
| wrong node URL | `P006_NODE_UNREACHABLE`, no score |
| server/APK version mismatch | `P007_CONTRACT_MISMATCH`, no business run |
| stream cut before terminal receipt | partial evidence, `P008_STREAM_INTERRUPTED`, no successful run |
| user cancellation | partial campaign, RPI null |
| result directory unwritable | launcher fails before campaign |
| artifact modified after packaging | package/manifest verifier fails |
| duplicate conflicting run upload | server rejects conflict and retains diagnostic |

## 9. P0 blocker definition

A defect is P0 when it prevents or invalidates the required fresh-install path, corrupts evidence, produces a score from incompatible/missing data, leaks a secret, or makes the fixed package non-reproducible.

The following are not P0 for Prototype 0.1 unless they block the required path:

- missing QR pairing;
- absence of automated hotspot/netem/PCAP;
- unsupported second Android device;
- lack of real-App testing;
- cosmetic chart differences;
- manual APK installation;
- lack of cloud sync.

## 10. Release notes

Final release notes must state:

- exact supported environment;
- synthetic application-layer scope;
- what RPI-0.1 means and does not mean;
- known limitations;
- five-step quick start;
- evidence verification command/action;
- source commit and all artifact hashes.
