#!/usr/bin/env python3
"""Fit a run's flashes to its recording: the CLI of ``tests/live/clock.sh``.

    tests/live/harness/clock.py [--radius S] RUN_DIR

RUN_DIR holds ``screen.mp4`` and ``clock.csv`` (``device_ns,wall_ms,on|off``).
The phone paints the flash colour at the start and at the end of the run; this
finds the two bursts in the video, matches them by length and on/off states,
fits the line and writes ``RUN_DIR/clock.txt``:

    video_seconds = offset_s + drift * (device_ns - ref_ns) / 1e9

The search, the formulas, the rounding and the messages are ``clock.sh``'s;
:mod:`harness.clockfit` is the pure arithmetic and this is the thin edge:
ffmpeg/ffprobe, the files and the exit status.

ffmpeg and ffprobe are looked up the same way as before: ``PHONE_LIVE_FFMPEG``
and ``PHONE_LIVE_FFPROBE``, then ``PATH``, then ``/run/current-system/sw/bin``.
Exit status is 0 only when every flash was found and the fit has an edge per
flash.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Sequence

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from harness import clockfit
else:
    from . import clockfit

RECORDING = "screen.mp4"
EDGES = "clock.csv"
CLOCK = "clock.txt"


def usage(program: str) -> int:
    print(f"usage: {program} [--radius S] RUN_DIR", file=sys.stderr)
    return 2


def parse_args(argv: Sequence[str], program: str) -> tuple[float, str] | int:
    """The shell's hand-written parser, including its first-positional stop."""
    radius = "2"
    rest = list(argv)
    directory = ""
    while rest:
        argument = rest.pop(0)
        if argument == "--radius":
            if not rest:
                return usage(program)
            radius = rest.pop(0)
        elif argument in ("-h", "--help"):
            return usage(program)
        elif argument == "--":
            directory = rest[0] if rest else ""
            rest = []
            break
        elif argument.startswith("-"):
            return usage(program)
        else:
            directory = argument
            rest = []
            break
    if not directory:
        return usage(program)
    return _number(radius), directory


def find_ffmpeg(env: dict[str, str]) -> str:
    ffmpeg = env.get("PHONE_LIVE_FFMPEG") or ""
    if not ffmpeg:
        ffmpeg = shutil.which("ffmpeg", path=env.get("PATH")) or ""
    if not ffmpeg and os.access("/run/current-system/sw/bin/ffmpeg", os.X_OK):
        ffmpeg = "/run/current-system/sw/bin/ffmpeg"
    if not ffmpeg:
        raise clockfit.ClockError(
            "no ffmpeg: install it, set PHONE_LIVE_FFMPEG, or use "
            "nix-shell -p ffmpeg"
        )
    return ffmpeg


def find_ffprobe(env: dict[str, str], ffmpeg: str) -> str:
    ffprobe = env.get("PHONE_LIVE_FFPROBE") or ""
    if not ffprobe:
        ffprobe = shutil.which("ffprobe", path=env.get("PATH")) or ""
    if not ffprobe and os.access(
        "/run/current-system/sw/bin/ffprobe", os.X_OK
    ):
        ffprobe = "/run/current-system/sw/bin/ffprobe"
    if not ffprobe:
        ffprobe = os.path.join(os.path.dirname(ffmpeg), "ffprobe")
    if not os.access(ffprobe, os.X_OK):
        raise clockfit.ClockError(f"no ffprobe next to {ffmpeg}")
    return ffprobe


def probe_duration(ffprobe: str, recording: Path) -> str:
    """The first line of ffprobe's duration, or "" when it could not read it."""
    try:
        done = subprocess.run(
            [
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "csv=p=0",
                str(recording),
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            check=False,
        )
    except OSError:
        return ""
    lines = done.stdout.splitlines()
    return lines[0] if lines else ""


def window_edges(
    ffmpeg: str,
    recording: Path,
    start: str,
    length: str,
) -> list[tuple[float, str]]:
    """The transitions of one window, or [] when ffmpeg could not read it."""
    try:
        done = subprocess.run(
            [
                ffmpeg,
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "info",
                "-ss",
                start,
                "-copyts",
                "-t",
                length,
                "-i",
                str(recording),
                "-vf",
                "scale=1:1:flags=area,showinfo",
                "-f",
                "null",
                "-",
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
            errors="replace",
            check=False,
        )
    except OSError:
        return []
    if done.returncode != 0:
        return []
    return clockfit.parse_showinfo(done.stderr)


def fit_recording(
    run_dir: str | os.PathLike[str],
    radius: float = 2.0,
    *,
    env: dict[str, str] | None = None,
) -> clockfit.Fit:
    """Find both bursts, fit them, write clock.txt and return the fit."""
    environment = dict(os.environ if env is None else env)
    directory = Path(run_dir)
    recording = directory / RECORDING
    edge_file = directory / EDGES
    if not recording.is_file():
        raise clockfit.ClockError(f"no recording at {recording}")
    if not edge_file.is_file():
        raise clockfit.ClockError(f"no flash edges at {edge_file}")

    ffmpeg = find_ffmpeg(environment)
    ffprobe = find_ffprobe(environment, ffmpeg)
    duration = probe_duration(ffprobe, recording)
    if not duration:
        raise clockfit.ClockError(f"cannot read the length of {recording}")

    try:
        edge_text = edge_file.read_text(encoding="utf-8", errors="replace")
    except OSError:
        edge_text = ""
    edges = clockfit.parse_edges(edge_text)
    if not edges:
        raise clockfit.ClockError(f"no flash edges in {edge_file}")

    pairs: list[clockfit.Pair] = []
    anchor_pts: float | None = None
    anchor_ns: int | None = None
    for burst in clockfit.bursts(edges):
        rows = clockfit.burst_edges(edges, burst)
        want = len(rows)
        host = rows[0].device_ns
        picked: list[tuple[float, str]] | None = None
        sizes: list[int] = []
        if anchor_pts is None:
            guess = 0.0
            guess_text = "0"
            windows = (4.0, 12.0, 30.0)
        else:
            guess = clockfit.guess_for(anchor_pts, anchor_ns or 0, host)
            guess_text = format(guess, ".3f")
            windows = (radius, 6.0, 20.0)
        for edge in windows:
            if anchor_pts is None:
                start, length = clockfit.clip(0.0, edge, _number(duration))
            else:
                start, length = clockfit.clip(
                    guess - edge, 2.0 * edge, _number(duration)
                )
            raw = window_edges(ffmpeg, recording, start, length)
            picked, sizes = clockfit.group_burst(raw, want, guess)
            if picked is not None:
                break
        if picked is None:
            raise clockfit.ClockError(
                f"burst {burst}: {want} flashes in the phone's record, "
                f"the recording has runs of {clockfit.sizes_text(sizes)}\n"
                "  (a run is a set of flashes less than a second apart; "
                "the phone put\n"
                f"   this one at {guess_text} s of {duration}, "
                "so did the recorder start late?)"
            )
        device_span = clockfit.device_span(rows)
        video_span = clockfit.video_span(picked)
        if abs(device_span - video_span) > clockfit.SPAN_TOLERANCE_S:
            raise clockfit.ClockError(
                f"burst {burst}: {format(device_span, '.3f')} s in the record, "
                f"{format(video_span, '.3f')} s in the recording\n"
                "  (that is not the same burst; the recorder may have "
                "started late)"
            )
        mismatch = clockfit.first_mismatch(rows, picked)
        if mismatch is not None:
            index, device_state, video_state = mismatch
            raise clockfit.ClockError(
                f"burst {burst}: the {index}th flash is {device_state} in the "
                f"record and {video_state} in the video"
            )
        for edge, point in zip(rows, picked):
            pairs.append(
                clockfit.Pair(device_ns=edge.device_ns, video_s=point[0])
            )
        if anchor_pts is None:
            anchor_ns = pairs[0].device_ns
            anchor_pts = pairs[0].video_s

    fit = clockfit.fit_pairs(pairs)
    (directory / CLOCK).write_text(clockfit.clock_text(fit), encoding="utf-8")
    return fit


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    program = Path(sys.argv[0]).name
    parsed = parse_args(arguments, program)
    if isinstance(parsed, int):
        return parsed
    radius, directory = parsed
    try:
        fit = fit_recording(directory, radius)
    except clockfit.ClockError as error:
        print(str(error), file=sys.stderr)
        return 1
    except OSError as error:
        print(str(error), file=sys.stderr)
        return 1
    print(clockfit.summary(fit))
    return 0


def _number(text: str) -> float:
    """awk's numeric coercion for a shell-provided value."""
    try:
        return float(text.strip())
    except (TypeError, ValueError):
        return 0.0


if __name__ == "__main__":
    raise SystemExit(main())
