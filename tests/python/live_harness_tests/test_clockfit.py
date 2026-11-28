"""The clock fit: edge parsing, windows, the line, clock.txt and the CLI."""

from __future__ import annotations

import sys
import unittest
from unittest import mock

from . import fixtures, support
from harness import clock, clockfit


class ClockfitTest(support.Case):
    def test_edges_are_grouped_into_bursts(self) -> None:
        edges = clockfit.parse_edges(fixtures.CLOCK_EDGES)
        self.same(len(edges), 4, "every valid row is an edge")
        self.same([edge.burst for edge in edges], [1, 1, 2, 2], "two bursts")
        self.same(edges[0].device_ns, 1000000000, "device_ns is kept")
        self.same(edges[2].wall_ms, 4000.0, "wall_ms is numeric")
        self.same(edges[0].state, "on", "the state word is kept")
        self.same(clockfit.bursts(edges), [1, 2], "burst numbers in order")
        self.same(
            [edge.state for edge in clockfit.burst_edges(edges, 2)],
            ["on", "off"],
            "a burst's edges are selectable",
        )

    def test_edge_header_is_skipped_and_bad_rows_dropped(self) -> None:
        text = """device_ns,wall_ms,on|off
not-a-number,0,on
1,0,on
2,2500,off
3,5001,on
4,20
"""
        edges = clockfit.parse_edges(text)
        self.same(len(edges), 3, "a short row and a non-numeric instant are dropped")
        self.same(
            [edge.burst for edge in edges],
            [1, 1, 2],
            "2500 ms does not split; 2501 does",
        )

    def test_showinfo_transitions(self) -> None:
        log = "\n".join(
            [
                "[showinfo] n:0 pts_time:0.5 duration:1 mean:[75 100 100]",
                "[showinfo] n:1 pts_time:1.0 duration:1 mean:[75 210 220]",
                "[showinfo] n:2 pts_time:1.2 duration:1 mean:[75 100 100]",
                "[showinfo] n:3 pts_time:9.0 duration:1",
                "unrelated",
            ]
        )
        self.same(
            clockfit.parse_showinfo(log),
            [(1.0, "on"), (1.2, "off")],
            "only a state change is a transition; a line without mean is ignored",
        )

    def test_group_burst_picks_nearest_wanted_size(self) -> None:
        raw = [(1.0, "on"), (1.2, "off"), (5.0, "on"), (5.2, "off")]
        picked, sizes = clockfit.group_burst(raw, 2, 5.1)
        self.same(sizes, [2, 2], "the window's runs are sized")
        self.same(picked[0], (5.0, "on"), "the nearest wanted run is picked")
        missing, sizes = clockfit.group_burst(raw, 3, 0.0)
        self.same(missing, None, "no run of the wanted size")
        self.same(sizes, [2, 2], "and the sizes are still reported")
        four = [(1.0, "on"), (1.2, "off"), (1.4, "on"), (1.6, "off")]
        one, _ = clockfit.group_burst(four, 4, 0.0)
        self.same(one[0], (1.0, "on"), "one run of four is picked whole")

    def test_clip_to_the_recording(self) -> None:
        self.same(clockfit.clip(0, 4, 10), ("0.000", "4.000"), "ordinary")
        self.same(
            clockfit.clip(-1, 4, 10),
            ("0.000", "3.000"),
            "a window before the first frame is clipped",
        )
        self.same(
            clockfit.clip(9, 5, 10),
            ("9.000", "1.000"),
            "a window past the end is clipped",
        )
        self.same(
            clockfit.clip(11, 2, 10),
            ("11.000", "0.000"),
            "a window entirely after the file is empty",
        )

    def test_spans_and_states(self) -> None:
        edges = clockfit.burst_edges(clockfit.parse_edges(fixtures.CLOCK_EDGES), 1)
        self.same(clockfit.device_span(edges), 0.2, "the phone's burst is 0.2 s")
        picked = [(1.0, "on"), (1.2, "off")]
        self.same(clockfit.video_span(picked), 0.2, "the video's is 0.2 s")
        self.same(clockfit.first_mismatch(edges, picked), None, "the states match")
        self.same(
            clockfit.first_mismatch(edges, [(1.0, "on"), (1.2, "on")]),
            (2, "off", "on"),
            "a state mismatch names the flash",
        )

    def test_guess_is_rounded_to_milliseconds(self) -> None:
        self.same(
            clockfit.guess_for(1.0, 1000000000, 5000000000),
            5.0,
            "four seconds after the first pair",
        )
        self.same(
            clockfit.guess_for(1.0004, 1000000000, 1000000001),
            1.0,
            "the shell's %.3f rounding is kept",
        )

    def test_fit_line(self) -> None:
        fit = clockfit.fit_pairs(
            [
                clockfit.Pair(1_000_000_000, 10.0),
                clockfit.Pair(2_000_000_000, 12.0),
            ]
        )
        self.same(fit.ref_ns, 1_000_000_000, "ref is the first pair")
        self.same(fit.offset_s, 10.0, "offset")
        self.same(fit.drift, 2.0, "drift")
        self.same(fit.edges, 2, "edge count")
        self.same(fit.worst_edge_ms, 0.0, "a perfect line has no worst edge")
        self.same(fit.fit_ms, 0.0, "and no fit error")
        self.same(
            clockfit.summary(fit),
            "clock: 2 edges, +/- 0.0 ms, offset 10.000000000 s, "
            "drift 1000000.0 ppm",
            "the summary line",
        )

    def test_fit_refusals(self) -> None:
        with self.assertRaises(clockfit.ClockError) as short:
            clockfit.fit_pairs([clockfit.Pair(1, 1.0)])
        self.checks += 1
        self.same(
            str(short.exception),
            "need at least two flashes to fit a clock",
            "one flash is not a fit",
        )
        with self.assertRaises(clockfit.ClockError) as same:
            clockfit.fit_pairs(
                [clockfit.Pair(1, 1.0), clockfit.Pair(1, 2.0)]
            )
        self.checks += 1
        self.same(
            str(same.exception),
            "every flash was at the same instant",
            "a vertical line is not a fit",
        )

    def test_clock_text(self) -> None:
        fit = clockfit.fit_pairs(
            [
                clockfit.Pair(1_000_000_000, 10.0),
                clockfit.Pair(2_000_000_000, 12.0),
            ]
        )
        self.same(
            clockfit.clock_text(fit),
            "# the phone's clock in the recording, from 2 flashed edges\n"
            "# video_seconds = offset_s + drift * (device_ns - ref_ns) / 1e9\n"
            "ref_ns 1000000000\n"
            "offset_s 10.000000000\n"
            "drift 2.000000000\n"
            "drift_ppm 1000000.0\n"
            "worst_edge_ms 0.0\n"
            "fit_ms 0.0\n"
            "edges 2\n",
            "clock.txt keeps every name and its formatting",
        )


class ClockCliTest(support.Case):
    def setUp(self) -> None:
        super().setUp()
        self.recording = self.sandbox.write("run/screen.mp4", "")
        self.edges = self.sandbox.write("run/clock.csv", fixtures.CLOCK_EDGES)

    def run_clock(self, *args):
        return self.sandbox.run(
            [sys.executable, str(support.LIVE / "harness" / "clock.py"), *args]
        )

    def test_fit_end_to_end_writes_clock_txt(self) -> None:
        self.sandbox.install_ffmpeg(
            [
                (0.5, False),
                (1.0, True),
                (1.2, False),
                (4.5, False),
                (5.0, True),
                (5.2, False),
            ]
        )
        done = self.run_clock("run")
        self.same(done.returncode, 0, f"the fit succeeds: {done.stderr}")
        self.same(
            done.stdout,
            "clock: 4 edges, +/- 0.0 ms, offset 1.000000000 s, "
            "drift 0.0 ppm\n",
            "the summary is the shell's one line",
        )
        self.same(
            self.text(self.recording.parent / "clock.txt"),
            "# the phone's clock in the recording, from 4 flashed edges\n"
            "# video_seconds = offset_s + drift * (device_ns - ref_ns) / 1e9\n"
            "ref_ns 1000000000\n"
            "offset_s 1.000000000\n"
            "drift 1.000000000\n"
            "drift_ppm 0.0\n"
            "worst_edge_ms 0.0\n"
            "fit_ms 0.0\n"
            "edges 4\n",
            "clock.txt names the mapping and both bursts",
        )

    def test_missing_recording_and_edges(self) -> None:
        missing = self.run_clock("nowhere")
        self.same(missing.returncode, 1, "a run without a recording fails")
        self.contains(
            missing.stderr, "no recording at nowhere/screen.mp4", "and says where"
        )
        self.recording.unlink()
        no_edges = self.run_clock("run")
        self.contains(
            no_edges.stderr, "no recording at run/screen.mp4", "the recording is first"
        )
        self.sandbox.write("run/screen.mp4", "")
        self.edges.unlink()
        no_edges = self.run_clock("run")
        self.same(no_edges.returncode, 1, "a run without edges fails")
        self.contains(
            no_edges.stderr, "no flash edges at run/clock.csv", "and says where"
        )

    def test_usage(self) -> None:
        done = self.run_clock()
        self.same(done.returncode, 2, "no directory is a usage error")
        self.contains(done.stderr, "usage: clock.py [--radius S] RUN_DIR", "usage")
        helpdone = self.run_clock("--help")
        self.same(helpdone.returncode, 2, "the shell's help is the usage error")
        bad_radius = self.run_clock("--radius")
        self.same(bad_radius.returncode, 2, "a flag without its value is usage")
        bad_flag = self.run_clock("--wat", "run")
        self.same(bad_flag.returncode, 2, "an unknown flag is usage")

    def test_a_missing_burst_says_what_it_saw(self) -> None:
        self.sandbox.write(
            "run/clock.csv",
            "device_ns,wall_ms,on|off\n"
            "1000000000,0,on\n"
            "1200000000,200,off\n"
            "5000000000,4000,on\n"
            "5200000000,4200,off\n"
            "5400000000,4400,on\n",
        )
        self.sandbox.install_ffmpeg([(0.5, False), (1.0, True), (1.2, False)])
        done = self.run_clock("run")
        self.same(done.returncode, 1, "the second burst is not there")
        self.contains(
            done.stderr,
            "burst 2: 3 flashes in the phone's record, the recording has runs of",
            "the failure names the burst and its size",
        )
        self.contains(
            done.stderr,
            "so did the recorder start late?",
            "and asks the question the shell asked",
        )

    def test_a_failed_window_is_tried_wider_and_then_reported(self) -> None:
        self.sandbox.install_ffmpeg([], fail=True)
        done = self.run_clock("run")
        self.same(done.returncode, 1, "ffmpeg failing is a failed fit")
        self.contains(done.stderr, "burst 1:", "the first burst is reported")

    def test_ffmpeg_lookup_failure_messages(self) -> None:
        with mock.patch("harness.clock.shutil.which", return_value=None):
            with mock.patch("harness.clock.os.access", return_value=False):
                with self.assertRaises(clockfit.ClockError) as missing:
                    clock.find_ffmpeg({})
                self.checks += 1
                self.same(
                    str(missing.exception),
                    "no ffmpeg: install it, set PHONE_LIVE_FFMPEG, or use "
                    "nix-shell -p ffmpeg",
                    "the ffmpeg message is the shell's",
                )
                with self.assertRaises(clockfit.ClockError) as probe:
                    clock.find_ffprobe({}, "/some/ffmpeg")
                self.checks += 1
                self.same(
                    str(probe.exception),
                    "no ffprobe next to /some/ffmpeg",
                    "the ffprobe message is the shell's",
                )


if __name__ == "__main__":
    unittest.main()
