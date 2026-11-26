## Summary

<!-- What was wrong, and what is different now. One change per PR. -->

## Test evidence

<!-- The commands you ran and their result. Not "tests pass" — the output. -->

```console
$ nix flake check
...
```

## Phone impact

<!--
What this costs on a real phone: does it need the phone, does it restart the
listener, does installing it clear saved proxies or settings? Write "none" if
no phone was touched.
-->

## Linked issues

<!-- Closes #... / Refs #... -->

## Checklist

- [ ] `nix flake check` passes (it compiles the APK and runs the unit and harness suites)
- [ ] `nixfmt --check flake.nix default.nix` passes, and any shell changes pass `shellcheck -S warning`
- [ ] `README.md` updated for user-visible changes
- [ ] `version` and a fastlane changelog are updated together, if this ships
- [ ] No secrets, signing keys, real addresses or personal data added
