#!/usr/bin/env bash
# Hermetic tests for the proxy app's Android-free logic: the SOCKS5 protocol,
# the configuration rules and the state text. Plain JDK, no phone, no Android
# SDK and no network.
#
#   bash tests/proxy-app.test.sh                 # every suite
#   bash tests/proxy-app.test.sh --only udp      # one area
#   bash tests/proxy-app.test.sh --only 'socks5-*'  # a glob, or a,b for two
#
# The suites live one per area under test/org/phonelink/proxy/ and share
# TestSupport; AllTests names them and runs the selected ones.
#
# These classes are compiled without android.jar, which is only possible because
# they carry no Android types: the logging and the upstream connection are
# seams, not framework calls.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}
for tool in "$JAVAC" "$JAVA"; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'missing %s: run this in nix develop .#android, or install a JDK\n' "$tool" >&2
    exit 2
  fi
done

usage() {
  printf 'usage: bash tests/proxy-app.test.sh [--only <suite>[,<suite>...]]\n' >&2
  printf '  a suite is an id or alias; * and ? are globs\n' >&2
  exit 2
}

only=()
while [ $# -gt 0 ]; do
  case $1 in
    --only)
      [ $# -ge 2 ] || usage
      only=(--only "$2")
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

sources=(
  "$ROOT/src/org/phonelink/proxy/Upstream.java"
  "$ROOT/src/org/phonelink/proxy/Actions.java"
  "$ROOT/src/org/phonelink/proxy/BootGate.java"
  "$ROOT/src/org/phonelink/proxy/Closeables.java"
  "$ROOT/src/org/phonelink/proxy/EgressPolicy.java"
  "$ROOT/src/org/phonelink/proxy/EgressState.java"
  "$ROOT/src/org/phonelink/proxy/EventLog.java"
  "$ROOT/src/org/phonelink/proxy/Help.java"
  "$ROOT/src/org/phonelink/proxy/ProxyConfig.java"
  "$ROOT/src/org/phonelink/proxy/ProxyState.java"
  "$ROOT/src/org/phonelink/proxy/ProxyRuntime.java"
  "$ROOT/src/org/phonelink/proxy/TileCycle.java"
  "$ROOT/src/org/phonelink/proxy/TilePlan.java"
  "$ROOT/src/org/phonelink/proxy/TileScope.java"
  "$ROOT/src/org/phonelink/proxy/WifiLockPolicy.java"
  "$ROOT/src/org/phonelink/proxy/Socks5Server.java"
  "$ROOT/src/org/phonelink/proxy/Handshake.java"
  "$ROOT/src/org/phonelink/proxy/Socks5Request.java"
  "$ROOT/src/org/phonelink/proxy/TcpRelay.java"
  "$ROOT/src/org/phonelink/proxy/UdpAssociation.java"
  "$ROOT/src/org/phonelink/proxy/UdpPacket.java"
  "$ROOT/src/org/phonelink/proxy/Traffic.java"
  "$ROOT/src/org/phonelink/proxy/ProfileDocument.java"
  "$ROOT/src/org/phonelink/proxy/ProfileTransfer.java"
  "$ROOT/src/org/phonelink/proxy/SavedIndex.java"
  "$ROOT/src/org/phonelink/proxy/TransferGate.java"
  "$ROOT/src/org/phonelink/proxy/UplinkPolicy.java"
)

# Every suite is compiled, including the ones --only does not run, so a broken
# file is a compile error and not a silent skip.
mapfile -t tests < <(find "$ROOT/test" -name '*.java' -print | sort)

# The compile is a part of this suite's time worth naming: it is one javac
# over the Android-free sources, and when the whole check gets slower it
# matters whether that or the cases did. PHONE_TEST_TIMINGS=<file> gets
# `part,ms` rows the flake's script check folds into its timings.csv.
started=$(date +%s%N)
if ! "$JAVAC" -encoding UTF-8 -Xlint:all -Werror -d "$TMP/classes" \
    "${sources[@]}" "${tests[@]}"; then
  printf '\n0 passed, 1 failed\n'
  exit 1
fi
compiled=$(date +%s%N)
part_timing() { # part_timing PART START END
  local ms=$(((($3 - $2)) / 1000000))
  printf '  %-6s %6s ms\n' "$1" "$ms"
  if [ -n "${PHONE_TEST_TIMINGS:-}" ]; then
    printf '%s,%s\n' "$1" "$ms" >>"$PHONE_TEST_TIMINGS"
  fi
}
part_timing javac "$started" "$compiled"

if [ ${#only[@]} -gt 0 ]; then
  "$JAVA" -Dphone.repo="$ROOT" -cp "$TMP/classes" org.phonelink.proxy.AllTests "${only[@]}"
else
  "$JAVA" -Dphone.repo="$ROOT" -cp "$TMP/classes" org.phonelink.proxy.AllTests
fi
status=$?
part_timing cases "$compiled" "$(date +%s%N)"

# The panel is Android code and not part of the JVM suite, but the two lines
# and the words that keep them apart are files: a missing one is a red mark
# that silently means two gates again.
ui=0
ui_assert() { # ui_assert FILE PATTERN WHAT
  if ! grep -q -- "$2" "$1"; then
    printf 'FAIL %s\n' "$3"
    ui=1
  fi
}
strings=$ROOT/res/values/strings.xml
layout=$ROOT/res/layout/activity_main.xml
ui_assert "$strings" 'name="oem_autostart_unreadable"' \
  "no separate unreadable OEM autostart line"
ui_assert "$strings" 'name="oem_autostart_blocked"' \
  "no likely-blocked OEM autostart line"
ui_assert "$strings" 'com.freeme.freemanager.permission.ACCESS_INTERFACE' \
  "the OEM line does not name the protected list's permission"
ui_assert "$strings" 'wireless-adb-keeper/autostart-allow.sh' \
  "the OEM line does not name the adb driver"
ui_assert "$strings" 'phone-link-tools/autotoggle' \
  "the OEM line does not name the accessibility driver"
ui_assert "$strings" 'the DokeOS Auto Start Management list is separate' \
  "the battery line no longer says it is only the battery gate"
ui_assert "$strings" 'The battery gate blocks the restart after a reboot' \
  "the header mark no longer says it is the battery gate"
ui_assert "$layout" 'android:id="@+id/oem_autostart"' \
  "the panel layout has no OEM autostart line"
[ -n "$status" ] || status=1
if [ "$status" -ne 0 ]; then
  exit "$status"
fi
exit "$ui"
