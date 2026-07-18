#!/usr/bin/env python3
"""Create a fail-closed, identity-verified ANEB Debug installation bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path


CONTRACT_VERSION = "aneb-debug-candidate-v1"
EXPECTED_PACKAGE = "com.aneb.probe.codex"
PACKAGE_RE = re.compile(
    r"package:\s+name='(?P<package>[^']+)'\s+versionCode='(?P<code>[^']+)'\s+"
    r"versionName='(?P<name>[^']+)'"
)
MIN_SDK_RE = re.compile(r"minSdkVersion:'(?P<value>[^']+)'")
TARGET_SDK_RE = re.compile(r"targetSdkVersion:'(?P<value>[^']+)'")
CERT_DN_RE = re.compile(r"Signer #1 certificate DN:\s*(?P<value>.+)")
CERT_SHA_RE = re.compile(r"Signer #1 certificate SHA-256 digest:\s*(?P<value>[0-9a-fA-F]{64})")


class CandidateError(ValueError):
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


def parse_identity(badging: str, signer: str) -> ApkIdentity:
    package = PACKAGE_RE.search(badging)
    min_sdk = MIN_SDK_RE.search(badging)
    target_sdk = TARGET_SDK_RE.search(badging)
    signer_dn = CERT_DN_RE.search(signer)
    signer_sha = CERT_SHA_RE.search(signer)
    if not all((package, min_sdk, target_sdk, signer_dn, signer_sha)):
        raise CandidateError("apk_identity_output_incomplete")
    try:
        return ApkIdentity(
            package_id=package.group("package"),
            version_code=int(package.group("code")),
            version_name=package.group("name"),
            min_sdk=int(min_sdk.group("value")),
            target_sdk=int(target_sdk.group("value")),
            signer_dn=signer_dn.group("value").strip(),
            signer_sha256=signer_sha.group("value").upper(),
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

如果版本、包名或 SHA-256 任一不匹配，不要安装。
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
    if output.exists() and any(output.iterdir()):
        raise CandidateError("candidate_output_not_empty")
    output.mkdir(parents=True, exist_ok=True)

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
    (output / "ANEB-安装说明.txt").write_text(
        installation_text(identity, apk_name, apk_digest, source_sha),
        encoding="utf-8",
    )
    checksums = [
        f"{apk_digest}  {apk_name}",
        f"{sha256(manifest_path)}  {manifest_path.name}",
        f"{sha256(output / 'ANEB-安装说明.txt')}  ANEB-安装说明.txt",
    ]
    (output / "checksums.sha256").write_text("\n".join(checksums) + "\n", encoding="utf-8")
    return manifest


def build_tool(name: str) -> Path:
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home:
        raise CandidateError("android_sdk_environment_missing")
    root = Path(android_home) / "build-tools"
    if not root.is_dir():
        raise CandidateError("android_build_tools_directory_missing")

    def version_key(path: Path) -> tuple[int, ...]:
        values = re.findall(r"\d+", path.name)
        return tuple(int(value) for value in values) if values else (0,)

    candidates = sorted((path for path in root.iterdir() if path.is_dir()), key=version_key, reverse=True)
    suffixes = [".exe"] if name == "aapt2" and os.name == "nt" else [".bat", ".cmd", ".exe"] if os.name == "nt" else [""]
    for directory in candidates:
        for suffix in suffixes:
            candidate = directory / f"{name}{suffix}"
            if candidate.is_file():
                return candidate
    raise CandidateError(f"android_build_tool_missing:{name}")


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
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-sha", default="")
    parser.add_argument("--source-ref", default="")
    parser.add_argument("--run-url", default="")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        badging = run_tool(build_tool("aapt2"), "dump", "badging", str(args.apk))
        signer = run_tool(build_tool("apksigner"), "verify", "--print-certs", str(args.apk))
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
