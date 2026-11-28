"""The clock fit of ``tests/live/clock.sh``: flashes in, a mapping out.

The phone paints the flash colour at instants it writes down (``clock.csv``),
and the recording shows those flashes as runs of frames. Matching the runs
gives one ``(device_ns, video_seconds)`` pair per flash; a least-squares line
through them is the mapping ``clock.txt`` holds:

    video_seconds = offset_s + drift * (device_ns - ref_ns) / 1e9

This module is the pure half: it parses the two texts and does the arithmetic
with the shell's own rules, including the ones a cleaner implementation would
change and a run's history would notice:

* ``clock.csv``'s first line is skipped unconditionally, as awk's ``NR == 1``
  did — even if the file has no header.
* A burst ends at a wall-clock gap strictly greater than 2500 ms; a run inside
  a window ends at a video gap strictly greater than 2.5 s.
* Counts and doubles go through the shell's ``printf`` formats. ``%.9f`` for
  offset/drift, ``%.1f`` for milliseconds, ``%.3f`` for window bounds; the
  summary and ``clock.txt`` print those rounded values, and ``drift_ppm`` is
  computed from the rounded drift, not the raw one.
* A window is clipped to the recording before ffmpeg is asked for it, and a
  burst has to last as long as the phone says it did (within 0.25 s) or it is
  a different burst.
* ``ref_ns`` is the first pair's device instant. The shell printed the text it
  read from ``clock.csv``; this stores an int, so a deliberately zero-padded
  value would be normalised. Device clocks are not zero-padded.

``clock.py`` owns ffmpeg, ffprobe, the files and the command line.
"""

from __future__ import annotations

import csv
import math
import re
from dataclasses import dataclass
from typing import Iterable, Sequence

# CSV parsing: the shell used `awk -F,`, and clock.csv has no quoting. The csv
# module is used anyway so a future writer cannot make a field that splits a
# row in two.
_DEVICE_NS = re.compile(r"^[0-9]+$")
_PTS = re.compile(r"pts_time:[0-9.]+")
_MEAN = re.compile(r"mean:\[([0-9]+) ([0-9]+) ([0-9]+)\]")

BURST_GAP_MS = 2500.0
RUN_GAP_S = 2.5
SPAN_TOLERANCE_S = 0.25


class ClockError(RuntimeError):
    """A fit could not be made; the message is what the shell printed."""


@dataclass(frozen=True)
class FlashEdge:
    """One line of ``clock.csv``, with the burst awk assigned it to."""

    burst: int
    device_ns: int
    wall_ms: float
    state: str


@dataclass(frozen=True)
class Pair:
    """One flash as the phone and the recording each saw it."""

    device_ns: int
    video_s: float


@dataclass(frozen=True)
class Fit:
    """The line through the pairs, in the units clock.txt uses."""

    ref_ns: int
    offset_s: float
    drift: float
    worst_edge_ms: float
    fit_ms: float
    edges: int


# -- parsing ---------------------------------------------------------------


def parse_edges(text: str) -> list[FlashEdge]:
    """``clock.csv`` grouped into bursts, exactly as the awk preamble did."""
    rows = list(csv.reader(text.splitlines()))
    edges: list[FlashEdge] = []
    burst = 0
    started = False
    last = 0.0
    for row in rows[1:]:  # NR == 1 { next }
        if len(row) < 3:
            continue
        if _DEVICE_NS.match(row[0]) is None:
            continue
        wall = _number(row[1])
        if not started or wall - last > BURST_GAP_MS:
            burst += 1
        started = True
        last = wall
        edges.append(
            FlashEdge(
                burst=burst,
                device_ns=int(row[0]),
                wall_ms=wall,
                state=row[2],
            )
        )
    return edges


def bursts(edges: Iterable[FlashEdge]) -> list[int]:
    """The burst numbers in the order the file presented them."""
    seen: list[int] = []
    for edge in edges:
        if edge.burst not in seen:
            seen.append(edge.burst)
    return seen


def burst_edges(edges: Iterable[FlashEdge], burst: int) -> list[FlashEdge]:
    return [edge for edge in edges if edge.burst == burst]


def parse_showinfo(log: str) -> list[tuple[float, str]]:
    """``window_edges``' awk: a transition line per state change.

    ffmpeg's showinfo prints the ``pts_time`` and the scaled frame's ``mean``
    on the same log line. The shell required that; a version that split them
    would have found no flashes, so this keeps the same requirement.
    """
    transitions: list[tuple[float, str]] = []
    previous: bool | None = None
    for line in log.splitlines():
        if "pts_time:" not in line:
            continue
        pts = _PTS.search(line)
        mean = _MEAN.search(line)
        if pts is None or mean is None:
            continue
        value = float(pts.group(0)[len("pts_time:") :])
        _, u, v = (int(part) for part in mean.groups())
        on = u > 195 and v > 200
        if previous is not None and on != previous:
            transitions.append((value, "on" if on else "off"))
        previous = on
    return transitions


def group_burst(
    raw: Sequence[tuple[float, str]], want: int, guess: float
) -> tuple[list[tuple[float, str]] | None, list[int]]:
    """The run with WANT transitions nearest GUESS, and every run's size.

    Returns ``(None, sizes)`` when no run has the wanted number; the caller
    prints the sizes the way the shell did.
    """
    runs: list[list[tuple[float, str]]] = []
    last: float | None = None
    for point in raw:
        if last is None or point[0] - last > RUN_GAP_S:
            runs.append([])
        runs[-1].append(point)
        last = point[0]
    sizes = [len(run) for run in runs]
    best: int | None = None
    nearest = 0.0
    for index, run in enumerate(runs):
        if len(run) != want:
            continue
        spread = abs(run[0][0] - guess)
        if best is None or spread < nearest:
            best = index
            nearest = spread
    if best is None:
        return None, sizes
    return runs[best], sizes


def clip(start: float, length: float, duration: float) -> tuple[str, str]:
    """A window inside the recording, formatted as the shell formatted it."""
    if start < 0:
        length += start
        start = 0.0
    if start + length > duration:
        length = duration - start
    if length < 0:
        length = 0.0
    return _fixed(start, 3), _fixed(length, 3)


def device_span(edges: Sequence[FlashEdge]) -> float:
    """The phone's own burst length, to three decimals, as awk printed it."""
    if not edges:
        return 0.0
    return float(_fixed((edges[-1].wall_ms - edges[0].wall_ms) / 1000.0, 3))


def video_span(points: Sequence[tuple[float, str]]) -> float:
    """The recording's burst length, to three decimals."""
    if not points:
        return 0.0
    return float(_fixed(points[-1][0] - points[0][0], 3))


def states_match(
    edges: Sequence[FlashEdge], points: Sequence[tuple[float, str]]
) -> bool:
    """Whether device and video agree on on/off for every flash."""
    return all(edge.state == point[1] for edge, point in zip(edges, points))


def first_mismatch(
    edges: Sequence[FlashEdge], points: Sequence[tuple[float, str]]
) -> tuple[int, str, str] | None:
    for index, (edge, point) in enumerate(zip(edges, points), start=1):
        if edge.state != point[1]:
            return index, edge.state, point[1]
    return None


def guess_for(anchor_pts: float, anchor_ns: int, host_ns: int) -> float:
    """Place a later burst from the first pair, rounded to milliseconds."""
    # awk read both instants as doubles, so do the same before dividing.
    value = float(anchor_pts) + (float(host_ns) - float(anchor_ns)) / 1e9
    return float(_fixed(value, 3))


# -- the fit ---------------------------------------------------------------


def fit_pairs(pairs: Sequence[Pair]) -> Fit:
    """Least squares through the pairs; the shell's two refusals included."""
    count = len(pairs)
    if count < 2:
        raise ClockError("need at least two flashes to fit a clock")
    ref = pairs[0].device_ns
    xs = [
        (float(pair.device_ns) - float(ref)) / 1e9 for pair in pairs
    ]
    ys = [pair.video_s for pair in pairs]
    mx = sum(xs) / count
    my = sum(ys) / count
    sxx = 0.0
    sxy = 0.0
    for x, y in zip(xs, ys):
        dx = x - mx
        dy = y - my
        sxx += dx * dx
        sxy += dx * dy
    if sxx == 0:
        raise ClockError("every flash was at the same instant")
    drift = sxy / sxx
    offset = my - drift * mx
    worst = 0.0
    squares = 0.0
    for x, y in zip(xs, ys):
        residual = y - (offset + drift * x)
        squares += residual * residual
        worst = max(worst, abs(residual))
    spread = math.sqrt(squares / count) / math.sqrt(count)
    return Fit(
        ref_ns=ref,
        offset_s=float(_fixed(offset, 9)),
        drift=float(_fixed(drift, 9)),
        worst_edge_ms=float(_fixed(worst * 1000.0, 1)),
        fit_ms=float(_fixed(spread * 1000.0, 1)),
        edges=count,
    )


def drift_ppm(fit: Fit) -> float:
    """PPM from the rounded drift, as the shell computed it."""
    return float(_fixed((fit.drift - 1.0) * 1e6, 1))


def clock_text(fit: Fit) -> str:
    """The exact ``clock.txt`` the shell wrote."""
    return (
        f"# the phone's clock in the recording, from {fit.edges} flashed edges\n"
        "# video_seconds = offset_s + drift * (device_ns - ref_ns) / 1e9\n"
        f"ref_ns {fit.ref_ns}\n"
        f"offset_s {_fixed(fit.offset_s, 9)}\n"
        f"drift {_fixed(fit.drift, 9)}\n"
        f"drift_ppm {_fixed(drift_ppm(fit), 1)}\n"
        f"worst_edge_ms {_fixed(fit.worst_edge_ms, 1)}\n"
        f"fit_ms {_fixed(fit.fit_ms, 1)}\n"
        f"edges {fit.edges}\n"
    )


def summary(fit: Fit) -> str:
    """The one line clock.sh printed for the harness to quote."""
    return (
        f"clock: {fit.edges} edges, +/- {_fixed(fit.fit_ms, 1)} ms, "
        f"offset {_fixed(fit.offset_s, 9)} s, "
        f"drift {_fixed(drift_ppm(fit), 1)} ppm"
    )


def sizes_text(sizes: Sequence[int]) -> str:
    """The burst sizes as the shell's sizes file read after command substitution."""
    return " ".join(str(size) for size in sizes)


# -- the shell's number habits --------------------------------------------


def _number(text: str) -> float:
    """awk's numeric coercion: a prefix that looks like a number, else 0."""
    match = re.match(r"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)", text.strip())
    if match is None:
        return 0.0
    try:
        return float(match.group(0))
    except ValueError:
        return 0.0


def _fixed(value: float, places: int) -> str:
    """C's printf("%.Nf"), which is what every shell number went through."""
    return format(value, f".{places}f")
