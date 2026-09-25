# Watch Link

A web page that connects to a Noise smartwatch over Bluetooth from **Chrome on Android**. There's nothing to install.

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

## Decoding steps and sleep

1. Connect, tap **Listen** on each unknown characteristic, and sync something on the watch, for example walk a few steps.
2. Tap **Export JSON** and share the file with Claude together with the numbers NoiseFit showed at that moment.
3. Once the format is decoded, it can be added to `decode.js` and to the Wrist Log summary.

## Files

- `index.html`: the page.
- `decode.js`: standard Bluetooth decoders and the resting heart rate estimate. It has no browser code, so it can be tested with Node.
