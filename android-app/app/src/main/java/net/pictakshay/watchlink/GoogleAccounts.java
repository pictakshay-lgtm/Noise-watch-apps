package net.pictakshay.watchlink;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google accounts in Watch Link: sign in with one or more Google accounts, show the user's name
 * and photo, and back up the synced watch data to each account's private Drive app folder
 * (a hidden folder only this app can see; it doesn't touch the user's files).
 *
 * Uses Google Identity Services' authorization API. The app is identified by its package name and
 * signing certificate, which must be registered as an Android OAuth client in Google Cloud; no
 * client ID is embedded in the app.
 */
final class GoogleAccounts {
    interface Callback { void done(String error, JSONObject result); }

    static final int REQUEST_CHOOSE_ACCOUNT = 10;
    static final int REQUEST_AUTHORIZE = 11;
    static final long BACKUP_INTERVAL_MS = 6 * 3600_000L;
    private static final List<Scope> SCOPES = Arrays.asList(
        new Scope("https://www.googleapis.com/auth/drive.appdata"), new Scope("email"), new Scope("profile"));
    private static final String FILE_NAME = "watchdata.json";
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();

    private static Callback pending;          // the page's addAccount call, waiting on activity results
    private static String pendingEmail;

    private GoogleAccounts() { }

    // ---------- accounts ----------

    static JSONArray list(Context ctx) {
        try { return new JSONArray(prefs(ctx).getString("googleAccounts", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    static void remove(Context ctx, String email) {
        JSONArray a = list(ctx), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!email.equals(a.optJSONObject(i).optString("email"))) out.put(a.optJSONObject(i));
        prefs(ctx).edit().putString("googleAccounts", out.toString()).apply();
    }

    /** Step 1: let the user pick (or add) a Google account on the phone. */
    static void add(Activity activity, Callback cb) {
        pending = cb;
        Intent chooser = AccountManager.newChooseAccountIntent(null, null, new String[]{"com.google"}, null, null, null, null);
        activity.startActivityForResult(chooser, REQUEST_CHOOSE_ACCOUNT);
    }

    /** Activity results for {@link #add}. Returns true if it was ours. */
    static boolean onActivityResult(Activity activity, int request, int resultCode, Intent data) {
        if (request == REQUEST_CHOOSE_ACCOUNT) {
            String email = data == null ? null : data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (resultCode != Activity.RESULT_OK || email == null) { finish("cancelled", null); return true; }
            pendingEmail = email;
            authorize(activity, email);
            return true;
        }
        if (request == REQUEST_AUTHORIZE) {
            if (resultCode != Activity.RESULT_OK) { finish("cancelled", null); return true; }
            try {
                AuthorizationResult r = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data);
                completeSignIn(activity, pendingEmail, r.getAccessToken());
            } catch (ApiException e) {
                finish(describe(e), null);
            }
            return true;
        }
        return false;
    }

    /** Step 2: ask Google for Drive-appdata + profile access for that account (shows consent once). */
    private static void authorize(Activity activity, String email) {
        Identity.getAuthorizationClient(activity).authorize(request(email))
            .addOnSuccessListener(r -> {
                if (r.hasResolution() && r.getPendingIntent() != null) {
                    try {
                        activity.startIntentSenderForResult(r.getPendingIntent().getIntentSender(), REQUEST_AUTHORIZE, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        finish("Couldn't open Google sign-in: " + e.getMessage(), null);
                    }
                } else {
                    completeSignIn(activity, email, r.getAccessToken());
                }
            })
            .addOnFailureListener(e -> finish(describe(e), null));
    }

    /** Step 3: fetch the profile, save the account, and make a first backup. */
    private static void completeSignIn(Context ctx, String email, String token) {
        if (token == null) { finish("Google didn't return an access token", null); return; }
        NET.execute(() -> {
            try {
                JSONObject me = httpJson("GET", "https://www.googleapis.com/oauth2/v3/userinfo", token, null, null);
                JSONObject acct = new JSONObject()
                    .put("email", me.optString("email", email))
                    .put("name", me.optString("name"))
                    .put("givenName", me.optString("given_name"))
                    .put("picture", me.optString("picture"))
                    .put("addedAt", System.currentTimeMillis());
                synchronized (GoogleAccounts.class) {
                    remove(ctx, acct.getString("email"));
                    JSONArray all = list(ctx);
                    all.put(acct);
                    prefs(ctx).edit().putString("googleAccounts", all.toString()).apply();
                }
                backupWithToken(ctx, acct.getString("email"), token);
                finish(null, acct);
            } catch (IOException | JSONException e) {
                finish("Signed in, but couldn't read your Google profile: " + e.getMessage(), null);
            }
        });
    }

    private static void finish(String error, JSONObject result) {
        Callback cb = pending;
        pending = null;
        if (cb != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.done(error, result));
    }

    // ---------- Drive backup ----------

    /** Backs up to every signed-in account whose last backup is older than 6 hours (or all, if forced). */
    static void backupAll(Context ctx, boolean force, Callback cb) {
        JSONArray accounts = list(ctx);
        if (accounts.length() == 0) { if (cb != null) cb.done("No Google account signed in", null); return; }
        for (int i = 0; i < accounts.length(); i++) {
            String email = accounts.optJSONObject(i).optString("email");
            if (!force && System.currentTimeMillis() - prefs(ctx).getLong("backupAt:" + email, 0) < BACKUP_INTERVAL_MS) continue;
            withToken(ctx, email, (err, tok) -> {
                if (err != null) { recordBackup(ctx, email, err); if (cb != null) cb.done(email + ": " + err, null); return; }
                NET.execute(() -> {
                    String e = null;
                    try { backupWithToken(ctx, email, tok.optString("token")); } catch (IOException | JSONException ex) { e = ex.getMessage(); }
                    String fe = e;
                    if (cb != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.done(fe == null ? null : email + ": " + fe, null));
                });
            });
        }
    }

    /** Replaces the phone's watch data with the Drive backup from {@code email}. */
    static void restore(Context ctx, String email, WatchData into, Callback cb) {
        withToken(ctx, email, (err, tok) -> {
            if (err != null) { cb.done(err, null); return; }
            NET.execute(() -> {
                try {
                    String id = findBackup(tok.optString("token"));
                    if (id == null) throw new IOException("No backup found in this account yet");
                    String body = httpText("GET", "https://www.googleapis.com/drive/v3/files/" + id + "?alt=media", tok.optString("token"), null, null);
                    into.replace(new JSONObject(body));
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.done(null, null));
                } catch (IOException | JSONException e) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.done(e.getMessage(), null));
                }
            });
        });
    }

    private static void backupWithToken(Context ctx, String email, String token) throws IOException, JSONException {
        BleService s = BleService.instance;
        String json = s != null ? s.sync().data().json().toString() : "{}";
        String id = findBackup(token);
        if (id != null) {
            httpText("PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + id + "?uploadType=media", token, "application/json", json.getBytes(StandardCharsets.UTF_8));
        } else {
            String boundary = "watchlink" + System.nanoTime();
            String body = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":\"" + FILE_NAME + "\",\"parents\":[\"appDataFolder\"]}\r\n"
                + "--" + boundary + "\r\nContent-Type: application/json\r\n\r\n" + json + "\r\n--" + boundary + "--";
            httpText("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", token,
                "multipart/related; boundary=" + boundary, body.getBytes(StandardCharsets.UTF_8));
        }
        recordBackup(ctx, email, null);
    }

    private static String findBackup(String token) throws IOException, JSONException {
        String q = URLEncoder.encode("name='" + FILE_NAME + "'", "UTF-8");
        JSONObject r = httpJson("GET", "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id)&q=" + q, token, null, null);
        JSONArray files = r.optJSONArray("files");
        return files != null && files.length() > 0 ? files.getJSONObject(0).getString("id") : null;
    }

    private static void recordBackup(Context ctx, String email, String error) {
        SharedPreferences.Editor e = prefs(ctx).edit().putString("backupError:" + email, error == null ? "" : error);
        if (error == null) e.putLong("backupAt:" + email, System.currentTimeMillis());
        e.apply();
    }

    /** Accounts plus their backup status, for the page. */
    static JSONArray describeAccounts(Context ctx) {
        JSONArray a = list(ctx);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String email = o.optString("email");
            try {
                o.put("backupAt", prefs(ctx).getLong("backupAt:" + email, 0)).put("backupError", prefs(ctx).getString("backupError:" + email, ""));
            } catch (JSONException ignored) { }
        }
        return a;
    }

    // ---------- tokens and HTTP ----------

    /** A fresh access token for an account that already granted access, without any UI. */
    private static void withToken(Context ctx, String email, Callback cb) {
        Identity.getAuthorizationClient(ctx).authorize(request(email))
            .addOnSuccessListener(r -> {
                if (r.hasResolution() || r.getAccessToken() == null) cb.done("Sign in to " + email + " again (Watch tab)", null);
                else {
                    try { cb.done(null, new JSONObject().put("token", r.getAccessToken())); } catch (JSONException ignored) { }
                }
            })
            .addOnFailureListener(e -> cb.done(describe(e), null));
    }

    private static AuthorizationRequest request(String email) {
        AuthorizationRequest.Builder b = AuthorizationRequest.builder().setRequestedScopes(SCOPES);
        if (email != null) b.setAccount(new Account(email, "com.google"));
        return b.build();
    }

    private static String describe(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof ApiException && ((ApiException) e).getStatusCode() == 10) {
            return "Google sign-in isn't set up for this app yet (developer error 10: register the Android OAuth client)";
        }
        return m;
    }

    private static JSONObject httpJson(String method, String url, String token, String type, byte[] body) throws IOException, JSONException {
        return new JSONObject(httpText(method, url, token, type, body));
    }

    private static String httpText(String method, String url, String token, String type, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if ("PATCH".equals(method)) {   // HttpURLConnection has no PATCH; Google APIs accept this override
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            c.setRequestMethod(method);
        }
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
        }
        int code = c.getResponseCode();
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) { byte[] buf = new byte[8192]; for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n); }
            String text = out.toString(StandardCharsets.UTF_8.name());
            if (code >= 400) throw new IOException("Google returned HTTP " + code + (code == 403 ? " (is the Drive API enabled?)" : ""));
            return text;
        } finally {
            c.disconnect();
        }
    }

    static boolean signInSkipped(Context ctx) { return prefs(ctx).getBoolean("signInSkipped", false); }
    static void skipSignIn(Context ctx) { prefs(ctx).edit().putBoolean("signInSkipped", true).apply(); }

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences("watchlink", Context.MODE_PRIVATE); }
}
