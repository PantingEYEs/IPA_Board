"""Historical pre-#49 JNA/batch comparison report reader; never run by current regression.

Kept for interpreting old reports, not as a current engine or performance claim.
"""
import csv
import json
from pathlib import Path
import statistics
from candidate_timing import distribution

RIME_PREFIX = "INSTRUMENTATION_STATUS: rime_batch_timing="


def export_rime_batch_report(output: str, report: Path) -> dict | None:
    """Same-runtime legacy/batch A/B samples; incomplete pairs cannot imply improvement."""
    rows = []
    seen = set()
    for line in output.splitlines():
        if not line.startswith(RIME_PREFIX):
            continue
        row = json.loads(line[len(RIME_PREFIX):])
        if not isinstance(row, dict) or set(row) != {"schema", "case", "mode", "round", "duration_ns", "count"}:
            raise ValueError("Invalid Rime batch timing schema")
        if row["schema"] != 1 or row["case"] not in {"yes", "shide", "soudesu"} or row["mode"] not in {"legacy", "batch"}:
            raise ValueError("Invalid Rime batch timing labels")
        for name in ("round", "duration_ns", "count"):
            if type(row[name]) is not int or row[name] < 0:
                raise ValueError("Invalid Rime batch timing integer")
        if row["round"] >= 20 or row["count"] > 80:
            raise ValueError("Invalid Rime batch timing bounds")
        key = row["case"], row["mode"], row["round"]
        if key in seen:
            raise ValueError("Duplicate Rime batch timing sample")
        seen.add(key); rows.append(row)
    if not rows:
        return None
    cases = []
    for case in sorted({r["case"] for r in rows}):
        by_mode = {mode: {r["round"]: r for r in rows if r["case"] == case and r["mode"] == mode}
                   for mode in ("legacy", "batch")}
        paired = sorted(set(by_mode["legacy"]) & set(by_mode["batch"]))
        equal_counts = all(by_mode["legacy"][i]["count"] == by_mode["batch"][i]["count"] for i in paired)
        item = {"case": case, "paired_rounds": len(paired), "equal_candidate_counts": equal_counts}
        for mode, samples in by_mode.items():
            item[mode] = distribution([r["duration_ns"] / 1e6 for r in samples.values()]) if samples else None
        # Compare matched samples only. Candidate content equivalence is separately asserted on-device.
        old = statistics.median(by_mode["legacy"][i]["duration_ns"] for i in paired) if paired else None
        new = statistics.median(by_mode["batch"][i]["duration_ns"] for i in paired) if paired else None
        item["paired_median_speedup"] = round(old / new, 4) if paired and equal_counts and new else None
        cases.append(item)
    summary = {"schema": 1, "scope": "Same Rime runtime, warm complete adapter queries, alternating order; content equivalence checked by JUnit", "cases": cases}
    (report / "rime-batch-timing.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
    with (report / "rime-batch-timing.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["schema", "case", "mode", "round", "duration_ns", "count", "duration_ms"])
        writer.writeheader()
        writer.writerows({**r, "duration_ms": round(r["duration_ns"] / 1e6, 4)} for r in rows)
    (report / "rime-batch-timing-summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("Rime legacy/batch query comparison (ms):", flush=True)
    for item in cases:
        print(f"  {item['case']:8} paired={item['paired_rounds']} legacy={item['legacy']} batch={item['batch']} "
              f"speedup={item['paired_median_speedup']}", flush=True)
    return summary
