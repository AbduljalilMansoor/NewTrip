package com.newtrip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * خدمة أمامية: تعمل أثناء الرحلة (من Start إلى End).
 * في النمط التلقائي (Settings.MODE_AUTO) تبقى تعمل أيضاً بين الرحلات في حالة «مراقبة»:
 * تبدأ الرحلة تلقائياً عند تشغيل السيارة والحركة، وتنتهي عند الإطفاء.
 * تقرأ السيارة كل ثانية + تستقبل الإشارات اللحظية، وتجمعها في TripSession.
 */
public class TripService extends Service implements CarBridge.Callback {
    private static final String CH = "newtrip";
    private static final int NOTIF_ID = 1;

    /** متاح للواجهة (نفس العملية). */
    public static volatile TripService instance;

    private final CarBridge car = new CarBridge();
    private SessionStore store;
    private volatile TripSession session;
    private HandlerThread thread;
    private Handler handler;
    private long lastSave, lastNotif;
    /** رحلة لم تُحفظ منذ أكثر من هذه المدة تُعتبر منتهية (السيارة أُطفئت أو الشاشة نامت). */
    private static final long STALE_MS = 10 * 60_000L;
    private long lastTickWall = System.currentTimeMillis();
    /** قرارات النمط التلقائي (البدء والإنهاء) في صنف مستقل قابل للاختبار. */
    private final AutoTrip auto = new AutoTrip();

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, TripService.class));
    }

    /** يُستدعى بعد تغيير نمط الرحلات: التلقائي يشغّل المراقبة، واليدوي يوقفها إن لم توجد رحلة جارية. */
    public static void syncWithSettings(Context ctx) {
        if (Settings.tripMode(ctx) == Settings.MODE_AUTO) start(ctx);
        else {
            TripService s = instance;
            if (s != null && s.session() == null) ctx.stopService(new Intent(ctx, TripService.class));
        }
    }

    public Live live() { return car.live; }
    public TripSession session() { return session; }

    @Override
    public void onCreate() {
        super.onCreate();
        Settings.applyLanguage(this);
        CarBridge.loadCalibration(this);
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "New Trip", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, notif(Tr.t("Connecting to the car…")));

        store = new SessionStore(this);
        session = store.loadActive();
        // رحلة قديمة لم تُنهَ (السيارة أُطفئت والشاشة نامت أو أُعيد تشغيلها): تُحفظ وتنتهي عند آخر وقت سُجّلت فيه
        if (session != null && System.currentTimeMillis() - store.activeModified() > STALE_MS) {
            endStale(store.activeModified());
        }
        car.setCallback(this);

        thread = new HandlerThread("newtrip-poll");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (session == null) session = store.loadActive();
        // لا رحلة: في النمط اليدوي تتوقف الخدمة، وفي التلقائي تبقى في حالة مراقبة
        if (session == null && Settings.tripMode(this) != Settings.MODE_AUTO) { stopSelf(); return START_NOT_STICKY; }
        if (session == null) updateIdleNotif();
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                car.connect(TripService.this);
                car.poll();
                TripSession s = session;
                long now = SystemClock.elapsedRealtime();
                long wall = System.currentTimeMillis();
                // سبات الشاشة: لو مرّت دقائق بين نبضتين فالرحلة انتهت فعلياً عند آخر نبضة
                if (s != null && wall - lastTickWall > STALE_MS) {
                    long at = lastTickWall;
                    lastTickWall = wall;
                    endStale(at);
                    return;
                }
                lastTickWall = wall;
                if (s == null) { monitorTick(); }
                if (s != null && shouldAutoEnd(now)) { autoEnd(); return; }
                if (s != null) {
                    s.advance(now, car.live);
                    s.sampleLevels(car.live);
                    if (now - lastSave > 15_000) { store.saveActive(s); lastSave = now; }
                    if (now - lastNotif > 5_000) { updateNotif(s); lastNotif = now; }
                }
            } catch (Throwable t) {
                Log.e("TripService", "tick", t);
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** حالة المراقبة (النمط التلقائي، لا رحلة جارية): تبدأ رحلة عندما تكون السيارة مشغّلة وتتحرك. */
    private void monitorTick() {
        if (Settings.tripMode(this) != Settings.MODE_AUTO) { stopSelf(); return; }   // عاد المستخدم للنمط اليدوي
        if (auto.shouldStart(car.live.powerMode, car.live.speedKmh)) beginTrip();
    }

    private synchronized void beginTrip() {
        if (session != null) return;
        android.content.SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        TripSession s = new TripSession();
        s.active = true;
        s.startWall = System.currentTimeMillis();
        s.negIsIn = p.getBoolean(MainActivity.PREF_NEG_IS_IN, true);
        s.manualForced = p.getBoolean(MainActivity.PREF_FORCED, false);
        s.manualTarget = p.getFloat(MainActivity.PREF_TARGET, Float.NaN);
        store.saveActive(s);
        session = s;
        lastSave = 0;
        auto.onTripStarted();
        s.advance(SystemClock.elapsedRealtime(), car.live);
        s.sampleLevels(car.live);
        updateNotif(s);
    }

    /** السيارة مطفأة (FLZCU_9_POWERMODE: 0 = OFF ، 1 = ACC ، 2 = ON ، 3 = START) والسيارة واقفة. */
    private boolean shouldAutoEnd(long now) {
        boolean enabled = Settings.autoEnd(this) || Settings.tripMode(this) == Settings.MODE_AUTO;
        return auto.shouldEnd(car.live.powerMode, car.live.speedKmh, now, enabled);
    }

    private void autoEnd() {
        File f = endTripInternal(0, true);
        notifyEnded(f, "Car switched off");
    }

    private void endStale(long endAt) {
        TripSession s = session;
        File f = endTripInternal(endAt, true);
        if (s != null) notifyEnded(f, "Previous trip");
    }

    private void notifyEnded(File f, String why) {
        if (f == null) return;
        StorageMonitor.ensureChannel(this);
        Intent open = new Intent(this, ReportActivity.class).putExtra(ReportActivity.EXTRA_FILE, f.getAbsolutePath())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        TripSession t = SessionStore.read(f);
        String detail = t == null ? "" : Tr.f("%s km · %s min", String.format(Locale.US, "%.1f", t.totalDistKm()), t.totalDurMs() / 60000);
        getSystemService(NotificationManager.class).notify(StorageMonitor.ENDED_ID,
                new Notification.Builder(this, StorageMonitor.ALERT_CH)
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setContentTitle(Tr.t("Trip saved automatically"))
                        .setContentText(Tr.t(why) + " · " + detail)
                        .setContentIntent(pi).setAutoCancel(true).build());
    }

    // ---------- CarBridge.Callback ----------
    @Override public void beforeChange(long now) {
        TripSession s = session;
        if (s != null) s.advance(now, car.live);
    }

    @Override public void onFuelLitres(double litres) {
        TripSession s = session;
        if (s != null) s.addFuel(litres, car.live);
    }

    // ---------- إنهاء الرحلة ----------
    /** يُستدعى من الواجهة. يعيد ملف التقرير. */
    public File endTrip() { return endTripInternal(0, false); }

    /** endAt = 0 → الآن. carOff = أُنهيت تلقائياً (فتُجرى مهام ما بعد الرحلة بما فيها فحص التحديث). */
    private synchronized File endTripInternal(long endAt, boolean carOff) {
        TripSession s = session;
        if (s == null) return null;
        if (endAt == 0) {
            s.advance(SystemClock.elapsedRealtime(), car.live);
            s.sampleLevels(car.live);
        }
        session = null;
        final boolean autoMode = Settings.tripMode(this) == Settings.MODE_AUTO;
        if (!autoMode) {
            instance = null;        // لتتحول الواجهة فوراً لشاشة البداية
            car.release();
        }
        final File f = endAt == 0 ? store.finish(s) : store.finishAt(s, endAt);
        if (autoMode) {
            // نمط تلقائي: تبقى الخدمة في حالة مراقبة. إنهاء يدوي أثناء القيادة لا يبدأ رحلة جديدة فوراً.
            auto.onTripEnded(carOff);
            updateIdleNotif();
        } else {
            stopForeground(true);
            stopSelf();
        }
        final android.content.Context app = getApplicationContext();
        new Thread(() -> PostTrip.run(app, f, carOff), "newtrip-post").start();
        return f;
    }

    private void updateNotif(TripSession s) {
        long mins = (System.currentTimeMillis() - s.startWall) / 60000;
        String text = String.format(Locale.US, "%.1f km · %d:%02d · %s",
                s.totalDistKm(), mins / 60, mins % 60, car.live.engineOn ? "HEV" : "EV");
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notif(Tr.t("New Trip — in progress"), text));
    }

    private void updateIdleNotif() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID,
                notif(Tr.t("New Trip — automatic mode"), Tr.t("Waiting for the car to move")));
    }

    private Notification notif(String text) { return notif(Tr.t("New Trip"), text); }

    private Notification notif(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        TripSession s = session;
        if (s != null && store != null) store.saveActive(s);
        if (thread != null) thread.quitSafely();
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
