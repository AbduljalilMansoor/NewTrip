package com.newtrip.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** يحوّل الرحلات إلى جداول CSV: ملخص الرحلات، شرائح السرعة، أنماط القيادة، تبديلات Smart/Forced، ونوافذ 30 ثانية. العناوين إنجليزية دائماً (صيغة ثابتة للتبادل والمزامنة). */
final class ExportData {
    private ExportData() { }

    private static String dt(long t) { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(t)); }

    private static double r(double v, int d) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return Double.NaN;
        double p = Math.pow(10, d);
        return Math.round(v * p) / p;
    }

    private static Object n(double v, int d) { double x = r(v, d); return Double.isNaN(x) ? null : (Object) x; }

    static List<Csv.Table> tables(List<TripSession> trips) {
        Csv.Table t = new Csv.Table("trips");
        t.add("Trip ID", "Date", "End", "Duration (min)", "Distance (km)", "Avg speed (km/h)", "Drive type", "SOC mode", "Target SOC",
                "Start battery %", "End battery %", "Battery used %", "Fuel (L)", "Fuel (L/100km)", "Battery out (kWh)",
                "Battery in (kWh)", "Battery net (kWh/100km)", "EV share %", "Avg RPM", "Max RPM", "A/C on %",
                "Avg outside C", "Avg cabin C", "Avg battery temp C", "Max battery temp C", "Avg coolant C", "Avg regen level",
                "Forced started (min)", "Forced start battery %", "Forced target %", "Forced deficit (target-battery)",
                "Car avg L/100km", "Car avg kWh/100km", "Windows");
        Csv.Table tiers = new Csv.Table("speed_tiers");
        tiers.add("Trip ID", "Date", "Mode", "Speed tier", "Speed km/h", "Duration (min)", "Distance (km)", "Avg speed (km/h)", "Fuel (L)",
                "L/100km", "Forced SOC target", "Smart %", "Forced %");
        Csv.Table modes = new Csv.Table("drive_modes");
        modes.add("Trip ID", "Date", "Mode", "EV km", "Engine km", "EV min", "Engine min", "Fuel (L)", "Battery out (kWh)", "Battery in (kWh)");
        Csv.Table wins = new Csv.Table("windows_30s");
        wins.add("Trip ID", "Date", "Time (min)", "Mode", "Duration (s)", "Distance (km)", "Avg speed (km/h)", "Battery out (kWh)",
                "Battery in (kWh)", "Fuel (L)", "Engine on (s)", "Avg RPM", "Max RPM", "Battery % (end)", "Battery % (start)",
                "Forced", "Target %", "Outside C", "Cabin C", "Battery temp C", "Coolant C", "A/C share", "A/C set C",
                "Regen level", "Peak battery kW");
        Csv.Table ev = new Csv.Table("soc_changes");
        ev.add("Trip ID", "Date", "Time (min)", "New mode", "Target %", "Battery %", "Speed km/h");

        for (TripSession s : trips) {
            String d = dt(s.startWall);
            double dist = s.totalDistKm(), fuel = s.totalFuelL(), out = s.totalKwhOut(), in = s.totalKwhIn();
            float[] fs = s.forcedStart();
            double durMin = s.totalDurMs() / 60000.0;
            t.add(s.id, d, s.endWall > 0 ? dt(s.endWall) : null, n(durMin, 1), n(dist, 2), n(s.avgSpeedKmh(), 1), s.driveType(), s.socModeLabel(),
                    s.targetLabel(), n(s.socStart, 0), n(s.socEnd, 0),
                    n(Float.isNaN(s.socStart) || Float.isNaN(s.socEnd) ? Double.NaN : s.socStart - s.socEnd, 0),
                    n(fuel, 2), n(dist > 0.3 ? fuel / dist * 100 : Double.NaN, 2), n(out, 2), n(in, 2),
                    n(dist > 0.3 ? (out - in) / dist * 100 : Double.NaN, 2), n(s.evShare(), 0),
                    n(s.avgRpm(), 0), n(s.maxRpm(), 0), n(s.acSharePct(), 0), n(s.avgTempC(), 1), n(s.avgCabinC(), 1),
                    n(s.avgPackC(), 1), n(s.maxPackC(), 1), n(s.avgCoolantC(), 0), n(s.avgRegen(), 1),
                    fs == null ? null : n(fs[0] / 60.0, 1), fs == null ? null : n(fs[1], 0), fs == null ? null : n(fs[2], 0),
                    fs == null ? null : n(fs[3], 0), n(s.carAvgFuel, 1), n(s.carAvgEnergy, 1), s.wins.size());

            for (int m = 0; m < TripSession.NUM_MODES; m++) {
                TripSession.Cell e = s.cells[m][TripSession.EV], g = s.cells[m][TripSession.ENG];
                if (e.durMs + g.durMs == 0) continue;
                modes.add(s.id, d, DashModel.MODE_NAMES[m], n(e.distKm, 2), n(g.distKm, 2), n(e.durMs / 60000.0, 1), n(g.durMs / 60000.0, 1),
                        n(e.fuelL + g.fuelL, 2), n(e.kwhOut + g.kwhOut, 2), n(e.kwhIn + g.kwhIn, 2));
                for (int k = 0; k < TripSession.NUM_TIERS; k++) {
                    TripSession.Tier x = s.tiers[m][k];
                    if (x.durMs == 0) continue;
                    double hrs = x.durMs / 3_600_000.0;
                    tiers.add(s.id, d, DashModel.MODE_NAMES[m], TripSession.TIER_NAMES[k], TripSession.TIER_RANGES[k], n(x.durMs / 60000.0, 1),
                            n(x.distKm, 2), n(hrs > 0 ? x.distKm / hrs : Double.NaN, 0), n(x.fuelL, 2),
                            n(k != TripSession.T_EV && x.distKm > 0.5 ? x.fuelL / x.distKm * 100 : Double.NaN, 1),
                            TripSession.targetText(x.targetMin, x.targetMax),
                            n(x.smartMs * 100.0 / x.durMs, 0), n(x.forcedMs * 100.0 / x.durMs, 0));
                }
            }
            for (float[] w : s.wins) {
                int mode = (int) w[TripSession.W_MODE];
                wins.add(s.id, d, n(w[TripSession.W_T] / 60.0, 2), mode >= 0 && mode < DashModel.MODE_NAMES.length ? DashModel.MODE_NAMES[mode] : null,
                        n(w[TripSession.W_DUR], 0), n(w[TripSession.W_DIST], 3), n(w[TripSession.W_SPD], 1), n(w[TripSession.W_OUT], 4),
                        n(w[TripSession.W_IN], 4), n(w[TripSession.W_FUEL], 4), n(w[TripSession.W_ENG], 0), n(w[TripSession.W_RPM], 0),
                        n(w[TripSession.W_RPMMAX], 0), n(w[TripSession.W_SOC], 1), n(w[TripSession.W_SOC0], 1),
                        w[TripSession.W_FORCED] > 0.5 ? "Forced" : "Smart", n(w[TripSession.W_TARGET], 0), n(w[TripSession.W_TEMP], 1),
                        n(w[TripSession.W_CABIN], 1), n(w[TripSession.W_PACK], 1), n(w[TripSession.W_COOL], 0), n(w[TripSession.W_AC], 2),
                        n(w[TripSession.W_SETT], 0), n(w[TripSession.W_REGEN], 1), n(w[TripSession.W_MAXKW], 1));
            }
            for (float[] e : s.events)
                ev.add(s.id, d, n(e[0] / 60.0, 1), e[1] > 0.5f ? "Forced" : "Smart", n(e[2], 0), n(e[3], 0), n(e[4], 0));
        }
        List<Csv.Table> out = new ArrayList<>();
        out.add(t); out.add(tiers); out.add(modes); out.add(ev); out.add(wins);
        return out;
    }
}
