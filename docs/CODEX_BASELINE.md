# Codex product-line baseline

Date: 2026-07-15

## Purpose

This repository is the independent Codex product line for ANEB Probe. It combines the fuller ANEB Probe product surface with the stricter engineering and evidence discipline proven by the ANEB Android Echo slice.

It does not replace or modify either source workspace.

## Provenance

- Functional baseline: `E:\C Project\ANEB`, Git commit `c5c16c7da9561a27822f3d5f1f0d47f213cc5a27`.
- UI source of truth: `E:\G Project\ANEB\ANEB_UI`, manifest version `2026.07.15-2`.
- Validation reference: `E:\G Project\ANEB\DevSpace\aneb-android-echo-v0.1.0`.
- Destination: `E:\G Project\ANEB\DevSpace\aneb-probe-codex-v0.2.0`.

The source Git metadata, build outputs, Gradle caches, and untracked evidence were not imported. The latest UI package replaces the older design handoff in the functional baseline.

## Truth hierarchy

1. Measurement definitions and thresholds: the four authoritative documents listed in `AGENTS.md`.
2. Runtime behavior: measurement/scoring code plus passing tests and retained evidence.
3. Product presentation: `design_handoff_aneb_probe/`, constrained by the measurement claim scopes.

When the UI and measurement language conflict, measurement integrity wins and the UI contract must be corrected before native implementation.

## Frozen baseline rules

- AQS scope: `application_end_to_end_to_probe_node`.
- LLM API probe scope: `application_end_to_end_to_llm_api`.
- Reachability scope: `application_reachability_to_probe_node`.
- VPN observation remains deferred by D-24 and is outside V1.
- KPI version remains `agent-qoe-kpi v0.2.2`; thresholds and weights are unchanged in this baseline.
- Failed or absent values remain nullable and never drive visible geometry as zero.

## Baseline quality state

The source snapshot builds and its 346 JVM tests pass. Initial Android Debug Lint has two release-blocking errors: restricted private-API reflection in `RadioCollector` and incomplete coarse/fine location permission declaration. Work package C1 closes only those blockers without changing scoring or run orchestration.

