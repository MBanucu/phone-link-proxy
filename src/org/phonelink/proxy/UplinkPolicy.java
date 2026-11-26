package org.phonelink.proxy;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether the listener may open, from the phone's lock state and the Wi-Fi
 * links that are connected.
 *
 * <p>The gate exists because the listener binds {@code 0.0.0.0}: it is reachable
 * from every network interface, so it may only be up while the phone is a client
 * of the configured SSID. The connectivity callbacks hand this class what they
 * last saw; it does no I/O and knows no Android types, so the rules are the same
 * on a phone and in the JVM suite.
 *
 * <p>Before the first unlock the platform redacts every SSID. That state is
 * "unknown", not "a different network": with nothing readable the phone is
 * normally on the laptop's own network, so the listener is allowed while locked
 * and the same observation is a pause as soon as the user unlocks. A redacted
 * SSID after unlock means the location permission or the location toggle is
 * missing, and the strict comparison cannot be made.
 *
 * <p>More than one Wi-Fi link saying the configured SSID, or a matching link
 * beside one whose SSID cannot be read, is ambiguous: the wildcard listener
 * would not know which link the gate was about, so it stays closed. A link that
 * is certainly on another SSID is a plain mismatch.
 */
final class UplinkPolicy {

    /** One connected network, as the connectivity callbacks last described it. */
    static final class Link {
        /** The SSID, or null when the platform redacted it or it is unknown. */
        final String ssid;
        /** Whether the network is a Wi-Fi link. */
        final boolean wifi;
        /** Whether the platform currently calls this the default network. */
        final boolean isDefault;

        Link(String ssid, boolean wifi, boolean isDefault) {
            this.ssid = ssid;
            this.wifi = wifi;
            this.isDefault = isDefault;
        }

        static Link onWifi(String ssid) {
            return new Link(ssid, true, false);
        }

        static Link onWifi(String ssid, boolean isDefault) {
            return new Link(ssid, true, isDefault);
        }

        static Link other(boolean isDefault) {
            return new Link(null, false, isDefault);
        }
    }

    /** The gate's answer: a bool the caller acts on and a line a person reads. */
    static final class Verdict {
        final boolean allowed;
        final String detail;

        Verdict(boolean allowed, String detail) {
            this.allowed = allowed;
            this.detail = detail;
        }
    }

    private UplinkPolicy() {
    }

    /**
     * The gate's answer for one snapshot of the phone.
     *
     * @param configured the SSID the listener is for; unused when anyNetwork
     * @param anyNetwork whether the configuration deliberately allows any link
     * @param userLocked whether the user has not unlocked since boot
     * @param links every connected network the callbacks know about
     */
    static Verdict decide(
            String configured, boolean anyNetwork, boolean userLocked, List<Link> links) {
        if (anyNetwork) {
            // "Any network" still means a network: with none there is nowhere
            // for the listener to be reached.
            if (links.isEmpty()) {
                return new Verdict(false, "no network");
            }
            return new Verdict(true, "any network is allowed");
        }
        if (links.isEmpty()) {
            return new Verdict(false, "no default network");
        }

        List<Link> wifi = new ArrayList<>();
        int matches = 0;
        int unknown = 0;
        int others = 0;
        String firstOther = null;
        for (Link link : links) {
            if (!link.wifi) {
                continue;
            }
            wifi.add(link);
            if (link.ssid == null) {
                unknown++;
            } else if (link.ssid.equals(configured)) {
                matches++;
            } else {
                others++;
                if (firstOther == null) {
                    firstOther = link.ssid;
                }
            }
        }
        if (wifi.isEmpty()) {
            return new Verdict(false, "no Wi-Fi network is connected");
        }
        if (matches > 1) {
            // Dual-band or a repeater can present the same SSID twice; the
            // wildcard listener cannot say which of them the gate meant.
            return new Verdict(false, matches + " Wi-Fi networks say \"" + configured
                    + "\"; cannot tell which one is the uplink");
        }
        if (matches == 1) {
            if (unknown > 0) {
                return new Verdict(false, "\"" + configured
                        + "\" is connected but another Wi-Fi SSID is unreadable");
            }
            return new Verdict(true, "on \"" + configured + "\"");
        }
        if (unknown > 0) {
            // Readable SSIDs that all differ are a mismatch; an unreadable one
            // is not. Before unlock the platform redacts them all, and the
            // phone is normally on the laptop's own network by then.
            if (userLocked) {
                return new Verdict(true, "on Wi-Fi (SSID unreadable before unlock)");
            }
            return new Verdict(false,
                    "cannot read the Wi-Fi SSID; needs location permission and location on");
        }
        if (others == 1) {
            return new Verdict(false, "on \"" + firstOther + "\", not \"" + configured + "\"");
        }
        return new Verdict(false, others + " Wi-Fi networks are connected, none of them \""
                + configured + "\"");
    }
}
