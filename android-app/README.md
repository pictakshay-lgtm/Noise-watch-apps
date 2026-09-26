# Watch Link for Android

The Watch Link app for the Noise Icon 2. It keeps the watch connected in the background and has a home screen with four tabs:

| Tab | What it does |
|---|---|
| **Today** | Steps ring against your goal, distance, calories, heart rate, last night's sleep, watch battery, the weather sent to the watch, and a 7-day steps chart. |
| **Music** | Now playing, play/pause/next/previous, open Spotify or YouTube Music, and optionally show each new song on the watch. The watch's own music buttons control the phone too. |
| **Ask Claude** | Ask questions about your watch data in plain language (needs your own Anthropic API key, stored only on the phone). |
| **Watch** | Connect / disconnect, Find my watch, Sync now, weather location, watch faces, and the advanced Bluetooth tools (the original Watch Link page). |

## Automatic sync

While the watch is connected, the app syncs by itself, even when the app is closed:

- **Every 15 minutes:** battery, today's steps / distance / calories, a heart-rate measurement (takes the watch about 30 s), last night's sleep, and the previous two days' steps and sleep.
- **Every 4 hours**, and whenever the watch asks: today's weather and a 7-day forecast from [Open-Meteo](https://open-meteo.com/) (free, no key). The place is a city you type, or the phone's location, saved while the app is open.
- **On every connection:** the time.

Data is stored on the phone in `watchdata.json` (about a month of readings). The watch commands come from the MoYoung / Da Fit protocol as reverse-engineered by Gadgetbridge (see `WatchProtocol.java`).

## Signing in

The welcome screen signs you in **with Claude**: your first name and an Anthropic API key (from console.anthropic.com → API keys). The key is checked with a free call (listing models), stored only on the phone, and powers Ask Claude; the name personalises the app. *Skip for now* works too; sign in later from *Watch → Claude account*.

## Google accounts, Drive backup and Google Fit (optional)

- **Google accounts** (one or more), optional, in *Watch → Google accounts & backup*. The app shows your name and photo, and backs up `watchdata.json` to each account's hidden **Drive app folder** every 6 hours (only this app can see it). *Restore from this backup* brings it back on a new phone.
- **Google Fit / Health Connect** (Android 14+): after each sync the app writes steps, distance, active calories, heart rate and sleep to Health Connect, which Google Fit, Samsung Health and others read. Health Connect is per phone, so every health app on the phone can use the same data.

### One-time Google Cloud setup (needed for sign-in)

Google only lets an app sign in once it's registered. Nothing needs to be pasted into the app; Google recognises it by package name and signing certificate.

1. Go to <https://console.cloud.google.com/>, create a project (e.g. *Watch Link*).
2. **APIs & Services → Library**: enable **Google Drive API**.
3. **APIs & Services → OAuth consent screen**: *External*, app name *Watch Link*, your email as support/developer contact. Under **Test users**, add every Google account you'll sign in with.
4. **Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `net.pictakshay.watchlink`
   - SHA-1: `7D:A4:1B:22:45:2B:56:A3:4E:03:D8:92:6E:89:75:E2:27:F0:EE:A1`
5. Save. Sign-in works within a few minutes. Until then the app says *developer error 10* and offers "Continue without an account".

### Permissions it asks for

- **Nearby devices** (Bluetooth), **Notifications** (the status notification).
- **Approximate location**, only if you choose "Use my location" for the weather.
- **Notification access**, only if you turn on "Show songs on the watch": Android requires it before an app can see what another app is playing. The app doesn't read your notifications.

## What's different from the web page

| | Web page (Chrome / Bluefy) | Android app |
|---|---|---|
| Connection after reload | Lost; reconnect needed (automatic in Chrome, one tap in Bluefy) | Kept |
| In the background | Lost when the page closes | Kept by a background service; a notification shows the status and has a Disconnect button |
| Watch already connected to the phone | Hidden from the device list | Listed in the device picker |
| Drops (out of range) | The page retries while open | The app retries until you disconnect |
| Watch face uploads | Limited by the browser's Bluetooth packet size | Asks the watch for a large packet size, so 244-byte chunks fit |

## Installing

1. On the Android phone, open the latest release: **Releases → Watch Link app** in this repository, or go straight to `https://github.com/pictakshay-lgtm/Noise-watch-apps/releases/latest/download/WatchLink.apk`.
2. Open the downloaded **WatchLink.apk** and allow installing from that source when Android asks.
3. Open **Watch Link** and allow **Nearby devices** (and notifications).
4. Tap **Connect to watch** and pick **Noise Icon 2**. After that, the app reconnects to it by itself.

New builds install over the old one, because every build is signed with the same key. `watchlink-debug.keystore` is a throwaway key kept in the repo for that reason. It's for sideloading only, never for a store listing.

## How it works

- `MainActivity` shows the bundled Watch Link page in a WebView, served from `https://appassets.androidplatform.net/assets/…`. When loading the page, it inserts `assets/app/ble-polyfill.js` ahead of the page's own scripts.
- `ble-polyfill.js` replaces `navigator.bluetooth` with an implementation backed by `window.NativeBle`, so the page runs unchanged.
- `BleBridge` is the JavaScript interface. It also shows the device picker, which lists connected and bonded watches as well as scan results.
- `BleService` is a foreground service that owns the single GATT connection. It queues GATT operations one at a time, requests MTU 517, and reconnects after drops.
- `WatchSync` runs the 15-minute sync (an inexact, Doze-friendly alarm via `SyncReceiver`), the 4-hourly weather, and the watch's music buttons. `WatchProtocol` builds and parses the watch's packets; `WatchData` stores the results.
- `MusicControl` turns watch buttons into media keys and follows the playing app's song (`NowPlayingService` exists only for the notification-access grant).
- `ClaudeChat` calls the Claude API with the official Java SDK (`claude-opus-5`, with server-side refusal fallbacks enabled).
- The home screen is `watch-link/app/index.html`. It and the live faces are copied from `watch-link/` and `watch-faces/live/` at build time.

## Building

GitHub Actions builds the app (`.github/workflows/android-app.yml`) on every change to `android-app/`, `watch-link/` or `watch-faces/live/`, and publishes it as a release from `main`. To build locally, install the Android SDK (API 34) and run `./gradlew assembleDebug` in this folder.
