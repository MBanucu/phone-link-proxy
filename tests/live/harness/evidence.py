"""The evidence files' names, headers and row shapes, in one place.

A run's history is the files it leaves, not a database: ``timings.csv`` is
append-only, ``uplink.csv`` says whose fault a failed tunnel check was,
``input.csv`` records the instants ``input`` could not tell, and
``failures.csv`` says where in the recording a check was decided. The shapes
are a contract with ``tests/live/results.py``, ``tests/live/pictures.py`` and
the clock fit, so they live here once instead of in a step function's printf.

These writers reproduce the shell's ``printf`` exactly, including its quoting:
``uplink.csv`` quotes the egress and verdict columns, ``checks.csv`` doubles a
quote inside the claim, and the timing rows are unquoted because step names
have no commas. Nothing here reads a file or makes a decision.
"""

from __future__ import annotations

import os
from pathlib import Path

TIMINGS_HEADER = "run,step,seconds,passed,failed,status"
CHECKS_HEADER = "run,step,verdict,file,line,function,decided_ms,claim"
INPUT_HEADER = "start_ns,end_ns,kind,what"
UPLINK_HEADER = "decided_ms,port,ping_ip,ping_host,egress,verdict"
FAILURES_HEADER = "check,step,start_ns,fail_ns,end_ns"
RUN_SETTINGS_HEADER = "key,value"

TIMINGS = "timings.csv"
TIMINGS_PARTIAL = "timings-partial.csv"
CHECKS = "checks.csv"
INPUT = "input.csv"
UPLINK = "uplink.csv"
FAILURES = "failures.csv"
RUN_SETTINGS = "run.csv"
CLOCK = "clock.txt"
CLOCK_EDGES = "clock.csv"


def append_timing(
    path: str | os.PathLike[str],
    run: str,
    step: str,
    seconds: str | float,
    passed: int,
    failed: int,
    status: str,
) -> None:
    """Append one step row, writing the header first when the file is new."""
    target = Path(path)
    line = (
        f"{run},{step},{_seconds(seconds)},{passed},{failed},{status}\n"
    )
    _append(target, TIMINGS_HEADER, line)


def append_check(
    path: str | os.PathLike[str],
    *,
    run: str,
    step: str,
    verdict: str,
    file: str,
    line: int,
    function: str,
    decided_ms: int,
    claim: str,
) -> None:
    """Append one verdict, with the claim quoted the way checks.csv expects."""
    target = Path(path)
    quoted = claim.replace('"', '""')
    row = (
        f'{run},{step},{verdict},{file},{line},{function},{decided_ms},'
        f'"{quoted}"\n'
    )
    _append(target, CHECKS_HEADER, row)


def append_input(
    path: str | os.PathLike[str],
    start_ns: int,
    end_ns: int,
    kind: str,
    what: str,
) -> None:
    target = Path(path)
    _append(target, INPUT_HEADER, f"{start_ns},{end_ns},{kind},{what}\n")


def append_uplink(
    path: str | os.PathLike[str],
    decided_ms: int,
    port: int,
    ping_ip: str,
    ping_host: str,
    egress: str,
    verdict: str,
) -> None:
    """One failure's route; egress and verdict are quoted, as the shell did."""
    target = Path(path)
    _append(
        target,
        UPLINK_HEADER,
        f'{decided_ms},{port},{ping_ip},{ping_host},"{egress}","{verdict}"\n',
    )


def append_failure(
    path: str | os.PathLike[str],
    check: int,
    step: str,
    start_ns: int,
    fail_ns: int,
    end_ns: int,
) -> None:
    target = Path(path)
    _append(
        target,
        FAILURES_HEADER,
        f"{check},{step},{start_ns},{fail_ns},{end_ns}\n",
    )


def append_setting(
    path: str | os.PathLike[str],
    key: str,
    value: str,
) -> None:
    target = Path(path)
    _append(target, RUN_SETTINGS_HEADER, f"{key},{value}\n")


def _append(target: Path, header: str, line: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        target.write_text(header + "\n", encoding="utf-8")
    with target.open("a", encoding="utf-8") as handle:
        handle.write(line)


def _seconds(value: str | float) -> str:
    if isinstance(value, str):
        return value
    return format(float(value), ".1f")
