"""Command line interface for model builds and empirical fitting."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from .fitting import fit_token_model
from .generator import (
    _sha256_json,
    build_artifacts,
    derive_realtime_runtime_variant,
    derive_token_runtime_variant,
)
from .model import load_model, validate_model


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="aneb-behavior")
    subparsers = parser.add_subparsers(dest="command", required=True)

    build = subparsers.add_parser("build", help="generate trace, profile, validation and manifest")
    build.add_argument("--model", required=True, type=Path)
    build.add_argument("--seed", required=True, type=int)
    build.add_argument("--out", required=True, type=Path)

    publish = subparsers.add_parser(
        "publish-runtime",
        help="publish the compact App profile/runtime pair without the verbose golden trace",
    )
    publish.add_argument("--model", required=True, type=Path)
    publish.add_argument("--seed", required=True, type=int)
    publish.add_argument("--out", required=True, type=Path)
    publish.add_argument(
        "--variant",
        choices=("quick", "standard", "stress", "recovery"),
        default="standard",
    )

    fit = subparsers.add_parser("fit-token", help="fit token model from session JSONL")
    fit.add_argument("--template", required=True, type=Path)
    fit.add_argument("--observations", required=True, type=Path)
    fit.add_argument("--out", required=True, type=Path)

    args = parser.parse_args(argv)
    if args.command == "build":
        return _build(args.model, args.seed, args.out)
    if args.command == "publish-runtime":
        return _publish_runtime(args.model, args.seed, args.out, args.variant)
    return _fit_token(args.template, args.observations, args.out)


def _build(model_path: Path, seed: int, output_dir: Path) -> int:
    model = load_model(model_path)
    artifacts = build_artifacts(model, seed)
    output_dir.mkdir(parents=True, exist_ok=True)
    _write_json(output_dir / "model.json", model)
    _write_jsonl(output_dir / "golden_trace.jsonl", artifacts.trace)
    if artifacts.runtime_plan is not None:
        _write_json(output_dir / "runtime_plan.json", artifacts.runtime_plan)
    _write_json(output_dir / "profile.json", artifacts.profile)
    _write_json(output_dir / "validation.json", artifacts.validation)
    (output_dir / "manifest.sha256").write_text(
        "".join(f"{digest.removeprefix('sha256:')}  {name}\n" for name, digest in artifacts.manifest.items()),
        encoding="utf-8",
    )
    return 0


def _fit_token(template_path: Path, observations_path: Path, output_path: Path) -> int:
    template = load_model(template_path)
    observations = [
        json.loads(line)
        for line in observations_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    fitted = fit_token_model(observations, template)
    validate_model(fitted)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    _write_json(output_path, fitted)
    return 0


def _publish_runtime(model_path: Path, seed: int, output_dir: Path, variant: str) -> int:
    model = load_model(model_path)
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


def _write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )


if __name__ == "__main__":
    raise SystemExit(main())
