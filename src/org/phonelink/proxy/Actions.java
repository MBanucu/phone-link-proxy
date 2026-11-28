package org.phonelink.proxy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one adb action table: what this build accepts, which extras each action
 * needs, whether the action is gated on the foreground or on an unlocked
 * screen, and one line saying what it does.
 *
 * <p>Everything that talks about the actions is generated from here: the
 * service's dispatch, the action section of {@link Help} (and therefore the
 * Info dialog and {@code dumpsys}), the {@code dumpsys} action list and the
 * README's table. Adding an action or an extra is an edit to this table and its
 * handler; the documentation cannot drift because it is not written twice.
 *
 * <p>Plain Java, no Android types: the hermetic suite validates the same table
 * the phone runs. Values are read through {@link Values}, which
 * {@link ProxyService} adapts from an {@code Intent}, so a missing or bad extra
 * is refused before a handler sees it. The messages name the action and the
 * extra, never a token, password or document body.
 */
final class Actions {

    /**
     * The adb contract's version: one integer, no feature names and no
     * credentials. The laptop reads it from {@code dumpsys} and {@code
     * state.txt} and refuses a build older than the action it needs.
     *
     * <p>History:
     * <ul>
     *   <li>1 — the first table: START, STOP, SAVE_PROFILE, DELETE_PROFILE,
     *       RENAME_PROFILE, EXPORT and IMPORT with the extras below.</li>
     * </ul>
     *
     * <p>Bump it only when an action or an extra changes in a way an older
     * laptop cannot drive. Do not put a token, password or feature string here.
     */
    static final int CONTRACT_VERSION = 1;

    static final String START = "START";
    static final String STOP = "STOP";
    static final String SAVE_PROFILE = "SAVE_PROFILE";
    static final String DELETE_PROFILE = "DELETE_PROFILE";
    static final String RENAME_PROFILE = "RENAME_PROFILE";
    static final String EXPORT = "EXPORT";
    static final String IMPORT = "IMPORT";

    static final String ACTION_START = "org.phonelink.proxy.action.START";
    static final String ACTION_STOP = "org.phonelink.proxy.action.STOP";
    static final String ACTION_SAVE_PROFILE = "org.phonelink.proxy.action.SAVE_PROFILE";
    static final String ACTION_DELETE_PROFILE = "org.phonelink.proxy.action.DELETE_PROFILE";
    static final String ACTION_RENAME_PROFILE = "org.phonelink.proxy.action.RENAME_PROFILE";
    static final String ACTION_EXPORT = "org.phonelink.proxy.action.EXPORT";
    static final String ACTION_IMPORT = "org.phonelink.proxy.action.IMPORT";

    /**
     * Whether the action needs the service in the foreground (a start from the
     * background cannot get the location foreground type) or the screen on and
     * the keyguard unlocked (a transfer binds a loopback socket).
     */
    enum Gate {
        NONE("none"),
        FOREGROUND("foreground"),
        UNLOCKED("unlocked");

        final String label;

        Gate(String label) {
            this.label = label;
        }
    }

    /** The values of one intent, without Android's {@code Intent} type. */
    interface Values {
        boolean has(String name);

        String string(String name);

        int integer(String name);
    }

    private interface Check {
        boolean valid(Values values, String name);
    }

    private static final Check ANY = (values, name) -> true;

    private static final Check PORT =
            (values, name) -> ProfileTransfer.validPort(values.integer(name));

    private static final Check MAX_CONNECTIONS = (values, name) -> {
        int max = values.integer(name);
        return max >= ProxyConfig.MIN_MAX_CONNECTIONS
                && max <= ProxyConfig.MAX_MAX_CONNECTIONS;
    };

    private static final Check NON_BLANK = (values, name) -> {
        String value = values.string(name);
        return value != null && !value.trim().isEmpty();
    };

    private static final Check AT_MOST_255_BYTES = (values, name) -> {
        String value = values.string(name);
        if (value == null) {
            return true;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= 255;
    };

    /** The token rule is the one the loopback wire enforces. */
    private static final Check TOKEN =
            (values, name) -> ProfileTransfer.validToken(values.string(name));

    /** One extra: its flag, value placeholder, validator and whether it is required. */
    static final class Extra {
        final String name;
        final String flag;
        final String value;
        final String rule;
        final boolean required;
        private final Check check;

        private Extra(
                String name, String flag, String value, String rule,
                boolean required, Check check) {
            this.name = name;
            this.flag = flag;
            this.value = value;
            this.rule = rule;
            this.required = required;
            this.check = check;
        }

        boolean valid(Values values) {
            return check.valid(values, name);
        }

        /** One line for the table, e.g. {@code --ei port N (1024..65535)}. */
        String usage() {
            String usage = flag + " " + name + (value.isEmpty() ? "" : " " + value);
            return rule.isEmpty() ? usage : usage + " (" + rule + ")";
        }
    }

    static final class Action {
        final String name;
        final String intentAction;
        final Gate gate;
        /** The notification's one word while the action runs. */
        final String progress;
        final String summary;
        final List<Extra> extras;

        private Action(
                String name, String intentAction, Gate gate, String progress,
                String summary, List<Extra> extras) {
            this.name = name;
            this.intentAction = intentAction;
            this.gate = gate;
            this.progress = progress;
            this.summary = summary;
            this.extras = Collections.unmodifiableList(new ArrayList<>(extras));
        }

        List<Extra> required() {
            return selected(true);
        }

        List<Extra> optional() {
            return selected(false);
        }

        private List<Extra> selected(boolean required) {
            List<Extra> chosen = new ArrayList<>();
            for (Extra extra : extras) {
                if (extra.required == required) {
                    chosen.add(extra);
                }
            }
            return chosen;
        }
    }

    /** What is wrong with one action's extras: the action and the extra to name. */
    static final class Problem {
        final Action action;
        final Extra extra;

        private Problem(Action action, Extra extra) {
            this.action = action;
            this.extra = extra;
        }

        /** A precise line for the notification and the log, never a value. */
        String message() {
            return action.name + " needs " + extra.usage();
        }
    }

    private static final List<Action> TABLE = table();

    private Actions() {
    }

    /** The action the intent names, or {@code null} when this build has none. */
    static Action find(String intentAction) {
        for (Action action : TABLE) {
            if (action.intentAction.equals(intentAction)) {
                return action;
            }
        }
        return null;
    }

    /** The first missing or invalid extra, or {@code null} when all are usable. */
    static Problem check(Action action, Values values) {
        for (Extra extra : action.extras) {
            boolean present = values.has(extra.name);
            if (!present) {
                if (extra.required) {
                    return new Problem(action, extra);
                }
                continue;
            }
            if (!extra.valid(values)) {
                return new Problem(action, extra);
            }
        }
        return null;
    }

    static int size() {
        return TABLE.size();
    }

    static List<Action> all() {
        return TABLE;
    }

    /** The action names, in table order, for a one-line list. */
    static String names() {
        List<String> names = new ArrayList<>();
        for (Action action : TABLE) {
            names.add(action.name);
        }
        return String.join(" ", names);
    }

    /** The non-secret version line {@code state.txt} carries. */
    static String stateLine() {
        return "contract_version " + CONTRACT_VERSION;
    }

    /**
     * The README action table. The block between the markers in
     * {@code README.md} must be exactly this text; the unit suite compares
     * them, so prose and table cannot disagree.
     */
    static String markdown() {
        StringBuilder out = new StringBuilder();
        out.append("| action | required extras | optional extras | gate | what it does |\n");
        out.append("|---|---|---|---|---|\n");
        for (Action action : TABLE) {
            out.append("| `").append(action.name).append("` | ")
                    .append(markdownExtras(action.required())).append(" | ")
                    .append(markdownExtras(action.optional())).append(" | ")
                    .append(action.gate.label).append(" | ")
                    .append(action.summary).append(" |\n");
        }
        return out.toString();
    }

    private static String markdownExtras(List<Extra> extras) {
        if (extras.isEmpty()) {
            return "\u2014";
        }
        List<String> parts = new ArrayList<>();
        for (Extra extra : extras) {
            parts.add("`" + extra.usage() + "`");
        }
        return String.join(", ", parts);
    }

    /** The action section of {@link Help}, generated from the same entries. */
    static String helpBlock() {
        StringBuilder out = new StringBuilder();
        out.append("Adb actions \u2014 contract_version ")
                .append(CONTRACT_VERSION).append('\n');
        for (Action action : TABLE) {
            out.append(String.format("  %-16s %-10s %s\n",
                    action.name, action.gate.label, action.summary));
            out.append("    intent    ").append(action.intentAction).append('\n');
            out.append("    needs     ")
                    .append(usageList(action.required(), "nothing"))
                    .append('\n');
            if (!action.optional().isEmpty()) {
                out.append("    optional  ")
                        .append(usageList(action.optional(), ""))
                        .append('\n');
            }
        }
        return out.toString();
    }

    private static String usageList(List<Extra> extras, String empty) {
        if (extras.isEmpty()) {
            return empty;
        }
        List<String> parts = new ArrayList<>();
        for (Extra extra : extras) {
            parts.add(extra.usage());
        }
        return String.join(", ", parts);
    }

    /**
     * The {@code dumpsys} lines: the contract version, the count and every
     * action with its gate and summary. Nothing here comes from an intent.
     */
    static String dump() {
        StringBuilder out = new StringBuilder();
        out.append("  contract_version ").append(CONTRACT_VERSION).append('\n');
        out.append("  actions     ").append(TABLE.size()).append('\n');
        for (Action action : TABLE) {
            out.append("  action      ").append(action.name)
                    .append(" \u00b7 ").append(action.gate.label)
                    .append(" \u00b7 ").append(action.summary).append('\n');
        }
        return out.toString();
    }

    private static List<Action> table() {
        List<Action> actions = new ArrayList<>();

        List<Extra> start = new ArrayList<>();
        start.add(optional("port", "--ei", "N", "1024..65535", PORT));
        start.add(optional("max_connections", "--ei", "N", "16..1024", MAX_CONNECTIONS));
        start.add(optional("user", "--es", "NAME", "at most 255 bytes", AT_MOST_255_BYTES));
        start.add(optional("password", "--es", "PASS", "at most 255 bytes", AT_MOST_255_BYTES));
        start.add(optional("ssid", "--es", "NAME", "", ANY));
        start.add(optional("anonymous", "--ez", "BOOL", "", ANY));
        start.add(optional("any_network", "--ez", "BOOL", "", ANY));
        start.add(optional("auto_start", "--ez", "BOOL", "", ANY));
        start.add(optional("wifi_lock", "--ez", "BOOL", "", ANY));
        start.add(optional("adb_wifi", "--ez", "BOOL", "", ANY));
        start.add(optional("profile", "--es", "NAME", "non-empty", NON_BLANK));
        actions.add(new Action(START, ACTION_START, Gate.FOREGROUND, "starting",
                "start or replace the listener on its port; omitted extras reuse the stored values",
                start));

        List<Extra> stop = new ArrayList<>();
        stop.add(optional("profile", "--es", "NAME", "non-empty", NON_BLANK));
        stop.add(optional("port", "--ei", "N", "1024..65535", PORT));
        actions.add(new Action(STOP, ACTION_STOP, Gate.NONE, "stopping",
                "stop every listener, or the one named", stop));

        List<Extra> save = new ArrayList<>();
        save.add(required("profile", "--es", "NAME", "non-empty", NON_BLANK));
        actions.add(new Action(SAVE_PROFILE, ACTION_SAVE_PROFILE, Gate.NONE, "saving",
                "keep the current configuration under a name", save));

        List<Extra> delete = new ArrayList<>();
        delete.add(required("profile", "--es", "NAME", "non-empty", NON_BLANK));
        actions.add(new Action(DELETE_PROFILE, ACTION_DELETE_PROFILE, Gate.NONE, "deleting",
                "remove a saved configuration by name", delete));

        List<Extra> rename = new ArrayList<>();
        rename.add(required("profile", "--es", "NAME", "non-empty", NON_BLANK));
        rename.add(required("to", "--es", "NEWNAME", "non-empty", NON_BLANK));
        actions.add(new Action(RENAME_PROFILE, ACTION_RENAME_PROFILE, Gate.NONE, "renaming",
                "give a saved configuration another name", rename));

        List<Extra> transfer = new ArrayList<>();
        transfer.add(required("port", "--ei", "N", "1024..65535", PORT));
        transfer.add(required("token", "--es", "TOKEN", "1..128 printable ASCII", TOKEN));
        actions.add(new Action(EXPORT, ACTION_EXPORT, Gate.UNLOCKED, "exporting",
                "send every saved proxy over a one-shot loopback socket", transfer));
        actions.add(new Action(IMPORT, ACTION_IMPORT, Gate.UNLOCKED, "importing",
                "read a ProfileDocument back over a loopback socket", transfer));

        return Collections.unmodifiableList(actions);
    }

    private static Extra required(
            String name, String flag, String value, String rule, Check check) {
        return new Extra(name, flag, value, rule, true, check);
    }

    private static Extra optional(
            String name, String flag, String value, String rule, Check check) {
        return new Extra(name, flag, value, rule, false, check);
    }
}
