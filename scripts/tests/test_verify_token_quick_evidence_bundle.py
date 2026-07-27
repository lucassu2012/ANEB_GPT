from __future__ import annotations

import atexit
import base64
import contextlib
import functools
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import ssl
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import prepare_token_run_evidence as evidence_helper
import verify_token_quick_client_db as client_verifier
import verify_token_quick_device_identity as device_identity_verifier
import verify_token_quick_evidence_bundle as bundle_verifier
import verify_token_quick_negative_client_db as negative_client_verifier
import verify_token_quick_raw_state as raw_state_verifier
import verify_token_run_audit as audit_verifier
from scripts.tests.test_prepare_token_run_evidence import (
    BOOT_ID,
    END_ID,
    INSTANCE_ID,
    INVOCATION_ID,
    JOURNAL_MONOTONIC_ANCHOR,
    LOCK_MARKER,
    LOCK_NONCE,
    LOCK_REMOTE_PID,
    MAIN_PID,
    PrepareTokenRunEvidenceCliTest,
    REMOTE_REALTIME_ANCHOR_USEC,
    START_ID,
)
from scripts.tests.test_verify_token_quick_client_db import (
    RUN_ID,
    valid_body,
    write_database,
)
from scripts.tests.test_verify_token_quick_negative_client_db import (
    create_negative_database,
)
from scripts.tests.test_verify_token_run_audit import TokenRunAuditCliTest


SCRIPT = SCRIPTS / "verify_token_quick_evidence_bundle.py"
SERVER_BASE = "https://203.0.113.10:8443"
REMOTE_HOST = "203.0.113.10"
SSH_KNOWN_HOSTS_SHA256 = "e" * 64
DEVICE_SERIAL = "WCR7N18A18002110"
DEVICE_BOOT_ID = "12345678-1234-4abc-8def-1234567890ab"
DEVICE_PROPERTIES = {
    "ro.serialno": DEVICE_SERIAL,
    "ro.boot.serialno": DEVICE_SERIAL,
    "ro.product.manufacturer": "HUAWEI",
    "ro.product.model": "P40 Pro",
    "ro.product.device": "ELS",
    "ro.product.name": "ELS-N29",
    "ro.build.fingerprint": (
        "HUAWEI/ELS-N29/HWELS:12/HUAWEIELS/1:user/release-keys"
    ),
    "ro.build.version.security_patch": "2026-06-01",
    "ro.boot.verifiedbootstate": "green",
    "ro.boot.vbmeta.device_state": "locked",
    "ro.boot.flash.locked": "1",
    "ro.boot.veritymode": "enforcing",
}
CI_SOURCE_REF = "refs/heads/codex/d82-three-trust-chains"
CI_RUN_URL = "https://github.com/lucassu2012/ANEB_GPT/actions/runs/123456789"
CI_WORKFLOW = "lucassu2012/ANEB_GPT/.github/workflows/ci.yml"
CI_APK_NAME = "ANEB-Probe-0.5.12-codex-debug.apk"
CI_INSTRUCTIONS_NAME = "ANEB-安装说明.txt"
COLLECTION_ID = "d82-token-quick-20260716T235955Z-0123456789abcdef0123456789abcdef"
PLAN_CREATED_AT = "2026-07-16T23:59:55.0000000Z"
PREFLIGHT_AT = "2026-07-16T23:59:57.0000000Z"
RECEIPT_AT = "2026-07-16T23:59:58.0000000Z"
BUNDLE_REMOTE_REALTIME_ANCHOR_USEC = 1_784_246_399_000_000
ROOM_COPY_AT = "2026-07-17T00:00:06.0000000Z"
FINAL_CLEAN_AT = "2026-07-17T00:00:07.0000000Z"
STATUS_AT = "2026-07-17T00:00:08.0000000Z"
FINALIZED_AT = "2026-07-17T00:00:09.0000000Z"
TOOL_PATHS = {
    "collector": "scripts/collect_token_quick_evidence.ps1",
    "derive_helper": "scripts/prepare_token_run_evidence.py",
    "audit_verifier": "scripts/verify_token_run_audit.py",
    "client_db_verifier": "scripts/verify_token_quick_client_db.py",
    "negative_proxy": "scripts/token_serverinfo_negative_proxy.py",
    "negative_proxy_evidence_verifier": (
        "scripts/verify_token_quick_negative_proxy_evidence.py"
    ),
    "negative_client_db_verifier": (
        "scripts/verify_token_quick_negative_client_db.py"
    ),
    "result_jsonl_verifier": "scripts/verify_result_jsonl.py",
    "bundle_verifier": "scripts/verify_token_quick_evidence_bundle.py",
    "ready_publisher": "scripts/publish_token_quick_ready.py",
    "ready_transaction": "scripts/quick_ready_transaction.py",
    "quick_evidence_security": "scripts/quick_evidence_security.py",
    "token_release_verifier": "scripts/verify_token_quick_evidence_release.py",
    "quick_collection_adapter": "scripts/quick_collection_verifier_adapter.py",
    "quick_collection_core": "scripts/quick_collection_verifier.py",
    "workflow_trace_cli": "scripts/quick_collection_trace_cli.py",
    "workflow_trace_core": "scripts/quick_collection_workflow.py",
    "time_chain_verifier": "scripts/verify_token_quick_time_chain.py",
    "raw_state_verifier": "scripts/verify_token_quick_raw_state.py",
    "device_identity_verifier": "scripts/verify_token_quick_device_identity.py",
    "ci_provenance_verifier": "scripts/verify_ci_apk_provenance.py",
    "ci_workflow": ".github/workflows/ci.yml",
    "debug_candidate_packager": "scripts/package_debug_candidate.py",
    "spec_catalog": "spec/catalog.json",
    "request_entry_contract": (
        "spec/execution-contracts/"
        "token_multimodal_quick-1.2.1.request-entry.json"
    ),
    "profile_manifest": "profiles/published/token_multimodal_quick/manifest.sha256",
    "profile_definition": "profiles/published/token_multimodal_quick/profile.json",
    "runtime_plan": "profiles/published/token_multimodal_quick/runtime_plan.json",
    "result_schema_core_v1": "spec/schemas/aneb-result-core-v1.schema.json",
    "result_schema_v1": "spec/schemas/aneb-result-v1.schema.json",
    "result_schema_v2": "spec/schemas/aneb-result-v2.schema.json",
    "room_schema_v19": (
        "app/probe/schemas/com.aneb.probe.data.AnebDatabase/19.json"
    ),
    "server_ca": "app/probe/src/main/res/raw/aneb_ip_ca.pem",
}
PROFILE_PATHS = (
    "profiles/published/token_multimodal_quick/manifest.sha256",
    "profiles/published/token_multimodal_quick/profile.json",
    "profiles/published/token_multimodal_quick/runtime_plan.json",
)
SOURCE_SUPPORT_PATHS = (".gitignore",)
ANDROID_BUILD_TOOLS_VERSION = "35.0.0"
VERIFIER_PROCESS_TIMEOUT_SECONDS = 120


def find_android_build_tools() -> Path | None:
    roots: list[Path] = []
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value:
            roots.append(Path(value))
    if os.name == "nt":
        roots.append(Path(r"E:\tools\android-sdk"))
    for root in roots:
        candidate = root / "build-tools" / ANDROID_BUILD_TOOLS_VERSION
        aapt2 = candidate / ("aapt2.exe" if os.name == "nt" else "aapt2")
        if aapt2.is_file() and (candidate / "lib" / "apksigner.jar").is_file():
            return candidate
    return None


def find_java() -> Path | None:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return candidate
    resolved = shutil.which("java")
    return Path(resolved) if resolved else None


ANDROID_BUILD_TOOLS = find_android_build_tools()
JAVA = find_java()
ANDROID_PLATFORM_JAR = (
    ANDROID_BUILD_TOOLS.parent.parent
    / "platforms"
    / "android-35"
    / "android.jar"
    if ANDROID_BUILD_TOOLS is not None
    else None
)
KEYTOOL = (
    JAVA.with_name("keytool.exe" if os.name == "nt" else "keytool")
    if JAVA is not None
    else None
)
APK_INTEGRATION_AVAILABLE = (
    ANDROID_BUILD_TOOLS is not None
    and JAVA is not None
    and ANDROID_PLATFORM_JAR is not None
    and ANDROID_PLATFORM_JAR.is_file()
    and KEYTOOL is not None
    and KEYTOOL.is_file()
)
_FROZEN_TOKEN_APK_TEMPORARY: tempfile.TemporaryDirectory[str] | None = None


def run_frozen_apk_tool(command: list[str], step: str) -> None:
    completed = subprocess.run(
        command,
        check=False,
        capture_output=True,
        timeout=60,
    )
    output = completed.stdout + completed.stderr
    if completed.returncode != 0 or len(output) > 256 * 1024:
        raise RuntimeError(f"Frozen Token APK fixture failed at {step}")


@functools.lru_cache(maxsize=1)
def frozen_token_apk() -> Path:
    global _FROZEN_TOKEN_APK_TEMPORARY
    if (
        ANDROID_BUILD_TOOLS is None
        or JAVA is None
        or ANDROID_PLATFORM_JAR is None
        or KEYTOOL is None
    ):
        raise RuntimeError("Frozen Token APK fixture tools are unavailable")
    _FROZEN_TOKEN_APK_TEMPORARY = tempfile.TemporaryDirectory(
        prefix="aneb-token-frozen-apk-"
    )
    atexit.register(_FROZEN_TOKEN_APK_TEMPORARY.cleanup)
    directory = Path(_FROZEN_TOKEN_APK_TEMPORARY.name)
    manifest = directory / "AndroidManifest.xml"
    unsigned_apk = directory / "unsigned.apk"
    signed_apk = directory / "ANEB-Probe-0.5.12-codex-debug.apk"
    keystore = directory / "debug.keystore"
    manifest.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        'package="com.aneb.probe.codex" android:versionCode="44" '
        'android:versionName="0.5.12-codex">\n'
        '  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35"/>\n'
        '  <application android:label="ANEB frozen Token test fixture"/>\n'
        "</manifest>\n",
        encoding="utf-8",
        newline="",
    )
    run_frozen_apk_tool(
        [
            str(ANDROID_BUILD_TOOLS / ("aapt2.exe" if os.name == "nt" else "aapt2")),
            "link",
            "-o",
            str(unsigned_apk),
            "-I",
            str(ANDROID_PLATFORM_JAR),
            "--manifest",
            str(manifest),
            "--min-sdk-version",
            "29",
            "--target-sdk-version",
            "35",
        ],
        "aapt2-link",
    )
    with zipfile.ZipFile(unsigned_apk, "a") as archive:
        archive.writestr("classes.dex", b"dex\n035\x00ANEB frozen Token test fixture")
    run_frozen_apk_tool(
        [
            str(KEYTOOL),
            "-genkeypair",
            "-keystore",
            str(keystore),
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-alias",
            "androiddebugkey",
            "-dname",
            "CN=ANEB Frozen Token Test,O=ANEB,C=CN",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "3650",
        ],
        "keytool",
    )
    run_frozen_apk_tool(
        [
            str(JAVA),
            "-jar",
            str(ANDROID_BUILD_TOOLS / "lib" / "apksigner.jar"),
            "sign",
            "--ks",
            str(keystore),
            "--ks-pass",
            "pass:android",
            "--key-pass",
            "pass:android",
            "--out",
            str(signed_apk),
            str(unsigned_apk),
        ],
        "apksigner-sign",
    )
    return signed_apk


@unittest.skipUnless(
    APK_INTEGRATION_AVAILABLE,
    "requires Java and Android Build Tools 35.0.0",
)
class FrozenTokenApkFixtureTests(unittest.TestCase):
    def test_fixture_has_the_frozen_token_client_identity(self) -> None:
        identity = bundle_verifier.verify_apk_identity(
            frozen_token_apk().read_bytes(),
            ANDROID_BUILD_TOOLS,
        )

        self.assertEqual("com.aneb.probe.codex", identity["package_name"])
        self.assertEqual("0.5.12-codex", identity["version_name"])
        self.assertEqual(44, identity["version_code"])


@functools.lru_cache(maxsize=1)
def frozen_apk_signer_sha256() -> str:
    if ANDROID_BUILD_TOOLS is None or JAVA is None:
        raise RuntimeError("Android identity tools are unavailable")
    apk = frozen_token_apk()
    completed = subprocess.run(
        [
            str(JAVA),
            "-jar",
            str(ANDROID_BUILD_TOOLS / "lib" / "apksigner.jar"),
            "verify",
            "--print-certs",
            str(apk),
        ],
        check=False,
        capture_output=True,
        timeout=30,
    )
    output = completed.stdout + completed.stderr
    if completed.returncode != 0 or len(output) > 256 * 1024:
        raise RuntimeError("Frozen Token APK signature verification failed")
    match = re.fullmatch(
        rb"(?s).*^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})\r?$.*",
        output,
        re.MULTILINE,
    )
    if match is None:
        raise RuntimeError("Real ANEB APK signer output is incomplete")
    return match.group(1).decode("ascii")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n",
        encoding="utf-8",
        newline="",
    )


class WorkflowTraceBundleEvidenceTests(unittest.TestCase):
    @staticmethod
    def _write_valid_evidence(bundle: Path) -> None:
        write_json(
            bundle / "workflow-trace.json",
            {
                "schema": "aneb-quick-workflow-trace@1.0.0",
                "events": [
                    {"phase": "preflight", "outcome": "pass"},
                    {"phase": "acquire", "outcome": "pass"},
                    {"phase": "collect", "outcome": "pass"},
                    {"phase": "cleanup_phone", "outcome": "pass"},
                    {"phase": "cleanup_remote", "outcome": "pass"},
                ],
            },
        )
        write_json(
            bundle / "workflow-decision.json",
            {
                "schema": "aneb-quick-workflow-decision@1.0.0",
                "publish_eligible": True,
                "primary_failure": None,
                "cleanup_failures": [],
            },
        )

    def test_valid_trace_and_decision_are_independently_recomputed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary)
            self._write_valid_evidence(bundle)

            decision = bundle_verifier.verify_workflow_trace_evidence(bundle)

        self.assertTrue(decision["publish_eligible"])
        self.assertIsNone(decision["primary_failure"])
        self.assertEqual([], decision["cleanup_failures"])

    def test_tampered_decision_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary)
            self._write_valid_evidence(bundle)
            write_json(
                bundle / "workflow-decision.json",
                {
                    "schema": "aneb-quick-workflow-decision@1.0.0",
                    "publish_eligible": False,
                    "primary_failure": None,
                    "cleanup_failures": [],
                },
            )

            with self.assertRaises(bundle_verifier.BundleFailure) as caught:
                bundle_verifier.verify_workflow_trace_evidence(bundle)

        self.assertEqual(
            "workflow_trace_evidence_invalid", caught.exception.reason_code
        )

    def test_invalid_trace_order_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary)
            self._write_valid_evidence(bundle)
            trace = json.loads(
                (bundle / "workflow-trace.json").read_text(encoding="utf-8")
            )
            trace["events"][3], trace["events"][4] = (
                trace["events"][4],
                trace["events"][3],
            )
            write_json(bundle / "workflow-trace.json", trace)

            with self.assertRaises(bundle_verifier.BundleFailure) as caught:
                bundle_verifier.verify_workflow_trace_evidence(bundle)

        self.assertEqual(
            "workflow_trace_evidence_invalid", caught.exception.reason_code
        )


def certificate_thumbprint(path: Path) -> str:
    der = ssl.PEM_cert_to_DER_cert(path.read_text(encoding="ascii"))
    return hashlib.sha1(der).hexdigest()


@contextlib.contextmanager
def held_publish_lock(evidence_root: Path) -> object:
    lock_path = evidence_root / ".aneb-d82-publish.lock"
    with lock_path.open("w+b") as stream:
        stream.write(b"\0")
        stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt

            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl

            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


class BundleFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.repository = root / "source"
        self.bundle = root / f"{COLLECTION_ID}.complete"
        self.repository.mkdir()
        self.bundle.mkdir()
        self.apk_sha = sha256_bytes(frozen_token_apk().read_bytes())
        self.signer_sha = frozen_apk_signer_sha256()
        self.device_policy_path = root / "approved-device-policy.json"
        write_json(
            self.device_policy_path,
            {
                "schema": "aneb-device-identity-policy",
                "schema_version": "1.0.0",
                "device_alias": "P40 Pro",
                "adb_serial_sha256": sha256_bytes(DEVICE_SERIAL.encode("utf-8")),
                "properties": DEVICE_PROPERTIES,
            },
        )
        self.device_policy_sha256 = sha256_bytes(
            self.device_policy_path.read_bytes()
        )
        self._copy_tooling_and_commit()
        self._write_payload()
        self.rebuild_manifests()

    def _candidate_attestation_output(
        self,
        *,
        workflow_uri: str | None = None,
        source_commit: str | None = None,
        subject_digest: str | None = None,
    ) -> list[object]:
        workflow = workflow_uri or (
            f"https://github.com/{CI_WORKFLOW}@{CI_SOURCE_REF}"
        )
        commit = source_commit or self.commit
        digest = subject_digest or sha256_bytes(self.candidate_apk.read_bytes())
        manifest_digest = sha256_bytes(
            (self.candidate_directory / "build-manifest.json").read_bytes()
        )
        instructions_digest = sha256_bytes(
            (self.candidate_directory / CI_INSTRUCTIONS_NAME).read_bytes()
        )
        return [
            {
                "attestation": {"bundle": "verified"},
                "verificationResult": {
                    "signature": {
                        "certificate": {
                            "certificateIssuer": (
                                "CN=sigstore-intermediate,O=sigstore.dev"
                            ),
                            "issuer": "https://token.actions.githubusercontent.com",
                            "subjectAlternativeName": workflow,
                            "buildSignerURI": workflow,
                            "buildSignerDigest": commit,
                            "runnerEnvironment": "github-hosted",
                            "sourceRepositoryURI": (
                                "https://github.com/lucassu2012/ANEB_GPT"
                            ),
                            "sourceRepositoryDigest": commit,
                            "sourceRepositoryRef": CI_SOURCE_REF,
                            "buildConfigURI": workflow,
                            "buildConfigDigest": commit,
                            "runInvocationURI": CI_RUN_URL + "/attempts/1",
                        }
                    },
                    "verifiedTimestamps": [
                        {
                            "type": "transparency-log",
                            "uri": "https://rekor.sigstore.dev",
                            "timestamp": "2026-07-19T00:00:00Z",
                        }
                    ],
                    "statement": {
                        "_type": "https://in-toto.io/Statement/v1",
                        "predicateType": "https://slsa.dev/provenance/v1",
                        "subject": [
                            {
                                "name": CI_APK_NAME,
                                "digest": {"sha256": digest},
                            },
                            {
                                "name": "build-manifest.json",
                                "digest": {"sha256": manifest_digest},
                            },
                            {
                                "name": CI_INSTRUCTIONS_NAME,
                                "digest": {"sha256": instructions_digest},
                            },
                        ],
                        "predicate": {},
                    },
                },
            }
        ]

    def write_fake_gh(
        self,
        *,
        workflow_uri: str | None = None,
        source_commit: str | None = None,
        subject_digest: str | None = None,
    ) -> None:
        write_json(
            self.fake_gh_output,
            self._candidate_attestation_output(
                workflow_uri=workflow_uri,
                source_commit=source_commit,
                subject_digest=subject_digest,
            ),
        )

    def rebuild_candidate_checksums(self) -> None:
        files = sorted(
            path
            for path in self.candidate_directory.iterdir()
            if path.name != "checksums.sha256"
        )
        (self.candidate_directory / "checksums.sha256").write_text(
            "".join(
                f"{sha256_bytes(path.read_bytes())}  {path.name}\n"
                for path in files
            ),
            encoding="utf-8",
            newline="",
        )

    def _write_ci_candidate(self) -> None:
        self.candidate_directory = self.bundle / "ci-candidate"
        self.candidate_directory.mkdir()
        self.candidate_apk = self.candidate_directory / CI_APK_NAME
        shutil.copyfile(frozen_token_apk(), self.candidate_apk)
        write_json(
            self.candidate_directory / "provenance.sigstore.json",
            {"mediaType": "application/vnd.dev.sigstore.bundle.v0.3+json"},
        )
        self.attestation_bundle_sha256 = sha256_bytes(
            (self.candidate_directory / "provenance.sigstore.json").read_bytes()
        )
        write_json(
            self.candidate_directory / "build-manifest.json",
            {
                "contract_version": "aneb-debug-candidate-v1",
                "artifact_kind": "debug_non_release",
                "public_release": False,
                "source": {
                    "git_sha": self.commit,
                    "git_ref": CI_SOURCE_REF,
                    "workflow_run_url": CI_RUN_URL,
                },
                "apk": {
                    "file_name": CI_APK_NAME,
                    "sha256": self.apk_sha,
                    "size_bytes": self.candidate_apk.stat().st_size,
                    "package_id": "com.aneb.probe.codex",
                    "version_name": "0.5.12-codex",
                    "version_code": 44,
                    "min_sdk": 29,
                    "target_sdk": 35,
                    "signer_dn": "C=US, O=Android, CN=Android Debug",
                    "signer_sha256": self.signer_sha,
                },
                "verification": {
                    "zip_integrity": "pass",
                    "gradle_apk_identity_match": "pass",
                    "apksigner_verification": "pass",
                    "debug_boundary": "pass",
                },
            },
        )
        (self.candidate_directory / CI_INSTRUCTIONS_NAME).write_text(
            "D-82 verified debug candidate\n", encoding="utf-8", newline=""
        )
        self.rebuild_candidate_checksums()

        self.fake_gh_output = self.root / "fake-gh-output.json"
        self.fake_gh_script = self.root / "fake-gh-contract.py"
        self.fake_gh_script.write_text(
            "import pathlib\n"
            "import sys\n"
            "if sys.argv[1:] == ['--version']:\n"
            "    print('gh version 2.96.0 (2026-07-16)')\n"
            "    raise SystemExit(0)\n"
            f"sys.stdout.buffer.write(pathlib.Path({str(self.fake_gh_output)!r})"
            ".read_bytes())\n",
            encoding="utf-8",
            newline="",
        )
        if os.name == "nt":
            self.gh_path = self.root / "fake-gh.cmd"
            self.gh_path.write_text(
                f'@echo off\r\n"{sys.executable}" '
                f'"{self.fake_gh_script}" %*\r\n',
                encoding="ascii",
                newline="",
            )
        else:
            self.gh_path = self.root / "fake-gh"
            self.gh_path.write_text(
                "#!/bin/sh\n"
                f"exec {json.dumps(sys.executable)} "
                f"{json.dumps(str(self.fake_gh_script))} \"$@\"\n",
                encoding="utf-8",
                newline="",
            )
            self.gh_path.chmod(0o755)
        self.gh_sha256 = sha256_bytes(self.gh_path.read_bytes())
        self.write_fake_gh()

    def _copy_tooling_and_commit(self) -> None:
        for relative in (*TOOL_PATHS.values(), *SOURCE_SUPPORT_PATHS):
            source = ROOT / relative
            destination = self.repository / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        subprocess.run(["git", "init", "-q"], cwd=self.repository, check=True)
        subprocess.run(
            ["git", "config", "user.email", "aneb-tests@example.invalid"],
            cwd=self.repository,
            check=True,
        )
        subprocess.run(
            ["git", "config", "user.name", "ANEB Tests"],
            cwd=self.repository,
            check=True,
        )
        subprocess.run(
            ["git", "add", "."],
            cwd=self.repository,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "commit", "-q", "-m", "fixture"],
            cwd=self.repository,
            check=True,
        )
        self.commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.repository, text=True
        ).strip()
        self.tooling = {
            label: sha256_bytes((self.repository / relative).read_bytes())
            for label, relative in TOOL_PATHS.items()
        }

    def _serverinfo(self, sequence: int) -> dict[str, object]:
        profile_digest = "sha256:" + (
            (self.repository / PROFILE_PATHS[0])
            .read_text(encoding="ascii")
            .splitlines()[0]
            .split()[0]
        )
        return {
            "anchor_wall_unix_ns": 1_721_370_000_000_000_000,
            "congestion_control": "cubic",
            "execution_capabilities": {
                "contract_id": "aneb-server-capability-receipt",
                "contract_version": "1.0.0",
                "primitives": [
                    {
                        "primitive_id": "download",
                        "wire_contract_id": "aneb-download-v1",
                    },
                    {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
                    {
                        "primitive_id": "token_sim",
                        "wire_contract_id": "aneb-token-task-v1",
                    },
                ],
                "validated_profiles": [
                    {
                        "profile_id": "token_multimodal_quick",
                        "profile_sha256": profile_digest,
                        "profile_version": "1.2.1",
                    }
                ],
            },
            "version": "aneb-server/0.8.0",
            "goos": "linux",
            "goarch": "amd64",
            "h3_enabled": True,
            "srv_ts_us": 1_721_370_000_000_000 + sequence,
            "tcp_slow_start_after_idle": "0",
            "uptime_s": 1_000 + (sequence // 2),
        }

    def _write_payload(self) -> None:
        for sequence, name in enumerate(
            ("identity-serverinfo", "start-barrier", "end-barrier"), 1
        ):
            serverinfo = self._serverinfo(sequence)
            write_json(self.bundle / f"{name}.json", serverinfo)
            (self.bundle / f"{name}.headers").write_bytes(
                b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"
            )

        receipt = PrepareTokenRunEvidenceCliTest.receipt()
        receipt.update(
            server_base=SERVER_BASE,
            captured_at_utc=RECEIPT_AT,
            remote_realtime_anchor_usec=BUNDLE_REMOTE_REALTIME_ANCHOR_USEC,
            serverinfo_body_sha256=sha256_bytes(
                (self.bundle / "identity-serverinfo.json").read_bytes()
            ),
        )
        write_json(self.bundle / "pre-start-receipt.json", receipt)

        audit_case = TokenRunAuditCliTest()
        events = [
            audit_case.reachability(run_id=RUN_ID),
            audit_case.capability(run_id=RUN_ID),
            *[
                audit_case.business("/api/v1/echo", "POST", run_id=RUN_ID)
                for _ in range(20)
            ],
            *[
                audit_case.business("/api/v1/token-sim", "POST", run_id=RUN_ID)
                for _ in range(3)
            ],
            audit_case.business("/api/v1/download", "GET", run_id=RUN_ID),
        ]
        message_text = audit_case.window(events)
        messages = message_text.rstrip("\n").split("\n")
        records = []
        for index, message in enumerate(messages, 1):
            record = PrepareTokenRunEvidenceCliTest.journal_record(index, message)
            record["__REALTIME_TIMESTAMP"] = str(
                BUNDLE_REMOTE_REALTIME_ANCHOR_USEC + index * 250_000
            )
            records.append(record)
        raw_journal = b"".join(
            (
                json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n"
            ).encode("utf-8")
            for record in records
        )
        (self.bundle / "journal.raw.jsonl").write_bytes(raw_journal)
        with tempfile.TemporaryDirectory() as derived:
            derived_root = Path(derived)
            args = SimpleNamespace(
                journal=self.bundle / "journal.raw.jsonl",
                pre_start_receipt=self.bundle / "pre-start-receipt.json",
                run_id=RUN_ID,
                start_barrier_id=START_ID,
                end_barrier_id=END_ID,
                message_output=derived_root / "token-run-audit.log",
                derivation_output=derived_root / "journal-derivation.json",
            )
            with contextlib.redirect_stdout(io.StringIO()):
                evidence_helper._derive(args)
            shutil.copyfile(args.message_output, self.bundle / "token-run-audit.log")
            shutil.copyfile(
                args.derivation_output, self.bundle / "journal-derivation.json"
            )
        audit_report = audit_verifier.verify_journal(
            (self.bundle / "token-run-audit.log").read_text(encoding="utf-8"),
            run_id=RUN_ID,
            start_barrier_id=START_ID,
            barrier_id=END_ID,
            mode="positive",
            profile_contract="token_multimodal_quick@1.2.1",
        )
        write_json(self.bundle / "request-entry-audit.json", audit_report)

        body = valid_body()
        body["context"]["endpoint"]["server_base"] = SERVER_BASE
        database = self.bundle / "aneb-probe.db"
        write_database(database, body)
        client_report, result_text = client_verifier.verify(
            database,
            run_id=RUN_ID,
            manifest=(
                ROOT
                / "profiles"
                / "published"
                / "token_multimodal_quick"
                / "manifest.sha256"
            ),
            expected_server_base=SERVER_BASE,
        )
        write_json(self.bundle / "client-db-report.json", client_report)
        (self.bundle / "client-result.json").write_text(
            result_text, encoding="utf-8", newline=""
        )
        database_digest = sha256_bytes(database.read_bytes())
        write_json(
            self.bundle / "room-copy-inventory.json",
            {
                "schema": "aneb-frozen-room-copy",
                "schema_version": "1.0.0",
                "captured_at_utc": ROOM_COPY_AT,
                "app_process_state": "stopped_before_copy",
                "files": [
                    {
                        "name": "aneb-probe.db",
                        "state": "present",
                        "bytes": database.stat().st_size,
                        "sha256": database_digest,
                    },
                    {"name": "aneb-probe.db-wal", "state": "absent"},
                    {"name": "aneb-probe.db-shm", "state": "absent"},
                ],
            },
        )

        shutil.copyfile(frozen_token_apk(), self.bundle / "installed-base.apk")
        self.apk_sha = sha256_bytes(
            (self.bundle / "installed-base.apk").read_bytes()
        )
        self.signer_sha = frozen_apk_signer_sha256()
        shutil.copyfile(self.device_policy_path, self.bundle / "device-policy.json")
        for stage in ("preflight", "final"):
            (self.bundle / f"device-adb-serial-{stage}.txt").write_text(
                DEVICE_SERIAL + "\n", encoding="utf-8", newline=""
            )
            (self.bundle / f"device-boot-id-{stage}.txt").write_text(
                DEVICE_BOOT_ID + "\n", encoding="utf-8", newline=""
            )
            (self.bundle / f"device-getprop-{stage}.txt").write_text(
                "".join(
                    f"{key}={DEVICE_PROPERTIES[key]}\n"
                    for key in device_identity_verifier.PROPERTY_KEYS
                ),
                encoding="utf-8",
                newline="",
            )
        self.device_report = device_identity_verifier.verify_device_identity(
            self.bundle,
            policy_path=self.device_policy_path,
            expected_input_serial=DEVICE_SERIAL,
        )
        self._write_ci_candidate()
        write_json(
            self.bundle / "device-preflight.json",
            {
                "schema": "aneb-p40-live-preflight",
                "schema_version": "1.0.0",
                "captured_at_utc": PREFLIGHT_AT,
                "adb_serial": DEVICE_SERIAL,
                "launcher": "com.huawei.android.launcher/.unihome.UniHomeLauncher",
                "stay_on_while_plugged_in": "0",
                "package_name": "com.aneb.probe.codex",
                "version_name": "0.5.12-codex",
                "version_code": 44,
                "signer_sha256": self.signer_sha,
                "apk_sha256": self.apk_sha,
                "run_as": "available",
                "tun0": "absent",
                "active_vpn": False,
            },
        )
        conflict_packages = (
            "com.aneb.probe",
            "com.aneb.probe.codex",
            "com.emanuelef.remote_capture",
            "com.pcapdroid.mitm",
            "com.wireguard.android",
        )
        write_json(
            self.bundle / "device-final-clean.json",
            {
                "schema": "aneb-p40-live-clean-after",
                "schema_version": "1.0.0",
                "captured_at_utc": FINAL_CLEAN_AT,
                "launcher": "com.huawei.android.launcher/.unihome.UniHomeLauncher",
                "processes": {package: "" for package in conflict_packages},
                "services": {package: "" for package in conflict_packages},
                "tun0": "absent",
                "active_vpn": False,
                "stay_on_while_plugged_in": "0",
            },
        )
        write_json(
            self.bundle / "cleanup-report.json",
            {
                "schema": "aneb-d82-collector-cleanup",
                "schema_version": "1.0.0",
                "captured_at_utc": FINAL_CLEAN_AT,
                "status": "pass",
                "errors": [],
                "target_stop_attempted": True,
                "negative_reverse_preflight_captured": False,
                "negative_reverse_mutation_attempted": False,
                "negative_reverse_remove_attempted": False,
                "negative_reverse_final_captured": False,
                "negative_proxy_completed": False,
                "negative_proxy_stop_attempted": False,
                "negative_proxy_stop_succeeded": False,
                "busy_sentinel_start_attempted": True,
                "busy_sentinel_started": True,
                "busy_sentinel_verified": True,
                "busy_sentinel_restored_after_target": True,
                "busy_sentinel_lost": False,
                "busy_sentinel_component": "com.android.settings/.Settings",
                "busy_sentinel_release_attempted": True,
                "busy_sentinel_home_succeeded": True,
                "stayon_restored": True,
                "lock_release_attempted": True,
            },
        )
        write_json(
            self.bundle / "collector-status.json",
            {
                "schema": "aneb-d82-collector-status",
                "schema_version": "1.0.0",
                "completed_at_utc": STATUS_AT,
                "status": "pass",
                "reason_code": "ok",
                "failure": None,
                "workflow_succeeded": True,
                "cleanup_succeeded": True,
                "collection_id": COLLECTION_ID,
                "run_id": RUN_ID,
                "start_barrier_id": START_ID,
                "end_barrier_id": END_ID,
                "partial_directory": str(self.bundle.with_suffix(".partial")),
                "complete_directory": str(self.bundle),
            },
        )
        WorkflowTraceBundleEvidenceTests._write_valid_evidence(self.bundle)
        write_json(
            self.bundle / "collector-plan.json",
            {
                "schema": "aneb-d82-collector-plan",
                "schema_version": "1.1.0",
                "execution_mode": "positive",
                "created_at_utc": PLAN_CREATED_AT,
                "collection_id": COLLECTION_ID,
                "run_id": RUN_ID,
                "run_timeout_seconds": 900,
                "lock_ttl_seconds": 1200,
                "server_base": SERVER_BASE,
                "client_server_base": SERVER_BASE,
                "negative_proxy_upstream_url": None,
                "negative_proxy_device_port": None,
                "remote_host": REMOTE_HOST,
                "ssh_known_hosts_sha256": SSH_KNOWN_HOSTS_SHA256,
                "adb_serial_sha256": sha256_bytes(DEVICE_SERIAL.encode("utf-8")),
                "device_policy_sha256": self.device_policy_sha256,
                "start_barrier_id": START_ID,
                "end_barrier_id": END_ID,
            },
        )
        for name, data in {
            "remote-pre-start.txt": b"frozen pre-start\n",
            "remote-end.txt": b"frozen end\n",
            "app-logcat.txt": f"TOKEN_V2_END run_id={RUN_ID} status=completed\n".encode(),
            "lock-acquired.txt": b"LOCK_ACQUIRED\n",
            "lock-released.txt": b"LOCK_RELEASED\n",
            "lock-release-verified.txt": b"LOCK_RELEASE_VERIFIED\n",
            "device-accessibility-final.txt": (
                b"ANEB_D82_DEVICE_ACCESSIBILITY_FINAL_V1\n"
                b"enabled_accessibility_services_command=settings get secure "
                b"enabled_accessibility_services\n"
                b"enabled_accessibility_services_output_begin\n"
                b"null\n"
                b"enabled_accessibility_services_output_end\n"
                b"dumpsys_accessibility_command=dumpsys accessibility\n"
                b"dumpsys_accessibility_output_begin\n"
                b"User state[attributes:{id=0, currentUser=true}]\n"
                b"  bound services:{}\n"
                b"  accessibility_shortcut_target_service="
                b"com.aneb.probe.codex/.accessibility.ProbeAccessibilityService\n"
                b"dumpsys_accessibility_output_end\n"
            ),
            "app-logcat.stderr.txt": b"",
            "journal-derivation.stdout.txt": b"ANEB_D82_DERIVATION_OK\n",
            "installed-apk-signer.txt": b"Signer #1 certificate SHA-256 digest: "
            + self.signer_sha.encode("ascii")
            + b"\n",
            "device-busy-sentinel-launch.txt": b"Status: ok\nActivity: com.android.settings/.Settings\n",
            "device-busy-sentinel-restore.txt": b"Status: ok\nActivity: com.android.settings/.Settings\n",
        }.items():
            (self.bundle / name).write_bytes(data)
        write_json(self.bundle / "device-identity-report.json", self.device_report)
        write_json(
            self.bundle / "ci-candidate-verification.json",
            {"status": "pass", "reason_code": "ok"},
        )
        write_json(
            self.bundle / "device-busy-sentinel.json",
            {
                "schema": "aneb-d82-busy-sentinel",
                "schema_version": "1.0.0",
                "started_at_utc": PREFLIGHT_AT,
                "intent_action": "android.settings.SETTINGS",
                "component": "com.android.settings/.Settings",
                "launcher_component": (
                    "com.huawei.android.launcher/.unihome.UniHomeLauncher"
                ),
            },
        )
        write_json(
            self.bundle / "device-busy-sentinel-release-guard.json",
            {
                "schema": "aneb-d82-busy-sentinel-release-guard",
                "schema_version": "1.0.0",
                "captured_at_utc": FINAL_CLEAN_AT,
                "sentinel_component": "com.android.settings/.Settings",
                "processes": {},
                "services": {},
                "tun": "absent",
                "connectivity_dump": "WIFI VALIDATED",
                "vpn_dump": "DISCONNECTED",
                "no_conflicting_session": True,
            },
        )
        sentinel_stages = (
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
        (self.bundle / "busy-sentinel-observations.jsonl").write_text(
            "".join(
                json.dumps(
                    {
                        "schema": "aneb-d82-busy-sentinel-observation",
                        "schema_version": "1.0.0",
                        "captured_at_utc": FINAL_CLEAN_AT,
                        "stage": stage,
                        "expected_component": "com.android.settings/.Settings",
                        "observed_components": [
                            "com.android.settings/.Settings",
                            "com.android.settings/.Settings",
                            "com.android.settings/.Settings",
                        ],
                        "matched": True,
                        "window_dump": "settings focused",
                        "activity_dump": "settings resumed",
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n"
                for stage in sentinel_stages
            ),
            encoding="utf-8",
            newline="",
        )
        self._write_raw_state_payload()

    def _write_raw_state_payload(self) -> None:
        launcher = "com.huawei.android.launcher/.unihome.UniHomeLauncher"
        empty_processes = {
            package: "" for package in sorted(raw_state_verifier.CONFLICT_PACKAGES)
        }
        empty_services = {
            package: "ACTIVITY MANAGER SERVICES\n  (nothing)"
            for package in sorted(raw_state_verifier.CONFLICT_PACKAGES)
        }
        for stage in ("preflight", "final"):
            (self.bundle / f"device-window-{stage}.txt").write_text(
                f"  mCurrentFocus=Window{{123 u0 {launcher}}}\n",
                encoding="utf-8",
                newline="",
            )
            (self.bundle / f"device-activity-{stage}.txt").write_text(
                f"  mFocusedApp=ActivityRecord{{123 u0 {launcher} t1}}\n"
                f"  topResumedActivity=ActivityRecord{{123 u0 {launcher} t1}}\n",
                encoding="utf-8",
                newline="",
            )
            write_json(self.bundle / f"device-processes-{stage}.json", empty_processes)
            write_json(self.bundle / f"device-services-{stage}.json", empty_services)
            (self.bundle / f"device-connectivity-{stage}.txt").write_text(
                "NetworkAgentInfo{ networkId=100 state: CONNECTED "
                "Transports: WIFI VALIDATED }\n",
                encoding="utf-8",
                newline="",
            )
            (self.bundle / f"device-vpn-{stage}.txt").write_text(
                "VPN manager state: DISCONNECTED\n", encoding="utf-8", newline=""
            )
            (self.bundle / f"device-tun-{stage}.txt").write_text(
                "absent\n", encoding="utf-8", newline=""
            )
            (self.bundle / f"device-stayon-{stage}.txt").write_text(
                "0\n", encoding="utf-8", newline=""
            )
        (self.bundle / "device-accessibility-preflight.txt").write_text(
            "enabled_accessibility_services=null\n"
            "User state:\n"
            "  Bound services: {}\n"
            "  Shortcut targets: [com.aneb.probe.codex/.AnebAccessibilityService]\n",
            encoding="utf-8",
            newline="",
        )
        (self.bundle / "device-package-preflight.txt").write_text(
            "Packages:\n"
            "  Package [com.aneb.probe.codex] (deadbeef):\n"
            "    versionCode=44 minSdk=29 targetSdk=35\n"
            "    versionName=0.5.12-codex\n",
            encoding="utf-8",
            newline="",
        )
        anchor = json.dumps(
            {
                "__CURSOR": "s=pre-start-cursor",
                "__MONOTONIC_TIMESTAMP": str(JOURNAL_MONOTONIC_ANCHOR),
            },
            separators=(",", ":"),
        ).encode("utf-8")
        anchor_b64 = base64.b64encode(anchor).decode("ascii")
        (self.bundle / "remote-pre-start.txt").write_text(
            f"boot_id={BOOT_ID}\n"
            f"systemd_invocation_id={INVOCATION_ID}\n"
            f"main_pid={MAIN_PID}\n"
            f"server_binary_sha256={'d' * 64}\n"
            f"remote_realtime_anchor_usec={BUNDLE_REMOTE_REALTIME_ANCHOR_USEC}\n"
            f"journal_anchor_json_base64={anchor_b64}\n",
            encoding="utf-8",
            newline="",
        )
        (self.bundle / "remote-end.txt").write_text(
            f"boot_id={BOOT_ID}\n"
            f"systemd_invocation_id={INVOCATION_ID}\n"
            f"main_pid={MAIN_PID}\n"
            f"server_binary_sha256={'d' * 64}\n",
            encoding="utf-8",
            newline="",
        )
        marker = f"/run/aneb-token-audit-{LOCK_NONCE}.lock"
        (self.bundle / "lock-acquired.txt").write_text(
            f"LOCK_ACQUIRED nonce={LOCK_NONCE} pid={LOCK_REMOTE_PID} "
            f"marker={marker}\n",
            encoding="utf-8",
            newline="",
        )
        (self.bundle / "lock-released.txt").write_text(
            f"LOCK_RELEASED nonce={LOCK_NONCE}\n"
            "process_exit=0\n"
            "stderr=\n",
            encoding="utf-8",
            newline="",
        )
        (self.bundle / "lock-release-verified.txt").write_text(
            f"LOCK_RELEASE_VERIFIED nonce={LOCK_NONCE}\n",
            encoding="utf-8",
            newline="",
        )
        write_json(
            self.bundle / "logcat-capture-marker.json",
            {
                "schema": "aneb-d82-logcat-capture-marker",
                "schema_version": "1.0.0",
                "captured_at_utc": PREFLIGHT_AT,
                "marker_nonce": LOCK_NONCE,
                "marker": f"D82_CAPTURE_MARKER nonce={LOCK_NONCE}",
            },
        )
        prefix = "1784246400.000  100  101 I AnebProbe: "
        events = (
            f"TOKEN_V2_START run_id={RUN_ID} variant=quick server={SERVER_BASE}",
            f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=validated_receipt",
            f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
            f"TOKEN_V2_RESULT run_id={RUN_ID} score=88.8 grade=A "
            "verdict=PASS confidence=MEDIUM",
            f"TOKEN_V2_END run_id={RUN_ID} status=completed",
        )
        (self.bundle / "app-logcat.txt").write_text(
            "1784246399.999  100  101 I AnebD82: "
            f"D82_CAPTURE_MARKER nonce={LOCK_NONCE}\n"
            + "".join(prefix + event + "\n" for event in events),
            encoding="utf-8",
            newline="",
        )

    def rebuild_manifests(self) -> None:
        for name in (
            "COMPLETE",
            "evidence-manifest.final.json",
            "evidence-inventory.draft.json",
        ):
            path = self.bundle / name
            if path.exists():
                path.unlink()
        files = []
        for path in sorted(self.bundle.rglob("*"), key=lambda item: item.as_posix()):
            if not path.is_file():
                continue
            raw = path.read_bytes()
            files.append(
                {
                    "bytes": len(raw),
                    "path": path.relative_to(self.bundle).as_posix(),
                    "sha256": sha256_bytes(raw),
                }
            )
        draft = {
            "acceptance_eligible": False,
            "evidence_scope": "inventory_only_not_d82_acceptance",
            "file_count": len(files),
            "files": files,
            "schema": "aneb-evidence-manifest-draft",
            "schema_version": "1.0.0",
            "status": "draft",
            "total_bytes": sum(item["bytes"] for item in files),
        }
        write_json(self.bundle / "evidence-inventory.draft.json", draft)

        catalog = json.loads((ROOT / "spec" / "catalog.json").read_text(encoding="utf-8"))
        contract = next(
            item
            for item in catalog["execution_evidence_contracts"]
            if item["contract_id"] == "aneb-token-quick-request-entry-counts"
        )
        ca_path = self.repository / TOOL_PATHS["server_ca"]
        serverinfo_digests = {
            "identity": sha256_bytes((self.bundle / "identity-serverinfo.json").read_bytes()),
            "start_barrier": sha256_bytes((self.bundle / "start-barrier.json").read_bytes()),
            "end_barrier": sha256_bytes((self.bundle / "end-barrier.json").read_bytes()),
        }
        final = {
            "schema": "aneb-d82-final-evidence-manifest",
            "schema_version": "1.1.0",
            "status": "final",
            "acceptance_eligible": True,
            "evidence_scope": "d82_token_quick_cross_bound_acceptance",
            "execution_mode": "positive",
            "finalized_at_utc": FINALIZED_AT,
            "collection_id": COLLECTION_ID,
            "run_id": RUN_ID,
            "start_barrier_id": START_ID,
            "end_barrier_id": END_ID,
            "profile_contract": "token_multimodal_quick@1.2.1",
            "profile_contract_definition_sha256": contract["canonical_sha256"],
            "tooling_provenance": {
                "source_commit": self.commit,
                "source_dirty": False,
                "files": self.tooling,
                "external_inputs": {
                    "ssh_known_hosts_sha256": SSH_KNOWN_HOSTS_SHA256,
                    "device_policy_sha256": self.device_policy_sha256,
                },
            },
            "device": self.device_report,
            "client": {
                "package_name": "com.aneb.probe.codex",
                "version_name": "0.5.12-codex",
                "version_code": 44,
                "signer_sha256": self.signer_sha,
                "apk_sha256": self.apk_sha,
            },
            "source": {
                "server_base": SERVER_BASE,
                "server_version": "aneb-server/0.8.0",
                "server_binary_sha256": "d" * 64,
                "boot_id": BOOT_ID,
                "systemd_invocation_id": INVOCATION_ID,
                "main_pid": MAIN_PID,
                "journal_cursor": "s=pre-start-cursor",
                "journal_monotonic_anchor": JOURNAL_MONOTONIC_ANCHOR,
                "remote_realtime_anchor_usec": BUNDLE_REMOTE_REALTIME_ANCHOR_USEC,
                "serverinfo_body_sha256": serverinfo_digests,
                "server_ca_sha256": sha256_bytes(ca_path.read_bytes()),
                "server_ca_thumbprint": certificate_thumbprint(ca_path),
            },
            "draft_inventory_sha256": sha256_bytes(
                (self.bundle / "evidence-inventory.draft.json").read_bytes()
            ),
            "client_result_body_sha256": json.loads(
                (self.bundle / "client-db-report.json").read_text(encoding="utf-8")
            )["result_body_sha256"],
            "file_count": draft["file_count"],
            "total_bytes": draft["total_bytes"],
            "files": files,
        }
        write_json(self.bundle / "evidence-manifest.final.json", final)
        manifest_sha = sha256_bytes(
            (self.bundle / "evidence-manifest.final.json").read_bytes()
        )
        (self.bundle / "COMPLETE").write_text(
            f"ANEB_D82_COMPLETE collection_id={COLLECTION_ID} run_id={RUN_ID} "
            f"manifest=evidence-manifest.final.json manifest_sha256={manifest_sha}\n",
            encoding="ascii",
            newline="",
        )

    def prepare_publication(self) -> Path:
        target = self.bundle
        stage = self.root / f"{COLLECTION_ID}.verification-stage"
        stage.mkdir()
        candidate = stage / target.name
        self.bundle.rename(candidate)
        self.bundle = candidate
        return target

    def run_publish_with_payload_mutation(
        self, publish_target: Path
    ) -> tuple[subprocess.CompletedProcess[str], dict[str, object]]:
        if ANDROID_BUILD_TOOLS is None:
            raise RuntimeError("Android build tools are unavailable")
        driver = self.root / "publish-mutation-driver.py"
        values = {
            "scripts": str(self.repository / "scripts"),
            "bundle": str(self.bundle),
            "repository": str(self.repository),
            "build_tools": str(ANDROID_BUILD_TOOLS),
            "target": str(publish_target),
            "remote_host": REMOTE_HOST,
            "known_hosts_sha256": SSH_KNOWN_HOSTS_SHA256,
            "device_policy": str(self.device_policy_path),
            "gh_path": str(self.gh_path),
        }
        driver.write_text(
            "import json\n"
            "from pathlib import Path\n"
            "import sys\n"
            f"values = json.loads({json.dumps(json.dumps(values))})\n"
            "sys.path.insert(0, values['scripts'])\n"
            "import verify_token_quick_evidence_bundle as verifier\n"
            "original = verifier.verify_bundle_unchanged\n"
            "def mutate_then_check(*args, **kwargs):\n"
            "    payload = Path(values['bundle']) / 'device-getprop-final.txt'\n"
            "    payload.write_bytes(payload.read_bytes() + b'\\n')\n"
            "    return original(*args, **kwargs)\n"
            "verifier.verify_bundle_unchanged = mutate_then_check\n"
            "try:\n"
            "    report = verifier.verify_bundle(\n"
            "        Path(values['bundle']), Path(values['repository']),\n"
            "        Path(values['build_tools']),\n"
            "        expected_remote_host=values['remote_host'],\n"
            "        expected_ssh_known_hosts_sha256=values['known_hosts_sha256'],\n"
            "        device_policy_path=Path(values['device_policy']),\n"
            "        gh_path=Path(values['gh_path']),\n"
            "        publish_target=Path(values['target']))\n"
            "    rc = 0\n"
            "except verifier.BundleFailure as error:\n"
            "    report = {'status':'fail','reason_code':error.reason_code}\n"
            "    rc = 1\n"
            "print(json.dumps(report, sort_keys=True, separators=(',', ':')))\n"
            "raise SystemExit(rc)\n",
            encoding="utf-8",
            newline="",
        )
        completed = subprocess.run(
            [sys.executable, str(driver)],
            cwd=self.root,
            text=True,
            capture_output=True,
            check=False,
            timeout=VERIFIER_PROCESS_TIMEOUT_SECONDS,
        )
        return completed, json.loads(completed.stdout)

    def run_publish_with_rename_failure(
        self, publish_target: Path
    ) -> tuple[subprocess.CompletedProcess[str], dict[str, object]]:
        if ANDROID_BUILD_TOOLS is None:
            raise RuntimeError("Android build tools are unavailable")
        driver = self.root / "publish-rename-failure-driver.py"
        values = {
            "scripts": str(self.repository / "scripts"),
            "bundle": str(self.bundle),
            "repository": str(self.repository),
            "build_tools": str(ANDROID_BUILD_TOOLS),
            "target": str(publish_target),
            "remote_host": REMOTE_HOST,
            "known_hosts_sha256": SSH_KNOWN_HOSTS_SHA256,
            "device_policy": str(self.device_policy_path),
            "gh_path": str(self.gh_path),
        }
        driver.write_text(
            "import json\n"
            "from pathlib import Path\n"
            "import sys\n"
            f"values = json.loads({json.dumps(json.dumps(values))})\n"
            "sys.path.insert(0, values['scripts'])\n"
            "import verify_token_quick_evidence_bundle as verifier\n"
            "def fail_rename(*args, **kwargs):\n"
            "    raise OSError('injected atomic rename failure')\n"
            "verifier.atomic_rename_no_replace = fail_rename\n"
            "try:\n"
            "    report = verifier.verify_bundle(\n"
            "        Path(values['bundle']), Path(values['repository']),\n"
            "        Path(values['build_tools']),\n"
            "        expected_remote_host=values['remote_host'],\n"
            "        expected_ssh_known_hosts_sha256=values['known_hosts_sha256'],\n"
            "        device_policy_path=Path(values['device_policy']),\n"
            "        gh_path=Path(values['gh_path']),\n"
            "        publish_target=Path(values['target']))\n"
            "    rc = 0\n"
            "except verifier.BundleFailure as error:\n"
            "    report = {'status':'fail','reason_code':error.reason_code}\n"
            "    rc = 1\n"
            "except Exception:\n"
            "    report = {'status':'fail','reason_code':'internal_verification_error'}\n"
            "    rc = 1\n"
            "print(json.dumps(report, sort_keys=True, separators=(',', ':')))\n"
            "raise SystemExit(rc)\n",
            encoding="utf-8",
            newline="",
        )
        completed = subprocess.run(
            [sys.executable, str(driver)],
            cwd=self.root,
            text=True,
            capture_output=True,
            check=False,
            timeout=VERIFIER_PROCESS_TIMEOUT_SECONDS,
        )
        return completed, json.loads(completed.stdout)

    def run_publish(
        self, publish_target: Path
    ) -> tuple[subprocess.CompletedProcess[str], dict[str, object]]:
        if ANDROID_BUILD_TOOLS is None:
            raise RuntimeError("Android build tools are unavailable")
        completed = subprocess.run(
            [
                sys.executable,
                str(self.repository / SCRIPT.relative_to(ROOT)),
                str(self.bundle),
                "--repository-root",
                str(self.repository),
                "--android-build-tools-dir",
                str(ANDROID_BUILD_TOOLS),
                "--expected-remote-host",
                REMOTE_HOST,
                "--expected-ssh-known-hosts-sha256",
                SSH_KNOWN_HOSTS_SHA256,
                "--device-policy-path",
                str(self.device_policy_path),
                "--gh-path",
                str(self.gh_path),
                "--expected-execution-mode",
                "positive",
                "--publish",
                "--publish-target",
                str(publish_target),
            ],
            cwd=self.root,
            text=True,
            capture_output=True,
            check=False,
            timeout=VERIFIER_PROCESS_TIMEOUT_SECONDS,
        )
        return completed, json.loads(completed.stdout)

    def run(
        self,
        android_build_tools: Path | None = ANDROID_BUILD_TOOLS,
        *,
        expected_remote_host: str = REMOTE_HOST,
        expected_ssh_known_hosts_sha256: str = SSH_KNOWN_HOSTS_SHA256,
        device_policy_path: Path | None = None,
        gh_path: Path | None = None,
        expected_execution_mode: str = "positive",
    ) -> tuple[subprocess.CompletedProcess[str], dict[str, object]]:
        if android_build_tools is None:
            raise RuntimeError("Android build tools are unavailable")
        completed = subprocess.run(
            [
                sys.executable,
                str(self.repository / SCRIPT.relative_to(ROOT)),
                str(self.bundle),
                "--repository-root",
                str(self.repository),
                "--android-build-tools-dir",
                str(android_build_tools),
                "--expected-remote-host",
                expected_remote_host,
                "--expected-ssh-known-hosts-sha256",
                expected_ssh_known_hosts_sha256,
                "--device-policy-path",
                str(device_policy_path or self.device_policy_path),
                "--gh-path",
                str(gh_path or self.gh_path),
                "--expected-execution-mode",
                expected_execution_mode,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=VERIFIER_PROCESS_TIMEOUT_SECONDS,
        )
        report = json.loads(completed.stdout)
        return completed, report


class NegativeBundleFixture(BundleFixture):
    def _write_payload(self) -> None:
        super()._write_payload()
        negative_server_base = "http://127.0.0.1:18765"
        upstream_url = SERVER_BASE + "/api/v1/serverinfo"

        plan = json.loads(
            (self.bundle / "collector-plan.json").read_text(encoding="utf-8")
        )
        plan.update(
            execution_mode="negative_receipt_missing",
            client_server_base=negative_server_base,
            negative_proxy_upstream_url=upstream_url,
            negative_proxy_device_port=18765,
        )
        write_json(self.bundle / "collector-plan.json", plan)

        end_serverinfo = self._serverinfo(4)
        write_json(self.bundle / "end-barrier.json", end_serverinfo)

        audit_case = TokenRunAuditCliTest()
        message_text = audit_case.window([audit_case.capability(run_id=RUN_ID)])
        messages = message_text.rstrip("\n").split("\n")
        records = []
        for index, message in enumerate(messages, 1):
            record = PrepareTokenRunEvidenceCliTest.journal_record(index, message)
            record["__REALTIME_TIMESTAMP"] = str(
                BUNDLE_REMOTE_REALTIME_ANCHOR_USEC + index * 250_000
            )
            records.append(record)
        (self.bundle / "journal.raw.jsonl").write_bytes(
            b"".join(
                (
                    json.dumps(record, sort_keys=True, separators=(",", ":"))
                    + "\n"
                ).encode("utf-8")
                for record in records
            )
        )
        with tempfile.TemporaryDirectory() as derived:
            derived_root = Path(derived)
            args = SimpleNamespace(
                journal=self.bundle / "journal.raw.jsonl",
                pre_start_receipt=self.bundle / "pre-start-receipt.json",
                run_id=RUN_ID,
                start_barrier_id=START_ID,
                end_barrier_id=END_ID,
                message_output=derived_root / "token-run-audit.log",
                derivation_output=derived_root / "journal-derivation.json",
            )
            with contextlib.redirect_stdout(io.StringIO()):
                evidence_helper._derive(args)
            shutil.copyfile(args.message_output, self.bundle / "token-run-audit.log")
            shutil.copyfile(
                args.derivation_output, self.bundle / "journal-derivation.json"
            )
        write_json(
            self.bundle / "request-entry-audit.json",
            audit_verifier.verify_journal(
                (self.bundle / "token-run-audit.log").read_text(encoding="utf-8"),
                run_id=RUN_ID,
                start_barrier_id=START_ID,
                barrier_id=END_ID,
                mode="negative",
                profile_contract="token_multimodal_quick@1.2.1",
            ),
        )

        for suffix in ("", "-wal", "-shm"):
            path = self.bundle / ("aneb-probe.db" + suffix)
            if path.exists():
                path.unlink()
        with tempfile.TemporaryDirectory() as live_directory:
            live_database = Path(live_directory) / "aneb-probe.db"
            connection = create_negative_database(live_database)
            try:
                for suffix in ("", "-wal", "-shm"):
                    shutil.copyfile(
                        Path(str(live_database) + suffix),
                        self.bundle / ("aneb-probe.db" + suffix),
                    )
            finally:
                connection.close()
        room_files = []
        for suffix in ("", "-wal", "-shm"):
            path = self.bundle / ("aneb-probe.db" + suffix)
            room_files.append(
                {
                    "name": path.name,
                    "state": "present",
                    "bytes": path.stat().st_size,
                    "sha256": sha256_bytes(path.read_bytes()),
                }
            )
        write_json(
            self.bundle / "room-copy-inventory.json",
            {
                "schema": "aneb-frozen-room-copy",
                "schema_version": "1.0.0",
                "captured_at_utc": ROOM_COPY_AT,
                "app_process_state": "stopped_before_copy",
                "files": room_files,
            },
        )
        negative_report, result_text = negative_client_verifier.verify(
            self.bundle / "aneb-probe.db",
            inventory=self.bundle / "room-copy-inventory.json",
            run_id=RUN_ID,
            manifest=(
                ROOT
                / "profiles"
                / "published"
                / "token_multimodal_quick"
                / "manifest.sha256"
            ),
            expected_server_base=negative_server_base,
        )
        write_json(self.bundle / "client-db-report.json", negative_report)
        (self.bundle / "client-result.json").write_text(
            result_text, encoding="utf-8", newline=""
        )

        prefix = "1784246400.000  100  101 I AnebProbe: "
        negative_events = (
            f"TOKEN_V2_START run_id={RUN_ID} variant=quick server={negative_server_base}",
            f"TOKEN_V2_RADIO run_id={RUN_ID} status=unavailable samples=0",
            f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
            (
                f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=rejected "
                "reason=receipt_missing detail=contract_rejected"
            ),
            f"TOKEN_V2_END run_id={RUN_ID} status=contract_rejected",
        )
        (self.bundle / "app-logcat.txt").write_text(
            "1784246399.999  100  101 I AnebD82: "
            f"D82_CAPTURE_MARKER nonce={LOCK_NONCE}\n"
            + "".join(prefix + event + "\n" for event in negative_events),
            encoding="utf-8",
            newline="",
        )

        proxy_directory = self.bundle / "negative-proxy"
        proxy_directory.mkdir()
        upstream = self._serverinfo(3)
        upstream_raw = json.dumps(
            upstream,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        filtered = dict(upstream)
        del filtered["execution_capabilities"]
        filtered_raw = json.dumps(
            filtered,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        headers = [["Content-Type", "application/json"]]
        headers_raw = json.dumps(
            headers, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        header_bytes = sum(
            len(name.encode("utf-8")) + len(value.encode("utf-8")) + 4
            for name, value in headers
        )
        peer_sha = "c" * 64
        ca_sha = sha256_bytes(
            (self.repository / TOOL_PATHS["server_ca"]).read_bytes()
        )
        ledger = {
            "schema": "aneb-token-serverinfo-negative-proxy-ledger",
            "schema_version": "1.0.0",
            "counts": {
                "accepted_requests": 1,
                "forbidden_requests": 0,
                "upstream_requests": 1,
            },
            "request": {
                "audit_role": "capability",
                "method": "GET",
                "path": "/api/v1/serverinfo",
                "run_id": RUN_ID,
            },
            "upstream": {
                "body_bytes": len(upstream_raw),
                "header_bytes": header_bytes,
                "status": 200,
                "url": upstream_url,
            },
        }
        receipt = {
            "schema": "aneb-token-serverinfo-negative-proxy-receipt",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "run_id": RUN_ID,
            "upstream_url": upstream_url,
            "upstream_body_bytes": len(upstream_raw),
            "upstream_body_sha256": sha256_bytes(upstream_raw),
            "filtered_body_bytes": len(filtered_raw),
            "filtered_body_sha256": sha256_bytes(filtered_raw),
            "peer_certificate_sha256": peer_sha,
            "ca_file_sha256": ca_sha,
            "evidence_scope": "upstream_fetch_and_filter_only",
            "client_delivery_proven": False,
        }
        (proxy_directory / "upstream-serverinfo.raw").write_bytes(upstream_raw)
        (proxy_directory / "filtered-serverinfo.json").write_bytes(filtered_raw)
        (proxy_directory / "upstream-serverinfo.headers.json").write_bytes(
            headers_raw
        )
        (proxy_directory / "peer-certificate.sha256").write_text(
            peer_sha + "\n", encoding="ascii", newline=""
        )
        (proxy_directory / "request-ledger.json").write_bytes(
            json.dumps(ledger, sort_keys=True, separators=(",", ":")).encode()
        )
        (proxy_directory / "proxy-receipt.json").write_bytes(
            json.dumps(receipt, sort_keys=True, separators=(",", ":")).encode()
        )
        host_port = 45678
        ready = {
            "listen_host": "127.0.0.1",
            "listen_port": host_port,
            "status": "ready",
        }
        passed = {
            "listen_host": "127.0.0.1",
            "listen_port": host_port,
            "reason_code": "ok",
            "run_id": RUN_ID,
            "status": "pass",
        }
        canonical = lambda value: json.dumps(
            value, sort_keys=True, separators=(",", ":")
        ).encode()
        (self.bundle / "negative-proxy.stdout.jsonl").write_bytes(
            canonical(ready) + b"\n" + canonical(passed) + b"\n"
        )
        (self.bundle / "negative-proxy.stderr.txt").write_bytes(b"")
        mapping = f"UsbFfs tcp:18765 tcp:{host_port}\n".encode("ascii")
        (self.bundle / "adb-reverse-preflight.txt").write_bytes(b"\n")
        (self.bundle / "adb-reverse-active.txt").write_bytes(mapping)
        (self.bundle / "adb-reverse-before-remove.txt").write_bytes(mapping)
        (self.bundle / "adb-reverse-final.txt").write_bytes(b"\n")

        cleanup = json.loads(
            (self.bundle / "cleanup-report.json").read_text(encoding="utf-8")
        )
        for key in (
            "negative_reverse_preflight_captured",
            "negative_reverse_mutation_attempted",
            "negative_reverse_remove_attempted",
            "negative_reverse_final_captured",
            "negative_proxy_completed",
            "negative_proxy_stop_attempted",
            "negative_proxy_stop_succeeded",
        ):
            cleanup[key] = True
        write_json(self.bundle / "cleanup-report.json", cleanup)

    def rebuild_manifests(self) -> None:
        super().rebuild_manifests()
        final_path = self.bundle / "evidence-manifest.final.json"
        final = json.loads(final_path.read_text(encoding="utf-8"))
        final["execution_mode"] = "negative_receipt_missing"
        final["evidence_scope"] = "d82_token_quick_contract_rejection_acceptance"
        final["client_result_body_sha256"] = json.loads(
            (self.bundle / "client-db-report.json").read_text(encoding="utf-8")
        )["result_body_sha256"]
        write_json(final_path, final)
        manifest_sha = sha256_bytes(final_path.read_bytes())
        (self.bundle / "COMPLETE").write_text(
            f"ANEB_D82_COMPLETE collection_id={COLLECTION_ID} run_id={RUN_ID} "
            f"manifest=evidence-manifest.final.json manifest_sha256={manifest_sha}\n",
            encoding="ascii",
            newline="",
        )

    def checkpoint_room_sidecars_absent(self) -> None:
        database = self.bundle / "aneb-probe.db"
        connection = sqlite3.connect(database)
        try:
            connection.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchall()
        finally:
            connection.close()
        for suffix in ("-wal", "-shm"):
            sidecar = Path(str(database) + suffix)
            if sidecar.exists():
                sidecar.unlink()
        write_json(
            self.bundle / "room-copy-inventory.json",
            {
                "schema": "aneb-frozen-room-copy",
                "schema_version": "1.0.0",
                "captured_at_utc": ROOM_COPY_AT,
                "app_process_state": "stopped_before_copy",
                "files": [
                    {
                        "name": database.name,
                        "state": "present",
                        "bytes": database.stat().st_size,
                        "sha256": sha256_bytes(database.read_bytes()),
                    },
                    {
                        "name": database.name + "-wal",
                        "state": "absent",
                    },
                    {
                        "name": database.name + "-shm",
                        "state": "absent",
                    },
                ],
            },
        )
        negative_report, result_text = negative_client_verifier.verify(
            database,
            inventory=self.bundle / "room-copy-inventory.json",
            run_id=RUN_ID,
            manifest=(
                ROOT
                / "profiles"
                / "published"
                / "token_multimodal_quick"
                / "manifest.sha256"
            ),
            expected_server_base="http://127.0.0.1:18765",
        )
        write_json(self.bundle / "client-db-report.json", negative_report)
        (self.bundle / "client-result.json").write_text(
            result_text, encoding="utf-8", newline=""
        )
        self.rebuild_manifests()


@unittest.skipUnless(
    APK_INTEGRATION_AVAILABLE,
    "requires the real ANEB Debug APK, Java, and Android Build Tools 35.0.0",
)
class TokenQuickEvidenceBundleVerifierTests(unittest.TestCase):
    def test_publish_mode_atomically_promotes_one_verified_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            publish_target = fixture.prepare_publication()
            completed, report = fixture.run_publish(publish_target)

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual("pass", report["status"])
            self.assertTrue(report["publication"])
            self.assertFalse(fixture.bundle.exists())
            self.assertTrue(publish_target.is_dir())
            self.assertNotIn(str(fixture.root), completed.stdout)

    def test_publish_mode_never_replaces_an_existing_complete_target(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            publish_target = fixture.prepare_publication()
            publish_target.mkdir()
            marker = publish_target / "belongs-to-another-run.txt"
            marker.write_text("preserve\n", encoding="utf-8")
            completed, report = fixture.run_publish(publish_target)

            self.assertEqual(1, completed.returncode)
            self.assertEqual("publish_target_exists", report["reason_code"])
            self.assertEqual("preserve\n", marker.read_text(encoding="utf-8"))
            self.assertTrue(fixture.bundle.is_dir())

    def test_publish_mode_rejects_a_target_outside_the_evidence_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            expected_target = fixture.prepare_publication()
            outside = fixture.root / "outside"
            outside.mkdir()
            publish_target = outside / expected_target.name
            completed, report = fixture.run_publish(publish_target)

            self.assertEqual(1, completed.returncode)
            self.assertEqual("publish_boundary_invalid", report["reason_code"])
            self.assertFalse(publish_target.exists())
            self.assertTrue(fixture.bundle.is_dir())

    def test_publish_mode_rejects_a_reparse_target_parent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            evidence_root = base / "evidence"
            evidence_root.mkdir()
            fixture = BundleFixture(evidence_root)
            expected_target = fixture.prepare_publication()
            alias = base / "evidence-alias"
            try:
                alias.symlink_to(evidence_root, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"symbolic links unavailable: {error}")
            publish_target = alias / expected_target.name
            completed, report = fixture.run_publish(publish_target)

            self.assertEqual(1, completed.returncode)
            self.assertEqual("publish_boundary_invalid", report["reason_code"])
            self.assertFalse(expected_target.exists())
            self.assertTrue(fixture.bundle.is_dir())

    def test_publish_mode_fails_closed_when_another_publisher_holds_the_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            publish_target = fixture.prepare_publication()
            with held_publish_lock(fixture.root):
                completed, report = fixture.run_publish(publish_target)

            self.assertEqual(1, completed.returncode)
            self.assertEqual("publish_lock_unavailable", report["reason_code"])
            self.assertFalse(publish_target.exists())
            self.assertTrue(fixture.bundle.is_dir())

    def test_publish_mode_reports_atomic_rename_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            publish_target = fixture.prepare_publication()
            completed, report = fixture.run_publish_with_rename_failure(
                publish_target
            )

            self.assertEqual(1, completed.returncode, completed.stderr)
            self.assertEqual("publish_failed", report["reason_code"])
            self.assertFalse(publish_target.exists())
            self.assertTrue(fixture.bundle.is_dir())

    def test_payload_change_after_semantics_cannot_be_published(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            publish_target = fixture.prepare_publication()
            completed, report = fixture.run_publish_with_payload_mutation(
                publish_target
            )

            self.assertEqual(1, completed.returncode, completed.stderr)
            self.assertEqual("fail", report["status"])
            self.assertEqual("bundle_changed_before_publish", report["reason_code"])
            self.assertFalse(publish_target.exists())
            self.assertTrue(fixture.bundle.exists())

    def test_complete_cross_bound_bundle_is_revalidated_from_raw_sources(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            completed, report = fixture.run()

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual("positive", report["execution_mode"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertTrue(report["journal_derivation_recomputed"])
        self.assertTrue(report["request_entry_audit_recomputed"])
        self.assertTrue(report["client_room_result_recomputed"])
        self.assertTrue(report["apk_identity_reverified"])
        self.assertTrue(report["evidence_time_chain_reverified"])
        self.assertTrue(report["raw_state_reverified"])
        self.assertEqual(26, report["raw_files_verified"])
        self.assertEqual(26, report["raw_state_files_verified"])
        self.assertEqual(6, report["device_identity_raw_files_verified"])
        self.assertEqual(32, report["raw_files_verified_total"])
        self.assertEqual(REMOTE_HOST, report["remote_host"])
        self.assertEqual(
            SSH_KNOWN_HOSTS_SHA256, report["ssh_known_hosts_sha256"]
        )
        self.assertTrue(report["candidate_provenance_reverified"])
        self.assertEqual("2.96.0", report["gh_version"])
        self.assertEqual(
            fixture.gh_sha256,
            report["gh_executable_sha256"],
        )
        self.assertEqual(
            fixture.attestation_bundle_sha256,
            report["attestation_bundle_sha256"],
        )
        self.assertEqual(fixture.device_report, report["device_identity"])
        self.assertNotIn(DEVICE_SERIAL, json.dumps(report, sort_keys=True))
        self.assertEqual(
            {
                "package_name": "com.aneb.probe.codex",
                "signer_sha256": fixture.signer_sha,
                "version_code": 44,
                "version_name": "0.5.12-codex",
            },
            report["verified_apk_identity"],
        )
        self.assertEqual(20, report["business_counts"]["echo"])
        self.assertEqual(3, report["business_counts"]["token_sim"])
        self.assertEqual(1, report["business_counts"]["download"])

    def test_self_consistent_manifest_cannot_hide_workflow_decision_tampering(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            write_json(
                fixture.bundle / "workflow-decision.json",
                {
                    "schema": "aneb-quick-workflow-decision@1.0.0",
                    "publish_eligible": False,
                    "primary_failure": None,
                    "cleanup_failures": [],
                },
            )
            fixture.rebuild_manifests()

            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode, completed.stderr)
        self.assertEqual("workflow_trace_evidence_invalid", report["reason_code"])

    def test_manifest_execution_mode_cannot_be_verified_as_another_mode(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            completed, report = fixture.run(
                expected_execution_mode="negative_receipt_missing"
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual("execution_mode_mismatch", report["reason_code"])

    def test_complete_receipt_missing_bundle_revalidates_all_negative_sources(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NegativeBundleFixture(Path(temporary))
            completed, report = fixture.run(
                expected_execution_mode="negative_receipt_missing"
            )

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("pass", report["status"])
        self.assertEqual("negative_receipt_missing", report["execution_mode"])
        self.assertEqual("receipt_missing", report["negative_reason_code"])
        self.assertIs(False, report["client_delivery_proven"])
        self.assertTrue(report["negative_proxy_evidence_recomputed"])
        self.assertEqual(12, report["negative_proxy_raw_files_verified"])
        self.assertEqual(
            {"echo": 0, "token_sim": 0, "download": 0},
            report["business_counts"],
        )
        self.assertEqual(0, report["typed_metrics_verified"])
        self.assertEqual(0, report["envelope_metrics_verified"])
        self.assertEqual(0, report["successful_task_count"])

    def test_negative_bundle_accepts_checkpointed_room_sidecars_both_absent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NegativeBundleFixture(Path(temporary))
            fixture.checkpoint_room_sidecars_absent()

            completed, report = fixture.run(
                expected_execution_mode="negative_receipt_missing"
            )

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("pass", report["status"])

    def test_negative_bundle_rejects_only_one_room_sidecar_absent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NegativeBundleFixture(Path(temporary))
            inventory_path = fixture.bundle / "room-copy-inventory.json"
            inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
            wal = fixture.bundle / "aneb-probe.db-wal"
            wal.unlink()
            inventory["files"][1] = {
                "name": "aneb-probe.db-wal",
                "state": "absent",
            }
            write_json(inventory_path, inventory)
            fixture.rebuild_manifests()

            completed, report = fixture.run(
                expected_execution_mode="negative_receipt_missing"
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual("room_copy_inventory_invalid", report["reason_code"])

    def test_negative_bundle_rejects_forged_absent_sidecars_that_exist(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NegativeBundleFixture(Path(temporary))
            inventory_path = fixture.bundle / "room-copy-inventory.json"
            inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
            inventory["files"][1:] = [
                {"name": "aneb-probe.db-wal", "state": "absent"},
                {"name": "aneb-probe.db-shm", "state": "absent"},
            ]
            write_json(inventory_path, inventory)
            fixture.rebuild_manifests()

            completed, report = fixture.run(
                expected_execution_mode="negative_receipt_missing"
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual("room_copy_inventory_invalid", report["reason_code"])

    def test_negative_bundle_rejects_absent_sidecar_with_zero_null_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NegativeBundleFixture(Path(temporary))
            fixture.checkpoint_room_sidecars_absent()
            inventory_path = fixture.bundle / "room-copy-inventory.json"
            inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
            inventory["files"][1].update(bytes=0, sha256=None)
            write_json(inventory_path, inventory)
            fixture.rebuild_manifests()

            completed, report = fixture.run(
                expected_execution_mode="negative_receipt_missing"
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual("room_copy_inventory_invalid", report["reason_code"])

    def test_negative_bundle_is_rejected_by_the_default_positive_cli_mode(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NegativeBundleFixture(Path(temporary))
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("execution_mode_mismatch", report["reason_code"])

    def test_self_consistent_negative_manifest_cannot_hide_proxy_over_filtering(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NegativeBundleFixture(Path(temporary))
            filtered_path = (
                fixture.bundle / "negative-proxy" / "filtered-serverinfo.json"
            )
            filtered = json.loads(filtered_path.read_text(encoding="utf-8"))
            del filtered["h3_enabled"]
            filtered_raw = json.dumps(
                filtered, sort_keys=True, separators=(",", ":")
            ).encode()
            filtered_path.write_bytes(filtered_raw)
            receipt_path = fixture.bundle / "negative-proxy" / "proxy-receipt.json"
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            receipt["filtered_body_bytes"] = len(filtered_raw)
            receipt["filtered_body_sha256"] = sha256_bytes(filtered_raw)
            receipt_path.write_bytes(
                json.dumps(receipt, sort_keys=True, separators=(",", ":")).encode()
            )
            fixture.rebuild_manifests()
            completed, report = fixture.run(
                expected_execution_mode="negative_receipt_missing"
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "negative_proxy_filtered_serverinfo_mismatch", report["reason_code"]
        )

    def test_self_consistent_negative_manifest_cannot_replace_room_reason(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NegativeBundleFixture(Path(temporary))
            report_path = fixture.bundle / "client-db-report.json"
            stored = json.loads(report_path.read_text(encoding="utf-8"))
            stored["negative_reason_code"] = "receipt_digest_mismatch"
            write_json(report_path, stored)
            fixture.rebuild_manifests()
            completed, report = fixture.run(
                expected_execution_mode="negative_receipt_missing"
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual("client_room_revalidation_failed", report["reason_code"])

    def test_payload_tampering_is_rejected_before_semantic_revalidation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            with (fixture.bundle / "client-result.json").open("ab") as stream:
                stream.write(b"\n")
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("fail", report["status"])
        self.assertEqual("payload_digest_mismatch", report["reason_code"])

    def test_expected_remote_host_must_match_plan_receipt_and_final_source(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            completed, report = fixture.run(
                expected_remote_host="198.51.100.44"
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual("remote_host_mismatch", report["reason_code"])

    def test_expected_known_hosts_digest_must_match_frozen_external_input(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            completed, report = fixture.run(
                expected_ssh_known_hosts_sha256="f" * 64
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual("ssh_known_hosts_mismatch", report["reason_code"])

    def test_external_device_policy_replacement_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            fixture.device_policy_path.write_bytes(
                fixture.device_policy_path.read_bytes() + b" "
            )
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("device_policy_mismatch", report["reason_code"])

    def test_device_boot_change_between_preflight_and_final_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            (fixture.bundle / "device-boot-id-final.txt").write_text(
                "87654321-4321-4cba-8fed-ba0987654321\n",
                encoding="utf-8",
                newline="",
            )
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("device_boot_id_mismatch", report["reason_code"])

    def test_device_fingerprint_change_between_preflight_and_final_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            path = fixture.bundle / "device-getprop-final.txt"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    DEVICE_PROPERTIES["ro.build.fingerprint"],
                    "HUAWEI/ELS-N29/HWELS:13/OTHER/2:user/release-keys",
                ),
                encoding="utf-8",
                newline="",
            )
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("device_properties_mismatch", report["reason_code"])

    def test_candidate_attestation_from_wrong_workflow_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            fixture.write_fake_gh(
                workflow_uri=(
                    "https://github.com/lucassu2012/ANEB_GPT/"
                    ".github/workflows/other.yml@" + CI_SOURCE_REF
                )
            )
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("attestation_policy_mismatch", report["reason_code"])

    def test_candidate_attestation_for_wrong_commit_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            fixture.write_fake_gh(source_commit="0" * 40)
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("attestation_policy_mismatch", report["reason_code"])

    def test_candidate_attestation_for_wrong_apk_digest_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            fixture.write_fake_gh(subject_digest="0" * 64)
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("attestation_subject_mismatch", report["reason_code"])

    def test_attested_candidate_apk_must_equal_pulled_installed_apk(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            fixture.candidate_apk.write_bytes(
                fixture.candidate_apk.read_bytes() + b"candidate-drift"
            )
            manifest_path = fixture.candidate_directory / "build-manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["apk"]["sha256"] = sha256_bytes(
                fixture.candidate_apk.read_bytes()
            )
            manifest["apk"]["size_bytes"] = fixture.candidate_apk.stat().st_size
            write_json(manifest_path, manifest)
            fixture.rebuild_candidate_checksums()
            fixture.write_fake_gh()
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("candidate_apk_identity_mismatch", report["reason_code"])

    def test_payload_mutated_after_semantic_checks_is_rejected_before_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            final_path = fixture.bundle / "evidence-manifest.final.json"
            final_raw = final_path.read_bytes()
            final = json.loads(final_raw.decode("utf-8"))
            entries, _ = bundle_verifier.verify_inventory(fixture.bundle, final)
            with (fixture.bundle / "client-result.json").open("ab") as stream:
                stream.write(b"\n")
            with self.assertRaises(bundle_verifier.BundleFailure) as raised:
                bundle_verifier.verify_bundle_unchanged(
                    fixture.bundle,
                    final,
                    final_raw,
                    entries,
                )

        self.assertEqual("bundle_changed_during_verification", raised.exception.reason_code)

    def test_self_consistent_manifest_cannot_treat_arbitrary_bytes_as_an_apk(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            apk_path = fixture.bundle / "installed-base.apk"
            apk_path.write_bytes(b"not an Android application package\n")
            fixture.apk_sha = sha256_bytes(apk_path.read_bytes())
            fixture.signer_sha = "6" * 64
            preflight_path = fixture.bundle / "device-preflight.json"
            preflight = json.loads(preflight_path.read_text(encoding="utf-8"))
            preflight["apk_sha256"] = fixture.apk_sha
            preflight["signer_sha256"] = fixture.signer_sha
            write_json(preflight_path, preflight)
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("fail", report["status"])
        self.assertEqual("apk_archive_invalid", report["reason_code"])

    def test_self_consistent_json_cannot_replace_the_apk_signer_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            fixture.signer_sha = "6" * 64
            preflight_path = fixture.bundle / "device-preflight.json"
            preflight = json.loads(preflight_path.read_text(encoding="utf-8"))
            preflight["signer_sha256"] = fixture.signer_sha
            write_json(preflight_path, preflight)
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("fail", report["status"])
        self.assertEqual("apk_identity_mismatch", report["reason_code"])

    def test_unavailable_pinned_android_tools_have_a_machine_reason(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            unavailable = Path(temporary) / "missing" / ANDROID_BUILD_TOOLS_VERSION
            completed, report = fixture.run(unavailable)

        self.assertEqual(1, completed.returncode)
        self.assertEqual("fail", report["status"])
        self.assertEqual("android_build_tools_unavailable", report["reason_code"])

    def test_clean_json_cannot_hide_a_bound_aneb_accessibility_service(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            accessibility = fixture.bundle / "device-accessibility-final.txt"
            accessibility.write_bytes(
                b"ANEB_D82_DEVICE_ACCESSIBILITY_FINAL_V1\n"
                b"enabled_accessibility_services_command=settings get secure "
                b"enabled_accessibility_services\n"
                b"enabled_accessibility_services_output_begin\n"
                b"null\n"
                b"enabled_accessibility_services_output_end\n"
                b"dumpsys_accessibility_command=dumpsys accessibility\n"
                b"dumpsys_accessibility_output_begin\n"
                b"  Bound services: {Service[label=ANEB, packageName="
                b"com.aneb.probe.codex]}\n"
                b"dumpsys_accessibility_output_end\n"
            )
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("fail", report["status"])
        self.assertEqual(
            "raw_accessibility_final_invalid", report["reason_code"]
        )

    def test_clean_json_cannot_hide_an_enabled_aneb_accessibility_service(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            accessibility = fixture.bundle / "device-accessibility-final.txt"
            accessibility.write_bytes(
                b"ANEB_D82_DEVICE_ACCESSIBILITY_FINAL_V1\n"
                b"enabled_accessibility_services_command=settings get secure "
                b"enabled_accessibility_services\n"
                b"enabled_accessibility_services_output_begin\n"
                b"com.aneb.probe/.accessibility.ProbeAccessibilityService\n"
                b"enabled_accessibility_services_output_end\n"
                b"dumpsys_accessibility_command=dumpsys accessibility\n"
                b"dumpsys_accessibility_output_begin\n"
                b"  bound services:{}\n"
                b"dumpsys_accessibility_output_end\n"
            )
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("fail", report["status"])
        self.assertEqual(
            "raw_accessibility_final_invalid", report["reason_code"]
        )

    def test_self_consistent_manifest_cannot_reverse_the_evidence_time_chain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            final_path = fixture.bundle / "device-final-clean.json"
            final_state = json.loads(final_path.read_text(encoding="utf-8"))
            final_state["captured_at_utc"] = "2026-07-16T23:59:50.0000000Z"
            write_json(final_path, final_state)
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("fail", report["status"])
        self.assertEqual("time_sequence_invalid", report["reason_code"])

    def test_self_consistent_manifest_cannot_hide_a_changed_room_database(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            database_path = fixture.bundle / "aneb-probe.db"
            with database_path.open("ab") as stream:
                stream.write(b"changed")
            inventory_path = fixture.bundle / "room-copy-inventory.json"
            inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
            inventory["files"][0]["bytes"] = database_path.stat().st_size
            inventory["files"][0]["sha256"] = sha256_bytes(database_path.read_bytes())
            write_json(inventory_path, inventory)
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("client_room_revalidation_failed", report["reason_code"])

    def test_self_consistent_manifest_cannot_hide_replayed_or_changed_journal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            raw = (fixture.bundle / "journal.raw.jsonl").read_text(encoding="utf-8")
            first, rest = raw.split("\n", 1)
            (fixture.bundle / "journal.raw.jsonl").write_text(
                first + "\n" + first + "\n" + rest,
                encoding="utf-8",
                newline="",
            )
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("journal_derivation_revalidation_failed", report["reason_code"])

    def test_tooling_provenance_must_match_committed_source_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            manifest_path = fixture.bundle / "evidence-manifest.final.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["tooling_provenance"]["files"]["audit_verifier"] = "0" * 64
            write_json(manifest_path, manifest)
            manifest_sha = sha256_bytes(manifest_path.read_bytes())
            (fixture.bundle / "COMPLETE").write_text(
                f"ANEB_D82_COMPLETE collection_id={COLLECTION_ID} run_id={RUN_ID} "
                f"manifest=evidence-manifest.final.json manifest_sha256={manifest_sha}\n",
                encoding="ascii",
                newline="",
            )
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("tooling_provenance_mismatch", report["reason_code"])

    def test_self_consistent_manifest_cannot_hide_serverinfo_chronology_change(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            start_path = fixture.bundle / "start-barrier.json"
            start = json.loads(start_path.read_text(encoding="utf-8"))
            identity = json.loads(
                (fixture.bundle / "identity-serverinfo.json").read_text(encoding="utf-8")
            )
            start["srv_ts_us"] = identity["srv_ts_us"]
            write_json(start_path, start)
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("serverinfo_sequence_invalid", report["reason_code"])

    def test_serverinfo_rejects_bare_profile_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            digests = {}
            for name in (
                "identity-serverinfo.json",
                "start-barrier.json",
                "end-barrier.json",
            ):
                path = fixture.bundle / name
                serverinfo = json.loads(path.read_text(encoding="utf-8"))
                profile = serverinfo["execution_capabilities"]["validated_profiles"][0]
                profile["profile_sha256"] = profile["profile_sha256"].removeprefix(
                    "sha256:"
                )
                write_json(path, serverinfo)
                label = {
                    "identity-serverinfo.json": "identity",
                    "start-barrier.json": "start_barrier",
                    "end-barrier.json": "end_barrier",
                }[name]
                digests[label] = sha256_bytes(path.read_bytes())
            final = json.loads(
                (fixture.bundle / "evidence-manifest.final.json").read_text(
                    encoding="utf-8"
                )
            )
            final["source"]["serverinfo_body_sha256"] = digests
            receipt = json.loads(
                (fixture.bundle / "pre-start-receipt.json").read_text(encoding="utf-8")
            )
            receipt["serverinfo_body_sha256"] = digests["identity"]

            with self.assertRaisesRegex(
                bundle_verifier.BundleFailure, "^serverinfo_response_invalid$"
            ):
                bundle_verifier.verify_serverinfo(fixture.bundle, final, receipt)

    def test_self_consistent_manifest_cannot_hide_unclean_final_device(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            final_path = fixture.bundle / "device-final-clean.json"
            final_state = json.loads(final_path.read_text(encoding="utf-8"))
            final_state["active_vpn"] = True
            write_json(final_path, final_state)
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("device_cleanup_evidence_invalid", report["reason_code"])

    def test_self_consistent_manifest_cannot_hide_room_copy_inventory_change(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = BundleFixture(Path(temporary))
            inventory_path = fixture.bundle / "room-copy-inventory.json"
            inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
            inventory["files"][0]["sha256"] = "0" * 64
            write_json(inventory_path, inventory)
            fixture.rebuild_manifests()
            completed, report = fixture.run()

        self.assertEqual(1, completed.returncode)
        self.assertEqual("room_copy_inventory_invalid", report["reason_code"])


if __name__ == "__main__":
    unittest.main()
