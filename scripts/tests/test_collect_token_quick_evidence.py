from __future__ import annotations

import ast
import json
import os
import base64
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "collect_token_quick_evidence.ps1"
DEVICE_SERIAL = "TEST-SERIAL"
DEVICE_BOOT_ID = "12345678-1234-4abc-8def-1234567890ab"
DEVICE_PROPERTIES = {
    "ro.serialno": DEVICE_SERIAL,
    "ro.boot.serialno": DEVICE_SERIAL,
    "ro.product.manufacturer": "HUAWEI",
    "ro.product.model": "P40 Pro",
    "ro.product.device": "ELS",
    "ro.product.name": "ELS-N29",
    "ro.build.fingerprint": "HUAWEI/ELS-N29/HWELS:12/HUAWEIELS/1:user/release-keys",
    "ro.build.version.security_patch": "2026-06-01",
    "ro.boot.verifiedbootstate": "green",
    "ro.boot.vbmeta.device_state": "locked",
    "ro.boot.flash.locked": "1",
    "ro.boot.veritymode": "enforcing",
}


def find_powershell() -> str | None:
    return shutil.which("pwsh") or shutil.which("powershell")


class TokenQuickEvidenceCollectorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.powershell = find_powershell()
        if self.powershell is None:
            self.skipTest("PowerShell is unavailable")

    def _write_never_call_tool(self, root: Path, name: str, marker: Path) -> Path:
        path = root / f"{name}.cmd"
        path.write_text(
            "@echo off\r\n"
            f">\"{marker}\" echo {name}\r\n"
            "exit /b 91\r\n",
            encoding="ascii",
        )
        return path

    def _run_preflight(
        self,
        temporary: Path,
        *,
        bundle_verifier: Path | None = None,
        negative_proxy: Path | None = None,
        negative_client_db_verifier: Path | None = None,
        evidence_mode: str = "positive",
        server_base: str = "https://aneb.invalid:8443",
        remote: str = "root@aneb.invalid",
        known_hosts_path: Path | None = None,
        device_policy_path: Path | None = None,
        candidate_directory: Path | None = None,
        gh_path: Path | None = None,
    ) -> tuple[subprocess.CompletedProcess[str], Path, Path]:
        marker = temporary / "external-tool-was-called.txt"
        commands = {
            name: self._write_never_call_tool(temporary, name, marker)
            for name in ("adb", "ssh", "curl")
        }
        build_tools = temporary / "build-tools" / "35.0.0"
        (build_tools / "lib").mkdir(parents=True)
        commands["apksigner"] = self._write_never_call_tool(
            build_tools, "apksigner", marker
        )
        (build_tools / "aapt2.exe").write_bytes(b"test-only-aapt2\n")
        (build_tools / "lib" / "apksigner.jar").write_bytes(b"test-only-jar\n")
        python_command = Path(shutil.which("python") or os.sys.executable)
        gh_command = gh_path or self._write_never_call_tool(temporary, "gh", marker)
        ssh_key = temporary / "id_test"
        ssh_key.write_text("test-only\n", encoding="ascii")
        known_hosts = known_hosts_path or temporary / "known_hosts"
        if known_hosts_path is None:
            known_hosts.write_text(
                "aneb.invalid ssh-ed25519 "
                + base64.b64encode(b"test-host-key").decode("ascii")
                + "\n",
                encoding="ascii",
            )
        device_policy = device_policy_path or temporary / "p40-device-policy.json"
        if device_policy_path is None:
            device_policy.write_text(
                json.dumps(
                    {
                        "schema": "aneb-device-identity-policy",
                        "schema_version": "1.0.0",
                        "device_alias": "P40 Pro",
                        "adb_serial_sha256": hashlib.sha256(
                            DEVICE_SERIAL.encode("utf-8")
                        ).hexdigest(),
                        "properties": DEVICE_PROPERTIES,
                    },
                    separators=(",", ":"),
                )
                + "\n",
                encoding="utf-8",
            )
        helpers = {
            "derive": ROOT / "scripts" / "prepare_token_run_evidence.py",
            "audit": ROOT / "scripts" / "verify_token_run_audit.py",
            "client-db": ROOT / "scripts" / "verify_token_quick_client_db.py",
            "bundle": bundle_verifier
            or ROOT / "scripts" / "verify_token_quick_evidence_bundle.py",
            "negative-proxy": negative_proxy
            or ROOT / "scripts" / "token_serverinfo_negative_proxy.py",
            "negative-client-db": negative_client_db_verifier
            or ROOT / "scripts" / "verify_token_quick_negative_client_db.py",
            "result-jsonl": ROOT / "scripts" / "verify_result_jsonl.py",
        }
        candidate = candidate_directory or temporary / "ci-candidate"
        if candidate_directory is None:
            candidate.mkdir()
            for name, contents in {
                "ANEB-Probe-0.5.12-codex-debug.apk": b"test-only-apk\n",
                "build-manifest.json": b"{}\n",
                "checksums.sha256": b"test-only\n",
                "provenance.sigstore.json": b"{}\n",
                "ANEB-安装说明.txt": "仅用于测试\n".encode("utf-8"),
            }.items():
                (candidate / name).write_bytes(contents)
        evidence_root = temporary / "evidence-must-not-exist"

        command = [
            self.powershell,
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(SCRIPT),
            "-PreflightOnly",
            "-AdbSerial",
            DEVICE_SERIAL,
            "-ServerBase",
            server_base,
            "-Remote",
            remote,
            "-SshKey",
            str(ssh_key),
            "-KnownHostsPath",
            str(known_hosts),
            "-DevicePolicyPath",
            str(device_policy),
            "-CandidateDirectory",
            str(candidate),
            "-GhPath",
            str(gh_command),
            "-ExpectedServerBinarySha256",
            "a" * 64,
            "-EvidenceMode",
            evidence_mode,
            "-EvidenceRoot",
            str(evidence_root),
            "-AdbPath",
            str(commands["adb"]),
            "-SshPath",
            str(commands["ssh"]),
            "-CurlPath",
            str(commands["curl"]),
            "-ApksignerPath",
            str(commands["apksigner"]),
            "-AndroidBuildToolsDir",
            str(build_tools),
            "-PythonPath",
            str(python_command),
            "-DeriveHelperPath",
            str(helpers["derive"]),
            "-AuditVerifierPath",
            str(helpers["audit"]),
            "-ClientDbVerifierPath",
            str(helpers["client-db"]),
            "-BundleVerifierPath",
            str(helpers["bundle"]),
            "-NegativeProxyPath",
            str(helpers["negative-proxy"]),
            "-NegativeClientDbVerifierPath",
            str(helpers["negative-client-db"]),
            "-ResultJsonlVerifierPath",
            str(helpers["result-jsonl"]),
        ]
        completed = subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=20,
        )
        return completed, marker, evidence_root

    @staticmethod
    def _powershell_literal(value: str | Path) -> str:
        return "'" + str(value).replace("'", "''") + "'"

    def _function_source(self, name: str) -> str:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = f"function {name}"
        self.assertIn(marker, source)
        return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

    @staticmethod
    def _write_device_identity_fixture(
        root: Path,
        *,
        final_boot_id: str = DEVICE_BOOT_ID,
        final_properties: dict[str, str] | None = None,
        policy_properties: dict[str, str] | None = None,
    ) -> tuple[Path, Path]:
        bundle = root / "bundle.complete"
        bundle.mkdir()
        policy = root / "p40-device-policy.json"
        policy_values = DEVICE_PROPERTIES if policy_properties is None else policy_properties
        policy.write_text(
            json.dumps(
                {
                    "schema": "aneb-device-identity-policy",
                    "schema_version": "1.0.0",
                    "device_alias": "P40 Pro",
                    "adb_serial_sha256": hashlib.sha256(
                        DEVICE_SERIAL.encode("utf-8")
                    ).hexdigest(),
                    "properties": policy_values,
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n",
            encoding="utf-8",
        )
        for stage in ("preflight", "final"):
            properties = (
                final_properties
                if stage == "final" and final_properties is not None
                else DEVICE_PROPERTIES
            )
            boot_id = final_boot_id if stage == "final" else DEVICE_BOOT_ID
            (bundle / f"device-adb-serial-{stage}.txt").write_bytes(
                (DEVICE_SERIAL + "\n").encode("utf-8")
            )
            (bundle / f"device-getprop-{stage}.txt").write_bytes(
                "".join(
                    f"{key}={properties[key]}\n" for key in DEVICE_PROPERTIES
                ).encode("utf-8")
            )
            (bundle / f"device-boot-id-{stage}.txt").write_bytes(
                (boot_id + "\n").encode("utf-8")
            )
        return bundle, policy

    def _run_device_identity_verification(
        self,
        root: Path,
        bundle: Path,
        policy: Path,
        *,
        input_serial: str = DEVICE_SERIAL,
    ) -> subprocess.CompletedProcess[str]:
        functions = "\n".join(
            self._function_source(name)
            for name in (
                "ConvertTo-NativeArgument",
                "Invoke-BoundedNativeTextOnce",
                "Write-TextNoBom",
                "Get-Utf8StringSha256",
                "Assert-DeviceIdentityReport",
                "Invoke-DeviceIdentityVerification",
            )
        )
        policy_sha256 = hashlib.sha256(policy.read_bytes()).hexdigest()
        wrapper = root / "device-identity-verification.ps1"
        wrapper.write_text(
            "$ErrorActionPreference = 'Stop'\n"
            f"$AdbSerial = {self._powershell_literal(input_serial)}\n"
            "$ToolCommandTimeoutSeconds = 10\n"
            "$script:ResolvedTools = [pscustomobject]@{\n"
            f"  Python = {self._powershell_literal(shutil.which('python') or os.sys.executable)}\n"
            f"  DevicePolicy = [pscustomobject]@{{ Path = {self._powershell_literal(policy)} }}\n"
            "  ToolingProvenance = [pscustomobject]@{ external_inputs = [pscustomobject]@{ "
            f"device_policy_sha256 = '{policy_sha256}' "
            "} }\n"
            "}\n"
            f"$script:DeviceIdentityVerifierPath = {self._powershell_literal(ROOT / 'scripts' / 'verify_token_quick_device_identity.py')}\n"
            "function Assert-ToolingProvenanceStable { param($ResolvedTools) }\n"
            + functions
            + "\ntry {\n"
            f"  $report = Invoke-DeviceIdentityVerification -EvidenceDirectory {self._powershell_literal(bundle)}\n"
            "  $report | ConvertTo-Json -Compress\n"
            "} catch {\n"
            "  [Console]::Error.WriteLine($_.Exception.Message)\n"
            "  exit 42\n"
            "}\n",
            encoding="utf-8",
        )
        return subprocess.run(
            [
                self.powershell,
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(wrapper),
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=20,
        )

    def test_known_hosts_must_be_a_regular_nonreparse_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            not_a_file = root / "known-hosts-directory"
            not_a_file.mkdir()
            completed, marker, evidence_root = self._run_preflight(
                root, known_hosts_path=not_a_file
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn(
            "ssh_known_hosts_not_regular_nonreparse_file",
            completed.stdout + completed.stderr,
        )
        self.assertFalse(marker.exists())
        self.assertFalse(evidence_root.exists())

    def test_known_hosts_parent_reparse_point_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "actual"
            target.mkdir()
            (target / "known_hosts").write_text("test-only\n", encoding="ascii")
            linked = root / "linked"
            try:
                os.symlink(target, linked, target_is_directory=True)
            except (OSError, NotImplementedError) as error:
                self.skipTest(f"directory symlink unavailable: {error}")
            completed, marker, evidence_root = self._run_preflight(
                root, known_hosts_path=linked / "known_hosts"
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn(
            "ssh_known_hosts_not_regular_nonreparse_file",
            completed.stdout + completed.stderr,
        )
        self.assertFalse(marker.exists())
        self.assertFalse(evidence_root.exists())

    def test_server_base_host_must_match_remote_host_without_alias_or_port_ambiguity(self) -> None:
        cases = (
            ("https://aneb.invalid:8443", "root@alias.invalid"),
            ("https://192.0.2.10:8443", "root@192.0.2.11"),
            ("https://aneb.invalid:8443", "root@aneb.invalid:22"),
            ("https://0300.0000.0002.0012:8443", "root@192.0.2.10"),
            ("https://aneb.invalid:08443", "root@aneb.invalid"),
        )
        for server_base, remote in cases:
            with self.subTest(server_base=server_base, remote=remote):
                with tempfile.TemporaryDirectory() as temporary:
                    completed, marker, evidence_root = self._run_preflight(
                        Path(temporary), server_base=server_base, remote=remote
                    )

                self.assertNotEqual(0, completed.returncode)
                self.assertIn(
                    "remote_server_host_binding_invalid",
                    completed.stdout + completed.stderr,
                )
                self.assertFalse(marker.exists())
                self.assertFalse(evidence_root.exists())

    def test_matching_ipv4_server_and_remote_hosts_are_supported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            completed, marker, evidence_root = self._run_preflight(
                Path(temporary),
                server_base="https://192.0.2.10:8443",
                remote="root@192.0.2.10",
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("ANEB_D82_PREFLIGHT_OK", completed.stdout)
        self.assertFalse(marker.exists())
        self.assertFalse(evidence_root.exists())

    def test_preflight_only_validates_locally_without_external_calls_or_evidence_writes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            completed, marker, evidence_root = self._run_preflight(Path(temporary))

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("ANEB_D82_PREFLIGHT_OK", completed.stdout)
        self.assertIn("external_calls=0", completed.stdout)
        self.assertFalse(marker.exists())
        self.assertFalse(evidence_root.exists())

    def test_omitted_ci_verifier_override_resolves_to_the_canonical_repository_asset(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            completed, marker, evidence_root = self._run_preflight(Path(temporary))
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertFalse(marker.exists())
        self.assertFalse(evidence_root.exists())
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            "$CiProvenanceVerifierPath = Join-Path $PSScriptRoot 'verify_ci_apk_provenance.py'",
            source,
        )
        preflight = self._function_source("Assert-LocalPreflight")
        self.assertIn("ciProvenanceVerifierExpectedPath", preflight)
        self.assertIn("Assert-CanonicalRepositoryAsset", preflight)

    def test_formal_collection_derives_client_identity_from_twice_verified_ci_candidate_before_adb(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        parameters = source.split(")\n\n$ErrorActionPreference", 1)[0]
        self.assertNotIn("ExpectedApkSha256", parameters)
        self.assertNotIn("ExpectedSignerSha256", parameters)
        self.assertIn("function Initialize-CiCandidateEvidence", source)
        formal = source.split("$paths = New-EvidenceStagingDirectory", 1)[1]
        initialized = formal.index("Initialize-CiCandidateEvidence")
        adb_preflight = formal.index("Assert-LiveDevicePreflight")
        self.assertLess(initialized, adb_preflight)
        initializer = self._function_source("Initialize-CiCandidateEvidence")
        self.assertGreaterEqual(
            initializer.count("Invoke-CiProvenanceVerification"), 3
        )
        self.assertIn("Copy-CiCandidateToEvidence", initializer)
        self.assertIn("ci-candidate-verification.json", initializer)
        self.assertIn("$script:ExpectedApkSha256", initializer)
        self.assertIn("$script:ExpectedSignerSha256", initializer)

    def test_collection_identity_and_plan_timestamp_share_one_frozen_clock_sample(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        formal = source.split("$script:LogcatMarkerNonce", 1)[1]
        collection = formal.split(
            "$paths = New-EvidenceStagingDirectory", 1
        )[0]
        plan = formal.split(
            "Write-JsonNoBom -Path (Join-Path $PartialDirectory 'collector-plan.json')",
            1,
        )[1].split("\n})", 1)[0]

        self.assertIn("$collectionCreatedAtUtc = [DateTime]::UtcNow", collection)
        self.assertIn(
            "$collectionCreatedAtUtc.ToString('yyyyMMddTHHmmssZ')", collection
        )
        self.assertIn(
            "created_at_utc = $collectionCreatedAtUtc.ToString('o')", plan
        )
        self.assertNotIn(
            "created_at_utc = [DateTime]::UtcNow.ToString('o')", plan
        )

    def test_preflight_rejects_missing_or_extra_ci_candidate_payload_without_external_calls(self) -> None:
        payloads = {
            "ANEB-Probe-0.5.12-codex-debug.apk": b"apk\n",
            "build-manifest.json": b"{}\n",
            "checksums.sha256": b"sum\n",
            "provenance.sigstore.json": b"{}\n",
            "ANEB-安装说明.txt": "说明\n".encode("utf-8"),
        }
        for case in ("missing", "extra"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                candidate = root / "candidate"
                candidate.mkdir()
                for name, contents in payloads.items():
                    if case == "missing" and name == "checksums.sha256":
                        continue
                    (candidate / name).write_bytes(contents)
                if case == "extra":
                    (candidate / "unexpected.txt").write_text("no\n", encoding="utf-8")
                completed, marker, evidence_root = self._run_preflight(
                    root, candidate_directory=candidate
                )
                self.assertNotEqual(0, completed.returncode)
                self.assertIn(
                    "ci_candidate_file_set_invalid",
                    completed.stdout + completed.stderr,
                )
                self.assertFalse(marker.exists())
                self.assertFalse(evidence_root.exists())

    def test_private_policy_and_candidate_are_in_each_mode_payload_final_closure(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        finalizer = self._function_source("Write-FinalEvidenceManifest")
        self.assertIn("$expectedRequiredCount", finalizer)
        self.assertIn("if ($EvidenceMode -ceq 'negative') { 71 } else { 59 }", finalizer)
        self.assertIn("$expectedRequiredCount += $requiredSidecars.Count", finalizer)
        self.assertIn("'device-policy.json'", finalizer)
        for relative in (
            "ci-candidate/ANEB-Probe-0.5.12-codex-debug.apk",
            "ci-candidate/build-manifest.json",
            "ci-candidate/checksums.sha256",
            "ci-candidate/provenance.sigstore.json",
        ):
            self.assertIn(relative, finalizer)
        self.assertIn("Assert-BundledDevicePolicyStable", finalizer)
        policy_copy = self._function_source("Copy-DevicePolicyToEvidence")
        self.assertIn("device-policy.json", policy_copy)
        self.assertIn("device_policy_copy_mismatch", policy_copy)
        public_report = self._function_source("Invoke-PublishedBundleVerification")
        self.assertNotIn("$AdbSerial", public_report)

    @unittest.skipUnless(os.name == "nt", "Windows ACL contract")
    def test_private_evidence_root_requires_current_owner_and_write_allowlist(self) -> None:
        function_source = self._function_source("Assert-PrivateEvidenceRoot")
        cases = (
            ("wrong_owner", "BUILTIN\\Administrators", "", "", "evidence_root_owner_invalid"),
            ("guest_write", "$currentName", "S-1-5-32-546", "Write", "evidence_root_acl_too_permissive"),
            ("guest_delete", "$currentName", "S-1-5-32-546", "Delete", "evidence_root_acl_too_permissive"),
            ("guest_read", "$currentName", "S-1-5-32-546", "ReadAndExecute", ""),
            ("system_write", "$currentName", "S-1-5-18", "Write", ""),
            ("administrators_full", "$currentName", "S-1-5-32-544", "FullControl", ""),
            ("current_full", "$currentName", "$currentSid", "FullControl", ""),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, owner, sid, rights, expected_error in cases:
                with self.subTest(name=name):
                    wrapper = root / f"acl-{name}.ps1"
                    owner_expression = owner if owner.startswith("$") else f"'{owner}'"
                    if sid:
                        sid_expression = sid if sid.startswith("$") else f"'{sid}'"
                        access_expression = (
                            "@([pscustomobject]@{ "
                            f"IdentityReference=[Security.Principal.SecurityIdentifier]::new({sid_expression}); "
                            "AccessControlType=[Security.AccessControl.AccessControlType]::Allow; "
                            f"FileSystemRights=[Security.AccessControl.FileSystemRights]::{rights} }})"
                        )
                    else:
                        access_expression = "@()"
                    wrapper.write_text(
                        "$ErrorActionPreference='Stop'\nSet-StrictMode -Version 2.0\n"
                        + function_source
                        + "\n$currentIdentity=[Security.Principal.WindowsIdentity]::GetCurrent()\n"
                        + "$currentName=$currentIdentity.Name; $currentSid=$currentIdentity.User.Value\n"
                        + f"$script:testAcl=[pscustomobject]@{{ Owner={owner_expression}; Access={access_expression} }}\n"
                        + "function Get-Acl { param([string]$LiteralPath); return $script:testAcl }\n"
                        + "$caught=''\ntry { Assert-PrivateEvidenceRoot -Path '.' } catch { $caught=$_.Exception.Message }\n"
                        + (
                            f"if ($caught -notlike '{expected_error}*') {{ throw \"unexpected_acl_result=$caught\" }}\n"
                            if expected_error
                            else "if (-not [string]::IsNullOrEmpty($caught)) { throw \"unexpected_acl_rejection=$caught\" }\n"
                        ),
                        encoding="utf-8",
                    )
                    completed = subprocess.run(
                        [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                        text=True,
                        capture_output=True,
                        check=False,
                        timeout=20,
                    )
                    self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_collector_and_independent_verifier_share_the_exact_25_tool_labels(self) -> None:
        collector = SCRIPT.read_text(encoding="utf-8")
        expected_block = collector.split(
            "$assetExpectedPaths = [ordered]@{", 1
        )[1].split("\n    }", 1)[0]
        collector_labels = {
            line.split("=", 1)[0].strip()
            for line in expected_block.splitlines()
            if "=" in line and line.split("=", 1)[0].strip().isidentifier()
        }
        verifier_tree = ast.parse(
            (ROOT / "scripts" / "verify_token_quick_evidence_bundle.py").read_text(
                encoding="utf-8"
            )
        )
        verifier_labels: set[str] | None = None
        for node in verifier_tree.body:
            if (
                isinstance(node, ast.Assign)
                and any(
                    isinstance(target, ast.Name) and target.id == "TOOL_PATHS"
                    for target in node.targets
                )
            ):
                verifier_labels = set(ast.literal_eval(node.value))
                break
        self.assertIsNotNone(verifier_labels)
        self.assertEqual(25, len(collector_labels))
        self.assertEqual(verifier_labels, collector_labels)

    def test_negative_preflight_binds_canonical_proxy_and_verifier_without_external_calls(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            completed, marker, evidence_root = self._run_preflight(
                Path(temporary),
                evidence_mode="negative",
                server_base="https://203.0.113.10:8443",
                remote="root@203.0.113.10",
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("evidence_mode=negative", completed.stdout)
        self.assertFalse(marker.exists())
        self.assertFalse(evidence_root.exists())

    def test_negative_preflight_rejects_a_dns_upstream_before_external_calls(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            completed, marker, evidence_root = self._run_preflight(
                Path(temporary),
                evidence_mode="negative",
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn(
            "negative_proxy_upstream_must_be_ipv4_literal",
            completed.stdout + completed.stderr,
        )
        self.assertFalse(marker.exists())
        self.assertFalse(evidence_root.exists())

    def test_negative_preflight_rejects_an_external_same_name_proxy(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            external = root / "external" / "token_serverinfo_negative_proxy.py"
            external.parent.mkdir()
            external.write_text("print('not trusted')\n", encoding="utf-8")
            completed, marker, evidence_root = self._run_preflight(
                root,
                evidence_mode="negative",
                negative_proxy=external,
                server_base="https://203.0.113.10:8443",
                remote="root@203.0.113.10",
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn(
            "repository_asset_path_mismatch label=negative serverinfo proxy",
            completed.stdout + completed.stderr,
        )
        self.assertFalse(marker.exists())
        self.assertFalse(evidence_root.exists())

    def test_ci_provenance_report_gate_accepts_one_bound_line_and_rejects_identity_substitution(self) -> None:
        source_commit = "c" * 40
        gh_sha256 = "a" * 64
        report = {
            "schema": "aneb-ci-apk-provenance-report",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "candidate_provenance_reverified": True,
            "repository": "lucassu2012/ANEB_GPT",
            "signer_workflow": "lucassu2012/ANEB_GPT/.github/workflows/ci.yml",
            "predicate_type": "https://slsa.dev/provenance/v1",
            "source_commit": source_commit,
            "source_ref": "refs/heads/main",
            "workflow_run_id": 123,
            "workflow_run_url": "https://github.com/lucassu2012/ANEB_GPT/actions/runs/123",
            "apk": {
                "file_name": "ANEB-Probe-0.5.12-codex-debug.apk",
                "sha256": "b" * 64,
                "size_bytes": 4096,
                "package_name": "com.aneb.probe.codex",
                "version_name": "0.5.12-codex",
                "version_code": 44,
                "signer_sha256": "d" * 64,
            },
            "files": {
                "attestation_bundle_sha256": "e" * 64,
                "build_manifest_sha256": "f" * 64,
                "checksums_sha256": "1" * 64,
            },
            "gh": {
                "version": "2.83.0",
                "executable_sha256": gh_sha256,
                "certificate_issuer": "sigstore",
                "oidc_issuer": "https://token.actions.githubusercontent.com",
                "runner_environment": "github-hosted",
                "run_invocation_uri": "https://github.com/lucassu2012/ANEB_GPT/actions/runs/123/attempts/1",
                "subject_alternative_name": "workflow",
                "verified_timestamp_count": 1,
            },
        }
        functions = "\n".join(
            self._function_source(name)
            for name in ("Test-ExactPropertyNames", "Assert-CiProvenanceReport")
        )
        forged = json.loads(json.dumps(report))
        forged["source_commit"] = "2" * 40
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "ci-report-gate.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "$PackageName = 'com.aneb.probe.codex'\n"
                "$ExpectedVersionName = '0.5.12-codex'\n"
                "$ExpectedVersionCode = 44\n"
                f"$script:ResolvedTools = [pscustomobject]@{{ GhSha256 = '{gh_sha256}' }}\n"
                + functions
                + "\n"
                + f"$valid = '{json.dumps(report, separators=(',', ':'))}'\n"
                + f"$forged = '{json.dumps(forged, separators=(',', ':'))}'\n"
                + f"$null = Assert-CiProvenanceReport -OutputLines @($valid) -ExitCode 0 -SourceCommit '{source_commit}'\n"
                + "$caught = $false\n"
                + f"try {{ $null = Assert-CiProvenanceReport -OutputLines @($forged) -ExitCode 0 -SourceCommit '{source_commit}' }} "
                + "catch { if ($_.Exception.Message -eq 'ci_provenance_verifier_report_binding_mismatch') { $caught = $true } else { throw } }\n"
                + "if (-not $caught) { throw 'forged_candidate_identity_was_accepted' }\n"
                + "$multi = $false\n"
                + f"try {{ $null = Assert-CiProvenanceReport -OutputLines @($valid, $valid) -ExitCode 0 -SourceCommit '{source_commit}' }} "
                + "catch { if ($_.Exception.Message -eq 'ci_provenance_verifier_output_invalid') { $multi = $true } else { throw } }\n"
                + "if (-not $multi) { throw 'multiline_candidate_report_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_device_policy_preflight_is_strict_and_bound_to_input_serial(self) -> None:
        valid = {
            "schema": "aneb-device-identity-policy",
            "schema_version": "1.0.0",
            "device_alias": "P40 Pro",
            "adb_serial_sha256": hashlib.sha256(
                DEVICE_SERIAL.encode("utf-8")
            ).hexdigest(),
            "properties": DEVICE_PROPERTIES,
        }
        cases: tuple[tuple[str, str, str], ...] = (
            (
                "wrong_alias",
                json.dumps({**valid, "device_alias": "another device"}),
                "device_policy_invalid",
            ),
            (
                "wrong_input_serial_hash",
                json.dumps({**valid, "adb_serial_sha256": "0" * 64}),
                "device_policy_input_serial_mismatch",
            ),
            (
                "duplicate_schema",
                '{"schema":"aneb-device-identity-policy",'
                '"schema":"aneb-device-identity-policy",'
                '"schema_version":"1.0.0","device_alias":"P40 Pro",'
                f'"adb_serial_sha256":"{valid["adb_serial_sha256"]}",'
                f'"properties":{json.dumps(DEVICE_PROPERTIES)}}}',
                "device_policy_invalid",
            ),
        )
        for name, body, reason in cases:
            with self.subTest(name=name):
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    policy = root / "p40-device-policy.json"
                    policy.write_text(body + "\n", encoding="utf-8")
                    completed, marker, evidence_root = self._run_preflight(
                        root, device_policy_path=policy
                    )
                self.assertNotEqual(0, completed.returncode)
                self.assertIn(reason, completed.stdout + completed.stderr)
                self.assertFalse(marker.exists())
                self.assertFalse(evidence_root.exists())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            policy_directory = root / "p40-device-policy.json"
            policy_directory.mkdir()
            completed, marker, evidence_root = self._run_preflight(
                root, device_policy_path=policy_directory
            )
        self.assertNotEqual(0, completed.returncode)
        self.assertIn(
            "device_policy_not_regular_nonreparse_file",
            completed.stdout + completed.stderr,
        )
        self.assertFalse(marker.exists())
        self.assertFalse(evidence_root.exists())

    def test_external_same_name_bundle_verifier_cannot_reach_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            external = root / "external" / "verify_token_quick_evidence_bundle.py"
            external.parent.mkdir()
            external.write_text(
                "print('{\"schema\":\"aneb-d82-bundle-verification-report\",'"
                "'\"schema_version\":\"1.0.0\",\"status\":\"pass\",'"
                "'\"reason_code\":\"ok\"}')\n",
                encoding="ascii",
            )
            completed, marker, evidence_root = self._run_preflight(
                root, bundle_verifier=external
            )

            self.assertNotEqual(0, completed.returncode, completed.stdout + completed.stderr)
            self.assertIn(
                "repository_asset_path_mismatch label=independent bundle verifier",
                completed.stdout + completed.stderr,
            )
            self.assertFalse(marker.exists())
            self.assertFalse(evidence_root.exists())
            self.assertEqual([], list(root.rglob("*.complete")))

    def test_all_repository_assets_are_exact_path_and_reparse_checked(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        preflight = source.split("function Assert-LocalPreflight", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("Assert-CanonicalRepositoryAsset", preflight)
        for label in (
            "collector",
            "derive_helper",
            "audit_verifier",
            "client_db_verifier",
            "bundle_verifier",
            "result_jsonl_verifier",
            "time_chain_verifier",
            "raw_state_verifier",
            "request_entry_contract",
            "profile_manifest",
            "profile_definition",
            "runtime_plan",
            "result_schema_core_v1",
            "result_schema_v1",
            "result_schema_v2",
            "room_schema_v19",
            "spec_catalog",
            "server_ca",
        ):
            with self.subTest(label=label):
                self.assertIn(f"{label} =", preflight)
        canonical = source.split("function Assert-CanonicalRepositoryAsset", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("repository_asset_path_mismatch", canonical)
        self.assertIn("repository_asset_reparse_point_forbidden", canonical)
        stable = source.split("function Assert-ToolingProvenanceStable", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("Assert-CanonicalRepositoryAsset", stable)

    def test_adb_ssh_and_tool_calls_use_bounded_native_processes(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        for function_name, timeout in (
            ("Invoke-AdbTextOnce", "$AdbCommandTimeoutSeconds"),
            ("Invoke-SshTextOnce", "$SshCommandTimeoutSeconds"),
            ("Invoke-ToolTextOnce", "$ToolCommandTimeoutSeconds"),
        ):
            body = source.split(f"function {function_name}", 1)[1].split(
                "\nfunction ", 1
            )[0]
            self.assertIn("Invoke-BoundedNativeTextOnce", body)
            self.assertIn(timeout, body)
        bounded = source.split("function Invoke-BoundedNativeTextOnce", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("WaitForExit($TimeoutSeconds * 1000)", bounded)
        self.assertIn("$process.Kill()", bounded)

    def test_every_ssh_process_uses_strict_pinned_known_hosts_arguments(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        ssh_arguments = source.split("function Get-SshArguments", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("Assert-SshKnownHostsStable", ssh_arguments)
        self.assertIn("StrictHostKeyChecking=yes", ssh_arguments)
        self.assertIn("CheckHostIP=yes", ssh_arguments)
        self.assertIn("HostName=$($script:ResolvedTools.BoundRemoteHost)", ssh_arguments)
        self.assertIn("CanonicalizeHostname=no", ssh_arguments)
        self.assertIn("Port=22", ssh_arguments)
        self.assertIn("UserKnownHostsFile=$($script:ResolvedTools.KnownHosts)", ssh_arguments)
        self.assertIn("GlobalKnownHostsFile=$($script:ResolvedTools.KnownHosts)", ssh_arguments)
        self.assertIn("KnownHostsCommand=none", ssh_arguments)
        self.assertNotIn("StrictHostKeyChecking=no", source)
        persistent = source.split("function Start-PersistentAuditLock", 1)[1].split(
            "\nfunction ", 1
        )[0]
        remote_file = source.split("function Export-LockedJournalOnce", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("Get-SshArguments", persistent)
        self.assertIn("Get-SshArguments", remote_file)

    def test_known_hosts_digest_drift_is_rejected_before_ssh_arguments_are_built(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        def function_source(name: str) -> str:
            marker = f"function {name}"
            return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            known_hosts = root / "known_hosts"
            original = b"aneb.invalid ssh-ed25519 test-key\n"
            known_hosts.write_bytes(original)
            digest = hashlib.sha256(original).hexdigest()
            escaped_path = str(known_hosts).replace("'", "''")
            wrapper = root / "known-hosts-drift.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\nSet-StrictMode -Version 2.0\n"
                + function_source("Resolve-RegularNonReparseFile")
                + "\n"
                + function_source("Assert-ToolingFileStable")
                + "\n"
                + function_source("Assert-SshKnownHostsStable")
                + "\n"
                + "$script:ResolvedTools = [pscustomobject]@{ KnownHosts = '"
                + escaped_path
                + "'; ToolingProvenance = [pscustomobject]@{ external_inputs = "
                + "[pscustomobject]@{ ssh_known_hosts_sha256 = '"
                + digest
                + "' } } }\n"
                + "Assert-SshKnownHostsStable\n"
                + "[IO.File]::AppendAllText($script:ResolvedTools.KnownHosts, 'drift')\n"
                + "$caught = $false\ntry { Assert-SshKnownHostsStable } catch { "
                + "if ($_.Exception.Message -like 'tooling_digest_drift label=ssh_known_hosts*') "
                + "{ $caught = $true } else { throw } }\n"
                + "if (-not $caught) { throw 'known_hosts_drift_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_plan_and_provenance_bind_remote_host_and_known_hosts_digest_without_contents(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        parameter_block = source.split("param(", 1)[1].split(
            "$ErrorActionPreference", 1
        )[0]
        self.assertIn("[Parameter(Mandatory = $true)][string]$KnownHostsPath", parameter_block)
        plan = source.split(
            "Write-JsonNoBom -Path (Join-Path $PartialDirectory 'collector-plan.json')", 1
        )[1].split("\n})", 1)[0]
        self.assertIn("remote_host = $script:ResolvedTools.BoundRemoteHost", plan)
        self.assertIn(
            "ssh_known_hosts_sha256 = [string]$script:ResolvedTools.ToolingProvenance.external_inputs.ssh_known_hosts_sha256",
            plan,
        )
        self.assertNotIn("known_hosts_path", plan.lower())
        preflight = source.split("function Assert-LocalPreflight", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("external_inputs = [ordered]@{", preflight)
        self.assertIn("ssh_known_hosts_sha256 = (Get-FileHash", preflight)
        stable = source.split("function Assert-ToolingProvenanceStable", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("external_inputs.ssh_known_hosts_sha256", stable)

    def test_device_identity_collection_and_manifest_contract_is_wired(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        parameter_block = source.split("param(", 1)[1].split(
            "$ErrorActionPreference", 1
        )[0]
        self.assertIn(
            "[Parameter(Mandatory = $true)][string]$DevicePolicyPath",
            parameter_block,
        )
        preflight = source.split("function Assert-LocalPreflight", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("device_identity_verifier", preflight)
        self.assertIn("device_policy_sha256", preflight)

        snapshot = source.split("function Write-DeviceIdentitySnapshot", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("get-serialno", snapshot)
        self.assertIn("/proc/sys/kernel/random/boot_id", snapshot)
        self.assertIn("$DevicePropertyKeys", snapshot)
        self.assertGreaterEqual(snapshot.count("Invoke-AdbTextOnce"), 3)
        self.assertIn("$serial -cne $AdbSerial", snapshot)
        for kind in ("adb-serial", "getprop", "boot-id"):
            self.assertIn(f"device-{kind}-$Stage.txt", snapshot)

        live_preflight = source.split("function Assert-LiveDevicePreflight", 1)[1].split(
            "\nfunction ", 1
        )[0]
        final_clean = source.split("function Assert-LiveDeviceCleanAfter", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("Write-DeviceIdentitySnapshot", live_preflight)
        self.assertIn("-Stage 'preflight'", live_preflight)
        self.assertIn("Write-DeviceIdentitySnapshot", final_clean)
        self.assertIn("-Stage 'final'", final_clean)
        self.assertIn("Invoke-DeviceIdentityVerification", final_clean)

        plan = source.split(
            "Write-JsonNoBom -Path (Join-Path $PartialDirectory 'collector-plan.json')", 1
        )[1].split("\n})", 1)[0]
        self.assertIn("adb_serial_sha256 =", plan)
        self.assertIn("device_policy_sha256 =", plan)
        self.assertNotIn("adb_serial =", plan)
        self.assertNotIn("DevicePolicyPath", plan)

        finalizer = source.split("function Write-FinalEvidenceManifest", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for stage in ("preflight", "final"):
            for kind in ("adb-serial", "getprop", "boot-id"):
                self.assertIn(f"device-{kind}-{stage}.txt", finalizer)
        self.assertIn("device-identity-report.json", finalizer)
        self.assertIn("device = [ordered]@{", finalizer)
        device_fields = (
            "schema",
            "schema_version",
            "status",
            "reason_code",
            "device_alias",
            "device_policy_sha256",
            "adb_serial_sha256",
            "android_boot_id",
            "properties_sha256",
            "serial_property_confirmed",
            "verified_boot_observed_complete",
            "verified_boot_secure",
            "raw_files_verified",
        )
        for field in device_fields:
            self.assertIn(field, finalizer)
        device_block = finalizer.split("device = [ordered]@{", 1)[1].split(
            "\n        }", 1
        )[0]
        assigned = {
            line.strip().split(" = ", 1)[0]
            for line in device_block.splitlines()
            if " = " in line
        }
        self.assertEqual(set(device_fields), assigned)
        self.assertNotIn("DevicePolicyPath", finalizer)

    def test_collector_device_identity_verifier_rejects_cross_snapshot_drift(self) -> None:
        changed_fingerprint = dict(DEVICE_PROPERTIES)
        changed_fingerprint["ro.build.fingerprint"] += ".changed"
        changed_policy = dict(DEVICE_PROPERTIES)
        changed_policy["ro.product.device"] = "OTHER"
        cases = (
            (
                "same_serial_different_boot",
                {"final_boot_id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"},
                DEVICE_SERIAL,
                "device_boot_id_mismatch",
            ),
            (
                "fingerprint_drift",
                {"final_properties": changed_fingerprint},
                DEVICE_SERIAL,
                "device_properties_mismatch",
            ),
            (
                "policy_tamper",
                {"policy_properties": changed_policy},
                DEVICE_SERIAL,
                "device_policy_mismatch",
            ),
            (
                "input_serial_mismatch",
                {},
                "OTHER-SERIAL",
                "device_serial_mismatch",
            ),
        )
        for name, fixture_options, input_serial, reason in cases:
            with self.subTest(name=name):
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    bundle, policy = self._write_device_identity_fixture(
                        root, **fixture_options
                    )
                    completed = self._run_device_identity_verification(
                        root, bundle, policy, input_serial=input_serial
                    )
                self.assertNotEqual(0, completed.returncode)
                self.assertIn(reason, completed.stdout + completed.stderr)
                self.assertNotIn(DEVICE_SERIAL, completed.stdout + completed.stderr)

    def test_collector_empty_verified_boot_is_observed_but_not_claimed_secure(self) -> None:
        properties = dict(DEVICE_PROPERTIES)
        for key in (
            "ro.boot.verifiedbootstate",
            "ro.boot.vbmeta.device_state",
            "ro.boot.flash.locked",
            "ro.boot.veritymode",
        ):
            properties[key] = ""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle, policy = self._write_device_identity_fixture(
                root,
                final_properties=properties,
                policy_properties=properties,
            )
            preflight = bundle / "device-getprop-preflight.txt"
            preflight.write_bytes(
                "".join(
                    f"{key}={properties[key]}\n" for key in DEVICE_PROPERTIES
                ).encode("utf-8")
            )
            completed = self._run_device_identity_verification(root, bundle, policy)

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        report = json.loads(completed.stdout)
        self.assertFalse(report["verified_boot_observed_complete"])
        self.assertFalse(report["verified_boot_secure"])
        self.assertNotIn(DEVICE_SERIAL, completed.stdout + completed.stderr)

    def test_serverinfo_and_barrier_curl_have_parent_process_deadlines(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("$CurlParentTimeoutSeconds = 30", source)
        for function_name in ("Invoke-ServerInfoOnce", "Invoke-BarrierOnce"):
            with self.subTest(function_name=function_name):
                body = source.split(f"function {function_name}", 1)[1].split(
                    "\nfunction ", 1
                )[0]
                self.assertIn("Invoke-BoundedNativeTextOnce", body)
                self.assertIn("-TimeoutSeconds $CurlParentTimeoutSeconds", body)
                self.assertNotIn("@(& $script:ResolvedTools.Curl", body)
                self.assertIn("curl_timeout", body)

    def test_collector_never_assigns_to_read_only_pid_automatic_variable(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        assignment_lines = [
            source.count("\n", 0, match.start()) + 1
            for match in re.finditer(
                r"(?im)^\s*\$pid\s*(?:=|\+=|-=|\+\+|--)",
                source,
            )
        ]
        self.assertEqual(
            [],
            assignment_lines,
            "PowerShell variable names are case-insensitive; assigning to $pid "
            f"overwrites the read-only $PID automatic variable at lines {assignment_lines}",
        )

    def test_bounded_native_process_kills_hung_child_with_machine_reason(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        convert = "function ConvertTo-NativeArgument" + source.split(
            "function ConvertTo-NativeArgument", 1
        )[1].split("\nfunction ", 1)[0]
        bounded = "function Invoke-BoundedNativeTextOnce" + source.split(
            "function Invoke-BoundedNativeTextOnce", 1
        )[1].split("\nfunction ", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "bounded-native.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                + convert
                + "\n"
                + bounded
                + "\n$started = [DateTime]::UtcNow\n"
                + "$shellPath = (Get-Process -Id $PID).Path\n"
                + "$caught = $false\n"
                + "try { $null = Invoke-BoundedNativeTextOnce -Command $shellPath "
                + "-Arguments @('-NoProfile','-Command','Start-Sleep -Seconds 20') "
                + "-TimeoutSeconds 1 -TimeoutReason 'adb_timeout stage=synthetic_hang' "
                + "-LaunchReason 'adb_launch_failed stage=synthetic_hang' } "
                + "catch { if ($_.Exception.Message -eq 'adb_timeout stage=synthetic_hang timeout_seconds=1') "
                + "{ $caught = $true } else { throw } }\n"
                + "if (-not $caught) { throw 'hung_process_was_not_timed_out' }\n"
                + "if (([DateTime]::UtcNow - $started).TotalSeconds -gt 6) { throw 'timeout_not_bounded' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=10,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_default_evidence_root_is_private_and_outside_the_repository(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        parameter_block = source.split("param(", 1)[1].split("$ErrorActionPreference", 1)[0]
        self.assertIn("LocalApplicationData", parameter_block)
        self.assertNotIn("Split-Path -Parent $PSScriptRoot) 'ValidationEvidence'", parameter_block)
        self.assertIn("evidence_root_inside_git_worktree_forbidden", source)

    @unittest.skipUnless(os.name == "nt", "Windows junction contract")
    def test_evidence_root_chain_rejects_an_ancestor_junction_before_writes(self) -> None:
        function_source = self._function_source("Assert-NonReparseDirectoryChain")
        source = SCRIPT.read_text(encoding="utf-8")
        preflight = self._function_source("Assert-LocalPreflight")
        self.assertLess(
            preflight.index("Assert-NonReparseDirectoryChain"),
            preflight.index("evidence_root_inside_git_worktree_forbidden"),
        )
        staging = self._function_source("New-EvidenceStagingDirectory")
        self.assertGreaterEqual(staging.count("Assert-NonReparseDirectoryChain"), 3)
        publication = source.split("$draftManifestPath = Write-EvidenceManifestDraft", 1)[1]
        self.assertIn("Assert-NonReparseDirectoryChain", publication)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "junction-target"
            target.mkdir()
            junction = root / "junction-parent"
            wrapper = root / "evidence-chain.ps1"
            wrapper.write_text(
                "$ErrorActionPreference='Stop'\nSet-StrictMode -Version 2.0\n"
                + function_source
                + f"\n$root='{root}'\n$target='{target}'\n$junction='{junction}'\n"
                + "$normal=Join-Path $root 'normal\\future\\evidence'\n"
                + "$null=Assert-NonReparseDirectoryChain -Path $normal -ReasonPrefix 'evidence_root'\n"
                + "try { $null=New-Item -ItemType Junction -Path $junction -Target $target } "
                + "catch { if ($_.Exception.Message -match 'Access is denied') { exit 77 } else { throw } }\n"
                + "$caught=$false\ntry { Assert-NonReparseDirectoryChain "
                + "-Path (Join-Path $junction 'future\\evidence') -ReasonPrefix 'evidence_root' } "
                + "catch { if ($_.Exception.Message -like 'evidence_root_reparse_point_forbidden*') "
                + "{ $caught=$true } else { throw } }\n"
                + "Remove-Item -LiteralPath $junction -Force\n"
                + "if (-not $caught) { throw 'ancestor_junction_was_accepted' }\n"
                + "if (Test-Path -LiteralPath (Join-Path $target 'future')) { throw 'junction_target_was_written' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=30,
            )

        if completed.returncode == 77:
            self.skipTest("Windows policy denied creation of a temporary junction")
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_one_persistent_ssh_process_holds_the_deploy_lock_until_nonce_release(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("function Start-PersistentAuditLock", source)
        self.assertIn('exec 9>"$LOCK_PATH"', source)
        self.assertIn("flock -n 9", source)
        self.assertIn("LOCK_ACQUIRED nonce=%s pid=%s marker=%s", source)
        self.assertIn('if ! IFS= read -r -t "$TTL_SECONDS" command; then', source)
        self.assertIn('[[ "$command" == "RELEASE $NONCE" ]]', source)
        self.assertIn("LOCK_RELEASED nonce=%s", source)
        self.assertIn('trap cleanup EXIT HUP INT TERM', source)
        self.assertIn("function Assert-PersistentAuditLock", source)
        self.assertIn("LOCK_HEALTHY", source)
        lock_assertion = source[source.index("function Assert-PersistentAuditLock") :]
        self.assertIn("flock -n", lock_assertion)

    def test_lock_ttl_is_enforced_by_the_holder_without_a_background_stdio_child(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        holder = source.split("$remoteLockHolder = @'", 1)[1].split("\n'@", 1)[0]
        self.assertIn('if ! IFS= read -r -t "$TTL_SECONDS" command; then', holder)
        self.assertNotIn("PARENT_PID", holder)
        self.assertNotIn("GUARD_PID", holder)
        self.assertNotIn('sleep "$TTL_SECONDS"', holder)
        self.assertNotRegex(holder, r"(?m)^\s*\)\s*&\s*$")

    def test_lock_release_uses_lf_only_protocol_lines(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        start_lock = source.split("function Start-PersistentAuditLock", 1)[1].split(
            "\nfunction ", 1
        )[0]
        release_lock = source.split("function Release-PersistentAuditLockOnce", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn('$process.StandardInput.NewLine = "`n"', start_lock)
        self.assertIn('StandardInput.WriteLine("RELEASE $($Lock.Nonce)")', release_lock)

    def test_lock_health_printf_emits_a_real_newline(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        lock_assertion = source[source.index("function Assert-PersistentAuditLock") :]
        health_line = next(
            line for line in lock_assertion.splitlines() if "printf 'LOCK_HEALTHY" in line
        )
        self.assertNotIn(
            r"pid=%s\\n",
            health_line,
            "Bash printf must receive one backslash before n; two emit a literal \\\\n",
        )

    def test_remote_bash_printf_never_receives_a_literal_backslash_n(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        offenders = [
            (line_number, line.strip())
            for line_number, line in enumerate(source.splitlines(), start=1)
            if "printf " in line and r"\\n" in line
        ]
        self.assertEqual(
            [],
            offenders,
            "Remote Bash printf format strings must use one backslash before n",
        )

    def test_remote_snapshot_parsers_accept_digit_bearing_contract_keys(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        def function(name: str) -> str:
            marker = f"function {name}"
            return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

        binary = "a" * 64
        boot = "b" * 32
        invocation = "c" * 32
        cursor = "s=1234567890abcdef"
        anchor = base64.b64encode(
            json.dumps(
                {"__CURSOR": cursor, "__MONOTONIC_TIMESTAMP": "123456789"},
                separators=(",", ":"),
            ).encode("utf-8")
        ).decode("ascii")
        pre = (
            f"boot_id={boot}\n"
            f"systemd_invocation_id={invocation}\n"
            "main_pid=3388782\n"
            f"server_binary_sha256={binary}\n"
            "remote_realtime_anchor_usec=1784246399000000\n"
            f"journal_anchor_json_base64={anchor}"
        )
        end = (
            f"boot_id={boot}\n"
            f"systemd_invocation_id={invocation}\n"
            "main_pid=3388782\n"
            f"server_binary_sha256={binary}"
        )
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "remote-snapshot-keys.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                + f"$ExpectedServerBinarySha256 = '{binary}'\n"
                + f"$script:Pre = '{pre}'\n"
                + f"$script:End = '{end}'\n"
                + "function Assert-PersistentAuditLock { param($Lock, [string]$Stage) }\n"
                + "function Write-TextNoBom { param([string]$Path, [string]$Text) }\n"
                + "function Invoke-SshTextOnce { param([string]$RemoteCommand, [string]$Stage); "
                + "if ($Stage -eq 'pre_start_snapshot') { return $script:Pre }; return $script:End }\n"
                + function("Get-RemotePreStartSnapshot")
                + "\n"
                + function("Assert-RemoteSnapshotStable")
                + "\n$snapshot = Get-RemotePreStartSnapshot -Lock ([pscustomobject]@{}) -EvidenceDirectory '.'\n"
                + "if ($snapshot.BinarySha256 -cne ('a' * 64)) { throw 'pre_snapshot_digit_key_lost' }\n"
                + "Assert-RemoteSnapshotStable -Lock ([pscustomobject]@{}) -Snapshot $snapshot -EvidenceDirectory '.'\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_live_preflight_uses_current_device_state_and_never_the_retired_lease_file(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("SHARED_TEST_STATUS", source)
        self.assertNotRegex(source.casefold(), r"\b(?:claim|leaseid|待交接|异常锁定)\b")
        self.assertIn("function Assert-LiveDevicePreflight", source)
        preflight = source.split("function Assert-LiveDevicePreflight", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for required in (
            "get-state",
            "dumpsys window",
            "pidof",
            "dumpsys activity services",
            "enabled_accessibility_services",
            "/sys/class/net/tun*",
            "dumpsys connectivity",
            "dumpsys vpn",
            "stay_on_while_plugged_in",
            "dumpsys package",
            "pm path",
            "run-as",
            "Signer #1 certificate SHA-256 digest",
        ):
            with self.subTest(required=required):
                self.assertIn(required, preflight)
        self.assertIn("com.huawei.android.launcher/.unihome.UniHomeLauncher", source)
        self.assertNotIn("force-stop", preflight)
        self.assertNotIn("am start", preflight)

    def test_live_focus_sampling_uses_full_huawei_window_dump(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("dumpsys window windows", source)
        self.assertGreaterEqual(source.count("'dumpsys window'"), 4)

    def test_launcher_gate_accepts_huawei_equivalent_dual_resumed_fields(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Assert-HuaweiLauncherFocused"
        function_source = marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        canonical_source = self._function_source(
            "ConvertTo-CanonicalAndroidComponent"
        )
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "launcher-dual-resumed.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "$LauncherComponent = 'com.huawei.android.launcher/.unihome.UniHomeLauncher'\n"
                f"{canonical_source}\n"
                f"{function_source}\n"
                "$window = @'\n"
                "mCurrentFocus=Window{42 u0 com.huawei.android.launcher/com.huawei.android.launcher.unihome.UniHomeLauncher}\n"
                "'@\n"
                "$activity = @'\n"
                "mFocusedApp=ActivityRecord{1 u0 com.huawei.android.launcher/.unihome.UniHomeLauncher t2}\n"
                "mResumedActivity: ActivityRecord{1 u0 com.huawei.android.launcher/.unihome.UniHomeLauncher t2}\n"
                "ResumedActivity: ActivityRecord{1 u0 com.huawei.android.launcher/.unihome.UniHomeLauncher t2}\n"
                "mLastResumedActivity=ActivityRecord{9 u0 com.example.history/.HistoricalActivity t1}\n"
                "'@\n"
                "$result=Assert-HuaweiLauncherFocused -WindowDump $window -ActivityDump $activity\n"
                "if (@($result.ResumedComponents).Count -ne 2) { throw 'resumed_shape_not_preserved' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_launcher_gate_rejects_historical_launcher_when_current_focus_is_elsewhere(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Assert-HuaweiLauncherFocused"
        self.assertIn(marker, source)
        function_source = marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        canonical_source = self._function_source(
            "ConvertTo-CanonicalAndroidComponent"
        )
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "launcher-gate.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "$LauncherComponent = 'com.huawei.android.launcher/.unihome.UniHomeLauncher'\n"
                f"{canonical_source}\n"
                f"{function_source}\n"
                "$window = @'\n"
                "mCurrentFocus=Window{42 u0 com.android.permissioncontroller/.permission.ui.GrantPermissionsActivity}\n"
                "'@\n"
                "$activity = @'\n"
                "mFocusedApp=ActivityRecord{1 u0 com.android.permissioncontroller/.permission.ui.GrantPermissionsActivity t9}\n"
                "topResumedActivity=ActivityRecord{1 u0 com.android.permissioncontroller/.permission.ui.GrantPermissionsActivity t9}\n"
                "Hist #1: ActivityRecord{2 u0 com.huawei.android.launcher/.unihome.UniHomeLauncher t1}\n"
                "'@\n"
                "Assert-HuaweiLauncherFocused -WindowDump $window -ActivityDump $activity\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn("live_state_not_launcher", completed.stdout + completed.stderr)

    def test_vpn_gate_ignores_listen_request_but_rejects_connected_vpn_agent(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Assert-NoActiveVpn"
        self.assertIn(marker, source)
        function_source = marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "vpn-gate.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{function_source}\n"
                "$listen = \"NetworkAgentInfo{ ni{[type: WIFI[], state: CONNECTED/CONNECTED]} nc{[ Transports: WIFI Capabilities: VALIDATED ]}}`nNetworkRequest [ LISTEN id=7, [ Transports: VPN Capabilities: NOT_RESTRICTED ]]\"\n"
                "Assert-NoActiveVpn -ConnectivityDump $listen -VpnDump 'No VPNs configured'\n"
                "$connected = 'NetworkAgentInfo{ ni{[type: VPN[], state: CONNECTED/CONNECTED]} nc{[ Transports: VPN Capabilities: VALIDATED ]}}'\n"
                "Assert-NoActiveVpn -ConnectivityDump $connected -VpnDump 'No VPNs configured'\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn("live_state_active_vpn", completed.stdout + completed.stderr)

    def test_barriers_are_fresh_one_shot_and_app_run_id_comes_from_current_uuidv7_log(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        parameter_block = source.split("param(", 1)[1].split("$ErrorActionPreference", 1)[0]
        for forbidden in ("RunId", "StartBarrierId", "EndBarrierId", "BarrierId"):
            self.assertNotIn(f"${forbidden}", parameter_block)
        self.assertIn("function Invoke-BarrierOnce", source)
        barrier = source.split("function Invoke-BarrierOnce", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("barrier_already_attempted", barrier)
        self.assertIn("$Attempted.Value = $true", barrier)
        self.assertIn("X-Aneb-Run-Id", barrier)
        self.assertIn("X-Aneb-Audit-Role", barrier)
        self.assertNotIn("--retry", barrier)
        self.assertEqual(1, barrier.count("$script:ResolvedTools.Curl"))
        self.assertIn("function Start-TokenQuickRun", source)
        launch = source.split("function Start-TokenQuickRun", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for extra in (
            "--es server",
            "--ez autorun true",
            "--es mode quick",
            "--es transport",
            "--es test_mode token",
        ):
            self.assertIn(extra, launch)
        self.assertIn("TOKEN_V2_START", source)
        self.assertIn("D82_CAPTURE_MARKER", source)
        self.assertIn("function Get-PostMarkerLogText", source)
        self.assertIn("uuidv7", source.casefold())
        self.assertNotIn("logcat -c", source)

    def test_logcat_boundary_excludes_a_complete_historical_run_in_the_same_second(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Get-PostMarkerLogText"
        self.assertIn(marker, source)
        function_source = marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        nonce = "a" * 32
        old_run = "019f8000-0000-7000-8000-000000000001"
        new_run = "019f8000-0000-7000-8000-000000000002"
        log_text = (
            f"TOKEN_V2_START run_id={old_run}\n"
            f"TOKEN_V2_END run_id={old_run} status=completed\n"
            f"D82_CAPTURE_MARKER nonce={nonce}\n"
            f"TOKEN_V2_START run_id={new_run}\n"
        )
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "log-boundary.ps1"
            escaped = log_text.replace("'", "''")
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{function_source}\n"
                f"$post = Get-PostMarkerLogText -Text '{escaped}' -MarkerNonce '{nonce}'\n"
                f"if ($post -match '{old_run}') {{ throw 'old_run_replayed' }}\n"
                f"if ($post -notmatch '{new_run}') {{ throw 'new_run_missing' }}\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_logcat_marker_poll_accepts_an_existing_empty_redirect_file(self) -> None:
        boundary = self._function_source("Get-PostMarkerLogText")
        marker_writer = self._function_source("Write-LogcatCaptureMarker")
        nonce = "c" * 32
        marker_line = f"1784851221.985 31128 31128 I AnebD82 : D82_CAPTURE_MARKER nonce={nonce}\n"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "app-logcat.txt"
            output.write_bytes(b"")
            wrapper = root / "empty-logcat-marker.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "function Invoke-AdbTextOnce { param([string[]]$Arguments, [string]$Stage); return '' }\n"
                "function Write-JsonNoBom { param([string]$Path, $Value); $script:MarkerWritten = $true }\n"
                f"{boundary}\n{marker_writer}\n"
                "$holder = [Diagnostics.Process]::GetCurrentProcess()\n"
                "$writer = Start-Job -ScriptBlock {\n"
                "  param([string]$Path, [string]$Line)\n"
                "  Start-Sleep -Milliseconds 300\n"
                "  [IO.File]::AppendAllText($Path, $Line, (New-Object Text.UTF8Encoding($false)))\n"
                f"}} -ArgumentList {self._powershell_literal(output)}, {self._powershell_literal(marker_line)}\n"
                "try {\n"
                f"  $logcat = [pscustomobject]@{{ Process=$holder; OutputPath={self._powershell_literal(output)} }}\n"
                f"  Write-LogcatCaptureMarker -Logcat $logcat -MarkerNonce '{nonce}' "
                f"-EvidenceDirectory {self._powershell_literal(root)}\n"
                "  if (-not $script:MarkerWritten) { throw 'marker_receipt_not_written' }\n"
                "} finally {\n"
                "  $writer | Stop-Job -ErrorAction SilentlyContinue\n"
                "  $writer | Remove-Job -Force -ErrorAction SilentlyContinue\n"
                "}\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_negative_completion_requires_one_durable_receipt_missing_rejection(self) -> None:
        boundary = self._function_source("Get-PostMarkerLogText")
        completion = self._function_source("Get-TokenQuickCompletionFromLog")
        nonce = "b" * 32
        run_id = "019f8000-0000-7000-8000-000000000003"
        log_text = (
            f"D82_CAPTURE_MARKER nonce={nonce}\n"
            f"TOKEN_V2_START run_id={run_id} variant=quick\n"
            f"TOKEN_V2_RADIO run_id={run_id} status=unavailable samples=0\n"
            f"TOKEN_V2_DB_WRITE run_id={run_id} ok=true\n"
            f"TOKEN_V2_CONTRACT run_id={run_id} status=rejected reason=receipt_missing detail=x\n"
            f"TOKEN_V2_END run_id={run_id} status=contract_rejected\n"
        )
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "negative-completion.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{boundary}\n{completion}\n"
                f"$actual = Get-TokenQuickCompletionFromLog -Text {self._powershell_literal(log_text)} "
                f"-MarkerNonce '{nonce}' -EvidenceModeValue 'negative'\n"
                f"if ($actual -cne '{run_id}') {{ throw 'negative_run_not_returned' }}\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_negative_launch_passes_only_the_fixed_loopback_origin_to_the_app(self) -> None:
        launch = self._function_source("Start-TokenQuickRun")
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "negative-launch.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "$PackageName = 'com.aneb.probe.codex'\n"
                "$ServerBase = 'https://203.0.113.10:8443'\n"
                "$EvidenceMode = 'negative'\n"
                "$NegativeClientServerBase = 'http://127.0.0.1:18765'\n"
                "$Transport = 'auto'\n"
                "$script:AppLaunchAttempted = $false\n"
                "$script:AppStarted = $false\n"
                "function Invoke-AdbTextOnce { param([string[]]$Arguments,[string]$Stage); "
                "$script:captured = $Arguments -join ' '; return 'Status: ok' }\n"
                f"{launch}\n"
                "$null = Start-TokenQuickRun\n"
                "if ($script:captured -notmatch [regex]::Escape(\"--es server '$NegativeClientServerBase'\")) { throw 'loopback_missing' }\n"
                "if ($script:captured -match [regex]::Escape($ServerBase)) { throw 'upstream_leaked_to_app' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_negative_reverse_state_never_adopts_or_leaves_an_unknown_mapping(self) -> None:
        verifier = self._function_source("Assert-NegativeAdbReverseState")
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "negative-reverse-state.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{verifier}\n"
                "$null = Assert-NegativeAdbReverseState -Text '' -Stage 'preflight' -DevicePort 18765 -HostPort 0\n"
                "$null = Assert-NegativeAdbReverseState -Text 'ABC123 tcp:18765 tcp:45678' -Stage 'active' -DevicePort 18765 -HostPort 45678\n"
                "$null = Assert-NegativeAdbReverseState -Text '' -Stage 'final' -DevicePort 18765 -HostPort 0\n"
                "$caught = $false\n"
                "try { $null = Assert-NegativeAdbReverseState -Text 'ABC123 tcp:17000 tcp:47000' -Stage 'preflight' -DevicePort 18765 -HostPort 0 } "
                "catch { if ($_.Exception.Message -eq 'negative_reverse_preexisting_mapping') { $caught = $true } else { throw } }\n"
                "if (-not $caught) { throw 'preexisting_mapping_was_adopted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_negative_proxy_machine_output_is_two_line_loopback_and_run_bound(self) -> None:
        ready_parser = self._function_source("Assert-NegativeProxyReadyOutput")
        final_parser = self._function_source("Assert-NegativeProxyFinalOutput")
        run_id = "019f8000-0000-7000-8000-000000000006"
        ready = '{"listen_host":"127.0.0.1","listen_port":45678,"status":"ready"}'
        final = (
            ready
            + "\n"
            + '{"listen_host":"127.0.0.1","listen_port":45678,"reason_code":"ok",'
            + f'"run_id":"{run_id}","status":"pass"}}'
        )
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "negative-proxy-output.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{ready_parser}\n{final_parser}\n"
                f"$ready = Assert-NegativeProxyReadyOutput -Text {self._powershell_literal(ready)}\n"
                "if ($ready.ListenPort -ne 45678) { throw 'ready_port_invalid' }\n"
                f"$null = Assert-NegativeProxyFinalOutput -Text {self._powershell_literal(final)} "
                f"-ExpectedRunId '{run_id}' -ExpectedPort 45678 -ExitCode 0 -StderrText ''\n"
                "$caught = $false\n"
                "try { "
                f"$null = Assert-NegativeProxyFinalOutput -Text {self._powershell_literal(final + chr(10) + '{}')} "
                f"-ExpectedRunId '{run_id}' -ExpectedPort 45678 -ExitCode 0 -StderrText '' "
                "} catch { if ($_.Exception.Message -eq 'negative_proxy_output_line_count_invalid') { $caught = $true } else { throw } }\n"
                "if (-not $caught) { throw 'extra_proxy_output_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_negative_proxy_and_reverse_are_ready_before_launch_and_cleared_before_end_barrier(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        start = self._function_source("Start-NegativeProxyAndReverse")
        self.assertIn("--listen-port", start)
        self.assertIn("Assert-NegativeProxyReadyOutput", start)
        self.assertIn("--no-rebind", start)
        self.assertLess(
            start.index("Assert-NegativeProxyReadyOutput"),
            start.index("--no-rebind"),
        )
        self.assertNotIn("--remove-all", source)

        cleanup = self._function_source("Complete-CollectorCleanup")
        for token in ("target_app", "negative_reverse", "negative_proxy", "end_barrier"):
            self.assertIn(f"Name = '{token}'", cleanup)
        self.assertLess(cleanup.index("Name = 'target_app'"), cleanup.index("Name = 'negative_reverse'"))
        self.assertLess(cleanup.index("Name = 'negative_reverse'"), cleanup.index("Name = 'negative_proxy'"))
        self.assertLess(cleanup.index("Name = 'negative_proxy'"), cleanup.index("Name = 'end_barrier'"))

        reverse_cleanup = self._function_source("Remove-NegativeAdbReverseOnce")
        self.assertIn("negative_reverse_before_remove", reverse_cleanup)
        self.assertIn("Assert-NegativeAdbReverseState", reverse_cleanup)
        self.assertLess(
            reverse_cleanup.index("negative_reverse_before_remove"),
            reverse_cleanup.index("'--remove'"),
        )

        formal = source.split("$script:ResolvedTools = Assert-LocalPreflight", 1)[1]
        self.assertLess(formal.index("Start-NegativeProxyAndReverse"), formal.index("Start-TokenQuickRun"))
        self.assertLess(formal.index("Wait-NegativeProxyCompletion"), formal.index("Stop-TargetAppOnce"))
        self.assertLess(formal.index("Stop-TargetAppOnce"), formal.index("-Role 'window_end'"))

    def test_negative_client_db_branch_binds_inventory_loopback_and_atomic_result(self) -> None:
        client = self._function_source("Invoke-ClientDbVerification")
        for token in (
            "verify_token_quick_negative_client_db.py",
            "--inventory",
            "room-copy-inventory.json",
            "--expected-server-base",
            "$NegativeClientServerBase",
            "--result-output",
            "client-result.json",
            "aneb-token-quick-negative-client-db-report",
            "receipt_missing",
            "business_task_count",
            "business_kpi_observation_count",
            "business_artifact_count",
            "network_score_count",
        ):
            self.assertIn(token, client)

    def test_negative_client_db_binds_profile_definition_not_request_contract(self) -> None:
        client = self._function_source("Invoke-ClientDbVerification")
        profile_digest = "a" * 64
        request_contract_digest = "b" * 64
        run_id = "019f9602-ca33-70e3-ac48-74212385413e"
        report = {
            "schema": "aneb-token-quick-negative-client-db-report",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "run_id": run_id,
            "run_uuid_unix_ms": 1784928193075,
            "run_start_delta_ms": 5,
            "started_at_epoch_ms": 1784928193080,
            "ended_at_epoch_ms": 1784928193555,
            "serialized_at_epoch_ms": 1784928193555,
            "room_user_version": 19,
            "frozen_source_unchanged": True,
            "analysis_copy_used": True,
            "strict_result_schema": "pass",
            "negative_reason_code": "receipt_missing",
            "endpoint_server_base": "http://127.0.0.1:18765",
            "profile_sha256": f"sha256:{profile_digest}",
            "business_task_count": 0,
            "business_kpi_observation_count": 0,
            "business_artifact_count": 0,
            "network_score_count": 0,
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wrapper = root / "negative-client-profile-binding.ps1"
            result_path = root / "client-result.json"
            result_path.write_text("{}\n", encoding="utf-8")
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "$EvidenceMode = 'negative'\n"
                "$NegativeClientDbVerifierPath = 'verify_token_quick_negative_client_db.py'\n"
                "$ClientDbVerifierPath = 'verify_token_quick_client_db.py'\n"
                "$NegativeClientServerBase = 'http://127.0.0.1:18765'\n"
                "$ServerBase = 'https://aneb.invalid:8443'\n"
                "$ToolCommandTimeoutSeconds = 30\n"
                "$script:ResolvedTools = [pscustomobject]@{ "
                "Python = 'python'; "
                f"ProfileContractDefinitionSha256 = '{request_contract_digest}'; "
                f"ProfileDefinitionSha256 = '{profile_digest}'; "
                "ToolingFiles = @{ profile_manifest = 'manifest.sha256' } }\n"
                "function Invoke-BoundedNativeTextOnce { "
                f"[pscustomobject]@{{ ExitCode = 0; Text = {self._powershell_literal(json.dumps(report, separators=(',', ':')))} }} }}\n"
                "function Write-TextNoBom { param([string]$Path, [string]$Text) "
                "[IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false)) }\n"
                "function Assert-NonEmptyFile { param([string]$Path, [string]$Label) "
                "if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or "
                "(Get-Item -LiteralPath $Path).Length -le 0) { throw 'missing_file' } }\n"
                f"{client}\n"
                f"$null = Invoke-ClientDbVerification -EvidenceDirectory {self._powershell_literal(root)} -RunId '{run_id}'\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_profile_definition_identity_comes_from_the_strict_published_manifest(self) -> None:
        resolver = self._function_source("Get-ExpectedProfileDefinitionSha256")
        profile_digest = "a" * 64
        runtime_digest = "b" * 64
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            valid = root / "manifest.sha256"
            valid.write_text(
                f"{profile_digest}  profile.json\n"
                f"{runtime_digest}  runtime_plan.json\n",
                encoding="utf-8",
            )
            invalid = root / "manifest-extra.sha256"
            invalid.write_text(
                valid.read_text(encoding="utf-8")
                + f"{'c' * 64}  unrelated.json\n",
                encoding="utf-8",
            )
            wrapper = root / "profile-definition-identity.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "function Assert-NonEmptyFile { param([string]$Path, [string]$Label) "
                "if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or "
                "(Get-Item -LiteralPath $Path).Length -le 0) { throw 'missing_file' } }\n"
                f"{resolver}\n"
                f"$actual = Get-ExpectedProfileDefinitionSha256 -Path {self._powershell_literal(valid)}\n"
                f"if ($actual -cne '{profile_digest}') {{ throw 'profile_digest_mismatch' }}\n"
                "$caught = $false\n"
                "try { "
                f"$null = Get-ExpectedProfileDefinitionSha256 -Path {self._powershell_literal(invalid)} "
                "} catch { if ($_.Exception.Message -eq 'profile_manifest_contract_invalid') "
                "{ $caught = $true } else { throw } }\n"
                "if (-not $caught) { throw 'invalid_manifest_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_negative_audit_requires_one_capability_and_zero_business(self) -> None:
        audit = self._function_source("Assert-RequestEntryAuditReport")
        derivation = self._function_source("Invoke-EvidenceDerivationAndAudit")
        for token in (
            "$EvidenceMode",
            "negative_zero_business",
            "counts.control",
            "counts.business_total",
            "counts.unattributed_business",
            "counts.unexpected_control",
        ):
            self.assertIn(token, audit)
        self.assertIn("'--mode', $EvidenceMode", derivation)

    def test_negative_completion_rejects_any_business_result_log(self) -> None:
        boundary = self._function_source("Get-PostMarkerLogText")
        completion = self._function_source("Get-TokenQuickCompletionFromLog")
        nonce = "c" * 32
        run_id = "019f8000-0000-7000-8000-000000000004"
        log_text = (
            f"D82_CAPTURE_MARKER nonce={nonce}\n"
            f"TOKEN_V2_START run_id={run_id} variant=quick\n"
            f"TOKEN_V2_DB_WRITE run_id={run_id} ok=true\n"
            f"TOKEN_V2_CONTRACT run_id={run_id} status=rejected reason=receipt_missing\n"
            f"TOKEN_V2_RESULT run_id={run_id} score=null verdict=INVALID\n"
            f"TOKEN_V2_END run_id={run_id} status=contract_rejected\n"
        )
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "negative-completion-result.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{boundary}\n{completion}\n"
                "$caught = $false\n"
                "try { "
                f"$null = Get-TokenQuickCompletionFromLog -Text {self._powershell_literal(log_text)} "
                f"-MarkerNonce '{nonce}' -EvidenceModeValue 'negative' "
                "} catch { if ($_.Exception.Message -eq 'negative_token_business_log_observed') { $caught = $true } else { throw } }\n"
                "if (-not $caught) { throw 'business_result_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_negative_completion_rejects_out_of_order_terminal_evidence(self) -> None:
        boundary = self._function_source("Get-PostMarkerLogText")
        completion = self._function_source("Get-TokenQuickCompletionFromLog")
        nonce = "d" * 32
        run_id = "019f8000-0000-7000-8000-000000000005"
        log_text = (
            f"D82_CAPTURE_MARKER nonce={nonce}\n"
            f"TOKEN_V2_START run_id={run_id} variant=quick\n"
            f"TOKEN_V2_CONTRACT run_id={run_id} status=rejected reason=receipt_missing\n"
            f"TOKEN_V2_RADIO run_id={run_id} status=unavailable samples=0\n"
            f"TOKEN_V2_DB_WRITE run_id={run_id} ok=true\n"
            f"TOKEN_V2_END run_id={run_id} status=contract_rejected\n"
        )
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "negative-completion-order.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{boundary}\n{completion}\n"
                "$caught = $false\n"
                "try { "
                f"$null = Get-TokenQuickCompletionFromLog -Text {self._powershell_literal(log_text)} "
                f"-MarkerNonce '{nonce}' -EvidenceModeValue 'negative' "
                "} catch { if ($_.Exception.Message -eq 'negative_token_log_order_invalid') { $caught = $true } else { throw } }\n"
                "if (-not $caught) { throw 'out_of_order_negative_log_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_d82_source_binding_freezes_remote_identity_and_audits_exact_quick_counts(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("function Get-RemotePreStartSnapshot", source)
        snapshot = source.split("function Get-RemotePreStartSnapshot", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for token in (
            "journalctl",
            "journal_anchor_json_base64",
            "remote_realtime_anchor_usec",
            "/proc/sys/kernel/random/boot_id",
            "InvocationID",
            "MainPID",
            "sha256sum",
            "Assert-PersistentAuditLock",
        ):
            self.assertIn(token, snapshot)
        self.assertIn("function Write-PreStartReceipt", source)
        receipt = source.split("function Write-PreStartReceipt", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for field in (
            "journal_cursor",
            "journal_monotonic_anchor",
            "remote_realtime_anchor_usec",
            "boot_id",
            "systemd_invocation_id",
            "main_pid",
            "server_version",
            "server_binary_sha256",
            "serverinfo_body_sha256",
            "lock_nonce",
            "lock_remote_pid",
            "lock_marker",
        ):
            self.assertIn(field, receipt)
        self.assertIn("function Export-LockedJournalOnce", source)
        export = source.split("function Export-LockedJournalOnce", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("--after-cursor", export)
        self.assertIn("--output=json", export)
        self.assertIn(
            "--output-fields=__CURSOR,__REALTIME_TIMESTAMP,__MONOTONIC_TIMESTAMP,_BOOT_ID,_SYSTEMD_INVOCATION_ID,_SYSTEMD_UNIT,_PID,MESSAGE",
            export,
        )
        self.assertIn("Assert-PersistentAuditLock", export)
        self.assertNotIn("--retry", export)
        self.assertIn("function Invoke-EvidenceDerivationAndAudit", source)
        audit = source.split("function Invoke-EvidenceDerivationAndAudit", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for token in (
            "prepare_token_run_evidence.py",
            "--journal",
            "--pre-start-receipt",
            "--message-output",
            "--derivation-output",
            "verify_token_run_audit.py",
            "--profile-contract",
            "token_multimodal_quick@1.2.1",
        ):
            self.assertIn(token, audit)

    def test_all_serverinfo_calls_use_the_frozen_private_ca_without_insecure_tls(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("aneb_ip_ca.pem", source)
        self.assertIn("function Get-ServerCaIdentity", source)
        serverinfo = source.split("function Invoke-ServerInfoOnce", 1)[1].split(
            "\nfunction ", 1
        )[0]
        barrier = source.split("function Invoke-BarrierOnce", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for function_source in (serverinfo, barrier):
            self.assertIn("--cacert", function_source)
            self.assertIn("$ServerCaPath", function_source)
            self.assertNotIn("--insecure", function_source)
        self.assertNotRegex(source, r"(?m)^\s*'?-k'?(?:,|\s*$)")
        self.assertIn("server_ca_sha256", source)

    def test_room_copy_is_read_only_digest_bound_and_verified_for_the_same_run(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("function Copy-FrozenRoomDatabase", source)
        copy = source.split("function Copy-FrozenRoomDatabase", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for token in (
            "run-as",
            "databases/aneb-probe.db",
            "databases/aneb-probe.db-wal",
            "databases/aneb-probe.db-shm",
            "sha256sum",
            "Invoke-NativeToFileOnce",
            "room_file_digest_mismatch",
            "room_wal_shm_state_mismatch",
        ):
            self.assertIn(token, copy)
        self.assertNotIn("rm ", copy)
        self.assertNotIn("cp ", copy)
        self.assertIn("function Invoke-ClientDbVerification", source)
        verify = source.split("function Invoke-ClientDbVerification", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for token in (
            "verify_token_quick_client_db.py",
            "--run-id",
            "--expected-server-base",
            "--result-output",
            "client-db-report.json",
            "client-result.json",
            "1.2.0",
            "run_uuid_unix_ms",
            "run_start_delta_ms",
            "started_at_epoch_ms",
            "ended_at_epoch_ms",
            "serialized_at_epoch_ms",
            "room_user_version",
            "frozen_source_unchanged",
            "analysis_copy_used",
            "strict_result_schema",
            "typed_metrics_verified",
            "envelope_metrics_verified",
            "typed_conclusions_verified",
        ):
                self.assertIn(token, verify)

    def test_room_copy_inventory_serializes_on_windows_powershell_5(self) -> None:
        room_copy = self._function_source("Copy-FrozenRoomDatabase")
        payload = b"frozen-room-copy-test\n"
        payload_base64 = base64.b64encode(payload).decode("ascii")
        payload_sha256 = hashlib.sha256(payload).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wrapper = root / "room-copy-inventory.ps1"
            wrapper.write_text(
                "$ErrorActionPreference='Stop'\n"
                "Set-StrictMode -Version 2.0\n"
                "$PackageName='com.aneb.probe.codex'\n"
                "$AdbSerial='TEST-SERIAL'\n"
                "$script:ResolvedTools=[pscustomobject]@{Adb='adb'}\n"
                f"$script:FrozenBytes=[Convert]::FromBase64String('{payload_base64}')\n"
                "function Invoke-AdbTextOnce {\n"
                "  param([string[]]$Arguments,[string]$Stage)\n"
                "  if ($Stage -like 'room_state_*') { return 'present' }\n"
                f"  if ($Stage -like 'room_digest_*') {{ return '{payload_sha256}  frozen' }}\n"
                "  throw \"unexpected_adb_stage:$Stage\"\n"
                "}\n"
                "function Invoke-NativeToFileOnce {\n"
                "  param([string]$Command,[string[]]$Arguments,[string]$OutputPath,"
                "[string]$Stage,[int]$TimeoutSeconds)\n"
                "  [IO.File]::WriteAllBytes($OutputPath,$script:FrozenBytes)\n"
                "}\n"
                "function Assert-NonEmptyFile {\n"
                "  param([string]$Path,[string]$Label)\n"
                "  if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or "
                "(Get-Item -LiteralPath $Path).Length -le 0) { throw 'fixture_file_missing' }\n"
                "}\n"
                "function Write-JsonNoBom {\n"
                "  param([string]$Path,$Value)\n"
                "  [IO.File]::WriteAllText($Path,($Value | ConvertTo-Json -Depth 8),"
                "(New-Object Text.UTF8Encoding($false)))\n"
                "}\n"
                f"{room_copy}\n"
                f"Copy-FrozenRoomDatabase -EvidenceDirectory {self._powershell_literal(root)}\n"
                f"$report=Get-Content -LiteralPath {self._powershell_literal(root / 'room-copy-inventory.json')} "
                "-Raw -Encoding UTF8 | ConvertFrom-Json\n"
                "if ($report.files.Count -ne 3) { throw 'room_inventory_count_invalid' }\n"
                "if (($report.files.name -join ',') -cne "
                "'aneb-probe.db,aneb-probe.db-wal,aneb-probe.db-shm') "
                "{ throw 'room_inventory_names_invalid' }\n"
                "if (@($report.files | Where-Object state -ne 'present').Count -ne 0) "
                "{ throw 'room_inventory_state_invalid' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=30,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_audit_report_contract_digest_mismatch_is_rejected_fail_closed(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Assert-RequestEntryAuditReport"
        self.assertIn(marker, source)
        function_source = source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        function_source = marker + function_source

        run_id = "019b62e2-8d75-7000-8000-000000000001"
        start_id = "11111111-1111-4111-8111-111111111111"
        end_id = "22222222-2222-4222-8222-222222222222"
        expected_digest = "c" * 64
        report = {
            "schema": "aneb-token-request-entry-audit-report",
            "schema_version": "2.1.0",
            "status": "pass",
            "reason_code": "ok",
            "mode": "positive",
            "run_id": run_id,
            "start_barrier_id": start_id,
            "barrier_id": end_id,
            "profile_contract": "token_multimodal_quick@1.2.1",
            "profile_contract_definition_sha256": "d" * 64,
            "profile_contract_enforcement": "positive_exact_business_counts",
            "evidence_scope": "request_entry_coverage_only",
            "expected_business_counts": {"echo": 20, "token_sim": 3, "download": 1},
            "counts": {
                "business": {
                    "echo": 20,
                    "token_sim": 3,
                    "download": 1,
                    "unexpected": 0,
                },
                "business_total": 24,
                "control": 2,
                "unattributed_business": 0,
                "unexpected_control": 0,
            },
        }
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            report_path = temporary_path / "audit.json"
            report_path.write_text(json.dumps(report), encoding="utf-8")
            wrapper = temporary_path / "assert-audit.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "$ProfileContract = 'token_multimodal_quick@1.2.1'\n"
                f"{function_source}\n"
                f"$audit = Get-Content -LiteralPath '{report_path}' -Raw | ConvertFrom-Json\n"
                "Assert-RequestEntryAuditReport "
                f"-Audit $audit -RunId '{run_id}' -StartBarrierId '{start_id}' "
                f"-EndBarrierId '{end_id}' -ExpectedContractDigest '{expected_digest}'\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn(
            "profile_contract_definition_sha256_mismatch",
            completed.stdout + completed.stderr,
        )

    def test_finally_cleanup_is_mandatory_before_atomic_complete_publication(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("function Complete-CollectorCleanup", source)
        cleanup = source.split("function Complete-CollectorCleanup", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for token in (
            "Stop-LogcatCaptureOnce",
            "Stop-TargetAppOnce",
            "Ensure-BusySentinelForCleanup",
            "Restore-StayonOnce",
            "Release-BusySentinelToLauncherOnce",
            "Assert-LiveDeviceCleanAfter",
            "Release-PersistentAuditLockOnce",
            "Assert-AuditLockReleased",
        ):
            self.assertIn(token, cleanup)
        for retry in (
            "Name = 'target_app'; Attempts = 3",
            "Name = 'logcat'; Attempts = 3",
            "Name = 'stayon'; Attempts = 3",
        ):
            self.assertIn(retry, cleanup)
        self.assertIn("Start-Sleep -Milliseconds 250", cleanup)
        self.assertIn("$errors.Add", cleanup)
        target_stop = source.split("function Stop-TargetAppOnce", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("force-stop", target_stop)
        self.assertNotIn("input keyevent HOME", target_stop)
        self.assertIn("$PackageName", target_stop)
        self.assertNotIn("$ClaudePackageName", target_stop)
        self.assertIn("finally {", source)
        finally_tail = source.rsplit("finally {", 1)[1]
        self.assertIn("Complete-CollectorCleanup", finally_tail)
        self.assertIn(".partial", source)
        self.assertIn(".complete", source)
        publication = source.rsplit("finally {", 1)[1]
        self.assertIn("$script:WorkflowSucceeded -and $script:CleanupSucceeded", publication)
        self.assertIn("Move-Item -LiteralPath $PartialDirectory", publication)
        self.assertIn("COMPLETE", publication)
        self.assertIn("evidence-inventory.draft.json", publication)
        self.assertIn("evidence-manifest.final.json", publication)
        self.assertLess(
            publication.index("Write-EvidenceManifestDraft"),
            publication.index("Write-FinalEvidenceManifest"),
        )
        self.assertLess(
            publication.index("Write-FinalEvidenceManifest"),
            publication.index("COMPLETE"),
        )

    def test_settings_busy_sentinel_owns_phone_until_final_cleanup(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        target_stop = source.split("function Stop-TargetAppOnce", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("force-stop", target_stop)
        self.assertNotIn("input keyevent HOME", target_stop)

        self.assertIn("function Start-BusySentinel", source)
        start = source.split("function Start-BusySentinel", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("android.settings.SETTINGS", start)
        self.assertIn("Activity:", start)
        self.assertIn("com\\.android\\.settings/", start)
        self.assertIn("device-busy-sentinel-launch.txt", start)
        self.assertIn("device-busy-sentinel.json", start)

        sentinel_assert = source.split(
            "function Assert-BusySentinelFocused", 1
        )[1].split("\nfunction ", 1)[0]
        self.assertIn("dumpsys window", sentinel_assert)
        self.assertNotIn("dumpsys window windows", sentinel_assert)
        self.assertIn("dumpsys activity activities", sentinel_assert)
        self.assertIn("Add-BusySentinelObservation", sentinel_assert)
        self.assertIn("busy-sentinel-observations.jsonl", source)
        self.assertIn("$script:BusySentinelComponent", sentinel_assert)
        self.assertIn("$LauncherComponent", sentinel_assert)

        preparation = source.split(
            "$devicePreflight = Assert-LiveDevicePreflight", 1
        )[1].split(
            "$null = Start-TokenQuickRun", 1
        )[0]
        preparation_order = (
            "Start-BusySentinel",
            "Start-PersistentAuditLock",
            "Get-RemotePreStartSnapshot",
            "Start-NegativeProxyAndReverse",
            "before_target_handoff",
        )
        preparation_positions = [preparation.index(token) for token in preparation_order]
        self.assertEqual(preparation_positions, sorted(preparation_positions))

        workflow = source.split("$null = Start-TokenQuickRun", 1)[1].split(
            "$script:WorkflowSucceeded = $true", 1
        )[0]
        ordered = (
            "Stop-TargetAppOnce",
            "Restore-BusySentinelAfterTarget",
            "Invoke-BarrierOnce",
            "Assert-RemoteSnapshotStable",
            "Copy-FrozenRoomDatabase",
            "Invoke-ClientDbVerification",
        )
        positions = [workflow.index(token) for token in ordered]
        self.assertEqual(positions, sorted(positions))
        self.assertGreaterEqual(workflow.count("Assert-BusySentinelFocused"), 5)

        cleanup = source.split("function Complete-CollectorCleanup", 1)[1].split(
            "\nfunction ", 1
        )[0]
        cleanup_order = (
            "Stop-TargetAppOnce",
            "Ensure-BusySentinelForCleanup",
            "Restore-StayonOnce",
            "Release-BusySentinelToLauncherOnce",
            "Assert-LiveDeviceCleanAfter",
        )
        cleanup_positions = [cleanup.index(token) for token in cleanup_order]
        self.assertEqual(cleanup_positions, sorted(cleanup_positions))

        release = source.split(
            "function Release-BusySentinelToLauncherOnce", 1
        )[1].split("\nfunction ", 1)[0]
        self.assertGreaterEqual(release.count("Assert-BusySentinelFocused"), 2)
        self.assertLess(
            release.index("Assert-NoUnattributedSessionBeforeHome"),
            release.index("after_release_guard"),
        )
        self.assertLess(
            release.index("after_release_guard"),
            release.index("input keyevent HOME"),
        )

        finalizer = source.split("function Write-FinalEvidenceManifest", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for required in (
            "device-busy-sentinel-launch.txt",
            "device-busy-sentinel-restore.txt",
            "device-busy-sentinel.json",
            "busy-sentinel-observations.jsonl",
            "device-busy-sentinel-release-guard.json",
        ):
            self.assertIn(required, finalizer)

    def test_busy_sentinel_loss_never_sends_home_or_allows_complete_publication(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        def function_source(name: str) -> str:
            marker = f"function {name}"
            return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "busy-sentinel-loss.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\nSet-StrictMode -Version 2.0\n"
                + function_source("Release-BusySentinelToLauncherOnce")
                + "\n"
                + function_source("Complete-CollectorCleanup")
                + "\n"
                + "$script:BusySentinelStarted=$true; $script:BusySentinelHomeSucceeded=$false; "
                + "$script:BusySentinelReleaseAttempted=$false; $script:BusySentinelStartAttempted=$true; "
                + "$script:BusySentinelVerified=$true; $script:BusySentinelLost=$false; "
                + "$script:BusySentinelRestoredAfterTarget=$false; "
                + "$script:BusySentinelComponent='com.android.settings/.HWSettings'\n"
                + "$script:StartBarrierAttempted=$false; $script:EndBarrierAttempted=$false; "
                + "$script:AuditLock=$null; $script:OriginalStayon=$null; "
                + "$script:TargetStopAttempted=$true; $script:StayonChanged=$false; "
                + "$script:LockReleaseAttempted=$false; $script:homeCalls=0\n"
                + "$script:NegativeProxy=$null; $script:NegativeProxyCompleted=$false; "
                + "$script:NegativeProxyStopAttempted=$false; $script:NegativeProxyStopSucceeded=$false; "
                + "$script:NegativeReversePreflightCaptured=$false; "
                + "$script:NegativeReverseMutationAttempted=$false; $script:NegativeReverseAdded=$false; "
                + "$script:NegativeReverseRemoveAttempted=$false; $script:NegativeReverseFinalCaptured=$false\n"
                + "function Stop-TargetAppOnce {}\n"
                + "function Remove-NegativeAdbReverseOnce { param([string]$EvidenceDirectory) }\n"
                + "function Stop-NegativeProxyOnce {}\n"
                + "function Ensure-BusySentinelForCleanup { param([string]$EvidenceDirectory) }\n"
                + "function Assert-BusySentinelFocused { param([string]$EvidenceDirectory,[string]$Stage); "
                + "if ($Stage -eq 'before_release_home') { $script:BusySentinelLost=$true; "
                + "throw 'busy_sentinel_focus_changed stage=before_release_home' } }\n"
                + "function Assert-NoUnattributedSessionBeforeHome { param([string]$EvidenceDirectory); "
                + "throw 'release_guard_must_not_run_after_focus_loss' }\n"
                + "function Invoke-AdbTextOnce { param([string[]]$Arguments,[string]$Stage); "
                + "if (($Arguments -join ' ') -match 'input keyevent HOME') { $script:homeCalls++ }; return '' }\n"
                + "function Assert-HuaweiLauncherFocused { param($WindowDump,$ActivityDump) }\n"
                + "function Stop-LogcatCaptureOnce {}\nfunction Restore-StayonOnce {}\n"
                + "function Write-JsonNoBom { param([string]$Path,$Value); $script:cleanupReport=$Value }\n"
                + "Complete-CollectorCleanup -EvidenceDirectory '.'\n"
                + "if ($script:homeCalls -ne 0) { throw 'home_was_sent_after_sentinel_loss' }\n"
                + "if ($script:CleanupSucceeded) { throw 'sentinel_loss_cleanup_was_pass' }\n"
                + "if ($script:BusySentinelHomeSucceeded) { throw 'sentinel_loss_marked_home_success' }\n"
                + "if (@($script:cleanupReport.errors | Where-Object { $_ -like 'busy_sentinel_release:*' }).Count -ne 1) "
                + "{ throw 'sentinel_loss_cleanup_error_missing' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        publication = source.rsplit("finally {", 1)[1]
        self.assertIn(
            "$script:WorkflowSucceeded -and $script:CleanupSucceeded",
            publication,
        )
        self.assertLess(
            publication.index("$script:WorkflowSucceeded -and $script:CleanupSucceeded"),
            publication.index("COMPLETE"),
        )

    def test_busy_sentinel_parses_and_records_exact_settings_component(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        def function_source(name: str) -> str:
            marker = f"function {name}"
            return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            escaped_root = str(root).replace("'", "''")
            wrapper = root / "busy-sentinel-start.ps1"
            wrapper.write_text(
                "$ErrorActionPreference='Stop'\nSet-StrictMode -Version 2.0\n"
                + "$LauncherComponent='com.huawei.android.launcher/.unihome.UniHomeLauncher'\n"
                + function_source("Write-TextNoBom")
                + "\n"
                + function_source("Write-JsonNoBom")
                + "\n"
                + function_source("Add-BusySentinelObservation")
                + "\n"
                + function_source("ConvertTo-CanonicalAndroidComponent")
                + "\n"
                + function_source("Assert-BusySentinelFocused")
                + "\n"
                + function_source("Start-BusySentinel")
                + "\n"
                + "$script:BusySentinelStarted=$false; $script:BusySentinelVerified=$false; "
                + "$script:BusySentinelStartAttempted=$false; $script:BusySentinelLost=$false; "
                + "$script:BusySentinelComponent=$null; $script:AppLaunchAttempted=$false; "
                + "$script:TargetStopSucceeded=$false; $script:BusySentinelRestoredAfterTarget=$false\n"
                + "function Invoke-AdbTextOnce { param([string[]]$Arguments,[string]$Stage); switch -Wildcard ($Stage) { "
                + "'start_busy_sentinel_settings' { return \"Starting: Intent { act=android.settings.SETTINGS }`nStatus: ok`nActivity: com.android.settings/.HWSettings`nComplete\" }; "
                + "'busy_sentinel_window_*' { return 'mCurrentFocus=Window{42 u0 com.android.settings/com.android.settings.HWSettings}' }; "
                + "'busy_sentinel_activity_*' { return \"mFocusedApp=ActivityRecord{1 u0 com.android.settings/.HWSettings t9}`ntopResumedActivity=ActivityRecord{1 u0 com.android.settings/.HWSettings t9}\" }; "
                + "default { throw \"unexpected_adb_stage=$Stage\" } } }\n"
                + "Start-BusySentinel -EvidenceDirectory '"
                + escaped_root
                + "'\n"
                + "if ($script:BusySentinelComponent -cne 'com.android.settings/.HWSettings' -or "
                + "-not $script:BusySentinelVerified -or $script:BusySentinelLost) { throw 'sentinel_identity_not_recorded' }\n"
                + "$structured=Get-Content -Raw -LiteralPath (Join-Path '"
                + escaped_root
                + "' 'device-busy-sentinel.json') | ConvertFrom-Json\n"
                + "if ($structured.component -cne 'com.android.settings/.HWSettings') { throw 'structured_component_invalid' }\n"
                + "$lines=@(Get-Content -LiteralPath (Join-Path '"
                + escaped_root
                + "' 'busy-sentinel-observations.jsonl'))\n"
                + "if ($lines.Count -ne 1) { throw 'sentinel_observation_count_invalid' }\n"
                + "$observation=$lines[0] | ConvertFrom-Json\n"
                + "if ($observation.stage -cne 'sentinel_started' -or -not $observation.matched) { throw 'sentinel_observation_invalid' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_busy_sentinel_evidence_accepts_equivalent_short_and_full_components(
        self,
    ) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        def function_source(name: str) -> str:
            marker = f"function {name}"
            return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

        required_stages = (
            "sentinel_started",
            "before_target_handoff",
            "sentinel_restored_after_target",
            "before_end_barrier",
            "after_remote_snapshot",
            "before_room_freeze",
            "after_room_freeze",
            "after_client_verifier",
            "workflow_complete",
            "cleanup_before_end_barrier",
            "cleanup_after_end_barrier",
            "before_release_home",
            "after_release_guard",
        )
        short_component = "com.android.settings/.HWSettings"
        full_component = (
            "com.android.settings/com.android.settings.HWSettings"
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            observations = root / "busy-sentinel-observations.jsonl"
            observations.write_text(
                "".join(
                    json.dumps(
                        {
                            "schema": "aneb-d82-busy-sentinel-observation",
                            "schema_version": "1.0.0",
                            "captured_at_utc": "2026-07-24T00:00:00.0000000Z",
                            "stage": stage,
                            "expected_component": short_component,
                            "observed_components": [full_component] * 4,
                            "matched": True,
                            "window_dump": "mCurrentFocus=Window{settings}",
                            "activity_dump": "mFocusedApp=ActivityRecord{settings}",
                        },
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                    + "\n"
                    for stage in required_stages
                ),
                encoding="utf-8",
            )
            escaped_root = str(root).replace("'", "''")
            wrapper = root / "busy-sentinel-evidence.ps1"
            wrapper.write_text(
                "$ErrorActionPreference='Stop'\nSet-StrictMode -Version 2.0\n"
                + function_source("Assert-NonEmptyFile")
                + "\n"
                + function_source("ConvertTo-CanonicalAndroidComponent")
                + "\n"
                + function_source("Assert-BusySentinelEvidence")
                + "\nAssert-BusySentinelEvidence -EvidenceDirectory '"
                + escaped_root
                + "' -ExpectedComponent '"
                + short_component
                + "'\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )
            lines = observations.read_text(encoding="utf-8").splitlines()
            mismatched = json.loads(lines[0])
            mismatched["observed_components"][0] = (
                "com.android.settings/com.android.settings.OtherSettings"
            )
            lines[0] = json.dumps(
                mismatched,
                sort_keys=True,
                separators=(",", ":"),
            )
            observations.write_text(
                "\n".join(lines) + "\n",
                encoding="utf-8",
            )
            rejected = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn(
            "busy_sentinel_evidence_focus_invalid stage=sentinel_started",
            rejected.stdout + rejected.stderr,
        )

    def test_final_manifest_requires_raw_preflight_and_cleanup_state(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        finalizer = source.split("function Write-FinalEvidenceManifest", 1)[1].split(
            "\nfunction ", 1
        )[0]
        for name in (
            "device-processes-preflight.json",
            "device-services-preflight.json",
            "device-processes-final.json",
            "device-services-final.json",
            "device-tun-preflight.txt",
            "device-tun-final.txt",
            "device-stayon-preflight.txt",
            "device-stayon-final.txt",
            "device-package-preflight.txt",
            "device-accessibility-final.txt",
        ):
            with self.subTest(name=name):
                self.assertIn(name, finalizer)
        final_clean = source.split("function Assert-LiveDeviceCleanAfter", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("ANEB_D82_DEVICE_ACCESSIBILITY_FINAL_V1", final_clean)
        self.assertIn(
            "enabled_accessibility_services_command=settings get secure enabled_accessibility_services",
            final_clean,
        )
        self.assertIn("dumpsys_accessibility_command=dumpsys accessibility", final_clean)

    def test_cleanup_retries_transient_steps_and_continues_after_exhaustion(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        cleanup = "function Complete-CollectorCleanup" + source.split(
            "function Complete-CollectorCleanup", 1
        )[1].split("\nfunction ", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "cleanup-retry.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                + cleanup
                + "\n$script:StartBarrierAttempted = $false\n"
                + "$script:EndBarrierAttempted = $false\n"
                + "$script:AuditLock = $null\n"
                + "$script:OriginalStayon = $null\n"
                + "$script:TargetStopAttempted = $true\n"
                + "$script:StayonChanged = $false\n"
                + "$script:LockReleaseAttempted = $false\n"
                + "$script:BusySentinelStarted = $false\n"
                + "$script:BusySentinelStartAttempted = $false\n"
                + "$script:BusySentinelVerified = $false\n"
                + "$script:BusySentinelRestoredAfterTarget = $false\n"
                + "$script:BusySentinelLost = $false\n"
                + "$script:BusySentinelComponent = $null\n"
                + "$script:BusySentinelReleaseAttempted = $false\n"
                + "$script:BusySentinelHomeSucceeded = $false\n"
                + "$script:NegativeProxy = $null\n"
                + "$script:NegativeProxyCompleted = $false\n"
                + "$script:NegativeProxyStopAttempted = $false\n"
                + "$script:NegativeProxyStopSucceeded = $false\n"
                + "$script:NegativeReversePreflightCaptured = $false\n"
                + "$script:NegativeReverseMutationAttempted = $false\n"
                + "$script:NegativeReverseAdded = $false\n"
                + "$script:NegativeReverseRemoveAttempted = $false\n"
                + "$script:NegativeReverseFinalCaptured = $false\n"
                + "$script:targetCalls = 0; $script:logcatCalls = 0; $script:stayonCalls = 0\n"
                + "function Stop-TargetAppOnce { $script:targetCalls++; throw 'persistent_target_failure' }\n"
                + "function Remove-NegativeAdbReverseOnce { param([string]$EvidenceDirectory) }\n"
                + "function Stop-NegativeProxyOnce {}\n"
                + "function Ensure-BusySentinelForCleanup { param([string]$EvidenceDirectory) }\n"
                + "function Release-BusySentinelToLauncherOnce { param([string]$EvidenceDirectory) }\n"
                + "function Stop-LogcatCaptureOnce { $script:logcatCalls++ }\n"
                + "function Restore-StayonOnce { $script:stayonCalls++ }\n"
                + "function Write-JsonNoBom { param([string]$Path, $Value); $script:cleanupReport = $Value }\n"
                + "Complete-CollectorCleanup -EvidenceDirectory '.'\n"
                + "if ($script:targetCalls -ne 3) { throw 'target_retry_count_invalid' }\n"
                + "if ($script:logcatCalls -ne 1 -or $script:stayonCalls -ne 1) { throw 'cleanup_did_not_continue' }\n"
                + "if ($script:CleanupSucceeded) { throw 'persistent_failure_was_ignored' }\n"
                + "if ($script:cleanupReport.errors.Count -ne 1) { throw 'cleanup_error_count_invalid' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_manifest_draft_digest_forgery_is_rejected_before_finalization(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        def function(name: str) -> str:
            marker = f"function {name}"
            self.assertIn(marker, source)
            return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

        functions = "\n".join(
            function(name)
            for name in ("Get-RelativeEvidencePath", "Assert-EvidenceManifestDraft")
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "artifact.txt"
            artifact.write_text("trusted-bytes\n", encoding="utf-8")
            draft_path = root / "evidence-inventory.draft.json"
            draft = {
                "acceptance_eligible": False,
                "evidence_scope": "inventory_only_not_d82_acceptance",
                "file_count": 1,
                "files": [
                    {
                        "bytes": artifact.stat().st_size,
                        "path": artifact.name,
                        "sha256": "0" * 64,
                    }
                ],
                "schema": "aneb-evidence-manifest-draft",
                "schema_version": "1.0.0",
                "status": "draft",
                "total_bytes": artifact.stat().st_size,
            }
            draft_path.write_text(json.dumps(draft), encoding="utf-8")
            wrapper = root / "assert-draft.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{functions}\n"
                f"Assert-EvidenceManifestDraft -EvidenceDirectory '{root}' "
                f"-DraftPath '{draft_path}' -RequiredPaths @('artifact.txt')\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn("evidence_draft_digest_mismatch", completed.stdout + completed.stderr)

    def test_manifest_draft_accepts_the_frozen_unicode_install_notes_path(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        def function(name: str) -> str:
            marker = f"function {name}"
            self.assertIn(marker, source)
            return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

        functions = "\n".join(
            function(name)
            for name in ("Get-RelativeEvidencePath", "Assert-EvidenceManifestDraft")
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            evidence.mkdir()
            candidate = evidence / "ci-candidate"
            candidate.mkdir()
            artifact = candidate / "ANEB-安装说明.txt"
            artifact.write_text("trusted-install-notes\n", encoding="utf-8")
            digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
            draft_path = evidence / "evidence-inventory.draft.json"
            draft = {
                "acceptance_eligible": False,
                "evidence_scope": "inventory_only_not_d82_acceptance",
                "file_count": 1,
                "files": [
                    {
                        "bytes": artifact.stat().st_size,
                        "path": "ci-candidate/ANEB-安装说明.txt",
                        "sha256": digest,
                    }
                ],
                "schema": "aneb-evidence-manifest-draft",
                "schema_version": "1.0.0",
                "status": "draft",
                "total_bytes": artifact.stat().st_size,
            }
            draft_path.write_text(json.dumps(draft), encoding="utf-8")
            wrapper = root / "assert-draft.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "$CandidateInstallNotesName = 'ANEB-' + "
                "[char]0x5B89 + [char]0x88C5 + [char]0x8BF4 + "
                "[char]0x660E + '.txt'\n"
                f"{functions}\n"
                f"$null = Assert-EvidenceManifestDraft -EvidenceDirectory '{evidence}' "
                f"-DraftPath '{draft_path}' "
                '-RequiredPaths @("ci-candidate/$CandidateInstallNotesName")\n',
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                encoding="utf-8",
                errors="replace",
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_final_manifest_sidecar_requirements_follow_the_frozen_inventory_pair(self) -> None:
        helper = self._function_source("Get-RequiredFrozenRoomSidecarPaths")
        assert_nonempty = self._function_source("Assert-NonEmptyFile")
        final_manifest = self._function_source("Write-FinalEvidenceManifest")
        self.assertIn("Get-RequiredFrozenRoomSidecarPaths", final_manifest)
        self.assertIn("$requiredPaths += $requiredSidecars", final_manifest)
        negative_requirements = final_manifest.split(
            "if ($EvidenceMode -ceq 'negative')", 1
        )[1].split("$requiredSidecars", 1)[0]
        self.assertNotIn("aneb-probe.db-wal", negative_requirements)
        self.assertNotIn("aneb-probe.db-shm", negative_requirements)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            absent = root / "absent"
            present = root / "present"
            absent.mkdir()
            present.mkdir()
            base = {
                "schema": "aneb-frozen-room-copy",
                "schema_version": "1.0.0",
                "captured_at_utc": "2026-07-23T01:02:03.0000000Z",
                "app_process_state": "stopped_before_copy",
            }
            absent_inventory = {
                **base,
                "files": [
                    {"name": "aneb-probe.db", "state": "present", "bytes": 1, "sha256": "a" * 64},
                    {"name": "aneb-probe.db-wal", "state": "absent"},
                    {"name": "aneb-probe.db-shm", "state": "absent"},
                ],
            }
            present_inventory = {
                **base,
                "files": [
                    {"name": "aneb-probe.db", "state": "present", "bytes": 1, "sha256": "a" * 64},
                    {"name": "aneb-probe.db-wal", "state": "present", "bytes": 1, "sha256": "b" * 64},
                    {"name": "aneb-probe.db-shm", "state": "present", "bytes": 1, "sha256": "c" * 64},
                ],
            }
            (absent / "room-copy-inventory.json").write_text(
                json.dumps(absent_inventory), encoding="utf-8"
            )
            (present / "room-copy-inventory.json").write_text(
                json.dumps(present_inventory), encoding="utf-8"
            )
            wrapper = root / "room-sidecar-requirements.ps1"
            wrapper.write_text(
                "$ErrorActionPreference='Stop'\nSet-StrictMode -Version 2.0\n"
                + assert_nonempty
                + "\n"
                + helper
                + f"\n$absent=@(Get-RequiredFrozenRoomSidecarPaths -EvidenceDirectory '{absent}')\n"
                + "if ($absent.Count -ne 0) { throw 'absent_sidecars_were_required' }\n"
                + f"$present=@(Get-RequiredFrozenRoomSidecarPaths -EvidenceDirectory '{present}')\n"
                + "if (($present -join ',') -cne 'aneb-probe.db-wal,aneb-probe.db-shm') "
                + "{ throw 'present_sidecar_requirements_invalid' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_final_manifest_sidecar_requirements_reject_a_one_sided_pair(self) -> None:
        helper = self._function_source("Get-RequiredFrozenRoomSidecarPaths")
        assert_nonempty = self._function_source("Assert-NonEmptyFile")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventory = {
                "schema": "aneb-frozen-room-copy",
                "schema_version": "1.0.0",
                "captured_at_utc": "2026-07-23T01:02:03.0000000Z",
                "app_process_state": "stopped_before_copy",
                "files": [
                    {"name": "aneb-probe.db", "state": "present", "bytes": 1, "sha256": "a" * 64},
                    {"name": "aneb-probe.db-wal", "state": "present", "bytes": 1, "sha256": "b" * 64},
                    {"name": "aneb-probe.db-shm", "state": "absent"},
                ],
            }
            (root / "room-copy-inventory.json").write_text(
                json.dumps(inventory), encoding="utf-8"
            )
            wrapper = root / "reject-one-sided-room-sidecars.ps1"
            wrapper.write_text(
                "$ErrorActionPreference='Stop'\nSet-StrictMode -Version 2.0\n"
                + assert_nonempty
                + "\n"
                + helper
                + "\n$caught=$false\ntry { $null=@(Get-RequiredFrozenRoomSidecarPaths "
                + f"-EvidenceDirectory '{root}') }} catch {{ if ($_.Exception.Message -ceq "
                + "'room_inventory_sidecar_state_invalid') { $caught=$true } else { throw } }\n"
                + "if (-not $caught) { throw 'one_sided_sidecar_pair_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_tooling_digest_drift_is_rejected(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Assert-ToolingFileStable"
        self.assertIn(marker, source)
        function_source = marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tool = root / "tool.py"
            tool.write_text("trusted\n", encoding="utf-8")
            digest = __import__("hashlib").sha256(tool.read_bytes()).hexdigest()
            tool.write_text("mutated\n", encoding="utf-8")
            wrapper = root / "tool-drift.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{function_source}\n"
                f"Assert-ToolingFileStable -Path '{tool}' -ExpectedSha256 '{digest}' -Label 'collector'\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn("tooling_digest_drift", completed.stdout + completed.stderr)

    def test_installed_apk_wrong_hash_is_rejected(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Assert-ExpectedFileSha256"
        self.assertIn(marker, source)
        function_source = marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / "installed-base.apk"
            apk.write_bytes(b"not-the-accepted-apk")
            wrapper = root / "apk-hash.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{function_source}\n"
                f"Assert-ExpectedFileSha256 -Path '{apk}' -ExpectedSha256 '{'0' * 64}' -Label 'installed_apk'\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn("expected_file_sha256_mismatch", completed.stdout + completed.stderr)

    def test_finalizer_rejects_identity_serverinfo_digest_tampering(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        def function(name: str) -> str:
            marker = f"function {name}"
            self.assertIn(marker, source)
            return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

        functions = "\n".join(
            function(name) for name in ("Assert-NonEmptyFile", "Assert-ServerInfoReceiptBinding")
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            body = root / "identity-serverinfo.json"
            body.write_text('{"version":"tampered"}\n', encoding="utf-8")
            wrapper = root / "serverinfo-binding.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                f"{functions}\n"
                "$receipt = [pscustomobject]@{ serverinfo_body_sha256 = '"
                + "0" * 64
                + "' }\n"
                f"Assert-ServerInfoReceiptBinding -Receipt $receipt -BodyPath '{body}'\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertNotEqual(0, completed.returncode)
        self.assertIn("serverinfo_receipt_digest_mismatch", completed.stdout + completed.stderr)

    def test_final_manifest_revalidates_all_three_raw_serverinfo_responses(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        finalizer = source.split("function Write-FinalEvidenceManifest", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("Assert-ServerInfoReceiptBinding", finalizer)
        self.assertIn("final_start_barrier", finalizer)
        self.assertIn("final_end_barrier", finalizer)
        self.assertEqual(3, finalizer.count("Assert-CapturedHttp200Headers"))

    def test_serverinfo_requires_exact_capability_receipt_and_primitives(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        def function(name: str) -> str:
            marker = f"function {name}"
            self.assertIn(marker, source)
            return marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]

        serverinfo = {
            "anchor_wall_unix_ns": 1_700_000_000_000_000_000,
            "congestion_control": "cubic",
            "execution_capabilities": {
                "contract_id": "aneb-server-capability-receipt",
                "contract_version": "1.0.0",
                "primitives": [
                    {"primitive_id": "download", "wire_contract_id": "aneb-download-v1"},
                    {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
                    {"primitive_id": "token_sim", "wire_contract_id": "aneb-token-task-v1"},
                ],
                "validated_profiles": [
                    {
                        "profile_id": "token_multimodal_quick",
                        "profile_sha256": "sha256:" + "a" * 64,
                        "profile_version": "1.2.1",
                    }
                ],
            },
            "goarch": "amd64",
            "goos": "linux",
            "h3_enabled": True,
            "srv_ts_us": 5_000_000,
            "tcp_slow_start_after_idle": "0",
            "uptime_s": 5,
            "version": "aneb-server/0.8.0",
        }
        forged = json.loads(json.dumps(serverinfo))
        forged["execution_capabilities"]["primitives"][2]["wire_contract_id"] = "forged"
        bare_digest = json.loads(json.dumps(serverinfo))
        bare_digest["execution_capabilities"]["validated_profiles"][0]["profile_sha256"] = (
            "a" * 64
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            valid_path = root / "valid.json"
            forged_path = root / "forged.json"
            bare_digest_path = root / "bare-digest.json"
            valid_path.write_text(json.dumps(serverinfo), encoding="utf-8")
            forged_path.write_text(json.dumps(forged), encoding="utf-8")
            bare_digest_path.write_text(json.dumps(bare_digest), encoding="utf-8")
            wrapper = root / "serverinfo-contract.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                "$ExpectedServerVersion = 'aneb-server/0.8.0'\n"
                + function("Assert-NonEmptyFile")
                + "\n"
                + function("Assert-ServerInfoBody")
                + "\n"
                + f"$null = Assert-ServerInfoBody -BodyPath '{valid_path}' -Stage 'valid'\n"
                + "$caught = $false\n"
                + f"try {{ $null = Assert-ServerInfoBody -BodyPath '{forged_path}' -Stage 'forged' }} "
                + "catch { if ($_.Exception.Message -like 'serverinfo_primitive_mismatch*') { $caught = $true } else { throw } }\n"
                + "if (-not $caught) { throw 'forged_primitive_was_accepted' }\n"
                + "$bareCaught = $false\n"
                + f"try {{ $null = Assert-ServerInfoBody -BodyPath '{bare_digest_path}' -Stage 'bare' }} "
                + "catch { if ($_.Exception.Message -like 'serverinfo_validated_profile_mismatch*') { $bareCaught = $true } else { throw } }\n"
                + "if (-not $bareCaught) { throw 'bare_profile_digest_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_final_manifest_binds_serverinfo_chronology_and_client_profile(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        finalizer = source.split("function Write-FinalEvidenceManifest", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("Assert-ServerInfoSequence", finalizer)
        self.assertIn("-ExpectedProfileSha256", finalizer)
        self.assertIn("$client.profile_sha256", finalizer)

    def test_serverinfo_sequence_rejects_replay_and_profile_substitution(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Assert-ServerInfoSequence"
        self.assertIn(marker, source)
        function_source = marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wrapper = root / "serverinfo-sequence.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                + function_source
                + "\n"
                + "$profile = 'sha256:' + ('a' * 64)\n"
                + "function New-Info([long]$Ts, [long]$Uptime, [string]$Sha) { "
                + "[pscustomobject]@{ anchor_wall_unix_ns=1700000000000000000; srv_ts_us=$Ts; uptime_s=$Uptime; "
                + "execution_capabilities=[pscustomobject]@{ validated_profiles=@([pscustomobject]@{ profile_sha256=$Sha }) } } }\n"
                + "$identity = New-Info 100 10 $profile\n"
                + "$start = New-Info 200 10 $profile\n"
                + "$end = New-Info 300 11 $profile\n"
                + "Assert-ServerInfoSequence -Identity $identity -StartBarrier $start -EndBarrier $end -ExpectedProfileSha256 $profile\n"
                + "$replayCaught = $false\n"
                + "try { Assert-ServerInfoSequence -Identity $identity -StartBarrier $start -EndBarrier $start -ExpectedProfileSha256 $profile } "
                + "catch { if ($_.Exception.Message -eq 'serverinfo_chronology_invalid') { $replayCaught = $true } else { throw } }\n"
                + "if (-not $replayCaught) { throw 'serverinfo_replay_was_accepted' }\n"
                + "$forged = New-Info 300 11 ('sha256:' + ('b' * 64))\n"
                + "$profileCaught = $false\n"
                + "try { Assert-ServerInfoSequence -Identity $identity -StartBarrier $start -EndBarrier $forged -ExpectedProfileSha256 $profile } "
                + "catch { if ($_.Exception.Message -like 'serverinfo_profile_binding_mismatch*') { $profileCaught = $true } else { throw } }\n"
                + "if (-not $profileCaught) { throw 'serverinfo_profile_substitution_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    self.powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(wrapper),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_atomic_publish_requires_independent_bundle_verification_before_success(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("[string]$BundleVerifierPath", source)
        preflight = source.split("function Assert-LocalPreflight", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("bundle_verifier", preflight)
        self.assertIn("result_jsonl_verifier", preflight)
        self.assertIn("$BundleVerifierPath", preflight)
        self.assertIn("$ResultJsonlVerifierPath", preflight)
        for label in (
            "request_entry_contract",
            "profile_manifest",
            "profile_definition",
            "runtime_plan",
            "result_schema_core_v1",
            "result_schema_v1",
            "result_schema_v2",
            "room_schema_v19",
        ):
            self.assertIn(label, preflight)
        publication = source.split("$draftManifestPath = Write-EvidenceManifestDraft", 1)[1]
        staged = publication.index(
            "Move-Item -LiteralPath $PartialDirectory -Destination $VerificationCandidateDirectory"
        )
        verified = publication.index("Invoke-PublishedBundleVerification")
        stage_removed = publication.index(
            "Remove-Item -LiteralPath $VerificationStageDirectory -Force"
        )
        report_committed = publication.index("[IO.File]::Move(")
        published = publication.index("$script:Published = $true")
        self.assertLess(staged, verified)
        self.assertLess(verified, stage_removed)
        self.assertLess(stage_removed, report_committed)
        self.assertLess(report_committed, published)
        verifier_call = publication[
            verified:publication.index("$verificationReportTempPath", verified)
        ]
        self.assertIn("-CompleteDirectory $VerificationCandidateDirectory", verifier_call)
        self.assertNotIn("-CompleteDirectory $CompleteDirectory", verifier_call)
        self.assertIn("-PublishTarget $CompleteDirectory", verifier_call)
        self.assertNotIn(
            "Move-Item -LiteralPath $VerificationCandidateDirectory -Destination $CompleteDirectory",
            publication,
        )
        self.assertIn("--publish", source)
        self.assertIn("--publish-target", source)
        self.assertIn("--device-policy-path", source)
        self.assertIn("--gh-path", source)
        self.assertIn("--repository-root", source)
        self.assertIn("--android-build-tools-dir", source)
        self.assertIn("--expected-remote-host", source)
        self.assertIn("--expected-ssh-known-hosts-sha256", source)
        self.assertIn("AndroidBuildToolsDir", source)
        self.assertIn(".verification.json", source)
        self.assertIn(".verification.partial", source)
        self.assertIn(".verification-stage", source)
        self.assertIn(".verification-failed.partial", source)
        self.assertIn("Remove-Item -LiteralPath $completeMarker", source)
        self.assertIn(
            "Remove-Item -LiteralPath $verificationReportTempPath -Force",
            publication,
        )
        self.assertNotIn(
            "Move-Item -LiteralPath $PartialDirectory -Destination $CompleteDirectory",
            publication,
        )
        self.assertIn("$script:ResolvedTools.ToolingFiles.Count -ne 25", source)
        report_verifier = self._function_source("Invoke-PublishedBundleVerification")
        self.assertLess(
            report_verifier.index("Assert-BundleVerificationReport"),
            report_verifier.index("Write-NewTextNoBom -Path $reportTempPath"),
        )

    @unittest.skipUnless(os.name == "nt", "Windows evidence publication contract")
    def test_ready_marker_is_digest_bound_and_is_the_last_release_commit(self) -> None:
        publish_ready = self._function_source("Publish-EvidenceReleaseReady")
        self.assertIn(
            "$roundTrip.committed_at_utc -is [DateTime]",
            publish_ready,
        )
        self.assertIn(
            "$roundTripCommittedAt -cne [string]$marker.committed_at_utc",
            publish_ready,
        )
        collection_id = "d82-token-quick-20260719T010203Z-" + "a" * 32
        run_id = "11111111-2222-7333-8444-555555555555"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / f"{collection_id}.complete"
            bundle.mkdir()
            manifest = bundle / "evidence-manifest.final.json"
            manifest.write_text('{"schema":"fixture"}\n', encoding="utf-8")
            manifest_sha = hashlib.sha256(manifest.read_bytes()).hexdigest()
            report = root / f"{collection_id}.verification.json"
            report.write_text('{"schema":"verification-fixture"}\n', encoding="utf-8")
            report_sha = hashlib.sha256(report.read_bytes()).hexdigest()
            ready = root / f"{collection_id}.READY.json"
            ready_temp = root / f"{collection_id}.ready.partial"
            wrapper = root / "publish-ready.ps1"
            wrapper.write_text(
                "$ErrorActionPreference='Stop'\nSet-StrictMode -Version 2.0\n"
                + self._function_source("Write-NewTextNoBom")
                + "\n"
                + self._function_source("Assert-NonEmptyFile")
                + "\n"
                + publish_ready
                + "\nfunction Assert-NonReparseDirectoryChain { param($Path,$ReasonPrefix); return $Path }\n"
                + "function Assert-PrivateEvidenceRoot { param($Path) }\n"
                + f"$result=Publish-EvidenceReleaseReady -EvidenceRootPath '{root}' "
                + f"-CollectionId '{collection_id}' -RunId '{run_id}' "
                + "-ExecutionMode 'negative_receipt_missing' "
                + f"-CompleteDirectory '{bundle}' -FinalManifestSha256 '{manifest_sha}' "
                + f"-VerificationReportPath '{report}'\n"
                + f"if ($result -cne '{ready}') {{ throw 'ready_path_invalid' }}\n"
                + f"if (Test-Path -LiteralPath '{ready_temp}') {{ throw 'ready_temp_remained' }}\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )
            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            marker = json.loads(ready.read_text(encoding="utf-8"))
            self.assertEqual("aneb-d82-evidence-release", marker["schema"])
            self.assertEqual("1.0.0", marker["schema_version"])
            self.assertEqual("ready", marker["status"])
            self.assertEqual("ok", marker["reason_code"])
            self.assertEqual(collection_id, marker["collection_id"])
            self.assertEqual(run_id, marker["run_id"])
            self.assertEqual("negative_receipt_missing", marker["execution_mode"])
            self.assertEqual(bundle.name, marker["bundle_leaf"])
            self.assertEqual(manifest_sha, marker["manifest_sha256"])
            self.assertEqual(report.name, marker["verification_report_leaf"])
            self.assertEqual(report_sha, marker["verification_report_sha256"])

        source = SCRIPT.read_text(encoding="utf-8")
        publication = source.split("$draftManifestPath = Write-EvidenceManifestDraft", 1)[1]
        report_commit = publication.index("[IO.File]::Move(")
        ready_commit = publication.index("Publish-EvidenceReleaseReady")
        published = publication.index("$script:Published = $true")
        self.assertLess(report_commit, ready_commit)
        self.assertLess(ready_commit, published)
        self.assertIn(".READY.json", source)
        self.assertIn(".ready.partial", source)

    def test_ready_marker_rejects_non_uuidv7_run_id(self) -> None:
        publish_ready = self._function_source("Publish-EvidenceReleaseReady")
        collection_id = "d82-token-quick-20260719T010203Z-" + "a" * 32
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wrapper = root / "reject-non-v7-ready.ps1"
            wrapper.write_text(
                "$ErrorActionPreference='Stop'\nSet-StrictMode -Version 2.0\n"
                + publish_ready
                + "\n$caught=$false\ntry { $null=Publish-EvidenceReleaseReady "
                + f"-EvidenceRootPath '{root}' -CollectionId '{collection_id}' "
                + "-RunId '11111111-2222-4333-8444-555555555555' "
                + "-ExecutionMode 'positive' -CompleteDirectory 'unused' "
                + f"-FinalManifestSha256 '{'b' * 64}' -VerificationReportPath 'unused' "
                + "} catch { if ($_.Exception.Message -ceq 'evidence_release_identity_invalid') "
                + "{ $caught=$true } else { throw } }\n"
                + "if (-not $caught) { throw 'non_uuidv7_ready_identity_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_bundle_verification_report_gate_rejects_multiline_or_nonpass_output(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Assert-BundleVerificationReport"
        self.assertIn(marker, source)
        function_source = marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        expected = {
            "collection_id": "d82-token-quick-20260719T010203Z-" + "a" * 32,
            "run_id": "11111111-2222-4333-8444-555555555555",
            "manifest_sha256": "b" * 64,
            "source_commit": "c" * 40,
            "server_version": "aneb-server/0.8.0",
            "server_binary_sha256": "d" * 64,
            "apk_sha256": "e" * 64,
            "remote_host": "aneb.invalid",
            "ssh_known_hosts_sha256": "9" * 64,
            "package_name": "com.aneb.probe.codex",
            "version_name": "0.5.12-codex",
            "version_code": 44,
            "signer_sha256": "f" * 64,
            "device_policy_sha256": "6" * 64,
            "adb_serial_sha256": "7" * 64,
            "attestation_bundle_sha256": "8" * 64,
            "gh_sha256": "a" * 64,
            "run_timeout_seconds": 900,
            "lock_ttl_seconds": 1200,
        }
        valid_report = {
            "schema": "aneb-d82-bundle-verification-report",
            "schema_version": "1.1.0",
            "status": "pass",
            "reason_code": "ok",
            "execution_mode": "positive",
            "publication": True,
            **{key: expected[key] for key in (
                "collection_id", "run_id", "manifest_sha256", "source_commit",
                "server_version", "server_binary_sha256", "apk_sha256",
                "remote_host", "ssh_known_hosts_sha256",
                "run_timeout_seconds", "lock_ttl_seconds",
            )},
            "apk_identity_reverified": True,
            "verified_apk_identity": {
                "package_name": expected["package_name"],
                "version_name": expected["version_name"],
                "version_code": expected["version_code"],
                "signer_sha256": expected["signer_sha256"],
            },
            "android_build_tools_version": "35.0.0",
            "accessibility_raw_reverified": True,
            "raw_state_reverified": True,
            "raw_files_verified": 26,
            "raw_state_files_verified": 26,
            "device_identity_raw_files_verified": 6,
            "raw_files_verified_total": 32,
            "device_identity": {
                "schema": "aneb-token-quick-device-identity-verification",
                "schema_version": "1.0.0",
                "status": "pass",
                "reason_code": "ok",
                "device_alias": "P40 Pro",
                "device_policy_sha256": expected["device_policy_sha256"],
                "adb_serial_sha256": expected["adb_serial_sha256"],
                "android_boot_id": "12345678-1234-4abc-8def-1234567890ab",
                "properties_sha256": "5" * 64,
                "serial_property_confirmed": True,
                "verified_boot_observed_complete": True,
                "verified_boot_secure": True,
                "raw_files_verified": 6,
            },
            "candidate_provenance_reverified": True,
            "attestation_bundle_sha256": expected["attestation_bundle_sha256"],
            "gh_version": "2.83.0",
            "gh_executable_sha256": expected["gh_sha256"],
            "evidence_time_chain_reverified": True,
            "run_duration_ms": 1000,
            "run_start_delta_ms": 5,
            "remote_receipt_clock_delta_ms": 25,
            "journal_derivation_recomputed": True,
            "request_entry_audit_recomputed": True,
            "client_room_result_recomputed": True,
            "negative_proxy_evidence_recomputed": False,
            "negative_reason_code": None,
            "client_delivery_proven": None,
            "negative_proxy_raw_files_verified": 0,
            "business_counts": {"echo": 20, "token_sim": 3, "download": 1},
            "typed_metrics_verified": 14,
            "envelope_metrics_verified": 26,
            "successful_task_count": 3,
        }
        expected_arguments = (
            f" -CollectionId '{expected['collection_id']}'"
            f" -RunId '{expected['run_id']}'"
            f" -FinalManifestSha256 '{expected['manifest_sha256']}'"
            f" -SourceCommit '{expected['source_commit']}'"
            f" -ServerVersion '{expected['server_version']}'"
            f" -ServerBinarySha256 '{expected['server_binary_sha256']}'"
            f" -ApkSha256 '{expected['apk_sha256']}'"
            f" -ExpectedRemoteHost '{expected['remote_host']}'"
            f" -ExpectedSshKnownHostsSha256 '{expected['ssh_known_hosts_sha256']}'"
            f" -ExpectedPackageName '{expected['package_name']}'"
            f" -ExpectedVersionName '{expected['version_name']}'"
            f" -ExpectedVersionCode {expected['version_code']}"
            f" -ExpectedSignerSha256 '{expected['signer_sha256']}'"
            f" -ExpectedDevicePolicySha256 '{expected['device_policy_sha256']}'"
            f" -ExpectedAdbSerialSha256 '{expected['adb_serial_sha256']}'"
            f" -ExpectedAttestationBundleSha256 '{expected['attestation_bundle_sha256']}'"
            f" -ExpectedGhSha256 '{expected['gh_sha256']}'"
            " -ExpectedExecutionMode 'positive'"
            f" -ExpectedRunTimeoutSeconds {expected['run_timeout_seconds']}"
            f" -ExpectedLockTtlSeconds {expected['lock_ttl_seconds']}"
        )
        negative_report = json.loads(json.dumps(valid_report))
        negative_report.update(
            execution_mode="negative_receipt_missing",
            negative_proxy_evidence_recomputed=True,
            negative_reason_code="receipt_missing",
            client_delivery_proven=False,
            negative_proxy_raw_files_verified=12,
            business_counts={"echo": 0, "token_sim": 0, "download": 0},
            typed_metrics_verified=0,
            envelope_metrics_verified=0,
            successful_task_count=0,
        )
        negative_tampered = json.loads(json.dumps(negative_report))
        negative_tampered["business_counts"]["echo"] = 1
        negative_arguments = expected_arguments.replace(
            "-ExpectedExecutionMode 'positive'",
            "-ExpectedExecutionMode 'negative_receipt_missing'",
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wrapper = root / "bundle-report-gate.ps1"
            wrapper.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                + function_source
                + "\n"
                + f"$valid = '{json.dumps(valid_report, separators=(',', ':'))}'\n"
                + "$null = Assert-BundleVerificationReport -OutputLines @($valid) -ExitCode 0"
                + expected_arguments
                + f"\n$negative = '{json.dumps(negative_report, separators=(',', ':'))}'\n"
                + "$null = Assert-BundleVerificationReport -OutputLines @($negative) -ExitCode 0"
                + negative_arguments
                + f"\n$negativeTampered = '{json.dumps(negative_tampered, separators=(',', ':'))}'\n"
                + "$negativeTamperCaught = $false\n"
                + "try { $null = Assert-BundleVerificationReport -OutputLines @($negativeTampered) -ExitCode 0"
                + negative_arguments
                + " } catch { if ($_.Exception.Message -like 'bundle_verifier_report_binding_mismatch*') "
                + "{ $negativeTamperCaught = $true } else { throw } }\n"
                + "if (-not $negativeTamperCaught) { throw 'negative_business_tamper_was_accepted' }\n"
                + "\n$multiCaught = $false\n"
                + "try { $null = Assert-BundleVerificationReport -OutputLines @($valid, $valid) -ExitCode 0"
                + expected_arguments
                + " } "
                + "catch { if ($_.Exception.Message -eq 'bundle_verifier_output_line_count_invalid') { $multiCaught = $true } else { throw } }\n"
                + "if (-not $multiCaught) { throw 'multiline_bundle_report_was_accepted' }\n"
                + "$failCaught = $false\n"
                + "$failed = '{\"schema\":\"aneb-d82-bundle-verification-report\",\"schema_version\":\"1.1.0\",\"status\":\"fail\",\"reason_code\":\"tampered\"}'\n"
                + "try { $null = Assert-BundleVerificationReport -OutputLines @($failed) -ExitCode 1"
                + expected_arguments
                + " } "
                + "catch { if ($_.Exception.Message -like 'bundle_verifier_rejected*') { $failCaught = $true } else { throw } }\n"
                + "if (-not $failCaught) { throw 'failed_bundle_report_was_accepted' }\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_failed_bundle_verification_never_publishes_sibling_report(self) -> None:
        collection_id = "d82-token-quick-20260719T010203Z-" + "a" * 32
        run_id = "11111111-2222-4333-8444-555555555555"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = root / f"{collection_id}.complete"
            candidate.mkdir()
            publish_target = root / "published.complete"
            final_report = root / f"{collection_id}.verification.json"
            partial_report = root / f"{collection_id}.verification.partial"
            wrapper = root / "failed-bundle-report.ps1"
            malformed_pass = json.dumps(
                {
                    "schema": "aneb-d82-bundle-verification-report",
                    "schema_version": "1.1.0",
                    "status": "pass",
                    "reason_code": "ok",
                    "execution_mode": "positive",
                },
                separators=(",", ":"),
            )
            wrapper.write_text(
                "$ErrorActionPreference='Stop'\nSet-StrictMode -Version 2.0\n"
                + self._function_source("Write-NewTextNoBom")
                + "\n"
                + self._function_source("Assert-BundleVerificationReport")
                + "\n"
                + self._function_source("Invoke-PublishedBundleVerification")
                + "\n$BundleVerifierPath='verify_token_quick_evidence_bundle.py'\n"
                + "$ToolCommandTimeoutSeconds=5\n"
                + "$script:ResolvedTools=[pscustomobject]@{ Python='python'; Gh='gh'; RepositoryRoot='repo'; "
                + "AndroidBuildToolsDir='build-tools'; DevicePolicy=[pscustomobject]@{Path='policy'} }\n"
                + "function Assert-ToolingProvenanceStable { param($ResolvedTools) }\n"
                + "function Assert-BundledDevicePolicyStable { param([string]$EvidenceDirectory) }\n"
                + "function Assert-GhExecutableStable {}\n"
                + "function Assert-NonEmptyFile { param([string]$Path,[string]$Label) }\n"
                + "function Invoke-BoundedNativeTextOnce { param($Command,$Arguments,$TimeoutSeconds,$TimeoutReason,$LaunchReason); "
                + "return [pscustomobject]@{ ExitCode=0; Text='"
                + malformed_pass.replace("'", "''")
                + "' } }\n"
                + "$caught=$false\ntry { $null=Invoke-PublishedBundleVerification "
                + f"-CompleteDirectory '{candidate}' -CollectionId '{collection_id}' -RunId '{run_id}' "
                + f"-FinalManifestSha256 '{'b' * 64}' -SourceCommit '{'c' * 40}' "
                + f"-ServerVersion 'aneb-server/0.8.0' -ServerBinarySha256 '{'d' * 64}' "
                + f"-ApkSha256 '{'e' * 64}' -ExpectedRemoteHost 'aneb.invalid' "
                + f"-ExpectedSshKnownHostsSha256 '{'9' * 64}' -ExpectedPackageName 'com.aneb.probe.codex' "
                + "-ExpectedVersionName '0.5.12-codex' -ExpectedVersionCode 44 "
                + f"-ExpectedSignerSha256 '{'f' * 64}' -ExpectedDevicePolicySha256 '{'6' * 64}' "
                + f"-ExpectedAdbSerialSha256 '{'7' * 64}' -ExpectedAttestationBundleSha256 '{'8' * 64}' "
                + f"-ExpectedGhSha256 '{'a' * 64}' -ExpectedExecutionMode 'positive' "
                + "-ExpectedRunTimeoutSeconds 900 -ExpectedLockTtlSeconds 1200 "
                + f"-EvidenceRootPath '{root}' -PublishTarget '{publish_target}' "
                + "} catch { if ($_.Exception.Message -like 'bundle_verifier_report_binding_mismatch*') "
                + "{ $caught=$true } else { throw } }\n"
                + "if (-not $caught) { throw 'invalid_report_was_accepted' }\n"
                + f"if (Test-Path -LiteralPath '{final_report}') {{ throw 'orphan_final_report' }}\n"
                + f"if (Test-Path -LiteralPath '{partial_report}') {{ throw 'orphan_partial_report' }}\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_bundle_verification_report_gate_rejects_substituted_bindings(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        marker = "function Assert-BundleVerificationReport"
        function_source = marker + source.split(marker, 1)[1].split("\nfunction ", 1)[0]
        expected = {
            "collection_id": "d82-token-quick-20260719T010203Z-" + "a" * 32,
            "run_id": "11111111-2222-4333-8444-555555555555",
            "manifest_sha256": "b" * 64,
            "source_commit": "c" * 40,
            "server_version": "aneb-server/0.8.0",
            "server_binary_sha256": "d" * 64,
            "apk_sha256": "e" * 64,
            "remote_host": "aneb.invalid",
            "ssh_known_hosts_sha256": "9" * 64,
            "package_name": "com.aneb.probe.codex",
            "version_name": "0.5.12-codex",
            "version_code": 44,
            "signer_sha256": "f" * 64,
            "device_policy_sha256": "6" * 64,
            "adb_serial_sha256": "7" * 64,
            "attestation_bundle_sha256": "8" * 64,
            "gh_sha256": "a" * 64,
            "run_timeout_seconds": 900,
            "lock_ttl_seconds": 1200,
        }
        report = {
            "schema": "aneb-d82-bundle-verification-report",
            "schema_version": "1.1.0",
            "status": "pass",
            "reason_code": "ok",
            "execution_mode": "positive",
            "publication": True,
            **{key: expected[key] for key in (
                "collection_id", "run_id", "manifest_sha256", "source_commit",
                "server_version", "server_binary_sha256", "apk_sha256",
                "remote_host", "ssh_known_hosts_sha256",
                "run_timeout_seconds", "lock_ttl_seconds",
            )},
            "apk_identity_reverified": True,
            "verified_apk_identity": {
                "package_name": expected["package_name"],
                "version_name": expected["version_name"],
                "version_code": expected["version_code"],
                "signer_sha256": expected["signer_sha256"],
            },
            "android_build_tools_version": "35.0.0",
            "accessibility_raw_reverified": True,
            "raw_state_reverified": True,
            "raw_files_verified": 26,
            "raw_state_files_verified": 26,
            "device_identity_raw_files_verified": 6,
            "raw_files_verified_total": 32,
            "device_identity": {
                "schema": "aneb-token-quick-device-identity-verification",
                "schema_version": "1.0.0",
                "status": "pass",
                "reason_code": "ok",
                "device_alias": "P40 Pro",
                "device_policy_sha256": expected["device_policy_sha256"],
                "adb_serial_sha256": expected["adb_serial_sha256"],
                "android_boot_id": "12345678-1234-4abc-8def-1234567890ab",
                "properties_sha256": "5" * 64,
                "serial_property_confirmed": True,
                "verified_boot_observed_complete": True,
                "verified_boot_secure": True,
                "raw_files_verified": 6,
            },
            "candidate_provenance_reverified": True,
            "attestation_bundle_sha256": expected["attestation_bundle_sha256"],
            "gh_version": "2.83.0",
            "gh_executable_sha256": expected["gh_sha256"],
            "evidence_time_chain_reverified": True,
            "run_duration_ms": 1000,
            "run_start_delta_ms": 5,
            "remote_receipt_clock_delta_ms": 25,
            "journal_derivation_recomputed": True,
            "request_entry_audit_recomputed": True,
            "client_room_result_recomputed": True,
            "negative_proxy_evidence_recomputed": False,
            "negative_reason_code": None,
            "client_delivery_proven": None,
            "negative_proxy_raw_files_verified": 0,
            "business_counts": {"echo": 20, "token_sim": 3, "download": 1},
            "typed_metrics_verified": 14,
            "envelope_metrics_verified": 26,
            "successful_task_count": 3,
        }
        expected_arguments = (
            f" -CollectionId '{expected['collection_id']}'"
            f" -RunId '{expected['run_id']}'"
            f" -FinalManifestSha256 '{expected['manifest_sha256']}'"
            f" -SourceCommit '{expected['source_commit']}'"
            f" -ServerVersion '{expected['server_version']}'"
            f" -ServerBinarySha256 '{expected['server_binary_sha256']}'"
            f" -ApkSha256 '{expected['apk_sha256']}'"
            f" -ExpectedRemoteHost '{expected['remote_host']}'"
            f" -ExpectedSshKnownHostsSha256 '{expected['ssh_known_hosts_sha256']}'"
            f" -ExpectedPackageName '{expected['package_name']}'"
            f" -ExpectedVersionName '{expected['version_name']}'"
            f" -ExpectedVersionCode {expected['version_code']}"
            f" -ExpectedSignerSha256 '{expected['signer_sha256']}'"
            f" -ExpectedDevicePolicySha256 '{expected['device_policy_sha256']}'"
            f" -ExpectedAdbSerialSha256 '{expected['adb_serial_sha256']}'"
            f" -ExpectedAttestationBundleSha256 '{expected['attestation_bundle_sha256']}'"
            f" -ExpectedGhSha256 '{expected['gh_sha256']}'"
            " -ExpectedExecutionMode 'positive'"
            f" -ExpectedRunTimeoutSeconds {expected['run_timeout_seconds']}"
            f" -ExpectedLockTtlSeconds {expected['lock_ttl_seconds']}"
        )
        substitutions: dict[str, object] = {
            "execution_mode": "negative_receipt_missing",
            "collection_id": "d82-token-quick-20260719T010203Z-" + "f" * 32,
            "run_id": "99999999-8888-4777-8666-555555555555",
            "manifest_sha256": "0" * 64,
            "source_commit": "1" * 40,
            "server_version": "aneb-server/9.9.9",
            "server_binary_sha256": "2" * 64,
            "apk_sha256": "3" * 64,
            "remote_host": "alias.invalid",
            "ssh_known_hosts_sha256": "4" * 64,
            "publication": False,
            "apk_identity_reverified": False,
            "verified_apk_identity": {
                "package_name": "com.aneb.probe",
                "version_name": expected["version_name"],
                "version_code": expected["version_code"],
                "signer_sha256": expected["signer_sha256"],
            },
            "android_build_tools_version": "34.0.0",
            "accessibility_raw_reverified": False,
            "raw_state_reverified": False,
            "raw_files_verified": 25,
            "raw_state_files_verified": 25,
            "device_identity_raw_files_verified": 5,
            "raw_files_verified_total": 30,
            "candidate_provenance_reverified": False,
            "attestation_bundle_sha256": "0" * 64,
            "gh_version": "",
            "gh_executable_sha256": "0" * 64,
            "evidence_time_chain_reverified": False,
            "run_duration_ms": 1_000_000,
            "run_start_delta_ms": 5001,
            "remote_receipt_clock_delta_ms": 60001,
            "run_timeout_seconds": 899,
            "lock_ttl_seconds": 1199,
            "journal_derivation_recomputed": False,
            "request_entry_audit_recomputed": False,
            "client_room_result_recomputed": False,
            "negative_proxy_evidence_recomputed": True,
            "negative_reason_code": "receipt_missing",
            "client_delivery_proven": False,
            "negative_proxy_raw_files_verified": 12,
            "business_counts": {"echo": 19, "token_sim": 3, "download": 1},
            "typed_metrics_verified": 13,
            "envelope_metrics_verified": 25,
            "successful_task_count": 2,
        }
        script_lines = [
            "$ErrorActionPreference = 'Stop'",
            function_source,
        ]
        for field, substitution in substitutions.items():
            forged = dict(report)
            forged[field] = substitution
            forged_json = json.dumps(forged, separators=(",", ":"))
            script_lines.extend(
                [
                    "$caught = $false",
                    f"$forged = '{forged_json}'",
                    "try { $null = Assert-BundleVerificationReport -OutputLines @($forged) -ExitCode 0"
                    + expected_arguments
                    + " } catch { if ($_.Exception.Message -like 'bundle_verifier_report_binding_mismatch*') "
                    + "{ $caught = $true } else { throw } }",
                    f"if (-not $caught) {{ throw 'substituted_{field}_was_accepted' }}",
                ]
            )
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / "bundle-report-bindings.ps1"
            wrapper.write_text("\n".join(script_lines) + "\n", encoding="utf-8")
            completed = subprocess.run(
                [self.powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                text=True,
                capture_output=True,
                check=False,
                timeout=20,
            )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_collector_plan_binds_run_and_lock_timeouts_into_final_inventory(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        plan = source.split("Write-JsonNoBom -Path (Join-Path $PartialDirectory 'collector-plan.json')", 1)[1].split(
            "\n})", 1
        )[0]
        self.assertIn("run_timeout_seconds = $RunTimeoutSeconds", plan)
        self.assertIn("lock_ttl_seconds = $LockTtlSeconds", plan)
        self.assertIn("adb_command_timeout_seconds = $AdbCommandTimeoutSeconds", plan)
        self.assertIn("ssh_command_timeout_seconds = $SshCommandTimeoutSeconds", plan)
        self.assertIn("tool_command_timeout_seconds = $ToolCommandTimeoutSeconds", plan)
        self.assertIn("android_build_tools_version = '35.0.0'", plan)
        self.assertIn("schema_version = '1.1.0'", plan)
        self.assertIn("execution_mode = $script:ExecutionMode", plan)
        self.assertIn("client_server_base", plan)
        self.assertIn("negative_proxy_upstream_url", plan)
        self.assertIn("negative_proxy_device_port", plan)
        finalizer = source.split("function Write-FinalEvidenceManifest", 1)[1].split(
            "\nfunction ", 1
        )[0]
        self.assertIn("collector-plan.json", finalizer)
        self.assertIn("run_timeout_seconds", finalizer)
        self.assertIn("lock_ttl_seconds", finalizer)
        self.assertIn("$RunTimeoutSeconds", finalizer)
        self.assertIn("$LockTtlSeconds", finalizer)
        self.assertIn("schema_version = '1.1.0'", finalizer)
        self.assertIn("evidence_scope = $script:EvidenceScope", finalizer)
        self.assertIn("execution_mode = $script:ExecutionMode", finalizer)
        self.assertIn("negative-proxy/upstream-serverinfo.raw", finalizer)
        self.assertIn("adb-reverse-final.txt", finalizer)


if __name__ == "__main__":
    unittest.main()
