# Automatic channel and width: keep 5 GHz 149 at 80 MHz

**Decision:** the `PhoneLink` AP stays on 5 GHz channel 149 at 80 MHz. Do not add
automatic channel selection, periodic scanning or width switching. The optional
startup fallback to 2.4 GHz is a separate change, tracked as
[phone-link-proxy#7](https://github.com/MBanucu/phone-link-proxy/issues/7).

This records the answer to issue #8. The measurements below are from that issue
(2026-11-26) and from a read-only re-check on 2026-11-28; the AP was not
restarted for this decision.

## What the link is doing now

Read-only, on the laptop that hosts the AP:

```text
$ iw dev wlp110s0
Interface wlp110s0
        ssid PhoneLink
        type AP
        channel 149 (5745 MHz), width: 80 MHz, center1: 5775 MHz
```

`iw reg get` reports `country DE: DFS-ETSI`, and the card is self-managed
(`DFS-UNSET` in the phy view). That is the configuration the measurements
describe.

## Why there is nothing to switch to

From issue #8, and confirmed in the current `iw phy` output:

- Channels 36–48 are marked `no IR`.
- Channels 52–144 are `no IR` (with radar detection where enabled, and some
  disabled outright), so they cannot initiate radiation in AP mode either; the
  self-managed LAR is not app-overridable.
- Channels 149, 153, 157, 161 and 165 are usable. An 80 MHz AP needs the
  149–161 block, and that is the only non-DFS 80 MHz block on this card.
- Narrower widths do have alternatives: 40 MHz can use 149–153 or 157–161,
  and 20 MHz has five channels. They are not free wins.

Throughput and retry cost, measured in issue #8:

| Width | Throughput | Retries |
|---|---|---|
| 80 MHz | 650 Mbps | 5.1% |
| 40 MHz | 390–400 Mbps | 1.9% |
| 20 MHz | 96–192 Mbps | 0.04% |

Narrowing reduces retries by a few points and gives up roughly 40% (40 MHz) or
most (20 MHz) of the link rate. For one client behind the proxy, whose ceiling
is the cellular chain rather than the Wi-Fi link, that is the wrong trade.

## Why NetworkManager's automatic mode does not help

From issue #8, not re-run here:

- In NetworkManager 1.56, `802-11-wireless.channel = 0` calls
  `ensure_hotspot_frequency()`. It hashes the SSID, shuffles a fixed list
  (`{5180, 5200, 5220, 5745, 5765, 5785, 5805}` for 5 GHz) and takes the first
  usable entry. It does not scan for the quietest channel and is not
  throughput-aware. On this card it can select 36–48, which are `no IR`.
- `channel-width = 0` means "safest (smallest)", i.e. 20 MHz, the opposite of
  what this link wants.
- Every change is an AP restart plus phone reassociation. Seconds of outage are
  acceptable at boot, not while a transfer or the tunnel/adb path is up.
- `iw dev wlp110s0 survey dump` returned no data while the AP was beaconing.
  A re-check on 2026-11-28 was also empty, so a channel-busy monitor has no
  input on this driver in AP mode.

## Why channel 36 was not tried again

Issue #8's acceptance asked for one attempt at channel 36 and one at
`channel = 0`. The failure mode is already recorded in `iw phy`: 36–48 are
`no IR`, so an AP cannot beacon there, and `channel = 0` can land on one of
them. Restarting the live AP to reproduce a flag that the driver prints is not
worth the outage. No claim in this page depends on a new channel-36 run.

## What would reopen this

Revisit only if one of these becomes true:

1. `survey dump` returns usable channel-busy data in AP mode, making a
   monitor possible without guessing.
2. A second non-DFS 80 MHz block appears on the card or in the regulatory
   domain, so a healthy 80 MHz link has somewhere to move.
3. The hardware changes. The current ceiling is the cellular chain, not the
   80 MHz link, so channel work has little end-to-end headroom.

hostapd ACS is not an option: it would replace NetworkManager's AP management
and does not fit the current module.

## Related decisions

- [Wi-Fi + USB multipath](multipath.md) — the other way to add link capacity,
  also not adopted.
- [Wi-Fi Direct](wifi-direct.md) — a different transport, not a channel change.
- [App rejoin](rejoin.md) — what the app can and cannot do when the link drops.
- [Architecture](architecture.md) — the SSID gate and listener lifecycle.
