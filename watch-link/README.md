# Watch Link

A web page that connects to a Noise smartwatch over Bluetooth from **Chrome on Android**, or from the **Bluefy** browser app on iPad and iPhone. On Android there's nothing to install.

- **Watch:** name, battery and model or firmware, if the watch shares them.
- **Heart rate:** live readings with low, average and high, plus a resting estimate (the lowest 1-minute average in the session).
- **For Wrist Log:** a one-line summary, such as today's resting heart rate and watch battery, that you copy and paste into Wrist Log.
- **What the watch shares:** every Bluetooth service and characteristic Chrome can open, with Read and Listen buttons.
- **Raw log:** every value read or received, in hex. You can export it as JSON.

## What it can and can't read

Web Bluetooth only reads what the watch openly shares. Noise watches send steps, sleep, SpO2 and stress to NoiseFit in Noise's own private format, which isn't documented. Where the watch shares them, the page can read:

| Data | Bluetooth service | Status |
|---|---|---|
| Heart rate | Heart Rate (0x180D) | Decoded, if the watch shares it. Some models only share it during a workout. |
| Battery | Battery (0x180F) | Decoded |
| Model and firmware | Device Information (0x180A) | Decoded |
| Steps, sleep, SpO2, stress | Noise's private service | Shown as raw hex. Needs decoding (see below). |

Chrome only opens services it's told about in advance. The page asks for the standard ones and the private services common on budget smartwatches. If your watch uses others, find them with the free **nRF Connect** app and add their UUIDs in the "Extra service UUIDs" box.

## Using it

1. Force-stop NoiseFit. The watch usually accepts only one Bluetooth connection at a time.
2. Turn on Bluetooth and Location on the phone.
3. Open the page in Chrome on Android and tap **Connect to watch**.

The page must be served over **https**. Opening the file directly doesn't work. The easiest option is GitHub Pages: go to repository **Settings → Pages → Deploy from a branch → `main` / `(root)`**. The page is then at `https://<user>.github.io/Noise-watch-apps/watch-link/`.

## iPad and iPhone

Safari, and every other iPad or iPhone browser, can't use Web Bluetooth, because Apple requires them all to use its WebKit engine, which doesn't support it. You have two options:

| Option | Effort | Notes |
|---|---|---|
| **Bluefy – Web BLE Browser** (free, App Store) | None. Open this page in Bluefy. | Bluefy is a separate browser app that adds Web Bluetooth, so this page runs there unchanged. Not yet tested with the Noise Icon 2. |
| A native iPad app (Swift, CoreBluetooth) | High | Needs a Mac with Xcode. Installing on your own iPad works with a free Apple ID, but the app has to be re-signed every 7 days. A paid developer account ($99 a year) removes that limit. |

Either way, the watch accepts only one Bluetooth connection at a time. Disconnect it from your phone (and close NoiseFit) before connecting from the iPad, and the other way round.

## Staying connected

A web page loses its Bluetooth connection when it closes or reloads, and browsers need a tap before a page's first connection. Within those limits, Watch Link:

- **Reconnects by itself after a drop.** It retries 6 times, waiting 2 s, 4 s, 8 s and so on up to 30 s. Tapping Disconnect stops the retries.
- **Reconnects on page load** to a watch picked before, where the browser supports `navigator.bluetooth.getDevices()` (Chrome, sometimes behind a flag). Bluefy on iPad still needs one tap on Connect to watch.
- **Remembers** (in this browser only) the watch's name, details, last heart rate, resting estimate and when it was last seen, plus your chosen face and packet size, and shows them before it reconnects.

## Uploading watch faces (experimental)

The **Upload watch face** panel sends a MoYoung-format face file (`.bin`) to the watch, with no laptop needed. It uses the same protocol as [dawfu](https://github.com/david47k/dawfu): a size header on `fee2`, the watch requesting 244-byte chunks on `fee3`, and chunks written to `fee6`. When the upload finishes, the page switches the watch to the uploaded face.

1. Connect to the watch (Connect to watch, or Reconnect saved watch).
2. Pick **One Dark Live** or **Web Live** (from [`watch-faces/live/`](../watch-faces/live/)), or choose your own `.bin` file.
3. Tap **Upload to watch** and keep the page open until it finishes.

The upload stops if the watch reports a platform other than `MOYOUNG-V2`. It hasn't been tested on a real Noise Icon 2 yet. Some phones' Bluetooth may not allow the 244-byte packets these watches use, in which case use dawfu on a laptop instead.

## Decoding steps and sleep

1. Connect, tap **Listen** on each unknown characteristic, and sync something on the watch, for example walk a few steps.
2. Tap **Export JSON** and share the file with Claude together with the numbers NoiseFit showed at that moment.
3. Once the format is decoded, it can be added to `decode.js` and to the Wrist Log summary.

## Files

- `index.html`: the page.
- `upload.js`: the watch face upload protocol. It has no browser code and is tested with Node.
- `decode.js`: standard Bluetooth decoders and the resting heart rate estimate. It has no browser code, so it can be tested with Node.
