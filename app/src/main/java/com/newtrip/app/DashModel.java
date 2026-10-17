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
    static final String[] DIST = {"Any", "< 10 km", "10–30 km", "30–60 km", "> 60 km"};

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

    int period, mode, drive, soc, target, temp, dist;

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
        if (dist > 0) {
            double d = s.totalDistKm();
            if (dist == 1 && d >= 10) return false;
            if (dist == 2 && (d < 10 || d >= 30)) return false;
            if (dist == 3 && (d < 30 || d >= 60)) return false;
            if (dist == 4 && d < 60) return false;
        }
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
    static final class Acc { double dur, eng, dist, out, in, fuel, rpmW, rpmD; int n; }

    static final class Heat {
        String[] rows, cols; double[][] val; String[][] tip; String unit; boolean cost; boolean any;
    }

    static String speedLabel(int b) {
        return b >= TripSession.SPEED_BINS - 1 ? "130+" : (b * 10) + "–" + (b * 10 + 10);
    }

    static int tempBand(double t) { return t < 25 ? 0 : t < 35 ? 1 : t < 42 ? 2 : 3; }

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
            h.cols[c] = type == 0 ? Tr.t(TripSession.ACCEL_NAMES[c])
                    : type == 1 ? (c * 10) + "–" + (c * 10 + 10) + "%" : Tr.t(TEMP[c + 1]);
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
                h.tip[r][c] = x.dur <= 0 ? Tr.t("No data") : Tr.f("%s km/h  ×  %s\n%s km  ·  %s min  ·  fuel %s L  ·  battery %s kWh net  ·  engine on %s%%",
                        h.rows[r], h.cols[c], f(x.dist, 1), f(x.dur / 60, 1), f(x.fuel, 2), f(x.out - x.in, 2), f(x.eng / x.dur * 100, 0));
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
        s.legend = Tr.all(ci == 0 ? new String[]{"Smart", "Forced", "Mixed"}
                : ci == 1 ? new String[]{"Hybrid", "Electric", "Mixed"}
                : ci == 2 ? MODE_NAMES : new String[]{"Trips"});
        return s;
    }

    String tripTip(int ptIndex) {
        Pt p = pts.get(ptIndex);
        TripSession s = p.s;
        String tgt = s.targetLabel().equals("—") ? "" : "  ·  " + s.targetLabel();
        String car = Float.isNaN(s.carAvgFuel) && Float.isNaN(s.carAvgEnergy) ? ""
                : "\n" + Tr.f("Car-reported averages: %s L/100km  ·  %s kWh/100km", f(s.carAvgFuel, 1), f(s.carAvgEnergy, 1));
        return Ui.date(s.startWall) + "\n" + Tr.f("%s km  ·  %s km/h", f(p.dist, 1), f(p.avgSpeed(), 0)) + "\n"
                + Tr.t(s.driveType()) + "  ·  " + Tr.t(s.socModeLabel()) + tgt + "\n"
                + Tr.f("Fuel %s L (%s L/100km)  ·  Battery %s kWh net", f(p.fuel, 2), f(yval(p, 0), 1), f(p.out - p.in, 1)) + "\n"
                + Tr.f("EV share %s%%  ·  Temp %s °C", f(p.evShare(), 0), f(p.temp, 0)) + car;
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
        s.legend = Tr.all(ci == 0 ? new String[]{"EV (engine off)", "Engine on"} : ci == 1 ? new String[]{"Smart", "Forced"} : MODE_NAMES);
        return s;
    }

    String winTip(int i) {
        float[] w = wins.get(i);
        double hrs = w[TripSession.W_DUR] / 3600.0;
        return Ui.date(winTrip.get(i).startWall + (long) (w[TripSession.W_T] * 1000)) + "  ·  " + Tr.t(MODE_NAMES[(int) w[TripSession.W_MODE]]) + "\n"
                + Tr.f("%s km/h  ·  battery %s%%  ·  %s °C", f(w[TripSession.W_SPD], 0), f(w[TripSession.W_SOC], 0), f(w[TripSession.W_TEMP], 0)) + "\n"
                + Tr.f("Net %s kW  ·  fuel %s L/h  ·  peak %s kW", f((w[TripSession.W_OUT] - w[TripSession.W_IN]) / hrs, 1),
                        f(w[TripSession.W_FUEL] / hrs, 1), f(w[TripSession.W_MAXKW], 0));
    }

    // ------------------------------------------------------------ أعمدة مجمّعة (من النوافذ)
    static final class Bars { String[] label; double[] val; double[] dist; String unit; }

    static int snapTarget(float t) {
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
            L.add(Tr.t(names[i])); V.add(v); D.add(x.dist);
        }
        b.label = L.toArray(new String[0]);
        b.val = new double[V.size()]; b.dist = new double[V.size()];
        for (int i = 0; i < b.val.length; i++) { b.val[i] = V.get(i); b.dist[i] = D.get(i); }
        return b;
    }

    // ------------------------------------------------------------ ملاحظات تلقائية
    /** تنبيه يُعرض مرة واحدة أسفل كل بطاقة نصائح. */
    static final String DISCLAIMER = "These are patterns in your own trips, not controlled tests; they firm up as you record more trips in each condition.";

    List<String> insights() {
        List<String> out = new ArrayList<>();
        if (pts.isEmpty()) { out.add(Tr.t("No trips match these filters.")); return out; }
        Kpi k = kpi();
        if (tripsNoDetail > 0)
            out.add("ℹ " + Tr.f("%s of %s trips were recorded before detailed logging; maps and operating-point charts use only newer trips.", tripsNoDetail, k.trips));

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
            out.add("⚡ " + Tr.f("Most efficient speed: %s km/h at %s kWh-eq/100km; least efficient: %s km/h at %s.",
                    speedLabel(best), f(eq[best] / dist[best] * 100, 1), speedLabel(worst), f(eq[worst] / dist[worst] * 100, 1)));

        // 2) أثر التسارع على الوقود (يُخفى إن لم تُسجَّل تسارعات تُذكر)
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
        if (fuelAll > 0.3 && tAll > 0 && tAcc / tAll >= 0.01)
            out.add("🚀 " + Tr.f("%s%% of fuel is burned while accelerating, which is only %s%% of driving time.",
                    f(fuelAcc / fuelAll * 100, 0), f(tAcc / tAll * 100, 0)));

        // 3) Smart مقابل Forced (من النوافذ)
        Acc sm = new Acc(), fo = new Acc();
        for (float[] w : wins) {
            Acc x = w[TripSession.W_FORCED] > 0.5 ? fo : sm;
            x.dist += w[TripSession.W_DIST]; x.fuel += w[TripSession.W_FUEL]; x.out += w[TripSession.W_OUT]; x.in += w[TripSession.W_IN];
        }
        if (sm.dist >= 3 && fo.dist >= 3) {
            double a = (sm.fuel * KWH_PER_L + sm.out - sm.in) / sm.dist * 100, b = (fo.fuel * KWH_PER_L + fo.out - fo.in) / fo.dist * 100;
            double fs = sm.fuel / sm.dist * 100, ff = fo.fuel / fo.dist * 100;
            out.add("🔋 " + Tr.f(b >= a
                            ? "Forced Power Saving: %s L/100km vs Smart: %s L/100km (%s%% more total energy; Forced %s km, Smart %s km)."
                            : "Forced Power Saving: %s L/100km vs Smart: %s L/100km (%s%% less total energy; Forced %s km, Smart %s km).",
                    f(ff, 1), f(fs, 1), f(Math.abs((b - a) / a * 100), 0), f(fo.dist, 0), f(sm.dist, 0)));
        }

        // 4) الحرارة والمكيف (صياغة حسب اتجاه الفرق)
        Acc cool = new Acc(), hot = new Acc(), acOn = new Acc(), acOff = new Acc();
        for (float[] w : wins) {
            float t = w[TripSession.W_TEMP];
            if (!Float.isNaN(t)) { Acc x = t < 30 ? cool : t >= 38 ? hot : null; if (x != null) { x.dist += w[TripSession.W_DIST]; x.out += w[TripSession.W_OUT]; x.in += w[TripSession.W_IN]; x.fuel += w[TripSession.W_FUEL]; } }
            Acc y = w[TripSession.W_AC] > 0.5 ? acOn : acOff;
            y.dist += w[TripSession.W_DIST]; y.out += w[TripSession.W_OUT]; y.in += w[TripSession.W_IN]; y.fuel += w[TripSession.W_FUEL];
        }
        if (cool.dist >= 3 && hot.dist >= 3) {
            double a = (cool.fuel * KWH_PER_L + cool.out - cool.in) / cool.dist * 100, b = (hot.fuel * KWH_PER_L + hot.out - hot.in) / hot.dist * 100;
            double pen = a > 0 ? (b - a) / a * 100 : 0;
            if (pen >= 3) out.add("🌡 " + Tr.f("Above 38 °C total energy use is %s%% higher than below 30 °C (%s vs %s kWh-eq/100km).", f(pen, 0), f(b, 1), f(a, 1)));
            else if (pen <= -3) out.add("🌡 " + Tr.f("Hot trips (above 38 °C) did not cost more energy than cool ones (%s%% lower: %s vs %s kWh-eq/100km); speed and driving mode probably outweigh temperature in your data.", f(-pen, 0), f(b, 1), f(a, 1)));
            else out.add("🌡 " + Tr.f("Outside temperature has no measurable effect on energy use in your data (%s vs %s kWh-eq/100km).", f(b, 1), f(a, 1)));
        }
        if (acOn.dist >= 3 && acOff.dist >= 3) {
            double a = (acOff.fuel * KWH_PER_L + acOff.out - acOff.in) / acOff.dist * 100, b = (acOn.fuel * KWH_PER_L + acOn.out - acOn.in) / acOn.dist * 100;
            double pen = a > 0 ? (b - a) / a * 100 : 0;
            if (pen >= 3) out.add("❄ " + Tr.f("With A/C on: %s kWh-eq/100km vs %s with A/C off (%s%% more).", f(b, 1), f(a, 1), f(pen, 0)));
            else if (pen <= -3) out.add("❄ " + Tr.f("Trips with A/C on were not more expensive (%s%% lower: %s vs %s kWh-eq/100km); other factors outweigh A/C in your data.", f(-pen, 0), f(b, 1), f(a, 1)));
            else out.add("❄ " + Tr.f("A/C has no measurable effect on energy use in your data (%s vs %s kWh-eq/100km).", f(b, 1), f(a, 1)));
        }

        // 5) أفضل رحلة
        Pt bestT = null;
        for (Pt p : pts) if (p.dist >= 5 && !Double.isNaN(p.energyEq100()) && (bestT == null || p.energyEq100() < bestT.energyEq100())) bestT = p;
        if (bestT != null)
            out.add("🏆 " + Tr.f("Best trip: %s, %s km at %s kWh-eq/100km (%s, %s).", Ui.date(bestT.s.startWall), f(bestT.dist, 1), f(bestT.energyEq100(), 1),
                    Tr.t(bestT.s.driveType()), Tr.t(bestT.s.socModeLabel())));
        return out;
    }

    // ============================================================ علاقات PHEV (البطارية، وضع SOC، الهدف، المسافة، RPM)
    static final String[] SOC_BAND = {"0–20%", "20–40%", "40–60%", "60–80%", "80–100%"};
    static final String[] DIST_BAND = {"< 10 km", "10–30 km", "30–60 km", "> 60 km"};
    static final String[] SET_NAMES = {"Smart", "Forced 25%", "Forced 40%", "Forced 55%", "Forced 70%", "Forced ?"};
    static final String[] DEF_BAND = {"At / above target", "1–15% below", "16–30% below", "31–45% below", "> 45% below"};
    static final String[] SPD_BAND = {"< 30", "30–60", "60–90", "90–120", "> 120"};
    static final String[] RPM_BAND = {"Engine off", "< 1500", "1500–2500", "2500–3500", "> 3500"};
    static final String[] FCOL = {"Speed (km/h)", "Engine RPM"};
    static final String[] FMETRIC = {"Fuel flow (L/h)", "Fuel (L/100km)"};
    static final String[] MSMETRIC = {"Fuel (L/100km)", "Energy-eq (kWh/100km)", "Avg RPM"};

    static int socBand(float soc) { return Math.max(0, Math.min(4, (int) (soc / 20f))); }
    static int distBand(double d) { return d < 10 ? 0 : d < 30 ? 1 : d < 60 ? 2 : 3; }
    static int deficitBand(double d) { return d <= 0 ? 0 : d <= 15 ? 1 : d <= 30 ? 2 : d <= 45 ? 3 : 4; }
    static int spdBand(float v) { return v < 30 ? 0 : v < 60 ? 1 : v < 90 ? 2 : v < 120 ? 3 : 4; }
    static int rpmBand(float r) { return Float.isNaN(r) ? 0 : r < 1500 ? 1 : r < 2500 ? 2 : r < 3500 ? 3 : 4; }

    /** رقم إعداد SOC لنافذة: 0 = Smart ، 1..4 = Forced بهدف 25/40/55/70 ، 5 = Forced بلا هدف معروف. */
    static int setIndex(float[] w) {
        if (w[TripSession.W_FORCED] <= 0.5f) return 0;
        int k = snapTarget(w[TripSession.W_TARGET]);
        return k < 1 ? 5 : k;
    }

    private interface Val { double of(Acc x); }

    private Heat makeHeat(String[] rn, String[] cn, Acc[][] a, Val val, String unit, boolean cost, boolean skipEmptyRows, String unitsTip) {
        Heat h = new Heat();
        h.unit = unit; h.cost = cost;
        java.util.ArrayList<Integer> keep = new java.util.ArrayList<>();
        for (int r = 0; r < rn.length; r++) {
            boolean any = false;
            for (int c = 0; c < cn.length; c++) if (a[r][c].n > 0) any = true;
            if (any || !skipEmptyRows) keep.add(r);
        }
        h.cols = Tr.all(cn);
        h.rows = new String[keep.size()];
        h.val = new double[keep.size()][cn.length];
        h.tip = new String[keep.size()][cn.length];
        for (int i = 0; i < keep.size(); i++) {
            int r = keep.get(i);
            h.rows[i] = Tr.t(rn[r]);
            for (int c = 0; c < cn.length; c++) {
                Acc x = a[r][c];
                double v = x.n > 0 ? val.of(x) : Double.NaN;
                h.val[i][c] = v;
                if (!Double.isNaN(v)) h.any = true;
                h.tip[i][c] = x.n == 0 ? Tr.t("No data") : Tr.t(rn[r]) + "  ×  " + Tr.t(cn[c]) + "\n" + x.n + " " + Tr.t(unitsTip) + "  ·  "
                        + Tr.f("%s km  ·  %s min  ·  fuel %s L", f(x.dist, 1), f(x.dur / 60, 1), f(x.fuel, 2))
                        + (x.rpmD > 0 ? "  ·  " + Tr.f("avg RPM %s", f(x.rpmW / x.rpmD, 0)) : "");
            }
        }
        return h;
    }

    static Acc[][] accGrid(int r, int c) {
        Acc[][] a = new Acc[r][c];
        for (Acc[] row : a) for (int j = 0; j < c; j++) row[j] = new Acc();
        return a;
    }

    static void addWin(Acc x, float[] w) {
        x.n++;
        x.dur += w[TripSession.W_DUR]; x.eng += w[TripSession.W_ENG]; x.dist += w[TripSession.W_DIST];
        x.out += w[TripSession.W_OUT]; x.in += w[TripSession.W_IN]; x.fuel += w[TripSession.W_FUEL];
        float rpm = w[TripSession.W_RPM];
        if (!Float.isNaN(rpm)) { x.rpmW += rpm * w[TripSession.W_DUR]; x.rpmD += w[TripSession.W_DUR]; }
    }

    // ---- 1) البطارية عند البداية مقابل استهلاك الوقود (رحلات) ----
    static final class Trend { boolean ok; double slope, intercept, r; int n; }
    Trend trend = new Trend();

    Scat startSocScatter() {
        List<float[]> v = new ArrayList<>(); List<Integer> col = new ArrayList<>(), ref = new ArrayList<>();
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0; int n = 0;
        for (int i = 0; i < pts.size(); i++) {
            Pt p = pts.get(i);
            float soc = p.s.socStart;
            if (Float.isNaN(soc) || p.dist < 2) continue;
            double y = p.fuel / p.dist * 100;
            String l = p.s.socModeLabel();
            col.add(l.startsWith("Smart") ? 0 : l.startsWith("Forced") ? 1 : 2);
            v.add(new float[]{soc, (float) y, (float) Math.max(1, Math.min(60, p.dist))});
            ref.add(i);
            sx += soc; sy += y; sxx += soc * soc; syy += y * y; sxy += soc * y; n++;
        }
        trend = new Trend();
        trend.n = n;
        double den = n * sxx - sx * sx;
        if (n >= 4 && den > 1e-9) {
            trend.slope = (n * sxy - sx * sy) / den;
            trend.intercept = (sy - trend.slope * sx) / n;
            double dy = n * syy - sy * sy;
            trend.r = dy > 1e-9 ? (n * sxy - sx * sy) / Math.sqrt(den * dy) : 0;
            trend.ok = true;
        }
        Scat s = new Scat();
        int m = v.size();
        s.x = new float[m]; s.y = new float[m]; s.size = new float[m]; s.color = new int[m]; s.ref = new int[m];
        for (int i = 0; i < m; i++) {
            s.x[i] = v.get(i)[0]; s.y[i] = v.get(i)[1]; s.size[i] = v.get(i)[2]; s.color[i] = col.get(i); s.ref[i] = ref.get(i);
        }
        s.legend = Tr.all(new String[]{"Smart", "Forced", "Mixed"});
        return s;
    }

    // ---- 2) البطارية عند البداية × مسافة الرحلة ----
    Heat socDistHeat() {
        Acc[][] a = accGrid(5, 4);
        for (Pt p : pts) {
            if (Float.isNaN(p.s.socStart) || p.dist <= 0) continue;
            Acc x = a[socBand(p.s.socStart)][distBand(p.s.totalDistKm())];
            x.n++; x.dist += p.dist; x.dur += p.durH * 3600; x.fuel += p.fuel; x.out += p.out; x.in += p.in;
        }
        return makeHeat(SOC_BAND, DIST_BAND, a, x -> x.dist >= 3 ? x.fuel / x.dist * 100 : Double.NaN, "L/100km", true, false, "trips");
    }

    // ---- 3) نمط القيادة × إعداد SOC (نوافذ) ----
    Heat modeSocHeat(int metric) {
        Acc[][] a = accGrid(TripSession.NUM_MODES, 6);
        for (float[] w : wins) {
            int m = (int) w[TripSession.W_MODE];
            if (m < 0 || m >= TripSession.NUM_MODES) continue;
            addWin(a[m][setIndex(w)], w);
        }
        Val v = metric == 0 ? x -> x.dist >= 1 ? x.fuel / x.dist * 100 : Double.NaN
                : metric == 1 ? x -> x.dist >= 1 ? (x.fuel * KWH_PER_L + x.out - x.in) / x.dist * 100 : Double.NaN
                : x -> x.rpmD >= 60 ? x.rpmW / x.rpmD : Double.NaN;
        return makeHeat(MODE_NAMES, SET_NAMES, a, v, metric == 0 ? "L/100km" : metric == 1 ? "kWh-eq/100km" : "RPM", true, true, "windows");
    }

    // ---- 4) Forced: الفجوة بين الهدف والبطارية × السرعة/RPM ----
    Heat forcedStressHeat(int colMode, int metric) {
        String[] cn = colMode == 0 ? SPD_BAND : RPM_BAND;
        Acc[][] a = accGrid(5, cn.length);
        for (float[] w : wins) {
            if (w[TripSession.W_FORCED] <= 0.5f) continue;
            float tg = w[TripSession.W_TARGET], soc = w[TripSession.W_SOC];
            if (Float.isNaN(tg) || Float.isNaN(soc)) continue;
            int c = colMode == 0 ? spdBand(w[TripSession.W_SPD]) : rpmBand(w[TripSession.W_RPM]);
            addWin(a[deficitBand(tg - soc)][c], w);
        }
        Val v = metric == 0 ? x -> x.dur >= 60 ? x.fuel / (x.dur / 3600.0) : Double.NaN
                : x -> x.dist >= 0.5 ? x.fuel / x.dist * 100 : Double.NaN;
        return makeHeat(DEF_BAND, cn, a, v, metric == 0 ? "L/h" : "L/100km", true, true, "windows");
    }

    // ---- 5) بدايات Forced ----
    static final class ForcedRow { Pt p; float tMin, soc, target, deficit; }

    List<ForcedRow> forcedStarts() {
        List<ForcedRow> out = new ArrayList<>();
        for (Pt p : pts) {
            float[] fs = p.s.forcedStart();
            if (fs == null) continue;
            ForcedRow r = new ForcedRow();
            r.p = p; r.tMin = fs[0] / 60f; r.soc = fs[1]; r.target = fs[2]; r.deficit = fs[3];
            out.add(r);
        }
        out.sort((x, y) -> Long.compare(y.p.s.startWall, x.p.s.startWall));
        return out;
    }

    // ---- 6) الخط الزمني لرحلة ----
    static final class Timeline {
        String title; float[] t, soc, target, speed, rpm, fuelLph; boolean[] forced; float[] evT; boolean[] evForced; float[] evTarget; float[] evSoc;
        boolean empty() { return t == null || t.length < 2; }
    }

    /** الرحلات المصفّاة التي لديها نوافذ، لقائمة اختيار الخط الزمني. */
    List<Integer> timelineTrips() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) if (pts.get(i).s.wins.size() >= 2) out.add(i);
        return out;
    }

    Timeline timeline(int ptIndex) {
        Timeline tl = new Timeline();
        if (ptIndex < 0 || ptIndex >= pts.size()) return tl;
        TripSession s = pts.get(ptIndex).s;
        int n = s.wins.size();
        tl.title = Ui.date(s.startWall) + "  ·  " + f(pts.get(ptIndex).dist, 1) + " km";
        tl.t = new float[n]; tl.soc = new float[n]; tl.target = new float[n]; tl.speed = new float[n]; tl.rpm = new float[n];
        tl.fuelLph = new float[n]; tl.forced = new boolean[n];
        for (int i = 0; i < n; i++) {
            float[] w = s.wins.get(i);
            tl.t[i] = (w[TripSession.W_T] + w[TripSession.W_DUR] / 2f) / 60f;
            tl.soc[i] = w[TripSession.W_SOC];
            tl.forced[i] = w[TripSession.W_FORCED] > 0.5f;
            tl.target[i] = tl.forced[i] ? w[TripSession.W_TARGET] : Float.NaN;
            tl.speed[i] = w[TripSession.W_SPD];
            tl.rpm[i] = w[TripSession.W_RPM];
            tl.fuelLph[i] = w[TripSession.W_DUR] > 0 ? w[TripSession.W_FUEL] / (w[TripSession.W_DUR] / 3600f) : Float.NaN;
        }
        int e = s.events.size();
        tl.evT = new float[e]; tl.evForced = new boolean[e]; tl.evTarget = new float[e]; tl.evSoc = new float[e];
        for (int i = 0; i < e; i++) {
            float[] ev = s.events.get(i);
            tl.evT[i] = ev[0] / 60f; tl.evForced[i] = ev[1] > 0.5f; tl.evTarget[i] = ev[2]; tl.evSoc[i] = ev[3];
        }
        return tl;
    }

    // ---- 7) ملاحظات PHEV ----
    List<String> phevInsights() {
        List<String> out = new ArrayList<>();
        if (pts.isEmpty()) { out.add(Tr.t("No trips match these filters.")); return out; }
        startSocScatter();
        Trend tr = trend;
        if (tr.ok && Math.abs(tr.r) >= 0.3) {
            double per10 = tr.slope * 10;
            out.add("🔋 " + (per10 < 0
                    ? Tr.f("Starting with more battery saves fuel: every +10%% of battery at trip start ≈ %s L/100km less (r = %s, %s trips).", f(-per10, 1), f(tr.r, 2), tr.n)
                    : Tr.f("In your data, a fuller battery at the start did NOT lower fuel use (+10%% ≈ +%s L/100km, r = %s). Trip length or Forced mode may be hiding the effect.", f(per10, 1), f(tr.r, 2))));
        } else if (tr.n >= 4) {
            out.add("🔋 " + Tr.f("No clear link between starting battery and fuel use yet (%s trips). Distance and SOC mode also matter; see the map below.", tr.n));
        } else {
            out.add("🔋 " + Tr.t("Need at least 4 trips with a start battery level to measure its effect on fuel."));
        }

        // مقارنة أعلى وأدنى شريحة بداية
        Acc[] sb = new Acc[5];
        for (int i = 0; i < 5; i++) sb[i] = new Acc();
        for (Pt p : pts) if (!Float.isNaN(p.s.socStart)) { Acc x = sb[socBand(p.s.socStart)]; x.n++; x.dist += p.dist; x.fuel += p.fuel; }
        int hi = -1, lo = -1;
        for (int i = 0; i < 5; i++) if (sb[i].n >= 2 && sb[i].dist >= 5) { if (lo < 0) lo = i; hi = i; }
        if (hi > lo && lo >= 0) {
            double a = sb[hi].fuel / sb[hi].dist * 100, b = sb[lo].fuel / sb[lo].dist * 100;
            out.add("📊 " + Tr.f("Trips starting at %s battery used %s L/100km, versus %s L/100km when starting at %s (%s%%).",
                    SOC_BAND[hi], f(a, 1), f(b, 1), SOC_BAND[lo], b > 0 ? (a <= b ? "" : "+") + f((a - b) / b * 100, 0) : "—"));
        }

        // أثر المسافة
        Acc[] db = new Acc[4];
        for (int i = 0; i < 4; i++) db[i] = new Acc();
        for (Pt p : pts) { Acc x = db[distBand(p.s.totalDistKm())]; x.n++; x.dist += p.dist; x.fuel += p.fuel; }
        int dl = -1, dh = -1;
        for (int i = 0; i < 4; i++) if (db[i].n >= 2 && db[i].dist >= 5) { if (dl < 0) dl = i; dh = i; }
        if (dh > dl && dl >= 0) {
            boolean shortCheaper = db[dl].fuel / db[dl].dist < db[dh].fuel / db[dh].dist;
            out.add("📏 " + Tr.f(shortCheaper
                            ? "%s trips average %s L/100km; %s trips average %s L/100km (the battery covers more of a short trip)."
                            : "%s trips average %s L/100km; %s trips average %s L/100km.",
                    DIST_BAND[dl], f(db[dl].fuel / db[dl].dist * 100, 1), DIST_BAND[dh], f(db[dh].fuel / db[dh].dist * 100, 1)));
        }

        // Forced: الفجوة بين الهدف والبطارية
        Acc near = new Acc(), far = new Acc(), farHi = new Acc(), farLo = new Acc();
        for (float[] w : wins) {
            if (w[TripSession.W_FORCED] <= 0.5f) continue;
            float tg = w[TripSession.W_TARGET], soc = w[TripSession.W_SOC];
            if (Float.isNaN(tg) || Float.isNaN(soc)) continue;
            double d = tg - soc;
            if (d <= 15) addWin(near, w);
            else if (d > 30) {
                addWin(far, w);
                float rpm = w[TripSession.W_RPM];
                if (!Float.isNaN(rpm) && rpm > 3000) addWin(farHi, w);
                else if (!Float.isNaN(rpm) && rpm < 2000) addWin(farLo, w);
            }
        }
        if (near.dur >= 180 && far.dur >= 180) {
            double a = far.fuel / (far.dur / 3600.0), b = near.fuel / (near.dur / 3600.0);
            out.add("⚡ " + (b > 0.05
                    ? Tr.f("In Forced mode, driving with the battery more than 30%% below the target burned %s L/h, versus %s L/h when within 15%% of the target (%s×).", f(a, 1), f(b, 1), f(a / b, 1))
                    : Tr.f("In Forced mode, driving with the battery more than 30%% below the target burned %s L/h, versus %s L/h when within 15%% of the target.", f(a, 1), f(b, 1))));
        }
        if (farHi.dur >= 120 && farLo.dur >= 120) {
            double a = farHi.fuel / (farHi.dur / 3600.0), b = farLo.fuel / (farLo.dur / 3600.0);
            out.add("🔥 " + Tr.f("With a large gap to target, engine speed above 3000 RPM burned %s L/h versus %s L/h below 2000 RPM. High speed + high RPM while charging is the most expensive combination.", f(a, 1), f(b, 1)));
        }

        // توقيت بدء Forced
        List<ForcedRow> fr = forcedStarts();
        if (!fr.isEmpty()) {
            double t = 0, sc = 0, tg = 0; int nt = 0, big = 0;
            for (ForcedRow r : fr) {
                t += r.tMin; if (!Float.isNaN(r.soc)) { sc += r.soc; nt++; } if (!Float.isNaN(r.target)) tg += r.target;
                if (!Float.isNaN(r.deficit) && r.deficit >= 30) big++;
            }
            String base = Tr.f("Forced charging was used in %s trips; it started on average %s min in, at %s%% battery with a target of %s%%.",
                    fr.size(), f(t / fr.size(), 0), nt > 0 ? f(sc / nt, 0) : "—", f(tg / fr.size(), 0));
            out.add("🕒 " + base + (big > 0 ? " " + Tr.f("In %s of them the target was 30%% or more above the battery level.", big) : ""));
        }

        // أفضل/أسوأ مزيج (نمط × إعداد SOC)
        Acc[][] ms = accGrid(TripSession.NUM_MODES, 6);
        for (float[] w : wins) {
            int m = (int) w[TripSession.W_MODE];
            if (m >= 0 && m < TripSession.NUM_MODES) addWin(ms[m][setIndex(w)], w);
        }
        int bm = -1, bs = -1, wm = -1, wsx = -1; double bv = 1e9, wv = -1;
        for (int m = 0; m < TripSession.NUM_MODES; m++) for (int k = 0; k < 6; k++) {
            Acc x = ms[m][k];
            if (x.dist < 5) continue;
            double v = x.fuel / x.dist * 100;
            if (v < bv) { bv = v; bm = m; bs = k; }
            if (v > wv) { wv = v; wm = m; wsx = k; }
        }
        if (bm >= 0 && (bm != wm || bs != wsx))
            out.add("🏁 " + Tr.f("Lowest fuel: %s + %s at %s L/100km; highest: %s + %s at %s L/100km.",
                    Tr.t(MODE_NAMES[bm]), Tr.t(SET_NAMES[bs]), f(bv, 1), Tr.t(MODE_NAMES[wm]), Tr.t(SET_NAMES[wsx]), f(wv, 1)));
        return out;
    }
}
