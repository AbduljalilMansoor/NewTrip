package com.newtrip.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** مخطط تشتتي: محوران بتدريج تلقائي، ألوان حسب الفئة، وحجم النقطة اختياري. الضغط على نقطة يعيد مرجعها. */
final class ScatterView extends View {
    interface OnPoint { void onPoint(int ref); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private DashModel.Scat s;
    private String xl = "", yl = "";
    private boolean big;
    private OnPoint cb;
    private float x0, x1, y0, y1;
    private int selected = -1;

    ScatterView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setData(DashModel.Scat data, String xLabel, String yLabel, boolean bigPoints, OnPoint listener) {
        s = data; xl = xLabel; yl = yLabel; big = bigPoints; cb = listener; selected = -1;
        if (s != null && !s.empty()) {
            x0 = y0 = Float.MAX_VALUE; x1 = y1 = -Float.MAX_VALUE;
            for (int i = 0; i < s.x.length; i++) {
                x0 = Math.min(x0, s.x[i]); x1 = Math.max(x1, s.x[i]);
                y0 = Math.min(y0, s.y[i]); y1 = Math.max(y1, s.y[i]);
            }
            float px = (x1 - x0) * 0.06f, py = (y1 - y0) * 0.08f;
            if (px == 0) px = Math.max(1f, Math.abs(x0) * 0.1f);
            if (py == 0) py = Math.max(1f, Math.abs(y0) * 0.1f);
            x0 -= px; x1 += px; y0 -= py; y1 += py;
            if (y0 < 0 && y0 > -py * 1.5f) y0 = 0;
        }
        invalidate();
    }

    private float L() { return 58 * d; }
    private float T() { return 30 * d; }
    private float B() { return 40 * d; }
    private float R() { return 12 * d; }
    private float sx(float v) { return L() + (v - x0) / (x1 - x0) * (getWidth() - L() - R()); }
    private float sy(float v) { return getHeight() - B() - (v - y0) / (y1 - y0) * (getHeight() - T() - B()); }

    private static double niceStep(double raw) {
        double pw = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / pw;
        return (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * pw;
    }

    private static String tick(double v, double step) {
        return step >= 1 ? String.format(java.util.Locale.US, "%.0f", v) : step >= 0.1 ? String.format(java.util.Locale.US, "%.1f", v)
                : String.format(java.util.Locale.US, "%.2f", v);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (s == null || s.empty()) {
            p.setColor(Ui.MUTED); p.setTextSize(15 * d); p.setTextAlign(Paint.Align.CENTER);
            c.drawText("No data for these filters", getWidth() / 2f, getHeight() / 2f, p);
            return;
        }
        p.setTextSize(11 * d);
        // الشبكة والتدريج
        double ys = niceStep((y1 - y0) / 5), xs = niceStep((x1 - x0) / 6);
        p.setStrokeWidth(1);
        for (double v = Math.ceil(y0 / ys) * ys; v <= y1; v += ys) {
            float y = sy((float) v);
            p.setColor(Ui.LINE); c.drawLine(L(), y, getWidth() - R(), y, p);
            p.setColor(Ui.MUTED); p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(tick(v, ys), L() - 6 * d, y + 4 * d, p);
        }
        for (double v = Math.ceil(x0 / xs) * xs; v <= x1; v += xs) {
            float x = sx((float) v);
            p.setColor(Ui.LINE); c.drawLine(x, T(), x, getHeight() - B(), p);
            p.setColor(Ui.MUTED); p.setTextAlign(Paint.Align.CENTER);
            c.drawText(tick(v, xs), x, getHeight() - B() + 16 * d, p);
        }
        p.setColor(Ui.TEXT); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(12 * d);
        c.drawText(xl, L() + (getWidth() - L() - R()) / 2, getHeight() - 6 * d, p);
        c.save();
        c.rotate(-90, 14 * d, T() + (getHeight() - T() - B()) / 2);
        c.drawText(yl, 14 * d, T() + (getHeight() - T() - B()) / 2 + 4 * d, p);
        c.restore();

        // النقاط
        for (int i = 0; i < s.x.length; i++) {
            p.setColor(DashModel.PAL[s.color[i] % DashModel.PAL.length]);
            p.setAlpha(big ? 210 : 150);
            float rad = big ? 5 * d + (float) Math.sqrt(s.size[i]) * 1.1f * d : 3 * d;
            c.drawCircle(sx(s.x[i]), sy(s.y[i]), rad, p);
        }
        p.setAlpha(255);
        if (selected >= 0) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * d); p.setColor(Ui.TEXT);
            c.drawCircle(sx(s.x[selected]), sy(s.y[selected]), (big ? 11 : 7) * d, p);
            p.setStyle(Paint.Style.FILL);
        }
        // مفتاح الألوان
        boolean[] used = new boolean[s.legend.length];
        for (int k : s.color) if (k >= 0 && k < used.length) used[k] = true;
        float lx = L();
        p.setTextAlign(Paint.Align.LEFT); p.setTextSize(12 * d);
        for (int k = 0; k < s.legend.length; k++) {
            if (!used[k]) continue;
            p.setColor(DashModel.PAL[k % DashModel.PAL.length]);
            c.drawCircle(lx + 5 * d, 14 * d, 5 * d, p);
            p.setColor(Ui.TEXT);
            c.drawText(s.legend[k], lx + 14 * d, 18 * d, p);
            lx += 24 * d + p.measureText(s.legend[k]);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && s != null && !s.empty()) {
            int best = -1; float bd = 34 * d * 34 * d;
            for (int i = 0; i < s.x.length; i++) {
                float dx = sx(s.x[i]) - e.getX(), dy = sy(s.y[i]) - e.getY();
                float dd = dx * dx + dy * dy;
                if (dd < bd) { bd = dd; best = i; }
            }
            if (best >= 0) { selected = best; invalidate(); if (cb != null) cb.onPoint(s.ref[best]); }
        }
        return true;
    }
}
