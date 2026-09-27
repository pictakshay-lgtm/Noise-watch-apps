package net.pictakshay.watchlink;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Asks Google Gemini a question with the user's own API key. The key is typed into the app on
 * the phone, encrypted with a key from the Android Keystore and never given back to the page.
 * Only the question the user typed is sent; nothing runs in the background.
 */
final class GeminiClient {
    interface Done {
        void ok(String text);
        void fail(String message);
    }

    private static final String PREFS = "watchlink";
    private static final String KEY_PREF = "gemini_key";
    private static final String ALIAS = "healthwatcher.gemini";
    private static final String[] MODELS = {"gemini-flash-latest", "gemini-2.5-flash"};
    private static final String INSTRUCTION =
        "You are the assistant inside Health Watcher, a companion app for a smartwatch. "
        + "Reply in plain text, no markdown. First line: a very short answer for the watch screen, "
        + "at most 12 words. Then a blank line, then a fuller answer of at most 120 words.";

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private GeminiClient() { }

    static boolean hasKey(Context c) { return prefs(c).contains(KEY_PREF); }

    static void clearKey(Context c) { prefs(c).edit().remove(KEY_PREF).apply(); }

    static void saveKey(Context c, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret());
        byte[] iv = cipher.getIV();
        byte[] sealed = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(sealed, 0, out, iv.length, sealed.length);
        prefs(c).edit().putString(KEY_PREF, Base64.encodeToString(out, Base64.NO_WRAP)).apply();
    }

    /** Runs the request on a background thread; the answer comes back on the main thread. */
    static void ask(Context c, String question, Done done) {
        Context app = c.getApplicationContext();
        worker.execute(() -> {
            String text = null, error = null;
            try {
                String key = loadKey(app);
                if (key == null) error = "Add your Gemini key first.";
                else {
                    for (int i = 0; i < MODELS.length && text == null; i++) {
                        try { text = request(app, key, MODELS[i], question); }
                        catch (ModelMissing e) { if (i == MODELS.length - 1) error = "Gemini couldn't find a model for this key."; }
                    }
                }
            } catch (Friendly e) {
                error = e.getMessage();
            } catch (IOException e) {
                error = "No internet connection, or Gemini couldn't be reached. Try again.";
            } catch (Exception e) {
                error = "Something went wrong asking Gemini. Try again.";
            }
            String t = text, err = error;
            main.post(() -> { if (t != null) done.ok(t); else done.fail(err); });
        });
    }

    // ---------- internals ----------

    private static final class Friendly extends Exception { Friendly(String m) { super(m); } }
    private static final class ModelMissing extends Exception { }

    private static String request(Context c, String key, String model, String question) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        try {
            http.setRequestMethod("POST");
            http.setConnectTimeout(15000);
            http.setReadTimeout(30000);
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            http.setRequestProperty("x-goog-api-key", key);
            // Lets a key restricted to "Android apps" (package + SHA-1) accept this app.
            http.setRequestProperty("X-Android-Package", c.getPackageName());
            String cert = certSha1(c);
            if (cert != null) http.setRequestProperty("X-Android-Cert", cert);

            JSONObject body = new JSONObject()
                .put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", INSTRUCTION))))
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", question)))))
                .put("generationConfig", new JSONObject().put("maxOutputTokens", 600));
            try (OutputStream out = http.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }

            int code = http.getResponseCode();
            String reply = read(code >= 400 ? http.getErrorStream() : http.getInputStream());
            if (code == 404) throw new ModelMissing();
            if (code == 400 && reply.contains("API_KEY_INVALID")) throw new Friendly("That key didn't work. Check it in Google AI Studio and save it again.");
            if (code == 403) throw new Friendly("This key isn't allowed to use Gemini from Health Watcher. Check the key's restrictions (package and SHA-1) in Google Cloud.");
            if (code == 429) throw new Friendly("Gemini's usage limit was reached. Try again in a minute.");
            if (code >= 400) throw new Friendly("Gemini returned an error (" + code + "). Try again.");

            JSONArray candidates = new JSONObject(reply).optJSONArray("candidates");
            JSONObject content = candidates == null || candidates.length() == 0 ? null : candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            StringBuilder text = new StringBuilder();
            for (int i = 0; parts != null && i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text"));
            if (text.length() == 0) throw new Friendly("Gemini didn't return an answer. Try rephrasing the question.");
            return text.toString().trim();
        } finally {
            http.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String loadKey(Context c) throws Exception {
        String stored = prefs(c).getString(KEY_PREF, null);
        if (stored == null) return null;
        byte[] all = Base64.decode(stored, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, all, 0, 12));
        return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }

    private static SecretKey secret() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return gen.generateKey();
    }

    /** The app's signing certificate SHA-1 as hex, as Google expects in X-Android-Cert. */
    @SuppressWarnings("deprecation")
    private static String certSha1(Context c) {
        try {
            Signature[] sigs;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                sigs = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo.getApkContentsSigners();
            } else {
                sigs = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length == 0) return null;
            byte[] d = MessageDigest.getInstance("SHA-1").digest(sigs[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02X", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
