package org.phonelink.proxy;

/**
 * The cellular request's retry state, kept apart from the Android callback.
 *
 * <p>{@link Egress} still owns the {@code Network} and the
 * {@code ConnectivityManager}, but the question "should we ask again, and when"
 * has no Android types in it. The retry is needed because a request started
 * before mobile data exists — at boot, in airplane mode, while the cellular
 * link drops — is answered once with {@code onUnavailable} and never repeats
 * itself. Clients then wait out {@link Egress#AWAIT_MS} and fail until the
 * service restarts.
 *
 * <p>The state is one of: never started, requesting, waiting for a retry, or
 * closed. A retry is only ever pending once, so a callback cannot be registered
 * twice; the delay grows 1 s, 2 s, 5 s, 10 s and then stays at 30 s, and a
 * network becoming available cancels the pending retry and starts the sequence
 * over.
 */
final class EgressState {

    /** The retry delays, in order; the last one repeats for every later try. */
    private static final long[] BACKOFF_MS = {1_000, 2_000, 5_000, 10_000, 30_000};

    /** Why there is no cached network right now, and the words for it. */
    private enum Why {
        NONE("waiting for cellular"),
        UNAVAILABLE("cellular unavailable"),
        LOST("cellular lost"),
        CAPABILITY_LOST("cellular lost internet");

        final String text;

        Why(String text) {
            this.text = text;
        }
    }

    private boolean started;
    private boolean closed;
    private boolean retryPending;
    private long retryAt;
    private int attempt;
    private Why why = Why.NONE;

    /** The first start asks for a network; a later one changes nothing. */
    synchronized boolean start() {
        if (started || closed) {
            return false;
        }
        started = true;
        why = Why.NONE;
        return true;
    }

    /**
     * A network arrived, so there is nothing to retry for now. The attempt
     * count stays: a network that turns out not to carry the internet must not
     * reset the backoff to one second on every request.
     */
    synchronized void present() {
        if (closed) {
            return;
        }
        retryPending = false;
        why = Why.NONE;
    }

    /** The network is usable: stop the retry and forget how many attempts failed. */
    synchronized void available() {
        present();
        attempt = 0;
    }

    /** {@code onUnavailable}: the request ended without a network. */
    synchronized long unavailable(long now) {
        return schedule(Why.UNAVAILABLE, now);
    }

    /** {@code onLost}: the network we had went away. */
    synchronized long lost(long now) {
        return schedule(Why.LOST, now);
    }

    /** The network is still there but no longer carries the internet. */
    synchronized long capabilityLost(long now) {
        return schedule(Why.CAPABILITY_LOST, now);
    }

    /**
     * The retry that was scheduled is due, so the caller may request again.
     *
     * <p>False when the retry was cancelled, the state was closed, or it is not
     * time yet. A true result consumes the retry: two runnables racing for it
     * cannot both request.
     */
    synchronized boolean retryReady(long now) {
        if (closed || !retryPending || now < retryAt) {
            return false;
        }
        retryPending = false;
        attempt++;
        return true;
    }

    /** Forget the network and the retry; close() must not leave a request behind. */
    synchronized void close() {
        closed = true;
        retryPending = false;
    }

    /** Whether {@link #close()} has run; a closed state never retries again. */
    synchronized boolean closed() {
        return closed;
    }

    /** The line {@code dumpsys} prints for the egress, or the network it has. */
    synchronized String describe(String network, long now) {
        if (network != null) {
            return network;
        }
        if (closed) {
            return "none (stopped)";
        }
        if (!started) {
            return "none (no listener started)";
        }
        return "none: " + reason(now);
    }

    /**
     * Why there is no usable cellular egress, for the timeout a client sees.
     * Never empty once {@link #start()} has run.
     */
    synchronized String reason(long now) {
        if (!retryPending) {
            return why.text;
        }
        long left = retryAt - now;
        return why.text + (left <= 0 ? ", retrying now"
                : ", retrying in " + ((left + 999) / 1000) + "s");
    }

    private long schedule(Why reason, long now) {
        if (closed || retryPending) {
            return -1;
        }
        why = reason;
        long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
        retryPending = true;
        retryAt = now + delay;
        return delay;
    }
}
