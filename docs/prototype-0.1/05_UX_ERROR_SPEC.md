# 05 — UX and Error Specification

Status: **Draft for G0 approval**  
Primary issues: #16 and #17

## 1. UX objective

A researcher who has the release ZIP and P40 Pro should understand what to do, what ANEB is measuring, whether the run is valid and where the evidence was saved without reading source code.

The prototype should feel deliberate and trustworthy, but visual expansion must not delay the end-to-end path.

## 2. Windows launcher flow

### 2.1 Start

The release root contains one obvious entry point:

```text
START_ANEB.bat
```

Double-click behavior:

1. show product/release version and package hash state;
2. run preflight;
3. start the local node;
4. wait for health and exact capability response;
5. print a READY panel with node URL, output path and shutdown action.

Recommended READY output:

```text
ANEB Prototype 0.1 — READY
Node URL: http://192.168.1.20:18088
Android: open ANEB > Prototype Mode > enter this URL
Results: C:\...\results
Scope: deterministic application-layer synthetic conditions
Press Q to stop ANEB cleanly.
```

Do not flood the primary view with build logs. Detailed logs go to `launcher.log`/`run.log` and are available through a diagnostic command.

### 2.2 Preflight states

- `PASS`: required capability verified.
- `WARN`: prototype can continue with a documented limitation.
- `FAIL`: execution cannot continue safely.

Required checks:

- manifest integrity;
- supported Windows architecture;
- output directory writable;
- port available;
- server executable starts;
- health endpoint returns expected version;
- at least one usable LAN address discovered;
- local firewall reachability guidance available.

A missing LAN address may offer ADB reverse as a development fallback, but normal release acceptance remains blocked until a LAN path is available.

## 3. Android information architecture

Prototype 0.1 adds an explicit **Prototype Mode** entry without replacing existing ANEB modes.

### Screen A — Prototype overview

Show:

- what the test does;
- synthetic application-layer disclosure;
- selected node URL and connection state;
- buttons: `Configure node`, `Start Quick`, `Start Acceptance`;
- latest Prototype campaign, if any.

Do not place AQS or formal-grade language on this screen.

### Screen B — Node setup and contract check

Fields/actions:

- node URL;
- saved-node selector;
- `Test connection`;
- optional `Use ADB reverse fallback` in developer/diagnostic area.

Successful validation shows:

- server version;
- workload version/hash abbreviated;
- three supported conditions;
- evidence schema and score policy;
- green `Compatible` state.

A reachable but incompatible node must show `Connected, incompatible` and disable campaign start.

### Screen C — Campaign confirmation

Show:

- mode: Quick or Acceptance;
- run count: 3 or 9;
- fixed order;
- approximate planned duration as a non-guaranteed estimate derived from schedules;
- destination for evidence;
- persistent disclosure.

Primary action: `Start campaign`.

### Screen D — Live execution

Show only actionable live state:

- current condition and run, e.g. `Slow — run 2 of 3`;
- overall completed runs, e.g. `5 / 9`;
- current phase: connecting, waiting for first event, streaming, finalizing, saving;
- live TTFT after first event;
- live logical event rate labeled `events/s`;
- stall indicator when a qualifying gap is observed;
- `Cancel` action.

The app must survive Activity recreation and background/resume through the existing foreground service pattern.

### Screen E — Results

Top section:

- campaign status;
- campaign mode;
- confidence;
- synthetic-scope badge;
- evidence integrity state.

Condition comparison:

- TTFT;
- completion;
- event rate;
- stall count/duration;
- success rate;
- RPI-0.1.

Each metric has a plain-language definition link. Null values render `—` plus the machine reason, never zero.

Actions:

- `Open report`;
- `Share evidence`;
- `View runs`;
- `Run again`.

### Screen F — Run detail

Show:

- run id;
- condition/version/hash;
- terminal status;
- sequence/receipt validation;
- per-run metrics;
- failure reason and suggested action;
- raw evidence export entry.

## 4. Result wording

Allowed examples:

- `Slow condition increased median first-event time relative to this campaign's Baseline.`
- `Unstable condition produced two detected stream stalls.`
- `Relative Prototype Index compares synthetic conditions within this campaign.`

Forbidden examples:

- `Your 5G network is poor.`
- `Kimi requires less than 200 ms.`
- `3% packet loss was simulated.`
- `Operator score: 72.`
- `AI model inference was slow.`

## 5. Error contract

Every blocking error includes:

- stable code;
- short title;
- one primary cause;
- one recommended next action;
- diagnostic detail/copy action;
- whether existing evidence was retained.

### Required error codes

| Code | Condition | Primary user action |
|---|---|---|
| `P001_PACKAGE_INTEGRITY` | release file missing or hash mismatch | re-extract/download fixed package |
| `P002_OUTPUT_NOT_WRITABLE` | result directory cannot be written | choose/move to writable directory |
| `P003_PORT_IN_USE` | configured server port unavailable | use displayed alternate port or stop conflict |
| `P004_SERVER_START_FAILED` | packaged node exits before health | open diagnostic log |
| `P005_NO_LAN_ADDRESS` | launcher cannot identify usable LAN URL | connect PC/phone to same LAN or use labeled fallback |
| `P006_NODE_UNREACHABLE` | Android cannot reach node | verify URL, LAN and firewall |
| `P007_CONTRACT_MISMATCH` | profile/schema/policy/version incompatible | use APK and server from same release |
| `P008_STREAM_INTERRUPTED` | stream ended without valid terminal receipt | retry; partial evidence retained |
| `P009_INVALID_SEQUENCE` | missing/duplicate/out-of-order event | keep evidence and report implementation defect |
| `P010_CAMPAIGN_CANCELLED` | user cancelled | view partial evidence or restart |
| `P011_EVIDENCE_UPLOAD_FAILED` | Android could not upload canonical record | export local evidence and retry finalize |
| `P012_FINALIZE_FAILED` | bundle/report verification failed | preserve `.partial` directory and open log |
| `P013_STORAGE_LOW` | insufficient storage for evidence | free space and retry |

Server-side internal errors may use additional codes but must map to one user-facing primary code.

## 6. Partial and failed campaigns

A partial campaign is not visually treated as a successful comparison.

Required behavior:

- show completed/failed/not-started runs;
- retain and expose available raw evidence;
- RPI values remain unavailable;
- primary action is `Retry incomplete campaign` or `Start new campaign`;
- never auto-fill missing runs from a previous campaign.

## 7. Minimum polish and layout quality

- no horizontal overflow at 375 × 667 logical pixels;
- primary actions remain visible with large font settings;
- screen readers receive meaningful labels for condition, progress and metric units;
- touch targets follow existing Android accessibility conventions;
- long hashes truncate visually but copy in full;
- live charts are optional; textual metrics and progress are mandatory;
- no remote assets or fake demo values in production routes;
- animations must not delay measurement clocks or block cancellation.

## 8. Installation guide

`README_FIRST.md` must contain no more than five primary steps:

1. unzip release;
2. run launcher;
3. install/open APK;
4. enter displayed node URL and run Quick;
5. open the generated report.

Troubleshooting follows after these steps and is organized by the stable error codes above.
