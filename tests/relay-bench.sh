#!/usr/bin/env bash
# Loopback throughput of the CONNECT relay, for before/after comparisons.
#
#   bash tests/relay-bench.sh                 # 256 MiB per scenario, 5 runs
#   bash tests/relay-bench.sh --mib=64 --repeats=3 --flows=1,4
#
# One scenario is a direction and a flow count: all clients connect first, a
# barrier releases them, and the clock covers only the bytes. The first small
# run warms the JVM. This is a loopback number, not the phone's cellular rate;
# it answers "did the selector relay regress the byte pump" and nothing else.
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

# The Android-free classes, the same set tests/proxy-app.test.sh compiles.
sources=(
  "$ROOT/src/org/phonelink/proxy/Upstream.java"
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
  "$ROOT/src/org/phonelink/proxy/ChannelRelay.java"
  "$ROOT/src/org/phonelink/proxy/UdpAssociation.java"
  "$ROOT/src/org/phonelink/proxy/UdpPacket.java"
  "$ROOT/src/org/phonelink/proxy/Traffic.java"
  "$ROOT/src/org/phonelink/proxy/ProfileDocument.java"
  "$ROOT/src/org/phonelink/proxy/ProfileTransfer.java"
  "$ROOT/src/org/phonelink/proxy/SavedIndex.java"
  "$ROOT/src/org/phonelink/proxy/TransferGate.java"
  "$ROOT/src/org/phonelink/proxy/UplinkPolicy.java"
)

if ! "$JAVAC" -encoding UTF-8 -Xlint:all -Werror -d "$TMP/classes" \
    "${sources[@]}" "$ROOT/test/org/phonelink/proxy/RelayBench.java"; then
  printf '\nrelay benchmark did not compile\n' >&2
  exit 1
fi

exec "$JAVA" -cp "$TMP/classes" org.phonelink.proxy.RelayBench "$@"
