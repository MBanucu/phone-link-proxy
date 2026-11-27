package org.phonelink.proxy;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;

/**
 * Opens upstream sockets on the cellular network.
 *
 * <p>While the phone is a Wi-Fi client of the laptop's access point, its default
 * network is that Wi-Fi link, and the gateway is the laptop. A socket opened on
 * the default route therefore goes straight back to the host that asked for it.
 * The app this replaces (5GProxy) holds an active {@code NetworkRequest} for
 * {@code TRANSPORT_CELLULAR} and binds its egress to the result, which is what
 * makes the tunnel leave the phone at all; this class does the same.
 *
 * <p>A request made before mobile data exists — at boot, in airplane mode, while
 * the cellular link is down — is answered once with {@code onUnavailable} and
 * then stops. The request is therefore made again with a bounded backoff while
 * the service runs, and again when the network is lost or stops carrying the
 * internet. {@link EgressState} holds that schedule; this class only turns its
 * transitions into {@code ConnectivityManager} calls.
 */
final class Egress implements Upstream, Closeable {

    private static final String TAG = "phone-link-proxy";

    /** Upper bound on waiting for a cellular network to come up, per CONNECT. */
    private static final long AWAIT_MS = 10_000;

    /**
     * How much of the CONNECT budget is too little to start another address:
     * less than this and the attempt would only report the deadline as a
     * timeout of its own.
     */
    private static final long MIN_CONNECT_MS = 250;

    private final ConnectivityManager manager;
    private final EgressState state = new EgressState();
    private final Handler retries = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private Network cellular;
    /**
     * Whether the guard has already printed its one line for the current
     * non-cellular resolution. A cellular link arriving again clears it.
     */
    private boolean guardWarned;

    /** Request again after the backoff; a close or an available network cancels it. */
    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (state.retryReady(SystemClock.elapsedRealtime())) {
                    requestLocked();
                }
            }
        }
    };

    private final ConnectivityManager.NetworkCallback callback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                    EgressPolicy.Route route = route(network, capabilities);
                    if (capabilities != null && !route.allowed()) {
                        // The request is for cellular, so this is already wrong.
                        // Do not cache it: a socket on it would loop.
                        refuse(network, route.reason);
                        return;
                    }
                    boolean carriesInternet = usable(capabilities);
                    synchronized (lock) {
                        cellular = network;
                        guardWarned = false;
                        if (carriesInternet) {
                            state.available();
                        } else {
                            // It is a cellular network, but validation may still
                            // be in flight; do not restart the backoff for it.
                            state.present();
                        }
                        retries.removeCallbacks(retry);
                        lock.notifyAll();
                    }
                }

                @Override
                public void onLost(Network network) {
                    synchronized (lock) {
                        if (!network.equals(cellular)) {
                            return;
                        }
                        cellular = null;
                        scheduleLocked(state.lost(SystemClock.elapsedRealtime()));
                        lock.notifyAll();
                    }
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    EgressPolicy.Route route = route(network, capabilities);
                    if (!route.allowed()) {
                        boolean current;
                        synchronized (lock) {
                            current = network.equals(cellular);
                        }
                        if (current) {
                            // It was resolved for this request but is not
                            // cellular: refuse it rather than open a socket that
                            // would come back down the laptop's link.
                            refuse(network, route.reason);
                        }
                        return;
                    }
                    if (usable(capabilities)) {
                        synchronized (lock) {
                            if (network.equals(cellular)) {
                                guardWarned = false;
                                state.available();
                            }
                        }
                        return;
                    }
                    synchronized (lock) {
                        if (!network.equals(cellular)) {
                            return;
                        }
                        // The network is still there, but a CONNECT on it cannot
                        // leave the phone: drop it and look for another one.
                        cellular = null;
                        scheduleLocked(state.capabilityLost(SystemClock.elapsedRealtime()));
                        lock.notifyAll();
                    }
                }

                @Override
                public void onUnavailable() {
                    synchronized (lock) {
                        cellular = null;
                        scheduleLocked(state.unavailable(SystemClock.elapsedRealtime()));
                        lock.notifyAll();
                    }
                }
            };

    Egress(Context context) {
        manager = context.getSystemService(ConnectivityManager.class);
    }

    /** Ask for a cellular network and keep asking while the service runs. */
    void start() {
        synchronized (lock) {
            if (!state.start()) {
                return;
            }
            requestLocked();
        }
    }

    /**
     * The network the tunnel leaves by, for the service's `dumpsys`.
     *
     * <p>Worth printing because it is not the phone's default route: this app
     * pins its egress to cellular so that a socket does not come back down the
     * Wi-Fi link the requester is on. A person reading a failed request, or a
     * harness asking "was it the phone?", cannot see that from the outside —
     * `ping` follows the default route, this name does not. When there is no
     * network, the line says why: unavailable, lost, or waiting for a retry.
     */
    String describe() {
        Network network;
        synchronized (lock) {
            network = cellular;
        }
        String label = null;
        if (network != null) {
            label = EgressPolicy.describe(
                    link(network, manager.getNetworkCapabilities(network)));
        }
        synchronized (lock) {
            return state.describe(label, SystemClock.elapsedRealtime());
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (state.closed()) {
                return;
            }
            state.close();
            cellular = null;
            retries.removeCallbacks(retry);
            // A CONNECT waiting here is about to fail; do not make it wait out
            // the whole timeout for a network that will no longer be asked for.
            lock.notifyAll();
        }
        try {
            manager.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException e) {
            // Already gone; nothing to unbind.
            Log.d(TAG, "network callback was not registered", e);
        }
    }

    /**
     * Connect to {@code host:port} on the cellular network.
     *
     * <p>The name is resolved on that network too ({@code Network.getAllByName}):
     * the system resolver would answer over the Wi-Fi link, whose DNS server is
     * the laptop, which forwards back into this proxy. The timeout is one
     * deadline from the resolve onwards, shared by every resolved address, so a
     * name with several addresses cannot take {@code N × timeoutMs}. An address
     * is only started while at least {@link #MIN_CONNECT_MS} of the budget is
     * left; the first one that connects wins.
     */
    @Override
    public Socket connect(String host, int port, int timeoutMs) throws Failure {
        Network network = awaitCellular();
        if (network == null) {
            String reason;
            synchronized (lock) {
                reason = state.reason(SystemClock.elapsedRealtime());
            }
            throw new Upstream.Failure(Socks5Server.REPLY_NETWORK_UNREACHABLE,
                    "no usable cellular egress for " + host + ":" + port + ": " + reason);
        }

        // The guard: whatever was resolved has to be cellular. A Wi-Fi/AP link
        // here is the hairpin, and opening the socket would send the request
        // back to the laptop that made it. Fail closed, with one clear line.
        String refusal = EgressPolicy.refusal(
                link(network, manager.getNetworkCapabilities(network)));
        if (refusal != null) {
            refuse(network, refusal);
            throw new Upstream.Failure(Socks5Server.REPLY_NETWORK_UNREACHABLE,
                    "refusing " + host + ":" + port + ": " + refusal);
        }

        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        InetAddress[] addresses;
        try {
            addresses = network.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new Upstream.Failure(Socks5Server.REPLY_HOST_UNREACHABLE,
                    "cannot resolve " + host + " on the cellular network");
        }

        IOException last = null;
        for (InetAddress address : addresses) {
            long left = deadline - SystemClock.elapsedRealtime();
            if (left < MIN_CONNECT_MS) {
                if (last == null) {
                    last = new SocketTimeoutException(
                            "the connect budget ran out before " + address.getHostAddress());
                }
                break;
            }
            Socket socket = null;
            try {
                socket = network.getSocketFactory().createSocket();
                socket.connect(new InetSocketAddress(address, port), (int) left);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException e) {
                last = e;
                Closeables.closeQuietly(socket);
            }
        }
        throw new Upstream.Failure(replyFor(last),
                "cannot reach " + host + ":" + port + " over cellular: " + last);
    }

    /** The policy's answer for one network the platform just described. */
    private EgressPolicy.Route route(Network network, NetworkCapabilities capabilities) {
        return EgressPolicy.select(Collections.singletonList(link(network, capabilities)));
    }

    /**
     * Drop a link that must not carry the tunnel, ask for another one, and
     * print the guard's one line. The line repeats only after a cellular link
     * has been accepted again, so a client retrying cannot flood the log.
     */
    private void refuse(Network network, String reason) {
        boolean warn;
        synchronized (lock) {
            warn = !guardWarned;
            guardWarned = true;
            if (network != null && (cellular == null || network.equals(cellular))) {
                cellular = null;
                scheduleLocked(state.capabilityLost(SystemClock.elapsedRealtime()));
                lock.notifyAll();
            }
        }
        if (warn) {
            Log.e(TAG, "refusing upstream egress: " + reason);
        }
    }

    /** One {@code Network} as the Android-free policy sees it. */
    private EgressPolicy.Link link(Network network, NetworkCapabilities capabilities) {
        LinkProperties properties = manager.getLinkProperties(network);
        String iface = properties == null ? null : properties.getInterfaceName();
        boolean cellular = capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        boolean wifi = capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean internet = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean validated = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean isDefault = network.equals(manager.getActiveNetwork());
        return new EgressPolicy.Link(
                networkId(network), iface, cellular, wifi, internet, validated, isDefault);
    }

    /** {@code Network(100)} as {@code 100}, which is what the dump should say. */
    private static String networkId(Network network) {
        String text = String.valueOf(network);
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open >= 0 && close > open) {
            return text.substring(open + 1, close);
        }
        return text;
    }

    /**
     * Ask the platform for a cellular network. Called with {@link #lock} held,
     * once at start and again for each retry; the previous registration is
     * removed first so the callback is never registered twice.
     */
    private void requestLocked() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        try {
            manager.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException e) {
            // The first request, or onUnavailable already released it.
            Log.d(TAG, "network callback was not registered", e);
        }
        manager.requestNetwork(request, callback);
    }

    private void scheduleLocked(long delayMs) {
        if (delayMs < 0) {
            return;
        }
        retries.removeCallbacks(retry);
        retries.postDelayed(retry, delayMs);
    }

    private static boolean usable(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private Network awaitCellular() {
        long deadline = SystemClock.elapsedRealtime() + AWAIT_MS;
        synchronized (lock) {
            while (cellular == null) {
                if (state.closed()) {
                    return null;
                }
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) {
                    return null;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return cellular;
        }
    }

    private static int replyFor(IOException failure) {
        if (failure instanceof SocketTimeoutException) {
            return Socks5Server.REPLY_HOST_UNREACHABLE;
        }
        if (failure instanceof ConnectException) {
            return Socks5Server.REPLY_CONNECTION_REFUSED;
        }
        return Socks5Server.REPLY_GENERAL_FAILURE;
    }

}
