package org.phonelink.proxy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runs every suite, or the ones a {@code --only} pattern names.
 *
 * <p>{@code bash tests/proxy-app.test.sh} runs all of them;
 * {@code bash tests/proxy-app.test.sh --only udp} runs one area. A pattern is
 * a suite id, an alias, or a glob such as {@code socks5-*}, and patterns can
 * be comma-separated.
 */
public final class AllTests {

    private AllTests() {
    }

    /** One named area of the suite. */
    private static final class Suite {
        final String id;
        final String[] aliases;
        final Runnable body;

        Suite(String id, String[] aliases, Runnable body) {
            this.id = id;
            this.aliases = aliases;
            this.body = body;
        }

        boolean matches(String pattern) {
            if (glob(pattern, id)) {
                return true;
            }
            for (String alias : aliases) {
                if (glob(pattern, alias)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static List<Suite> suites() {
        List<Suite> suites = new ArrayList<>();
        suites.add(new Suite("socks5-tcp", new String[] {"tcp"}, Socks5TcpTest::cases));
        suites.add(new Suite("socks5-lifecycle", new String[] {"lifecycle"},
                Socks5LifecycleTest::cases));
        suites.add(new Suite("socks5-udp", new String[] {"udp"}, Socks5UdpTest::cases));
        suites.add(new Suite("uplink-policy", new String[] {"uplink"}, UplinkPolicyTest::cases));
        suites.add(new Suite("config", new String[] {}, ConfigTest::cases));
        suites.add(new Suite("actions", new String[] {"contract"}, ActionsTest::cases));
        suites.add(new Suite("help", new String[] {}, HelpTest::cases));
        suites.add(new Suite("traffic", new String[] {}, TrafficTest::cases));
        suites.add(new Suite("event-log", new String[] {"log"}, EventLogTest::cases));
        suites.add(new Suite("boot-gate", new String[] {"boot"}, BootGateTest::cases));
        suites.add(new Suite("proxy-state", new String[] {"state"}, ProxyStateTest::cases));
        suites.add(new Suite("egress-policy", new String[] {"egress"},
                EgressPolicyTest::cases));
        suites.add(new Suite("egress-state", new String[] {"egress-retry"},
                EgressStateTest::cases));
        suites.add(new Suite("runtime", new String[] {}, RuntimeTest::cases));
        suites.add(new Suite("tile-plan", new String[] {"tile"}, TilePlanTest::cases));
        suites.add(new Suite("profile-document", new String[] {"document"},
                ProfileDocumentTest::cases));
        suites.add(new Suite("profile-transfer", new String[] {"transfer"},
                ProfileTransferTest::cases));
        suites.add(new Suite("saved-index", new String[] {"index"},
                SavedIndexTest::cases));
        return suites;
    }

    public static void main(String[] args) {
        String only = null;
        for (int index = 0; index < args.length; index++) {
            if (!"--only".equals(args[index]) || index + 1 >= args.length) {
                usage();
            }
            only = args[++index];
        }

        List<Suite> suites = suites();
        List<Suite> chosen = only == null ? suites : select(suites, only);
        if (chosen.isEmpty()) {
            System.err.println("no suite matches --only " + only);
            System.err.println("suites: " + ids(suites));
            System.exit(2);
        }

        for (Suite suite : chosen) {
            TestSupport.begin(suite.id);
            suite.body.run();
        }
        System.exit(TestSupport.finish() == 0 ? 0 : 1);
    }

    /** The suites the comma-separated patterns name, in their fixed order. */
    private static List<Suite> select(List<Suite> suites, String only) {
        Set<Suite> chosen = new LinkedHashSet<>();
        for (String raw : only.split(",")) {
            String pattern = raw.trim().toLowerCase(Locale.ROOT);
            if (pattern.isEmpty()) {
                continue;
            }
            for (Suite suite : suites) {
                if (suite.matches(pattern)) {
                    chosen.add(suite);
                }
            }
        }
        return new ArrayList<>(chosen);
    }

    /** A whole-name match, or a glob with {@code *} and {@code ?}. */
    private static boolean glob(String pattern, String name) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return name.toLowerCase(Locale.ROOT).matches(regex.toString());
    }

    private static String ids(List<Suite> suites) {
        List<String> ids = new ArrayList<>();
        for (Suite suite : suites) {
            ids.add(suite.id);
        }
        return String.join(", ", ids);
    }

    private static void usage() {
        System.err.println("usage: AllTests [--only <suite>[,<suite>...]]");
        System.err.println("       a suite is an id or alias; * and ? are globs");
        System.exit(2);
    }
}
