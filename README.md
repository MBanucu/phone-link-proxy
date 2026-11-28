# phone-link proxy

The SOCKS5 listener the laptop's tunnel talks to, as our own app instead of the
vendored `com.tokyoxpa3.androidproxy`.

Small on purpose: no AndroidX, no Gradle, no third-party dependencies, so the
build is `aapt2` + `javac` + `d8` + `apksigner` and needs nothing from the
network once the SDK is in the store.

It began as `android/` in the laptop project that drives it over adb and now
lives on its own: its own build, its own tests, its own releases. The adb
contract between the two is documented by the app itself — `About this app`,
and the service's own `dumpsys` dump — and is not expected to change with either
side. The ownership split, version policy and change order are stated once, on
the laptop side:
[`docs/proxy-boundary.md`](https://github.com/MBanucu/phone-link/blob/main/docs/proxy-boundary.md).

New to the code: [`docs/architecture.md`](docs/architecture.md) is the map — the
layers, every class, the lifecycle, the state, and the file to open for a task.

## Store metadata

`fastlane/metadata/android/en-US/` holds what a store listing needs: the title,
the two descriptions, a per-versionCode changelog (`changelogs/<code>.txt`,
where the code is `major*10000 + minor*100 + patch`), a 512 px `images/icon.png`
and four screenshots in `images/phoneScreenshots/` — the list with a proxy
running, the Listener page, the Service page and the Log page with a download
moving through it. `nix flake check` fails if the current `version` has no
changelog.

The screenshots are of the **test build** (`org.phonelink.proxy.test`) holding
three throwaway proxies (`cafe`, `hotel`, `laptop`, user `demo`), not of the app
the phone lives on, so nothing about somebody's real uplink is in them.

The launcher icon is `res/mipmap-anydpi-v26/ic_launcher.xml`: a white rounded
phone with an arrow leaving it, on the panel's purple, drawn inside the adaptive
icon's safe circle; `images/icon.png` is the same drawing at the size stores ask
for. The drawing itself, as one SVG at the adaptive icon's 108 dp, is
`artwork/launcher-icon.svg`: the phone and the arrow are
`ic_launcher_foreground.xml`, the gradient behind them
`ic_launcher_background.xml`, and a change starts in the SVG.
`artwork/render-icon.sh` rewrites `icon.png` from it (and `--check` says whether
the committed one is that rendering; `nix flake check` runs exactly that, and so
does the release workflow before it builds a tag, so the picture cannot fall
behind the drawing).

`ic_stat_proxy` stays what notifications and the tile use, where a one-colour
silhouette is what the system wants.

## Why it exists

- **Egress is cellular by construction.** On the AP link the phone's default
  network is the laptop, so a socket opened on the default route comes back to
  the host that asked for it. `Egress` holds an active `TRANSPORT_CELLULAR`
  `NetworkRequest` and resolves and connects on it —
  the laptop repository's `docs/phone-network.md`, section "How the proxy gets out over cellular".
  `EgressPolicy` makes that choice explicit and testable without Android: a
  validated Wi-Fi default never wins over a cellular link. If the network the
  egress resolved is ever not cellular, `Egress.connect` refuses the request with
  one log line instead of opening a socket that would loop. `dumpsys` prints the
  result as `egress cellular network N on <iface>`, so a live check can assert
  where the tunnel leaves the phone.
- **It listens on `0.0.0.0`**, so the port is the same on `ap0`, `rndis0` and
  `wlan0` and survives the address changing. That is trap 5 fixed rather than
  worked around.
- **The wake lock is held only while a client is connected.** The app it
  replaces holds one for as long as it runs, which made
  `5GProxy::WakeLock` the phone's largest partial wake lock (16 h accounted,
  ~79 min held in one acquisition).
- **No screen automation.** The service is exported and guarded by
  `android.permission.DUMP`, so `am start-foreground-service` drives it and
  nothing else does. Starting or stopping the proxy does not need the phone
  unlocked, a uiautomator dump, or a button text.

## When it listens

By default the port is only open while the phone is a client of the configured
SSID, so it can sit on a hotel or café Wi-Fi without offering a relay to it:

| phone state | verdict |
|---|---|
| client of the configured SSID (`PhoneLink` by default) | listening |
| joined any other Wi-Fi | paused, port closed |
| no Wi-Fi at all | paused, port closed |

Tethering has no first-class support. With the default SSID gate the hotspot
and USB paths keep the port closed (the vendored app still covers hotspot
mode). `any_network` removes that gate: the same wildcard listener then
opens on whatever network Android reports, including a hotspot or USB tether. It
is not a separate transport mode — egress is still the cellular request — and it
does not by itself make USB fallback work: the laptop's `proxyHosts` still has
to name an address at which the phone is reachable, and a tether address can
change with every connection. That is the trade-off: the port is exposed on
every network the phone joins, not only the one you named, so the username and
password still apply; it is unauthenticated only when `anonymous` is set
explicitly. A first-class USB transport is planned; until then `any_network` is
the way to use the USB path.

The service stays in the foreground while paused and reports why
(`not listening: on "FRITZ!Box 7530 XJ", not "PhoneLink"`), so it opens by
itself when the phone rejoins `PhoneLink`. SSID changes arrive as connectivity
callbacks, so nothing is polled.

Reading the SSID is reading location data, and two things are needed for it, all
in the manifest: `ACCESS_FINE_LOCATION` granted and location switched on;
`ACCESS_WIFI_STATE`, because `WifiManager` is the source that is not redacted on
this build (the copy in `NetworkCapabilities` reports `<unknown ssid>`). The
service type is `specialUse` only — a `location` type cannot be started before
the first unlock at all, and is not needed: the SSID reads fine with the
permission alone.

Before the first unlock the SSID is unavailable by design, so the first row of
the table above is relaxed: any connected Wi-Fi counts, and the exact
`PhoneLink` comparison resumes the moment the phone is unlocked. That relax is
what lets the listener come up during boot. The DokeOS Auto Start Management
list sits in front of four Android gates — a missing `RECEIVE_BOOT_COMPLETED`,
`directBootAware` on the receiver rather than the application, the unusable
`location` service type, and this SSID rule — and all five are documented in
[docs/pre-unlock-autostart.md](docs/pre-unlock-autostart.md).

### Keeping the Wi-Fi association

Android may leave an access point that has no internet after 30–94 seconds on
this phone (`mIpReachabilityDisconnectEnabled=true`), and the listener closes
with the link. `wifi_lock` is an opt-in **experiment** for that case, not a
promise: it asks Android for a Wi-Fi lock while at least one listener is bound
on Wi-Fi. It is off by default, and anything started with `any_network` does
not hold it because that listener need not use Wi-Fi at all. Pausing or
stopping the last such listener releases the lock; so does the service going
away.

The platform weakens what the lock can mean. From Android 14,
`WIFI_MODE_FULL_HIGH_PERF` is deprecated and AOSP's `WifiLockManager` converts
it to `WIFI_MODE_FULL_LOW_LATENCY`; low latency is only effective while Wi-Fi
is connected, the screen is on and the acquiring app has foreground importance,
which a foreground *service* does not give it. The app therefore asks for
`WIFI_MODE_FULL_LOW_LATENCY` on API 34+ and `WIFI_MODE_FULL_HIGH_PERF` on API
29–33. A lock that is held costs battery while it is held. Treat #11 as open
until a 30-minute screen-off test on the phone shows the association surviving;
the adb switch is how such a run is set up.

```sh
adb shell am start-foreground-service -n org.phonelink.proxy/.ProxyService \
    -a org.phonelink.proxy.action.START --ez wifi_lock true \
    --ez any_network false
adb shell dumpsys activity service org.phonelink.proxy/.ProxyService |
    grep -E 'wifi_lock |listening'
```

## Build

```sh
nix develop
./build.sh
```

The SDK (build-tools 35.0.0 + platform 35) comes from this flake's dev
shell. No private key is tracked: the first `./build.sh` generates a throwaway
dev key at `build/dev.keystore` (mode 600, password `android`) and reuses it
on later builds in the same checkout, so `adb install -r` updates the
sideloaded app instead of failing with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
`build/` stays ignored, and a fresh checkout or a `nix build` sandbox gets its
own dev key, so an APK built there needs an uninstall before it can replace one
built elsewhere. Never ship an APK signed with the dev key; a release is signed
with a key of your own, see below.

`nix flake check` builds the same APK in the sandbox (`apk`), and
`nix build .#proxy-apk` gives the artifact without a phone. Three APKs come out:
the app, `capture.apk` (the accessibility dumper the live harness reads the
screen from) and `devicetest.apk` (the instrumentation that runs the panel's own
checks inside the app's process). Only the first is the proxy; the other two are
installed by a test run and removed by it.

### Signing a release

An APK for other people is signed with the release key. `--release` makes that
mandatory: without `PHONE_APP_KEYSTORE` it fails instead of falling back to the
generated dev key.

```sh
PHONE_APP_KEYSTORE=~/keys/phone-link.jks PHONE_APP_KEY_ALIAS=phonelink \
PHONE_APP_KEYSTORE_PASS=... PHONE_APP_KEY_PASS=... \
  nix develop -c ./build.sh --release
```

The four variables are the keystore (JKS or PKCS12), the alias inside it and the
two passwords, the key's being optional when it is the store's. They reach
`apksigner` as `env:` references, so no password appears in a process listing or
in the log of the job that ran the build. With `PHONE_APP_KEYSTORE` unset, a
plain `./build.sh` still produces a locally installable APK signed with the dev
key; that is the only thing the dev key is for.

Generate a release key once and keep it somewhere it cannot be lost — the same
key is the only way to update an installed app in place, so a lost keystore
means every phone has to uninstall and loses its saved proxies:

```sh
keytool -genkeypair -keystore phone-link.jks -storetype PKCS12 \
  -alias phonelink -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=phone-link release"
```

Tagging publishes an APK. `.github/workflows/release.yml` runs on a `v*` tag,
fails unless the tag is exactly `v$(cat version)`, and runs the same
`nix flake check` as CI (unit, harness, format, icon, changelog and compile)
before building. It then builds from the tagged tree with the repository's
release secrets (`ANDROID_KEYSTORE`, the keystore file base64-encoded because a
secret is text, plus `ANDROID_KEYSTORE_PASS`, `ANDROID_KEY_ALIAS` and
optionally `ANDROID_KEY_PASS`). `ANDROID_CERT_SHA256` is required: it is the
release certificate's SHA-256 fingerprint, the finished APK must carry it, and
the workflow refuses to publish if it is unset, if the APK carries the debug
key, or if the certificate differs. It then attaches
`phone-link-proxy-<version>.apk` and its `.sha256` to the release for the tag.
The version in the name is the app's own `version` file, which is the one
source `build.sh` stamps into the manifest.

The laptop repository's `phone-proxy-install` fetches that APK (checksum
checked) and installs it. On the laptop the first run is `phone-proxy-install`,
then `phone-proxy-setup`: setup starts the proxy with the credentials from the
root-only secrets file, turns auto-start on unless `--no-auto-start` is given,
and verifies a request through the listener. `phone-proxy-setup --check`
reports the settings and repeats that request without changing them. A phone
that already runs a locally built APK cannot update in place — that one carries
a checkout's dev key — so it needs `--replace-signer` once, which uninstalls
first and takes the app's saved proxies with it.

## Tests

- `tests/proxy-app.test.sh` — hermetic, plain JDK. It compiles `Upstream`,
  `Closeables`, `EgressPolicy`, `EgressState`, `ProxyConfig`, `ProxyState`,
  `ProxyRuntime` and `Socks5Server` **without** `android.jar` and runs the
  protocol and configuration cases. The cases live one area per file under
  `test/org/phonelink/proxy/` (`Socks5TcpTest`, `Socks5LifecycleTest`,
  `Socks5UdpTest`, `ConfigTest`, `ProfileDocumentTest`, `ProfileTransferTest`,
  `BootGateTest`, `EgressPolicyTest`, `ProxyStateTest`, …), with the assertions,
  loopback helpers and fixtures in `TestSupport`. `--only udp` runs one area,
  and `--only 'socks5-*'` or `--only tcp,udp` selects more than one. The cases:
  method negotiation, both credential failures, unauthenticated mode, CONNECT
  for IPv4 and for a domain name, relaying 32 KiB, half-close, 60 concurrent
  connections, the count returning to the live flows, a half-closed relay
  released after its linger, an answer delivered after the client half-closes,
  both traffic counters, 1000 idle tunnels held by the fixed relay pool and
  ended by close, saturation refused instead of queued, a refused
  command, an unknown address type, an upstream failure reply, client counting,
  a non-SOCKS greeting; UDP ASSOCIATE's request/reply framing for IPv4, IPv6
  and domain names, a real UDP echo through an association, fragments and
  oversized datagrams dropped without ending it, and the association ending
  with its control connection, its idle timeout and a listener stop; the
  configuration rules (port range, the connection ceiling's bounds and its
  profile round-trip, credentials, anonymous, SSID, byte limits); the one action
  table, with a missing or bad extra named precisely, and the generated help,
  `dumpsys` and README blocks compared to it;
  the cellular egress selection (a validated Wi-Fi default never wins over
  cellular, and a non-cellular resolution is refused), its retry state and its
  dump line;
  the status text the panel, the tile and the notification share; the runtime
  listener; and the `running` flag the tile turns into its subtitle. This is
  why the code has a `Logger` seam, an `Upstream` interface and a config/state
  split instead of `Log.i`, `Egress` and string building inside the service —
  and why the `unit` check carries a JDK.
- `tests/relay-bench.sh` — loopback throughput for the CONNECT relay, for a
  before/after comparison: down and up, one and four parallel flows, a fixed
  MiB per scenario, and a small warmup run. It is a measurement, not a gate,
  and it is not part of `nix flake check`; the number says whether the selector
  changed the byte pump, not what the phone's cellular link does.
- `tests/live/proxy-app.sh` — the steps and the claims, with
  `tests/live/phone.sh` beside it holding how to look at and drive the phone
  (captures, gestures, sequences, the panel's navigation, unlock and settings),
  so a step reads as a claim. It needs a phone with a Wi-Fi client link and the
  built APK — the *test* one, `build/phone-link-proxy-test.apk` (or the
  file named by `PROXY_APP_APK`), which installs as
  `org.phonelink.proxy.test`. The id is the whole of the second instance: its
  own store, its own listeners, its own notification and tile, so a run leaves
  the proxy the phone lives on alone — and the run checks that, by reading the
  other instance's dump before it starts and requiring it to read the same
  when it ends. It unlocks the phone through `PHONE_UNLOCK` — the laptop repository's
  `bin/phone-unlock`, which reads the root-only PIN — when `sudo -n` allows it,
  and skips those steps otherwise (the shade ignores tile clicks and uiautomator cannot dump
  a sleeping panel), then: install, open the list, require a card to open its
  proxy, `auto_start` showing on the `Service` segment, the header warning and
  the battery request, `200` through the tunnel, a wrong password refused, the
  `anonymous` box applied by the one button, an unrelated SSID keeping the port
  closed with the service up, `any_network` bypassing the predicate, profiles
  saved and loaded over adb, `Save` on both segments, a card starting and
  stopping its proxy, `Rename` from the menu and `RENAME_PROFILE` over adb, the
  info marks and `About this app`, `New proxy`, ten saved proxies without losing
  the button under them, the traffic counters and the log
  on the `Log` segment, stop, start and stop again from the Quick Settings tile,
  and uninstall. A phone that stays locked skips the steps that need the screen
  instead of failing. Not part of `nix flake check`.

  Every run leaves its evidence in `result-live/<UTC start>/` (gitignored, or
  `PHONE_LIVE_ARTIFACTS`): `screen.mp4`, the phone's screen recorded for the
  whole run, and `ui-NNNNN.xml`, the screen as XML for every change the
  accessibility capture recorded — 387 of them in the last run, against 122
  from polling — with `ui-index.csv` giving each one's timestamp and what
  caused it, so what the phone showed at a moment can be re-read afterwards.
  `tests/live/frames.sh result-live/<run>/screen.mp4 0:42 95.5` writes a still
  per moment; it needs ffmpeg on the host. `checks.csv` is the run's verdicts as
  a list: one row per check with the step, the harness file and line that asked
  it, the function it was asked from, when the laptop decided it and the claim —
  and a failure prints by itself what happened just before it (the previous
  check, the phone's clock, its last three actions and two captures), so a red
  line is a place to look rather than a place to start searching. Each step's
  duration and pass/fail
  counts are appended to `result-live/timings.csv`, one row per step plus a
  `TOTAL` row: the file is never rewritten, which is what makes "how long does
  this run cost" a question with an answer. `PHONE_LIVE_CAPTURE=0` turns the
  recording and the snapshots off for a quick run.

  The steps wait on the phone, not on the clock. The service answers a `WAIT`
  broadcast when the screen does one of three things: holds a piece of XML
  (`--es has 'text="OK"'`), stops holding it (`--ez gone true`), changes at all
  (`--ez change true`), or has been still for a moment (`--ei quiet 250`). The
  broadcast is held, so the harness's next command arrives the instant the
  screen said it — after a tap it asks for "still", after a dialog it asks for
  the dialog's own button — and the answer is the result code: `0` for the
  screen, `1` for the clock. Measured: a needle already on screen answers in
  0.13 s, one that never arrives returns at the timeout with `result=1`, and the
  first run built this way took 324 s against 377 s of fixed sleeps, with the
  steps that open dialogs (`Save`, the info marks, `new proxy`) each 5–11 s
  cheaper. `PHONE_LIVE_DUMPS=uiautomator` falls back to sleeping a second.

  A step that is a straight line — open a page, type a name, press its button,
  wait for the page behind — is one `SEQUENCE` broadcast instead of one per tap,
  key and wait (`capture/.../Sequence.java` has the grammar):
  `--es script 'wait class=android.widget.EditText 2000; text harness-new;
  tap-id button1 3000; wait id=back 3000'`. The phone runs it on a thread of
  its own, stamps every instruction in `actions.csv`, and answers with the
  instruction that stopped it, which the harness prints when that step fails.
  Instructions are `tap`, `tap-id`, `tap-text` (waits for the place to hold
  still, so a sliding menu is not tapped where it was), `swipe`, `text` (into
  the focused field, replacing it — no clearing first), `keyboard-off` (Back,
  only if a keyboard window is really up), `back`, `home`, `wait`, `wait-gone`,
  `wait-change`, `wait-quiet` and `expect`/`expect-gone`; matchers are `text=`,
  `contains=`, `id=`, `desc=`, `class=` and `xml=`. Measured: the new-proxy
  dialog — open, type, press, land on the page — is 0.9 s in one message, and
  the harness steps that use it (`rename`, `info marks`, `new proxy`, the
  battery dialog on every toggle) lost 3–7 s each.

  A selector is several matchers that have to sit on the same node, so one card
  can be told from the card beside it: `id=card_name text=hotel`. The whole
  selector is one argument, quoted in the script, and a term's value cannot
  hold a space. With selectors and four more instructions, the loops that were
  the harness's — scroll about until it is on screen, then tap it or read it —
  are one message: `scroll-top` (four drags, sized from the phone's own screen,
  ending still), `scroll-to SELECTOR 8000` (top, then down until a node
  matches), `tap-scroll SELECTOR 8000` (the same, then tap what it found) and
  `read SELECTOR 3000`, which answers with the node's text: the result code is
  `0` and the result data is the value, empty when the node has no text. So
  `panel_open` is `tap-scroll "id=card_name text=harness-two" 12000; wait
  id=back 4000`, `ui_text_seek` is `scroll-to id=adb_state 8000; read
  id=adb_state 2000`, and the ten-card list finds `New proxy` under the cards
  with `scroll-to id=new_profile 10000`. Measured on the phone: `tap-scroll` is
  1.0–1.4 s from the broadcast to the tap it took, scrolling included, and
  `scroll-to` 0.9 s — one message where the harness sent 8–12 — but the run is
  not faster for it (249.7 s against 239.7 s with one check more): what those
  loops cost was the waiting for the page, not the round trips.

  Everything the phone writes down is on one clock
  (`SystemClock.elapsedRealtimeNanos()`, which counts through deep sleep):
  `ui-index.csv` gives each capture the instant it was read, `actions.csv`
  every tap, swipe, Back and Home the service took, with the instant it
  dispatched it, and `input.csv` the typing, which has to go through `input`
  and so carries the two readings that bracket it — the interval the instant is
  inside. Taps go through the service because `input tap` is a fresh JVM per
  call: it costs a third of a second and nobody can say when it landed. The
  service waits for each gesture to finish before dispatching the next one: two
  strokes in flight at once are one multi-touch gesture, and four drags fired a
  millisecond apart moved the page not at all, so the tap after them landed on
  a screen that had gone somewhere else.

  That clock is tied to `screen.mp4` by flashing: the service paints the whole
  screen the one magenta nothing else here is, at instants it records
  (`clock.csv`), once when the recorder is up and once before it is stopped.
  `tests/live/clock.sh` finds those flashes in the video (decoding at 1×1, a
  few seconds of it) and fits `video_seconds = offset_s + drift * (device_ns -
  ref_ns) / 1e9` into `clock.txt`. Measured on a 380 s run: 16 edges fitted to
  ±4.5 ms across the 308 s between the two bursts. The drift is part of that:
  43 ppm over 308 s is 13 ms, so a mapping without it would be wrong by more
  than the target by the end of a run. One flash on its own can sit a few
  frames away from the line, because a screen changes 60 times a second and the
  encoder skips frames when it is busy with an animation — which is why the fit,
  not the worst edge, is what a run reports. The first burst is looked for from the
  second placed from it, because the recording begins whenever `screenrecord`
  got going and its last frame can be seconds after the last flash (it only
  encodes a screen that changes): neither end of the file is a time the phone
  knows. A run fails when the fit is worse than 10 ms.

  The XML comes from `capture/` — a second, test-only APK with an
  `AccessibilityService` that writes the screen on demand and once per changed
  screen. `adb shell uiautomator dump` costs about 2.5 s here (a cold JVM, then
  the wait for a quiet accessibility window); the service is already connected,
  so a requested capture is 0.1–0.4 s, and the whole run went from 530 s to
  under 380 s while making 6 more checks. The harness installs it, enables it through
  `settings put secure enabled_accessibility_services` (and puts the phone's
  previous value back), and uninstalls it at the end; `PHONE_LIVE_DUMPS=uiautomator`
  keeps the slow path, which is also what happens when the capture APK is not
  built.

  It writes what `uiautomator` would have: only what is visible (an off-screen
  child of a scroll view has no place in a dump, and a caller that taps the
  centre of one taps something else), and only the windows of the app in front
  plus its own dialogs, so the status bar and the launcher behind it stay out
  of the way.

- `devicetest/` — the panel's checks that do not need a picture, run by
  the phone itself. `am instrument -w -e first inside-a -e second inside-b
  org.phonelink.proxytest/.PanelTests` loads one `Instrumentation` into the
  proxy's process, starts the activity and reads the views: the badge on a card,
  the word on its button, the summary that carries the *in use* mark, the line
  under the page's button and the port it names. No JUnit and no androidx — no
  runner jar to vendor and no repository to resolve — so it builds with the same
  `aapt2`, `javac`, `d8` and `apksigner` as everything else, and the expected
  strings come from the app's own resource table, so wording is asserted where
  the wording is the claim and nowhere else. It sends one `sendStatus` per claim
  (`check`, `verdict`, and `detail`, which is what it actually saw), and the
  harness's step `the page from the inside` turns every status into a check of
  its own: a run that says nothing is a failure, and a run that stopped at a bad
  claim reports how many of its nine it made instead of looking like a shorter
  green one. What it cannot decide it does not claim — that a port serves is the
  laptop's socket, the screen is the capture service's, `dumpsys` and `logcat`
  are adb's — and because `am instrument` starts the app's process again (a
  listener running before it is gone after), the step stands alone with nothing
  listening when it starts and starts what it needs from the card and the page.
  Measured: ten claims in 13.0 s, eight of them spent saving the two profiles
  over adb; `several at once` gave up its three page checks and 4.3 s to it.

## Run

```sh
adb install -r -g build/phone-link-proxy.apk

# start (credentials are required unless anonymous is passed explicitly)
adb shell am start-foreground-service -n org.phonelink.proxy/.ProxyService \
    -a org.phonelink.proxy.action.START \
    --ei port 1080 --es user proxy --es password "$PHONE_SOCKS_PASS"

# stop
adb shell am start-foreground-service -n org.phonelink.proxy/.ProxyService \
    -a org.phonelink.proxy.action.STOP
```

### Adb actions

This build speaks `contract_version 1`, an integer with a documented history in
`Actions`; `dumpsys` and the non-secret `state.txt` both carry it, so the laptop
can tell a build that is too old from a phone whose screen is locked. The
table below is generated from the app's one `Actions` table, and the unit suite
refuses a README that does not match it. No action, extra or version line holds
a token, password or export document.

<!-- adb-actions:start -->
| action | required extras | optional extras | gate | what it does |
|---|---|---|---|---|
| `START` | — | `--ei port N (1024..65535)`, `--ei max_connections N (16..1024)`, `--es user NAME (at most 255 bytes)`, `--es password PASS (at most 255 bytes)`, `--es ssid NAME`, `--ez anonymous BOOL`, `--ez any_network BOOL`, `--ez auto_start BOOL`, `--ez wifi_lock BOOL`, `--ez adb_wifi BOOL`, `--es profile NAME (non-empty)` | foreground | start or replace the listener on its port; omitted extras reuse the stored values |
| `STOP` | — | `--es profile NAME (non-empty)`, `--ei port N (1024..65535)` | none | stop every listener, or the one named |
| `SAVE_PROFILE` | `--es profile NAME (non-empty)` | — | none | keep the current configuration under a name |
| `DELETE_PROFILE` | `--es profile NAME (non-empty)` | — | none | remove a saved configuration by name |
| `RENAME_PROFILE` | `--es profile NAME (non-empty)`, `--es to NEWNAME (non-empty)` | — | none | give a saved configuration another name |
| `EXPORT` | `--ei port N (1024..65535)`, `--es token TOKEN (1..128 printable ASCII)` | — | unlocked | send every saved proxy over a one-shot loopback socket |
| `IMPORT` | `--ei port N (1024..65535)`, `--es token TOKEN (1..128 printable ASCII)` | — | unlocked | read a ProfileDocument back over a loopback socket |
<!-- adb-actions:end -->

Every START extra is optional and a start that omits one reuses the stored
value; on a fresh install it still needs a `user` and `password`, or
`anonymous=true` (see Settings). The `gate` column says which actions need the
service in the foreground and which need the screen on and the keyguard
unlocked.

`-g` grants `POST_NOTIFICATIONS` at install; the foreground notification is
what keeps the service out of the background-start rules, and it carries a Stop
action.

`build.sh` writes two APKs of this app: `phone-link-proxy.apk` under
`org.phonelink.proxy`, and `phone-link-proxy-test.apk` under
`org.phonelink.proxy.test`, which is the one the live harness installs. They are
the same code — one release build and one for testing — and a phone can hold
both, listening on their own ports with their own stores, so testing a build
never replaces the proxy the phone lives on. Only the label differs ("Phone link
proxy" and "Phone link proxy (test)"), in the launcher, the notification and the
tile, because two identical entries in a list are a thing to avoid.

The second id is a relinked manifest, so the classes keep the package they were
compiled in and only the application id moves: a component of the test build is
`org.phonelink.proxy.test/org.phonelink.proxy.ProxyService`, not
`org.phonelink.proxy.test/.ProxyService`, and the resource ids stay
`org.phonelink.proxy:id/...` because the resource table is not renamed with it.
The help text says which form belongs to the build it is printed from.

The same start and stop are on the phone: the app is a launcher entry ("Phone
link proxy") and the notification opens it.

```sh
adb shell am start -n org.phonelink.proxy/.MainActivity
```

The panel shows the live state (`listening on 0.0.0.0:1080 · 0 clients`, or why
it is closed), pre-fills the stored port, connection ceiling, user, password
and SSID, and writes
through the same `ConfigStore` as the adb extras, so both paths configure one
listener. `anonymous` stays adb-only, because the panel always asks for a
password; the panel's Start also re-reads the fields, so changing them and
pressing Start restarts the listener with the new settings.

`max_connections` is how many SOCKS connections the listener may hold at once
(16..1024, 256 by default). An established CONNECT costs no thread — the relay
is a fixed selector pool — so raising it is how a desktop browser with a
hundred keep-alive flows is served instead of being refused; the panel's
`Max connections` field writes the same key as `--ei max_connections N`.

The same two actions are on a Quick Settings tile: tapping "Phone link proxy"
starts and stops the proxies the tile is for, and the subtitle says what is
happening — `listening`, `paused` (the service is up but the SSID did not
match) or `off`. The tile follows the panel, adb and the boot receiver because
all of them publish into the same `ProxyRuntime`.

Which proxies the tile is for, and how far a click reaches, is the tile's own
page — `The tile` in the app's burger menu, and the `Start and stop from the
Quick Settings tile` switch on each proxy's Service page for the per-proxy
answer. The page has three sections: the proxies on the tile (a checkbox per
saved proxy), what a click starts while the tile is off, and what a click stops
while it is on. Both directions are the proxies on the tile (the default), the
proxy in use, or every saved proxy, so "start them all, stop only mine" needs
no second tile. A proxy the tile does not name is left as it is. With nothing
on the tile a click starts the configuration the last start stored and stops
everything, which is what the tile did before it had a page. Android's long
press on a tile stays what it is — App info — because the page belongs in the
app, not behind a gesture that a user expects to explain the app.

A tile click is user interaction, so the tile may start a foreground service
from the background, but Android still refuses the `location` service type for
such a start unless the app may read location *in the background*. Grant
"Allow all the time" in the app's location settings (`adb install -g` does it
for a test build); without it the service cannot start from the shade at all
and says so in a notification, because a foreground service without a type is
not allowed for this target and a service that cannot read the SSID has nothing
to offer anyway. From the panel the same start works either way, since the app
is visible.

```sh
adb shell cmd statusbar add-tile org.phonelink.proxy/.ProxyTile
adb shell cmd statusbar expand-settings
adb shell cmd statusbar click-tile org.phonelink.proxy/.ProxyTile
```

`click-tile` is a no-op while the shade is closed, so expand it first; to check
the result, `dumpsys activity services org.phonelink.proxy | grep isForeground`
should show `isForeground` with the `specialUse` type, and `curl` through the
port should answer `200`.

The panel's other checkbox, "Start automatically and keep running", stores
`auto_start`. With it on, the app wants the service up all the
time: `START_STICKY` keeps it alive, the boot receiver starts it after a
reboot, and the SSID gate opens and closes the port as the phone comes and goes
from the AP, so the tile is not needed in the common case.

A restart after a reboot needs **two independent gates**, and on this phone
both default to closed:

1. **DokeOS Auto Start Management** — System Manager → App Management → Auto
   Start Management, with "Phone link proxy" enabled. Without this the vendor
   marks the package stopped at boot: the boot receiver never runs, so no app
   code gets a chance to start the service.
2. **The battery-optimization exemption** —
   `adb shell cmd deviceidle whitelist +org.phonelink.proxy`. This is the
   exemption that start needs on Android 15: without it the log says
   `Background started FGS: Disallowed`. It cannot help if gate 1 kept the
   receiver from running.

The panel reports and asks for gate 2 only: the line under the auto-start
checkbox says whether the exemption is granted, tapping it opens the system
request, and pressing Start with auto-start on asks when it is missing. That
line is drawn red and bold while auto-start is on and the exemption is missing,
and as an ordinary small line once the checkbox is off — only a promise the app
cannot keep is worth a colour.

A second line, directly under it, is the DokeOS gate and is never reported as
clear: the vendor list is behind
`com.freeme.freemanager.permission.ACCESS_INTERFACE`, so neither the app nor
adb can read it. The line says the list is unreadable and names the screen and
the two drivers (`wireless-adb-keeper/autostart-allow.sh "Phone link proxy"`,
`phone-link-tools/autotoggle`). When auto-start is on and no boot broadcast
reached the app for the current boot, it says **likely blocked this boot** and
dates the observation — a suspicion from the missing broadcast, not a read of
the list. `stopped=true` is what a clean install shows too and is not used as
evidence. The red header mark stays the battery gate's alone, so a granted
exemption still does not promise a restart.

The adb path has the same switch, and gate 2 can be granted without touching
the phone:

```sh
adb shell am start-foreground-service -n org.phonelink.proxy/.ProxyService \
    -a org.phonelink.proxy.action.START --ez auto_start true

# gate 2 only: this does not add the app to DokeOS Auto Start Management
adb shell cmd deviceidle whitelist +org.phonelink.proxy
```

### Coming back after a reboot

Both gates, then one reboot to prove it. The DokeOS list has no readable or
writable API — it needs a signature permission — so it is a screen on the
phone, not an adb command:

1. Enable "Phone link proxy" in System Manager → App Management → Auto Start
   Management. If that screen has been driven before on this workspace,
   `wireless-adb-keeper/autostart-allow.sh "Phone link proxy"` (adb +
   uiautomator, phone unlocked) or `phone-link-tools/autotoggle` (accessibility
   service) opens it and flips the row; both still need their one-time manual
   or accessibility step.
2. `adb shell cmd deviceidle whitelist +org.phonelink.proxy`
3. Reboot, then leave the phone alone until `adb connect` works again.
4. Check that the service is up and the port is open:

```sh
PORT=1081   # the port this proxy listens on
adb shell pidof org.phonelink.proxy
adb shell dumpsys activity services org.phonelink.proxy | grep isForeground
curl -sS -o /dev/null -w '%{http_code}\n' \
    --socks5-hostname proxy:"$PHONE_SOCKS_PASS"@192.168.77.232:"$PORT" https://example.com
```

A pid, `isForeground` in the dump and `200` from the curl are the restart
working. A blocked boot is the opposite: no pid, no service record, no
`phone-link-proxy` line in `adb logcat -d`, and the receiver runs only when the
app is opened. If gate 2 is granted and the boot was still blocked, gate 1 is
the one to check; DokeOS logs it as
`BVPFWLogger: AppAutoStartupPolicy: prevent start receiver of package` naming
the app. `dumpsys package org.phonelink.proxy | grep stopped` reads
`stopped=true` for a package DokeOS kept from starting — but also for one that
has never been opened, so read it after a first launch and together with the
missing boot log line.

Measured for gate 1 alone on the throwaway direct-boot probe kept beside this
checkout (`../direct-boot-probe`, same phone): without the entry the package
read `stopped=true` at every boot,
its process never appeared in a locked window, and that vendor line was in the
log; with the entry, `LOCKED_BOOT_COMPLETED` arrived about 27 s after boot
(`receiversSize:11`, up from 1). This app's own restart was measured with both
gates on — see below. On 2026-11-24 a third app,
`io.github.mbanucu.wirelessadbkeeper`, showed the late variant on this phone:
its boot broadcasts were enqueued at +238 s, at the instant the app was opened
(`process_started_after_boot_s=238`).

Wireless debugging is the other half of that. This phone clears
`adb_wifi_enabled` on some reboots, and while it is off nothing advertises the
port the laptop needs — the old way back was Developer options → Wireless
debugging, which asks for a human on the phone. The checkbox "Turn wireless
debugging back on when the phone joins the SSID" (`adb_wifi`, on by default)
has the app write that setting whenever the uplink gate opens, which is the same
write `phone-adb-wifi` makes over adb. A normal app cannot write a secure
setting, so it needs the one permission adb can hand out:

```sh
# phone-proxy-install runs this after installing this APK
adb shell pm grant org.phonelink.proxy android.permission.WRITE_SECURE_SETTINGS
```

The panel is two screens: home is the list of saved proxies, and one of them
opens for its settings and its log. That page is split into three segments —
`Listener` (the port, the connection ceiling, the credentials, the SSID and
the two listener boxes),
`Service` (auto-start, wireless debugging, the opt-in Wi-Fi lock, and what the
phone says about each)
and `Log` (this run's counters and that proxy's events) — so nothing has to be
scrolled past to reach the next thing. `Back` (or the arrow in the header)
returns to the list.

The title bar is gone. Its only content was the app's own name, so the layout
carries a header row of icons instead: the name of the list (or of the open
proxy with a back arrow), a warning mark that appears while auto-start is on
and the battery exemption is missing (tapping it opens the battery request,
from either screen; it cannot see the DokeOS list, so a blocked boot has no red
mark — the separate Auto Start Management line carries the dated suspicion
when the app can see one), an `i` for the app's own Info, and the burger. The menu holds
`New proxy`, `Save`, `Save as…`, `Rename`, `Delete` for the open one,
`All events`, and `About this app` — the same Info the header's `i` opens. The open proxy carries
a plain `Save` next to `Revert`, and `Save as…`/`Rename`/`Delete` stay in the
menu so the form is not crowded.

Every control explains itself: the small `i` next to a field, a box, `Save`,
`Revert`, the one button under the segments, `New proxy` and the log's `Clear`
opens a dialog with what that control is for. The marks are the same
information the Info dialog carries, one control at a time, so a first run does
not need the whole contract.

The list is the app: one card per saved proxy with its name, its state —
`Running` in green, `Waiting` while the service is up but the network does not
match, `Stopped` otherwise — and what it holds (`port 1081 · SSID PhoneLink ·
user proxy`, then `auto-start on · wireless debugging on`), never the password.
The card of the one the service is using says `in use · …` and follows the
service as clients come and go. With nothing saved the page says so, and
`New proxy` is the way in either way: it copies the configuration in use — same
credentials and network — takes the next free port, and opens the result, so
the wizard is the opened proxy itself. Only the cards scroll: `New proxy` is
pinned under them, because with a dozen saved proxies a button at the end of
the list is a dozen cards away (measured with ten: the button stays on screen
and the eleventh proxy is created without scrolling). A name that already
exists is refused rather than overwritten; `Save as…` is the way to make a copy
on purpose.

That shape is also where several proxies will report into once the service can
run more than one at a time: a card's state is already that proxy's own state,
and the list is rebuilt from the store. Today exactly one can be running, and
the card of the one that is says so.

There is one button instead of two: it says `Start` while that proxy is not the
one running and `Stop` while it is, on the card and on the open proxy alike. On
the card it starts or stops that proxy without opening it and without asking
for the battery exemption; on the open page it stores the form into the proxy
first and then starts it, so the proxy that runs is the one that was saved and
the list marks the right card. A name is only a label, so `Rename` moves the
settings, the service switches and the mark of which proxy is in use with it.

Each proxy keeps its own log: the last 200 things that happened to it, newest
first with a time — the state changes (`listening on 0.0.0.0:1081 · 0
clients`, `not listening: on "FRITZ", not "PhoneLink"`), its settings being
saved, wireless debugging being turned back on, and failures with their
reason. Events are tagged with the proxy they belong to, by the service while
it runs that proxy and by the panel when it writes about a named one; `All
events` in the menu shows everything, whatever it was about. The log lives in
memory and is cleared with a tap, because the complete record is still
`adb logcat -s phone-link-proxy`.

Above a proxy's events are the traffic counters for the current run — `↓ 6.5 kB
· ↑ 1.9 kB · now 0 B/s down, 0 B/s up` — and a sparkline of the last minute in
one-second steps, one line per direction, hidden while nothing has moved. The
bytes are counted in the relay where they are copied (`Socks5Server.pump`), the
panel samples them once a second while a proxy is open, and a start resets both,
so the page answers "what has this run moved" rather than "what has this phone
ever moved".

The Service page's switches follow the store, so `--ez auto_start …`,
`--ez adb_wifi …` or `--ez wifi_lock …` while the panel is open shows up there
without reopening it. Ticking wireless debugging stores it at once — it only
changes what happens on the next SSID match — while auto-start and the Wi-Fi
lock wait for `Save`, and a form that differs from the saved proxy says
`Not saved yet — Save writes this to the configuration`. `Revert` drops the edits and shows the store again.
Under the wireless-debugging checkbox a line says what Android is doing right
now (`Wireless debugging: on — adb can reach the phone`), and why the app cannot
help when the permission was never granted. Everything else on the page is
setup, which only takes effect on `Save` or `Start`: the same page exposes the
`anonymous` extra as "Listen without authentication". Start is the filled
button because it is the one that does something.

The panel also explains itself: the `i` in the header — and `About this app` in
the menu — opens a dialog with what the app is, when the port opens, what every
control does and the whole adb contract, and each control's own `i` explains
that one control. The same text is the service's own `dumpsys` dump,
followed by what it is doing right now, so an agent reads the contract and the
facts on stdout without a screen:

```sh
adb shell dumpsys activity service org.phonelink.proxy/.ProxyService
```

A stopped service has no record to dump, so there the panel is the way in, or
`adb shell am start -n org.phonelink.proxy/.MainActivity --ez help true` opens
the same dialog on the phone. The few facts a check needs while the service is
stopped are still readable: every change to the store rewrites
`/sdcard/Android/data/org.phonelink.proxy/files/state.txt`, one `key value`
line each — `auto_start`, `adb_wifi`, `wifi_lock`, `port`, `ssid`,
`any_network`, `in_use`, `oem_autostart unreadable` —
so `phone-doctor` can say what auto-start is set to and what Android's battery
gate reports for a phone whose service is not running. It also carries a
non-secret saved-proxy index: `saved_index_version 1`, `saved_index_count <n>`,
then one line per saved proxy in the form
`saved_index <b64(name)> <port> <b64(user)> <anonymous 0|1> <auto_start 0|1> <b64(ssid)> <any_network 0|1>`
(Base64URL, standard `=` padding, `~` for an empty field), or
`saved_index none` when the count is 0. That lets a stopped service still
report the saved names and ports; no password, token or export document is in
the file. It also carries
`last_boot no_broadcast <epoch seconds>` only when the observation is worth
recording: no boot broadcast reached the app in the current boot and auto-start
is on. That line states the observation and the time the app noticed, not a
cause; the panel and `phone-doctor` turn it into the dated "likely blocked"
wording. It cannot see the DokeOS list, and the file holds no credentials or
guesses.

Measured on the phone (Android 15): with the switch on, `settings put global
adb_wifi_enabled 0` plus the phone re-joining `PhoneLink` ends with
`wireless debugging enabled (it was off)` in the log, the mDNS record back on a
new port and `adb connect` working again; with the switch off the log says
`wireless debugging left alone: the panel switch is off` and the setting keeps
the value it had.

Measured on the phone (Android 15; Blackview XPLORE 2, DokeOS 4.2): with the
app already allowed in Auto Start Management, taking only the battery exemption
away makes the receiver log `Background started FGS: Disallowed`, leaves the
service record behind with `app=null`, and no port opens. With the exemption
back, the service comes back at boot — `isForeground` with the `specialUse`
type, `uplink allowed: on "PhoneLink"`, `listening on 0.0.0.0:1081` — and a
request through it answers `200`. The DokeOS gate was measured separately; that
measurement is in the reboot checklist above.

A reboot also rotates the wireless-debugging port, so adb needs the new one.
The phone advertises it over mDNS, which Avahi reads in about a second — much
faster than scanning 32768–60999, where an aggressive scan can also miss it:

```sh
ADDR=$(avahi-browse -rtp _adb-tls-connect._tcp 2>/dev/null |
    awk -F';' '$1=="=" && $3=="IPv4" {print $8":"$9; exit}')
adb connect "$ADDR"
```

Check it:

```sh
adb shell dumpsys activity services org.phonelink.proxy | grep isForeground
curl -sS -o /dev/null -w '%{http_code}\n' \
    --socks5-hostname proxy:"$PHONE_SOCKS_PASS"@192.168.77.232:1080 https://example.com
```

`adb logcat -s phone-link-proxy` reports the bound port, refused credentials and
every CONNECT that had no cellular network to use.

## Several at once

Every saved proxy carries its own port, credentials and SSID, and the service
holds a listener per port rather than one listener: `START` adds, and a start
on a port that is already taken replaces what held it. So two proxies can serve
at the same time, each with its own gate on its own network and its own
counters:

```sh
adb shell am start-foreground-service -n org.phonelink.proxy/.ProxyService \
    -a org.phonelink.proxy.action.START --es profile laptop
adb shell am start-foreground-service -n org.phonelink.proxy/.ProxyService \
    -a org.phonelink.proxy.action.START --es profile hotel
```

`STOP` alone stops them all; `STOP --es profile NAME` stops that one and leaves
the rest listening, and `STOP --ei port N` stops whatever holds that port. A
button in the panel means one proxy, never all of them: `Start` on a card or on
a page starts that proxy and `Stop` stops that proxy — a page whose proxy is not
running says which of the two a start would be, `Stopped — Start adds this one
beside the others`, or `Stopped — port 18081 is another proxy's; Start replaces
it` when a listener already holds its port. `Start` also marks that proxy *in
use*, the mark on its card; a restart at boot starts that proxy, and the tile
starts it only while the tile names nothing. The panel's `i` marks say so.
`dumpsys` lists every running listener with its port, its bytes and its clients,
then the counters of all of them; the notification names them (`2 proxies:
"laptop" on 1081, "hotel" on 1082`), and the panel's list marks every card that
is running rather than only the last one started. The tile speaks for the ones
its settings name: a click starts them while none of them is up and stops them
while one is, and a proxy it does not name keeps doing what it was doing.

The counters are per listener, so a proxy's Log page answers "what has *this*
proxy moved" and a start begins at zero. The wake lock is held while any of them
has a client.

## Settings

Read from the intent on start and kept in the app's private, non-backed-up
preferences. They are device-protected, so the boot receiver can read them
before the first unlock — the same trade-off as the pre-unlock listener: the app
can read the credentials while the device is locked. The `state.txt` file adb
can read holds no credentials; an export you choose to write does, in plain
text.

| extra | default | meaning |
|---|---|---|
| `port` (`--ei`) | `1080` | listen port, `1024..65535` |
| `user` (`--es`) | — | RFC 1929 username, required unless `anonymous` |
| `password` (`--es`) | — | RFC 1929 password, required unless `anonymous` |
| `anonymous` (`--ez`) | `false` | listen without authentication |
| `ssid` (`--es`) | `PhoneLink` | SSID the listener may run on |
| `any_network` (`--ez`) | `false` | ignore the SSID gate and listen on any connected network; not a transport mode, and still authenticated unless `anonymous` |
| `auto_start` (`--ez`) | `false` | come back after a reboot and stay up (the SSID still gates the port) |
| `wifi_lock` (`--ez`) | `false` | opt-in experiment: hold a Wi-Fi lock while a Wi-Fi listener is bound; costs battery and does not promise to fix #11 |
| `adb_wifi` (`--ez`) | `true` | turn wireless debugging back on when the phone joins the SSID (needs `WRITE_SECURE_SETTINGS`) |
| `profile` (`--es`) | — | the saved configuration to start from; the extras above are applied on top of it |

Every extra is persisted, so a later start without one reuses the stored value;
pass it explicitly to change a setting.

A configuration the service already has can be kept under a name, which is what
the panel's `Save as…` does and what an agent does without a screen:

```sh
adb shell am start-foreground-service -n org.phonelink.proxy/.ProxyService \
    -a org.phonelink.proxy.action.SAVE_PROFILE --es profile hotel
```

Saving does not disturb a running listener. `dumpsys` lists what is saved with
the service switches each one holds, the panel's `Saved` page shows the same names,
and `START --es profile hotel` starts that configuration again later, on any
port or SSID the same command adds on top. An unknown name starts nothing and
logs `no saved configuration called "hotel"`; a name that already exists is
replaced, which is the "save again" the panel asks about and adb does not.
Removing one is the same shape, and also does not disturb a running listener:

```sh
adb shell am start-foreground-service -n org.phonelink.proxy/.ProxyService \
    -a org.phonelink.proxy.action.DELETE_PROFILE --es profile hotel
```

Passing the credential on the `am` command line puts it in the shell process's
argv for the moment the command runs — the same exposure as any `adb shell`
argument, and the reason `phone-link` keeps it in the root-only secrets file
rather than the Nix store. An unauthenticated listener is off by default:
`anonymous=true` removes the password check, and a wildcard listener on a Wi-Fi
LAN is then an open relay.

### Taking them off the phone, and putting them back

The menu's **Export every proxy…** writes all of it — every saved proxy with its
port, SSID, user, password, `anonymous`, `any_network`, `auto_start`,
`adb_wifi`, `wifi_lock` and tile mark, plus the configuration in use and the
two names that
mark it — to one file the system picker chooses. **Import proxies…** reads such a
file back and asks whether to replace every proxy on the phone or to merge by
name. Nothing is written until the file has been read and understood, so a file
that is not one of ours says so instead of leaving a half-imported store.

This is the answer to the two ways a phone loses them: changing the signing key
(`phone-proxy-install --replace-signer` uninstalls first) and a lost or reset
phone. The laptop does it without the picker:

```sh
adb shell am start-foreground-service -n org.phonelink.proxy/.ProxyService \
    -a org.phonelink.proxy.action.EXPORT --ei port 47123 --es token 6f3c…
adb forward tcp:0 tcp:47123          # prints the laptop's port
# then, on 127.0.0.1:<that port>:
#   client → PLX1 <token>\n
#   app    → OK <n>\n + n bytes of the JSON above
```

`IMPORT` is the same socket with the document going the other way: the laptop
sends `PLX1 <token>\nPUT <n>\n` and the body, and the app answers
`OK <count>\n`. Errors are `ERR <code> <message>\n` with `bad-token`,
`too-large`, `bad-document`, `busy`, `timeout` or `io`. The app binds
`127.0.0.1` only, accepts exactly one connection, gives it 60 seconds, compares
the token in constant time and then closes the socket and the action. An
unknown action or bad extra fails closed instead of falling through to a start.

The gate is the screen: **both actions only run while the screen is on and the
keyguard is not locked** (`PowerManager.isInteractive()` true and
`KeyguardManager.isKeyguardLocked()` false). A phone with the screen off or a
locked keyguard binds nothing and answers with a notification and a log line,
so the laptop's connect fails — that is the intended answer, not a bug. The
document is at most 1 MiB, at most
256 saved proxies, and every entry is validated as a start would validate it
(port `1024..65535`, connection ceiling `16..1024`, user and password unless
`anonymous`, an SSID unless `any_network`, a non-empty name of at most 255
bytes) before the first write. A bad document applies nothing; an import
replaces the store, like the menu's *replace*, so it is not a merge.

No export file, cache or document is written on the phone: the ordinary,
credential-free `state.txt` the service already maintains is still updated, but
the document only crosses the loopback socket. Error messages on the wire are
generic so a parser message cannot quote a line of the file, and the app never
logs document contents or the token. (The token is on the `am` command line by
design; it is one-shot and useless after the connection. A password never is.)

The residual risk is adb itself: anyone who can run `adb shell` on the phone
while the screen is on and unlocked can already start a listener, read the app's
preferences through a debug build, or use the panel. This adds no new principal;
it removes the plain-text file on the phone and the hand-typed export. Locking
the screen and turning it off before walking away is what closes the adb path,
exactly as it does for `START`.

The format is JSON, `{"app": "phone-link-proxy", "version": 1, "active": …, "saved": […]}`,
written field-per-line by `ProfileDocument` and read by the same class, so it is
unit-tested on a JVM. A missing field keeps its default, unknown fields are
ignored, and a file whose `version` is newer than the app is refused with the
reason. The file holds passwords in plain text: the panel says so before it
writes one, and the app never puts it anywhere by itself.

## Which build is this

The current release is 0.12.0. The APK's `versionName` is this repository's
`version` and its `versionCode`
is `major*10000 + minor*100 + patch`, stamped into a copy of
`AndroidManifest.xml` by `build.sh` from that file. The tracked manifest keeps a template
(`0.0.0`), so a checkout that was never rebuilt does not claim a release, and
`nix flake check` builds it, and the release workflow refuses to publish an APK
carrying the debug certificate.
`dumpsys package org.phonelink.proxy | grep versionName` answers which release
the phone runs, the release asset is named after the same number, and
`phone-doctor` warns when a newer tag exists (a cached, one-a-day GitHub call;
offline it says the check is unknown instead of guessing).

## What this scaffold does not do yet

- Tethering is not a first-class transport. By default the SSID gate keeps the
  port closed on hotspot and USB tether; `any_network` opens the same wildcard
  listener there, but egress is still cellular and the laptop's `proxyHosts`
  still has to reach the phone (a tether address changes per connection). Use
  the vendored proxy for hotspot mode.
- A boot restart needs two gates, not one (see `Run`): the app enabled in
  DokeOS Auto Start Management — a signature-protected screen the app cannot
  read or write — and the battery-optimization exemption. Missing the second,
  Android 15 refuses the foreground-service start
  (`Background started FGS: Disallowed`); missing the first, the package is
  stopped at boot and the receiver never runs at all. The panel can check the
  second and can only report the first as unreadable, or as a dated "likely
  blocked this boot" when no boot broadcast arrived; it never claims the vendor
  list is open. Either way the listener stays off until the next explicit start
  or the tile.
- The laptop repository's `phone-proxy-*` scripts that still talk to the old
  app's screen and its `Start 5G Proxy` / `Stop Proxy Service` buttons are its
  own to retire; this app's adb contract is the replacement.
- `BIND` is refused, and there is no other `dial` language beyond SOCKS5.
  `CONNECT` and `UDP ASSOCIATE` are implemented, so TCP and UDP (QUIC
  included) can leave through the tunnel; a client that only speaks `CONNECT`
  still cannot send UDP through it. UDP is relayed only for a client that uses
  the SOCKS5 UDP path: `UDP ASSOCIATE` plus the SOCKS5 UDP datagram header
  (`RSV RSV FRAG ATYP DST.ADDR DST.PORT DATA`). Each association is pinned to
  the first datagram's source address and port, uses the same cellular egress
  as `CONNECT`, drops fragments and malformed datagrams, and is bounded in
  datagram size and queue depth.

## Licence

GPL-3.0-or-later: the app is free software, and a modified copy that someone
distributes has to be free software too. `LICENSE` is the licence's own text,
fetched from <https://www.gnu.org/licenses/gpl-3.0.txt> rather than retyped;
`build.sh`, the resources and the tests are all covered by it.

## F-Droid

`fdroid/` holds the metadata entry F-Droid's build server reads
(`org.phonelink.proxy.yml`, current release 0.12.0) and `fdroid/README.md` the
reasoning behind each field, how to submit it, and how to add the next
release's entry. Nothing there is shipped in the APK.
