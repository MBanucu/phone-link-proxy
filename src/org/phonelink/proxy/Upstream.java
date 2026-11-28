package org.phonelink.proxy;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Where a CONNECT and a UDP association go.
 *
 * <p>Implemented by {@link Egress} on the phone and by the hermetic tests on the
 * JVM, which is why this interface carries no Android types: the protocol code
 * can then be compiled and exercised with a plain JDK.
 */
interface Upstream {

    Socket connect(String host, int port, int timeoutMs) throws Failure;

    /**
     * A datagram socket bound to the same cellular egress a CONNECT would use.
     *
     * <p>It must never fall back to the phone's default route: on the AP link
     * that route leads back to the laptop, and a UDP relay on it would loop.
     * The default fails closed so a test or a future implementation that only
     * knows CONNECT cannot accidentally relay UDP over the wrong network.
     */
    default DatagramSocket datagram() throws Failure {
        throw new Failure(Handshake.REPLY_COMMAND_NOT_SUPPORTED,
                "this upstream cannot carry UDP");
    }

    /**
     * Resolve a UDP destination name on the egress network.
     *
     * <p>A SOCKS5 UDP request may carry a domain name. Resolving it with the
     * system resolver would ask the DNS server on the default (Wi-Fi/AP) route,
     * which is the laptop, so the implementation has to ask on the egress.
     */
    default InetAddress[] resolve(String host) throws Failure {
        throw new Failure(Handshake.REPLY_HOST_UNREACHABLE,
                "this upstream cannot resolve " + host);
    }

    /** A CONNECT or a UDP association that could not be honoured. */
    final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        final int reply;

        Failure(int reply, String message) {
            super(message);
            this.reply = reply;
        }
    }
}
