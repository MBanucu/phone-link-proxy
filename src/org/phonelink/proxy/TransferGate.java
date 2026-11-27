package org.phonelink.proxy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One export or import at a time.
 *
 * <p>An {@link AtomicBoolean} rather than a plain flag: {@code onStartCommand}
 * reads it on the main thread while the worker clears it on another, and a
 * second action that raced the first must be refused rather than open a second
 * socket. Kept apart from the service so the claim can be tested on a JVM
 * without Android.
 */
final class TransferGate {

    private final AtomicBoolean busy = new AtomicBoolean();

    /** The caller that gets {@code true} owns the transfer until {@link #end()}. */
    boolean begin() {
        return busy.compareAndSet(false, true);
    }

    void end() {
        busy.set(false);
    }
}
