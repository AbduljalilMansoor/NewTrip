package com.newtrip.app;

/** آخر القيم المعروفة من السيارة. null = غير متوفرة. الأوقات بـ elapsedRealtime. */
public final class Live {
    public volatile Float speedKmh;
    public volatile Integer rpm;
    public volatile Float packKw;        // كما تأتي من السيارة (الإشارة حسب الطراز)
    public volatile Integer driveMode;   // 0=ECO 1=NORMAL 2=SPORT 3+=أخرى
    public volatile Float socPct;
    public volatile Float fuelPct;
    public volatile Float fuelLitres;
    public volatile Float odometerKm;
    public volatile Integer gear;
    public volatile Float evRangeKm;     // المدى الكهربائي = SOC × 100 كم (أقصى مدى للبطارية 100 كم)
    public volatile Float evRangeRawKm;  // القيمة الخام من السيارة (VCU_CLTC_RANGEAVAL) للتشخيص فقط
    public volatile Boolean powerSaveForced; // true = Forced Power Saving، false = Smart، null = الإشارة غير متوفرة
    public volatile Float forcedSocTarget;   // هدف SOC في وضع Forced إن توفرت الإشارة
    public volatile Float rangeKm;       // المدى في شاشة العدادات (MCU_PERIOD_TCDTE)

    /** أقصى مدى كهربائي للبطارية بالكيلومتر (عند 100%). */
    public static final float EV_MAX_RANGE_KM = 100f;

    public volatile boolean engineOn;
    public volatile float fuelFlowLph = Float.NaN;

    // للتشخيص
    public volatile long speedCbTs, rpmCbTs, powerCbTs, modeCbTs, fuelCbTs;
    public volatile long fuelCbCount;
    public volatile String source = "غير متصل";
    public volatile String realtime = "—";

    public static String modeName(Integer m) {
        if (m == null) return "—";
        switch (m) {
            case 0: return "ECO";
            case 1: return "NORMAL";
            case 2: return "SPORT";
            case 3: return "SNOW";
            case 4: return "MUD";
            case 5: case 6: return "SAND";
            case 7: return "ROCK";
            default: return "MODE " + m;
        }
    }
}
