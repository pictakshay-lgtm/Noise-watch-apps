package net.pictakshay.watchlink;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.ArrayAdapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The JavaScript side (ble-polyfill.js) calls {@link #call} with a method name, JSON arguments
 * and a callback id; the answer goes back through window.__nativeBle.resolve/reject.
 * Watch events (notifications, disconnects) go to window.__nativeBle.event.
 */
@SuppressLint("MissingPermission")
public class BleBridge {
    private final MainActivity activity;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> waitingForService = new ArrayList<>();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private BleService service;

    /** Methods for the app's own panels (sync, weather, music, Ask Claude); they don't need Bluetooth permission. */
    private static final List<String> APP_METHODS = Arrays.asList("appData", "music", "openMusicApp", "openClaude",
        "weatherCity", "weatherUseLocation", "setUploading");

    BleBridge(MainActivity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    void attach(BleService s) {
        service = s;
        if (s == null) return;
        s.setListener(event -> js("window.__nativeBle && window.__nativeBle.event(" + JSONObject.quote(event.toString()) + ")"));
        for (Runnable r : waitingForService) r.run();
        waitingForService.clear();
    }

    @JavascriptInterface
    public boolean isConnected(String id) {
        BleService s = service;
        return s != null && s.isConnected(id);
    }

    @JavascriptInterface
    public void call(String method, String argsJson, String callbackId) {
        main.post(() -> {
            if (service == null) { waitingForService.add(() -> dispatch(method, argsJson, callbackId)); return; }
            dispatch(method, argsJson, callbackId);
        });
    }

    private void dispatch(String method, String argsJson, String cb) {
        BleService.Result result = new BleService.Result() {
            @Override public void ok(Object value) { resolve(cb, value); }
            @Override public void fail(String name, String message) { reject(cb, name, message); }
        };
        try {
            JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
            if (APP_METHODS.contains(method)) { appMethod(method, a, result); return; }
            if (!activity.hasBluetoothPermissions() && !method.equals("availability")) {
                result.fail("SecurityError", "Allow Watch Link to use Bluetooth (Nearby devices) in Android settings.");
                return;
            }
            switch (method) {
                case "availability": result.ok(activity.hasBluetoothPermissions() && service.isAvailable()); break;
                case "getDevices": result.ok(service.knownDevices()); break;
                case "requestDevice": pickDevice(a, result); break;
                case "connect": service.connect(a.getString("id"), result); break;
                case "disconnect": service.disconnect(); result.ok(null); break;
                case "mtu": result.ok(service.mtu()); break;
                case "services": result.ok(service.services()); break;
                case "characteristics": result.ok(service.characteristics(a.getString("service"))); break;
                case "read": service.read(a.getString("service"), a.getString("char"), result); break;
                case "write":
                    service.write(a.getString("service"), a.getString("char"),
                        Base64.decode(a.getString("value"), Base64.NO_WRAP), a.optBoolean("withResponse"), result);
                    break;
                case "startNotifications": service.setNotify(a.getString("service"), a.getString("char"), true, result); break;
                case "stopNotifications": service.setNotify(a.getString("service"), a.getString("char"), false, result); break;
                case "syncNow": service.sync().sync("manual"); result.ok(null); break;
                case "findWatch": service.sync().findWatch(); result.ok(null); break;
                case "weatherNow": service.sync().sendWeather(result); break;
                default: result.fail("NotSupportedError", "Unknown method " + method);
            }
        } catch (JSONException | IllegalArgumentException e) {
            result.fail("TypeError", String.valueOf(e.getMessage()));
        }
    }

    // ---------- app panels: sync, weather, music, Ask Claude ----------

    private void appMethod(String method, JSONObject a, BleService.Result result) throws JSONException {
        WatchSync sync = service.sync();
        switch (method) {
            case "appData":
                result.ok(new JSONObject()
                    .put("data", sync.data().json())
                    .put("status", sync.status())
                    .put("connected", service.isReady())
                    .put("weatherPlace", Weather.hasPlace(activity))
                    .put("weatherCity", prefs().getString("weatherCity", "")));
                break;
            case "setUploading": sync.setUploading(a.optBoolean("on")); result.ok(null); break;
            case "music": {
                String op = a.optString("op");
                sync.music().press("next".equals(op) ? WatchProtocol.OP_NEXT : "previous".equals(op) ? WatchProtocol.OP_PREVIOUS : WatchProtocol.OP_PLAY_PAUSE);
                result.ok(null);
                break;
            }
            case "openMusicApp": {
                boolean spotify = "spotify".equals(a.optString("app"));
                if (sync.music().open(spotify ? MusicControl.SPOTIFY : MusicControl.YT_MUSIC)) result.ok(null);
                else result.fail("NotFoundError", (spotify ? "Spotify" : "YouTube Music") + " isn't installed on this phone.");
                break;
            }
            case "openClaude": {
                // Free with a Claude Pro/Max plan: hand the question plus the watch data to the Claude app
                // (or claude.ai in the browser).
                String text = a.optString("question").trim() + "\n\nThis is my data from my Noise Icon 2 smartwatch, synced by the "
                    + "Watch Link app. Please answer in plain language, and tell me if the data is too sparse.\n\n"
                    + sync.data().summary(System.currentTimeMillis());
                android.content.Intent app = new android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text).setPackage("com.anthropic.claude")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    activity.startActivity(app);
                    result.ok("app");
                } catch (android.content.ActivityNotFoundException e) {
                    String q = text.length() > 6000 ? text.substring(0, 6000) : text;
                    try {
                        activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://claude.ai/new?q=" + java.net.URLEncoder.encode(q, "UTF-8"))));
                        result.ok("web");
                    } catch (Exception ex) { result.fail("NotFoundError", "Couldn't open Claude: " + ex.getMessage()); }
                }
                break;
            }
            case "weatherCity": {
                String name = a.optString("name").trim();
                background.execute(() -> {
                    try {
                        String place = Weather.setCity(activity, name);
                        main.post(() -> { result.ok(place); if (service.isReady()) service.sync().sendWeather(null); });
                    } catch (Exception e) {
                        main.post(() -> result.fail("NotFoundError", String.valueOf(e.getMessage())));
                    }
                });
                break;
            }
            case "weatherUseLocation":
                activity.requestLocation(granted -> {
                    if (!granted) { result.fail("SecurityError", "Location permission was not given. You can type a city instead."); return; }
                    Weather.useDeviceLocation(activity);
                    if (!Weather.hasPlace(activity)) { result.fail("NotFoundError", "The phone has no recent location yet. Open Maps once, or type a city."); return; }
                    result.ok(null);
                    if (service.isReady()) service.sync().sendWeather(null);
                });
                break;
            default: result.fail("NotSupportedError", "Unknown method " + method);
        }
    }

    private android.content.SharedPreferences prefs() {
        return activity.getSharedPreferences("watchlink", Context.MODE_PRIVATE);
    }

    // ---------- device picker ----------

    /**
     * Scans for watches and shows them in a list. Unlike a web page, the app also lists
     * watches the phone is already connected to (a connected watch stops advertising).
     */
    private void pickDevice(JSONObject options, BleService.Result result) throws JSONException {
        BluetoothManager m = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = m == null ? null : m.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { result.fail("NotFoundError", "Bluetooth is off"); return; }

        boolean all = options.optBoolean("acceptAllDevices");
        JSONArray filters = options.optJSONArray("filters");
        Map<String, BluetoothDevice> found = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        ArrayAdapter<String> list = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels);

        Runnable refresh = () -> {
            labels.clear();
            for (BluetoothDevice d : found.values()) {
                String name = d.getName();
                labels.add((name != null ? name : "Unknown device") + "   " + d.getAddress());
            }
            list.notifyDataSetChanged();
        };

        for (BluetoothDevice d : m.getConnectedDevices(BluetoothProfile.GATT)) {
            if (all || matches(d.getName(), null, filters)) found.put(d.getAddress(), d);
        }
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (all || matches(d.getName(), null, filters)) found.put(d.getAddress(), d);
        }
        refresh.run();

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        ScanCallback scan = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String name = r.getScanRecord() != null && r.getScanRecord().getDeviceName() != null
                    ? r.getScanRecord().getDeviceName() : d.getName();
                List<ParcelUuid> uuids = r.getScanRecord() == null ? null : r.getScanRecord().getServiceUuids();
                if (!all && !matches(name, uuids, filters)) return;
                main.post(() -> { if (!found.containsKey(d.getAddress())) { found.put(d.getAddress(), d); refresh.run(); } });
            }
        };
        final boolean[] settled = {false};
        Runnable stopScan = () -> { try { if (scanner != null) scanner.stopScan(scan); } catch (RuntimeException ignored) { } };

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(all ? "Choose a device" : "Choose your watch")
            .setAdapter(list, (d, which) -> {
                settled[0] = true;
                stopScan.run();
                BluetoothDevice picked = new ArrayList<>(found.values()).get(which);
                try {
                    result.ok(new JSONObject().put("id", picked.getAddress())
                        .put("name", picked.getName() == null ? JSONObject.NULL : picked.getName()));
                } catch (JSONException e) { result.fail("TypeError", e.getMessage()); }
            })
            .setNegativeButton("Cancel", null)
            .setOnDismissListener(d -> {
                stopScan.run();
                if (!settled[0]) result.fail("NotFoundError", "User cancelled the requestDevice() chooser.");
            })
            .create();
        dialog.show();

        if (scanner != null) {
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
            main.postDelayed(stopScan, 20000);
        }
    }

    /** Web Bluetooth filter semantics: any filter matches if all of its conditions match. */
    private static boolean matches(String name, List<ParcelUuid> uuids, JSONArray filters) {
        if (filters == null || filters.length() == 0) return true;
        for (int i = 0; i < filters.length(); i++) {
            JSONObject f = filters.optJSONObject(i);
            if (f == null) continue;
            boolean ok = true;
            if (f.has("name")) ok = f.optString("name").equals(name);
            if (ok && f.has("namePrefix")) ok = name != null && name.startsWith(f.optString("namePrefix"));
            JSONArray services = f.optJSONArray("services");
            if (ok && services != null && services.length() > 0) {
                if (uuids == null) ok = false; // bonded/connected devices: judged by name only
                else for (int j = 0; j < services.length() && ok; j++) {
                    ok = uuids.contains(ParcelUuid.fromString(services.optString(j)));
                }
            }
            if (ok) return true;
        }
        return false;
    }

    // ---------- replies to JavaScript ----------

    private void resolve(String cb, Object value) {
        if (cb == null || cb.isEmpty()) return;
        String json = value == null ? "null" : value instanceof String ? JSONObject.quote((String) value) : value.toString();
        js("window.__nativeBle && window.__nativeBle.resolve(" + JSONObject.quote(cb) + "," + JSONObject.quote(json) + ")");
    }

    private void reject(String cb, String name, String message) {
        if (cb == null || cb.isEmpty()) return;
        js("window.__nativeBle && window.__nativeBle.reject(" + JSONObject.quote(cb) + "," + JSONObject.quote(name) + ","
            + JSONObject.quote(message == null ? "" : message) + ")");
    }

    private void js(String code) {
        main.post(() -> web.evaluateJavascript(code, null));
    }
}
