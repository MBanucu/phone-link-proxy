package org.phonelink.proxy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;

/**
 * One CONNECT: open the upstream with the retry, answer, then hand both
 * channels to {@link ChannelRelay}, which shovels bytes both ways in a fixed
 * selector pool until both directions are done and the linger expires.
 *
 * <p>The blocking part stays here, on the bounded handler thread: the connect
 * timeout, the retry with a fresh socket, and the SOCKS reply are what a client
 * waits for, and they are short. After the reply the handler returns to its
 * pool and the tunnel costs no thread at all.
 */
final class TcpRelay {

    /**
     * One upstream attempt. A lost handshake on the cellular uplink is a
     * timeout here, and it is better to surface it in 4 s than to make the
     * page wait out 15 s for a connection that is never going to complete.
     */
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    /** One retry with a fresh socket is enough: a new source port gets a new NAT mapping. */
    private static final int CONNECT_ATTEMPTS = 2;

    private final Upstream upstream;
    private final ChannelRelay relay;
    private final ChannelRelay.Sockets sockets;
    private final Socks5Server.Logger logger;

    TcpRelay(
            Upstream upstream,
            ChannelRelay relay,
            ChannelRelay.Sockets sockets,
            Socks5Server.Logger logger) {
        this.upstream = upstream;
        this.relay = relay;
        this.sockets = sockets;
        this.logger = logger;
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    /**
     * Open the upstream, reply, then register both channels with the selector.
     *
     * @return true when the relay now owns both channels; false when the
     *     connection failed or the listener is closing, in which case the
     *     caller still owns the client and must close it
     */
    boolean serve(SocketChannel client, OutputStream out, Socks5Request request)
            throws IOException {
        SocketChannel connected;
        try {
            connected = connect(request.host, request.port);
        } catch (Upstream.Failure failure) {
            log(failure.getMessage());
            Handshake.writeReply(out, failure.reply, null);
            return false;
        }
        // The upstream half is a tunnel too: a stop has to close it.
        if (!sockets.track(connected.socket())) {
            Closeables.closeBoundedQuietly(connected.socket());
            return false;
        }
        try {
            Handshake.writeReply(out, Handshake.REPLY_SUCCEEDED, connected.socket());
            client.socket().setSoTimeout(0);
            client.configureBlocking(false);
            connected.configureBlocking(false);
            if (!relay.register(client, connected)) {
                sockets.untrack(connected.socket());
                Closeables.closeBoundedQuietly(connected.socket());
                return false;
            }
            return true;
        } catch (IOException e) {
            sockets.untrack(connected.socket());
            Closeables.closeBoundedQuietly(connected.socket());
            throw e;
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
    private SocketChannel connect(String host, int port) throws Upstream.Failure {
        for (int attempt = 1; ; attempt++) {
            try {
                return upstream.connect(host, port, CONNECT_TIMEOUT_MS);
            } catch (Upstream.Failure failure) {
                if (attempt >= CONNECT_ATTEMPTS
                        || failure.reply != Handshake.REPLY_HOST_UNREACHABLE) {
                    throw failure;
                }
                log("retrying " + host + ":" + port + " after " + CONNECT_TIMEOUT_MS
                        + " ms: " + failure.getMessage());
            }
        }
    }
}
