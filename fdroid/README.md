# The F-Droid entry

`org.phonelink.proxy.yml` is the metadata entry for
[F-Droid](https://f-droid.org) — the file that tells their build server how to
build this app and where its source is. It lives here so that a change to the
build touches it in the same commit; the copy F-Droid reads is the one in
`metadata/` of their [fdroiddata](https://gitlab.com/fdroid/fdroiddata) repo.

It is not shipped in the APK, so it costs nothing to keep.

## Submitting it

F-Droid takes metadata through a merge request on GitLab:

1. Fork `fdroiddata` (a GitLab account is the one thing that cannot be prepared
   here), branch `org.phonelink.proxy`.
2. Copy this file to `metadata/org.phonelink.proxy.yml` in the fork, unchanged.
3. From a checkout of the fork, with `fdroidserver` installed — on this laptop,
   `nix run nixpkgs#fdroidserver -- lint org.phonelink.proxy` — the entry lints
   clean: no errors, no warnings. `fdroid rewritemeta org.phonelink.proxy` is
   what put the file in the shape above, so a reviewer's rewrite will not touch
   it.
4. Open the merge request. Reviewers run `fdroid build --server` and check the
   result; the first inclusion takes weeks, not days.

`fdroid build` locally needs a writable SDK, which a nix store path is not, so
the entry's `prebuild` line was checked by hand instead: `./build.sh` was run
under `env -i` with only JDK 17, `ANDROID_SDK_ROOT` and coreutils/findutils/
sed/gawk/zip on `PATH`, and produced the same 173 890-byte APK as the release.
That is what F-Droid's build server offers — Debian userland, no nix, and
`sdkmanager` to fetch the platform and build-tools it needs.

## The three decisions in it

- `License: GPL-3.0-or-later` is the licence in `LICENSE`, added in 0.11.1.
  F-Droid refuses an app whose source does not say what it may be used for.
- `commit` is the full hash of the release tag, not the tag name: F-Droid
  pins the commit so a moved tag cannot change what is being built.
- no `scandelete` or keystore entry: `build.sh` generates a throwaway dev key
  at `build/dev.keystore` on first use (mode 600, password `android`) and
  `build/` is not part of the source, so the tarball F-Droid publishes and
  scans carries no private key. Nothing tracked has to be deleted before the
  build.

Their build re-signs the APK with F-Droid's own key, so an install from
f-droid.org cannot update one from this repository's releases: Android refuses
an update over a different signature. `phone-proxy-install --replace-signer`
uninstalls first, which clears the app's saved proxies — export them before.

## Updating it for a release

`versionCode` is derived from `version` at build time (`0.12.0` → 1200) rather
than written into `AndroidManifest.xml`, whose `versionCode="0"` is a
placeholder. `fdroid checkupdates` reads the manifest in each tagged revision,
so it cannot see 1200 and would record 0 — which is why the entry sets
`AutoUpdateMode: None` and `UpdateCheckMode: None`.

The cost of that: each release is one more `Builds` block, added by hand with
the release's `versionName`, `versionCode` and full commit hash, plus
`CurrentVersion`/`CurrentVersionCode`. `nix flake check` already refuses a
`version` that drifts from the flake's, and `build.sh` refuses anything that is
not `X.Y.Z`, so the numbers to copy are the ones the tag carries; the commit is
`git rev-list -1 vX.Y.Z`.

The alternative — making `checkupdates` work — would mean stamping the real
versionCode into the manifest in every release commit, duplicating the `version`
file that is documented as the single source. Not done.
