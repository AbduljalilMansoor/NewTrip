package com.newtrip.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * خط زمني لرحلة واحدة بثلاثة مسارات تشترك في محور الزمن:
 * (1) البطارية % مع هدف SOC وتظليل فترات Forced وعلامات التبديل، (2) السرعة، (3) RPM مع استهلاك الوقود لتر/ساعة.
 * الضغط على أي نقطة يعرض قيمها.
 */
final class TimelineView extends View {
    interface OnTap { void on(String tip); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private DashModel.Timeline tl;
    private OnTap cb;
    private float tMax = 1, rpmMax = 1000, fuelMax = 5, spdMax = 40;
    private int selected = -1;

    private static final int BLUE = 0xFF29B6F6, ORANGE = 0xFFFFA726, PURPLE = 0xFFAB47BC;

    TimelineView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setData(DashModel.Timeline t, OnTap listener) {
        tl = t; cb = listener; selected = -1;
        if (t != null && !t.empty()) {
            tMax = 1; rpmMax = 1000; fuelMax = 5; spdMax = 40;
            for (int i = 0; i < t.t.length; i++) {
                tMax = Math.max(tMax, t.t[i] + 0.3f);
                if (!Float.isNaN(t.rpm[i])) rpmMax = Math.max(rpmMax, t.rpm[i]);
                if (!Float.isNaN(t.fuelLph[i])) fuelMax = Math.max(fuelMax, t.fuelLph[i]);
                if (!Float.isNaN(t.speed[i])) spdMax = Math.max(spdMax, t.speed[i]);
            }
            rpmMax = (float) Math.ceil(rpmMax / 1000f) * 1000f;
            fuelMax = (float) Math.ceil(fuelMax / 5f) * 5f;
            spdMax = (float) Math.ceil(spdMax / 20f) * 20f;
        }
        invalidate();
    }

    private float L() { return 54 * d; }
    private float R() { return 46 * d; }
    private float xOf(float t) { return L() + t / tMax * (getWidth() - L() - R()); }

    // حدود المسارات: {top, bottom}
    private float[] track(int i) {
        float top = 20 * d, bottom = getHeight() - 26 * d, gap = 20 * d;
        float avail = bottom - top - 2 * gap;
        float[] share = {0.40f, 0.26f, 0.34f};
        float y = top;
        for (int k = 0; k < i; k++) y += avail * share[k] + gap;
        return new float[]{y, y + avail * share[i]};
    }

    private float yOf(int tr, float v, float lo, float hi) {
        float[] b = track(tr);
        return b[1] - (Math.max(lo, Math.min(hi, v)) - lo) / (hi - lo) * (b[1] - b[0]);
    }

    private void frame(Canvas c, int tr, String title, float hi, int axisColor, String fmt) {
        float[] b = track(tr);
        p.setStrokeWidth(1);
        for (int i = 0; i <= 2; i++) {
            float y = b[1] - (b[1] - b[0]) * i / 2f;
            p.setColor(Ui.LINE);
            c.drawLine(L(), y, getWidth() - R(), y, p);
            p.setColor(axisColor); p.setTextSize(10 * d); p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Ui.num(hi * i / 2f, 0), L() - 5 * d, y + 3.5f * d, p);
        }
        p.setColor(Ui.MUTED); p.setTextSize(11 * d); p.setTextAlign(Paint.Align.LEFT);
        c.drawText(title, L(), b[0] - 6 * d, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (tl == null || tl.empty()) {
            p.setColor(Ui.MUTED); p.setTextSize(15 * d); p.setTextAlign(Paint.Align.CENTER);
            c.drawText(Tr.t("Pick a trip recorded after this update to see its timeline"), getWidth() / 2f, getHeight() / 2f, p);
            return;
        }
        int n = tl.t.length;
        float half = Math.max(0.15f, n > 1 ? (tl.t[1] - tl.t[0]) / 2f : 0.25f);

        // ---- المسار 1: البطارية والهدف و Forced ----
        frame(c, 0, Tr.t("Battery %   (orange band = Forced power saving, orange line = target)"), 100, BLUE, "");
        float[] b0 = track(0);
        for (int i = 0; i < n; i++) {
            if (!tl.forced[i]) continue;
            p.setColor(0x33FFA726);
            c.drawRect(xOf(tl.t[i] - half), b0[0], xOf(tl.t[i] + half), b0[1], p);
            if (!Float.isNaN(tl.target[i])) {
                p.setColor(ORANGE); p.setStrokeWidth(2.5f * d);
                float y = yOf(0, tl.target[i], 0, 100);
                c.drawLine(xOf(tl.t[i] - half), y, xOf(tl.t[i] + half), y, p);
            }
        }
        line(c, 0, tl.soc, 0, 100, BLUE);
        p.setTextAlign(Paint.Align.LEFT); p.setTextSize(11 * d);
        for (int i = 0; i < tl.evT.length; i++) {
            float x = xOf(tl.evT[i]);
            p.setColor(tl.evForced[i] ? ORANGE : Ui.EV); p.setStrokeWidth(1.5f * d);
            c.drawLine(x, b0[0], x, b0[1], p);
            String t = tl.evForced[i] ? Tr.t("Forced ON") + (Float.isNaN(tl.evTarget[i]) ? "" : " → " + Ui.num(tl.evTarget[i], 0) + "%")
                    + (Float.isNaN(tl.evSoc[i]) ? "" : " @ " + Ui.num(tl.evSoc[i], 0) + "%") : Tr.t("Smart");
            c.drawText(t, x + 4 * d, b0[0] + 13 * d + (i % 2) * 13 * d, p);
        }

        // ---- المسار 2: السرعة ----
        frame(c, 1, Tr.t("Speed km/h"), spdMax, Ui.TEXT, "");
        line(c, 1, tl.speed, 0, spdMax, 0xFFB0BEC5);

        // ---- المسار 3: RPM + الوقود ----
        frame(c, 2, Tr.t("Engine RPM (purple, left)   Fuel flow L/h (orange bars, right)"), rpmMax, PURPLE, "");
        float[] b2 = track(2);
        for (int i = 0; i < n; i++) {
            if (Float.isNaN(tl.fuelLph[i])) continue;
            p.setColor(0x99FFA726);
            c.drawRect(xOf(tl.t[i] - half), yOf(2, tl.fuelLph[i], 0, fuelMax), xOf(tl.t[i] + half), b2[1], p);
        }
        line(c, 2, tl.rpm, 0, rpmMax, PURPLE);
        p.setColor(ORANGE); p.setTextSize(10 * d); p.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i <= 2; i++) c.drawText(Ui.num(fuelMax * i / 2f, 0), getWidth() - R() + 5 * d, b2[1] - (b2[1] - b2[0]) * i / 2f + 3.5f * d, p);

        // محور الزمن
        p.setColor(Ui.MUTED); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(10 * d);
        for (int i = 0; i <= 6; i++) c.drawText(Ui.num(tMax * i / 6f, 0) + " min", xOf(tMax * i / 6f), getHeight() - 8 * d, p);

        if (selected >= 0) {
            float x = xOf(tl.t[selected]);
            p.setColor(Ui.TEXT); p.setStrokeWidth(1.5f * d);
            c.drawLine(x, track(0)[0], x, track(2)[1], p);
        }
    }

    private void line(Canvas c, int tr, float[] v, float lo, float hi, int color) {
        p.setColor(color); p.setStrokeWidth(2.5f * d);
        float px = Float.NaN, py = Float.NaN;
        for (int i = 0; i < v.length; i++) {
            if (Float.isNaN(v[i])) { px = Float.NaN; continue; }
            float x = xOf(tl.t[i]), y = yOf(tr, v[i], lo, hi);
            if (!Float.isNaN(px)) c.drawLine(px, py, x, y, p);
            px = x; py = y;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && tl != null && !tl.empty() && cb != null) {
            int best = 0; float bd = Float.MAX_VALUE;
            for (int i = 0; i < tl.t.length; i++) {
                float dd = Math.abs(xOf(tl.t[i]) - e.getX());
                if (dd < bd) { bd = dd; best = i; }
            }
            selected = best;
            invalidate();
            cb.on(Tr.f("%s min   ·   battery %s%%", Ui.num(tl.t[best], 1), Ui.num(tl.soc[best], 0))
                    + (tl.forced[best] ? "   ·   " + Tr.f("Forced, target %s%%", Ui.num(tl.target[best], 0)) : "   ·   " + Tr.t("Smart"))
                    + "   ·   " + Ui.num(tl.speed[best], 0) + " km/h"
                    + "   ·   " + (Float.isNaN(tl.rpm[best]) ? Tr.t("engine off") : Ui.num(tl.rpm[best], 0) + " RPM")
                    + "   ·   " + Tr.f("fuel %s L/h", Ui.num(tl.fuelLph[best], 1)));
        }
        return true;
    }
}
