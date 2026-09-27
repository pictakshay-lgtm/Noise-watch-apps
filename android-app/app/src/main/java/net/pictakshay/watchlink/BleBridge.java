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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private BleService service;

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
            if (method.equals("mediaKey")) { service.mediaKey(a.optInt("op")); result.ok(null); return; }
            if (method.equals("openYtMusic")) {
                android.content.Intent i = activity.getPackageManager().getLaunchIntentForPackage(BleService.YT_MUSIC);
                if (i == null) i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com"));
                activity.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                result.ok(null);
                return;
            }
            if (method.equals("state")) { result.ok(service.state()); return; }
            // Ask Gemini (your own key, stored encrypted on the phone; the page never gets it back).
            if (method.equals("aiStatus")) { result.ok(new JSONObject().put("hasKey", GeminiClient.hasKey(activity))); return; }
            if (method.equals("aiSetKey")) {
                String key = a.optString("key").trim();
                if (key.length() < 20 || key.contains(" ")) { result.fail("TypeError", "That doesn't look like a Gemini API key."); return; }
                try { GeminiClient.saveKey(activity, key); } catch (Exception e) { result.fail("SecurityError", "Couldn't store the key on this phone."); return; }
                result.ok(new JSONObject().put("hasKey", true));
                return;
            }
            if (method.equals("aiClearKey")) { GeminiClient.clearKey(activity); result.ok(new JSONObject().put("hasKey", false)); return; }
            if (method.equals("aiAsk")) {
                String q = a.optString("q").trim();
                if (q.isEmpty()) { result.fail("TypeError", "Type a question first."); return; }
                if (q.length() > 2000) q = q.substring(0, 2000);
                GeminiClient.ask(activity, q, new GeminiClient.Done() {
                    @Override public void ok(String text) {
                        try { result.ok(new JSONObject().put("text", text)); } catch (JSONException e) { result.fail("TypeError", e.getMessage()); }
                    }
                    @Override public void fail(String message) { result.fail("AiError", message); }
                });
                return;
            }
            if (method.equals("callStatus")) { result.ok(callStatus()); return; }
            if (method.equals("setCalls")) {
                if (!a.optBoolean("on")) { prefs().edit().putBoolean("calls", false).apply(); result.ok(callStatus()); return; }
                activity.requestCallPermissions(() -> {
                    prefs().edit().putBoolean("calls", activity.hasCallPermission()).apply();
                    try { result.ok(callStatus()); } catch (JSONException e) { result.fail("TypeError", e.getMessage()); }
                });
                return;
            }
            if (!activity.hasBluetoothPermissions() && !method.equals("availability")) {
                result.fail("SecurityError", "Allow Health Watcher to use Bluetooth (Nearby devices) in Android settings.");
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
                case "testCall":
                    if (!service.incomingCall("Health Watcher test call")) { result.fail("NetworkError", "Connect the watch first."); break; }
                    CallReceiver.startTest(10000);
                    main.postDelayed(() -> { CallReceiver.endTest(); service.callEnded(); }, 10000);
                    result.ok(null);
                    break;
                case "stopNotifications": service.setNotify(a.getString("service"), a.getString("char"), false, result); break;
                default: result.fail("NotSupportedError", "Unknown method " + method);
            }
        } catch (JSONException | IllegalArgumentException e) {
            result.fail("TypeError", String.valueOf(e.getMessage()));
        }
    }

    private JSONObject callStatus() throws JSONException {
        return new JSONObject()
            .put("on", prefs().getBoolean("calls", false) && activity.hasCallPermission())
            .put("names", activity.granted(android.Manifest.permission.READ_CALL_LOG)
                && activity.granted(android.Manifest.permission.READ_CONTACTS))
            .put("reject", activity.granted(android.Manifest.permission.ANSWER_PHONE_CALLS))
            .put("buttons", service == null ? "" : service.callButtons());
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
