# ANEB Prototype 0.1 — Specification Index

Status: **Product scope approved; technical specs under G0 review**  
Product Owner decision date: **2026-08-28**  
Product baseline: `ANEB_GPT/main`  
Integration branch: `product/prototype-0.1`  
Program issue: **#13**

## Product statement

ANEB Prototype 0.1 is a usable research prototype, not a commercial product and not a completed industry benchmark. It demonstrates a complete, repeatable path from a Huawei P40 Pro to a self-hosted ANEB node under deterministic application-layer streaming conditions, then publishes machine-readable evidence and an offline HTML report.

The prototype must answer one narrow question:

> Can ANEB run a deterministic streaming-text reference workload under clearly different synthetic application-layer conditions and consistently measure the resulting user-visible timing degradation?

## Binding specification set

| File | Purpose | Primary workstream |
|---|---|---|
| `00_PRODUCT_SPEC.md` | scope, user outcome, non-goals and Definition of Done | PMO / #13 |
| `01_ARCHITECTURE_SPEC.md` | component boundaries, runtime topology and branch strategy | CORE + APP + RELEASE / #15–#17 |
| `02_WORKLOAD_IMPAIRMENT_SPEC.md` | deterministic reference workload and three conditions | SPEC + CORE / #14–#15 |
| `03_METRICS_SCORING_SPEC.md` | metric clocks, aggregation, confidence and RPI-0.1 | SPEC + APP / #14 + #16 |
| `04_EVIDENCE_REPORT_SPEC.md` | evidence directory, schemas, atomic publication and report | RELEASE + QA / #17–#18 |
| `05_UX_ERROR_SPEC.md` | Windows and Android flow, error states and minimum polish | APP + RELEASE / #16–#17 |
| `06_RELEASE_ACCEPTANCE_SPEC.md` | packaging, quality gates and P40 acceptance | RELEASE + QA / #17–#18 |
| `07_PROGRAM_GOVERNANCE.md` | conversation routing, status protocol and change control | PMO / #13 |
| `08_DECISION_LOG_PROPOSAL.md` | append-only Product Owner decision proposal for G0 | SPEC + PMO / #14 + #13 |
| `STATUS.md` | live program status and dependency board | PMO / #13 |

## Source-of-truth order

When documents or conversations disagree, use this order:

1. Product Owner decisions recorded in `docs/DECISION_LOG.md`.
2. This versioned Prototype 0.1 specification set.
3. Accepted GitHub issues and merged pull requests.
4. Existing ANEB V1 documents where they do not conflict with the narrower prototype scope.
5. Chat messages and historical handoffs as non-binding context.

No conversation may silently change a metric, score, evidence field, claim boundary or release gate. Proposed changes must be written against #13, include impact on dependencies, and receive Product Owner approval before implementation.

## Repository roles

- **ANEB_GPT**: the only Prototype 0.1 product repository.
- **ANEB_CC**: read-only reference plus selective backport and acceptance lane under #18. It is not a second product, integration target or source of alternate scoring truth.

## Workstream issues

- #13 — program control and final acceptance
- #14 — specification review and measurement contract
- #15 — deterministic Go workload and application-layer impairment engine
- #16 — Android Prototype Mode and campaign execution
- #17 — Windows launcher, exporter and HTML report
- #18 — selective ANEB_CC reuse and Huawei P40 Pro acceptance
- #19 — Prototype 0.2 real-App manual protocol; non-blocking for 0.1

## Versioning

- Product version: `prototype-0.1`
- Workload: `streaming_text_reference_v0.1`
- Conditions: `baseline_v0.1`, `slow_v0.1`, `unstable_v0.1`
- Evidence schema: `aneb-prototype-evidence-0.1`
- Relative score policy: `rpi-0.1`

Any semantic change to one of these contracts requires a version increment. Formatting-only edits do not.
