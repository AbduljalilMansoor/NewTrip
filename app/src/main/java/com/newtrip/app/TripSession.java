package com.newtrip.app;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * رحلة واحدة من Start إلى End.
 * كل لحظة تُنسب لخانة: [النمط] × [كهرباء EV أو محرك Engine].
 */
public final class TripSession {
    public static final int SPORT = 0, ECO = 1, NORMAL = 2, OTHERS = 3, PARK = 4;
    public static final int NUM_MODES = 5;
    public static final String[] MODE_NAMES = {"Sport", "Eco", "Normal", "Others", "Park"};
    /** VCU_1_G_PRNDGEARACT: 1=P 2=R 3=N 4=D */
    public static final int GEAR_P = 1;
    public static final int EV = 0, ENG = 1;

    /** شرائح السرعة للتحليل (Analyze). EV only = المحرك مطفأ بأي سرعة. */
    public static final int T_LOW = 0, T_MED = 1, T_HIGH = 2, T_VHIGH = 3, T_EV = 4;
    public static final int NUM_TIERS = 5;
    public static final String[] TIER_NAMES = {"Low", "Med", "High", "Very High", "EV only"};
    public static final String[] TIER_RANGES = {"Below 60", "Between 60 to 90", "Between 90 to 130", "Above 130", "Any"};

    // ---- خريطة الاستهلاك: [النمط][شريحة السرعة 10كم/س][شريحة التسارع] ----
    public static final int SPEED_BINS = 14, ACCEL_BINS = 5;
    public static final String[] ACCEL_NAMES = {"Hard brake", "Brake/coast", "Cruise", "Accelerate", "Hard accel"};

    public static int speedBin(float kmh) { return Math.max(0, Math.min(SPEED_BINS - 1, (int) (kmh / 10f))); }

    /** a بالمتر/ث²: < -1 ، < -0.3 ، ≤ 0.3 ، ≤ 1 ، > 1 */
    public static int accelBin(float a) {
        if (a < -1.0f) return 0;
        if (a < -0.3f) return 1;
        if (a <= 0.3f) return 2;
        if (a <= 1.0f) return 3;
        return 4;
    }

    public static final class Gc {
        public long durMs, engMs;
        public double distKm, out, in, fuel;
        boolean empty() { return durMs == 0 && fuel == 0; }
    }

    // ---- نوافذ 30 ثانية (للمخططات التشتتية وخرائط SOC/الحرارة) ----
    public static final long WIN_MS = 30_000;
    public static final int MAX_WINS = 1500;
    /** فهارس float[] لكل نافذة */
    public static final int W_T = 0, W_DUR = 1, W_DIST = 2, W_SPD = 3, W_OUT = 4, W_IN = 5, W_FUEL = 6,
            W_ENG = 7, W_SOC = 8, W_FORCED = 9, W_TARGET = 10, W_TEMP = 11, W_AC = 12, W_MODE = 13,
            W_MAXKW = 14, W_HOUR = 15, W_RPM = 16, W_RPMMAX = 17, W_REGEN = 18, W_CABIN = 19, W_PACK = 20,
            W_SOC0 = 21, W_COOL = 22, W_SETT = 23, W_LEN = 24;
    public static final int MAX_EVENTS = 80;
    private static final double NAN_S = -999;

    public static int tierOf(boolean engineOn, Float speedKmh) {
        if (!engineOn) return T_EV;
        float v = speedKmh == null ? 0f : speedKmh;
        if (v < 60f) return T_LOW;
        if (v < 90f) return T_MED;
        if (v <= 130f) return T_HIGH;
        return T_VHIGH;
    }

    /** تجميع شريحة سرعة داخل نمط قيادة: المدة والمسافة والوقود، ووقت Smart مقابل Forced. */
    public static final class Tier {
        public long durMs, smartMs, forcedMs;
        public double distKm, fuelL;
        public float targetMin = Float.NaN, targetMax = Float.NaN;   // Forced SOC target المرصود

        void noteTarget(float t) {
            if (Float.isNaN(t)) return;
            if (Float.isNaN(targetMin) || t < targetMin) targetMin = t;
            if (Float.isNaN(targetMax) || t > targetMax) targetMax = t;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("dur", durMs).put("smart", smartMs).put("forced", forcedMs)
             .put("dist", distKm).put("fuel", fuelL);
            putF(o, "tMin", targetMin); putF(o, "tMax", targetMax);
            return o;
        }

        static Tier fromJson(JSONObject o) {
            Tier t = new Tier();
            t.durMs = o.optLong("dur"); t.smartMs = o.optLong("smart"); t.forcedMs = o.optLong("forced");
            t.distKm = o.optDouble("dist", 0); t.fuelL = o.optDouble("fuel", 0);
            t.targetMin = getF(o, "tMin"); t.targetMax = getF(o, "tMax");
            return t;
        }
    }

    public static final class Cell {
        public long durMs;
        public double distKm, kwhOut, kwhIn, fuelL, refuelPct;
        public float socFirst = Float.NaN, socLast = Float.NaN;
        public float fuelFirst = Float.NaN, fuelLast = Float.NaN;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("dur", durMs).put("dist", distKm).put("out", kwhOut).put("in", kwhIn)
             .put("fuel", fuelL).put("refuel", refuelPct);
            putF(o, "socF", socFirst); putF(o, "socL", socLast);
            putF(o, "fuelF", fuelFirst); putF(o, "fuelL", fuelLast);
            return o;
        }

        static Cell fromJson(JSONObject o) {
            Cell c = new Cell();
            c.durMs = o.optLong("dur");
            c.distKm = o.optDouble("dist", 0);
            c.kwhOut = o.optDouble("out", 0);
            c.kwhIn = o.optDouble("in", 0);
            c.fuelL = o.optDouble("fuel", 0);
            c.refuelPct = o.optDouble("refuel", 0);
            c.socFirst = getF(o, "socF"); c.socLast = getF(o, "socL");
            c.fuelFirst = getF(o, "fuelF"); c.fuelLast = getF(o, "fuelL");
            return c;
        }
    }

    public long startWall, endWall;
    public boolean active;
    /** true = القيمة السالبة لقدرة البطارية تعني شحناً (الافتراضي في DisplayMirror). */
    public boolean negIsIn = true;
    public float socStart = Float.NaN, socEnd = Float.NaN, fuelStart = Float.NaN, fuelEnd = Float.NaN;
    public float odoStart = Float.NaN, odoEnd = Float.NaN;
    public final Cell[][] cells = new Cell[NUM_MODES][2];
    /** [نمط القيادة][شريحة السرعة]. الرحلات القديمة (قبل Analyze) بلا بيانات: hasTiers = false. */
    public final Tier[][] tiers = new Tier[NUM_MODES][NUM_TIERS];
    public boolean hasTiers = true;
    public final Gc[][][] grid = new Gc[NUM_MODES][SPEED_BINS][ACCEL_BINS];
    public final List<float[]> wins = new ArrayList<>();
    /** زمن إشارة HCU_POWERMODEFED: EV / HEV (بالملّي ثانية) */
    public long evSigMs, hevSigMs;
    public float carAvgFuel = Float.NaN, carAvgEnergy = Float.NaN;   // آخر قراءة من السيارة
    // حالة النافذة الجارية (لا تُحفظ)
    private long wDur, wEng, wForced, wAc, wTempMs;
    private double wDist, wOut, wIn, wFuel, wTempSum;
    private float wMaxKw, wTarget = Float.NaN, wSoc = Float.NaN;
    private int wMode;
    private long wStartWall, wStartAct;
    /** مدة القيادة الفعلية (بدون فجوات الإيقاف) بالملّي ثانية؛ أساس محور الزمن في الرسوم. */
    public long activeMs;
    private float prevV = Float.NaN, accelEma;
    private long accelAccMs;
    // نافذة: RPM والاسترداد والحرارة
    private double wRpmSum, wRegenSum, wCabinSum, wPackSum, wCoolSum, wSetSum;
    private long wRpmMs, wRegenMs, wCabinMs, wPackMs, wCoolMs, wSetMs;
    private float wRpmMax, wSoc0 = Float.NaN;
    // إجماليات الرحلة (تُحفظ)
    public double rpmSum, regenSum, cabinSum, packSum, coolSum;
    public long rpmMs, regenMs, cabinMs, packMs, coolMs, acMs;
    public float rpmMax, packMax = Float.NaN;
    /** تغيّرات وضع Smart/Forced: {ثانية من بداية الرحلة، forced(0/1)، الهدف، SOC، السرعة} */
    public final List<float[]> events = new ArrayList<>();
    private boolean evInit, evLastForced;
    private float evLastTarget = Float.NaN;
    /** وضع توفير الطاقة اليدوي (يُستخدم إذا لم تتوفر إشارة من السيارة). */
    public boolean manualForced;
    public float manualTarget = Float.NaN;

    /** ملف الرحلة في السجل (لا يُحفظ، يُضبط عند القراءة). */
    public transient java.io.File file;

    private long lastAdvance = -1;   // elapsedRealtime، لا يُحفظ
    private float minFuelPct = Float.NaN;

    public TripSession() {
        for (int m = 0; m < NUM_MODES; m++) for (int k = 0; k < 2; k++) cells[m][k] = new Cell();
        for (int m = 0; m < NUM_MODES; m++) for (int t = 0; t < NUM_TIERS; t++) tiers[m][t] = new Tier();
        for (int m = 0; m < NUM_MODES; m++)
            for (int a = 0; a < SPEED_BINS; a++) for (int b = 0; b < ACCEL_BINS; b++) grid[m][a][b] = new Gc();
    }

    public static int bucket(Integer mode) {
        if (mode == null) return OTHERS;
        switch (mode) {
            case 0: return ECO;
            case 1: return NORMAL;
            case 2: return SPORT;
            default: return OTHERS;
        }
    }

    /** الغِيار P = وقوف. كل ما تستهلكه السيارة وهي واقفة (مكيف، محرك يشتغل للشحن...) يُنسب لخانة Park. */
    public static boolean isParked(Live l) {
        Integer g = l.gear;
        return g != null && g == GEAR_P;
    }

    /** الخانة الحالية: Park إذا كان الغِيار P، وإلا نمط القيادة. */
    public static int bucketOf(Live l) {
        return isParked(l) ? PARK : bucket(l.driveMode);
    }

    private Cell cur(Live l) { return cells[bucketOf(l)][l.engineOn ? ENG : EV]; }

    /** يُستدعى قبل أي تغيّر في القيم الحية: ينسب الفترة الماضية للخانة الحالية. */
    public synchronized void advance(long now, Live l) {
        if (!active) { lastAdvance = now; return; }
        if (lastAdvance > 0) {
            long dt = now - lastAdvance;
            if (dt > 0 && dt <= 5000) { // الفجوات الطويلة (السيارة مطفأة) لا تُحسب
                activeMs += dt;
                int mode = bucketOf(l);
                boolean eng = l.engineOn;
                Cell c = cells[mode][eng ? ENG : EV];
                c.durMs += dt;
                double h = dt / 3_600_000.0;
                Float v = l.speedKmh;
                double dKm = v != null ? v * h : 0;
                c.distKm += dKm;
                double outK = 0, inK = 0;
                Float p = l.packKw;
                float netKw = 0;
                if (p != null) {
                    float out = negIsIn ? p : -p;   // موجب = صرف من البطارية
                    netKw = out;
                    if (out >= 0) outK = out * h; else inK = -out * h;
                }
                c.kwhOut += outK; c.kwhIn += inK;
                Tier t = tiers[mode][tierOf(eng, v)];
                t.durMs += dt;
                t.distKm += dKm;
                boolean forced = isForced(l);
                float target = targetSoc(l);
                if (forced) { t.forcedMs += dt; t.noteTarget(target); }
                else t.smartMs += dt;

                // التسارع (متوسط متحرك) ثم خلية الشبكة
                float vv = v == null ? 0f : v;
                if (Float.isNaN(prevV)) prevV = vv;
                accelAccMs += dt;
                if (accelAccMs >= 300) {
                    float inst = (vv - prevV) / 3.6f / (accelAccMs / 1000f);
                    accelEma = 0.6f * accelEma + 0.4f * inst;
                    prevV = vv;
                    accelAccMs = 0;
                }
                Gc g = grid[mode][speedBin(vv)][accelBin(accelEma)];
                g.durMs += dt; g.distKm += dKm; g.out += outK; g.in += inK;
                if (eng) g.engMs += dt;

                // النافذة الجارية
                if (wDur == 0) { wStartWall = System.currentTimeMillis(); wStartAct = activeMs - dt; }
                wDur += dt; wDist += dKm; wOut += outK; wIn += inK;
                if (eng) wEng += dt;
                if (forced) { wForced += dt; if (!Float.isNaN(target)) wTarget = target; }
                Float temp = l.outsideTempC;
                if (temp != null) { wTempSum += temp * (double) dt; wTempMs += dt; }
                if (l.acOn != null && l.acOn > 0) wAc += dt;
                if (netKw > wMaxKw) wMaxKw = netKw;
                wMode = mode;
                if (l.socPct != null) wSoc = l.socPct;
                if (l.hevMode != null) { if (l.hevMode == 1) evSigMs += dt; else if (l.hevMode == 2) hevSigMs += dt; }
                // RPM / A/C / الاسترداد / الحرارة
                Integer rpm = l.rpm;
                if (rpm != null && rpm > 0) {
                    rpmSum += rpm * (double) dt; rpmMs += dt; if (rpm > rpmMax) rpmMax = rpm;
                    wRpmSum += rpm * (double) dt; wRpmMs += dt; if (rpm > wRpmMax) wRpmMax = rpm;
                }
                if (l.acOn != null && l.acOn > 0) acMs += dt;
                Integer rg = l.regenLevel;
                if (rg != null) { regenSum += rg * (double) dt; regenMs += dt; wRegenSum += rg * (double) dt; wRegenMs += dt; }
                Float cab = l.cabinTempC;
                if (cab != null) { cabinSum += cab * (double) dt; cabinMs += dt; wCabinSum += cab * (double) dt; wCabinMs += dt; }
                Float pk = l.packTempMaxC;
                if (pk != null) {
                    packSum += pk * (double) dt; packMs += dt; wPackSum += pk * (double) dt; wPackMs += dt;
                    if (Float.isNaN(packMax) || pk > packMax) packMax = pk;
                }
                Float cl = l.coolantTempC;
                if (cl != null) { coolSum += cl * (double) dt; coolMs += dt; wCoolSum += cl * (double) dt; wCoolMs += dt; }
                Float st = l.acSetTempC;
                if (st != null && l.acOn != null && l.acOn > 0) { wSetSum += st * (double) dt; wSetMs += dt; }
                if (Float.isNaN(wSoc0) && l.socPct != null) wSoc0 = l.socPct;
                noteSocMode(forced, target, l);
                if (wDur >= WIN_MS) flushWindow();
            }
        }
        lastAdvance = now;
    }

    private void flushWindow() {
        if (wDur > 0 && wins.size() < MAX_WINS) {
            float[] w = new float[W_LEN];
            double hrs = wDur / 3_600_000.0;
            w[W_T] = wStartAct / 1000f;
            w[W_DUR] = wDur / 1000f;
            w[W_DIST] = (float) wDist;
            w[W_SPD] = hrs > 0 ? (float) (wDist / hrs) : 0f;
            w[W_OUT] = (float) wOut; w[W_IN] = (float) wIn; w[W_FUEL] = (float) wFuel;
            w[W_ENG] = wEng / 1000f;
            w[W_SOC] = wSoc;
            w[W_FORCED] = wForced * 2 > wDur ? 1f : 0f;
            w[W_TARGET] = wTarget;
            w[W_TEMP] = wTempMs > 0 ? (float) (wTempSum / wTempMs) : Float.NaN;
            w[W_AC] = wDur > 0 ? (float) wAc / wDur : 0f;
            w[W_MODE] = wMode;
            w[W_MAXKW] = wMaxKw;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(wStartWall);
            w[W_HOUR] = cal.get(Calendar.HOUR_OF_DAY);
            w[W_RPM] = wRpmMs > 0 ? (float) (wRpmSum / wRpmMs) : Float.NaN;
            w[W_RPMMAX] = wRpmMs > 0 ? wRpmMax : Float.NaN;
            w[W_REGEN] = wRegenMs > 0 ? (float) (wRegenSum / wRegenMs) : Float.NaN;
            w[W_CABIN] = wCabinMs > 0 ? (float) (wCabinSum / wCabinMs) : Float.NaN;
            w[W_PACK] = wPackMs > 0 ? (float) (wPackSum / wPackMs) : Float.NaN;
            w[W_SOC0] = wSoc0;
            w[W_COOL] = wCoolMs > 0 ? (float) (wCoolSum / wCoolMs) : Float.NaN;
            w[W_SETT] = wSetMs > 0 ? (float) (wSetSum / wSetMs) : Float.NaN;
            wins.add(w);
        }
        wDur = wEng = wForced = wAc = wTempMs = 0;
        wDist = wOut = wIn = wFuel = wTempSum = 0;
        wMaxKw = 0; wTarget = Float.NaN;
        wRpmSum = wRegenSum = wCabinSum = wPackSum = wCoolSum = wSetSum = 0;
        wRpmMs = wRegenMs = wCabinMs = wPackMs = wCoolMs = wSetMs = 0;
        wRpmMax = 0; wSoc0 = Float.NaN;
    }

    /** يسجّل لحظة تبديل Smart ↔ Forced أو تغيير الهدف مع مستوى البطارية والسرعة وقتها. */
    private void noteSocMode(boolean forced, float target, Live l) {
        boolean first = !evInit;
        boolean changed = first ? forced
                : forced != evLastForced || (forced && !(Float.isNaN(target) && Float.isNaN(evLastTarget)) && target != evLastTarget);
        if (changed && events.size() < MAX_EVENTS) {
            float t = activeMs / 1000f;
            float soc = l.socPct != null ? l.socPct : Float.NaN;
            if (first && !Float.isNaN(socStart)) soc = socStart;
            events.add(new float[]{Math.max(0f, t), forced ? 1f : 0f, forced ? target : Float.NaN, soc,
                    l.speedKmh == null ? 0f : l.speedKmh});
        }
        evInit = true; evLastForced = forced; evLastTarget = target;
    }

    // ---------- معلومات إضافية للتقارير واللوحة ----------
    public synchronized double avgRpm() { return rpmMs > 0 ? rpmSum / rpmMs : Double.NaN; }
    public synchronized double maxRpm() { return rpmMax > 0 ? rpmMax : Double.NaN; }
    /** نسبة زمن تشغيل المكيف (0–100) أو NaN. */
    public synchronized double acSharePct() { long d = totalDurMs(); return d > 0 ? acMs * 100.0 / d : Double.NaN; }
    public synchronized double avgCabinC() { return cabinMs > 0 ? cabinSum / cabinMs : Double.NaN; }
    public synchronized double avgPackC() { return packMs > 0 ? packSum / packMs : Double.NaN; }
    public synchronized double maxPackC() { return packMax; }
    public synchronized double avgRegen() { return regenMs > 0 ? regenSum / regenMs : Double.NaN; }
    public synchronized double avgCoolantC() { return coolMs > 0 ? coolSum / coolMs : Double.NaN; }

    /** أول دخول في Forced: {ثانية، SOC، الهدف، العجز = الهدف − SOC، السرعة} أو null. */
    public synchronized float[] forcedStart() {
        for (float[] e : events) if (e[1] > 0.5f) {
            float deficit = Float.isNaN(e[2]) || Float.isNaN(e[3]) ? Float.NaN : e[2] - e[3];
            return new float[]{e[0], e[3], e[2], deficit, e[4]};
        }
        return null;
    }

    /** يُستدعى عند إنهاء الرحلة: يحفظ ما تبقى من النافذة الأخيرة إن كانت 5 ثوانٍ فأكثر. */
    public synchronized void closeWindow() {
        if (wDur >= 5000) flushWindow();
    }

    public synchronized void addFuel(double litres, Live l) {
        if (!active || litres <= 0) return;
        cur(l).fuelL += litres;
        int mode = bucketOf(l);
        tiers[mode][tierOf(l.engineOn, l.speedKmh)].fuelL += litres;
        float vv = l.speedKmh == null ? 0f : l.speedKmh;
        grid[mode][speedBin(vv)][accelBin(accelEma)].fuel += litres;
        wFuel += litres;
    }

    /** Forced Power Saving؟ الإشارة من السيارة إن وُجدت، وإلا الاختيار اليدوي. */
    public boolean isForced(Live l) {
        return l.powerSaveForced != null ? l.powerSaveForced : manualForced;
    }

    /** هدف SOC في وضع Forced (NaN إن لم يُعرف). */
    public float targetSoc(Live l) {
        return l.forcedSocTarget != null ? l.forcedSocTarget : manualTarget;
    }

    /** يُستدعى كل ثانية لتسجيل البطارية والوقود والعداد. */
    public synchronized void sampleLevels(Live l) {
        if (l.carAvgFuel != null) carAvgFuel = l.carAvgFuel;
        if (l.carAvgEnergy != null) carAvgEnergy = l.carAvgEnergy;
        if (!active) return;
        Cell c = cur(l);
        Float soc = l.socPct;
        if (soc != null) {
            if (Float.isNaN(c.socFirst)) c.socFirst = soc;
            c.socLast = soc;
            if (Float.isNaN(socStart)) socStart = soc;
            socEnd = soc;
        }
        Float fuel = l.fuelPct;
        if (fuel != null) {
            if (Float.isNaN(c.fuelFirst)) c.fuelFirst = fuel;
            c.fuelLast = fuel;
            if (Float.isNaN(fuelStart)) fuelStart = fuel;
            fuelEnd = fuel;
            // اكتشاف التعبئة: ارتفاع 8% أو أكثر عن أدنى قيمة
            if (Float.isNaN(minFuelPct) || fuel < minFuelPct) minFuelPct = fuel;
            else if (fuel - minFuelPct >= 8f) { c.refuelPct += fuel - minFuelPct; minFuelPct = fuel; }
        }
        Float odo = l.odometerKm;
        if (odo != null) {
            if (Float.isNaN(odoStart)) odoStart = odo;
            odoEnd = odo;
        }
    }

    // ---------- مجاميع ----------
    public synchronized Cell total(int kind) {
        Cell t = new Cell();
        for (int m = 0; m < NUM_MODES; m++) {
            Cell c = cells[m][kind];
            t.durMs += c.durMs; t.distKm += c.distKm; t.kwhOut += c.kwhOut; t.kwhIn += c.kwhIn;
            t.fuelL += c.fuelL; t.refuelPct += c.refuelPct;
        }
        t.socFirst = socStart; t.socLast = socEnd; t.fuelFirst = fuelStart; t.fuelLast = fuelEnd;
        return t;
    }

    public synchronized double totalDistKm() { return total(EV).distKm + total(ENG).distKm; }
    public synchronized double totalFuelL() { return total(EV).fuelL + total(ENG).fuelL; }
    public synchronized double totalKwhOut() { return total(EV).kwhOut + total(ENG).kwhOut; }
    public synchronized double totalKwhIn() { return total(EV).kwhIn + total(ENG).kwhIn; }
    public synchronized long totalDurMs() { return total(EV).durMs + total(ENG).durMs; }

    /** نسبة المسافة على الكهرباء. */
    public synchronized double evShare() {
        double all = totalDistKm();
        return all > 0.01 ? total(EV).distKm / all * 100.0 : 0;
    }

    // ---------- معلومات الرحلة (تُسجَّل في التقرير) ----------
    public synchronized long engineMs() { return total(ENG).durMs; }

    /** Hybrid إذا اشتغل المحرك في أي لحظة من الرحلة، وإلا Electric. */
    public synchronized String driveType() {
        if (evSigMs + hevSigMs > 0) {      // من إشارة السيارة HCU_POWERMODEFED (1=EV, 2=HEV)
            double evShareT = evSigMs / (double) (evSigMs + hevSigMs);
            if (evShareT > 0.95) return "Electric";
            if (evShareT < 0.05) return "Hybrid";
            return "Mixed";
        }
        return engineMs() > 0 ? "Hybrid" : "Electric";
    }

    /** متوسط حرارة الخارج (°C) من النوافذ، NaN إن لم تُسجَّل. */
    public synchronized double avgTempC() {
        double sum = 0, w = 0;
        for (float[] x : wins) if (!Float.isNaN(x[W_TEMP])) { sum += x[W_TEMP] * x[W_DUR]; w += x[W_DUR]; }
        return w > 0 ? sum / w : Double.NaN;
    }

    public synchronized boolean hasGrid() {
        for (Gc[][] a : grid) for (Gc[] b : a) for (Gc g : b) if (!g.empty()) return true;
        return false;
    }

    public synchronized long smartMs() {
        long x = 0;
        for (Tier[] row : tiers) for (Tier t : row) x += t.smartMs;
        return x;
    }

    public synchronized long forcedMs() {
        long x = 0;
        for (Tier[] row : tiers) for (Tier t : row) x += t.forcedMs;
        return x;
    }

    /** Smart Power Save / Forced Power Saving / Mixed (إذا تغيّر الوضع أثناء الرحلة). */
    public synchronized String socModeLabel() {
        long sm = smartMs(), fo = forcedMs();
        if (sm == 0 && fo == 0) return "—";
        if (fo == 0) return "Smart Power Save";
        if (sm == 0) return "Forced Power Saving";
        return "Mixed";
    }

    /** Target SOC لوضع Forced، مثل "30%" أو "30–40%"، و"—" إن لم يوجد. */
    public synchronized String targetLabel() {
        float lo = Float.NaN, hi = Float.NaN;
        for (Tier[] row : tiers) for (Tier t : row) {
            if (!Float.isNaN(t.targetMin) && (Float.isNaN(lo) || t.targetMin < lo)) lo = t.targetMin;
            if (!Float.isNaN(t.targetMax) && (Float.isNaN(hi) || t.targetMax > hi)) hi = t.targetMax;
        }
        return targetText(lo, hi);
    }

    /** {أدنى, أعلى} لهدف SOC في وضع Forced، أو NaN إن لم يوجد. */
    public synchronized float[] targetRange() {
        float lo = Float.NaN, hi = Float.NaN;
        for (Tier[] row : tiers) for (Tier t : row) {
            if (!Float.isNaN(t.targetMin) && (Float.isNaN(lo) || t.targetMin < lo)) lo = t.targetMin;
            if (!Float.isNaN(t.targetMax) && (Float.isNaN(hi) || t.targetMax > hi)) hi = t.targetMax;
        }
        return new float[]{lo, hi};
    }

    public static String targetText(float lo, float hi) {
        if (Float.isNaN(lo) || Float.isNaN(hi)) return "—";
        if (Math.round(lo) == Math.round(hi)) return Math.round(lo) + "%";
        return Math.round(lo) + "–" + Math.round(hi) + "%";
    }

    /** متوسط السرعة الكلي (كم/س) = المسافة ÷ المدة. */
    public synchronized double avgSpeedKmh() {
        long d = totalDurMs();
        return d > 0 ? totalDistKm() / (d / 3_600_000.0) : Double.NaN;
    }

    // ---------- JSON ----------
    public synchronized JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", 2).put("start", startWall).put("end", endWall).put("active", active).put("negIsIn", negIsIn);
        putF(o, "socStart", socStart); putF(o, "socEnd", socEnd);
        putF(o, "fuelStart", fuelStart); putF(o, "fuelEnd", fuelEnd);
        putF(o, "odoStart", odoStart); putF(o, "odoEnd", odoEnd);
        putF(o, "minFuel", minFuelPct);
        // معلومات الرحلة المسجّلة (للقراءة السريعة من الملف)
        o.put("hasTiers", hasTiers).put("mForced", manualForced);
        putF(o, "mTarget", manualTarget);
        o.put("driveType", driveType()).put("socMode", socModeLabel()).put("targetSoc", targetLabel());
        double avg = avgSpeedKmh();
        if (!Double.isNaN(avg)) o.put("avgKmh", Math.round(avg * 10) / 10.0);
        JSONArray tr = new JSONArray();
        for (int m = 0; m < NUM_MODES; m++) {
            JSONArray row = new JSONArray();
            for (int t = 0; t < NUM_TIERS; t++) row.put(tiers[m][t].toJson());
            tr.put(row);
        }
        o.put("tiers", tr);
        o.put("evSigMs", evSigMs).put("hevSigMs", hevSigMs);
        putF(o, "carAvgFuel", carAvgFuel); putF(o, "carAvgEnergy", carAvgEnergy);
        JSONArray gr = new JSONArray();
        for (int m = 0; m < NUM_MODES; m++)
            for (int a = 0; a < SPEED_BINS; a++)
                for (int b = 0; b < ACCEL_BINS; b++) {
                    Gc g = grid[m][a][b];
                    if (g.empty()) continue;
                    gr.put(new JSONArray().put(m).put(a).put(b).put(g.durMs).put(g.engMs)
                            .put(g.distKm).put(g.out).put(g.in).put(g.fuel));
                }
        o.put("grid", gr);
        JSONArray wa = new JSONArray();
        for (float[] w : wins) {
            JSONArray r = new JSONArray();
            for (float x : w) r.put(Float.isNaN(x) ? NAN_S : Math.round(x * 1000.0) / 1000.0);
            wa.put(r);
        }
        o.put("wins", wa);
        o.put("activeMs", activeMs);
        o.put("rpmSum", rpmSum).put("rpmMs", rpmMs).put("rpmMax", rpmMax)
         .put("regenSum", regenSum).put("regenMs", regenMs).put("cabinSum", cabinSum).put("cabinMs", cabinMs)
         .put("packSum", packSum).put("packMs", packMs).put("coolSum", coolSum).put("coolMs", coolMs).put("acMs", acMs);
        putF(o, "packMax", packMax);
        double ar = avgRpm();
        if (!Double.isNaN(ar)) o.put("avgRpm", Math.round(ar));
        JSONArray ev = new JSONArray();
        for (float[] e : events) {
            JSONArray r = new JSONArray();
            for (float x : e) r.put(Float.isNaN(x) ? NAN_S : Math.round(x * 100.0) / 100.0);
            ev.put(r);
        }
        o.put("events", ev);
        JSONArray a = new JSONArray();
        for (int m = 0; m < NUM_MODES; m++) {
            JSONArray row = new JSONArray();
            row.put(cells[m][EV].toJson());
            row.put(cells[m][ENG].toJson());
            a.put(row);
        }
        o.put("cells", a);
        return o;
    }

    public static TripSession fromJson(JSONObject o) throws JSONException {
        TripSession s = new TripSession();
        s.startWall = o.getLong("start");
        s.endWall = o.optLong("end");
        s.active = o.optBoolean("active");
        s.negIsIn = o.optBoolean("negIsIn", true);
        s.socStart = getF(o, "socStart"); s.socEnd = getF(o, "socEnd");
        s.fuelStart = getF(o, "fuelStart"); s.fuelEnd = getF(o, "fuelEnd");
        s.odoStart = getF(o, "odoStart"); s.odoEnd = getF(o, "odoEnd");
        s.minFuelPct = getF(o, "minFuel");
        s.manualForced = o.optBoolean("mForced");
        s.manualTarget = getF(o, "mTarget");
        JSONArray tr = o.optJSONArray("tiers");
        s.hasTiers = tr != null;       // رحلة قديمة = بلا بيانات شرائح
        if (tr != null) {
            for (int m = 0; m < NUM_MODES && m < tr.length(); m++) {
                JSONArray row = tr.getJSONArray(m);
                for (int t = 0; t < NUM_TIERS && t < row.length(); t++)
                    s.tiers[m][t] = Tier.fromJson(row.getJSONObject(t));
            }
        }
        s.evSigMs = o.optLong("evSigMs"); s.hevSigMs = o.optLong("hevSigMs");
        s.carAvgFuel = getF(o, "carAvgFuel"); s.carAvgEnergy = getF(o, "carAvgEnergy");
        JSONArray gr = o.optJSONArray("grid");
        if (gr != null) for (int i = 0; i < gr.length(); i++) {
            JSONArray r = gr.getJSONArray(i);
            int m = r.getInt(0), sb = r.getInt(1), ab = r.getInt(2);
            if (m < 0 || m >= NUM_MODES || sb < 0 || sb >= SPEED_BINS || ab < 0 || ab >= ACCEL_BINS) continue;
            Gc g = s.grid[m][sb][ab];
            g.durMs = r.getLong(3); g.engMs = r.getLong(4);
            g.distKm = r.getDouble(5); g.out = r.getDouble(6); g.in = r.getDouble(7); g.fuel = r.getDouble(8);
        }
        JSONArray wa = o.optJSONArray("wins");
        if (wa != null) for (int i = 0; i < wa.length(); i++) {
            JSONArray r = wa.getJSONArray(i);
            float[] w = new float[W_LEN];
            java.util.Arrays.fill(w, Float.NaN);
            for (int k = 0; k < W_LEN && k < r.length(); k++) {
                double x = r.getDouble(k);
                w[k] = x <= -998 ? Float.NaN : (float) x;
            }
            s.wins.add(w);
        }
        s.activeMs = o.optLong("activeMs");
        s.rpmSum = o.optDouble("rpmSum", 0); s.rpmMs = o.optLong("rpmMs"); s.rpmMax = (float) o.optDouble("rpmMax", 0);
        s.regenSum = o.optDouble("regenSum", 0); s.regenMs = o.optLong("regenMs");
        s.cabinSum = o.optDouble("cabinSum", 0); s.cabinMs = o.optLong("cabinMs");
        s.packSum = o.optDouble("packSum", 0); s.packMs = o.optLong("packMs");
        s.coolSum = o.optDouble("coolSum", 0); s.coolMs = o.optLong("coolMs"); s.acMs = o.optLong("acMs");
        s.packMax = getF(o, "packMax");
        JSONArray evs = o.optJSONArray("events");
        if (evs != null) for (int i = 0; i < evs.length(); i++) {
            JSONArray r = evs.getJSONArray(i);
            float[] e = new float[5];
            for (int k = 0; k < 5 && k < r.length(); k++) { double x = r.getDouble(k); e[k] = x <= -998 ? Float.NaN : (float) x; }
            s.events.add(e);
        }
        JSONArray a = o.getJSONArray("cells");
        for (int m = 0; m < NUM_MODES && m < a.length(); m++) {
            JSONArray row = a.getJSONArray(m);
            s.cells[m][EV] = Cell.fromJson(row.getJSONObject(0));
            s.cells[m][ENG] = Cell.fromJson(row.getJSONObject(1));
        }
        return s;
    }

    static void putF(JSONObject o, String k, float v) throws JSONException {
        if (!Float.isNaN(v)) o.put(k, (double) v);
    }

    static float getF(JSONObject o, String k) {
        return o.has(k) ? (float) o.optDouble(k, Double.NaN) : Float.NaN;
    }
}
