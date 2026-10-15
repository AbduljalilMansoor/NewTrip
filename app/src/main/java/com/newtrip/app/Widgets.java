package com.newtrip.app;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/** عناصر واجهة مشتركة: قائمة منسدلة بنمط التطبيق، وصف تبديل ON/OFF. */
final class Widgets {
    private Widgets() { }

    interface Pick { void pick(int index); }
    interface Toggle { void on(boolean value); }

    static Spinner spinner(Context c, String[] items, int sel, Pick cb) {
        final Spinner sp = new Spinner(c, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                return style(c, (TextView) super.getView(pos, cv, parent), false);
            }
            @Override public View getDropDownView(int pos, View cv, ViewGroup parent) {
                return style(c, (TextView) super.getDropDownView(pos, cv, parent), true);
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(sel, false);
        sp.setBackground(Ui.round(Ui.CARD2, Ui.dp(c, 10)));
        sp.setPopupBackgroundDrawable(Ui.round(Ui.CARD2, Ui.dp(c, 10)));
        sp.setMinimumWidth(Ui.dp(c, 130));
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

    private static TextView style(Context c, TextView t, boolean drop) {
        t.setTextColor(Ui.TEXT);
        t.setTextSize(drop ? 17 : 16);
        t.setSingleLine(true);
        int pd = Ui.dp(c, drop ? 14 : 10);
        t.setPadding(pd, Ui.dp(c, drop ? 12 : 8), pd, Ui.dp(c, drop ? 12 : 8));
        if (drop) t.setBackgroundColor(Ui.CARD2);
        return t;
    }

    /** صف: عنوان + شرح صغير، ويمينه زر ON/OFF. */
    static LinearLayout toggleRow(Context c, String title, String sub, boolean initial, Toggle cb) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(c, title, 17, Ui.TEXT, true));
        if (sub != null) col.addView(Ui.text(c, sub, 13, Ui.MUTED, false));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        final boolean[] v = {initial};
        final TextView pill = Ui.text(c, initial ? "ON" : "OFF", 16, Ui.TEXT, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(Ui.dp(c, 22), Ui.dp(c, 8), Ui.dp(c, 22), Ui.dp(c, 8));
        pill.setBackground(Ui.round(initial ? Ui.GOOD : Ui.CARD2, Ui.dp(c, 16)));
        pill.setOnClickListener(x -> {
            v[0] = !v[0];
            pill.setText(v[0] ? "ON" : "OFF");
            pill.setBackground(Ui.round(v[0] ? Ui.GOOD : Ui.CARD2, Ui.dp(c, 16)));
            cb.on(v[0]);
        });
        row.addView(pill);
        return row;
    }
}
