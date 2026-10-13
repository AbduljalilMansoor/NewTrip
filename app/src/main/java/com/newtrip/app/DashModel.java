package com.newtrip.app;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * نموذج لوحة التحليل (بلا واجهة): يطبّق الفلاتر على كل الرحلات ثم يبني
 * خرائط الاستهلاك، والمخططات التشتتية، والأعمدة المجمّعة، والملاحظات التلقائية.
 * مصادر البيانات: خلايا الرحلة (للرحلات القديمة والجديدة)، وشبكة السرعة×التسارع ونوافذ الـ30 ثانية (الرحلات الجديدة).
 */
final class DashModel {
    static final String[] PERIOD = {"All time", "Today", "Last 7 days", "Last 30 days"};
    static final String[] MODE = {"ALL", "Park", "Eco", "Normal", "Sport", "Others"};
    static final int[] MODE_ID = {-1, TripSession.PARK, TripSession.ECO, TripSession.NORMAL, TripSession.SPORT, TripSession.OTHERS};
    static final String[] DRIVE = {"All", "Hybrid", "Electric"};
    static final String[] SOC = {"All", "Smart Power Save", "Forced Power Saving"};
    static final String[] TARGET = {"Any", "25%", "40%", "55%", "70%"};
    static final int[] TARGET_V = {0, 25, 40, 55, 70};
    static final String[] TEMP = {"Any", "< 25 °C", "25–35 °C", "35–42 °C", "> 42 °C"};

    static final String[] MAP_TYPE = {"Speed × Acceleration", "Speed × Battery level", "Speed × Outside temp"};
    static final String[] MAP_METRIC = {"Fuel (L/100km)", "Battery net (kWh/100km)", "Engine-on share (%)", "Time share (%)"};
    static final String[] TX = {"Avg speed (km/h)", "Distance (km)", "Duration (min)", "Outside temp (°C)", "EV share (%)", "Start battery (%)", "Battery used (%)"};
    static final String[] TY = {"Fuel (L/100km)", "Battery (kWh/100km)", "Fuel used (L)", "Battery energy (kWh)", "EV share (%)", "Battery % per 10 km"};
    static final String[] TCOLOR = {"SOC mode", "Drive type", "Dominant mode", "None"};
    static final String[] WX = {"Speed (km/h)", "Outside temp (°C)", "Battery level (%)"};
    static final String[] WY = {"Battery net (kW)", "Fuel flow (L/h)", "Fuel (L/100km)", "Peak battery power (kW)"};
    static final String[] WCOLOR = {"Engine on/off", "SOC mode", "Mode"};
    static final String[] GROUP = {"SOC setting", "Drive mode", "Outside temp", "Speed band", "Time of day"};
    static final String[] GMETRIC = {"Fuel (L/100km)", "Battery net (kWh/100km)", "Energy-eq (kWh/100km)", "EV time share (%)", "Distance (km)"};

    static final String[] MODE_NAMES = new String[TripSession.NUM_MODES];
    static {
        MODE_NAMES[TripSession.PARK] = "Park"; MODE_NAMES[TripSession.ECO] = "Eco";
        MODE_NAMES[TripSession.NORMAL] = "Normal"; MODE_NAMES[TripSession.SPORT] = "Sport";
        MODE_NAMES[TripSession.OTHERS] = "Others";
    }
    static final int[] PAL = {0xFF29B6F6, 0xFFFFA726, 0xFF66BB6A, 0xFFEF5350, 0xFFAB47BC};
    /** 1 لتر بنزين ≈ 8.9 كيلوواط ساعة (لمقارنة الوقود والكهرباء بوحدة واحدة) */
    static final double KWH_PER_L = 8.9;

    int period, mode, drive, soc, target, temp;

    private final List<TripSession> all;
    final List<TripSession> trips = new ArrayList<>();
    final List<Pt> pts = new ArrayList<>();
    final List<float[]> wins = new ArrayList<>();
    final List<TripSession> winTrip = new ArrayList<>();
    int tripsNoDetail;

    DashModel(List<TripSession> all) { this.all = all; recompute(); }

    // ------------------------------------------------------------ نقطة رحلة
    static final class Pt {
        TripSession s;
        double dist, durH, fuel, out, in, evDist, temp;
        int domMode;
        double avgSpeed() { return durH > 0 ? dist / durH : Double.NaN; }
        double evShare() { return dist > 0 ? evDist / dist * 100 : Double.NaN; }
        double energyEq100() { return dist > 0.3 ? (fuel * KWH_PER_L + (out - in)) / dist * 100 : Double.NaN; }
    }

    // ------------------------------------------------------------ الفلاتر
    private boolean modeOk(int m) { return mode == 0 || MODE_ID[mode] == m; }

    private boolean tripOk(TripSession s) {
        long now = System.currentTimeMillis();
        if (period == 1) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            if (s.startWall < c.getTimeInMillis()) return false;
        } else if (period == 2) { if (s.startWall < now - 7L * 86400000L) return false;
        } else if (period == 3) { if (s.startWall < now - 30L * 86400000L) return false; }
        if (drive == 1 && !"Hybrid".equals(s.driveType())) return false;
        if (drive == 2 && !"Electric".equals(s.driveType())) return false;
        if (soc == 1 && !(s.forcedMs() == 0 && s.smartMs() > 0)) return false;
        if (soc == 2 && !(s.smartMs() == 0 && s.forcedMs() > 0)) return false;
        if (target > 0) {
            float[] r = s.targetRange();
            int v = TARGET_V[target];
            if (Float.isNaN(r[0]) || r[0] - 7 > v || r[1] + 7 < v) return false;
        }
        if (temp > 0) {
            double t = s.avgTempC();
            if (Double.isNaN(t)) return false;
            if (temp == 1 && t >= 25) return false;
            if (temp == 2 && (t < 25 || t >= 35)) return false;
            if (temp == 3 && (t < 35 || t >= 42)) return false;
            if (temp == 4 && t < 42) return false;
        }
        return true;
    }

    void recompute() {
        trips.clear(); pts.clear(); wins.clear(); winTrip.clear(); tripsNoDetail = 0;
        for (TripSession s : all) {
            if (!tripOk(s)) continue;
            Pt p = new Pt();
            p.s = s;
            double bestDist = -1;
            for (int m = 0; m < TripSession.NUM_MODES; m++) {
                if (!modeOk(m)) continue;
                TripSession.Cell ev = s.cells[m][TripSession.EV], en = s.cells[m][TripSession.ENG];
                double d = ev.distKm + en.distKm;
                p.dist += d; p.durH += (ev.durMs + en.durMs) / 3_600_000.0;
                p.fuel += ev.fuelL + en.fuelL; p.out += ev.kwhOut + en.kwhOut; p.in += ev.kwhIn + en.kwhIn;
                p.evDist += ev.distKm;
                if (d > bestDist) { bestDist = d; p.domMode = m; }
            }
            if (p.dist < 0.3) continue;
            p.temp = s.avgTempC();
            trips.add(s); pts.add(p);
            if (s.wins.isEmpty()) tripsNoDetail++;
            for (float[] w : s.wins) {
                if (!modeOk((int) w[TripSession.W_MODE])) continue;
                wins.add(w); winTrip.add(s);
            }
        }
    }

    // ------------------------------------------------------------ KPI
    static final class Kpi { int trips; double dist, durH, fuel, out, in, evDist, temp; }

    Kpi kpi() {
        Kpi k = new Kpi();
        double ts = 0, tw = 0;
        for (Pt p : pts) {
            k.trips++; k.dist += p.dist; k.durH += p.durH; k.fuel += p.fuel; k.out += p.out; k.in += p.in; k.evDist += p.evDist;
            if (!Double.isNaN(p.temp)) { ts += p.temp * p.durH; tw += p.durH; }
        }
        k.temp = tw > 0 ? ts / tw : Double.NaN;
        return k;
    }

    static String f(double v, int d) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        return String.format(Locale.US, "%." + d + "f", v);
    }

    // ------------------------------------------------------------ خريطة الاستهلاك
    static final class Acc { double dur, eng, dist, out, in, fuel; }

    static final class Heat {
        String[] rows, cols; double[][] val; String[][] tip; String unit; boolean cost; boolean any;
    }

    private static String speedLabel(int b) {
        return b >= TripSession.SPEED_BINS - 1 ? "130+" : (b * 10) + "–" + (b * 10 + 10);
    }

    private static int tempBand(double t) { return t < 25 ? 0 : t < 35 ? 1 : t < 42 ? 2 : 3; }

    Heat heat(int type, int metric) {
        int nr = TripSession.SPEED_BINS;
        int nc = type == 0 ? TripSession.ACCEL_BINS : type == 1 ? 10 : 4;
        Acc[][] a = new Acc[nr][nc];
        for (Acc[] r : a) for (int j = 0; j < nc; j++) r[j] = new Acc();
        if (type == 0) {
            for (TripSession s : trips)
                for (int m = 0; m < TripSession.NUM_MODES; m++) {
                    if (!modeOk(m)) continue;
                    for (int sb = 0; sb < nr; sb++) for (int ab = 0; ab < nc; ab++) {
                        TripSession.Gc g = s.grid[m][sb][ab];
                        if (g.empty()) continue;
                        Acc x = a[sb][ab];
                        x.dur += g.durMs / 1000.0; x.eng += g.engMs / 1000.0; x.dist += g.distKm;
                        x.out += g.out; x.in += g.in; x.fuel += g.fuel;
                    }
                }
        } else {
            for (float[] w : wins) {
                int col;
                if (type == 1) {
                    float soc = w[TripSession.W_SOC];
                    if (Float.isNaN(soc)) continue;
                    col = Math.max(0, Math.min(9, (int) (soc / 10f)));
                } else {
                    float t = w[TripSession.W_TEMP];
                    if (Float.isNaN(t)) continue;
                    col = tempBand(t);
                }
                Acc x = a[TripSession.speedBin(w[TripSession.W_SPD])][col];
                x.dur += w[TripSession.W_DUR]; x.eng += w[TripSession.W_ENG]; x.dist += w[TripSession.W_DIST];
                x.out += w[TripSession.W_OUT]; x.in += w[TripSession.W_IN]; x.fuel += w[TripSession.W_FUEL];
            }
        }
        double totDur = 0;
        int first = -1, last = -1;
        for (int r = 0; r < nr; r++) for (int c = 0; c < nc; c++) {
            totDur += a[r][c].dur;
            if (a[r][c].dur > 0) { if (first < 0) first = r; last = r; }
        }
        Heat h = new Heat();
        h.unit = metric == 0 ? "L/100km" : metric == 1 ? "kWh/100km" : "%";
        h.cost = metric <= 1;
        if (first < 0) { h.rows = new String[0]; h.cols = new String[0]; h.val = new double[0][0]; h.tip = new String[0][0]; return h; }
        int rows = last - first + 1;
        h.rows = new String[rows]; h.cols = new String[nc]; h.val = new double[rows][nc]; h.tip = new String[rows][nc];
        for (int c = 0; c < nc; c++) {
            h.cols[c] = type == 0 ? TripSession.ACCEL_NAMES[c]
                    : type == 1 ? (c * 10) + "–" + (c * 10 + 10) + "%" : TEMP[c + 1];
        }
        for (int r = 0; r < rows; r++) {
            h.rows[r] = speedLabel(first + r);
            for (int c = 0; c < nc; c++) {
                Acc x = a[first + r][c];
                double v = Double.NaN;
                if (metric == 0 && x.dist >= 0.5) v = x.fuel / x.dist * 100;
                else if (metric == 1 && x.dist >= 0.5) v = (x.out - x.in) / x.dist * 100;
                else if (metric == 2 && x.dur >= 20) v = x.eng / x.dur * 100;
                else if (metric == 3 && x.dur > 0 && totDur > 0) v = x.dur / totDur * 100;
                h.val[r][c] = v;
                if (!Double.isNaN(v)) h.any = true;
                h.tip[r][c] = x.dur <= 0 ? "No data" : h.rows[r] + " km/h  ×  " + h.cols[c] + "\n"
                        + f(x.dist, 1) + " km  ·  " + f(x.dur / 60, 1) + " min  ·  fuel " + f(x.fuel, 2)
                        + " L  ·  battery " + f(x.out - x.in, 2) + " kWh net  ·  engine on " + f(x.eng / x.dur * 100, 0) + "%";
            }
        }
        return h;
    }

    // ------------------------------------------------------------ مخططات تشتتية
    static final class Scat {
        float[] x, y, size; int[] color; int[] ref; String[] legend; boolean empty() { return x == null || x.length == 0; }
    }

    private double xval(Pt p, int i) {
        switch (i) {
            case 0: return p.avgSpeed();
            case 1: return p.dist;
            case 2: return p.durH * 60;
            case 3: return p.temp;
            case 4: return p.evShare();
            case 5: return p.s.socStart;
            default: return p.s.socStart - p.s.socEnd;
        }
    }

    private double yval(Pt p, int i) {
        switch (i) {
            case 0: return p.dist > 0.3 ? p.fuel / p.dist * 100 : Double.NaN;
            case 1: return p.dist > 0.3 ? (p.out - p.in) / p.dist * 100 : Double.NaN;
            case 2: return p.fuel;
            case 3: return p.out - p.in;
            case 4: return p.evShare();
            default: return p.dist > 0.3 ? (p.s.socStart - p.s.socEnd) / p.dist * 10 : Double.NaN;
        }
    }

    Scat tripScatter(int xi, int yi, int ci) {
        List<float[]> v = new ArrayList<>(); List<Integer> col = new ArrayList<>(), ref = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            Pt p = pts.get(i);
            double x = xval(p, xi), y = yval(p, yi);
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || Double.isInfinite(y)) continue;
            int c = 0;
            if (ci == 0) { String l = p.s.socModeLabel(); c = l.startsWith("Smart") ? 0 : l.startsWith("Forced") ? 1 : 2; }
            else if (ci == 1) { String l = p.s.driveType(); c = "Hybrid".equals(l) ? 0 : "Electric".equals(l) ? 1 : 2; }
            else if (ci == 2) c = p.domMode;
            v.add(new float[]{(float) x, (float) y, (float) Math.max(1, Math.min(60, p.dist))});
            col.add(c); ref.add(i);
        }
        Scat s = new Scat();
        int n = v.size();
        s.x = new float[n]; s.y = new float[n]; s.size = new float[n]; s.color = new int[n]; s.ref = new int[n];
        for (int i = 0; i < n; i++) {
            s.x[i] = v.get(i)[0]; s.y[i] = v.get(i)[1]; s.size[i] = v.get(i)[2];
            s.color[i] = col.get(i); s.ref[i] = ref.get(i);
        }
        s.legend = ci == 0 ? new String[]{"Smart", "Forced", "Mixed"}
                : ci == 1 ? new String[]{"Hybrid", "Electric", "Mixed"}
                : ci == 2 ? MODE_NAMES : new String[]{"Trips"};
        return s;
    }

    String tripTip(int ptIndex) {
        Pt p = pts.get(ptIndex);
        TripSession s = p.s;
        return Ui.date(s.startWall) + "\n" + f(p.dist, 1) + " km  ·  " + f(p.avgSpeed(), 0) + " km/h\n"
                + s.driveType() + "  ·  " + s.socModeLabel() + (s.targetLabel().equals("—") ? "" : "  ·  " + s.targetLabel()) + "\n"
                + "Fuel " + f(p.fuel, 2) + " L (" + f(yval(p, 0), 1) + " L/100km)  ·  Battery " + f(p.out - p.in, 1) + " kWh net\n"
                + "EV share " + f(p.evShare(), 0) + "%  ·  Temp " + f(p.temp, 0) + " °C"
                + (Float.isNaN(s.carAvgFuel) && Float.isNaN(s.carAvgEnergy) ? ""
                : "\nCar-reported averages: " + f(s.carAvgFuel, 1) + " L/100km  ·  " + f(s.carAvgEnergy, 1) + " kWh/100km");
    }

    Scat winScatter(int xi, int yi, int ci) {
        int n0 = wins.size();
        int stride = Math.max(1, n0 / 2500);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n0; i += stride) idx.add(i);
        float[] x = new float[idx.size()], y = new float[idx.size()], sz = new float[idx.size()];
        int[] c = new int[idx.size()], ref = new int[idx.size()];
        int n = 0;
        for (int i : idx) {
            float[] w = wins.get(i);
            double hrs = w[TripSession.W_DUR] / 3600.0;
            if (hrs <= 0) continue;
            double xv = xi == 0 ? w[TripSession.W_SPD] : xi == 1 ? w[TripSession.W_TEMP] : w[TripSession.W_SOC];
            double yv;
            switch (yi) {
                case 0: yv = (w[TripSession.W_OUT] - w[TripSession.W_IN]) / hrs; break;
                case 1: yv = w[TripSession.W_FUEL] / hrs; break;
                case 2: yv = w[TripSession.W_DIST] > 0.1 ? w[TripSession.W_FUEL] / w[TripSession.W_DIST] * 100 : Double.NaN; break;
                default: yv = w[TripSession.W_MAXKW];
            }
            if (Double.isNaN(xv) || Double.isNaN(yv) || Double.isInfinite(yv)) continue;
            x[n] = (float) xv; y[n] = (float) yv; sz[n] = 1;
            c[n] = ci == 0 ? (w[TripSession.W_ENG] / w[TripSession.W_DUR] > 0.5 ? 1 : 0)
                    : ci == 1 ? (w[TripSession.W_FORCED] > 0.5 ? 1 : 0) : (int) w[TripSession.W_MODE];
            ref[n] = i;
            n++;
        }
        Scat s = new Scat();
        s.x = java.util.Arrays.copyOf(x, n); s.y = java.util.Arrays.copyOf(y, n); s.size = java.util.Arrays.copyOf(sz, n);
        s.color = java.util.Arrays.copyOf(c, n); s.ref = java.util.Arrays.copyOf(ref, n);
        s.legend = ci == 0 ? new String[]{"EV (engine off)", "Engine on"} : ci == 1 ? new String[]{"Smart", "Forced"} : MODE_NAMES;
        return s;
    }

    String winTip(int i) {
        float[] w = wins.get(i);
        double hrs = w[TripSession.W_DUR] / 3600.0;
        return Ui.date(winTrip.get(i).startWall + (long) (w[TripSession.W_T] * 1000)) + "  ·  " + MODE_NAMES[(int) w[TripSession.W_MODE]] + "\n"
                + f(w[TripSession.W_SPD], 0) + " km/h  ·  battery " + f(w[TripSession.W_SOC], 0) + "%  ·  " + f(w[TripSession.W_TEMP], 0) + " °C\n"
                + "Net " + f((w[TripSession.W_OUT] - w[TripSession.W_IN]) / hrs, 1) + " kW  ·  fuel " + f(w[TripSession.W_FUEL] / hrs, 1) + " L/h  ·  peak "
                + f(w[TripSession.W_MAXKW], 0) + " kW";
    }

    // ------------------------------------------------------------ أعمدة مجمّعة (من النوافذ)
    static final class Bars { String[] label; double[] val; double[] dist; String unit; }

    private static int snapTarget(float t) {
        if (Float.isNaN(t)) return -1;
        int best = 0; float bd = 999;
        for (int i = 1; i < TARGET_V.length; i++) { float d = Math.abs(TARGET_V[i] - t); if (d < bd) { bd = d; best = i; } }
        return best;
    }

    Bars bars(int by, int metric) {
        String[] names;
        if (by == 0) names = new String[]{"Smart", "Forced 25%", "Forced 40%", "Forced 55%", "Forced 70%"};
        else if (by == 1) names = MODE_NAMES;
        else if (by == 2) names = new String[]{"< 25 °C", "25–35 °C", "35–42 °C", "> 42 °C"};
        else if (by == 3) names = new String[]{"< 30", "30–60", "60–90", "90–120", "> 120"};
        else names = new String[]{"Night 0–6", "Morning 6–12", "Afternoon 12–18", "Evening 18–24"};
        Acc[] g = new Acc[names.length];
        for (int i = 0; i < g.length; i++) g[i] = new Acc();
        for (float[] w : wins) {
            int k;
            if (by == 0) { k = w[TripSession.W_FORCED] > 0.5 ? Math.max(1, snapTarget(w[TripSession.W_TARGET])) : 0; }
            else if (by == 1) k = (int) w[TripSession.W_MODE];
            else if (by == 2) { if (Float.isNaN(w[TripSession.W_TEMP])) continue; k = tempBand(w[TripSession.W_TEMP]); }
            else if (by == 3) { float v = w[TripSession.W_SPD]; k = v < 30 ? 0 : v < 60 ? 1 : v < 90 ? 2 : v < 120 ? 3 : 4; }
            else { int hr = (int) w[TripSession.W_HOUR]; k = hr / 6; }
            if (k < 0 || k >= g.length) continue;
            Acc x = g[k];
            x.dur += w[TripSession.W_DUR]; x.eng += w[TripSession.W_ENG]; x.dist += w[TripSession.W_DIST];
            x.out += w[TripSession.W_OUT]; x.in += w[TripSession.W_IN]; x.fuel += w[TripSession.W_FUEL];
        }
        Bars b = new Bars();
        b.unit = metric == 0 ? "L/100km" : metric <= 2 ? "kWh/100km" : metric == 3 ? "%" : "km";
        java.util.ArrayList<String> L = new java.util.ArrayList<>();
        java.util.ArrayList<Double> V = new java.util.ArrayList<>(), D = new java.util.ArrayList<>();
        for (int i = 0; i < g.length; i++) {
            Acc x = g[i];
            if (x.dur <= 0 || (metric < 3 && x.dist < 0.5)) continue;
            double v;
            if (metric == 0) v = x.fuel / x.dist * 100;
            else if (metric == 1) v = (x.out - x.in) / x.dist * 100;
            else if (metric == 2) v = (x.fuel * KWH_PER_L + x.out - x.in) / x.dist * 100;
            else if (metric == 3) v = (x.dur - x.eng) / x.dur * 100;
            else v = x.dist;
            L.add(names[i]); V.add(v); D.add(x.dist);
        }
        b.label = L.toArray(new String[0]);
        b.val = new double[V.size()]; b.dist = new double[V.size()];
        for (int i = 0; i < b.val.length; i++) { b.val[i] = V.get(i); b.dist[i] = D.get(i); }
        return b;
    }

    // ------------------------------------------------------------ ملاحظات تلقائية
    List<String> insights() {
        List<String> out = new ArrayList<>();
        if (pts.isEmpty()) { out.add("No trips match these filters."); return out; }
        Kpi k = kpi();
        if (tripsNoDetail > 0)
            out.add("ℹ " + tripsNoDetail + " of " + k.trips + " trips were recorded before detailed logging; maps and operating-point charts use only newer trips.");

        // 1) أفضل/أسوأ سرعة بالطاقة المكافئة (شبكة السرعة)
        double[] dist = new double[TripSession.SPEED_BINS], eq = new double[TripSession.SPEED_BINS];
        for (TripSession s : trips)
            for (int m = 0; m < TripSession.NUM_MODES; m++) {
                if (!modeOk(m)) continue;
                for (int sb = 0; sb < TripSession.SPEED_BINS; sb++) for (int ab = 0; ab < TripSession.ACCEL_BINS; ab++) {
                    TripSession.Gc g = s.grid[m][sb][ab];
                    dist[sb] += g.distKm; eq[sb] += g.fuel * KWH_PER_L + g.out - g.in;
                }
            }
        int best = -1, worst = -1;
        for (int i = 1; i < dist.length; i++) {
            if (dist[i] < 3) continue;
            double v = eq[i] / dist[i];
            if (best < 0 || v < eq[best] / dist[best]) best = i;
            if (worst < 0 || v > eq[worst] / dist[worst]) worst = i;
        }
        if (best >= 0 && worst >= 0 && best != worst)
            out.add("⚡ Most efficient speed: " + speedLabel(best) + " km/h at " + f(eq[best] / dist[best] * 100, 1)
                    + " kWh-eq/100km; least efficient: " + speedLabel(worst) + " km/h at " + f(eq[worst] / dist[worst] * 100, 1) + ".");

        // 2) أثر التسارع على الوقود
        double fuelAcc = 0, fuelAll = 0, tAcc = 0, tAll = 0;
        for (TripSession s : trips)
            for (int m = 0; m < TripSession.NUM_MODES; m++) {
                if (!modeOk(m)) continue;
                for (int sb = 0; sb < TripSession.SPEED_BINS; sb++) for (int ab = 0; ab < TripSession.ACCEL_BINS; ab++) {
                    TripSession.Gc g = s.grid[m][sb][ab];
                    fuelAll += g.fuel; tAll += g.durMs;
                    if (ab >= 3) { fuelAcc += g.fuel; tAcc += g.durMs; }
                }
            }
        if (fuelAll > 0.3 && tAll > 0)
            out.add("🚀 " + f(fuelAcc / fuelAll * 100, 0) + "% of fuel is burned while accelerating, which is only "
                    + f(tAcc / tAll * 100, 0) + "% of driving time.");

        // 3) Smart مقابل Forced (من النوافذ)
        Acc sm = new Acc(), fo = new Acc();
        for (float[] w : wins) {
            Acc x = w[TripSession.W_FORCED] > 0.5 ? fo : sm;
            x.dist += w[TripSession.W_DIST]; x.fuel += w[TripSession.W_FUEL]; x.out += w[TripSession.W_OUT]; x.in += w[TripSession.W_IN];
        }
        if (sm.dist >= 3 && fo.dist >= 3) {
            double a = (sm.fuel * KWH_PER_L + sm.out - sm.in) / sm.dist * 100, b = (fo.fuel * KWH_PER_L + fo.out - fo.in) / fo.dist * 100;
            double fs = sm.fuel / sm.dist * 100, ff = fo.fuel / fo.dist * 100;
            out.add("🔋 Forced Power Saving: " + f(ff, 1) + " L/100km vs Smart: " + f(fs, 1) + " L/100km ("
                    + (b >= a ? "+" : "") + f((b - a) / a * 100, 0) + "% total energy; Forced " + f(fo.dist, 0) + " km, Smart " + f(sm.dist, 0) + " km).");
        }

        // 4) الحرارة والمكيف
        Acc cool = new Acc(), hot = new Acc(), acOn = new Acc(), acOff = new Acc();
        for (float[] w : wins) {
            float t = w[TripSession.W_TEMP];
            if (!Float.isNaN(t)) { Acc x = t < 30 ? cool : t >= 38 ? hot : null; if (x != null) { x.dist += w[TripSession.W_DIST]; x.out += w[TripSession.W_OUT]; x.in += w[TripSession.W_IN]; x.fuel += w[TripSession.W_FUEL]; } }
            Acc y = w[TripSession.W_AC] > 0.5 ? acOn : acOff;
            y.dist += w[TripSession.W_DIST]; y.out += w[TripSession.W_OUT]; y.in += w[TripSession.W_IN]; y.fuel += w[TripSession.W_FUEL];
        }
        if (cool.dist >= 3 && hot.dist >= 3) {
            double a = (cool.fuel * KWH_PER_L + cool.out - cool.in) / cool.dist * 100, b = (hot.fuel * KWH_PER_L + hot.out - hot.in) / hot.dist * 100;
            out.add("🌡 Above 38 °C total energy use is " + (b >= a ? "+" : "") + f((b - a) / a * 100, 0) + "% vs below 30 °C ("
                    + f(b, 1) + " vs " + f(a, 1) + " kWh-eq/100km).");
        }
        if (acOn.dist >= 3 && acOff.dist >= 3) {
            double a = (acOff.fuel * KWH_PER_L + acOff.out - acOff.in) / acOff.dist * 100, b = (acOn.fuel * KWH_PER_L + acOn.out - acOn.in) / acOn.dist * 100;
            out.add("❄ With A/C on: " + f(b, 1) + " kWh-eq/100km vs " + f(a, 1) + " with A/C off (" + (b >= a ? "+" : "") + f((b - a) / a * 100, 0) + "%).");
        }

        // 5) أفضل رحلة
        Pt bestT = null;
        for (Pt p : pts) if (p.dist >= 5 && !Double.isNaN(p.energyEq100()) && (bestT == null || p.energyEq100() < bestT.energyEq100())) bestT = p;
        if (bestT != null)
            out.add("🏆 Best trip: " + Ui.date(bestT.s.startWall) + ", " + f(bestT.dist, 1) + " km at " + f(bestT.energyEq100(), 1)
                    + " kWh-eq/100km (" + bestT.s.driveType() + ", " + bestT.s.socModeLabel() + ").");
        out.add("These are correlations in your own trips (not controlled tests); more trips in each condition make them more reliable.");
        return out;
    }
}
