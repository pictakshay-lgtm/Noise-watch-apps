package net.pictakshay.watchlink;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Owns the one Bluetooth LE connection to the watch. It runs as a foreground service, so the
 * connection survives the page reloading, the activity being recreated and the app going to the
 * background. It reconnects by itself after a drop until the user disconnects.
 *
 * Android allows one GATT operation at a time, so reads, writes and descriptor writes go through
 * a queue. All state is touched on the main thread.
 */
@SuppressLint("MissingPermission") // MainActivity requests the Bluetooth permissions first.
public class BleService extends Service {

    public interface Listener { void onEvent(JSONObject event); }

    public interface Result {
        void ok(Object value);
        void fail(String name, String message);
    }

    public class LocalBinder extends Binder {
        BleService get() { return BleService.this; }
    }

    private static final String CHANNEL = "watch";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS = "watchlink";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String MOYOUNG = "0000feea-0000-1000-8000-00805f9b34fb";
    private static final String MOYOUNG_IN = "0000fee3-0000-1000-8000-00805f9b34fb";
    private static final String MOYOUNG_OUT = "0000fee2-0000-1000-8000-00805f9b34fb";
    // MoYoung messages (as in Gadgetbridge's DaFit support): CMD 0x41 = notification,
    // type 0 = incoming call, type 0xFF = call answered/ended.
    private static final int CMD_MESSAGE = 0x41;
    private static final int MESSAGE_CALL = 0x00;
    private static final int MESSAGE_CALL_OFF = 0xFF;
    /** The running service, so {@link CallReceiver} can reach the watch. */
    static BleService instance;
    static final String YT_MUSIC = "com.google.android.apps.youtube.music";
    private static final Result IGNORE = new Result() {
        @Override public void ok(Object value) { }
        @Override public void fail(String name, String message) { }
    };
    static final String ACTION_DISCONNECT = "net.pictakshay.watchlink.DISCONNECT";

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Deque<Op> queue = new ArrayDeque<>();
    private final List<Result> waitingForConnect = new ArrayList<>();

    private Listener listener;
    private BluetoothGatt gatt;
    private BluetoothDevice device;
    private boolean ready;            // connected and services discovered
    private boolean userDisconnected;
    private int retries;
    private Op current;
    private int mtu = 23;   // negotiated on connect; a write can carry mtu - 3 bytes
    private String state = "disconnected";   // disconnected | connecting | connected | reconnecting

    // ---------- lifecycle ----------

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnect();
            return START_NOT_STICKY;
        }
        startInForeground("Watch Link is ready");
        if (device == null) {
            String saved = prefs().getString("address", null);
            if (saved != null && adapter() != null) connect(saved, null);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        closeGatt();
        super.onDestroy();
    }

    void setListener(Listener l) { listener = l; }

    // ---------- public API (called on the main thread) ----------

    boolean isAvailable() {
        BluetoothAdapter a = adapter();
        return a != null && a.isEnabled();
    }

    boolean isConnected(String address) {
        return ready && device != null && device.getAddress().equals(address);
    }

    /** The watch we last connected to, plus any Noise watch the phone is already connected to. */
    JSONArray knownDevices() {
        JSONArray out = new JSONArray();
        List<String> seen = new ArrayList<>();
        String saved = prefs().getString("address", null);
        if (saved != null) {
            addDevice(out, seen, saved, prefs().getString("name", null));
        }
        BluetoothManager m = manager();
        if (m != null) {
            for (BluetoothDevice d : m.getConnectedDevices(BluetoothProfile.GATT)) {
                String name = d.getName();
                if (name != null && name.toLowerCase().contains("noise")) addDevice(out, seen, d.getAddress(), name);
            }
        }
        return out;
    }

    void connect(String address, Result result) {
        BluetoothAdapter a = adapter();
        if (a == null) { if (result != null) result.fail("NotSupportedError", "Bluetooth isn't available"); return; }
        if (isConnected(address)) { if (result != null) result.ok(null); return; }
        if (result != null) waitingForConnect.add(result);
        if (device != null && device.getAddress().equals(address) && gatt != null) return; // already connecting
        closeGatt();
        userDisconnected = false;
        startForegroundService(new Intent(this, BleService.class)); // keep running after the UI closes
        device = a.getRemoteDevice(address);
        prefs().edit().putString("address", address).putString("name", device.getName()).apply();
        startInForeground("Connecting to " + displayName());
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
        setState(retries > 0 ? "reconnecting" : "connecting");
    }

    void disconnect() {
        userDisconnected = true;
        main.removeCallbacksAndMessages(null);
        boolean wasConnected = ready;
        closeGatt();
        failAll("NetworkError", "Disconnected");
        if (wasConnected || device != null) emit("disconnected");
        device = null;
        setState("disconnected");
        prefs().edit().remove("address").apply(); // don't auto-connect again until the user picks it
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    JSONArray services() throws JSONException {
        JSONArray out = new JSONArray();
        if (gatt == null) return out;
        for (BluetoothGattService s : gatt.getServices()) out.put(s.getUuid().toString());
        return out;
    }

    JSONArray characteristics(String service) throws JSONException {
        JSONArray out = new JSONArray();
        BluetoothGattService s = gatt == null ? null : gatt.getService(UUID.fromString(service));
        if (s == null) return out;
        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
            int p = c.getProperties();
            JSONObject props = new JSONObject()
                .put("read", (p & BluetoothGattCharacteristic.PROPERTY_READ) != 0)
                .put("write", (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                .put("writeWithoutResponse", (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                .put("notify", (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                .put("indicate", (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0);
            out.put(new JSONObject().put("uuid", c.getUuid().toString()).put("properties", props));
        }
        return out;
    }

    void read(String service, String characteristic, Result result) {
        BluetoothGattCharacteristic c = find(service, characteristic, result);
        if (c == null) return;
        enqueue(new Op(result) {
            @Override boolean start() { return gatt.readCharacteristic(c); }
        });
    }

    int mtu() { return mtu; }

    void write(String service, String characteristic, byte[] value, boolean withResponse, Result result) {
        BluetoothGattCharacteristic c = find(service, characteristic, result);
        if (c == null) return;
        if (value.length > mtu - 3) {
            // Android would silently cut the write short, which the watch sees as a corrupt chunk.
            result.fail("NetworkError", "The Bluetooth link only fits " + (mtu - 3) + " bytes per write (MTU " + mtu + "), but "
                + value.length + " were sent. Choose a smaller Packet size.");
            return;
        }
        int type = withResponse ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        enqueue(new Op(result) {
            @Override boolean start() {
                if (Build.VERSION.SDK_INT >= 33) {
                    return gatt.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS;
                }
                c.setWriteType(type);
                c.setValue(value);
                return gatt.writeCharacteristic(c);
            }
        });
    }

    void setNotify(String service, String characteristic, boolean enable, Result result) {
        BluetoothGattCharacteristic c = find(service, characteristic, result);
        if (c == null) return;
        if (!gatt.setCharacteristicNotification(c, enable)) { result.fail("NetworkError", "Couldn't change notifications"); return; }
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) { result.ok(null); return; }
        boolean indicate = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
        byte[] value = !enable ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            : indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        enqueue(new Op(result) {
            @Override boolean start() {
                if (Build.VERSION.SDK_INT >= 33) return gatt.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS;
                d.setValue(value);
                return gatt.writeDescriptor(d);
            }
        });
    }

    // ---------- GATT callback ----------

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            main.post(() -> {
                if (g != gatt) return;
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    retries = 0;
                    mtu = 23;
                    // Ask for a large MTU so 244-byte watch face chunks fit in one write.
                    if (!g.requestMtu(517)) g.discoverServices();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean wasReady = ready;
                    ready = false;
                    g.close();
                    gatt = null;
                    failQueue("NetworkError", "The watch disconnected");
                    if (wasReady) emit("disconnected");
                    if (!userDisconnected && device != null) scheduleReconnect();
                    else setState("disconnected");
                }
            });
        }

        @Override public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
                g.discoverServices();
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { g.disconnect(); return; }
                ready = true;
                startInForeground("Connected to " + displayName());
                for (Result r : new ArrayList<>(waitingForConnect)) r.ok(null);
                waitingForConnect.clear();
                emit("connected");
                setState("connected");
                // Listen for the watch's music buttons even when the page isn't open.
                if (g.getService(UUID.fromString(MOYOUNG)) != null) setNotify(MOYOUNG, MOYOUNG_IN, true, IGNORE);
            });
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            main.post(() -> finish(status, Base64.encodeToString(value, Base64.NO_WRAP)));
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT >= 33) return; // the byte[] overload above handles 33+
            byte[] value = c.getValue();
            main.post(() -> finish(status, Base64.encodeToString(value == null ? new byte[0] : value, Base64.NO_WRAP)));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> finish(status, null));
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> finish(status, null));
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            notifyChanged(c, value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return;
            byte[] value = c.getValue();
            notifyChanged(c, value == null ? new byte[0] : value.clone());
        }
    };

    private void notifyChanged(BluetoothGattCharacteristic c, byte[] value) {
        // Watch buttons: FE EA 20 06 67 <op>. While a call rings they only answer or reject it:
        // op 3 rejects, any other button answers. Otherwise op 0 = play/pause, 1 = previous, 2 = next.
        if (value != null && value.length >= 6 && value[0] == (byte) 0xFE && value[1] == (byte) 0xEA
                && c.getUuid().toString().equals(MOYOUNG_IN)) {
            boolean ringing = CallReceiver.isRinging();
            int cmd = value[4] & 0xFF, op = value[5] & 0xFF;
            if (ringing) main.post(() -> rememberCallButton(value));
            if (cmd == 0x67) {
                if (ringing) main.post(op == 3 ? this::rejectCall : this::answerCall);
                else if (op <= 2) main.post(() -> mediaKey(op));
            }
        }
        String service = c.getService().getUuid().toString();
        String ch = c.getUuid().toString();
        String b64 = Base64.encodeToString(value, Base64.NO_WRAP);
        main.post(() -> {
            try {
                emit(new JSONObject().put("type", "notify").put("service", service).put("char", ch).put("value", b64));
            } catch (JSONException ignored) { }
        });
    }

    // ---------- queue ----------

    private abstract static class Op {
        final Result result;
        Op(Result result) { this.result = result; }
        abstract boolean start();
    }

    private void enqueue(Op op) {
        queue.add(op);
        if (current == null) next();
    }

    private void next() {
        current = null;
        while (!queue.isEmpty()) {
            Op op = queue.poll();
            if (gatt == null) { op.result.fail("NetworkError", "Not connected"); continue; }
            current = op;
            boolean started;
            try { started = op.start(); } catch (RuntimeException e) { started = false; }
            if (started) return;
            current = null;
            op.result.fail("NetworkError", "The phone couldn't start that Bluetooth operation");
        }
    }

    private void finish(int status, Object value) {
        Op op = current;
        if (op == null) return;
        if (status == BluetoothGatt.GATT_SUCCESS) op.result.ok(value);
        else op.result.fail("NetworkError", "Bluetooth operation failed (status " + status + ")");
        next();
    }

    private void failQueue(String name, String message) {
        if (current != null) current.result.fail(name, message);
        current = null;
        while (!queue.isEmpty()) queue.poll().result.fail(name, message);
    }

    private void failAll(String name, String message) {
        failQueue(name, message);
        for (Result r : waitingForConnect) r.fail(name, message);
        waitingForConnect.clear();
    }

    // ---------- helpers ----------

    private void scheduleReconnect() {
        long delay = Math.min(30000, 2000L << Math.min(retries, 4));
        retries++;
        startInForeground("Reconnecting to " + displayName() + "…");
        setState("reconnecting");
        String address = device.getAddress();
        main.postDelayed(() -> { if (!userDisconnected && gatt == null) connect(address, null); }, delay);
    }

    private BluetoothGattCharacteristic find(String service, String characteristic, Result result) {
        if (gatt == null || !ready) { result.fail("NetworkError", "Not connected"); return null; }
        BluetoothGattService s = gatt.getService(UUID.fromString(service));
        BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(UUID.fromString(characteristic));
        if (c == null) result.fail("NotFoundError", "No such characteristic: " + characteristic);
        return c;
    }

    private void closeGatt() {
        ready = false;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (RuntimeException ignored) { }
            gatt = null;
        }
    }

    /** The one connection state the page shows; sent to it on every change. */
    JSONObject state() {
        try {
            return new JSONObject().put("state", state)
                .put("name", device == null ? JSONObject.NULL : displayName());
        } catch (JSONException e) { return new JSONObject(); }
    }

    private void setState(String s) {
        if (s.equals(state)) return;
        state = s;
        try { emit(state().put("type", "state")); } catch (JSONException ignored) { }
    }

    private void emit(String type) {
        try { emit(new JSONObject().put("type", type)); } catch (JSONException ignored) { }
    }

    private void emit(JSONObject event) {
        try { if (device != null) event.put("id", device.getAddress()); } catch (JSONException ignored) { }
        if (listener != null) listener.onEvent(event);
    }

    private static void addDevice(JSONArray out, List<String> seen, String address, String name) {
        if (seen.contains(address)) return;
        seen.add(address);
        try { out.put(new JSONObject().put("id", address).put("name", name == null ? JSONObject.NULL : name)); }
        catch (JSONException ignored) { }
    }

    private String displayName() {
        String n = device == null ? null : device.getName();
        return n != null ? n : "the watch";
    }

    private void startInForeground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Watch connection", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, BleService.class).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Watch Link")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Disconnect", stop).build())
            .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, n);
    }

    /** Presses a media key; Android sends it to whatever is playing (YouTube Music, Spotify, …). */
    void mediaKey(int op) {
        int key = op == 2 ? android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            : op == 1 ? android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS : android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        long t = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, key, 0));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, key, 0));
    }

    // ---------- calls ----------

    /** Shows an incoming call on the watch. Returns false when no watch is connected. */
    boolean incomingCall(String who) {
        if (!ready || gatt == null || gatt.getService(UUID.fromString(MOYOUNG)) == null) return false;
        clearCallButtons();
        byte[] text = who.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = Math.min(text.length, 60);
        while (len > 0 && len < text.length && (text[len] & 0xC0) == 0x80) len--; // don't split a character
        byte[] payload = new byte[len + 1];
        payload[0] = (byte) MESSAGE_CALL;
        System.arraycopy(text, 0, payload, 1, len);
        sendToWatch(CMD_MESSAGE, payload);
        return true;
    }

    void callEnded() {
        if (ready && gatt != null && gatt.getService(UUID.fromString(MOYOUNG)) != null) {
            sendToWatch(CMD_MESSAGE, new byte[]{(byte) MESSAGE_CALL_OFF});
        }
    }

    /** The watch's reject button. Needs the "Phone" permission (ANSWER_PHONE_CALLS). */
    @SuppressWarnings("deprecation")
    void rejectCall() {
        if (!CallReceiver.isRinging() || !canManageCalls()) return; // only act on a call we're showing
        try { getSystemService(android.telecom.TelecomManager.class).endCall(); } catch (RuntimeException ignored) { }
    }

    /** The watch's answer button: picks up on the phone (audio stays on the phone). */
    void answerCall() {
        if (!CallReceiver.isRinging() || !canManageCalls()) return;
        try { getSystemService(android.telecom.TelecomManager.class).acceptRingingCall(); } catch (RuntimeException ignored) { }
    }

    private boolean canManageCalls() {
        return checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Keeps what the watch sent during the last call, so the Calls card can show it. */
    private void rememberCallButton(byte[] value) {
        StringBuilder hex = new StringBuilder();
        for (int i = 4; i < value.length; i++) hex.append(String.format("%02X ", value[i]));
        String line = hex.toString().trim();
        String prev = prefs().getString("callButtons", "");
        String all = prev.isEmpty() ? line : prev + ", " + line;
        prefs().edit().putString("callButtons", all.length() > 120 ? all.substring(all.length() - 120) : all).apply();
    }

    /** A new call starts with a clean record of watch buttons. */
    void clearCallButtons() { prefs().edit().remove("callButtons").apply(); }

    String callButtons() { return prefs().getString("callButtons", ""); }

    /** Frames FE EA 20+len>>8 len cmd payload and writes it in MTU-sized pieces, as the watch expects. */
    private void sendToWatch(int cmd, byte[] payload) {
        int total = payload.length + 5;
        byte[] pkt = new byte[total];
        pkt[0] = (byte) 0xFE; pkt[1] = (byte) 0xEA;
        pkt[2] = (byte) (0x20 + (total >> 8)); pkt[3] = (byte) total; pkt[4] = (byte) cmd;
        System.arraycopy(payload, 0, pkt, 5, payload.length);
        int step = mtu - 3;
        for (int i = 0; i < total; i += step) {
            write(MOYOUNG, MOYOUNG_OUT, java.util.Arrays.copyOfRange(pkt, i, Math.min(total, i + step)), false, IGNORE);
        }
    }

    private BluetoothManager manager() { return (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE); }
    private BluetoothAdapter adapter() { BluetoothManager m = manager(); return m == null ? null : m.getAdapter(); }
    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
}
