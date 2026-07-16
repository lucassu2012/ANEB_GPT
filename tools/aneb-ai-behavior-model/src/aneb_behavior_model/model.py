"""Model loading, contract validation, canonical hashing and sampling."""

from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from pathlib import Path
from typing import Any

from .catalogs import CATALOGS, metric_catalog
from .rng import Pcg32

MODEL_CONTRACT = "aneb-behavior-model-v1"
PROFILE_CONTRACT = "aneb-profile-v2"
SUPPORTED_BUSINESS_TYPES = {"token_multimodal", "ai_realtime_voice"}
SUPPORTED_STATUSES = {"hypothesis", "calibrated", "validated", "retired"}


class ModelError(ValueError):
    pass


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def model_sha256(model: dict[str, Any]) -> str:
    return "sha256:" + hashlib.sha256(canonical_json_bytes(model)).hexdigest()


def load_model(path: str | Path) -> dict[str, Any]:
    model = json.loads(Path(path).read_text(encoding="utf-8"))
    validate_model(model)
    return model


def validate_model(model: dict[str, Any]) -> None:
    required = {
        "model_contract_version",
        "model_id",
        "model_version",
        "business_type",
        "status",
        "source",
        "prng",
        "generation",
        "profile_export",
    }
    missing = sorted(required - model.keys())
    if missing:
        raise ModelError(f"missing model fields: {missing}")
    if model["model_contract_version"] != MODEL_CONTRACT:
        raise ModelError(f"unsupported model contract: {model['model_contract_version']}")
    if model["business_type"] not in SUPPORTED_BUSINESS_TYPES:
        raise ModelError(f"unsupported business_type: {model['business_type']}")
    if model["status"] not in SUPPORTED_STATUSES:
        raise ModelError(f"unsupported model status: {model['status']}")
    if model["prng"] != "pcg32-v1":
        raise ModelError("published models must use prng=pcg32-v1")
    profile = model["profile_export"]
    if profile.get("contract_version") != PROFILE_CONTRACT:
        raise ModelError("profile_export must use aneb-profile-v2")
    if profile.get("execution_target") != "aneb_probe_simulator":
        raise ModelError("profile execution_target must be aneb_probe_simulator")
    if profile.get("claim_scope") != "application_end_to_end_to_probe_node":
        raise ModelError("profile claim_scope must target the ANEB probe node")
    catalog_id = profile.get("measurement_catalog_id")
    if catalog_id not in CATALOGS:
        raise ModelError(f"unknown profile measurement_catalog_id: {catalog_id}")

    generation = model["generation"]
    if model["business_type"] == "token_multimodal":
        workloads = generation.get("workloads", [])
        if not workloads:
            raise ModelError("token_multimodal requires at least one workload")
        for workload in workloads:
            _validate_distribution(workload.get("payload_bytes"), "payload_bytes")
            _validate_distribution(workload.get("processing_delay_ms"), "processing_delay_ms")
            _validate_distribution(workload.get("output_token_count"), "output_token_count")
            _validate_markov(workload.get("token_interval_model"))
    else:
        for field in (
            "turn_count",
            "user_speech_ms",
            "response_wait_ms",
            "response_audio_ms",
        ):
            _validate_distribution(generation.get(field), field)
        frame_ms = generation.get("audio_frame_ms")
        if not isinstance(frame_ms, int) or frame_ms <= 0:
            raise ModelError("audio_frame_ms must be a positive integer")


def _validate_distribution(distribution: Any, name: str) -> None:
    if not isinstance(distribution, dict):
        raise ModelError(f"{name} must be a distribution object")
    if distribution.get("type") != "empirical":
        raise ModelError(f"{name}: v0.1 supports type=empirical only")
    values = distribution.get("values")
    if not isinstance(values, list) or not values:
        raise ModelError(f"{name}: empirical values must be non-empty")
    if not all(isinstance(value, (int, float)) and value >= 0 for value in values):
        raise ModelError(f"{name}: empirical values must be non-negative numbers")


def _validate_markov(markov: Any) -> None:
    if not isinstance(markov, dict) or markov.get("type") != "markov_empirical":
        raise ModelError("token_interval_model must be markov_empirical")
    states = markov.get("states", {})
    if set(states) != {"FAST", "NORMAL", "PAUSE"}:
        raise ModelError("token interval states must be FAST/NORMAL/PAUSE")
    for state, distribution in states.items():
        _validate_distribution(distribution, f"token_interval_model.states.{state}")
    start = markov.get("start_probabilities", {})
    transitions = markov.get("transition_probabilities", {})
    _validate_probability_row(start, "start_probabilities")
    if set(transitions) != set(states):
        raise ModelError("transition matrix must contain every state row")
    for state, row in transitions.items():
        _validate_probability_row(row, f"transition row {state}")


def _validate_probability_row(row: Any, name: str) -> None:
    if not isinstance(row, dict) or set(row) != {"FAST", "NORMAL", "PAUSE"}:
        raise ModelError(f"{name} must contain FAST/NORMAL/PAUSE")
    values = [float(value) for value in row.values()]
    if any(value < 0 for value in values) or abs(sum(values) - 1.0) > 1e-6:
        raise ModelError(f"{name} must be non-negative and sum to 1")


def sample_empirical(distribution: dict[str, Any], rng: Pcg32) -> float:
    return float(rng.choice(distribution["values"]))


def export_profile(model: dict[str, Any], seed: int) -> dict[str, Any]:
    profile = deepcopy(model["profile_export"])
    catalog_id = profile.pop("measurement_catalog_id")
    profile["measurements"] = metric_catalog(catalog_id)
    profile["measurement_catalog_id"] = catalog_id
    model_hash = model_sha256(model)
    profile.setdefault("business", {})
    profile["business"].update(
        {
            "behavior_model_id": model["model_id"],
            "behavior_model_version": model["model_version"],
            "behavior_model_hash": model_hash,
            "calibration_status": model["status"],
            "model_source_kind": model["source"].get("kind", "unknown"),
        }
    )
    profile["trace"] = {
        "contract_version": "aneb-behavior-trace-v1",
        "seed": seed,
        "prng": model["prng"],
    }
    profile["phases"] = [
        {
            "type": "behavior_trace",
            "model_id": model["model_id"],
            "model_version": model["model_version"],
            "model_hash": model_hash,
            "seed": seed,
        }
    ]
    return profile
