"""Deterministic report contracts, without Android or wall-clock performance assertions."""
import csv
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout

import candidate_timing as timing


def event(stage, start=1_000_000, end=None, engine="ALL", count=0, outcome="ok", request=1, revision=1, expected="EN,ZH"):
    return dict(schema=1, scenario="warm", case="synthetic-1", request_id=request, revision=revision,
                engine=engine, stage=stage, start_ns=start, end_ns=start if end is None else end,
                count=count, outcome=outcome, expected_engines=expected)


def wire(rows):
    return "\n".join(timing.PREFIX + json.dumps(r) for r in rows)


class CandidateTimingReportTest(unittest.TestCase):
    def test_arrival_spread_and_first_publication_include_empty_success(self):
        rows = [event("query_started"), event("reply", 3_000_000, engine="EN", outcome="empty"),
                event("publish_callback", 8_000_000, 9_000_000, count=3),
                event("reply", 7_000_000, 10_000_000, engine="ZH")]
        report = timing.summarize(timing.parse_events(wire(rows)))
        query = report["requests"][0]
        self.assertEqual("complete", query["status"])
        self.assertEqual(["EN", "ZH"], query["arrival_order"])
        self.assertEqual(8.0, query["first_nonempty_publish_ms"])
        self.assertEqual(6.0, query["all_replies_ms"])
        self.assertEqual(4.0, query["arrival_spread_ms"])

    def test_stale_and_coalesced_requests_never_appear_complete(self):
        rows = [event("query_started"), event("reply", engine="ZH", outcome="stale"),
                event("query_started", request=2), event("reply", engine="EN", request=2),
                event("reply", engine="ZH", request=2)]
        old, new = timing.summarize(rows)["requests"]
        self.assertEqual("incomplete", old["status"])
        self.assertEqual(1, old["stale_replies"])
        self.assertIsNone(old["all_replies_ms"])
        self.assertEqual("complete", new["status"])
        self.assertEqual(0, new["stale_replies"])

    def test_failure_and_all_disabled_have_no_fabricated_latency(self):
        error = timing.summarize([event("query_started"), event("reply", engine="EN", outcome="error")])["requests"][0]
        self.assertEqual("error", error["status"])
        self.assertIsNone(error["arrival_spread_ms"])
        disabled = timing.summarize([event("query_started", expected="")])["requests"][0]
        self.assertEqual("complete", disabled["status"])
        self.assertIsNone(disabled["all_replies_ms"])
        self.assertIsNone(disabled["first_nonempty_publish_ms"])

    def test_restarted_coordinator_ids_are_separated_by_revision(self):
        rows = [event("query_started", expected="", revision=1), event("query_started", expected="", revision=2)]
        self.assertEqual(2, len(timing.summarize(rows)["requests"]))

    def test_malformed_or_private_payload_is_rejected(self):
        for changes in ({"raw": "private"}, {"end_ns": 0}, {"stage": "unknown"},
                        {"count": True}, {"outcome": "timeout"}, {"engine": "other"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                timing.parse_events(wire([{**event("merge"), **changes}]))

    def test_exports_events_on_failed_junit_output_and_ignores_other_status(self):
        rows = [event("query_started"), event("engine_query", 1_000_000, 6_500_000, "ZH")]
        output = "INSTRUMENTATION_STATUS: other=unrelated\n" + wire(rows) + "\nFAILURES!!!\n"
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()) as console:
            root = Path(directory)
            report = timing.export_report(output, root)
            self.assertEqual("incomplete", report["requests"][0]["status"])
            with (root / "candidate-timing.csv").open() as stream:
                csv_rows = list(csv.DictReader(stream))
            self.assertEqual("5.5", csv_rows[1]["duration_ms"])
            self.assertEqual(rows, [json.loads(line) for line in (root / "candidate-timing.jsonl").read_text().splitlines()])
            self.assertEqual(5.5, json.loads((root / "candidate-timing-summary.json").read_text())["stages"][1]["median_ms"])
            self.assertIn("5.5000", console.getvalue())
        self.assertIsNone(timing.export_report("OK (1 test)", Path("unused")))

    def test_percentiles_use_nearest_rank_and_preserve_small_measurements(self):
        self.assertEqual({"n": 3, "min_ms": .0001, "median_ms": .25, "p95_ms": 8, "max_ms": 8},
                         timing.distribution([8, .0001, .25]))


class RimeBatchTimingReportTest(unittest.TestCase):
    def rows(self):
        return [dict(schema=1, case="yes", mode=mode, round=i, duration_ns=duration, count=6)
                for i in range(2) for mode, duration in [("legacy", 10_000_000), ("batch", 1_000_000)]]

    def export(self, rows):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            output = "\n".join(timing.RIME_PREFIX + json.dumps(r) for r in rows)
            return timing.export_rime_batch_report(output, Path(directory))

    def test_comparison_uses_matched_rounds_and_keeps_counts(self):
        row = self.export(self.rows())["cases"][0]
        self.assertEqual(2, row["paired_rounds"])
        self.assertEqual(10.0, row["paired_median_speedup"])
        self.assertTrue(row["equal_candidate_counts"])

    def test_unmatched_or_mismatched_samples_cannot_claim_speedup(self):
        self.assertIsNone(self.export([self.rows()[0]])["cases"][0]["paired_median_speedup"])
        rows = self.rows(); rows[1]["count"] = 5
        self.assertIsNone(self.export(rows)["cases"][0]["paired_median_speedup"])

    def test_rejects_private_extra_fields_duplicate_samples_and_bad_types(self):
        for changes in ({"raw": "private"}, {"duration_ns": -1}, {"round": True}, {"count": 81}, {"mode": "unknown"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.export([{**self.rows()[0], **changes}])
        with self.assertRaises(ValueError):
            self.export([self.rows()[0], self.rows()[0]])

    def test_unrelated_output_does_not_create_report(self):
        self.assertIsNone(timing.export_rime_batch_report("OK (1 test)", Path("unused")))
