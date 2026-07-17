from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

import jsonschema

from aneb_behavior_model.calibration import (
    CalibrationError,
    calibrate_and_validate_token,
    prepare_token_dataset,
    promote_validated_model,
    verify_validated_model,
)
from aneb_behavior_model.cli import main as cli_main
from aneb_behavior_model.model import load_model, validate_model


ROOT = Path(__file__).resolve().parents[1]


def _subject(label: str) -> str:
    return "hmac-sha256:" + hashlib.sha256(("test-secret:" + label).encode()).hexdigest()


def _row(kind: str, partition: str, index: int, *, degraded: bool = False) -> dict:
    payload_base = {"text": 4096, "document": 5 * 1024 * 1024, "image": 10 * 1024 * 1024}[kind]
    processing_base = {"text": 600, "document": 1800, "image": 3200}[kind]
    token_base = {"text": 240, "document": 800, "image": 420}[kind]
    scale = 5 if degraded else 1
    return {
        "observation_contract_version": "aneb-token-observation-v1",
        "observation_id": f"obs-{partition}-{kind}-{index:03d}",
        "subject_group_id": _subject(f"{partition}-{kind}-{index:03d}"),
        "workload_kind": kind,
        "payload_bytes": payload_base + (index % 5) * 100,
        "processing_delay_ms": (processing_base + (index % 5) * 20) * scale,
        "output_token_count": token_base + (index % 5) * 5,
        "token_intervals_ms": [10, 12, 35, 45, 320 * scale, 30],
        "response_artifact_bytes": 0,
    }


def _metadata(*, authorized: bool = True) -> dict:
    return {
        "metadata_contract_version": "aneb-calibration-metadata-v1",
        "prepared_at": "2026-07-18T06:00:00Z",
        "authorization": {
            "status": "authorized" if authorized else "pending",
            "basis": "first_party_measurement",
            "approved_by": "product-owner",
            "approved_at": "2026-07-18T05:00:00Z",
            "allowed_purposes": ["behavior_model_calibration"],
            "content_policy": "derived_statistics_only",
            "content_retained": False,
        },
        "scope": {
            "source_kind": "real_application_observation",
            "provider_labels": ["authorized-provider-cohort"],
            "geography_labels": ["city-bucket-1"],
            "device_classes": ["android-flagship"],
            "observation_window_start": "2026-07-01T00:00:00Z",
            "observation_window_end": "2026-07-15T00:00:00Z",
            "collection_method": "derived-session-statistics-v1",
        },
    }


class CalibrationPipelineTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.training = [
            _row(kind, "train", index)
            for kind in ("text", "document", "image")
            for index in range(20)
        ]
        self.holdout = [
            _row(kind, "holdout", index)
            for kind in ("text", "document", "image")
            for index in range(10)
        ]

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write_json(self, name: str, value: object) -> Path:
        path = self.root / name
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return path

    def _write_jsonl(self, name: str, rows: list[dict]) -> Path:
        path = self.root / name
        path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
        return path

    def _prepare(self, *, metadata: dict | None = None, training: list[dict] | None = None, holdout: list[dict] | None = None) -> Path:
        output = self.root / "dataset"
        prepare_token_dataset(
            self._write_jsonl("training-source.jsonl", training or self.training),
            self._write_jsonl("holdout-source.jsonl", holdout or self.holdout),
            self._write_json("metadata.json", metadata or _metadata()),
            dataset_id="authorized-token-cohort",
            dataset_version="1.0.0",
            output_dir=output,
        )
        return output / "dataset_manifest.json"

    def _calibrate(self, manifest: Path):
        template = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        return calibrate_and_validate_token(template, manifest, candidate_version="0.2.0")

    def test_authorized_subject_disjoint_pipeline_passes_and_promotes(self) -> None:
        manifest_path = self._prepare()
        model, report = self._calibrate(manifest_path)
        self.assertEqual("calibrated", model["status"])
        self.assertEqual("pass", report["status"])
        self.assertEqual(0, report["summary"]["failed_check_count"])
        promoted = promote_validated_model(model, report, manifest_path)
        self.assertEqual("validated", promoted["status"])
        validate_model(promoted)
        verify_validated_model(promoted, report, manifest_path)

        schemas = {
            "observation": ROOT / "schemas/aneb-token-observation-v1.schema.json",
            "dataset": ROOT / "schemas/aneb-calibration-dataset-v1.schema.json",
            "validation": ROOT / "schemas/aneb-model-validation-v1.schema.json",
        }
        jsonschema.Draft202012Validator(json.loads(schemas["observation"].read_text(encoding="utf-8"))).validate(self.training[0])
        jsonschema.Draft202012Validator(json.loads(schemas["dataset"].read_text(encoding="utf-8"))).validate(json.loads(manifest_path.read_text(encoding="utf-8")))
        jsonschema.Draft202012Validator(json.loads(schemas["validation"].read_text(encoding="utf-8"))).validate(report)

    def test_cli_executes_prepare_calibrate_promote_chain(self) -> None:
        training = self._write_jsonl("cli-training.jsonl", self.training)
        holdout = self._write_jsonl("cli-holdout.jsonl", self.holdout)
        metadata = self._write_json("cli-metadata.json", _metadata())
        dataset = self.root / "cli-dataset"
        self.assertEqual(
            0,
            cli_main(
                [
                    "prepare-token-dataset",
                    "--training", str(training),
                    "--holdout", str(holdout),
                    "--metadata", str(metadata),
                    "--dataset-id", "authorized-token-cli",
                    "--dataset-version", "1.0.0",
                    "--out", str(dataset),
                ]
            ),
        )
        candidate = self.root / "cli-candidate"
        self.assertEqual(
            0,
            cli_main(
                [
                    "calibrate-token",
                    "--template", str(ROOT / "models/token_multimodal_hypothesis_v0.1.json"),
                    "--dataset-manifest", str(dataset / "dataset_manifest.json"),
                    "--candidate-version", "0.2.0",
                    "--out", str(candidate),
                ]
            ),
        )
        promoted = self.root / "token-validated.json"
        self.assertEqual(
            0,
            cli_main(
                [
                    "promote-token",
                    "--model", str(candidate / "calibrated_model.json"),
                    "--validation", str(candidate / "validation.json"),
                    "--dataset-manifest", str(dataset / "dataset_manifest.json"),
                    "--out", str(promoted),
                ]
            ),
        )
        self.assertEqual("validated", load_model(promoted)["status"])
        with self.assertRaisesRegex(ValueError, "requires --validation"):
            cli_main(
                [
                    "publish-runtime",
                    "--model", str(promoted),
                    "--seed", "20260718",
                    "--out", str(self.root / "unsafe-runtime"),
                ]
            )
        runtime = self.root / "validated-runtime"
        self.assertEqual(
            0,
            cli_main(
                [
                    "publish-runtime",
                    "--model", str(promoted),
                    "--validation", str(candidate / "validation.json"),
                    "--dataset-manifest", str(dataset / "dataset_manifest.json"),
                    "--seed", "20260718",
                    "--variant", "standard",
                    "--out", str(runtime),
                ]
            ),
        )
        self.assertTrue((runtime / "profile.json").is_file())
        self.assertTrue((runtime / "runtime_plan.json").is_file())

    def test_unauthorized_metadata_is_rejected(self) -> None:
        with self.assertRaisesRegex(CalibrationError, "dataset_not_authorized"):
            self._prepare(metadata=_metadata(authorized=False))

    def test_unknown_content_field_is_rejected_not_silently_ignored(self) -> None:
        rows = deepcopy(self.training)
        rows[0]["raw_prompt"] = "must never enter calibration"
        with self.assertRaisesRegex(CalibrationError, "unverified_fields"):
            self._prepare(training=rows)

    def test_training_holdout_subject_overlap_is_rejected(self) -> None:
        holdout = deepcopy(self.holdout)
        holdout[0]["subject_group_id"] = self.training[0]["subject_group_id"]
        with self.assertRaisesRegex(CalibrationError, "training_holdout_subject_overlap"):
            self._prepare(holdout=holdout)

    def test_partition_digest_tamper_is_rejected(self) -> None:
        manifest = self._prepare()
        path = manifest.parent / "training.jsonl"
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        rows[0]["payload_bytes"] += 1
        self._write_jsonl("dataset/training.jsonl", rows)
        with self.assertRaisesRegex(CalibrationError, "partition_digest_mismatch"):
            self._calibrate(manifest)

    def test_bad_holdout_fails_and_cannot_promote(self) -> None:
        degraded = [
            _row(kind, "holdout", index, degraded=True)
            for kind in ("text", "document", "image")
            for index in range(10)
        ]
        model, report = self._calibrate(self._prepare(holdout=degraded))
        self.assertEqual("fail", report["status"])
        self.assertGreater(report["summary"]["failed_check_count"], 0)
        with self.assertRaisesRegex(CalibrationError, "holdout_validation_not_passed"):
            promote_validated_model(model, report, self.root / "dataset/dataset_manifest.json")

    def test_validation_report_is_bound_to_exact_candidate(self) -> None:
        model, report = self._calibrate(self._prepare())
        tampered = deepcopy(model)
        tampered["generation"]["workloads"][0]["payload_bytes"]["values"][0] += 1
        with self.assertRaisesRegex(CalibrationError, "validation_candidate_digest_mismatch"):
            promote_validated_model(tampered, report, self.root / "dataset/dataset_manifest.json")

    def test_tampered_pass_report_is_recomputed_and_rejected(self) -> None:
        manifest = self._prepare()
        model, report = self._calibrate(manifest)
        report["summary"]["maximum_primary_relative_error"] = 0.123456
        with self.assertRaisesRegex(CalibrationError, "validation_report_not_reproducible"):
            promote_validated_model(model, report, manifest)

    def test_minimum_per_workload_is_fail_closed(self) -> None:
        sparse = [row for row in self.holdout if row["workload_kind"] != "image"] + [
            _row("image", "holdout", 0)
        ]
        with self.assertRaisesRegex(CalibrationError, "insufficient_holdout_coverage"):
            self._calibrate(self._prepare(holdout=sparse))


if __name__ == "__main__":
    unittest.main()
