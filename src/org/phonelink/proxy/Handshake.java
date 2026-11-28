package org.phonelink.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The SOCKS5 greeting, RFC 1929 authentication and request parsing for one
 * client connection, and the reply framing both commands answer with.
 *
 * <p>It holds only the configured credentials and the log seam; sockets belong
 * to {@link Socks5Server}. Deliberately free of Android types.
 */
final class Handshake {
    static final int REPLY_SUCCEEDED = 0x00;
    static final int REPLY_GENERAL_FAILURE = 0x01;
    static final int REPLY_NETWORK_UNREACHABLE = 0x03;
    static final int REPLY_HOST_UNREACHABLE = 0x04;
    static final int REPLY_CONNECTION_REFUSED = 0x05;
    static final int REPLY_COMMAND_NOT_SUPPORTED = 0x07;
    static final int REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

    static final int VERSION = 0x05;
    static final int METHOD_NO_AUTH = 0x00;
    static final int METHOD_USER_PASSWORD = 0x02;
    static final int METHOD_NONE_ACCEPTABLE = 0xFF;
    static final int COMMAND_CONNECT = 0x01;
    static final int COMMAND_UDP_ASSOCIATE = 0x03;
    static final int ADDRESS_IPV4 = 0x01;
    static final int ADDRESS_DOMAIN = 0x03;
    static final int ADDRESS_IPV6 = 0x04;

    private final String user;
    private final String password;
    private final Socks5Server.Logger logger;

    Handshake(String user, String password, Socks5Server.Logger logger) {
        this.user = user;
        this.password = password;
        this.logger = logger;
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    /** Method negotiation; returns false when the client cannot be served. */
    boolean negotiate(DataInputStream in, OutputStream out) throws IOException {
        if (in.readUnsignedByte() != VERSION) {
            return false;
        }
        int offered = in.readUnsignedByte();
        byte[] methods = new byte[offered];
        in.readFully(methods);

        boolean authenticate = !user.isEmpty() && !password.isEmpty();
        int chosen = -1;
        for (byte method : methods) {
            int value = method & 0xFF;
            if (authenticate ? value == METHOD_USER_PASSWORD : value == METHOD_NO_AUTH) {
                chosen = value;
                break;
            }
        }
        if (chosen < 0) {
            out.write(new byte[] {VERSION, (byte) METHOD_NONE_ACCEPTABLE});
            out.flush();
            return false;
        }
        out.write(new byte[] {VERSION, (byte) chosen});
        out.flush();
        return chosen != METHOD_USER_PASSWORD || authenticate(in, out);
    }

    private boolean authenticate(DataInputStream in, OutputStream out) throws IOException {
        if (in.readUnsignedByte() != 0x01) {
            return false;
        }
        byte[] givenUser = new byte[in.readUnsignedByte()];
        in.readFully(givenUser);
        byte[] givenPassword = new byte[in.readUnsignedByte()];
        in.readFully(givenPassword);

        boolean userMatches =
                MessageDigest.isEqual(givenUser, user.getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches =
                MessageDigest.isEqual(givenPassword, password.getBytes(StandardCharsets.UTF_8));
        // Both comparisons always run, so a wrong user does not answer faster
        // than a wrong password.
        boolean granted = userMatches & passwordMatches;

        out.write(new byte[] {0x01, (byte) (granted ? 0x00 : 0x01)});
        out.flush();
        if (!granted) {
            log("rejected a client with bad credentials");
        }
        return granted;
    }

    Socks5Request readRequest(DataInputStream in, OutputStream out) throws IOException {
        if (in.readUnsignedByte() != VERSION) {
            return null;
        }
        int command = in.readUnsignedByte();
        in.readUnsignedByte(); // reserved
        int addressType = in.readUnsignedByte();

        String host;
        switch (addressType) {
            case ADDRESS_IPV4:
            case ADDRESS_IPV6:
                byte[] address = new byte[addressType == ADDRESS_IPV4 ? 4 : 16];
                in.readFully(address);
                host = InetAddress.getByAddress(address).getHostAddress();
                break;
            case ADDRESS_DOMAIN:
                byte[] name = new byte[in.readUnsignedByte()];
                in.readFully(name);
                host = new String(name, StandardCharsets.UTF_8);
                if (host.isEmpty()) {
                    writeReply(out, REPLY_GENERAL_FAILURE, null);
                    return null;
                }
                break;
            default:
                writeReply(out, REPLY_ADDRESS_TYPE_NOT_SUPPORTED, null);
                return null;
        }
        int port = in.readUnsignedShort();
        return new Socks5Request(command, addressType, host, port);
    }

    static void writeReply(OutputStream out, int reply, Socket bound) throws IOException {
        InetAddress address = null;
        int port = 0;
        if (bound != null) {
            address = bound.getLocalAddress();
            port = bound.getLocalPort();
        }
        writeReplyTo(out, reply, address, port);
    }

    /**
     * The reply with an explicit bound address, for a UDP relay whose socket is
     * bound to the wildcard instead of one address.
     */
    static void writeReplyTo(OutputStream out, int reply, InetAddress address, int port)
            throws IOException {
        byte[] bytes = address == null ? new byte[4] : address.getAddress();
        if (bytes.length != 4 && bytes.length != 16) {
            bytes = new byte[4];
        }
        ByteArrayOutputStream replyBytes = new ByteArrayOutputStream(22);
        replyBytes.write(VERSION);
        replyBytes.write(reply);
        replyBytes.write(0x00);
        replyBytes.write(bytes.length == 16 ? ADDRESS_IPV6 : ADDRESS_IPV4);
        replyBytes.write(bytes, 0, bytes.length);
        replyBytes.write((port >> 8) & 0xFF);
        replyBytes.write(port & 0xFF);
        out.write(replyBytes.toByteArray());
        out.flush();
    }
}
