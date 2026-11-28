package org.phonelink.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the SOCKS5 listeners alive while the uplink makes sense, and reports
 * them in the notification shade.
 *
 * <p>More than one proxy can listen at a time: every saved proxy carries its
 * own port, credentials, SSID and switches, so the list is a list of listeners
 * and this service holds one of each. The listeners themselves are
 * {@link Port}s in {@link Ports}, kept by port because a port is what a
 * listener is: a start on a port makes room by stopping whatever held it, and
 * the other ports are left alone. What is left here is what a listener cannot
 * answer for itself — the foreground notification, the shared cellular egress,
 * the wake lock that follows the clients, the boot and the tile, and the store's
 * front door for the actions adb sends.
 *
 * <p>Two ways in, one implementation: adb drives it with
 * {@code am start-foreground-service} through a {@code DUMP}-guarded service,
 * and {@link MainActivity} calls the same actions from the panel. Both write
 * through {@link ConfigStore}, so neither can configure a listener differently
 * from the other.
 *
 * <p>A listener is only open while its {@link Uplink} says the phone is on the
 * Wi-Fi it was configured for; otherwise it stays up, reports why, and binds
 * nothing.
 */
public final class ProxyService extends Service implements Port.Host {

    static final String TAG = "phone-link-proxy";

    static final String ACTION_START = Actions.ACTION_START;
    /** Stop everything, or with --es profile/--ei port just that one. */
    static final String ACTION_STOP = Actions.ACTION_STOP;
    /** Keep the configuration the service has under a name, and stop. */
    static final String ACTION_SAVE_PROFILE = Actions.ACTION_SAVE_PROFILE;
    /** Remove a saved configuration by name. */
    static final String ACTION_DELETE_PROFILE = Actions.ACTION_DELETE_PROFILE;
    /** Give a saved configuration another name: --es profile OLD --es to NEW. */
    static final String ACTION_RENAME_PROFILE = Actions.ACTION_RENAME_PROFILE;
    /** Pull every saved proxy over a one-shot loopback socket, unlocked only. */
    static final String ACTION_EXPORT = Actions.ACTION_EXPORT;
    /** Put a ProfileDocument back over a one-shot loopback socket, unlocked only. */
    static final String ACTION_IMPORT = Actions.ACTION_IMPORT;
    /**
     * The loopback port an export/import binds for its one connection. Not the
     * listener's SOCKS port, which the same extra spells for a START.
     */
    static final String EXTRA_TRANSFER_PORT = "port";
    /** The one-time token the laptop sends first; never a password. */
    static final String EXTRA_TOKEN = "token";

    private static final int NOTIFICATION_ID = 1;
    private static final int ERROR_NOTIFICATION_ID = 2;
    private static final String CHANNEL = "proxy";

    private Ports ports;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    /** One log line, not one per state change, when the lock cannot be held. */
    private boolean wifiLockWarned;
    private NotificationManager notifications;
    private Egress egress;
    private ProxyConfig config;
    /** Whether this instance has already been asked to do something. */
    private boolean startedBefore;
    /** One export or import at a time; the second one fails closed. */
    private final TransferGate transferring = new TransferGate();
    /** Wireless debugging is a switch of the whole app, and one start is enough. */
    private boolean adbTouched;
    /**
     * Set before the ports are closed in {@link #onDestroy}. A connection
     * thread can still be finishing a tunnel, and its last callback must not
     * reacquire a lock, re-post a notification, or publish a listener that is
     * gone.
     */
    private volatile boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        ports = new Ports(this);
        // The few facts the doctor reads when this service is not running: a
        // stopped app prints nothing for `dumpsys activity service`, which is
        // the state it is in when a phone is broken. Observe the boot first so
        // the state file carries the boot gate facts of this run.
        ConfigStore.observeBoot(this);
        ConfigStore.publishState(this);
        notifications = getSystemService(NotificationManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phone-link-proxy:egress");
        // A reference-counted lock would need exactly as many releases as
        // acquires; this one is held while at least one client is connected.
        wakeLock.setReferenceCounted(false);

        WifiManager wifi = getSystemService(WifiManager.class);
        if (wifi == null) {
            wifiLockWarned = true;
            Log.w(TAG, "no WifiManager; wifi_lock will be ignored");
        } else {
            try {
                // Not reference counted either: the lock follows the listeners,
                // and one acquire or release per change is all this needs.
                wifiLock = wifi.createWifiLock(wifiLockMode(), "phone-link:proxy");
                wifiLock.setReferenceCounted(false);
            } catch (SecurityException e) {
                wifiLockWarned = true;
                Log.w(TAG, "cannot create the Wi-Fi lock; the listener still runs", e);
            }
        }

        NotificationChannel channel =
                new NotificationChannel(CHANNEL, getString(R.string.channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The table decides what this build accepts, which extras it needs and
        // which gate applies. An intent with no action is the panel's and the
        // boot receiver's ordinary start, exactly as before the table existed.
        String asked = intent == null ? ACTION_START : intent.getAction();
        if (asked == null) {
            asked = ACTION_START;
        }
        Actions.Action action = Actions.find(asked);
        // A service that was already doing something is not restarted by a
        // save or a delete, and a start that only came for one has to go away
        // again.
        boolean first = !startedBefore;
        startedBefore = true;

        // Must happen within five seconds of startForegroundService(), which is
        // how the scripts deliver a stop as well: a stop that skipped this was
        // reported as a foreground service that never reached the foreground.
        boolean foreground = beginForeground(
                action == null ? "starting" : action.progress);
        if (action == null) {
            // An action this build does not know must not fall through to a
            // start: that is how an older app answered EXPORT by starting a
            // listener and printing nothing the laptop could use.
            reportFailure(getString(R.string.error_unknown_action, asked));
            if (ports.isEmpty()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        // Missing or invalid required extras are named here, before a handler
        // can mistake them for an empty store or a timeout.
        Actions.Problem problem = Actions.check(action, values(intent));
        if (problem != null) {
            reportFailure(problem.message());
            if (ports.isEmpty()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        switch (action.name) {
            case Actions.EXPORT:
            case Actions.IMPORT:
                if (action.gate == Actions.Gate.UNLOCKED && !unlocked()) {
                    // The screen is the gate: no socket exists behind a locked
                    // keyguard, so the laptop's connect cannot be answered.
                    reportFailure(getString(R.string.error_transfer_locked));
                    if (ports.isEmpty()) {
                        stopSelf();
                    }
                    return START_NOT_STICKY;
                }
                transfer(Actions.EXPORT.equals(action.name), intent, startId);
                return START_NOT_STICKY;
            case Actions.STOP:
                stopRequested(intent);
                // A stop of one listener beside others leaves a service that is
                // still somebody's uplink and must come back if the system kills it.
                return stickiness();
            case Actions.SAVE_PROFILE:
                saveCurrent(intent, first);
                return stickiness();
            case Actions.DELETE_PROFILE:
                deleteSaved(intent, first);
                return stickiness();
            case Actions.RENAME_PROFILE:
                renameSaved(intent, first);
                return stickiness();
            case Actions.START:
                break;
            default:
                // The table named an action this dispatch does not handle;
                // that is a build bug, and it still must not fall through to a
                // start that answers with a listener instead of an error.
                reportFailure(getString(R.string.error_unknown_action, asked));
                if (ports.isEmpty()) {
                    stopSelf();
                }
                return START_NOT_STICKY;
        }
        if (action.gate == Actions.Gate.FOREGROUND && !foreground) {
            // Starting from the shade or at boot needs location "all the time";
            // from the panel it works without it.
            fail(getString(R.string.error_background_start));
            return START_NOT_STICKY;
        }

        try {
            config = ConfigStore.resolve(this, intent);
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
            return START_NOT_STICKY;
        }
        // Which saved proxy this is, so the list can mark its card and the log
        // can keep a proxy's events apart: the one the start named, or the one
        // whose settings these are.
        String profile = intent == null
                ? null
                : intent.getStringExtra(ConfigStore.EXTRA_PROFILE);
        profile = profile == null || profile.trim().isEmpty()
                ? ConfigStore.profileMatching(this, config)
                : profile.trim();
        start(profile, config);
        return stickiness();
    }

    /**
     * What to tell the system when an action returns: keep the service while a
     * listener remains, and let it go only when nothing is running at all.
     */
    private int stickiness() {
        return ports.isEmpty() ? START_NOT_STICKY : START_STICKY;
    }

    /**
     * The intent's extras as the action table reads them. The request is
     * deliberately small: the table never needs a value the action would not
     * accept, and a value it does not accept is refused, not logged.
     */
    private static Actions.Values values(Intent intent) {
        return new Actions.Values() {
            @Override
            public boolean has(String name) {
                return intent != null && intent.hasExtra(name);
            }

            @Override
            public String string(String name) {
                if (intent == null) {
                    return null;
                }
                try {
                    return intent.getStringExtra(name);
                } catch (RuntimeException e) {
                    // An extra of the wrong type is an invalid extra, not a
                    // reason to crash the service before it can say so.
                    return null;
                }
            }

            @Override
            public int integer(String name) {
                if (intent == null) {
                    return 0;
                }
                try {
                    return intent.getIntExtra(name, 0);
                } catch (RuntimeException e) {
                    return 0;
                }
            }
        };
    }

    /**
     * "Unlocked" means the screen is on and the keyguard is not: a phone with
     * no secure lock still has an off screen, and a transfer must not create a
     * socket behind it.
     */
    private boolean unlocked() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive()
                && keyguard != null && !keyguard.isKeyguardLocked();
    }

    /**
     * Promote the service to the foreground with the location type, which is
     * what keeps the SSID readable while the app is not in front — the point
     * when the tile or the boot receiver starts us.
     *
     * <p>Android refuses that type for a start from the background without
     * background location, and there is no honest fallback: a service with no
     * type is prohibited for this target, and without the SSID the uplink could
     * not tell which Wi-Fi this is anyway. So report instead of guessing.
     *
     * @return whether the service is now in the foreground; a start that cannot
     *     get there has to give up, or the system kills it without ceremony
     */
    private boolean beginForeground(String text) {
        Notification notification = buildNotification(text);
        // Before the first unlock the location type cannot be used at all: its
        // runtime grant lives in credential-encrypted storage, so the system
        // refuses it even though checkSelfPermission reports it as granted.
        // specialUse needs no runtime permission, which is what lets the boot
        // receiver bring the listener up early. Once the user is unlocked the
        // location type is what the app has always used, and the old behaviour
        // (report instead of guessing) is kept for the case where background
        // location was denied.
        // The manifest declares specialUse only, so that is the only type the
        // system accepts; passing the location type here would be refused as
        // "not in the manifest" anyway.
        return promote(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    /** One foreground attempt with an explicit type. */
    private boolean promote(Notification notification, int type) {
        try {
            startForeground(NOTIFICATION_ID, notification, type);
            return true;
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "the system refused foreground type " + type, e);
            return false;
        }
    }

    @Override
    public void onDestroy() {
        // Before the ports: closing a tunnel wakes its connection thread, whose
        // last clientsChanged() must not touch the locks or the shade again.
        destroyed = true;
        // Closing the last port closes the egress with it.
        ports.stop(null, false, 0);
        // A client connected when the stop arrived would otherwise leave the
        // partial wake lock held until the process dies.
        holdWakeLock(false);
        // With no ports and destroyed set, this releases the Wi-Fi lock if it
        // was held; a late changed() cannot reacquire it.
        updateWifiLock();
        closeEgress();
        // Nothing that happens after this is about the listeners that just went.
        EventLog.setActiveTag(null);
        // publishAll() is a no-op now, but the panel still has to be emptied.
        ProxyRuntime.publish(new ArrayList<>());
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifications.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * The app's own {@code --help}: this is what
     * {@code adb shell dumpsys activity service org.phonelink.proxy/.ProxyService}
     * prints. The same words as the panel's Info dialog, followed by what the
     * service is doing right now, so an agent reads the contract and the facts
     * together.
     */
    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println(Help.text(getPackageName()));
        writer.println();
        writer.println("Right now");
        // The contract's version and the actions this build accepts, both from
        // the one table, so the laptop can refuse an old app before it loses a
        // transfer to a timeout.
        writer.print(Actions.dump());
        List<Port> all = ports.all();
        writer.println("  state       " + ProxyState.summary(ports.states()));
        // The saved configuration asked for by the last start, which is what the
        // list marks and what an agent means by "the proxy in use".
        String inUse = ConfigStore.inUse(this);
        writer.println("  profile     " + (inUse == null ? "none" : "\"" + inUse + "\""));
        long up = 0;
        long down = 0;
        for (Port each : all) {
            long[] totals = each.traffic().totals();
            up += totals[0];
            down += totals[1];
            writer.println("  running     " + each.label()
                    // An unnamed one is already known by its port.
                    + (each.name() == null ? "" : " \u00b7 port " + each.config().port)
                    + " \u00b7 \u2193 " + Traffic.human(totals[1])
                    + " · \u2191 " + Traffic.human(totals[0])
                    + " · " + each.clients() + (each.clients() == 1 ? " client" : " clients"));
        }
        writer.println("  traffic     \u2193 " + Traffic.human(down)
                + " \u00b7 \u2191 " + Traffic.human(up) + " in this run");
        if (config != null) {
            writer.println("  using       " + config.summary());
            writer.println("  max_connections " + config.maxConnections);
        }
        writer.println("  udp         UDP ASSOCIATE supported; BIND refused");
        // Not the phone's default route: a request that fails here has to be
        // told apart from one the phone's Wi-Fi could have carried.
        Egress leaving = egress;
        writer.println("  egress      "
                + (leaving == null ? "none (no listener started)" : leaving.describe()));
        writer.println("  auto-start  " + ConfigStore.autoStart(this));
        // Never "clear": the vendor list is behind a signature permission, so
        // the only honest answer from a running service is that it is unknown.
        writer.println("  oem_autostart " + BootGate.UNREADABLE
                + " (DokeOS list is signature-protected)");
        long missedAt = ConfigStore.bootMissedAt(this);
        if (missedAt > 0) {
            writer.println("  last_boot   no_broadcast " + missedAt);
        }
        writer.println("  adb_wifi    " + ConfigStore.adbWireless(this)
                + " (Android's switch is " + (AdbWireless.on(this) ? "on" : "off") + ")");
        writer.println("  wifi_lock   " + (wifiLock != null && wifiLock.isHeld()
                ? "held" : "not held")
                + " · setting " + (ConfigStore.wifiLock(this) ? "on" : "off"));
        // What the Quick Settings tile switches: the saved proxies whose switch
        // is on, and how far a click reaches in each direction.
        List<String> bound = ConfigStore.tileProfiles(this);
        writer.println("  tile        "
                + (bound.isEmpty() ? "nothing bound" : String.join(", ", bound)));
        writer.println("  tile_start  " + ConfigStore.tileStart(this).name().toLowerCase());
        writer.println("  tile_stop   " + ConfigStore.tileStop(this).name().toLowerCase());
        writer.println("  tile_cycle  "
                + TileCycle.shown(ConfigStore.tileCycle(this)));
        List<String> saved = ConfigStore.profileNames(this);
        if (saved.isEmpty()) {
            writer.println("  saved       none");
        }
        for (String name : saved) {
            ProxyConfig profile = ConfigStore.profile(this, name);
            writer.println("  saved       \"" + name + "\" — "
                    + (profile == null ? "?" : profile.summary())
                    + " · auto-start "
                    + (ConfigStore.profileAutoStart(this, name) ? "on" : "off")
                    + " · wireless debugging "
                    + (ConfigStore.profileAdbWireless(this, name) ? "on" : "off"));
        }
        writer.flush();
    }

    // -- what a port asks the service for ---------------------------------

    @Override
    public Context context() {
        return this;
    }

    @Override
    public Upstream upstream() {
        if (egress == null && !destroyed) {
            egress = new Egress(this);
            egress.start();
        }
        return egress;
    }

    /**
     * A port's network just became usable. The port adb uses lives only while
     * wireless debugging is on, and a reboot can switch it off; this is the one
     * moment that matters for the host, because the SSID just matched.
     */
    @Override
    public void uplinkUp(Port port) {
        if (destroyed) {
            return;
        }
        if (!adbTouched && ConfigStore.adbWireless(this)) {
            adbTouched = true;
            boolean wasOff = !AdbWireless.on(this);
            AdbWireless.enable(this);
            if (wasOff && AdbWireless.on(this)) {
                EventLog.addTo(port.name(), "wireless debugging turned back on");
            }
        } else if (!ConfigStore.adbWireless(this)) {
            Log.i(TAG, "wireless debugging left alone: the panel switch is off");
        }
    }

    /**
     * Serialised with itself so the count is read while no other connection
     * callback is changing it: the last disconnect cannot be seen as zero just
     * as a new client arrives, and a client still connected cannot have the
     * lock released under it.
     */
    @Override
    public synchronized void clientsChanged() {
        if (destroyed) {
            return;
        }
        holdWakeLock(ports.totalClients() > 0);
    }

    @Override
    public void changed() {
        if (destroyed) {
            return;
        }
        // A port binds or pauses here, and a connection thread calls this with
        // the last client; both can change whether a Wi-Fi lock is wanted.
        updateWifiLock();
        publishAll();
    }

    /**
     * One port could not do its job. The others are not its business, so this
     * takes that port out of the list and leaves the rest listening.
     */
    @Override
    public void failed(Port port, String message) {
        if (destroyed) {
            return;
        }
        Log.e(TAG, message);
        EventLog.addTo(port.name(), "failed: " + message);
        notifyError(message);
        ports.remove(port);
        port.close();
        holdWakeLock(ports.totalClients() > 0);
        updateWifiLock();
        if (ports.isEmpty()) {
            closeEgress();
            stopSelf();
        } else {
            publishAll();
        }
    }

    // -- starting and stopping -------------------------------------------

    /**
     * Start the configuration as the proxy called {@code name}. A start on a
     * port that is already taken is a restart of that port; every other port
     * keeps listening, which is the whole point of the list.
     */
    private void start(String name, ProxyConfig config) {
        Port port = ports.start(name, config);
        if (name != null) {
            // An ad-hoc start under a name no profile holds is not "the proxy in
            // use": it would erase the mark the list draws on a card.
            ConfigStore.setInUse(this, name);
        }
        EventLog.setActiveTag(name);
        Log.i(TAG, "starting " + port.label() + " on port " + config.port);
        EventLog.addTo(name, "starting on port " + config.port);
        updateWifiLock();
        publishAll();
        port.watch();
    }

    /** {@code STOP}: everything, or the one the intent names. */
    private void stopRequested(Intent intent) {
        String name = intent == null ? null : intent.getStringExtra(ConfigStore.EXTRA_PROFILE);
        name = name == null ? null : name.trim();
        boolean byPort = intent != null && intent.hasExtra(ConfigStore.EXTRA_PORT);
        int port = intent == null ? 0 : intent.getIntExtra(ConfigStore.EXTRA_PORT, 0);
        stopListeners(name, byPort, port);
    }

    /**
     * Close the listeners a stop is about — one name, one port, or all of them
     * — and let the service go when the last one is gone. Kept apart from
     * {@link #stopRequested} so a delete can stop what its name owned without
     * going through the intent again.
     */
    private void stopListeners(String name, boolean byPort, int port) {
        for (Port each : ports.stop(name, byPort, port)) {
            Log.i(TAG, "stopped " + each.label() + " on port " + each.config().port);
            EventLog.addTo(each.name(), "stopped listening on port " + each.config().port);
        }
        holdWakeLock(ports.totalClients() > 0);
        updateWifiLock();
        if (ports.isEmpty()) {
            closeEgress();
            stopSelf();
        } else {
            publishAll();
        }
    }

    private void closeEgress() {
        if (egress != null) {
            // No client can arrive while nothing is bound, so the cellular
            // request would only keep the radio busy.
            egress.close();
            egress = null;
        }
    }

    private synchronized void holdWakeLock(boolean held) {
        if (held && !wakeLock.isHeld()) {
            // Only while somebody is actually using a tunnel: a lock held for
            // the lifetime of the service is the battery cost this app exists
            // to avoid.
            wakeLock.acquire();
        } else if (!held && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    /**
     * Android 14 maps the deprecated high-performance request to low latency by
     * default, and low latency is only honoured while Wi-Fi is connected, the
     * screen is on and the app has foreground importance — which a foreground
     * service does not give it. Asking for low latency on 34+ says what the
     * platform will actually do; older versions still know high performance.
     */
    @SuppressWarnings("deprecation")
    private static int wifiLockMode() {
        // The deprecation is the point: 29–33 know only this mode, and 34+
        // would convert it to low latency anyway.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
    }

    /**
     * Follow the listeners: hold the Wi-Fi lock while the switch is on and a
     * listener that needs Wi-Fi is actually bound. Called from connection
     * threads as well as the main thread, so acquires and releases are
     * serialised here rather than left to race.
     */
    private synchronized void updateWifiLock() {
        List<WifiLockPolicy.Listener> listeners = new ArrayList<>();
        for (Port each : ports.all()) {
            listeners.add(new WifiLockPolicy.Listener(
                    each.state().listening, each.config().anyNetwork));
        }
        // Destroyed means the listeners just went; this call releases the lock
        // rather than letting a late callback put it back.
        boolean wanted = !destroyed
                && WifiLockPolicy.hold(ConfigStore.wifiLock(this), listeners);
        if (wifiLock == null) {
            if (wanted && !wifiLockWarned) {
                wifiLockWarned = true;
                Log.w(TAG, "wifi_lock is on but this phone has no usable Wi-Fi lock;"
                        + " the listener runs without it");
            }
            return;
        }
        if (wanted == wifiLock.isHeld()) {
            return;
        }
        try {
            if (wanted) {
                wifiLock.acquire();
                Log.i(TAG, "holding the Wi-Fi lock while a Wi-Fi listener is bound");
            } else {
                wifiLock.release();
                Log.i(TAG, "released the Wi-Fi lock");
            }
        } catch (SecurityException e) {
            // The listener is the point; a lock the system refuses is a note in
            // the log, not a reason to stop serving.
            Log.w(TAG, "cannot hold the Wi-Fi lock; the listener still runs", e);
        }
    }

    // -- saved configurations --------------------------------------------

    /**
     * {@code SAVE_PROFILE} keeps the configuration the service has under a name,
     * so an agent can configure the listener once and then start it by name.
     * The same store as the panel's {@code Save as…}, so either sees the other.
     */
    private void saveCurrent(Intent intent, boolean first) {
        String name = intent == null
                ? ""
                : intent.getStringExtra(ConfigStore.EXTRA_PROFILE);
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            fail(getString(R.string.error_profile_name));
            return;
        }
        ConfigStore.saveProfile(this, name, ConfigStore.load(this),
                ConfigStore.autoStart(this), ConfigStore.adbWireless(this),
                ConfigStore.wifiLock(this));
        EventLog.addTo(name, "saved configuration \"" + name + "\"");
        Log.i(TAG, "saved configuration \"" + name + "\"");
        if (first && ports.isEmpty()) {
            stopSelf();
        } else {
            publishAll();
        }
    }

    /** {@code DELETE_PROFILE}: the panel's Delete, without the screen. */
    private void deleteSaved(Intent intent, boolean first) {
        String name = intent == null
                ? ""
                : intent.getStringExtra(ConfigStore.EXTRA_PROFILE);
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            fail(getString(R.string.error_profile_name));
            return;
        }
        if (ConfigStore.profile(this, name) == null) {
            fail(getString(R.string.error_profile_missing, name));
            return;
        }
        ConfigStore.deleteProfile(this, name);
        if (name.equals(ConfigStore.inUse(this))) {
            // The list must not keep pointing at a proxy that no longer exists.
            ConfigStore.setInUse(this, null);
        }
        // A listener running under that name has nothing left to be called.
        Port named = ports.byName(name);
        EventLog.addTo(name, "deleted \"" + name + "\"");
        Log.i(TAG, "deleted configuration \"" + name + "\"");
        if (named != null) {
            // The configuration is the listener's name, so it goes with the
            // configuration: a port the list cannot show is a port the panel
            // cannot stop. This is also what the panel's own Delete does, so
            // both ways of deleting one end the same.
            EventLog.addTo(name, "deleted \"" + name + "\" while it was running");
            stopListeners(name, false, 0);
            return;
        }
        if (first && ports.isEmpty()) {
            stopSelf();
        } else {
            publishAll();
        }
    }

    /** {@code RENAME_PROFILE}: the panel's Rename, without the screen. */
    private void renameSaved(Intent intent, boolean first) {
        String name = intent == null
                ? ""
                : intent.getStringExtra(ConfigStore.EXTRA_PROFILE);
        String to = intent == null ? "" : intent.getStringExtra(ConfigStore.EXTRA_TO);
        name = name == null ? "" : name.trim();
        to = to == null ? "" : to.trim();
        if (name.isEmpty() || to.isEmpty()) {
            fail(getString(R.string.error_profile_name));
            return;
        }
        if (!ConfigStore.renameProfile(this, name, to)) {
            // The panel renames in the store itself and then tells the service,
            // so the old name is already gone there and only the listener that
            // was running under it is left to move. Anything else is an error.
            if (ConfigStore.profile(this, name) != null || ConfigStore.profile(this, to) == null) {
                fail(getString(R.string.error_profile_exists, to));
                return;
            }
        }
        EventLog.addTo(to, "renamed \"" + name + "\" to \"" + to + "\"");
        // A proxy that is running under the old name keeps logging under the
        // new one instead of a name that no longer exists. The entry is the
        // same one — the listening port, its counters and its clients are not
        // affected — so only the name it is filed under moves.
        ports.rename(name, to);
        if (to.equals(ConfigStore.inUse(this))) {
            EventLog.setActiveTag(to);
        }
        Log.i(TAG, "renamed configuration \"" + name + "\" to \"" + to + "\"");
        if (first && ports.isEmpty()) {
            stopSelf();
        } else {
            publishAll();
        }
    }

    // -- exporting and importing over adb --------------------------------

    /**
     * One {@code EXPORT} or {@code IMPORT}, on a worker thread: the socket waits
     * up to a minute for the laptop, and {@link #onStartCommand} must return.
     *
     * <p>The extras and the unlocked gate were checked from the action table
     * before this was called, so a missing port, a bad token or a locked screen
     * never reaches the socket. A second transfer still fails closed here.
     */
    private void transfer(boolean exporting, Intent intent, int startId) {
        int port = intent == null ? 0 : intent.getIntExtra(EXTRA_TRANSFER_PORT, 0);
        String token = intent == null ? null : intent.getStringExtra(EXTRA_TOKEN);
        if (!transferring.begin()) {
            // Never stop the service here: the first transfer is still using it.
            reportFailure(getString(R.string.error_transfer_busy));
            return;
        }
        final int[] applied = {0};
        Thread worker = new Thread(() -> {
            String what = exporting ? "export" : "import";
            try {
                if (exporting) {
                    ProfileTransfer.export(
                            port, token, ProfileTransfer.DEFAULT_LIFETIME_MS,
                            () -> ConfigStore.exportDocument(this));
                    EventLog.add("exported every saved proxy to the laptop");
                    Log.i(TAG, "exported every saved proxy to the laptop");
                } else {
                    ProfileTransfer.importDocument(
                            port, token, ProfileTransfer.DEFAULT_LIFETIME_MS,
                            text -> applied[0] = ConfigStore.importDocument(this, text, true));
                    EventLog.add("imported " + applied[0]
                            + " saved proxies from the laptop");
                    Log.i(TAG, "imported " + applied[0] + " saved proxies from the laptop");
                }
            } catch (ProfileTransfer.Failure e) {
                // The code alone: a message from the parser could quote the
                // document, and the document holds passwords.
                Log.w(TAG, what + " transfer refused: " + e.code);
                EventLog.add(what + " transfer refused: " + e.code);
            } catch (RuntimeException e) {
                Log.e(TAG, what + " transfer failed: " + e.getClass().getSimpleName());
                EventLog.add(what + " transfer failed");
            } finally {
                transferring.end();
                if (ports.isEmpty()) {
                    stopSelf(startId);
                } else {
                    publishAll();
                }
            }
        }, "proxy-profile-transfer");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException e) {
            transferring.end();
            reportFailure(getString(R.string.error_transfer_failed));
        }
    }

    // -- the notifications ------------------------------------------------

    /** Share every listener's state with the panel, the shade and the Log page. */
    private void publishAll() {
        if (destroyed) {
            return;
        }
        List<ProxyRuntime.Proxy> view = new ArrayList<>();
        for (Port each : ports.all()) {
            view.add(new ProxyRuntime.Proxy(each.state(), each.traffic()));
        }
        ProxyRuntime.publish(view);
        if (startedBefore) {
            notifications.notify(NOTIFICATION_ID,
                    buildNotification(ProxyState.summary(ports.states())));
        }
    }

    private void fail(String message) {
        reportFailure(message);
        stopSelf();
    }

    /** Report a refused action without stopping listeners another start owns. */
    private void reportFailure(String message) {
        Log.e(TAG, message);
        EventLog.add("failed: " + message);
        notifyError(message);
    }

    private void notifyError(String message) {
        if (destroyed) {
            return;
        }
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_proxy)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .build();
        notifications.notify(ERROR_NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ProxyService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent =
                PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_proxy)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_proxy),
                        getString(R.string.action_stop), stopIntent).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }
}
