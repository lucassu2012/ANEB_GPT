# S2 Token lifecycle Adapter contract

## 1. Evidence correction

- [KNOWN|HIGH] D-108 / S2-V4g completed the Token READY publisher migration,
  31-file tooling closure, local full gate, and clean CI run `30303472975` at
  commit `c3f1d117bc40422d7226b3fbff3c8727f6924649`.
- [KNOWN|HIGH] At the D-108 boundary, Token did not yet use
  `quick_collection_workflow.py` or a `QuickCollectionContract`; Realtime and
  Network did.
- [KNOWN|HIGH] Therefore D-108 was complete while S2 remained open; D-109 is
  the migration recorded below.

## 2. Target Module and seam

`WorkflowTrace` is the family-neutral deep Module. Its Interface accepts an
ordered, canonical lifecycle trace and returns retained failures plus publish
eligibility. Its Implementation alone owns phase ordering, duplicate/missing
phase rejection, cleanup requirements, and multi-failure retention.

Adapters at this seam:

1. Realtime/Network: the existing Python callback executor records the same
   trace while invoking its live backend.
2. Token: the existing PowerShell executor records the real phase outcomes and
   calls a bounded Python CLI before publication.

Token business checks, persistent SSH/logcat/proxy handles, cleanup retry
counts, evidence bytes, failure codes, demotion rules, and READY publication
stay in the Token Adapter Implementation.

## 3. S2-V5a shadow migration

1. Add immutable `token_quick_contract()` without changing the two existing
   family contracts.
2. Add the WorkflowTrace evaluator and canonical CLI.
3. Make the existing Python `run_workflow` Adapter derive its result from the
   same evaluator without changing callback order.
4. Token appends actual outcomes without reordering its current try/finally.
5. Token classifies existing named cleanup failures into phone and remote
   outcomes while preserving the current combined cleanup report.
6. Before publication, Token requires both its historical gate and the shared
   publish-eligibility result to pass. Any mismatch is fail-closed.
7. Add the trace Module/CLI to collector, bundle verifier, and fixture tooling
   closure as one exact set.

## 4. Explicit non-goals

- Do not move Token 20/3/1, receipt-missing, Room, audit, log ordering, or score
  semantics into the shared Module.
- Do not serialize or hand off live SSH, logcat, proxy, or ADB handles between
  Python and PowerShell processes.
- Do not reorder the Token cleanup sequence in S2-V5a.
- Do not rerun the frozen EC1 P40 READY evidence for this mechanical refactor.
- Do not call S2 complete until shadow equivalence, three-family regression,
  full local quality gate, and clean CI all pass.

## 5. Verification ladder

1. RED/GREEN: Token immutable contract.
2. RED/GREEN: successful trace and publish eligibility.
3. RED/GREEN: primary failure, both cleanup failures, publish failure,
   missing/duplicate/out-of-order phases, and owned-resource preflight policy.
4. RED/GREEN: canonical CLI, duplicate JSON key, NaN, UTF-8, and output bounds.
5. RED/GREEN: Token PowerShell shadow gate and mismatch fail-closed behavior.
6. Token collector/bundle/release plus Realtime/Network cross-family regression.
7. PowerShell AST, direct CLI, frozen READY consumer, full quality gate, clean
   commit CI.

## 6. Current evidence and remaining gates

- [KNOWN|HIGH] The production tooling closure is now 33 files: the prior
  D-108 31-file set plus the WorkflowTrace core and canonical CLI.
- [KNOWN|HIGH] Token collector is 95/95 PASS with one platform skip; the
  independent Token bundle verifier is 123/123 PASS with two platform skips.
- [KNOWN|HIGH] The 11-module shared/Token/Realtime/Network regression is
  150/150 PASS with four platform skips. Every coordinated pre/post process
  scan was zero.
- [KNOWN|HIGH] PowerShell AST parsing, four production-module `py_compile`,
  `git diff --check`, and the 687-file repository secret scan pass.
- [KNOWN|HIGH] The full local quality gate passes: 846 main Python tests with
  16 platform skips, 44 additional Python tests, Android, Go, release/spec,
  packaging, and secret gates all pass; pre/post process scans are zero.
- [KNOWN|HIGH] These results close implementation, focused regression, and the
  full local gate. Clean commit CI and the final S2 closure audit remain
  required.
