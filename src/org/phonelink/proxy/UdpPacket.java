package org.phonelink.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The SOCKS5 UDP request/reply header (RFC 1928 §7):
 * {@code RSV RSV FRAG ATYP DST.ADDR DST.PORT DATA}. Parsing and encoding
 * are pure and Android-free, so the framing is tested without a phone.
 */
final class UdpPacket {
    /** Null for a domain name, which the association resolves on egress. */
    final InetAddress address;
    final String host;
    final int port;
    /** Where the payload starts in the datagram the caller passed in. */
    final int offset;

    private UdpPacket(InetAddress address, String host, int port, int offset) {
        this.address = address;
        this.host = host;
        this.port = port;
        this.offset = offset;
    }

    /**
     * Parse one request datagram; null means drop it. Reserved bytes must
     * be zero, {@code FRAG} other than zero is unsupported, the address
     * length has to fit, and a NUL port is not a destination.
     */
    static UdpPacket parse(byte[] data, int length, int max) {
        if (data == null || length < 4 || length > max) {
            return null;
        }
        if (data[0] != 0 || data[1] != 0 || data[2] != 0) {
            return null;
        }
        int addressType = data[3] & 0xFF;
        int cursor = 4;
        InetAddress address = null;
        String host;
        switch (addressType) {
            case Handshake.ADDRESS_IPV4:
            case Handshake.ADDRESS_IPV6: {
                int size = addressType == Handshake.ADDRESS_IPV4 ? 4 : 16;
                if (length < cursor + size + 2) {
                    return null;
                }
                byte[] raw = Arrays.copyOfRange(data, cursor, cursor + size);
                try {
                    address = InetAddress.getByAddress(raw);
                } catch (UnknownHostException impossible) {
                    return null;
                }
                host = address.getHostAddress();
                cursor += size;
                break;
            }
            case Handshake.ADDRESS_DOMAIN: {
                if (length < cursor + 1) {
                    return null;
                }
                int nameLength = data[cursor] & 0xFF;
                cursor++;
                if (nameLength == 0 || length < cursor + nameLength + 2) {
                    return null;
                }
                host = new String(data, cursor, nameLength, StandardCharsets.UTF_8);
                if (host.isEmpty()) {
                    return null;
                }
                cursor += nameLength;
                break;
            }
            default:
                return null;
        }
        int port = ((data[cursor] & 0xFF) << 8) | (data[cursor + 1] & 0xFF);
        cursor += 2;
        if (port == 0 || cursor > length) {
            return null;
        }
        return new UdpPacket(address, host, port, cursor);
    }

    /**
     * Wrap a payload from {@code source:port} as a reply; null when the
     * header plus payload would exceed the cap.
     */
    static byte[] encode(
            InetAddress source, int port, byte[] data, int length, int max) {
        if (source == null || data == null || length < 0) {
            return null;
        }
        byte[] raw = source.getAddress();
        if (raw.length != 4 && raw.length != 16) {
            return null;
        }
        int total = 4 + raw.length + 2 + length;
        if (total > max) {
            return null;
        }
        byte[] packet = new byte[total];
        packet[0] = 0;
        packet[1] = 0;
        packet[2] = 0;
        packet[3] = (byte) (raw.length == 4 ? Handshake.ADDRESS_IPV4 : Handshake.ADDRESS_IPV6);
        System.arraycopy(raw, 0, packet, 4, raw.length);
        int cursor = 4 + raw.length;
        packet[cursor++] = (byte) ((port >> 8) & 0xFF);
        packet[cursor++] = (byte) (port & 0xFF);
        System.arraycopy(data, 0, packet, cursor, length);
        return packet;
    }
}
