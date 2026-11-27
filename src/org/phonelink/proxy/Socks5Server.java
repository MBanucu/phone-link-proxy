package org.phonelink.proxy;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A SOCKS5 server (RFC 1928) with username/password authentication (RFC 1929).
 *
 * <p>{@code CONNECT} (TCP) and {@code UDP ASSOCIATE} are implemented;
 * {@code BIND} is refused with {@code command not supported}. A UDP association
 * is one relay socket on the client side and one datagram socket from
 * {@link Upstream} on the cellular side. It follows RFC 1928's framing
 * ({@code RSV RSV FRAG ATYP DST.ADDR DST.PORT DATA}), drops fragments and
 * malformed datagrams, bounds both a datagram and the queue behind it, and ends
 * with the control connection, an idle timeout, or a listener stop.
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
    private static final int COMMAND_UDP_ASSOCIATE = 0x03;
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
        final int addressType;
        final String host;
        final int port;

        Request(int command, int addressType, String host, int port) {
            this.command = command;
            this.addressType = addressType;
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
    private final int maxConnections;
    private final long relayLingerMs;
    private final int udpMaxDatagram;
    private final int udpQueue;
    private final long udpIdleMs;
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
        this.relayLingerMs = relayLingerMs;
        this.udpMaxDatagram = udpMaxDatagram;
        this.udpQueue = udpQueue;
        this.udpIdleMs = udpIdleMs;
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
            if (request.command == COMMAND_UDP_ASSOCIATE) {
                serveUdp(client, out, request);
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
     * Serve one UDP association: open both sockets, reply with the relay's
     * bound address, then hold the control connection until the client closes
     * it. The datagrams themselves are carried by the association's own
     * threads, because this thread has to stay on the TCP read: closing the
     * control connection is what ends the association (RFC 1928 §7).
     */
    private void serveUdp(Socket client, OutputStream out, Request request) throws IOException {
        UdpAssociation association;
        try {
            association = openUdpAssociation(client, request);
        } catch (Upstream.Failure failure) {
            log(failure.getMessage());
            writeReply(out, failure.reply, null);
            return;
        }
        if (association == null) {
            // close() won the race; the listener is stopping, so nothing here
            // may be admitted.
            writeReply(out, REPLY_GENERAL_FAILURE, null);
            return;
        }
        try {
            association.start();
            writeReplyTo(out, REPLY_SUCCEEDED, association.replyAddress(), association.replyPort());
            client.setSoTimeout(0);
            // Nothing is expected on the control connection after the reply;
            // reading it is only how its close is noticed. Bytes are ignored.
            InputStream control = client.getInputStream();
            byte[] discard = new byte[256];
            while (control.read(discard) != -1) {
                // The association ends when this returns -1.
            }
        } finally {
            association.close();
        }
    }

    /**
     * Bind the client-facing relay socket and the cellular datagram socket.
     * The association is published before its threads start, so a listener
     * stop can close a socket that is not yet relaying.
     */
    private UdpAssociation openUdpAssociation(Socket control, Request request)
            throws Upstream.Failure {
        // The upstream socket first: if there is no cellular egress there is
        // nothing to bind, and the reply says why.
        DatagramSocket upstreamSocket = upstream.datagram();
        DatagramSocket relaySocket = null;
        try {
            relaySocket = new DatagramSocket(null);
            relaySocket.setReuseAddress(true);
            relaySocket.bind(new InetSocketAddress(0));
        } catch (IOException e) {
            Closeables.closeQuietly(relaySocket);
            Closeables.closeQuietly(upstreamSocket);
            throw new Upstream.Failure(REPLY_GENERAL_FAILURE,
                    "cannot open a UDP relay socket: " + e.getMessage());
        }
        UdpAssociation association = new UdpAssociation(
                control, relaySocket, upstreamSocket, request);
        synchronized (lifecycle) {
            if (closed) {
                association.close();
                return null;
            }
            associations.add(association);
        }
        return association;
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
        return new Request(command, addressType, host, port);
    }

    private static void writeReply(OutputStream out, int reply, Socket bound) throws IOException {
        InetAddress address = null;
        int port = 0;
        if (bound != null) {
            address = bound.getLocalAddress();
            port = bound.getLocalPort();
        }
        writeReplyTo(out, reply, address, port);
    }

    /**
     * The reply with an explicit bound address, for a UDP relay whose socket is
     * bound to the wildcard instead of one address.
     */
    private static void writeReplyTo(OutputStream out, int reply, InetAddress address, int port)
            throws IOException {
        byte[] bytes = address == null ? new byte[4] : address.getAddress();
        if (bytes.length != 4 && bytes.length != 16) {
            bytes = new byte[4];
        }
        ByteArrayOutputStream replyBytes = new ByteArrayOutputStream(22);
        replyBytes.write(VERSION);
        replyBytes.write(reply);
        replyBytes.write(0x00);
        replyBytes.write(bytes.length == 16 ? ADDRESS_IPV6 : ADDRESS_IPV4);
        replyBytes.write(bytes, 0, bytes.length);
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
     * One UDP association: the client-facing relay socket, the cellular
     * datagram socket, and the one queue between them.
     *
     * <p>The client sends SOCKS5 UDP request datagrams to the relay socket and
     * receives wrapped replies from it. Upstream replies arrive on the
     * unconnected cellular socket and are wrapped with their source address, so
     * the client can tell one destination from another. The association pins
     * itself to the first source datagram (or to the address in the
     * {@code UDP ASSOCIATE} request when it named one), so another host on the
     * hotspot cannot drive the relay.
     */
    private final class UdpAssociation implements Closeable {
        private final Socket control;
        private final DatagramSocket relay;
        private final DatagramSocket upstreamSocket;
        private final BlockingQueue<Outbound> queue;
        private final InetAddress declaredAddress;
        private final int declaredPort;
        private final List<Thread> workers = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object pinLock = new Object();
        private InetAddress pinnedAddress;
        private int pinnedPort = -1;
        private volatile long lastActivity;
        private volatile InetSocketAddress clientEndpoint;

        UdpAssociation(
                Socket control,
                DatagramSocket relay,
                DatagramSocket upstreamSocket,
                Request request) {
            this.control = control;
            this.relay = relay;
            this.upstreamSocket = upstreamSocket;
            this.queue = new LinkedBlockingQueue<>(udpQueue);
            InetAddress address = null;
            if (request.addressType == ADDRESS_IPV4 || request.addressType == ADDRESS_IPV6) {
                try {
                    address = InetAddress.getByName(request.host);
                } catch (UnknownHostException ignored) {
                    // It was a literal a moment ago; treat it as unspecified.
                }
            }
            this.declaredAddress = address == null || address.isAnyLocalAddress() ? null : address;
            this.declaredPort = request.port;
            this.lastActivity = System.nanoTime();
        }

        /** The address the client should send to: the one its control link reached. */
        InetAddress replyAddress() {
            InetAddress local = control.getLocalAddress();
            if (local == null || local.isAnyLocalAddress()) {
                return relay.getLocalAddress();
            }
            return local;
        }

        int replyPort() {
            return relay.getLocalPort();
        }

        void start() {
            startWorker("socks5-udp-client", this::receiveFromClient);
            startWorker("socks5-udp-send", this::sendUpstream);
            startWorker("socks5-udp-receive", this::receiveFromUpstream);
        }

        private void startWorker(String name, Runnable body) {
            Thread thread = new Thread(() -> {
                try {
                    body.run();
                } finally {
                    workers.remove(Thread.currentThread());
                }
            }, name);
            thread.setDaemon(true);
            workers.add(thread);
            thread.start();
        }

        /**
         * Read SOCKS5 request datagrams from the client. Anything that is not
         * one is dropped: a bad fragment, a truncated header, a source that is
         * not this association's client, or one too large for the cap.
         */
        private void receiveFromClient() {
            byte[] buffer = new byte[udpMaxDatagram + 1];
            try {
                while (!closed.get()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    relay.receive(packet);
                    UdpPacket request =
                            UdpPacket.parse(packet.getData(), packet.getLength(), udpMaxDatagram);
                    if (request == null) {
                        continue;
                    }
                    InetSocketAddress from =
                            new InetSocketAddress(packet.getAddress(), packet.getPort());
                    if (!admits(from)) {
                        continue;
                    }
                    clientEndpoint = from;
                    byte[] payload = Arrays.copyOfRange(
                            packet.getData(), request.offset, packet.getLength());
                    // offer, not put: a full queue drops this datagram rather
                    // than blocking the reader while the egress is busy.
                    if (queue.offer(new Outbound(request, payload))) {
                        lastActivity = System.nanoTime();
                    }
                }
            } catch (IOException e) {
                // A close is how this ends; anything else is fatal for the
                // association too, because the relay cannot receive any more.
                if (!closed.get()) {
                    log("udp relay failed: " + e.getMessage());
                }
            } finally {
                close();
            }
        }

        /**
         * Drain the bounded queue to the cellular socket. A destination name is
         * resolved on the egress, never on the laptop-facing default route.
         */
        private void sendUpstream() {
            try {
                while (!closed.get()) {
                    Outbound outbound = queue.take();
                    InetAddress destination = outbound.request.address;
                    if (destination == null) {
                        InetAddress[] addresses;
                        try {
                            addresses = upstream.resolve(outbound.request.host);
                        } catch (Upstream.Failure failure) {
                            // A name that does not resolve drops that datagram
                            // only; UDP has no reply for a local failure.
                            log(failure.getMessage());
                            continue;
                        }
                        if (addresses.length == 0) {
                            continue;
                        }
                        destination = addresses[0];
                    }
                    byte[] payload = outbound.payload;
                    try {
                        upstreamSocket.send(
                                new DatagramPacket(payload, payload.length, destination,
                                        outbound.request.port));
                    } catch (IOException e) {
                        close();
                        return;
                    }
                    lastActivity = System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Wrap each upstream reply and deliver it to the pinned client endpoint. */
        private void receiveFromUpstream() {
            byte[] buffer = new byte[udpMaxDatagram + 1];
            long idleNanos = TimeUnit.MILLISECONDS.toNanos(udpIdleMs);
            int pollMs = (int) Math.max(50, Math.min(1_000, udpIdleMs / 4));
            try {
                upstreamSocket.setSoTimeout(pollMs);
                while (!closed.get()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        upstreamSocket.receive(packet);
                    } catch (SocketTimeoutException idle) {
                        if (System.nanoTime() - lastActivity > idleNanos) {
                            close();
                            return;
                        }
                        continue;
                    }
                    lastActivity = System.nanoTime();
                    if (packet.getAddress() == null) {
                        continue;
                    }
                    byte[] reply = UdpPacket.encode(
                            packet.getAddress(), packet.getPort(),
                            packet.getData(), packet.getLength(), udpMaxDatagram);
                    InetSocketAddress client = clientEndpoint;
                    if (reply == null || client == null) {
                        continue;
                    }
                    relay.send(new DatagramPacket(reply, reply.length, client));
                }
            } catch (IOException e) {
                if (!closed.get()) {
                    log("udp relay failed: " + e.getMessage());
                }
                close();
            }
        }

        /**
         * Whether a datagram may use this association. The request's address
         * wins when it named one; otherwise the first datagram pins the client,
         * so every later one has to come from the same address and port.
         */
        private boolean admits(InetSocketAddress from) {
            synchronized (pinLock) {
                if (pinnedPort < 0) {
                    if (declaredAddress != null && !declaredAddress.equals(from.getAddress())) {
                        return false;
                    }
                    if (declaredPort != 0 && declaredPort != from.getPort()) {
                        return false;
                    }
                    pinnedAddress = from.getAddress();
                    pinnedPort = from.getPort();
                    return true;
                }
                return pinnedPort == from.getPort()
                        && pinnedAddress != null
                        && pinnedAddress.equals(from.getAddress());
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            associations.remove(this);
            queue.clear();
            Closeables.closeQuietly(relay);
            Closeables.closeQuietly(upstreamSocket);
            for (Thread worker : workers) {
                worker.interrupt();
            }
            // The control thread is blocked reading TCP; closing its socket is
            // what releases it (and the association it carries).
            Closeables.resetQuietly(control);
        }
    }

    /** One queued client datagram: its parsed destination and its payload. */
    private static final class Outbound {
        final UdpPacket request;
        final byte[] payload;

        Outbound(UdpPacket request, byte[] payload) {
            this.request = request;
            this.payload = payload;
        }
    }

    /**
     * The SOCKS5 UDP request/reply header (RFC 1928 §7):
     * {@code RSV RSV FRAG ATYP DST.ADDR DST.PORT DATA}. Parsing and encoding
     * are pure and Android-free, so the framing is tested without a phone.
     */
    static final class UdpPacket {
        /** Null for a domain name, which the association resolves on egress. */
        final InetAddress address;
        final String host;
        final int port;
        /** Where the payload starts in the datagram the caller passed in. */
        final int offset;

        private UdpPacket(InetAddress address, String host, int port, int offset) {
            this.address = address;
            this.host = host;
            this.port = port;
            this.offset = offset;
        }

        /**
         * Parse one request datagram; null means drop it. Reserved bytes must
         * be zero, {@code FRAG} other than zero is unsupported, the address
         * length has to fit, and a NUL port is not a destination.
         */
        static UdpPacket parse(byte[] data, int length, int max) {
            if (data == null || length < 4 || length > max) {
                return null;
            }
            if (data[0] != 0 || data[1] != 0 || data[2] != 0) {
                return null;
            }
            int addressType = data[3] & 0xFF;
            int cursor = 4;
            InetAddress address = null;
            String host;
            switch (addressType) {
                case ADDRESS_IPV4:
                case ADDRESS_IPV6: {
                    int size = addressType == ADDRESS_IPV4 ? 4 : 16;
                    if (length < cursor + size + 2) {
                        return null;
                    }
                    byte[] raw = Arrays.copyOfRange(data, cursor, cursor + size);
                    try {
                        address = InetAddress.getByAddress(raw);
                    } catch (UnknownHostException impossible) {
                        return null;
                    }
                    host = address.getHostAddress();
                    cursor += size;
                    break;
                }
                case ADDRESS_DOMAIN: {
                    if (length < cursor + 1) {
                        return null;
                    }
                    int nameLength = data[cursor] & 0xFF;
                    cursor++;
                    if (nameLength == 0 || length < cursor + nameLength + 2) {
                        return null;
                    }
                    host = new String(data, cursor, nameLength, StandardCharsets.UTF_8);
                    if (host.isEmpty()) {
                        return null;
                    }
                    cursor += nameLength;
                    break;
                }
                default:
                    return null;
            }
            int port = ((data[cursor] & 0xFF) << 8) | (data[cursor + 1] & 0xFF);
            cursor += 2;
            if (port == 0 || cursor > length) {
                return null;
            }
            return new UdpPacket(address, host, port, cursor);
        }

        /**
         * Wrap a payload from {@code source:port} as a reply; null when the
         * header plus payload would exceed the cap.
         */
        static byte[] encode(
                InetAddress source, int port, byte[] data, int length, int max) {
            if (source == null || data == null || length < 0) {
                return null;
            }
            byte[] raw = source.getAddress();
            if (raw.length != 4 && raw.length != 16) {
                return null;
            }
            int total = 4 + raw.length + 2 + length;
            if (total > max) {
                return null;
            }
            byte[] packet = new byte[total];
            packet[0] = 0;
            packet[1] = 0;
            packet[2] = 0;
            packet[3] = (byte) (raw.length == 4 ? ADDRESS_IPV4 : ADDRESS_IPV6);
            System.arraycopy(raw, 0, packet, 4, raw.length);
            int cursor = 4 + raw.length;
            packet[cursor++] = (byte) ((port >> 8) & 0xFF);
            packet[cursor++] = (byte) (port & 0xFF);
            System.arraycopy(data, 0, packet, cursor, length);
            return packet;
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
