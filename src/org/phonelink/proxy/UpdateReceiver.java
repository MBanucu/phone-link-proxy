package org.phonelink.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the listener back after the app is replaced, when the user asked for
 * that.
 *
 * <p>An update kills the process, and a sticky restart is not guaranteed across
 * a package replacement, so without this the port stays shut until somebody
 * opens the app or sends a start. The decision is the boot receiver's: only
 * with {@code auto_start} on and a stored port, and the SSID gate still decides
 * whether the port actually opens.
 *
 * <p>Not a boot: the vendor gate's boot observation stays with
 * {@link BootReceiver}.
 */
public final class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }
        AutoStart.resume(context);
    }
}
