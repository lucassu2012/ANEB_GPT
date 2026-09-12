"""Internal R1 visible-event analysis; not a network score or formal schema."""
from decimal import Decimal
import hashlib
import json
import math
import copy
from pathlib import Path
import sys
import statistics


def summarize(rows):
    grouped = {}
    for row in rows:
        r = row["input_record"]
        group = r.get("summary_group", {})
        fields = [group.get("id"), r.get("record_kind"),
                  *[r.get("app", {}).get(k) for k in ("name", "version", "model_mode")],
                  r.get("method_id"), r.get("action_text"), r.get("condition"),
                  *[r.get("metadata", {}).get(k) for k in ("device", "network_description")],
                  *[r.get("anchors", {}).get(k) for k in ("send", "feedback", "body")]]
        if group.get("confirmed") is True and all(
                isinstance(v, str) and v.strip() and v.strip().upper() not in ("UNKNOWN", "NA") for v in fields):
            grouped.setdefault(tuple(fields), []).append(row)
    output = []
    for identity, members in grouped.items():
        metrics = {}
        for name in ("ttfr", "ttfc", "completion"):
            attempts = [{"attempt_id": r["attempt_id"], **r[name]} for r in members]
            valid = [r["interval_s"] for r in attempts if r["interval_s"] is not None]
            points = [pair[0] for pair in valid if pair[0] == pair[1]]
            metrics[name] = {"attempts": attempts, "valid_n": len(valid), "point_n": len(points),
                "na_n": sum(r["status"] == "NA" for r in attempts),
                "uncertain_n": sum(r["status"] == "uncertain" for r in attempts),
                "point_median_s": statistics.median(points) if points else None,
                "point_range_s": [min(points), max(points)] if points else None}
        output.append({"group_id": identity[0], "comparability_key": list(identity[1:]), "metrics": metrics})
    return output


def feedback(record, end_name="first_feedback", start_name="send"):
    def na(reason):
        return {"status": "NA", "interval_s": None, "reason": reason}
    if record.get("outcome", {}).get("executed") is not True:
        return na("not_executed")
    clock = record.get("clock", {})
    if clock.get("unit") != "ms" or not clock.get("video_id") or not clock.get("mapping"):
        return na("clock_unavailable")
    events = record.get("events", {})
    start = (events.get(start_name) or {}).get("time_ms")
    end = (events.get(end_name) or {}).get("time_ms")
    if start is None or end is None:
        return na("event_unavailable")
    if any(not isinstance(pair, list) or len(pair) != 2 or any(
            type(v) not in (int, float) or not math.isfinite(v) for v in pair)
           or pair[0] < 0 or pair[0] > pair[1] for pair in (start, end)):
        return na("invalid_interval")
    if not clock.get("domain_id") or any(
            events[name].get("clock_domain_id") != clock["domain_id"]
            for name in (start_name, end_name)):
        return na("clock_domain_mismatch")
    divisor = Decimal(1000)
    interval = [float((Decimal(str(end[0])) - Decimal(str(start[1]))) / divisor),
                float((Decimal(str(end[1])) - Decimal(str(start[0]))) / divisor)]
    if interval[0] < 0:
        return {"status": "uncertain", "interval_s": None, "reason": "event_order_uncertain"}
    return {"status": "interval", "interval_s": interval}


def completion(record):
    outcome = record.get("outcome", {})
    observation = record.get("completion_observation", {})
    stable = feedback(record, "complete_confirm", "last_content")
    if (outcome.get("status") != "completed" or outcome.get("visible_completion") != "yes"
            or not outcome.get("completion_basis")
            or observation.get("body_continuously_visible") is not True
            or observation.get("ui_exited_generation_normally") is not True
            or stable["interval_s"] is None or stable["interval_s"][0] < 3):
        return {"status": "NA", "interval_s": None, "reason": "completion_unconfirmed"}
    return feedback(record, "last_content")


def stalls(record):
    intervals = []
    gaps = [g for g in record.get("observed_intervals", []) if g.get("kind") == "gap"]
    for gap in record.get("observed_intervals", []):
        proxy = copy.deepcopy(record)
        domain = record.get("clock", {}).get("domain_id")
        proxy["events"] = {"send": {"time_ms": gap.get("start_ms"), "clock_domain_id": domain},
                           "first_feedback": {"time_ms": gap.get("end_ms"), "clock_domain_id": domain}}
        duration = feedback(proxy)
        value = duration["interval_s"]
        if gap.get("kind") == "gap" or gap.get("fully_visible") is not True:
            classification = "observation_gap"
        elif gap.get("kind") == "unclosed_tail" or gap.get("resumed") is False:
            classification = "right_censored"
        elif gap.get("kind") != "update_gap" or gap.get("resumed") is not True:
            classification = "uncertain"
        elif value is None:
            classification = duration["status"]
        else:
            classification = "confirmed" if value[0] >= 2 else "below_threshold" if value[1] < 2 else "uncertain"
        if classification in ("confirmed", "below_threshold", "uncertain") and value is not None:
            for other in gaps:
                start, end = other.get("start_ms"), other.get("end_ms")
                # Unknown gap bounds cannot establish uninterrupted observation.
                if (not isinstance(start, list) or len(start) != 2 or
                        not isinstance(end, list) or len(end) != 2 or
                        any(type(v) not in (int, float) or not math.isfinite(v) for v in start + end) or
                        start[0] <= gap["end_ms"][1] and end[1] >= gap["start_ms"][0]):
                    classification = "observation_gap"
                    break
        intervals.append({"classification": classification, "interval_s": value,
                          "input_interval": copy.deepcopy(gap), "reason": duration.get("reason")})
    return {"status": "partial" if intervals else "not_computed", "intervals": intervals,
            "confirmed_observed_count": sum(i["classification"] == "confirmed" for i in intervals),
            "total_count": None, "max_interval_s": None}


def analyze(document):
    if document.get("input_revision") != "alignment-1":
        raise ValueError("input_revision must be alignment-1")
    if document.get("record_kind") not in ("SAMPLE", "OBSERVED"):
        raise ValueError("unknown_record_kind")
    rows = []
    seen = set()
    for record in document["records"]:
        if record.get("record_kind") != document["record_kind"]:
            raise ValueError("mixed_record_kind")
        if document["record_kind"] == "OBSERVED":
            ref = record.get("evidence", {}).get("local_ref")
            if not isinstance(ref, str) or not ref.strip():
                raise ValueError("observed_source_reference_missing")
        if type(record.get("outcome", {}).get("executed")) is not bool:
            raise ValueError("executed must be boolean")
        identity = record["attempt_id"]
        if not isinstance(identity, str) or not identity:
            raise ValueError("invalid_attempt_id")
        if identity in seen:
            raise ValueError("duplicate_attempt_id")
        seen.add(identity)
        rows.append({"attempt_id": record["attempt_id"],
                     "input_record": copy.deepcopy(record),
                     "stalls": stalls(record),
                     "ttfr": feedback(record), "ttfc": feedback(record, "first_content"),
                     "completion": completion(record)})
    attempted = sum(r["input_record"].get("outcome", {}).get("executed") is True for r in rows)
    return {"record_kind": document["record_kind"], "input_revision": "alignment-1",
            "records": rows, "groups": summarize(rows), "counts": {"planned": len(rows), "attempted": attempted,
            "not_run": sum(r["input_record"].get("outcome", {}).get("executed") is False for r in rows),
            "visible_completed_confirmed": sum(r["completion"]["interval_s"] is not None for r in rows)}}


def main():
    raw = Path(sys.argv[1]).read_bytes()
    result = analyze(json.loads(raw.decode("utf-8")))
    result["source_sha256"] = hashlib.sha256(raw).hexdigest()
    print(json.dumps(result, ensure_ascii=True, allow_nan=False))


if __name__ == "__main__":
    main()
