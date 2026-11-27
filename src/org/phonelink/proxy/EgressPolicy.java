package org.phonelink.proxy;

import java.util.List;

/**
 * Which network the upstream sockets may use, and when one has to be refused.
 *
 * <p>On the AP link the phone's default network is the laptop's Wi-Fi, so a
 * socket opened on the default route comes back to the host that asked for it.
 * The egress therefore chooses a cellular link even when a validated Wi-Fi link
 * is the default. The choice is made here, without Android types, so the
 * hairpin case is exercised by the JVM suite instead of only on a phone.
 *
 * <p>{@link #refusal} is the runtime guard: a link the platform resolved for
 * the tunnel but that is not cellular must not carry a socket, because that
 * socket would follow the default route back into the laptop. It fails closed
 * and gives the one line the log prints.
 */
final class EgressPolicy {

    /** One network as the connectivity callbacks described it. */
    static final class Link {
        /** The platform's numeric network id, without its {@code Network(...)} wrapper. */
        final String network;
        /** The interface name, or null when the platform did not say. */
        final String iface;
        final boolean cellular;
        final boolean wifi;
        final boolean internet;
        final boolean validated;
        /** Whether the platform currently calls this the default network. */
        final boolean isDefault;

        Link(String network, String iface, boolean cellular, boolean wifi,
                boolean internet, boolean validated, boolean isDefault) {
            this.network = network;
            this.iface = iface;
            this.cellular = cellular;
            this.wifi = wifi;
            this.internet = internet;
            this.validated = validated;
            this.isDefault = isDefault;
        }

        static Link cellular(String network, String iface) {
            return new Link(network, iface, true, false, true, true, false);
        }

        static Link wifi(String network, String iface, boolean isDefault) {
            return new Link(network, iface, false, true, true, true, isDefault);
        }
    }

    /** The link selection's answer: the one to use, or why there is none. */
    static final class Route {
        final Link link;
        final String reason;

        Route(Link link, String reason) {
            this.link = link;
            this.reason = reason;
        }

        boolean allowed() {
            return link != null;
        }
    }

    private EgressPolicy() {
    }

    /**
     * Pick the cellular link out of what the platform knows.
     *
     * <p>A Wi-Fi link is never chosen, however validated or default it is:
     * this is the hairpin, and choosing it is the loop. A validated cellular
     * link is preferred over one still waiting for validation, but an
     * unvalidated cellular link is still better than the Wi-Fi default.
     */
    static Route select(List<Link> links) {
        Link chosen = null;
        Link defaultLink = null;
        for (Link link : links) {
            if (link == null) {
                continue;
            }
            if (link.cellular) {
                if (chosen == null || (!chosen.validated && link.validated)) {
                    chosen = link;
                }
            } else if (defaultLink == null && (link.isDefault || link.wifi)) {
                defaultLink = link;
            }
        }
        if (chosen != null) {
            return new Route(chosen, null);
        }
        if (defaultLink != null) {
            return new Route(null, "the default is " + kind(defaultLink)
                    + " (network " + defaultLink.network
                    + ") and no cellular network is available; a socket on it would"
                    + " follow the default route back into the laptop");
        }
        return new Route(null, "no cellular network is available");
    }

    /**
     * Why a link the egress resolved must not carry an upstream socket, or null
     * when it may. Only a cellular transport is accepted, so an unknown link
     * fails closed rather than risking the loop.
     */
    static String refusal(Link link) {
        if (link == null) {
            return "no network was resolved for the upstream socket";
        }
        if (link.cellular) {
            return null;
        }
        return "the resolved egress is " + kind(link) + " (network " + link.network
                + "), not cellular; a socket on it would follow the default route"
                + " back into the laptop";
    }

    /**
     * The {@code egress} line a person reads in {@code dumpsys}, for example
     * {@code cellular network 100 on rmnet_data0}.
     */
    static String describe(Link link) {
        if (link == null) {
            return null;
        }
        StringBuilder line = new StringBuilder(kind(link))
                .append(" network ").append(link.network);
        if (link.iface != null && !link.iface.isEmpty()) {
            line.append(" on ").append(link.iface);
        }
        if (!link.cellular) {
            line.append(" (refused: not cellular)");
        }
        return line.toString();
    }

    private static String kind(Link link) {
        if (link.cellular) {
            return "cellular";
        }
        if (link.wifi) {
            return "Wi-Fi/AP";
        }
        return "an unknown transport";
    }
}
