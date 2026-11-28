"""The shared sandbox: a temporary run folder, adb/ffmpeg stubs, and checks.

No test in this tree needs a phone: ``adb`` is a small Python script that logs
its argv and answers from a table, ``ffmpeg``/``ffprobe`` are scripts that
print the frames or duration a test asks for, and every run folder is under
``tempfile.mkdtemp``. The assertions count themselves so a suite can say how
many branches it covered, not just how many test methods ran.
"""

from __future__ import annotations

import atexit
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
LIVE = ROOT / "tests" / "live"
if str(LIVE) not in sys.path:
    sys.path.insert(0, str(LIVE))

_TOTAL = 0


@atexit.register
def _report_checks() -> None:
    if _TOTAL:
        print(f"\n{_TOTAL} harness Python checks")


class Sandbox:
    """A temporary checkout-shaped run folder with stubs on PATH."""

    def __init__(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="live-harness-python-"))
        self.bin = self.root / "bin"
        self.log = self.root / "log"
        self.artifacts = self.root / "result"
        for directory in (self.bin, self.log, self.artifacts):
            directory.mkdir()

        env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith(("PHONE_", "PROXY_"))
        }
        env.update(
            {
                "PATH": str(self.bin)
                + os.pathsep
                + env.get("PATH", "/usr/bin:/bin"),
                "HOME": str(self.root),
                "TMPDIR": str(self.root),
                "PYTHONDONTWRITEBYTECODE": "1",
                "PYTHONPATH": str(LIVE),
                "ADB_LOG": str(self.log / "adb.log"),
                "ADB_ANSWERS": str(self.log / "adb-answers.json"),
                "ADB_STATE": str(self.log / "adb-state.json"),
            }
        )
        self.env = env
        (self.log / "adb-answers.json").write_text("[]\n", encoding="utf-8")
        (self.log / "adb-state.json").write_text("{}\n", encoding="utf-8")
        (self.log / "adb.log").write_text("", encoding="utf-8")

    def close(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)

    # -- fixtures ------------------------------------------------------
    def write(self, name: str, text: str) -> Path:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def write_bytes(self, name: str, data: bytes) -> Path:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def install_script(self, name: str, body: str) -> Path:
        path = self.bin / name
        # Nix builds have no /usr/bin/env; the test interpreter is the one to
        # put on the stub's shebang.
        if body.startswith("#!"):
            _, _, rest = body.partition("\n")
            body = f"#!{sys.executable}\n{rest}"
        path.write_text(body, encoding="utf-8")
        path.chmod(0o755)
        return path

    # -- adb -----------------------------------------------------------
    def answer_adb(self, handlers: list[dict]) -> None:
        """Install bin/adb; each handler is a match and one or more answers.

        An answer is a string (stdout) or a dict with ``out``, ``err``,
        ``code`` and/or ``b64``. A handler with ``responses`` returns them in
        order and repeats the last one after that.
        """
        (self.log / "adb-answers.json").write_text(
            json.dumps(handlers, indent=2) + "\n", encoding="utf-8"
        )
        (self.log / "adb-state.json").write_text("{}\n", encoding="utf-8")
        (self.log / "adb.log").write_text("", encoding="utf-8")
        self.install_script(
            "adb",
            """#!/usr/bin/env python3
import base64
import json
import os
import sys

argv = sys.argv[1:]
with open(os.environ["ADB_LOG"], "a", encoding="utf-8") as handle:
    handle.write(json.dumps(argv) + "\\n")
with open(os.environ["ADB_ANSWERS"], encoding="utf-8") as handle:
    answers = json.load(handle)
try:
    with open(os.environ["ADB_STATE"], encoding="utf-8") as handle:
        state = json.load(handle)
except (OSError, ValueError):
    state = {}
key = " ".join(argv)
for index, handler in enumerate(answers):
    if handler["match"] not in key:
        continue
    if "responses" in handler:
        responses = handler["responses"]
        used = int(state.get(str(index), 0))
        response = responses[min(used, len(responses) - 1)]
        state[str(index)] = used + 1
        with open(os.environ["ADB_STATE"], "w", encoding="utf-8") as handle:
            json.dump(state, handle)
    else:
        response = handler
    if isinstance(response, str):
        sys.stdout.write(response)
        raise SystemExit(0)
    if "b64" in response:
        sys.stdout.buffer.write(base64.b64decode(response["b64"]))
    else:
        sys.stdout.write(response.get("out", ""))
    sys.stderr.write(response.get("err", ""))
    raise SystemExit(int(response.get("code", 0)))
raise SystemExit(0)
""",
        )

    def adb_calls(self) -> list[list[str]]:
        text = (self.log / "adb.log").read_text(encoding="utf-8")
        return [json.loads(line) for line in text.splitlines() if line]

    def adb_shell_commands(self) -> list[str]:
        commands = []
        for argv in self.adb_calls():
            if "shell" in argv:
                at = argv.index("shell")
                commands.append(" ".join(argv[at + 1 :]))
        return commands

    # -- ffmpeg --------------------------------------------------------
    def install_ffmpeg(
        self,
        frames: list[tuple[float, bool]],
        duration: float = 10.0,
        *,
        fail: bool = False,
    ) -> None:
        """Install ffprobe/ffmpeg scripts that render frames as showinfo lines."""
        self.install_script(
            "ffprobe",
            "#!/usr/bin/env python3\n"
            f"print({duration!r})\n",
        )
        self.install_script(
            "ffmpeg",
            """#!/usr/bin/env python3
import json
import os
import sys

args = sys.argv[1:]

def value(flag, default):
    try:
        return float(args[args.index(flag) + 1])
    except (ValueError, IndexError):
        return default

if os.environ.get("FAKE_FFMPEG_FAIL") == "1":
    raise SystemExit(1)
start = value("-ss", 0.0)
length = value("-t", 10.0)
frames = json.loads(os.environ.get("FAKE_FRAMES", "[]"))
for at, on in frames:
    if start <= float(at) < start + length:
        u = 210 if on else 100
        v = 220 if on else 100
        sys.stderr.write(
            "[Parsed_showinfo_1 @ 0x0] n:0 pts:0 pts_time:"
            + str(at)
            + " duration:1 mean:[75 "
            + str(u)
            + " "
            + str(v)
            + "]\\n"
        )
raise SystemExit(0)
""",
        )
        self.env["FAKE_FRAMES"] = json.dumps(frames)
        self.env["FAKE_FFMPEG_FAIL"] = "1" if fail else "0"
        self.env["PHONE_LIVE_FFMPEG"] = str(self.bin / "ffmpeg")
        self.env["PHONE_LIVE_FFPROBE"] = str(self.bin / "ffprobe")

    def run(
        self,
        argv: list[str],
        *,
        env: dict[str, str] | None = None,
        timeout: float = 60,
    ) -> subprocess.CompletedProcess[str]:
        merged = dict(self.env)
        if env:
            merged.update(env)
        return subprocess.run(
            argv,
            cwd=self.root,
            env=merged,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
            check=False,
        )


class Case(unittest.TestCase):
    """A case whose checks are counted and whose failure says both values."""

    def setUp(self) -> None:
        global _TOTAL
        self.sandbox = Sandbox()
        self.addCleanup(self.sandbox.close)
        self.checks = 0

    def same(self, got, want, claim: str) -> None:
        global _TOTAL
        self.checks += 1
        _TOTAL += 1
        if got != want:
            self.fail(f"{claim}: got {got!r}, wanted {want!r}")

    def true(self, value, claim: str) -> None:
        self.same(bool(value), True, claim)

    def false(self, value, claim: str) -> None:
        self.same(bool(value), False, claim)

    def contains(self, text: str, needle: str, claim: str) -> None:
        self.same(needle in text, True, f"{claim} (in {text!r})")

    def matches(self, text: str, pattern: str, claim: str) -> None:
        global _TOTAL
        self.checks += 1
        _TOTAL += 1
        if re.search(pattern, text, re.MULTILINE) is None:
            self.fail(f"{claim}: no line in {text!r} matches {pattern!r}")

    def text(self, path: Path) -> str:
        return path.read_text(encoding="utf-8")

    def bytes(self, path: Path) -> bytes:
        return path.read_bytes()

    def png(self) -> bytes:
        return base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4"
            "nGNgYGBgAAAABQABDQottAAAAABJRU5ErkJggg=="
        )
