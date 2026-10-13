package com.newtrip.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * لوحة التحليل الذكية: فلاتر منسدلة (الفترة، النمط، Hybrid/Electric، وضع SOC، الهدف، الحرارة)
 * ومؤشرات رئيسية وملاحظات تلقائية وخريطة استهلاك ومخططات تشتتية للكفاءة وأعمدة مقارنة.
 */
public class DashboardActivity extends Activity {
    private interface Pick { void pick(int i); }

    private DashModel model;
    private LinearLayout root, bodyHolder, kpiRow, insightsBox;
    private HeatmapView heat;
    private ScatterView tripScat, winScat;
    private BarsView bars;
    private TextView heatTip, tripTip, winTip, openBtn;
    private int selTrip = -1;
    private int mapType, mapMetric, tx, ty = 0, tc, wx, wy, wc, gBy, gMetric = 2;
    private final List<Spinner> filterSpinners = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiScale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        UiScale.applyWindow(this);

        root = new LinearLayout(this);
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

    // ------------------------------------------------------------ بناء الواجهة
    private void buildBody(boolean noTrips) {
        bodyHolder.removeAllViews();
        if (noTrips) {
            bodyHolder.addView(Ui.text(this, "No trips recorded yet. Finish a trip to see analytics.", 18, Ui.MUTED, false));
            return;
        }
        // شريط الفلاتر
        LinearLayout fr = Ui.row(this);
        fr.setGravity(Gravity.BOTTOM);
        fr.addView(filter("Period", DashModel.PERIOD, i -> { model.period = i; changed(); }));
        fr.addView(filter("Mode", DashModel.MODE, i -> { model.mode = i; changed(); }));
        fr.addView(filter("Drive type", DashModel.DRIVE, i -> { model.drive = i; changed(); }));
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

        LinearLayout ic = Ui.card(this);
        ic.addView(Ui.text(this, "Insights", 20, Ui.TEXT, true));
        insightsBox = new LinearLayout(this);
        insightsBox.setOrientation(LinearLayout.VERTICAL);
        ic.addView(insightsBox);
        c.addView(ic, fullLp(6));

        // الصف الأول: خريطة الاستهلاك + تشتت الرحلات
        LinearLayout rowA = Ui.row(this);
        rowA.setBaselineAligned(false);
        LinearLayout heatCard = Ui.card(this);
        heatCard.addView(Ui.text(this, "Consumption map", 20, Ui.TEXT, true));
        LinearLayout hc = Ui.row(this);
        hc.addView(labeled("Map", spinner(DashModel.MAP_TYPE, 0, i -> { mapType = i; refreshHeat(); })));
        hc.addView(labeled("Metric", spinner(DashModel.MAP_METRIC, 0, i -> { mapMetric = i; refreshHeat(); })));
        heatCard.addView(hc);
        heat = new HeatmapView(this);
        heatCard.addView(heat, new LinearLayout.LayoutParams(-1, -2));
        heatTip = Ui.text(this, "Tap a cell for details", 13, Ui.MUTED, false);
        heatCard.addView(heatTip);
        rowA.addView(heatCard, cardLp(1.15f, 6));

        LinearLayout tripCard = Ui.card(this);
        tripCard.addView(Ui.text(this, "Trip efficiency scatter", 20, Ui.TEXT, true));
        LinearLayout tcr = Ui.row(this);
        tcr.addView(labeled("X", spinner(DashModel.TX, 0, i -> { tx = i; refreshTripScatter(); })));
        tcr.addView(labeled("Y", spinner(DashModel.TY, 0, i -> { ty = i; refreshTripScatter(); })));
        tcr.addView(labeled("Color", spinner(DashModel.TCOLOR, 0, i -> { tc = i; refreshTripScatter(); })));
        tripCard.addView(tcr);
        tripScat = new ScatterView(this);
        tripCard.addView(tripScat, new LinearLayout.LayoutParams(-1, Ui.dp(this, 330)));
        tripTip = Ui.text(this, "Tap a point (size = distance)", 13, Ui.MUTED, false);
        tripCard.addView(tripTip);
        openBtn = Ui.button(this, "Open trip report", Ui.EV, v -> openSelected());
        openBtn.setTextSize(14);
        openBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams oblp = new LinearLayout.LayoutParams(-2, -2);
        oblp.topMargin = Ui.dp(this, 6);
        tripCard.addView(openBtn, oblp);
        rowA.addView(tripCard, cardLp(1f, 6));
        c.addView(rowA);

        // الصف الثاني: نقاط التشغيل + الأعمدة المجمّعة
        LinearLayout rowB = Ui.row(this);
        rowB.setBaselineAligned(false);
        LinearLayout winCard = Ui.card(this);
        winCard.addView(Ui.text(this, "Operating points (30 s windows)", 20, Ui.TEXT, true));
        LinearLayout wr = Ui.row(this);
        wr.addView(labeled("X", spinner(DashModel.WX, 0, i -> { wx = i; refreshWinScatter(); })));
        wr.addView(labeled("Y", spinner(DashModel.WY, 0, i -> { wy = i; refreshWinScatter(); })));
        wr.addView(labeled("Color", spinner(DashModel.WCOLOR, 0, i -> { wc = i; refreshWinScatter(); })));
        winCard.addView(wr);
        winScat = new ScatterView(this);
        winCard.addView(winScat, new LinearLayout.LayoutParams(-1, Ui.dp(this, 330)));
        winTip = Ui.text(this, "Tap a point for details", 13, Ui.MUTED, false);
        winCard.addView(winTip);
        rowB.addView(winCard, cardLp(1.15f, 6));

        LinearLayout barCard = Ui.card(this);
        barCard.addView(Ui.text(this, "Compare by", 20, Ui.TEXT, true));
        LinearLayout br = Ui.row(this);
        br.addView(labeled("Group", spinner(DashModel.GROUP, 0, i -> { gBy = i; refreshBars(); })));
        br.addView(labeled("Metric", spinner(DashModel.GMETRIC, gMetric, i -> { gMetric = i; refreshBars(); })));
        barCard.addView(br);
        bars = new BarsView(this);
        barCard.addView(bars, new LinearLayout.LayoutParams(-1, Ui.dp(this, 330)));
        rowB.addView(barCard, cardLp(1f, 6));
        c.addView(rowB);

        refreshAll();
    }

    /** بطاقة في صف أفقي: عرض بالوزن. */
    private LinearLayout.LayoutParams cardLp(float weight, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        int m = Ui.dp(this, marginDp);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    /** بطاقة في عمود رأسي: عرض كامل. */
    private LinearLayout.LayoutParams fullLp(int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        int m = Ui.dp(this, marginDp);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private View filter(String label, String[] items, Pick cb) {
        Spinner sp = spinner(items, 0, cb);
        filterSpinners.add(sp);
        View v = labeled(label, sp);
        return v;
    }

    private View labeled(String label, Spinner sp) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, Ui.dp(this, 10), 0);
        col.addView(Ui.text(this, label, 12, Ui.MUTED, false));
        col.addView(sp);
        return col;
    }

    private Spinner spinner(String[] items, int sel, Pick cb) {
        final Spinner sp = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                return style((TextView) super.getView(pos, cv, parent), false);
            }
            @Override public View getDropDownView(int pos, View cv, ViewGroup parent) {
                return style((TextView) super.getDropDownView(pos, cv, parent), true);
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(sel, false);
        sp.setBackground(Ui.round(Ui.CARD2, Ui.dp(this, 10)));
        sp.setPopupBackgroundDrawable(Ui.round(Ui.CARD2, Ui.dp(this, 10)));
        sp.setMinimumWidth(Ui.dp(this, 130));
        final int[] last = {sel};
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == last[0]) return;
                last[0] = position;
                cb.pick(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        return sp;
    }

    private TextView style(TextView t, boolean drop) {
        t.setTextColor(Ui.TEXT);
        t.setTextSize(drop ? 17 : 16);
        t.setSingleLine(true);
        int pd = Ui.dp(this, drop ? 14 : 10);
        t.setPadding(pd, Ui.dp(this, drop ? 12 : 8), pd, Ui.dp(this, drop ? 12 : 8));
        if (drop) t.setBackgroundColor(Ui.CARD2);
        return t;
    }

    private void resetFilters() {
        if (model == null) return;
        model.period = model.mode = model.drive = model.soc = model.target = model.temp = 0;
        for (Spinner s : filterSpinners) s.setSelection(0, false);
        changed();
    }

    // ------------------------------------------------------------ التحديث
    private void changed() {
        model.recompute();
        selTrip = -1;
        refreshAll();
    }

    private void refreshAll() {
        refreshKpis();
        refreshInsights();
        refreshHeat();
        refreshTripScatter();
        refreshWinScatter();
        refreshBars();
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

    private void kpi(String label, String value, int color) {
        TextView v = Ui.tile(kpiRow, label, color);
        v.setTextSize(22);
        v.setText(value);
    }

    private void refreshInsights() {
        insightsBox.removeAllViews();
        for (String s : model.insights()) {
            TextView t = Ui.text(this, s, 16, s.startsWith("These are") || s.startsWith("ℹ") ? Ui.MUTED : Ui.TEXT, false);
            t.setPadding(0, Ui.dp(this, 5), 0, 0);
            insightsBox.addView(t);
        }
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
        tripTip.setText("Tap a point (size = distance)");
        openBtn.setVisibility(View.GONE);
        selTrip = -1;
    }

    private void refreshWinScatter() {
        DashModel.Scat s = model.winScatter(wx, wy, wc);
        winScat.setData(s, DashModel.WX[wx], DashModel.WY[wy], false, ref -> winTip.setText(model.winTip(ref)));
        winTip.setText(model.wins.isEmpty() ? "Needs trips recorded after this update" : "Tap a point for details");
    }

    private void refreshBars() {
        bars.setBars(model.bars(gBy, gMetric));
    }

    private void openSelected() {
        if (selTrip < 0 || selTrip >= model.pts.size()) return;
        TripSession s = model.pts.get(selTrip).s;
        if (s.file == null) return;
        startActivity(new Intent(this, ReportActivity.class).putExtra(ReportActivity.EXTRA_FILE, s.file.getAbsolutePath()));
    }
}
