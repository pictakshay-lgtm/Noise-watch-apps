package net.pictakshay.watchlink;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the phone in sync with the watch, natively, so it works with the app closed:
 *  - every 15 minutes: battery, today's steps / distance / calories, a heart-rate measurement,
 *    last night's sleep and the two previous days' steps and sleep;
 *  - every 4 hours (and whenever the watch asks): today's weather and a 7-day forecast;
 *  - on every connection: the time;
 *  - always: the watch's music buttons control the phone's music, and optionally the current
 *    song is shown on the watch.
 * Everything lands in {@link WatchData}; the page is told with an "app" event so it can refresh.
 * All methods run on the main thread.
 */
final class WatchSync {
    static final long SYNC_INTERVAL_MS = 15 * 60_000L;
    static final long WEATHER_INTERVAL_MS = 4 * 3600_000L;
    static final String ACTION_SYNC = "net.pictakshay.watchlink.SYNC";
    private static final long HR_TIMEOUT_MS = 60_000L;

    private final BleService svc;
    private final WatchData data;
    private final MusicControl music;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final WatchProtocol.Reassembler reassembler = new WatchProtocol.Reassembler();
    private PowerManager.WakeLock wakeLock;
    private boolean measuringHr;
    private boolean uploading;
    private String status = "waiting for the watch";

    private final Runnable stopHr = () -> { if (measuringHr) { measuringHr = false; send(WatchProtocol.heartRate(false)); } };

    WatchSync(BleService svc) {
        this.svc = svc;
        this.data = new WatchData(new java.io.File(svc.getFilesDir(), "watchdata.json"));
        this.music = new MusicControl(svc);
    }

    WatchData data() { return data; }
    MusicControl music() { return music; }
    String status() { return status; }

    /** The page uploads watch faces over the same link; stay quiet meanwhile. */
    void setUploading(boolean u) { uploading = u; }

    // ---------- connection lifecycle ----------

    void onReady() {
        svc.setNotify(WatchProtocol.SERVICE, WatchProtocol.DATA_IN, true, BleService.IGNORE);
        svc.setNotify(WatchProtocol.SERVICE, WatchProtocol.STEPS_CHAR, true, BleService.IGNORE);
        send(WatchProtocol.syncTime(System.currentTimeMillis(), TimeZone.getDefault()));
        music.watchSongs(this::onSong);
        scheduleNext();
        main.postDelayed(() -> sync("connected"), 3000);
    }

    void onDisconnected() {
        main.removeCallbacks(stopHr);
        measuringHr = false;
        status = "watch disconnected";
        releaseWakeLock();
    }

    void shutdown() {
        cancelAlarm();
        music.stop();
        net.shutdownNow();
        releaseWakeLock();
    }

    /** Called by the 15-minute alarm. */
    void onAlarm() {
        scheduleNext();
        sync("scheduled");
    }

    // ---------- the sync itself ----------

    void sync(String reason) {
        if (!svc.isReady()) { status = "not connected at last sync attempt"; publish(); return; }
        if (uploading) { status = "skipped (watch face upload in progress)"; publish(); return; }
        acquireWakeLock();
        status = "syncing (" + reason + ")";
        publish();
        svc.read(WatchProtocol.BATTERY_SERVICE, WatchProtocol.BATTERY_LEVEL, new BleService.Result() {
            @Override public void ok(Object v) {
                byte[] b = decode(v);
                if (b.length > 0) data.recordBattery(System.currentTimeMillis(), b[0] & 0xFF);
                publish();
            }
            @Override public void fail(String n, String m) { }
        });
        svc.read(WatchProtocol.SERVICE, WatchProtocol.STEPS_CHAR, new BleService.Result() {
            @Override public void ok(Object v) { onSteps(decode(v)); }
            @Override public void fail(String n, String m) { }
        });
        send(WatchProtocol.packet(WatchProtocol.CMD_SYNC_SLEEP, new byte[0]));
        send(WatchProtocol.packet(WatchProtocol.CMD_SYNC_PAST_SLEEP_AND_STEP, new byte[] { WatchProtocol.PAST_SLEEP_1_DAY_AGO }));
        send(WatchProtocol.packet(WatchProtocol.CMD_SYNC_PAST_SLEEP_AND_STEP, new byte[] { WatchProtocol.PAST_STEPS_1_DAY_AGO }));
        send(WatchProtocol.packet(WatchProtocol.CMD_SYNC_PAST_SLEEP_AND_STEP, new byte[] { WatchProtocol.PAST_STEPS_2_DAYS_AGO }));
        // A heart-rate measurement takes the watch ~30 s; the answer arrives as a notification.
        measuringHr = true;
        send(WatchProtocol.heartRate(true));
        main.removeCallbacks(stopHr);
        main.postDelayed(stopHr, HR_TIMEOUT_MS);
        main.postDelayed(() -> {
            data.setLastSync(System.currentTimeMillis(), "ok");
            status = "synced";
            publish();
            releaseWakeLock();
        }, HR_TIMEOUT_MS + 2000);

        if (System.currentTimeMillis() - data.weatherSentAt() > WEATHER_INTERVAL_MS - 60_000L) sendWeather(null);
    }

    void findWatch() { send(WatchProtocol.packet(WatchProtocol.CMD_FIND_MY_WATCH, new byte[0])); }

    /** Fetches the weather and sends it to the watch. result may be null. */
    void sendWeather(BleService.Result result) {
        net.execute(() -> {
            try {
                Weather.Report r = Weather.fetch(svc);
                main.post(() -> {
                    if (!svc.isReady()) { if (result != null) result.fail("NetworkError", "The watch isn't connected"); return; }
                    send(WatchProtocol.weatherToday(r.condition, r.temp, r.city));
                    send(WatchProtocol.weatherForecast(r.days));
                    try {
                        data.recordWeather(new JSONObject().put("sentAt", System.currentTimeMillis()).put("summary", r.summary)
                            .put("temp", r.temp).put("city", r.city));
                    } catch (JSONException ignored) { }
                    publish();
                    if (result != null) result.ok(r.summary);
                });
            } catch (Exception e) {
                main.post(() -> {
                    try { data.recordWeather(new JSONObject().put("sentAt", data.weatherSentAt()).put("error", String.valueOf(e.getMessage()))); }
                    catch (JSONException ignored) { }
                    if (result != null) result.fail("NetworkError", String.valueOf(e.getMessage()));
                });
            }
        });
    }

    // ---------- from the watch ----------

    void onNotify(String characteristic, byte[] value) {
        if (WatchProtocol.STEPS_CHAR.equals(characteristic)) { onSteps(value); return; }
        if (!WatchProtocol.DATA_IN.equals(characteristic)) return;
        WatchProtocol.Packet p = reassembler.add(value);
        if (p == null) return;
        long now = System.currentTimeMillis();
        switch (p.command) {
            case WatchProtocol.CMD_NOTIFY_PHONE_OPERATION:
                if (p.payload.length > 0 && p.payload[0] != WatchProtocol.OP_REJECT_CALL) music.press(p.payload[0]);
                break;
            case WatchProtocol.CMD_TRIGGER_MEASURE_HEARTRATE:
                if (p.payload.length > 0 && (p.payload[0] & 0xFF) > 0) {
                    data.recordHeartRate(now, p.payload[0] & 0xFF);
                    stopHr.run();
                    main.removeCallbacks(stopHr);
                    publish();
                }
                break;
            case WatchProtocol.CMD_SYNC_SLEEP:
                data.recordSleep(WatchData.day(now), p.payload);
                break;
            case WatchProtocol.CMD_SYNC_PAST_SLEEP_AND_STEP:
                onPast(now, p.payload);
                break;
            case WatchProtocol.CMD_NOTIFY_WEATHER_CHANGE:
                sendWeather(null);
                break;
            default:
                break;
        }
    }

    private void onPast(long now, byte[] payload) {
        if (payload.length < 1) return;
        byte[] rest = new byte[payload.length - 1];
        System.arraycopy(payload, 1, rest, 0, rest.length);
        long dayMs = 24 * 3600_000L;
        switch (payload[0]) {
            case WatchProtocol.PAST_STEPS_1_DAY_AGO:
            case WatchProtocol.PAST_STEPS_2_DAYS_AGO: {
                int[] s = WatchProtocol.steps(rest);
                int ago = payload[0] == WatchProtocol.PAST_STEPS_1_DAY_AGO ? 1 : 2;
                if (s != null) data.recordDaySteps(WatchData.day(now - ago * dayMs), s[0], s[1], s[2]);
                break;
            }
            case WatchProtocol.PAST_SLEEP_1_DAY_AGO:
            case WatchProtocol.PAST_SLEEP_2_DAYS_AGO: {
                int ago = payload[0] == WatchProtocol.PAST_SLEEP_1_DAY_AGO ? 1 : 2;
                data.recordSleep(WatchData.day(now - ago * dayMs), rest);
                break;
            }
            default:
                break;
        }
    }

    private void onSteps(byte[] value) {
        int[] s = WatchProtocol.steps(value);
        if (s == null) return;
        data.recordSteps(System.currentTimeMillis(), s[0], s[1], s[2]);
        publish();
    }

    private void onSong(String app, String title, String artist, boolean playing) {
        try {
            svc.emitApp("song", new JSONObject().put("app", app).put("title", title).put("artist", artist).put("playing", playing));
        } catch (JSONException ignored) { }
        if (playing && songOnWatch() && svc.isReady() && !uploading) {
            send(WatchProtocol.message(WatchProtocol.MESSAGE_OTHER, app, artist.isEmpty() ? title : title + " - " + artist));
        }
    }

    boolean songOnWatch() { return prefs().getBoolean("songOnWatch", false); }
    void setSongOnWatch(boolean on) {
        prefs().edit().putBoolean("songOnWatch", on).apply();
        music.watchSongs(this::onSong);
    }

    // ---------- helpers ----------

    private void send(byte[] packet) { svc.writePacket(WatchProtocol.SERVICE, WatchProtocol.DATA_OUT, packet); }

    private void publish() {
        try { svc.emitApp("data", new JSONObject().put("status", status)); } catch (JSONException ignored) { }
    }

    private static byte[] decode(Object v) {
        return v instanceof String ? android.util.Base64.decode((String) v, android.util.Base64.NO_WRAP) : new byte[0];
    }

    private void scheduleNext() {
        AlarmManager am = svc.getSystemService(AlarmManager.class);
        // Inexact, but allowed to fire in Doze; Android may stretch it a little to save battery.
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + SYNC_INTERVAL_MS, alarmIntent());
    }

    private void cancelAlarm() { svc.getSystemService(AlarmManager.class).cancel(alarmIntent()); }

    private PendingIntent alarmIntent() {
        return PendingIntent.getBroadcast(svc, 0, new Intent(svc, SyncReceiver.class).setAction(ACTION_SYNC),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = svc.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchLink:sync");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(HR_TIMEOUT_MS + 10_000L);
    }

    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }

    private SharedPreferences prefs() { return svc.getSharedPreferences("watchlink", Context.MODE_PRIVATE); }
}
