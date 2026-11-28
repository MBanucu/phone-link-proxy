package org.phonelink.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One CONNECT tunnel: open the upstream with the retry, answer, then shovel
 * bytes both ways until both directions are done, with the linger that bounds
 * a half-close.
 *
 * <p>The handler thread drives client-to-upstream and one relay thread drives
 * upstream-to-client, so a live connection costs two threads rather than three.
 * When a direction ends cleanly the other one gets the configured linger to
 * drain, then both sockets are closed; a failed direction ends the tunnel at
 * once. That bound is what stops a client half-close from pinning a handler
 * while the origin holds its side open.
 */
final class TcpRelay {

    /** Where the listener keeps its relay threads, so a stop can interrupt them. */
    interface Pumps {
        /** @return false once the listener is closing, so the tunnel ends here */
        boolean admit(Thread pump);

        void release(Thread pump);
    }

    /** Where the listener keeps its sockets, so a stop can close the upstream. */
    interface Sockets {
        /** @return false once the listener is closing, so the tunnel ends here */
        boolean track(Socket socket);

        void untrack(Socket socket);
    }

    /**
     * One upstream attempt. A lost handshake on the cellular uplink is a
     * timeout here, and it is better to surface it in 4 s than to make the
     * page wait out 15 s for a connection that is never going to complete.
     */
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    /** One retry with a fresh socket is enough: a new source port gets a new NAT mapping. */
    private static final int CONNECT_ATTEMPTS = 2;

    private final Upstream upstream;
    private final Traffic traffic;
    private final ScheduledThreadPoolExecutor linger;
    private final long lingerMs;
    private final Pumps pumps;
    private final Sockets sockets;
    private final Socks5Server.Logger logger;

    TcpRelay(
            Upstream upstream,
            Traffic traffic,
            ScheduledThreadPoolExecutor linger,
            long lingerMs,
            Pumps pumps,
            Sockets sockets,
            Socks5Server.Logger logger) {
        this.upstream = upstream;
        this.traffic = traffic;
        this.linger = linger;
        this.lingerMs = lingerMs;
        this.pumps = pumps;
        this.sockets = sockets;
        this.logger = logger;
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    /**
     * Serve one CONNECT: open the upstream, reply, then pump until both ends
     * are done. The upstream socket is tracked so a listener stop closes it.
     */
    void serve(Socket client, OutputStream out, Socks5Request request) throws IOException {
        Socket connected;
        try {
            connected = connect(request.host, request.port);
        } catch (Upstream.Failure failure) {
            log(failure.getMessage());
            Handshake.writeReply(out, failure.reply, null);
            return;
        }
        // The upstream half is a tunnel too: a stop has to close it.
        if (!sockets.track(connected)) {
            Closeables.resetQuietly(connected);
            return;
        }
        try {
            Handshake.writeReply(out, Handshake.REPLY_SUCCEEDED, connected);
            client.setSoTimeout(0);
            run(client, connected);
        } finally {
            sockets.untrack(connected);
            Closeables.closeBoundedQuietly(connected);
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
    private Socket connect(String host, int port) throws Upstream.Failure {
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

    /** Shovel bytes both ways until both directions are done, then close. */
    private void run(Socket client, Socket upstream) {
        RelayEnd end = new RelayEnd(client, upstream);
        CountDownLatch otherDone = new CountDownLatch(1);

        Thread download = new Thread(() -> {
            boolean clean = copy(upstream, client, false);
            end.ended(clean);
            otherDone.countDown();
        }, "socks5-relay");
        download.setDaemon(true);
        if (!pumps.admit(download)) {
            // The tunnel is already ending; do not create a thread that
            // would only block on a socket close() has closed.
            end.closeBoth();
            return;
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
            pumps.release(download);
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
                        this::closeBoth, lingerMs, TimeUnit.MILLISECONDS);
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
}
