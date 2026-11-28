"""The evidence files stay the files ``results.py`` and ``pictures.py`` read."""

from __future__ import annotations

import unittest

from . import fixtures, support
from harness import eta, evidence


class EvidenceTest(support.Case):
    def test_timing_rows_are_the_results_fixture(self) -> None:
        path = self.sandbox.root / "timings.csv"
        for run, step, seconds, passed, failed, status in [
            ("20261116T000000Z", "unlock", "3.0", 1, 0, "ok"),
            ("20261116T000000Z", "panel", "12.5", 3, 0, "ok"),
            ("20261116T000000Z", "TOTAL", "180.0", 7, 0, "ok"),
        ]:
            evidence.append_timing(
                path, run, step, seconds, passed, failed, status
            )
        self.same(
            self.text(path),
            fixtures.RESULTS_TIMINGS,
            "the header and rows are results.test.sh's fixture",
        )
        self.same(
            eta.load(path).get("panel"), 12500, "and the estimate reads them"
        )

    def test_timing_appends_and_never_rewrites(self) -> None:
        path = self.sandbox.root / "timings-partial.csv"
        evidence.append_timing(path, "r1", "stop", 15.0, 4, 0, "ok")
        evidence.append_timing(path, "r1", "PARTIAL", 24.5, 5, 0, "partial")
        lines = self.text(path).splitlines()
        self.same(lines[0], evidence.TIMINGS_HEADER, "the header is written once")
        self.same(len(lines), 3, "two rows follow it")
        self.same(
            lines[1], "r1,stop,15.0,4,0,ok", "a float seconds is one decimal"
        )
        self.same(
            lines[2], "r1,PARTIAL,24.5,5,0,partial", "a partial row keeps its word"
        )

    def test_check_claim_is_the_results_fixture(self) -> None:
        path = self.sandbox.root / "checks.csv"
        evidence.append_check(
            path,
            run="20261116T000000Z",
            step="panel",
            verdict="fail",
            file="tests/live/proxy-app.sh",
            line=851,
            function="step_panel",
            decided_ms=1794795270195,
            claim='the panel opens, "really"',
        )
        expected = fixtures.RESULTS_CHECKS.splitlines()[2] + "\n"
        self.same(
            self.text(path),
            evidence.CHECKS_HEADER + "\n" + expected,
            "a claim with quotes is doubled, not split into columns",
        )

    def test_uplink_quotes_egress_and_verdict(self) -> None:
        path = self.sandbox.root / "uplink.csv"
        evidence.append_uplink(
            path, 1794795270195, 18080, "192.168.77.232", "example.com",
            "wlan0, Cellular", "the phone answered",
        )
        lines = self.text(path).splitlines()
        self.same(lines[0], evidence.UPLINK_HEADER, "the six columns are unchanged")
        self.same(
            lines[1],
            '1794795270195,18080,192.168.77.232,example.com,'
            '"wlan0, Cellular","the phone answered"',
            "egress and verdict are quoted so a comma stays inside",
        )

    def test_input_and_failure_rows(self) -> None:
        input_path = self.sandbox.root / "input.csv"
        evidence.append_input(
            input_path, 100000000000, 101500000000, "input", "tap 10 20"
        )
        self.same(
            self.text(input_path),
            "start_ns,end_ns,kind,what\n"
            "100000000000,101500000000,input,tap 10 20\n",
            "input rows keep the interval and the action",
        )
        failures = self.sandbox.root / "failures.csv"
        evidence.append_failure(failures, 1, "panel", 10, 20, 30)
        self.same(
            self.text(failures),
            "check,step,start_ns,fail_ns,end_ns\n1,panel,10,20,30\n",
            "a failure row has the five picture-planning fields",
        )

    def test_names_match_the_run_folder(self) -> None:
        self.same(evidence.TIMINGS, "timings.csv", "full history name")
        self.same(evidence.TIMINGS_PARTIAL, "timings-partial.csv", "partial name")
        self.same(evidence.UPLINK, "uplink.csv", "uplink name")
        self.same(evidence.CLOCK, "clock.txt", "clock mapping name")
        self.same(evidence.CLOCK_EDGES, "clock.csv", "flash edge name")


if __name__ == "__main__":
    unittest.main()
