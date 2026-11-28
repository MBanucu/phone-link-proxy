package org.phonelink.proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the SOCKS5 greeting, authentication, CONNECT relay and its
 * refusal paths.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class Socks5TcpTest {

    private Socks5TcpTest() {
    }

    static void cases() {
        run("a client offering only no-auth is refused when credentials are set", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                greet(socket, 0x00);
                assertEqual(0xFF, readMethod(socket), "selected method");
                assertEqual(-1, socket.getInputStream().read(), "connection closed");
            }
        });

        run("when both methods are offered, user/password wins", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                greet(socket, 0x00, 0x02);
                assertEqual(0x02, readMethod(socket), "selected method");
            }
        });

        run("correct credentials are accepted", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                greet(socket, 0x02);
                assertEqual(0x02, readMethod(socket), "selected method");
                assertTrue(authenticate(socket, USER, PASSWORD), "credentials refused");
            }
        });

        run("a wrong password is rejected and the connection ends", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                greet(socket, 0x02);
                assertEqual(0x02, readMethod(socket), "selected method");
                assertTrue(!authenticate(socket, USER, "wrong"), "wrong password accepted");
                assertEqual(-1, socket.getInputStream().read(), "connection closed");
            }
        });

        run("a wrong user is rejected", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                greet(socket, 0x02);
                assertEqual(0x02, readMethod(socket), "selected method");
                assertTrue(!authenticate(socket, "someone", PASSWORD), "wrong user accepted");
            }
        });

        run("without credentials the server serves CONNECT unauthenticated", () -> {
            try (Fixture fixture = new Fixture("", "", null); Socket socket = fixture.open()) {
                greet(socket, 0x00);
                assertEqual(0x00, readMethod(socket), "selected method");
                requestConnect(socket, 0x01, 0x01, "127.0.0.1", 80);
                assertEqual(0x00, readReply(socket), "reply code");
            }
        });

        run("CONNECT relays bytes in both directions", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                handshake(socket);
                requestConnect(socket, 0x01, 0x01, "127.0.0.1", 80);
                assertEqual(0x00, readReply(socket), "reply code");

                byte[] payload = new byte[32 * 1024];
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (i * 31);
                }
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
                byte[] echoed = readFully(socket.getInputStream(), payload.length);
                assertTrue(Arrays.equals(payload, echoed), "echoed bytes differ");
            }
        });

        run("half close is forwarded to the upstream", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                handshake(socket);
                requestConnect(socket, 0x01, 0x01, "127.0.0.1", 80);
                assertEqual(0x00, readReply(socket), "reply code");

                socket.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                socket.shutdownOutput();
                assertEqual("ping", new String(readFully(socket.getInputStream(), 4),
                        StandardCharsets.UTF_8), "echo after half close");
                assertEqual(-1, socket.getInputStream().read(), "end of stream");
            }
        });

        // A plain close leaves FIN_WAIT1 behind when the peer has gone, and a
        // SYN that later lands on that orphan is blackholed. The bounded close
        // must still deliver everything that was written before it.
        run("a bounded close delivers the bytes and the end of stream", () -> {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket reader = new Socket()) {
                reader.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        listener.getLocalPort()), TIMEOUT_MS);
                reader.setSoTimeout(TIMEOUT_MS);
                Socket writer = listener.accept();
                writer.getOutputStream().write("done".getBytes(StandardCharsets.UTF_8));
                writer.getOutputStream().flush();
                long start = System.nanoTime();
                Closeables.closeBoundedQuietly(writer);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertTrue(writer.isClosed(), "writer not closed");
                assertTrue(elapsedMs <= 2_000, "bounded close blocked for " + elapsedMs + " ms");
                assertEqual("done", new String(readFully(reader.getInputStream(), 4),
                        StandardCharsets.UTF_8), "bytes written before the close");
                assertEqual(-1, reader.getInputStream().read(), "end of stream");
            }
        });

        run("a reset close frees the socket at once", () -> {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket reader = new Socket()) {
                reader.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        listener.getLocalPort()), TIMEOUT_MS);
                Socket writer = listener.accept();
                long start = System.nanoTime();
                Closeables.resetQuietly(writer);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertTrue(writer.isClosed(), "writer not closed");
                assertTrue(elapsedMs <= 500, "reset blocked for " + elapsedMs + " ms");
            }
        });

        run("a domain name reaches the upstream unresolved", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                handshake(socket);
                requestConnect(socket, 0x01, 0x03, "example.test", 443);
                assertEqual(0x00, readReply(socket), "reply code");
                assertEqual("example.test", fixture.upstream.host, "host passed upstream");
                assertEqual(443, fixture.upstream.port, "port passed upstream");
            }
        });

        run("BIND is refused with command-not-supported", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                handshake(socket);
                requestConnect(socket, 0x02, 0x01, "127.0.0.1", 80);
                assertEqual(Handshake.REPLY_COMMAND_NOT_SUPPORTED, readReply(socket), "reply code");
            }
        });

        run("an unknown address type is refused", () -> {
            try (Fixture fixture = new Fixture(); Socket socket = fixture.open()) {
                handshake(socket);
                byte[] request = {0x05, 0x01, 0x00, 0x02, 0x00, 0x50};
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();
                assertEqual(
                        Handshake.REPLY_ADDRESS_TYPE_NOT_SUPPORTED, readReply(socket), "reply code");
            }
        });

        run("an upstream failure reply is forwarded", () -> {
            Upstream failing = (host, port, timeoutMs) -> {
                throw new Upstream.Failure(Handshake.REPLY_CONNECTION_REFUSED, "refused");
            };
            try (Fixture fixture = new Fixture(USER, PASSWORD, failing);
                    Socket socket = fixture.open()) {
                handshake(socket);
                requestConnect(socket, 0x01, 0x01, "127.0.0.1", 80);
                assertEqual(Handshake.REPLY_CONNECTION_REFUSED, readReply(socket), "reply code");
            }
        });
    }
}
