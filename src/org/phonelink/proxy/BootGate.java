package org.phonelink.proxy;

/**
 * What this app can honestly say about the DokeOS Auto Start Management gate.
 *
 * <p>The list is behind {@code com.freeme.freemanager.permission.ACCESS_INTERFACE},
 * so neither the app nor adb can read it. The app therefore never reports the
 * gate as open. What it can observe is whether its boot receiver ran in the
 * current boot: Android's {@code BOOT_COUNT} moves at every boot, and the
 * receiver records the count it saw. A count above that marker means no boot
 * broadcast reached the app this boot; with {@code auto_start} on, that is the
 * shape a vendor block leaves behind. It stays a suspicion — a force-stop or
 * another failed boot gate leaves the same shape — so the panel and the doctor
 * say "likely blocked", never "blocked".
 *
 * <p>No Android types on purpose: the wording this class feeds is pinned by the
 * hermetic JVM suite.
 */
final class BootGate {

    /** The value the state file and the doctor read: the vendor list is not readable. */
    static final String UNREADABLE = "unreadable";

    private BootGate() {
    }

    /** The one line about the vendor gate that every state file carries. */
    static String oemLine() {
        return "oem_autostart " + UNREADABLE;
    }

    /**
     * The dated suspicion, or "" when there is none.
     *
     * <p>The timestamp is epoch seconds of the first observation after the
     * missed boot, not the boot's own clock; the panel and the doctor say so.
     */
    static String lastBootLine(long blockedAt) {
        return blockedAt > 0 ? "last_boot blocked " + blockedAt : "";
    }

    /** Every boot fact this app may write, one `key value` line each. */
    static String stateText(long blockedAt) {
        String last = lastBootLine(blockedAt);
        return last.isEmpty() ? oemLine() : oemLine() + "\n" + last;
    }

    /**
     * Whether the receiver has not run in the current boot.
     *
     * <p>Both counts have to be known: a clean install has no marker yet, and
     * "never seen a boot" is not evidence that one was blocked.
     */
    static boolean missedThisBoot(long bootCount, long lastSeenBoot) {
        return bootCount >= 0 && lastSeenBoot >= 0 && bootCount > lastSeenBoot;
    }

    /**
     * The blocked timestamp to keep after looking at the phone.
     *
     * <p>Set on the first observation of a missed boot with auto-start on, kept
     * while the same gap lasts, and cleared when auto-start is off (there was no
     * promise to keep) or the receiver has been seen again (the gate opened).
     */
    static long observedBlockedAt(
            long bootCount, long lastSeenBoot, boolean autoStart, long blockedAt, long now) {
        if (!missedThisBoot(bootCount, lastSeenBoot)) {
            return 0;
        }
        if (!autoStart) {
            return 0;
        }
        return blockedAt > 0 ? blockedAt : now;
    }
}
