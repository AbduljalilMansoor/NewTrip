package com.newtrip.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;

/**
 * مقاسات النافذة لشاشة السيارة: النافذة شبه كاملة (تملأ الشاشة تقريباً مع هامش صغير)،
 * والمحتوى بحجمه الطبيعي بلا تصغير، فيظهر كتطبيق شاشة سيارة لا كتطبيق هاتف.
 * للتعديل: WINDOW_W / WINDOW_H (نسبة من الشاشة) و FACTOR (1.0 = بلا تصغير للمحتوى).
 */
final class UiScale {
    private UiScale() { }

    static final float WINDOW_W = 0.96f, WINDOW_H = 0.94f;
    static final float FACTOR = 1.0f;

    /** يُستدعى من attachBaseContext: يضبط كثافة الشاشة فتتغير كل وحدات dp/sp معاً (1.0 = بلا تغيير). */
    static Context wrap(Context base) {
        Settings.applyLanguage(base);   // كل شاشة تمر من هنا فتُضبط اللغة قبل بناء الواجهة
        if (FACTOR == 1.0f) return base;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.densityDpi = Math.round(c.densityDpi * FACTOR);
        return base.createConfigurationContext(c);
    }

    /** يجعل النافذة تملأ معظم الشاشة في الوسط (تتطلب FloatingTheme في المانيفست). */
    static void applyWindow(Activity a) {
        Point p = new Point();
        a.getWindowManager().getDefaultDisplay().getRealSize(p);
        Window w = a.getWindow();
        w.setLayout(Math.round(p.x * WINDOW_W), Math.round(p.y * WINDOW_H));
        w.setGravity(Gravity.CENTER);
        w.setBackgroundDrawable(new ColorDrawable(Ui.BG));
    }
}
