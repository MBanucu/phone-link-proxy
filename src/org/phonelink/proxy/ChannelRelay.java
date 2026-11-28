package org.phonelink.proxy;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The CONNECT relay: one fixed pool of selector threads carries every tunnel.
 *
 * <p>{@link TcpRelay} opens the upstream and writes the SOCKS reply in a
 * handler thread. After that both {@link SocketChannel}s are handed here and
 * switched to non-blocking mode. Each thread owns one {@link Selector}, reads
 * into a direct buffer per direction and writes until the buffer is empty,
 * enabling {@code OP_WRITE} for a partial write and disabling the source's
 * {@code OP_READ} while that buffer drains. A live tunnel costs no thread, so
 * a browser's hundred keep-alive flows and a thousand idle CONNECTs fit in the
 * same two relay threads.
 *
 * <p>Half-close is kept from the thread-per-connection relay: the first clean
 * end shuts the peer's output down and schedules a close after the linger, and
 * the second clean end closes at once. A failed direction closes both sockets
 * immediately. All channel I/O happens on the selector thread; the linger only
 * marks a tunnel ended and wakes the selector, which then closes it. That
 * keeps a close from racing a write or a read.
 *
 * <p>Deliberately free of Android types, like the rest of the protocol.
 */
final class ChannelRelay {

    /** Relays per listener: fixed, whatever the number of live connections. */
    static final int THREADS = 2;

    /** One direct buffer per direction, allocated only when that direction moves data. */
    static final int BUFFER_SIZE = 32 * 1024;

    /** A selector waits at most this long before checking whether it is stopping. */
    private static final long SELECT_TICK_MS = 1_000;

    /** Where the listener keeps the sockets a stop must close and counts clients. */
    interface Sockets {
        /** @return false once the listener is closing, so the tunnel ends here */
        boolean track(Socket socket);

        void untrack(Socket socket);

        /** One CONNECT's client is fully released: decrement the client count. */
        void relayEnded();
    }

    private final Traffic traffic;
    private final ScheduledThreadPoolExecutor linger;
    private final long lingerMs;
    private final Sockets sockets;
    private final Socks5Server.Logger logger;
    private final RelayLoop[] loops;
    private final AtomicInteger next = new AtomicInteger();
    /** Guards {@link #register} against {@link #close}. */
    private final Object lifecycle = new Object();
    private boolean closed;

    ChannelRelay(
            Traffic traffic,
            ScheduledThreadPoolExecutor linger,
            long lingerMs,
            Sockets sockets,
            Socks5Server.Logger logger,
            int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("at least one relay thread is needed");
        }
        this.traffic = traffic;
        this.linger = linger;
        this.lingerMs = lingerMs;
        this.sockets = sockets;
        this.logger = logger;
        loops = new RelayLoop[threads];
        for (int index = 0; index < threads; index++) {
            loops[index] = new RelayLoop();
        }
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    /** Open the selectors and start the fixed pool; the server calls this after binding. */
    void start() throws IOException {
        List<RelayLoop> opened = new ArrayList<>();
        try {
            for (RelayLoop loop : loops) {
                loop.open();
                opened.add(loop);
            }
        } catch (IOException e) {
            for (RelayLoop loop : opened) {
                loop.abort();
            }
            throw e;
        }
        for (RelayLoop loop : loops) {
            loop.start();
        }
    }

    /**
     * Hand one CONNECT to a selector.
     *
     * @return true when the relay now owns both channels and will release the
     *     client count; false when the listener is closing and the caller must
     *     close them itself
     */
    boolean register(SocketChannel client, SocketChannel upstream) {
        RelayLoop loop;
        synchronized (lifecycle) {
            if (closed) {
                return false;
            }
            loop = loops[Math.floorMod(next.getAndIncrement(), loops.length)];
        }
        return loop.offer(new Tunnel(loop, client, upstream));
    }

    /** Stop the relays; the listener has already closed every tracked socket. */
    void close() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
        }
        for (RelayLoop loop : loops) {
            loop.stop();
        }
    }

    /** One selector thread and the tunnels assigned to it. */
    private final class RelayLoop implements Runnable {
        private final Queue<Tunnel> incoming = new ConcurrentLinkedQueue<>();
        private final Queue<Tunnel> finished = new ConcurrentLinkedQueue<>();
        /** Registered tunnels, kept here because a closed channel's key vanishes. */
        private final Set<Tunnel> attached = new HashSet<>();
        private Selector selector;
        private volatile boolean running = true;

        void open() throws IOException {
            selector = Selector.open();
        }

        void start() {
            Thread thread = new Thread(this, "socks5-relay");
            thread.setDaemon(true);
            thread.start();
        }

        /** Queue a tunnel, or refuse it once this selector is no longer running. */
        synchronized boolean offer(Tunnel tunnel) {
            if (!running) {
                return false;
            }
            incoming.add(tunnel);
            wakeup();
            return true;
        }

        void enqueueFinished(Tunnel tunnel) {
            finished.add(tunnel);
            wakeup();
        }

        /** Called on this loop's thread once both keys are registered. */
        void attached(Tunnel tunnel) {
            attached.add(tunnel);
        }

        /** Called on this loop's thread when the tunnel is closed. */
        void detach(Tunnel tunnel) {
            attached.remove(tunnel);
        }

        void wakeup() {
            Selector current = selector;
            if (current != null) {
                current.wakeup();
            }
        }

        void stop() {
            synchronized (this) {
                running = false;
            }
            wakeup();
        }

        /** A start that failed before the thread ran: release the selector. */
        void abort() {
            running = false;
            Closeables.closeQuietly(selector);
        }

        @Override
        public void run() {
            try {
                while (running) {
                    registerPending();
                    selector.select(SELECT_TICK_MS);
                    Iterator<SelectionKey> selected = selector.selectedKeys().iterator();
                    while (selected.hasNext()) {
                        SelectionKey key = selected.next();
                        selected.remove();
                        Tunnel tunnel = (Tunnel) key.attachment();
                        if (tunnel == null) {
                            continue;
                        }
                        try {
                            tunnel.ready(key);
                        } catch (CancelledKeyException e) {
                            tunnel.end();
                        } catch (RuntimeException e) {
                            tunnel.end();
                        }
                    }
                    drainFinished();
                }
            } catch (IOException e) {
                if (running) {
                    log("relay selector failed: " + e.getMessage());
                }
            } finally {
                releaseEverything();
            }
        }

        private void registerPending() {
            Tunnel tunnel;
            while ((tunnel = incoming.poll()) != null) {
                if (!running) {
                    tunnel.closeNow();
                    continue;
                }
                try {
                    tunnel.attach(selector);
                } catch (ClosedChannelException e) {
                    tunnel.closeNow();
                } catch (RuntimeException e) {
                    tunnel.closeNow();
                }
            }
        }

        private void drainFinished() {
            Tunnel tunnel;
            while ((tunnel = finished.poll()) != null) {
                tunnel.closeNow();
            }
        }

        /** A stop or a selector failure still closes and untracks every tunnel. */
        private void releaseEverything() {
            List<Tunnel> remaining = new ArrayList<>(attached);
            synchronized (this) {
                running = false;
                Tunnel pending;
                while ((pending = incoming.poll()) != null) {
                    remaining.add(pending);
                }
            }
            for (Tunnel tunnel : remaining) {
                tunnel.end();
            }
            drainFinished();
            Closeables.closeQuietly(selector);
        }
    }

    /** One CONNECT after the reply: two channels, two buffers, one linger. */
    private final class Tunnel {
        private final RelayLoop loop;
        private final SocketChannel client;
        private final SocketChannel upstream;
        private final AtomicReference<ScheduledFuture<?>> reaper = new AtomicReference<>();
        private SelectionKey clientKey;
        private SelectionKey upstreamKey;
        private ByteBuffer upload;
        private ByteBuffer download;
        private boolean clientEof;
        private boolean upstreamEof;
        private volatile boolean ended;
        private boolean closedNow;

        Tunnel(RelayLoop loop, SocketChannel client, SocketChannel upstream) {
            this.loop = loop;
            this.client = client;
            this.upstream = upstream;
        }

        /** Register both channels, unless the tunnel ended while it was queued. */
        synchronized void attach(Selector selector) throws ClosedChannelException {
            if (ended) {
                return;
            }
            clientKey = client.register(selector, SelectionKey.OP_READ, this);
            try {
                upstreamKey = upstream.register(selector, SelectionKey.OP_READ, this);
            } catch (ClosedChannelException e) {
                clientKey.cancel();
                clientKey = null;
                throw e;
            }
            loop.attached(this);
        }

        /** One selected key: flush what the other direction left, then read. */
        void ready(SelectionKey key) {
            if (ended) {
                return;
            }
            if (key == clientKey) {
                if (hasPending(download)) {
                    drain(download, client, false);
                }
                if (!ended && key.isValid() && (key.interestOps() & SelectionKey.OP_READ) != 0) {
                    read(client, true);
                }
            } else if (key == upstreamKey) {
                if (hasPending(upload)) {
                    drain(upload, upstream, true);
                }
                if (!ended && key.isValid() && (key.interestOps() & SelectionKey.OP_READ) != 0) {
                    read(upstream, false);
                }
            }
            if (!ended) {
                applyInterests();
            }
        }

        /** Read one chunk from {@code source} into its direction's buffer. */
        private void read(SocketChannel source, boolean uploaded) {
            ByteBuffer buffer = uploaded ? upload : download;
            if (buffer == null) {
                buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
                // An empty buffer is one with nothing left to write, not one
                // that has just been reset to the whole capacity.
                buffer.limit(0);
                if (uploaded) {
                    upload = buffer;
                } else {
                    download = buffer;
                }
            }
            buffer.clear();
            int read;
            try {
                read = source.read(buffer);
            } catch (IOException e) {
                fail();
                return;
            }
            if (read < 0) {
                buffer.limit(0);
                sourceEof(uploaded);
                return;
            }
            if (read == 0) {
                buffer.limit(0);
                return;
            }
            buffer.flip();
            drain(buffer, uploaded ? upstream : client, uploaded);
        }

        /** Write as much of {@code buffer} as the sink takes; keep the rest for OP_WRITE. */
        private void drain(ByteBuffer buffer, SocketChannel sink, boolean uploaded) {
            try {
                while (buffer.hasRemaining()) {
                    int written = sink.write(buffer);
                    if (written <= 0) {
                        break;
                    }
                    if (uploaded) {
                        traffic.add(written, 0);
                    } else {
                        traffic.add(0, written);
                    }
                }
            } catch (IOException e) {
                fail();
                return;
            }
            if (!buffer.hasRemaining()) {
                // Keep "nothing to write" distinct from a cleared buffer's
                // whole-capacity limit; interests() and the OP_WRITE path
                // both ask hasRemaining().
                buffer.position(0).limit(0);
            }
        }

        /** One source reached end-of-stream: forward the half-close and linger. */
        private void sourceEof(boolean uploaded) {
            if (uploaded) {
                clientEof = true;
            } else {
                upstreamEof = true;
            }
            try {
                (uploaded ? upstream : client).shutdownOutput();
            } catch (IOException e) {
                fail();
                return;
            }
            if (clientEof && upstreamEof) {
                end();
                return;
            }
            scheduleLinger();
        }

        private void scheduleLinger() {
            if (reaper.get() != null) {
                return;
            }
            try {
                ScheduledFuture<?> scheduled =
                        linger.schedule(this::end, lingerMs, TimeUnit.MILLISECONDS);
                if (!reaper.compareAndSet(null, scheduled)) {
                    scheduled.cancel(false);
                }
            } catch (RejectedExecutionException e) {
                // The listener is closing and has already closed the sockets.
                end();
            }
        }

        private void fail() {
            end();
        }

        /** Interest is read while the direction is free, write while it has data. */
        private void applyInterests() {
            apply(clientKey, interests(clientEof, upload, download));
            apply(upstreamKey, interests(upstreamEof, download, upload));
        }

        private void apply(SelectionKey key, int operations) {
            if (key == null) {
                return;
            }
            try {
                if (key.isValid() && key.interestOps() != operations) {
                    key.interestOps(operations);
                }
            } catch (CancelledKeyException e) {
                end();
            }
        }

        /** {@code sourceDone} means no more reads; the other buffer is pending writes. */
        private int interests(boolean sourceDone, ByteBuffer readBuffer, ByteBuffer writeBuffer) {
            int operations = 0;
            if (!sourceDone && !hasPending(readBuffer)) {
                operations |= SelectionKey.OP_READ;
            }
            if (hasPending(writeBuffer)) {
                operations |= SelectionKey.OP_WRITE;
            }
            return operations;
        }

        private boolean hasPending(ByteBuffer buffer) {
            return buffer != null && buffer.hasRemaining();
        }

        /** Mark the tunnel done and let the selector close it; safe from the linger. */
        void end() {
            ScheduledFuture<?> scheduled;
            synchronized (this) {
                if (ended) {
                    return;
                }
                ended = true;
                if (clientKey != null) {
                    clientKey.cancel();
                }
                if (upstreamKey != null) {
                    upstreamKey.cancel();
                }
                scheduled = reaper.getAndSet(null);
            }
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            loop.enqueueFinished(this);
            loop.wakeup();
        }

        /** Close both sockets, untrack them and release the client count exactly once. */
        void closeNow() {
            synchronized (this) {
                if (closedNow) {
                    return;
                }
                closedNow = true;
                ended = true;
                if (clientKey != null) {
                    clientKey.cancel();
                }
                if (upstreamKey != null) {
                    upstreamKey.cancel();
                }
            }
            Closeables.closeBoundedQuietly(client.socket());
            Closeables.closeBoundedQuietly(upstream.socket());
            sockets.untrack(client.socket());
            sockets.untrack(upstream.socket());
            loop.detach(this);
            sockets.relayEnded();
        }
    }
}
