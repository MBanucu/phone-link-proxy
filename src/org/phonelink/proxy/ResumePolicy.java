package org.phonelink.proxy;

/**
 * Which restarts a listener may make without the user.
 *
 * <p>{@code auto_start} is the promise; a stored port is what it comes back on.
 * Both a reboot and a package update ask this one question, so "off" means the
 * same thing after either: the update leaves the listener stopped, exactly as a
 * reboot does.
 *
 * <p>No Android types on purpose: both decisions are pinned by the hermetic JVM
 * suite.
 */
final class ResumePolicy {

    private ResumePolicy() {
    }

    static boolean shouldStart(boolean autoStart, boolean configured) {
        return autoStart && configured;
    }
}
