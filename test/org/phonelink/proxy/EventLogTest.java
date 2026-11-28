package org.phonelink.proxy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the event log ring and its per-proxy views.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class EventLogTest {

    private EventLogTest() {
    }

    static void cases() {
        run("the event log keeps the newest entries and forgets the rest", () -> {
            EventLog.clear();
            for (int i = 0; i < 205; i++) {
                EventLog.add("event " + i);
            }
            List<String> entries = EventLog.newestFirst();
            assertEqual(200, entries.size(), "ring size");
            assertTrue(entries.get(0).endsWith("event 204"), "newest first");
            assertTrue(entries.get(199).endsWith("event 5"), "oldest kept");
            AtomicInteger seen = new AtomicInteger();
            Runnable listener = seen::incrementAndGet;
            EventLog.addListener(listener);
            EventLog.add("one more");
            EventLog.removeListener(listener);
            EventLog.clear();
            assertEqual(1, seen.get(), "the page is told once");
            assertEqual(0, EventLog.newestFirst().size(), "clear empties it");
        });

        run("the log keeps a proxy's events apart from the app's", () -> {
            EventLog.clear();
            EventLog.setActiveTag("hotel");
            EventLog.add("listening on 0.0.0.0:1080 \u00b7 0 clients");
            EventLog.addTo("home", "saved configuration \"home\"");
            EventLog.setActiveTag(null);
            EventLog.add("wireless debugging managed by the app");

            List<String> hotel = EventLog.forProfile("hotel");
            assertEqual(1, hotel.size(), "the running proxy's own event");
            assertTrue(hotel.get(0).endsWith("listening on 0.0.0.0:1080 \u00b7 0 clients"),
                    "with its line");
            assertEqual(1, EventLog.forProfile("home").size(), "a saved proxy's event");
            assertEqual(0, EventLog.forProfile("nothing").size(), "no events for a stranger");
            assertEqual(3, EventLog.newestFirst().size(), "everything at once");
            // The app's own event is not charged to the proxy that ran before it.
            assertTrue(EventLog.forProfile("hotel").get(0).contains("listening"),
                    "the tag is not sticky");
            EventLog.clear();
            assertEqual(0, EventLog.forProfile(null).size(), "clear empties every view");
        });
    }
}
