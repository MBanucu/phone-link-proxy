"""The phone-facing edge: adb, downloads, gestures and the panel.

This is the thin half of ``tests/live/phone.sh``. It knows how to run a
command on the phone, where a dump or a recording lands, and which broadcast
starts a capture; it makes no claims and decides no verdict. The parsing rules
live in :mod:`harness.uiautomator`, the estimate in :mod:`harness.eta`, and
the clock fit in :mod:`harness.clock` / :mod:`harness.clockfit`.

The shell's observable conventions are kept:

* ``ARTIFACTS`` is the run folder; ``input.csv`` gets ``start_ns,end_ns,kind,
  what`` rows, ``ui-index.csv`` gets one row per snapshot, and a directory
  named ``.dump`` in it is the lock shared with a snapshot loop.
* A capture service broadcast is preferred over ``input`` while its service
  is up. ``input`` cannot say when it landed, so its row carries the two
  ``/proc/uptime`` readings around it, in nanoseconds.
* ``ui_dump`` accepts only a hierarchy that holds the panel (or one of the
  app's own dialogs); anything else gets one ``am start`` and then one Back
  per call, not one per retry.
* A sequence is one broadcast and answers with ``result=0`` and, when the
  script read a field, ``data="..."``. The instruction that stopped it is kept
  for the failure note.
* Settings the run changed (accessibility service, autofill, Play Protect,
  screen timeout) are remembered and put back.

Differences are documented where they matter: XML is parsed rather than
grepped, CR is removed from every adb line (the shell did it per caller), and
a Python argument list does not need ``phone_arg``'s single-quote dance except
where the shell's broadcast contract printed it. A command whose output was
captured by ``$(...)`` has trailing newlines removed here too.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence, TextIO

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from harness import clock, clockfit, evidence, uiautomator
else:
    from . import clock, clockfit, evidence, uiautomator

REMOTE_DUMP = "/sdcard/proxy-app-ui.xml"
REMOTE_SHOT = "/sdcard/proxy-app-shot.xml"
REMOTE_RECORDING = "/sdcard/proxy-app.mp4"
TAP_SERVICE = "org.phonelink.proxy"


@dataclass(frozen=True)
class Result:
    """One finished adb command, with the bytes as well as the text."""

    argv: tuple[str, ...]
    code: int
    raw: bytes
    out: str
    err: str


def phone_arg(value: str) -> str:
    """The shell's one-argument quote: XML is full of punctuation a shell eats."""
    return "'" + str(value).replace("'", "'\\''") + "'"


class Phone:
    """One attached phone, its run folder and its settings."""

    def __init__(
        self,
        serial: str,
        artifacts: str | os.PathLike[str],
        *,
        pkg: str | None = None,
        res: str = TAP_SERVICE,
        service: str | None = None,
        activity: str | None = None,
        capture_pkg: str | None = None,
        capture_dir: str | None = None,
        capture_apk: str | os.PathLike[str] | None = None,
        devicetest_pkg: str | None = None,
        stable_pkg: str | None = None,
        env: dict[str, str] | None = None,
        sleep=time.sleep,
        now_ms=None,
        remux_script: str | os.PathLike[str] | None = None,
        clock_module=None,
    ) -> None:
        self.env = dict(os.environ if env is None else env)
        self.serial = serial
        self.artifacts = Path(artifacts)
        self.pkg = pkg or self.env.get("PHONE_PROXY_PKG", "org.phonelink.proxy.test")
        self.res = res
        self.service = service or f"{self.pkg}/org.phonelink.proxy.ProxyService"
        self.activity = activity or f"{self.pkg}/org.phonelink.proxy.MainActivity"
        self.capture_pkg = capture_pkg or self.env.get(
            "PROXY_CAPTURE_PKG", "org.phonelink.capture"
        )
        self.capture_dir = capture_dir or (
            f"/sdcard/Android/data/{self.capture_pkg}/files"
        )
        self.capture_apk = Path(capture_apk) if capture_apk else None
        self.devicetest_pkg = devicetest_pkg or self.env.get(
            "PROXY_DEVICETEST_PKG", "org.phonelink.proxytest"
        )
        self.stable_pkg = stable_pkg or self.env.get(
            "PHONE_STABLE_PKG", "org.phonelink.proxy"
        )
        self.sleep = sleep
        self.now_ms = now_ms or (lambda: int(time.time() * 1000))
        self.clock_module = clock_module or clock
        self.remux_script = Path(
            remux_script
            or Path(__file__).resolve().parents[2] / "tests/live/remux.sh"
        )

        self.capture_dumps = False
        self.capture_old_services = ""
        self.capture_old_enabled = ""
        self.sequence_stopped = ""
        self.sequence_value = ""
        self.screenrecord_pid = ""
        self.snapshot_thread: threading.Thread | None = None
        self.capturing = False
        self.remuxed = False
        self.remux_failed = False
        self.clock_summary = ""
        self.clock_fit = ""
        self.clock_failed = False
        self.screen_timeout: str | None = None
        self.unlocked = False
        self.autofill_service = ""
        self.verifier_setting = ""

        self.flash_edges = int(self.env.get("PHONE_LIVE_FLASH_EDGES", "8"))
        self.flash_period = int(self.env.get("PHONE_LIVE_FLASH_PERIOD", "200"))
        self.capture_enabled = self.env.get("PHONE_LIVE_CAPTURE", "1") != "0"
        self.remux_enabled = self.env.get("PHONE_LIVE_REMUX", "1") != "0"

    # -- adb -----------------------------------------------------------
    def _run(
        self,
        argv: Sequence[str],
        *,
        timeout: float | None = None,
    ) -> Result:
        command = tuple(str(part) for part in argv)
        try:
            done = subprocess.run(
                list(command),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                check=False,
                env=self.env,
            )
        except subprocess.TimeoutExpired as error:
            raw = error.output if isinstance(error.output, bytes) else b""
            text = raw.decode("utf-8", "replace")
            return Result(command, 124, raw, text, "timeout")
        except OSError as error:
            return Result(command, 127, b"", "", str(error))
        raw = done.stdout or b""
        out = raw.decode("utf-8", "replace")
        err = (done.stderr or b"").decode("utf-8", "replace")
        return Result(command, done.returncode, raw, out, err)

    def adb(self, *args, timeout: float | None = None) -> Result:
        return self._run(
            ["adb", "-s", self.serial, *args],
            timeout=timeout,
        )

    def phone(self, *args, timeout: float | None = None) -> Result:
        return self.adb(*args, timeout=timeout)

    def shell_result(self, *args, timeout: float | None = None) -> Result:
        # adb shell takes one remote command string. Joining here keeps the
        # shell's rule: a value that must survive the remote shell's word
        # splitting is quoted with phone_arg by the caller, exactly as
        # phone.sh did before it handed the argument to adb.
        command = " ".join(str(argument) for argument in args)
        return self._run(
            ["adb", "-s", self.serial, "shell", command],
            timeout=timeout,
        )

    def shell(self, *args, timeout: float | None = None) -> str:
        """Command substitution: CR removed, trailing newlines stripped."""
        result = self.shell_result(*args, timeout=timeout)
        return result.out.replace("\r", "").rstrip("\n")

    def shell_raw(self, *args, timeout: float | None = None) -> str:
        """A redirected command: CR removed, the trailing newline kept."""
        result = self.shell_result(*args, timeout=timeout)
        return result.out.replace("\r", "")

    # -- the phone's clock and the run's rows ---------------------------
    def device_ns(self) -> int | None:
        """The phone's monotonic clock in nanoseconds, or None."""
        seconds = self.shell("cut -d' ' -f1 /proc/uptime")
        if not re.fullmatch(r"[0-9.]+", seconds):
            return None
        return _ns_from_seconds(seconds)

    def input_action(self, *args) -> bool:
        """Run ``input`` with the two clock readings around it, and record it."""
        what = " ".join(str(argument) for argument in args)
        command = (
            "a=$(cut -d' ' -f1 /proc/uptime)\n"
            f"input {what}\n"
            "b=$(cut -d' ' -f1 /proc/uptime)\n"
            'echo "$a $b"'
        )
        readings = self.shell(command)
        fields = None
        for line in readings.splitlines():
            split = line.split()
            if len(split) == 2:
                fields = split
                break
        if fields is None:
            # The shell's `*) return 0`: `input` ran even if the readings did
            # not come back, and the caller's status is not a verdict on it.
            return True
        start_ns = _ns_from_seconds(fields[0])
        end_ns = _ns_from_seconds(fields[1])
        evidence.append_input(
            self.artifacts / evidence.INPUT, start_ns, end_ns, "input", what
        )
        return True

    # -- uiautomator and the capture service ---------------------------
    @contextmanager
    def dump_lock(self):
        """One dump at a time, shared with a snapshot loop through a directory."""
        lock = self.artifacts / ".dump"
        waited = 0
        while True:
            try:
                lock.mkdir()
                break
            except FileExistsError:
                pass
            except OSError:
                pass
            self.sleep(0.2)
            waited += 1
            if waited >= 60:
                # Left behind by something that died holding it.
                try:
                    lock.rmdir()
                except OSError:
                    pass
                try:
                    lock.mkdir()
                    break
                except OSError:
                    pass
        try:
            yield
        finally:
            try:
                lock.rmdir()
            except OSError:
                pass

    def ui_snapshots(self) -> None:
        """The uiautomator fallback: as many dumps as the phone will serve."""
        marker = self.artifacts / ".capturing"
        index = 0
        while marker.exists():
            index += 1
            with self.dump_lock():
                self.shell_result(
                    "timeout", "8", "uiautomator", "dump", REMOTE_SHOT,
                    timeout=20,
                )
                xml = self.shell_raw("cat", REMOTE_SHOT)
                (self.artifacts / f"ui-{index:05d}.xml").write_text(
                    xml, encoding="utf-8"
                )
                with (self.artifacts / "ui-index.csv").open(
                    "a", encoding="utf-8"
                ) as handle:
                    handle.write(f"{index},{self.now_ms()},poll\n")
            self.sleep(0.2)

    def capture_start(self) -> bool:
        """Install the capture app and point the settings at it, or say no."""
        if self.capture_dumps:
            return True
        if self.capture_apk is None or not self.capture_apk.is_file():
            return False
        if self.env.get("PHONE_LIVE_DUMPS", "capture") == "uiautomator":
            return False
        if self.phone("install", "-r", "-g", str(self.capture_apk)).code != 0:
            return False
        self.capture_old_services = self.shell(
            "settings", "get", "secure", "enabled_accessibility_services"
        )
        self.capture_old_enabled = self.shell(
            "settings", "get", "secure", "accessibility_enabled"
        )
        services = f"{self.capture_pkg}/.DumpService"
        if self.capture_old_services not in ("", "null"):
            services = f"{self.capture_old_services}:{services}"
        self.shell(
            "settings", "put", "secure", "enabled_accessibility_services",
            services,
        )
        self.shell("settings", "put", "secure", "accessibility_enabled", "1")
        self.shell("rm", "-rf", self.capture_dir)
        for _ in range(15):
            self.sleep(2)
            if "<hierarchy" in self.capture_dump():
                self.capture_dumps = True
                return True
        self.capture_stop()
        return False

    def capture_stop(self) -> None:
        self.capture_watch(False)
        if self.capture_old_services or self.capture_old_enabled:
            self.shell(
                "settings", "put", "secure", "enabled_accessibility_services",
                self.capture_old_services,
            )
            self.shell(
                "settings", "put", "secure", "accessibility_enabled",
                self.capture_old_enabled,
            )
            self.capture_old_services = ""
            self.capture_old_enabled = ""
        self.capture_dumps = False

    def capture_dump(self) -> str:
        """One capture through the service; the file exists before it returns."""
        self.shell(
            "am", "broadcast", "-a", f"{self.capture_pkg}.DUMP",
            "-n", f"{self.capture_pkg}/.DumpReceiver",
        )
        return self.shell_raw("cat", f"{self.capture_dir}/latest.xml")

    def capture_watch(self, enabled: bool) -> None:
        self.shell(
            "am", "broadcast", "-a", f"{self.capture_pkg}.WATCH",
            "--ez", "watch", "true" if enabled else "false",
            "-n", f"{self.capture_pkg}/.DumpReceiver",
        )

    def flash_markers(self) -> None:
        self.shell(
            "am", "broadcast", "-a", f"{self.capture_pkg}.FLASH",
            "--ei", "edges", str(self.flash_edges),
            "--ei", "period", str(self.flash_period),
            "-n", f"{self.capture_pkg}/.DumpReceiver",
        )

    # -- gestures and waits --------------------------------------------
    def tap(self, x: int, y: int) -> bool:
        if self.capture_dumps and self.shell_result(
            "am", "broadcast", "-a", f"{self.capture_pkg}.TAP",
            "--ei", "x", str(x), "--ei", "y", str(y),
            "-n", f"{self.capture_pkg}/.DumpReceiver",
        ).code == 0:
            return True
        return self.input_action("tap", x, y)

    def swipe(
        self, x1: int, y1: int, x2: int, y2: int, milliseconds: int
    ) -> bool:
        if self.capture_dumps and self.shell_result(
            "am", "broadcast", "-a", f"{self.capture_pkg}.SWIPE",
            "--ei", "x1", str(x1), "--ei", "y1", str(y1),
            "--ei", "x2", str(x2), "--ei", "y2", str(y2),
            "--ei", "ms", str(milliseconds),
            "-n", f"{self.capture_pkg}/.DumpReceiver",
        ).code == 0:
            return True
        return self.input_action(
            "swipe", x1, y1, x2, y2, milliseconds
        )

    def back(self) -> bool:
        if self.capture_dumps and self.shell_result(
            "am", "broadcast", "-a", f"{self.capture_pkg}.BACK",
            "-n", f"{self.capture_pkg}/.DumpReceiver",
        ).code == 0:
            return True
        return self.input_action("keyevent", 4)

    def home(self) -> bool:
        if self.capture_dumps and self.shell_result(
            "am", "broadcast", "-a", f"{self.capture_pkg}.HOME",
            "-n", f"{self.capture_pkg}/.DumpReceiver",
        ).code == 0:
            return True
        return self.input_action("keyevent", "KEYCODE_HOME")

    def wait_phone(self, *args) -> bool:
        if not self.capture_dumps:
            return False
        output = self.shell(
            "am", "broadcast", "-a", f"{self.capture_pkg}.WAIT", *args,
            "-n", f"{self.capture_pkg}/.DumpReceiver",
        )
        return "result=0" in output

    def wait_for(self, needle: str, milliseconds: int = 3000) -> bool:
        return self.wait_phone(
            "--es", "has", phone_arg(needle),
            "--ei", "timeout", str(milliseconds),
        )

    def wait_gone(self, needle: str, milliseconds: int = 3000) -> bool:
        return self.wait_phone(
            "--es", "has", phone_arg(needle), "--ez", "gone", "true",
            "--ei", "timeout", str(milliseconds),
        )

    def wait_change(self, milliseconds: int = 2000) -> bool:
        return self.wait_phone(
            "--ez", "change", "true", "--ei", "timeout", str(milliseconds)
        )

    def settle(self, quiet_ms: int = 250) -> bool:
        if not self.capture_dumps:
            self.sleep(1)
            return True
        return self.wait_phone(
            "--ei", "quiet", str(quiet_ms), "--ei", "timeout", "2500"
        )

    def watch_dump(self, pattern: str, milliseconds: int = 5000) -> bool:
        waited = 0
        while True:
            text = self.shell("dumpsys", "activity", "service", self.service)
            try:
                if re.search(pattern, text):
                    return True
            except re.error:
                return False
            if waited >= milliseconds:
                return False
            self.sleep(0.2)
            waited += 200

    def sequence(self, script: str) -> bool:
        """One straight line on the phone; the value or the stopping point."""
        if not self.capture_dumps:
            return False
        self.sequence_value = ""
        self.sequence_stopped = ""
        output = self.shell(
            "am", "broadcast", "-a", f"{self.capture_pkg}.SEQUENCE",
            "--es", "script", phone_arg(script),
            "-n", f"{self.capture_pkg}/.DumpReceiver",
        )
        values = _data_values(output)
        if "result=0" in output:
            self.sequence_value = values
            return True
        self.sequence_stopped = values
        return False

    def open_panel(self) -> bool:
        self.shell("am", "start", "-W", "-n", self.activity)
        return self.settle()

    # -- the panel ------------------------------------------------------
    def ui_dump(self) -> str | None:
        """The panel's XML, or None after eight attempts."""
        recovered = 0
        for _ in range(8):
            if self.capture_dumps:
                xml = self.capture_dump()
            else:
                with self.dump_lock():
                    self.shell_result(
                        "timeout", "8", "uiautomator", "dump", REMOTE_DUMP,
                        timeout=20,
                    )
                    xml = self.shell_raw("cat", REMOTE_DUMP)
            if uiautomator.looks_like_panel(xml, self.res):
                return xml
            if not uiautomator.has_package(xml, self.pkg):
                if recovered == 0:
                    self.shell("am", "start", "-W", "-n", self.activity)
                    recovered = 1
                elif recovered == 1:
                    self.back()
                    recovered = 2
            self.wait_change(700)
        return None

    def ui_screen(self) -> uiautomator.Screen | None:
        xml = self.ui_dump()
        if xml is None:
            return None
        try:
            return uiautomator.Screen.parse(xml)
        except ET.ParseError:
            return None

    def ui_text(self, resource_id: str) -> str:
        screen = self.ui_screen()
        return screen.text(resource_id) if screen is not None else ""

    def ui_bounds(self, resource_id: str) -> tuple[int, int] | None:
        screen = self.ui_screen()
        return screen.bounds(resource_id) if screen is not None else None

    def card_line(self, name: str, resource_id: str) -> uiautomator.Node | None:
        screen = self.ui_screen()
        return screen.card_line(name, resource_id) if screen is not None else None

    def card_state(self, name: str) -> str:
        node = self.card_line(name, "card_state")
        return node.text if node is not None else ""

    def card_button_text(self, name: str) -> str:
        node = self.card_line(name, "card_toggle")
        return node.text if node is not None else ""

    def panel_home(self) -> bool:
        for _ in range(3):
            xml = self.ui_dump() or ""
            if 'id/new_profile"' in xml or 'id/home_scroll"' in xml:
                return True
            if "id/header_title" in xml:
                self.back()
            else:
                self.shell("am", "start", "-W", "-n", self.activity)
            self.settle()
        return False

    def panel_open(self, name: str) -> bool:
        if not self.panel_home():
            return False
        return self.sequence(
            f'tap-scroll "id=card_name text={name}" 12000; wait id=back 4000'
        )

    def panel_tap(self, resource_id: str) -> bool:
        if self.sequence(
            f"tap-scroll id={resource_id} 12000; wait-quiet 250"
        ):
            return True
        self.settle()
        return self.sequence(
            f"scroll-top; tap-scroll id={resource_id} 12000; wait-quiet 250"
        )

    def panel_top(self) -> bool:
        return self.sequence("scroll-top")

    def panel_toggle(self) -> bool:
        if not self.panel_tap("toggle"):
            return False
        self.dismiss_battery()
        return True

    def dismiss_battery(self) -> bool:
        self.sequence(
            'wait contains="Let the proxy restart by itself" 700; '
            "tap-id button2 1000; wait-gone id=button2 2000"
        )
        return True

    def menu_pick(self, text: str) -> bool:
        return self.sequence(
            f'tap-id menu 3000; tap-text "{text}" 3000; wait-quiet 250'
        )

    def card_toggle(self, name: str) -> bool:
        node = self.card_line(name, "card_toggle")
        if node is None:
            return False
        point = node.centre()
        if point is None:
            return False
        self.tap(*point)
        self.settle()
        self.dismiss_battery()
        return True

    def ui_tap_id(self, resource_id: str) -> bool:
        for _ in range(10):
            point = self.ui_bounds(resource_id)
            if point is not None:
                self.tap(*point)
                self.settle()
                return True
            self.wait_change(500)
        return False

    def ui_text_seek(self, resource_id: str) -> str | None:
        if not self.sequence(
            f"scroll-to id={resource_id} 8000; read id={resource_id} 2000"
        ):
            return None
        if not self.sequence_value:
            return None
        return self.sequence_value

    def panel_where(self, out: TextIO | None = None) -> list[str]:
        stream = out or sys.stdout
        focus = ""
        for line in self.shell("dumpsys", "window").splitlines():
            if "mCurrentFocus" in line:
                focus = line.lstrip(" ")
                break
        records = sum(
            1
            for line in self.shell("dumpsys", "activity", "activities").splitlines()
            if self.activity in line
        )
        xml = self.ui_dump() or ""
        lines = [
            f"  focus: {focus}",
            f"  activity records: {records}",
            f"  app nodes: {uiautomator.count_resource_ids(xml, self.res)}",
        ]
        if self.sequence_stopped:
            lines.append(f"  last sequence stopped at: {self.sequence_stopped}")
        for line in lines:
            print(line, file=stream)
        return lines

    # -- profiles and settings -----------------------------------------
    def save_profile(self, name: str) -> None:
        self.shell(
            "am", "start-foreground-service", "-n", self.service,
            "-a", "org.phonelink.proxy.action.SAVE_PROFILE",
            "--es", "profile", name,
        )
        self.sleep(2)

    def delete_profile_by_adb(self, name: str) -> None:
        self.shell(
            "am", "start-foreground-service", "-n", self.service,
            "-a", "org.phonelink.proxy.action.DELETE_PROFILE",
            "--es", "profile", name,
        )
        self.sleep(2)

    def keyguard_showing(self) -> bool:
        return "showing=true" in self.shell("dumpsys", "window", "policy")

    def quiet_autofill(self) -> None:
        self.autofill_service = self.shell(
            "settings", "get", "secure", "autofill_service"
        )
        self.shell("settings", "put", "secure", "autofill_service", "null")

    def restore_autofill(self) -> None:
        if self.autofill_service in ("", "null") or "://" in self.autofill_service:
            return
        self.shell(
            "settings", "put", "secure", "autofill_service",
            self.autofill_service,
        )

    def quiet_play_protect(self) -> None:
        self.verifier_setting = self.shell(
            "settings", "get", "global", "verifier_verify_adb_installs"
        )
        self.shell(
            "settings", "put", "global", "verifier_verify_adb_installs", "0"
        )

    def restore_play_protect(self) -> None:
        if self.verifier_setting in ("", "null"):
            return
        self.shell(
            "settings", "put", "global", "verifier_verify_adb_installs",
            self.verifier_setting,
        )

    def unlock_phone(self) -> bool:
        if self.screen_timeout is None:
            value = self.shell(
                "settings", "get", "system", "screen_off_timeout"
            )
            if value in ("", "null") or not value.isdigit():
                value = "60000"
            self.screen_timeout = value
            self.shell(
                "settings", "put", "system", "screen_off_timeout", "600000"
            )
        if not self.keyguard_showing():
            return True
        unlock = self.env.get("UNLOCK", "")
        if not unlock or not os.access(unlock, os.X_OK):
            return False
        environment = dict(self.env)
        environment["PHONE_SERIAL"] = self.serial
        try:
            done = subprocess.run(
                ["sudo", "-n", "-E", unlock],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                env=environment,
                check=False,
            )
        except OSError:
            return False
        if done.returncode != 0:
            return False
        if self.keyguard_showing():
            return False
        self.unlocked = True
        return True

    # -- package and activity facts ------------------------------------
    def package_version(self) -> str | None:
        text = self.shell("dumpsys", "package", self.pkg)
        for line in text.splitlines():
            match = re.match(r"^.*versionName=(.*)$", line)
            if match is not None:
                return match.group(1)
        return None

    def activity_records(self) -> int:
        return sum(
            1
            for line in self.shell("dumpsys", "activity", "activities").splitlines()
            if self.activity in line
        )

    def current_focus(self) -> str:
        for line in self.shell("dumpsys", "window").splitlines():
            if "mCurrentFocus" in line:
                return line.lstrip(" ")
        return ""

    def service_foreground(self) -> bool:
        return "isForeground=true" in self.shell(
            "dumpsys", "activity", "services", self.pkg
        )

    def activity_up(self) -> bool:
        return self.activity in self.shell(
            "dumpsys", "activity", "activities"
        )

    def wifi_link(self) -> tuple[str, str]:
        """``(address, ssid)`` as ``proxy-app.sh`` reads them before a run."""
        address = ""
        for line in self.shell("ip", "-4", "addr", "show", "wlan0").splitlines():
            if "inet " in line:
                fields = line.split()
                try:
                    address = fields[fields.index("inet") + 1].split("/")[0]
                except (ValueError, IndexError):
                    address = ""
                break
        ssid = ""
        for line in self.shell("cmd", "wifi", "status").splitlines():
            match = re.match(r'^Wifi is connected to "(.*)"$', line)
            if match is not None:
                ssid = match.group(1)
                break
        return address, ssid

    def screencap(self, path: str | os.PathLike[str]) -> bool:
        """One PNG from the current screen, without the panel's XML."""
        result = self._run(
            ["adb", "-s", self.serial, "exec-out", "screencap", "-p"],
            timeout=30,
        )
        if result.code != 0 or not result.raw:
            return False
        Path(path).write_bytes(result.raw)
        return True

    # -- the recording and its clock -----------------------------------
    def start_capture(self, out: TextIO | None = None) -> bool:
        if not self.capture_enabled:
            return False
        self.shell("rm", "-f", REMOTE_RECORDING)
        pid = self.shell(
            "screenrecord --bit-rate 4000000 --time-limit 1800 "
            f"{REMOTE_RECORDING} >/dev/null 2>&1 & echo $!"
        )
        self.screenrecord_pid = re.sub(r"[\r\n ]", "", pid)
        if self.capture_dumps:
            self.capture_watch(True)
            # screenrecord only encodes a screen that changes; the shade gives
            # it a first frame and the phone paints its clock into it.
            self.shell("cmd", "statusbar", "expand-notifications")
            self.settle(500)
            self.shell("cmd", "statusbar", "collapse")
            self.settle(500)
            self.flash_markers()
        else:
            (self.artifacts / ".capturing").write_text("", encoding="utf-8")
            self.snapshot_thread = threading.Thread(
                target=self.ui_snapshots, daemon=True
            )
            self.snapshot_thread.start()
        self.capturing = True
        print(
            f"capture: screen.mp4 and ui-*.xml in {self.artifacts}",
            file=out or sys.stdout,
        )
        return True

    def stop_capture(self) -> None:
        if not self.capturing:
            return
        self.capturing = False
        if self.screenrecord_pid and self.capture_dumps:
            self.flash_markers()
            pause = float(
                format(
                    self.flash_edges * self.flash_period / 1000.0 + 0.6,
                    ".1f",
                )
            )
            self.sleep(pause)
        if self.capture_dumps:
            self.capture_watch(False)
            self.phone("pull", f"{self.capture_dir}/.", str(self.artifacts) + "/")
            self.shell("rm", "-rf", self.capture_dir)
            index = self.artifacts / "index.csv"
            if index.is_file():
                os.replace(index, self.artifacts / "ui-index.csv")
            latest = self.artifacts / "latest.xml"
            if latest.exists():
                latest.unlink()
        marker = self.artifacts / ".capturing"
        if marker.exists():
            marker.unlink()
        if self.snapshot_thread is not None:
            self.snapshot_thread.join()
            self.snapshot_thread = None
        if self.screenrecord_pid:
            self.shell("kill", "-INT", self.screenrecord_pid)
            self.sleep(2)
            self.phone(
                "pull", REMOTE_RECORDING, str(self.artifacts / "screen.mp4")
            )
            self.shell("rm", "-f", REMOTE_RECORDING)
            self.screenrecord_pid = ""
            recording = self.artifacts / "screen.mp4"
            if self.remux_enabled and recording.is_file():
                self._remux(recording)
        self.calibrate_clock()

    def _remux(self, recording: Path) -> None:
        log = (self.artifacts / "remux.txt").open("w", encoding="utf-8")
        try:
            done = subprocess.run(
                ["bash", str(self.remux_script), str(recording)],
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                env=self.env,
                check=False,
            )
        except OSError:
            self.remux_failed = True
            return
        finally:
            log.close()
        if done.returncode == 0:
            self.remuxed = True
        else:
            self.remux_failed = True

    def calibrate_clock(self, out: TextIO | None = None) -> clockfit.Fit | None:
        recording = self.artifacts / "screen.mp4"
        edges = self.artifacts / "clock.csv"
        if not recording.is_file() or not edges.is_file():
            return None
        try:
            fit = self.clock_module.fit_recording(self.artifacts, env=self.env)
        except clockfit.ClockError:
            self.clock_failed = True
            print(
                "clock: the flashes could not be fitted to the recording",
                file=out or sys.stdout,
            )
            return None
        self.clock_summary = clockfit.summary(fit).removeprefix("clock: ")
        self.clock_fit = format(fit.fit_ms, ".1f")
        print(f"clock: {self.clock_summary}", file=out or sys.stdout)
        print(
            "       so a device_ns in ui-index.csv, actions.csv or input.csv is",
            file=out or sys.stdout,
        )
        print(
            "       mapped by clock.txt; the flashes are in clock.csv",
            file=out or sys.stdout,
        )
        return fit


def _data_values(output: str) -> str:
    """The last ``data="..."`` on each line, the way the shell's sed read it."""
    values: list[str] = []
    for line in output.splitlines():
        found = re.findall(r'.*data="([^"]*)"', line)
        if found:
            values.append(found[-1])
    return "\n".join(values)


def _ns_from_seconds(text: str) -> int:
    """awk's ``printf "%.0f", t * 1e9``."""
    return int(format(_awk_number(text) * 1e9, ".0f"))


def _awk_number(text: str) -> float:
    match = re.match(
        r"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)", str(text).strip()
    )
    if match is None:
        return 0.0
    try:
        return float(match.group(0))
    except ValueError:
        return 0.0
