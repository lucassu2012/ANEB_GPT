# ANEB Prototype 0.1 — Machine-Readable Contracts

Status: **G0 rework — reviewable exact head**

These files are the executable counterparts of `docs/prototype-0.1/`:

- `profile-manifest.json` — frozen workload, condition parameters, campaign order and canonical schedule hashes;
- `score-policy.json` — `rpi-0.1` eligibility, weights, null reasons and confidence rules;
- `capabilities.schema.json` — fail-closed server capability response contract;
- `run-record.schema.json` — canonical Android per-run measurement record.

The terminal receipt is the exact object nested in
`terminal_event.details` in `events.jsonl`; no separate receipt file is part of
the canonical evidence bundle. Its vocabulary and field types are fixed by the
workload/evidence specifications and the validator rejects missing, extra or
renamed fields.

These four files are the complete v0.1 machine contract package. There is no
`evidence-schema.json` artifact; evidence directory/report rules remain in
`docs/prototype-0.1/04_EVIDENCE_REPORT_SPEC.md`.

## Rules

1. Implementations must consume or verify these contracts; they must not duplicate their values as an unversioned second truth source.
2. A semantic change to timing, metrics, score eligibility, claim scope or evidence fields requires a version increment and Product Owner decision.
3. JSON objects are UTF-8. Text contract identities use SHA-256 over the
   checked-in canonical bytes: no BOM and LF line endings. A Windows checkout
   may display CRLF, but the verifier hashes the canonical LF bytes.
4. Condition identity is `profile_manifest_sha256` plus `id`, `version` and
   `schedule_sha256`; `nominal_interval_ms` is independently checked against
   the manifest and schedule, but is not an additional identity component.
5. Hash identity fields are bare lowercase 64-hex SHA-256 values (no `sha256:` prefix). The three schedule values are computed from the exact canonical CSV described in `profile-manifest.json` and `02_WORKLOAD_IMPAIRMENT_SPEC.md`.
6. Unknown mandatory versions and fields fail closed in the formal Prototype flow.
7. Missing measurements remain JSON `null`; they are not converted to zero, timeout ceilings or sentinel numbers.
8. These contracts do not authorize AQS changes, IP-layer impairment claims, vendor-App claims or formal ANEB industry scoring.

## Required implementation tests

- regenerate and verify all three canonical schedule hashes;
- validate a known-good capability document and reject version/hash/claim mismatches;
- validate successful, interrupted, cancelled, zero-event failed and
  not-started run records;
- prove `task_success=true` requires complete status, 120 events and a valid terminal receipt;
- prove `score_eligible=true` requires task success;
- enforce the closed status-to-reason mapping and failed-status receipt/event/
  metric topology (including cancelled, timeout and server rejection);
- bind `mandatory_metric_missing` to the interrupted partial topology and keep
  incompatible evidence/clock reasons in the documented exception set;
- reject the published duplicate-condition, prefixed-hash, invalid-index and null-success counterexamples;
- verify Android `elapsedRealtimeNanos` boundaries, arithmetic even median and strict stall equality/`+1ns` vectors;
- recompute the seven-file campaign chain (`events.jsonl` plus nested terminal
  receipt -> run metrics -> RFC4180 CSV -> formal run-record schema ->
  summary/null reasons/RPI -> embedded report) and reject identity, receipt,
  ordering, cardinality, chronology and tamper stages; no partial-summary
  sidecar is permitted;
- enforce the normative VERSION product fields with `contract_hashes` and
  `schedule_hashes` for exactly these four contracts;
- enforce the Product Owner-approved null precedence with
  `campaign_incomplete` before `contract_mismatch` and `invalid_evidence`,
  deduplicate reasons and set `primary_null_reason` to the first value;
- reject a changed `clock_domain_id`, timestamp regression or boot-absolute t0
  splice;
- load `score-policy.json` or verify an exact embedded copy before calculating RPI.

Run the development vectors from the repository checkout with:

```powershell
python contracts/prototype-0.1/validate_contracts.py
```
