package com.newtrip.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Analyze: اختر رحلة أو عدة رحلات + نمط القيادة، فيُنشر جدول شرائح السرعة
 * (Low / Med / High / Very High / EV only / Total) مع مستوى البطارية في البداية والنهاية.
 */
public class AnalyzeActivity extends Activity {
    static final String EXTRA_FILES = "files";

    /** خيارات Mode في القائمة. القيمة = رقم الخانة في TripSession، و -1 = ALL. */
    private static final String[] MODE_LABELS = {"Park", "Eco", "Normal", "Sport", "Others", "ALL"};
    private static final int[] MODE_IDS = {TripSession.PARK, TripSession.ECO, TripSession.NORMAL,
            TripSession.SPORT, TripSession.OTHERS, -1};

    private static final int[] COL_W = {96, 170, 96, 104, 100, 84, 100, 128, 96, 96};
    private static final int ROW_H = 52;

    private final List<TripSession> trips = new ArrayList<>();
    private boolean[] selected;
    private int modeIdx = 5;           // ALL

    private LinearLayout tripList, resultHolder;
    private final List<TextView> chips = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiScale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        UiScale.applyWindow(this);

        SessionStore store = new SessionStore(this);
        for (File f : store.history()) {
            TripSession s = SessionStore.read(f);
            if (s == null) continue;
            s.file = f;
            trips.add(s);
        }
        selected = new boolean[trips.size()];
        String[] pre = getIntent().getStringArrayExtra(EXTRA_FILES);
        if (pre != null) {
            for (String path : pre)
                for (int i = 0; i < trips.size(); i++)
                    if (trips.get(i).file.getAbsolutePath().equals(path) && trips.get(i).hasTiers) selected[i] = true;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        int p = Ui.dp(this, 20);
        root.setPadding(p, p, p, p);

        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹", 34, Ui.MUTED, true);
        back.setPadding(0, 0, p, 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        header.addView(Ui.text(this, "Analyze", 28, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView dash = Ui.button(this, "📈  Dashboard", Ui.EV, v -> startActivity(new android.content.Intent(this, DashboardActivity.class)));
        dash.setTextSize(16);
        dash.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        TextView xl = Ui.button(this, "📊  Export Excel", Ui.CARD2, v -> exportSelected());
        xl.setTextSize(16);
        xl.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(-2, -2);
        xlp.setMarginEnd(Ui.dp(this, 10));
        header.addView(xl, xlp);
        header.addView(dash);
        root.addView(header);

        LinearLayout body = Ui.row(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = Ui.dp(this, 10);
        root.addView(body, blp);

        // ---- يسار: اختيار الرحلات ----
        LinearLayout left = Ui.card(this);
        LinearLayout lh = Ui.row(this);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        lh.addView(Ui.text(this, "Trips", 20, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        lh.addView(smallBtn("All", v -> setAll(true)));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.setMarginStart(Ui.dp(this, 8));
        lh.addView(smallBtn("None", v -> setAll(false)), nlp);
        left.addView(lh);
        left.addView(Ui.text(this, "Select one or more trips", 13, Ui.MUTED, false));
        tripList = new LinearLayout(this);
        tripList.setOrientation(LinearLayout.VERTICAL);
        ScrollView lsv = new ScrollView(this);
        lsv.addView(tripList);
        LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lslp.topMargin = Ui.dp(this, 8);
        left.addView(lsv, lslp);
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        // ---- يمين: Mode + النتائج ----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout mrow = Ui.row(this);
        mrow.setGravity(Gravity.CENTER_VERTICAL);
        mrow.addView(Ui.text(this, "Mode:", 18, Ui.MUTED, true));
        for (int i = 0; i < MODE_LABELS.length; i++) {
            final int idx = i;
            TextView c = Ui.text(this, MODE_LABELS[i], 16, Ui.TEXT, true);
            c.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            c.setOnClickListener(v -> { modeIdx = idx; refresh(); });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.setMarginStart(Ui.dp(this, 8));
            mrow.addView(c, clp);
            chips.add(c);
        }
        HorizontalScrollView mh = new HorizontalScrollView(this);
        mh.setHorizontalScrollBarEnabled(false);
        mh.addView(mrow);
        right.addView(mh);

        resultHolder = new LinearLayout(this);
        resultHolder.setOrientation(LinearLayout.VERTICAL);
        ScrollView rsv = new ScrollView(this);
        rsv.addView(resultHolder);
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(-1, 0, 1f);
        rslp.topMargin = Ui.dp(this, 10);
        right.addView(rsv, rslp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 2.4f);
        rlp.setMarginStart(Ui.dp(this, 14));
        body.addView(right, rlp);

        setContentView(root);
        refresh();
    }

    private void exportSelected() {
        final List<TripSession> sel = new ArrayList<>();
        for (int i = 0; i < trips.size(); i++) if (selected[i]) sel.add(trips.get(i));
        if (sel.isEmpty()) { android.widget.Toast.makeText(this, "Select one or more trips first", android.widget.Toast.LENGTH_LONG).show(); return; }
        new Thread(() -> {
            try {
                final File out = Exporter.exportXlsx(this, sel);
                runOnUiThread(() -> Exporter.offer(this, out, Exporter.MIME_XLSX));
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this, "Export failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }, "export").start();
    }

    private TextView smallBtn(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 15, Ui.TEXT, true);
        t.setBackground(Ui.round(Ui.CARD2, Ui.dp(this, 8)));
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        t.setOnClickListener(l);
        return t;
    }

    private void setAll(boolean on) {
        for (int i = 0; i < selected.length; i++) selected[i] = on && trips.get(i).hasTiers;
        refresh();
    }

    // ---------------- إعادة الرسم ----------------
    private void refresh() {
        for (int i = 0; i < chips.size(); i++)
            chips.get(i).setBackground(Ui.round(i == modeIdx ? Ui.EV : Ui.CARD2, Ui.dp(this, 10)));

        tripList.removeAllViews();
        if (trips.isEmpty()) tripList.addView(Ui.text(this, "No trips yet", 16, Ui.MUTED, false));
        for (int i = 0; i < trips.size(); i++) {
            final int idx = i;
            TripSession s = trips.get(i);
            LinearLayout r = Ui.row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            r.setBackground(selected[i] ? Ui.round(0x3329B6F6, Ui.dp(this, 10)) : null);
            TextView box = Ui.text(this, selected[i] ? "☑" : "☐", 24, s.hasTiers ? Ui.EV : Ui.LINE, true);
            box.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 10), 0);
            r.addView(box);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(this, Ui.date(s.startWall), 16, s.hasTiers ? Ui.TEXT : Ui.MUTED, true));
            String info = Ui.num(s.totalDistKm(), 1) + " km  ·  " + s.driveType() + "  ·  " + s.socModeLabel();
            col.addView(Ui.text(this, s.hasTiers ? info : info + "  ·  no speed-tier data", 13, Ui.MUTED, false));
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            r.setOnClickListener(v -> {
                if (!trips.get(idx).hasTiers) return;     // رحلة قديمة قبل Analyze
                selected[idx] = !selected[idx];
                refresh();
            });
            tripList.addView(r);
        }

        resultHolder.removeAllViews();
        List<TripSession> sel = new ArrayList<>();
        for (int i = 0; i < trips.size(); i++) if (selected[i]) sel.add(trips.get(i));
        if (sel.isEmpty()) {
            TextView h = Ui.text(this, "Select one or more trips to analyze.", 18, Ui.MUTED, false);
            h.setPadding(0, Ui.dp(this, 20), 0, 0);
            resultHolder.addView(h);
            return;
        }
        resultHolder.addView(batteryHeader(sel));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.dp(this, 10);
        resultHolder.addView(table(sel), tlp);
        TextView note = Ui.text(this, "Mode = " + MODE_LABELS[modeIdx] + "  ·  " + sel.size()
                + (sel.size() == 1 ? " trip" : " trips") + "  ·  Speed tiers apply while the engine runs; "
                + "\"EV only\" = engine off at any speed.", 13, Ui.MUTED, false);
        note.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        resultHolder.addView(note);
    }

    // ---------------- رأس الجدول: مستوى البطارية ----------------
    private View batteryHeader(List<TripSession> sel) {
        double a = 0, z = 0;
        int na = 0, nz = 0;
        for (TripSession s : sel) {
            if (!Float.isNaN(s.socStart)) { a += s.socStart; na++; }
            if (!Float.isNaN(s.socEnd)) { z += s.socEnd; nz++; }
        }
        boolean single = sel.size() == 1;
        String start = na == 0 ? "—" : Ui.num(a / na, 0) + "%";
        String end = nz == 0 ? "—" : Ui.num(z / nz, 0) + "%";

        LinearLayout r = Ui.row(this);
        r.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 14)));
        r.setGravity(Gravity.CENTER_VERTICAL);
        int pd = Ui.dp(this, 14);
        r.setPadding(pd, pd, pd, pd);
        r.addView(Ui.text(this, "Battery level:", 18, Ui.MUTED, true));
        TextView t = Ui.text(this, single
                ? "  @start = " + start + "     @end = " + end
                : "  Avg @start = " + start + "     Avg @end = " + end + "   (" + sel.size() + " trips)",
                20, Ui.EV, true);
        r.addView(t);
        return r;
    }

    // ---------------- الجدول ----------------
    private static final class Agg {
        long dur, smart, forced;
        double dist, fuel;
        float tMin = Float.NaN, tMax = Float.NaN;

        void add(TripSession.Tier t) {
            dur += t.durMs; smart += t.smartMs; forced += t.forcedMs;
            dist += t.distKm; fuel += t.fuelL;
            if (!Float.isNaN(t.targetMin) && (Float.isNaN(tMin) || t.targetMin < tMin)) tMin = t.targetMin;
            if (!Float.isNaN(t.targetMax) && (Float.isNaN(tMax) || t.targetMax > tMax)) tMax = t.targetMax;
        }

        void add(Agg o) {
            dur += o.dur; smart += o.smart; forced += o.forced; dist += o.dist; fuel += o.fuel;
            if (!Float.isNaN(o.tMin) && (Float.isNaN(tMin) || o.tMin < tMin)) tMin = o.tMin;
            if (!Float.isNaN(o.tMax) && (Float.isNaN(tMax) || o.tMax > tMax)) tMax = o.tMax;
        }
    }

    private View table(List<TripSession> sel) {
        int modeId = MODE_IDS[modeIdx];
        Agg[] rows = new Agg[TripSession.NUM_TIERS];
        for (int t = 0; t < rows.length; t++) rows[t] = new Agg();
        for (TripSession s : sel)
            for (int m = 0; m < TripSession.NUM_MODES; m++) {
                if (modeId >= 0 && m != modeId) continue;
                for (int t = 0; t < TripSession.NUM_TIERS; t++) rows[t].add(s.tiers[m][t]);
            }
        Agg total = new Agg();
        for (Agg a : rows) total.add(a);

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 14)));
        int pd = Ui.dp(this, 8);
        t.setPadding(pd, pd, pd, pd);

        // صف المجموعات: SOC Time % يغطي آخر عمودين
        LinearLayout g = Ui.row(this);
        int before = 0;
        for (int i = 0; i < 8; i++) before += COL_W[i];
        g.addView(cell("", before, Ui.TEXT, false, 0));
        g.addView(cell("SOC Time %", COL_W[8] + COL_W[9], Ui.TEXT, true, 0x3329B6F6));
        t.addView(g);

        String[] heads = {"Speed Tier", "Speed Km/h", "Avg speed", "Duration (hh:mm)", "Distance (km)",
                "Fuel (L)", "L/100Km", "Forced SOC target", "Smart", "Forced"};
        LinearLayout h = Ui.row(this);
        for (int i = 0; i < heads.length; i++) h.addView(cell(heads[i], COL_W[i], Ui.MUTED, true, 0));
        t.addView(h);
        t.addView(divider());

        for (int i = 0; i < TripSession.NUM_TIERS; i++) {
            t.addView(dataRow(TripSession.TIER_NAMES[i], TripSession.TIER_RANGES[i], rows[i],
                    i != TripSession.T_EV, false));
        }
        t.addView(divider());
        t.addView(dataRow("Total", "Not Applicable", total, true, true));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(t);
        return hs;
    }

    private View dataRow(String name, String range, Agg a, boolean showLper100, boolean bold) {
        boolean has = a.dur > 0;
        LinearLayout r = Ui.row(this);
        int c = bold ? Ui.TEXT : 0xFFCFD8DC;
        r.addView(cell(name, COL_W[0], Ui.TEXT, true, 0));
        r.addView(cell(range, COL_W[1], Ui.MUTED, false, 0));
        r.addView(cell(has ? Ui.num(Ui.avg(a.dist, a.dur), 0) : "—", COL_W[2], c, bold, 0));
        r.addView(cell(has ? hhmm(a.dur) : "—", COL_W[3], c, bold, 0));
        r.addView(cell(has ? Ui.num(a.dist, 1) : "—", COL_W[4], c, bold, 0));
        r.addView(cell(has ? Ui.num(a.fuel, 2) : "—", COL_W[5], Ui.ENG, bold, 0));
        r.addView(cell(has && showLper100 && a.dist > 0.5 ? Ui.num(a.fuel / a.dist * 100, 1) : "—",
                COL_W[6], Ui.ENG, bold, 0));
        r.addView(cell(TripSession.targetText(a.tMin, a.tMax), COL_W[7], c, bold, 0));
        r.addView(cell(has ? Ui.num(a.smart * 100.0 / a.dur, 0) + "%" : "—", COL_W[8], Ui.EV, bold, 0));
        r.addView(cell(has ? Ui.num(a.forced * 100.0 / a.dur, 0) + "%" : "—", COL_W[9], Ui.EV, bold, 0));
        return r;
    }

    private static String hhmm(long ms) {
        long m = Math.round(ms / 60000.0);
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    private TextView cell(String s, int widthDp, int color, boolean bold, int bg) {
        TextView t = Ui.text(this, s, 15, color, bold);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        if (bg != 0) t.setBackground(Ui.round(bg, Ui.dp(this, 8)));
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, widthDp), Ui.dp(this, ROW_H)));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        return v;
    }
}
