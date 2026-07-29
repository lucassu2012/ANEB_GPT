from __future__ import annotations

import dataclasses
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest import mock

from scripts import run_repeatability_campaign as campaign
from scripts import analyze_repeatability_cohort as repeatability
from scripts import finalize_repeatability_qualification_campaign as finalizer
from scripts.finalize_repeatability_qualification_campaign import (
    FinalizationError,
    load_completed_campaign,
)
from scripts.tests.test_analyze_repeatability_cohort import (
    _network_qualification_run,
    _realtime_qualification_run,
    _token_qualification_run,
    _as_cellular,
)
from scripts.tests.test_export_repeatability_cohort import _database
from scripts.tests.test_run_repeatability_campaign import _bind_qualification_hashes


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def _write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _write_completed_campaign(
    evidence: Path,
    *,
    run_ids: tuple[str, ...] | None = None,
    stage_id: str = "Q1_WIFI",
    q1_prerequisite_report_paths: tuple[Path, ...] = (),
) -> tuple[str, ...]:
    prepared = campaign.prepare_qualification_campaign(
        repository_root=REPOSITORY_ROOT,
        stage_id=stage_id,
        q1_prerequisite_report_paths=q1_prerequisite_report_paths,
    )
    if run_ids is None:
        run_ids = tuple(
            f"00000000-0000-7000-8000-{index:012x}"
            for index in range(1, len(prepared.plan) + 1)
        )
    if len(run_ids) != len(prepared.plan):
        raise AssertionError("fixture_run_count_mismatch")
    campaign._write_prepared_campaign_evidence(
        evidence=evidence,
        prepared=prepared,
    )
    _write_json(
        evidence / "campaign-runs.json",
        {
            "engineering_validation_only": True,
            "formal_baseline_eligible": False,
            "runs": [
                {
                    "family": run_spec.family,
                    "ordinal": run_spec.ordinal,
                    "qualification_run": dataclasses.asdict(run_spec),
                    "terminal": {
                        "run_id": run_id,
                        "terminal_status": "completed",
                    },
                }
                for run_spec, run_id in zip(
                    prepared.plan,
                    run_ids,
                    strict=True,
                )
            ],
        },
    )
    _write_json(
        evidence / "final-status.json",
        {
            "campaign_complete": True,
            "cleanup_errors": [],
            "original_install_restored": True,
            "primary_error": None,
            "run_count": len(prepared.plan),
        },
    )
    return run_ids


def _write_room_snapshot(evidence: Path) -> None:
    room = evidence / "campaign-room"
    room.mkdir()
    for name, payload in (
        ("aneb-probe.db", b"frozen-db"),
        ("aneb-probe.db-wal", b"frozen-wal"),
        ("aneb-probe.db-shm", b"frozen-shm"),
    ):
        (room / name).write_bytes(payload)
    _write_room_snapshot_receipt(evidence)


def _write_room_snapshot_receipt(evidence: Path) -> None:
    room = evidence / "campaign-room"
    files: dict[str, dict[str, object]] = {}
    for name in ("aneb-probe.db", "aneb-probe.db-wal", "aneb-probe.db-shm"):
        payload = (room / name).read_bytes()
        files[name] = {
            "sha256": hashlib.sha256(payload).hexdigest(),
            "size_bytes": len(payload),
        }
    _write_json(room / "room-snapshot.json", {"files": files})


def _expected_profile_identity(family: str) -> dict[str, object]:
    contract = campaign.load_qualification_campaign_contract(
        repository_root=REPOSITORY_ROOT,
        stage_id="Q1_WIFI",
    )
    binding = next(item for item in contract.profile_bindings if item.family == family)
    return {
        "profile_id": binding.profile_id,
        "profile_version": binding.profile_version,
        "variant": binding.execution_variant,
        "profile_fingerprint": {
            "algorithm": "sha256",
            "canonicalization": "canonical-json-v1",
            "value": f"sha256:{binding.profile_sha256}",
        },
        "runtime_artifact_hash": {
            "algorithm": "sha256",
            "canonicalization": "canonical-json-v1",
            "value": f"sha256:{binding.runtime_plan_sha256}",
        },
    }


class FinalizeRepeatabilityQualificationCampaignTests(unittest.TestCase):
    def test_completed_campaign_is_partitioned_by_the_frozen_plan_without_pooling(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            run_ids = _write_completed_campaign(evidence)
            completed = load_completed_campaign(evidence)

        self.assertEqual("Q1_WIFI", completed.stage_id)
        self.assertEqual(
            {
                "token": run_ids[0:10],
                "realtime": run_ids[10:20],
                "network": run_ids[20:30],
            },
            completed.run_ids_by_family,
        )
        self.assertFalse(completed.formal_baseline_eligible)

    def test_plan_drift_or_duplicate_run_id_is_rejected_before_room_access(self) -> None:
        for case, expected_code in (
            ("plan_drift", "campaign_run_binding_mismatch"),
            ("duplicate_run_id", "campaign_terminal_invalid"),
        ):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                evidence = Path(directory)
                _write_completed_campaign(evidence)
                runs_path = evidence / "campaign-runs.json"
                runs = json.loads(runs_path.read_text(encoding="utf-8"))
                if case == "plan_drift":
                    runs["runs"][0]["qualification_run"]["profile_sha256"] = "0" * 64
                else:
                    runs["runs"][1]["terminal"]["run_id"] = runs["runs"][0][
                        "terminal"
                    ]["run_id"]
                _write_json(runs_path, runs)

                with self.assertRaisesRegex(FinalizationError, expected_code):
                    load_completed_campaign(evidence)

    def test_room_snapshot_read_failure_is_normalized_before_any_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "captured"
            evidence.mkdir()
            _write_completed_campaign(evidence)
            _write_room_snapshot(evidence)
            output = root / "finalized"
            database = evidence / "campaign-room" / "aneb-probe.db"
            original_read_bytes = Path.read_bytes

            def fail_database_read(path: Path) -> bytes:
                if path == database:
                    raise OSError("simulated_room_snapshot_read_failure")
                return original_read_bytes(path)

            with mock.patch.object(
                Path,
                "read_bytes",
                autospec=True,
                side_effect=fail_database_read,
            ):
                with self.assertRaisesRegex(
                    FinalizationError,
                    "^campaign_room_snapshot_invalid$",
                ):
                    finalizer.finalize_completed_campaign(
                        evidence,
                        output,
                        repository_root=REPOSITORY_ROOT,
                    )

            self.assertFalse(output.exists())
            self.assertEqual([], list(root.glob(".finalized.*.partial")))

    def test_exported_cohort_read_failure_is_normalized_without_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "captured"
            evidence.mkdir()
            _write_completed_campaign(evidence)
            _write_room_snapshot(evidence)
            output = root / "finalized"
            original_read_bytes = Path.read_bytes

            def export_family(
                database: Path,
                requested_run_ids: tuple[str, ...],
                destination: Path,
                *,
                root: Path,
            ) -> dict[str, object]:
                del database, root
                payload = (
                    json.dumps(
                        {"family": destination.name.removesuffix("-cohort.jsonl")},
                        sort_keys=True,
                    )
                    + "\n"
                ).encode("utf-8")
                destination.write_bytes(payload)
                return {
                    "output_sha256": hashlib.sha256(payload).hexdigest(),
                    "results_reconstructed": False,
                    "run_count": len(requested_run_ids),
                    "run_ids": list(requested_run_ids),
                    "scores_recomputed": False,
                }

            def analyze_family(
                documents: list[dict[str, object]],
                *,
                root: Path,
                stage_id: str,
                prerequisite_report: dict[str, object] | None = None,
            ) -> dict[str, object]:
                del documents, root, stage_id, prerequisite_report
                return {"status": "repeatability_passed"}

            def fail_exported_cohort_read(path: Path) -> bytes:
                if path.name == "token-cohort.jsonl":
                    raise OSError("simulated_exported_cohort_read_failure")
                return original_read_bytes(path)

            with (
                mock.patch.object(
                    Path,
                    "read_bytes",
                    autospec=True,
                    side_effect=fail_exported_cohort_read,
                ),
                mock.patch.object(
                    finalizer,
                    "_validated_report",
                    return_value=b"{}\n",
                ),
            ):
                with self.assertRaisesRegex(
                    FinalizationError,
                    "^qualification_family_failed:token$",
                ):
                    finalizer.finalize_completed_campaign(
                        evidence,
                        output,
                        repository_root=REPOSITORY_ROOT,
                        _export_cohort=export_family,
                        _analyze_qualification=analyze_family,
                    )

            self.assertFalse(output.exists())
            self.assertEqual([], list(root.glob(".finalized.*.partial")))

    def test_publication_rename_failure_is_normalized_and_cleans_partial_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "captured"
            evidence.mkdir()
            _write_completed_campaign(evidence)
            _write_room_snapshot(evidence)
            output = root / "finalized"

            def export_family(
                database: Path,
                requested_run_ids: tuple[str, ...],
                destination: Path,
                *,
                root: Path,
            ) -> dict[str, object]:
                del database, root
                payload = (
                    json.dumps(
                        {"family": destination.name.removesuffix("-cohort.jsonl")},
                        sort_keys=True,
                    )
                    + "\n"
                ).encode("utf-8")
                destination.write_bytes(payload)
                return {
                    "output_sha256": hashlib.sha256(payload).hexdigest(),
                    "results_reconstructed": False,
                    "run_count": len(requested_run_ids),
                    "run_ids": list(requested_run_ids),
                    "scores_recomputed": False,
                }

            def analyze_family(
                documents: list[dict[str, object]],
                *,
                root: Path,
                stage_id: str,
                prerequisite_report: dict[str, object] | None = None,
            ) -> dict[str, object]:
                del documents, root, stage_id, prerequisite_report
                return {"status": "repeatability_passed"}

            with (
                mock.patch.object(
                    Path,
                    "rename",
                    autospec=True,
                    side_effect=OSError("simulated_publication_rename_failure"),
                ),
                mock.patch.object(
                    finalizer,
                    "_validated_report",
                    return_value=b"{}\n",
                ),
            ):
                with self.assertRaisesRegex(
                    FinalizationError,
                    "^finalization_publication_failed$",
                ):
                    finalizer.finalize_completed_campaign(
                        evidence,
                        output,
                        repository_root=REPOSITORY_ROOT,
                        _export_cohort=export_family,
                        _analyze_qualification=analyze_family,
                    )

            self.assertFalse(output.exists())
            self.assertEqual([], list(root.glob(".finalized.*.partial")))

    def test_three_families_are_exported_and_analyzed_without_pooling_before_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "captured"
            evidence.mkdir()
            run_ids = _write_completed_campaign(evidence)
            run_ids_by_family = {
                "token": run_ids[0:10],
                "realtime": run_ids[10:20],
                "network": run_ids[20:30],
            }
            _write_room_snapshot(evidence)
            output = root / "finalized"
            events: list[tuple[object, ...]] = []

            def export_family(
                database: Path,
                requested_run_ids: tuple[str, ...],
                destination: Path,
                *,
                root: Path,
            ) -> dict[str, object]:
                del database, root
                family = destination.name.removesuffix("-cohort.jsonl")
                events.append(("export", family, tuple(requested_run_ids)))
                payload = json.dumps({"family": family}, sort_keys=True) + "\n"
                destination.write_bytes(payload.encode("utf-8"))
                return {
                    "schema_version": "aneb-repeatability-export-v1",
                    "run_count": len(requested_run_ids),
                    "run_ids": list(requested_run_ids),
                    "output_path": str(destination.resolve()),
                    "output_sha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
                    "database_source_sha256": {},
                    "results_reconstructed": False,
                    "scores_recomputed": False,
                }

            def analyze_family(
                documents: list[dict[str, object]],
                *,
                root: Path,
                stage_id: str,
                prerequisite_report: dict[str, object] | None = None,
            ) -> dict[str, object]:
                del root
                family = documents[0]["family"]
                events.append(("analyze", family, stage_id, prerequisite_report))
                test_type = {
                    "token": "token_simulation",
                    "realtime": "ai_realtime_simulation",
                    "network": "network_comprehensive",
                }[family]
                return {
                    "schema_version": "aneb-repeatability-qualification-v1",
                    "status": "repeatability_passed",
                    "policy": {"stage_id": stage_id},
                    "cohort": {
                        "run_ids": list(run_ids_by_family[family]),
                        "identity": {
                            "test_type": test_type,
                            "profile": _expected_profile_identity(family),
                        },
                    },
                    "formal_baseline_eligible": False,
                }

            result = finalizer.finalize_completed_campaign(
                evidence,
                output,
                repository_root=REPOSITORY_ROOT,
                _export_cohort=export_family,
                _analyze_qualification=analyze_family,
            )

            self.assertEqual(
                [
                    ("export", "token", run_ids[0:10]),
                    ("analyze", "token", "Q1_WIFI", None),
                    ("export", "realtime", run_ids[10:20]),
                    ("analyze", "realtime", "Q1_WIFI", None),
                    ("export", "network", run_ids[20:30]),
                    ("analyze", "network", "Q1_WIFI", None),
                ],
                events,
            )
            self.assertEqual(output.resolve(), result.output_directory)
            self.assertFalse(result.formal_baseline_eligible)
            self.assertEqual(
                {"token", "realtime", "network"},
                set(result.report_sha256_by_family),
            )
            manifest = json.loads(
                (output / "finalization-manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual("Q1_WIFI", manifest["stage_id"])
            self.assertEqual(
                ["token", "realtime", "network"],
                [item["family"] for item in manifest["families"]],
            )
            self.assertFalse(manifest["formal_baseline_eligible"])
            self.assertEqual([], list(root.glob(".finalized.*.partial")))
            with self.assertRaisesRegex(FinalizationError, "finalization_path_invalid"):
                finalizer.finalize_completed_campaign(
                    evidence,
                    output,
                    repository_root=REPOSITORY_ROOT,
                    _export_cohort=export_family,
                    _analyze_qualification=analyze_family,
                )

    def test_real_room_export_and_d110_analysis_publish_three_q1_reports(self) -> None:
        documents = [
            *[
                _bind_qualification_hashes(
                    _token_qualification_run(index, 500.0 + (index % 2)),
                    family="token",
                )
                for index in range(1, 11)
            ],
            *[
                _bind_qualification_hashes(
                    _realtime_qualification_run(
                        index,
                        (
                            0.999 - (index % 3) * 0.001,
                            40.0 + (index % 3),
                            100.0 + (index % 2),
                        ),
                    ),
                    family="realtime",
                )
                for index in range(11, 21)
            ],
            *[
                _bind_qualification_hashes(
                    _network_qualification_run(
                        index,
                        (
                            100.0 + (index % 2),
                            20.0 + (index % 2) * 0.1,
                            50.0 + (index % 2),
                        ),
                    ),
                    family="network",
                )
                for index in range(21, 31)
            ],
        ]
        run_ids = tuple(document["run"]["run_id"] for document in documents)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "captured"
            evidence.mkdir()
            _write_completed_campaign(evidence, run_ids=run_ids)
            room = evidence / "campaign-room"
            room.mkdir()
            database = room / "aneb-probe.db"
            _database(database, documents)
            connection = sqlite3.connect(database)
            try:
                self.assertEqual(
                    "wal",
                    connection.execute("PRAGMA journal_mode = WAL").fetchone()[0],
                )
                connection.execute("PRAGMA wal_autocheckpoint = 0")
                connection.execute(
                    "CREATE TABLE finalizer_fixture_guard (value INTEGER NOT NULL)"
                )
                connection.execute(
                    "INSERT INTO finalizer_fixture_guard VALUES (1)"
                )
                connection.commit()
                self.assertTrue(Path(str(database) + "-wal").is_file())
                self.assertTrue(Path(str(database) + "-shm").is_file())
                _write_room_snapshot_receipt(evidence)

                output = root / "finalized"
                result = finalizer.finalize_completed_campaign(
                    evidence,
                    output,
                    repository_root=REPOSITORY_ROOT,
                )
            finally:
                connection.close()

            self.assertEqual("Q1_WIFI", result.stage_id)
            for family, expected_test_type in (
                ("token", "token_simulation"),
                ("realtime", "ai_realtime_simulation"),
                ("network", "network_comprehensive"),
            ):
                self.assertEqual(
                    10,
                    len(
                        (output / f"{family}-cohort.jsonl")
                        .read_text(encoding="utf-8")
                        .splitlines()
                    ),
                )
                report = json.loads(
                    (output / f"{family}-qualification-report.json").read_text(
                        encoding="utf-8"
                    )
                )
                self.assertEqual("repeatability_passed", report["status"])
                self.assertEqual(expected_test_type, report["cohort"]["identity"]["test_type"])
                self.assertFalse(report["formal_baseline_eligible"])
            self.assertEqual([], list(root.glob(".finalized.*.partial")))

    def test_family_failure_publishes_neither_final_output_nor_partial_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "captured"
            evidence.mkdir()
            run_ids = _write_completed_campaign(evidence)
            run_ids_by_family = {
                "token": run_ids[0:10],
                "realtime": run_ids[10:20],
                "network": run_ids[20:30],
            }
            _write_room_snapshot(evidence)
            output = root / "finalized"
            events: list[tuple[str, str]] = []

            def export_family(
                database: Path,
                requested_run_ids: tuple[str, ...],
                destination: Path,
                *,
                root: Path,
            ) -> dict[str, object]:
                del database, root
                family = destination.name.removesuffix("-cohort.jsonl")
                events.append(("export", family))
                payload = (json.dumps({"family": family}, sort_keys=True) + "\n").encode(
                    "utf-8"
                )
                destination.write_bytes(payload)
                return {
                    "run_count": len(requested_run_ids),
                    "run_ids": list(requested_run_ids),
                    "output_sha256": hashlib.sha256(payload).hexdigest(),
                    "results_reconstructed": False,
                    "scores_recomputed": False,
                }

            def analyze_family(
                documents: list[dict[str, object]],
                *,
                root: Path,
                stage_id: str,
                prerequisite_report: dict[str, object] | None = None,
            ) -> dict[str, object]:
                del root, prerequisite_report
                family = documents[0]["family"]
                events.append(("analyze", family))
                if family == "realtime":
                    raise ValueError("fixture_analysis_failure")
                return {
                    "schema_version": "aneb-repeatability-qualification-v1",
                    "status": "repeatability_passed",
                    "policy": {"stage_id": stage_id},
                    "cohort": {
                        "run_ids": list(run_ids_by_family[family]),
                        "identity": {
                            "test_type": {
                                "token": "token_simulation",
                                "network": "network_comprehensive",
                            }[family],
                            "profile": _expected_profile_identity(family),
                        }
                    },
                    "formal_baseline_eligible": False,
                }

            with self.assertRaisesRegex(
                FinalizationError,
                "qualification_family_failed:realtime",
            ):
                finalizer.finalize_completed_campaign(
                    evidence,
                    output,
                    repository_root=REPOSITORY_ROOT,
                    _export_cohort=export_family,
                    _analyze_qualification=analyze_family,
                )

            self.assertEqual(
                [
                    ("export", "token"),
                    ("analyze", "token"),
                    ("export", "realtime"),
                    ("analyze", "realtime"),
                ],
                events,
            )
            self.assertFalse(output.exists())
            self.assertEqual([], list(root.glob(".finalized.*.partial")))

    def test_failed_family_report_is_not_represented_as_qualification_passed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "captured"
            evidence.mkdir()
            run_ids = _write_completed_campaign(evidence)
            run_ids_by_family = {
                "token": run_ids[0:10],
                "realtime": run_ids[10:20],
                "network": run_ids[20:30],
            }
            _write_room_snapshot(evidence)
            output = root / "finalized"

            def export_family(
                database: Path,
                requested_run_ids: tuple[str, ...],
                destination: Path,
                *,
                root: Path,
            ) -> dict[str, object]:
                del database, root
                family = destination.name.removesuffix("-cohort.jsonl")
                payload = (json.dumps({"family": family}) + "\n").encode("utf-8")
                destination.write_bytes(payload)
                return {
                    "run_count": len(requested_run_ids),
                    "run_ids": list(requested_run_ids),
                    "output_sha256": hashlib.sha256(payload).hexdigest(),
                    "results_reconstructed": False,
                    "scores_recomputed": False,
                }

            def analyze_family(
                documents: list[dict[str, object]],
                *,
                root: Path,
                stage_id: str,
                prerequisite_report: dict[str, object] | None = None,
            ) -> dict[str, object]:
                del root, prerequisite_report
                family = documents[0]["family"]
                return {
                    "schema_version": "aneb-repeatability-qualification-v1",
                    "status": (
                        "repeatability_failed"
                        if family == "network"
                        else "repeatability_passed"
                    ),
                    "policy": {"stage_id": stage_id},
                    "cohort": {
                        "run_ids": list(run_ids_by_family[family]),
                        "identity": {
                            "test_type": {
                                "token": "token_simulation",
                                "realtime": "ai_realtime_simulation",
                                "network": "network_comprehensive",
                            }[family],
                            "profile": _expected_profile_identity(family),
                        },
                    },
                    "formal_baseline_eligible": False,
                }

            result = finalizer.finalize_completed_campaign(
                evidence,
                output,
                repository_root=REPOSITORY_ROOT,
                _export_cohort=export_family,
                _analyze_qualification=analyze_family,
            )

            self.assertEqual(
                {
                    "token": "repeatability_passed",
                    "realtime": "repeatability_passed",
                    "network": "repeatability_failed",
                },
                result.report_status_by_family,
            )
            self.assertFalse(result.qualification_passed)
            self.assertFalse(result.formal_baseline_eligible)
            self.assertTrue((output / "finalization-manifest.json").is_file())
            self.assertEqual([], list(root.glob(".finalized.*.partial")))

    def test_real_q2_analysis_binds_each_family_to_its_own_q1_report(self) -> None:
        q1_documents = {
            "token": [
                _bind_qualification_hashes(
                    _token_qualification_run(index, 500.0 + (index % 2)),
                    family="token",
                )
                for index in range(1, 11)
            ],
            "realtime": [
                _bind_qualification_hashes(
                    _realtime_qualification_run(
                        index,
                        (
                            0.999 - (index % 3) * 0.001,
                            40.0 + (index % 3),
                            100.0 + (index % 2),
                        ),
                    ),
                    family="realtime",
                )
                for index in range(1, 11)
            ],
            "network": [
                _bind_qualification_hashes(
                    _network_qualification_run(
                        index,
                        (
                            100.0 + (index % 2),
                            20.0 + (index % 2) * 0.1,
                            50.0 + (index % 2),
                        ),
                    ),
                    family="network",
                )
                for index in range(1, 11)
            ],
        }
        q2_documents = [
            *[
                _bind_qualification_hashes(
                    _as_cellular(
                        _token_qualification_run(index, 500.0 + (index % 2))
                    ),
                    family="token",
                )
                for index in range(11, 21)
            ],
            *[
                _bind_qualification_hashes(
                    _as_cellular(
                        _realtime_qualification_run(
                            index,
                            (
                                0.998 - (index % 3) * 0.001,
                                41.0 + (index % 3),
                                101.0 + (index % 2),
                            ),
                        ),
                    ),
                    family="realtime",
                )
                for index in range(21, 31)
            ],
            *[
                _bind_qualification_hashes(
                    _as_cellular(
                        _network_qualification_run(
                            index,
                            (
                                101.0 + (index % 2),
                                20.1 + (index % 2) * 0.1,
                                51.0 + (index % 2),
                            ),
                        ),
                    ),
                    family="network",
                )
                for index in range(31, 41)
            ],
        ]
        run_ids = tuple(document["run"]["run_id"] for document in q2_documents)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            q1_paths: list[Path] = []
            q1_reports: dict[str, dict[str, object]] = {}
            for family in ("token", "realtime", "network"):
                report = repeatability.analyze_qualification(
                    q1_documents[family],
                    root=REPOSITORY_ROOT,
                    stage_id="Q1_WIFI",
                )
                path = root / f"q1-{family}.json"
                path.write_bytes(
                    json.dumps(
                        report,
                        ensure_ascii=False,
                        allow_nan=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    ).encode("utf-8")
                )
                q1_paths.append(path)
                q1_reports[family] = report

            evidence = root / "captured"
            evidence.mkdir()
            _write_completed_campaign(
                evidence,
                run_ids=run_ids,
                stage_id="Q2_CELLULAR",
                q1_prerequisite_report_paths=tuple(q1_paths),
            )
            room = evidence / "campaign-room"
            room.mkdir()
            database = room / "aneb-probe.db"
            _database(database, q2_documents)
            connection = sqlite3.connect(database)
            try:
                self.assertEqual(
                    "wal",
                    connection.execute("PRAGMA journal_mode = WAL").fetchone()[0],
                )
                connection.execute("PRAGMA wal_autocheckpoint = 0")
                connection.execute(
                    "CREATE TABLE finalizer_fixture_guard (value INTEGER NOT NULL)"
                )
                connection.execute(
                    "INSERT INTO finalizer_fixture_guard VALUES (1)"
                )
                connection.commit()
                _write_room_snapshot_receipt(evidence)

                output = root / "finalized"
                result = finalizer.finalize_completed_campaign(
                    evidence,
                    output,
                    repository_root=REPOSITORY_ROOT,
                )
            finally:
                connection.close()

            self.assertEqual("Q2_CELLULAR", result.stage_id)
            for family in ("token", "realtime", "network"):
                report = json.loads(
                    (output / f"{family}-qualification-report.json").read_text(
                        encoding="utf-8"
                    )
                )
                self.assertEqual("repeatability_passed", report["status"])
                self.assertEqual("pass", report["prerequisite_gate"]["status"])
                self.assertEqual(
                    repeatability._canonical_digest(q1_reports[family]),
                    report["prerequisite_gate"]["report_sha256"],
                )
                self.assertFalse(report["formal_baseline_eligible"])


if __name__ == "__main__":
    unittest.main()
