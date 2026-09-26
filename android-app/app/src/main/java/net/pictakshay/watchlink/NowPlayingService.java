package net.pictakshay.watchlink;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the user can grant Watch Link notification access, which Android requires
 * before an app may read other apps' media sessions (the current song). It reads no notifications.
 */
public class NowPlayingService extends NotificationListenerService { }
