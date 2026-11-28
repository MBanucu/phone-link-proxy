package org.phonelink.proxy;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The start a reboot and an app update share: the listener comes back by itself
 * only when the user asked for that and there is a stored port to come back on.
 *
 * <p>An update is the case a sticky restart does not cover. {@code adb install
 * -r} replaces the APK and kills the process, and the system does not restart a
 * service that a package update stopped; {@code MY_PACKAGE_REPLACED} is the
 * only notice the app gets.
 */
final class AutoStart {

    private AutoStart() {
    }

    static void resume(Context context) {
        if (!ResumePolicy.shouldStart(
                ConfigStore.autoStart(context), ConfigStore.isConfigured(context))) {
            return;
        }
        Intent start = new Intent(context, ProxyService.class)
                .setAction(ProxyService.ACTION_START);
        try {
            context.startForegroundService(start);
        } catch (IllegalStateException e) {
            // Android 15 refuses some foreground starts from a receiver. A
            // plain start rides the receiver's temporary allowlist instead;
            // the service promotes itself in onStartCommand, and stops itself
            // if that is refused too.
            Log.w(ProxyService.TAG, "no foreground start, trying a plain start", e);
            try {
                context.startService(start);
            } catch (IllegalStateException again) {
                Log.w(ProxyService.TAG, "cannot start the listener", again);
            }
        }
    }
}
