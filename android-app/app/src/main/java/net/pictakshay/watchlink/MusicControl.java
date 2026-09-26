package net.pictakshay.watchlink;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Music for the watch. The watch's play/pause, previous and next buttons arrive as
 * CMD_NOTIFY_PHONE_OPERATION packets and become media key presses, which Android hands to
 * whichever app is playing (Spotify, YouTube Music, …). No extra permission is needed for that.
 *
 * Reading the current song needs notification access (Android's rule for seeing other apps'
 * media sessions). With it, the app can show the song in the page and, if the user turns it on,
 * send each new song to the watch as a notification. The watch firmware has no "now playing"
 * command, so a notification is the only way to get the title onto the watch.
 */
final class MusicControl {
    static final String SPOTIFY = "com.spotify.music";
    static final String YT_MUSIC = "com.google.android.apps.youtube.music";

    interface SongListener { void onSong(String app, String title, String artist, boolean playing); }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private MediaController controller;
    private SongListener listener;
    private String lastKey = "";

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { report(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { report(); }
        @Override public void onSessionDestroyed() { pickController(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> pickController();

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

    boolean installed(String pkg) { return ctx.getPackageManager().getLaunchIntentForPackage(pkg) != null; }

    boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(ctx, NowPlayingService.class).flattenToString());
    }

    void openNotificationAccessSettings() {
        ctx.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Starts following the playing app's song. Does nothing until notification access is granted. */
    void watchSongs(SongListener l) {
        listener = l;
        if (!hasNotificationAccess()) return;
        try {
            if (sessions == null) {
                sessions = ctx.getSystemService(MediaSessionManager.class);
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, new ComponentName(ctx, NowPlayingService.class), main);
            }
            pickController();
        } catch (SecurityException ignored) {
            sessions = null;
        }
    }

    void stop() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        if (controller != null) controller.unregisterCallback(callback);
        sessions = null;
        controller = null;
    }

    JSONObject nowPlaying() {
        JSONObject o = new JSONObject();
        try {
            o.put("access", hasNotificationAccess())
                .put("spotify", installed(SPOTIFY))
                .put("ytMusic", installed(YT_MUSIC));
            if (controller != null && controller.getMetadata() != null) {
                MediaMetadata m = controller.getMetadata();
                PlaybackState s = controller.getPlaybackState();
                o.put("app", appName(controller.getPackageName()))
                    .put("title", m.getString(MediaMetadata.METADATA_KEY_TITLE))
                    .put("artist", m.getString(MediaMetadata.METADATA_KEY_ARTIST))
                    .put("playing", s != null && s.getState() == PlaybackState.STATE_PLAYING);
            }
        } catch (JSONException ignored) { }
        return o;
    }

    private void pickController() {
        if (sessions == null) return;
        List<MediaController> list;
        try { list = sessions.getActiveSessions(new ComponentName(ctx, NowPlayingService.class)); }
        catch (SecurityException e) { return; }
        MediaController pick = null;
        for (MediaController c : list) {   // prefer whatever is playing, then Spotify / YouTube Music
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            if (pick == null || SPOTIFY.equals(c.getPackageName()) || YT_MUSIC.equals(c.getPackageName())) pick = c;
        }
        if (controller != null && (pick == null || !pick.getSessionToken().equals(controller.getSessionToken()))) {
            controller.unregisterCallback(callback);
            controller = null;
        }
        if (pick != null && controller == null) {
            controller = pick;
            controller.registerCallback(callback, main);
        }
        report();
    }

    private void report() {
        if (listener == null || controller == null || controller.getMetadata() == null) return;
        MediaMetadata m = controller.getMetadata();
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        PlaybackState s = controller.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        String key = title + "|" + artist + "|" + playing;
        if (title == null || key.equals(lastKey)) return;
        lastKey = key;
        listener.onSong(appName(controller.getPackageName()), title, artist == null ? "" : artist, playing);
    }

    private static String appName(String pkg) {
        if (SPOTIFY.equals(pkg)) return "Spotify";
        if (YT_MUSIC.equals(pkg)) return "YouTube Music";
        return pkg;
    }
}
