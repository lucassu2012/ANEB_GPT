from __future__ import annotations

import unittest

from scripts.quick_collection_contract import (
    network_quick_contract,
    realtime_quick_contract,
)


class QuickCollectionContractTest(unittest.TestCase):
    def test_network_identity_is_frozen_to_m0_ec3_release(self) -> None:
        contract = network_quick_contract()

        self.assertEqual("network", contract.category)
        self.assertEqual(
            "network_comprehensive_quick@1.2.0",
            contract.profile_contract,
        )
        self.assertEqual("0.5.14-codex", contract.expected_version_name)
        self.assertEqual(46, contract.expected_version_code)
        self.assertEqual("aneb-server/0.8.2", contract.expected_server_version)
        self.assertEqual(
            "ANEB-Probe-0.5.14-codex-debug.apk",
            contract.candidate_apk_name,
        )
        self.assertEqual(
            {
                "ANEB-Probe-0.5.14-codex-debug.apk",
                "build-manifest.json",
                "checksums.sha256",
                "provenance.sigstore.json",
                "ANEB-安装说明.txt",
            },
            set(contract.candidate_files),
        )
        self.assertEqual("network_run", contract.audit_scope)
        self.assertEqual("aneb-network-audit", contract.remote_marker_prefix)
        self.assertEqual(
            "aneb-network-busy-sentinel",
            contract.busy_sentinel_schema,
        )
        self.assertEqual("start_network_quick", contract.launch_operation_code)
        self.assertEqual(
            "aneb-network-quick-collector-plan",
            contract.plan_schema,
        )
        self.assertEqual(
            "aneb-network-quick-collector-status",
            contract.status_schema,
        )
        self.assertEqual(
            "aneb-network-quick-evidence-manifest",
            contract.manifest_schema,
        )

    def test_realtime_identity_remains_on_accepted_m0_ec2_release(self) -> None:
        contract = realtime_quick_contract()

        self.assertEqual("realtime", contract.category)
        self.assertEqual("ai_realtime_voice_quick@1.1.1", contract.profile_contract)
        self.assertEqual("0.5.13-codex", contract.expected_version_name)
        self.assertEqual(45, contract.expected_version_code)
        self.assertEqual("aneb-server/0.8.1", contract.expected_server_version)
        self.assertEqual(
            "ANEB-Probe-0.5.13-codex-debug.apk",
            contract.candidate_apk_name,
        )
        self.assertEqual("realtime_run", contract.audit_scope)
        self.assertEqual("aneb-realtime-audit", contract.remote_marker_prefix)
        self.assertEqual(
            "aneb-realtime-busy-sentinel",
            contract.busy_sentinel_schema,
        )
        self.assertEqual("start_realtime_quick", contract.launch_operation_code)
        self.assertEqual(
            "aneb-realtime-quick-collector-plan",
            contract.plan_schema,
        )


if __name__ == "__main__":
    unittest.main()
