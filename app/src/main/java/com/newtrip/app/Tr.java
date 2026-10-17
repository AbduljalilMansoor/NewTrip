package com.newtrip.app;

import java.util.Locale;

/**
 * ترجمة واجهة التطبيق (إنجليزي ← عربي). النص الإنجليزي هو المفتاح.
 *  - Tr.t("Settings")            : نص ثابت.
 *  - Tr.f("%s trips", n)         : قالب بمتغيرات (القالب العربي في TrData بنفس ترتيب المتغيرات، ويمكن استخدام %1$s للترتيب).
 * إن لم توجد ترجمة يظهر النص الإنجليزي (لا أخطاء). اللغة تُحدَّد من Settings.applyLanguage().
 * الأرقام والوحدات (km, kWh, L/100km…) تبقى بالأحرف اللاتينية في اللغتين.
 */
final class Tr {
    private Tr() { }

    /** true = العربية. */
    static volatile boolean ar;

    static boolean isArabic() { return ar; }

    static String t(String en) {
        if (!ar || en == null) return en;
        String v = TrData.AR.get(en);
        return v != null ? v : en;
    }

    static String f(String en, Object... args) {
        String tpl = ar ? t(en) : en;
        try {
            return String.format(Locale.US, tpl, args);
        } catch (RuntimeException e) {
            try { return String.format(Locale.US, en, args); } catch (RuntimeException e2) { return en; }
        }
    }

    /** يترجم كل عنصر في مصفوفة (لقوائم الاختيار). */
    static String[] all(String[] en) {
        String[] out = new String[en.length];
        for (int i = 0; i < en.length; i++) out[i] = t(en[i]);
        return out;
    }
}
