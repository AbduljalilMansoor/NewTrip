package com.newtrip.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.View;

/** أعمدة مجمّعة: القيمة فوق كل عمود، واسم الفئة ومسافتها تحته. */
final class BarsView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private DashModel.Bars b;

    BarsView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setBars(DashModel.Bars bars) { b = bars; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        p.setTextAlign(Paint.Align.CENTER);
        if (b == null || b.label.length == 0) {
            p.setColor(Ui.MUTED); p.setTextSize(15 * d);
            c.drawText("No data for these filters", getWidth() / 2f, getHeight() / 2f, p);
            return;
        }
        int n = b.label.length;
        double max = 0, min = 0;
        for (double v : b.val) { max = Math.max(max, v); min = Math.min(min, v); }
        if (max == min) max = min + 1;
        float top = 26 * d, bottom = 46 * d, w = getWidth(), h = getHeight();
        float slot = w / n, bw = Math.min(slot * 0.6f, 70 * d);
        float zeroY = top + (float) (max / (max - min)) * (h - top - bottom);
        p.setColor(Ui.LINE); p.setStrokeWidth(1);
        c.drawLine(0, zeroY, w, zeroY, p);
        for (int i = 0; i < n; i++) {
            float cx = slot * (i + 0.5f);
            float y = top + (float) ((max - b.val[i]) / (max - min)) * (h - top - bottom);
            r.set(cx - bw / 2, Math.min(y, zeroY), cx + bw / 2, Math.max(y, zeroY) + 1);
            p.setColor(DashModel.PAL[i % DashModel.PAL.length]);
            c.drawRoundRect(r, 5 * d, 5 * d, p);
            p.setColor(Ui.TEXT); p.setTextSize(13 * d); p.setFakeBoldText(true);
            c.drawText(Ui.num(b.val[i], Math.abs(b.val[i]) >= 100 ? 0 : 1), cx, Math.min(y, zeroY) - 6 * d, p);
            p.setFakeBoldText(false);
            p.setColor(Ui.MUTED); p.setTextSize(11 * d);
            String lab = TextUtils.ellipsize(b.label[i], new android.text.TextPaint(p), slot - 4 * d, TextUtils.TruncateAt.END).toString();
            c.drawText(lab, cx, h - 26 * d, p);
            c.drawText(Ui.num(b.dist[i], 0) + " km", cx, h - 10 * d, p);
        }
    }
}
