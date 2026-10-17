package com.newtrip.app;

/**
 * منطق القرار للنمط التلقائي (بلا Android، قابل للاختبار):
 *  - البدء: السيارة مشغّلة (أو الحالة مجهولة) والسرعة ≥ 5 كم/س لثلاث نبضات (ثوانٍ) متتالية.
 *  - الإنهاء: حالة التشغيل OFF لمدة 10 ثوانٍ، أو ACC لمدة 90 ثانية، والسيارة واقفة.
 *  - بعد إنهاء يدوي أثناء القيادة لا تبدأ رحلة جديدة حتى تُطفأ السيارة (أو تقف 5 دقائق).
 * powerMode: 0 = OFF ، 1 = ACC ، 2 = ON ، 3 = START ، null = غير معروفة.
 */
final class AutoTrip {
    static final float START_KMH = 5f;
    static final int START_TICKS = 3, RESUME_STILL_TICKS = 300;
    static final long END_OFF_MS = 10_000L, END_ACC_MS = 90_000L;

    private int moving, still;
    private boolean suppress;
    private long offSince = -1;   // -1 = غير مضبوط

    /** تُستدعى كل ثانية أثناء المراقبة (لا رحلة جارية). true = ابدأ رحلة الآن. */
    boolean shouldStart(Integer powerMode, Float speedKmh) {
        if (powerMode != null && powerMode < 2) {         // مطفأة: صفّر العدّادات وارفع المنع
            suppress = false; moving = 0; still = 0;
            return false;
        }
        if (suppress) {
            still = (speedKmh == null || speedKmh < 1f) ? still + 1 : 0;
            if (still > RESUME_STILL_TICKS) suppress = false;
            return false;
        }
        moving = (speedKmh != null && speedKmh >= START_KMH) ? moving + 1 : 0;
        if (moving >= START_TICKS) { moving = 0; return true; }
        return false;
    }

    /** تُستدعى كل ثانية أثناء الرحلة. true = أنهِ الرحلة (السيارة أُطفئت). */
    boolean shouldEnd(Integer powerMode, Float speedKmh, long nowMs, boolean enabled) {
        if (!enabled) { offSince = -1; return false; }
        if (powerMode != null && powerMode < 2 && (speedKmh == null || speedKmh < 3f)) {
            if (offSince < 0) offSince = nowMs;
            return nowMs - offSince >= (powerMode == 0 ? END_OFF_MS : END_ACC_MS);
        }
        offSince = -1;
        return false;
    }

    /** عند بدء رحلة. */
    void onTripStarted() { offSince = -1; moving = 0; still = 0; }

    /** عند انتهاء رحلة. carOff = انتهت بسبب إطفاء السيارة (فلا داعي للمنع). */
    void onTripEnded(boolean carOff) { suppress = !carOff; moving = 0; still = 0; offSince = -1; }
}
