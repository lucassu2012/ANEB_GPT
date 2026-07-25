from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import verify_token_quick_device_identity as device_verifier


SERIAL = "P40PROTEST0001"
BOOT_ID = "12345678-1234-4abc-8def-1234567890ab"
PROPERTIES = {
    "ro.serialno": SERIAL,
    "ro.boot.serialno": SERIAL,
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


def serial_sha256(value: str = SERIAL) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode(
        "utf-8"
    )


class DeviceIdentityFixture:
    def __init__(self, root: Path) -> None:
        self.bundle = root / "bundle.complete"
        self.bundle.mkdir(parents=True)
        self.policy = root / "p40-device-policy.json"
        self.write_policy()
        self.write_all()

    def write_policy(self, *, properties: dict[str, str] | None = None) -> None:
        self.policy.write_bytes(
            json_bytes(
                {
                    "schema": "aneb-device-identity-policy",
                    "schema_version": "1.0.0",
                    "device_alias": "P40 Pro",
                    "adb_serial_sha256": serial_sha256(),
                    "properties": properties or PROPERTIES,
                }
            )
        )

    def write(self, name: str, value: str | bytes) -> None:
        raw = value.encode("utf-8") if isinstance(value, str) else value
        (self.bundle / name).write_bytes(raw)

    @staticmethod
    def getprop(properties: dict[str, str] | None = None) -> str:
        values = properties or PROPERTIES
        return "".join(f"{key}={values[key]}\n" for key in device_verifier.PROPERTY_KEYS)

    def write_all(self) -> None:
        for stage in ("preflight", "final"):
            self.write(f"device-adb-serial-{stage}.txt", SERIAL + "\n")
            self.write(f"device-getprop-{stage}.txt", self.getprop())
            self.write(f"device-boot-id-{stage}.txt", BOOT_ID + "\n")

    def verify(self) -> dict[str, object]:
        return device_verifier.verify_device_identity(
            self.bundle,
            policy_path=self.policy,
            expected_input_serial=SERIAL,
        )


class DeviceIdentityVerifierTests(unittest.TestCase):
    def fixture(self, temporary: str) -> DeviceIdentityFixture:
        return DeviceIdentityFixture(Path(temporary))

    def assert_reason(self, fixture: DeviceIdentityFixture, expected: str) -> None:
        with self.assertRaises(device_verifier.DeviceIdentityFailure) as captured:
            fixture.verify()
        self.assertEqual(expected, captured.exception.reason_code)

    def test_pre_and_final_identity_matches_private_policy(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            report = self.fixture(temporary).verify()

        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(serial_sha256(), report["adb_serial_sha256"])
        self.assertEqual(BOOT_ID, report["android_boot_id"])
        self.assertEqual(6, report["raw_files_verified"])
        self.assertTrue(report["serial_property_confirmed"])
        self.assertTrue(report["verified_boot_observed_complete"])
        self.assertTrue(report["verified_boot_secure"])
        self.assertNotIn(SERIAL, json.dumps(report))

    def test_input_serial_must_equal_adb_get_serialno(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            with self.assertRaises(device_verifier.DeviceIdentityFailure) as captured:
                device_verifier.verify_device_identity(
                    fixture.bundle,
                    policy_path=fixture.policy,
                    expected_input_serial="OTHERDEVICE0001",
                )
        self.assertEqual("device_serial_mismatch", captured.exception.reason_code)

    def test_policy_serial_hash_must_match_raw_serial(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            policy = json.loads(fixture.policy.read_text(encoding="utf-8"))
            policy["adb_serial_sha256"] = "0" * 64
            fixture.policy.write_bytes(json_bytes(policy))
            self.assert_reason(fixture, "device_policy_mismatch")

    def test_pre_and_final_serial_change_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write("device-adb-serial-final.txt", "OTHERDEVICE0001\n")
            self.assert_reason(fixture, "device_serial_mismatch")

    def test_same_serial_different_android_boot_id_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "device-boot-id-final.txt",
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\n",
            )
            self.assert_reason(fixture, "device_boot_id_mismatch")

    def test_build_fingerprint_drift_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            changed = dict(PROPERTIES)
            changed["ro.build.fingerprint"] += ".changed"
            fixture.write("device-getprop-final.txt", fixture.getprop(changed))
            self.assert_reason(fixture, "device_properties_mismatch")

    def test_property_policy_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            changed = dict(PROPERTIES)
            changed["ro.product.device"] = "OTHER"
            fixture.write_policy(properties=changed)
            self.assert_reason(fixture, "device_policy_mismatch")

    def test_duplicate_property_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            body = fixture.getprop() + f"ro.serialno={SERIAL}\n"
            fixture.write("device-getprop-preflight.txt", body)
            self.assert_reason(fixture, "device_properties_invalid")

    def test_missing_raw_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            (fixture.bundle / "device-boot-id-final.txt").unlink()
            self.assert_reason(fixture, "device_identity_raw_unavailable")

    def test_duplicate_policy_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.policy.write_text(
                '{"schema":"aneb-device-identity-policy",'
                '"schema":"aneb-device-identity-policy",'
                '"schema_version":"1.0.0","device_alias":"P40 Pro",'
                f'"adb_serial_sha256":"{serial_sha256()}",'
                f'"properties":{json.dumps(PROPERTIES)}}}\n',
                encoding="utf-8",
            )
            self.assert_reason(fixture, "device_policy_invalid")

    def test_symlink_policy_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            link = Path(temporary) / "policy-link.json"
            try:
                link.symlink_to(fixture.policy)
            except OSError:
                self.skipTest("symlinks unavailable for this Windows account")
            with self.assertRaises(device_verifier.DeviceIdentityFailure) as captured:
                device_verifier.verify_device_identity(
                    fixture.bundle,
                    policy_path=link,
                    expected_input_serial=SERIAL,
                )
        self.assertEqual("device_policy_unavailable", captured.exception.reason_code)

    def test_empty_verified_boot_values_are_bound_without_secure_claim(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            properties = dict(PROPERTIES)
            for key in device_verifier.VERIFIED_BOOT_KEYS:
                properties[key] = ""
            fixture.write_policy(properties=properties)
            for stage in ("preflight", "final"):
                fixture.write(f"device-getprop-{stage}.txt", fixture.getprop(properties))
            report = fixture.verify()
        self.assertFalse(report["verified_boot_observed_complete"])
        self.assertFalse(report["verified_boot_secure"])


if __name__ == "__main__":
    unittest.main()
