package org.nfcresearch.qpolllab.link;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.nfcresearch.qpolllab.settings.AppSettings;
import org.nfcresearch.qpolllab.ui.MainActivity;

/** Opens the app after boot when BLE mode has a saved device. OEM autostart must allow it. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !"android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction()))
            return;
        AppSettings settings = new AppSettings(context);
        if (!settings.bleMode() || settings.bleAddress().isEmpty()) return;
        if (!settings.carouselEnabled() && !settings.calendarAutoPush()) return;
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
    }
}
