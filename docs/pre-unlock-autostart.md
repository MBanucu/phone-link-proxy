# Starting before the first unlock

This app is supposed to have its listener up before the phone's owner touches
it: the paired laptop reaches it over the `PhoneLink` Wi-Fi during boot, which
is often the only way in. Getting there took four separate gates, each one
silent in its own way. This page records them so the next person pays for one
boot, not thirty.

Everything below was established on **Blackview XPLO2EEA0001541, DokeOS 4.2 on
Android 15 (API 35), unrooted**, by booting repeatedly with the phone left at
`State: RUNNING_LOCKED`.

## The four gates

### 1. `RECEIVE_BOOT_COMPLETED`

Without the permission the broadcast is dropped at enqueue, before any
component of ours is considered:

```
reason: skipped by policy at enqueue: Permission Denial: receiving
  Intent { act=android.intent.action.LOCKED_BOOT_COMPLETED ... }
  to <pkg>/<receiver> requires android.permission.RECEIVE_BOOT_COMPLETED
  due to sender null (uid 1000)
```

Symptom: no `am_proc_start` anywhere, and nothing in logcat under the app's own
tag. `dumpsys activity broadcasts` is where the `reason:` line shows up —
`logcat` alone does not print it.

Fix: declare `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />`.

### 2. `directBootAware` on the component, not just the application

`android:directBootAware="true"` on `<application>` is documented as the default
for all components. On this firmware it is not applied to receivers, and the
result is that the broadcast resolves **no component for this package at all** —
it is not in the `LOCKED_BOOT_COMPLETED` record.

Symptom: the vendor's startup manager reports `isAllowBootStartup=true` for the
package, `LOCKED_BOOT_COMPLETED` is dispatched to other apps in the same boot,
and the app still never starts. It receives `BOOT_COMPLETED` after the unlock,
which is why it looks like "it only starts late".

Diagnose with the queue, not with logs:

```sh
adb shell dumpsys activity broadcasts | grep -A40 'LOCKED_BOOT_COMPLETED'
# look for: name=<pkg>/<receiver>  packageName=<pkg>
```

Fix: write it on the receiver (and on the service that the receiver starts):

```xml
<receiver android:name=".BootReceiver"
    android:exported="true"
    android:directBootAware="true">
```

### 3. The foreground service type cannot be `location`

A process that does start will die one line later if the service requests a
`location` type while the user is locked:

```
java.lang.SecurityException: Starting FGS with type location ...
  requires permissions: all of [android.permission.FOREGROUND_SERVICE_LOCATION]
  any of [ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION]
  and the app must be in the eligible state/exemptions to access the
  foreground only permission
```

The grants exist — they live in credential-encrypted storage, which is not
readable until the first unlock, so no combination of permissions helps.
`specialUse` is the only usable type pre-unlock.

Fix: `android:foregroundServiceType="specialUse"` plus
`android.permission.FOREGROUND_SERVICE_SPECIAL_USE`, and pass the type to the
call as well (required from targetSdk 34):

```java
startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
```

Declaring both types in the manifest (`location|specialUse`) does not help: the
service must not request `location` pre-unlock, so this app uses `specialUse`
only and keeps `ACCESS_FINE_LOCATION` purely for reading the SSID.

### 4. The uplink predicate has to tolerate a locked user

Two separate wrong assumptions, both fatal for the listener even when the
process is healthy:

```
"PhoneLink" uplink paused: the default network is not Wi-Fi
"PhoneLink" uplink paused: cannot read the Wi-Fi SSID; needs location permission and location on
```

- `getActiveNetwork()` is mobile data on this phone; the Wi-Fi link to the
  laptop is a **secondary** network. Look for the Wi-Fi transport across
  `getAllNetworks()` instead, and register a `NetworkRequest` for Wi-Fi so its
  connect/disconnect actually triggers a re-check.
- The SSID is `<unknown ssid>` until the first unlock, on purpose. Comparing it
  strictly means the port can never open while locked.

Fix: accept the Wi-Fi link while the user is locked and resume the exact SSID
comparison once unlocked.

**Trade-off, deliberate:** while locked the proxy binds to whichever Wi-Fi is
up, because the SSID cannot be checked. The strict `PhoneLink` match returns at
unlock. If that is not acceptable for your threat model, the alternative is to
wait for the unlock and give up the pre-unlock listener.

## What is *not* the problem

The vendor's autostart list (`Auto Start Management`) is a red herring. It
reported `isAllowBootStartup=true` for the package, and logcat confirmed it even
excluded the app from its stop sweep (`StopPackagesThread:skip stop
isAllowBootStartup pkg: <pkg>`) — while the app still never started. Allowlisting
a package there is still useful for surviving that sweep, but it neither grants
nor explains `LOCKED_BOOT_COMPLETED`.

## How to check a build

```sh
adb reboot
# stay locked; adjust the sleep to taste
sleep 30
adb shell dumpsys user | grep -m1 'State:'        # expect RUNNING_LOCKED

# 1) did the receiver run at all?
adb logcat -d | grep -E 'Start proc [0-9]+:<pkg>/'
#    "for broadcast {<pkg>/<pkg>.BootReceiver}" is the proof the gate opened

# 2) is the listener up? 1081 = 0x439 in /proc/net/tcp
adb shell 'cat /proc/net/tcp /proc/net/tcp6' | grep -c ':0439'

# 3) what did the app decide?
adb logcat -d | grep '<log tag>'
#    "uplink allowed: on Wi-Fi (SSID unreadable before unlock)"  good
#    "uplink paused: ..."                                        see gate 4

# 4) end-to-end, from the laptop
curl --socks5-hostname <phone-ip>:1081 --proxy-user <user>:<pass> http://example.com
```

A SOCKS request that returns `200` while `dumpsys user` still says
`RUNNING_LOCKED` is the whole goal.

## Limits of this page

- The `directBootAware` inheritance failure (gate 2) is vendor behaviour, not
  documented Android behaviour; on other phones the application-level attribute
  may be enough.
- The SSID/location interaction on gate 4 was observed on this build; the
  `<unknown ssid>` redaction is standard, but which permission combination
  unredacts it is not.
- Only Android 15 / targetSdk 35 was tested, and only with a `specialUse`
  foreground service.
