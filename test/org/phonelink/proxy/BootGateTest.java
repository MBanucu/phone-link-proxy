package org.phonelink.proxy;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the two boot gates.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class BootGateTest {

    private BootGateTest() {
    }

    static void cases() {
        run("the OEM gate is unreadable even when the battery gate is clear", () -> {
            // BootGate has no battery input on purpose: a granted exemption
            // cannot turn the vendor list into a readable fact, so the state
            // file says unreadable and writes no ok for it.
            assertEqual("oem_autostart unreadable", BootGate.stateText(0),
                    "the vendor gate is not reported as clear");
            assertFalse(BootGate.stateText(0).contains("last_boot"),
                    "a clean install has no missed-boot claim");
        });

        run("a missed boot is a dated observation only with auto-start on", () -> {
            long seen = 41;
            assertTrue(BootGate.missedThisBoot(42, seen), "a newer boot was missed");
            assertFalse(BootGate.missedThisBoot(seen, seen), "the receiver saw this boot");
            assertFalse(BootGate.missedThisBoot(42, -1), "a clean install has no marker");
            assertEqual(0, (int) BootGate.observedMissedAt(42, seen, false, 0, 1_000),
                    "auto-start off is not a missed restart");
            assertEqual(1_000, (int) BootGate.observedMissedAt(42, seen, true, 0, 1_000),
                    "the first look dates the observation");
            assertEqual(1_000, (int) BootGate.observedMissedAt(43, seen, true, 1_000, 2_000),
                    "a later look keeps the first date");
            assertEqual(0, (int) BootGate.observedMissedAt(41, seen, true, 1_000, 2_000),
                    "the receiver running clears it");
            // The persisted line is the observation, not the conclusion: the
            // panel and the doctor are where "likely blocked" is earned.
            assertTrue(BootGate.stateText(1_000).contains("last_boot no_broadcast 1000"),
                    "the dated observation is machine-readable");
            assertFalse(BootGate.stateText(1_000).contains("blocked"),
                    "the state file does not persist the suspicion");
        });
    }
}
