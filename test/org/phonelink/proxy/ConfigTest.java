package org.phonelink.proxy;

import java.util.Arrays;
import java.util.List;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for ProxyConfig rules and the Wi-Fi lock policy.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class ConfigTest {

    private ConfigTest() {
    }

    static void cases() {
        run("defaults are port 1080, no credentials and PhoneLink", () -> {
            ProxyConfig config = ProxyConfig.defaults();
            assertEqual(1080, config.port, "default port");
            assertEqual("PhoneLink", config.ssid, "default SSID");
            assertEqual("", config.user, "default user");
            assertTrue(!config.anyNetwork, "any_network is off by default");
            assertTrue(!config.anonymous, "authentication is on by default");
            assertEqual(256, config.maxConnections, "default max_connections");
        });

        run("a connection ceiling outside 16..1024 is rejected", () -> {
            expectRejected("15", () ->
                    ProxyConfig.of(1080, "proxy", "s3cret", "PhoneLink", false, false, 15));
            expectRejected("1025", () ->
                    ProxyConfig.of(1080, "proxy", "s3cret", "PhoneLink", false, false, 1025));
            assertEqual(16, ProxyConfig.of(1080, "proxy", "s3cret", "PhoneLink", false, false,
                    16).maxConnections, "the lower bound");
            assertEqual(1024, ProxyConfig.of(1080, "proxy", "s3cret", "PhoneLink", false, false,
                    1024).maxConnections, "the upper bound");
        });

        run("a non-default connection ceiling passes through", () -> {
            ProxyConfig config = ProxyConfig.of(1080, "proxy", "s3cret", "PhoneLink", false,
                    false, 512);
            assertEqual(512, config.maxConnections, "max_connections");
            assertEqual("port 1080 · SSID PhoneLink · user proxy", config.summary(),
                    "the summary is unchanged");
        });

        run("a port outside 1024..65535 is rejected", () -> {
            expectRejected("port 80", () ->
                    ProxyConfig.of(80, "proxy", "s3cret", "PhoneLink", false, false,
                            ProxyConfig.DEFAULT_MAX_CONNECTIONS));
            expectRejected("port 70000", () ->
                    ProxyConfig.of(70000, "proxy", "s3cret", "PhoneLink", false, false,
                            ProxyConfig.DEFAULT_MAX_CONNECTIONS));
        });

        run("credentials are required unless anonymous", () -> {
            expectRejected("empty user and password", () ->
                    ProxyConfig.of(1080, "", "", "PhoneLink", false, false,
                            ProxyConfig.DEFAULT_MAX_CONNECTIONS));
            expectRejected("a password without a user", () ->
                    ProxyConfig.of(1080, "", "s3cret", "PhoneLink", false, false,
                            ProxyConfig.DEFAULT_MAX_CONNECTIONS));
        });

        run("anonymous clears stale credentials", () -> {
            ProxyConfig config = ProxyConfig.of(1080, "proxy", "s3cret", "PhoneLink", false,
                    true, ProxyConfig.DEFAULT_MAX_CONNECTIONS);
            assertTrue(config.anonymous, "anonymous is kept");
            assertEqual("", config.user, "user cleared");
            assertEqual("", config.password, "password cleared");
        });

        run("a credential longer than 255 bytes is rejected", () -> {
            char[] tooLong = new char[256];
            Arrays.fill(tooLong, 'x');
            expectRejected("a 256-byte password", () ->
                    ProxyConfig.of(1080, "proxy", new String(tooLong), "PhoneLink", false,
                            false, ProxyConfig.DEFAULT_MAX_CONNECTIONS));
        });

        run("an empty SSID is rejected unless any_network is set", () -> {
            expectRejected("an empty SSID", () ->
                    ProxyConfig.of(1080, "proxy", "s3cret", "", false, false,
                            ProxyConfig.DEFAULT_MAX_CONNECTIONS));
            ProxyConfig config = ProxyConfig.of(1080, "proxy", "s3cret", "", true, false,
                    ProxyConfig.DEFAULT_MAX_CONNECTIONS);
            assertTrue(config.anyNetwork, "any_network is kept");
        });

        run("valid settings pass through unchanged", () -> {
            ProxyConfig config = ProxyConfig.of(1081, "proxy", "s3cret", "PhoneLink", false,
                    false, ProxyConfig.DEFAULT_MAX_CONNECTIONS);
            assertEqual(1081, config.port, "port");
            assertEqual("proxy", config.user, "user");
            assertEqual("s3cret", config.password, "password");
            assertEqual("PhoneLink", config.ssid, "SSID");
        });

        run("the summary says what a configuration holds, without the password", () -> {
            ProxyConfig config =
                    ProxyConfig.of(1081, "proxy", "s3cret", "PhoneLink", false, false,
                            ProxyConfig.DEFAULT_MAX_CONNECTIONS);
            assertEqual("port 1081 · SSID PhoneLink · user proxy", config.summary(),
                    "summary");
            ProxyConfig open = ProxyConfig.of(1081, "", "", "", true, true,
                    ProxyConfig.DEFAULT_MAX_CONNECTIONS);
            assertEqual("port 1081 · any network · no authentication", open.summary(),
                    "anonymous summary");
        });

        run("the Wi-Fi lock follows a bound listener that needs Wi-Fi", () -> {
            assertTrue(WifiLockPolicy.hold(true,
                    List.of(new WifiLockPolicy.Listener(true, false))),
                    "a listening Wi-Fi proxy holds it");
            assertFalse(WifiLockPolicy.hold(false,
                    List.of(new WifiLockPolicy.Listener(true, false))),
                    "the switch off releases it");
            assertFalse(WifiLockPolicy.hold(true,
                    List.of(new WifiLockPolicy.Listener(false, false))),
                    "a paused proxy does not hold it");
            assertFalse(WifiLockPolicy.hold(true,
                    List.of(new WifiLockPolicy.Listener(true, true))),
                    "an any_network proxy does not need it");
            assertTrue(WifiLockPolicy.hold(true, List.of(
                    new WifiLockPolicy.Listener(true, true),
                    new WifiLockPolicy.Listener(true, false))),
                    "one Wi-Fi listener is enough");
            assertFalse(WifiLockPolicy.hold(true, List.of()),
                    "no listeners, no lock");
        });
    }
}
