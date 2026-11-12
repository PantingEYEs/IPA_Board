"""Export opt-in candidate pipeline instrumentation events. No logcat or private app files."""
from __future__ import annotations

from collections import defaultdict
import csv
import json
import math
from pathlib import Path
import statistics

PREFIX = "INSTRUMENTATION_STATUS: candidate_timing="
FIELDS = ("schema", "scenario", "case", "request_id", "revision", "engine", "stage",
          "start_ns", "end_ns", "count", "outcome", "expected_engines")
STAGES = {"query_started", "compose_to_query", "dispatch_wait", "outbound_ipc", "worker_queue",
          "initialize", "engine_query", "serialize", "inbound_ipc", "decode", "merge",
          "publish_callback", "reply", "render", "layout", "software_draw"}


def parse_events(output: str) -> list[dict]:
    events = []
    for line in output.splitlines():
        if not line.startswith(PREFIX):
            continue
        row = json.loads(line[len(PREFIX):])
        # Whitelist the schema: input/candidate strings must never enter this report.
        if not isinstance(row, dict) or set(row) != set(FIELDS) or row["schema"] != 1:
            raise ValueError("Invalid candidate timing schema")
        for field in ("request_id", "revision", "start_ns", "end_ns", "count"):
            if type(row[field]) is not int or row[field] < 0:
                raise ValueError("Invalid candidate timing integer")
        if row["end_ns"] < row["start_ns"] or row["stage"] not in STAGES:
            raise ValueError("Invalid candidate timing stage or clock")
        if row["engine"] not in {"ALL", "UI", "ZH", "JA", "EN", "IPA"}:
            raise ValueError("Invalid candidate timing engine")
        if row["outcome"] not in {"ok", "empty", "stale", "error", "invalid"}:
            raise ValueError("Invalid candidate timing outcome")
        for field in ("scenario", "case", "expected_engines"):
            if not isinstance(row[field], str) or len(row[field]) > 80:
                raise ValueError("Invalid candidate timing label")
        if not set(filter(None, row["expected_engines"].split(","))) <= {"ZH", "JA", "EN", "IPA"}:
            raise ValueError("Invalid expected engine set")
        events.append(row)
    return events


def distribution(values: list[float]) -> dict:
    values = sorted(values)
    return {"n": len(values), "min_ms": round(values[0], 4), "median_ms": round(statistics.median(values), 4),
            "p95_ms": round(values[math.ceil(len(values) * .95) - 1], 4),
            "max_ms": round(values[-1], 4)}


def summarize(events: list[dict]) -> dict:
    grouped = defaultdict(list)
    stages = defaultdict(list)
    for row in events:
        grouped[(row["revision"], row["request_id"])].append(row)
        stages[(row["scenario"], row["engine"], row["stage"])].append((row["end_ns"] - row["start_ns"]) / 1e6)
    requests = []
    for (revision, request_id), rows in sorted(grouped.items()):
        start = next((r for r in rows if r["stage"] == "query_started"), None)
        if start is None:
            continue  # e.g. a late reply from a query made before attaching the probe
        origin = start["start_ns"]
        expected = set(filter(None, start["expected_engines"].split(",")))
        replies = [r for r in rows if r["stage"] == "reply"]
        accepted = [r for r in replies if r["outcome"] in {"ok", "empty"}]
        ready = {r["engine"] for r in accepted}
        errors = [r for r in replies if r["outcome"] in {"error", "invalid"}]
        callbacks = [r for r in rows if r["stage"] == "publish_callback" and r["count"] > 0]
        complete = expected <= ready and not errors
        arrival = sorted(accepted, key=lambda r: r["start_ns"])
        requests.append({"revision": revision, "request_id": request_id,
                         "scenario": start["scenario"], "case": start["case"],
                         "expected_engines": sorted(expected), "received_engines": sorted(ready),
                         "status": "error" if errors else "complete" if complete else "incomplete",
                         "stale_replies": sum(r["outcome"] == "stale" for r in replies),
                         "first_nonempty_publish_ms": round((min(r["end_ns"] for r in callbacks) - origin) / 1e6, 4) if callbacks else None,
                         "all_replies_ms": round((max(r["start_ns"] for r in accepted) - origin) / 1e6, 4) if complete and expected else None,
                         "arrival_spread_ms": round((arrival[-1]["start_ns"] - arrival[0]["start_ns"]) / 1e6, 4) if complete and expected else None,
                         "arrival_order": [r["engine"] for r in arrival],
                         "nonempty_publications": len(callbacks)})
    return {"schema": 1, "clock": "Android elapsedRealtimeNanos; device monotonic, cross-process",
            "scope": "Synthetic composition, real engine IPC, production unattached candidate view; excludes GPU/display presentation and E5 inference",
            "requests": requests,
            "stages": [{"scenario": scenario, "engine": engine, "stage": stage, **distribution(values)}
                       for (scenario, engine, stage), values in sorted(stages.items())]}


def export_report(output: str, report: Path) -> dict | None:
    events = parse_events(output)
    if not events:
        return None
    summary = summarize(events)
    (report / "candidate-timing.jsonl").write_text("".join(json.dumps(r) + "\n" for r in events), encoding="utf-8")
    with (report / "candidate-timing.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=[*FIELDS, "duration_ms"])
        writer.writeheader()
        writer.writerows({**r, "duration_ms": round((r["end_ns"] - r["start_ns"]) / 1e6, 4)} for r in events)
    (report / "candidate-timing-summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("Candidate timing (ms; p95 uses nearest rank, no speed pass/fail threshold):", flush=True)
    for row in summary["stages"]:
        if row["stage"] in {"initialize", "worker_queue", "engine_query", "render", "merge"}:
            print(f"  {row['scenario']:14} {row['engine']:3} {row['stage']:14} n={row['n']:3} "
                  f"median={row['median_ms']:10.4f} p95={row['p95_ms']:10.4f} max={row['max_ms']:10.4f}", flush=True)
    return summary
