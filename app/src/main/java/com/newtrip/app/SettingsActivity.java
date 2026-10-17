package com.newtrip.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** الإعدادات: التخزين وحدّه، الإنهاء التلقائي عند إطفاء السيارة، التصدير التلقائي، التحديث التلقائي، حول التطبيق وإلغاء التثبيت. */
public class SettingsActivity extends Activity {
    private static final int[] LIMITS_MB = {50, 100, 200, 500, 1000, 2000};

    private LinearLayout storageBox, tripModeBox, languageBox;

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
        header.addView(Ui.text(this, "Settings", 28, Ui.TEXT, true));
        root.addView(header);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svlp.topMargin = Ui.dp(this, 10);
        root.addView(sv, svlp);

        // ---- التخزين ----
        LinearLayout st = Ui.card(this);
        st.addView(Ui.text(this, "Storage", 20, Ui.TEXT, true));
        storageBox = new LinearLayout(this);
        storageBox.setOrientation(LinearLayout.VERTICAL);
        st.addView(storageBox);
        LinearLayout lim = Ui.row(this);
        lim.setGravity(Gravity.CENTER_VERTICAL);
        lim.setPadding(0, Ui.dp(this, 8), 0, 0);
        lim.addView(Ui.text(this, "Warn me when trip files exceed", 17, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        String[] labels = new String[LIMITS_MB.length];
        int sel = 1;
        for (int i = 0; i < LIMITS_MB.length; i++) {
            labels[i] = LIMITS_MB[i] >= 1000 ? (LIMITS_MB[i] / 1000) + " GB" : LIMITS_MB[i] + " MB";
            if (LIMITS_MB[i] == Settings.storageLimitMb(this)) sel = i;
        }
        lim.addView(Widgets.spinner(this, labels, sel, i -> { Settings.setStorageLimitMb(this, LIMITS_MB[i]); refreshStorage(); }));
        st.addView(lim);
        LinearLayout actions = Ui.row(this);
        actions.setPadding(0, Ui.dp(this, 10), 0, 0);
        actions.addView(action("Export all trips (CSV)", Ui.EV, v -> exportAll()));
        actions.addView(action("Delete old trips…", Ui.CARD2, v -> deleteOld()));
        actions.addView(action("Clear exports", Ui.CARD2, v -> clearExports()));
        st.addView(actions);
        col.addView(st, cardLp());

        // ---- تسجيل الرحلات ----
        LinearLayout tr = Ui.card(this);
        tr.addView(Ui.text(this, "Trip recording", 20, Ui.TEXT, true));
        tripModeBox = new LinearLayout(this);
        tripModeBox.setOrientation(LinearLayout.VERTICAL);
        tr.addView(tripModeBox);
        renderTripMode();
        LinearLayout ex = Ui.row(this);
        ex.setGravity(Gravity.CENTER_VERTICAL);
        ex.setPadding(0, Ui.dp(this, 8), 0, 0);
        ex.addView(Ui.text(this, "Auto-export when a trip ends", 17, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        ex.addView(Widgets.spinner(this, new String[]{"Off", "PDF", "CSV"}, Settings.autoExport(this),
                i -> Settings.setAutoExport(this, i)));
        tr.addView(ex);
        col.addView(tr, cardLp());

        // ---- اللغة ----
        LinearLayout lg = Ui.card(this);
        lg.addView(Ui.text(this, "Language", 20, Ui.TEXT, true));
        languageBox = new LinearLayout(this);
        languageBox.setOrientation(LinearLayout.VERTICAL);
        lg.addView(languageBox);
        renderLanguage();
        col.addView(lg, cardLp());

        // ---- التحديث ----
        LinearLayout up = Ui.card(this);
        up.addView(Ui.text(this, "Updates", 20, Ui.TEXT, true));
        up.addView(Widgets.toggleRow(this, "Update automatically",
                "Checks every 6 hours and installs when no trip is running (for example after the car is switched off).",
                Settings.autoUpdate(this), v -> Settings.setAutoUpdate(this, v)));
        LinearLayout ur = Ui.row(this);
        ur.setPadding(0, Ui.dp(this, 8), 0, 0);
        ur.addView(action("Check for update now", Ui.EV, v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_CHECK_UPDATE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        }));
        up.addView(ur);
        col.addView(up, cardLp());

        // ---- حول ----
        LinearLayout ab = Ui.card(this);
        ab.addView(Ui.text(this, "About", 20, Ui.TEXT, true));
        ab.addView(Ui.text(this, "New Trip  v" + Updater.installedName(this), 17, Ui.TEXT, false));
        ab.addView(Ui.text(this, Settings.COPYRIGHT, 14, Ui.MUTED, false));
        LinearLayout ar = Ui.row(this);
        ar.setPadding(0, Ui.dp(this, 10), 0, 0);
        ar.addView(action("Uninstall New Trip", Ui.BAD, v -> confirmUninstall()));
        ab.addView(ar);
        col.addView(ab, cardLp());

        setContentView(root);
        refreshStorage();
    }

    /** صف خيار (راديو): نقطة + عنوان + شرح. */
    private View optionRow(String title, String sub, boolean selected, View.OnClickListener l) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pd = Ui.dp(this, 10);
        row.setPadding(pd, pd, pd, pd);
        row.setBackground(Ui.round(selected ? 0x3329B6F6 : 0x00000000, Ui.dp(this, 12)));
        TextView dot = Ui.text(this, selected ? "●" : "○", 24, selected ? Ui.EV : Ui.MUTED, true);
        dot.setPadding(0, 0, Ui.dp(this, 12), 0);
        row.addView(dot);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, title, 17, Ui.TEXT, true));
        if (sub != null) col.addView(Ui.text(this, sub, 13, Ui.MUTED, false));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 4);
        row.setLayoutParams(lp);
        return row;
    }

    private void renderTripMode() {
        tripModeBox.removeAllViews();
        int mode = Settings.tripMode(this);
        tripModeBox.addView(optionRow("Manual start (current)",
                "You press Start. The trip ends when you press End, or automatically when the car is switched off.",
                mode == Settings.MODE_MANUAL, v -> setTripMode(Settings.MODE_MANUAL)));
        tripModeBox.addView(optionRow("Automatic start and end",
                "A trip starts by itself when the car is on and moving, and ends when the car is switched off. You can still press Start or End yourself.",
                mode == Settings.MODE_AUTO, v -> setTripMode(Settings.MODE_AUTO)));
        if (mode == Settings.MODE_MANUAL) {
            tripModeBox.addView(Widgets.toggleRow(this, "End trip automatically when the car is switched off",
                    "Uses the car power state (OFF, or ACC for 90 s). A trip left open is also closed after a long gap.",
                    Settings.autoEnd(this), v -> Settings.setAutoEnd(this, v)));
        } else {
            TextView n = Ui.text(this, "Automatic mode keeps a small notification running so it can notice when the car starts moving.", 13, Ui.MUTED, false);
            n.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), 0, 0);
            tripModeBox.addView(n);
        }
    }

    private void setTripMode(int mode) {
        if (Settings.tripMode(this) == mode) return;
        Settings.setTripMode(this, mode);
        TripService.syncWithSettings(this);
        renderTripMode();
    }

    private void renderLanguage() {
        languageBox.removeAllViews();
        int l = Settings.language(this);
        String sys = Settings.systemLanguage();
        String autoSub = sys == null ? "The car language could not be read, so please choose below."
                : Tr.f("Detected from the car: %s", sys.equals("ar") ? "العربية" : "English");
        languageBox.addView(optionRow("Automatic (car language)", autoSub, l == Settings.LANG_AUTO, v -> pickLanguage(Settings.LANG_AUTO)));
        languageBox.addView(optionRow("English", null, l == Settings.LANG_EN, v -> pickLanguage(Settings.LANG_EN)));
        languageBox.addView(optionRow("العربية", null, l == Settings.LANG_AR, v -> pickLanguage(Settings.LANG_AR)));
    }

    private void pickLanguage(int l) {
        if (Settings.language(this) == l && l != Settings.LANG_AUTO) return;
        Settings.setLanguage(this, l);
        recreate();
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 12);
        return lp;
    }

    private TextView action(String label, int color, View.OnClickListener l) {
        TextView t = Ui.button(this, label, color, l);
        t.setTextSize(15);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(Ui.dp(this, 10));
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------ التخزين
    private void refreshStorage() {
        storageBox.removeAllViews();
        SessionStore store = new SessionStore(this);
        long trips = StorageMonitor.dirSize(store.historyDir());
        long exports = StorageMonitor.dirSize(Exporter.exportsDir(this));
        long limit = StorageMonitor.limitBytes(this);
        long used = trips + exports;
        int n = store.history().size();
        boolean over = used > limit;
        storageBox.addView(Ui.text(this, Tr.f("%s of %s MB   (%s trips %s, exports %s)", StorageMonitor.human(used),
                Settings.storageLimitMb(this), n, StorageMonitor.human(trips), StorageMonitor.human(exports)),
                17, over ? Ui.BAD : Ui.TEXT, true));
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.round(Ui.CARD2, Ui.dp(this, 6)));
        float frac = Math.min(1f, limit > 0 ? used / (float) limit : 1f);
        View fill = new View(this);
        fill.setBackground(Ui.round(over ? Ui.BAD : Ui.EV, Ui.dp(this, 6)));
        bar.addView(fill, new LinearLayout.LayoutParams(0, Ui.dp(this, 10), Math.max(0.001f, frac)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, Ui.dp(this, 10), Math.max(0.001f, 1f - frac)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 8);
        storageBox.addView(bar, blp);
        if (over) {
            TextView w = Ui.text(this, "⚠ Over the limit. Export what you need, then delete old trips.", 15, Ui.BAD, false);
            w.setPadding(0, Ui.dp(this, 6), 0, 0);
            storageBox.addView(w);
        }
    }

    private void exportAll() {
        Toast.makeText(this, Tr.t("Exporting…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<TripSession> all = new ArrayList<>();
                for (File f : new SessionStore(this).history()) {
                    TripSession s = SessionStore.read(f);
                    if (s != null) all.add(s);
                }
                if (all.isEmpty()) { runOnUiThread(() -> Toast.makeText(this, Tr.t("No trips to export"), Toast.LENGTH_LONG).show()); return; }
                List<File> out = Exporter.exportCsv(this, all);
                runOnUiThread(() -> { refreshStorage(); Exporter.offer(this, out, Exporter.MIME_CSV); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, Tr.f("Export failed: %s", e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }, "export-all").start();
    }

    private void deleteOld() {
        final int[] days = {30, 90, 180, 365};
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("Delete trips older than"))
                .setItems(Tr.all(new String[]{"30 days", "90 days", "180 days", "1 year"}), (d, which) -> {
                    long cutoff = System.currentTimeMillis() - days[which] * 86400000L;
                    SessionStore st = new SessionStore(this);
                    List<File> old = new ArrayList<>();
                    for (File f : st.history()) if (startOf(f) < cutoff) old.add(f);
                    if (old.isEmpty()) { Toast.makeText(this, Tr.t("Nothing older than that"), Toast.LENGTH_LONG).show(); return; }
                    new AlertDialog.Builder(this)
                            .setMessage(Tr.f("Delete %s trips? This cannot be undone. Export them first if you need them.", old.size()))
                            .setPositiveButton(Tr.t("Delete"), (d2, w2) -> { for (File f : old) st.delete(f); refreshStorage(); })
                            .setNegativeButton(Tr.t("Cancel"), null)
                            .show();
                })
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    /** وقت بداية الرحلة من اسم الملف trip_<ms>.json */
    private static long startOf(File f) {
        try { return Long.parseLong(f.getName().replace("trip_", "").replace(".json", "")); }
        catch (Exception e) { return f.lastModified(); }
    }

    private void clearExports() {
        File[] fs = Exporter.exportsDir(this).listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.delete()) n++;
        Toast.makeText(this, Tr.f("%s export files deleted", n), Toast.LENGTH_SHORT).show();
        refreshStorage();
    }

    // ------------------------------------------------------------ إلغاء التثبيت
    private void confirmUninstall() {
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("Uninstall New Trip?"))
                .setMessage(Tr.t("The app and all saved trips will be removed from this screen. Export your trips first if you want to keep them."))
                .setPositiveButton(Tr.t("Uninstall"), (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        Toast.makeText(this, Tr.t("This screen has no uninstall dialog. Use system Settings > Apps > New Trip."),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }
}
