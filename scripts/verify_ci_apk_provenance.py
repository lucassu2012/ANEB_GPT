#!/usr/bin/env python3
"""Verify that an ANEB Debug APK came from the approved GitHub CI workflow."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import threading
from typing import Any, NoReturn, Sequence
import unicodedata
import zipfile


REPORT_SCHEMA = "aneb-ci-apk-provenance-report"
REPORT_VERSION = "1.0.0"
EXPECTED_REPOSITORY = "lucassu2012/ANEB_GPT"
EXPECTED_REPOSITORY_URI = f"https://github.com/{EXPECTED_REPOSITORY}"
EXPECTED_WORKFLOW = "lucassu2012/ANEB_GPT/.github/workflows/ci.yml"
EXPECTED_PREDICATE_TYPE = "https://slsa.dev/provenance/v1"
EXPECTED_PACKAGE = "com.aneb.probe.codex"
EXPECTED_CONTRACT = "aneb-debug-candidate-v1"
EXPECTED_OIDC_ISSUER = "https://token.actions.githubusercontent.com"
INSTALL_NOTES_NAME = "ANEB-安装说明.txt"
MAX_APK_BYTES = 512 * 1024 * 1024
MAX_MANIFEST_BYTES = 128 * 1024
MAX_INSTALL_NOTES_BYTES = 128 * 1024
MAX_CHECKSUM_BYTES = 128 * 1024
MAX_BUNDLE_BYTES = 16 * 1024 * 1024
MAX_GH_STDOUT_BYTES = 8 * 1024 * 1024
MAX_GH_STDERR_BYTES = 512 * 1024
MAX_GH_VERSION_BYTES = 64 * 1024
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_VERSION_NAME_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-codex$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
SOURCE_REF_RE = re.compile(
    r"^refs/heads/(?:main|codex/[A-Za-z0-9](?:[A-Za-z0-9._/-]{0,198}[A-Za-z0-9])?)$"
)
RUN_URL_RE = re.compile(
    r"^https://github\.com/lucassu2012/ANEB_GPT/actions/runs/([1-9][0-9]{0,19})$"
)
GH_VERSION_RE = re.compile(r"^gh version ([0-9]+\.[0-9]+\.[0-9]+)(?:[-+][^ ]+)?(?: |$)")
CHECKSUM_RE = re.compile(r"^([0-9a-fA-F]{64})  ([^\r\n]+)$")


class ProvenanceVerificationFailure(ValueError):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


class DuplicateJsonKey(ValueError):
    pass


class NonstandardJsonConstant(ValueError):
    pass


def fail(reason_code: str) -> NoReturn:
    raise ProvenanceVerificationFailure(reason_code)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(key)
        result[key] = value
    return result


def _reject_json_constant(value: str) -> NoReturn:
    raise NonstandardJsonConstant(value)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        fail("candidate_file_invalid")
    return digest.hexdigest()


def _is_reparse(path: Path) -> bool:
    try:
        info = path.lstat()
    except OSError:
        return True
    if stat.S_ISLNK(info.st_mode):
        return True
    attributes = getattr(info, "st_file_attributes", 0)
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    return bool(attributes & reparse_flag)


def _nonreparse_lexical_path(path: Path, reason: str) -> Path:
    try:
        absolute = Path(os.path.abspath(os.fspath(path)))
    except (OSError, TypeError, ValueError):
        fail(reason)
    for component in reversed((absolute, *absolute.parents)):
        if not os.path.lexists(component):
            continue
        if _is_reparse(component):
            fail(reason)
    return absolute


def _regular_file(path: Path, *, maximum: int, reason: str) -> bytes:
    try:
        if _is_reparse(path) or not path.is_file():
            fail(reason)
        size = path.stat().st_size
        if size <= 0 or size > maximum:
            fail(reason)
        with path.open("rb") as stream:
            raw = stream.read(maximum + 1)
    except ProvenanceVerificationFailure:
        raise
    except OSError:
        fail(reason)
    if len(raw) != size or len(raw) > maximum:
        fail(reason)
    return raw


def _strict_json(raw: bytes, reason: str) -> Any:
    try:
        text = raw.decode("utf-8")
        return json.loads(
            text,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_json_constant,
        )
    except (
        UnicodeError,
        json.JSONDecodeError,
        DuplicateJsonKey,
        NonstandardJsonConstant,
    ):
        fail(reason)


def _dict(value: Any, reason: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(reason)
    return value


def _list(value: Any, reason: str) -> list[Any]:
    if not isinstance(value, list):
        fail(reason)
    return value


def _validate_apk_archive(path: Path) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) < 2 or len(entries) > 100_000:
                fail("apk_archive_invalid")
            names = {entry.filename for entry in entries}
            if "AndroidManifest.xml" not in names or not any(
                re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", name) for name in names
            ):
                fail("apk_archive_invalid")
            if any(
                name.startswith(("/", "\\"))
                or ".." in Path(name.replace("\\", "/")).parts
                for name in names
            ):
                fail("apk_archive_invalid")
    except ProvenanceVerificationFailure:
        raise
    except (OSError, zipfile.BadZipFile):
        fail("apk_archive_invalid")


def _load_manifest(
    candidate: Path,
    expected_source_commit: str,
    expected_version_name: str,
    expected_version_code: int,
) -> tuple[dict[str, Any], bytes]:
    raw = _regular_file(
        candidate / "build-manifest.json",
        maximum=MAX_MANIFEST_BYTES,
        reason="build_manifest_invalid",
    )
    manifest = _dict(_strict_json(raw, "build_manifest_invalid"), "build_manifest_invalid")
    if set(manifest) != {
        "contract_version",
        "artifact_kind",
        "public_release",
        "source",
        "apk",
        "verification",
    }:
        fail("build_manifest_invalid")
    source = _dict(manifest["source"], "build_manifest_invalid")
    apk = _dict(manifest["apk"], "build_manifest_invalid")
    verification = _dict(manifest["verification"], "build_manifest_invalid")
    manifest_commit = source.get("git_sha")
    if not isinstance(manifest_commit, str) or COMMIT_RE.fullmatch(manifest_commit) is None:
        fail("build_manifest_invalid")
    if manifest_commit != expected_source_commit:
        fail("source_commit_mismatch")
    if (
        manifest["contract_version"] != EXPECTED_CONTRACT
        or manifest["artifact_kind"] != "debug_non_release"
        or manifest["public_release"] is not False
        or set(source) != {"git_sha", "git_ref", "workflow_run_url"}
        or not isinstance(source.get("git_ref"), str)
        or SOURCE_REF_RE.fullmatch(source["git_ref"]) is None
        or ".." in source["git_ref"]
        or "//" in source["git_ref"]
        or not isinstance(source.get("workflow_run_url"), str)
        or RUN_URL_RE.fullmatch(source["workflow_run_url"]) is None
        or set(apk)
        != {
            "file_name",
            "sha256",
            "size_bytes",
            "package_id",
            "version_name",
            "version_code",
            "min_sdk",
            "target_sdk",
            "signer_dn",
            "signer_sha256",
        }
        or not isinstance(apk.get("file_name"), str)
        or re.fullmatch(r"ANEB-Probe-[A-Za-z0-9._-]+-debug\.apk", apk["file_name"])
        is None
        or Path(apk["file_name"]).name != apk["file_name"]
        or not isinstance(apk.get("sha256"), str)
        or SHA256_RE.fullmatch(apk["sha256"].lower()) is None
        or type(apk.get("size_bytes")) is not int
        or not 0 < apk["size_bytes"] <= MAX_APK_BYTES
        or apk.get("package_id") != EXPECTED_PACKAGE
        or not isinstance(apk.get("version_name"), str)
        or type(apk.get("version_code")) is not int
        or type(apk.get("min_sdk")) is not int
        or type(apk.get("target_sdk")) is not int
        or not isinstance(apk.get("signer_dn"), str)
        or not isinstance(apk.get("signer_sha256"), str)
        or SHA256_RE.fullmatch(apk["signer_sha256"].lower()) is None
        or set(verification)
        != {
            "zip_integrity",
            "gradle_apk_identity_match",
            "apksigner_verification",
            "debug_boundary",
        }
        or set(verification.values()) != {"pass"}
    ):
        fail("build_manifest_invalid")
    if (
        apk["version_name"] != expected_version_name
        or apk["version_code"] != expected_version_code
    ):
        fail("apk_version_mismatch")
    return manifest, raw


def _verify_checksum_inventory(candidate: Path) -> tuple[bytes, dict[str, str]]:
    raw = _regular_file(
        candidate / "checksums.sha256",
        maximum=MAX_CHECKSUM_BYTES,
        reason="checksums_invalid",
    )
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        fail("checksums_invalid")
    if not text.endswith("\n") or "\r" in text:
        fail("checksums_invalid")
    entries: dict[str, str] = {}
    for line in text.splitlines():
        match = CHECKSUM_RE.fullmatch(line)
        if match is None:
            fail("checksums_invalid")
        name = match.group(2)
        if (
            name in entries
            or Path(name).name != name
            or name == "checksums.sha256"
            or name in {".", ".."}
            or unicodedata.normalize("NFC", name) != name
            or any(ord(character) < 32 or ord(character) == 127 for character in name)
        ):
            fail("checksums_invalid")
        entries[name] = match.group(1).lower()
    try:
        actual_names = {
            path.name
            for path in candidate.iterdir()
            if path.name != "checksums.sha256"
        }
        if any(
            not path.is_file() or _is_reparse(path) for path in candidate.iterdir()
        ):
            fail("candidate_file_set_invalid")
    except ProvenanceVerificationFailure:
        raise
    except OSError:
        fail("candidate_file_set_invalid")
    if set(entries) != actual_names:
        fail("checksums_invalid")
    for name, digest in entries.items():
        if _sha256_file(candidate / name) != digest:
            fail("checksums_mismatch")
    return raw, entries


def _read_stream_bounded(
    stream: Any,
    maximum: int,
    result: bytearray,
    overflow: threading.Event,
    process: subprocess.Popen[bytes],
) -> None:
    try:
        while True:
            chunk = stream.read(64 * 1024)
            if not chunk:
                return
            remaining = maximum + 1 - len(result)
            if remaining > 0:
                result.extend(chunk[:remaining])
            if len(result) > maximum or len(chunk) > remaining:
                overflow.set()
                try:
                    process.kill()
                except OSError:
                    pass
                return
    finally:
        stream.close()


def _run_bounded(
    command: Sequence[str],
    *,
    timeout_seconds: int,
    stdout_limit: int,
    stderr_limit: int,
    timeout_reason: str,
    output_reason: str,
) -> tuple[int, bytes, bytes]:
    try:
        process = subprocess.Popen(
            list(command),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=False,
            close_fds=os.name != "nt",
        )
    except OSError:
        fail("gh_launch_failed")
    assert process.stdout is not None and process.stderr is not None
    stdout = bytearray()
    stderr = bytearray()
    overflow = threading.Event()
    readers = [
        threading.Thread(
            target=_read_stream_bounded,
            args=(process.stdout, stdout_limit, stdout, overflow, process),
            daemon=True,
        ),
        threading.Thread(
            target=_read_stream_bounded,
            args=(process.stderr, stderr_limit, stderr, overflow, process),
            daemon=True,
        ),
    ]
    for reader in readers:
        reader.start()
    try:
        return_code = process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        try:
            process.kill()
        except OSError:
            pass
        process.wait(timeout=5)
        for reader in readers:
            reader.join(timeout=5)
        fail(timeout_reason)
    for reader in readers:
        reader.join(timeout=5)
    if any(reader.is_alive() for reader in readers):
        fail(output_reason)
    if overflow.is_set():
        fail(output_reason)
    return return_code, bytes(stdout), bytes(stderr)


def _verify_policy_output(
    raw: bytes,
    *,
    expected_subjects: dict[str, str],
    source_commit: str,
    source_ref: str,
    workflow_run_url: str,
) -> dict[str, Any]:
    value = _strict_json(raw, "gh_output_invalid")
    results = _list(value, "gh_output_invalid")
    if len(results) != 1:
        fail("attestation_result_invalid")
    item = _dict(results[0], "attestation_result_invalid")
    if set(item) != {"attestation", "verificationResult"} or not isinstance(
        item.get("attestation"), dict
    ):
        fail("attestation_result_invalid")
    verification = _dict(item["verificationResult"], "attestation_result_invalid")
    statement = _dict(verification.get("statement"), "attestation_result_invalid")
    subjects = _list(statement.get("subject"), "attestation_result_invalid")
    if (
        statement.get("_type") not in {
            "https://in-toto.io/Statement/v1",
            "https://in-toto.io/Statement/v0.1",
        }
        or statement.get("predicateType") != EXPECTED_PREDICATE_TYPE
        or len(subjects) != len(expected_subjects)
    ):
        fail("attestation_subject_mismatch")
    actual_subjects: dict[str, str] = {}
    for value in subjects:
        subject = _dict(value, "attestation_subject_mismatch")
        digest = _dict(subject.get("digest"), "attestation_subject_mismatch")
        name = subject.get("name")
        sha256_value = digest.get("sha256")
        if (
            set(subject) != {"name", "digest"}
            or not isinstance(name, str)
            or name in actual_subjects
            or set(digest) != {"sha256"}
            or not isinstance(sha256_value, str)
            or SHA256_RE.fullmatch(sha256_value.lower()) is None
        ):
            fail("attestation_subject_mismatch")
        actual_subjects[name] = sha256_value.lower()
    if actual_subjects != expected_subjects:
        fail("attestation_subject_mismatch")
    signature = _dict(verification.get("signature"), "attestation_policy_mismatch")
    certificate = _dict(signature.get("certificate"), "attestation_policy_mismatch")
    expected_workflow_uri = (
        f"https://github.com/{EXPECTED_WORKFLOW}@{source_ref}"
    )
    required = {
        "issuer": EXPECTED_OIDC_ISSUER,
        "subjectAlternativeName": expected_workflow_uri,
        "buildSignerURI": expected_workflow_uri,
        "buildSignerDigest": source_commit,
        "runnerEnvironment": "github-hosted",
        "sourceRepositoryURI": EXPECTED_REPOSITORY_URI,
        "sourceRepositoryDigest": source_commit,
        "sourceRepositoryRef": source_ref,
        "buildConfigURI": expected_workflow_uri,
        "buildConfigDigest": source_commit,
    }
    certificate_issuer = certificate.get("certificateIssuer")
    if (
        not isinstance(certificate_issuer, str)
        or not certificate_issuer
        or any(certificate.get(key) != expected for key, expected in required.items())
    ):
        fail("attestation_policy_mismatch")
    run_invocation = certificate.get("runInvocationURI")
    if (
        not isinstance(run_invocation, str)
        or re.fullmatch(re.escape(workflow_run_url) + r"/attempts/[1-9][0-9]*", run_invocation)
        is None
    ):
        fail("attestation_policy_mismatch")
    timestamps = _list(
        verification.get("verifiedTimestamps"), "attestation_policy_mismatch"
    )
    if not timestamps or any(not isinstance(value, dict) for value in timestamps):
        fail("attestation_policy_mismatch")
    return {
        "certificate_issuer": certificate_issuer,
        "oidc_issuer": certificate["issuer"],
        "runner_environment": certificate["runnerEnvironment"],
        "run_invocation_uri": run_invocation,
        "subject_alternative_name": certificate["subjectAlternativeName"],
        "verified_timestamp_count": len(timestamps),
    }


def verify_candidate(
    candidate_directory: Path,
    *,
    expected_source_commit: str,
    expected_version_name: str,
    expected_version_code: int,
    gh_command: Sequence[str],
    timeout_seconds: int = 30,
) -> dict[str, Any]:
    """Return a machine report for one verified candidate, or raise a reason-coded error."""

    if not isinstance(expected_source_commit, str) or COMMIT_RE.fullmatch(
        expected_source_commit
    ) is None:
        fail("source_commit_invalid")
    if (
        not isinstance(expected_version_name, str)
        or EXPECTED_VERSION_NAME_RE.fullmatch(expected_version_name) is None
        or type(expected_version_code) is not int
        or expected_version_code <= 0
    ):
        fail("expected_apk_identity_invalid")
    if type(timeout_seconds) is not int or not 1 <= timeout_seconds <= 120:
        fail("timeout_invalid")
    candidate_lexical = _nonreparse_lexical_path(
        candidate_directory,
        "candidate_directory_invalid",
    )
    try:
        candidate = candidate_lexical.resolve(strict=True)
    except OSError:
        fail("candidate_directory_invalid")
    if not candidate.is_dir() or _is_reparse(candidate):
        fail("candidate_directory_invalid")
    if not gh_command or any(not isinstance(value, str) or not value for value in gh_command):
        fail("gh_executable_invalid")
    gh_lexical = _nonreparse_lexical_path(
        Path(gh_command[0]),
        "gh_executable_invalid",
    )
    try:
        gh_executable = gh_lexical.resolve(strict=True)
    except OSError:
        fail("gh_executable_invalid")
    if _is_reparse(gh_executable) or not gh_executable.is_file():
        fail("gh_executable_invalid")
    executable_sha256 = _sha256_file(gh_executable)

    manifest, manifest_raw = _load_manifest(
        candidate,
        expected_source_commit,
        expected_version_name,
        expected_version_code,
    )
    checksums_raw, checksum_entries = _verify_checksum_inventory(candidate)
    apk_metadata = _dict(manifest["apk"], "build_manifest_invalid")
    source = _dict(manifest["source"], "build_manifest_invalid")
    expected_payload_names = {
        apk_metadata["file_name"],
        "build-manifest.json",
        INSTALL_NOTES_NAME,
        "provenance.sigstore.json",
    }
    if set(checksum_entries) != expected_payload_names:
        fail("candidate_file_set_invalid")
    apk = candidate / apk_metadata["file_name"]
    apk_raw_header = _regular_file(apk, maximum=MAX_APK_BYTES, reason="apk_file_invalid")
    if len(apk_raw_header) != apk_metadata["size_bytes"]:
        fail("apk_identity_mismatch")
    apk_sha256 = hashlib.sha256(apk_raw_header).hexdigest()
    if apk_sha256 != apk_metadata["sha256"].lower():
        fail("apk_identity_mismatch")
    _validate_apk_archive(apk)
    install_notes_raw = _regular_file(
        candidate / INSTALL_NOTES_NAME,
        maximum=MAX_INSTALL_NOTES_BYTES,
        reason="install_notes_invalid",
    )
    bundle = candidate / "provenance.sigstore.json"
    bundle_raw = _regular_file(
        bundle, maximum=MAX_BUNDLE_BYTES, reason="attestation_bundle_invalid"
    )
    if not isinstance(_strict_json(bundle_raw, "attestation_bundle_invalid"), dict):
        fail("attestation_bundle_invalid")

    version_rc, version_stdout, _ = _run_bounded(
        [*gh_command, "--version"],
        timeout_seconds=timeout_seconds,
        stdout_limit=MAX_GH_VERSION_BYTES,
        stderr_limit=MAX_GH_STDERR_BYTES,
        timeout_reason="gh_version_timeout",
        output_reason="gh_version_output_too_large",
    )
    try:
        version_text = version_stdout.decode("utf-8")
    except UnicodeError:
        fail("gh_version_invalid")
    first_line = version_text.splitlines()[0] if version_text.splitlines() else ""
    version_match = GH_VERSION_RE.match(first_line)
    if version_rc != 0 or version_match is None:
        fail("gh_version_invalid")

    command = [
        *gh_command,
        "attestation",
        "verify",
        str(apk),
        "--repo",
        EXPECTED_REPOSITORY,
        "--bundle",
        str(bundle),
        "--signer-workflow",
        EXPECTED_WORKFLOW,
        "--source-digest",
        expected_source_commit,
        "--source-ref",
        source["git_ref"],
        "--predicate-type",
        EXPECTED_PREDICATE_TYPE,
        "--deny-self-hosted-runners",
        "--format",
        "json",
    ]
    rc, stdout, _ = _run_bounded(
        command,
        timeout_seconds=timeout_seconds,
        stdout_limit=MAX_GH_STDOUT_BYTES,
        stderr_limit=MAX_GH_STDERR_BYTES,
        timeout_reason="gh_attestation_timeout",
        output_reason="gh_attestation_output_too_large",
    )
    if rc != 0:
        fail("gh_attestation_rejected")
    policy = _verify_policy_output(
        stdout,
        expected_subjects={
            apk.name: apk_sha256,
            "build-manifest.json": hashlib.sha256(manifest_raw).hexdigest(),
            INSTALL_NOTES_NAME: hashlib.sha256(install_notes_raw).hexdigest(),
        },
        source_commit=expected_source_commit,
        source_ref=source["git_ref"],
        workflow_run_url=source["workflow_run_url"],
    )
    run_match = RUN_URL_RE.fullmatch(source["workflow_run_url"])
    assert run_match is not None
    return {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "candidate_provenance_reverified": True,
        "repository": EXPECTED_REPOSITORY,
        "signer_workflow": EXPECTED_WORKFLOW,
        "predicate_type": EXPECTED_PREDICATE_TYPE,
        "source_commit": expected_source_commit,
        "source_ref": source["git_ref"],
        "workflow_run_id": int(run_match.group(1)),
        "workflow_run_url": source["workflow_run_url"],
        "apk": {
            "file_name": apk.name,
            "sha256": apk_sha256,
            "size_bytes": apk.stat().st_size,
            "package_name": apk_metadata["package_id"],
            "version_name": apk_metadata["version_name"],
            "version_code": apk_metadata["version_code"],
            "signer_sha256": apk_metadata["signer_sha256"].lower(),
        },
        "files": {
            "attestation_bundle_sha256": hashlib.sha256(bundle_raw).hexdigest(),
            "build_manifest_sha256": hashlib.sha256(manifest_raw).hexdigest(),
            "checksums_sha256": hashlib.sha256(checksums_raw).hexdigest(),
        },
        "gh": {
            "version": version_match.group(1),
            "executable_sha256": executable_sha256,
            **policy,
        },
    }


def _failure_report(reason_code: str) -> dict[str, Any]:
    return {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_VERSION,
        "status": "fail",
        "reason_code": reason_code,
        "candidate_provenance_reverified": False,
        "source_commit": None,
        "apk": None,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate_directory", type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--expected-version-code", type=int, required=True)
    parser.add_argument("--gh-path", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=30)
    args = parser.parse_args()
    try:
        report = verify_candidate(
            args.candidate_directory,
            expected_source_commit=args.source_commit.lower(),
            expected_version_name=args.expected_version_name,
            expected_version_code=args.expected_version_code,
            gh_command=(str(args.gh_path),),
            timeout_seconds=args.timeout_seconds,
        )
        return_code = 0
    except ProvenanceVerificationFailure as error:
        report = _failure_report(error.reason_code)
        return_code = 1
    except Exception:
        report = _failure_report("internal_verification_error")
        return_code = 1
    payload = (
        json.dumps(report, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("ascii")
    sys.stdout.buffer.write(payload)
    sys.stdout.buffer.flush()
    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
