"""Generate deterministic business-side event plans and candidate Profiles."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any

from .model import export_profile, model_sha256, sample_empirical, validate_model
from .rng import Pcg32
from .statistics import describe

TRACE_CONTRACT = "aneb-behavior-trace-v1"


@dataclass(frozen=True)
class BuildArtifacts:
    trace: list[dict[str, Any]]
    profile: dict[str, Any]
    validation: dict[str, Any]
    manifest: dict[str, str]


def build_artifacts(model: dict[str, Any], seed: int) -> BuildArtifacts:
    validate_model(model)
    if model["business_type"] == "token_multimodal":
        trace = _generate_token_trace(model, seed)
    else:
        trace = _generate_realtime_trace(model, seed)
    profile = export_profile(model, seed)
    validation = validate_trace(model, trace)
    manifest = {
        "model.json": model_sha256(model),
        "golden_trace.jsonl": _sha256_jsonl(trace),
        "profile.json": _sha256_json(profile),
        "validation.json": _sha256_json(validation),
    }
    return BuildArtifacts(trace, profile, validation, manifest)


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
    turn_count = max(1, round(sample_empirical(generation["turn_count"], rng)))
    interruption_probability = float(generation.get("interruption_probability", 0.15))
    now = 0.0

    def append(event_type: str, turn: int | None, **extra: Any) -> None:
        events.append(
            _base_event(
                model,
                seed,
                len(events),
                business_type="ai_realtime_voice",
                turn=turn,
                event_type=event_type,
                planned_offset_ms=round(now, 3),
                **extra,
            )
        )

    append("session_connect_start", None)
    setup_ms = sample_empirical(generation.get("session_setup_ms", {"type": "empirical", "values": [250]}), rng)
    now += setup_ms
    append("session_ready_plan", None, setup_ms=setup_ms)

    for turn in range(turn_count):
        speech_ms = sample_empirical(generation["user_speech_ms"], rng)
        response_wait_ms = sample_empirical(generation["response_wait_ms"], rng)
        response_audio_ms = sample_empirical(generation["response_audio_ms"], rng)
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
        append("speech_end_plan", turn, speech_ms=uplink_frames * frame_ms)
        append("response_wait_start_anchor", turn, anchor="speech_committed")
        now += response_wait_ms
        append("response_audio_start_plan", turn, response_wait_ms=response_wait_ms)
        downlink_frames = max(1, round(response_audio_ms / frame_ms))
        interrupted = rng.random() < interruption_probability
        interruption_at = (
            max(1, int(downlink_frames * float(generation.get("interruption_position", 0.45))))
            if interrupted
            else None
        )
        for seq in range(downlink_frames):
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
        append("turn_complete_plan", turn, interrupted=interrupted)
        silence_ms = sample_empirical(
            generation.get("inter_turn_silence_ms", {"type": "empirical", "values": [500]}),
            rng,
        )
        now += silence_ms
    append("session_complete_plan", None, turns=turn_count)
    return events


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

