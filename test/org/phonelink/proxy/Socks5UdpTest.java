package org.phonelink.proxy;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for UDP ASSOCIATE framing, relay and lifetime.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class Socks5UdpTest {

    private Socks5UdpTest() {
    }

    static void cases() {
        run("a UDP request and a reply use the SOCKS5 header for IPv4 and IPv6", () -> {
            byte[] payload = "quic".getBytes(StandardCharsets.UTF_8);
            InetAddress v4 = InetAddress.getByName("192.0.2.7");
            byte[] request = udpRequest(v4, 443, payload, 0);
            UdpPacket parsed = UdpPacket.parse(
                    request, request.length, Socks5Server.UDP_MAX_DATAGRAM);
            assertTrue(parsed != null, "an IPv4 request was dropped");
            assertTrue(v4.equals(parsed.address), "IPv4 address");
            assertEqual(443, parsed.port, "IPv4 port");
            assertEqual("quic", new String(
                    Arrays.copyOfRange(request, parsed.offset, request.length),
                    StandardCharsets.UTF_8), "IPv4 payload");

            InetAddress v6 = InetAddress.getByName("2001:db8::7");
            byte[] six = udpRequest(v6, 53, payload, 0);
            assertEqual(0x04, six[3] & 0xFF, "IPv6 address type");
            parsed = UdpPacket.parse(
                    six, six.length, Socks5Server.UDP_MAX_DATAGRAM);
            assertTrue(parsed != null, "an IPv6 request was dropped");
            assertTrue(v6.equals(parsed.address), "IPv6 address");
            assertEqual(53, parsed.port, "IPv6 port");

            byte[] named = udpRequest("dns.example", 53, payload, 0);
            assertEqual(0x03, named[3] & 0xFF, "domain address type");
            parsed = UdpPacket.parse(
                    named, named.length, Socks5Server.UDP_MAX_DATAGRAM);
            assertTrue(parsed != null, "a domain request was dropped");
            assertEqual("dns.example", parsed.host, "domain name");
            assertTrue(parsed.address == null, "a domain is not resolved while parsing");

            byte[] reply = UdpPacket.encode(
                    v6, 443, payload, payload.length, Socks5Server.UDP_MAX_DATAGRAM);
            assertTrue(reply != null, "the reply was not encoded");
            assertEqual(0x04, reply[3] & 0xFF, "an IPv6 source gets an IPv6 reply header");
            parsed = UdpPacket.parse(
                    reply, reply.length, Socks5Server.UDP_MAX_DATAGRAM);
            assertTrue(parsed != null, "the encoded reply did not parse");
            assertTrue(v6.equals(parsed.address), "the reply source address");
            assertEqual(443, parsed.port, "the reply source port");
            assertTrue(Arrays.equals(payload,
                    Arrays.copyOfRange(reply, parsed.offset, reply.length)), "the reply payload");
        });

        run("a fragmented, short or oversized UDP request is dropped", () -> {
            InetAddress destination = InetAddress.getByName("192.0.2.7");
            byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);

            byte[] fragmented = udpRequest(destination, 443, payload, 1);
            assertTrue(UdpPacket.parse(
                    fragmented, fragmented.length, Socks5Server.UDP_MAX_DATAGRAM) == null,
                    "FRAG=1 was accepted");

            byte[] shortPacket = new byte[] {0, 0, 0};
            assertTrue(UdpPacket.parse(
                    shortPacket, shortPacket.length, Socks5Server.UDP_MAX_DATAGRAM) == null,
                    "a three-byte request was accepted");

            byte[] truncated = Arrays.copyOf(udpRequest(destination, 443, payload, 0), 9);
            assertTrue(UdpPacket.parse(
                    truncated, truncated.length, Socks5Server.UDP_MAX_DATAGRAM) == null,
                    "an IPv4 header cut before its port was accepted");

            byte[] badType = udpRequest(destination, 443, payload, 0);
            badType[3] = 0x02;
            assertTrue(UdpPacket.parse(
                    badType, badType.length, Socks5Server.UDP_MAX_DATAGRAM) == null,
                    "an unknown address type was accepted");

            byte[] oversized = new byte[Socks5Server.UDP_MAX_DATAGRAM + 1];
            assertTrue(UdpPacket.parse(
                    oversized, oversized.length, Socks5Server.UDP_MAX_DATAGRAM) == null,
                    "a datagram over the cap was accepted");
            assertTrue(UdpPacket.encode(
                    destination, 443, new byte[100], 100, 64) == null,
                    "an encoded reply over the cap was accepted");
        });

        run("UDP ASSOCIATE relays a datagram through a real UDP echo", () -> {
            try (Fixture fixture = new Fixture();
                    UdpEcho echo = new UdpEcho();
                    Socket control = fixture.open();
                    DatagramSocket udp = new DatagramSocket(
                            0, InetAddress.getLoopbackAddress())) {
                handshake(control);
                Reply reply = associate(control);
                udp.setSoTimeout(TIMEOUT_MS);

                byte[] payload = "udp-through-the-tunnel".getBytes(StandardCharsets.UTF_8);
                sendUdp(udp, reply.bound, udpRequest(
                        InetAddress.getByName("127.0.0.1"), echo.port(), payload, 0));
                byte[] answer = receiveUdp(udp);
                UdpPacket parsed = UdpPacket.parse(
                        answer, answer.length, Socks5Server.UDP_MAX_DATAGRAM);
                assertTrue(parsed != null, "the reply did not parse");
                assertEqual(echo.port(), parsed.port, "the reply comes from the echo port");
                assertTrue(Arrays.equals(payload,
                        Arrays.copyOfRange(answer, parsed.offset, answer.length)),
                        "the payload did not round-trip");
                assertEqual(1, fixture.clients.get(), "the association is one client");
            }
        });

        run("a fragmented or oversized datagram is dropped and the association survives", () -> {
            try (Fixture fixture = new Fixture(USER, PASSWORD, null,
                            Socks5Server.MAX_CONNECTIONS, Socks5Server.RELAY_LINGER_MS, 64);
                    UdpEcho echo = new UdpEcho();
                    Socket control = fixture.open();
                    DatagramSocket udp = new DatagramSocket(
                            0, InetAddress.getLoopbackAddress())) {
                handshake(control);
                Reply reply = associate(control);
                InetAddress destination = InetAddress.getByName("127.0.0.1");

                sendUdp(udp, reply.bound,
                        udpRequest(destination, echo.port(), new byte[] {1, 2, 3}, 1));
                assertNoUdp(udp, "a fragmented datagram was relayed");

                sendUdp(udp, reply.bound,
                        udpRequest(destination, echo.port(), new byte[100], 0));
                assertNoUdp(udp, "an oversized datagram was relayed");

                // The drops did not take the association down.
                byte[] payload = "still-here".getBytes(StandardCharsets.UTF_8);
                sendUdp(udp, reply.bound,
                        udpRequest(destination, echo.port(), payload, 0));
                byte[] answer = receiveUdp(udp);
                UdpPacket parsed = UdpPacket.parse(
                        answer, answer.length, Socks5Server.UDP_MAX_DATAGRAM);
                assertTrue(parsed != null, "the reply after the drops did not parse");
                assertTrue(Arrays.equals(payload,
                        Arrays.copyOfRange(answer, parsed.offset, answer.length)),
                        "the association stopped relaying after a drop");
            }
        });

        run("closing the control connection ends the UDP association", () -> {
            try (Fixture fixture = new Fixture();
                    UdpEcho echo = new UdpEcho();
                    DatagramSocket udp = new DatagramSocket(
                            0, InetAddress.getLoopbackAddress())) {
                Socket control = fixture.open();
                try {
                    handshake(control);
                    Reply reply = associate(control);
                    InetAddress destination = InetAddress.getByName("127.0.0.1");
                    byte[] payload = "before".getBytes(StandardCharsets.UTF_8);
                    sendUdp(udp, reply.bound,
                            udpRequest(destination, echo.port(), payload, 0));
                    assertTrue(receiveUdp(udp).length > payload.length,
                            "the association did not relay before the close");

                    control.close();
                    assertEqual(0, waitForClients(fixture.clients),
                            "clients after the control close");

                    sendUdp(udp, reply.bound,
                            udpRequest(destination, echo.port(), payload, 0));
                    assertNoUdp(udp, "the relay survived its control connection");
                } finally {
                    Closeables.closeQuietly(control);
                }
            }
        });

        run("stopping the listener closes every UDP association", () -> {
            Fixture fixture = new Fixture();
            try (UdpEcho echo = new UdpEcho();
                    DatagramSocket udp = new DatagramSocket(
                            0, InetAddress.getLoopbackAddress())) {
                Socket control = fixture.open();
                try {
                    handshake(control);
                    Reply reply = associate(control);
                    InetAddress destination = InetAddress.getByName("127.0.0.1");
                    byte[] payload = "before-stop".getBytes(StandardCharsets.UTF_8);
                    sendUdp(udp, reply.bound,
                            udpRequest(destination, echo.port(), payload, 0));
                    assertTrue(receiveUdp(udp).length > payload.length,
                            "the association did not relay before the stop");

                    fixture.server.close();
                    assertEqual(0, waitForClients(fixture.clients),
                            "clients after the listener stopped");
                    assertClosed(control, "the control connection of a stopped listener");

                    sendUdp(udp, reply.bound,
                            udpRequest(destination, echo.port(), payload, 0));
                    assertNoUdp(udp, "the relay survived the listener stop");
                } finally {
                    Closeables.closeQuietly(control);
                }
            } finally {
                fixture.close();
            }
        });

        run("an idle UDP association ends on its own", () -> {
            long idleMs = 300;
            try (Fixture fixture = new Fixture(USER, PASSWORD, null,
                            Socks5Server.MAX_CONNECTIONS, Socks5Server.RELAY_LINGER_MS,
                            Socks5Server.UDP_MAX_DATAGRAM, Socks5Server.UDP_QUEUE, idleMs);
                    UdpEcho echo = new UdpEcho();
                    DatagramSocket udp = new DatagramSocket(
                            0, InetAddress.getLoopbackAddress())) {
                Socket control = fixture.open();
                try {
                    handshake(control);
                    Reply reply = associate(control);
                    InetAddress destination = InetAddress.getByName("127.0.0.1");
                    byte[] payload = "wake".getBytes(StandardCharsets.UTF_8);
                    sendUdp(udp, reply.bound,
                            udpRequest(destination, echo.port(), payload, 0));
                    receiveUdp(udp);

                    assertEqual(0, waitForClients(fixture.clients),
                            "clients after the idle timeout");
                    assertClosed(control, "the control connection of an idle association");
                } finally {
                    Closeables.closeQuietly(control);
                }
            }
        });
    }
}
