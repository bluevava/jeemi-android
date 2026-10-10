package io.jeemi.android.testprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

/** A second APK/UID with a real, local-only TUN. Never included in product APKs. */
public class CompetingVpnService extends VpnService {
    static volatile boolean active;
    private ParcelFileDescriptor tun;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            closeTunnel();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("probe", "VPN test", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "probe")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("VPN test").build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        else startForeground(101, notification);
        if (tun == null) tun = new Builder().setSession("Jeemi competing test VPN")
            .addAddress("192.0.2.1", 30).addRoute("198.51.100.0", 24).establish();
        active = tun != null;
        return START_NOT_STICKY;
    }

    private synchronized void closeTunnel() {
        active = false;
        if (tun != null) { try { tun.close(); } catch (Exception ignored) { } tun = null; }
    }
    @Override public void onRevoke() { closeTunnel(); super.onRevoke(); }
    @Override public void onDestroy() {
        closeTunnel();
        super.onDestroy();
    }
}
