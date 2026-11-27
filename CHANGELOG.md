# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- The cellular egress is now selected and guarded explicitly (`EgressPolicy`).
  With the phone's default network a validated Wi-Fi/AP and cellular available,
  the proxy still picks the cellular link, and the JVM suite covers that
  hairpin without a phone. If the resolved egress is ever not cellular,
  `Egress.connect` refuses the request with one clear log line instead of
  opening a socket that would follow the default route back into the laptop.
  `dumpsys` prints the result as `egress cellular network N on <iface>` so a
  live check can assert it (phone-link#45).

## [0.13.2] - 2026-11-26

### Fixed

- A finished connection is closed within a second (`SO_LINGER` 1 s) instead of
  possibly leaving an orphaned `FIN_WAIT1`/`LAST_ACK` socket behind. On the
  phone those orphans never expire on their own, and a new connection that
  reused the same 4-tuple was answered with a challenge ACK instead of a
  SYN-ACK, so it hung until the client's timeout: 5 of 300 bare connects to
  the listener, 5 of 5 of them reproducing on the same source port, while a
  fresh port connected in 0.1 s (phone-link#32).

## [0.13.1] - 2026-11-26

### Fixed

- A CONNECT whose cellular handshake is lost is retried once with a fresh socket
  after 4 s instead of waiting out 15 s; a refused connection is not retried. The
  cellular egress now shares one timeout budget across a host's addresses. This
  is the ~1–4% of new connections that made pages stall for 5 s (#42).

## [0.13.0] - 2026-11-26

### Added

- The listener's connection ceiling is configurable (`max_connections`, 16..1024,
  default 256) through the panel, the profile documents and adb. Two threads per
  live connection mean a higher ceiling costs memory, and the selector relay
  that removes that cost is #37.

## [0.12.3] - 2026-11-26

### Changed

- The connection ceiling is 256 instead of 128. A desktop browser alone held
  about a hundred keep-alive flows; Chrome plus a handful of tools reached the
  128 limit, so the next refusal hit ordinary desktop use. The thread cost stays
  two per live connection (514 threads at the ceiling), inside the phone's
  process limit (#37).

## [0.12.2] - 2026-11-26

### Fixed

- The SOCKS5 relay is reaped when one direction half-closes: the other gets a
  10 s linger before both sockets close, so a client FIN no longer pins a
  handler until the whole tunnel is restarted. The ceiling is 128 concurrent
  connections at two threads each (handler plus one relay thread) instead of 32
  at three, and a client over the ceiling is refused immediately rather than
  queued (#33).

## [0.12.1] - 2026-11-26

### Fixed

- `Egress` asks for a cellular network again after `onUnavailable`, after the
  network is lost and after it loses `NET_CAPABILITY_VALIDATED` or
  `NET_CAPABILITY_INTERNET`, with a bounded backoff (1 s, 2 s, 5 s, 10 s, then
  every 30 s) while the service runs. `describe()` says why there is no usable
  cellular egress — unavailable, lost or waiting — and a CONNECT that times out
  names cellular egress instead of failing silently until the service restarts.

## [0.12.0] - 2026-11-26

### Added

- An opt-in `wifi_lock` experiment for the access point that drops a phone
  with no uplink (#11): while a listener that needs Wi-Fi is bound, the service
  holds a `WifiLock` — `WIFI_MODE_FULL_LOW_LATENCY` on Android 14+,
  `WIFI_MODE_FULL_HIGH_PERF` before that — and releases it when the listener
  pauses, stops or the service goes away. It is off by default, is visible in
  `dumpsys` as `wifi_lock held|not held`, and is not a promised fix: Android 14
  only honours low latency with the screen on and the app in the foreground,
  and the lock costs battery. A listener started with `any_network` does not
  hold it.
- `docs/pre-unlock-autostart.md`: the four Android gates behind the DokeOS Auto
  Start Management list that keep an app from being started before the first
  unlock on this firmware, each with its symptom, the offending log line and
  the check that proves it. Also corrects the README,
  which claimed a `location` foreground service type was needed to read the
  SSID: that type cannot be started while locked, and the SSID reads without it.
- `fdroid/`: the metadata entry for F-Droid's build server, in the shape
  `fdroid rewritemeta` produces, lint-clean under `fdroid lint`, with a README
  that says why `debug.keystore` is scandeleted, why the commit is a full hash
  and why each release is a hand-added `Builds` block. Submitting it needs a
  GitLab account.

### Changed

- The Wi-Fi gate follows the network that actually matches the configured SSID.
  The service keeps a map of connected Wi-Fi networks from the connectivity
  callbacks instead of calling `getAllNetworks()` inside them, uses
  `FLAG_INCLUDE_LOCATION_INFO` on Android 12+, pauses when more than one
  network matches, and re-checks the gate immediately when the user unlocks.
- A release is published only when its tag names `version`, the flake checks
  pass, and (when `ANDROID_CERT_SHA256` is set) the APK carries the expected
  release certificate.

### Fixed

- Stopping a listener closes its accepted client sockets and relay pumps, not
  just the listening socket. Active tunnels end, and late callbacks cannot
  re-post a notification after the service is gone.
- The partial wake lock and the Wi-Fi lock are released in `onDestroy`, and the
  client count is synchronized so a stop cannot release a wake lock while a
  client is still connected.
- `Socks5Server` closes the socket when `bind()` fails, and the accept loop no
  longer has a null-server race. The relay pool is bounded at 32 concurrent
  connections.
- The tracked `debug.keystore` is gone; `build.sh` generates a per-checkout dev
  key under `build/` and preserves it across rebuilds.

## [0.11.1] - 2026-11-17

Nothing in the app behaves differently; the source says what it may be used
for, which is what F-Droid's inclusion asks for first.

### Added

- `LICENSE`: GPL-3.0-or-later, the licence's own text from gnu.org rather than
  a retyping, with a README section naming it and what a distributed fork has
  to do. The repository had no licence at all, so nobody could tell whether a
  copy was allowed.

## [0.11.0] - 2026-11-17

### Added

- A launcher icon of its own: an adaptive icon of a white rounded phone with an
  arrow leaving it, on the panel's purple, drawn inside the safe circle. Until
  now a launcher had nothing but the status-bar glyph, so the app sat in the
  drawer as a grey square with a bell in it. `ic_stat_proxy` is still what
  notifications and the tile use.
- Screenshots for the store listing: the list with a proxy running, the Listener
  page, the Service page and the Log page with a 44 MB download moving through
  it, taken on the test build with throwaway proxies, plus the 512 px
  `images/icon.png`. F-Droid and Play can now show what the app looks like.

## [0.10.0] - 2026-11-17

The first release of the app as its own repository, at the version it carried
in the laptop project's tree.

### Added

- `flake.nix` of its own: the `nix develop` shell with the Android SDK, the
  `proxy-apk` package, and three checks (`apk` compiles and signs, `unit` runs
  the Android-free logic on a plain JDK, `format` keeps the two nix files
  nixfmt-clean). Nothing here needs the laptop project to build.
- `fastlane/metadata/android/en-US/` — title, short and full description, and a
  per-versionCode changelog — so a store listing has its text in the tree the
  build comes from.
- The live harness moved in with the app: `tests/live/proxy-app.sh` and the
  tools it uses (`phone.sh` for the phone, `clock.sh` and `frames.sh` for the
  recording, `pictures.py` for a failed check's frames, `remux.sh` for the
  fast-start pass, `results.py` for the history), with their hermetic tests
  beside them. It builds and installs `build/phone-link-proxy-test.apk` itself,
  so a run leaves the proxy the phone lives on alone.
- The release workflow moved in: a `v*` tag builds the tagged tree with the
  repository's release key, reads the certificate back, refuses to publish a
  debug-signed APK, and attaches `phone-link-proxy-<version>.apk` and its
  `.sha256`.
- `default.nix` fails the build when `version` is not X.Y.Z and keeps `build/`
  out of the tree, as before.

### Changed

- The unlock is no longer this repository's to keep: the harness reads
  `PHONE_UNLOCK` (the laptop project's root-only `bin/phone-unlock`) and skips
  the steps that need an unlocked screen when it is not set, instead of
  assuming a path in another checkout.
- `version` is the one source of the app's version. The laptop project's flake
  used to compare it with its own; it no longer has one to compare.
