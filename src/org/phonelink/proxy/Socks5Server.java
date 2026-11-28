package org.phonelink.proxy;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A SOCKS5 server (RFC 1928) with username/password authentication (RFC 1929):
 * the composition and lifecycle owner for one listener.
 *
 * <p>{@code CONNECT} (TCP) and {@code UDP ASSOCIATE} are implemented;
 * {@code BIND} is refused with {@code command not supported}. The handshake is
 * {@link Handshake}, the TCP tunnel {@link TcpRelay}, the UDP relay
 * {@link UdpAssociation} and the framing {@link Socks5Request}/{@link UdpPacket};
 * this class admits and counts clients, tracks what a stop must end, and closes
 * it. It listens on the wildcard address, so the port is the same on the hotspot
 * ({@code ap0}), USB tether ({@code rndis0}) and the AP link ({@code wlan0}).
 *
 * <p>Deliberately free of Android types: the logger and the upstream are seams,
 * so this class and its tests run on a plain JDK.
 */
final class Socks5Server implements Closeable,
        TcpRelay.Pumps, TcpRelay.Sockets, UdpAssociation.Registry {

    private static final int HANDSHAKE_TIMEOUT_MS = 30_000;

    /** A SOCKS5 UDP datagram, header included, may not exceed this. */
    static final int UDP_MAX_DATAGRAM = 64 * 1024;
    /** Datagrams waiting for the upstream sender; a flood is dropped, not buffered. */
    static final int UDP_QUEUE = 256;
    /** An association with no traffic for this long ends, like the control link. */
    static final long UDP_IDLE_MS = 60_000;

    /**
     * The default ceiling: how many connections are served at once when nothing
     * else says otherwise. It is a setting now ({@code max_connections}, 16..1024
     * in {@link ProxyConfig}), so a listener can fit a desktop's flow count
     * without a rebuild; this constant is what the convenience constructor and
     * the tests use. Each live connection costs two threads, so the ceiling
     * stays bounded on purpose; a selector-based relay is issue #37.
     */
    static final int MAX_CONNECTIONS = 256;
    /** How long the other direction may keep draining after one direction ends. */
    static final int RELAY_LINGER_MS = 10_000;
    /** Idle handler threads go away instead of holding the whole ceiling open. */
    private static final int HANDLER_KEEPALIVE_SECONDS = 60;

    /** Called from connection threads. */
    interface Listener {
        void onClientsChanged(int clients);
    }

    /** Where diagnostics go on the phone; the tests pass a no-op. */
    interface Logger {
        void log(String message);
    }

    private final int port;
    private final String user;
    private final String password;
    /** This listener's counters: one instance per running proxy. */
    private final Traffic traffic;
    private final Upstream upstream;
    private final Listener listener;
    private final Logger logger;
    private final AtomicInteger clients = new AtomicInteger();
    /** Accepted and upstream sockets, so close() can end every tunnel. */
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    /** Live UDP associations, so a stop ends them and frees their sockets. */
    private final Set<UdpAssociation> associations = ConcurrentHashMap.newKeySet();
    /** Relay threads in flight; close() interrupts them as well as their sockets. */
    private final Set<Thread> pumps = ConcurrentHashMap.newKeySet();
    /** Guards close() and what is admitted after it. */
    private final Object lifecycle = new Object();
    /** One handler per live connection; no queue, so saturation is refused at once. */
    private final ThreadPoolExecutor handlers;
    /** One shared thread that closes a half-closed relay after its linger. */
    private final ScheduledThreadPoolExecutor linger;
    private final TcpRelay relay;
    private final UdpAssociation.Settings udpSettings;
    private final int maxConnections;
    private volatile ServerSocket server;
    private volatile boolean running;
    /** Guarded by {@link #lifecycle}: close() has already run. */
    private boolean closed;

    /** the bound port, or 0 when the server is not listening */
    int port() {
        ServerSocket socket = server;
        return socket == null ? 0 : socket.getLocalPort();
    }

    Socks5Server(
            int port,
            String user,
            String password,
            Upstream upstream,
            Traffic traffic,
            Listener listener,
            Logger logger) {
        this(port, user, password, upstream, traffic, listener, logger,
                MAX_CONNECTIONS, RELAY_LINGER_MS);
    }

    /** The ceiling and linger are parameters so a test can fill a small pool. */
    Socks5Server(
            int port,
            String user,
            String password,
            Upstream upstream,
            Traffic traffic,
            Listener listener,
            Logger logger,
            int maxConnections,
            long relayLingerMs) {
        this(port, user, password, upstream, traffic, listener, logger,
                maxConnections, relayLingerMs, UDP_MAX_DATAGRAM, UDP_QUEUE, UDP_IDLE_MS);
    }

    /** The UDP bounds are parameters too, so a test can fill a small one cheaply. */
    Socks5Server(
            int port,
            String user,
            String password,
            Upstream upstream,
            Traffic traffic,
            Listener listener,
            Logger logger,
            int maxConnections,
            long relayLingerMs,
            int udpMaxDatagram,
            int udpQueue,
            long udpIdleMs) {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("at least one connection must be served");
        }
        if (relayLingerMs < 0) {
            throw new IllegalArgumentException("the relay linger cannot be negative");
        }
        if (udpMaxDatagram < 8 || udpQueue < 1 || udpIdleMs < 1) {
            throw new IllegalArgumentException("the UDP bounds must be positive");
        }
        this.port = port;
        this.user = user;
        this.password = password;
        this.upstream = upstream;
        this.traffic = traffic == null ? new Traffic() : traffic;
        this.listener = listener;
        this.logger = logger;
        this.maxConnections = maxConnections;
        this.udpSettings = new UdpAssociation.Settings(udpMaxDatagram, udpQueue, udpIdleMs);
        handlers = new ThreadPoolExecutor(
                maxConnections, maxConnections,
                HANDLER_KEEPALIVE_SECONDS, TimeUnit.SECONDS,
                // No queue: an accepted client either gets a handler now or is
                // refused, never parked until a slot happens to free up.
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "socks5-handler");
                    thread.setDaemon(true);
                    return thread;
                });
        handlers.allowCoreThreadTimeOut(true);
        linger = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "socks5-linger");
            thread.setDaemon(true);
            return thread;
        });
        // A cancelled linger is a relay that finished on its own; drop its
        // socket references now instead of leaving them queued for the delay.
        linger.setRemoveOnCancelPolicy(true);
        relay = new TcpRelay(upstream, this.traffic, linger, relayLingerMs, this, this, logger);
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    /** Bind and start accepting. Throws if the port is taken. */
    void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port), Math.max(50, maxConnections));
        } catch (IOException e) {
            // A failed bind is not a reason to leak the descriptor.
            Closeables.closeQuietly(socket);
            throw e;
        }
        synchronized (lifecycle) {
            if (closed) {
                Closeables.closeQuietly(socket);
                throw new IOException("the server is already closed");
            }
            server = socket;
            running = true;
        }
        Thread acceptor = new Thread(this::acceptLoop, "socks5-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket listening;
            synchronized (lifecycle) {
                if (closed || !running) {
                    return;
                }
                listening = server;
            }
            // close() may have cleared it between the check and the read.
            if (listening == null) {
                return;
            }
            Socket client;
            try {
                client = listening.accept();
            } catch (IOException e) {
                // A closed listening socket is how a stop looks; anything else
                // is a listener that cannot accept any more either.
                if (!closed) {
                    log("accept failed: " + e.getMessage());
                }
                return;
            } catch (RuntimeException e) {
                if (!closed) {
                    log("accept failed: " + e);
                }
                return;
            }
            if (!track(client)) {
                Closeables.resetQuietly(client);
                return;
            }
            try {
                handlers.execute(() -> handle(client));
            } catch (RejectedExecutionException e) {
                // Bounded on purpose: refusing a client beats exhausting the
                // process with threads a LAN client asked for.
                untrack(client);
                Closeables.resetQuietly(client);
                log("refused a client: " + maxConnections + " connections already in use");
            }
        }
    }

    /** Admit a socket to the set close() closes, or refuse it after a close. */
    @Override
    public boolean track(Socket socket) {
        synchronized (lifecycle) {
            if (closed) {
                return false;
            }
            sockets.add(socket);
            return true;
        }
    }

    @Override
    public void untrack(Socket socket) {
        sockets.remove(socket);
    }

    @Override
    public boolean admit(Thread pump) {
        synchronized (lifecycle) {
            if (closed) {
                return false;
            }
            pumps.add(pump);
            return true;
        }
    }

    @Override
    public void release(Thread pump) {
        pumps.remove(pump);
    }

    @Override
    public boolean admit(UdpAssociation association) {
        synchronized (lifecycle) {
            if (closed) {
                return false;
            }
            associations.add(association);
            return true;
        }
    }

    @Override
    public void release(UdpAssociation association) {
        associations.remove(association);
    }

    private void handle(Socket client) {
        int count;
        synchronized (lifecycle) {
            if (closed) {
                sockets.remove(client);
                Closeables.resetQuietly(client);
                return;
            }
            count = clients.incrementAndGet();
        }
        listener.onClientsChanged(count);
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            Handshake handshake = new Handshake(user, password, logger);

            if (!handshake.negotiate(in, out)) {
                return;
            }
            Socks5Request request = handshake.readRequest(in, out);
            if (request == null) {
                return;
            }
            if (request.command == Handshake.COMMAND_CONNECT) {
                relay.serve(client, out, request);
            } else if (request.command == Handshake.COMMAND_UDP_ASSOCIATE) {
                UdpAssociation.serve(upstream, client, out, request, udpSettings, logger, this);
            } else {
                Handshake.writeReply(out, Handshake.REPLY_COMMAND_NOT_SUPPORTED, null);
            }
        } catch (IOException e) {
            log("connection ended: " + e.getMessage());
        } finally {
            untrack(client);
            Closeables.closeBoundedQuietly(client);
            listener.onClientsChanged(clients.decrementAndGet());
        }
    }

    /**
     * Stop listening and end every tunnel this server owns.
     *
     * <p>Idempotent: the second call finds the listening socket gone and does
     * nothing. The accepted and upstream sockets are closed first, which
     * unblocks the relay reads, and the pump threads are interrupted as a
     * second way out for anything not blocked on a socket.
     */
    @Override
    public void close() {
        ServerSocket listening;
        List<Socket> tunnels;
        List<UdpAssociation> udp;
        List<Thread> relays;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            listening = server;
            server = null;
            tunnels = new ArrayList<>(sockets);
            sockets.clear();
            udp = new ArrayList<>(associations);
            associations.clear();
            relays = new ArrayList<>(pumps);
            pumps.clear();
        }
        Closeables.closeQuietly(listening);
        for (UdpAssociation association : udp) {
            association.close();
        }
        for (Socket socket : tunnels) {
            Closeables.resetQuietly(socket);
        }
        for (Thread relay : relays) {
            relay.interrupt();
        }
        handlers.shutdownNow();
        linger.shutdownNow();
    }
}
