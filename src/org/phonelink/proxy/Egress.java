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

    private final ConnectivityManager manager;
    private final EgressState state = new EgressState();
    private final Handler retries = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private Network cellular;

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
                    boolean carriesInternet = usable(manager.getNetworkCapabilities(network));
                    synchronized (lock) {
                        cellular = network;
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
                    if (usable(capabilities)) {
                        synchronized (lock) {
                            if (network.equals(cellular)) {
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
            LinkProperties properties = manager.getLinkProperties(network);
            String iface = properties == null ? null : properties.getInterfaceName();
            label = (iface == null ? "cellular" : iface)
                    + " (cellular, network " + network + ")";
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
     * the laptop, which forwards back into this proxy.
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

        InetAddress[] addresses;
        try {
            addresses = network.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new Upstream.Failure(Socks5Server.REPLY_HOST_UNREACHABLE,
                    "cannot resolve " + host + " on the cellular network");
        }

        IOException last = null;
        for (InetAddress address : addresses) {
            Socket socket = null;
            try {
                socket = network.getSocketFactory().createSocket();
                socket.connect(new InetSocketAddress(address, port), timeoutMs);
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
