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
    static final int EXPORT_OFF = 0, EXPORT_PDF = 1, EXPORT_CSV = 2;

    static final String K_LANG = "lang", K_LANG_CHOSEN = "lang_chosen", K_TRIP_MODE = "trip_mode";
    static final int LANG_AUTO = 0, LANG_EN = 1, LANG_AR = 2;
    /** MODE_MANUAL: تبدأ يدوياً وتنتهي يدوياً أو عند إطفاء السيارة. MODE_AUTO: تبدأ وتنتهي تلقائياً. */
    static final int MODE_MANUAL = 0, MODE_AUTO = 1;

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** نمط الرحلات: 0 = يدوي (الحالي)، 1 = تلقائي (تبدأ عند تشغيل السيارة والحركة، وتنتهي عند الإطفاء). */
    static int tripMode(Context c) { return p(c).getInt(K_TRIP_MODE, MODE_MANUAL); }
    static void setTripMode(Context c, int m) { p(c).edit().putInt(K_TRIP_MODE, m).apply(); }

    // ---- اللغة ----
    /** 0 = تلقائي (لغة شاشة السيارة)، 1 = English، 2 = العربية. */
    static int language(Context c) { return p(c).getInt(K_LANG, LANG_AUTO); }
    static void setLanguage(Context c, int l) {
        p(c).edit().putInt(K_LANG, l).putBoolean(K_LANG_CHOSEN, true).apply();
        applyLanguage(c);
    }

    /** لغة النظام الحالية (لغة شاشة السيارة) إن كانت ar أو en، وإلا null (غير مدعومة/غير مقروءة). */
    static String systemLanguage() {
        String l = java.util.Locale.getDefault().getLanguage();
        return "ar".equals(l) || "en".equals(l) ? l : null;
    }

    /** يضبط Tr حسب الإعداد. تلقائي: يتبع لغة شاشة السيارة؛ وإن لم تكن عربية أو إنجليزية تبقى الإنجليزية حتى يختار المستخدم. */
    static void applyLanguage(Context c) {
        int l = language(c);
        if (l == LANG_EN) Tr.ar = false;
        else if (l == LANG_AR) Tr.ar = true;
        else Tr.ar = "ar".equals(systemLanguage());
    }

    /** هل نطلب من المستخدم اختيار اللغة يدوياً؟ (تلقائي + لغة السيارة غير مقروءة/غير مدعومة + لم يختر بعد) */
    static boolean languageNeedsManualChoice(Context c) {
        return language(c) == LANG_AUTO && systemLanguage() == null && !p(c).getBoolean(K_LANG_CHOSEN, false);
    }

    /** الحد الأقصى لحجم ملفات الرحلات بالميغابايت (الافتراضي 100). */
    static int storageLimitMb(Context c) { return Math.max(1, p(c).getInt(K_LIMIT, DEFAULT_LIMIT_MB)); }
    static void setStorageLimitMb(Context c, int mb) { p(c).edit().putInt(K_LIMIT, mb).apply(); }

    /** إنهاء الرحلة تلقائياً عند إطفاء السيارة (الافتراضي: مفعّل). */
    static boolean autoEnd(Context c) { return p(c).getBoolean(K_AUTO_END, true); }
    static void setAutoEnd(Context c, boolean v) { p(c).edit().putBoolean(K_AUTO_END, v).apply(); }

    /** تصدير تلقائي عند انتهاء كل رحلة: 0 = إيقاف ، 1 = PDF ، 2 = CSV. */
    static int autoExport(Context c) { return p(c).getInt(K_AUTO_EXPORT, EXPORT_OFF); }
    static void setAutoExport(Context c, int v) { p(c).edit().putInt(K_AUTO_EXPORT, v).apply(); }

    /** تحديث تلقائي (الافتراضي: مفعّل). */
    static boolean autoUpdate(Context c) { return p(c).getBoolean(K_AUTO_UPDATE, true); }
    static void setAutoUpdate(Context c, boolean v) { p(c).edit().putBoolean(K_AUTO_UPDATE, v).apply(); }
}
