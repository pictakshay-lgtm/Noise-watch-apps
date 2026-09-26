package net.pictakshay.watchlink;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Music for the watch. The watch's play/pause, previous and next buttons arrive as
 * CMD_NOTIFY_PHONE_OPERATION packets and become media key presses, which Android hands to
 * whichever app is playing (Spotify, YouTube Music, …). No extra permission is needed.
 */
final class MusicControl {
    static final String SPOTIFY = "com.spotify.music";
    static final String YT_MUSIC = "com.google.android.apps.youtube.music";

    private final Context ctx;

    MusicControl(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** Sends a media key; op is WatchProtocol.OP_PLAY_PAUSE / OP_NEXT / OP_PREVIOUS. */
    void press(int op) {
        int key = op == WatchProtocol.OP_NEXT ? KeyEvent.KEYCODE_MEDIA_NEXT
            : op == WatchProtocol.OP_PREVIOUS ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
    }

    /** Opens Spotify or YouTube Music; returns false if it isn't installed. */
    boolean open(String pkg) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }
}
