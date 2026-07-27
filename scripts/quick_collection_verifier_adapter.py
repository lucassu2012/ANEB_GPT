#!/usr/bin/env python3
"""Family-neutral high-level mechanics for Quick collection verification.

Families provide immutable identity contracts and business validators.  This
module owns only the shared evidence mechanics and never imports a Token,
Realtime, or Network collector/verifier.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import stat
from typing import Any, Callable

if __package__:
    from scripts import quick_collection_verifier as core
else:
    import quick_collection_verifier as core


MAX_JSON_BYTES = 32 * 1024 * 1024
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
MAX_APK_BYTES = 256 * 1024 * 1024
MAX_TEXT_BYTES = 16 * 1024 * 1024
REMOTE_CURSOR_RE = re.compile(r"^[A-Za-z0-9;:_.=-]{10,1024}$")
TUNNEL_INTERFACE_RE = re.compile(
    r"^(?:tun[0-9]*|tap[0-9]*|wg[0-9A-Za-z_.-]*|"
    r"wireguard[0-9A-Za-z_.-]*)$",
    re.IGNORECASE,
)
REMOTE_KEYS = (
    "boot_id",
    "systemd_invocation_id",
    "main_pid",
    "server_binary_sha256",
    "eth0_qdisc_sha256",
    "firewall_full_sha256",
    "firewall_v4_sha256",
    "firewall_v6_sha256",
    "firewall_nft_sha256",
    "docker_sha256",
    "journal_cursor",
)
REMOTE_STABLE_KEYS = tuple(key for key in REMOTE_KEYS if key != "journal_cursor")
DEFAULT_RELEVANT_PACKAGES = (
    "com.aneb.probe.codex",
    "com.aneb.probe",
    "com.aneb.experiencelab",
    "com.moonshot.kimichat",
    "com.moonshot.kimiclaw",
    "com.deepseek.chat",
    "com.larus.nova",
    "com.aliyun.tongyi",
    "com.ss.android.ugc.aweme",
    "com.wireguard.android",
    "com.emanuelef.remote_capture",
    "com.pcapdroid.mitm",
)
DEFAULT_LAUNCHER_COMPONENT = (
    "com.huawei.android.launcher/.unihome.UniHomeLauncher"
)
DEFAULT_DEVICE_PROPERTY_KEYS = (
    "ro.serialno",
    "ro.boot.serialno",
    "ro.product.manufacturer",
    "ro.product.model",
    "ro.product.device",
    "ro.product.name",
    "ro.build.fingerprint",
    "ro.build.version.security_patch",
    "ro.boot.verifiedbootstate",
    "ro.boot.vbmeta.device_state",
    "ro.boot.flash.locked",
    "ro.boot.veritymode",
)
DEFAULT_OPTIONAL_DEVICE_PROPERTY_KEYS = frozenset(
    {
        "ro.serialno",
        "ro.boot.serialno",
        "ro.boot.verifiedbootstate",
        "ro.boot.vbmeta.device_state",
        "ro.boot.flash.locked",
        "ro.boot.veritymode",
    }
)
DEFAULT_NEGATIVE_REQUIRED_PATHS = frozenset(
    {
        "negative-proxy/upstream-serverinfo.raw",
        "negative-proxy/filtered-serverinfo.json",
        "negative-proxy/upstream-serverinfo.headers.json",
        "negative-proxy/peer-certificate.sha256",
        "negative-proxy/request-ledger.json",
        "negative-proxy/proxy-receipt.json",
        "negative-proxy.stdout.jsonl",
        "negative-proxy.stderr.txt",
        "negative-proxy-delivery-receipt.json",
        "adb-reverse-preflight.txt",
        "adb-reverse-active.txt",
        "adb-reverse-before-remove.txt",
        "adb-reverse-final.txt",
    }
)
PLAN_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "collection_id",
        "profile_contract",
        "evidence_mode",
        "transport",
        "package_name",
        "version_name",
        "version_code",
        "server_base",
        "client_server_base",
        "negative_upstream_url",
        "remote_host",
        "expected_server_version",
        "expected_server_binary_sha256",
        "expected_apk_sha256",
        "expected_signer_sha256",
        "source_commit",
        "workflow_run_id",
        "server_ca_sha256",
        "device_policy_sha256",
        "adb_serial_sha256",
        "start_barrier_id",
        "end_barrier_id",
        "run_timeout_seconds",
        "lock_ttl_seconds",
        "install_candidate",
    }
)
STATUS_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "collection_id",
        "run_id",
        "mode",
        "cleanup_phone",
        "cleanup_remote",
    }
)
RUN_RECEIPT_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "collection_id",
        "run_id",
        "mode",
        "terminal_status",
        "contract_status",
        "cross_bound_report_sha256",
        "cross_bound",
    }
)


@dataclass(frozen=True)
class CandidateContract:
    apk_name: str
    files: frozenset[str]
    package_name: str
    version_name: str
    version_code: int
    repository: str = "lucassu2012/ANEB_GPT"
    signer_workflow: str = "lucassu2012/ANEB_GPT/.github/workflows/ci.yml"


@dataclass(frozen=True)
class PhoneStateContract:
    receipt_schema: str
    launcher_component: str
    relevant_packages: tuple[str, ...]


@dataclass(frozen=True)
class DeviceIdentityContract:
    identity_schema: str
    property_keys: tuple[str, ...]
    optional_property_keys: frozenset[str]


@dataclass(frozen=True)
class QuickCollectionVerifierAdapter:
    manifest_schema: str
    complete_marker: str
    remote_marker_prefix: str
    candidate: CandidateContract
    phone: PhoneStateContract
    device_identity: DeviceIdentityContract
    evidence_root_validator: Callable[[Path, dict[str, Any]], None]
    serverinfo_body_validator: Callable[[object], None]
    serverinfo_sequence_validator: Callable[[list[dict[str, Any]]], None]
    negative_required_paths: frozenset[str]

    def verify_manifest(self, bundle: Path) -> tuple[dict[str, Any], str]:
        return verify_manifest(bundle, expected_schema=self.manifest_schema)

    def verify_evidence_root_security(self, bundle: Path) -> str:
        return verify_evidence_root_security(bundle, self.evidence_root_validator)

    def verify_mode_inventory(self, manifest: dict[str, Any], *, mode: str) -> None:
        verify_mode_inventory(
            manifest,
            mode=mode,
            negative_required_paths=self.negative_required_paths,
        )

    def verify_complete(
        self,
        bundle: Path,
        *,
        collection: str,
        run_id: str,
        manifest_sha256: str,
    ) -> None:
        verify_complete(
            bundle,
            collection=collection,
            run_id=run_id,
            manifest_sha256=manifest_sha256,
            marker=self.complete_marker,
        )

    def verify_candidate(self, bundle: Path, plan: dict[str, Any]) -> None:
        verify_candidate(bundle, plan, contract=self.candidate)

    def verify_device_identity(self, bundle: Path, plan: dict[str, Any]) -> None:
        verify_device_identity(bundle, plan, contract=self.device_identity)

    def verify_phone_pair(self, bundle: Path, prefix: str) -> str:
        return verify_phone_pair(bundle, prefix, contract=self.phone)

    def verify_remote(self, bundle: Path, plan: dict[str, Any]) -> str:
        return verify_remote(bundle, plan)

    def verify_lock(self, bundle: Path) -> str:
        return verify_lock(bundle, marker_prefix=self.remote_marker_prefix)

    def verify_serverinfo(self, bundle: Path) -> str:
        return verify_serverinfo(
            bundle,
            body_validator=self.serverinfo_body_validator,
            sequence_validator=self.serverinfo_sequence_validator,
        )


def verify_manifest(
    bundle: Path,
    *,
    expected_schema: str,
) -> tuple[dict[str, Any], str]:
    manifest, raw = core.load_json(
        bundle / "evidence-manifest.json",
        "manifest_invalid",
        maximum=MAX_MANIFEST_BYTES,
    )
    if (
        not core.exact(manifest, {"schema", "schema_version", "files"})
        or manifest.get("schema") != expected_schema
        or manifest.get("schema_version") != "1.0.0"
        or not isinstance(manifest.get("files"), list)
    ):
        core.fail("manifest_contract_invalid")
    actual: dict[str, Path] = {}
    try:
        for path in sorted(bundle.rglob("*")):
            metadata = path.lstat()
            if core.is_reparse(metadata):
                core.fail("collection_path_reparse_forbidden")
            if stat.S_ISREG(metadata.st_mode):
                relative = path.relative_to(bundle).as_posix()
                if relative not in {"evidence-manifest.json", "COMPLETE"}:
                    actual[relative] = path
            elif not stat.S_ISDIR(metadata.st_mode):
                core.fail("collection_entry_type_invalid")
    except core.CollectionVerificationFailure:
        raise
    except OSError:
        core.fail("manifest_inventory_unreadable")
    observed: list[str] = []
    for record in manifest["files"]:
        if (
            not core.exact(record, {"path", "bytes", "sha256"})
            or type(record.get("bytes")) is not int
            or int(record["bytes"]) < 0
            or not isinstance(record.get("sha256"), str)
            or core.SHA256_RE.fullmatch(str(record["sha256"])) is None
        ):
            core.fail("manifest_record_invalid")
        relative = core.safe_relative(record["path"])
        if relative in observed or relative not in actual:
            core.fail("manifest_record_invalid")
        payload = core.read_regular(
            actual[relative],
            maximum=MAX_APK_BYTES,
            reason="manifest_file_unreadable",
            allow_empty=True,
        )
        if len(payload) != record["bytes"] or core.sha256(payload) != record["sha256"]:
            core.fail("manifest_file_binding_mismatch")
        observed.append(relative)
    if observed != list(actual):
        core.fail("manifest_coverage_mismatch")
    return manifest, core.sha256(raw)


def _validate_candidate_report(
    report: dict[str, Any],
    *,
    source_commit: str,
    contract: CandidateContract,
) -> tuple[str, int, str]:
    root_keys = {
        "schema", "schema_version", "status", "reason_code",
        "candidate_provenance_reverified", "repository", "signer_workflow",
        "predicate_type", "source_commit", "source_ref", "workflow_run_id",
        "workflow_run_url", "apk", "files", "gh",
    }
    if (
        not core.exact(report, root_keys)
        or report.get("schema") != "aneb-ci-apk-provenance-report"
        or report.get("schema_version") != "1.0.0"
        or report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or report.get("candidate_provenance_reverified") is not True
        or report.get("repository") != contract.repository
        or report.get("signer_workflow") != contract.signer_workflow
        or report.get("predicate_type") != "https://slsa.dev/provenance/v1"
        or report.get("source_commit") != source_commit
        or not isinstance(report.get("source_ref"), str)
        or re.fullmatch(r"refs/(?:heads|tags)/[^\r\n]{1,512}", report["source_ref"])
        is None
        or type(report.get("workflow_run_id")) is not int
        or int(report["workflow_run_id"]) <= 0
        or report.get("workflow_run_url")
        != f"https://github.com/{contract.repository}/actions/runs/{report.get('workflow_run_id')}"
    ):
        core.fail("candidate_report_invalid")
    apk, files, gh = report["apk"], report["files"], report["gh"]
    if (
        not core.exact(
            apk,
            {"file_name", "sha256", "size_bytes", "package_name", "version_name", "version_code", "signer_sha256"},
        )
        or not core.exact(files, {"attestation_bundle_sha256", "build_manifest_sha256", "checksums_sha256"})
        or not core.exact(
            gh,
            {"version", "executable_sha256", "certificate_issuer", "oidc_issuer", "runner_environment", "run_invocation_uri", "subject_alternative_name", "verified_timestamp_count"},
        )
        or apk.get("file_name") != contract.apk_name
        or apk.get("package_name") != contract.package_name
        or apk.get("version_name") != contract.version_name
        or apk.get("version_code") != contract.version_code
        or type(apk.get("size_bytes")) is not int
        or not 0 < int(apk["size_bytes"]) <= MAX_APK_BYTES
        or not isinstance(apk.get("sha256"), str)
        or core.SHA256_RE.fullmatch(apk["sha256"]) is None
        or not isinstance(apk.get("signer_sha256"), str)
        or core.SHA256_RE.fullmatch(apk["signer_sha256"]) is None
        or any(not isinstance(files.get(key), str) or core.SHA256_RE.fullmatch(files[key]) is None for key in files)
        or not isinstance(gh.get("version"), str)
        or re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", gh["version"]) is None
        or not isinstance(gh.get("executable_sha256"), str)
        or core.SHA256_RE.fullmatch(gh["executable_sha256"]) is None
        or gh.get("runner_environment") != "github-hosted"
        or type(gh.get("verified_timestamp_count")) is not int
        or int(gh["verified_timestamp_count"]) <= 0
    ):
        core.fail("candidate_report_invalid")
    return str(apk["sha256"]), int(apk["size_bytes"]), str(apk["signer_sha256"])


def verify_candidate(bundle: Path, plan: dict[str, Any], *, contract: CandidateContract) -> None:
    reports = [
        core.load_json(bundle / leaf, "candidate_report_invalid")[0]
        for leaf in ("ci-source-before.json", "ci-candidate-verification.json", "ci-source-after.json")
    ]
    if reports[0] != reports[1] or reports[0] != reports[2]:
        core.fail("candidate_report_drift")
    apk_sha, apk_size, signer_sha = _validate_candidate_report(
        reports[0], source_commit=plan["source_commit"], contract=contract
    )
    if (
        reports[0]["workflow_run_id"] != plan["workflow_run_id"]
        or apk_sha != plan["expected_apk_sha256"]
        or signer_sha != plan["expected_signer_sha256"]
    ):
        core.fail("candidate_plan_binding_mismatch")
    candidate = bundle / "ci-candidate"
    core.assert_directory(candidate, "candidate_directory_invalid")
    try:
        names = {path.name for path in candidate.iterdir()}
    except OSError:
        core.fail("candidate_directory_invalid")
    if names != contract.files:
        core.fail("candidate_file_set_invalid")
    candidate_apk = candidate / contract.apk_name
    if candidate_apk.stat().st_size != apk_size or core.sha256_file(candidate_apk) != apk_sha:
        core.fail("candidate_apk_binding_mismatch")
    digest_map = {
        "attestation_bundle_sha256": "provenance.sigstore.json",
        "build_manifest_sha256": "build-manifest.json",
        "checksums_sha256": "checksums.sha256",
    }
    report_files = reports[0]["files"]
    for field, leaf in digest_map.items():
        if core.sha256_file(candidate / leaf, maximum=MAX_TEXT_BYTES) != report_files[field]:
            core.fail("candidate_auxiliary_binding_mismatch")
    installed = core.read_regular(bundle / "installed-base.apk", maximum=MAX_APK_BYTES, reason="installed_apk_invalid")
    if len(installed) != apk_size or core.sha256(installed) != apk_sha:
        core.fail("installed_apk_binding_mismatch")
    package_dump = core.read_regular(bundle / "installed-package.txt", maximum=MAX_TEXT_BYTES, reason="installed_package_invalid")
    try:
        package_text = package_dump.decode("utf-8", errors="strict")
    except UnicodeError:
        core.fail("installed_package_invalid")
    version_codes = re.findall(r"(?m)^\s*versionCode=([0-9]+)\b", package_text)
    version_names = re.findall(r"(?m)^\s*versionName=([^\r\n]+)\r?$", package_text)
    if (
        not version_codes
        or any(int(value) != contract.version_code for value in version_codes)
        or not version_names
        or any(value.strip() != contract.version_name for value in version_names)
    ):
        core.fail("installed_package_invalid")
    install_path = bundle / "adb-install.txt"
    if plan["install_candidate"]:
        install = core.read_regular(install_path, maximum=MAX_TEXT_BYTES, reason="install_receipt_invalid")
        try:
            install_text = install.decode("utf-8", errors="strict")
        except UnicodeError:
            core.fail("install_receipt_invalid")
        if "Success" not in install_text.splitlines():
            core.fail("install_receipt_invalid")
    elif install_path.exists():
        core.fail("install_receipt_unexpected")


def _active_vpn(connectivity: str) -> bool:
    for line in connectivity.splitlines():
        if "NetworkAgentInfo{" not in line:
            continue
        if re.search(r"(?i)(?:Transports?:\s*VPN|type:\s*VPN)", line) is None:
            continue
        active = re.search(r"(?i)(?:state:\s*CONNECTED(?:/CONNECTED)?|CONNECTED/CONNECTED|\bVALIDATED\b)", line)
        disconnected = re.search(r"(?i)(?:state:\s*DISCONNECTED|DISCONNECTED/DISCONNECTED)", line)
        if active is not None and disconnected is None:
            return True
    return False


def _is_empty_service_dump(value: str) -> bool:
    normalized = value.strip()
    return normalized in {"", "(nothing)", "No services"} or re.fullmatch(
        r"ACTIVITY MANAGER SERVICES \(dumpsys activity services\)\r?\n[ \t]*\(nothing\)",
        normalized,
    ) is not None


def phone_state(raw: dict[str, Any], *, contract: PhoneStateContract) -> dict[str, object]:
    expected = {"device_state", "current_user", "activity", "processes", "services", "enabled_accessibility", "accessibility_dump", "interfaces", "connectivity", "vpn", "stayon", "wifi_on"}
    if set(raw) != expected or raw.get("device_state") != "device" or raw.get("current_user") != "0":
        core.fail("phone_state_invalid")
    activity = raw.get("activity")
    if not isinstance(activity, str):
        core.fail("phone_state_invalid")
    focused_lines = [line for line in activity.splitlines() if re.match(r"^\s*mFocusedApp=", line)]
    resumed_lines = [line for line in activity.splitlines() if re.match(r"^\s*(?:topResumedActivity|mResumedActivity|ResumedActivity)\s*[:=]", line)]
    if len(focused_lines) != 1 or not resumed_lines:
        core.fail("phone_state_invalid")
    focused = core.canonical_component(focused_lines[0], reason="phone_state_invalid")
    resumed = tuple(core.canonical_component(line, reason="phone_state_invalid") for line in resumed_lines)
    launcher = core.canonical_component(contract.launcher_component, reason="phone_state_invalid")
    if focused != launcher or any(item != launcher for item in resumed):
        core.fail("phone_state_invalid")
    processes_raw, services_raw = raw.get("processes"), raw.get("services")
    if (
        not isinstance(processes_raw, dict)
        or not isinstance(services_raw, dict)
        or set(processes_raw) != set(contract.relevant_packages)
        or set(services_raw) != set(contract.relevant_packages)
    ):
        core.fail("phone_state_invalid")
    processes: list[list[str]] = []
    services: list[list[str]] = []
    for package in contract.relevant_packages:
        process, service = processes_raw[package], services_raw[package]
        if not isinstance(process, str) or not isinstance(service, str):
            core.fail("phone_state_invalid")
        process = process.strip()
        if process or not _is_empty_service_dump(service):
            core.fail("phone_state_invalid")
        processes.append([package, process])
        services.append([package, ""])
    accessibility, accessibility_dump = raw.get("enabled_accessibility"), raw.get("accessibility_dump")
    if not isinstance(accessibility, str) or not isinstance(accessibility_dump, str):
        core.fail("phone_state_invalid")
    accessibility = accessibility.strip()
    if any(package in accessibility for package in contract.relevant_packages):
        core.fail("phone_state_invalid")
    for line in accessibility_dump.splitlines():
        if re.search(r"(?i)\b(?:bound|enabled)\b.*\bservices?\b", line) and any(package in line for package in contract.relevant_packages):
            core.fail("phone_state_invalid")
    interfaces_raw, connectivity, vpn_dump = raw.get("interfaces"), raw.get("connectivity"), raw.get("vpn")
    if not all(isinstance(value, str) for value in (interfaces_raw, connectivity, vpn_dump)):
        core.fail("phone_state_invalid")
    assert isinstance(interfaces_raw, str) and isinstance(connectivity, str) and isinstance(vpn_dump, str)
    interfaces = sorted({line.strip() for line in interfaces_raw.splitlines() if line.strip()})
    if any(TUNNEL_INTERFACE_RE.fullmatch(item) for item in interfaces):
        core.fail("phone_state_invalid")
    active_vpn = _active_vpn(connectivity)
    vpn_service_active = any(
        "LISTEN" not in line.upper()
        and re.search(r"(?i)(?:state\s*[:=]\s*CONNECTED|mNetworkInfo.*\bCONNECTED(?:/CONNECTED)?\b)", line)
        for line in vpn_dump.splitlines()
    )
    if active_vpn or vpn_service_active:
        core.fail("phone_state_invalid")
    stayon, wifi_on = raw.get("stayon"), raw.get("wifi_on")
    if (
        not isinstance(stayon, str)
        or re.fullmatch(r"(?:null|[0-9]+)", stayon) is None
        or not isinstance(wifi_on, str)
        or wifi_on not in {"0", "1"}
    ):
        core.fail("phone_state_invalid")
    return {"focused_component": focused, "resumed_components": list(resumed), "processes": processes, "services": services, "enabled_accessibility": accessibility, "interfaces": interfaces, "active_vpn": active_vpn, "stayon": stayon, "wifi_on": wifi_on}


def phone_state_sha256(raw: dict[str, Any], *, contract: PhoneStateContract) -> str:
    return core.sha256(core.canonical_json(phone_state(raw, contract=contract)))


def verify_phone_pair(bundle: Path, prefix: str, *, contract: PhoneStateContract) -> str:
    first = core.load_json(bundle / f"{prefix}-t0-raw.json", "phone_state_invalid")[0]
    second = core.load_json(bundle / f"{prefix}-t2-raw.json", "phone_state_invalid")[0]
    receipt = core.load_json(bundle / f"{prefix}-receipt.json", "phone_receipt_invalid")[0]
    first_state = phone_state(first, contract=contract)
    second_state = phone_state(second, contract=contract)
    first_hash = core.sha256(core.canonical_json(first_state))
    second_hash = core.sha256(core.canonical_json(second_state))
    if first_hash != second_hash:
        core.fail("phone_state_not_stable")
    if (
        not core.exact(receipt, {"schema", "schema_version", "status", "reason_code", "stable", "t0_sha256", "t2_sha256", "focused_component", "stayon", "wifi_on"})
        or receipt.get("schema") != contract.receipt_schema
        or receipt.get("schema_version") != "1.0.0"
        or receipt.get("status") != "pass"
        or receipt.get("reason_code") != "ok"
        or receipt.get("stable") is not True
        or receipt.get("t0_sha256") != first_hash
        or receipt.get("t2_sha256") != second_hash
        or receipt.get("focused_component") != second_state["focused_component"]
        or receipt.get("stayon") != second_state["stayon"]
        or receipt.get("wifi_on") != second_state["wifi_on"]
    ):
        core.fail("phone_receipt_invalid")
    return first_hash


def verify_device_identity(bundle: Path, plan: dict[str, Any], *, contract: DeviceIdentityContract) -> None:
    policy, policy_raw = core.load_json(bundle / "device-policy.json", "device_policy_invalid", require_canonical=False)
    identity = core.load_json(bundle / "device-identity.json", "device_identity_invalid")[0]
    if (
        not core.exact(policy, {"schema", "schema_version", "device_alias", "adb_serial_sha256", "properties"})
        or policy.get("schema") != "aneb-device-identity-policy"
        or policy.get("schema_version") != "1.0.0"
        or policy.get("device_alias") != "P40 Pro"
        or policy.get("adb_serial_sha256") != plan["adb_serial_sha256"]
        or core.sha256(policy_raw) != plan["device_policy_sha256"]
        or not core.exact(policy.get("properties"), set(contract.property_keys))
    ):
        core.fail("device_policy_invalid")
    properties = policy["properties"]
    for key in contract.property_keys:
        value = properties[key]
        if (
            not isinstance(value, str)
            or "\r" in value or "\n" in value or "\x00" in value
            or len(value.encode("utf-8")) > 2048
            or (key not in contract.optional_property_keys and not value)
        ):
            core.fail("device_policy_invalid")
    if (
        not core.exact(identity, {"schema", "schema_version", "status", "adb_serial_sha256", "android_boot_id", "properties"})
        or identity.get("schema") != contract.identity_schema
        or identity.get("schema_version") != "1.0.0"
        or identity.get("status") != "pass"
        or identity.get("adb_serial_sha256") != policy["adb_serial_sha256"]
        or identity.get("properties") != properties
        or not isinstance(identity.get("android_boot_id"), str)
        or re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", identity["android_boot_id"]) is None
    ):
        core.fail("device_identity_invalid")


def _validate_remote(value: dict[str, Any]) -> dict[str, str]:
    if set(value) != set(REMOTE_KEYS) or any(not isinstance(value.get(key), str) for key in REMOTE_KEYS):
        core.fail("remote_snapshot_invalid")
    result = {key: str(value[key]) for key in REMOTE_KEYS}
    if (
        re.fullmatch(r"[0-9a-f]{32}", result["boot_id"]) is None
        or re.fullmatch(r"[0-9a-f]{32}", result["systemd_invocation_id"]) is None
        or re.fullmatch(r"[1-9][0-9]*", result["main_pid"]) is None
        or any(core.SHA256_RE.fullmatch(result[key]) is None for key in REMOTE_KEYS if key.endswith("_sha256"))
        or REMOTE_CURSOR_RE.fullmatch(result["journal_cursor"]) is None
    ):
        core.fail("remote_snapshot_invalid")
    return result


def verify_remote(bundle: Path, plan: dict[str, Any]) -> str:
    snapshots = [_validate_remote(core.load_json(bundle / leaf, "remote_snapshot_invalid")[0]) for leaf in ("remote-pre.json", "remote-post-window.json", "remote-final.json")]
    if any(snapshot["server_binary_sha256"] != plan["expected_server_binary_sha256"] for snapshot in snapshots):
        core.fail("remote_binary_identity_mismatch")
    if any(snapshots[0][key] != snapshot[key] for snapshot in snapshots[1:] for key in REMOTE_STABLE_KEYS):
        core.fail("remote_snapshot_drift")
    return snapshots[0]["journal_cursor"]


def verify_lock(bundle: Path, *, marker_prefix: str) -> str:
    if re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", marker_prefix) is None:
        core.fail("lock_receipt_invalid")
    acquired = core.read_regular(bundle / "lock-acquired.txt", maximum=4096, reason="lock_receipt_invalid")
    released = core.read_regular(bundle / "lock-released.txt", maximum=4096, reason="lock_receipt_invalid")
    try:
        acquired_text, released_text = acquired.decode("utf-8", errors="strict"), released.decode("utf-8", errors="strict")
    except UnicodeError:
        core.fail("lock_receipt_invalid")
    match = re.fullmatch(r"LOCK_ACQUIRED nonce=([0-9a-f]{32}) pid=([1-9][0-9]*) " + rf"marker=/run/{re.escape(marker_prefix)}-\1\.lock\n", acquired_text)
    if match is None:
        core.fail("lock_receipt_invalid")
    nonce = match.group(1)
    if released_text != f"LOCK_RELEASED nonce={nonce}\n":
        core.fail("lock_receipt_mismatch")
    return nonce


def _validate_http_headers(value: dict[str, Any]) -> None:
    if set(value) != {"status", "headers"} or value.get("status") != 200 or not isinstance(value.get("headers"), list):
        core.fail("serverinfo_http_capture_invalid")
    content_types: list[str] = []
    for item in value["headers"]:
        if not isinstance(item, list) or len(item) != 2 or not all(isinstance(part, str) for part in item):
            core.fail("serverinfo_http_capture_invalid")
        if item[0].casefold() == "content-type":
            content_types.append(item[1].split(";", 1)[0].strip().casefold())
    if content_types != ["application/json"]:
        core.fail("serverinfo_http_capture_invalid")


def verify_serverinfo(
    bundle: Path,
    *,
    body_validator: Callable[[object], None],
    sequence_validator: Callable[[list[dict[str, Any]]], None],
) -> str:
    bodies: list[dict[str, Any]] = []
    for name in ("identity-serverinfo", "start-barrier", "end-barrier"):
        body = core.load_json(bundle / f"{name}.json", "serverinfo_invalid", require_canonical=False)[0]
        headers = core.load_json(bundle / f"{name}.headers.json", "serverinfo_http_capture_invalid")[0]
        _validate_http_headers(headers)
        body_validator(body)
        bodies.append(body)
    sequence_validator(bodies)
    return str(bodies[0]["version"])


def verify_mode_inventory(manifest: dict[str, Any], *, mode: str, negative_required_paths: frozenset[str]) -> None:
    paths = {str(record["path"]) for record in manifest["files"] if isinstance(record, dict) and isinstance(record.get("path"), str)}
    if mode == "positive" and any(path == "negative-proxy" or path.startswith("negative-proxy/") or path.startswith("negative-proxy.") or path.startswith("adb-reverse-") for path in paths):
        core.fail("positive_mode_forbidden_evidence")
    if mode == "negative" and not negative_required_paths.issubset(paths):
        core.fail("negative_mode_evidence_missing")


def verify_evidence_root_security(bundle: Path, validator: Callable[[Path, dict[str, Any]], None]) -> str:
    report, raw = core.load_json(bundle / "evidence-root-security.json", "evidence_root_security_invalid", maximum=64 * 1024, require_canonical=True)
    validator(bundle.parent, report)
    return core.sha256(raw)


def build_complete_marker(
    *,
    collection: str,
    run_id: str,
    manifest_sha256: str,
    marker: str,
    manifest_leaf: str | None = None,
) -> bytes:
    manifest_binding = "" if manifest_leaf is None else f"manifest={manifest_leaf} "
    return (
        f"{marker} collection_id={collection} run_id={run_id} "
        f"{manifest_binding}manifest_sha256={manifest_sha256}\n"
    ).encode("ascii")


def verify_complete(bundle: Path, *, collection: str, run_id: str, manifest_sha256: str, marker: str) -> None:
    complete = core.read_regular(bundle / "COMPLETE", maximum=4096, reason="complete_marker_invalid")
    expected = build_complete_marker(
        collection=collection,
        run_id=run_id,
        manifest_sha256=manifest_sha256,
        marker=marker,
    )
    if complete != expected:
        core.fail("complete_marker_mismatch")


__all__ = (
    "CandidateContract", "DEFAULT_DEVICE_PROPERTY_KEYS",
    "DEFAULT_LAUNCHER_COMPONENT", "DEFAULT_NEGATIVE_REQUIRED_PATHS",
    "DEFAULT_OPTIONAL_DEVICE_PROPERTY_KEYS", "DEFAULT_RELEVANT_PACKAGES",
    "DeviceIdentityContract", "MAX_APK_BYTES",
    "MAX_JSON_BYTES", "MAX_MANIFEST_BYTES", "MAX_TEXT_BYTES", "PLAN_KEYS",
    "PhoneStateContract", "QuickCollectionVerifierAdapter", "RUN_RECEIPT_KEYS",
    "build_complete_marker",
    "STATUS_KEYS", "phone_state_sha256", "verify_complete",
)
