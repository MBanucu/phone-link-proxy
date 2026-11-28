# The build shell and the APK derivation: a JDK, an Android
# SDK with build-tools and one platform, and zip. Kept out of flake.nix so the
# flake stays wiring and the SDK (which is large) stays in a shell of its own.
#
# The Android SDK license is per-evaluation configuration, not a package
# argument, so nixpkgs is imported again here instead of reusing
# nixpkgs.legacyPackages.
{ pkgs, nixpkgs }:
let
  androidPkgs = import nixpkgs.outPath {
    system = pkgs.stdenv.hostPlatform.system;
    config = {
      android_sdk.accept_license = true;
      # Google's SDK components are unfree under several names (cmdline-tools,
      # platform-tools, build-tools, the platform zips), so this private nixpkgs
      # instance allows unfree rather than keeping a list that changes with the
      # SDK version. Nothing from here leaks into the other shells.
      allowUnfree = true;
    };
  };
  sdk = androidPkgs.androidenv.composeAndroidPackages {
    # Google Play refuses a new app or an update that targets less than API 36
    # since 2026-08-31, and the AAB is linked against the same platform.
    buildToolsVersions = [ "36.0.0" ];
    platformVersions = [ "36" ];
    includeCmake = false;
    includeEmulator = false;
    includeNDK = false;
    includeSources = false;
    includeSystemImages = false;
  };
  sdkRoot = "${sdk.androidsdk}/libexec/android-sdk";
in
{
  inherit sdkRoot;

  shell = pkgs.mkShell {
    packages = [
      androidPkgs.jdk17
      androidPkgs.zip
      # The AAB path: aapt2 links the module in proto format and bundletool
      # packs it. Not used by the four APKs.
      pkgs.bundletool
    ];
    ANDROID_SDK_ROOT = sdkRoot;
    shellHook = ''
      export ANDROID_HOME="$ANDROID_SDK_ROOT"
      echo "android: $ANDROID_SDK_ROOT"
      # The flake's build sees only the git tree: a new source file that nobody
      # added is missing from it, and the "cannot find symbol" errors that follow
      # read as a bug in the code just written.
      if command -v git >/dev/null 2>&1; then
        untracked=$(git ls-files --others --exclude-standard 2>/dev/null |
          grep -E '\.(java|xml)$' || true)
        if [ -n "$untracked" ]; then
          printf '\nnote: untracked files are invisible to nix build:\n%s\n\n' "$untracked" >&2
        fi
      fi
    '';
  };

  # Compiled by `nix flake check`, and available as packages.<system>.proxy-apk.
  # The sandbox copy has no build/ directory, so build.sh generates a throwaway
  # dev key at app/build/dev.keystore (mode 600, password `android`) on every
  # build: the artifact is valid but not reproducible. That is the local/test
  # path only; a release build passes --release and PHONE_APP_KEYSTORE.
  apk =
    pkgs.runCommand "phone-link-proxy-apk"
      {
        nativeBuildInputs = [
          androidPkgs.jdk17
          androidPkgs.zip
        ];
        ANDROID_SDK_ROOT = sdkRoot;
      }
      ''
        export HOME=$TMPDIR
        cp -r ${./.} app
        chmod -R u+w app
        ./app/build.sh
        install -Dm644 app/build/phone-link-proxy.apk $out/phone-link-proxy.apk
        # The same app under org.phonelink.proxy.test: what a live run installs,
        # so testing an unstable build never replaces the proxy the phone lives
        # on. A phone can hold both at once.
        install -Dm644 app/build/phone-link-proxy-test.apk $out/phone-link-proxy-test.apk
        # The test-only accessibility service the live harness takes its dumps
        # from, and the test-only instrumentation that runs the panel's checks
        # inside the proxy's process; nothing installs them but a test run.
        install -Dm644 app/build/capture.apk $out/capture.apk
        install -Dm644 app/build/devicetest.apk $out/devicetest.apk
      '';

  # The Google Play artifact. AABs are built from the same compiled code and
  # resources as the APK; only the packaging and the signing differ (Play
  # requires a JAR signature, so build.sh uses jarsigner for this one).
  aab =
    pkgs.runCommand "phone-link-proxy-aab"
      {
        nativeBuildInputs = [
          androidPkgs.jdk17
          androidPkgs.zip
          androidPkgs.bundletool
        ];
        ANDROID_SDK_ROOT = sdkRoot;
      }
      ''
        export HOME=$TMPDIR
        cp -r ${./.} app
        chmod -R u+w app
        ./app/build.sh --bundle
        install -Dm644 app/build/phone-link-proxy.aab $out/phone-link-proxy.aab
      '';
}
