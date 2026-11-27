# Working in this repository

Notes for whoever — person or agent — is changing this checkout.

## Where things are

- `build.sh` — the whole build: `aapt2`, `javac`, `d8`, `zipalign`,
  `apksigner`, no Gradle and no dependencies. It writes four APKs into
  `build/`: the app, its test build (`org.phonelink.proxy.test`), the capture
  service the live harness reads the screen from, and the on-device
  instrumentation.
- `src/`, `res/`, `AndroidManifest.xml` — the app. `test/` is the Android-free
  unit test, `capture/` and `devicetest/` are test-only.
- `tests/proxy-app.test.sh` — hermetic, plain JDK, no phone.
- `tests/live/` — the harness on a real phone (`proxy-app.sh` holds the steps
  and their claims, `phone.sh` how to look at and drive the phone). Needs a
  phone, the SDK, and installs a package; never run by `nix flake check`.
- `fastlane/metadata/android/en-US/` — the store listing's text and
  per-versionCode changelogs.
- `version` — the one source of the version. Bump it, add
  `fastlane/metadata/android/en-US/changelogs/<code>.txt`, then tag.

## Rules

- **Conventional Commits**, one change per commit: `feat(scope): …`,
  `fix(scope): …`, `test(live): …`, `docs: …`, `chore(release): …`. The footer
  is `Co-Authored-By: Codebuff <noreply@codebuff.com>` and nothing else.
- **No secrets in the repository.** A dev key is generated per checkout at
  `build/dev.keystore` (ignored) and is never what a release is signed with;
  `./build.sh --release` requires `PHONE_APP_KEYSTORE`, and the release
  workflow signs with the `ANDROID_*` secrets.
- **Never edit `tests/live/proxy-app.sh` while a run is executing.** It is read
  as it runs; a half-written file is a run that fails somewhere unrelated.
- **The phone is not a fixture.** It is somebody's daily uplink: the test build
  exists so a run never replaces the proxy the phone lives on, and a run puts
  back the settings it changed (accessibility service, autofill, screen
  timeout).
- **A speed claim needs a measurement.** Say what was measured and by how much,
  or leave the number out.
- **Leave the tree green.** `nix flake check` before committing.
