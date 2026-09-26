package net.pictakshay.watchlink;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthPermissions;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord;
import android.health.connect.datatypes.DistanceRecord;
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.StepsRecord;
import android.health.connect.datatypes.units.Energy;
import android.health.connect.datatypes.units.Length;
import android.os.Build;
import android.os.OutcomeReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Copies synced watch data into Health Connect, Android's shared health store, which Google Fit,
 * Samsung Health and other apps read. Uses the Health Connect built into Android 14+ (the old
 * Google Fit APIs are shut down). Records carry a client ID, so writing the same day again
 * updates it instead of adding a duplicate.
 */
final class HealthSync {
    static final String[] PERMISSIONS = {
        HealthPermissions.WRITE_STEPS, HealthPermissions.WRITE_DISTANCE, HealthPermissions.WRITE_ACTIVE_CALORIES_BURNED,
        HealthPermissions.WRITE_HEART_RATE, HealthPermissions.WRITE_SLEEP,
    };
    private static final int DAYS_BACK = 3;

    private HealthSync() { }

    static boolean supported() { return Build.VERSION.SDK_INT >= 34; }

    static boolean granted(Context ctx) {
        if (!supported()) return false;
        for (String p : PERMISSIONS) if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    static boolean enabled(Context ctx) { return prefs(ctx).getBoolean("healthEnabled", false); }
    static void setEnabled(Context ctx, boolean on) { prefs(ctx).edit().putBoolean("healthEnabled", on).apply(); }

    interface Done { void done(String error, int written); }

    /** Writes the last few days of steps/distance/calories/sleep and all new heart-rate readings. */
    @TargetApi(34)
    static void write(Context ctx, JSONObject data, Executor executor, Done done) {
        if (!granted(ctx)) { done.done("Health Connect permission not granted", 0); return; }
        HealthConnectManager hc = ctx.getSystemService(HealthConnectManager.class);
        if (hc == null) { done.done("Health Connect isn't available on this phone", 0); return; }
        ZoneId zone = ZoneId.systemDefault();
        long lastHr = prefs(ctx).getLong("healthLastHr", 0);
        List<Record> records = new ArrayList<>();
        long newestHr = lastHr;

        JSONObject days = data.optJSONObject("days");
        JSONObject latest = data.optJSONObject("latest");
        if (days != null) {
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = days.keys(); it.hasNext(); ) keys.add(it.next());
            Collections.sort(keys);
            for (String key : keys.subList(Math.max(0, keys.size() - DAYS_BACK), keys.size())) {
                JSONObject d = days.optJSONObject(key);
                LocalDate date = LocalDate.parse(key);
                Instant start = date.atStartOfDay(zone).toInstant();
                Instant end = date.equals(LocalDate.now(zone)) && latest != null && latest.optLong("stepsAt") > 0
                    ? Instant.ofEpochMilli(latest.optLong("stepsAt")) : date.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1);
                if (!end.isAfter(start)) continue;
                int steps = d.optInt("steps", -1);
                if (steps > 0) {
                    records.add(new StepsRecord.Builder(meta("steps-" + key, steps), start, end, steps).build());
                    int dist = d.optInt("distance");
                    if (dist > 0) records.add(new DistanceRecord.Builder(meta("distance-" + key, dist), start, end, Length.fromMeters(dist)).build());
                    int kcal = d.optInt("calories");
                    if (kcal > 0) records.add(new ActiveCaloriesBurnedRecord.Builder(meta("calories-" + key, kcal), start, end, Energy.fromCalories(kcal * 1000.0)).build());
                }
                SleepSessionRecord sleep = sleepRecord(key, date, d.optJSONArray("sleep"), zone);
                if (sleep != null) records.add(sleep);
            }
        }
        JSONArray hr = data.optJSONArray("hr");
        if (hr != null) {
            for (int i = 0; i < hr.length(); i++) {
                JSONObject r = hr.optJSONObject(i);
                long t = r == null ? 0 : r.optLong("t");
                if (t <= lastHr) continue;
                Instant at = Instant.ofEpochMilli(t);
                List<HeartRateRecord.HeartRateSample> one = Collections.singletonList(new HeartRateRecord.HeartRateSample(r.optInt("bpm"), at));
                records.add(new HeartRateRecord.Builder(meta("hr-" + t, 1), at, at.plusSeconds(1), one).build());
                newestHr = Math.max(newestHr, t);
            }
        }
        if (records.isEmpty()) { done.done(null, 0); return; }
        final long hrMark = newestHr;
        final int count = records.size();
        hc.insertRecords(records, executor, new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
            @Override public void onResult(InsertRecordsResponse r) {
                prefs(ctx).edit().putLong("healthLastHr", hrMark).putLong("healthWrittenAt", System.currentTimeMillis()).apply();
                done.done(null, count);
            }
            @Override public void onError(HealthConnectException e) { done.done(String.valueOf(e.getMessage()), 0); }
        });
    }

    /**
     * The watch reports sleep as segments {type 0=awake 1=light 2=deep, start "HH:MM"} for the night
     * that ended on {@code date}; times after noon belong to the evening before. The final segment
     * (waking up) marks the end.
     */
    @TargetApi(34)
    private static SleepSessionRecord sleepRecord(String key, LocalDate date, JSONArray segs, ZoneId zone) {
        if (segs == null || segs.length() < 2) return null;
        List<Instant> starts = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        Instant prev = null;
        for (int i = 0; i < segs.length(); i++) {
            JSONObject s = segs.optJSONObject(i);
            LocalTime t;
            try { t = LocalTime.parse(s.optString("start")); } catch (RuntimeException e) { return null; }
            ZonedDateTime when = (t.getHour() >= 12 ? date.minusDays(1) : date).atTime(t).atZone(zone);
            Instant at = when.toInstant();
            if (prev != null && !at.isAfter(prev)) at = prev.plusSeconds(60);   // keep stages in order
            starts.add(at);
            types.add(s.optInt("type"));
            prev = at;
        }
        List<SleepSessionRecord.Stage> stages = new ArrayList<>();
        for (int i = 0; i < starts.size() - 1; i++) {
            int type = types.get(i);
            int stage = type == 2 ? SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP
                : type == 1 ? SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT : SleepSessionRecord.StageType.STAGE_TYPE_AWAKE;
            stages.add(new SleepSessionRecord.Stage(starts.get(i), starts.get(i + 1), stage));
        }
        Instant start = starts.get(0), end = starts.get(starts.size() - 1);
        if (!end.isAfter(start)) return null;
        return new SleepSessionRecord.Builder(meta("sleep-" + key, end.toEpochMilli()), start, end)
            .setTitle("Noise watch").setStages(stages).build();
    }

    @TargetApi(34)
    private static Metadata meta(String id, long version) {
        return new Metadata.Builder().setClientRecordId("watchlink-" + id).setClientRecordVersion(version).build();
    }

    static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("supported", supported()).put("granted", granted(ctx)).put("enabled", enabled(ctx))
                .put("writtenAt", prefs(ctx).getLong("healthWrittenAt", 0)).put("error", prefs(ctx).getString("healthError", ""));
        } catch (org.json.JSONException ignored) { }
        return o;
    }

    static void recordError(Context ctx, String error) { prefs(ctx).edit().putString("healthError", error == null ? "" : error).apply(); }

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences("watchlink", Context.MODE_PRIVATE); }
}
