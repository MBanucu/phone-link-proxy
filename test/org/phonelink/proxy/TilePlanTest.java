package org.phonelink.proxy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for what the Quick Settings tile starts and stops.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class TilePlanTest {

    private TilePlanTest() {
    }

    /** A tile with no cycle: the switch it always was, before the cycle. */
    private static TilePlan tile(List<String> bound, String inUse, List<String> saved,
            TileScope start, TileScope stop, List<ProxyState> states) {
        return TilePlan.of(bound, inUse, saved, start,
                new ArrayList<TileScope>(), stop, states);
    }

    static void cases() {
        run("a tile with no settings at all keeps the behaviour it had", () -> {
            TilePlan plan = tile(new ArrayList<>(), null, new ArrayList<>(),
                    TileScope.TILE, TileScope.TILE,
                    Arrays.asList(ProxyState.listening("anyone", 1081, 1)));
            assertTrue(plan.fallback, "nothing configured");
            assertTrue(plan.up, "the one that runs is the tile's answer");
            assertTrue(plan.listening, "and it listens");
            assertEqual("", String.join(",", plan.start), "nothing to name");
            assertEqual("", String.join(",", plan.stop), "nothing to name");
        });

        run("two proxies on the tile are started together when none is up", () -> {
            TilePlan plan = tile(Arrays.asList("a", "b"), null,
                    Arrays.asList("a", "b"), TileScope.TILE, TileScope.TILE,
                    new ArrayList<>());
            assertTrue(!plan.fallback, "configured");
            assertTrue(!plan.up, "nothing is up");
            assertEqual("a,b", String.join(",", plan.start), "both start");
            assertEqual("", String.join(",", plan.stop), "nothing stops");
        });

        run("a click while one of them listens stops them all", () -> {
            TilePlan plan = tile(Arrays.asList("a", "b"), null,
                    Arrays.asList("a", "b"), TileScope.TILE, TileScope.TILE,
                    Arrays.asList(ProxyState.listening("a", 1081, 1)));
            assertTrue(plan.up, "one is up");
            assertTrue(plan.listening, "one listens");
            assertEqual("a,b", String.join(",", plan.stop), "both stop");
            assertEqual("", String.join(",", plan.start), "nothing starts");
        });

        run("a paused proxy on the tile is up, so a click turns the set off", () -> {
            TilePlan plan = tile(Arrays.asList("a", "b"), null,
                    Arrays.asList("a", "b"), TileScope.TILE, TileScope.TILE,
                    Arrays.asList(ProxyState.paused("a", "on another network")));
            assertTrue(plan.up, "one is up");
            assertTrue(!plan.listening, "none listens");
            assertEqual("a,b", String.join(",", plan.stop), "both stop");
        });

        run("a proxy that is not on the tile is not the tile's business", () -> {
            TilePlan plan = tile(Arrays.asList("a"), null, Arrays.asList("a", "other"),
                    TileScope.TILE, TileScope.TILE,
                    Arrays.asList(ProxyState.listening("other", 1090, 2)));
            assertTrue(!plan.up, "somebody else's proxy is not this switch");
            assertTrue(!plan.listening, "so the tile is not on");
            assertEqual("a", String.join(",", plan.start), "the one on the tile starts");
            assertEqual("", String.join(",", plan.stop), "and the other is not stopped");
        });

        run("a stop side alone cannot make a tile that never turns on", () -> {
            // Stop everything, bound to nothing: the tile has nothing to start
            // from its settings, so it starts what it always did.
            TilePlan plan = tile(new ArrayList<>(), null, Arrays.asList("a"),
                    TileScope.TILE, TileScope.ALL, new ArrayList<>());
            assertTrue(plan.fallback, "the fallback is what starts something");
            assertTrue(!plan.stopAll, "and the stop side is not an action on its own");
        });

        run("the start side can mean every saved proxy", () -> {
            TilePlan plan = tile(new ArrayList<>(), null, Arrays.asList("a", "b"),
                    TileScope.ALL, TileScope.TILE, new ArrayList<>());
            assertTrue(!plan.fallback, "every saved proxy is a setting");
            assertEqual("a,b", String.join(",", plan.start), "all of them start");
        });

        run("a tile's cycle has one spelling and one order", () -> {
            List<TileScope> cycle = TileCycle.parse("ALL, TILE,ALL,nonsense");
            assertEqual("TILE,ALL", TileCycle.format(cycle), "canonical order, no duplicates");
            assertEqual("tile,all", TileCycle.shown(cycle), "as the dump says it");
            assertTrue(TileCycle.has(cycle, TileScope.TILE), "the tile's set is in it");
            assertTrue(!TileCycle.has(cycle, TileScope.IN_USE), "the proxy in use is not");
            assertEqual("ALL", TileCycle.format(TileCycle.with(cycle, TileScope.TILE, false)),
                    "turning one off");
            assertEqual("TILE,IN_USE,ALL",
                    TileCycle.format(TileCycle.with(cycle, TileScope.IN_USE, true)),
                    "turning one on lands in the order, not at the end");
            assertEqual("", TileCycle.format(TileCycle.parse(null)), "nothing stored");
            assertEqual("none", TileCycle.shown(TileCycle.parse("")), "the dump's word for it");
        });

        run("a cycle's next press brings up only what is missing", () -> {
            // The tile's proxy is up; the cycle's set (everything saved) is not,
            // so the click adds the two that are not listening — and nothing
            // else, because starting a listener that is up replaces it on its
            // port and drops its clients.
            TilePlan plan = TilePlan.of(Arrays.asList("a"), null,
                    Arrays.asList("a", "b", "c"), TileScope.TILE,
                    Arrays.asList(TileScope.ALL), TileScope.ALL,
                    Arrays.asList(ProxyState.listening("a", 1081, 1)));
            assertTrue(!plan.fallback, "the tile is configured");
            assertTrue(plan.up, "something of it is up");
            assertTrue(plan.listening, "and it listens");
            assertEqual("b,c", String.join(",", plan.start), "only the missing ones start");
            assertEqual("", String.join(",", plan.stop), "nothing stops");
            assertTrue(plan.cycleUp == null, "no set of the cycle is complete yet");
        });

        run("a cycle stops once every set it lists is up", () -> {
            TilePlan plan = TilePlan.of(Arrays.asList("a"), null,
                    Arrays.asList("a", "b", "c"), TileScope.TILE,
                    Arrays.asList(TileScope.TILE, TileScope.ALL), TileScope.ALL,
                    Arrays.asList(ProxyState.listening("a", 1081, 1),
                            ProxyState.listening("b", 1082, 0),
                            ProxyState.listening("c", 1083, 0)));
            assertTrue(plan.stopAll, "the last press is the stop side");
            assertEqual("", String.join(",", plan.start), "nothing starts");
            assertTrue(plan.cycleUp == TileScope.ALL,
                    "and the subtitle names the widest set that is up");
        });

        run("a cycle skips a set that is already up", () -> {
            // The tile's own set is in the cycle and already up, so the next
            // press is the one after it.
            TilePlan plan = TilePlan.of(Arrays.asList("a"), null,
                    Arrays.asList("a", "b"), TileScope.TILE,
                    Arrays.asList(TileScope.TILE, TileScope.ALL), TileScope.ALL,
                    Arrays.asList(ProxyState.listening("a", 1081, 1)));
            assertEqual("b", String.join(",", plan.start), "the set after the one that is up");
            assertTrue(plan.cycleUp == TileScope.TILE, "the completed set is the tile's");
        });

        run("the stop side can mean everything, which is a stop with no name", () -> {
            TilePlan plan = tile(Arrays.asList("a"), null, Arrays.asList("a", "b"),
                    TileScope.TILE, TileScope.ALL,
                    Arrays.asList(ProxyState.listening("a", 1081, 1)));
            assertTrue(plan.stopAll, "one stop for all of them");
            assertEqual("", String.join(",", plan.stop), "no names needed");
        });

        run("the proxy in use is a scope only while a saved proxy holds that name", () -> {
            TilePlan plan = tile(new ArrayList<>(), "gone", Arrays.asList("a"),
                    TileScope.IN_USE, TileScope.TILE, new ArrayList<>());
            assertTrue(plan.fallback, "the mark points at nothing, so the tile falls back");
            TilePlan kept = tile(new ArrayList<>(), "a", Arrays.asList("a"),
                    TileScope.IN_USE, TileScope.TILE, new ArrayList<>());
            assertEqual("a", String.join(",", kept.start), "the one in use starts");
        });

        run("a stored scope no build knows is the narrow one", () -> {
            assertEqual("TILE", TileScope.named("nonsense", TileScope.TILE).name(),
                    "unrecognised");
            assertEqual("TILE", TileScope.named(null, TileScope.TILE).name(), "missing");
            assertEqual("ALL", TileScope.named("ALL", TileScope.TILE).name(), "stored");
        });
    }
}
