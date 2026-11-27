package org.phonelink.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The adb transfer: one loopback connection that carries a
 * {@link ProfileDocument}, then closes.
 *
 * <p>The laptop picks a free port and a one-time token, starts an {@code EXPORT}
 * or {@code IMPORT} action with both as extras, forwards a local port to that
 * device port with {@code adb forward}, and speaks this protocol on it. The
 * document never touches the phone's file system and no password is ever printed
 * by {@code dumpsys} or put on an {@code am} command line.
 *
 * <p>Plain Java so the framing, the token match and the limits are testable on
 * a JVM: only {@link ProxyService} knows about Android (the keyguard gate, the
 * foreground notification, {@link ConfigStore}). The gate is applied before this
 * class is called, so nothing here binds a locked phone.
 *
 * <p>The wire is UTF-8. A line is ASCII, ends in one {@code \n}, and is at most
 * {@link #MAX_LINE_BYTES}; the body is length-prefixed and copied exactly, never
 * terminated by a newline.
 *
 * <pre>
 * client  PLX1 &lt;token&gt;\n
 * export  OK &lt;n&gt;\n + n bytes of document
 * import  PUT &lt;n&gt;\n + n bytes of document, then OK &lt;count&gt;\n
 * error   ERR &lt;code&gt; &lt;message&gt;\n
 * </pre>
 */
final class ProfileTransfer {

    /** The largest document either direction carries; more is refused unread. */
    static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    /** A line is a magic word, a token or one number: this is generous. */
    static final int MAX_LINE_BYTES = 256;
    /** How long the action waits for its one connection, and for each read. */
    static final int DEFAULT_LIFETIME_MS = 60_000;

    private static final String MAGIC = "PLX1";

    private ProfileTransfer() {
    }

    /** Where an export's document comes from. */
    interface Export {
        String document();
    }

    /** Where an import's document goes; returns the number of saved proxies. */
    interface Import {
        int apply(String document);
    }

    /**
     * A refused or failed transfer, named by the code the client was sent (or,
     * before a connection, the code the service reports). The message is for the
     * app's own log and never contains a document or a password.
     */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        final String code;

        Failure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    static boolean validPort(int port) {
        return port >= 1024 && port <= 65535;
    }

    /** A token the laptop can put in an {@code --es} without quoting trouble. */
    static boolean validToken(String token) {
        if (token == null || token.isEmpty() || token.length() > 128) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            char c = token.charAt(index);
            if (c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    /** Constant-time when the two are the same length, which is all this needs. */
    static boolean tokenMatches(String expected, String given) {
        if (expected == null || given == null) {
            return false;
        }
        return MessageDigest.isEqual(bytes(expected), bytes(given));
    }

    /** Export from a fresh loopback listener on {@code port}. */
    static void export(int port, String token, long lifetimeMs, Export source)
            throws Failure {
        ServerSocket server = bind(port, lifetimeMs);
        try {
            export(server, token, lifetimeMs, source);
        } finally {
            Closeables.closeQuietly(server);
        }
    }

    /** Import through a fresh loopback listener on {@code port}. */
    static void importDocument(int port, String token, long lifetimeMs, Import sink)
            throws Failure {
        ServerSocket server = bind(port, lifetimeMs);
        try {
            importDocument(server, token, lifetimeMs, sink);
        } finally {
            Closeables.closeQuietly(server);
        }
    }

    /**
     * Export through an already bound listener. Package-private so the JVM test
     * can ask for port 0 and read the port it got, with no race for a free one.
     */
    static void export(ServerSocket server, String token, long lifetimeMs, Export source)
            throws Failure {
        serve(server, true, token, lifetimeMs, source, null);
    }

    /** Import through an already bound listener; the test's seam. */
    static void importDocument(
            ServerSocket server, String token, long lifetimeMs, Import sink)
            throws Failure {
        serve(server, false, token, lifetimeMs, null, sink);
    }

    private static ServerSocket bind(int port, long lifetimeMs) throws Failure {
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            // Never 0.0.0.0: this socket is the whole of adb's way in, and it
            // exists only on loopback for one connection.
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1);
            server.setSoTimeout(timeout(lifetimeMs));
            return server;
        } catch (IOException | RuntimeException e) {
            Closeables.closeQuietly(server);
            throw new Failure("bind-failed", "cannot listen on 127.0.0.1:" + port);
        }
    }

    /**
     * One connection, one request, one response. The server socket is the
     * caller's; this closes the accepted socket and returns after it, so an
     * action that started this can stop.
     */
    private static void serve(
            ServerSocket server,
            boolean exporting,
            String token,
            long lifetimeMs,
            Export source,
            Import sink)
            throws Failure {
        Socket client = null;
        try {
            server.setSoTimeout(timeout(lifetimeMs));
            client = server.accept();
            client.setSoTimeout(timeout(lifetimeMs));
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String hello = readLine(in);
            if (hello == null) {
                throw new Failure("timeout", "the laptop sent no token");
            }
            String[] words = hello.split(" ", -1);
            if (words.length != 2
                    || !MAGIC.equals(words[0])
                    || !tokenMatches(token, words[1])) {
                // Say what happened without confirming which half was wrong.
                refuse(out, "bad-token", "the token does not match this transfer");
            }

            if (exporting) {
                String document;
                try {
                    document = source.document();
                } catch (RuntimeException e) {
                    throw new Failure("io", "cannot read the saved proxies");
                }
                byte[] body = bytes(document);
                if (body.length == 0 || body.length > MAX_DOCUMENT_BYTES) {
                    refuse(out, "too-large", "the document is larger than the transfer allows");
                }
                write(out, "OK " + body.length + "\n");
                out.write(body);
                out.flush();
            } else {
                String command = readLine(in);
                if (command == null) {
                    throw new Failure("timeout", "the laptop sent no document header");
                }
                String[] put = command.split(" ", -1);
                if (put.length != 2 || !"PUT".equals(put[0])) {
                    refuse(out, "bad-document", "expected PUT <length>");
                }
                long declared;
                try {
                    declared = Long.parseLong(put[1]);
                } catch (NumberFormatException e) {
                    refuse(out, "bad-document", "the document length is not a number");
                    return;
                }
                if (declared < 1 || declared > MAX_DOCUMENT_BYTES) {
                    refuse(out, "too-large", "the document is larger than the transfer allows");
                }
                byte[] body = readBody(in, (int) declared);
                int count;
                try {
                    count = sink.apply(new String(body, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    // Never echo the exception's text: a parser message can quote
                    // a fragment of the file, and the file holds passwords.
                    refuse(out, "bad-document", "the document is not valid for this app");
                    return;
                }
                write(out, "OK " + count + "\n");
                out.flush();
            }
        } catch (SocketTimeoutException e) {
            throw new Failure("timeout", "the transfer timed out");
        } catch (IOException e) {
            throw new Failure("io", "the transfer failed");
        } finally {
            // Bounded, so the bytes written above are delivered before the
            // action ends even when the laptop has already gone.
            Closeables.closeBoundedQuietly(client);
        }
    }

    private static byte[] readBody(InputStream in, int length) throws IOException, Failure {
        byte[] body = new byte[length];
        int at = 0;
        while (at < length) {
            int read = in.read(body, at, length - at);
            if (read < 0) {
                throw new Failure("bad-document", "the document is shorter than its length says");
            }
            at += read;
        }
        return body;
    }

    private static String readLine(InputStream in) throws IOException, Failure {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (line.size() == 0) {
                    return null;
                }
                throw new Failure("bad-document", "the request ends in the middle of a line");
            }
            if (b == '\n') {
                break;
            }
            if (line.size() >= MAX_LINE_BYTES) {
                throw new Failure("bad-document", "a request line is too long");
            }
            line.write(b);
        }
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        // A client that writes CRLF is still understood; the body is not.
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    private static void refuse(OutputStream out, String code, String message)
            throws Failure {
        try {
            write(out, error(code, message));
            out.flush();
        } catch (IOException ignored) {
            // The client may already be gone; the failure is still the answer.
        }
        throw new Failure(code, message);
    }

    static String error(String code, String message) {
        return "ERR " + code + " " + message + "\n";
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(bytes(text));
    }

    private static int timeout(long lifetimeMs) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, lifetimeMs));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
