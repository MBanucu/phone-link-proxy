# Architecture: start here

The app is one flat package, `org.phonelink.proxy`, built by `build.sh` with no
Gradle, AndroidX or third-party code. This page is the map: the layers, every
class, the lifecycle and the state, then the file to open for a task and the
check that proves it. Paths and names only, never line numbers.

The contract itself is not copied here. `Help` is what `dumpsys` prints and the
panel's **Info** shows; the [README](../README.md) has the commands; the
[boundary page](https://github.com/MBanucu/phone-link/blob/main/docs/proxy-boundary.md)
says which repository owns what. When this page and those disagree, they win.

## Layers

```text
MainActivity / ProxyScreen / HomeScreen / Help / TileSettingsDialog
        │  Intents and ConfigStore (same process, no binder)
        ▼
ProxyService  ──►  Ports  ──►  Port  ──►  Socks5Server
     │                          │              │
     │                          ▼              ▼
     │                       Uplink         Upstream
     │                    (SSID gate)          │
     │                                        ▼
     │                                      Egress
     │                                        │
     └── ProxyRuntime, EventLog, notification ▼
         wake/Wi-Fi locks, tile, boot    Android Network
                                         (cellular request)
```

- **Panel** — `MainActivity` draws the list through `HomeScreen` and one proxy
  through `ProxyScreen`; `Help` is the contract text.
- **Service** — `ProxyService` is the only Android `Service`, the adb entry
  point, the foreground notification and the owner of the shared egress, locks
  and transfers. `Ports` is its map of listeners.
- **Listener** — one `Port` owns one `Uplink` gate and one `Socks5Server`; it
  binds and pauses as the phone's Wi-Fi changes.
- **Protocol** — `Socks5Server` speaks SOCKS5 and asks `Upstream` for every
  socket. It has no Android types.
- **Egress** — `Egress` implements `Upstream` with a cellular
  `NetworkRequest`; `EgressPolicy` decides which link may carry a socket.
- **Android** — connectivity callbacks, Wi-Fi information, locks, keyguard,
  notification, Quick Settings, boot broadcast.

## Classes

`Owner` is whose lifecycle an instance follows: the Android component that
creates it, the object that closes it, or `none` for a static or immutable
helper. `Name fixed` is about renaming: `yes` marks a component declared in
`AndroidManifest.xml`, whose class name is addressed by adb, the launcher, the
tile or the boot broadcast. `layout` means a layout XML names the class; `no`
means the name is internal.

| File | Role | Owner | Name fixed |
|---|---|---|---|
| [`MainActivity.java`](../src/org/phonelink/proxy/MainActivity.java) | The panel: list, one proxy's page, menus, dialogs, Save/Start/Delete, export/import pickers. | Android (activity) | yes — activity |
| [`HomeScreen.java`](../src/org/phonelink/proxy/HomeScreen.java) | Draws the saved proxies as cards and reports which was opened or pressed. | `MainActivity` | no |
| [`ProxyScreen.java`](../src/org/phonelink/proxy/ProxyScreen.java) | Draws an open proxy's form, three segments, counters and log; returns the form to save. | `MainActivity` | no |
| [`TileSettingsDialog.java`](../src/org/phonelink/proxy/TileSettingsDialog.java) | The tile's own page: which proxies it starts, stops and cycles through. | `MainActivity` | no |
| [`Accent.java`](../src/org/phonelink/proxy/Accent.java) | Reads the theme's accent colour for the one action button. | none (static) | no |
| [`Help.java`](../src/org/phonelink/proxy/Help.java) | The adb contract text, printed by `dumpsys` and the Info dialog. | none (static, Android-free) | no |
| [`TrafficView.java`](../src/org/phonelink/proxy/TrafficView.java) | The download/upload sparkline on the Log segment. | view tree from `activity_main.xml` | layout |
| [`ProxyTile.java`](../src/org/phonelink/proxy/ProxyTile.java) | Quick Settings tile: computes a click with `TilePlan` and sends start/stop. | Android (tile service) | yes — tile service |
| [`ProxyService.java`](../src/org/phonelink/proxy/ProxyService.java) | Foreground service, adb actions, dump, shared egress, locks, notification, transfers. | Android (service) | yes — service |
| [`Ports.java`](../src/org/phonelink/proxy/Ports.java) | The listeners by port, in start order; start replaces a port, stop selects one or all. | `ProxyService` | no |
| [`Port.java`](../src/org/phonelink/proxy/Port.java) | One listener: config, traffic, clients, state, its `Uplink` and `Socks5Server`. | `Ports` | no |
| [`ConfigStore.java`](../src/org/phonelink/proxy/ConfigStore.java) | The store: preferences, profiles, adb extras, tile settings, boot facts, `state.txt`, export/import. | app (static) | no |
| [`ProxyRuntime.java`](../src/org/phonelink/proxy/ProxyRuntime.java) | The running listeners shared with the panel and tile; a static list, no binder. | app (static) | no |
| [`EventLog.java`](../src/org/phonelink/proxy/EventLog.java) | The last 200 events in memory, tagged by saved proxy. | app (static) | no |
| [`TransferGate.java`](../src/org/phonelink/proxy/TransferGate.java) | One export or import at a time, across threads. | `ProxyService` | no |
| [`Socks5Server.java`](../src/org/phonelink/proxy/Socks5Server.java) | SOCKS5 CONNECT and UDP ASSOCIATE, authentication, admission, relay threads. | `Port` | no |
| [`Upstream.java`](../src/org/phonelink/proxy/Upstream.java) | The seam: `connect`, `datagram` and `resolve` on the egress network. | contract; `Egress` implements it | no |
| [`Traffic.java`](../src/org/phonelink/proxy/Traffic.java) | Byte totals and a minute of per-second samples for one listener. | `Port` | no |
| [`Closeables.java`](../src/org/phonelink/proxy/Closeables.java) | Close-without-throwing, including the bounded linger that avoids orphaned TCP tuples. | none (static) | no |
| [`Egress.java`](../src/org/phonelink/proxy/Egress.java) | Implements `Upstream` with a cellular `Network` and its retries. | `ProxyService` (one shared) | no |
| [`EgressPolicy.java`](../src/org/phonelink/proxy/EgressPolicy.java) | Which link may carry an upstream socket, and the refusal line when it may not. | none (static, Android-free) | no |
| [`EgressState.java`](../src/org/phonelink/proxy/EgressState.java) | The cellular request's retry schedule and its `dumpsys` words. | `Egress` | no |
| [`Uplink.java`](../src/org/phonelink/proxy/Uplink.java) | Watches connectivity and the keyguard; reports allowed/paused for one port. | `Port` | no |
| [`UplinkPolicy.java`](../src/org/phonelink/proxy/UplinkPolicy.java) | The SSID gate's rules, with no Android types. | none (static, Android-free) | no |
| [`WifiLockPolicy.java`](../src/org/phonelink/proxy/WifiLockPolicy.java) | Whether the Wi-Fi lock is wanted for the current listeners. | none (static, Android-free) | no |
| [`BootGate.java`](../src/org/phonelink/proxy/BootGate.java) | The boot observation lines: the vendor gate is unreadable, a missed boot is dated. | none (static, Android-free) | no |
| [`BootReceiver.java`](../src/org/phonelink/proxy/BootReceiver.java) | Notes the boot, then starts the service when `auto_start` is on. | Android (receiver) | yes — receiver |
| [`AdbWireless.java`](../src/org/phonelink/proxy/AdbWireless.java) | Turns wireless debugging back on when the configured SSID matches. | none (static) | no |
| [`TilePlan.java`](../src/org/phonelink/proxy/TilePlan.java) | What one tile click starts and stops, from settings and running state. | none (static, Android-free) | no |
| [`TileCycle.java`](../src/org/phonelink/proxy/TileCycle.java) | The ordered sets a tile click brings up before "off". | none (static, Android-free) | no |
| [`TileScope.java`](../src/org/phonelink/proxy/TileScope.java) | How far a click reaches: the tile's proxies, the one in use, or all. | none (static enum, Android-free) | no |
| [`ProfileDocument.java`](../src/org/phonelink/proxy/ProfileDocument.java) | The JSON export/import file and its validation. | none (static, Android-free) | no |
| [`ProfileTransfer.java`](../src/org/phonelink/proxy/ProfileTransfer.java) | The one-shot loopback protocol that carries a `ProfileDocument`. | `ProxyService` worker thread | no |
| [`ProxyConfig.java`](../src/org/phonelink/proxy/ProxyConfig.java) | Immutable, validated listener settings. | value, held by `Port`/`ConfigStore` | no |
| [`ProxyState.java`](../src/org/phonelink/proxy/ProxyState.java) | Immutable listener state and the one line the panel, tile and notification share. | value, held by `Port`/`ProxyRuntime` | no |

Renaming anything else is a normal refactor: update its references and the
package stays. Renaming a manifest component is not: `Help` prints the component
name, the README's adb commands use it, and the test build is a relinked
manifest that keeps the class package while the application id changes.

## Lifecycle

### Service start

Four callers start `ProxyService` with an action: the panel's Start
(`MainActivity`), a tile click (`ProxyTile`), `BootReceiver` when `auto_start` is
set, and `adb shell am start-foreground-service` (the exported, `DUMP`-guarded
service). The notification's Stop action enters the same `STOP` path. All of
them go through `onStartCommand`; the panel and adb both write through
`ConfigStore`, so they cannot configure a listener differently.

- The service promotes itself to the foreground with the manifest's
  `specialUse` type before anything else, inside the five seconds
  `startForegroundService` allows. A start that cannot get there reports a
  notification and gives up.
- `START` resolves the configuration: stored values, overlaid with the intent's
  extras, then validated by `ProxyConfig`. A named profile is loaded first; an
  omitted extra keeps the stored value. An unknown action fails closed rather
  than falling through to a start.
- `start` adds the port through `Ports.start`. A start on a port that is
  already held closes the old `Port` (and its clients) and replaces it; every
  other port keeps listening. The name becomes "in use", `EventLog` gets a line,
  the panel/tile are republished, and `Port.watch` begins.
- `SAVE_PROFILE`, `RENAME_PROFILE` and `DELETE_PROFILE` change the store; a
  rename also moves the running port's name, and a delete stops a listener
  that was running under that name.
- The return value is `START_STICKY` while at least one port remains, otherwise
  `START_NOT_STICKY`, so a save-only start goes away again.

### SSID gate

`Port.watch` creates a `Uplink` for that port's SSID (or `any_network`). It
registers:

- the default-network callback, so a lost default is seen;
- a Wi-Fi callback, with location information on API 31+, because that is the
  only way the transport carries a readable SSID;
- an `ACTION_USER_UNLOCKED` receiver, because before the first unlock every
  SSID is redacted and the strict comparison can only start at unlock.

`UplinkPolicy` decides from a map of the links the callbacks last described,
never from a synchronous `getAllNetworks()` inside a callback. The rules:

| Observation | Answer |
|---|---|
| A Wi-Fi link whose SSID equals the configured one | allowed |
| `any_network` is on, with at least one link | allowed, Wi-Fi or not |
| Wi-Fi on another readable SSID | paused |
| SSID redacted, phone still locked | allowed (unknown, not a mismatch) |
| SSID redacted after unlock | paused — the location grant or toggle is missing |
| Two links matching, or a match beside an unreadable link | paused — ambiguous |
| Tethering or USB | not a match; only `any_network` opens there |

Allowed means `Port.bind` and `ProxyService.uplinkUp` (the one place
`AdbWireless` may be asked to switch wireless debugging back on). Not allowed
means `Port.pause`: the `Socks5Server` is closed and its clients go, but the
`Port`, its counters and the service stay, and the state says
`paused`/`waiting` with the reason. The next match binds again. The service is
not stopped by a closed gate; only a stop, a failed bind or the last port going
ends it.

### Listener admission

`Port.bind` asks `ProxyService.upstream()` for the shared `Egress` (started on
first need), builds a `Socks5Server` and starts it. `Socks5Server.start` binds
the wildcard address on the configured port and starts `socks5-accept`.

Admission has three ceilings:

- **Listener**: binding `0.0.0.0:port` fails if the port is taken; the port is
  removed from `Ports` and the service says so. Other ports are untouched.
- **Connection count**: an accepted socket is tracked, then handed to a
  `ThreadPoolExecutor` with a synchronous queue and `max_connections` handlers.
  A client beyond the ceiling is reset at once, never queued; this bounds both
  threads and memory whatever a LAN client asks for.
- **Protocol**: the handshake has `HANDSHAKE_TIMEOUT_MS`; `CONNECT` gets
  `CONNECT_TIMEOUT_MS` per attempt and `CONNECT_ATTEMPTS` attempts with a fresh
  socket, because a new source port gets a new carrier NAT mapping. `BIND` is
  refused with command-not-supported. A UDP association pins one client
  endpoint and drops datagrams from any other.

Every accepted client and every upstream socket is tracked so `close()` can end
it; a socket admitted after `close()` is reset instead of served.

### Threads

| Thread | Created by | Work | Ends when |
|---|---|---|---|
| main | Android | Service/activity callbacks, bind/pause/close, locks, publishing | process |
| connectivity callbacks | `ConnectivityManager` on the main looper | Feed `Uplink`/`Egress`; call `evaluate` | unregistered on close |
| `socks5-accept` | `Socks5Server.start` | Accept, track, dispatch; reset on refusal | listener close |
| `socks5-handler` pool | `Socks5Server` | Greeting, auth, request; then client→upstream for CONNECT, or hold the UDP control connection | connection ends; idle core threads time out |
| `socks5-relay` | one per CONNECT | Upstream→client; first direction to end schedules the shared linger | both sockets closed |
| `socks5-linger` | one per listener | Bounded close of a half-closed relay after `RELAY_LINGER_MS` | server close |
| `socks5-udp-client`, `socks5-udp-send`, `socks5-udp-receive` | one set per UDP association | Receive from the client, queue to the cellular socket, return replies | association close, idle timeout, control close or listener stop |
| `proxy-profile-transfer` | `ProxyService.transfer` | One export or import on loopback | transfer done or failed |

The CONNECT handler plus its relay means two threads per live connection, which
is why `max_connections` is a setting and why the thread count is bounded.
UDP has a bounded queue (`UDP_QUEUE`) and drops datagrams when it is full; an
association with no traffic for `UDP_IDLE_MS` ends itself.

### Locks

- **Partial wake lock** `phone-link-proxy:egress`: held exactly while
  `Ports.totalClients() > 0`, released when the last client leaves, when a port
  pauses, and in `onDestroy`. It is not reference counted.
- **Wi-Fi lock** `phone-link:proxy`: `WifiLockPolicy` asks for it while the
  `wifi_lock` switch is on and at least one port is listening without
  `any_network`. The mode is low latency on API 34+ and high performance below.
  A phone that refuses or lacks the lock logs once and keeps serving.

Both locks are released before the service can be killed, and a late connection
callback after `destroyed` cannot reacquire one.

### Stop and `Port.close`

- `STOP` with no extra stops every listener; `--es profile NAME` stops that
  one; `--ei port N` stops the one on that port. The rest keep listening and
  the service stays sticky.
- `Port.close` is idempotent: mark `closed`, close the `Uplink` (unregister the
  callbacks and receiver), then detach and close the `Socks5Server`. Server
  close stops accepting, closes every tracked socket, interrupts the relay/UDP
  workers, shuts the handler and linger pools, and ends the associations.
- `Ports.stop` removes the entries first, then closes them, so a callback from a
  closing server cannot rewrite a port that replaced it. A pause keeps the
  `Port` and its counters; a stop discards it, so a later start is a fresh
  listener with fresh counters.
- When the last port goes, `ProxyService` closes the shared `Egress`, releases
  the locks and calls `stopSelf`. `onDestroy` sets `destroyed` before closing the
  ports, clears the `ProxyRuntime` list and the `EventLog` active tag, removes
  the foreground notification, and closes the egress again if it is still there.
- A **transfer** is not a listener: `EXPORT`/`IMPORT` require the screen on and
  the keyguard unlocked, take `TransferGate`, bind `127.0.0.1` for one
  connection, and never stop a listener another action owns. Their wire is
  `ProfileTransfer`; the commands are in the README.

## State

### `SharedPreferences`

`ConfigStore` uses one private store, `config`, in device-protected storage, so
the boot receiver can read it before the first unlock. At first unlock the
credential-protected copy is moved over. Keys:

- listener: `port`, `user`, `password`, `anonymous`, `ssid`, `any_network`,
  `max_connections`;
- switches: `auto_start`, `adb_wifi`, `wifi_lock`;
- tile: `tile`, `tile_start`, `tile_stop`, `tile_cycle`, `selected_profile`;
- profiles: `profiles` plus `profile.<name>.*` for each saved proxy;
- bookkeeping: `in_use`, `boot_seen`, `boot_missed_at`.

This is the only place a saved password lives. It is app-private, and the
export document carries it only when the user chooses a destination.

### `state.txt`

`ConfigStore.publishState` writes a credential-free summary to the app's
external files directory (`Android/data/<package>/files/state.txt`), beside a
`.new` file it renames over the old one so a reader never sees half a line. It
holds the service switches, stored port and connection ceiling, SSID,
`any_network`, the proxy in use, and `BootGate`'s two lines
(`oem_autostart unreadable`, and `last_boot no_broadcast <epoch>` when there is
one). It is written by the service and by every store change, so it survives
the service being stopped — which is when a broken phone is diagnosed.

### `dumpsys`

`adb shell dumpsys activity service org.phonelink.proxy/.ProxyService` prints
`Help.text` (the whole adb contract), then `Right now`: aggregate state, the
proxy in use, each running listener with its port, bytes and clients, totals,
the config summary, `max_connections`, UDP support, the `egress` line, the two
boot gates, `adb_wifi`, the Wi-Fi lock, the tile's settings, and every saved
proxy with its summary and switches. A `config.summary()` names the port, the
SSID and the user — never the password.

### `EventLog`

The panel's log and its **All events** view read a 200-entry in-memory ring,
newest first, each entry tagged with a saved proxy (or untagged for the app).
It is not persisted and not dumped; `adb logcat -s phone-link-proxy` remains the
complete record. It is a snapshot of what just happened, not an audit trail.

### What must never appear there

- **Passwords**, in `state.txt`, `dumpsys`, `EventLog`, logcat or an error
  notification. `dumpsys` prints the user and whether authentication is on, not
  the secret.
- **Tokens** from an `EXPORT`/`IMPORT` action. The token exists on the `am`
  command line by design, for one connection; it is never logged or stored.
- **Document bodies** (the export JSON) or any line of them. Transfer errors log
  a code, not a parser message, because a parser message could quote the
  document — and the document holds passwords.
- **Per-connection payloads.** Counters and client counts are state; the bytes
  are not kept.

The export file itself is the exception by design: it holds passwords in plain
text, is written only where the system picker points, and the panel warns before
writing it.

## Contract pointers

- [`Help.java`](../src/org/phonelink/proxy/Help.java) — the adb contract, the
  extras and what each action does. The same text is `dumpsys` and the Info
  dialog, and the unit suite pins it.
- [README: Run](../README.md#run) — the start/stop/save commands.
- [README: Settings](../README.md#settings) — the panel and its switches.
- [README: When it listens](../README.md#when-it-listens) — the SSID gate and
  the Wi-Fi lock in prose.
- [README: Taking them off the phone, and putting them back](../README.md#taking-them-off-the-phone-and-putting-them-back)
  — the export/import wire and its gate.
- [README: Tests](../README.md#tests) — what each suite claims.
- [AGENTS.md](../AGENTS.md) — repository rules, build and commit conventions.
- [Starting before the first unlock](pre-unlock-autostart.md) — the four Android
  boot gates measured on the phone.
- [The boundary page](https://github.com/MBanucu/phone-link/blob/main/docs/proxy-boundary.md)
  — which repository owns the contract, build, install and harnesses.

## I want to…

| Task | First file | Test |
|---|---|---|
| Add or change an adb action | `ProxyService.java` (`ACTION_*`, `onStartCommand`), then `Help.java` and the README | `bash tests/proxy-app.test.sh` pins the help text; `bash tests/live/proxy-app.sh` drives the action over adb |
| Change SOCKS behaviour (auth, CONNECT, UDP, replies, limits) | `Socks5Server.java`; `Upstream.java` if the seam moves | `bash tests/proxy-app.test.sh` — the protocol cases, including UDP and saturation |
| Change where upstream traffic leaves | `Egress.java`; `EgressPolicy.java` / `EgressState.java` for the rule and retry | `bash tests/proxy-app.test.sh` — egress selection, refusal and retry; a phone run for the real interface |
| Change the panel | `MainActivity.java`, then `HomeScreen.java` / `ProxyScreen.java`; `TrafficView.java` for the sparkline | `bash tests/live/proxy-app.sh` (panel steps) and the in-process `devicetest/` checks |
| Change the transfer | `ProfileDocument.java` for the format, `ProfileTransfer.java` for the wire, `ProxyService.transfer` for the gate | `bash tests/proxy-app.test.sh` — document round-trip, framing, token and limits |
| Add a permission | `AndroidManifest.xml`, then the code that needs it and `Help.java` if the contract changes | `nix flake check` builds the APK; `bash tests/live/proxy-app.sh` on a phone for the runtime grant |
| Change the SSID gate | `UplinkPolicy.java` for the rule, `Uplink.java` for what it observes | `bash tests/proxy-app.test.sh` — the lock/unlock, match, mismatch and ambiguity cases |
| Change the tile's click | `TilePlan.java` / `TileCycle.java` / `TileScope.java`; `ProxyTile.java` only sends it | `bash tests/proxy-app.test.sh` — the tile cases |
| Change what `dumpsys` or `state.txt` says | `ProxyService.dump`, `ConfigStore.publishState`, `BootGate.java` | `bash tests/proxy-app.test.sh` — state text and the egress line; a live run for the real dump |

## Test map

Two suites, on purpose: one needs only a JDK, the other needs a phone.

### Hermetic: `bash tests/proxy-app.test.sh`

Compiles the Android-free classes without `android.jar` and runs
`test/org/phonelink/proxy/ProxyAppTest.java`: the SOCKS5 handshake and both
credential failures, anonymous mode, CONNECT over IPv4/IPv6/domain, 32 KiB
relay, half-close and linger, concurrency, saturation, refusals, client counts;
UDP ASSOCIATE framing, a real echo, fragments, the queue cap, control close and
idle timeout; the configuration rules; the SSID gate; both boot gates; the state
text; egress selection, refusal and retry; the runtime listener; the tile plan
and cycle; `ProfileDocument` and `ProfileTransfer`; `Help`'s contract text.

It is also the flake's `unit` check, so `nix flake check` runs it.

Hermetic harness helpers have their own suites, no phone either:
`bash tests/eta.test.sh` (run estimate), `bash tests/results.test.sh` (CSV
history importer) and `bash tests/pictures.test.sh` (which frames explain a
failure).

### Live: `bash tests/live/proxy-app.sh`

Needs a phone, the SDK and the **test** APK (`build/phone-link-proxy-test.apk`,
or `PROXY_APP_APK`), which installs as `org.phonelink.proxy.test` with its own
store and listeners. It installs, opens the panel, requires a card to open its
proxy, checks auto-start and the panel's gates, puts `200` through the tunnel,
refuses a wrong password, applies anonymous and `any_network`, keeps the port
closed on an unrelated SSID, exercises profiles and renames over adb, Save and
Save as, ten proxies, the counters and log, the tile, and uninstalls. It skips
screen steps on a phone that stays locked, and never runs in `nix flake check`.

- `tests/live/phone.sh` — how to unlock, capture, tap and wait on the phone;
  the run's step library.
- `devicetest/` — `PanelTests`, installed into the proxy's process by
  `am instrument`, so a claim about the page is a claim about the view tree.
- `capture/` — the service the harness reads the screen from.
- `tests/live/clock.sh`, `frames.sh`, `remux.sh`, `pictures.py` — after a run:
  tie the phone clock to the recording, pull stills, make the MP4 seekable, and
  pick the frames that explain a failure.
- `tests/live/eta.sh`, `results.py` — during and after: remaining-time estimate
  from `timings.csv`, and the queryable copy of past runs.

### What proves what

| Question | Command |
|---|---|
| Is the protocol or a pure rule right? | `bash tests/proxy-app.test.sh` |
| Does the app compile and the APK build? | `nix flake check` (or `./build.sh`) |
| Does the panel show it, does adb drive it, does a real port serve? | `bash tests/live/proxy-app.sh` |
| Does the egress really leave by cellular, and does the SSID gate close on a real link? | the live run; its tunnel step and its unrelated-SSID step |
| Is a run slow, or did a check fail before? | `tests/live/results.py`, `result-live/` |

What the unit suite cannot prove, and does not claim: real SSID redaction, the
foreground-start and location grants, lock acquisition, view rendering, and a
socket actually leaving by the cellular interface. Those are the live run's.

## Read next

- [README](../README.md) — what it is and the commands.
- [AGENTS.md](../AGENTS.md) — build, rules and the version bump.
- [pre-unlock-autostart.md](pre-unlock-autostart.md) — why the boot path is shaped
  this way.
- [The boundary page](https://github.com/MBanucu/phone-link/blob/main/docs/proxy-boundary.md)
  — the split with `MBanucu/phone-link`.
