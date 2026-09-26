# Watch Link for Android

An Android app version of [Watch Link](../watch-link/). It shows the same page, but the Bluetooth connection is kept by the app, so it **stays connected** when you reload the page, leave the app or lock the phone.

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
- The page and the live faces are copied from `watch-link/` and `watch-faces/live/` at build time, so the app always matches the website.

## Building

GitHub Actions builds the app (`.github/workflows/android-app.yml`) on every change to `android-app/`, `watch-link/` or `watch-faces/live/`, and publishes it as a release from `main`. To build locally, install the Android SDK (API 34) and run `./gradlew assembleDebug` in this folder.
