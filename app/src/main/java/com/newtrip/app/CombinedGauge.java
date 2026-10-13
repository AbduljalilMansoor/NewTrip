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
    private static final float LEFT_START = 112f, LEFT_SWEEP = 152f;     // من أسفل اليسار إلى أعلى اليسار
    private static final float RIGHT_START = 68f, RIGHT_SWEEP = -152f;   // من أسفل اليمين إلى أعلى اليمين

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
        float stroke = s * 0.075f;
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
            arc.setColor(Ui.EV);
            c.drawArc(box, LEFT_START, LEFT_SWEEP * socShown / 100f, false, arc);
        }
        if (!Float.isNaN(fuelShown) && fuelShown > 0.5f) {
            arc.setColor(fuelShown < 15f ? Ui.BAD : Ui.ENG);
            c.drawArc(box, RIGHT_START, RIGHT_SWEEP * fuelShown / 100f, false, arc);
        }

        // النسب المئوية أعلى اليسار واليمين
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setColor(Ui.EV);
        txt.setTextSize(r * 0.34f);
        c.drawText(Float.isNaN(socShown) ? "—" : Math.round(socShown) + "%", cx - r * 0.50f, cy - r * 0.38f, txt);
        txt.setColor(Ui.ENG);
        c.drawText(Float.isNaN(fuelShown) ? "—" : Math.round(fuelShown) + "%", cx + r * 0.50f, cy - r * 0.38f, txt);
        txt.setTypeface(Typeface.DEFAULT);
        txt.setColor(Ui.MUTED);
        txt.setTextSize(r * 0.12f);
        c.drawText("BATTERY", cx - r * 0.50f, cy - r * 0.60f, txt);
        c.drawText("FUEL", cx + r * 0.50f, cy - r * 0.60f, txt);

        // الوسط: المدى الكلي
        txt.setTextSize(r * 0.115f);
        c.drawText("TOTAL COMBINED RANGE", cx, cy - r * 0.08f, txt);
        boolean both = !Float.isNaN(evKm) && !Float.isNaN(fuelKm);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setColor(Ui.TEXT);
        txt.setTextSize(r * 0.36f);
        c.drawText(both ? Math.round(evKm + fuelKm) + " km" : "—", cx, cy + r * 0.26f, txt);
        txt.setTypeface(Typeface.DEFAULT);
        txt.setColor(Ui.MUTED);
        txt.setTextSize(r * 0.105f);
        c.drawText("EST. REMAINING", cx, cy + r * 0.42f, txt);

        // مدى الكهرباء ومدى البنزين تحت الوسط
        drawRange(c, "EV Range", evKm, Ui.EV, cx - r * 0.42f, cy + r * 0.66f, r);
        drawRange(c, "HV Fuel Range", fuelKm, Ui.ENG, cx + r * 0.42f, cy + r * 0.66f, r);
    }

    private void drawRange(Canvas c, String label, float km, int color, float x, float y, float r) {
        txt.setTypeface(Typeface.DEFAULT);
        txt.setColor(Ui.MUTED);
        txt.setTextSize(r * 0.10f);
        c.drawText(label, x, y, txt);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setColor(color);
        txt.setTextSize(r * 0.17f);
        c.drawText(Float.isNaN(km) ? "—" : Math.round(km) + " km", x, y + r * 0.19f, txt);
    }
}
