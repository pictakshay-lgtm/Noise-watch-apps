// Decoders for standard Bluetooth GATT values, plus the resting heart rate estimate.
// Pure functions (no browser APIs) so they can be tested with Node.

export const STD_SERVICES = {
  "0000180d-0000-1000-8000-00805f9b34fb": "Heart Rate",
  "0000180f-0000-1000-8000-00805f9b34fb": "Battery",
  "0000180a-0000-1000-8000-00805f9b34fb": "Device Information",
  "00001800-0000-1000-8000-00805f9b34fb": "Generic Access",
  "00001801-0000-1000-8000-00805f9b34fb": "Generic Attribute",
  "00001805-0000-1000-8000-00805f9b34fb": "Current Time",
  "00001814-0000-1000-8000-00805f9b34fb": "Running Speed and Cadence",
  "00001822-0000-1000-8000-00805f9b34fb": "Pulse Oximeter",
  "0000181c-0000-1000-8000-00805f9b34fb": "User Data",
  "6e400001-b5a3-f393-e0a9-e50e24dcca9e": "Nordic UART (vendor data)",
};

export const STD_CHARS = {
  "00002a37-0000-1000-8000-00805f9b34fb": "Heart Rate Measurement",
  "00002a38-0000-1000-8000-00805f9b34fb": "Body Sensor Location",
  "00002a19-0000-1000-8000-00805f9b34fb": "Battery Level",
  "00002a29-0000-1000-8000-00805f9b34fb": "Manufacturer Name",
  "00002a24-0000-1000-8000-00805f9b34fb": "Model Number",
  "00002a25-0000-1000-8000-00805f9b34fb": "Serial Number",
  "00002a26-0000-1000-8000-00805f9b34fb": "Firmware Revision",
  "00002a27-0000-1000-8000-00805f9b34fb": "Hardware Revision",
  "00002a28-0000-1000-8000-00805f9b34fb": "Software Revision",
  "00002a00-0000-1000-8000-00805f9b34fb": "Device Name",
  "00002a2b-0000-1000-8000-00805f9b34fb": "Current Time",
  "00002a5f-0000-1000-8000-00805f9b34fb": "PLX Continuous Measurement",
};

// Services Web Bluetooth may open. It only exposes services listed up front, so this
// includes vendor services common on budget smartwatches as well as the standard ones.
// All as full 128-bit strings: Chrome accepts 16-bit numbers too, but other Web Bluetooth
// browsers (e.g. Bluefy on iPad) can reject them.
export const CANDIDATE_SERVICES = [
  ...Object.keys(STD_SERVICES),
  ...["fee0", "fee1", "fee7", "feea", "fff0", "ffe0", "ae00", "ae30", "d0ff", "190e", "3802"].map(s => `0000${s}-0000-1000-8000-00805f9b34fb`),
];

// The minimum a MoYoung watch needs: vendor service, device info, battery, heart rate.
export const ESSENTIAL_SERVICES = ["feea", "180a", "180f", "180d"].map(s => `0000${s}-0000-1000-8000-00805f9b34fb`);

// Readable text for errors from any browser, including ones that reject with a bare string or object.
export function errorText(e) {
  if (e == null) return "unknown error";
  if (typeof e === "string") return e;
  const parts = [e.name, e.message].filter(Boolean);
  if (parts.length) return parts.join(": ");
  const text = String(e);
  if (text !== "[object Object]") return text;
  try { const json = JSON.stringify(e); if (json && json !== "{}") return json; } catch (_) {}
  return "no details from the browser";
}

const TEXT_CHARS = new Set(["2a29", "2a24", "2a25", "2a26", "2a27", "2a28", "2a00"]);

export function shortId(uuid) {
  const m = /^0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb$/i.exec(uuid);
  return m ? m[1].toLowerCase() : uuid.toLowerCase();
}

// Accepts "180d", "0x180d", "fee0" or a full 128-bit UUID.
export function normalizeUuid(input) {
  const s = String(input).trim().toLowerCase().replace(/^0x/, "");
  if (/^[0-9a-f]{4}$/.test(s)) return `0000${s}-0000-1000-8000-00805f9b34fb`;
  if (/^[0-9a-f]{8}$/.test(s)) return `${s}-0000-1000-8000-00805f9b34fb`;
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(s)) return s;
  return null;
}

export function toHex(bytes) {
  return Array.from(bytes, b => b.toString(16).padStart(2, "0")).join(" ");
}

function toText(bytes) {
  return new TextDecoder().decode(bytes).replace(/\0+$/, "");
}

// Heart Rate Measurement (0x2A37): flags byte, then uint8 or uint16 bpm, optional energy and RR intervals.
export function parseHeartRate(bytes) {
  if (bytes.length < 2) return null;
  const flags = bytes[0];
  const wide = flags & 0x01;
  let i = 1;
  const bpm = wide ? bytes[i] | (bytes[i + 1] << 8) : bytes[i];
  i += wide ? 2 : 1;
  const contact = (flags & 0x06) === 0x06 ? true : (flags & 0x06) === 0x04 ? false : null;
  let energy = null;
  if (flags & 0x08) { energy = bytes[i] | (bytes[i + 1] << 8); i += 2; }
  const rr = [];
  if (flags & 0x10) {
    for (; i + 1 < bytes.length; i += 2) rr.push(Math.round(((bytes[i] | (bytes[i + 1] << 8)) / 1024) * 1000));
  }
  return { bpm, contact, energy, rr };
}

// Returns a readable string for known characteristics, otherwise null.
export function describeValue(charUuid, bytes) {
  const id = shortId(charUuid);
  if (id === "2a19" && bytes.length) return `${bytes[0]}%`;
  if (id === "2a37") { const hr = parseHeartRate(bytes); return hr ? `${hr.bpm} bpm` : null; }
  if (TEXT_CHARS.has(id)) return toText(bytes);
  return null;
}

// Resting HR estimate: the lowest 60-second rolling average across the session.
// samples: [{t: ms, bpm}] in time order. Needs at least one full window.
export function restingEstimate(samples, windowMs = 60000) {
  if (!samples.length || samples[samples.length - 1].t - samples[0].t < windowMs) return null;
  let best = Infinity, start = 0, sum = 0;
  for (let end = 0; end < samples.length; end++) {
    sum += samples[end].bpm;
    while (samples[end].t - samples[start].t > windowMs) sum -= samples[start++].bpm;
    if (samples[end].t - samples[0].t >= windowMs) best = Math.min(best, sum / (end - start + 1));
  }
  return Number.isFinite(best) ? Math.round(best) : null;
}
