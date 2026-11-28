package org.phonelink.proxy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the one adb action table and the three places generated
 * from it: the help text, the {@code dumpsys} list and the README table.
 *
 * <p>Plain JDK, no Android. {@code bash tests/proxy-app.test.sh --only <suite>}
 * runs this suite on its own.
 */
public final class ActionsTest {

    private static final String START_MARKER = "<!-- adb-actions:start -->";
    private static final String END_MARKER = "<!-- adb-actions:end -->";

    private ActionsTest() {
    }

    static void cases() {
        run("the table names every action this build dispatches", () -> {
            assertEqual(7, Actions.size(), "the action count");
            assertEqual("START STOP SAVE_PROFILE DELETE_PROFILE RENAME_PROFILE EXPORT IMPORT",
                    Actions.names(), "the action names and their order");
            assertEqual(Actions.ACTION_START, Actions.find(Actions.ACTION_START).intentAction,
                    "START's intent action");
            assertEqual(Actions.ACTION_STOP, Actions.find(Actions.ACTION_STOP).intentAction,
                    "STOP's intent action");
            assertEqual(Actions.ACTION_SAVE_PROFILE,
                    Actions.find(Actions.ACTION_SAVE_PROFILE).intentAction,
                    "SAVE_PROFILE's intent action");
            assertEqual(Actions.ACTION_DELETE_PROFILE,
                    Actions.find(Actions.ACTION_DELETE_PROFILE).intentAction,
                    "DELETE_PROFILE's intent action");
            assertEqual(Actions.ACTION_RENAME_PROFILE,
                    Actions.find(Actions.ACTION_RENAME_PROFILE).intentAction,
                    "RENAME_PROFILE's intent action");
            assertEqual(Actions.ACTION_EXPORT, Actions.find(Actions.ACTION_EXPORT).intentAction,
                    "EXPORT's intent action");
            assertEqual(Actions.ACTION_IMPORT, Actions.find(Actions.ACTION_IMPORT).intentAction,
                    "IMPORT's intent action");
        });

        run("an unknown action is not in the table", () -> {
            // The service fails closed for this, rather than starting a
            // listener an older app would have started.
            assertTrue(Actions.find("org.phonelink.proxy.action.NOPE") == null,
                    "an unknown action was accepted");
            assertTrue(Actions.find("") == null, "the empty action was accepted");
            assertTrue(Actions.find(null) == null, "a null action was accepted");
        });

        run("a missing required extra names the action and the extra", () -> {
            assertEqual("SAVE_PROFILE needs --es profile NAME (non-empty)",
                    problem(Actions.SAVE_PROFILE, new Values()).message(),
                    "save with no name");
            assertEqual("DELETE_PROFILE needs --es profile NAME (non-empty)",
                    problem(Actions.DELETE_PROFILE, new Values()).message(),
                    "delete with no name");
            assertEqual("RENAME_PROFILE needs --es profile NAME (non-empty)",
                    problem(Actions.RENAME_PROFILE, new Values()).message(),
                    "rename with no name");
            assertEqual("RENAME_PROFILE needs --es to NEWNAME (non-empty)",
                    problem(Actions.RENAME_PROFILE, new Values().put("profile", "old")).message(),
                    "rename with no new name");
            assertEqual("EXPORT needs --ei port N (1024..65535)",
                    problem(Actions.EXPORT, new Values()).message(),
                    "export with no port");
            assertEqual("EXPORT needs --es token TOKEN (1..128 printable ASCII)",
                    problem(Actions.EXPORT, new Values().put("port", 47123)).message(),
                    "export with no token");
            assertEqual("IMPORT needs --ei port N (1024..65535)",
                    problem(Actions.IMPORT, new Values()).message(),
                    "import with no port");
        });

        run("a bad required extra names the action and the extra", () -> {
            assertEqual("EXPORT needs --ei port N (1024..65535)",
                    problem(Actions.EXPORT,
                            new Values().put("port", 80).put("token", "6f3c")).message(),
                    "a privileged port");
            assertEqual("EXPORT needs --es token TOKEN (1..128 printable ASCII)",
                    problem(Actions.EXPORT,
                            new Values().put("port", 47123).put("token", "two words")).message(),
                    "a token with a space");
            StringBuilder longToken = new StringBuilder();
            for (int index = 0; index < 129; index++) {
                longToken.append('a');
            }
            assertEqual("IMPORT needs --es token TOKEN (1..128 printable ASCII)",
                    problem(Actions.IMPORT,
                            new Values().put("port", 47123)
                                    .put("token", longToken.toString())).message(),
                    "a token over 128 characters");
            assertEqual("SAVE_PROFILE needs --es profile NAME (non-empty)",
                    problem(Actions.SAVE_PROFILE, new Values().put("profile", "   ")).message(),
                    "a blank name");
            assertEqual("RENAME_PROFILE needs --es to NEWNAME (non-empty)",
                    problem(Actions.RENAME_PROFILE,
                            new Values().put("profile", "old").put("to", "")).message(),
                    "an empty new name");
            // An optional extra is validated too, because a bad port on STOP
                    // would otherwise silently stop nothing.
            assertEqual("STOP needs --ei port N (1024..65535)",
                    problem(Actions.STOP, new Values().put("port", 0)).message(),
                    "a stop on port zero");
            assertEqual("START needs --ei max_connections N (16..1024)",
                    problem(Actions.START,
                            new Values().put("max_connections", 4)).message(),
                    "an impossible connection ceiling");
        });

        run("a valid action passes the table", () -> {
            assertTrue(Actions.check(action(Actions.START), new Values()) == null,
                    "a start with stored values was refused");
            assertTrue(Actions.check(action(Actions.STOP), new Values()) == null,
                    "a stop-all was refused");
            assertTrue(Actions.check(action(Actions.START),
                            new Values().put("port", 1081).put("user", "proxy")
                                    .put("password", "s3cret").put("profile", "laptop")) == null,
                    "a full start was refused");
            assertTrue(Actions.check(action(Actions.EXPORT),
                            new Values().put("port", 47123).put("token", "6f3c")) == null,
                    "a valid export was refused");
            assertTrue(Actions.check(action(Actions.RENAME_PROFILE),
                            new Values().put("profile", "old").put("to", "new")) == null,
                    "a valid rename was refused");
        });

        run("the README action table is the table", () -> {
            String readme = read("README.md");
            int start = readme.indexOf(START_MARKER);
            int end = readme.indexOf(END_MARKER);
            assertTrue(start >= 0, "README has no adb-actions start marker");
            assertTrue(end > start, "README has no adb-actions end marker");
            assertTrue(readme.indexOf(START_MARKER, start + 1) < 0,
                    "README has more than one action table");
            String block = readme.substring(start + START_MARKER.length(), end).trim();
            assertEqual(Actions.markdown().trim(), block,
                    "the README action table is not the generated one");
            assertTrue(readme.contains("`contract_version " + Actions.CONTRACT_VERSION + "`"),
                    "README does not name the contract version");
        });

        run("the help text carries the generated action section", () -> {
            String help = Help.text("org.phonelink.proxy");
            assertTrue(help.contains(Actions.helpBlock()),
                    "the help action section is not the table's");
            for (Actions.Action action : Actions.all()) {
                assertTrue(help.contains(action.name + " "), "help omits " + action.name);
                assertTrue(help.contains(action.intentAction),
                        "help omits the intent action for " + action.name);
            }
        });

        run("the dumpsys action list is generated from the table", () -> {
            String dump = Actions.dump();
            assertTrue(dump.startsWith("  contract_version " + Actions.CONTRACT_VERSION + "\n"),
                    "dumpsys does not start with the contract version");
            assertTrue(dump.contains("  actions     " + Actions.size() + "\n"),
                    "dumpsys does not count the actions");
            for (Actions.Action action : Actions.all()) {
                assertTrue(dump.contains("  action      " + action.name
                                + " \u00b7 " + action.gate.label
                                + " \u00b7 " + action.summary + "\n"),
                        "dumpsys omits " + action.name);
            }
        });

        run("the version line carries no secret", () -> {
            assertEqual(1, Actions.CONTRACT_VERSION, "the contract version");
            assertEqual("contract_version 1", Actions.stateLine(), "the state.txt line");
            String line = Actions.stateLine().toLowerCase(java.util.Locale.ROOT);
            for (String secret : new String[] {"token", "password", "secret", "bearer"}) {
                assertFalse(line.contains(secret), "the version line holds " + secret);
            }
        });
    }

    private static Actions.Action action(String name) {
        for (Actions.Action action : Actions.all()) {
            if (action.name.equals(name)) {
                return action;
            }
        }
        throw new AssertionError("the table does not name " + name);
    }

    private static Actions.Problem problem(String name, Values values) {
        Actions.Problem problem = Actions.check(action(name), values);
        assertTrue(problem != null, name + " accepted the extras");
        return problem;
    }

    /** A README or other repository file, rooted where the suite was started. */
    private static String read(String name) throws Exception {
        Path root = Path.of(System.getProperty("phone.repo", "."));
        return new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
    }

    /** The extras of one intent, without Android's Intent. */
    private static final class Values implements Actions.Values {
        private final Map<String, Object> values = new HashMap<>();

        Values put(String name, Object value) {
            values.put(name, value);
            return this;
        }

        @Override
        public boolean has(String name) {
            return values.containsKey(name);
        }

        @Override
        public String string(String name) {
            Object value = values.get(name);
            return value == null ? null : String.valueOf(value);
        }

        @Override
        public int integer(String name) {
            Object value = values.get(name);
            return value instanceof Integer ? (Integer) value : 0;
        }
    }
}
