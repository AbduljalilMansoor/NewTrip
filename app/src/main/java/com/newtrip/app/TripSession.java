package com.newtrip.app;

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
                Cell c = cur(l);
                c.durMs += dt;
                double h = dt / 3_600_000.0;
                Float v = l.speedKmh;
                if (v != null) c.distKm += v * h;
                Float p = l.packKw;
                if (p != null) {
                    float out = negIsIn ? p : -p;   // موجب = صرف من البطارية
                    if (out >= 0) c.kwhOut += out * h;
                    else c.kwhIn += -out * h;
                }
                Tier t = tiers[bucketOf(l)][tierOf(l.engineOn, l.speedKmh)];
                t.durMs += dt;
                if (v != null) t.distKm += v * h;
                if (isForced(l)) { t.forcedMs += dt; t.noteTarget(targetSoc(l)); }
                else t.smartMs += dt;
            }
        }
        lastAdvance = now;
    }

    public synchronized void addFuel(double litres, Live l) {
        if (!active || litres <= 0) return;
        cur(l).fuelL += litres;
        tiers[bucketOf(l)][tierOf(l.engineOn, l.speedKmh)].fuelL += litres;
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
    public synchronized String driveType() { return engineMs() > 0 ? "Hybrid" : "Electric"; }

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
