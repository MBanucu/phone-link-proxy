package org.phonelink.proxy;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A SOCKS5 server (RFC 1928) with username/password authentication (RFC 1929).
 *
 * <p>Only {@code CONNECT} is implemented; {@code BIND} and {@code UDP ASSOCIATE}
 * are refused with {@code command not supported}, which is what the tunnel needs
 * and what keeps the surface small.
 *
 * <p>It listens on the wildcard address, so the port is the same on the hotspot
 * ({@code ap0}), USB tether ({@code rndis0}) and the AP link ({@code wlan0}),
 * whatever addresses those interfaces get and whenever they appear.
 *
 * <p>A CONNECT gets {@link #CONNECT_TIMEOUT_MS} (4 s), not 15: a cellular
 * handshake that is going to fail either fails at once or is lost in the
 * carrier's NAT, and a lost one surfaces as a timeout. When the upstream
 * reports that timeout as {@code host unreachable}, the CONNECT is tried once
 * more with a fresh socket and therefore a fresh source port, which is what
 * recovers the ~1–4% of new connections that stalled for 5 s (#42). A refused
 * connection or a missing cellular network is final and is not retried; a name
 * that does not resolve carries the same host-unreachable code and is retried
 * once, which costs only a second lookup. The worst case for one SOCKS CONNECT
 * is {@code CONNECT_ATTEMPTS × CONNECT_TIMEOUT_MS}.
 *
 * <p>Deliberately free of Android types: the logger and the upstream are seams,
 * so this class and its tests run on a plain JDK.
 */
final class Socks5Server implements Closeable {

    static final int REPLY_SUCCEEDED = 0x00;
    static final int REPLY_GENERAL_FAILURE = 0x01;
    static final int REPLY_NETWORK_UNREACHABLE = 0x03;
    static final int REPLY_HOST_UNREACHABLE = 0x04;
    static final int REPLY_CONNECTION_REFUSED = 0x05;
    static final int REPLY_COMMAND_NOT_SUPPORTED = 0x07;
    static final int REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

    private static final int VERSION = 0x05;
    private static final int METHOD_NO_AUTH = 0x00;
    private static final int METHOD_USER_PASSWORD = 0x02;
    private static final int METHOD_NONE_ACCEPTABLE = 0xFF;
    private static final int COMMAND_CONNECT = 0x01;
    private static final int ADDRESS_IPV4 = 0x01;
    private static final int ADDRESS_DOMAIN = 0x03;
    private static final int ADDRESS_IPV6 = 0x04;

    private static final int HANDSHAKE_TIMEOUT_MS = 30_000;
    /**
     * One upstream attempt. A lost handshake on the cellular uplink is a
     * timeout here, and it is better to surface it in 4 s than to make the
     * page wait out 15 s for a connection that is never going to complete.
     */
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    /** One retry with a fresh socket is enough: a new source port gets a new NAT mapping. */
    private static final int CONNECT_ATTEMPTS = 2;

    /**
     * The default ceiling: how many connections are served at once when nothing
     * else says otherwise. It is a setting now ({@code max_connections}, 16..1024
     * in {@link ProxyConfig}), so a listener can fit a desktop's flow count
     * without a rebuild; this constant is what the convenience constructor and
     * the tests use.
     *
     * <p>Each live connection costs two threads: the handler thread that runs
     * the handshake and then pumps client-to-upstream, and one relay thread
     * that pumps upstream-to-client. So the ceiling is
     * {@code 2 * maxConnections + 1 accept + 1 shared linger = 514} threads at
     * the default 256, against {@code 3 * 32 = 96} for the old three-thread
     * relay. The handler threads time out after a minute, so idle bursts do not
     * keep the budget reserved. The ceiling stays bounded on purpose: a client
     * cannot make the process create threads without end, whatever the setting
     * allows.
     *
     * <p>A desktop browser alone can hold about a hundred keep-alive flows; 128
     * was reached by Chrome and a handful of tools, so the next refusal hit a
     * normal desktop. Two threads per connection at the default 256 stay inside
     * the phone's limits (RLIMIT_NPROC is 61417), while a selector-based relay
     * that does not need a thread pair per idle flow is the long-term fix,
     * tracked as issue #37.
     */
    static final int MAX_CONNECTIONS = 256;
    /**
     * How long the other direction may keep draining after one direction ends.
     * A client that half-closes while the origin keeps the connection alive
     * must not pin a handler forever; a failed direction closes both at once.
     */
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

    private static final class Request {
        final int command;
        final String host;
        final int port;

        Request(int command, String host, int port) {
            this.command = command;
            this.host = host;
            this.port = port;
        }
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
    /** Relay threads in flight; close() interrupts them as well as their sockets. */
    private final Set<Thread> pumps = ConcurrentHashMap.newKeySet();
    /** Guards close() and what is admitted after it. */
    private final Object lifecycle = new Object();
    /** One handler per live connection; no queue, so saturation is refused at once. */
    private final ThreadPoolExecutor handlers;
    /** One shared thread that closes a half-closed relay after its linger. */
    private final ScheduledThreadPoolExecutor linger;
    private final int maxConnections;
    private final long relayLingerMs;
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
        if (maxConnections < 1) {
            throw new IllegalArgumentException("at least one connection must be served");
        }
        if (relayLingerMs < 0) {
            throw new IllegalArgumentException("the relay linger cannot be negative");
        }
        this.port = port;
        this.user = user;
        this.password = password;
        this.upstream = upstream;
        this.traffic = traffic == null ? new Traffic() : traffic;
        this.listener = listener;
        this.logger = logger;
        this.maxConnections = maxConnections;
        this.relayLingerMs = relayLingerMs;
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
    private boolean track(Socket socket) {
        synchronized (lifecycle) {
            if (closed) {
                return false;
            }
            sockets.add(socket);
            return true;
        }
    }

    private void untrack(Socket socket) {
        sockets.remove(socket);
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
        Socket connected = null;
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            if (!negotiate(in, out)) {
                return;
            }
            Request request = readRequest(in, out);
            if (request == null) {
                return;
            }
            if (request.command != COMMAND_CONNECT) {
                writeReply(out, REPLY_COMMAND_NOT_SUPPORTED, null);
                return;
            }
            try {
                connected = openUpstream(request.host, request.port);
            } catch (Upstream.Failure failure) {
                log(failure.getMessage());
                writeReply(out, failure.reply, null);
                return;
            }
            // The upstream half is a tunnel too: a stop has to close it.
            if (!track(connected)) {
                Closeables.resetQuietly(connected);
                return;
            }
            writeReply(out, REPLY_SUCCEEDED, connected);
            client.setSoTimeout(0);
            relay(client, connected);
        } catch (IOException e) {
            log("connection ended: " + e.getMessage());
        } finally {
            untrack(client);
            if (connected != null) {
                untrack(connected);
            }
            Closeables.closeBoundedQuietly(connected);
            Closeables.closeBoundedQuietly(client);
            listener.onClientsChanged(clients.decrementAndGet());
        }
    }

    /**
     * Open the upstream, retrying once when the handshake was lost.
     *
     * <p>Only {@code host unreachable} is retried: that is the reply
     * {@link Egress} uses for a timeout, which on the cellular uplink is the
     * carrier dropping the handshake's NAT mapping. Each attempt calls
     * {@link Upstream#connect} afresh, so the retry gets a new socket, a new
     * source port and therefore a new mapping. A refusal or a missing egress
     * network is reported as it is; a name that does not resolve shares this
     * reply code and is simply looked up once more.
     */
    private Socket openUpstream(String host, int port) throws Upstream.Failure {
        for (int attempt = 1; ; attempt++) {
            try {
                return upstream.connect(host, port, CONNECT_TIMEOUT_MS);
            } catch (Upstream.Failure failure) {
                if (attempt >= CONNECT_ATTEMPTS
                        || failure.reply != REPLY_HOST_UNREACHABLE) {
                    throw failure;
                }
                log("retrying " + host + ":" + port + " after " + CONNECT_TIMEOUT_MS
                        + " ms: " + failure.getMessage());
            }
        }
    }

    /** Method negotiation; returns false when the client cannot be served. */
    private boolean negotiate(DataInputStream in, OutputStream out) throws IOException {
        if (in.readUnsignedByte() != VERSION) {
            return false;
        }
        int offered = in.readUnsignedByte();
        byte[] methods = new byte[offered];
        in.readFully(methods);

        boolean authenticate = !user.isEmpty() && !password.isEmpty();
        int chosen = -1;
        for (byte method : methods) {
            int value = method & 0xFF;
            if (authenticate ? value == METHOD_USER_PASSWORD : value == METHOD_NO_AUTH) {
                chosen = value;
                break;
            }
        }
        if (chosen < 0) {
            out.write(new byte[] {VERSION, (byte) METHOD_NONE_ACCEPTABLE});
            out.flush();
            return false;
        }
        out.write(new byte[] {VERSION, (byte) chosen});
        out.flush();
        return chosen != METHOD_USER_PASSWORD || authenticate(in, out);
    }

    private boolean authenticate(DataInputStream in, OutputStream out) throws IOException {
        if (in.readUnsignedByte() != 0x01) {
            return false;
        }
        byte[] givenUser = new byte[in.readUnsignedByte()];
        in.readFully(givenUser);
        byte[] givenPassword = new byte[in.readUnsignedByte()];
        in.readFully(givenPassword);

        boolean userMatches =
                MessageDigest.isEqual(givenUser, user.getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches =
                MessageDigest.isEqual(givenPassword, password.getBytes(StandardCharsets.UTF_8));
        // Both comparisons always run, so a wrong user does not answer faster
        // than a wrong password.
        boolean granted = userMatches & passwordMatches;

        out.write(new byte[] {0x01, (byte) (granted ? 0x00 : 0x01)});
        out.flush();
        if (!granted) {
            log("rejected a client with bad credentials");
        }
        return granted;
    }

    private Request readRequest(DataInputStream in, OutputStream out) throws IOException {
        if (in.readUnsignedByte() != VERSION) {
            return null;
        }
        int command = in.readUnsignedByte();
        in.readUnsignedByte(); // reserved
        int addressType = in.readUnsignedByte();

        String host;
        switch (addressType) {
            case ADDRESS_IPV4:
            case ADDRESS_IPV6:
                byte[] address = new byte[addressType == ADDRESS_IPV4 ? 4 : 16];
                in.readFully(address);
                host = InetAddress.getByAddress(address).getHostAddress();
                break;
            case ADDRESS_DOMAIN:
                byte[] name = new byte[in.readUnsignedByte()];
                in.readFully(name);
                host = new String(name, StandardCharsets.UTF_8);
                if (host.isEmpty()) {
                    writeReply(out, REPLY_GENERAL_FAILURE, null);
                    return null;
                }
                break;
            default:
                writeReply(out, REPLY_ADDRESS_TYPE_NOT_SUPPORTED, null);
                return null;
        }
        int port = in.readUnsignedShort();
        return new Request(command, host, port);
    }

    private static void writeReply(OutputStream out, int reply, Socket bound) throws IOException {
        byte[] address = new byte[4];
        int port = 0;
        if (bound != null) {
            InetAddress local = bound.getLocalAddress();
            if (local != null) {
                address = local.getAddress();
            }
            port = bound.getLocalPort();
        }
        ByteArrayOutputStream replyBytes = new ByteArrayOutputStream(22);
        replyBytes.write(VERSION);
        replyBytes.write(reply);
        replyBytes.write(0x00);
        replyBytes.write(address.length == 16 ? ADDRESS_IPV6 : ADDRESS_IPV4);
        replyBytes.write(address, 0, address.length);
        replyBytes.write((port >> 8) & 0xFF);
        replyBytes.write(port & 0xFF);
        out.write(replyBytes.toByteArray());
        out.flush();
    }

    /**
     * Shovel bytes both ways until both directions are done, then close.
     *
     * <p>The handler thread drives client-to-upstream and one relay thread
     * drives upstream-to-client, so a live connection costs two threads rather
     * than three. When a direction ends cleanly the other one gets
     * {@link #RELAY_LINGER_MS} to drain, then both sockets are closed; a failed
     * direction ends the tunnel at once. That bound is what stops a client
     * half-close from pinning a handler while the origin holds its side open.
     */
    private void relay(Socket client, Socket upstream) {
        RelayEnd end = new RelayEnd(client, upstream);
        CountDownLatch otherDone = new CountDownLatch(1);

        Thread download = new Thread(() -> {
            boolean clean = copy(upstream, client, false);
            end.ended(clean);
            otherDone.countDown();
        }, "socks5-relay");
        download.setDaemon(true);
        synchronized (lifecycle) {
            if (closed) {
                // The tunnel is already ending; do not create a thread that
                // would only block on a socket close() has closed.
                end.closeBoth();
                return;
            }
            pumps.add(download);
        }
        download.start();
        try {
            boolean clean = copy(client, upstream, true);
            end.ended(clean);
            try {
                otherDone.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            pumps.remove(download);
            end.cancel();
            end.closeBoth();
        }
    }

    /**
     * Copy one direction to its end.
     *
     * @param uploaded whether this half moves client bytes upstream, which is
     *     only needed for the counters the panel graphs
     * @return true when the source reached end-of-stream, false when the socket
     *     failed
     */
    private boolean copy(Socket from, Socket to, boolean uploaded) {
        byte[] buffer = new byte[32 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (uploaded) {
                    traffic.add(read, 0);
                } else {
                    traffic.add(0, read);
                }
            }
            // Half close: the other direction may still have data to deliver.
            to.shutdownOutput();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The shared end of one relay: whichever direction stops first decides how
     * long the other may keep running.
     */
    private final class RelayEnd {
        private final Socket client;
        private final Socket upstream;
        private final AtomicReference<ScheduledFuture<?>> reaper = new AtomicReference<>();

        RelayEnd(Socket client, Socket upstream) {
            this.client = client;
            this.upstream = upstream;
        }

        /**
         * One direction stopped. A clean half-close gives the other direction
         * the linger; a failure closes both now so neither pump is left behind.
         */
        void ended(boolean clean) {
            if (!clean) {
                closeBoth();
                return;
            }
            if (reaper.get() != null) {
                return;
            }
            try {
                ScheduledFuture<?> scheduled = linger.schedule(
                        this::closeBoth, relayLingerMs, TimeUnit.MILLISECONDS);
                if (!reaper.compareAndSet(null, scheduled)) {
                    scheduled.cancel(false);
                }
            } catch (RejectedExecutionException e) {
                // close() has already shut the scheduler and the sockets down.
                closeBoth();
            }
        }

        /** The other direction finished inside its linger; forget the reaper. */
        void cancel() {
            ScheduledFuture<?> scheduled = reaper.getAndSet(null);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }

        void closeBoth() {
            Closeables.closeBoundedQuietly(client);
            Closeables.closeBoundedQuietly(upstream);
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
            relays = new ArrayList<>(pumps);
            pumps.clear();
        }
        Closeables.closeQuietly(listening);
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
