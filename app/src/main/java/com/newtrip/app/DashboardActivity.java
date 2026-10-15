package com.newtrip.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * لوحة التحليل الذكية بثلاث تبويبات:
 *  1) PHEV relations: علاقة مستوى البطارية عند البداية ووضع SOC والهدف والمسافة والسرعة/RPM باستهلاك الوقود.
 *  2) Overview: ملاحظات عامة ومقارنات مجمّعة.
 *  3) Maps & scatter: خرائط الاستهلاك والمخططات التشتتية التفصيلية.
 * الفلاتر المنسدلة في الأعلى تؤثر على كل التبويبات.
 */
public class DashboardActivity extends Activity {
    private static final int TAB_PHEV = 0, TAB_OVERVIEW = 1, TAB_MAPS = 2;

    private DashModel model;
    private LinearLayout bodyHolder, kpiRow;
    private final LinearLayout[] tabs = new LinearLayout[3];
    private final TextView[] tabChips = new TextView[3];
    private final boolean[] dirty = {true, true, true};
    private int tab = TAB_PHEV;
    private final List<Spinner> filterSpinners = new ArrayList<>();

    // PHEV
    private LinearLayout phevInsightsBox, tlHolder, forcedTable;
    private ScatterView startScat;
    private HeatmapView socDistHeat, modeSocHeat, stressHeat;
    private TimelineView timeline;
    private TextView startTip, socDistTip, modeSocTip, stressTip, tlTip, startOpen;
    private int startSel = -1, mapMetricPhev, stressCol, stressMetric;
    private List<Integer> tlTrips = new ArrayList<>();
    private int tlChoice = -1;
    // Overview
    private LinearLayout insightsBox;
    private BarsView bars;
    private int gBy, gMetric = 2;
    // Maps
    private HeatmapView heat;
    private ScatterView tripScat, winScat;
    private TextView heatTip, tripTip, winTip, openBtn;
    private int selTrip = -1, mapType, mapMetric, tx, ty, tc, wx, wy, wc;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiScale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        UiScale.applyWindow(this);

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
        header.addView(Ui.text(this, "Dashboard", 28, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView export = Ui.button(this, "📊  Export Excel", Ui.CARD2, v -> exportFiltered());
        export.setTextSize(15);
        export.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.setMarginEnd(Ui.dp(this, 10));
        header.addView(export, elp);
        TextView reset = Ui.button(this, "Reset filters", Ui.CARD2, v -> resetFilters());
        reset.setTextSize(15);
        reset.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        header.addView(reset);
        root.addView(header);

        bodyHolder = new LinearLayout(this);
        bodyHolder.setOrientation(LinearLayout.VERTICAL);
        TextView loading = Ui.text(this, "Loading trips…", 18, Ui.MUTED, false);
        loading.setPadding(0, Ui.dp(this, 20), 0, 0);
        bodyHolder.addView(loading);
        root.addView(bodyHolder, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        final SessionStore store = new SessionStore(this);
        new Thread(() -> {
            final List<TripSession> all = new ArrayList<>();
            for (File f : store.history()) {
                TripSession s = SessionStore.read(f);
                if (s == null) continue;
                s.file = f;
                all.add(s);
            }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                model = new DashModel(all);
                buildBody(all.isEmpty());
            });
        }, "dash-load").start();
    }

    // ============================================================ الهيكل
    private void buildBody(boolean noTrips) {
        bodyHolder.removeAllViews();
        if (noTrips) {
            bodyHolder.addView(Ui.text(this, "No trips recorded yet. Finish a trip to see analytics.", 18, Ui.MUTED, false));
            return;
        }
        LinearLayout fr = Ui.row(this);
        fr.setGravity(Gravity.BOTTOM);
        fr.addView(filter("Period", DashModel.PERIOD, i -> { model.period = i; changed(); }));
        fr.addView(filter("Trip distance", DashModel.DIST, i -> { model.dist = i; changed(); }));
        fr.addView(filter("Drive mode", DashModel.MODE, i -> { model.mode = i; changed(); }));
        fr.addView(filter("Hybrid / Electric", DashModel.DRIVE, i -> { model.drive = i; changed(); }));
        fr.addView(filter("SOC mode", DashModel.SOC, i -> { model.soc = i; changed(); }));
        fr.addView(filter("Target SOC", DashModel.TARGET, i -> { model.target = i; changed(); }));
        fr.addView(filter("Outside temp", DashModel.TEMP, i -> { model.temp = i; changed(); }));
        HorizontalScrollView fh = new HorizontalScrollView(this);
        fh.setHorizontalScrollBarEnabled(false);
        fh.addView(fr);
        bodyHolder.addView(fh);

        ScrollView sv = new ScrollView(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        sv.addView(c);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svlp.topMargin = Ui.dp(this, 8);
        bodyHolder.addView(sv, svlp);

        kpiRow = Ui.row(this);
        c.addView(kpiRow);

        // شريط التبويبات
        LinearLayout tb = Ui.row(this);
        String[] names = {"PHEV relations", "Overview", "Maps & scatter"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView chip = Ui.text(this, names[i], 17, Ui.TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
            chip.setOnClickListener(v -> switchTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4));
            tb.addView(chip, lp);
            tabChips[i] = chip;
        }
        c.addView(tb);

        tabs[TAB_PHEV] = buildPhevTab();
        tabs[TAB_OVERVIEW] = buildOverviewTab();
        tabs[TAB_MAPS] = buildMapsTab();
        for (LinearLayout t : tabs) c.addView(t);
        switchTab(TAB_PHEV);
        refreshKpis();
    }

    private void switchTab(int i) {
        tab = i;
        for (int k = 0; k < 3; k++) {
            tabs[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabChips[k].setBackground(Ui.round(k == i ? Ui.EV : Ui.CARD2, Ui.dp(this, 12)));
        }
        refreshTab(i);
    }

    private void refreshTab(int i) {
        if (!dirty[i]) return;
        dirty[i] = false;
        if (i == TAB_PHEV) refreshPhev();
        else if (i == TAB_OVERVIEW) refreshOverview();
        else refreshMaps();
    }

    private void changed() {
        model.recompute();
        selTrip = -1; startSel = -1;
        java.util.Arrays.fill(dirty, true);
        refreshKpis();
        refreshTab(tab);
    }

    private void resetFilters() {
        if (model == null) return;
        model.period = model.mode = model.drive = model.soc = model.target = model.temp = model.dist = 0;
        for (Spinner s : filterSpinners) s.setSelection(0, false);
        changed();
    }

    // ============================================================ أدوات واجهة
    private View filter(String label, String[] items, Widgets.Pick cb) {
        Spinner sp = Widgets.spinner(this, items, 0, cb);
        filterSpinners.add(sp);
        return labeled(label, sp);
    }

    private View labeled(String label, View sp) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, Ui.dp(this, 10), 0);
        col.addView(Ui.text(this, label, 12, Ui.MUTED, false));
        col.addView(sp);
        return col;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams cardLp(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private LinearLayout.LayoutParams fullLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private LinearLayout cardWith(String title, String how) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, title, 20, Ui.TEXT, true));
        if (how != null) {
            TextView h = Ui.text(this, how, 13, Ui.MUTED, false);
            h.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
            c.addView(h);
        }
        return c;
    }

    private LinearLayout hrow() {
        LinearLayout r = Ui.row(this);
        r.setBaselineAligned(false);
        return r;
    }

    private void kpi(String label, String value, int color) {
        TextView v = Ui.tile(kpiRow, label, color);
        v.setTextSize(22);
        v.setText(value);
    }

    private void refreshKpis() {
        DashModel.Kpi k = model.kpi();
        kpiRow.removeAllViews();
        kpi("Trips", String.valueOf(k.trips), Ui.TEXT);
        kpi("Distance", DashModel.f(k.dist, 0) + " km", Ui.TEXT);
        kpi("Avg speed", DashModel.f(k.durH > 0 ? k.dist / k.durH : Double.NaN, 0) + " km/h", Ui.TEXT);
        kpi("Fuel", DashModel.f(k.dist > 0 ? k.fuel / k.dist * 100 : Double.NaN, 1) + " L/100", Ui.ENG);
        kpi("Battery (net)", DashModel.f(k.dist > 0 ? (k.out - k.in) / k.dist * 100 : Double.NaN, 1) + " kWh/100", Ui.EV);
        kpi("EV share", DashModel.f(k.dist > 0 ? k.evDist / k.dist * 100 : Double.NaN, 0) + "%", Ui.EV);
        kpi("Avg temp", DashModel.f(k.temp, 0) + " °C", Ui.TEXT);
    }

    private void insightLines(LinearLayout box, List<String> lines) {
        box.removeAllViews();
        for (String s : lines) {
            boolean quiet = s.startsWith("These are") || s.startsWith("ℹ");
            TextView t = Ui.text(this, s, 16, quiet ? Ui.MUTED : Ui.TEXT, false);
            t.setPadding(0, Ui.dp(this, 5), 0, 0);
            box.addView(t);
        }
    }

    private void exportFiltered() {
        if (model == null || model.trips.isEmpty()) { Toast.makeText(this, "No trips to export", Toast.LENGTH_LONG).show(); return; }
        final List<TripSession> list = new ArrayList<>(model.trips);
        Toast.makeText(this, "Exporting " + list.size() + " trips…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final File out = Exporter.exportXlsx(this, list);
                runOnUiThread(() -> Exporter.offer(this, out, Exporter.MIME_XLSX));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "export").start();
    }

    private void openTrip(int ptIndex) {
        if (ptIndex < 0 || ptIndex >= model.pts.size()) return;
        TripSession s = model.pts.get(ptIndex).s;
        if (s.file == null) return;
        startActivity(new Intent(this, ReportActivity.class).putExtra(ReportActivity.EXTRA_FILE, s.file.getAbsolutePath()));
    }

    // ============================================================ تبويب 1: PHEV relations
    private LinearLayout buildPhevTab() {
        LinearLayout t = vbox();

        LinearLayout intro = cardWith("How battery level, SOC mode and target drive fuel use",
                "A PHEV drives on the battery as long as it can; the engine only helps when the battery is low, the load is high, or Forced mode is charging it. "
                        + "A fuller battery at the start usually means less fuel. In Forced mode the engine charges up to the target: the bigger the gap between the "
                        + "target and the battery, and the higher the speed or RPM, the more fuel it burns.");
        phevInsightsBox = vbox();
        intro.addView(phevInsightsBox);
        t.addView(intro, fullLp());

        // صف 1
        LinearLayout r1 = hrow();
        LinearLayout a = cardWith("1  Start battery vs fuel use", "Each dot is a trip (size = distance). The white line is the trend. Lower is better.");
        startScat = new ScatterView(this);
        a.addView(startScat, new LinearLayout.LayoutParams(-1, Ui.dp(this, 300)));
        startTip = Ui.text(this, "Tap a point for the trip details", 13, Ui.MUTED, false);
        a.addView(startTip);
        startOpen = Ui.button(this, "Open trip report", Ui.EV, v -> openTrip(startSel));
        startOpen.setTextSize(14);
        startOpen.setVisibility(View.GONE);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
        olp.topMargin = Ui.dp(this, 6);
        a.addView(startOpen, olp);
        r1.addView(a, cardLp(1f));

        LinearLayout bcard = cardWith("2  Start battery × trip distance", "Fuel in L/100km. Green = low, red = high. A full battery on a short trip should be greenest.");
        socDistHeat = new HeatmapView(this);
        socDistHeat.setLabelWidth(70);
        bcard.addView(socDistHeat, new LinearLayout.LayoutParams(-1, -2));
        socDistTip = Ui.text(this, "Tap a cell for details", 13, Ui.MUTED, false);
        bcard.addView(socDistTip);
        r1.addView(bcard, cardLp(1f));
        t.addView(r1);

        // صف 2
        LinearLayout r2 = hrow();
        LinearLayout c = cardWith("3  Drive mode × SOC setting", "Which mode and Smart/Forced target combination uses the least fuel.");
        c.addView(labeled("Metric", Widgets.spinner(this, DashModel.MSMETRIC, 0, i -> { mapMetricPhev = i; refreshModeSoc(); })));
        modeSocHeat = new HeatmapView(this);
        modeSocHeat.setLabelWidth(70);
        c.addView(modeSocHeat, new LinearLayout.LayoutParams(-1, -2));
        modeSocTip = Ui.text(this, "Tap a cell for details", 13, Ui.MUTED, false);
        c.addView(modeSocTip);
        r2.addView(c, cardLp(1f));

        LinearLayout d = cardWith("4  Forced charging: gap to target × speed / RPM",
                "Gap = target − battery (e.g. target 70%, battery 30% → 40% below). Dark red = expensive: a large gap with high speed or high RPM.");
        LinearLayout dc = Ui.row(this);
        dc.addView(labeled("Columns", Widgets.spinner(this, DashModel.FCOL, 0, i -> { stressCol = i; refreshStress(); })));
        dc.addView(labeled("Metric", Widgets.spinner(this, DashModel.FMETRIC, 0, i -> { stressMetric = i; refreshStress(); })));
        d.addView(dc);
        stressHeat = new HeatmapView(this);
        stressHeat.setLabelWidth(120);
        d.addView(stressHeat, new LinearLayout.LayoutParams(-1, -2));
        stressTip = Ui.text(this, "Tap a cell for details", 13, Ui.MUTED, false);
        d.addView(stressTip);
        r2.addView(d, cardLp(1f));
        t.addView(r2);

        // جدول بدايات Forced
        LinearLayout e = cardWith("5  When Forced charging started", "Battery level and target at the moment Forced was switched on. Tap a row to open the trip.");
        forcedTable = vbox();
        e.addView(forcedTable);
        t.addView(e, fullLp());

        // الخط الزمني
        LinearLayout f = cardWith("6  Trip timeline", "Battery, Forced periods and target, speed, engine RPM and fuel flow on one time axis. Tap the chart for exact values.");
        tlHolder = Ui.row(this);
        f.addView(tlHolder);
        timeline = new TimelineView(this);
        f.addView(timeline, new LinearLayout.LayoutParams(-1, Ui.dp(this, 420)));
        tlTip = Ui.text(this, "Tap the chart for values", 13, Ui.MUTED, false);
        f.addView(tlTip);
        t.addView(f, fullLp());
        return t;
    }

    private void refreshPhev() {
        insightLines(phevInsightsBox, model.phevInsights());

        DashModel.Scat s = model.startSocScatter();
        startScat.setData(s, "Battery at trip start (%)", "Fuel (L/100km)", true, ref -> {
            startSel = ref;
            startTip.setText(model.tripTip(ref));
            startOpen.setVisibility(View.VISIBLE);
        });
        startScat.setTrend(model.trend);
        startTip.setText("Tap a point for the trip details");
        startOpen.setVisibility(View.GONE);
        startSel = -1;

        socDistHeat.setHeat(model.socDistHeat(), tip -> socDistTip.setText(tip));
        refreshModeSoc();
        refreshStress();
        refreshForcedTable();
        refreshTimeline();
    }

    private void refreshModeSoc() {
        modeSocHeat.setHeat(model.modeSocHeat(mapMetricPhev), tip -> modeSocTip.setText(tip));
    }

    private void refreshStress() {
        stressHeat.setHeat(model.forcedStressHeat(stressCol, stressMetric), tip -> stressTip.setText(tip));
    }

    private TextView cell(String s, float w, int color, boolean bold) {
        TextView t = Ui.text(this, s, 15, color, bold);
        t.setSingleLine(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, w));
        return t;
    }

    private void refreshForcedTable() {
        forcedTable.removeAllViews();
        List<DashModel.ForcedRow> rows = model.forcedStarts();
        if (rows.isEmpty()) {
            forcedTable.addView(Ui.text(this, "No trips with Forced charging in the current filters.", 16, Ui.MUTED, false));
            return;
        }
        LinearLayout h = Ui.row(this);
        String[] heads = {"Trip", "Battery at start", "Forced began", "Target", "Gap", "RPM avg / max", "Fuel L/100km", "Distance"};
        float[] w = {1.5f, 1f, 1.5f, 0.7f, 0.7f, 1.2f, 1f, 0.9f};
        for (int i = 0; i < heads.length; i++) h.addView(cell(heads[i], w[i], Ui.MUTED, true));
        forcedTable.addView(h);
        int shown = 0;
        for (DashModel.ForcedRow r : rows) {
            if (shown++ >= 15) break;
            final int idx = model.pts.indexOf(r.p);
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            row.setBackground(Ui.round(shown % 2 == 0 ? Ui.CARD2 : 0x00000000, Ui.dp(this, 8)));
            double gap = r.deficit;
            int gapColor = Double.isNaN(gap) ? Ui.MUTED : gap >= 30 ? Ui.BAD : gap >= 15 ? Ui.ENG : Ui.GOOD;
            row.addView(cell(Ui.date(r.p.s.startWall), w[0], Ui.TEXT, true));
            row.addView(cell(DashModel.f(r.p.s.socStart, 0) + "%", w[1], Ui.EV, false));
            row.addView(cell(DashModel.f(r.tMin, 0) + " min @ " + DashModel.f(r.soc, 0) + "%", w[2], Ui.TEXT, false));
            row.addView(cell(DashModel.f(r.target, 0) + "%", w[3], Ui.ENG, true));
            row.addView(cell(Double.isNaN(gap) ? "—" : (gap > 0 ? "−" : "+") + DashModel.f(Math.abs(gap), 0) + "%", w[4], gapColor, true));
            row.addView(cell(DashModel.f(r.p.s.avgRpm(), 0) + " / " + DashModel.f(r.p.s.maxRpm(), 0), w[5], Ui.TEXT, false));
            row.addView(cell(DashModel.f(r.p.dist > 0.3 ? r.p.fuel / r.p.dist * 100 : Double.NaN, 1), w[6], Ui.ENG, true));
            row.addView(cell(DashModel.f(r.p.dist, 1) + " km", w[7], Ui.TEXT, false));
            row.setOnClickListener(v -> openTrip(idx));
            forcedTable.addView(row);
        }
    }

    private void refreshTimeline() {
        tlTrips = model.timelineTrips();
        tlHolder.removeAllViews();
        if (tlTrips.isEmpty()) {
            timeline.setData(null, null);
            tlHolder.addView(Ui.text(this, "No trips with detailed logging in the current filters.", 15, Ui.MUTED, false));
            return;
        }
        String[] labels = new String[tlTrips.size()];
        int sel = 0;
        boolean found = false;
        for (int i = 0; i < labels.length; i++) {
            DashModel.Pt p = model.pts.get(tlTrips.get(i));
            boolean forced = p.s.forcedStart() != null;
            labels[i] = Ui.date(p.s.startWall) + "  ·  " + DashModel.f(p.dist, 0) + " km" + (forced ? "  ·  Forced" : "");
            if (forced && !found) { sel = i; found = true; }       // الافتراضي: أول رحلة فيها Forced
        }
        tlChoice = sel;
        tlHolder.addView(labeled("Trip", Widgets.spinner(this, labels, sel, i -> { tlChoice = i; showTimeline(); })));
        showTimeline();
    }

    private void showTimeline() {
        if (tlChoice < 0 || tlChoice >= tlTrips.size()) return;
        timeline.setData(model.timeline(tlTrips.get(tlChoice)), tip -> tlTip.setText(tip));
        tlTip.setText("Tap the chart for values");
    }

    // ============================================================ تبويب 2: Overview
    private LinearLayout buildOverviewTab() {
        LinearLayout t = vbox();
        LinearLayout ic = cardWith("Insights", null);
        insightsBox = vbox();
        ic.addView(insightsBox);
        t.addView(ic, fullLp());

        LinearLayout barCard = cardWith("Compare by", "Average for each group of driving windows (needs trips recorded with detailed logging).");
        LinearLayout br = Ui.row(this);
        br.addView(labeled("Group", Widgets.spinner(this, DashModel.GROUP, 0, i -> { gBy = i; refreshBars(); })));
        br.addView(labeled("Metric", Widgets.spinner(this, DashModel.GMETRIC, gMetric, i -> { gMetric = i; refreshBars(); })));
        barCard.addView(br);
        bars = new BarsView(this);
        barCard.addView(bars, new LinearLayout.LayoutParams(-1, Ui.dp(this, 320)));
        t.addView(barCard, fullLp());
        return t;
    }

    private void refreshOverview() {
        insightLines(insightsBox, model.insights());
        refreshBars();
    }

    private void refreshBars() { bars.setBars(model.bars(gBy, gMetric)); }

    // ============================================================ تبويب 3: Maps & scatter
    private LinearLayout buildMapsTab() {
        LinearLayout t = vbox();
        LinearLayout rowA = hrow();
        LinearLayout heatCard = cardWith("Consumption map", "Speed bands (rows) against acceleration, battery level or outside temperature.");
        LinearLayout hc = Ui.row(this);
        hc.addView(labeled("Map", Widgets.spinner(this, DashModel.MAP_TYPE, 0, i -> { mapType = i; refreshHeat(); })));
        hc.addView(labeled("Metric", Widgets.spinner(this, DashModel.MAP_METRIC, 0, i -> { mapMetric = i; refreshHeat(); })));
        heatCard.addView(hc);
        heat = new HeatmapView(this);
        heatCard.addView(heat, new LinearLayout.LayoutParams(-1, -2));
        heatTip = Ui.text(this, "Tap a cell for details", 13, Ui.MUTED, false);
        heatCard.addView(heatTip);
        rowA.addView(heatCard, cardLp(1.15f));

        LinearLayout tripCard = cardWith("Trip efficiency scatter", "One dot per trip; size = distance.");
        LinearLayout tcr = Ui.row(this);
        tcr.addView(labeled("X", Widgets.spinner(this, DashModel.TX, 0, i -> { tx = i; refreshTripScatter(); })));
        tcr.addView(labeled("Y", Widgets.spinner(this, DashModel.TY, 0, i -> { ty = i; refreshTripScatter(); })));
        tcr.addView(labeled("Color", Widgets.spinner(this, DashModel.TCOLOR, 0, i -> { tc = i; refreshTripScatter(); })));
        tripCard.addView(tcr);
        tripScat = new ScatterView(this);
        tripCard.addView(tripScat, new LinearLayout.LayoutParams(-1, Ui.dp(this, 330)));
        tripTip = Ui.text(this, "Tap a point", 13, Ui.MUTED, false);
        tripCard.addView(tripTip);
        openBtn = Ui.button(this, "Open trip report", Ui.EV, v -> openTrip(selTrip));
        openBtn.setTextSize(14);
        openBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams oblp = new LinearLayout.LayoutParams(-2, -2);
        oblp.topMargin = Ui.dp(this, 6);
        tripCard.addView(openBtn, oblp);
        rowA.addView(tripCard, cardLp(1f));
        t.addView(rowA);

        LinearLayout winCard = cardWith("Operating points (30 s windows)", "Each dot is 30 seconds of driving: how battery power, fuel flow and RPM change with speed, temperature or battery level.");
        LinearLayout wr = Ui.row(this);
        wr.addView(labeled("X", Widgets.spinner(this, DashModel.WX, 0, i -> { wx = i; refreshWinScatter(); })));
        wr.addView(labeled("Y", Widgets.spinner(this, DashModel.WY, 0, i -> { wy = i; refreshWinScatter(); })));
        wr.addView(labeled("Color", Widgets.spinner(this, DashModel.WCOLOR, 0, i -> { wc = i; refreshWinScatter(); })));
        winCard.addView(wr);
        winScat = new ScatterView(this);
        winCard.addView(winScat, new LinearLayout.LayoutParams(-1, Ui.dp(this, 330)));
        winTip = Ui.text(this, "Tap a point for details", 13, Ui.MUTED, false);
        winCard.addView(winTip);
        t.addView(winCard, fullLp());
        return t;
    }

    private void refreshMaps() {
        refreshHeat();
        refreshTripScatter();
        refreshWinScatter();
    }

    private void refreshHeat() {
        heat.setHeat(model.heat(mapType, mapMetric), tip -> heatTip.setText(tip));
        heatTip.setText("Tap a cell for details");
    }

    private void refreshTripScatter() {
        DashModel.Scat s = model.tripScatter(tx, ty, tc);
        tripScat.setData(s, DashModel.TX[tx], DashModel.TY[ty], true, ref -> {
            selTrip = ref;
            tripTip.setText(model.tripTip(ref));
            openBtn.setVisibility(View.VISIBLE);
        });
        tripTip.setText("Tap a point");
        openBtn.setVisibility(View.GONE);
        selTrip = -1;
    }

    private void refreshWinScatter() {
        DashModel.Scat s = model.winScatter(wx, wy, wc);
        winScat.setData(s, DashModel.WX[wx], DashModel.WY[wy], false, ref -> winTip.setText(model.winTip(ref)));
        winTip.setText(model.wins.isEmpty() ? "Needs trips recorded after this update" : "Tap a point for details");
    }
}
