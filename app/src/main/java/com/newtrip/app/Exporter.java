package com.newtrip.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** تصدير الرحلات إلى CSV أو PDF، ومشاركتها، وحفظ نسخة في مجلد التنزيلات. الملفات تُحفظ في مجلد التصدير الخاص بالتطبيق. */
final class Exporter {
    private Exporter() { }

    static final String MIME_PDF = "application/pdf";
    static final String MIME_CSV = "text/csv";

    static File exportsDir(Context c) {
        File d = new File(c.getFilesDir(), "exports");
        d.mkdirs();
        return d;
    }

    static String authority(Context c) { return c.getPackageName() + ".exports"; }

    private static String stamp(long t) { return new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date(t)); }

    // ------------------------------------------------------------ CSV
    /**
     * يكتب جداول CSV (ملف لكل جدول: trips ، speed_tiers ، drive_modes ، soc_changes ، windows_30s) ويعيد الملفات.
     * الجداول الفارغة (عدا trips) تُتخطى. الصف الأول عناوين إنجليزية ثابتة، والعمود الأول Trip ID.
     */
    static List<File> exportCsv(Context c, List<TripSession> trips) throws IOException {
        String prefix = trips.size() == 1 ? "trip_" + stamp(trips.get(0).startWall)
                : "trips_" + stamp(System.currentTimeMillis()) + "_" + trips.size();
        List<File> out = new ArrayList<>();
        for (Csv.Table t : ExportData.tables(trips)) {
            if (!t.name.equals("trips") && !t.hasData()) continue;
            File f = new File(exportsDir(c), prefix + "_" + t.name + ".csv");
            try (FileOutputStream o = new FileOutputStream(f)) { Csv.write(o, t); }
            out.add(f);
        }
        return out;
    }

    // ------------------------------------------------------------ PDF
    private static final int PW = 842, PH = 595, M = 28;   // A4 أفقي بالنقاط

    static File exportPdf(Context c, TripSession s) throws IOException {
        File f = new File(exportsDir(c), "trip_" + stamp(s.startWall) + ".pdf");
        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.Page p1 = doc.startPage(new PdfDocument.PageInfo.Builder(PW, PH, 1).create());
            drawSummary(p1.getCanvas(), s);
            doc.finishPage(p1);
            if (s.wins.size() >= 2) {
                PdfDocument.Page p2 = doc.startPage(new PdfDocument.PageInfo.Builder(PW, PH, 2).create());
                drawTimeline(p2.getCanvas(), s);
                doc.finishPage(p2);
            }
            try (OutputStream o = new FileOutputStream(f)) { doc.writeTo(o); }
        } finally {
            doc.close();
        }
        return f;
    }

    private static Paint paint(float size, boolean bold, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    private static String f(double v, int d) { return DashModel.f(v, d); }

    private static void header(Canvas c, TripSession s, String title) {
        c.drawText(title, M, 42, paint(22, true, 0xFF1F4E79));
        Paint r = paint(12, false, 0xFF555555);
        r.setTextAlign(Paint.Align.RIGHT);
        c.drawText(new SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(new Date(s.startWall)), PW - M, 42, r);
        Paint line = paint(1, false, 0xFF1F4E79);
        line.setStrokeWidth(1.5f);
        c.drawLine(M, 52, PW - M, 52, line);
    }

    private static void footer(Canvas c, int page) {
        Paint p = paint(9, false, 0xFF888888);
        c.drawText(Settings.COPYRIGHT, M, PH - 14, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Page " + page, PW - M, PH - 14, p);
    }

    private static void tile(Canvas c, float x, float y, float w, String label, String value) {
        Paint bg = paint(1, false, 0xFFEAF1F8);
        c.drawRoundRect(new RectF(x, y, x + w, y + 44), 6, 6, bg);
        c.drawText(label, x + 8, y + 15, paint(9.5f, false, 0xFF555555));
        Paint v = paint(15, true, 0xFF111111);
        float size = 15;
        while (size > 8 && v.measureText(value) > w - 14) { size -= 0.5f; v.setTextSize(size); }   // يصغّر الخط ليتسع النص
        c.drawText(value, x + 8, y + 36, v);
    }

    private static void drawSummary(Canvas c, TripSession s) {
        header(c, s, "New Trip - Trip report");
        c.drawText(s.driveType() + "   |   " + s.socModeLabel() + ("—".equals(s.targetLabel()) ? "" : "   |   Target " + s.targetLabel()),
                M, 72, paint(13, true, 0xFF333333));
        float tw = (PW - 2 * M - 5 * 8) / 6f;
        String[][] t1 = {
                {"Distance", f(s.totalDistKm(), 1) + " km"},
                {"Duration", (s.totalDurMs() / 60000) + " min"},
                {"Avg speed", f(s.avgSpeedKmh(), 0) + " km/h"},
                {"Battery start > end", f(s.socStart, 0) + "% > " + f(s.socEnd, 0) + "%"},
                {"Fuel", f(s.totalFuelL(), 1) + " L | " + f(s.totalDistKm() > 0.3 ? s.totalFuelL() / s.totalDistKm() * 100 : Double.NaN, 1) + "/100"},
                {"Battery net", f(s.totalDistKm() > 0.3 ? (s.totalKwhOut() - s.totalKwhIn()) / s.totalDistKm() * 100 : Double.NaN, 1) + " kWh/100"}};
        float[] fs = s.forcedStart();
        String[][] t2 = {
                {"EV share", f(s.evShare(), 0) + "%"},
                {"RPM avg / max", f(s.avgRpm(), 0) + " / " + f(s.maxRpm(), 0)},
                {"A/C on", f(s.acSharePct(), 0) + "% of time"},
                {"Outside / cabin", f(s.avgTempC(), 0) + " / " + f(s.avgCabinC(), 0) + " C"},
                {"Battery temp avg/max", f(s.avgPackC(), 0) + " / " + f(s.maxPackC(), 0) + " C"},
                {fs == null ? "Forced charging" : "Forced started", fs == null ? "not used"
                        : f(fs[0] / 60, 0) + "m @ " + f(fs[1], 0) + "% > " + f(fs[2], 0) + "%"}};
        for (int i = 0; i < 6; i++) {
            tile(c, M + i * (tw + 8), 86, tw, t1[i][0], t1[i][1]);
            tile(c, M + i * (tw + 8), 140, tw, t2[i][0], t2[i][1]);
        }

        // جدول شرائح السرعة
        float y = 212;
        c.drawText("Speed tiers (all modes)", M, y, paint(13, true, 0xFF1F4E79));
        y += 10;
        String[] heads = {"Speed tier", "Speed km/h", "Avg km/h", "Duration", "Distance km", "Fuel L", "L/100km", "Forced target", "Smart %", "Forced %"};
        float[] cw = {82, 122, 66, 70, 80, 62, 70, 90, 60, 60};
        y = row(c, y, cw, heads, true);
        TripSession.Tier tot = new TripSession.Tier();
        for (int k = 0; k < TripSession.NUM_TIERS; k++) {
            TripSession.Tier a = new TripSession.Tier();
            for (int m = 0; m < TripSession.NUM_MODES; m++) merge(a, s.tiers[m][k]);
            merge(tot, a);
            y = row(c, y, cw, tierRow(TripSession.TIER_NAMES[k], TripSession.TIER_RANGES[k], a, k != TripSession.T_EV), false);
        }
        y = row(c, y, cw, tierRow("Total", "-", tot, true), true);

        // جدول أنماط القيادة
        y += 18;
        c.drawText("Drive modes", M, y, paint(13, true, 0xFF1F4E79));
        y += 10;
        String[] mh = {"Mode", "EV km", "Engine km", "EV min", "Engine min", "Fuel L", "Battery net kWh"};
        float[] mw = {100, 80, 90, 80, 90, 80, 110};
        y = row(c, y, mw, mh, true);
        for (int m = 0; m < TripSession.NUM_MODES; m++) {
            TripSession.Cell e = s.cells[m][TripSession.EV], g = s.cells[m][TripSession.ENG];
            if (e.durMs + g.durMs == 0) continue;
            y = row(c, y, mw, new String[]{DashModel.MODE_NAMES[m], f(e.distKm, 1), f(g.distKm, 1), f(e.durMs / 60000.0, 0),
                    f(g.durMs / 60000.0, 0), f(e.fuelL + g.fuelL, 2), f(e.kwhOut + g.kwhOut - e.kwhIn - g.kwhIn, 2)}, false);
        }
        footer(c, 1);
    }

    private static void merge(TripSession.Tier a, TripSession.Tier b) {
        a.durMs += b.durMs; a.smartMs += b.smartMs; a.forcedMs += b.forcedMs; a.distKm += b.distKm; a.fuelL += b.fuelL;
        if (!Float.isNaN(b.targetMin) && (Float.isNaN(a.targetMin) || b.targetMin < a.targetMin)) a.targetMin = b.targetMin;
        if (!Float.isNaN(b.targetMax) && (Float.isNaN(a.targetMax) || b.targetMax > a.targetMax)) a.targetMax = b.targetMax;
    }

    private static String[] tierRow(String name, String range, TripSession.Tier a, boolean showL100) {
        boolean has = a.durMs > 0;
        double hrs = a.durMs / 3_600_000.0;
        return new String[]{name, range, has ? f(hrs > 0 ? a.distKm / hrs : Double.NaN, 0) : "-",
                has ? String.format(Locale.US, "%02d:%02d", Math.round(a.durMs / 60000.0) / 60, Math.round(a.durMs / 60000.0) % 60) : "-",
                has ? f(a.distKm, 1) : "-", has ? f(a.fuelL, 2) : "-",
                has && showL100 && a.distKm > 0.5 ? f(a.fuelL / a.distKm * 100, 1) : "-",
                TripSession.targetText(a.targetMin, a.targetMax),
                has ? f(a.smartMs * 100.0 / a.durMs, 0) + "%" : "-", has ? f(a.forcedMs * 100.0 / a.durMs, 0) + "%" : "-"};
    }

    private static float row(Canvas c, float y, float[] cw, String[] cells, boolean head) {
        float x = M;
        float h = 17;
        if (head) c.drawRect(M, y, M + sum(cw), y + h, paint(1, false, 0xFF1F4E79));
        Paint p = paint(10, head, head ? 0xFFFFFFFF : 0xFF222222);
        for (int i = 0; i < cells.length && i < cw.length; i++) {
            c.drawText(cells[i], x + 5, y + 12, p);
            x += cw[i];
        }
        if (!head) c.drawLine(M, y + h, M + sum(cw), y + h, paint(1, false, 0xFFDDDDDD));
        return y + h;
    }

    private static float sum(float[] a) { float s = 0; for (float v : a) s += v; return s; }

    // ---------- الصفحة الثانية: الخط الزمني ----------
    private static void drawTimeline(Canvas c, TripSession s) {
        header(c, s, "Trip timeline");
        List<float[]> w = s.wins;
        float tMax = 1;
        for (float[] x : w) tMax = Math.max(tMax, (x[TripSession.W_T] + x[TripSession.W_DUR]) / 60f);
        float left = M + 40, right = PW - M, top = 70, ph = 120, gap = 36;

        // 1) البطارية + الهدف + Forced
        RectF a = new RectF(left, top, right, top + ph);
        panel(c, a, "Battery %  (orange band = Forced power saving, line = target)", 0, 100, tMax);
        Paint band = paint(1, false, 0x33FFA726);
        Paint tgt = paint(1, false, 0xFFFFA726);
        tgt.setStrokeWidth(1.5f);
        for (float[] x : w) {
            float x0 = px(a, x[TripSession.W_T] / 60f, tMax), x1 = px(a, (x[TripSession.W_T] + x[TripSession.W_DUR]) / 60f, tMax);
            if (x[TripSession.W_FORCED] > 0.5f) {
                c.drawRect(x0, a.top, x1, a.bottom, band);
                if (!Float.isNaN(x[TripSession.W_TARGET])) {
                    float ty = py(a, x[TripSession.W_TARGET], 0, 100);
                    c.drawLine(x0, ty, x1, ty, tgt);
                }
            }
        }
        line(c, a, w, TripSession.W_SOC, 0, 100, tMax, 0xFF29B6F6);

        // 2) السرعة
        RectF b = new RectF(left, a.bottom + gap, right, a.bottom + gap + ph);
        float vmax = 40;
        for (float[] x : w) vmax = Math.max(vmax, x[TripSession.W_SPD]);
        vmax = (float) Math.ceil(vmax / 20) * 20;
        panel(c, b, "Speed km/h", 0, vmax, tMax);
        line(c, b, w, TripSession.W_SPD, 0, vmax, tMax, 0xFF455A64);

        // 3) RPM + استهلاك الوقود
        RectF d = new RectF(left, b.bottom + gap, right, b.bottom + gap + ph);
        float rmax = 1000;
        for (float[] x : w) if (!Float.isNaN(x[TripSession.W_RPMMAX])) rmax = Math.max(rmax, x[TripSession.W_RPMMAX]);
        rmax = (float) Math.ceil(rmax / 1000) * 1000;
        panel(c, d, "RPM (purple line)   Fuel flow L/h (orange bars)", 0, rmax, tMax);
        float fmax = 5;
        for (float[] x : w) if (x[TripSession.W_DUR] > 0) fmax = Math.max(fmax, x[TripSession.W_FUEL] / (x[TripSession.W_DUR] / 3600f));
        Paint bar = paint(1, false, 0x88FFA726);
        for (float[] x : w) {
            if (x[TripSession.W_DUR] <= 0) continue;
            float fl = x[TripSession.W_FUEL] / (x[TripSession.W_DUR] / 3600f);
            float x0 = px(d, x[TripSession.W_T] / 60f, tMax), x1 = px(d, (x[TripSession.W_T] + x[TripSession.W_DUR]) / 60f, tMax);
            c.drawRect(x0, py(d, fl, 0, fmax), Math.max(x1, x0 + 1), d.bottom, bar);
        }
        line(c, d, w, TripSession.W_RPM, 0, rmax, tMax, 0xFF8E24AA);
        c.drawText("Fuel scale: 0 - " + f(fmax, 0) + " L/h   |   time axis in minutes (active driving)", left, d.bottom + 26, paint(9, false, 0xFF666666));
        footer(c, 2);
    }

    private static float px(RectF r, float tMin, float tMax) { return r.left + (tMin / tMax) * r.width(); }
    private static float py(RectF r, float v, float lo, float hi) { return r.bottom - (v - lo) / (hi - lo) * r.height(); }

    private static void panel(Canvas c, RectF r, String title, float lo, float hi, float tMax) {
        c.drawText(title, r.left, r.top - 6, paint(10.5f, true, 0xFF333333));
        Paint g = paint(1, false, 0xFFDDDDDD);
        for (int i = 0; i <= 4; i++) {
            float y = r.bottom - r.height() * i / 4f;
            c.drawLine(r.left, y, r.right, y, g);
            Paint t = paint(8.5f, false, 0xFF777777);
            t.setTextAlign(Paint.Align.RIGHT);
            c.drawText(f(lo + (hi - lo) * i / 4f, 0), r.left - 4, y + 3, t);
        }
        Paint t = paint(8.5f, false, 0xFF777777);
        t.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i <= 5; i++) c.drawText(f(tMax * i / 5f, 0) + " min", r.left + r.width() * i / 5f, r.bottom + 11, t);
    }

    private static void line(Canvas c, RectF r, List<float[]> w, int idx, float lo, float hi, float tMax, int color) {
        Paint p = paint(1, false, color);
        p.setStrokeWidth(2f);
        float px = Float.NaN, py = Float.NaN;
        for (float[] x : w) {
            float v = x[idx];
            if (Float.isNaN(v)) { px = Float.NaN; continue; }
            float cx = px(r, (x[TripSession.W_T] + x[TripSession.W_DUR] / 2f) / 60f, tMax), cy = py(r, Math.max(lo, Math.min(hi, v)), lo, hi);
            if (!Float.isNaN(px)) c.drawLine(px, py, cx, cy, p);
            px = cx; py = cy;
        }
    }

    // ------------------------------------------------------------ مشاركة وحفظ
    /** نافذة بعد التصدير لملف واحد. */
    static void offer(Activity a, File f, String mime) { offer(a, java.util.Collections.singletonList(f), mime); }

    /** نافذة بعد التصدير: مشاركة، أو حفظ نسخة في Downloads. */
    static void offer(Activity a, List<File> files, String mime) {
        long size = 0;
        for (File f : files) size += f.length();
        String names = files.size() == 1 ? files.get(0).getName() : Tr.f("%s files", files.size());
        new AlertDialog.Builder(a)
                .setTitle(Tr.t("Saved"))
                .setMessage(names + "  (" + StorageMonitor.human(size) + ")\n\n" + Tr.t("Share it or save a copy to the Downloads folder."))
                .setPositiveButton(Tr.t("Share"), (d, w) -> share(a, files, mime))
                .setNeutralButton(Tr.t("Save to Downloads"), (d, w) -> {
                    String where = null;
                    for (File f : files) where = saveToDownloads(a, f, mime);
                    android.widget.Toast.makeText(a, where == null ? Tr.t("Could not save a copy") : Tr.f("Saved: %s", files.size() == 1 ? where : "Downloads/NewTrip"),
                            android.widget.Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(Tr.t("Close"), null)
                .show();
    }

    static void share(Context c, File f, String mime) { share(c, java.util.Collections.singletonList(f), mime); }

    static void share(Context c, List<File> files, String mime) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) uris.add(new Uri.Builder().scheme("content").authority(authority(c)).appendPath(f.getName()).build());
        Intent i = new Intent(files.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        i.setType(mime);
        if (files.size() > 1) i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        else i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        i.putExtra(Intent.EXTRA_SUBJECT, "New Trip - " + files.get(0).getName());
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(i, Tr.t("Share trip"));
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(c instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(chooser);
        } catch (Exception e) {
            android.widget.Toast.makeText(c, Tr.t("No app available to share this file"), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Android 10+: مجلد Downloads/NewTrip عبر MediaStore. أقدم: المجلد الخارجي الخاص بالتطبيق. يعيد وصف المكان أو null. */
    static String saveToDownloads(Context c, File f, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver r = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NewTrip");
                Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return null;
                try (OutputStream o = r.openOutputStream(u); InputStream in = new FileInputStream(f)) { copy(in, o); }
                return "Downloads/NewTrip/" + f.getName();
            }
            File base = c.getExternalFilesDir("exports");
            if (base == null) return null;
            base.mkdirs();
            File out = new File(base, f.getName());
            try (OutputStream o = new FileOutputStream(out); InputStream in = new FileInputStream(f)) { copy(in, o); }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream o) throws IOException {
        byte[] b = new byte[32 * 1024];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
    }
}
