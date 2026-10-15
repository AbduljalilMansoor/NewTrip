package com.newtrip.app;

import android.content.Context;
import android.content.SharedPreferences;

/** إعدادات التطبيق (SharedPreferences) مع قيمها الافتراضية. */
final class Settings {
    private Settings() { }

    /** سطر حقوق النشر (يظهر تحت زر Start وفي الإعدادات). عدّله هنا. */
    static final String COPYRIGHT = "© 2026 New Trip. All rights reserved.";

    static final String K_LIMIT = "storage_limit_mb", K_AUTO_END = "auto_end", K_AUTO_EXPORT = "auto_export",
            K_AUTO_UPDATE = "auto_update", K_LAST_ALERT = "storage_alert_ts", K_LAST_UPDATE_CHECK = "last_update_check";
    static final int DEFAULT_LIMIT_MB = 100;
    static final int EXPORT_OFF = 0, EXPORT_PDF = 1, EXPORT_XLSX = 2;

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** الحد الأقصى لحجم ملفات الرحلات بالميغابايت (الافتراضي 100). */
    static int storageLimitMb(Context c) { return Math.max(1, p(c).getInt(K_LIMIT, DEFAULT_LIMIT_MB)); }
    static void setStorageLimitMb(Context c, int mb) { p(c).edit().putInt(K_LIMIT, mb).apply(); }

    /** إنهاء الرحلة تلقائياً عند إطفاء السيارة (الافتراضي: مفعّل). */
    static boolean autoEnd(Context c) { return p(c).getBoolean(K_AUTO_END, true); }
    static void setAutoEnd(Context c, boolean v) { p(c).edit().putBoolean(K_AUTO_END, v).apply(); }

    /** تصدير تلقائي عند انتهاء كل رحلة: 0 = إيقاف ، 1 = PDF ، 2 = Excel. */
    static int autoExport(Context c) { return p(c).getInt(K_AUTO_EXPORT, EXPORT_OFF); }
    static void setAutoExport(Context c, int v) { p(c).edit().putInt(K_AUTO_EXPORT, v).apply(); }

    /** تحديث تلقائي (الافتراضي: مفعّل). */
    static boolean autoUpdate(Context c) { return p(c).getBoolean(K_AUTO_UPDATE, true); }
    static void setAutoUpdate(Context c, boolean v) { p(c).edit().putBoolean(K_AUTO_UPDATE, v).apply(); }
}
