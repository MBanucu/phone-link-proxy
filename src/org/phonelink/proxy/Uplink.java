package org.phonelink.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.util.Log;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Decides whether the listener may run.
 *
 * <p>Allowed only while the phone is a client of the configured SSID. Everywhere
 * else the port stays closed, because a wildcard listener on an arbitrary Wi-Fi
 * LAN is an open relay behind one password. Tethering deliberately does not
 * count: the hotspot and USB paths are not wanted here.
 *
 * <p>The answer comes from a map of the networks the callbacks last described,
 * not from {@code getAllNetworks()} inside a callback: the synchronous
 * {@code ConnectivityManager} calls race with the callback that is asking. A
 * Wi-Fi callback is registered beside the default callback, because the laptop
 * link is usually secondary and its connect and disconnect would otherwise
 * never be reported; the Wi-Fi callback asks for location information on API 31
 * and later so {@code getTransportInfo()} carries the SSID. A redacted SSID is
 * "unknown" rather than "a different network", and before the first unlock that
 * unknown is allowed — the platform redacts every SSID then, and the phone is
 * normally on the laptop's own network. {@code ACTION_USER_UNLOCKED} triggers
 * another evaluation, so the strict comparison takes over the moment the SSIDs
 * become readable.
 */
final class Uplink implements Closeable {

    private static final String TAG = "phone-link-proxy";

    interface Listener {
        void onUplinkChanged(boolean allowed, String detail);
    }

    /** What the callbacks last said about one network. */
    private static final class Observation {
        NetworkCapabilities capabilities;
        WifiInfo wifiInfo;
        /** Whether the Wi-Fi callback currently tracks this network. */
        boolean viaWifi;
        /** Whether the latest capabilities call it a Wi-Fi transport. */
        boolean wifiTransport;
        /** Whether the default callback currently tracks this network. */
        boolean isDefault;

        boolean wifi() {
            return viaWifi || wifiTransport;
        }
    }

    private final Context context;
    private final ConnectivityManager manager;
    private final WifiManager wifiManager;
    private final UserManager userManager;
    private final String ssid;
    private final boolean anyNetwork;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * The networks both callbacks know about, keyed by the platform's handle.
     * Guarded by its own monitor: the callbacks run on {@link #handler}, but
     * close and the service may not.
     */
    private final Map<Network, Observation> networks = new HashMap<>();

    private final ConnectivityManager.NetworkCallback defaultCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    observe(network, null, true, false);
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    observe(network, capabilities, true, false);
                }

                @Override
                public void onLost(Network network) {
                    lost(network, true, false);
                }
            };

    private final ConnectivityManager.NetworkCallback wifiCallback = wifiCallback();

    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                Log.i(TAG, "user unlocked; re-checking the uplink");
                evaluate();
            }
        }
    };

    private boolean running;
    private boolean defaultRegistered;
    private boolean wifiRegistered;
    private boolean receiverRegistered;
    private String failure;
    private boolean allowed;
    private String detail = "";

    Uplink(Context context, String ssid, boolean anyNetwork, Listener listener) {
        this.context = context;
        this.manager = context.getSystemService(ConnectivityManager.class);
        this.wifiManager = context.getSystemService(WifiManager.class);
        this.userManager = context.getSystemService(UserManager.class);
        this.ssid = ssid;
        this.anyNetwork = anyNetwork;
        this.listener = listener;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        failure = null;
        try {
            manager.registerDefaultNetworkCallback(defaultCallback, handler);
            defaultRegistered = true;
        } catch (RuntimeException e) {
            failure = "cannot watch the default network: " + message(e);
            Log.w(TAG, failure, e);
        }
        if (failure == null) {
            try {
                manager.registerNetworkCallback(wifiRequest(), wifiCallback, handler);
                wifiRegistered = true;
            } catch (RuntimeException e) {
                failure = "cannot watch Wi-Fi networks: " + message(e);
                Log.w(TAG, failure, e);
            }
        }
        if (failure == null) {
            failure = watchUnlock();
        }
        evaluate();
    }

    /**
     * The Wi-Fi callback, asking for location information where that is the
     * only way {@code getTransportInfo()} returns a readable SSID.
     */
    private ConnectivityManager.NetworkCallback wifiCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override
                public void onAvailable(Network network) {
                    observe(network, null, false, true);
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    observe(network, capabilities, false, true);
                }

                @Override
                public void onLost(Network network) {
                    lost(network, false, true);
                }
            };
        }
        // Before API 31 the constructor has no flags; the transport info was
        // never redacted from an app that holds the location permission.
        return new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                observe(network, null, false, true);
            }

            @Override
            public void onCapabilitiesChanged(
                    Network network, NetworkCapabilities capabilities) {
                observe(network, capabilities, false, true);
            }

            @Override
            public void onLost(Network network) {
                lost(network, false, true);
            }
        };
    }

    private static NetworkRequest wifiRequest() {
        return new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
    }

    /** Watch for the first unlock, when the SSIDs stop being redacted. */
    private String watchUnlock() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(unlockReceiver, filter);
            }
            receiverRegistered = true;
            return null;
        } catch (RuntimeException e) {
            String reason = "cannot watch for the first unlock: " + message(e);
            Log.w(TAG, reason, e);
            return reason;
        }
    }

    private void observe(Network network, NetworkCapabilities capabilities,
            boolean viaDefault, boolean viaWifi) {
        synchronized (networks) {
            if (viaDefault) {
                // The default callback follows one network at a time; the old
                // one may remain connected and still be followed by the Wi-Fi
                // callback, so it loses the mark but not its observation.
                demoteDefault(network);
            }
            Observation observation = networks.get(network);
            if (observation == null) {
                observation = new Observation();
                networks.put(network, observation);
            }
            if (viaDefault) {
                observation.isDefault = true;
            }
            if (viaWifi) {
                observation.viaWifi = true;
            }
            if (capabilities != null) {
                observation.capabilities = capabilities;
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    observation.wifiTransport = true;
                }
                WifiInfo info = wifiInfo(capabilities);
                if (info == null || ssidOf(info) == null) {
                    // Pre-31 the transport copy can still be redacted; the
                    // Wi-Fi service answers with ACCESS_WIFI_STATE held.
                    WifiInfo legacy = legacyWifiInfo();
                    if (legacy != null) {
                        info = legacy;
                    }
                }
                rememberWifiInfo(observation, info);
            } else if (observation.wifi()) {
                rememberWifiInfo(observation, legacyWifiInfo());
            }
        }
        evaluate();
    }

    private void demoteDefault(Network network) {
        for (Iterator<Map.Entry<Network, Observation>> it = networks.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<Network, Observation> entry = it.next();
            if (entry.getKey().equals(network)) {
                continue;
            }
            Observation other = entry.getValue();
            other.isDefault = false;
            if (!other.viaWifi) {
                it.remove();
            }
        }
    }

    private void lost(Network network, boolean viaDefault, boolean viaWifi) {
        synchronized (networks) {
            Observation observation = networks.get(network);
            if (observation == null) {
                return;
            }
            if (viaDefault) {
                observation.isDefault = false;
            }
            if (viaWifi) {
                observation.viaWifi = false;
                observation.wifiTransport = false;
                observation.wifiInfo = null;
            }
            if (!observation.isDefault && !observation.viaWifi) {
                networks.remove(network);
            }
        }
        evaluate();
    }

    /**
     * Keep the best SSID the platform has told us for this network. A callback
     * without {@code FLAG_INCLUDE_LOCATION_INFO} delivers a redacted copy; it
     * must not replace the readable one the Wi-Fi callback delivered.
     */
    private static void rememberWifiInfo(Observation observation, WifiInfo info) {
        if (info == null) {
            return;
        }
        String known = ssidOf(info);
        if (known != null || observation.wifiInfo == null) {
            observation.wifiInfo = info;
        }
    }

    private static WifiInfo wifiInfo(NetworkCapabilities capabilities) {
        Object transport = capabilities.getTransportInfo();
        return transport instanceof WifiInfo ? (WifiInfo) transport : null;
    }

    /**
     * The Wi-Fi service's answer, only where the transport copy is never
     * unredacted. API 31 and later take the callback's SSID instead.
     */
    @SuppressWarnings("deprecation") // getConnectionInfo(): the pre-31 fallback
    private WifiInfo legacyWifiInfo() {
        if (wifiManager == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return null;
        }
        return wifiManager.getConnectionInfo();
    }

    private void evaluate() {
        if (!running) {
            return;
        }
        UplinkPolicy.Verdict verdict;
        if (failure != null) {
            verdict = new UplinkPolicy.Verdict(false, failure);
        } else {
            verdict = UplinkPolicy.decide(ssid, anyNetwork, userLocked(), snapshot());
        }
        if (verdict.allowed == allowed && verdict.detail.equals(detail)) {
            return;
        }
        allowed = verdict.allowed;
        detail = verdict.detail;
        listener.onUplinkChanged(allowed, detail);
    }

    private boolean userLocked() {
        return userManager != null && !userManager.isUserUnlocked();
    }

    private List<UplinkPolicy.Link> snapshot() {
        List<UplinkPolicy.Link> links = new ArrayList<>();
        synchronized (networks) {
            for (Observation observation : networks.values()) {
                links.add(new UplinkPolicy.Link(ssid(observation),
                        observation.wifi(), observation.isDefault));
            }
        }
        return links;
    }

    private static String ssid(Observation observation) {
        return observation.wifiInfo == null ? null : ssidOf(observation.wifiInfo);
    }

    private static String ssidOf(WifiInfo info) {
        String reported = info.getSSID();
        if (reported == null || reported.isEmpty()) {
            return null;
        }
        String plain = stripQuotes(reported);
        if (plain.isEmpty() || plain.startsWith("<")) {
            // WifiInfo.SSID_UNKNOWN, or the empty answer before the first
            // unlock: unknown, so the policy decides what that means.
            return null;
        }
        return plain;
    }

    private static String stripQuotes(String reported) {
        if (reported.length() >= 2 && reported.startsWith("\"") && reported.endsWith("\"")) {
            return reported.substring(1, reported.length() - 1);
        }
        return reported;
    }

    private static String message(RuntimeException failure) {
        String text = failure.getMessage();
        if (text == null || text.isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        return text;
    }

    @Override
    public void close() {
        running = false;
        if (defaultRegistered) {
            defaultRegistered = false;
            try {
                manager.unregisterNetworkCallback(defaultCallback);
            } catch (RuntimeException e) {
                // Never registered, already gone, or the service is stopping.
                Log.d(TAG, "default network callback was already gone", e);
            }
        }
        if (wifiRegistered) {
            wifiRegistered = false;
            try {
                manager.unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException e) {
                Log.d(TAG, "Wi-Fi network callback was already gone", e);
            }
        }
        if (receiverRegistered) {
            receiverRegistered = false;
            try {
                context.unregisterReceiver(unlockReceiver);
            } catch (RuntimeException e) {
                Log.d(TAG, "unlock receiver was already gone", e);
            }
        }
    }
}
