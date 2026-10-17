package com.newtrip.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * عداد مدمج بتصميم لوحة الهجين: قوس أزرق يسار (البطارية) وقوس برتقالي يمين (الوقود)،
 * وفي الوسط «TOTAL COMBINED RANGE» بالكيلومتر، وتحته مدى الكهرباء ومدى البنزين.
 * كل قوس يمتلئ من الأسفل إلى الأعلى بنسبته.
 */
final class CombinedGauge extends View {
    // الزوايا بنظام أندرويد: 0 = الساعة 3، موجبة مع عقارب الساعة. الفتحة في الأسفل وفي الأعلى.
    // الفتحة السفلية واسعة (حوالي 80°) كما في التصميم المرجعي، وفجوة صغيرة في الأعلى.
    private static final float LEFT_START = 130f, LEFT_SWEEP = 136f;     // من أسفل اليسار إلى أعلى اليسار
    private static final float RIGHT_START = 50f, RIGHT_SWEEP = -136f;   // من أسفل اليمين إلى أعلى اليمين
    private static final int BLUE = 0xFF29B6F6, ORANGE = 0xFFFFA726;     // بطارية = أزرق ، وقود = برتقالي

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private float soc = Float.NaN, fuel = Float.NaN;           // الهدف
    private float socShown = Float.NaN, fuelShown = Float.NaN; // المعروض (حركة ناعمة)
    private float evKm = Float.NaN, fuelKm = Float.NaN;

    CombinedGauge(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Ui.CARD2);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        txt.setTextAlign(Paint.Align.CENTER);
    }

    /** socPct/fuelPct بالنسبة المئوية، evKm/fuelKm مدى كل مصدر بالكيلومتر (null = غير متوفر). */
    void setValues(Float socPct, Float fuelPct, Float evRangeKm, Float fuelRangeKm) {
        soc = socPct == null ? Float.NaN : clamp(socPct);
        fuel = fuelPct == null ? Float.NaN : clamp(fuelPct);
        evKm = evRangeKm == null ? Float.NaN : evRangeKm;
        fuelKm = fuelRangeKm == null ? Float.NaN : fuelRangeKm;
        if (Float.isNaN(socShown) || Float.isNaN(soc)) socShown = soc;
        if (Float.isNaN(fuelShown) || Float.isNaN(fuel)) fuelShown = fuel;
        invalidate();
    }

    private static boolean differs(float a, float b) {
        if (Float.isNaN(a) && Float.isNaN(b)) return false;
        return a != b;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(100f, v)); }

    private static float ease(float shown, float target) {
        if (Float.isNaN(target)) return Float.NaN;
        if (Float.isNaN(shown) || Math.abs(target - shown) < 0.05f) return target;
        return shown + (target - shown) * 0.2f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float stroke = s * 0.055f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = (s - stroke) / 2f;
        box.set(cx - r, cy - r, cx + r, cy + r);

        socShown = ease(socShown, soc);
        fuelShown = ease(fuelShown, fuel);
        if (differs(socShown, soc) || differs(fuelShown, fuel)) postInvalidateOnAnimation();

        track.setStrokeWidth(stroke);
        c.drawArc(box, LEFT_START, LEFT_SWEEP, false, track);
        c.drawArc(box, RIGHT_START, RIGHT_SWEEP, false, track);

        arc.setStrokeWidth(stroke);
        if (!Float.isNaN(socShown) && socShown > 0.5f) {
            arc.setColor(BLUE);
            c.drawArc(box, LEFT_START, LEFT_SWEEP * socShown / 100f, false, arc);
        }
        if (!Float.isNaN(fuelShown) && fuelShown > 0.5f) {
            arc.setColor(ORANGE);
            c.drawArc(box, RIGHT_START, RIGHT_SWEEP * fuelShown / 100f, false, arc);
        }

        // النسب المئوية أعلى اليسار واليمين
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setColor(BLUE);
        txt.setTextSize(r * 0.20f);
        c.drawText(Float.isNaN(socShown) ? "—" : Math.round(socShown) + "%", cx - r * 0.50f, cy - r * 0.42f, txt);
        txt.setColor(ORANGE);
        c.drawText(Float.isNaN(fuelShown) ? "—" : Math.round(fuelShown) + "%", cx + r * 0.50f, cy - r * 0.42f, txt);
        txt.setTypeface(Typeface.DEFAULT);
        txt.setColor(Ui.MUTED);
        txt.setTextSize(r * 0.075f);
        c.drawText(Tr.t("BATTERY"), cx - r * 0.50f, cy - r * 0.68f, txt);
        c.drawText(Tr.t("FUEL"), cx + r * 0.50f, cy - r * 0.68f, txt);

        // الوسط: المدى الكلي
        txt.setTextSize(r * 0.075f);
        c.drawText(Tr.t("TOTAL COMBINED RANGE"), cx, cy - r * 0.12f, txt);
        boolean both = !Float.isNaN(evKm) && !Float.isNaN(fuelKm);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setColor(Ui.TEXT);
        txt.setTextSize(r * 0.22f);
        c.drawText(both ? Math.round(evKm + fuelKm) + " km" : "—", cx, cy + r * 0.12f, txt);
        txt.setTypeface(Typeface.DEFAULT);
        txt.setColor(Ui.MUTED);
        txt.setTextSize(r * 0.07f);
        c.drawText(Tr.t("EST. REMAINING"), cx, cy + r * 0.26f, txt);

        // مدى الكهرباء ومدى البنزين تحت الوسط
        drawRange(c, "EV Range", evKm, BLUE, cx - r * 0.28f, cy + r * 0.56f, r);
        drawRange(c, "HV Fuel Range", fuelKm, ORANGE, cx + r * 0.28f, cy + r * 0.56f, r);
    }

    private void drawRange(Canvas c, String label, float km, int color, float x, float y, float r) {
        txt.setTypeface(Typeface.DEFAULT);
        txt.setColor(Ui.MUTED);
        txt.setTextSize(r * 0.07f);
        c.drawText(Tr.t(label), x, y, txt);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setColor(color);
        txt.setTextSize(r * 0.11f);
        c.drawText(Float.isNaN(km) ? "—" : Math.round(km) + " km", x, y + r * 0.13f, txt);
    }
}
