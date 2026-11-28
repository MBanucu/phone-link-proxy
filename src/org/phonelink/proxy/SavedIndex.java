package org.phonelink.proxy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The non-secret index of saved proxies that {@code state.txt} carries while the
 * service is stopped.
 *
 * <p>A stopped service has no {@code dumpsys} record, so without this file a
 * laptop could not tell "no saved proxies" from "the service is not running".
 * The index says which proxies are saved, their port and switches, and nothing
 * else: a name, a user and an SSID are not credentials, and a password is never
 * part of an {@link Entry}.
 *
 * <p>The format is one text block, appended to the existing {@code key value}
 * lines:
 *
 * <pre>
 * saved_index_version 1
 * saved_index_count 2
 * saved_index &lt;b64(name)&gt; &lt;port&gt; &lt;b64(user)&gt; &lt;anonymous 0|1&gt; &lt;auto_start 0|1&gt; &lt;b64(ssid)&gt; &lt;any_network 0|1&gt;
 * </pre>
 *
 * <p>No saved proxies writes {@code saved_index_count 0} and {@code saved_index
 * none}, so an empty list is distinguishable from a file the app never wrote.
 * Names, users and SSIDs are UTF-8 and URL-safe Base64 with standard {@code =}
 * padding, which keeps spaces, quotes and non-ASCII text in one field with no
 * quoting rules; an empty field is {@value #EMPTY} ({@code ~}), a character
 * outside the Base64URL alphabet. Plain Java, so the JVM suite covers the
 * format.
 */
final class SavedIndex {

    /** The format version a reader has to understand. */
    static final int VERSION = 1;

    /** An empty Base64URL field; {@code ~} is not in the Base64URL alphabet. */
    static final String EMPTY = "~";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder();

    private SavedIndex() {
    }

    /** One saved proxy, with everything the index may publish and nothing more. */
    static final class Entry {
        final String name;
        final ProxyConfig config;
        final boolean autoStart;

        /**
         * @param config the saved configuration; only its port, user, anonymous,
         *     SSID and any-network fields are ever read, never its password
         */
        Entry(String name, ProxyConfig config, boolean autoStart) {
            this.name = name == null ? "" : name;
            this.config = config;
            this.autoStart = autoStart;
        }
    }

    /** The index lines, newline-terminated, in the order the entries came. */
    static String format(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        out.append("saved_index_version ").append(VERSION).append('\n');
        out.append("saved_index_count ").append(entries.size()).append('\n');
        if (entries.isEmpty()) {
            out.append("saved_index none\n");
            return out.toString();
        }
        for (Entry entry : entries) {
            out.append(line(entry)).append('\n');
        }
        return out.toString();
    }

    /** One {@code saved_index} line, without its newline. */
    static String line(Entry entry) {
        ProxyConfig config = entry.config;
        return "saved_index "
                + base64(entry.name)
                + " " + config.port
                // An anonymous configuration has no user to publish, even if a
                // hand-edited preference left an old one behind.
                + " " + base64(config.anonymous ? "" : config.user)
                + " " + flag(config.anonymous)
                + " " + flag(entry.autoStart)
                + " " + base64(config.ssid)
                + " " + flag(config.anyNetwork);
    }

    /**
     * One field, or {@link #EMPTY} for the empty string. Non-empty text always
     * encodes to at least four characters, so the sentinel cannot be a value.
     */
    static String base64(String value) {
        if (value == null || value.isEmpty()) {
            return EMPTY;
        }
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int flag(boolean set) {
        return set ? 1 : 0;
    }
}
