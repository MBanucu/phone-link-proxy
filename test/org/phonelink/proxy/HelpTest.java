package org.phonelink.proxy;

import java.util.List;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the help text an agent reads.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class HelpTest {

    private HelpTest() {
    }

    static void cases() {
        run("the help text an agent reads names the whole adb contract", () -> {
            String text = Help.text("org.phonelink.proxy");
            for (String extra : List.of("--ei port", "--ei max_connections", "--es user",
                    "--es password", "--es ssid", "--ez anonymous", "--ez any_network",
                    "--ez auto_start", "--ez wifi_lock", "--ez adb_wifi",
                    "--es profile")) {
                assertTrue(text.contains(extra), "help does not mention " + extra);
            }
            assertTrue(text.contains("opt-in experiment"),
                    "the help text sells the Wi-Fi lock as a fix");
            assertTrue(text.contains("UDP ASSOCIATE"),
                    "the help text does not say UDP is supported");
            assertTrue(text.contains("BIND is refused"),
                    "the help text does not say BIND is still refused");
            assertTrue(text.contains("org.phonelink.proxy.action.START"), "no start action");
            assertTrue(text.contains("org.phonelink.proxy.action.STOP"), "no stop action");
            assertTrue(text.contains("org.phonelink.proxy.action.SAVE_PROFILE"),
                    "no save-profile action");
            assertTrue(text.contains("org.phonelink.proxy.action.DELETE_PROFILE"),
                    "no delete-profile action");
            // The panel and the service print this same text, so the command
            // that reaches it from adb has to be in it.
            assertTrue(text.contains(Help.adbDump("org.phonelink.proxy")),
                    "help does not say how to print it");
            // The gate the app cannot read has to be named as unreadable, with
            // the permission that protects it and the two drivers that can
            // still reach the screen.
            assertTrue(text.contains("com.freeme.freemanager.permission.ACCESS_INTERFACE"),
                    "help does not name the vendor permission");
            assertTrue(text.contains("wireless-adb-keeper/autostart-allow.sh"),
                    "help does not name the adb driver");
            assertTrue(text.contains("phone-link-tools/autotoggle"),
                    "help does not name the accessibility driver");
            assertTrue(text.contains("likely blocked this boot"),
                    "help does not describe the dated suspicion");
            assertTrue(text.contains("last_boot no_broadcast <epoch>"),
                    "help does not name the observation it persists");
            assertTrue(text.contains("never as clear"),
                    "help does not say the vendor gate is never clear");
        });

        run("the help text names the package the app is installed as", () -> {
            String text = Help.text("org.phonelink.proxy.test");
            // The id is what adb is given, the class keeps its package, so both
            // halves have to be there for a command that would actually work.
            assertTrue(text.contains("org.phonelink.proxy.test/org.phonelink.proxy.ProxyService"),
                    "a test build points at the wrong service");
            assertTrue(text.contains("org.phonelink.proxy.test/org.phonelink.proxy.MainActivity"),
                    "a test build points at the wrong activity");
            assertFalse(text.contains("org.phonelink.proxy/.ProxyService"),
                    "a test build still prints the stable component");
            // The actions are literals in the code, so they do not move with the id.
            assertTrue(text.contains("org.phonelink.proxy.action.START"), "no start action");
        });
    }
}
