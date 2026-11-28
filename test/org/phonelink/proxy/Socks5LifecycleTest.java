package org.phonelink.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for how the listener counts, retries, saturates and stops.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class Socks5LifecycleTest {

    private Socks5LifecycleTest() {
    }

    static void cases() {
        run("a CONNECT lost to a timeout is retried once with a fresh upstream", () -> {
            try (FlakyUpstream upstream =
                            new FlakyUpstream(1, Handshake.REPLY_HOST_UNREACHABLE);
                    Fixture fixture = new Fixture(USER, PASSWORD, upstream);
                    Socket socket = fixture.open()) {
                connect(socket);
                assertEqual(2, upstream.attempts.get(), "upstream attempts");
                // The second attempt's socket is the live one, so the tunnel
                // works rather than carrying the failed half.
                socket.getOutputStream().write(0x42);
                socket.getOutputStream().flush();
                assertEqual(0x42, readFully(socket.getInputStream(), 1)[0] & 0xFF,
                        "the retried tunnel relays");
            }
        });

        run("a refused CONNECT is reported without a retry", () -> {
            try (FlakyUpstream upstream = new FlakyUpstream(
                            Integer.MAX_VALUE, Handshake.REPLY_CONNECTION_REFUSED);
                    Fixture fixture = new Fixture(USER, PASSWORD, upstream);
                    Socket socket = fixture.open()) {
                handshake(socket);
                requestConnect(socket, 0x01, 0x01, "127.0.0.1", 80);
                assertEqual(Handshake.REPLY_CONNECTION_REFUSED, readReply(socket), "reply code");
                assertEqual(1, upstream.attempts.get(), "upstream attempts");
            }
        });

        run("clients are counted up and down", () -> {
            try (Fixture fixture = new Fixture()) {
                Socket socket = fixture.open();
                handshake(socket);
                requestConnect(socket, 0x01, 0x01, "127.0.0.1", 80);
                assertEqual(0x00, readReply(socket), "reply code");
                assertEqual(1, fixture.clients.get(), "connected clients");
                socket.close();
                long deadline = System.currentTimeMillis() + TIMEOUT_MS;
                while (fixture.clients.get() != 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertEqual(0, fixture.clients.get(), "connected clients after close");
            }
        });

        run("more than the old 32-connection ceiling are served at once", () -> {
            int count = 60;
            try (Fixture fixture = new Fixture()) {
                List<Socket> sockets = new ArrayList<>();
                try {
                    for (int i = 0; i < count; i++) {
                        Socket socket = fixture.open();
                        sockets.add(socket);
                        connect(socket);
                    }
                    assertEqual(count, fixture.clients.get(), "connected clients");

                    for (int i = 0; i < count; i++) {
                        sockets.get(i).getOutputStream().write(i);
                        sockets.get(i).getOutputStream().flush();
                    }
                    for (int i = 0; i < count; i++) {
                        byte[] echoed = readFully(sockets.get(i).getInputStream(), 1);
                        assertEqual(i, echoed[0] & 0xFF, "echo on connection " + i);
                    }
                } finally {
                    for (Socket socket : sockets) {
                        Closeables.closeQuietly(socket);
                    }
                }
                assertEqual(0, waitForClients(fixture.clients), "clients after the burst");
            }
        });

        run("the client count follows the live flows after a burst", () -> {
            int count = 40;
            try (Fixture fixture = new Fixture()) {
                List<Socket> sockets = new ArrayList<>();
                try {
                    for (int i = 0; i < count; i++) {
                        Socket socket = fixture.open();
                        sockets.add(socket);
                        connect(socket);
                    }
                    // Every one of the first count - 1 ends, leaving one flow.
                    for (int i = 0; i < count - 1; i++) {
                        Closeables.closeQuietly(sockets.get(i));
                    }
                    assertEqual(1, waitForClients(fixture.clients, 1),
                            "clients with one live flow");

                    Socket last = sockets.get(count - 1);
                    last.getOutputStream().write(0x2A);
                    last.getOutputStream().flush();
                    assertEqual(0x2A, readFully(last.getInputStream(), 1)[0] & 0xFF,
                            "the live flow still relays");
                } finally {
                    for (Socket socket : sockets) {
                        Closeables.closeQuietly(socket);
                    }
                }
                assertEqual(0, waitForClients(fixture.clients), "clients after the last close");
            }
        });

        run("a half-closed client is released after the relay linger", () -> {
            long lingerMs = 400;
            try (HoldingUpstream upstream = new HoldingUpstream();
                    Fixture fixture = new Fixture(USER, PASSWORD, upstream,
                            Socks5Server.MAX_CONNECTIONS, lingerMs);
                    Socket socket = fixture.open()) {
                connect(socket);
                socket.shutdownOutput();

                // The origin keeps its side open, so the tunnel may not end at
                // once: a bounded linger is what releases it.
                socket.setSoTimeout((int) lingerMs / 2);
                try {
                    int read = socket.getInputStream().read();
                    throw new AssertionError("the half-close ended early: read " + read);
                } catch (SocketTimeoutException stillOpen) {
                    // Expected: the other direction is still inside its linger.
                }
                socket.setSoTimeout(TIMEOUT_MS);
                assertClosed(socket, "the half-closed tunnel is closed after the linger");
                assertEqual(0, waitForClients(fixture.clients), "clients after the linger");
            }
        });

        run("a saturated listener refuses a client instead of queueing it", () -> {
            int max = 4;
            try (Fixture fixture = new Fixture(USER, PASSWORD, null, max,
                    Socks5Server.RELAY_LINGER_MS)) {
                List<Socket> live = new ArrayList<>();
                try {
                    for (int i = 0; i < max; i++) {
                        Socket socket = fixture.open();
                        live.add(socket);
                        connect(socket);
                    }
                    assertEqual(max, fixture.clients.get(), "connected clients at the ceiling");

                    Socket extra = fixture.open();
                    try {
                        long started = System.nanoTime();
                        assertClosed(extra, "the refused client is closed");
                        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                        assertTrue(elapsedMs < 2_000,
                                "refusal took " + elapsedMs + " ms instead of failing fast");
                    } finally {
                        Closeables.closeQuietly(extra);
                    }
                    assertEqual(max, fixture.clients.get(),
                            "a refused client is not counted");

                    // Saturation must not disturb the flows already running.
                    live.get(0).getOutputStream().write(0x5A);
                    live.get(0).getOutputStream().flush();
                    assertEqual(0x5A, readFully(live.get(0).getInputStream(), 1)[0] & 0xFF,
                            "an existing flow still relays");
                } finally {
                    for (Socket socket : live) {
                        Closeables.closeQuietly(socket);
                    }
                }
                assertEqual(0, waitForClients(fixture.clients), "clients after saturation");
            }
        });

        run("closing the server ends an established tunnel, stops accepting and is idempotent", () -> {
            Fixture fixture = new Fixture();
            try {
                Socket socket = fixture.open();
                try {
                    handshake(socket);
                    requestConnect(socket, 0x01, 0x01, "127.0.0.1", 80);
                    assertEqual(0x00, readReply(socket), "reply code");

                    fixture.server.close();
                    assertClosed(socket, "the established tunnel is closed");
                    assertEqual(0, waitForClients(fixture.clients), "clients after stop");

                    // The second close is a no-op, not another shutdown.
                    fixture.server.close();

                    Socket late = new Socket();
                    try {
                        late.connect(new InetSocketAddress("127.0.0.1", fixture.port), 500);
                        throw new AssertionError("a stopped server accepted a connection");
                    } catch (IOException expected) {
                        // Refused: the accept loop stopped with the listener.
                    } finally {
                        Closeables.closeQuietly(late);
                    }
                } finally {
                    socket.close();
                }
            } finally {
                fixture.close();
            }
        });

        run("a client that has not handshaken is closed when the server stops", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                fixture.server.close();
                assertClosed(socket, "the accepted socket is closed");
                fixture.server.close();
            }
        });

        run("a non-SOCKS greeting closes the connection", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                socket.getOutputStream().write(new byte[] {0x04, 0x01, 0x00});
                socket.getOutputStream().flush();
                assertEqual(-1, socket.getInputStream().read(), "connection closed");
            }
        });
    }
}
