package org.phonelink.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One UDP association: the client-facing relay socket, the cellular datagram
 * socket, and the one queue between them.
 *
 * <p>The client sends SOCKS5 UDP request datagrams to the relay socket and
 * receives wrapped replies from it. Upstream replies arrive on the
 * unconnected cellular socket and are wrapped with their source address, so
 * the client can tell one destination from another. The association pins
 * itself to the first source datagram (or to the address in the
 * {@code UDP ASSOCIATE} request when it named one), so another host on the
 * hotspot cannot drive the relay.
 */
final class UdpAssociation implements Closeable {

    /** Where the listener keeps its live associations, so a stop ends them. */
    interface Registry {
        /** @return false once the listener is closing, so nothing is admitted */
        boolean admit(UdpAssociation association);

        void release(UdpAssociation association);
    }

    /** The bounds one association is built with; a test can fill a small one cheaply. */
    static final class Settings {
        final int maxDatagram;
        final int queueSize;
        final long idleMs;

        Settings(int maxDatagram, int queueSize, long idleMs) {
            this.maxDatagram = maxDatagram;
            this.queueSize = queueSize;
            this.idleMs = idleMs;
        }
    }

    /**
     * Serve one UDP ASSOCIATE: open both sockets, reply with the relay's bound
     * address, then hold the control connection until the client closes it.
     * The datagrams themselves are carried by the association's own threads,
     * because this thread has to stay on the TCP read: closing the control
     * connection is what ends the association (RFC 1928 §7).
     */
    static void serve(
            Upstream upstream,
            Socket control,
            OutputStream out,
            Socks5Request request,
            Settings settings,
            Socks5Server.Logger logger,
            Registry registry) throws IOException {
        UdpAssociation association;
        try {
            association = open(upstream, control, request, settings, logger, registry);
        } catch (Upstream.Failure failure) {
            log(logger, failure.getMessage());
            Handshake.writeReply(out, failure.reply, null);
            return;
        }
        if (association == null) {
            // close() won the race; the listener is stopping, so nothing here
            // may be admitted.
            Handshake.writeReply(out, Handshake.REPLY_GENERAL_FAILURE, null);
            return;
        }
        try {
            association.start();
            Handshake.writeReplyTo(out, Handshake.REPLY_SUCCEEDED,
                    association.replyAddress(), association.replyPort());
            control.setSoTimeout(0);
            // Nothing is expected on the control connection after the reply;
            // reading it is only how its close is noticed. Bytes are ignored.
            InputStream input = control.getInputStream();
            byte[] discard = new byte[256];
            while (input.read(discard) != -1) {
                // The association ends when this returns -1.
            }
        } finally {
            association.close();
        }
    }

    /**
     * Bind the relay and the cellular datagram socket and publish the
     * association before its threads start, so a listener stop can close a
     * socket that is not yet relaying.
     *
     * @return null when the listener is closing
     */
    private static UdpAssociation open(
            Upstream upstream,
            Socket control,
            Socks5Request request,
            Settings settings,
            Socks5Server.Logger logger,
            Registry registry) throws Upstream.Failure {
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
            throw new Upstream.Failure(Handshake.REPLY_GENERAL_FAILURE,
                    "cannot open a UDP relay socket: " + e.getMessage());
        }
        UdpAssociation association = new UdpAssociation(
                control, relaySocket, upstreamSocket, request,
                settings, upstream, logger, registry);
        if (!registry.admit(association)) {
            association.close();
            return null;
        }
        return association;
    }

    private static void log(Socks5Server.Logger logger, String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    private final Socket control;
    private final DatagramSocket relay;
    private final DatagramSocket upstreamSocket;
    private final BlockingQueue<Outbound> queue;
    private final InetAddress declaredAddress;
    private final int declaredPort;
    private final Settings settings;
    private final Upstream upstream;
    private final Socks5Server.Logger logger;
    private final Registry registry;
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object pinLock = new Object();
    private InetAddress pinnedAddress;
    private int pinnedPort = -1;
    private volatile long lastActivity;
    private volatile InetSocketAddress clientEndpoint;

    private UdpAssociation(
            Socket control,
            DatagramSocket relay,
            DatagramSocket upstreamSocket,
            Socks5Request request,
            Settings settings,
            Upstream upstream,
            Socks5Server.Logger logger,
            Registry registry) {
        this.control = control;
        this.relay = relay;
        this.upstreamSocket = upstreamSocket;
        this.queue = new LinkedBlockingQueue<>(settings.queueSize);
        this.settings = settings;
        this.upstream = upstream;
        this.logger = logger;
        this.registry = registry;
        InetAddress address = null;
        if (request.addressType == Handshake.ADDRESS_IPV4
                || request.addressType == Handshake.ADDRESS_IPV6) {
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

    private void start() {
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
        byte[] buffer = new byte[settings.maxDatagram + 1];
        try {
            while (!closed.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                relay.receive(packet);
                UdpPacket request = UdpPacket.parse(
                        packet.getData(), packet.getLength(), settings.maxDatagram);
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
                log(logger, "udp relay failed: " + e.getMessage());
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
                        log(logger, failure.getMessage());
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
        byte[] buffer = new byte[settings.maxDatagram + 1];
        long idleNanos = TimeUnit.MILLISECONDS.toNanos(settings.idleMs);
        int pollMs = (int) Math.max(50, Math.min(1_000, settings.idleMs / 4));
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
                        packet.getData(), packet.getLength(), settings.maxDatagram);
                InetSocketAddress client = clientEndpoint;
                if (reply == null || client == null) {
                    continue;
                }
                relay.send(new DatagramPacket(reply, reply.length, client));
            }
        } catch (IOException e) {
            if (!closed.get()) {
                log(logger, "udp relay failed: " + e.getMessage());
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
        registry.release(this);
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

    /** One queued client datagram: its parsed destination and its payload. */
    private static final class Outbound {
        final UdpPacket request;
        final byte[] payload;

        Outbound(UdpPacket request, byte[] payload) {
            this.request = request;
            this.payload = payload;
        }
    }
}
