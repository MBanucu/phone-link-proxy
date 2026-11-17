# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
