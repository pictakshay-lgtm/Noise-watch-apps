package net.pictakshay.watchlink;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Weather for the watch from Open-Meteo (free, no API key). The place is either a city the
 * user typed, or the phone's last known location, saved while the app is open, because
 * Android doesn't hand out location to apps in the background. Blocking; call off the main thread.
 */
final class Weather {
    static final class Report {
        int condition;          // WatchProtocol.WEATHER_*
        int temp;               // °C now
        int[][] days;           // 7 x {condition, min, max}
        String city;
        String summary;
    }

    private Weather() { }

    /** Remembers the phone's last known location, if the app may use it. Call while the app is visible. */
    @SuppressLint("MissingPermission")
    static void rememberLocation(Context ctx) {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        if (lm == null) return;
        Location best = null;
        for (String p : lm.getProviders(true)) {
            Location l = lm.getLastKnownLocation(p);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
        }
        if (best == null) return;
        SharedPreferences.Editor e = prefs(ctx).edit()
            .putString("weatherLat", String.valueOf(best.getLatitude()))
            .putString("weatherLon", String.valueOf(best.getLongitude()));
        if (!prefs(ctx).getBoolean("weatherCityManual", false)) e.remove("weatherCity");
        e.apply();
    }

    /** Looks up a city by name and uses it for the weather from now on. Returns its display name. */
    static String setCity(Context ctx, String name) throws IOException, JSONException {
        JSONObject r = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(name, "UTF-8"));
        JSONArray results = r.optJSONArray("results");
        if (results == null || results.length() == 0) throw new IOException("No place called \"" + name + "\" found");
        JSONObject c = results.getJSONObject(0);
        prefs(ctx).edit()
            .putString("weatherLat", String.valueOf(c.getDouble("latitude")))
            .putString("weatherLon", String.valueOf(c.getDouble("longitude")))
            .putString("weatherCity", c.getString("name"))
            .putBoolean("weatherCityManual", true)
            .apply();
        return c.getString("name") + (c.has("country") ? ", " + c.getString("country") : "");
    }

    static void useDeviceLocation(Context ctx) {
        prefs(ctx).edit().putBoolean("weatherCityManual", false).remove("weatherCity").apply();
        rememberLocation(ctx);
    }

    static boolean hasPlace(Context ctx) { return prefs(ctx).contains("weatherLat"); }

    static Report fetch(Context ctx) throws IOException, JSONException {
        SharedPreferences p = prefs(ctx);
        String lat = p.getString("weatherLat", null), lon = p.getString("weatherLon", null);
        if (lat == null || lon == null) throw new IOException("No location yet: open the app with location allowed, or type a city");
        JSONObject j = get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
            + "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min"
            + "&timezone=auto&forecast_days=7");
        Report r = new Report();
        JSONObject cur = j.getJSONObject("current");
        int code = cur.getInt("weather_code");
        r.condition = WatchProtocol.conditionFromWmo(code);
        r.temp = (int) Math.round(cur.getDouble("temperature_2m"));
        JSONObject daily = j.getJSONObject("daily");
        JSONArray codes = daily.getJSONArray("weather_code"), max = daily.getJSONArray("temperature_2m_max"), min = daily.getJSONArray("temperature_2m_min");
        r.days = new int[Math.min(7, codes.length())][];
        for (int i = 0; i < r.days.length; i++) {
            r.days[i] = new int[] { WatchProtocol.conditionFromWmo(codes.getInt(i)),
                (int) Math.round(min.getDouble(i)), (int) Math.round(max.getDouble(i)) };
        }
        r.city = p.getString("weatherCity", "");
        r.summary = String.format(Locale.US, "%s%d°C, %s; today %d–%d°C", r.city.isEmpty() ? "" : r.city + ": ",
            r.temp, describe(code), r.days.length > 0 ? r.days[0][1] : r.temp, r.days.length > 0 ? r.days[0][2] : r.temp);
        return r;
    }

    static String describe(int wmo) {
        if (wmo == 0) return "clear";
        if (wmo <= 2) return "partly cloudy";
        if (wmo == 3) return "overcast";
        if (wmo == 45 || wmo == 48) return "fog";
        if (wmo >= 51 && wmo <= 57) return "drizzle";
        if ((wmo >= 61 && wmo <= 67) || (wmo >= 80 && wmo <= 82)) return "rain";
        if ((wmo >= 71 && wmo <= 77) || wmo == 85 || wmo == 86) return "snow";
        if (wmo >= 95) return "thunderstorm";
        return "haze";
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            if (c.getResponseCode() >= 400) throw new IOException("Weather service returned HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences("watchlink", Context.MODE_PRIVATE); }
}
