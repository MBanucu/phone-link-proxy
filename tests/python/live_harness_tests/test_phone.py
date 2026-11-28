"""The adb edge: dumps, gestures, captures, settings and the panel facts."""

from __future__ import annotations

import unittest
from pathlib import Path

from . import fixtures, support
from harness import phone as phone_module


class PhoneTest(support.Case):
    def setUp(self) -> None:
        super().setUp()
        self.capture_apk = self.sandbox.write("capture.apk", "")
        self.phone = phone_module.Phone(
            "SERIAL",
            self.sandbox.artifacts,
            capture_apk=self.capture_apk,
            env=dict(self.sandbox.env),
            sleep=lambda _seconds: None,
            now_ms=lambda: 123456,
        )

    def commands(self) -> list[str]:
        return self.sandbox.adb_shell_commands()

    def test_device_ns_and_its_invalid_counterparts(self) -> None:
        self.sandbox.answer_adb(
            [
                {
                    "match": "cut -d' ' -f1 /proc/uptime",
                    "out": "1234.5\n",
                }
            ]
        )
        self.same(
            self.phone.device_ns(),
            1234500000000,
            "the monotonic clock is nanoseconds",
        )
        self.sandbox.answer_adb(
            [{"match": "cut -d' ' -f1 /proc/uptime", "out": "not-a-number\n"}]
        )
        self.same(self.phone.device_ns(), None, "a bad reading is no reading")
        self.sandbox.answer_adb(
            [{"match": "cut -d' ' -f1 /proc/uptime", "out": "1.2.3\n"}]
        )
        self.same(
            self.phone.device_ns(),
            1200000000,
            "awk's numeric prefix is what the shell read",
        )

    def test_input_action_records_the_interval(self) -> None:
        self.sandbox.answer_adb(
            [{"match": "input tap 10 20", "out": "100.0 101.5\n"}]
        )
        self.true(self.phone.input_action("tap", 10, 20), "input ran")
        self.same(
            self.text(self.sandbox.artifacts / "input.csv"),
            "start_ns,end_ns,kind,what\n"
            "100000000000,101500000000,input,tap 10 20\n",
            "the row carries the readings and what was run",
        )
        self.same(
            len([c for c in self.commands() if "input tap 10 20" in c]),
            1,
            "one shell command runs input",
        )

    def test_tap_prefers_the_capture_service(self) -> None:
        self.phone.capture_dumps = True
        self.sandbox.answer_adb(
            [
                {
                    "match": "org.phonelink.capture.TAP",
                    "out": "Broadcast completed: result=0\n",
                }
            ]
        )
        self.true(self.phone.tap(10, 20), "the service took the tap")
        self.same(
            len([c for c in self.commands() if "input tap" in c]),
            0,
            "no input fallback when the service answered",
        )
        self.same(
            len([c for c in self.commands() if "org.phonelink.capture.TAP" in c]),
            1,
            "one TAP broadcast",
        )
        self.contains(self.commands()[0], "--ei x 10 --ei y 20", "coordinates")

    def test_tap_falls_back_to_input_when_the_service_fails(self) -> None:
        self.phone.capture_dumps = True
        self.sandbox.answer_adb(
            [
                {
                    "match": "org.phonelink.capture.TAP",
                    "out": "",
                    "code": 1,
                },
                {
                    "match": "input tap 10 20",
                    "out": "100.0 101.0\n",
                },
            ]
        )
        self.true(self.phone.tap(10, 20), "the fallback still taps")
        self.contains(
            self.text(self.sandbox.artifacts / "input.csv"),
            "input,tap 10 20",
            "and records that it could not say exactly when",
        )

    def test_sequence_value_and_stopping_point(self) -> None:
        self.phone.capture_dumps = True
        self.sandbox.answer_adb(
            [
                {
                    "match": "org.phonelink.capture.SEQUENCE",
                    "responses": [
                        {"out": 'Broadcast completed: result=0, data="hello"\n'},
                        {"out": 'Broadcast completed: result=-1, data="tap-id x"\n'},
                    ],
                }
            ]
        )
        self.true(self.phone.sequence("read id=name"), "a good sequence")
        self.same(self.phone.sequence_value, "hello", "the field it read")
        self.false(self.phone.sequence("tap-id x"), "a stopped sequence")
        self.same(
            self.phone.sequence_stopped,
            "tap-id x",
            "the instruction that stopped it",
        )
        self.contains(
            self.commands()[0],
            "--es script 'read id=name'",
            "the script is one quoted argument for the remote shell",
        )

    def test_wait_for_quotes_the_needle(self) -> None:
        self.phone.capture_dumps = True
        self.sandbox.answer_adb(
            [{"match": "org.phonelink.capture.WAIT", "out": "result=0\n"}]
        )
        self.true(self.phone.wait_for("<hierarchy>", 1500), "the wait answered")
        command = next(c for c in self.commands() if "WAIT" in c)
        self.contains(command, "--es has '<hierarchy>'", "the XML is quoted")
        self.contains(command, "--ei timeout 1500", "the timeout is the argument")

    def test_watch_dump_polls_until_the_service_says_it(self) -> None:
        self.sandbox.answer_adb(
            [
                {
                    "match": "dumpsys activity service",
                    "responses": [
                        {"out": "nothing yet\n"},
                        {"out": "wanted line\n"},
                    ],
                }
            ]
        )
        self.true(self.phone.watch_dump("wanted", 400), "the pattern appeared")
        self.same(
            len([c for c in self.commands() if "dumpsys activity service" in c]),
            2,
            "one call per look",
        )

    def test_settings_are_put_back(self) -> None:
        self.sandbox.answer_adb(
            [
                {
                    "match": "settings get secure autofill_service",
                    "out": "com.example/.Autofill\n",
                },
                {
                    "match": "settings get global verifier_verify_adb_installs",
                    "out": "1\n",
                },
                {
                    "match": "settings get system screen_off_timeout",
                    "out": "30000\n",
                },
                {
                    "match": "dumpsys window policy",
                    "out": "showing=false\n",
                },
            ]
        )
        self.phone.quiet_autofill()
        self.phone.quiet_play_protect()
        self.true(self.phone.unlock_phone(), "the phone is already awake")
        self.phone.restore_autofill()
        self.phone.restore_play_protect()
        commands = self.commands()
        self.contains(
            "\n".join(commands),
            "settings put secure autofill_service null",
            "autofill is quieted",
        )
        self.contains(
            "\n".join(commands),
            "settings put global verifier_verify_adb_installs 0",
            "Play Protect verification is quieted",
        )
        self.contains(
            "\n".join(commands),
            "settings put secure autofill_service com.example/.Autofill",
            "autofill goes back",
        )
        self.contains(
            "\n".join(commands),
            "settings put global verifier_verify_adb_installs 1",
            "the verifier setting goes back",
        )
        self.contains(
            "\n".join(commands),
            "settings put system screen_off_timeout 600000",
            "the run's timeout is ten minutes",
        )
        self.same(self.phone.screen_timeout, "30000", "the old timeout is remembered")

    def test_restore_skips_a_url_shaped_autofill(self) -> None:
        self.phone.autofill_service = "com.example://service"
        self.phone.restore_autofill()
        self.same(len(self.commands()), 0, "a URL-shaped value is not put back")
        self.phone.verifier_setting = ""
        self.phone.restore_play_protect()
        self.same(len(self.commands()), 0, "and neither is an empty verifier")

    def test_capture_start_succeeds_and_remembers_old_settings(self) -> None:
        self.sandbox.answer_adb(
            [
                {"match": "install -r -g", "out": "Success\n"},
                {
                    "match": "settings get secure enabled_accessibility_services",
                    "out": "old/service\n",
                },
                {
                    "match": "settings get secure accessibility_enabled",
                    "out": "1\n",
                },
                {
                    "match": "cat /sdcard/Android/data/org.phonelink.capture/files/latest.xml",
                    "out": "<hierarchy rotation=\"0\" />\n",
                },
            ]
        )
        self.true(self.phone.capture_start(), "the capture service answered")
        self.true(self.phone.capture_dumps, "and is now the dump source")
        self.same(
            self.phone.capture_old_services,
            "old/service",
            "the old service is remembered",
        )
        commands = "\n".join(self.commands())
        self.contains(
            commands,
            "settings put secure enabled_accessibility_services "
            "old/service:org.phonelink.capture/.DumpService",
            "the existing service is kept and ours is appended",
        )
        self.contains(
            commands,
            "settings put secure accessibility_enabled 1",
            "accessibility is turned on",
        )

    def test_capture_start_failure_stops_and_restores(self) -> None:
        self.sandbox.answer_adb(
            [
                {"match": "install -r -g", "out": "Success\n"},
                {
                    "match": "settings get secure enabled_accessibility_services",
                    "out": "old/service\n",
                },
                {
                    "match": "settings get secure accessibility_enabled",
                    "out": "1\n",
                },
                {
                    "match": "cat /sdcard/Android/data/org.phonelink.capture/files/latest.xml",
                    "out": "not a dump\n",
                },
            ]
        )
        self.false(self.phone.capture_start(), "15 quiet captures is a no")
        self.false(self.phone.capture_dumps, "the fallback is used")
        commands = "\n".join(self.commands())
        self.contains(
            commands,
            "settings put secure enabled_accessibility_services old/service",
            "the old service is put back",
        )
        self.contains(
            commands,
            "settings put secure accessibility_enabled 1",
            "and the enabled setting",
        )
        self.contains(commands, ".WATCH --ez watch false", "watch is turned off")

    def test_ui_dump_recovers_with_one_start(self) -> None:
        self.sandbox.answer_adb(
            [
                {
                    "match": "cat /sdcard/proxy-app-ui.xml",
                    "responses": [fixtures.BAD_XML, fixtures.PANEL_XML],
                },
                {"match": "am start -W -n", "out": "Starting: Intent\n"},
            ]
        )
        xml = self.phone.ui_dump()
        self.true(xml is not None, "the second dump is the panel")
        self.contains(xml or "", "id/new_profile", "and holds the list")
        self.same(
            len([c for c in self.commands() if "am start -W -n" in c]),
            1,
            "the app gets one chance before Back",
        )

    def test_ui_dump_gives_up_after_eight(self) -> None:
        self.sandbox.answer_adb(
            [{"match": "cat /sdcard/proxy-app-ui.xml", "out": fixtures.BAD_XML}]
        )
        self.same(self.phone.ui_dump(), None, "eight useless dumps is None")
        self.same(
            len([c for c in self.commands() if "cat /sdcard/proxy-app-ui.xml" in c]),
            8,
            "each attempt reads the file",
        )

    def test_ui_text_bounds_and_cards(self) -> None:
        self.sandbox.answer_adb(
            [{"match": "cat /sdcard/proxy-app-ui.xml", "out": fixtures.CARDS_XML}]
        )
        self.same(self.phone.ui_text("header_title"), "Proxies", "ui_text")
        self.same(self.phone.ui_bounds("card_toggle"), (920, 260), "ui_bounds")
        self.same(self.phone.card_state("beta"), "Stopped", "card state")
        self.same(self.phone.card_button_text("alpha"), "Stop", "card button")
        self.same(self.phone.card_state("missing"), "", "no card, no state")

    def test_panel_home_goes_back_from_a_page(self) -> None:
        self.phone.capture_dumps = True
        self.sandbox.answer_adb(
            [
                {
                    "match": "cat /sdcard/Android/data/org.phonelink.capture/files/latest.xml",
                    "responses": [
                        fixtures.HEADER_ONLY_XML,
                        fixtures.PANEL_XML,
                    ],
                },
                {
                    "match": "org.phonelink.capture.BACK",
                    "out": "result=0\n",
                },
            ]
        )
        self.true(self.phone.panel_home(), "the list is showing")
        self.contains(
            "\n".join(self.commands()),
            "org.phonelink.capture.BACK",
            "a page is left with one Back",
        )

    def test_package_and_activity_facts(self) -> None:
        self.sandbox.answer_adb(
            [
                {"match": "dumpsys package", "out": fixtures.DUMPSYS_PACKAGE},
                {
                    "match": "dumpsys window",
                    "out": "  mCurrentFocus=Window{abc u0 org.phonelink.proxy.test/.MainActivity}\n",
                },
                {
                    "match": "dumpsys activity activities",
                    "out": "org.phonelink.proxy.test/org.phonelink.proxy.MainActivity\n"
                    "org.phonelink.proxy.test/org.phonelink.proxy.MainActivity\n",
                },
                {
                    "match": "dumpsys activity services",
                    "out": "isForeground=true\n",
                },
                {"match": "ip -4 addr show wlan0", "out": fixtures.WIFI_IP},
                {"match": "cmd wifi status", "out": fixtures.WIFI_STATUS},
            ]
        )
        self.same(self.phone.package_version(), "0.17.0", "versionName")
        self.same(
            self.phone.current_focus(),
            "mCurrentFocus=Window{abc u0 org.phonelink.proxy.test/.MainActivity}",
            "the focus line is left-trimmed",
        )
        self.same(self.phone.activity_records(), 2, "activity records")
        self.true(self.phone.activity_up(), "the activity is up")
        self.true(self.phone.service_foreground(), "the service is foreground")
        self.same(
            self.phone.wifi_link(),
            ("192.168.77.232", "PhoneLink"),
            "address and SSID",
        )

    def test_screencap_returns_the_png_bytes(self) -> None:
        png = self.png()
        self.sandbox.answer_adb(
            [{"match": "exec-out screencap -p", "b64": self._b64(png)}]
        )
        target = self.sandbox.root / "shot.png"
        self.true(self.phone.screencap(target), "screencap wrote a file")
        self.same(self.bytes(target), png, "the bytes are untouched")

    def test_screencap_failure_leaves_no_file(self) -> None:
        self.sandbox.answer_adb(
            [{"match": "exec-out screencap -p", "out": "", "code": 1}]
        )
        target = self.sandbox.root / "shot.png"
        self.false(self.phone.screencap(target), "a failed screencap is false")
        self.false(target.exists(), "and writes nothing")

    def test_profile_broadcasts(self) -> None:
        self.sandbox.answer_adb([])
        self.phone.save_profile("harness-panel")
        self.phone.delete_profile_by_adb("harness-panel")
        commands = "\n".join(self.commands())
        self.contains(commands, "SAVE_PROFILE --es profile harness-panel", "save")
        self.contains(
            commands, "DELETE_PROFILE --es profile harness-panel", "delete"
        )

    def test_panel_where_says_what_is_showing(self) -> None:
        self.sandbox.answer_adb(
            [
                {
                    "match": "dumpsys window",
                    "out": "  mCurrentFocus=Window{abc u0 com.example/.Main}\n",
                },
                {
                    "match": "dumpsys activity activities",
                    "out": "org.phonelink.proxy.test/org.phonelink.proxy.MainActivity\n",
                },
                {
                    "match": "cat /sdcard/proxy-app-ui.xml",
                    "out": fixtures.PANEL_XML,
                },
            ]
        )
        import io

        stream = io.StringIO()
        lines = self.phone.panel_where(stream)
        self.same(len(lines), 3, "focus, records and nodes")
        self.contains(stream.getvalue(), "focus: mCurrentFocus=Window", "focus")
        self.contains(stream.getvalue(), "activity records: 1", "records")
        self.contains(stream.getvalue(), "app nodes: 3", "nodes")

    def _b64(self, data: bytes) -> str:
        import base64

        return base64.b64encode(data).decode("ascii")


if __name__ == "__main__":
    unittest.main()
