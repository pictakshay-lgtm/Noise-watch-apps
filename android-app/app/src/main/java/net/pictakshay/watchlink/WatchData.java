package net.pictakshay.watchlink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Everything synced from the watch, kept in one JSON file in the app's private storage:
 *   latest   - the newest steps / distance / calories / heart rate / battery with timestamps
 *   days     - per-day totals ("2026-09-26": {steps, distance, calories}) and sleep segments
 *   hr       - heart-rate readings [{t, bpm}]
 *   battery  - battery readings [{t, level}]
 *   weather  - what was last sent to the watch
 *   lastSync - when the last 15-minute sync finished
 * Lists are capped so the file stays small (about a month of 15-minute readings).
 */
final class WatchData {
    private static final int MAX_SAMPLES = 3000;
    private static final int MAX_DAYS = 60;

    private final File file;
    private JSONObject root;

    WatchData(File file) {
        this.file = file;
        root = load(file);
    }

    static String day(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    synchronized JSONObject json() {
        try { return new JSONObject(root.toString()); } catch (JSONException e) { return new JSONObject(); }
    }

    synchronized void recordSteps(long t, int steps, int distance, int calories) {
        try {
            JSONObject latest = obj(root, "latest");
            latest.put("steps", steps).put("distance", distance).put("calories", calories).put("stepsAt", t);
            recordDaySteps(day(t), steps, distance, calories);
        } catch (JSONException ignored) { }
    }

    synchronized void recordDaySteps(String date, int steps, int distance, int calories) {
        try {
            JSONObject d = obj(obj(root, "days"), date);
            d.put("steps", steps).put("distance", distance).put("calories", calories);
            trimDays();
            save();
        } catch (JSONException ignored) { }
    }

    synchronized void recordHeartRate(long t, int bpm) {
        if (bpm <= 0 || bpm > 250) return;
        try {
            obj(root, "latest").put("hr", bpm).put("hrAt", t);
            append("hr", new JSONObject().put("t", t).put("bpm", bpm));
            save();
        } catch (JSONException ignored) { }
    }

    synchronized void recordBattery(long t, int level) {
        try {
            obj(root, "latest").put("battery", level).put("batteryAt", t);
            append("battery", new JSONObject().put("t", t).put("level", level));
            save();
        } catch (JSONException ignored) { }
    }

    /** Sleep segments as the watch reports them: {type 0=awake 1=light 2=deep, start "HH:MM"}. */
    synchronized void recordSleep(String date, byte[] data) {
        if (data == null || data.length % 3 != 0) return;
        try {
            JSONArray segs = new JSONArray();
            int light = 0, deep = 0;
            for (int i = 0; i < data.length; i += 3) {
                int type = data[i], h = data[i + 1], m = data[i + 2];
                segs.put(new JSONObject().put("type", type).put("start", String.format(Locale.US, "%02d:%02d", h, m)));
                if (i + 3 < data.length) {
                    int mins = ((data[i + 4] * 60 + data[i + 5]) - (h * 60 + m) + 1440) % 1440;
                    if (type == 1) light += mins; else if (type == 2) deep += mins;
                }
            }
            JSONObject d = obj(obj(root, "days"), date);
            d.put("sleep", segs).put("sleepLightMin", light).put("sleepDeepMin", deep);
            save();
        } catch (JSONException ignored) { }
    }

    synchronized void recordWeather(JSONObject w) {
        try { root.put("weather", w); save(); } catch (JSONException ignored) { }
    }

    synchronized void setLastSync(long t, String status) {
        try { root.put("lastSync", t).put("lastSyncStatus", status); save(); } catch (JSONException ignored) { }
    }

    synchronized long weatherSentAt() {
        JSONObject w = root.optJSONObject("weather");
        return w == null ? 0 : w.optLong("sentAt");
    }

    /** A compact, readable digest of the stored data for Claude's system prompt. */
    synchronized String summary(long now) {
        StringBuilder s = new StringBuilder();
        JSONObject latest = root.optJSONObject("latest");
        if (latest != null) {
            s.append("Latest readings:\n");
            if (latest.has("steps")) s.append(String.format(Locale.US, "- Steps today: %d (%d m, %d kcal), read %s\n",
                latest.optInt("steps"), latest.optInt("distance"), latest.optInt("calories"), ago(now, latest.optLong("stepsAt"))));
            if (latest.has("hr")) s.append(String.format(Locale.US, "- Heart rate: %d bpm, measured %s\n", latest.optInt("hr"), ago(now, latest.optLong("hrAt"))));
            if (latest.has("battery")) s.append(String.format(Locale.US, "- Watch battery: %d%%, read %s\n", latest.optInt("battery"), ago(now, latest.optLong("batteryAt"))));
        }
        JSONObject days = root.optJSONObject("days");
        if (days != null && days.length() > 0) {
            s.append("\nDaily totals (most recent last):\n");
            java.util.List<String> keys = sortedKeys(days);
            for (String k : keys.subList(Math.max(0, keys.size() - 14), keys.size())) {
                JSONObject d = days.optJSONObject(k);
                s.append("- ").append(k).append(": ");
                if (d.has("steps")) s.append(d.optInt("steps")).append(" steps, ").append(d.optInt("distance")).append(" m, ").append(d.optInt("calories")).append(" kcal");
                if (d.has("sleepLightMin")) s.append(d.has("steps") ? "; " : "").append("sleep light ").append(d.optInt("sleepLightMin")).append(" min, deep ").append(d.optInt("sleepDeepMin")).append(" min");
                s.append('\n');
            }
        }
        JSONArray hr = root.optJSONArray("hr");
        if (hr != null && hr.length() > 0) {
            s.append("\nRecent heart-rate readings (bpm): ");
            for (int i = Math.max(0, hr.length() - 40); i < hr.length(); i++) {
                JSONObject r = hr.optJSONObject(i);
                s.append(new SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date(r.optLong("t")))).append('=').append(r.optInt("bpm"));
                if (i < hr.length() - 1) s.append(", ");
            }
            s.append('\n');
        }
        JSONObject w = root.optJSONObject("weather");
        if (w != null) s.append("\nWeather sent to the watch: ").append(w.optString("summary")).append('\n');
        if (root.has("lastSync")) s.append("\nLast sync with the watch: ").append(ago(now, root.optLong("lastSync"))).append('\n');
        return s.length() == 0 ? "No watch data has been synced yet." : s.toString();
    }

    // ---------- helpers ----------

    private void append(String key, JSONObject item) throws JSONException {
        JSONArray a = root.optJSONArray(key);
        if (a == null) { a = new JSONArray(); root.put(key, a); }
        a.put(item);
        if (a.length() > MAX_SAMPLES) {
            JSONArray b = new JSONArray();
            for (int i = a.length() - MAX_SAMPLES; i < a.length(); i++) b.put(a.get(i));
            root.put(key, b);
        }
    }

    private void trimDays() {
        JSONObject days = root.optJSONObject("days");
        if (days == null || days.length() <= MAX_DAYS) return;
        java.util.List<String> keys = sortedKeys(days);
        for (int i = 0; i < keys.size() - MAX_DAYS; i++) days.remove(keys.get(i));
    }

    private static java.util.List<String> sortedKeys(JSONObject o) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys);
        return keys;
    }

    private static JSONObject obj(JSONObject parent, String key) throws JSONException {
        JSONObject o = parent.optJSONObject(key);
        if (o == null) { o = new JSONObject(); parent.put(key, o); }
        return o;
    }

    private static String ago(long now, long t) {
        if (t <= 0) return "unknown";
        long m = Math.max(0, (now - t) / 60000);
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return (m / 60) + " h ago";
        return (m / 1440) + " days ago";
    }

    private static JSONObject load(File f) {
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            return new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private void save() {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }
}
