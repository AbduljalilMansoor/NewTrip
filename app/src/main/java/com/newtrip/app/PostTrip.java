package com.newtrip.app;

import android.content.Context;
import android.util.Log;

import java.io.File;

/** مهام ما بعد انتهاء الرحلة (على خيط خلفي): تصدير تلقائي، فحص حجم الملفات، وتحديث التطبيق إن كانت السيارة مطفأة. */
final class PostTrip {
    private PostTrip() { }

    static void run(Context ctx, File tripFile, boolean carOff) {
        try {
            int mode = Settings.autoExport(ctx);
            if (tripFile != null && mode != Settings.EXPORT_OFF) {
                TripSession s = SessionStore.read(tripFile);
                if (s != null) {
                    if (mode == Settings.EXPORT_PDF) Exporter.exportPdf(ctx, s);
                    else Exporter.exportXlsx(ctx, java.util.Collections.singletonList(s));
                }
            }
        } catch (Throwable t) { Log.e("PostTrip", "export", t); }
        try { StorageMonitor.check(ctx, false); } catch (Throwable t) { Log.e("PostTrip", "storage", t); }
        if (carOff) {
            try { Updater.autoUpdateIfNeeded(ctx); } catch (Throwable t) { Log.e("PostTrip", "update", t); }
        }
    }
}
