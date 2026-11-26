package org.phonelink.proxy;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

/**
 * One listening proxy: the port it binds, the network it waits for, the bytes it
 * moved, and the state the panel, the tile and the notification read.
 *
 * <p>Asked for by {@link Ports}, which decides how many there are and what a
 * start or a stop does to the others. A port answers the phone's network itself
 * — it binds while the Wi-Fi is the one the proxy was configured for and waits
 * while it is not — and closes everything it owns. What it does not know is
 * where the cellular egress comes from, how many clients there are in total, or
 * that a notification exists: those are the service's, through {@link Host}.
 */
final class Port {

    /** What a port needs from the service it lives in. */
    interface Host {
        /** The context a network callback is registered against. */
        Context context();

        /** The cellular egress the ports share, started on demand. */
        Upstream upstream();

        /** This port's network just became usable: the service's own business. */
        void uplinkUp(Port port);

        /** The total number of clients changed, so the wake lock may have to. */
        void clientsChanged();

        /** This port's state changed: the panel, the tile and the shade. */
        void changed();

        /** This port could not do its job, and does not exist any more. */
        void failed(Port port, String why);
    }

    /**
     * Guards every field below that changes after construction. A connection
     * thread updates {@link #clients} and {@link #state}, the main thread binds,
     * pauses, renames and closes, and the dump reads them; one monitor keeps a
     * client count from being published against a listener that has gone.
     */
    private final Object lock = new Object();
    /** Not final: a rename moves the port to its new name in place. */
    private String name;
    private final ProxyConfig config;
    private final Traffic traffic = new Traffic();
    private final Host host;
    private Uplink uplink;
    private Socks5Server server;
    /** Waiting, paused or listening; the clients count is kept current. */
    private ProxyState state;
    private int clients;
    private boolean closed;

    Port(Host host, String name, ProxyConfig config) {
        this.host = host;
        this.name = name;
        this.config = config;
        this.state = ProxyState.waiting(name);
    }

    String name() {
        synchronized (lock) {
            return name;
        }
    }

    ProxyConfig config() {
        return config;
    }

    Traffic traffic() {
        return traffic;
    }

    int clients() {
        synchronized (lock) {
            return clients;
        }
    }

    /** The live state, not a copy of the last one: the port is the authority. */
    ProxyState state() {
        synchronized (lock) {
            return server == null ? state : ProxyState.listening(name, config.port, clients);
        }
    }

    /** How a log line and the dump name it; an unnamed one is known by its port. */
    String label() {
        synchronized (lock) {
            return name == null ? "port " + config.port : "\"" + name + "\"";
        }
    }

    /**
     * Follow the phone's network: bind while it is the configured Wi-Fi, wait
     * while it is not, and follow it back when it returns.
     */
    void watch() {
        Uplink candidate = new Uplink(host.context(), config.ssid, config.anyNetwork,
                this::onUplinkChanged);
        // Started before it is published: close() can then only find a Uplink
        // that is already running, never one that starts after being closed.
        candidate.start();
        boolean stop;
        synchronized (lock) {
            stop = closed;
            if (!stop) {
                uplink = candidate;
            }
        }
        if (stop) {
            candidate.close();
        }
    }

    private void onUplinkChanged(boolean allowed, String why) {
        synchronized (lock) {
            if (closed) {
                return;
            }
        }
        Log.i(ProxyService.TAG, label() + " uplink " + (allowed ? "allowed" : "paused")
                + ": " + why);
        if (!allowed) {
            pause(why);
            host.changed();
            return;
        }
        host.uplinkUp(this);
        bind();
    }

    /**
     * Bind the port, or say why not; a failure takes the port out of the list.
     *
     * <p>The candidate is filed before it starts accepting, so a client it
     * admits immediately is counted against this server and not ignored as a
     * stale callback from the one before it.
     */
    private boolean bind() {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            if (server != null) {
                return true;
            }
        }
        // Outside the lock: asking for the egress may start it, which is not a
        // state change this port should hold its monitor for.
        Upstream upstream = host.upstream();
        if (upstream == null) {
            // The service is going away and will not start an egress for a
            // listener it is closing.
            return false;
        }
        final Socks5Server[] self = new Socks5Server[1];
        Socks5Server candidate = new Socks5Server(
                config.port, config.user, config.password, upstream, traffic,
                connected -> setClients(self[0], connected),
                message -> Log.i(ProxyService.TAG, message));
        self[0] = candidate;
        IOException failure = null;
        synchronized (lock) {
            if (closed) {
                candidate.close();
                return false;
            }
            if (server != null) {
                candidate.close();
                return true;
            }
            server = candidate;
            try {
                candidate.start();
                state = ProxyState.listening(name, config.port, clients);
            } catch (IOException e) {
                server = null;
                failure = e;
                candidate.close();
            }
        }
        if (failure != null) {
            host.failed(this, "cannot listen on 0.0.0.0:" + config.port + ": "
                    + failure.getMessage());
            return false;
        }
        Log.i(ProxyService.TAG, label() + " listening on 0.0.0.0:" + config.port
                + (config.user.isEmpty() ? " without authentication" : " with authentication"));
        host.changed();
        return true;
    }

    /**
     * Close the listener and keep the counters: the network can come back.
     * The server is detached before it is closed, so the client callbacks its
     * sockets produce see that they are no longer this port's listeners.
     */
    private void unbind() {
        Socks5Server going;
        synchronized (lock) {
            going = server;
            server = null;
            clients = 0;
        }
        if (going != null) {
            going.close();
        }
    }

    private void pause(String why) {
        Socks5Server going;
        synchronized (lock) {
            if (closed) {
                return;
            }
            going = server;
            server = null;
            clients = 0;
            state = ProxyState.paused(name, why);
        }
        if (going != null) {
            going.close();
        }
        // Nobody is connected to a port that is not bound, so the wake lock the
        // service holds for its clients may go with them.
        host.clientsChanged();
    }

    /** Everything this port owns: its socket, its network callback, its clients. */
    void close() {
        Uplink going;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            going = uplink;
            uplink = null;
        }
        if (going != null) {
            going.close();
        }
        unbind();
    }

    /** Another name for the same port; what it is listening on does not move. */
    void rename(String to) {
        synchronized (lock) {
            name = to;
            state = state.listening
                    ? ProxyState.listening(to, config.port, clients)
                    : ProxyState.paused(to, state.reason);
        }
    }

    /**
     * Called from connection threads, once per change. A callback from a server
     * this port has already detached is ignored: it belongs to a listener that
     * stopped and must not rewrite the state of one that replaced it.
     */
    private void setClients(Socks5Server source, int connected) {
        synchronized (lock) {
            if (closed || source == null || source != server) {
                return;
            }
            clients = Math.max(0, connected);
            state = ProxyState.listening(name, config.port, clients);
        }
        host.clientsChanged();
        host.changed();
    }
}
