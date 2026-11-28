package org.phonelink.proxy;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the cellular egress retry state.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class EgressStateTest {

    private EgressStateTest() {
    }

    static void cases() {
        run("a service started before cellular waits, then uses the network", () -> {
            EgressState state = new EgressState();
            assertTrue(state.start(), "the first start asks for cellular");
            assertFalse(state.start(), "a second start registers no second callback");
            String waiting = state.describe(null, 0);
            assertTrue(waiting.contains("waiting for cellular"), waiting);
            state.available();
            String network = "rmnet_data0 (cellular, network 1)";
            assertEqual(network, state.describe(network, 0), "the network it found");
        });

        run("onUnavailable asks again with a bounded backoff", () -> {
            EgressState state = new EgressState();
            state.start();
            long now = 10_000;
            for (long delay : new long[] {1_000, 2_000, 5_000, 10_000, 30_000, 30_000}) {
                assertEqual((int) delay, (int) state.unavailable(now), "retry delay");
                String line = state.describe(null, now);
                assertTrue(line.contains("cellular unavailable"), line);
                assertTrue(line.contains("retrying in "), line);
                assertFalse(state.retryReady(now + delay - 1), "retried before its delay");
                now += delay;
                assertTrue(state.retryReady(now), "did not retry at its delay");
            }
        });

        run("onLost drops the network and asks again", () -> {
            EgressState state = new EgressState();
            state.start();
            state.available();
            long now = 500;
            assertEqual(1_000, (int) state.lost(now), "first retry after a loss");
            String line = state.describe(null, now);
            assertTrue(line.contains("cellular lost"), line);
            assertFalse(state.retryReady(now + 999), "retried before the delay");
            assertTrue(state.retryReady(now + 1_000), "did not retry after the delay");
        });

        run("loss of internet on the cached network asks again", () -> {
            EgressState state = new EgressState();
            state.start();
            state.available();
            assertEqual(1_000, (int) state.capabilityLost(0), "first retry after the loss");
            String line = state.describe(null, 0);
            assertTrue(line.contains("cellular lost internet"), line);
            assertTrue(state.retryReady(1_000), "did not retry after the delay");
        });

        run("close stops a pending retry", () -> {
            EgressState state = new EgressState();
            state.start();
            state.unavailable(0);
            state.close();
            assertFalse(state.retryReady(60_000), "a closed state retried");
            assertEqual("none (stopped)", state.describe(null, 60_000), "the dump after close");
            assertFalse(state.start(), "a closed state could ask again");
        });

        run("a retry is scheduled once and a callback is registered once", () -> {
            EgressState state = new EgressState();
            assertTrue(state.start(), "the first start");
            assertFalse(state.start(), "a duplicate start");
            assertEqual(1_000, (int) state.unavailable(0), "first retry");
            assertEqual(-1, (int) state.unavailable(10), "a duplicate unavailable rescheduled");
            assertEqual(-1, (int) state.lost(20), "a loss during the wait rescheduled");
            assertTrue(state.retryReady(1_000), "the retry is due");
            assertFalse(state.retryReady(1_000), "the retry fired twice");
            state.unavailable(2_000);
            state.available();
            assertFalse(state.retryReady(60_000), "an available network left a retry behind");
        });
    }
}
