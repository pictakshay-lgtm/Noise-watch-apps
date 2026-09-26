package net.pictakshay.watchlink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The 15-minute alarm lands here and is handed to the running watch service. */
public class SyncReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        BleService s = BleService.instance;
        if (s != null && WatchSync.ACTION_SYNC.equals(intent.getAction())) s.sync().onAlarm();
    }
}
