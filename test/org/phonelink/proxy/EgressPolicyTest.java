package org.phonelink.proxy;

import java.util.Arrays;
import java.util.List;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the cellular egress selection and guard.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class EgressPolicyTest {

    private EgressPolicyTest() {
    }

    static void cases() {
        run("with Wi-Fi as the default, the egress selects cellular", () -> {
            // The hairpin: the laptop's AP is validated and the default route,
            // and mobile data is available as a non-default link.
            List<EgressPolicy.Link> links = Arrays.asList(
                    EgressPolicy.Link.wifi("100", "wlan0", true),
                    EgressPolicy.Link.cellular("101", "rmnet_data0"));
            EgressPolicy.Route route = EgressPolicy.select(links);
            assertTrue(route.allowed(), route.reason);
            assertEqual("101", route.link.network, "the cellular network");
            assertTrue(route.link.cellular, "the selected link is cellular");
            assertTrue(!route.link.isDefault, "the Wi-Fi default was not selected");
        });

        run("the normal AP case keeps selecting cellular when the AP is the default", () -> {
            // The same shape as above, from the other direction: no cellular
            // first in the list, and the AP still validated and default.
            List<EgressPolicy.Link> links = Arrays.asList(
                    EgressPolicy.Link.cellular("101", "rmnet_data0"),
                    EgressPolicy.Link.wifi("100", "wlan0", true));
            EgressPolicy.Route route = EgressPolicy.select(links);
            assertTrue(route.allowed(), route.reason);
            assertEqual("101", route.link.network, "the cellular network");
        });

        run("with no cellular link the selection fails closed and names the loop", () -> {
            EgressPolicy.Route route = EgressPolicy.select(Arrays.asList(
                    EgressPolicy.Link.wifi("100", "wlan0", true)));
            assertTrue(!route.allowed(), "a Wi-Fi default was selected");
            assertTrue(route.reason.contains("back into the laptop"), route.reason);
            assertTrue(route.reason.contains("Wi-Fi/AP"), route.reason);
        });

        run("a resolved Wi-Fi/AP egress is refused, not opened", () -> {
            String refusal = EgressPolicy.refusal(
                    EgressPolicy.Link.wifi("100", "wlan0", true));
            assertTrue(refusal != null, "the Wi-Fi egress was allowed");
            assertTrue(refusal.contains("not cellular"), refusal);
            assertTrue(refusal.contains("back into the laptop"), refusal);
            assertTrue(EgressPolicy.refusal(
                    EgressPolicy.Link.cellular("101", "rmnet_data0")) == null,
                    "a cellular egress was refused");
        });

        run("the dumpsys egress line names the cellular network and interface", () -> {
            String line = EgressPolicy.describe(
                    EgressPolicy.Link.cellular("101", "rmnet_data0"));
            assertEqual("cellular network 101 on rmnet_data0", line, "the egress line");
            assertTrue(EgressPolicy.describe(
                    EgressPolicy.Link.wifi("100", "wlan0", true))
                    .contains("refused"), "a non-cellular link was not marked refused");
        });
    }
}
