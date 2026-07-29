from __future__ import annotations

import copy
import hashlib
import inspect
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import scripts.analyze_repeatability_cohort as repeatability
import scripts.run_repeatability_campaign as campaign
from scripts.tests.test_analyze_repeatability_cohort import (
    _network_qualification_run,
    _realtime_qualification_run,
    _token_qualification_run,
)
from scripts.run_repeatability_campaign import (
    CampaignError,
    _coerce_receipt_bytes,
    _install_candidate,
    _launch_arguments,
    assert_radio_permissions_granted,
    record_radio_permission_preflight,
    radio_permissions,
    build_campaign_plan,
    load_qualification_campaign_contract,
    parse_token_terminal_markers,
    prepare_qualification_campaign,
)


RUN_ID = "019fa000-1111-7222-8333-444455556666"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
QUALIFICATION_HASHES = {
    "token": (
        "eaeb0af8c1a38c88a8f341c120701580659625eb3b68b8d7960db2888a01ee7b",
        "d8f31633e0c0d91a321bb1007f7cb0c30e84f855fa4e1e7b0a181e80879e7ea4",
    ),
    "realtime": (
        "ad86006f48bb06716c9d69d430d84f511c206ebd9114feffd0ca8679aeace75c",
        "883b36003dbb84cb264c7742908c9f045f3fa7c2938db9a339566f6b32b70eda",
    ),
    "network": (
        "e39dcabd2276a19c193e0a6b0c3126af734ff7c8d2fba17c91d0d48019a0c375",
        "f430fba09fd7453872690fd0d5cf9ad130637f87f347a6247c04ad069b2e4aab",
    ),
}


def _bind_qualification_hashes(
    document: dict[str, object], *, family: str
) -> dict[str, object]:
    profile_sha256, runtime_sha256 = QUALIFICATION_HASHES[family]
    profile = document["profile"]
    profile["resolution_status"] = "resolved"
    profile["profile_fingerprint"] = {
        "algorithm": "sha256",
        "canonicalization": "canonical-json-v1",
        "value": f"sha256:{profile_sha256}",
    }
    profile["profile_evidence_ref_id"] = "profile-artifact"
    profile["runtime_artifact_status"] = "resolved"
    profile["runtime_artifact_hash"] = {
        "algorithm": "sha256",
        "canonicalization": "canonical-json-v1",
        "value": f"sha256:{runtime_sha256}",
    }
    profile["runtime_artifact_evidence_ref_id"] = "runtime-artifact"
    return document


def _q1_qualification_reports() -> dict[str, dict[str, object]]:
    return {
        "token": repeatability.analyze_qualification(
            [
                _bind_qualification_hashes(
                    _token_qualification_run(index, 500.0 + (index % 2)),
                    family="token",
                )
                for index in range(1, 11)
            ],
            root=REPOSITORY_ROOT,
            stage_id="Q1_WIFI",
        ),
        "realtime": repeatability.analyze_qualification(
            [
                _bind_qualification_hashes(
                    _realtime_qualification_run(index, (0.999, 40.0, 100.0)),
                    family="realtime",
                )
                for index in range(1, 11)
            ],
            root=REPOSITORY_ROOT,
            stage_id="Q1_WIFI",
        ),
        "network": repeatability.analyze_qualification(
            [
                _bind_qualification_hashes(
                    _network_qualification_run(index, (100.0, 20.0, 50.0)),
                    family="network",
                )
                for index in range(1, 11)
            ],
            root=REPOSITORY_ROOT,
            stage_id="Q1_WIFI",
        ),
    }


def _write_q1_qualification_reports(
    directory: Path,
    reports: dict[str, dict[str, object]],
    *,
    families: tuple[str, str, str] = ("token", "realtime", "network"),
) -> tuple[Path, ...]:
    paths = []
    for index, family in enumerate(families):
        path = directory / f"{index}-{family}-q1.json"
        path.write_text(
            json.dumps(reports[family], ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        paths.append(path)
    return tuple(paths)


class RepeatabilityCampaignTests(unittest.TestCase):
    def test_phone_receipt_text_is_encoded_exactly_once(self) -> None:
        self.assertEqual(b'{"ok":true}', _coerce_receipt_bytes('{"ok":true}'))
        self.assertEqual(b'{"ok":true}', _coerce_receipt_bytes(b'{"ok":true}'))

    def test_radio_permission_set_is_exact_and_not_open_ended(self) -> None:
        self.assertEqual(
            (
                "android.permission.READ_PHONE_STATE",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
            ),
            radio_permissions(),
        )

    def test_runtime_permission_verifier_requires_each_unique_granted_row(self) -> None:
        granted = "\n".join(
            f"    {permission}: granted=true, flags=[ USER_SET ]"
            for permission in radio_permissions()
        )
        self.assertEqual(
            {permission: True for permission in radio_permissions()},
            assert_radio_permissions_granted(granted),
        )
        with self.assertRaisesRegex(
            CampaignError,
            r"radio_permission_not_granted:android\.permission\.ACCESS_FINE_LOCATION",
        ):
            assert_radio_permissions_granted(
                granted.replace(
                    "android.permission.ACCESS_FINE_LOCATION: granted=true",
                    "android.permission.ACCESS_FINE_LOCATION: granted=false",
                )
            )

    def test_runtime_permission_verifier_accepts_granted_rows_without_flags(self) -> None:
        granted = "\n".join(
            f"    {permission}: granted=true"
            for permission in radio_permissions()
        )
        self.assertEqual(
            {permission: True for permission in radio_permissions()},
            assert_radio_permissions_granted(granted),
        )

    def test_radio_permission_preflight_retains_diagnostic_receipt_before_denial(self) -> None:
        package_dump = "\n".join(
            f"    {permission}: granted={'false' if permission.endswith('FINE_LOCATION') else 'true'}, flags=[ USER_SET ]"
            for permission in radio_permissions()
        )
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "radio-permissions.json"
            with self.assertRaisesRegex(
                CampaignError,
                r"radio_permission_not_granted:android\.permission\.ACCESS_FINE_LOCATION",
            ):
                record_radio_permission_preflight(package_dump, output)
            receipt = json.loads(output.read_text(encoding="ascii"))

        self.assertEqual("aneb-repeatability-radio-permissions-v1", receipt["schema_version"])
        self.assertEqual("com.aneb.probe.codex", receipt["package_name"])
        self.assertEqual(
            {permission: permission != "android.permission.ACCESS_FINE_LOCATION" for permission in radio_permissions()},
            receipt["permissions"],
        )
        self.assertEqual(
            ["android.permission.ACCESS_FINE_LOCATION"],
            receipt["denied_permissions"],
        )
        self.assertFalse(receipt["all_granted"])
        self.assertTrue(receipt["diagnostic_only"])
        self.assertFalse(receipt["formal_baseline_eligible"])

    def test_candidate_install_records_permission_receipt_before_denial(self) -> None:
        package_dump = "\n".join(
            f"    {permission}: granted={'false' if permission.endswith('FINE_LOCATION') else 'true'}, flags=[ USER_SET ]"
            for permission in radio_permissions()
        )
        results = [
            subprocess.CompletedProcess([], 0, b"", b""),
            subprocess.CompletedProcess([], 0, b"Success\n", b""),
            *[subprocess.CompletedProcess([], 0, b"", b"") for _ in radio_permissions()],
            subprocess.CompletedProcess([], 0, package_dump.encode("utf-8"), b""),
        ]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "radio-permissions.json"
            with mock.patch(
                "scripts.run_repeatability_campaign._adb_raw",
                side_effect=results,
            ) as adb_raw:
                with self.assertRaisesRegex(
                    CampaignError,
                    r"radio_permission_not_granted:android\.permission\.ACCESS_FINE_LOCATION",
                ):
                    _install_candidate(
                        Path("adb"),
                        "serial",
                        Path("candidate.apk"),
                        permission_receipt_path=output,
                    )
            self.assertTrue(output.is_file())
            self.assertEqual(6, adb_raw.call_count)

    def test_q1_campaign_plan_is_policy_bound_and_never_substitutes_quick(self) -> None:
        contract = load_qualification_campaign_contract(
            repository_root=REPOSITORY_ROOT,
            stage_id="Q1_WIFI",
        )
        plan = build_campaign_plan(contract=contract)

        self.assertEqual("aneb-repeatability-qualification-balanced-v1", contract.policy_id)
        self.assertEqual("505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa", contract.policy_sha256)
        self.assertEqual("Q1_WIFI", contract.stage_id)
        self.assertEqual("wifi", contract.transport)
        self.assertEqual(10, contract.runs_per_family)
        self.assertFalse(contract.transport_pooling_allowed)
        self.assertTrue(contract.q2_requires_q1_pass)
        self.assertEqual(30, len(plan))
        self.assertEqual(
            [(family, ordinal) for family in ("token", "realtime", "network") for ordinal in range(1, 11)],
            [(run.family, run.ordinal) for run in plan],
        )
        self.assertEqual(
            ["A"] * 5 + ["B"] * 5,
            [run.batch_id for run in plan if run.family == "token"],
        )
        self.assertTrue(all(run.stage_id == "Q1_WIFI" for run in plan))
        self.assertTrue(all(run.transport == "wifi" for run in plan))
        self.assertTrue(all(run.execution_variant == "repeatability_qualification" for run in plan))
        self.assertNotIn("quick", {run.execution_variant for run in plan})
        self.assertEqual(
            {
                "token": (
                    "token_multimodal_repeatability_qualification",
                    "eaeb0af8c1a38c88a8f341c120701580659625eb3b68b8d7960db2888a01ee7b",
                    "d8f31633e0c0d91a321bb1007f7cb0c30e84f855fa4e1e7b0a181e80879e7ea4",
                ),
                "realtime": (
                    "ai_realtime_voice_repeatability_qualification",
                    "ad86006f48bb06716c9d69d430d84f511c206ebd9114feffd0ca8679aeace75c",
                    "883b36003dbb84cb264c7742908c9f045f3fa7c2938db9a339566f6b32b70eda",
                ),
                "network": (
                    "network_comprehensive_repeatability_qualification",
                    "e39dcabd2276a19c193e0a6b0c3126af734ff7c8d2fba17c91d0d48019a0c375",
                    "f430fba09fd7453872690fd0d5cf9ad130637f87f347a6247c04ad069b2e4aab",
                ),
            },
            {
                family: (
                    next(run.profile_id for run in plan if run.family == family),
                    next(run.profile_sha256 for run in plan if run.family == family),
                    next(run.runtime_plan_sha256 for run in plan if run.family == family),
                )
                for family in ("token", "realtime", "network")
            },
        )

    def test_q2_campaign_plan_is_cellular_and_remains_separate_from_q1(self) -> None:
        q1 = build_campaign_plan(
            contract=load_qualification_campaign_contract(
                repository_root=REPOSITORY_ROOT,
                stage_id="Q1_WIFI",
            )
        )
        q2 = build_campaign_plan(
            contract=load_qualification_campaign_contract(
                repository_root=REPOSITORY_ROOT,
                stage_id="Q2_CELLULAR",
            )
        )

        self.assertEqual(30, len(q2))
        self.assertTrue(all(run.stage_id == "Q2_CELLULAR" for run in q2))
        self.assertTrue(all(run.transport == "cellular" for run in q2))
        self.assertTrue(all(run.q1_pass_required for run in q2))
        self.assertEqual(set(), {(run.stage_id, run.transport) for run in q1} & {(run.stage_id, run.transport) for run in q2})

    def test_q1_campaign_preparation_freezes_the_policy_plan_without_a_prerequisite(self) -> None:
        prepared = prepare_qualification_campaign(
            repository_root=REPOSITORY_ROOT,
            stage_id="Q1_WIFI",
            q1_prerequisite_report_paths=(),
        )

        self.assertEqual("Q1_WIFI", prepared.contract.stage_id)
        self.assertEqual(30, len(prepared.plan))
        self.assertEqual((), prepared.q1_prerequisites)

    def test_q2_campaign_preparation_rejects_missing_q1_family_reports(self) -> None:
        with self.assertRaisesRegex(
            CampaignError,
            "q2_prerequisite_reports_required",
        ):
            prepare_qualification_campaign(
                repository_root=REPOSITORY_ROOT,
                stage_id="Q2_CELLULAR",
                q1_prerequisite_report_paths=(),
            )

    def test_q2_campaign_preparation_accepts_exactly_one_passed_q1_report_per_family(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            reports = _q1_qualification_reports()
            paths = _write_q1_qualification_reports(Path(directory), reports)

            prepared = prepare_qualification_campaign(
                repository_root=REPOSITORY_ROOT,
                stage_id="Q2_CELLULAR",
                q1_prerequisite_report_paths=paths,
            )

        self.assertEqual("Q2_CELLULAR", prepared.contract.stage_id)
        self.assertEqual(30, len(prepared.plan))
        self.assertEqual(
            ["token", "realtime", "network"],
            [item.family for item in prepared.q1_prerequisites],
        )
        self.assertEqual(
            ["token_simulation", "ai_realtime_simulation", "network_comprehensive"],
            [item.policy_family for item in prepared.q1_prerequisites],
        )
        self.assertTrue(
            all(
                len(item.raw_sha256) == 64 and len(item.canonical_sha256) == 64
                for item in prepared.q1_prerequisites
            )
        )
        self.assertTrue(
            all(
                hashlib.sha256(item.canonical_bytes).hexdigest()
                == item.canonical_sha256
                for item in prepared.q1_prerequisites
            )
        )

    def test_q2_campaign_preparation_rejects_duplicate_family_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            reports = _q1_qualification_reports()
            paths = _write_q1_qualification_reports(
                Path(directory),
                reports,
                families=("token", "token", "network"),
            )

            with self.assertRaisesRegex(
                CampaignError, "q2_prerequisite_family_coverage_invalid"
            ):
                prepare_qualification_campaign(
                    repository_root=REPOSITORY_ROOT,
                    stage_id="Q2_CELLULAR",
                    q1_prerequisite_report_paths=paths,
                )

    def test_q2_campaign_preparation_rejects_failed_q1_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            reports = _q1_qualification_reports()
            reports["realtime"]["status"] = "repeatability_failed"
            reports["realtime"]["repeatability_gate"]["status"] = "fail"
            paths = _write_q1_qualification_reports(Path(directory), reports)

            with self.assertRaisesRegex(
                CampaignError, "q2_prerequisite_not_passed"
            ):
                prepare_qualification_campaign(
                    repository_root=REPOSITORY_ROOT,
                    stage_id="Q2_CELLULAR",
                    q1_prerequisite_report_paths=paths,
                )

    def test_q2_campaign_preparation_rejects_policy_identity_tamper(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            reports = _q1_qualification_reports()
            reports["network"]["policy"]["canonical_sha256"] = "0" * 64
            paths = _write_q1_qualification_reports(Path(directory), reports)

            with self.assertRaisesRegex(
                CampaignError, "q2_prerequisite_report_invalid"
            ):
                prepare_qualification_campaign(
                    repository_root=REPOSITORY_ROOT,
                    stage_id="Q2_CELLULAR",
                    q1_prerequisite_report_paths=paths,
                )

    def test_q2_campaign_preparation_rejects_duplicate_json_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            reports = _q1_qualification_reports()
            paths = list(_write_q1_qualification_reports(Path(directory), reports))
            original = paths[0].read_text(encoding="utf-8")
            paths[0].write_text(
                '{"schema_version":"aneb-repeatability-qualification-v1",'
                + original.lstrip()[1:],
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                CampaignError, "q2_prerequisite_report_invalid"
            ):
                prepare_qualification_campaign(
                    repository_root=REPOSITORY_ROOT,
                    stage_id="Q2_CELLULAR",
                    q1_prerequisite_report_paths=tuple(paths),
                )

    def test_q2_campaign_preparation_rejects_profile_artifact_hash_tamper(self) -> None:
        for field in ("profile_fingerprint", "runtime_artifact_hash"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                reports = copy.deepcopy(_q1_qualification_reports())
                reports["token"]["cohort"]["identity"]["profile"][field][
                    "value"
                ] = "sha256:" + "0" * 64
                paths = _write_q1_qualification_reports(Path(directory), reports)

                with self.assertRaisesRegex(
                    CampaignError, "q2_prerequisite_report_invalid"
                ):
                    prepare_qualification_campaign(
                        repository_root=REPOSITORY_ROOT,
                        stage_id="Q2_CELLULAR",
                        q1_prerequisite_report_paths=paths,
                    )

    def test_run_prepares_qualification_before_any_evidence_or_external_state(self) -> None:
        args = SimpleNamespace(
            qualification_stage="Q2_CELLULAR",
            q1_prerequisite_report=(),
            evidence_parent=REPOSITORY_ROOT,
        )

        with (
            mock.patch.object(
                campaign,
                "prepare_qualification_campaign",
                side_effect=CampaignError("qualification_blocked_before_external_state"),
            ) as prepare,
            mock.patch.object(
                campaign,
                "_create_evidence_directory",
                side_effect=AssertionError("evidence_created_before_qualification"),
            ) as create_evidence,
        ):
            with self.assertRaisesRegex(
                CampaignError,
                "qualification_blocked_before_external_state",
            ):
                campaign.run(args)

        prepare.assert_called_once_with(
            repository_root=REPOSITORY_ROOT,
            stage_id="Q2_CELLULAR",
            q1_prerequisite_report_paths=(),
        )
        create_evidence.assert_not_called()

    def test_evidence_directory_name_identifies_the_s3_m2_qualification_stage(self) -> None:
        for stage_id, stage_slug in (
            ("Q1_WIFI", "q1-wifi"),
            ("Q2_CELLULAR", "q2-cellular"),
        ):
            with self.subTest(stage_id=stage_id), tempfile.TemporaryDirectory() as directory:
                parent = Path(directory)
                evidence = campaign._create_evidence_directory(
                    parent,
                    stage_id=stage_id,
                )

                self.assertEqual(parent.resolve(), evidence.parent)
                self.assertRegex(
                    evidence.name,
                    rf"^s3-m2-qualification-{stage_slug}-\d{{8}}T\d{{6}}Z-[0-9a-f]{{12}}$",
                )

    def test_cli_requires_qualification_stage_and_removes_the_repetition_knob(self) -> None:
        parser = campaign.build_parser()
        required = [
            "--serial",
            "serial-1",
            "--server-base",
            "https://example.test:8443",
            "--remote",
            "operator@example.test",
            "--ssh-key",
            "ssh-key",
            "--known-hosts",
            "known-hosts",
            "--candidate",
            "candidate",
            "--source-commit",
            "a" * 40,
            "--expected-server-binary-sha256",
            "b" * 64,
            "--phone-guard",
            "phone-guard.py",
            "--evidence-parent",
            "evidence",
            "--server-ca",
            "server-ca.pem",
            "--adb",
            "adb",
            "--ssh",
            "ssh",
            "--python",
            "python",
            "--gh",
            "gh",
        ]

        with mock.patch("sys.stderr"), self.assertRaises(SystemExit) as missing_stage:
            parser.parse_args(required)
        self.assertEqual(2, missing_stage.exception.code)

        args = parser.parse_args(
            required
            + [
                "--qualification-stage",
                "Q2_CELLULAR",
                "--q1-prerequisite-report",
                "token.json",
                "--q1-prerequisite-report",
                "realtime.json",
                "--q1-prerequisite-report",
                "network.json",
            ]
        )
        self.assertEqual("Q2_CELLULAR", args.qualification_stage)
        self.assertEqual(
            (Path("token.json"), Path("realtime.json"), Path("network.json")),
            tuple(args.q1_prerequisite_report),
        )
        self.assertFalse(hasattr(args, "repetitions"))

    def test_run_freezes_the_prepared_plan_before_constructing_external_clients(self) -> None:
        prepared = SimpleNamespace(contract=SimpleNamespace(stage_id="Q1_WIFI"))
        evidence = Path("evidence")
        args = SimpleNamespace(
            qualification_stage="Q1_WIFI",
            q1_prerequisite_report=(),
            evidence_parent=Path("evidence-parent"),
        )

        with (
            mock.patch.object(
                campaign,
                "prepare_qualification_campaign",
                return_value=prepared,
            ),
            mock.patch.object(
                campaign,
                "_create_evidence_directory",
                return_value=evidence,
            ) as create_evidence,
            mock.patch.object(
                campaign,
                "_write_prepared_campaign_evidence",
                create=True,
                side_effect=CampaignError("prepared_evidence_frozen"),
            ) as write_prepared,
            mock.patch.object(
                campaign.mechanics,
                "SubprocessRunner",
                side_effect=AssertionError(
                    "external_client_constructed_before_prepared_evidence"
                ),
            ) as subprocess_runner,
        ):
            with self.assertRaisesRegex(CampaignError, "prepared_evidence_frozen"):
                campaign.run(args)

        create_evidence.assert_called_once_with(
            Path("evidence-parent"),
            stage_id="Q1_WIFI",
        )
        write_prepared.assert_called_once_with(evidence=evidence, prepared=prepared)
        subprocess_runner.assert_not_called()

    def test_prepared_evidence_freezes_exact_plan_and_prerequisite_bytes(self) -> None:
        profile = campaign.QualificationProfileBinding(
            family="token",
            policy_family="token_simulation",
            profile_id="token_multimodal_repeatability_qualification",
            profile_version="1.0.0",
            profile_sha256="1" * 64,
            runtime_plan_sha256="2" * 64,
            execution_variant="repeatability_qualification",
        )
        contract = campaign.QualificationCampaignContract(
            policy_id="aneb-repeatability-qualification-balanced-v1",
            policy_version="1.0.0",
            policy_sha256="3" * 64,
            stage_id="Q2_CELLULAR",
            stage_order=("Q1_WIFI", "Q2_CELLULAR"),
            transport="cellular",
            runs_per_family=10,
            transport_pooling_allowed=False,
            q2_requires_q1_pass=True,
            token_batches=(("A", 5), ("B", 5)),
            profile_bindings=(profile,),
        )
        run_spec = campaign.QualificationCampaignRun(
            stage_id="Q2_CELLULAR",
            transport="cellular",
            policy_id=contract.policy_id,
            policy_version=contract.policy_version,
            policy_sha256=contract.policy_sha256,
            family="token",
            ordinal=1,
            batch_id="A",
            profile_id=profile.profile_id,
            profile_version=profile.profile_version,
            profile_sha256=profile.profile_sha256,
            runtime_plan_sha256=profile.runtime_plan_sha256,
            execution_variant="repeatability_qualification",
            q1_pass_required=True,
        )
        prerequisite_bytes = b'{"family":"token","status":"qualification_passed"}'
        prerequisite = campaign.QualificationPrerequisiteReport(
            family="token",
            policy_family="token_simulation",
            source_path=Path("token-q1.json"),
            raw_sha256="4" * 64,
            canonical_sha256=hashlib.sha256(prerequisite_bytes).hexdigest(),
            canonical_bytes=prerequisite_bytes,
        )
        prepared = campaign.PreparedQualificationCampaign(
            contract=contract,
            plan=(run_spec,),
            q1_prerequisites=(prerequisite,),
        )

        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            campaign._write_prepared_campaign_evidence(
                evidence=evidence,
                prepared=prepared,
            )

            self.assertEqual(
                prerequisite_bytes,
                (evidence / "q1-prerequisite-token.json").read_bytes(),
            )
            contract_document = json.loads(
                (evidence / "qualification-contract.json").read_text(encoding="utf-8")
            )
            plan_document = json.loads(
                (evidence / "qualification-plan.json").read_text(encoding="utf-8")
            )
            manifest = json.loads(
                (evidence / "q1-prerequisite-manifest.json").read_text(
                    encoding="utf-8"
                )
            )

        self.assertEqual("Q2_CELLULAR", contract_document["stage_id"])
        self.assertEqual("cellular", contract_document["transport"])
        self.assertEqual(1, plan_document["run_count"])
        self.assertTrue(plan_document["engineering_validation_only"])
        self.assertFalse(plan_document["formal_baseline_eligible"])
        self.assertEqual("Q2_CELLULAR", plan_document["runs"][0]["stage_id"])
        self.assertEqual(
            {
                "reports": [
                    {
                        "canonical_sha256": prerequisite.canonical_sha256,
                        "family": "token",
                        "file_name": "q1-prerequisite-token.json",
                        "policy_family": "token_simulation",
                        "raw_sha256": "4" * 64,
                        "source_file_name": "token-q1.json",
                    }
                ],
                "required": True,
                "stage_id": "Q2_CELLULAR",
            },
            manifest,
        )

    def test_run_executes_only_the_frozen_prepared_plan(self) -> None:
        source = inspect.getsource(campaign.run)

        self.assertIn("for run_spec in prepared.plan:", source)
        self.assertIn("family = run_spec.family", source)
        self.assertIn("ordinal = run_spec.ordinal", source)
        self.assertRegex(source, r"_launch_arguments\(\s*run_spec,")
        self.assertIn(
            "len(run_receipts) == len(prepared.plan)",
            source,
        )
        self.assertNotIn("args.repetitions", source)
        self.assertNotIn("build_campaign_plan(", source)

    def test_qualification_launch_arguments_bind_stage_and_profile_without_quick(self) -> None:
        q1 = build_campaign_plan(
            contract=load_qualification_campaign_contract(
                repository_root=REPOSITORY_ROOT,
                stage_id="Q1_WIFI",
            )
        )
        token = next(run for run in q1 if run.family == "token")
        arguments = _launch_arguments(
            token,
            serial="serial-1",
            server_base="https://example.test:8443",
            adb=Path("adb"),
        )

        self.assertEqual(
            ["adb", "-s", "serial-1", "shell", "am", "start", "-W", "-n", "com.aneb.probe.codex/com.aneb.probe.ui.MainActivity"],
            arguments[:9],
        )
        self.assertEqual(
            {
                "--es server": "https://example.test:8443",
                "--ez autorun": "true",
                "--es transport": "wifi",
                "--es test_mode": "token_simulation",
                "--ez qualification_requested": "true",
                "--es qualification_stage_id": "Q1_WIFI",
                "--es qualification_policy_id": "aneb-repeatability-qualification-balanced-v1",
                "--es qualification_policy_version": "1.0.0",
                "--es qualification_policy_sha256": "505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa",
                "--es qualification_profile_id": "token_multimodal_repeatability_qualification",
                "--es qualification_profile_version": "1.0.0",
                "--es qualification_profile_sha256": "eaeb0af8c1a38c88a8f341c120701580659625eb3b68b8d7960db2888a01ee7b",
                "--es qualification_runtime_plan_sha256": "d8f31633e0c0d91a321bb1007f7cb0c30e84f855fa4e1e7b0a181e80879e7ea4",
            },
            {f"{arguments[index]} {arguments[index + 1]}": arguments[index + 2] for index in range(9, len(arguments), 3)},
        )
        self.assertNotIn("quick", arguments)
        self.assertNotIn("--es mode", {f"{arguments[index]} {arguments[index + 1]}" for index in range(9, len(arguments), 3)})

        q2_network = next(
            run
            for run in build_campaign_plan(
                contract=load_qualification_campaign_contract(
                    repository_root=REPOSITORY_ROOT,
                    stage_id="Q2_CELLULAR",
                )
            )
            if run.family == "network"
        )
        q2_arguments = _launch_arguments(
            q2_network,
            serial="serial-1",
            server_base="https://example.test:8443",
            adb=Path("adb"),
        )
        q2_extras = {
            f"{q2_arguments[index]} {q2_arguments[index + 1]}": q2_arguments[index + 2]
            for index in range(9, len(q2_arguments), 3)
        }
        self.assertEqual("cellular", q2_extras["--es transport"])
        self.assertEqual("network_basic", q2_extras["--es test_mode"])
        self.assertEqual("Q2_CELLULAR", q2_extras["--es qualification_stage_id"])

    def test_token_positive_terminal_requires_one_complete_durable_chain(self) -> None:
        text = "\n".join(
            (
                f"I/AnebProbe: TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://120.79.148.0:8443",
                f"I/AnebProbe: TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
                f"I/AnebProbe: TOKEN_V2_END run_id={RUN_ID} status=completed",
            )
        )
        terminal = parse_token_terminal_markers(text, mode="positive")
        self.assertEqual(RUN_ID, terminal.run_id)
        self.assertEqual("completed", terminal.terminal_status)

    def test_token_positive_terminal_rejects_missing_or_foreign_chain(self) -> None:
        with self.assertRaises(CampaignError):
            parse_token_terminal_markers(
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick\n"
                f"TOKEN_V2_END run_id={RUN_ID} status=completed\n",
                mode="positive",
            )
        with self.assertRaises(CampaignError):
            parse_token_terminal_markers(
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick\n"
                f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true\n"
                f"TOKEN_V2_END run_id=019fa000-1111-7222-8333-444455556667 status=completed\n",
                mode="positive",
            )


if __name__ == "__main__":
    unittest.main()
