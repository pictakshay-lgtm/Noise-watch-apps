package net.pictakshay.watchlink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;

/**
 * Shows incoming calls on the watch. Android sends PHONE_STATE twice when a call rings: once
 * without the number and, if READ_CALL_LOG is granted, once with it. The first is held back
 * briefly so the watch buzzes once, with the caller's name when we can find it.
 */
public class CallReceiver extends BroadcastReceiver {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Runnable pending;
    private static boolean alerted;   // the watch is showing a call we sent

    @Override public void onReceive(Context context, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        if (!context.getSharedPreferences("watchlink", Context.MODE_PRIVATE).getBoolean("calls", false)) return;
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        @SuppressWarnings("deprecation")
        String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        Context app = context.getApplicationContext();

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            if (alerted) return;
            if (number == null || number.isEmpty()) {
                if (pending == null) {
                    pending = () -> { pending = null; alert(app, "Incoming call"); };
                    main.postDelayed(pending, 700);
                }
            } else {
                if (pending != null) { main.removeCallbacks(pending); pending = null; }
                String name = contactName(app, number);
                alert(app, name != null ? name : number);
            }
        } else {
            // Answered (OFFHOOK) or ended (IDLE): clear the call screen on the watch.
            if (pending != null) { main.removeCallbacks(pending); pending = null; }
            if (alerted) {
                alerted = false;
                BleService s = BleService.instance;
                if (s != null) s.callEnded();
            }
        }
    }

    private static long testUntil;   // a test call from the Calls card is on the watch

    static boolean isRinging() { return alerted || android.os.SystemClock.uptimeMillis() < testUntil; }

    /** Treats the watch as showing a call for a while, so its buttons can be tried. */
    static void startTest(long ms) { testUntil = android.os.SystemClock.uptimeMillis() + ms; }

    static void endTest() { testUntil = 0; }

    private static void alert(Context app, String who) {
        BleService s = BleService.instance;
        if (s == null) return;
        alerted = s.incomingCall(who);
    }

    private static String contactName(Context app, String number) {
        if (app.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor c = app.getContentResolver().query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) { }
        return null;
    }
}
