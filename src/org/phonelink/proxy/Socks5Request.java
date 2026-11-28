package org.phonelink.proxy;

/**
 * One parsed SOCKS5 request (RFC 1928 §4): the command it asks for and the
 * destination it names.
 *
 * <p>A plain value, Android-free, so the request parsing and the UDP
 * association can talk about a destination without a socket.
 */
final class Socks5Request {
    final int command;
    final int addressType;
    final String host;
    final int port;

    Socks5Request(int command, int addressType, String host, int port) {
        this.command = command;
        this.addressType = addressType;
        this.host = host;
        this.port = port;
    }
}
