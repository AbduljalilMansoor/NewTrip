package com.newtrip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.util.Locale;

/** يراقب حجم ملفات الرحلات (والتصديرات) وينبّه السائق عند تجاوز الحد المضبوط (الافتراضي 100MB). */
final class StorageMonitor {
    private StorageMonitor() { }

    static final String ALERT_CH = "newtrip_alerts";
    static final int ALERT_ID = 3, ENDED_ID = 2;
    private static final long REALERT_MS = 12L * 3600 * 1000;   // لا يكرر التنبيه أكثر من مرة كل 12 ساعة

    static long dirSize(File d) {
        long n = 0;
        File[] fs = d == null ? null : d.listFiles();
        if (fs == null) return 0;
        for (File f : fs) n += f.isDirectory() ? dirSize(f) : f.length();
        return n;
    }

    /** حجم ملفات الرحلات + ملفات التصدير بالبايت. */
    static long usedBytes(Context c) {
        return dirSize(new SessionStore(c).historyDir()) + dirSize(Exporter.exportsDir(c));
    }

    static long limitBytes(Context c) { return Settings.storageLimitMb(c) * 1024L * 1024L; }

    static boolean isOver(Context c) { return usedBytes(c) > limitBytes(c); }

    static String human(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= 1024L * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%d KB", Math.max(0, b / 1024));
    }

    static void ensureChannel(Context c) {
        c.getSystemService(NotificationManager.class)
                .createNotificationChannel(new NotificationChannel(ALERT_CH, "New Trip alerts", NotificationManager.IMPORTANCE_DEFAULT));
    }

    /** يرسل إشعاراً إن تجاوز الحجم الحد (مرة كل 12 ساعة). force = تجاهل المهلة. */
    static void check(Context c, boolean force) {
        long used = usedBytes(c), limit = limitBytes(c);
        if (used <= limit) return;
        long last = Settings.p(c).getLong(Settings.K_LAST_ALERT, 0);
        long now = System.currentTimeMillis();
        if (!force && now - last < REALERT_MS) return;
        Settings.p(c).edit().putLong(Settings.K_LAST_ALERT, now).apply();
        ensureChannel(c);
        PendingIntent pi = PendingIntent.getActivity(c, 3, new Intent(c, SettingsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, ALERT_CH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(Tr.t("Trip storage limit exceeded"))
                .setContentText(Tr.f("Trip files use %s (limit %s MB). Export or delete old trips.", human(used), Settings.storageLimitMb(c)))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(ALERT_ID, n);
    }
}
