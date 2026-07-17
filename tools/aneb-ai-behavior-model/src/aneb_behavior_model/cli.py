"""Command line interface for model builds and empirical fitting."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from .calibration import (
    calibrate_and_validate_token,
    load_json_object,
    prepare_token_dataset,
    promote_validated_model,
    verify_validated_model,
)
from .generator import (
    _sha256_json,
    build_artifacts,
    derive_realtime_runtime_variant,
    derive_token_runtime_variant,
)
from .model import load_model


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="aneb-behavior")
    subparsers = parser.add_subparsers(dest="command", required=True)

    build = subparsers.add_parser("build", help="generate trace, profile, validation and manifest")
    build.add_argument("--model", required=True, type=Path)
    build.add_argument("--seed", required=True, type=int)
    build.add_argument("--out", required=True, type=Path)
    build.add_argument("--validation", type=Path)
    build.add_argument("--dataset-manifest", type=Path)

    publish = subparsers.add_parser(
        "publish-runtime",
        help="publish the compact App profile/runtime pair without the verbose golden trace",
    )
    publish.add_argument("--model", required=True, type=Path)
    publish.add_argument("--seed", required=True, type=int)
    publish.add_argument("--out", required=True, type=Path)
    publish.add_argument("--validation", type=Path)
    publish.add_argument("--dataset-manifest", type=Path)
    publish.add_argument(
        "--variant",
        choices=("quick", "standard", "stress", "recovery"),
        default="standard",
    )

    prepare = subparsers.add_parser(
        "prepare-token-dataset",
        help="package authorized, subject-disjoint training and holdout observations",
    )
    prepare.add_argument("--training", required=True, type=Path)
    prepare.add_argument("--holdout", required=True, type=Path)
    prepare.add_argument("--metadata", required=True, type=Path)
    prepare.add_argument("--dataset-id", required=True)
    prepare.add_argument("--dataset-version", required=True)
    prepare.add_argument("--out", required=True, type=Path)

    calibrate = subparsers.add_parser(
        "calibrate-token",
        help="fit from the training partition and validate only against the frozen holdout",
    )
    calibrate.add_argument("--template", required=True, type=Path)
    calibrate.add_argument("--dataset-manifest", required=True, type=Path)
    calibrate.add_argument("--candidate-version", required=True)
    calibrate.add_argument("--out", required=True, type=Path)

    promote = subparsers.add_parser(
        "promote-token",
        help="promote a calibrated candidate only when its bound holdout report passed",
    )
    promote.add_argument("--model", required=True, type=Path)
    promote.add_argument("--validation", required=True, type=Path)
    promote.add_argument("--dataset-manifest", required=True, type=Path)
    promote.add_argument("--out", required=True, type=Path)

    args = parser.parse_args(argv)
    if args.command == "build":
        return _build(args.model, args.seed, args.out, args.validation, args.dataset_manifest)
    if args.command == "publish-runtime":
        return _publish_runtime(
            args.model,
            args.seed,
            args.out,
            args.variant,
            args.validation,
            args.dataset_manifest,
        )
    if args.command == "prepare-token-dataset":
        prepare_token_dataset(
            args.training,
            args.holdout,
            args.metadata,
            dataset_id=args.dataset_id,
            dataset_version=args.dataset_version,
            output_dir=args.out,
        )
        return 0
    if args.command == "calibrate-token":
        return _calibrate_token(args.template, args.dataset_manifest, args.candidate_version, args.out)
    return _promote_token(args.model, args.validation, args.dataset_manifest, args.out)


def _build(
    model_path: Path,
    seed: int,
    output_dir: Path,
    validation_path: Path | None,
    manifest_path: Path | None,
) -> int:
    model = load_model(model_path)
    holdout_validation = _verify_publishable_status(model, validation_path, manifest_path)
    artifacts = build_artifacts(model, seed)
    output_dir.mkdir(parents=True, exist_ok=True)
    _write_json(output_dir / "model.json", model)
    _write_jsonl(output_dir / "golden_trace.jsonl", artifacts.trace)
    if artifacts.runtime_plan is not None:
        _write_json(output_dir / "runtime_plan.json", artifacts.runtime_plan)
    _write_json(output_dir / "profile.json", artifacts.profile)
    _write_json(output_dir / "validation.json", artifacts.validation)
    manifest = dict(artifacts.manifest)
    if holdout_validation is not None:
        _write_json(output_dir / "holdout_validation.json", holdout_validation)
        manifest["holdout_validation.json"] = _sha256_json(holdout_validation)
    (output_dir / "manifest.sha256").write_text(
        "".join(f"{digest.removeprefix('sha256:')}  {name}\n" for name, digest in manifest.items()),
        encoding="utf-8",
    )
    return 0


def _calibrate_token(template_path: Path, manifest_path: Path, candidate_version: str, output_dir: Path) -> int:
    template = load_model(template_path)
    fitted, validation = calibrate_and_validate_token(
        template,
        manifest_path,
        candidate_version=candidate_version,
    )
    output_dir.mkdir(parents=True, exist_ok=True)
    _write_json(output_dir / "calibrated_model.json", fitted)
    _write_json(output_dir / "validation.json", validation)
    selected = {
        "calibrated_model.json": _sha256_json(fitted),
        "validation.json": _sha256_json(validation),
    }
    (output_dir / "manifest.sha256").write_text(
        "".join(f"{digest.removeprefix('sha256:')}  {name}\n" for name, digest in selected.items()),
        encoding="utf-8",
    )
    return 0 if validation["status"] == "pass" else 1


def _promote_token(model_path: Path, validation_path: Path, manifest_path: Path, output_path: Path) -> int:
    model = load_model(model_path)
    validation = load_json_object(validation_path)
    promoted = promote_validated_model(model, validation, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    _write_json(output_path, promoted)
    return 0


def _publish_runtime(
    model_path: Path,
    seed: int,
    output_dir: Path,
    variant: str,
    validation_path: Path | None,
    manifest_path: Path | None,
) -> int:
    model = load_model(model_path)
    _verify_publishable_status(model, validation_path, manifest_path)
    artifacts = build_artifacts(model, seed)
    if model["business_type"] == "token_multimodal":
        profile, runtime_plan = derive_token_runtime_variant(artifacts, variant)
    elif model["business_type"] == "ai_realtime_voice":
        profile, runtime_plan = derive_realtime_runtime_variant(artifacts, variant)
    else:
        raise ValueError(f"unsupported runtime business_type: {model['business_type']}")
    output_dir.mkdir(parents=True, exist_ok=True)
    _write_json(output_dir / "profile.json", profile)
    _write_json(output_dir / "runtime_plan.json", runtime_plan)
    selected = {
        "profile.json": _sha256_json(profile),
        "runtime_plan.json": _sha256_json(runtime_plan),
    }
    (output_dir / "manifest.sha256").write_text(
        "".join(f"{digest.removeprefix('sha256:')}  {name}\n" for name, digest in selected.items()),
        encoding="utf-8",
    )
    return 0


def _verify_publishable_status(
    model: dict[str, Any],
    validation_path: Path | None,
    manifest_path: Path | None,
) -> dict[str, Any] | None:
    if model["status"] == "calibrated":
        raise ValueError("calibrated candidates cannot publish before holdout promotion")
    if model["status"] != "validated":
        if validation_path is not None or manifest_path is not None:
            raise ValueError("holdout evidence is only accepted for validated models")
        return None
    if validation_path is None or manifest_path is None:
        raise ValueError("validated publication requires --validation and --dataset-manifest")
    validation = load_json_object(validation_path)
    verify_validated_model(model, validation, manifest_path)
    return validation


def _write_json(path: Path, value: Any) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )


if __name__ == "__main__":
    raise SystemExit(main())
