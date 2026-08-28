# 08 — Decision Log Proposal

Status: **Product Owner direction approved on 2026-08-28; pending insertion into `docs/DECISION_LOG.md` during G0**

The following rows are proposed as the next decisions after D-37 on the `main` baseline. The final IDs must be rechecked immediately before insertion to avoid collision with concurrently merged history.

| Proposed ID | Date | Decision | Reason / source |
|---|---|---|---|
| D-38 | 2026-08-28 | **ANEB first delivery is narrowed to a usable Prototype 0.1 rather than the complete standards platform.** The only product baseline is `ANEB_GPT/main`, integrated through `product/prototype-0.1`. `ANEB_CC` becomes a reference, selective-backport and P40 acceptance lane; it must not remain a competing product or be merged wholesale. Prototype 0.1 supports one Windows PC, one Huawei P40 Pro, one self-hosted deterministic streaming workload, three conditions, machine-readable evidence and an offline report. Third-party App automation, full taxonomy, 720-run matrix, multi-device coverage and commercial features are outside scope. | Product Owner approved the ANEB project audit recommendation on 2026-08-28; issue #13; `docs/prototype-0.1/00_PRODUCT_SPEC.md` |
| D-39 | 2026-08-28 | **Prototype 0.1 degradation is deterministic and application-layer only.** It uses versioned initial delay, logical event pacing and scheduled application pauses for `streaming_text_reference_v0.1`; it does not implement or claim IP packet loss, RAN impairment, operator SLA evidence or real vendor behavior. Existing `claim_scope=application_end_to_end_to_probe_node` remains; evidence adds `evidence_mode=synthetic_application_impairment` and `impairment_layer=application`. Windows hotspot, administrator rights, tc/netem and mandatory PCAP are removed as release prerequisites. | Product Owner priority is a usable prototype; `02_WORKLOAD_IMPAIRMENT_SPEC.md`; issue #15 |
| D-40 | 2026-08-28 | **Approve experimental Relative Prototype Index policy `rpi-0.1` strictly for same-campaign synthetic comparison.** It is independent from AQS and all formal ANEB scores. Mandatory missing measurements yield null; incomplete campaigns have null RPI; successful-condition aggregation uses the frozen TTFT/completion/stall definitions and explicit success-rate penalty. No letter grade or industry/vendor claim is permitted. Any semantic change requires a new policy version and decision-log entry. | Product Owner approved the prototype recommendation; `03_METRICS_SCORING_SPEC.md`; issues #14/#16 |
| D-41 | 2026-08-28 | **GitHub issues, versioned specs, PRs and evidence are the cross-conversation source of truth.** Current ANEB conversations are split into PMO (#13), specification (#14), Go core (#15), Android app (#16), release (#17), ANEB_CC reuse/P40 acceptance (#18) and non-blocking real-App Prototype 0.2 research (#19). A chat-only completion claim does not pass a release gate. Final release requires G0–G6 and Product Owner acceptance. | Need to prevent scope drift and incompatible parallel implementations; `07_PROGRAM_GOVERNANCE.md`; `06_RELEASE_ACCEPTANCE_SPEC.md` |

## Insertion procedure

1. Re-read the current tail of `docs/DECISION_LOG.md` on the integration target.
2. Renumber the proposed rows if a concurrent decision already uses D-38 or later.
3. Insert by append-only edit before the rejection-record section.
4. Do not modify or rewrite earlier decisions.
5. Update any internal references if IDs change.
6. Link the insertion commit from issue #14 and #13.
