#!/usr/bin/env python3
"""Create a fail-closed, identity-verified ANEB Debug installation bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unicodedata
import zipfile
from dataclasses import dataclass
from pathlib import Path


CONTRACT_VERSION = "aneb-debug-candidate-v1"
EXPECTED_PACKAGE = "com.aneb.probe.codex"
PROVENANCE_BUNDLE_NAME = "provenance.sigstore.json"
INSTALL_NOTES_NAME = "ANEB-安装说明.txt"
MAX_PROVENANCE_BUNDLE_BYTES = 16 * 1024 * 1024
MAX_CANDIDATE_PAYLOAD_BYTES = 512 * 1024 * 1024
MAX_CANDIDATE_TOTAL_BYTES = 600 * 1024 * 1024
MAX_CANDIDATE_PAYLOADS = 32
PACKAGE_NAME_RE = re.compile(r"package:.*?\bname='(?P<value>[^']+)'")
VERSION_CODE_RE = re.compile(r"package:.*?\bversionCode='(?P<value>[^']+)'")
VERSION_NAME_RE = re.compile(r"package:.*?\bversionName='(?P<value>[^']+)'")
MIN_SDK_RE = re.compile(r"(?:minSdkVersion|sdkVersion):'(?P<value>[^']+)'")
TARGET_SDK_RE = re.compile(r"targetSdkVersion:'(?P<value>[^']+)'")
CERT_DN_RE = re.compile(r"Signer #1 certificate DN:\s*(?P<value>.+)")
CERT_SHA_RE = re.compile(
    r"Signer #1 certificate SHA-256 digest:\s*"
    r"(?P<value>(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}|[0-9a-fA-F]{64})"
)


class CandidateError(ValueError):
    pass


class DuplicateJsonKey(ValueError):
    pass


class NonstandardJsonConstant(ValueError):
    pass


@dataclass(frozen=True)
class ApkIdentity:
    package_id: str
    version_code: int
    version_name: str
    min_sdk: int
    target_sdk: int
    signer_dn: str
    signer_sha256: str


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


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


def _assert_nonreparse_lexical_chain(path: Path, *, reason: str) -> Path:
    try:
        absolute = Path(os.path.abspath(os.fspath(path)))
    except (OSError, TypeError, ValueError) as error:
        raise CandidateError(reason) from error
    for component in reversed((absolute, *absolute.parents)):
        if not os.path.lexists(component):
            continue
        if _is_reparse(component):
            raise CandidateError(reason)
    return absolute


def _unique_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(key)
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise NonstandardJsonConstant(value)


def _read_regular_file(path: Path, *, maximum: int, reason: str) -> bytes:
    if _is_reparse(path) or not path.is_file():
        raise CandidateError(reason)
    try:
        size = path.stat().st_size
        if not 0 < size <= maximum:
            raise CandidateError(reason)
        with path.open("rb") as stream:
            raw = stream.read(maximum + 1)
    except CandidateError:
        raise
    except OSError as error:
        raise CandidateError(reason) from error
    if len(raw) != size or len(raw) > maximum:
        raise CandidateError(reason)
    return raw


def parse_identity(badging: str, signer: str) -> ApkIdentity:
    package = PACKAGE_NAME_RE.search(badging)
    version_code = VERSION_CODE_RE.search(badging)
    version_name = VERSION_NAME_RE.search(badging)
    min_sdk = MIN_SDK_RE.search(badging)
    target_sdk = TARGET_SDK_RE.search(badging)
    signer_dn = CERT_DN_RE.search(signer)
    signer_sha = CERT_SHA_RE.search(signer)
    fields = {
        "package": package,
        "version_code": version_code,
        "version_name": version_name,
        "min_sdk": min_sdk,
        "target_sdk": target_sdk,
        "signer_dn": signer_dn,
        "signer_sha256": signer_sha,
    }
    missing = [name for name, value in fields.items() if value is None]
    if missing:
        raise CandidateError("apk_identity_output_incomplete:" + ",".join(missing))
    try:
        return ApkIdentity(
            package_id=package.group("value"),
            version_code=int(version_code.group("value")),
            version_name=version_name.group("value"),
            min_sdk=int(min_sdk.group("value")),
            target_sdk=int(target_sdk.group("value")),
            signer_dn=signer_dn.group("value").strip(),
            signer_sha256=signer_sha.group("value").replace(":", "").upper(),
        )
    except ValueError as error:
        raise CandidateError("apk_identity_number_invalid") from error


def load_gradle_metadata(path: Path) -> dict[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CandidateError("gradle_output_metadata_invalid") from error
    elements = payload.get("elements")
    if payload.get("variantName") != "debug" or not isinstance(elements, list) or len(elements) != 1:
        raise CandidateError("gradle_output_not_single_debug_apk")
    element = elements[0]
    if not isinstance(element, dict) or element.get("type") != "SINGLE" or element.get("filters") != []:
        raise CandidateError("gradle_output_not_universal_apk")
    return {
        "package_id": payload.get("applicationId"),
        "version_code": element.get("versionCode"),
        "version_name": element.get("versionName"),
        "output_file": element.get("outputFile"),
    }


def verify_apk_zip(path: Path) -> None:
    if not path.is_file():
        raise CandidateError("apk_missing")
    try:
        with zipfile.ZipFile(path) as archive:
            corrupt = archive.testzip()
            names = set(archive.namelist())
    except (OSError, zipfile.BadZipFile) as error:
        raise CandidateError("apk_zip_invalid") from error
    if corrupt is not None:
        raise CandidateError(f"apk_zip_corrupt:{corrupt}")
    missing = {"AndroidManifest.xml", "classes.dex"} - names
    if missing:
        raise CandidateError("apk_required_entries_missing:" + ",".join(sorted(missing)))


def verify_debug_boundary(identity: ApkIdentity, metadata: dict[str, object], apk: Path) -> None:
    expected = {
        "package_id": identity.package_id,
        "version_code": identity.version_code,
        "version_name": identity.version_name,
        "output_file": apk.name,
    }
    if metadata != expected:
        raise CandidateError("apk_gradle_identity_mismatch")
    if identity.package_id != EXPECTED_PACKAGE:
        raise CandidateError("candidate_package_not_codex_debug")
    if not identity.version_name.endswith("-codex"):
        raise CandidateError("candidate_version_not_codex_debug")
    if "CN=Android Debug" not in identity.signer_dn:
        raise CandidateError("candidate_signer_not_android_debug")


def installation_text(identity: ApkIdentity, apk_name: str, apk_sha256: str, source_sha: str) -> str:
    return f"""ANEB Probe Debug 候选安装说明

这不是正式签名 Release，不能用于公开商店发布或正式取证签名声明。

版本：{identity.version_name}（versionCode {identity.version_code}）
包名：{identity.package_id}
文件：{apk_name}
SHA-256：{apk_sha256}
源码提交：{source_sha or '未提供'}

安装步骤：
1. 解压 GitHub Actions 下载的压缩包。
2. 在电脑上核对 checksums.sha256；Windows 可运行：
   Get-FileHash -Algorithm SHA256 \"{apk_name}\"
3. 把 APK 传到手机，在系统“文件管理”中点击安装；如系统询问，仅对当前文件管理来源临时允许安装。
4. 桌面名称应为 ANEB Probe，应用详情包名应为 {identity.package_id}，可与 Claude 包并存。
5. 测试结束后返回桌面并彻底退出 ANEB。

如果版本、包名或 SHA-256 任一不匹配，请不要安装。
"""


def package_candidate(
    apk: Path,
    metadata_path: Path,
    output: Path,
    badging: str,
    signer: str,
    source_sha: str = "",
    source_ref: str = "",
    run_url: str = "",
) -> dict[str, object]:
    verify_apk_zip(apk)
    metadata = load_gradle_metadata(metadata_path)
    identity = parse_identity(badging, signer)
    verify_debug_boundary(identity, metadata, apk)
    output = _assert_nonreparse_lexical_chain(
        output,
        reason="candidate_output_invalid",
    )
    if output.exists() and any(output.iterdir()):
        raise CandidateError("candidate_output_not_empty")
    output.mkdir(parents=True, exist_ok=True)
    _assert_nonreparse_lexical_chain(output, reason="candidate_output_invalid")

    apk_name = f"ANEB-Probe-{identity.version_name}-debug.apk"
    packaged_apk = output / apk_name
    shutil.copy2(apk, packaged_apk)
    apk_digest = sha256(packaged_apk)
    manifest: dict[str, object] = {
        "contract_version": CONTRACT_VERSION,
        "artifact_kind": "debug_non_release",
        "public_release": False,
        "source": {
            "git_sha": source_sha,
            "git_ref": source_ref,
            "workflow_run_url": run_url,
        },
        "apk": {
            "file_name": apk_name,
            "sha256": apk_digest,
            "size_bytes": packaged_apk.stat().st_size,
            "package_id": identity.package_id,
            "version_name": identity.version_name,
            "version_code": identity.version_code,
            "min_sdk": identity.min_sdk,
            "target_sdk": identity.target_sdk,
            "signer_dn": identity.signer_dn,
            "signer_sha256": identity.signer_sha256,
        },
        "verification": {
            "zip_integrity": "pass",
            "gradle_apk_identity_match": "pass",
            "apksigner_verification": "pass",
            "debug_boundary": "pass",
        },
    }
    manifest_path = output / "build-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output / INSTALL_NOTES_NAME).write_text(
        installation_text(identity, apk_name, apk_digest, source_sha),
        encoding="utf-8",
    )
    checksums = [
        f"{apk_digest}  {apk_name}",
        f"{sha256(manifest_path)}  {manifest_path.name}",
        f"{sha256(output / INSTALL_NOTES_NAME)}  {INSTALL_NOTES_NAME}",
    ]
    (output / "checksums.sha256").write_text("\n".join(checksums) + "\n", encoding="utf-8")
    return manifest


def _rebuild_checksums(output: Path) -> dict[str, str]:
    try:
        paths = list(output.iterdir())
    except OSError as error:
        raise CandidateError("candidate_inventory_invalid") from error
    names = {path.name for path in paths}
    apk_names = {
        name
        for name in names
        if re.fullmatch(r"ANEB-Probe-[A-Za-z0-9._-]+-debug\.apk", name)
    }
    expected_names = {
        "checksums.sha256",
        "build-manifest.json",
        INSTALL_NOTES_NAME,
        PROVENANCE_BUNDLE_NAME,
        *apk_names,
    }
    if (
        len(apk_names) != 1
        or names != expected_names
        or not any(path.name == "checksums.sha256" for path in paths)
        or any(
            not path.is_file()
            or _is_reparse(path)
            or path.name.startswith(".")
            or unicodedata.normalize("NFC", path.name) != path.name
            or any(ord(character) < 32 or ord(character) == 127 for character in path.name)
            for path in paths
        )
    ):
        raise CandidateError("candidate_inventory_invalid")
    try:
        sizes = {path.name: path.stat().st_size for path in paths}
    except OSError as error:
        raise CandidateError("candidate_inventory_invalid") from error
    if (
        any(not 0 < size <= MAX_CANDIDATE_PAYLOAD_BYTES for size in sizes.values())
        or sum(sizes.values()) > MAX_CANDIDATE_TOTAL_BYTES
    ):
        raise CandidateError("candidate_inventory_invalid")
    payloads = sorted(
        (path for path in paths if path.name != "checksums.sha256"),
        key=lambda path: path.name,
    )
    try:
        inventory = {path.name: sha256(path) for path in payloads}
    except OSError as error:
        raise CandidateError("candidate_inventory_invalid") from error
    content = "".join(
        f"{digest}  {name}\n" for name, digest in inventory.items()
    ).encode("utf-8")
    temporary_name: str | None = None
    try:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=".checksums.", suffix=".tmp", dir=output
        )
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, output / "checksums.sha256")
        temporary_name = None
    except OSError as error:
        raise CandidateError("checksums_rebuild_failed") from error
    finally:
        if temporary_name is not None:
            try:
                Path(temporary_name).unlink()
            except OSError:
                pass
    return inventory


def finalize_candidate(candidate: Path, bundle: Path) -> dict[str, object]:
    """Attach one Sigstore bundle and atomically rebuild the candidate inventory."""

    candidate = _assert_nonreparse_lexical_chain(
        candidate,
        reason="candidate_directory_invalid",
    )
    bundle = _assert_nonreparse_lexical_chain(
        bundle,
        reason="attestation_bundle_invalid",
    )
    if not candidate.is_dir() or _is_reparse(candidate):
        raise CandidateError("candidate_directory_invalid")
    if not bundle.is_file() or _is_reparse(bundle):
        raise CandidateError("attestation_bundle_invalid")
    try:
        candidate = candidate.resolve(strict=True)
        bundle = bundle.resolve(strict=True)
    except OSError as error:
        raise CandidateError("candidate_path_invalid") from error
    if candidate == bundle or candidate in bundle.parents:
        raise CandidateError("attestation_bundle_boundary_invalid")
    try:
        raw = _read_regular_file(
            bundle,
            maximum=MAX_PROVENANCE_BUNDLE_BYTES,
            reason="attestation_bundle_invalid",
        )
        parsed = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_unique_json_object,
            parse_constant=_reject_json_constant,
        )
    except (
        OSError,
        UnicodeError,
        json.JSONDecodeError,
        DuplicateJsonKey,
        NonstandardJsonConstant,
    ) as error:
        raise CandidateError("attestation_bundle_invalid") from error
    if not 0 < len(raw) <= MAX_PROVENANCE_BUNDLE_BYTES or not isinstance(parsed, dict):
        raise CandidateError("attestation_bundle_invalid")
    target = candidate / PROVENANCE_BUNDLE_NAME
    try:
        with target.open("xb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        inventory = _rebuild_checksums(candidate)
    except FileExistsError as error:
        raise CandidateError("attestation_bundle_target_exists") from error
    except Exception:
        try:
            target.unlink()
        except OSError:
            pass
        raise
    return {
        "bundle_sha256": inventory[PROVENANCE_BUNDLE_NAME],
        "payload_count": len(inventory),
    }


def build_tool(name: str, version: str = "") -> Path:
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home:
        raise CandidateError("android_sdk_environment_missing")
    root = Path(android_home) / "build-tools"
    if not root.is_dir():
        raise CandidateError("android_build_tools_directory_missing")

    def version_key(path: Path) -> tuple[int, ...]:
        values = re.findall(r"\d+", path.name)
        return tuple(int(value) for value in values) if values else (0,)

    if version:
        if re.fullmatch(r"\d+(?:\.\d+){1,3}", version) is None:
            raise CandidateError("android_build_tools_version_invalid")
        candidates = [root / version]
    else:
        candidates = sorted((path for path in root.iterdir() if path.is_dir()), key=version_key, reverse=True)
    suffixes = [".exe"] if name == "aapt2" and os.name == "nt" else [".bat", ".cmd", ".exe"] if os.name == "nt" else [""]
    for directory in candidates:
        for suffix in suffixes:
            candidate = directory / f"{name}{suffix}"
            if candidate.is_file():
                return candidate
    requested = f":{version}" if version else ""
    raise CandidateError(f"android_build_tool_missing:{name}{requested}")


def run_tool(tool: Path, *arguments: str) -> str:
    command = [str(tool), *arguments]
    if os.name == "nt" and tool.suffix.lower() in {".bat", ".cmd"}:
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *command]
    completed = subprocess.run(command, text=True, encoding="utf-8", errors="replace", capture_output=True, check=False)
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip().replace("\n", " | ")
        raise CandidateError(f"android_build_tool_failed:{tool.name}:{detail}")
    return (completed.stdout + completed.stderr).strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--finalize-bundle",
        type=Path,
        help="Attach the actions/attest bundle and atomically rebuild checksums.",
    )
    parser.add_argument("--source-sha", default="")
    parser.add_argument("--source-ref", default="")
    parser.add_argument("--run-url", default="")
    parser.add_argument(
        "--build-tools-version",
        default=os.environ.get("ANEB_BUILD_TOOLS_VERSION", ""),
        help="Exact Android build-tools version used for identity/signature verification.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.finalize_bundle is not None:
        if args.apk is not None or args.metadata is not None:
            print(
                "ANEB debug candidate finalization: FAIL: "
                "finalize_mode_arguments_invalid",
                file=sys.stderr,
            )
            return 1
        try:
            report = finalize_candidate(args.output, args.finalize_bundle)
        except CandidateError as error:
            print(f"ANEB debug candidate finalization: FAIL: {error}", file=sys.stderr)
            return 1
        print(
            "ANEB debug candidate finalization: PASS: "
            f"bundle_sha256={report['bundle_sha256']} "
            f"payload_count={report['payload_count']}"
        )
        return 0
    if args.apk is None or args.metadata is None:
        print(
            "ANEB debug candidate packaging: FAIL: apk_and_metadata_required",
            file=sys.stderr,
        )
        return 1
    try:
        badging = run_tool(build_tool("aapt2", args.build_tools_version), "dump", "badging", str(args.apk))
        signer = run_tool(
            build_tool("apksigner", args.build_tools_version),
            "verify",
            "--print-certs",
            str(args.apk),
        )
        manifest = package_candidate(
            args.apk,
            args.metadata,
            args.output,
            badging,
            signer,
            args.source_sha,
            args.source_ref,
            args.run_url,
        )
    except CandidateError as error:
        print(f"ANEB debug candidate packaging: FAIL: {error}", file=sys.stderr)
        return 1
    apk = manifest["apk"]
    print(
        "ANEB debug candidate packaging: PASS: "
        f"{apk['package_id']} {apk['version_name']} sha256={apk['sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
