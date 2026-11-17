{
  description = "phone-link proxy: the SOCKS5 listener the laptop's tunnel talks to";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  # The Android SDK is unfree and this flake is its only user, so default.nix
  # imports nixpkgs again with the license accepted rather than making every
  # evaluation of the flake carry that configuration.
  outputs =
    { self, nixpkgs }:
    let
      inherit (nixpkgs) lib;
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      eachSystem = f: lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
      android = pkgs: import ./default.nix { inherit pkgs nixpkgs; };
    in
    {
      packages = eachSystem (
        pkgs:
        let
          app = android pkgs;
        in
        {
          # The four APKs build.sh writes: the app, its test build, the capture
          # service the live harness reads the screen from and the
          # instrumentation that runs the panel's own checks.
          proxy-apk = app.apk;
          default = app.apk;
        }
      );

      checks = eachSystem (
        pkgs:
        let
          app = android pkgs;
        in
        {
          # Compiling is a check: a break in src/ fails `nix flake check`
          # instead of waiting for a manual build.
          apk = app.apk;

          # The Android-free logic on a plain JDK: the SOCKS5 protocol, the
          # configuration rules, the state text and the profile document.
          unit = pkgs.runCommand "phone-link-proxy-unit" { nativeBuildInputs = [ pkgs.jdk17 ]; } ''
            export HOME=$TMPDIR
            JAVAC=${pkgs.jdk17}/bin/javac JAVA=${pkgs.jdk17}/bin/java \
              bash ${self.outPath}/tests/proxy-app.test.sh
            touch $out
          '';

          # The harness's own tools, which need no phone: the estimate of a
          # run's remaining time, the CSV history importer and the planner that
          # picks a failed check's frames.
          harness =
            pkgs.runCommand "phone-link-proxy-harness-tests"
              {
                nativeBuildInputs = [
                  pkgs.bash
                  pkgs.coreutils
                  pkgs.python3
                ];
              }
              ''
                for suite in ${self.outPath}/tests/eta.test.sh \
                  ${self.outPath}/tests/results.test.sh \
                  ${self.outPath}/tests/pictures.test.sh; do
                  bash "$suite"
                done
                touch $out
              '';

          format = pkgs.runCommand "phone-link-proxy-format-check" { nativeBuildInputs = [ pkgs.nixfmt ]; } ''
            nixfmt --check ${./flake.nix} ${./default.nix}
            touch $out
          '';

          # The listing's icon is the rendering of artwork/launcher-icon.svg,
          # not a second drawing that can drift from it: edit the SVG without
          # re-rendering and this fails instead of shipping a stale picture.
          icon =
            pkgs.runCommand "phone-link-proxy-icon-check"
              {
                nativeBuildInputs = [
                  pkgs.coreutils
                  pkgs.librsvg
                ];
              }
              ''
                cp -r ${self} tree
                chmod -R u+w tree
                sh tree/artwork/render-icon.sh --check
                touch $out
              '';

          # The listing carries a changelog per versionCode (tags/versions are not
          # what F-Droid or Play read). A version bump without one is caught
          # here rather than at upload time.
          changelog =
            let
              version = lib.removeSuffix "\n" (builtins.readFile ./version);
              parts = map lib.toInt (lib.splitString "." version);
              code = toString (
                builtins.elemAt parts 0 * 10000 + builtins.elemAt parts 1 * 100 + builtins.elemAt parts 2
              );
              changelogs = builtins.readDir ./fastlane/metadata/android/en-US/changelogs;
            in
            assert lib.assertMsg (changelogs ? "${code}.txt")
              "no fastlane changelog for $version (versionCode $code): add fastlane/metadata/android/en-US/changelogs/${code}.txt";
            pkgs.runCommand "phone-link-proxy-changelog-check" { } ''
              test -s ${./fastlane/metadata/android/en-US/changelogs}/${code}.txt
              touch $out
            '';
        }
      );

      devShells = eachSystem (pkgs: {
        # librsvg renders artwork/launcher-icon.svg into the listing's icon; it
        # is in the shell so `artwork/render-icon.sh` works without a host copy.
        default = (android pkgs).shell.overrideAttrs (old: {
          nativeBuildInputs = old.nativeBuildInputs ++ [ pkgs.librsvg ];
        });
      });

      formatter = eachSystem (pkgs: pkgs.nixfmt);
    };
}
