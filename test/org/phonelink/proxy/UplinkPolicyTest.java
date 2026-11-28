package org.phonelink.proxy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the Wi-Fi/uplink gate.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class UplinkPolicyTest {

    private UplinkPolicyTest() {
    }

    static void cases() {
        run("a redacted SSID before the first unlock keeps the listener up", () -> {
            UplinkPolicy.Verdict verdict = UplinkPolicy.decide("PhoneLink", false, true,
                    Arrays.asList(UplinkPolicy.Link.onWifi(null)));
            assertTrue(verdict.allowed, "redacted before unlock: " + verdict.detail);
            assertTrue(verdict.detail.contains("before unlock"),
                    "the reason names the unlock: " + verdict.detail);
        });

        run("the same redacted network is a pause once the user unlocks", () -> {
            List<UplinkPolicy.Link> links =
                    Arrays.asList(UplinkPolicy.Link.onWifi(null));
            assertTrue(UplinkPolicy.decide("PhoneLink", false, true, links).allowed,
                    "allowed while locked");
            UplinkPolicy.Verdict verdict =
                    UplinkPolicy.decide("PhoneLink", false, false, links);
            assertFalse(verdict.allowed, "redacted after unlock was allowed");
            assertTrue(verdict.detail.contains("cannot read"),
                    "the reason says the SSID is unreadable: " + verdict.detail);
        });

        run("one Wi-Fi link on the configured SSID is allowed", () -> {
            UplinkPolicy.Verdict verdict = UplinkPolicy.decide("PhoneLink", false, false,
                    Arrays.asList(UplinkPolicy.Link.onWifi("PhoneLink")));
            assertTrue(verdict.allowed, "exact match was paused: " + verdict.detail);
            assertEqual("on \"PhoneLink\"", verdict.detail, "reason");
        });

        run("a readable SSID that differs is a mismatch", () -> {
            UplinkPolicy.Verdict verdict = UplinkPolicy.decide("PhoneLink", false, false,
                    Arrays.asList(UplinkPolicy.Link.onWifi("FRITZ")));
            assertFalse(verdict.allowed, "a wrong SSID was allowed");
            assertEqual("on \"FRITZ\", not \"PhoneLink\"", verdict.detail, "reason");
        });

        run("a redacted SSID beside a wrong one is unknown, not a mismatch, while locked", () -> {
            UplinkPolicy.Verdict verdict = UplinkPolicy.decide("PhoneLink", false, true,
                    Arrays.asList(UplinkPolicy.Link.onWifi(null),
                            UplinkPolicy.Link.onWifi("FRITZ")));
            assertTrue(verdict.allowed,
                    "the redacted link should not read as a mismatch: " + verdict.detail);
        });

        run("with no network at all the gate stays closed", () -> {
            UplinkPolicy.Verdict verdict = UplinkPolicy.decide("PhoneLink", false, true,
                    new ArrayList<>());
            assertFalse(verdict.allowed, "no network was allowed");
            assertEqual("no default network", verdict.detail, "reason");
        });

        run("a cellular-only phone is not on Wi-Fi, so the gate stays closed", () -> {
            UplinkPolicy.Verdict verdict = UplinkPolicy.decide("PhoneLink", false, false,
                    Arrays.asList(UplinkPolicy.Link.other(true)));
            assertFalse(verdict.allowed, "cellular was allowed as Wi-Fi");
            assertEqual("no Wi-Fi network is connected", verdict.detail, "reason");
        });

        run("two Wi-Fi links on the configured SSID are ambiguous and pause", () -> {
            UplinkPolicy.Verdict verdict = UplinkPolicy.decide("PhoneLink", false, false,
                    Arrays.asList(UplinkPolicy.Link.onWifi("PhoneLink"),
                            UplinkPolicy.Link.onWifi("PhoneLink")));
            assertFalse(verdict.allowed, "two matching networks were allowed");
            assertTrue(verdict.detail.contains("2 Wi-Fi networks say \"PhoneLink\""),
                    "the reason names the ambiguity: " + verdict.detail);
        });

        run("a match beside an unreadable SSID is ambiguous and pauses", () -> {
            UplinkPolicy.Verdict verdict = UplinkPolicy.decide("PhoneLink", false, false,
                    Arrays.asList(UplinkPolicy.Link.onWifi("PhoneLink"),
                            UplinkPolicy.Link.onWifi(null)));
            assertFalse(verdict.allowed, "an unreadable second SSID was ignored");
            assertTrue(verdict.detail.contains("another Wi-Fi SSID is unreadable"),
                    "the reason names the ambiguity: " + verdict.detail);
        });

        run("any_network still needs a network to open on", () -> {
            assertFalse(UplinkPolicy.decide("PhoneLink", true, true, new ArrayList<>()).allowed,
                    "any_network with no network was allowed");
            assertTrue(UplinkPolicy.decide("PhoneLink", true, false,
                    Arrays.asList(UplinkPolicy.Link.onWifi("FRITZ"))).allowed,
                    "any_network refused a connected network");
        });
    }
}
