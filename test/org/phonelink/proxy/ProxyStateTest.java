package org.phonelink.proxy;

import java.util.ArrayList;
import java.util.Arrays;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the state text the panel and the notification show.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class ProxyStateTest {

    private ProxyStateTest() {
    }

    static void cases() {
        run("the listening text counts clients", () -> {
            assertEqual("listening on 0.0.0.0:1080 \u00b7 0 clients",
                    ProxyState.listening(1080, 0).describe(), "no clients");
            assertEqual("listening on 0.0.0.0:1080 \u00b7 1 client",
                    ProxyState.listening(1080, 1).describe(), "one client");
            assertEqual("listening on 0.0.0.0:1080 \u00b7 3 clients",
                    ProxyState.listening(1080, 3).describe(), "three clients");
        });

        run("the paused and stopped text explain themselves", () -> {
            assertEqual("not listening: on \"FRITZ\", not \"PhoneLink\"",
                    ProxyState.paused("on \"FRITZ\", not \"PhoneLink\"").describe(), "paused");
            assertEqual("not listening: stopped", ProxyState.stopped().describe(), "stopped");
            assertEqual("not listening: waiting for an allowed network",
                    ProxyState.waiting().describe(), "waiting");
        });

        run("state text names the proxy it is about", () -> {
            // The line under a proxy is about the proxy on the page, so it does
            // not repeat its name; the notification, which may hold several,
            // names the one it is talking about.
            assertEqual("listening on 0.0.0.0:1081 \u00b7 2 clients",
                    ProxyState.listening("laptop", 1081, 2).describe(), "named listener");
            assertEqual("not listening: on \"FRITZ\"",
                    ProxyState.paused("laptop", "on \"FRITZ\"").describe(), "named pause");
            assertEqual("\"laptop\" on 1081", ProxyState.listening("laptop", 1081, 0).label(),
                    "label of a listener");
            assertEqual("port 1081", ProxyState.listening(1081, 0).label(),
                    "label of an unnamed one");
            assertEqual("\"b\" paused", ProxyState.paused("b", "on another network").label(),
                    "label of a paused one");
            // One proxy in the notification says everything about itself, and
            // several are counted and named.
            assertEqual("\"a\" listening on 0.0.0.0:1080 \u00b7 0 clients",
                    ProxyState.summary(Arrays.asList(
                            ProxyState.listening("a", 1080, 0))), "one proxy");
            assertEqual("2 proxies: \"a\" on 1080, \"b\" paused",
                    ProxyState.summary(Arrays.asList(
                            ProxyState.listening("a", 1080, 0),
                            ProxyState.paused("b", "on another network"))), "two proxies");
            assertEqual("not listening", ProxyState.summary(new ArrayList<>()), "none");
        });

        run("the footer says what a start would do", () -> {
            // Nothing running: the plain stopped line.
            assertEqual("NOTHING", String.valueOf(
                    ProxyState.footer(new ArrayList<>(), 1080)), "nothing running");
            // Another proxy on another port: a start adds this one.
            assertEqual("BESIDE", String.valueOf(ProxyState.footer(Arrays.asList(
                    ProxyState.listening("laptop", 1081, 0)), 1080)), "another port");
            // A listener already holding this port is the one start replaces.
            assertEqual("REPLACES", String.valueOf(ProxyState.footer(Arrays.asList(
                    ProxyState.listening("laptop", 1081, 0),
                    ProxyState.listening("hotel", 1080, 2)), 1080)), "same port");
            // A port the form has not got right says nothing about replacing it.
            assertEqual("BESIDE", String.valueOf(ProxyState.footer(Arrays.asList(
                    ProxyState.listening("laptop", 1081, 0)), 0)), "no port");
        });

        run("running separates a paused service from a stopped one", () -> {
            assertTrue(!ProxyState.stopped().running, "stopped is not running");
            assertTrue(ProxyState.waiting().running, "waiting is running");
            assertTrue(ProxyState.paused("on another network").running, "paused is running");
            assertTrue(ProxyState.listening(1080, 0).running, "listening is running");
            assertTrue(!ProxyState.stopped().listening, "stopped is not listening");
        });
    }
}
