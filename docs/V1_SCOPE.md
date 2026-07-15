# ANEB Probe Version 1 scope

Status: Product Owner approved baseline direction on 2026-07-15.

Complexity: Ambitious.

## Version 1 must have

- Native Android app in Kotlin/Compose; no WebView product shell.
- Real Home → Connecting → Testing → Result state flow backed by `TestEngine`.
- Quick and forensic modes with explicit confidence and validity states.
- AQS and T/U/C/N KPI presentation using the frozen v0.2.2 semantics.
- Local Room history, result detail, JSON/CSV export, and native share card.
- Test-node selection, reachability status, settings persistence, cancellation, offline, timeout, permission-denied, and background/resume handling.
- No-key AI endpoint reachability and optional keyed API probes as separate evidence; neither enters AQS.
- Domestic Probe operation, privacy-safe evidence, release signing readiness, and a repeatable quality gate.

## Later, not Version 1

- Public experience map and crowdsourced location aggregation.
- VpnService traffic observation (D-24 remains in force).
- Overseas nodes, CAMARA QoD, cloud accounts/sync, subscriptions, and payments.
- iOS.

These items stay out until Version 1 measurement integrity, real-device acceptance, retention, and operating cost are known.

## Primary user flow

```text
Open app
  -> review selected network and Probe node
  -> start test
  -> connecting and environment validation
  -> live response/stream/upload phases
  -> result with AQS, confidence, scope, and user-readable verdict
  -> save locally
  -> inspect details, export, share, or retest
```

## Version 1 definition of done

- No mock timers or hard-coded result values in production routes.
- Unit tests, Debug Lint, Debug APK, and Go server tests pass from one command.
- No release-blocking Lint errors; tracked warnings have an owner and decision.
- All error and nullable states render without fake zero values.
- 375×667 and larger phone layouts have no horizontal overflow or clipped primary actions.
- Physical-device and public-node gaps remain `BLOCKED_EXTERNAL`; they are never converted to PASS.

