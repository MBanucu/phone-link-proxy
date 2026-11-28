"""The live estimate of ``tests/live/eta.sh``: medians in, a plan out.

The history is ``result-live/timings.csv`` (or ``timings-partial.csv``): one
row per step per run, append-only, so the last N rows of a step are its last N
runs. That is the same file a person reads afterwards; a run reads it while it
is going.

This module is the pure half. It copies the shell's arithmetic and its
rounding, because the plan line and the median history are compared across
runs:

* the CSV's first row is skipped unconditionally (the header) and a step is
  identified by column 2; ``TOTAL`` and ``PARTIAL`` are runs, not steps.
* A ``PARTIAL`` row names a run that is not part of the full history. The
  shell's ``full`` mode drops every row of such a run, including rows that
  come before the PARTIAL row; this does the same by collecting the run ids
  first.
* awk turned the median into a string with its default ``CONVFMT`` (six
  significant digits) before the shell converted it to milliseconds, so
  1234.5678 s became 1234570 ms, and a huge median reached exponent notation.
  :func:`load` rounds the same way.
* ``printf "%.0f"`` is C's rounding (half to even), which is what
  :func:`format` and ``format(value, ".0f")`` use.
* :func:`left` is bash integer arithmetic: the over-estimate branch truncates.

``eta.sh`` is sourced by ``proxy-app.sh``, not invoked, so it has no command
line and neither does this module. ``ticker`` is the one edge: a state file
and a clock, deliberately thin so a caller can run it in a thread.
"""

from __future__ import annotations

import csv
import io
import os
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence, TextIO

DEFAULT_RUNS = 5
DEFAULT_SLOW_MS = 30000


@dataclass(frozen=True)
class Plan:
    """The two lines ``eta_plan`` printed and the total it left behind."""

    total_ms: int
    lines: tuple[str, ...]

    @property
    def text(self) -> str:
        return "\n".join(self.lines) + "\n"


# -- history ---------------------------------------------------------------


def load(
    path: str | os.PathLike[str],
    runs: int = DEFAULT_RUNS,
    *,
    full: bool = False,
) -> dict[str, int]:
    """Medians in milliseconds for every step in the history."""
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    rows = list(csv.reader(io.StringIO(text)))
    if not rows:
        return {}
    # awk's `NR == 1 { next }`: the first row is the header even when it is not.
    body = rows[1:]
    partial: set[str] = set()
    records: list[tuple[str, str, float]] = []
    for row in body:
        if len(row) < 3 or row[1] == "":
            continue
        if row[1] == "PARTIAL":
            partial.add(row[0])
            continue
        if row[1] == "TOTAL":
            continue
        records.append((row[0], row[1], _number(row[2])))
    values: dict[str, list[float]] = {}
    for run, step, seconds in records:
        if full and run in partial:
            continue
        values.setdefault(step, []).append(seconds)
    medians: dict[str, int] = {}
    for step, seconds in values.items():
        window = seconds[-runs:] if runs > 0 else []
        if not window:
            # The shell's `from = n - runs + 1` with runs <= 0 also indexed an
            # empty array; nothing in the harness asks for that.
            medians[step] = 0
            continue
        median = _median(window)
        # awk's CONVFMT (%.6g) sits between the median and the millisecond.
        medians[step] = int(format(float(format(median, ".6g")) * 1000.0, ".0f"))
    return medians


def fallback(medians: dict[str, int] | None) -> int | None:
    """The median of the known prices, for a step with no history of its own."""
    values = sorted(int(value) for value in (medians or {}).values())
    if not values:
        return None
    return _median_int(values)


def prices(
    order: Sequence[str],
    medians: dict[str, int] | None,
    borrowed_ms: int | None,
    borrowed: Iterable[str] = (),
) -> tuple[dict[str, int], list[str]]:
    """The prices the caller builds before ``plan``.

    A step with no median is priced at ``borrowed_ms`` (or 0) and named in the
    returned borrowed list; the caller's own seeded prices can be named too.
    """
    known = medians or {}
    marked = list(borrowed)
    estimate: dict[str, int] = {}
    for step in order:
        if step in known:
            estimate[step] = known[step]
        else:
            estimate[step] = borrowed_ms if borrowed_ms is not None else 0
            marked.append(step)
    return estimate, marked


# -- formatting and arithmetic --------------------------------------------


def format_ms(milliseconds: int) -> str:
    """Milliseconds as a person reads them: 0.4s, 12.4s, 1m02s, 1h02m."""
    ms = int(milliseconds)
    if ms < 0:
        ms = 0
    seconds = ms / 1000.0
    if seconds < 60:
        return format(seconds, ".1f") + "s"
    if seconds < 3600:
        return f"{int(seconds / 60)}m{int(seconds) % 60:02d}s"
    return f"{int(seconds / 3600)}h{int(seconds / 60) % 60:02d}m"


def left(current: int, after: int, elapsed: int) -> int:
    """Milliseconds from now until the end, or at least none."""
    cur = int(current or 0)
    rest = int(after or 0)
    gone = int(elapsed or 0)
    if cur <= 0:
        remaining = rest
    elif gone <= cur:
        remaining = rest + cur - gone
    else:
        # The step is over its estimate: price the rest at the same pace.
        remaining = (cur + rest) * gone // cur - gone
    return max(remaining, 0)


def slow(current: int, elapsed: int, slow_ms: int = DEFAULT_SLOW_MS) -> bool:
    """Whether a step is the news: twice its estimate and slow_ms over it."""
    cur = int(current or 0)
    gone = int(elapsed or 0)
    if cur <= 0:
        return False
    if gone < cur * 2:
        return False
    return gone - cur >= slow_ms


def plan(
    steps: Sequence[str],
    *,
    medians: dict[str, int] | None = None,
    estimate: dict[str, int] | None = None,
    borrowed: Iterable[str] | str = (),
) -> Plan:
    """One plan line and, when needed, the "priced like the rest" line.

    With ``estimate``, those are the prices (the caller already knows which
    were borrowed); without it, the prices come from ``medians`` and a step
    with none borrows :func:`fallback` and is marked ``~``.
    """
    have_estimate = estimate is not None
    marked = _borrowed_set(borrowed)
    total = 0
    line = ""
    assumed = ""
    for step in steps:
        borrowed_here = False
        if have_estimate:
            value = int((estimate or {}).get(step, 0) or 0)
            borrowed_here = step in marked
        else:
            known = (medians or {}).get(step)
            if known is None:
                value = fallback(medians)
                borrowed_here = value is not None
            else:
                value = int(known)
            if value is None:
                value = 0
        if value <= 0:
            part = "?"
            assumed += " " + step
        elif borrowed_here:
            part = "~" + format_ms(value)
            assumed += " " + step
        else:
            part = format_ms(value)
        total += value
        line += f" {part} {step} ·"
    lines = [f"plan {format_ms(total)}:{line}"]
    if assumed:
        lines.append(
            f"plan   (no history of its own, priced like the rest:{assumed})"
        )
    return Plan(total_ms=total, lines=tuple(lines))


# -- the mark and the ticker ----------------------------------------------


def mark_fields(
    order: Sequence[str],
    estimate: dict[str, int] | None,
    name: str,
    started_ms: int,
) -> str | None:
    """The state line for a step boundary, or ``None`` for an unknown step."""
    prices = estimate or {}
    for index, step in enumerate(order):
        if step != name:
            continue
        after = sum(int(prices.get(later, 0) or 0) for later in order[index + 1 :])
        rest = len(order) - index - 1
        return (
            f"{name}\t{int(prices.get(name, 0) or 0)}\t{after}\t"
            f"{int(started_ms)}\t{rest}\n"
        )
    return None


def write_mark(
    state_path: str | os.PathLike[str] | None,
    order: Sequence[str],
    estimate: dict[str, int] | None,
    name: str,
    started_ms: int,
) -> bool:
    """Write the ticker's state whole and move it into place."""
    if not state_path:
        return False
    line = mark_fields(order, estimate, name, started_ms)
    if line is None:
        return False
    path = Path(state_path)
    part = path.with_name(path.name + ".part")
    part.write_text(line, encoding="utf-8")
    os.replace(part, path)
    return True


def read_mark(path: str | os.PathLike[str]) -> tuple[str, int, int, int, int] | None:
    """The five fields of the ticker's state, or ``None`` when it is not there."""
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    fields = text.splitlines()[0].split("\t") if text.splitlines() else []
    if len(fields) < 5 or fields[0] == "":
        return None
    try:
        return (
            fields[0],
            int(fields[1]),
            int(fields[2]),
            int(fields[3]),
            int(fields[4]),
        )
    except ValueError:
        return None


class Ticker:
    """The background line: the step, its usual time, and what is left.

    ``tick()`` is the unit a test can call; ``run()`` is the loop the harness
    starts in a thread. ``now_ms`` and ``sleep`` are seams so the line does not
    depend on how loaded the machine is.
    """

    def __init__(
        self,
        state_path: str | os.PathLike[str],
        every: float = 15.0,
        *,
        out: TextIO | None = None,
        now_ms=None,
        stop: threading.Event | None = None,
        slow_ms: int = DEFAULT_SLOW_MS,
    ) -> None:
        self.state_path = Path(state_path)
        self.every = float(every)
        self.out = out or sys.stdout
        self.now_ms = now_ms or (lambda: int(time.time() * 1000))
        self.stop = stop or threading.Event()
        self.slow_ms = slow_ms
        self.last = ""
        self.warned = False

    def tick(self) -> list[str]:
        mark = read_mark(self.state_path)
        if mark is None:
            return []
        step, current, after, started, rest = mark
        if step != self.last:
            self.last = step
            self.warned = False
        elapsed = int(self.now_ms()) - started
        remaining = left(current, after, elapsed)
        note = ""
        if current > 0 and elapsed > current:
            note = ", over its estimate"
        word = "step" if rest == 1 else "steps"
        lines: list[str] = []
        if not self.warned and slow(current, elapsed, self.slow_ms):
            self.warned = True
            ratio = format(elapsed / current, ".1f")
            lines.append(
                f"slow {step} {format_ms(elapsed)} in, usually "
                f"{format_ms(current)} ({ratio}×, {format_ms(elapsed - current)} over)"
            )
        lines.append(
            f"eta {format_ms(remaining)} left  ({step} {format_ms(elapsed)} in, "
            f"usually {format_ms(current)}{note}; {rest} {word} after)"
        )
        return lines

    def run(self) -> None:
        while not self.stop.is_set():
            if self.stop.wait(self.every):
                break
            for line in self.tick():
                print(line, file=self.out, flush=True)


# -- the shell's number habits --------------------------------------------


def _number(text: str) -> float:
    """awk's numeric coercion: a numeric prefix, or zero."""
    import re

    match = re.match(r"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)", text.strip())
    if match is None:
        return 0.0
    try:
        return float(match.group(0))
    except ValueError:
        return 0.0


def _median(values: Sequence[float]) -> float:
    ordered = sorted(values)
    count = len(ordered)
    if count % 2:
        return ordered[(count + 1) // 2 - 1]
    return (ordered[count // 2 - 1] + ordered[count // 2]) / 2.0


def _median_int(values: Sequence[int]) -> int:
    ordered = sorted(values)
    count = len(ordered)
    if count % 2:
        median = ordered[(count + 1) // 2 - 1]
    else:
        median = (ordered[count // 2 - 1] + ordered[count // 2]) / 2.0
    return int(format(float(median), ".0f"))


def _borrowed_set(borrowed: Iterable[str] | str) -> set[str]:
    if isinstance(borrowed, str):
        return set(borrowed.split())
    return set(borrowed)
