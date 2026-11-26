package org.phonelink.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;

/** Closing that never throws; shared so the protocol code stays Android-free. */
final class Closeables {

    /**
     * How long a finished connection may wait for its FIN to be acknowledged
     * before the socket is reset.
     */
    static final int CLOSE_LINGER_SECONDS = 1;

    private Closeables() {
    }

    static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing is best effort; the caller already has the failure it cares about.
        }
    }

    /**
     * Close a finished connection without leaving an orphaned socket behind.
     *
     * <p>A plain {@code close()} only queues the FIN. When the peer is gone, or
     * the link loses that last packet, the socket stays in {@code FIN_WAIT1}
     * (or {@code LAST_ACK}) until the kernel exhausts its retransmit budget.
     * On this phone that can be many minutes, and the phone keeps the orphan in
     * its TCP table. A later client that reuses the same 4-tuple is then
     * blackholed: a SYN that lands on a {@code FIN_WAIT1} socket is answered
     * with a challenge ACK, never a SYN-ACK, so the new connection hangs until
     * the client gives up.
     *
     * <p>Bounding the close to {@link #CLOSE_LINGER_SECONDS} keeps the graceful
     * path (a live peer acknowledges in milliseconds, and the socket closes
     * normally) while a dead peer is reset, which frees the tuple at once.
     */
    static void closeBoundedQuietly(Socket socket) {
        closeWithLinger(socket, CLOSE_LINGER_SECONDS);
    }

    /**
     * Close and reset at once. Used when the connection never carried a byte,
     * so there is nothing a reset could truncate, and when the caller must not
     * block (the accept loop refusing a client).
     */
    static void resetQuietly(Socket socket) {
        closeWithLinger(socket, 0);
    }

    private static void closeWithLinger(Socket socket, int seconds) {
        if (socket == null) {
            return;
        }
        try {
            if (!socket.isClosed()) {
                socket.setSoLinger(true, seconds);
            }
        } catch (IOException | RuntimeException ignored) {
            // The socket may already be closed or unsupported; close() below
            // still runs and is what matters.
        }
        closeQuietly(socket);
    }
}
