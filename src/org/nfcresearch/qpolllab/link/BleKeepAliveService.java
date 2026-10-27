package org.nfcresearch.qpolllab.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import org.nfcresearch.qpolllab.R;
import org.nfcresearch.qpolllab.ui.MainActivity;

/**
 * Foreground keep-alive while BLE carousel or calendar auto-push is enabled.
 * Actual push work is driven by MainActivity timers; this service only keeps the process.
 */
public final class BleKeepAliveService extends Service {
    public static final String CHANNEL = "ble-keepalive";
    public static final int NOTIFY_ID = 398;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "蓝牙保持连接", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("轮播或日程自动推送时保持应用运行");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("墨水屏蓝牙")
                .setContentText("轮播 / 日程自动推送运行中")
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else
                startForeground(NOTIFY_ID, notification);
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
