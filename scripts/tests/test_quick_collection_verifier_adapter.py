from __future__ import annotations

import inspect
from pathlib import Path
import tempfile
import unittest

from scripts import quick_collection_verifier_adapter as adapter_module
from scripts import verify_network_quick_collection as network_verifier


class QuickCollectionVerifierAdapterTests(unittest.TestCase):
    def test_neutral_adapter_has_no_family_imports(self) -> None:
        source = inspect.getsource(adapter_module)
        for forbidden in (
            "verify_realtime",
            "verify_network",
            "verify_token",
            "collect_realtime",
            "collect_network",
            "collect_token",
        ):
            self.assertNotIn(forbidden, source)

    def test_network_verifier_has_no_realtime_production_dependency(self) -> None:
        source = inspect.getsource(network_verifier)
        self.assertNotIn("verify_realtime_quick_collection", source)
        self.assertNotIn("collect_realtime_quick_evidence", source)

    def test_complete_marker_is_contract_driven(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory)
            (bundle / "COMPLETE").write_bytes(
                b"NETWORK_COMPLETE collection_id=collection "
                b"run_id=019fa3d7-8ab2-76eb-90bd-182a482b3c7f "
                + b"manifest_sha256="
                + b"a" * 64
                + b"\n"
            )
            adapter_module.verify_complete(
                bundle,
                collection="collection",
                run_id="019fa3d7-8ab2-76eb-90bd-182a482b3c7f",
                manifest_sha256="a" * 64,
                marker="NETWORK_COMPLETE",
            )


if __name__ == "__main__":
    unittest.main()
