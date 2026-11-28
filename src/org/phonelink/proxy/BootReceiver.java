package org.phonelink.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the listener back after a reboot when the user asked for that.
 *
 * <p>Only with {@code auto_start}: a package that was installed but never used,
 * or used with the option off, does not open a port by itself. The port itself
 * stays shut until the phone is on the configured Wi-Fi, so a restart here
 * costs a notification and nothing else.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // LOCKED_BOOT_COMPLETED is the one that arrives before the first
        // unlock; on this phone it only does so because DokeOS allowlists the
        // app.
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        // Before the auto-start check: that this broadcast arrived is the fact
        // the DokeOS gate is judged by, whether or not a listener should start.
        ConfigStore.noteBoot(context);
        AutoStart.resume(context);
    }
}
