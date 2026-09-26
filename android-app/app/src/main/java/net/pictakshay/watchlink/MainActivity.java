package net.pictakshay.watchlink;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the Watch Link page (bundled in the app) in a WebView. navigator.bluetooth is replaced
 * by a small script (assets/app/ble-polyfill.js) that talks to {@link BleService} through
 * {@link BleBridge}, so the page works unchanged but the connection lives in the service.
 */
public class MainActivity extends Activity {
    static final String ORIGIN = "https://appassets.androidplatform.net";
    static final String START_PAGE = ORIGIN + "/assets/watch-link/app/index.html";
    private static final int PERMISSION_REQUEST = 1;
    private static final int LOCATION_REQUEST = 2;

    interface Granted { void done(boolean granted); }
    private Granted locationCallback;

    private WebView web;
    private BleBridge bridge;
    private BleService service;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((BleService.LocalBinder) binder).get();
            bridge.attach(service);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            bridge.attach(null);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // the page's localStorage memory
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        bridge = new BleBridge(this, web);
        web.addJavascriptInterface(bridge, "NativeBle");
        web.setWebChromeClient(new WebChromeClient() {
            // The page asks "Upload … to the watch?" with confirm(); show it as a normal Android dialog.
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                input.setText(defaultValue);
                input.setSelectAllOnFocus(true);
                if (defaultValue != null && defaultValue.matches("\\d+")) input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                android.widget.FrameLayout box = new android.widget.FrameLayout(MainActivity.this);
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                box.setPadding(pad, pad / 2, pad, 0);
                box.addView(input);
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(message)
                    .setView(box)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
                return true;
            }
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.confirm())
                    .show();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                // Add the Bluetooth polyfill before the page's own scripts run.
                String path = url.getPath() == null ? "" : url.getPath();
                if (path.startsWith("/assets/watch-link/") && (path.endsWith("/") || path.endsWith(".html"))) {
                    WebResourceResponse page = injectPolyfill(path.substring("/assets/".length()) + (path.endsWith("/") ? "index.html" : ""));
                    if (page != null) return page;
                }
                return loader.shouldInterceptRequest(url);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url.toString().startsWith(ORIGIN)) return false;
                // Links such as "Open Wrist Log" open in the normal browser.
                startActivity(new Intent(Intent.ACTION_VIEW, url));
                return true;
            }
        });

        requestBluetoothPermissions();
        bindService(new Intent(this, BleService.class), connection, Context.BIND_AUTO_CREATE);
        boolean privacy = getIntent() != null && "android.intent.action.VIEW_PERMISSION_USAGE".equals(getIntent().getAction());
        web.loadUrl(privacy ? ORIGIN + "/assets/watch-link/app/privacy.html" : START_PAGE);
    }

    @Override protected void onResume() {
        super.onResume();
        Weather.rememberLocation(this);   // only possible while the app is on screen
    }

    /** Asks for approximate location (for local weather on the watch). */
    void requestLocation(Granted callback) {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            callback.done(true);
            return;
        }
        locationCallback = callback;
        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
    }

    @Override protected void onDestroy() {
        // The service keeps the watch connected; only the UI goes away.
        if (service != null) service.setListener(null);
        unbindService(connection);
        web.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private WebResourceResponse injectPolyfill(String asset) {
        try {
            String html = readAsset(asset);
            String tag = "<script src=\"/assets/app/ble-polyfill.js\"></script>";
            int head = html.indexOf("<head>");
            html = head >= 0 ? html.substring(0, head + 6) + tag + html.substring(head + 6) : tag + html;
            return new WebResourceResponse("text/html", "utf-8",
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return null;
        }
    }

    private String readAsset(String path) throws IOException {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    // ---------- permissions ----------

    static String[] neededPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    boolean hasBluetoothPermissions() {
        String[] needed = Build.VERSION.SDK_INT >= 31
            ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : needed) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void requestBluetoothPermissions() {
        if (hasBluetoothPermissions()) { startWatchService(); return; }
        requestPermissions(neededPermissions(), PERMISSION_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == PERMISSION_REQUEST && hasBluetoothPermissions()) startWatchService();
        if (code == LOCATION_REQUEST && locationCallback != null) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) Weather.rememberLocation(this);
            locationCallback.done(granted);
            locationCallback = null;
        }
    }

    /** Starts the service in the foreground; it reconnects to the last watch on its own. */
    private void startWatchService() {
        startForegroundService(new Intent(this, BleService.class));
    }
}
