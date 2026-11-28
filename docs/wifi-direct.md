# Wi-Fi Direct (P2P): proven transport, not the default link

**Verdict:** Wi-Fi Direct is a working phone-hosted transport on this hardware,
but it is not adopted as the default phone↔laptop link now. This app is
AP-first: the laptop hosts `PhoneLink`, the phone is a Wi-Fi client, and the
listener's SSID gate is built around that association. A standing P2P group
needs an app-side foreground service and a different gate. P2P remains a
fallback, with the experiments and evidence kept in
[`MBanucu/p2p-self-probe`](https://github.com/MBanucu/p2p-self-probe).

This records the answer to issue #12.

## What the sibling probe measured

All of the following is from `p2p-self-probe`'s
[`docs/direct-links.md`](https://github.com/MBanucu/p2p-self-probe/blob/main/docs/direct-links.md)
and [`docs/findings.md`](https://github.com/MBanucu/p2p-self-probe/blob/main/docs/findings.md),
measured 2026-11-27. It was not re-run for this page.

- The phone's default P2P group owner came up on 6 GHz (6135/6055 MHz), which
  the laptop's Intel 8265 cannot see.
- Forcing 5 GHz with `setGroupOperatingFrequency(5180)` plus an SSID and
  passphrase put the GO on **5180 MHz**, SSID `DIRECT-KL-5G`. The laptop joined
  as a plain WPA2 station, the phone's DHCP gave it `192.168.49.149`, and
  `adb connect 192.168.49.1:5555` worked with the Wireless debugging toggle
  **off**, no AP, no hotspot and no cable.
- The group lives on the probe's timer. Keeping it up needs a foreground
  service.
- P2P and the phone hotspot cannot coexist; the HAL refused the second
  interface (`Failed to setup interface for P2P`).
- A client STA association in the same window did not survive: the probe
  records the STA data path breaking at about **3 minutes**.

The sibling probe therefore answers the transport question. It does **not**
show 30+ minutes of unattended P2P with the screen off, nor a reboot rejoin.
Those were the decision criteria in issue #12 and remain unmeasured.

## Why it is not the default here

The app's current contract is a client association:

- [`Uplink`](../src/org/phonelink/proxy/Uplink.java) watches the phone's Wi-Fi
  client link and compares its SSID with the configured one.
  [`UplinkPolicy`](../src/org/phonelink/proxy/UplinkPolicy.java) pauses the
  listener on any other SSID, and `any_network` is the only escape.
- A P2P group owner has no ordinary client `WifiInfo` for that gate. A P2P
  group client has no `NetworkAgent` on this build, so `ConnectivityManager`
  does not present it as a usable Wi-Fi network. Either role needs the gate
  relaxed, most likely to `any_network`, or a new P2P-aware gate.
- The group needs an app-side foreground service and a persistent-group join
  after boot. That is app code, permissions (`NEARBY_WIFI_DEVICES` and the
  location grant), and a new lifecycle, not a module switch.
- The laptop side needs P2P scripts and a DHCP setup on `wpa_supplicant`'s
  group interface. The current module owns NetworkManager and hostapd; P2P
  replaces that path rather than adding to it.
- The measured AP path already works, and the end-to-end ceiling is the
  proxy/mobile chain, not the link. P2P only has to match the AP, not beat it.

The one thing P2P would buy is independence from a laptop-hosted AP: the group
survives a laptop reboot because the phone owns it. That is a topology benefit,
not a throughput one, and it is not enough to offset an unproven 30-minute
idle path and a new app lifecycle today.

## What would change the verdict

Adopt P2P only after the sibling probe or a prototype shows all of:

1. A persistent group rejoins after a phone reboot without unlock, with the
   proxy listener up and the group held by a foreground service.
2. The link holds for 30+ minutes idle, screen off, with mobile data still the
   default network and the #11 no-uplink disassociation not recurring.
3. A single SOCKS flow and two parallel flows are at least at the AP path's
   measured range, with the negotiated channel and width recorded.
4. The phone's normal Wi-Fi use and adb access still work; the hotspot
   limitation is acceptable.

Until then the AP-first path stays the default. The no-uplink recovery work
belongs with the topology issues in `MBanucu/phone-link`, not with a P2P
rewrite.

## Related decisions

- [Automatic channel and width](auto-channel.md) — why the AP stays on 149/80.
- [Wi-Fi + USB multipath](multipath.md) — a second path that does not add
  uplink capacity.
- [App rejoin](rejoin.md) — why the app cannot silently re-associate.
- [Starting before the first unlock](pre-unlock-autostart.md) — the boot path a
  P2P group would also have to satisfy.
