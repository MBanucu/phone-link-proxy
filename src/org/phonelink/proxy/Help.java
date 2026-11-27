package org.phonelink.proxy;

/**
 * The app explaining itself, in one place: the panel's Info dialog and the
 * service's {@code dumpsys} dump print the same text, so what a person reads on
 * the phone cannot drift from what an agent reads over adb.
 *
 * <p>No Android types on purpose: it is part of the hermetic JVM suite, where a
 * test pins the adb contract this text documents.
 */
final class Help {

    /**
     * The package the classes and the adb actions are named in, which is the
     * same whatever this app is installed as: build.sh relinks the manifest
     * under a second id for the build the live harness installs, and the code
     * keeps its package.
     */
    static final String CODE = "org.phonelink.proxy";

    /** The one command that prints this text over adb, for the package asked for. */
    static String adbDump(String pkg) {
        return "adb shell dumpsys activity service " + component(pkg, "ProxyService");
    }

    /**
     * A component as adb wants it: `org.phonelink.proxy/.ProxyService` when the
     * id is the code package, and the long form when a test build carries
     * another id — the class keeps its package, the id does not.
     */
    static String component(String pkg, String name) {
        return pkg.equals(CODE) ? pkg + "/." + name : pkg + "/" + CODE + "." + name;
    }

    /** The activity extra that opens the Info dialog, for a phone in one's hand. */
    static final String EXTRA_HELP = "help";

    private Help() {
    }

    static String text(String pkg) {
        String service = component(pkg, "ProxyService");
        return String.join("\n",
                "Phone link proxy — a SOCKS5 listener that lets the laptop use this",
                "phone's mobile connection.",
                "",
                "It carries TCP (CONNECT) and UDP (UDP ASSOCIATE, so QUIC and other",
                "UDP flows work); BIND is refused.",
                "",
                "Several saved proxies can listen at once, each on its own port,",
                "with its own credentials and its own SSID: the list is a list of",
                "listeners. One port holds one listener, so starting a proxy on a",
                "port that is taken replaces what was there; the other ports keep",
                "listening. The Quick Settings tile switches the proxies whose \"Start",
                "and stop from the tile\" is on, and with none on it starts the one in",
                "use and stops everything. Its settings — the menu's The tile — say how",
                "far a click reaches in each direction, and a cycle of sets to bring up",
                "in between: the tile's proxies, then every saved proxy, then off, with",
                "the subtitle naming the set that is up and a set that is already up",
                "left alone, so a wider one only starts what is missing.",
                "",
                "The laptop joins this phone's hotspot and drives it over adb with",
                "wireless debugging: it starts the listener here and sends its own",
                "traffic through it, so the internet sees the phone's mobile address.",
                "The phone is itself a client of the laptop's access point at that",
                "moment, so its normal route leads back to the laptop; the app holds",
                "an explicit cellular network request for its egress instead.",
                "",
                "When the port is open",
                "  Only while the phone is a client of the configured SSID — or of any",
                "  network when \"Ignore the SSID\" is on. Otherwise the service stays",
                "  up with the port closed and the notification says why; it opens on",
                "  the next SSID match.",
                "",
                "  Android may leave an access point that has no internet after a",
                "  minute, which closes this listener with the link. wifi_lock asks",
                "  Android to keep the Wi-Fi association while a Wi-Fi listener is",
                "  bound. It is an opt-in experiment, not a promise: Android 14+",
                "  treats the lock as low latency and may only honour that with the",
                "  screen on and the app in the foreground, and the lock costs",
                "  battery while it is held. A listener started with any_network is",
                "  left out, since it need not use Wi-Fi at all.",
                "",
                "The panel — a list of saved proxies, and one of them opened. The",
                "header holds Info, a warning mark while the battery exemption is",
                "missing (the DokeOS list cannot be read), and a menu; the state is",
                "one line above Start and Stop, so the list gets the room. The",
                "battery line and that red mark are about the battery gate only;",
                "the separate Auto Start Management line says the vendor list is",
                "unreadable, or that a boot was likely blocked.",
                "  Home                   every saved proxy with what it holds and",
                "                         whether it is running; New proxy copies the",
                "                         one in use and takes the next free port, then",
                "                         opens it. With nothing saved the page says so",
                "                         and the same button is the way in.",
                "  One proxy              port, user, password, SSID, the two boxes,",
                "                         auto-start, its battery line, wireless",
                "                         debugging and the tile switch — the whole",
                "                         configuration, applied and stored by Start.",
                "                         Save writes the form, Save as… copies it under",
                "                         another name, Revert drops the edits, Delete",
                "                         removes it.",
                "  Its log                the last 200 things that happened to this",
                "                         proxy — starts, uplink changes, client counts,",
                "                         failures — with the bytes it moved in this run",
                "                         and a sparkline of the last minute. The menu's",
                "                         All events has everything, whatever it was about.",
                "",
                "Over adb, no unlock needed",
                "  adb shell am start-foreground-service -n " + service + " \\",
                "    -a org.phonelink.proxy.action.START --ei port 1081 --es user proxy \\",
                "    --es password … --es ssid PhoneLink",
                "  adb shell am start-foreground-service -n " + service + " \\",
                "    -a org.phonelink.proxy.action.STOP",
                "",
                "  A stop with --es profile NAME stops that proxy and leaves the rest;",
                "  --ei port N stops the one on that port; neither stops them all.",
                "",
                "  The panel's Start and Stop mean one proxy too: Start adds this",
                "  one, replacing only what holds its port (the line under the",
                "  button says which of the two it is), and Stop stops this one.",
                "  Start also marks it in use — the mark on its card — which is",
                "  what the tile and a reboot start.",
                "",
                "  Every extra is optional; a start that omits one reuses the stored",
                "  value, so changing only the port keeps the credentials.",
                "    --ei port N             listener port (default 1080)",
                "    --ei max_connections N  connections served at once (default 256,",
                "                            16..1024; each live one costs two threads)",
                "    --es user NAME          SOCKS5 user; empty means no authentication",
                "    --es password PASS      SOCKS5 password",
                "    --es ssid NAME          the Wi-Fi the listener may run on",
                "    --ez anonymous BOOL     listen without authentication",
                "    --ez any_network BOOL   ignore the SSID",
                "    --ez auto_start BOOL    come back after a reboot",
                "    --ez wifi_lock BOOL     hold a Wi-Fi lock while a Wi-Fi listener",
                "                            is bound; an opt-in experiment, and it",
                "                            costs battery",
                "    --ez adb_wifi BOOL      switch wireless debugging back on when the",
                "                            SSID matches, so adb can reach the phone",
                "    --es profile NAME       start the saved configuration called NAME,",
                "                            with any other extra applied on top",
                "",
                "  The configuration the service has becomes a saved one with",
                "  adb shell am start-foreground-service -n " + service + " \\",
                "    -a org.phonelink.proxy.action.SAVE_PROFILE --es profile NAME",
                "  the same command with",
                "    -a org.phonelink.proxy.action.RENAME_PROFILE --es profile NAME \\",
                "    --es to NEWERNAME",
                "  renames it, and with",
                "    -a org.phonelink.proxy.action.DELETE_PROFILE --es profile NAME",
                "  removes it.",
                "",
                "  Every saved proxy can leave for the laptop and come back over a",
                "  one-shot loopback socket, which is how a signer change or a lost",
                "  phone is survived without a file on this phone:",
                "    -a org.phonelink.proxy.action.EXPORT --ei port N --es token T",
                "    -a org.phonelink.proxy.action.IMPORT --ei port N --es token T",
                "  The port is the transfer's, not a listener's. It works only while",
                "  the screen is unlocked; the app binds 127.0.0.1, takes one",
                "  connection for at most a minute, and never writes the document",
                "  to storage. The laptop's README has the framing.",
                "  `dumpsys` lists what is running — each with its port, its bytes",
                "  and its clients — the network the tunnel leaves by, then what is",
                "  saved, with what each one holds and which one was started last;",
                "  the panel's list shows the same names.",
                "",
                "  Both paths write one store, so the panel shows what adb started.",
                "",
                "About a restart after a reboot",
                "  Two independent gates: this app enabled in DokeOS Auto Start",
                "  Management (System Manager > App Management > Auto Start",
                "  Management) and exempt from battery optimization. The vendor",
                "  list is behind com.freeme.freemanager.permission.ACCESS_INTERFACE:",
                "  neither this app nor adb can read or write it, so the panel",
                "  reports it as unreadable, never as clear. The battery line and",
                "  the red header mark are the battery gate alone.",
                "",
                "  A boot whose broadcast never reached this app, while auto-start",
                "  is on, is reported as \"likely blocked this boot\" and dated when",
                "  the app noticed. The state file only records the observation,",
                "  last_boot no_broadcast <epoch>; the words \"likely blocked\" are",
                "  the panel's interpretation, not a read of the list. stopped=true",
                "  is also what a clean install shows.",
                "",
                "  The screen is driven by wireless-adb-keeper/autostart-allow.sh",
                "  \"Phone link proxy\" (adb + uiautomator, phone unlocked) or",
                "  phone-link-tools/autotoggle (accessibility service); both need",
                "  their one-time manual or accessibility step. Without both gates",
                "  the tile and adb still start the proxy; it just does not come",
                "  back by itself.",
                "",
                "This text",
                "  " + adbDump(pkg),
                "  or the Info button in the panel. With the app stopped,",
                "  adb shell am start -n " + component(pkg, "MainActivity")
                            + " --ez help true",
                "  opens the panel with this dialog.");
    }
}
