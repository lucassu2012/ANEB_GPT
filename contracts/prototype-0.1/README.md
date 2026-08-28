# ANEB Prototype 0.1 — Machine-Readable Contracts

Status: **Draft until G0 approval and merge into `product/prototype-0.1`**

These files are the executable counterparts of `docs/prototype-0.1/`:

- `profile-manifest.json` — frozen workload, condition parameters, campaign order and canonical schedule hashes;
- `score-policy.json` — `rpi-0.1` eligibility, weights, null reasons and confidence rules;
- `capabilities.schema.json` — fail-closed server capability response contract;
- `run-record.schema.json` — canonical Android per-run measurement record.

## Rules

1. Implementations must consume or verify these contracts; they must not duplicate their values as an unversioned second truth source.
2. A semantic change to timing, metrics, score eligibility, claim scope or evidence fields requires a version increment and Product Owner decision.
3. JSON objects are UTF-8. Contract artifact hashes use SHA-256 over the exact checked-in bytes unless a contract defines a more specific canonicalization.
4. The condition `schedule_sha256` values are computed from the canonical CSV described in `profile-manifest.json` and `02_WORKLOAD_IMPAIRMENT_SPEC.md`.
5. Unknown mandatory versions and fields fail closed in the formal Prototype flow.
6. Missing measurements remain JSON `null`; they are not converted to zero, timeout ceilings or sentinel numbers.
7. These contracts do not authorize AQS changes, IP-layer impairment claims, vendor-App claims or formal ANEB industry scoring.

## Required implementation tests

- regenerate and verify all three canonical schedule hashes;
- validate a known-good capability document and reject version/hash/claim mismatches;
- validate successful, interrupted, cancelled and not-started run records;
- prove `task_success=true` requires complete status, 120 events and a valid terminal receipt;
- prove `score_eligible=true` requires task success;
- load `score-policy.json` or verify an exact embedded copy before calculating RPI.
