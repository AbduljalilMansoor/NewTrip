package com.newtrip.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** عند إقلاع الشاشة: نكمل رحلة لم تُنهَ، أو نشغّل مراقبة الرحلات التلقائية. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // رحلة لم تُنهَ، أو النمط التلقائي (يبدأ المراقبة من أول تشغيل)
        if (new SessionStore(ctx).loadActive() != null || Settings.tripMode(ctx) == Settings.MODE_AUTO) {
            TripService.start(ctx);
        }
    }
}
