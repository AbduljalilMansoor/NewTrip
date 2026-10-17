package com.newtrip.app;

import java.util.ArrayList;
import java.util.List;

/**
 * يكتب «الخلاصات» (Takeaways) و«تشخيص القياسات» (Telemetry diagnoses) و«ملاحظات التحسين العملية» (Optimization notes)
 * لكل لوحة ولملخص عام، اعتماداً فقط على بيانات رحلاتك المسجّلة وفلاتر اللوحة الحالية.
 * القواعد حتمية (بلا ذكاء اصطناعي خارجي) وتذكر حجم العيّنة، وتقول صراحةً عند عدم كفاية البيانات.
 * كل النصوص قوالب Tr.f (إنجليزي ← عربي في TrData).
 */
final class Advisor {
    private Advisor() { }

    static final int OVERALL = 0, PHEV = 1, OVERVIEW = 2, MAPS = 3;

    static final class Advice {
        final List<String> takeaways = new ArrayList<>(), diagnoses = new ArrayList<>(), actions = new ArrayList<>();
    }

    private static final String OK = "✓ ", WARN = "⚠ ", BAD = "✖ ", INFO = "ℹ ";

    // ------------------------------------------------------------ إحصاءات مشتركة
    private static final class S {
        int n, tripsStart, tripsStartLow;
        double dist, durH, fuel, out, in, evDist, startSum, maxPack = Double.NaN;
        // نوافذ (ثوانٍ / كم)
        double wDur, wEng, wDist, wForcedDist, lowSocDur, slowDur, slowEng, idleDur, idleEng, rpmHighDur, engDur, engDurRpm;
        double cabinSum, cabinDur, acDur, regenSum, regenDur, tempDur, packDur, peakKw;
        int forcedWins, forcedNoTarget, wins;

        boolean hasForced() { return forcedWins > 0; }
        double eq100() { return dist > 0.3 ? (fuel * DashModel.KWH_PER_L + out - in) / dist * 100 : Double.NaN; }
        double fuel100() { return dist > 0.3 ? fuel / dist * 100 : Double.NaN; }
        double kwh100() { return dist > 0.3 ? (out - in) / dist * 100 : Double.NaN; }
        double evShare() { return dist > 0 ? evDist / dist * 100 : Double.NaN; }
    }

    private static S stats(DashModel m) {
        S s = new S();
        for (DashModel.Pt p : m.pts) {
            s.n++; s.dist += p.dist; s.durH += p.durH; s.fuel += p.fuel; s.out += p.out; s.in += p.in; s.evDist += p.evDist;
            float st = p.s.socStart;
            if (!Float.isNaN(st)) { s.tripsStart++; s.startSum += st; if (st < 30) s.tripsStartLow++; }
            double mp = p.s.maxPackC();
            if (!Double.isNaN(mp) && (Double.isNaN(s.maxPack) || mp > s.maxPack)) s.maxPack = mp;
        }
        for (float[] w : m.wins) {
            double dur = w[TripSession.W_DUR];
            s.wins++; s.wDur += dur; s.wEng += w[TripSession.W_ENG]; s.wDist += w[TripSession.W_DIST];
            boolean forced = w[TripSession.W_FORCED] > 0.5f;
            if (forced) { s.wForcedDist += w[TripSession.W_DIST]; s.forcedWins++; if (Float.isNaN(w[TripSession.W_TARGET])) s.forcedNoTarget++; }
            float soc = w[TripSession.W_SOC];
            if (!Float.isNaN(soc) && soc < 20) s.lowSocDur += dur;
            float spd = w[TripSession.W_SPD];
            if (spd < 30) { s.slowDur += dur; s.slowEng += w[TripSession.W_ENG]; }
            if (spd < 2) { s.idleDur += dur; s.idleEng += w[TripSession.W_ENG]; }
            if (w[TripSession.W_ENG] > 0) {
                s.engDur += w[TripSession.W_ENG];
                if (!Float.isNaN(w[TripSession.W_RPM])) {
                    s.engDurRpm += w[TripSession.W_ENG];
                    if (w[TripSession.W_RPM] > 3000) s.rpmHighDur += w[TripSession.W_ENG];
                }
            }
            if (!Float.isNaN(w[TripSession.W_CABIN])) { s.cabinSum += w[TripSession.W_CABIN] * dur; s.cabinDur += dur; }
            if (!Float.isNaN(w[TripSession.W_TEMP])) s.tempDur += dur;
            if (!Float.isNaN(w[TripSession.W_PACK])) s.packDur += dur;
            if (w[TripSession.W_AC] > 0.5f) s.acDur += dur;
            if (!Float.isNaN(w[TripSession.W_REGEN])) { s.regenSum += w[TripSession.W_REGEN] * dur; s.regenDur += dur; }
            if (w[TripSession.W_MAXKW] > s.peakKw) s.peakKw = w[TripSession.W_MAXKW];
        }
        return s;
    }

    private static String f(double v, int d) { return DashModel.f(v, d); }
    private static String pct(double part, double whole) { return whole > 0 ? f(part / whole * 100, 0) : "—"; }

    private static double eq(DashModel.Acc x) { return x.dist >= 3 ? (x.fuel * DashModel.KWH_PER_L + x.out - x.in) / x.dist * 100 : Double.NaN; }

    // ------------------------------------------------------------ نقطة الدخول
    static Advice advise(DashModel m, int scope) {
        Advice a = new Advice();
        if (m.pts.isEmpty()) {
            a.takeaways.add(Tr.t("No trips match the current filters. Widen the filters or record more trips."));
            return a;
        }
        S s = stats(m);
        switch (scope) {
            case PHEV: phev(m, s, a); break;
            case OVERVIEW: overview(m, s, a); break;
            case MAPS: maps(m, s, a); break;
            default: overall(m, s, a);
        }
        if (a.takeaways.isEmpty()) a.takeaways.add(Tr.t("Not enough data yet for firm conclusions."));
        if (a.actions.isEmpty()) a.actions.add(Tr.t("Nothing stands out to change yet. Keep recording trips so the comparison gets stronger."));
        return a;
    }

    private static void addAll(List<String> to, List<String> from) { to.addAll(from); }

    // ------------------------------------------------------------ عام (Summary)
    private static void overall(DashModel m, S s, Advice a) {
        a.takeaways.add(Tr.f("%s trips, %s km at an average %s km/h: fuel %s L/100km, battery %s kWh/100km (net), %s%% of the distance on EV.",
                s.n, f(s.dist, 0), f(s.durH > 0 ? s.dist / s.durH : Double.NaN, 0), f(s.fuel100(), 1), f(s.kwh100(), 1), f(s.evShare(), 0)));
        a.takeaways.add(Tr.f("Total energy use is %s kWh-eq/100km (fuel counted at 8.9 kWh per litre), the single number to compare conditions.", f(s.eq100(), 1)));

        m.startSocScatter();
        DashModel.Trend tr = m.trend;
        if (tr.ok && Math.abs(tr.r) >= 0.3 && tr.slope < 0)
            a.takeaways.add(Tr.f("Battery at the start of the trip is your biggest lever: every +10%% costs about %s L/100km less (r = %s over %s trips).",
                    f(-tr.slope * 10, 1), f(tr.r, 2), tr.n));
        String combo = bestWorst(m);
        if (combo != null) a.takeaways.add(combo);
        if (s.hasForced()) a.takeaways.add(Tr.f("Forced charging covered %s%% of the driving distance.", pct(s.wForcedDist, s.wDist)));

        // ---- تشخيص القياسات ----
        if (m.tripsNoDetail == 0) a.diagnoses.add(OK + Tr.f("All %s trips have detailed logging (30 s windows, RPM, temperatures).", s.n));
        else a.diagnoses.add(WARN + Tr.f("%s of %s trips predate detailed logging, so maps and operating-point charts use only the newer trips.", m.tripsNoDetail, s.n));
        if (s.engDur > 0) {
            double cov = s.engDurRpm / s.engDur * 100;
            a.diagnoses.add((cov >= 80 ? OK : WARN) + Tr.f("RPM was recorded for %s%% of engine-running time.", f(cov, 0))
                    + (cov >= 80 ? "" : " " + Tr.t("Check the RPM signal; high-RPM findings may be incomplete.")));
        }
        if (s.wDur > 0) {
            a.diagnoses.add((s.tempDur / s.wDur >= 0.8 ? OK : WARN) + Tr.f("Outside temperature was recorded for %s%% of driving time; battery temperature for %s%%.",
                    pct(s.tempDur, s.wDur), pct(s.packDur, s.wDur)));
        }
        if (!Double.isNaN(s.maxPack)) {
            if (s.maxPack >= 50) a.diagnoses.add(BAD + Tr.f("Battery temperature reached %s °C. Sustained heat above 45–50 °C accelerates ageing and may limit power.", f(s.maxPack, 0)));
            else if (s.maxPack >= 40) a.diagnoses.add(WARN + Tr.f("Battery temperature peaked at %s °C, on the warm side.", f(s.maxPack, 0)));
            else a.diagnoses.add(OK + Tr.f("Battery temperature stayed below %s °C.", f(Math.max(40, Math.ceil(s.maxPack / 5) * 5), 0)));
        }
        if (s.wDur > 0 && s.lowSocDur / s.wDur > 0.25)
            a.diagnoses.add(WARN + Tr.f("%s%% of the driving time was with the battery below 20%%, where the engine has to do most of the work.", pct(s.lowSocDur, s.wDur)));
        if (s.slowDur > 600 && s.slowEng / s.slowDur > 0.25)
            a.diagnoses.add(WARN + Tr.f("The engine ran during %s%% of the time below 30 km/h. In slow traffic that usually means a low battery, Forced charging or heavy A/C.", pct(s.slowEng, s.slowDur)));
        if (s.forcedWins > 0 && s.forcedNoTarget > 0.1 * s.forcedWins)
            a.diagnoses.add(WARN + Tr.f("The Forced target was unknown for %s%% of Forced driving, so target-gap results are incomplete.", pct(s.forcedNoTarget, s.forcedWins)));

        // ---- ملاحظات التحسين ----
        int before = a.actions.size();
        if (tr.ok && tr.slope < 0 && Math.abs(tr.r) >= 0.3 && s.tripsStart > 0 && s.startSum / s.tripsStart < 70)
            a.actions.add(Tr.f("Charge to 80%% or more before departure whenever you can: your trips start at %s%% on average, and each extra 10%% saves about %s L/100km.",
                    f(s.startSum / s.tripsStart, 0), f(-tr.slope * 10, 1)));
        forcedAction(m, s, a);
        speedAction(m, a);
        accelAction(m, a);
        climateAction(m, s, a);
        if (s.engDurRpm > 0 && s.rpmHighDur / s.engDurRpm > 0.15)
            a.actions.add(Tr.f("The engine ran above 3000 RPM for %s%% of its running time. Ease off the accelerator on motorway ramps and hills to stay in the efficient RPM range.", pct(s.rpmHighDur, s.engDurRpm)));
        if (a.actions.size() == before) { /* يُملأ الافتراضي في advise() */ }
    }

    // ------------------------------------------------------------ PHEV
    private static void phev(DashModel m, S s, Advice a) {
        addAll(a.takeaways, m.phevInsights());
        m.startSocScatter();
        DashModel.Trend tr = m.trend;

        // ---- تشخيص ----
        List<DashModel.ForcedRow> fr = m.forcedStarts();
        int big = 0, above = 0, known = 0;
        for (DashModel.ForcedRow r : fr) {
            if (!Float.isNaN(r.deficit)) { known++; if (r.deficit >= 30) big++; if (r.deficit <= 0) above++; }
        }
        if (!fr.isEmpty() && known > 0) {
            if (big > 0) a.diagnoses.add((big * 2 > known ? BAD : WARN) + Tr.f("Forced charging started with the target 30%% or more above the battery in %s of %s trips: the most expensive way to use it.", big, known));
            else a.diagnoses.add(OK + Tr.f("Forced charging never started with a gap of 30%% or more (%s trips).", known));
            if (above > 0) a.diagnoses.add(WARN + Tr.f("In %s trips Forced was switched on when the battery was already at or above the target, so it only held the charge and burned fuel for no gain.", above));
        } else if (fr.isEmpty()) {
            a.diagnoses.add(INFO + Tr.t("No Forced-charging trips in the current filters, so the gap analysis is empty."));
        }
        if (s.engDurRpm > 0 && s.rpmHighDur > 300)
            a.diagnoses.add((s.rpmHighDur / s.engDurRpm > 0.2 ? WARN : INFO) + Tr.f("The engine spent %s minutes above 3000 RPM (%s%% of its running time).", f(s.rpmHighDur / 60, 0), pct(s.rpmHighDur, s.engDurRpm)));
        if (s.tripsStart > 0 && s.tripsStartLow * 1.0 / s.tripsStart > 0.3)
            a.diagnoses.add(WARN + Tr.f("%s%% of trips started with less than 30%% battery, so the engine carried much of the load.", pct(s.tripsStartLow, s.tripsStart)));
        if (tr.n >= 4) {
            if (Math.abs(tr.r) >= 0.6) a.diagnoses.add(OK + Tr.f("The start-battery effect is strong and consistent (r = %s over %s trips).", f(tr.r, 2), tr.n));
            else if (Math.abs(tr.r) >= 0.3) a.diagnoses.add(INFO + Tr.f("The start-battery effect is moderate (r = %s); distance, speed and SOC mode also play a part.", f(tr.r, 2)));
            else a.diagnoses.add(INFO + Tr.f("The start-battery effect is weak in this sample (r = %s over %s trips); more trips, or a narrower distance filter, will clarify it.", f(tr.r, 2), tr.n));
        } else {
            a.diagnoses.add(INFO + Tr.f("Only %s trips have a start-battery reading; at least 4 are needed for a trend.", tr.n));
        }
        if (s.forcedWins > 0 && s.forcedNoTarget > 0.1 * s.forcedWins)
            a.diagnoses.add(WARN + Tr.f("The Forced target was unknown for %s%% of Forced driving; confirm the HCU_SETSOCFED signal is available.", pct(s.forcedNoTarget, s.forcedWins)));

        // ---- ملاحظات ----
        double[] thr = startThreshold(m);
        if (thr != null)
            a.actions.add(Tr.f("Aim to start longer trips with at least %s%% battery: below that, fuel use rises to about %s L/100km versus %s L/100km above it.",
                    f(thr[0], 0), f(thr[1], 1), f(thr[2], 1)));
        else if (tr.ok && tr.slope < 0)
            a.actions.add(Tr.f("Charge before you go: each +10%% of starting battery saves about %s L/100km in your data.", f(-tr.slope * 10, 1)));
        forcedAction(m, s, a);
        double[] fb = forcedSpeedBest(m);
        if (fb != null)
            a.actions.add(Tr.f("When you do charge in Forced mode, do it while cruising at %s km/h: that is where your fuel per km was lowest (%s L/100km), versus %s L/100km at %s km/h.",
                    DashModel.speedLabel((int) fb[0]), f(fb[1], 1), f(fb[3], 1), DashModel.speedLabel((int) fb[2])));
        double[] db = distanceEffect(m);
        if (db != null && db[0] > db[1] * 1.2)
            a.actions.add(Tr.f("Short trips (%s) are cheapest on the battery alone (%s L/100km versus %s L/100km on long trips): keep Smart or EV mode and avoid Forced charging for them.",
                    DashModel.DIST_BAND[(int) db[2]], f(db[1], 1), f(db[0], 1)));
    }

    // ------------------------------------------------------------ Overview
    private static void overview(DashModel m, S s, Advice a) {
        addAll(a.takeaways, m.insights());

        // مقارنة أنماط القيادة (طاقة مكافئة)
        DashModel.Acc[] modes = new DashModel.Acc[TripSession.NUM_MODES];
        for (int i = 0; i < modes.length; i++) modes[i] = new DashModel.Acc();
        for (float[] w : m.wins) {
            int k = (int) w[TripSession.W_MODE];
            if (k >= 0 && k < modes.length) addWin2(modes[k], w);
        }
        int bi = -1, wi = -1;
        for (int i = 0; i < modes.length; i++) {
            double v = eq(modes[i]);
            if (Double.isNaN(v)) continue;
            if (bi < 0 || v < eq(modes[bi])) bi = i;
            if (wi < 0 || v > eq(modes[wi])) wi = i;
        }
        if (bi >= 0 && wi >= 0 && bi != wi) {
            double b = eq(modes[bi]), w = eq(modes[wi]);
            a.takeaways.add(Tr.f("%s mode is the most economical (%s kWh-eq/100km) and %s the least (%s), a gap of %s%%.",
                    Tr.t(DashModel.MODE_NAMES[bi]), f(b, 1), Tr.t(DashModel.MODE_NAMES[wi]), f(w, 1), f((w - b) / b * 100, 0)));
        }

        // ---- تشخيص ----
        double[] tp = tempPenalty(m);
        if (tp != null) {
            if (tp[0] >= 3) a.diagnoses.add((tp[0] >= 15 ? WARN : INFO) + Tr.f("Energy use is %s%% higher above 38 °C than below 30 °C (%s vs %s kWh-eq/100km).", f(tp[0], 0), f(tp[1], 1), f(tp[2], 1)));
            else if (tp[0] <= -3) a.diagnoses.add(INFO + Tr.f("Energy use was not higher in hot weather (%s%% lower: %s vs %s kWh-eq/100km); speed, mode or SOC mode outweigh temperature in your data.", f(-tp[0], 0), f(tp[1], 1), f(tp[2], 1)));
            else a.diagnoses.add(OK + Tr.t("Outside temperature has no measurable effect on energy use in your data."));
        }
        double[] ac = acPenalty(m);
        if (ac != null) {
            if (ac[0] >= 3) a.diagnoses.add((ac[0] >= 10 ? WARN : INFO) + Tr.f("A/C adds about %s%% to energy use (%s vs %s kWh-eq/100km with it off).", f(ac[0], 0), f(ac[1], 1), f(ac[2], 1)));
            else if (ac[0] <= -3) a.diagnoses.add(INFO + Tr.f("Trips with A/C on were not more expensive (%s%% lower: %s vs %s kWh-eq/100km); other factors outweigh A/C in your data.", f(-ac[0], 0), f(ac[1], 1), f(ac[2], 1)));
            else a.diagnoses.add(OK + Tr.t("A/C has no measurable effect on energy use in your data."));
        }
        if (s.wDur > 0 && s.regenDur > 0 && s.regenSum / s.regenDur < 0.5)
            a.diagnoses.add(INFO + Tr.f("The average regen level was %s; a higher setting recovers more energy in stop-and-go driving.", f(s.regenSum / s.regenDur, 1)));
        if (s.idleDur > 300 && s.idleEng / s.idleDur > 0.2)
            a.diagnoses.add(WARN + Tr.f("The engine ran for %s%% of the time while the car was stationary (about %s minutes): idle charging or heavy A/C.", pct(s.idleEng, s.idleDur), f(s.idleEng / 60, 0)));
        if (m.tripsNoDetail > 0) a.diagnoses.add(WARN + Tr.f("%s trips lack detailed logging; group comparisons use only newer trips.", m.tripsNoDetail));
        if (a.diagnoses.isEmpty()) a.diagnoses.add(OK + Tr.t("No unusual patterns in temperature, A/C, regen or idling."));

        // ---- ملاحظات ----
        if (bi >= 0 && wi >= 0 && bi != wi && (eq(modes[wi]) - eq(modes[bi])) / eq(modes[bi]) > 0.08)
            a.actions.add(Tr.f("Use %s mode for everyday driving: it saves about %s%% energy compared with %s mode.",
                    Tr.t(DashModel.MODE_NAMES[bi]), f((eq(modes[wi]) - eq(modes[bi])) / eq(modes[wi]) * 100, 0), Tr.t(DashModel.MODE_NAMES[wi])));
        climateAction(m, s, a);
        if (s.idleDur > 300 && s.idleEng / s.idleDur > 0.2)
            a.actions.add(Tr.t("Avoid sitting with the engine running: switch to EV mode or park with the A/C off when the battery allows."));
        speedAction(m, a);
    }

    // ------------------------------------------------------------ Maps & scatter
    private static void maps(DashModel m, S s, Advice a) {
        // ---- خلاصات ----
        double[] sw = sweetSpot(m);
        if (sw != null)
            a.takeaways.add(Tr.f("Your sweet spot is %s km/h (%s kWh-eq/100km); at %s km/h it costs %s%% more (%s).",
                    DashModel.speedLabel((int) sw[0]), f(sw[1], 1), DashModel.speedLabel((int) sw[2]), f((sw[3] - sw[1]) / sw[1] * 100, 0), f(sw[3], 1)));
        double[] ac = accelShare(m);
        if (ac != null)
            a.takeaways.add(Tr.f("%s%% of the fuel is burned while accelerating, which is only %s%% of the driving time.", f(ac[0], 0), f(ac[1], 0)));
        double r = speedEfficiencyCorrelation(m);
        if (!Double.isNaN(r)) {
            if (r > 0.3) a.takeaways.add(Tr.f("Trips with a higher average speed use more energy per km (r = %s): highway-speed driving is where you pay most.", f(r, 2)));
            else if (r < -0.3) a.takeaways.add(Tr.f("Trips with a higher average speed use less energy per km (r = %s): your slow, stop-and-go trips are the expensive ones.", f(r, 2)));
            else a.takeaways.add(Tr.f("Average trip speed has little link to energy use (r = %s).", f(r, 2)));
        }
        double[] rg = regenShare(m);
        if (rg != null) a.takeaways.add(Tr.f("Regenerative braking returned %s%% of the battery energy used (%s kWh recovered).", f(rg[0], 0), f(rg[1], 1)));
        List<DashModel.Pt> bad = outliers(m);
        if (!bad.isEmpty()) {
            DashModel.Pt w = bad.get(0);
            a.takeaways.add(Tr.f("%s trips used 50%% or more energy per km than your median. The worst was %s: %s kWh-eq/100km over %s km.",
                    bad.size(), Ui.date(w.s.startWall), f(w.energyEq100(), 1), f(w.dist, 1)));
        }

        // ---- تشخيص ----
        if (s.peakKw > 0) {
            if (s.peakKw >= 100) a.diagnoses.add(WARN + Tr.f("Battery power peaked at %s kW: very hard acceleration stresses the pack and burns extra fuel.", f(s.peakKw, 0)));
            else a.diagnoses.add(OK + Tr.f("Peak battery power was %s kW, a moderate driving style.", f(s.peakKw, 0)));
        }
        if (s.idleDur > 300 && s.idleEng / s.idleDur > 0.15)
            a.diagnoses.add(WARN + Tr.f("The engine ran while stationary for about %s minutes in total.", f(s.idleEng / 60, 0)));
        double[] hc = hardAccelShare(m);
        if (hc != null) {
            if (hc[0] < 1) a.diagnoses.add(OK + Tr.t("Hard accelerations (above 1 m/s²) are rare: under 1% of the driving time."));
            else a.diagnoses.add((hc[0] > 12 ? WARN : OK) + Tr.f("Hard accelerations (above 1 m/s²) take %s%% of the driving time.", f(hc[0], 0)));
        }
        if (!bad.isEmpty() && bad.size() * 4 > m.pts.size())
            a.diagnoses.add(WARN + Tr.f("%s of %s trips are well above your typical consumption; look at their conditions (temperature, A/C, Forced mode, distance).", bad.size(), m.pts.size()));
        if (m.wins.isEmpty()) a.diagnoses.add(WARN + Tr.t("Operating-point charts need trips recorded with detailed logging."));
        else a.diagnoses.add(OK + Tr.f("%s operating windows (30 s each) are plotted.", m.wins.size()));

        // ---- ملاحظات ----
        if (sw != null && sw[3] > sw[1] * 1.15)
            a.actions.add(Tr.f("Where traffic allows, cruise around %s km/h; speeds around %s km/h cost about %s%% more energy.",
                    DashModel.speedLabel((int) sw[0]), DashModel.speedLabel((int) sw[2]), f((sw[3] - sw[1]) / sw[1] * 100, 0)));
        accelAction(m, a);
        if (rg != null && rg[0] < 12)
            a.actions.add(Tr.t("Regen recovers little: lift off earlier and let the car slow with regenerative braking instead of braking late, and try a stronger regen level."));
        if (s.idleDur > 300 && s.idleEng / s.idleDur > 0.15)
            a.actions.add(Tr.t("Reduce engine idling: use EV mode when stopped and avoid very low A/C set points while parked."));
    }

    // ------------------------------------------------------------ قواعد مشتركة
    private static void forcedAction(DashModel m, S s, Advice a) {
        if (!s.hasForced()) return;
        double[] fs = forcedGap(m);
        if (fs != null)
            a.actions.add(Tr.f("Keep the Forced target within about 15%% of the current battery: with a gap above 30%% the engine burned %s L/h versus %s L/h near the target (%s times more). For a bigger gap, drive gently or stay in Smart.",
                    f(fs[0], 1), f(fs[1], 1), f(fs[1] > 0.05 ? fs[0] / fs[1] : Double.NaN, 1)));
        else
            a.actions.add(Tr.t("Use Smart Power Save by default and reserve Forced charging for when you need EV range later, for example before a city leg."));
    }

    private static void speedAction(DashModel m, Advice a) {
        double[] sw = sweetSpot(m);
        if (sw != null && sw[3] > sw[1] * 1.2)
            a.actions.add(Tr.f("Cruise around %s km/h when you can: it is your most efficient band (%s kWh-eq/100km), while %s km/h costs %s%% more.",
                    DashModel.speedLabel((int) sw[0]), f(sw[1], 1), DashModel.speedLabel((int) sw[2]), f((sw[3] - sw[1]) / sw[1] * 100, 0)));
    }

    private static void accelAction(DashModel m, Advice a) {
        double[] ac = accelShare(m);
        if (ac != null && ac[0] > 55 && ac[1] < 40)
            a.actions.add(Tr.f("Accelerate more smoothly: %s%% of fuel goes into acceleration that occupies only %s%% of the time. Anticipate traffic and ease on the accelerator.", f(ac[0], 0), f(ac[1], 0)));
    }

    private static void climateAction(DashModel m, S s, Advice a) {
        double[] ac = acPenalty(m);
        if (ac != null && ac[0] >= 10)
            a.actions.add(Tr.f("A/C costs about %s%% more energy: pre-cool the cabin while parked in shade or connected to a charger, use recirculation, and raise the set point by 1–2 °C.", f(ac[0], 0)));
        double[] tp = tempPenalty(m);
        if (tp != null && tp[0] >= 15)
            a.actions.add(Tr.f("Hot weather adds %s%% to energy use: park in shade, ventilate before starting and avoid fast driving in the hottest hours.", f(tp[0], 0)));
    }

    // ------------------------------------------------------------ حسابات مساعدة
    private static void addWin2(DashModel.Acc x, float[] w) { DashModel.addWin(x, w); }

    private static String bestWorst(DashModel m) {
        DashModel.Acc[][] ms = DashModel.accGrid(TripSession.NUM_MODES, 6);
        for (float[] w : m.wins) {
            int k = (int) w[TripSession.W_MODE];
            if (k >= 0 && k < TripSession.NUM_MODES) DashModel.addWin(ms[k][DashModel.setIndex(w)], w);
        }
        int bm = -1, bs = -1, wm = -1, ws = -1; double bv = 1e9, wv = -1;
        for (int k = 0; k < TripSession.NUM_MODES; k++) for (int j = 0; j < 6; j++) {
            double v = eq(ms[k][j]);
            if (Double.isNaN(v)) continue;
            if (v < bv) { bv = v; bm = k; bs = j; }
            if (v > wv) { wv = v; wm = k; ws = j; }
        }
        if (bm < 0 || (bm == wm && bs == ws)) return null;
        return Tr.f("Best combination: %s + %s (%s kWh-eq/100km). Worst: %s + %s (%s).",
                Tr.t(DashModel.MODE_NAMES[bm]), Tr.t(DashModel.SET_NAMES[bs]), f(bv, 1),
                Tr.t(DashModel.MODE_NAMES[wm]), Tr.t(DashModel.SET_NAMES[ws]), f(wv, 1));
    }

    /** {أفضل شريحة سرعة، طاقتها، أسوأ شريحة، طاقتها} من الشبكة، أو null. */
    private static double[] sweetSpot(DashModel m) {
        double[] dist = new double[TripSession.SPEED_BINS], eq = new double[TripSession.SPEED_BINS];
        for (TripSession s : m.trips)
            for (int md = 0; md < TripSession.NUM_MODES; md++) {
                if (m.mode != 0 && DashModel.MODE_ID[m.mode] != md) continue;
                for (int sb = 0; sb < TripSession.SPEED_BINS; sb++) for (int ab = 0; ab < TripSession.ACCEL_BINS; ab++) {
                    TripSession.Gc g = s.grid[md][sb][ab];
                    dist[sb] += g.distKm; eq[sb] += g.fuel * DashModel.KWH_PER_L + g.out - g.in;
                }
            }
        int best = -1, worst = -1;
        for (int i = 1; i < dist.length; i++) {
            if (dist[i] < 3) continue;
            double v = eq[i] / dist[i];
            if (best < 0 || v < eq[best] / dist[best]) best = i;
            if (worst < 0 || v > eq[worst] / dist[worst]) worst = i;
        }
        if (best < 0 || best == worst) return null;
        return new double[]{best, eq[best] / dist[best] * 100, worst, eq[worst] / dist[worst] * 100};
    }

    /** {حصة الوقود أثناء التسارع %، حصة الزمن %} */
    private static double[] accelShare(DashModel m) {
        double fa = 0, fall = 0, ta = 0, tall = 0;
        for (TripSession s : m.trips)
            for (int md = 0; md < TripSession.NUM_MODES; md++) {
                if (m.mode != 0 && DashModel.MODE_ID[m.mode] != md) continue;
                for (int sb = 0; sb < TripSession.SPEED_BINS; sb++) for (int ab = 0; ab < TripSession.ACCEL_BINS; ab++) {
                    TripSession.Gc g = s.grid[md][sb][ab];
                    fall += g.fuel; tall += g.durMs;
                    if (ab >= 3) { fa += g.fuel; ta += g.durMs; }
                }
            }
        return fall > 0.3 && tall > 0 && ta / tall >= 0.01 ? new double[]{fa / fall * 100, ta / tall * 100} : null;
    }

    private static double[] hardAccelShare(DashModel m) {
        double ha = 0, all = 0;
        for (TripSession s : m.trips)
            for (int md = 0; md < TripSession.NUM_MODES; md++) {
                if (m.mode != 0 && DashModel.MODE_ID[m.mode] != md) continue;
                for (int sb = 0; sb < TripSession.SPEED_BINS; sb++) for (int ab = 0; ab < TripSession.ACCEL_BINS; ab++) {
                    TripSession.Gc g = s.grid[md][sb][ab];
                    all += g.durMs; if (ab == 4) ha += g.durMs;
                }
            }
        return all > 60_000 ? new double[]{ha / all * 100} : null;
    }

    /** {نسبة الاسترداد % من طاقة البطارية المصروفة، كيلوواط ساعة مستردة} */
    private static double[] regenShare(DashModel m) {
        double out = 0, in = 0;
        for (DashModel.Pt p : m.pts) { out += p.out; in += p.in; }
        return out > 1 ? new double[]{in / out * 100, in} : null;
    }

    private static double speedEfficiencyCorrelation(DashModel m) {
        int n = 0; double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (DashModel.Pt p : m.pts) {
            double x = p.avgSpeed(), y = p.energyEq100();
            if (Double.isNaN(x) || Double.isNaN(y) || p.dist < 3) continue;
            n++; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y;
        }
        if (n < 5) return Double.NaN;
        double den = (n * sxx - sx * sx) * (n * syy - sy * sy);
        return den > 1e-9 ? (n * sxy - sx * sy) / Math.sqrt(den) : Double.NaN;
    }

    private static List<DashModel.Pt> outliers(DashModel m) {
        List<Double> v = new ArrayList<>();
        for (DashModel.Pt p : m.pts) if (p.dist >= 3 && !Double.isNaN(p.energyEq100())) v.add(p.energyEq100());
        List<DashModel.Pt> out = new ArrayList<>();
        if (v.size() < 5) return out;
        java.util.Collections.sort(v);
        double med = v.get(v.size() / 2);
        for (DashModel.Pt p : m.pts) if (p.dist >= 3 && !Double.isNaN(p.energyEq100()) && p.energyEq100() >= med * 1.5) out.add(p);
        out.sort((x, y) -> Double.compare(y.energyEq100(), x.energyEq100()));
        return out;
    }

    /** {زيادة الاستهلاك % فوق 38°، طاقة حار، طاقة بارد} أو null. */
    private static double[] tempPenalty(DashModel m) {
        DashModel.Acc cool = new DashModel.Acc(), hot = new DashModel.Acc();
        for (float[] w : m.wins) {
            float t = w[TripSession.W_TEMP];
            if (Float.isNaN(t)) continue;
            if (t < 30) DashModel.addWin(cool, w); else if (t >= 38) DashModel.addWin(hot, w);
        }
        double a = eq(cool), b = eq(hot);
        return Double.isNaN(a) || Double.isNaN(b) || a <= 0 ? null : new double[]{(b - a) / a * 100, b, a};
    }

    /** {زيادة % بسبب المكيف، طاقة مع المكيف، طاقة بدونه} أو null. */
    private static double[] acPenalty(DashModel m) {
        DashModel.Acc on = new DashModel.Acc(), off = new DashModel.Acc();
        for (float[] w : m.wins) DashModel.addWin(w[TripSession.W_AC] > 0.5f ? on : off, w);
        double a = eq(off), b = eq(on);
        return Double.isNaN(a) || Double.isNaN(b) || a <= 0 ? null : new double[]{(b - a) / a * 100, b, a};
    }

    /** {وقود لتر/س عند فجوة >30%، وقود لتر/س عند فجوة ≤15%} في Forced، أو null. */
    private static double[] forcedGap(DashModel m) {
        DashModel.Acc near = new DashModel.Acc(), far = new DashModel.Acc();
        for (float[] w : m.wins) {
            if (w[TripSession.W_FORCED] <= 0.5f) continue;
            float tg = w[TripSession.W_TARGET], soc = w[TripSession.W_SOC];
            if (Float.isNaN(tg) || Float.isNaN(soc)) continue;
            double d = tg - soc;
            if (d <= 15) DashModel.addWin(near, w); else if (d > 30) DashModel.addWin(far, w);
        }
        if (near.dur < 180 || far.dur < 180) return null;
        return new double[]{far.fuel / (far.dur / 3600.0), near.fuel / (near.dur / 3600.0)};
    }

    /** {أفضل شريحة سرعة في Forced، لتر/100، أسوأ شريحة، لتر/100} أو null. */
    private static double[] forcedSpeedBest(DashModel m) {
        DashModel.Acc[] g = new DashModel.Acc[TripSession.SPEED_BINS];
        for (int i = 0; i < g.length; i++) g[i] = new DashModel.Acc();
        for (float[] w : m.wins) if (w[TripSession.W_FORCED] > 0.5f) DashModel.addWin(g[TripSession.speedBin(w[TripSession.W_SPD])], w);
        int b = -1, wst = -1;
        for (int i = 0; i < g.length; i++) {
            if (g[i].dist < 3) continue;
            double v = g[i].fuel / g[i].dist * 100;
            if (b < 0 || v < g[b].fuel / g[b].dist * 100) b = i;
            if (wst < 0 || v > g[wst].fuel / g[wst].dist * 100) wst = i;
        }
        if (b < 0 || b == wst || g[wst].fuel / g[wst].dist < 1.15 * g[b].fuel / g[b].dist) return null;
        return new double[]{b, g[b].fuel / g[b].dist * 100, wst, g[wst].fuel / g[wst].dist * 100};
    }

    /** {وقود الرحلات القصيرة، وقود الطويلة، شريحة القصيرة...} → {طويلة L/100, قصيرة L/100, رقم شريحة القصيرة} بشرط أن تكون القصيرة أرخص. */
    private static double[] distanceEffect(DashModel m) {
        DashModel.Acc[] d = new DashModel.Acc[4];
        for (int i = 0; i < 4; i++) d[i] = new DashModel.Acc();
        for (DashModel.Pt p : m.pts) { DashModel.Acc x = d[DashModel.distBand(p.s.totalDistKm())]; x.n++; x.dist += p.dist; x.fuel += p.fuel; }
        int lo = -1, hi = -1;
        for (int i = 0; i < 4; i++) if (d[i].n >= 2 && d[i].dist >= 5) { if (lo < 0) lo = i; hi = i; }
        if (lo < 0 || hi <= lo) return null;
        return new double[]{d[hi].fuel / d[hi].dist * 100, d[lo].fuel / d[lo].dist * 100, lo};
    }

    /** أدنى مستوى بطارية عند البداية يعطي استهلاكاً قريباً من الأفضل: {الحد %، وقود تحته، وقود فوقه} أو null. */
    private static double[] startThreshold(DashModel m) {
        DashModel.Acc[] b = new DashModel.Acc[5];
        for (int i = 0; i < 5; i++) b[i] = new DashModel.Acc();
        for (DashModel.Pt p : m.pts) {
            if (Float.isNaN(p.s.socStart) || p.dist < 3) continue;
            DashModel.Acc x = b[DashModel.socBand(p.s.socStart)];
            x.n++; x.dist += p.dist; x.fuel += p.fuel;
        }
        double best = Double.MAX_VALUE;
        for (DashModel.Acc x : b) if (x.n >= 2 && x.dist >= 5) best = Math.min(best, x.fuel / x.dist * 100);
        if (best == Double.MAX_VALUE) return null;
        int cut = -1;
        for (int i = 0; i < 5; i++) if (b[i].n >= 2 && b[i].dist >= 5 && b[i].fuel / b[i].dist * 100 <= best * 1.25 + 0.3) { cut = i; break; }
        if (cut <= 0) return null;
        double lowF = 0, lowD = 0, hiF = 0, hiD = 0;
        for (int i = 0; i < 5; i++) { if (i < cut) { lowF += b[i].fuel; lowD += b[i].dist; } else { hiF += b[i].fuel; hiD += b[i].dist; } }
        if (lowD < 5 || hiD < 5) return null;
        return new double[]{cut * 20, lowF / lowD * 100, hiF / hiD * 100};
    }
}
