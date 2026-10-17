package com.newtrip.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** خريطة حرارية: صفوف = شرائح السرعة، أعمدة = التسارع أو مستوى البطارية أو الحرارة. الضغط على خلية يعرض تفاصيلها. */
final class HeatmapView extends View {
    interface OnCell { void onCell(String tip); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private DashModel.Heat h;
    private OnCell cb;
    private double lo, hi;

    HeatmapView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        p.setTextAlign(Paint.Align.CENTER);
    }

    void setHeat(DashModel.Heat heat, OnCell listener) {
        h = heat; cb = listener;
        lo = Double.MAX_VALUE; hi = -Double.MAX_VALUE;
        if (h != null) for (double[] row : h.val) for (double v : row) {
            if (Double.isNaN(v)) continue;
            lo = Math.min(lo, v); hi = Math.max(hi, v);
        }
        requestLayout();
        invalidate();
    }

    private float labelDp = 70;
    /** عرض عمود أسماء الصفوف بالـ dp (الافتراضي 70). */
    void setLabelWidth(int dp) { labelDp = dp; }
    private float left() { return labelDp * d; }
    private float top() { return 34 * d; }
    private float cellH() { return 27 * d; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int rows = h == null || h.rows.length == 0 ? 3 : h.rows.length;
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (int) (top() + rows * cellH() + 8 * d));
    }

    private int color(double v) {
        double t = hi > lo ? (v - lo) / (hi - lo) : 0.5;
        t = Math.max(0, Math.min(1, t));
        if (h.cost) {      // أخضر ← أصفر ← أحمر (الأقل أفضل)
            int a = 0xFF2E7D32, b = 0xFFF9A825, c = 0xFFC62828;
            return t < 0.5 ? lerp(a, b, (float) (t * 2)) : lerp(b, c, (float) ((t - 0.5) * 2));
        }
        return lerp(0xFF1D2B36, 0xFF29B6F6, (float) t);   // أزرق: الأكثر = الأسطع
    }

    private static int lerp(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (h == null || h.rows.length == 0 || !h.any) {
            p.setColor(Ui.MUTED); p.setTextSize(15 * d);
            c.drawText(Tr.t("Not enough data for this map"), getWidth() / 2f, getHeight() / 2f, p);
            return;
        }
        int nc = h.cols.length, nr = h.rows.length;
        float cw = (getWidth() - left()) / nc, ch = cellH();
        p.setTextSize(11 * d);
        for (int j = 0; j < nc; j++) {
            p.setColor(Ui.MUTED);
            c.drawText(h.cols[j], left() + cw * (j + 0.5f), top() - 10 * d, p);
        }
        for (int i = 0; i < nr; i++) {
            p.setColor(Ui.MUTED); p.setTextSize(11 * d);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(h.rows[i], left() - 8 * d, top() + ch * (i + 0.5f) + 4 * d, p);
            p.setTextAlign(Paint.Align.CENTER);
            for (int j = 0; j < nc; j++) {
                r.set(left() + cw * j + 1.5f * d, top() + ch * i + 1.5f * d, left() + cw * (j + 1) - 1.5f * d, top() + ch * (i + 1) - 1.5f * d);
                double v = h.val[i][j];
                if (Double.isNaN(v)) { p.setColor(0xFF141D24); c.drawRoundRect(r, 4 * d, 4 * d, p); continue; }
                p.setColor(color(v));
                c.drawRoundRect(r, 4 * d, 4 * d, p);
                p.setColor(0xFFFFFFFF); p.setTextSize(11 * d);
                c.drawText(Ui.num(v, Math.abs(v) >= 100 ? 0 : 1), r.centerX(), r.centerY() + 4 * d, p);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && h != null && h.rows.length > 0 && cb != null) {
            int nc = h.cols.length;
            float cw = (getWidth() - left()) / nc;
            int j = (int) ((e.getX() - left()) / cw), i = (int) ((e.getY() - top()) / cellH());
            if (i >= 0 && i < h.rows.length && j >= 0 && j < nc) cb.onCell(h.tip[i][j]);
        }
        return true;
    }
}
