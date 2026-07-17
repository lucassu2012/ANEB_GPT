"""Generate deterministic business-side event plans and candidate Profiles."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from copy import deepcopy
from typing import Any

from .model import export_profile, model_sha256, sample_empirical, validate_model
from .rng import Pcg32
from .statistics import describe

TRACE_CONTRACT = "aneb-behavior-trace-v1"
TOKEN_RUNTIME_CONTRACT = "aneb-token-runtime-plan-v1"
REALTIME_RUNTIME_CONTRACT = "aneb-realtime-runtime-plan-v1"


@dataclass(frozen=True)
class BuildArtifacts:
    trace: list[dict[str, Any]]
    runtime_plan: dict[str, Any] | None
    profile: dict[str, Any]
    validation: dict[str, Any]
    manifest: dict[str, str]


def build_artifacts(model: dict[str, Any], seed: int) -> BuildArtifacts:
    validate_model(model)
    if model["business_type"] == "token_multimodal":
        trace = _generate_token_trace(model, seed)
        runtime_plan = _build_token_runtime_plan(model, trace, seed)
        runtime_plan["variant"] = "standard"
        profile = export_profile(model, seed)
        _bind_token_runtime_plan(profile, runtime_plan, seed, "standard")
    else:
        trace = _generate_realtime_trace(model, seed)
        runtime_plan = _build_realtime_runtime_plan(model, trace, seed)
        runtime_plan["variant"] = "standard"
        profile = export_profile(model, seed)
        _bind_realtime_runtime_plan(profile, runtime_plan, seed, "standard")
    validation = validate_trace(model, trace)
    manifest = {
        "model.json": model_sha256(model),
        "golden_trace.jsonl": _sha256_jsonl(trace),
        "profile.json": _sha256_json(profile),
        "validation.json": _sha256_json(validation),
    }
    manifest["runtime_plan.json"] = _sha256_json(runtime_plan)
    return BuildArtifacts(trace, runtime_plan, profile, validation, manifest)


def derive_token_runtime_variant(
    artifacts: BuildArtifacts,
    variant: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Return a published standard, quick, or isolated large-object stress runtime pair."""
    if artifacts.runtime_plan is None or artifacts.runtime_plan.get("contract_version") != TOKEN_RUNTIME_CONTRACT:
        raise ValueError("token runtime variant requires token_multimodal artifacts")
    if variant not in {"standard", "quick", "stress"}:
        raise ValueError(f"unsupported runtime variant: {variant}")
    plan = deepcopy(artifacts.runtime_plan)
    profile = deepcopy(artifacts.profile)
    if variant == "quick":
        by_kind: dict[str, list[dict[str, Any]]] = {}
        for task in plan["tasks"]:
            by_kind.setdefault(task["workload_kind"], []).append(task)
        selected = [
            min(tasks, key=lambda task: float(task["planned_duration_ms"]))
            for tasks in by_kind.values()
        ]
        for index, task in enumerate(selected):
            task["start_after_previous_ms"] = 0.0 if index == 0 else min(
                800.0,
                float(task["start_after_previous_ms"]),
            )
        plan["tasks"] = selected
        plan["task_count"] = len(selected)
        profile["profile_id"] = "token_multimodal_quick"
        profile.setdefault("business", {})["label"] = "多模态 Token 快测"
    elif variant == "stress":
        video_tasks = [
            task for task in plan["tasks"]
            if task["workload_kind"] == "video"
            and int(task["upload"]["payload_bytes"]) >= 100 * 1024 * 1024
            and int(task["response_artifact_bytes"]) >= 100 * 1024 * 1024
        ]
        if not video_tasks:
            raise ValueError("stress variant requires a 100MiB video upload and response artifact")
        plan["tasks"] = video_tasks
        plan["task_count"] = len(video_tasks)
        profile["profile_id"] = "token_multimodal_stress"
        profile.setdefault("business", {})["label"] = "多模态 Token 大对象压力测试"
    plan["variant"] = variant
    _bind_token_runtime_plan(profile, plan, int(plan["seed"]), variant)
    return profile, plan


def derive_realtime_runtime_variant(
    artifacts: BuildArtifacts,
    variant: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Return a published realtime standard, quick, or controlled-recovery pair."""
    if artifacts.runtime_plan is None or artifacts.runtime_plan.get("contract_version") != REALTIME_RUNTIME_CONTRACT:
        raise ValueError("realtime runtime variant requires ai_realtime_voice artifacts")
    if variant not in {"standard", "quick", "recovery"}:
        raise ValueError(f"unsupported runtime variant: {variant}")
    plan = deepcopy(artifacts.runtime_plan)
    profile = deepcopy(artifacts.profile)
    if variant == "quick":
        source_sessions = plan["sessions"]
        source = next(
            (session for session in source_sessions if any(turn["interrupted"] for turn in session["turns"])),
            source_sessions[0],
        )
        turns = source["turns"]
        interrupted = next((turn for turn in turns if turn["interrupted"]), turns[0])
        selected = turns[:2]
        if interrupted not in selected:
            selected.append(interrupted)
        else:
            selected = turns[:3]
        selected = sorted(selected, key=lambda turn: int(turn["turn_index"]))
        selected = deepcopy(selected)
        for index, turn in enumerate(selected):
            turn["turn_index"] = index
            turn["turn_id"] = f"{source['session_id']}-quick-turn-{index + 1:04d}"
        quick_session = deepcopy(source)
        quick_session["start_after_previous_ms"] = 0.0
        quick_session["turns"] = selected
        quick_session["turn_count"] = len(selected)
        quick_session["planned_duration_ms"] = round(
            float(quick_session["setup_ms"])
            + sum(float(turn["start_after_previous_ms"]) + float(turn["planned_duration_ms"]) for turn in selected),
            3,
        )
        plan["sessions"] = [quick_session]
        plan["session_count"] = 1
        profile["profile_id"] = "ai_realtime_voice_quick"
        profile.setdefault("business", {})["label"] = "AI 实时语音快测"
    elif variant == "recovery":
        # Recovery is deliberately separate from Standard. The business model
        # remains untouched; this derived artifact adds only auditable test actions.
        selected_sessions: list[dict[str, Any]] = []
        recovery_probe_template = min(
            (turn for session in plan["sessions"] for turn in session["turns"]),
            key=lambda turn: (
                float(turn["speech_ms"]) + float(turn["response_wait_ms"]),
                float(turn["planned_duration_ms"]),
            ),
        )
        for session_index, source in enumerate(plan["sessions"][:4]):
            controlled_failure = session_index % 2 == 0
            if controlled_failure:
                selected_turns = deepcopy(
                    sorted(source["turns"], key=lambda turn: float(turn["planned_duration_ms"]))[:1]
                )
            else:
                selected_turns = [
                    deepcopy(recovery_probe_template),
                    *deepcopy(
                        sorted(source["turns"], key=lambda turn: float(turn["planned_duration_ms"]))[:2]
                    ),
                ]
            for turn_index, turn in enumerate(selected_turns):
                turn["turn_index"] = turn_index
                turn["turn_id"] = f"recovery-session-{session_index + 1:04d}-turn-{turn_index + 1:04d}"
                turn["start_after_previous_ms"] = 0.0 if turn_index == 0 else min(
                    300.0,
                    float(turn["start_after_previous_ms"]),
                )
            session = deepcopy(source)
            session["session_id"] = f"recovery-session-{session_index + 1:04d}"
            session["start_after_previous_ms"] = 0.0
            session["turns"] = selected_turns
            session["turn_count"] = len(selected_turns)
            session["planned_duration_ms"] = round(
                float(session["setup_ms"])
                + sum(
                    float(turn["start_after_previous_ms"]) + float(turn["planned_duration_ms"])
                    for turn in selected_turns
                ),
                3,
            )
            session["controlled_disconnect_after_turn"] = 0 if controlled_failure else None
            selected_sessions.append(session)
        plan["sessions"] = selected_sessions
        plan["session_count"] = len(selected_sessions)
        profile["profile_id"] = "ai_realtime_voice_recovery"
        profile["version"] = "1.2.0"
        profile["claim_scope"] = "controlled_server_disconnect_recovery_to_probe_node"
        business = profile.setdefault("business", {})
        business["label"] = "AI 实时语音受控恢复"
        features = business.setdefault("behavior_feature_ids", [])
        if "controlled_server_disconnect_recovery" not in features:
            features.append("controlled_server_disconnect_recovery")
        evaluation = profile.setdefault("evaluation", {})
        evaluation["target_set_id"] = "realtime-recovery-targets-v1"
        evaluation["score_policy_id"] = "realtime-recovery-score-v2"
        evaluation["conclusion_policy_id"] = "realtime-recovery-conclusions-v2"
        evaluation["required_metric_ids"] = ["LIVE-B05", "LIVE-B09", "LIVE-B11", "LIVE-N02"]
        evaluation["guardrail_metric_ids"] = ["LIVE-B11"]
        evaluation["group_weights"] = {"recovery_path": 0.65, "recovered_quality": 0.35}
        required_minimums = {"LIVE-B05": 400, "LIVE-B09": 6, "LIVE-B11": 2, "LIVE-N02": 10}
        for metric in profile["measurements"]:
            metric_id = metric["metric_id"]
            metric["required_for_score"] = metric_id in required_minimums
            if metric_id in required_minimums:
                metric["minimum_sample_count"] = required_minimums[metric_id]
        plan["recovery_probe_contract"] = "fixed_model_derived_minimum_speech_plus_wait_v1"
    plan["variant"] = variant
    _bind_realtime_runtime_plan(profile, plan, int(plan["seed"]), variant)
    return profile, plan


def _bind_token_runtime_plan(
    profile: dict[str, Any],
    runtime_plan: dict[str, Any],
    seed: int,
    variant: str,
) -> None:
    runtime_hash = _sha256_json(runtime_plan)
    planned_ms = sum(
        float(task["start_after_previous_ms"]) + float(task["planned_duration_ms"])
        for task in runtime_plan["tasks"]
    )
    profile["evidence_tier"] = variant
    profile["est_duration_s"] = round(planned_ms / 1000.0, 1)
    profile["execution_plan"] = {
        "contract_version": TOKEN_RUNTIME_CONTRACT,
        "artifact": "runtime_plan.json",
        "artifact_hash": runtime_hash,
        "seed": seed,
        "variant": variant,
    }
    for phase in profile.get("phases", []):
        if phase.get("type") == "behavior_trace":
            phase["runtime_artifact"] = "runtime_plan.json"
            phase["runtime_artifact_hash"] = runtime_hash


def _bind_realtime_runtime_plan(
    profile: dict[str, Any],
    runtime_plan: dict[str, Any],
    seed: int,
    variant: str,
) -> None:
    runtime_hash = _sha256_json(runtime_plan)
    planned_ms = sum(
        float(session["start_after_previous_ms"]) + float(session["planned_duration_ms"])
        for session in runtime_plan["sessions"]
    )
    profile["evidence_tier"] = variant
    profile["est_duration_s"] = round(planned_ms / 1000.0, 1)
    profile["execution_plan"] = {
        "contract_version": REALTIME_RUNTIME_CONTRACT,
        "artifact": "runtime_plan.json",
        "artifact_hash": runtime_hash,
        "seed": seed,
        "variant": variant,
    }
    for phase in profile.get("phases", []):
        if phase.get("type") == "behavior_trace":
            phase["runtime_artifact"] = "runtime_plan.json"
            phase["runtime_artifact_hash"] = runtime_hash


def _build_token_runtime_plan(
    model: dict[str, Any],
    trace: list[dict[str, Any]],
    seed: int,
) -> dict[str, Any]:
    """Collapse the verbose golden trace into the exact plan consumed by ANEB App.

    The runtime artifact contains business-side intent only. It never contains an
    observed RTT, arrival time, loss decision, or synthetic network impairment.
    """
    by_task: dict[str, list[dict[str, Any]]] = {}
    for event in trace:
        task_id = event.get("task_id")
        if task_id is not None:
            by_task.setdefault(str(task_id), []).append(event)

    tasks: list[dict[str, Any]] = []
    previous_complete_ms: float | None = None
    for task_id, events in by_task.items():
        user_action = _only_event(events, "user_action")
        upload = _only_event(events, "upload_complete_plan")
        processing = _only_event(events, "processing_end_plan")
        complete = _only_event(events, "task_complete_plan")
        tokens = sorted(
            (event for event in events if event["event_type"] == "token_emit_plan"),
            key=lambda event: int(event["seq"]),
        )
        artifact = next(
            (event for event in events if event["event_type"] == "artifact_complete_plan"),
            None,
        )
        upload_chunks = [event for event in events if event["event_type"] == "upload_chunk_plan"]
        cadence_ms = 0.0
        if len(upload_chunks) > 1:
            cadence_ms = max(
                0.0,
                float(upload_chunks[1]["planned_offset_ms"])
                - float(upload_chunks[0]["planned_offset_ms"]),
            )
        start_ms = float(user_action["planned_offset_ms"])
        gap_ms = 0.0 if previous_complete_ms is None else max(0.0, start_ms - previous_complete_ms)
        tasks.append(
            {
                "task_id": task_id,
                "workload_kind": user_action["workload_kind"],
                "repetition": int(user_action["repetition"]),
                "start_after_previous_ms": round(gap_ms, 3),
                "upload": {
                    "payload_bytes": int(upload["bytes"]),
                    "chunk_bytes": max(int(event["bytes"]) for event in upload_chunks),
                    "chunk_cadence_ms": round(cadence_ms, 3),
                },
                "processing_ms": round(float(processing["duration_ms"]), 3),
                "token_stream": {
                    "intervals_ms": [round(float(event["planned_interval_ms"]), 3) for event in tokens],
                    "sizes_bytes": [int(event["token_bytes"]) for event in tokens],
                },
                "response_artifact_bytes": int(artifact["bytes"]) if artifact else 0,
                "planned_duration_ms": round(float(complete["planned_offset_ms"]) - start_ms, 3),
            }
        )
        previous_complete_ms = float(complete["planned_offset_ms"])

    return {
        "contract_version": TOKEN_RUNTIME_CONTRACT,
        "model_id": model["model_id"],
        "model_version": model["model_version"],
        "model_hash": model_sha256(model),
        "calibration_status": model["status"],
        "seed": seed,
        "task_count": len(tasks),
        "tasks": tasks,
        "claim": (
            "product hypothesis; not a validated representation of any named provider"
            if model["status"] == "hypothesis"
            else "versioned business behavior plan; validation evidence is separate"
        ),
    }


def _only_event(events: list[dict[str, Any]], event_type: str) -> dict[str, Any]:
    matches = [event for event in events if event["event_type"] == event_type]
    if len(matches) != 1:
        raise ValueError(f"expected exactly one {event_type}, got {len(matches)}")
    return matches[0]


def _base_event(model: dict[str, Any], seed: int, index: int, **payload: Any) -> dict[str, Any]:
    return {
        "trace_contract_version": TRACE_CONTRACT,
        "model_id": model["model_id"],
        "model_version": model["model_version"],
        "seed": seed,
        "event_index": index,
        **payload,
    }


def _generate_token_trace(model: dict[str, Any], seed: int) -> list[dict[str, Any]]:
    rng = Pcg32(seed)
    events: list[dict[str, Any]] = []
    task_id = 0
    task_start_ms = 0.0
    gap = model["generation"].get("gap_between_tasks_ms", {"type": "empirical", "values": [500]})
    for workload in model["generation"]["workloads"]:
        for repetition in range(int(workload.get("repetitions", 1))):
            task_id += 1
            payload_bytes = max(1, round(sample_empirical(workload["payload_bytes"], rng)))
            chunk_bytes = max(1, int(workload.get("chunk_bytes", 64 * 1024)))
            chunk_cadence_ms = max(0.0, float(workload.get("upload_chunk_cadence_ms", 1.0)))
            processing_ms = sample_empirical(workload["processing_delay_ms"], rng)
            token_count = max(1, round(sample_empirical(workload["output_token_count"], rng)))

            def append(event_type: str, offset_ms: float, **extra: Any) -> None:
                events.append(
                    _base_event(
                        model,
                        seed,
                        len(events),
                        business_type="token_multimodal",
                        task_id=f"task-{task_id:04d}",
                        workload_kind=workload["kind"],
                        repetition=repetition,
                        event_type=event_type,
                        planned_offset_ms=round(offset_ms, 3),
                        **extra,
                    )
                )

            now = task_start_ms
            append("user_action", now)
            remaining = payload_bytes
            chunk_index = 0
            while remaining > 0:
                size = min(chunk_bytes, remaining)
                append("upload_chunk_plan", now, chunk_index=chunk_index, bytes=size)
                remaining -= size
                chunk_index += 1
                now += chunk_cadence_ms
            append("upload_complete_plan", now, bytes=payload_bytes, chunks=chunk_index)
            append("processing_start_anchor", now, anchor="upload_received")
            now += processing_ms
            append("processing_end_plan", now, duration_ms=processing_ms)

            markov = workload["token_interval_model"]
            state = rng.weighted_choice(markov["start_probabilities"])
            for token_index in range(token_count):
                interval_ms = sample_empirical(markov["states"][state], rng)
                if token_index > 0:
                    now += interval_ms
                append(
                    "token_emit_plan",
                    now,
                    seq=token_index,
                    interval_state=state,
                    planned_interval_ms=interval_ms if token_index > 0 else 0.0,
                    token_bytes=max(1, round(sample_empirical(workload["token_bytes"], rng))),
                )
                state = rng.weighted_choice(markov["transition_probabilities"][state])

            artifact_distribution = workload.get("response_artifact_bytes")
            if artifact_distribution:
                artifact_bytes = max(0, round(sample_empirical(artifact_distribution, rng)))
                if artifact_bytes > 0:
                    append("artifact_start_plan", now, bytes=artifact_bytes)
                    remaining = artifact_bytes
                    artifact_chunk = max(1, int(workload.get("artifact_chunk_bytes", 64 * 1024)))
                    artifact_index = 0
                    while remaining > 0:
                        size = min(artifact_chunk, remaining)
                        append(
                            "artifact_chunk_plan",
                            now,
                            chunk_index=artifact_index,
                            bytes=size,
                        )
                        remaining -= size
                        artifact_index += 1
                    append("artifact_complete_plan", now, bytes=artifact_bytes, chunks=artifact_index)

            append("task_complete_plan", now, output_tokens=token_count)
            task_start_ms = now + sample_empirical(gap, rng)
    return events


def _generate_realtime_trace(model: dict[str, Any], seed: int) -> list[dict[str, Any]]:
    rng = Pcg32(seed)
    generation = model["generation"]
    events: list[dict[str, Any]] = []
    frame_ms = int(generation["audio_frame_ms"])
    session_count = max(
        1,
        round(
            sample_empirical(
                generation.get("session_count", {"type": "empirical", "values": [1]}),
                rng,
            )
        ),
    )
    interruption_probability = float(generation.get("interruption_probability", 0.15))
    minimum_interruptions = max(0, int(generation.get("minimum_interruptions_per_session", 0)))
    now = 0.0

    for session_index in range(session_count):
        if session_index > 0:
            now += sample_empirical(
                generation.get("inter_session_silence_ms", {"type": "empirical", "values": [1000]}),
                rng,
            )
        session_id = f"session-{session_index + 1:04d}"

        def append(event_type: str, turn: int | None, **extra: Any) -> None:
            events.append(
                _base_event(
                    model,
                    seed,
                    len(events),
                    business_type="ai_realtime_voice",
                    session_id=session_id,
                    session_index=session_index,
                    turn=turn,
                    event_type=event_type,
                    planned_offset_ms=round(now, 3),
                    **extra,
                )
            )

        append("session_connect_start", None)
        setup_ms = sample_empirical(
            generation.get("session_setup_ms", {"type": "empirical", "values": [250]}),
            rng,
        )
        now += setup_ms
        append("session_ready_plan", None, setup_ms=setup_ms)
        turn_count = max(1, round(sample_empirical(generation["turn_count"], rng)))
        interruption_draws = [rng.random() for _ in range(turn_count)]
        interrupted_turns = {
            index for index, draw in enumerate(interruption_draws) if draw < interruption_probability
        }
        if len(interrupted_turns) < min(minimum_interruptions, turn_count):
            for index in sorted(range(turn_count), key=lambda item: interruption_draws[item]):
                interrupted_turns.add(index)
                if len(interrupted_turns) >= min(minimum_interruptions, turn_count):
                    break

        for turn in range(turn_count):
            speech_ms = sample_empirical(generation["user_speech_ms"], rng)
            response_wait_ms = sample_empirical(generation["response_wait_ms"], rng)
            response_audio_ms = sample_empirical(generation["response_audio_ms"], rng)
            commit_mode = str(rng.choice(generation.get("commit_modes", ["vad"])))
            append("speech_start_plan", turn)
            uplink_frames = max(1, round(speech_ms / frame_ms))
            for seq in range(uplink_frames):
                append(
                    "audio_uplink_frame_plan",
                    turn,
                    seq=seq,
                    duration_ms=frame_ms,
                    bytes=int(generation.get("uplink_frame_bytes", 640)),
                )
                now += frame_ms
            append("speech_end_plan", turn, speech_ms=uplink_frames * frame_ms, commit_mode=commit_mode)
            append("response_wait_start_anchor", turn, anchor="speech_committed")
            now += response_wait_ms
            planned_downlink_frames = max(1, round(response_audio_ms / frame_ms))
            append(
                "response_audio_start_plan",
                turn,
                response_wait_ms=response_wait_ms,
                planned_downlink_frames=planned_downlink_frames,
            )
            interrupted = turn in interrupted_turns
            interruption_at = (
                max(
                    1,
                    min(
                        planned_downlink_frames - 1,
                        int(planned_downlink_frames * float(generation.get("interruption_position", 0.45))),
                    ),
                )
                if interrupted and planned_downlink_frames > 1
                else None
            )
            for seq in range(planned_downlink_frames):
                if interruption_at is not None and seq == interruption_at:
                    append("barge_in_plan", turn, after_frames=seq)
                    append("response_cancel_plan", turn, expected_stop_within_ms=300)
                    break
                append(
                    "audio_downlink_frame_plan",
                    turn,
                    seq=seq,
                    duration_ms=frame_ms,
                    bytes=int(generation.get("downlink_frame_bytes", 960)),
                )
                now += frame_ms
            append("turn_complete_plan", turn, interrupted=interruption_at is not None)
            now += sample_empirical(
                generation.get("inter_turn_silence_ms", {"type": "empirical", "values": [500]}),
                rng,
            )
        append(
            "session_complete_plan",
            None,
            turns=turn_count,
            interruptions=len(interrupted_turns),
        )
    return events


def _build_realtime_runtime_plan(
    model: dict[str, Any],
    trace: list[dict[str, Any]],
    seed: int,
) -> dict[str, Any]:
    """Collapse the realtime golden trace into a compact full-duplex plan."""
    by_session: dict[str, list[dict[str, Any]]] = {}
    for event in trace:
        session_id = event.get("session_id")
        if session_id is not None:
            by_session.setdefault(str(session_id), []).append(event)

    sessions: list[dict[str, Any]] = []
    previous_complete_ms: float | None = None
    generation = model["generation"]
    for session_id, session_events in by_session.items():
        connect = _only_event(session_events, "session_connect_start")
        ready = _only_event(session_events, "session_ready_plan")
        complete = _only_event(session_events, "session_complete_plan")
        connect_ms = float(connect["planned_offset_ms"])
        start_gap_ms = 0.0 if previous_complete_ms is None else max(0.0, connect_ms - previous_complete_ms)
        by_turn: dict[int, list[dict[str, Any]]] = {}
        for event in session_events:
            if event.get("turn") is not None:
                by_turn.setdefault(int(event["turn"]), []).append(event)
        turns: list[dict[str, Any]] = []
        previous_turn_complete_ms = float(ready["planned_offset_ms"])
        for turn_index, turn_events in sorted(by_turn.items()):
            speech_start = _only_event(turn_events, "speech_start_plan")
            speech_end = _only_event(turn_events, "speech_end_plan")
            response_start = _only_event(turn_events, "response_audio_start_plan")
            turn_complete = _only_event(turn_events, "turn_complete_plan")
            uplink_frames = [event for event in turn_events if event["event_type"] == "audio_uplink_frame_plan"]
            downlink_frames = [event for event in turn_events if event["event_type"] == "audio_downlink_frame_plan"]
            barge = next((event for event in turn_events if event["event_type"] == "barge_in_plan"), None)
            cancel = next((event for event in turn_events if event["event_type"] == "response_cancel_plan"), None)
            turn_start_ms = float(speech_start["planned_offset_ms"])
            turn_complete_ms = float(turn_complete["planned_offset_ms"])
            turns.append(
                {
                    "turn_id": f"{session_id}-turn-{turn_index + 1:04d}",
                    "turn_index": turn_index,
                    "start_after_previous_ms": round(max(0.0, turn_start_ms - previous_turn_complete_ms), 3),
                    "uplink_frames": len(uplink_frames),
                    "uplink_frame_bytes": int(uplink_frames[0]["bytes"]),
                    "response_wait_ms": round(float(response_start["response_wait_ms"]), 3),
                    "planned_downlink_frames": int(response_start["planned_downlink_frames"]),
                    "downlink_frames_before_stop": len(downlink_frames),
                    "downlink_frame_bytes": int(downlink_frames[0]["bytes"]),
                    "interrupted": barge is not None,
                    "barge_in_after_frames": int(barge["after_frames"]) if barge else None,
                    "expected_stop_within_ms": int(cancel["expected_stop_within_ms"]) if cancel else None,
                    "speech_ms": round(float(speech_end["speech_ms"]), 3),
                    "commit_mode": str(speech_end["commit_mode"]),
                    "planned_duration_ms": round(turn_complete_ms - turn_start_ms, 3),
                }
            )
            previous_turn_complete_ms = turn_complete_ms
        complete_ms = float(complete["planned_offset_ms"])
        sessions.append(
            {
                "session_id": session_id,
                "start_after_previous_ms": round(start_gap_ms, 3),
                "setup_ms": round(float(ready["setup_ms"]), 3),
                "frame_ms": int(generation["audio_frame_ms"]),
                "turn_count": len(turns),
                "turns": turns,
                "planned_duration_ms": round(complete_ms - connect_ms, 3),
            }
        )
        previous_complete_ms = complete_ms

    return {
        "contract_version": REALTIME_RUNTIME_CONTRACT,
        "model_id": model["model_id"],
        "model_version": model["model_version"],
        "model_hash": model_sha256(model),
        "calibration_status": model["status"],
        "seed": seed,
        "session_count": len(sessions),
        "sessions": sessions,
        "claim": (
            "product hypothesis; not a validated representation of any named provider"
            if model["status"] == "hypothesis"
            else "versioned business behavior plan; validation evidence is separate"
        ),
    }


def validate_trace(model: dict[str, Any], trace: list[dict[str, Any]]) -> dict[str, Any]:
    issues: list[str] = []
    if not trace:
        issues.append("trace_empty")
    indexes = [event.get("event_index") for event in trace]
    if indexes != list(range(len(trace))):
        issues.append("event_index_not_contiguous")
    offsets = [float(event.get("planned_offset_ms", -1)) for event in trace]
    if any(current < previous for previous, current in zip(offsets, offsets[1:])):
        issues.append("planned_offsets_not_monotonic")
    forbidden = {"arrival_ms", "network_delay_ms", "packet_loss", "measured_rtt_ms"}
    leaked = sorted({key for event in trace for key in forbidden if key in event})
    if leaked:
        issues.append("network_outcome_leaked_into_business_trace:" + ",".join(leaked))

    token_intervals = [
        float(event["planned_interval_ms"])
        for event in trace
        if event["event_type"] == "token_emit_plan" and event.get("seq", 0) > 0
    ]
    payloads = [
        float(event["bytes"])
        for event in trace
        if event["event_type"] == "upload_complete_plan"
    ]
    response_waits = [
        float(event["response_wait_ms"])
        for event in trace
        if event["event_type"] == "response_audio_start_plan"
    ]
    return {
        "validation_contract_version": "aneb-behavior-validation-v1",
        "model_id": model["model_id"],
        "model_version": model["model_version"],
        "model_status": model["status"],
        "structural_valid": not issues,
        "issues": issues,
        "event_count": len(trace),
        "summaries": {
            "payload_bytes": describe(payloads),
            "token_interval_ms": describe(token_intervals),
            "response_wait_ms": describe(response_waits),
        },
        "claim": (
            "structural validation only; hypothesis models are not validated "
            "representations of any named provider"
            if model["status"] == "hypothesis"
            else "structural validation passed; calibration evidence is a separate artifact"
        ),
    }


def _sha256_json(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def _sha256_jsonl(trace: list[dict[str, Any]]) -> str:
    encoded = b"".join(
        json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8") + b"\n"
        for event in trace
    )
    return "sha256:" + hashlib.sha256(encoded).hexdigest()
