# ANEB Probe Codex operating rules

Before changing measurement or scoring behavior, read:

0. `docs/PLAN_ALIGNMENT_2026-07-17.md`（产品架构：P1a/P1b/P2/P3 + Profile 横切）
1. `docs/ANEB Probe 开发设计文档.md`
2. `docs/智能体互联网时代（Agentic Internet）移动通信网络的新型网络性能与体验诉求.md` section 5
3. `docs/测量红队清单.md`
4. `docs/DECISION_LOG.md`
5. `docs/CODEX_BASELINE.md`

Non-negotiable rules:

- Preserve the three claim scopes and never market AQS as MOS, RAN latency, IP-layer packet loss, an operator-wide rating, or an SLA.
- Missing or failed measurements are `null`, never zero or a sentinel value.
- Do not change KPI thresholds, AQS weights, the T4 veto, run orchestration, or log keys without a new `DECISION_LOG` entry and Product Owner approval.
- Invalid runs retain raw evidence but do not contribute KPI or AQS aggregates.
- API keys never enter logs, Room, exports, evidence, or Git.
- `design_handoff_aneb_probe/` is the UI source of truth. Translate it to native Compose; do not ship it in a WebView.
- Preserve the logical project boundaries inside this monorepo: `app/` = P1, `server/` + `gateway/` = P2, `tools/aneb-ai-behavior-model/` + schemas = P3, and `profiles/` = the cross-cutting contract. Do not physically split repositories until ownership or release cadence requires it.
- Business variation within supported primitives belongs in Profile data. A new transport primitive, measurement semantic, or score algorithm requires a versioned contract change before P1/P2 code changes; Profile data is never executable arbitrary code.
- Run `powershell -ExecutionPolicy Bypass -File scripts/quality_gate.ps1` before handing off code.
