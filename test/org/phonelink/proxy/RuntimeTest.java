package org.phonelink.proxy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the runtime registry of running proxies.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class RuntimeTest {

    private RuntimeTest() {
    }

    static void cases() {
        run("the runtime forwards state to listeners until they leave", () -> {
            ProxyRuntime.publish(new ArrayList<>());
            List<List<ProxyState>> seen = new ArrayList<>();
            ProxyRuntime.Listener listener = seen::add;
            ProxyRuntime.addListener(listener);
            ProxyRuntime.publish(Arrays.asList(
                    new ProxyRuntime.Proxy(ProxyState.listening("one", 1080, 0), new Traffic())));
            assertEqual(1, seen.size(), "updates seen");
            assertTrue(seen.get(0).get(0).listening, "the state is forwarded");
            ProxyRuntime.removeListener(listener);
            ProxyRuntime.publish(new ArrayList<>());
            assertEqual(1, seen.size(), "updates after removal");
            assertTrue(!ProxyRuntime.state().listening, "the runtime keeps the latest state");
        });

        run("the runtime keeps every running proxy, each with its own counters", () -> {
            Traffic first = new Traffic();
            Traffic second = new Traffic();
            first.add(0, 1000);
            second.add(0, 2000);
            ProxyRuntime.publish(Arrays.asList(
                    new ProxyRuntime.Proxy(ProxyState.listening("one", 1080, 1), first),
                    new ProxyRuntime.Proxy(ProxyState.paused("two", "on another network"), second)));
            assertEqual(2, ProxyRuntime.running().size(), "two running");
            assertEqual(2, ProxyRuntime.states().size(), "two states");
            assertTrue(ProxyRuntime.forName("one") != null, "the first is found by name");
            assertTrue(ProxyRuntime.forName("two").state.running, "a paused one is still running");
            assertTrue(ProxyRuntime.forName("three") == null, "a stranger is not running");
            assertEqual(1000, (int) ProxyRuntime.forName("one").traffic.totals()[1],
                    "the first's bytes");
            assertEqual(2000, (int) ProxyRuntime.forName("two").traffic.totals()[1],
                    "the second's bytes");
            // All of them as one state, with the clients of everything put
            // together: what a caller with no opinion about which proxy means
            // gets. The tile has an opinion, so it uses TilePlan below.
            ProxyState all = ProxyRuntime.state();
            assertTrue(all.running, "something runs");
            assertTrue(all.listening, "something listens");
            assertEqual(1080, all.port, "the port that listens");
            assertEqual(1, all.clients, "the clients of both");
            ProxyRuntime.publish(new ArrayList<>());
            assertTrue(ProxyRuntime.running().isEmpty(), "publishing nothing empties it");
        });
    }
}
