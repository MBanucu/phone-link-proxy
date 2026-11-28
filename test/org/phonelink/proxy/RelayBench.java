package org.phonelink.proxy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback throughput for the CONNECT relay, measured the same way before and
 * after the selector rewrite.
 *
 * <p>One scenario is a direction (download is upstream to client, upload is
 * client to upstream), a flow count and a total number of MiB. All flows
 * connect and complete the SOCKS handshake first, then a barrier releases
 * them; the stopwatch covers only the bytes. The first, smaller run is a
 * warmup and is not printed.
 *
 * <p>Run it with {@code bash tests/relay-bench.sh}; the output is one CSV row
 * per run and one median row per scenario. Plain JDK, no phone: this is a
 * loopback measurement, not the phone's cellular rate.
 */
public final class RelayBench {

    private static final int CHUNK = 64 * 1024;
    private static final int TIMEOUT_MS = 60_000;

    private RelayBench() {
    }

    public static void main(String[] args) throws Exception {
        int mib = 256;
        int repeats = 5;
        int[] flows = {1, 4};
        for (String argument : args) {
            if (argument.startsWith("--mib=")) {
                mib = Integer.parseInt(argument.substring("--mib=".length()));
            } else if (argument.startsWith("--repeats=")) {
                repeats = Integer.parseInt(argument.substring("--repeats=".length()));
            } else if (argument.startsWith("--flows=")) {
                String[] parts = argument.substring("--flows=".length()).split(",");
                flows = new int[parts.length];
                for (int index = 0; index < parts.length; index++) {
                    flows[index] = Integer.parseInt(parts[index].trim());
                }
            } else {
                System.err.println("usage: RelayBench [--mib=256] [--repeats=5] [--flows=1,4]");
                System.exit(2);
            }
        }
        System.out.println("BENCH,direction,flows,mib,ms,mbps,cpu_ms_per_gib");
        for (String direction : new String[] {"down", "up"}) {
            for (int count : flows) {
                // A small warmup of the same shape, so the first timed run is
                // not paying for class loading and the JIT.
                run(direction, count, Math.max(1, mib / 16));
                List<Run> runs = new ArrayList<>();
                for (int repeat = 0; repeat < repeats; repeat++) {
                    Run result = run(direction, count, mib);
                    runs.add(result);
                    System.out.printf(Locale.US, "BENCH,%s,%d,%d,%d,%.1f,%.1f%n",
                            direction, count, mib, result.elapsedMs, result.mbps,
                            result.cpuMsPerGib);
                }
                Collections.sort(runs);
                Run median = runs.get(runs.size() / 2);
                System.out.printf(Locale.US, "MEDIAN,%s,%d,%d,%d,%.1f,%.1f%n",
                        direction, count, mib, median.elapsedMs, median.mbps,
                        median.cpuMsPerGib);
            }
        }
    }

    /** One measured scenario: wall time, rate and the CPU it cost. */
    private static final class Run implements Comparable<Run> {
        final long elapsedMs;
        final double mbps;
        final double cpuMsPerGib;

        Run(long elapsedMs, double mbps, double cpuMsPerGib) {
            this.elapsedMs = elapsedMs;
            this.mbps = mbps;
            this.cpuMsPerGib = cpuMsPerGib;
        }

        @Override
        public int compareTo(Run other) {
            return Double.compare(mbps, other.mbps);
        }
    }

    /** One scenario: move {@code mib} MiB and report the wall and CPU cost. */
    private static Run run(String direction, int flows, int mib) throws Exception {
        long perFlow = (long) mib * 1024 * 1024 / flows;
        try (Backend backend = new Backend(direction, perFlow);
                Socks5Server server = server(backend.port())) {
            server.start();
            List<Socket> clients = new ArrayList<>(flows);
            List<Thread> threads = new ArrayList<>(flows);
            CountDownLatch ready = new CountDownLatch(flows);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicLong bytes = new AtomicLong();
            for (int index = 0; index < flows; index++) {
                Socket client = open(server.port(), backend.port());
                clients.add(client);
                Thread thread = new Thread(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        if (direction.equals("down")) {
                            bytes.addAndGet(download(client));
                        } else {
                            bytes.addAndGet(upload(client, perFlow));
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }, "bench-flow-" + index);
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
            ready.await();
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean)
                            ManagementFactory.getOperatingSystemMXBean();
            long cpuStarted = os.getProcessCpuTime();
            long started = System.nanoTime();
            go.countDown();
            for (Thread thread : threads) {
                thread.join(TIMEOUT_MS);
                if (thread.isAlive()) {
                    throw new IOException("a flow did not finish within " + TIMEOUT_MS + " ms");
                }
            }
            long elapsed = System.nanoTime() - started;
            long cpuElapsed = os.getProcessCpuTime() - cpuStarted;
            if (failure.get() != null) {
                throw new IOException("a flow failed", failure.get());
            }
            long expected = perFlow * flows;
            if (bytes.get() != expected) {
                throw new IOException("moved " + bytes.get() + " of " + expected + " bytes");
            }
            for (Socket client : clients) {
                Closeables.closeQuietly(client);
            }
            double seconds = elapsed / 1_000_000_000.0;
            double mbps = (bytes.get() * 8.0 / 1_000_000.0) / seconds;
            double cpuMsPerGib = (cpuElapsed / 1_000_000.0) / (bytes.get() / (1024.0 * 1024.0 * 1024.0));
            return new Run(Math.round(seconds * 1000.0), mbps, cpuMsPerGib);
        }
    }

    private static Socks5Server server(int backendPort) {
        return new Socks5Server(0, "", "", new BenchUpstream(backendPort), new Traffic(),
                clients -> { }, message -> { }, 1_024, Socks5Server.RELAY_LINGER_MS);
    }

    private static Socket open(int proxyPort, int backendPort) throws IOException {
        Socket socket = new Socket("127.0.0.1", proxyPort);
        socket.setSoTimeout(TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        OutputStream out = socket.getOutputStream();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        out.write(new byte[] {0x05, 0x01, 0x00});
        out.flush();
        if (in.readUnsignedByte() != 0x05 || in.readUnsignedByte() != 0x00) {
            throw new IOException("the proxy refused no-auth");
        }
        byte[] request = new byte[] {
                0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1,
                (byte) (backendPort >> 8), (byte) backendPort};
        out.write(request);
        out.flush();
        int version = in.readUnsignedByte();
        int reply = in.readUnsignedByte();
        in.readUnsignedByte();
        int type = in.readUnsignedByte();
        if (version != 0x05 || reply != 0x00) {
            throw new IOException("CONNECT failed: version " + version + " reply " + reply);
        }
        in.readFully(new byte[type == 0x04 ? 16 : 4]);
        in.readUnsignedShort();
        return socket;
    }

    private static long download(Socket socket) throws IOException {
        byte[] buffer = new byte[CHUNK];
        InputStream in = socket.getInputStream();
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
        }
        return total;
    }

    private static long upload(Socket socket, long bytes) throws IOException {
        byte[] buffer = new byte[CHUNK];
        OutputStream out = socket.getOutputStream();
        long left = bytes;
        while (left > 0) {
            int chunk = (int) Math.min(left, buffer.length);
            out.write(buffer, 0, chunk);
            left -= chunk;
        }
        out.flush();
        socket.shutdownOutput();
        // The origin closes after its own EOF; read it so the flow is done.
        byte[] discard = new byte[CHUNK];
        while (socket.getInputStream().read(discard) != -1) {
            // Wait for the close.
        }
        return bytes;
    }

    /** Connects every CONNECT to the benchmark's loopback origin. */
    private static final class BenchUpstream implements Upstream {
        private final int backendPort;

        BenchUpstream(int backendPort) {
            this.backendPort = backendPort;
        }

        @Override
        public SocketChannel connect(String host, int port, int timeoutMs) throws Failure {
            try {
                SocketChannel channel = SocketChannel.open();
                channel.socket().connect(
                        new InetSocketAddress("127.0.0.1", backendPort), timeoutMs);
                channel.socket().setTcpNoDelay(true);
                return channel;
            } catch (IOException e) {
                throw new Failure(Handshake.REPLY_CONNECTION_REFUSED, e.getMessage());
            }
        }
    }

    /** The origin: a source for download, a sink for upload. */
    private static final class Backend implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread acceptor;
        private final String direction;
        private final long bytes;

        Backend(String direction, long bytes) throws IOException {
            this.direction = direction;
            this.bytes = bytes;
            listener = new ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(this::accept, "bench-backend");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void accept() {
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept();
                    Thread worker = new Thread(() -> serve(socket), "bench-origin");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (Socket open = socket) {
                byte[] buffer = new byte[CHUNK];
                if (direction.equals("down")) {
                    long left = bytes;
                    OutputStream out = open.getOutputStream();
                    while (left > 0) {
                        int chunk = (int) Math.min(left, buffer.length);
                        out.write(buffer, 0, chunk);
                        left -= chunk;
                    }
                    out.flush();
                    open.shutdownOutput();
                } else {
                    InputStream in = open.getInputStream();
                    while (in.read(buffer) != -1) {
                        // Drain the upload.
                    }
                    open.shutdownOutput();
                }
            } catch (IOException ignored) {
                // The benchmark's client closed first; that ends this origin.
            }
        }

        @Override
        public void close() {
            Closeables.closeQuietly(listener);
        }
    }
}
