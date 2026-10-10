package com.newtrip.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;

/**
 * يصغّر New Trip بنسبة 30%: النافذة نفسها تصبح 70% من الشاشة (تظهر في الوسط)، وكل المقاسات داخلها
 * (الخطوط، الهوامش، العدادات، الجداول…) تصغر بالنسبة نفسها، فيبقى التخطيط كما هو لكن أصغر.
 * لتغيير النسبة عدّل FACTOR فقط (0.7 = أصغر بـ 30%، 1.0 = بلا تصغير).
 */
final class UiScale {
    private UiScale() { }

    static final float FACTOR = 0.70f;

    /** يُستدعى من attachBaseContext: يصغّر كثافة الشاشة فتصغر كل وحدات dp/sp معاً. */
    static Context wrap(Context base) {
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.densityDpi = Math.round(c.densityDpi * FACTOR);
        return base.createConfigurationContext(c);
    }

    /** يجعل النافذة 70% من الشاشة في الوسط (تتطلب FloatingTheme في المانيفست). */
    static void applyWindow(Activity a) {
        Point p = new Point();
        a.getWindowManager().getDefaultDisplay().getRealSize(p);
        Window w = a.getWindow();
        w.setLayout(Math.round(p.x * FACTOR), Math.round(p.y * FACTOR));
        w.setGravity(Gravity.CENTER);
        w.setBackgroundDrawable(new ColorDrawable(Ui.BG));
    }
}
