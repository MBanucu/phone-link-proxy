package org.phonelink.proxy;

import java.nio.charset.StandardCharsets;

/**
 * The listener's settings, validated.
 *
 * <p>Plain Java so the rules can be tested on the JVM: the service, the adb
 * extras and the activity all end up here, and none of them may disagree about
 * what a usable configuration is.
 */
final class ProxyConfig {

    static final int DEFAULT_PORT = 1080;
    static final String DEFAULT_SSID = "PhoneLink";
    static final int DEFAULT_MAX_CONNECTIONS = 256;
    static final int MIN_MAX_CONNECTIONS = 16;
    static final int MAX_MAX_CONNECTIONS = 1024;

    final int port;
    final String user;
    final String password;
    final String ssid;
    final boolean anyNetwork;
    /** Listen without credentials; a checkbox in the panel, `anonymous=true` over adb. */
    final boolean anonymous;
    /** How many SOCKS connections may be open at once. */
    final int maxConnections;

    private ProxyConfig(
            int port,
            String user,
            String password,
            String ssid,
            boolean anyNetwork,
            boolean anonymous,
            int maxConnections) {
        this.port = port;
        this.user = user;
        this.password = password;
        this.ssid = ssid;
        this.anyNetwork = anyNetwork;
        this.anonymous = anonymous;
        this.maxConnections = maxConnections;
    }

    /** Values as stored, without judgement: for pre-filling the form. */
    static ProxyConfig raw(
            int port,
            String user,
            String password,
            String ssid,
            boolean anyNetwork,
            boolean anonymous,
            int maxConnections) {
        return new ProxyConfig(port, nullToEmpty(user), nullToEmpty(password),
                nullToEmpty(ssid), anyNetwork, anonymous, maxConnections);
    }

    static ProxyConfig defaults() {
        return raw(DEFAULT_PORT, "", "", DEFAULT_SSID, false, false, DEFAULT_MAX_CONNECTIONS);
    }

    /**
     * Validate and normalise, or explain why the settings cannot be used.
     *
     * @throws IllegalArgumentException with a message fit for the notification,
     *     the log and the UI
     */
    static ProxyConfig of(
            int port,
            String user,
            String password,
            String ssid,
            boolean anyNetwork,
            boolean anonymous,
            int maxConnections) {
        String givenUser = nullToEmpty(user);
        String givenPassword = nullToEmpty(password);
        String givenSsid = nullToEmpty(ssid);

        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("port must be 1024..65535, got " + port);
        }
        if (maxConnections < MIN_MAX_CONNECTIONS || maxConnections > MAX_MAX_CONNECTIONS) {
            throw new IllegalArgumentException("max_connections must be "
                    + MIN_MAX_CONNECTIONS + ".." + MAX_MAX_CONNECTIONS
                    + ", got " + maxConnections);
        }
        if (anonymous) {
            // The listener treats empty credentials as "no authentication", so
            // an anonymous start must not leave stale ones behind.
            givenUser = "";
            givenPassword = "";
        } else {
            if (givenUser.isEmpty() || givenPassword.isEmpty()) {
                throw new IllegalArgumentException(
                        "no credentials: set a user and password, or anonymous=true");
            }
            if (bytes(givenUser) > 255 || bytes(givenPassword) > 255) {
                throw new IllegalArgumentException("user and password must be at most 255 bytes");
            }
        }
        if (!anyNetwork && givenSsid.isEmpty()) {
            throw new IllegalArgumentException(
                    "no SSID to match: set an SSID, or any_network=true");
        }
        return new ProxyConfig(port, givenUser, givenPassword, givenSsid, anyNetwork, anonymous,
                maxConnections);
    }

    /**
     * One line for the panel: what this configuration actually says, without
     * the password. Used both for the configuration in use and for a saved one
     * that is only being looked at, which is why it says "user" rather than
     * repeating the form's field labels.
     */
    String summary() {
        String network = anyNetwork
                ? "any network"
                : "SSID " + (ssid.isEmpty() ? "—" : ssid);
        String auth = anonymous
                ? "no authentication"
                : "user " + (user.isEmpty() ? "—" : user);
        return "port " + port + " · " + network + " · " + auth;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
