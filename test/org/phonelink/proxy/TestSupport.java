package org.phonelink.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The harness the suites share: the case runner, the assertions, the
 * loopback helpers and the fixtures the SOCKS5 tests talk to.
 *
 * <p>Plain JDK, no framework. A suite calls {@link #run(String, Case)} for
 * every case; {@link AllTests} names the suite, runs the selected ones and
 * prints the count. Everything listens on loopback and the upstream is a
 * local echo, so nothing here needs a phone or the network.
 */
public final class TestSupport {

    static final String USER = "proxy";
    static final String PASSWORD = "s3cret";
    static final int TIMEOUT_MS = 3_000;

    private static int passed;
    private static int failed;
    private static String suite = "";

    private TestSupport() {
    }

    /** The suite a following {@link #run} belongs to, for the case's line. */
    static void begin(String name) {
        suite = name;
    }

    interface Case {
        void run() throws Exception;
    }

    /**
     * Run one case: a failure is one red line and the next case still runs, so
     * one bad relay does not hide the rest of the suite.
     */
    static void run(String name, Case body) {
        String label = suite.isEmpty() ? name : suite + ": " + name;
        try {
            body.run();
            passed++;
            System.out.println("ok   " + label);
        } catch (Throwable e) {
            failed++;
            System.out.println("FAIL " + name + ": " + e);
        }
    }

    /** Print the count and return the failure count for the process's status. */
    static int finish() {
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        return failed;
    }

    /** A one-connection loopback listener, as the service would create. */
    static ServerSocket loopback() throws IOException {
        ServerSocket server =
                new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(TIMEOUT_MS);
        return server;
    }

    static Socket connect(ServerSocket server) throws IOException {
        Socket socket = new Socket();
        socket.connect(
                new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), server.getLocalPort()),
                TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        return socket;
    }

    static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    /** One protocol line, without its LF. */
    static String readLine(Socket socket) throws IOException {
        StringBuilder line = new StringBuilder();
        InputStream in = socket.getInputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("the answer ended before a line did");
            }
            if (b == '\n') {
                return line.toString();
            }
            line.append((char) b);
        }
    }

    /** One saved proxy a start could accept. */
    static ProfileDocument validDocument() {
        ProfileDocument document = new ProfileDocument();
        ProfileDocument.Entry entry = new ProfileDocument.Entry();
        entry.name = "laptop";
        entry.config =
                ProxyConfig.raw(1081, "proxy", "s3cret", "PhoneLink", false, false, 256);
        document.saved.add(entry);
        return document;
    }

    // --- assertions --------------------------------------------------------

    static void assertTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    static void assertFalse(boolean condition, String what) {
        if (condition) {
            throw new AssertionError(what);
        }
    }

    static void assertEqual(int expected, int actual, String what) {
        if (expected != actual) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    static void assertEqual(String expected, String actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected \"" + expected + "\" but was \"" + actual + "\"");
        }
    }

    /** Read until the peer closes; a reset is just as closed as end-of-stream. */
    static void assertClosed(Socket socket, String what) {
        int read;
        try {
            read = socket.getInputStream().read();
        } catch (SocketTimeoutException e) {
            throw new AssertionError(what + ": still open", e);
        } catch (IOException e) {
            return;
        }
        assertEqual(-1, read, what);
    }

    /** The count a connection-thread callback settles on, or the last one seen. */
    static int waitForClients(AtomicInteger clients) throws InterruptedException {
        return waitForClients(clients, 0);
    }

    static int waitForClients(AtomicInteger clients, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (clients.get() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return clients.get();
    }

    static void assertRejected(String what, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(what + ": the text was accepted");
    }

    static void expectRejected(String what, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(what + " was accepted");
    }

    // --- protocol client ---------------------------------------------------

    static void greet(Socket socket, int... methods) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(0x05);
        out.write(methods.length);
        for (int method : methods) {
            out.write(method);
        }
        out.flush();
    }

    static int readMethod(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        assertEqual(0x05, in.readUnsignedByte(), "greeting version");
        return in.readUnsignedByte();
    }

    static boolean authenticate(Socket socket, String user, String password)
            throws IOException {
        byte[] name = user.getBytes(StandardCharsets.UTF_8);
        byte[] secret = password.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        message.write(0x01);
        message.write(name.length);
        message.write(name, 0, name.length);
        message.write(secret.length);
        message.write(secret, 0, secret.length);
        socket.getOutputStream().write(message.toByteArray());
        socket.getOutputStream().flush();

        DataInputStream in = new DataInputStream(socket.getInputStream());
        assertEqual(0x01, in.readUnsignedByte(), "auth version");
        return in.readUnsignedByte() == 0x00;
    }

    static void handshake(Socket socket) throws IOException {
        greet(socket, 0x02);
        assertEqual(0x02, readMethod(socket), "selected method");
        assertTrue(authenticate(socket, USER, PASSWORD), "credentials refused");
    }

    /** Handshake and open one CONNECT to the fixture's echo backend. */
    static void connect(Socket socket) throws IOException {
        handshake(socket);
        requestConnect(socket, 0x01, 0x01, "127.0.0.1", 80);
        assertEqual(0x00, readReply(socket), "reply code");
    }

    static void requestConnect(
            Socket socket, int command, int addressType, String host, int port)
            throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(0x05);
        request.write(command);
        request.write(0x00);
        request.write(addressType);
        if (addressType == 0x01) {
            for (String part : host.split("\\.")) {
                request.write(Integer.parseInt(part));
            }
        } else if (addressType == 0x03) {
            byte[] name = host.getBytes(StandardCharsets.UTF_8);
            request.write(name.length);
            request.write(name, 0, name.length);
        }
        request.write((port >> 8) & 0xFF);
        request.write(port & 0xFF);
        socket.getOutputStream().write(request.toByteArray());
        socket.getOutputStream().flush();
    }

    static int readReply(Socket socket) throws IOException {
        return readReplyFull(socket).code;
    }

    /** A parsed SOCKS5 reply: the code and the bound address, when it named one. */
    static final class Reply {
        final int code;
        final InetSocketAddress bound;

        Reply(int code, InetSocketAddress bound) {
            this.code = code;
            this.bound = bound;
        }
    }

    static Reply readReplyFull(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        assertEqual(0x05, in.readUnsignedByte(), "reply version");
        int code = in.readUnsignedByte();
        in.readUnsignedByte(); // reserved
        int addressType = in.readUnsignedByte();
        InetAddress address = null;
        switch (addressType) {
            case 0x01: {
                byte[] raw = new byte[4];
                in.readFully(raw);
                address = InetAddress.getByAddress(raw);
                break;
            }
            case 0x04: {
                byte[] raw = new byte[16];
                in.readFully(raw);
                address = InetAddress.getByAddress(raw);
                break;
            }
            case 0x03:
                in.readFully(new byte[in.readUnsignedByte()]);
                break;
            default:
                throw new AssertionError("unexpected reply address type " + addressType);
        }
        int port = in.readUnsignedShort();
        return new Reply(code, address == null ? null : new InetSocketAddress(address, port));
    }

    /** Handshake and open one {@code UDP ASSOCIATE}; its reply names the relay. */
    static Reply associate(Socket control) throws IOException {
        requestConnect(control, 0x03, 0x01, "0.0.0.0", 0);
        Reply reply = readReplyFull(control);
        assertEqual(0x00, reply.code, "UDP ASSOCIATE reply code");
        assertTrue(reply.bound != null, "UDP ASSOCIATE named no relay");
        assertTrue(reply.bound.getPort() > 0, "UDP ASSOCIATE named no port");
        return reply;
    }

    /** One SOCKS5 UDP request around an IPv4 or IPv6 destination. */
    static byte[] udpRequest(
            InetAddress destination, int port, byte[] payload, int fragment) {
        byte[] raw = destination.getAddress();
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(0);
        request.write(0);
        request.write(fragment);
        request.write(raw.length == 4 ? 0x01 : 0x04);
        request.write(raw, 0, raw.length);
        request.write((port >> 8) & 0xFF);
        request.write(port & 0xFF);
        request.write(payload, 0, payload.length);
        return request.toByteArray();
    }

    /** The same with a domain name, which the association resolves on egress. */
    static byte[] udpRequest(String host, int port, byte[] payload, int fragment) {
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(0);
        request.write(0);
        request.write(fragment);
        request.write(0x03);
        request.write(name.length);
        request.write(name, 0, name.length);
        request.write((port >> 8) & 0xFF);
        request.write(port & 0xFF);
        request.write(payload, 0, payload.length);
        return request.toByteArray();
    }

    static void sendUdp(DatagramSocket socket, InetSocketAddress to, byte[] datagram)
            throws IOException {
        socket.send(new DatagramPacket(datagram, datagram.length, to));
    }

    static byte[] receiveUdp(DatagramSocket socket) throws IOException {
        socket.setSoTimeout(TIMEOUT_MS);
        DatagramPacket packet = new DatagramPacket(new byte[65536], 65536);
        socket.receive(packet);
        return Arrays.copyOf(packet.getData(), packet.getLength());
    }

    static void assertNoUdp(DatagramSocket socket, String what) throws IOException {
        socket.setSoTimeout(500);
        DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException expected) {
            return;
        }
        throw new AssertionError(what);
    }

    static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new AssertionError("stream ended after " + offset + " of " + length + " bytes");
            }
            offset += read;
        }
        return buffer;
    }

    // --- fixtures ----------------------------------------------------------

    static final class Fixture implements AutoCloseable {
        final Socks5Server server;
        final FakeUpstream upstream;
        final AtomicInteger clients = new AtomicInteger();
        final Traffic traffic = new Traffic();
        final int port;

        Fixture() throws IOException {
            this(USER, PASSWORD, null);
        }

        Fixture(String user, String password, Upstream custom) throws IOException {
            this(user, password, custom,
                    Socks5Server.MAX_CONNECTIONS, Socks5Server.RELAY_LINGER_MS);
        }

        Fixture(String user, String password, Upstream custom, int maxConnections,
                long relayLingerMs) throws IOException {
            this(user, password, custom, maxConnections, relayLingerMs,
                    Socks5Server.UDP_MAX_DATAGRAM);
        }

        Fixture(String user, String password, Upstream custom, int maxConnections,
                long relayLingerMs, int udpMaxDatagram) throws IOException {
            this(user, password, custom, maxConnections, relayLingerMs, udpMaxDatagram,
                    Socks5Server.UDP_QUEUE, Socks5Server.UDP_IDLE_MS);
        }

        Fixture(String user, String password, Upstream custom, int maxConnections,
                long relayLingerMs, int udpMaxDatagram, int udpQueue, long udpIdleMs)
                throws IOException {
            upstream = new FakeUpstream();
            server = new Socks5Server(
                    0,
                    user,
                    password,
                    custom == null ? upstream : custom,
                    traffic,
                    clients::set,
                    message -> { },
                    maxConnections,
                    relayLingerMs,
                    udpMaxDatagram,
                    udpQueue,
                    udpIdleMs);
            server.start();
            port = server.port();
        }

        Socket open() throws IOException {
            Socket socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(TIMEOUT_MS);
            return socket;
        }

        @Override
        public void close() {
            server.close();
            upstream.close();
        }
    }

    /** Records what CONNECT asked for and answers with a local echo socket. */
    static final class FakeUpstream implements Upstream {
        private final ServerSocket backend;
        private final Thread thread;

        volatile String host;
        volatile int port;
        volatile int timeoutMs;

        FakeUpstream() throws IOException {
            backend = new ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "echo-backend");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            while (!backend.isClosed()) {
                try {
                    Socket socket = backend.accept();
                    Thread worker = new Thread(() -> echo(socket), "echo-connection");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void echo(Socket socket) {
            try (Socket open = socket) {
                byte[] buffer = new byte[8 * 1024];
                InputStream in = open.getInputStream();
                OutputStream out = open.getOutputStream();
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // The test closed its end first; that ends this echo.
            }
        }

        @Override
        public Socket connect(String host, int port, int timeoutMs) throws Failure {
            this.host = host;
            this.port = port;
            this.timeoutMs = timeoutMs;
            try {
                Socket socket = new Socket("127.0.0.1", backend.getLocalPort());
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException e) {
                throw new Failure(Handshake.REPLY_CONNECTION_REFUSED, e.getMessage());
            }
        }

        /**
         * A loopback datagram socket, which is the JVM's stand-in for the
         * cellular egress: the test's echo backend is on loopback too.
         */
        @Override
        public DatagramSocket datagram() throws Failure {
            try {
                return new DatagramSocket(0, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new Failure(Handshake.REPLY_GENERAL_FAILURE, e.getMessage());
            }
        }

        @Override
        public InetAddress[] resolve(String host) throws Failure {
            try {
                return InetAddress.getAllByName(host);
            } catch (IOException e) {
                throw new Failure(Handshake.REPLY_HOST_UNREACHABLE,
                        "cannot resolve " + host + ": " + e.getMessage());
            }
        }

        void close() {
            try {
                backend.close();
            } catch (IOException ignored) {
                // The fixture is done with it.
            }
        }
    }

    /**
     * Fails the first {@code failures} CONNECTs with {@code reply}, then echoes
     * through a real {@link FakeUpstream}. Counts every attempt, so a test can
     * tell one attempt from a retry.
     */
    static final class FlakyUpstream implements Upstream, AutoCloseable {
        private final FakeUpstream delegate;
        private final int failures;
        private final int reply;
        final AtomicInteger attempts = new AtomicInteger();

        FlakyUpstream(int failures, int reply) throws IOException {
            delegate = new FakeUpstream();
            this.failures = failures;
            this.reply = reply;
        }

        @Override
        public Socket connect(String host, int port, int timeoutMs) throws Failure {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failures) {
                throw new Failure(reply, "attempt " + attempt + " failed with reply " + reply);
            }
            return delegate.connect(host, port, timeoutMs);
        }

        @Override
        public DatagramSocket datagram() throws Failure {
            return delegate.datagram();
        }

        @Override
        public InetAddress[] resolve(String host) throws Failure {
            return delegate.resolve(host);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * An origin that keeps its side of the connection open after the client
     * half-closes: the state the old relay waited in forever.
     */
    static final class HoldingUpstream implements Upstream, AutoCloseable {
        private final ServerSocket backend;
        private final Thread thread;
        private final List<Socket> held = new ArrayList<>();

        HoldingUpstream() throws IOException {
            backend = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "holding-backend");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            while (!backend.isClosed()) {
                try {
                    Socket socket = backend.accept();
                    synchronized (held) {
                        held.add(socket);
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        @Override
        public Socket connect(String host, int port, int timeoutMs) throws Failure {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", backend.getLocalPort()),
                        timeoutMs);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException e) {
                throw new Failure(Handshake.REPLY_CONNECTION_REFUSED, e.getMessage());
            }
        }

        @Override
        public void close() {
            Closeables.closeQuietly(backend);
            synchronized (held) {
                for (Socket socket : held) {
                    Closeables.closeQuietly(socket);
                }
                held.clear();
            }
        }
    }

    /** A loopback UDP echo origin, so an association can be tested end to end. */
    static final class UdpEcho implements AutoCloseable {
        private final DatagramSocket socket;
        private final Thread thread;

        UdpEcho() throws IOException {
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "udp-echo");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            byte[] buffer = new byte[8 * 1024];
            while (!socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    socket.send(new DatagramPacket(
                            packet.getData(), packet.getLength(), packet.getSocketAddress()));
                } catch (IOException e) {
                    return;
                }
            }
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() {
            Closeables.closeQuietly(socket);
        }
    }
}
