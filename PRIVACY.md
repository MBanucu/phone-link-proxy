# Privacy policy — phone-link proxy

Effective 2026-11-28. This policy covers the Android app **phone-link proxy**
(`org.phonelink.proxy`), published by MBanucu.

## The app does not collect or send your data

phone-link proxy has no analytics, no advertising, no crash reporting, no
accounts and no third-party SDKs. It does not send any information about you or
your device to the developer or to anyone else.

The app is a local SOCKS5 listener. It accepts connections from the clients you
point at it (normally a laptop on the same network or USB link) and relays those
connections through the phone's active network. The app does not inspect, store
or report the contents of that traffic.

## What is stored, and where

Everything the app keeps stays on the device, in the app's private storage:

- the proxy profiles you create: port, maximum connections, username, password,
  SSID, and the auto-start and network settings;
- the app's own log lines and traffic counters for each profile;
- the app's settings in the app's private preferences.

Credentials are kept in device-protected, non-backed-up private preferences.
Android backup does not copy them off the device. An export file is only
created when you explicitly export, is written wherever you choose, and is
plain text; deleting it is up to you.

Uninstalling the app removes this data. A signing-key change on a phone that
already has a locally built copy also requires an uninstall, which removes it
too; export the profiles first if you want to keep them.

## Permissions

- **Internet and network state**: to listen and to use the phone's cellular or
  Wi-Fi network.
- **Foreground service and notifications**: to keep the listener running and
  show its notification while it does.
- **Request ignore battery optimizations**: optional, only when you ask the app
  to keep the listener alive in the background.

The app does not request location, contacts, storage, camera, microphone or
accessibility permissions.

## Children

The app is a network tool for adults and is not directed at children.

## Changes

If this policy changes, the new version is committed to this repository and the
date above is updated.

## Contact

Questions about this policy: michael.banucu@googlemail.com
