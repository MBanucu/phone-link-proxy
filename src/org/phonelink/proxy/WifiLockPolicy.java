package org.phonelink.proxy;

import java.util.List;

/**
 * Whether the service should hold a Wi-Fi lock right now.
 *
 * <p>Plain Java, so the rule is tested on a JVM instead of on a phone: the lock
 * is only useful for a listener that is actually bound to a Wi-Fi interface. A
 * paused listener has no association to protect, and an {@code any_network}
 * listener may be serving a USB or Ethernet client, so neither should keep the
 * Wi-Fi radio awake.
 */
final class WifiLockPolicy {

    /** One listener as far as the lock is concerned. */
    static final class Listener {
        final boolean listening;
        final boolean anyNetwork;

        Listener(boolean listening, boolean anyNetwork) {
            this.listening = listening;
            this.anyNetwork = anyNetwork;
        }
    }

    private WifiLockPolicy() {
    }

    /**
     * True while the switch is on and at least one listener is bound on a
     * network that depends on the Wi-Fi association.
     */
    static boolean hold(boolean enabled, List<Listener> listeners) {
        if (!enabled) {
            return false;
        }
        for (Listener listener : listeners) {
            if (listener.listening && !listener.anyNetwork) {
                return true;
            }
        }
        return false;
    }
}
