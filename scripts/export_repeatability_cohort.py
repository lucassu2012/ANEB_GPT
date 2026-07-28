#!/usr/bin/env python3
"""Export immutable strict-v2 Room envelopes for repeatability analysis.

The exporter preserves each stored ``bodyJson`` byte sequence.  It verifies the
Room digest and strict-v2 schema before writing JSONL; it never reconstructs a
result or reruns scoring.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
from typing import Any, Iterable
import uuid

from scripts.analyze_repeatability_cohort import (
    CohortError,
    _canonical_digest,
    _object_without_duplicates,
    _reject_non_finite,
    _validate_strict_v2,
)


SCHEMA_VERSION = "aneb-repeatability-export-v1"
ROOM_IDENTITY_HASH = "5eea8eb8e2b9f91767a34e3499c77484"
MAX_DATABASE_BYTES = 256 * 1024 * 1024


class ExportError(RuntimeError):
    """Raised when frozen Room evidence cannot be exported safely."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _source_hashes(database: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for suffix in ("", "-wal", "-shm"):
        path = Path(str(database) + suffix)
        if path.exists():
            if not path.is_file() or path.is_symlink():
                raise ExportError("database_source_invalid")
            if path.stat().st_size > MAX_DATABASE_BYTES:
                raise ExportError("database_source_too_large")
            result[path.name] = _sha256(path)
    if database.name not in result:
        raise ExportError("database_missing")
    return result


def _strict_body(text: str, *, run_id: str) -> dict[str, Any]:
    if "\n" in text or "\r" in text:
        raise ExportError(f"body_not_single_line:{run_id}")
    try:
        value = json.loads(
            text,
            object_pairs_hook=_object_without_duplicates,
            parse_constant=_reject_non_finite,
        )
    except (CohortError, TypeError, UnicodeError, ValueError, json.JSONDecodeError) as exc:
        raise ExportError(f"body_json_invalid:{run_id}") from exc
    if not isinstance(value, dict):
        raise ExportError(f"body_not_object:{run_id}")
    return value


def _radio_rows(connection: sqlite3.Connection, run_id: str) -> list[dict[str, Any]]:
    rows = connection.execute(
        """
        SELECT tsNanos, cellTsNanos, stale, subId, subSwitched,
               networkType, overrideType, nrState, rat, pci, tac, arfcn,
               rsrp, rsrq, sinr, operatorName
          FROM radio_sample WHERE runId = ? ORDER BY tsNanos, id
        """,
        (run_id,),
    ).fetchall()
    return [
        {
            "elapsed_realtime_nanos": row["tsNanos"],
            "cell_elapsed_realtime_nanos": row["cellTsNanos"],
            "stale": bool(row["stale"]),
            "sub_id": None if row["subId"] == -1 else row["subId"],
            "sub_switched": bool(row["subSwitched"]),
            "network_type": row["networkType"],
            "override_type": row["overrideType"],
            "nr_state": row["nrState"],
            "rat": row["rat"],
            "pci": row["pci"],
            "tac": row["tac"],
            "arfcn": row["arfcn"],
            "rsrp_dbm": row["rsrp"],
            "rsrq_db": row["rsrq"],
            "sinr_db": row["sinr"],
            "operator_name": row["operatorName"],
        }
        for row in rows
    ]


def _read_rows(
    database: Path, run_ids: list[str], *, root: Path
) -> tuple[list[str], dict[str, str]]:
    before = _source_hashes(database)
    with tempfile.TemporaryDirectory(prefix="aneb-repeatability-room-") as temporary:
        snapshot_root = Path(temporary)
        for name in before:
            shutil.copy2(database.parent / name, snapshot_root / name)
        snapshot = snapshot_root / database.name
        try:
            connection = sqlite3.connect(snapshot.resolve().as_uri() + "?mode=ro", uri=True)
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA query_only = ON")
            if [row[0] for row in connection.execute("PRAGMA quick_check")] != ["ok"]:
                raise ExportError("database_integrity_failed")
            identity = connection.execute(
                "SELECT identity_hash FROM room_master_table WHERE id = 42"
            ).fetchall()
            if len(identity) != 1 or identity[0][0] != ROOM_IDENTITY_HASH:
                raise ExportError("room_identity_mismatch")

            bodies: list[str] = []
            for index, run_id in enumerate(run_ids):
                rows = connection.execute(
                    """
                    SELECT runId, schemaVersion, testType, canonicalSha256, bodyJson
                      FROM result_envelope WHERE runId = ?
                    """,
                    (run_id,),
                ).fetchall()
                if len(rows) != 1:
                    raise ExportError(f"result_envelope_cardinality_invalid:{run_id}")
                row = rows[0]
                body = _strict_body(row["bodyJson"], run_id=run_id)
                if (
                    row["runId"] != run_id
                    or body.get("schema_version") != row["schemaVersion"]
                    or body.get("test_type") != row["testType"]
                    or body.get("run", {}).get("run_id") != run_id
                    or _canonical_digest(body) != row["canonicalSha256"]
                ):
                    raise ExportError(f"result_envelope_binding_mismatch:{run_id}")
                try:
                    _validate_strict_v2(body, root=root, index=index)
                except CohortError as exc:
                    raise ExportError(f"strict_v2_invalid:{run_id}:{exc}") from exc
                envelope_radio = body.get("context", {}).get("radio", {})
                if (
                    envelope_radio.get("collection_status") != "collected"
                    or envelope_radio.get("sample_count") != len(envelope_radio.get("samples", []))
                    or _radio_rows(connection, run_id) != envelope_radio.get("samples")
                ):
                    raise ExportError(f"radio_envelope_binding_mismatch:{run_id}")
                bodies.append(row["bodyJson"])
        except sqlite3.Error as exc:
            raise ExportError("database_query_failed") from exc
        finally:
            if "connection" in locals():
                connection.close()
    if _source_hashes(database) != before:
        raise ExportError("database_source_modified_during_export")
    return bodies, before


def export_cohort(
    database: Path,
    run_ids: Iterable[str],
    output: Path,
    *,
    root: Path,
) -> dict[str, Any]:
    """Write requested frozen envelopes as create-once JSONL and return a receipt."""

    requested = list(run_ids)
    if not requested or len(requested) != len(set(requested)):
        raise ExportError("run_ids_empty_or_duplicate")
    if output.exists():
        raise ExportError("output_exists")
    if not output.parent.is_dir():
        raise ExportError("output_parent_missing")

    bodies, source_hashes = _read_rows(database, requested, root=root)
    payload = ("\n".join(bodies) + "\n").encode("utf-8")
    temporary = output.parent / f".{output.name}.{uuid.uuid4().hex}.partial"
    try:
        with temporary.open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.rename(output)
    except OSError as exc:
        temporary.unlink(missing_ok=True)
        raise ExportError("output_commit_failed") from exc

    return {
        "schema_version": SCHEMA_VERSION,
        "run_count": len(requested),
        "run_ids": requested,
        "output_path": str(output.resolve()),
        "output_sha256": hashlib.sha256(payload).hexdigest(),
        "database_source_sha256": source_hashes,
        "results_reconstructed": False,
        "scores_recomputed": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("run_ids", nargs="+")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args(argv)
    try:
        receipt = export_cohort(
            args.database,
            args.run_ids,
            args.output,
            root=args.root,
        )
    except ExportError as exc:
        sys.stderr.write(f"EXPORT_INVALID {exc}\n")
        return 2
    sys.stdout.write(json.dumps(receipt, ensure_ascii=False, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
