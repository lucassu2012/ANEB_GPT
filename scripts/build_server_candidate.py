#!/usr/bin/env python3
"""Build a commit-bound, reproducible ANEB server candidate and provenance.

The command emits only bounded error codes. In particular, repository dirtiness
is never rendered because porcelain output can contain sensitive file names.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import ssl
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Mapping, Sequence, TextIO


SCHEMA = "aneb-server-build-provenance-v1"
CANONICAL_FLAGS = (
    "-trimpath",
    "-buildvcs=true",
    "-mod=readonly",
    "-pgo=off",
)
FIXED_ENVIRONMENT = {
    "GOOS": "linux",
    "GOARCH": "amd64",
    "GOAMD64": "v1",
    "CGO_ENABLED": "0",
    "GOFLAGS": "",
    "GOENV": "off",
    "GOEXPERIMENT": "",
    "GOFIPS140": "off",
    "GOWORK": "off",
    "GOTOOLCHAIN": "local",
}
ARTIFACT_ALLOWLIST = {
    "aneb-server.service": "server/aneb-server.service",
    "root-profiles/basic_network.json": "profiles/basic_network.json",
    "root-profiles/s1_chat.json": "profiles/s1_chat.json",
    "root-profiles/s2_coding_agent.json": "profiles/s2_coding_agent.json",
    "root-profiles/s3_multimodal.json": "profiles/s3_multimodal.json",
    "execution-profiles/token_multimodal_quick/profile.json": (
        "profiles/published/token_multimodal_quick/profile.json"
    ),
    "execution-profiles/token_multimodal_quick/runtime_plan.json": (
        "profiles/published/token_multimodal_quick/runtime_plan.json"
    ),
    "execution-profiles/token_multimodal_quick/manifest.sha256": (
        "profiles/published/token_multimodal_quick/manifest.sha256"
    ),
    "execution-profiles/ai_realtime_voice_quick/profile.json": (
        "profiles/published/ai_realtime_voice_quick/profile.json"
    ),
    "execution-profiles/ai_realtime_voice_quick/runtime_plan.json": (
        "profiles/published/ai_realtime_voice_quick/runtime_plan.json"
    ),
    "execution-profiles/ai_realtime_voice_quick/manifest.sha256": (
        "profiles/published/ai_realtime_voice_quick/manifest.sha256"
    ),
    "tls/ip-cert.pem": "server/tls/ip/aneb_ip_cert.pem",
}
OPTIONAL_ARTIFACTS = frozenset({"tls/ip-cert.pem"})
MANDATORY_ARTIFACTS = frozenset(ARTIFACT_ALLOWLIST) - OPTIONAL_ARTIFACTS
OUTPUT_NAMES = {
    "binary": "aneb-server-linux",
    "provenance": "build-provenance.json",
    "buildinfo": "go-buildinfo.json",
}
COMMIT_RE = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
GO_VERSION_RE = re.compile(r"^go[0-9]+(?:\.[0-9]+)+(?:[A-Za-z0-9._-]*)?$")
SOURCE_LIST_FIELDS = (
    "GoFiles",
    "CgoFiles",
    "CFiles",
    "CXXFiles",
    "MFiles",
    "HFiles",
    "FFiles",
    "SFiles",
    "SwigFiles",
    "SwigCXXFiles",
    "SysoFiles",
    "EmbedFiles",
)


class CandidateBuildError(Exception):
    """Expected failure with a bounded, non-sensitive code."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class CandidatePaths:
    repo_root: Path
    expected_commit: str
    server_dir: Path
    output_bin: Path
    provenance: Path
    buildinfo: Path
    artifact_snapshot_root: Path
    artifacts: Mapping[str, Path]
    optional_artifact_payloads: Mapping[str, bytes]


def _canonical_json(payload: object) -> bytes:
    try:
        rendered = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    except (TypeError, ValueError) as error:
        raise CandidateBuildError("provenance_serialization_failed") from error
    return (rendered + "\n").encode("utf-8")


def _run(
    arguments: Sequence[str],
    *,
    cwd: Path | None = None,
    environment: Mapping[str, str] | None = None,
    failure_code: str,
) -> bytes:
    try:
        completed = subprocess.run(
            list(arguments),
            cwd=cwd,
            env=dict(environment) if environment is not None else None,
            text=False,
            capture_output=True,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise CandidateBuildError(failure_code) from error
    if completed.returncode != 0 or not isinstance(completed.stdout, bytes):
        raise CandidateBuildError(failure_code)
    return completed.stdout


def _decode_utf8(payload: bytes, code: str) -> str:
    try:
        return payload.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise CandidateBuildError(code) from error


def _existing_canonical_directory(raw: Path, code: str) -> Path:
    if not raw.is_absolute():
        raise CandidateBuildError(code)
    try:
        resolved = raw.resolve(strict=True)
    except OSError as error:
        raise CandidateBuildError(code) from error
    if raw != resolved or not resolved.is_dir() or resolved.is_symlink():
        raise CandidateBuildError(code)
    return resolved


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
    except ValueError:
        return False
    return True


def _validate_output(raw: Path, repo_root: Path, expected_name: str) -> Path:
    if not raw.is_absolute() or raw.name != expected_name or raw.exists():
        raise CandidateBuildError("output_path_invalid")
    try:
        parent = raw.parent.resolve(strict=True)
    except OSError as error:
        raise CandidateBuildError("output_path_invalid") from error
    resolved = parent / raw.name
    if raw != resolved or parent.is_symlink() or _is_within(resolved, repo_root):
        raise CandidateBuildError("output_path_invalid")
    return resolved


def _validate_output_directory(
    raw: Path,
    repo_root: Path,
    expected_name: str,
) -> Path:
    if (
        not raw.is_absolute()
        or raw.name != expected_name
        or raw.exists()
        or raw.is_symlink()
    ):
        raise CandidateBuildError("output_path_invalid")
    try:
        parent = raw.parent.resolve(strict=True)
    except OSError as error:
        raise CandidateBuildError("output_path_invalid") from error
    resolved = parent / raw.name
    if raw != resolved or parent.is_symlink() or _is_within(resolved, repo_root):
        raise CandidateBuildError("output_path_invalid")
    return resolved


def _artifact_arguments(values: Sequence[str]) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for value in values:
        name, separator, raw_path = value.partition("=")
        if not separator or not name or not raw_path or name in parsed:
            raise CandidateBuildError("artifact_argument_invalid")
        if name not in ARTIFACT_ALLOWLIST:
            raise CandidateBuildError("artifact_not_allowed")
        parsed[name] = raw_path
    if not MANDATORY_ARTIFACTS.issubset(parsed):
        raise CandidateBuildError("artifact_set_incomplete")
    return parsed


def _validate_artifacts(
    repo_root: Path,
    values: Sequence[str],
) -> tuple[dict[str, Path], dict[str, bytes]]:
    raw_artifacts = _artifact_arguments(values)
    artifacts: dict[str, Path] = {}
    for name, raw_value in raw_artifacts.items():
        raw_path = Path(raw_value)
        if not raw_path.is_absolute():
            raise CandidateBuildError("artifact_path_invalid")
        expected = repo_root.joinpath(*PurePosixPath(ARTIFACT_ALLOWLIST[name]).parts)
        try:
            resolved = raw_path.resolve(strict=True)
            expected_resolved = expected.resolve(strict=True)
        except OSError as error:
            raise CandidateBuildError("artifact_path_invalid") from error
        if (
            raw_path != resolved
            or resolved != expected_resolved
            or not resolved.is_file()
            or resolved.is_symlink()
            or not _is_within(resolved, repo_root)
        ):
            raise CandidateBuildError("artifact_path_invalid")
        artifacts[name] = resolved

    optional_payloads: dict[str, bytes] = {}
    certificate = artifacts.get("tls/ip-cert.pem")
    if certificate is not None:
        try:
            certificate_payload = _read_regular_bytes(
                certificate,
                "public_certificate_invalid",
            )
            certificate_text = certificate_payload.decode("ascii", errors="strict")
            if "PRIVATE KEY" in certificate_text:
                raise ValueError("private material")
            ssl.PEM_cert_to_DER_cert(certificate_text)
        except (OSError, UnicodeError, ValueError) as error:
            raise CandidateBuildError("public_certificate_invalid") from error
        optional_payloads["tls/ip-cert.pem"] = certificate_payload
    return artifacts, optional_payloads


def _validate_paths(args: argparse.Namespace) -> CandidatePaths:
    repo_root = _existing_canonical_directory(args.repo_root, "repo_root_invalid")
    if COMMIT_RE.fullmatch(args.expected_commit) is None:
        raise CandidateBuildError("expected_commit_invalid")
    server_dir = _existing_canonical_directory(args.server_dir, "server_dir_invalid")
    if server_dir != repo_root / "server":
        raise CandidateBuildError("server_dir_invalid")
    git_marker = repo_root / ".git"
    if not git_marker.exists() or git_marker.is_symlink():
        raise CandidateBuildError("repo_root_invalid")

    output_bin = _validate_output(args.output_bin, repo_root, OUTPUT_NAMES["binary"])
    provenance = _validate_output(
        args.provenance,
        repo_root,
        OUTPUT_NAMES["provenance"],
    )
    buildinfo = _validate_output(
        args.buildinfo,
        repo_root,
        OUTPUT_NAMES["buildinfo"],
    )
    artifact_snapshot_root = _validate_output_directory(
        args.artifact_snapshot_root,
        repo_root,
        "upload-artifacts",
    )
    if len(
        {
            output_bin.parent,
            provenance.parent,
            buildinfo.parent,
            artifact_snapshot_root.parent,
        }
    ) != 1:
        raise CandidateBuildError("output_path_invalid")
    artifacts, optional_artifact_payloads = _validate_artifacts(
        repo_root,
        args.artifact,
    )
    return CandidatePaths(
        repo_root=repo_root,
        expected_commit=args.expected_commit,
        server_dir=server_dir,
        output_bin=output_bin,
        provenance=provenance,
        buildinfo=buildinfo,
        artifact_snapshot_root=artifact_snapshot_root,
        artifacts=artifacts,
        optional_artifact_payloads=optional_artifact_payloads,
    )


def _tool(name: str, code: str) -> str:
    resolved = shutil.which(name)
    if not resolved:
        raise CandidateBuildError(code)
    return resolved


def _git_text(git: str, repo_root: Path, arguments: Sequence[str], code: str) -> str:
    output = _run(
        [git, "-C", str(repo_root), *arguments],
        failure_code=code,
    )
    return _decode_utf8(output, code).strip()


def _repository_head(git: str, repo_root: Path, code: str) -> str:
    head = _git_text(git, repo_root, ["rev-parse", "--verify", "HEAD^{commit}"], code)
    if COMMIT_RE.fullmatch(head) is None:
        raise CandidateBuildError(code)
    return head


def _assert_repository_root(git: str, repo_root: Path) -> None:
    root_text = _git_text(
        git,
        repo_root,
        ["rev-parse", "--show-toplevel"],
        "repo_root_invalid",
    )
    try:
        reported = Path(root_text).resolve(strict=True)
    except OSError as error:
        raise CandidateBuildError("repo_root_invalid") from error
    if reported != repo_root:
        raise CandidateBuildError("repo_root_invalid")


def _assert_clean(git: str, repo_root: Path, code: str) -> None:
    status = _run(
        [
            git,
            "-C",
            str(repo_root),
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
            "--ignore-submodules=none",
        ],
        failure_code=code,
    )
    if status:
        raise CandidateBuildError(code)


def _assert_no_index_flags(git: str, repo_root: Path) -> None:
    entries = _run(
        [git, "-C", str(repo_root), "ls-files", "-v", "-z", "--cached"],
        failure_code="repository_index_flags_invalid",
    )
    for entry in entries.split(b"\0"):
        if not entry:
            continue
        if len(entry) < 3 or entry[1:2] != b" ":
            raise CandidateBuildError("repository_index_flags_invalid")
        tag = entry[0:1]
        if tag == b"S" or b"a" <= tag <= b"z":
            raise CandidateBuildError("repository_index_flags_forbidden")


def _assert_tracked(git: str, repo_root: Path, path: Path) -> None:
    try:
        relative = path.relative_to(repo_root).as_posix()
    except ValueError as error:
        raise CandidateBuildError("source_path_invalid") from error
    _run(
        [git, "-C", str(repo_root), "ls-files", "--error-unmatch", "--", relative],
        failure_code="source_not_tracked",
    )


def _build_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(FIXED_ENVIRONMENT)
    return environment


def _create_repository_snapshot(
    git: str,
    source_root: Path,
    commit: str,
    destination: Path,
) -> Path:
    _run(
        [
            git,
            "-c",
            "core.autocrlf=false",
            "-c",
            "core.eol=lf",
            "clone",
            "--quiet",
            "--no-hardlinks",
            "--no-checkout",
            "--",
            str(source_root),
            str(destination),
        ],
        failure_code="repository_snapshot_failed",
    )
    _run(
        [git, "-C", str(destination), "config", "--local", "core.autocrlf", "false"],
        failure_code="repository_snapshot_failed",
    )
    _run(
        [git, "-C", str(destination), "config", "--local", "core.eol", "lf"],
        failure_code="repository_snapshot_failed",
    )
    _run(
        [git, "-C", str(destination), "checkout", "--quiet", "--detach", commit],
        failure_code="repository_snapshot_failed",
    )
    snapshot_root = _existing_canonical_directory(
        destination,
        "repository_snapshot_invalid",
    )
    _assert_repository_root(git, snapshot_root)
    if _repository_head(git, snapshot_root, "repository_snapshot_invalid") != commit:
        raise CandidateBuildError("repository_snapshot_invalid")
    _assert_clean(git, snapshot_root, "repository_snapshot_dirty")
    alternates = snapshot_root / ".git" / "objects" / "info" / "alternates"
    if alternates.exists() or alternates.is_symlink():
        raise CandidateBuildError("repository_snapshot_invalid")
    return snapshot_root


def _snapshot_candidate_paths(paths: CandidatePaths, snapshot_root: Path) -> CandidatePaths:
    artifacts: dict[str, Path] = {}
    for name, original in paths.artifacts.items():
        relative = PurePosixPath(ARTIFACT_ALLOWLIST[name])
        snapshot_path = snapshot_root.joinpath(*relative.parts)
        if name in OPTIONAL_ARTIFACTS:
            snapshot_path.parent.mkdir(parents=True, exist_ok=True)
            del original
            payload = paths.optional_artifact_payloads.get(name)
            if payload is None:
                raise CandidateBuildError("artifact_snapshot_failed")
            _write_file(snapshot_path, payload)
        try:
            resolved = snapshot_path.resolve(strict=True)
        except OSError as error:
            raise CandidateBuildError("artifact_snapshot_failed") from error
        if (
            resolved != snapshot_path
            or not resolved.is_file()
            or resolved.is_symlink()
            or not _is_within(resolved, snapshot_root)
        ):
            raise CandidateBuildError("artifact_snapshot_failed")
        artifacts[name] = resolved
    return CandidatePaths(
        repo_root=snapshot_root,
        expected_commit=paths.expected_commit,
        server_dir=snapshot_root / "server",
        output_bin=paths.output_bin,
        provenance=paths.provenance,
        buildinfo=paths.buildinfo,
        artifact_snapshot_root=paths.artifact_snapshot_root,
        artifacts=artifacts,
        optional_artifact_payloads=paths.optional_artifact_payloads,
    )


def _parse_json_object(payload: bytes, code: str) -> dict[str, object]:
    try:
        parsed = json.loads(_decode_utf8(payload, code))
    except json.JSONDecodeError as error:
        raise CandidateBuildError(code) from error
    if not isinstance(parsed, dict):
        raise CandidateBuildError(code)
    return parsed


def _parse_json_object_stream(payload: bytes, code: str) -> list[dict[str, object]]:
    rendered = _decode_utf8(payload, code)
    decoder = json.JSONDecoder()
    parsed: list[dict[str, object]] = []
    position = 0
    try:
        while position < len(rendered):
            while position < len(rendered) and rendered[position].isspace():
                position += 1
            if position >= len(rendered):
                break
            value, position = decoder.raw_decode(rendered, position)
            if not isinstance(value, dict):
                raise CandidateBuildError(code)
            parsed.append(value)
    except json.JSONDecodeError as error:
        raise CandidateBuildError(code) from error
    if not parsed:
        raise CandidateBuildError(code)
    return parsed


def _assert_build_sources_tracked(
    go: str,
    git: str,
    paths: CandidatePaths,
    environment: Mapping[str, str],
) -> tuple[Path, ...]:
    listed = _run(
        [go, "list", "-deps", "-mod=readonly", "-json", "."],
        cwd=paths.server_dir,
        environment=environment,
        failure_code="go_source_list_failed",
    )
    packages = _parse_json_object_stream(listed, "go_source_list_invalid")
    sources: set[Path] = set()
    for package in packages:
        try:
            package_dir = Path(str(package["Dir"])).resolve(strict=True)
        except (KeyError, OSError) as error:
            raise CandidateBuildError("go_source_list_invalid") from error
        module = package.get("Module")
        if module is not None and not isinstance(module, dict):
            raise CandidateBuildError("go_source_list_invalid")
        if isinstance(module, dict):
            replacement = module.get("Replace")
            if replacement is not None and not isinstance(replacement, dict):
                raise CandidateBuildError("go_source_list_invalid")
            if isinstance(replacement, dict) and replacement.get("Dir"):
                try:
                    replacement_dir = Path(str(replacement["Dir"])).resolve(strict=True)
                except OSError as error:
                    raise CandidateBuildError("go_source_list_invalid") from error
                if not _is_within(replacement_dir, paths.server_dir):
                    raise CandidateBuildError("go_local_replace_forbidden")
        if not _is_within(package_dir, paths.server_dir):
            if _is_within(package_dir, paths.repo_root):
                raise CandidateBuildError("go_source_path_invalid")
            continue
        source_names: list[str] = []
        for field in SOURCE_LIST_FIELDS:
            values = package.get(field, [])
            if not isinstance(values, list) or any(
                not isinstance(value, str) for value in values
            ):
                raise CandidateBuildError("go_source_list_invalid")
            source_names.extend(values)
        for name in source_names:
            source = package_dir / name
            try:
                resolved = source.resolve(strict=True)
            except OSError as error:
                raise CandidateBuildError("go_source_path_invalid") from error
            if (
                source != resolved
                or source.is_symlink()
                or not resolved.is_file()
                or not _is_within(resolved, paths.server_dir)
            ):
                raise CandidateBuildError("go_source_path_invalid")
            _assert_tracked(git, paths.repo_root, resolved)
            sources.add(resolved)
    if not sources:
        raise CandidateBuildError("go_source_list_invalid")
    return tuple(sorted(sources, key=lambda item: item.as_posix()))


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        raise CandidateBuildError("artifact_read_failed") from error
    return digest.hexdigest()


def _read_regular_bytes(path: Path, code: str) -> bytes:
    try:
        metadata = path.lstat()
        if path.is_symlink() or not stat.S_ISREG(metadata.st_mode):
            raise CandidateBuildError(code)
        flags = os.O_RDONLY
        if hasattr(os, "O_BINARY"):
            flags |= os.O_BINARY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        descriptor = os.open(path, flags)
    except OSError as error:
        raise CandidateBuildError(code) from error
    try:
        opened = os.fstat(descriptor)
        identity = (metadata.st_dev, metadata.st_ino)
        opened_identity = (opened.st_dev, opened.st_ino)
        if identity != opened_identity or not stat.S_ISREG(opened.st_mode):
            raise CandidateBuildError(code)
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            payload = stream.read()
        finished = os.fstat(descriptor)
        if (
            (finished.st_dev, finished.st_ino) != identity
            or finished.st_size != opened.st_size
            or len(payload) != opened.st_size
            or finished.st_mtime_ns != opened.st_mtime_ns
            or finished.st_ctime_ns != opened.st_ctime_ns
        ):
            raise CandidateBuildError(code)
        return payload
    except OSError as error:
        raise CandidateBuildError(code) from error
    finally:
        os.close(descriptor)


def _head_blob_bytes(
    git: str,
    repo_root: Path,
    commit: str,
    path: Path,
) -> bytes:
    try:
        relative = path.relative_to(repo_root).as_posix()
    except ValueError as error:
        raise CandidateBuildError("repository_snapshot_input_mismatch") from error
    tree_entry = _run(
        [git, "-C", str(repo_root), "ls-tree", "-z", commit, "--", relative],
        failure_code="repository_snapshot_input_mismatch",
    )
    records = [record for record in tree_entry.split(b"\0") if record]
    if len(records) != 1 or b"\t" not in records[0]:
        raise CandidateBuildError("repository_snapshot_input_mismatch")
    header, encoded_path = records[0].split(b"\t", 1)
    fields = header.split(b" ")
    if (
        len(fields) != 3
        or fields[0] not in {b"100644", b"100755"}
        or fields[1] != b"blob"
        or re.fullmatch(rb"(?:[0-9a-f]{40}|[0-9a-f]{64})", fields[2]) is None
    ):
        raise CandidateBuildError("repository_snapshot_input_mismatch")
    try:
        recorded_path = encoded_path.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise CandidateBuildError("repository_snapshot_input_mismatch") from error
    if recorded_path != relative:
        raise CandidateBuildError("repository_snapshot_input_mismatch")
    return _run(
        [git, "-C", str(repo_root), "cat-file", "blob", fields[2].decode("ascii")],
        failure_code="repository_snapshot_input_mismatch",
    )


def _assert_exact_head_inputs(
    git: str,
    repo_root: Path,
    commit: str,
    paths: Sequence[Path],
) -> None:
    for path in sorted(set(paths), key=lambda item: item.as_posix()):
        actual = _read_regular_bytes(path, "repository_snapshot_input_mismatch")
        expected = _head_blob_bytes(git, repo_root, commit, path)
        if actual != expected:
            raise CandidateBuildError("repository_snapshot_input_mismatch")


def _file_record(repo_root: Path, path: Path) -> dict[str, object]:
    try:
        size = path.stat().st_size
        relative = path.relative_to(repo_root).as_posix()
    except (OSError, ValueError) as error:
        raise CandidateBuildError("artifact_read_failed") from error
    return {"path": relative, "sha256": _sha256(path), "bytes": size}


def _binary_record(path: Path) -> dict[str, object]:
    try:
        size = path.stat().st_size
    except OSError as error:
        raise CandidateBuildError("binary_read_failed") from error
    if size <= 0:
        raise CandidateBuildError("binary_read_failed")
    return {"sha256": _sha256(path), "bytes": size}


def _snapshot_inputs(paths: CandidatePaths) -> dict[str, dict[str, object]]:
    inputs = {
        "go.mod": _file_record(paths.repo_root, paths.server_dir / "go.mod"),
        "go.sum": _file_record(paths.repo_root, paths.server_dir / "go.sum"),
    }
    for name, path in sorted(paths.artifacts.items()):
        inputs[f"artifact:{name}"] = _file_record(paths.repo_root, path)
    return inputs


def _go_version(go: str, environment: Mapping[str, str]) -> str:
    rendered = _decode_utf8(
        _run([go, "version"], environment=environment, failure_code="go_version_failed"),
        "go_version_invalid",
    ).strip()
    fields = rendered.split()
    if len(fields) != 4 or fields[:2] != ["go", "version"]:
        raise CandidateBuildError("go_version_invalid")
    version = fields[2]
    if GO_VERSION_RE.fullmatch(version) is None:
        raise CandidateBuildError("go_version_invalid")
    return version


def _verify_modules(go: str, server_dir: Path, environment: Mapping[str, str]) -> None:
    _run(
        [go, "mod", "verify"],
        cwd=server_dir,
        environment=environment,
        failure_code="go_module_verify_failed",
    )


def _validate_buildinfo(
    payload: dict[str, object],
    *,
    commit: str,
    go_version: str,
) -> None:
    if payload.get("GoVersion") != go_version:
        raise CandidateBuildError("go_buildinfo_version_mismatch")
    settings_value = payload.get("Settings")
    if not isinstance(settings_value, list):
        raise CandidateBuildError("go_buildinfo_settings_invalid")
    settings: dict[str, str] = {}
    for item in settings_value:
        if not isinstance(item, dict) or set(item) != {"Key", "Value"}:
            raise CandidateBuildError("go_buildinfo_settings_invalid")
        key = item["Key"]
        value = item["Value"]
        if not isinstance(key, str) or not isinstance(value, str) or key in settings:
            raise CandidateBuildError("go_buildinfo_settings_invalid")
        settings[key] = value
    expected = {
        "vcs": "git",
        "vcs.revision": commit,
        "vcs.modified": "false",
        "GOOS": FIXED_ENVIRONMENT["GOOS"],
        "GOARCH": FIXED_ENVIRONMENT["GOARCH"],
        "GOAMD64": FIXED_ENVIRONMENT["GOAMD64"],
        "CGO_ENABLED": FIXED_ENVIRONMENT["CGO_ENABLED"],
        "-trimpath": "true",
    }
    if any(settings.get(key) != value for key, value in expected.items()) or settings.get(
        "GOFIPS140", "off"
    ) != FIXED_ENVIRONMENT["GOFIPS140"]:
        raise CandidateBuildError("go_buildinfo_contract_mismatch")


def _write_file(path: Path, payload: bytes) -> None:
    try:
        with path.open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except OSError as error:
        raise CandidateBuildError("candidate_output_write_failed") from error


def _write_artifact_snapshot(root: Path, artifacts: Mapping[str, Path]) -> None:
    try:
        root.mkdir(mode=0o700)
    except OSError as error:
        raise CandidateBuildError("artifact_snapshot_failed") from error
    for name, source in sorted(artifacts.items()):
        destination = root.joinpath(*PurePosixPath(name).parts)
        try:
            destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        except OSError as error:
            raise CandidateBuildError("artifact_snapshot_failed") from error
        payload = _read_regular_bytes(source, "artifact_snapshot_failed")
        _write_file(destination, payload)


def _publish(staged: Mapping[Path, Path]) -> None:
    published: list[Path] = []
    try:
        for source, destination in staged.items():
            source.replace(destination)
            published.append(destination)
    except OSError as error:
        for path in published:
            try:
                if path.is_dir() and not path.is_symlink():
                    shutil.rmtree(path)
                else:
                    path.unlink()
            except OSError:
                pass
        raise CandidateBuildError("candidate_publish_failed") from error


def build_candidate(paths: CandidatePaths) -> dict[str, object]:
    git = _tool("git", "git_not_found")
    go = _tool("go", "go_not_found")
    environment = _build_environment()
    _assert_repository_root(git, paths.repo_root)
    before_head = _repository_head(git, paths.repo_root, "repository_head_invalid")
    if before_head != paths.expected_commit:
        raise CandidateBuildError("repository_head_mismatch")
    _assert_no_index_flags(git, paths.repo_root)
    _assert_clean(git, paths.repo_root, "repository_dirty_before_build")

    for path in (paths.server_dir / "go.mod", paths.server_dir / "go.sum"):
        if not path.is_file() or path.is_symlink():
            raise CandidateBuildError("module_file_invalid")
        _assert_tracked(git, paths.repo_root, path)
    for name, path in paths.artifacts.items():
        if name not in OPTIONAL_ARTIFACTS:
            _assert_tracked(git, paths.repo_root, path)
    _assert_build_sources_tracked(go, git, paths, environment)
    go_version = _go_version(go, environment)

    output_parent = paths.output_bin.parent
    with tempfile.TemporaryDirectory(prefix=".aneb-candidate-", dir=output_parent) as raw_stage:
        stage = Path(raw_stage)
        snapshot_root = _create_repository_snapshot(
            git,
            paths.repo_root,
            before_head,
            stage / "source",
        )
        snapshot_paths = _snapshot_candidate_paths(paths, snapshot_root)
        _assert_no_index_flags(git, snapshot_root)
        before_sources = _assert_build_sources_tracked(
            go,
            git,
            snapshot_paths,
            environment,
        )
        _verify_modules(go, snapshot_paths.server_dir, environment)
        exact_head_inputs = [
            snapshot_paths.server_dir / "go.mod",
            snapshot_paths.server_dir / "go.sum",
            *before_sources,
            *(
                path
                for name, path in snapshot_paths.artifacts.items()
                if name not in OPTIONAL_ARTIFACTS
            ),
        ]
        _assert_exact_head_inputs(
            git,
            snapshot_root,
            before_head,
            exact_head_inputs,
        )
        before_inputs = _snapshot_inputs(snapshot_paths)
        staged_binary = stage / OUTPUT_NAMES["binary"]
        staged_buildinfo = stage / OUTPUT_NAMES["buildinfo"]
        staged_provenance = stage / OUTPUT_NAMES["provenance"]
        staged_artifact_snapshot = stage / "upload-artifacts"
        _run(
            [
                go,
                "build",
                *CANONICAL_FLAGS,
                "-o",
                str(staged_binary),
                ".",
            ],
            cwd=snapshot_paths.server_dir,
            environment=environment,
            failure_code="go_build_failed",
        )
        if not staged_binary.is_file() or staged_binary.is_symlink():
            raise CandidateBuildError("go_build_output_invalid")

        raw_buildinfo = _run(
            [go, "version", "-m", "-json", str(staged_binary)],
            environment=environment,
            failure_code="go_buildinfo_failed",
        )
        buildinfo_object = _parse_json_object(raw_buildinfo, "go_buildinfo_invalid")
        _validate_buildinfo(
            buildinfo_object,
            commit=before_head,
            go_version=go_version,
        )
        buildinfo_bytes = _canonical_json(buildinfo_object)
        binary = _binary_record(staged_binary)
        after_sources = _assert_build_sources_tracked(
            go,
            git,
            snapshot_paths,
            environment,
        )
        if after_sources != before_sources:
            raise CandidateBuildError("repository_snapshot_input_mismatch")
        _assert_exact_head_inputs(
            git,
            snapshot_root,
            before_head,
            exact_head_inputs,
        )
        after_inputs = _snapshot_inputs(snapshot_paths)
        if before_inputs != after_inputs:
            raise CandidateBuildError("repository_inputs_changed_after_build")
        if _repository_head(
            git,
            snapshot_root,
            "repository_snapshot_invalid",
        ) != before_head:
            raise CandidateBuildError("repository_snapshot_changed_after_build")
        _assert_clean(git, snapshot_root, "repository_snapshot_changed_after_build")
        after_head = _repository_head(
            git,
            paths.repo_root,
            "repository_head_invalid_after_build",
        )
        if after_head != before_head:
            raise CandidateBuildError("repository_head_changed_after_build")
        _assert_no_index_flags(git, paths.repo_root)
        _assert_clean(git, paths.repo_root, "repository_dirty_after_build")
        confirmed_head = _repository_head(
            git,
            paths.repo_root,
            "repository_head_invalid_after_build",
        )
        if confirmed_head != before_head:
            raise CandidateBuildError("repository_head_changed_after_build")

        module_files = {
            name: after_inputs[name]
            for name in ("go.mod", "go.sum")
        }
        artifacts = []
        for name in sorted(paths.artifacts):
            record = dict(after_inputs[f"artifact:{name}"])
            record["name"] = name
            artifacts.append(record)
        provenance: dict[str, object] = {
            "schema": SCHEMA,
            "commit": before_head,
            "GoVersion": go_version,
            "canonical_flags": list(CANONICAL_FLAGS),
            "environment": dict(FIXED_ENVIRONMENT),
            "binary": binary,
            "module_files": module_files,
            "artifacts": artifacts,
            "go_buildinfo": {
                "bytes": len(buildinfo_bytes),
                "sha256": hashlib.sha256(buildinfo_bytes).hexdigest(),
            },
        }
        provenance_bytes = _canonical_json(provenance)
        _write_file(staged_buildinfo, buildinfo_bytes)
        _write_file(staged_provenance, provenance_bytes)
        _write_artifact_snapshot(staged_artifact_snapshot, snapshot_paths.artifacts)
        _publish(
            {
                staged_binary: paths.output_bin,
                staged_buildinfo: paths.buildinfo,
                staged_provenance: paths.provenance,
                staged_artifact_snapshot: paths.artifact_snapshot_root,
            }
        )
    return provenance


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build a clean-HEAD ANEB Linux server candidate with provenance.",
        allow_abbrev=False,
    )
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--server-dir", type=Path, required=True)
    parser.add_argument("--output-bin", type=Path, required=True)
    parser.add_argument("--provenance", type=Path, required=True)
    parser.add_argument("--buildinfo", type=Path, required=True)
    parser.add_argument("--artifact-snapshot-root", type=Path, required=True)
    parser.add_argument("--artifact", action="append", default=[], required=True)
    return parser


def main(
    argv: Sequence[str] | None = None,
    *,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
) -> int:
    stdout = stdout or sys.stdout
    stderr = stderr or sys.stderr
    try:
        args = build_parser().parse_args(argv)
        provenance = build_candidate(_validate_paths(args))
    except CandidateBuildError as error:
        print(f"ERROR code={error.code}", file=stderr)
        return 2
    print(
        f"OK schema={SCHEMA} commit={provenance['commit']} "
        f"binary_sha256={provenance['binary']['sha256']}",
        file=stdout,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
