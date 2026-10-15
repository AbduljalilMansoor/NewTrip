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
 * خدمة أمامية تعمل فقط أثناء الرحلة (من Start إلى End).
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
    private long offSince, lastTickWall = System.currentTimeMillis();

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, TripService.class));
    }

    public Live live() { return car.live; }
    public TripSession session() { return session; }

    @Override
    public void onCreate() {
        super.onCreate();
        CarBridge.loadCalibration(this);
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "New Trip", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, notif("جاري الاتصال بالسيارة…"));

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
        if (session == null) { stopSelf(); return START_NOT_STICKY; }
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

    /** السيارة مطفأة (FLZCU_9_POWERMODE: 0 = OFF ، 1 = ACC ، 2 = ON ، 3 = START) والسيارة واقفة. */
    private boolean shouldAutoEnd(long now) {
        if (!Settings.autoEnd(this)) { offSince = 0; return false; }
        Integer pw = car.live.powerMode;
        Float v = car.live.speedKmh;
        if (pw != null && pw < 2 && (v == null || v < 3f)) {
            if (offSince == 0) offSince = now;
            long need = pw == 0 ? 10_000L : 90_000L;     // OFF: 10 ثوانٍ ، ACC: 90 ثانية
            return now - offSince >= need;
        }
        offSince = 0;
        return false;
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
        String detail = t == null ? "" : String.format(Locale.US, "%.1f km · %d min", t.totalDistKm(), t.totalDurMs() / 60000);
        getSystemService(NotificationManager.class).notify(StorageMonitor.ENDED_ID,
                new Notification.Builder(this, StorageMonitor.ALERT_CH)
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setContentTitle("Trip saved automatically")
                        .setContentText(why + " · " + detail)
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
        instance = null;            // لتتحول الواجهة فوراً لشاشة البداية
        car.release();
        final File f = endAt == 0 ? store.finish(s) : store.finishAt(s, endAt);
        stopForeground(true);
        stopSelf();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> PostTrip.run(app, f, carOff), "newtrip-post").start();
        return f;
    }

    private void updateNotif(TripSession s) {
        long mins = (System.currentTimeMillis() - s.startWall) / 60000;
        String text = String.format(Locale.US, "%.1f كم · %d:%02d · %s",
                s.totalDistKm(), mins / 60, mins % 60, car.live.engineOn ? "محرك" : "كهرباء");
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notif(text));
    }

    private Notification notif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("New Trip — جارية")
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
