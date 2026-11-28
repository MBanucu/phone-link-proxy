# App-driven rejoin: ruled out

**Verdict:** a normal `targetSdk 35` app cannot silently bring the phone back to
the `PhoneLink` AP after a disconnect, and cannot make that rejoin survive a
reboot. The only candidate is a `WifiNetworkSuggestion` approved once after the
saved network is forgotten. That needs user action up front, is not silent, and
is a feature prototype rather than a drop-in fix. The tracker issue is
[phone-link#39](https://github.com/MBanucu/phone-link/issues/39); this page
records the verdict against it.

This page records the platform contract. No phone experiment that changes the
phone's Wi-Fi was run; the issue's own question was about what a normal app can
do, and the answer is fixed by the SDK contract.

## The contract, for a normal app

Assumptions: `targetSdk 35`, Android 15, no root, not a device owner or profile
owner, not a system app. The app is the proxy app: a foreground service with
`CHANGE_WIFI_STATE`, `ACCESS_WIFI_STATE` and `ACCESS_FINE_LOCATION`, but an
ordinary UID.

| API | Result for this app | Consequence |
|---|---|---|
| `WifiManager.setWifiEnabled(false)` | Always returns `false` for `targetSdk` ≥ Q. | Cannot turn Wi-Fi off or on. |
| `addNetwork` | Always returns `-1` for `targetSdk` ≥ Q. | Cannot create a saved network. |
| `enableNetwork` | Always returns `false` for `targetSdk` ≥ Q. | Cannot select or re-enable a saved network. |
| `updateNetwork` | Always returns `-1` for `targetSdk` ≥ Q. | Cannot rewrite the saved profile. |
| `WifiNetworkSpecifier` + `requestNetwork` | Works, but shows a system approval dialog and is request-scoped. | Not silent, not durable, and unreliable from a booted service behind the keyguard. |
| `WifiNetworkSuggestion` | The user approves once; the platform scores it. Ignored when a saved network has the same SSID and security. | The saved `PhoneLink` profile masks it. Only usable after the user forgets that profile. |
| `cmd wifi` from the app UID | Shell-only; the Wi-Fi service enforces `NETWORK_SETTINGS`. | Not an app path. |

The exemptions — device owner, profile owner, system app — do not apply here.
`CHANGE_WIFI_STATE` is necessary for the calls above but does not lift the
`targetSdk` restrictions.

## What the app already does, and why it is not enough

- [`Uplink`](../src/org/phonelink/proxy/Uplink.java) watches connectivity and
  the keyguard. It pauses the listener when the SSID does not match and binds
  it again when the phone is back on the configured SSID.
  [`UplinkPolicy`](../src/org/phonelink/proxy/UplinkPolicy.java) decides that;
  it observes, it does not associate.
- The opt-in `wifi_lock` keeps the radio awake while a listener is running. It
  is not a reconnect mechanism and does not survive the phone deciding the
  network has no internet.
- [`BootReceiver`](../src/org/phonelink/proxy/BootReceiver.java) starts the
  service before the first unlock, so the listener can come up as soon as a
  Wi-Fi link exists. It does not create that link. The boot gates are in
  [Starting before the first unlock](pre-unlock-autostart.md).
- `AdbWireless` turns wireless debugging back on only once the phone is already
  on the configured SSID. It cannot bring the association back.

The Android behaviour behind the original bug is outside the app: after a
link-only AP restart, the phone may disable auto-join for a network with no
internet. The app can see the SSID disappear; it cannot re-enable the saved
network.

## The one candidate, and why it is not a fix

The only API that can lead to an automatic rejoin later is
`WifiNetworkSuggestion`. Its limits are structural:

1. The user approves the suggestion once. That is already user interaction, so
   it fails the "no user interaction" requirement at setup time.
2. A suggestion is ignored while a saved network with the same SSID and
   security exists. The phone has `PhoneLink` saved, so the user must forget it
   first.
3. The platform chooses whether and when to join. It may prefer another
   network, and the user can revoke the suggestion.
4. It does not make the association silent or unconditional. It only makes the
   one-time setup possible without a custom ROM.

A prototype would need this acceptance test, with the saved `PhoneLink` network
forgotten by the user and one suggestion approved:

1. Restart the AP. With the phone locked and no USB, the phone rejoins
   `PhoneLink` within 60 s and the proxy listener opens on 1081.
2. Reboot the phone with the AP up. After boot and before unlock, the phone
   rejoins within 60 s and the listener opens.
3. Restart the AP again. The phone rejoins again without a tap.
4. Record `dumpsys wifi` suggestion state and the app's uplink lines for each
   step.

That is a new feature decision, not a bug fix, and it still requires the user to
forget the saved network. No follow-up issue was filed from this verdict; the
prototype should be proposed on its own if the trade-off is wanted.

## What actually recovers the link

None of these are app-side rejoin:

- the user taps the network in Settings;
- USB tethering, then `any_network` for the listener;
- a topology that does not depend on the phone's client association, such as
  an always-on external AP or a phone-hosted link;
- a laptop-side action while the phone still auto-joins.

Those belong to the topology work in `MBanucu/phone-link`
([#37](https://github.com/MBanucu/phone-link/issues/37),
[#38](https://github.com/MBanucu/phone-link/issues/38)) and, for P2P, to
[Wi-Fi Direct](wifi-direct.md).

## Related decisions

- [Automatic channel and width](auto-channel.md) — the AP side of the link.
- [Wi-Fi + USB multipath](multipath.md) — the USB path as capacity, not rejoin.
- [Wi-Fi Direct](wifi-direct.md) — a phone-hosted transport that avoids the
  client association entirely.
- [Starting before the first unlock](pre-unlock-autostart.md) — what the app can
  do at boot, and what it cannot.
