package org.phonelink.proxy;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the profile document and its export.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class ProfileDocumentTest {

    private ProfileDocumentTest() {
    }

    static void cases() {
        run("an export carries every proxy, passwords and switches", () -> {
            ProfileDocument document = new ProfileDocument();
            ProfileDocument.Entry laptop = new ProfileDocument.Entry();
            laptop.name = "laptop";
            laptop.config = ProxyConfig.raw(1081, "proxy", "s3cret", "PhoneLink", true, false,
                    512);
            laptop.autoStart = true;
            laptop.adbWireless = false;
            laptop.wifiLock = true;
            laptop.tile = true;
            document.saved.add(laptop);
            ProfileDocument.Entry hotel = new ProfileDocument.Entry();
            hotel.name = "hotel \"the\" \\ one";
            hotel.config = ProxyConfig.raw(1082, "user", "pass\tword", "Hotel", false, true,
                    ProxyConfig.DEFAULT_MAX_CONNECTIONS);
            document.saved.add(hotel);
            document.inUse = "laptop";
            document.selected = "hotel";
            document.active = laptop;

            ProfileDocument read = ProfileDocument.parse(document.write());
            assertEqual(2, read.saved.size(), "saved proxies");
            ProfileDocument.Entry first = read.saved.get(0);
            assertEqual("laptop", first.name, "name");
            assertEqual(1081, first.config.port, "port");
            assertEqual(512, first.config.maxConnections, "max connections");
            assertEqual("proxy", first.config.user, "user");
            assertEqual("s3cret", first.config.password, "password");
            assertEqual("PhoneLink", first.config.ssid, "ssid");
            assertTrue(first.config.anyNetwork, "any network");
            assertTrue(first.autoStart, "auto start");
            assertFalse(first.adbWireless, "adb wifi");
            assertTrue(first.wifiLock, "wifi lock");
            assertTrue(first.tile, "tile");
            ProfileDocument.Entry second = read.saved.get(1);
            assertEqual("hotel \"the\" \\ one", second.name, "an escaped name");
            assertEqual("pass\tword", second.config.password, "an escaped password");
            assertTrue(second.config.anonymous, "anonymous");
            assertEqual("laptop", read.inUse, "in use");
            assertEqual("hotel", read.selected, "selected");
            assertEqual(1081, read.active.config.port, "the configuration in use");
        });

        run("an export round-trips the connection ceiling, and an old file gets 256", () -> {
            ProfileDocument document = new ProfileDocument();
            ProfileDocument.Entry laptop = new ProfileDocument.Entry();
            laptop.name = "laptop";
            laptop.config = ProxyConfig.raw(1081, "proxy", "s3cret", "PhoneLink", false, false,
                    512);
            document.saved.add(laptop);
            document.active = laptop;

            ProfileDocument read = ProfileDocument.parse(document.write());
            assertEqual(512, read.saved.get(0).config.maxConnections,
                    "the exported max_connections");
            assertEqual(512, read.active.config.maxConnections,
                    "the configuration in use keeps it too");

            ProfileDocument old = ProfileDocument.parse(
                    "{\"saved\": [{\"name\": \"old\", \"port\": 1081}]}");
            assertEqual(ProxyConfig.DEFAULT_MAX_CONNECTIONS,
                    old.saved.get(0).config.maxConnections,
                    "a document without the field imports as 256");
        });

        run("a document hands over its proxies by name, the last one winning", () -> {
            ProfileDocument document = ProfileDocument.parse(
                    "{\"saved\": ["
                            + "{\"name\": \"a\", \"port\": 1081},"
                            + "{\"name\": \"b\", \"port\": 1082},"
                            + "{\"name\": \"a\", \"port\": 1083}]}");
            assertEqual(2, document.byName().size(), "names");
            assertEqual("a", document.byName().get(0).name, "first name");
            assertEqual(1083, document.byName().get(0).config.port, "the later one wins");
            assertEqual("b", document.byName().get(1).name, "second name");
        });

        run("a hand-written document keeps the defaults it leaves out", () -> {
            ProfileDocument document = ProfileDocument.parse(
                    "{\"saved\": [{\"name\": \"bare\"}]}");
            ProfileDocument.Entry entry = document.saved.get(0);
            assertEqual(ProxyConfig.DEFAULT_PORT, entry.config.port, "port");
            assertEqual(ProxyConfig.DEFAULT_MAX_CONNECTIONS, entry.config.maxConnections,
                    "an old document keeps the 256 default");
            assertEqual(ProxyConfig.DEFAULT_SSID, entry.config.ssid, "ssid");
            assertTrue(entry.adbWireless, "the wireless-debugging switch defaults on");
            assertFalse(entry.autoStart, "auto start");
            assertFalse(entry.wifiLock, "the Wi-Fi lock defaults off");
            assertTrue(document.active == null, "no configuration in use");
        });

        run("a nameless entry is skipped, not imported as an empty name", () -> {
            ProfileDocument document = ProfileDocument.parse(
                    "{\"saved\": [{\"port\": 1081}, {\"name\": \"  \"}, {\"name\": \"kept\"}]}");
            assertEqual(1, document.saved.size(), "entries");
            assertEqual("kept", document.saved.get(0).name, "name");
        });

        run("a document from a newer app is refused", () -> {
            assertRejected("newer", () -> ProfileDocument.parse("{\"version\": 2}"));
            assertRejected("not json", () -> ProfileDocument.parse("every saved proxy"));
            assertRejected("an array", () -> ProfileDocument.parse("[\"a\"]"));
            assertRejected("empty", () -> ProfileDocument.parse(""));
        });
    }
}
