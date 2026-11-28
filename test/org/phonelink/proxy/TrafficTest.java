package org.phonelink.proxy;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the traffic counters and their history.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class TrafficTest {

    private TrafficTest() {
    }

    static void cases() {
        run("the traffic counters and the window the sparkline draws", () -> {
            Traffic counts = new Traffic();
            counts.add(100, 1000);
            counts.add(50, 500);
            long[] totals = counts.totals();
            assertEqual(150, (int) totals[0], "uploaded");
            assertEqual(1500, (int) totals[1], "downloaded");
            long[] rates = counts.sample();
            assertEqual(150, (int) rates[0], "upload rate");
            assertEqual(1500, (int) rates[1], "download rate");
            // A sample closes the interval that just ended, so the next one is
            // quiet unless more bytes arrived in between.
            counts.add(10, 750);
            counts.sample();
            long[][] history = counts.history();
            assertEqual(2, history[0].length, "two intervals");
            assertEqual(750, (int) history[1][1], "newest download interval");
            assertEqual(10, (int) history[0][1], "newest upload interval");
            assertEqual(1500, (int) history[1][0], "the interval before it");
            counts.reset();
            assertEqual(0, counts.history()[0].length, "reset empties the window");
            assertEqual(0, (int) counts.totals()[1], "reset zeroes the totals");
            // Counters belong to a listener, so a second one starts at zero
            // however busy the first is.
            assertEqual(0, (int) new Traffic().totals()[1], "a new listener starts empty");
            assertEqual("512 B", Traffic.human(512), "bytes");
            assertEqual("1.5 kB", Traffic.human(1500), "kilobytes");
            assertEqual("2.0 MB", Traffic.human(2_000_000), "megabytes");
        });
    }
}
