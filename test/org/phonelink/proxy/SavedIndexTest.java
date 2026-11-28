package org.phonelink.proxy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the saved-proxy index {@code state.txt} carries.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class SavedIndexTest {

    private SavedIndexTest() {
    }

    static void cases() {
        run("no saved proxies still writes an index", () -> {
            assertEqual("saved_index_version 1\n"
                            + "saved_index_count 0\n"
                            + "saved_index none\n",
                    SavedIndex.format(Collections.emptyList()), "the empty index");
        });

        run("one saved proxy is one unambiguous line", () -> {
            ProxyConfig config =
                    ProxyConfig.raw(1081, "proxy", "s3cret", "PhoneLink", false, false, 256);
            String text = SavedIndex.format(
                    List.of(new SavedIndex.Entry("laptop", config, true)));
            assertEqual("saved_index_version 1\n"
                            + "saved_index_count 1\n"
                            + "saved_index bGFwdG9w 1081 cHJveHk= 0 1 UGhvbmVMaW5r 0\n",
                    text, "the one-entry index");
        });

        run("several saved proxies keep their order and are counted", () -> {
            List<SavedIndex.Entry> entries = new ArrayList<>();
            entries.add(new SavedIndex.Entry("alpha", config(1080, "a", "first"), false));
            entries.add(new SavedIndex.Entry("beta", config(1081, "b", "second"), true));
            entries.add(new SavedIndex.Entry("gamma", config(1082, "c", "third"), false));
            String text = SavedIndex.format(entries);
            String[] lines = text.split("\n");
            assertEqual(5, lines.length, "version, count and three proxies");
            assertEqual("saved_index_count 3", lines[1], "the count");
            assertTrue(lines[2].startsWith("saved_index "), "alpha first");
            assertTrue(lines[3].startsWith("saved_index "), "beta second");
            assertTrue(lines[4].startsWith("saved_index "), "gamma third");
            assertEqual("alpha", decode(field(lines[2], 1)), "first name");
            assertEqual("beta", decode(field(lines[3], 1)), "second name");
            assertEqual("gamma", decode(field(lines[4], 1)), "third name");
            assertTrue(!text.contains("saved_index none"), "a list is not none");
        });

        run("spaces, quotes and non-ASCII survive as Base64URL fields", () -> {
            String name = "h\u00f4tel \"A\" / \u6771\u4eac";
            String user = "user name \"one\"";
            String ssid = "WLAN \"G\u00e4ste\" \ud83d\ude80";
            ProxyConfig config = ProxyConfig.raw(1081, user, "s3cret", ssid, false, false, 256);
            String line = SavedIndex.line(new SavedIndex.Entry(name, config, false));
            assertEqual(8, line.split(" ").length, "eight space-separated fields");
            assertEqual("saved_index", field(line, 0), "the marker");
            assertEqual(name, decode(field(line, 1)), "the name");
            assertEqual("1081", field(line, 2), "the port");
            assertEqual(user, decode(field(line, 3)), "the user");
            assertEqual("0", field(line, 4), "anonymous");
            assertEqual("0", field(line, 5), "auto_start");
            assertEqual(ssid, decode(field(line, 6)), "the SSID");
            assertEqual("0", field(line, 7), "any_network");
            assertTrue(!line.contains(" " + name + " "), "the name is not written plainly");
            assertTrue(!line.contains(ssid), "the SSID is not written plainly");
        });

        run("an anonymous proxy publishes no user", () -> {
            // raw, not of: a hand-edited store may still hold a stale user.
            ProxyConfig config =
                    ProxyConfig.raw(1080, "stale", "s3cret", "", true, true, 256);
            String line = SavedIndex.line(new SavedIndex.Entry("open", config, false));
            assertEqual(SavedIndex.EMPTY, field(line, 3), "the empty user sentinel");
            assertEqual("", decode(field(line, 3)), "an anonymous user is empty");
            assertEqual("1", field(line, 4), "anonymous");
            assertEqual(SavedIndex.EMPTY, field(line, 6), "the empty SSID sentinel");
            assertEqual("1", field(line, 7), "any_network");
        });

        run("the index never carries the password", () -> {
            String secret = "s3cret";
            ProxyConfig config =
                    ProxyConfig.raw(1081, "proxy", secret, "PhoneLink", false, false, 256);
            String text = SavedIndex.format(
                    List.of(new SavedIndex.Entry("laptop", config, true)));
            assertFalse(text.contains(secret), "the password in plain text");
            assertFalse(text.contains(SavedIndex.base64(secret)), "the password in Base64");
            assertFalse(text.contains("password"), "a password field");
        });
    }

    private static ProxyConfig config(int port, String user, String password) {
        return ProxyConfig.raw(port, user, password, "PhoneLink", false, false, 256);
    }

    /** Field {@code at} of a space-separated index line. */
    private static String field(String line, int at) {
        return line.split(" ")[at];
    }

    private static String decode(String value) {
        if (SavedIndex.EMPTY.equals(value)) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
