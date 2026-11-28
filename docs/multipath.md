# Wi-Fi + USB multipath: no-go

**Decision:** do not add multipath aggregation now. The phone kernel has no
MPTCP, the proxy is a userspace SOCKS relay that terminates the TCP flow, and
both candidate paths share the phone's cellular uplink. A single SOCKS flow
cannot be striped across them without concurrent app and kernel work, and no
such change has a measured win.

This records the answer to issue #9.

## What was checked, 2026-11-28

### Laptop

```text
$ sysctl net.mptcp.enabled
net.mptcp.enabled = 1

$ cat /proc/sys/net/mptcp/enabled
1

$ ip mptcp limits
add_addr_accepted 0 subflows 2

$ curl --version
curl 8.23.0 (x86_64-pc-linux-gnu) libcurl/8.23.0 ...
Protocols: ... http https ...
Features: ... HTTP2 HTTP3 ... threadsafe ...

$ curl --help all | grep mptcp
     --mptcp                       Enable Multipath TCP
```

`mptcpize` is not installed. The laptop can therefore originate MPTCP with
curl, and the kernel allows two subflows, but that only helps if the peer also
speaks MPTCP.

### Phone

The phone was reachable over wireless adb as `192.168.77.232:5555`:

```text
$ adb -s 192.168.77.232:5555 shell uname -r
6.1.99-android14-11-g3bee8051ec68-ab12513326

$ adb -s 192.168.77.232:5555 shell 'cat /proc/sys/net/mptcp/enabled'
cat: /proc/sys/net/mptcp/enabled: No such file or directory

$ adb -s 192.168.77.232:5555 shell 'ls /proc/net/mptcp'
ls: /proc/net/mptcp: No such file or directory
```

The phone kernel has no MPTCP stack exposed at all. That is the blocker: the
laptop side alone cannot aggregate anything.

## Why the proxy cannot paper over it

The app is a Java SOCKS5 relay, not a kernel path:

- [`Socks5Server`](../src/org/phonelink/proxy/Socks5Server.java) accepts a TCP
  connection, reads the SOCKS request, and relays bytes in userspace.
- [`Egress`](../src/org/phonelink/proxy/Egress.java) opens the upstream socket
  on a cellular `Network` and is the only egress for the shared listener.
- [`Port`](../src/org/phonelink/proxy/Port.java) binds one wildcard listener on
  `0.0.0.0`, so the same port 1081 is reachable on `wlan0` and `rndis0`, but a
  client still gets one TCP connection and one four-tuple.

A single SOCKS connection is a byte stream. To aggregate it, both ends need
either MPTCP sockets or a userspace multipath scheduler that understands where
to split and reorder the stream. The Android kernel provides neither today, and
the app does not expose a second listener or a multipath protocol. Adding that
would be a new app feature, not a configuration change.

## Why the second path does not add internet capacity

Wi-Fi and USB are both laptop-to-phone paths. The phone's egress is the same
cellular `NetworkRequest` for either one, and the phone's mobile uplink was
observed at 115/136 Mbps in issue #9. Two local paths into the phone do not
create a second uplink. At best they could add laptop↔phone local throughput,
and the measured end-to-end proxy ceiling is already roughly 39–181 Mbps over
the AP path and 200–225 Mbps over USB (the figure recorded in issue #12); there
is no measured gain from bonding them.

## What a future go would require

All of these, together:

1. An Android kernel with MPTCP enabled, and a way for the app's sockets to use
   it (a Java/Android API or an `mptcpize`-style wrapper).
2. A proxy that accepts MPTCP on both the listener and the upstream side, or a
   userspace multipath protocol with a scheduler and reorder buffer.
3. A benchmark that shows one flow beating the best single path. Parallel
   connections are not aggregation; the proxy already has a connection ceiling
   and a selector refactor in flight ([#37](https://github.com/MBanucu/phone-link-proxy/issues/37)).

Until then the USB path remains a fallback transport, not a bonded one. The
existing `any_network` switch already opens the wildcard listener on USB; that
is the supported way to use the cable. See [architecture](architecture.md) for
the listener and egress split.

## Related decisions

- [Automatic channel and width](auto-channel.md) — the Wi-Fi link is not the
  end-to-end bottleneck.
- [Wi-Fi Direct](wifi-direct.md) — another transport, same egress.
- [App rejoin](rejoin.md) — why the app cannot pick the path back up silently.
