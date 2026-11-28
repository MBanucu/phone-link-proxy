"""The Python replacement for the live harness's three shell helpers.

``tests/live/phone.sh``, ``clock.sh`` and ``eta.sh`` stay the running helpers
until the caller moves; this package is their tested replacement. The split is
the same for all three:

* ``uiautomator``, ``clockfit`` and ``eta`` are pure: XML or CSV text in,
  nodes or numbers out, no subprocess and no file.
* ``phone`` and ``clock`` are the thin edge: adb, ffmpeg, the run folder, the
  broadcasts. They hold no arithmetic and no matching rules of their own.
* ``evidence`` holds the names, headers and row shapes a run's files keep, so
  a later step module cannot drift from ``results.py`` or ``pictures.py``.

Run the CLI with ``tests/live/harness/clock.py`` (the shell's arguments and
output), and the tests with ``python3 -m unittest discover -s tests/python``.
"""

from __future__ import annotations

__all__ = [
    "clock",
    "clockfit",
    "eta",
    "evidence",
    "phone",
    "uiautomator",
]
