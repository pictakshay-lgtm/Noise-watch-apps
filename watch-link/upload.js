// Watch face upload for MoYoung v2 watches (Da Fit platform; the Noise Icon 2 firmware is MOY-VRZ4).
// A port of the protocol used by dawfu (https://github.com/david47k/dawfu):
//   1. Write FE EA 20 09 74 + file size (u32, big-endian) to fee2.
//   2. The watch notifies on fee3 with FE EA 20 07 74 + chunk number (u16, BE) for each chunk it wants;
//      reply by writing that 244-byte slice of the file to fee6.
//   3. The watch notifies FE EA 20 09 74 + checksum (u32) when it has everything; acknowledge with
//      FE EA 20 09 74 00 00 00 00, then switch to the uploaded face (slot 13) with FE EA 20 06 19 0D.
// No browser APIs here, so it can be tested with Node.

export const CHUNK_SIZE = 244;
const PREP = [0xfe, 0xea, 0x20, 0x09, 0x74];
const CHUNK_REQ = [0xfe, 0xea, 0x20, 0x07, 0x74];
const SHOW_UPLOADED_FACE = [0xfe, 0xea, 0x20, 0x06, 0x19, 0x0d];

const startsWith = (bytes, prefix) => bytes.length >= prefix.length && prefix.every((b, i) => bytes[i] === b);

export function prepCommand(size) {
  return Uint8Array.from([...PREP, (size >>> 24) & 0xff, (size >>> 16) & 0xff, (size >>> 8) & 0xff, size & 0xff]);
}

// Drives one upload. `send(bytes)` writes to fee2, `sendFile(bytes)` writes to fee6.
// Feed every fee3 notification to handle(); it resolves `done` with the watch's checksum.
export class FaceUploader {
  // packetSize: bytes per BLE write. The watch always counts in 244-byte chunks; on phones whose
  // Bluetooth can't send 244 bytes at once (often iPads), each chunk is split across several writes.
  constructor(file, { send, sendFile, onProgress = () => {}, onEvent = () => {}, packetSize = CHUNK_SIZE, timeoutMs = 20000 }) {
    this.packetSize = Math.max(20, Math.min(CHUNK_SIZE, packetSize));
    this.onEvent = onEvent;
    this.lastChunk = -1;
    this.chunkCount = Math.ceil(file.length / CHUNK_SIZE);
    this.file = file;
    this.send = send;
    this.sendFile = sendFile;
    this.onProgress = onProgress;
    this.timeoutMs = timeoutMs;
    this.expected = 0;
    this.finished = false;
    this.done = new Promise((resolve, reject) => { this.resolve = resolve; this.reject = reject; });
  }

  async start() {
    this.armTimeout();
    await this.send(prepCommand(this.file.length));
    return this.done;
  }

  armTimeout() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.fail(new Error(this.lastChunk < 0
      ? "The watch stopped responding before asking for any data. Keep it close and awake, then try again."
      : `The watch stopped responding after chunk ${this.lastChunk + 1} of ${this.chunkCount}. Keep it close and awake, then try again.`)), this.timeoutMs);
  }

  fail(err) {
    if (this.finished) return;
    this.finished = true;
    clearTimeout(this.timer);
    this.reject(err);
  }

  async handle(bytes) {
    if (this.finished) return;
    try {
      if (startsWith(bytes, CHUNK_REQ) && bytes.length >= 7) {
        this.armTimeout();
        const n = (bytes[5] << 8) | bytes[6];
        const start = n * CHUNK_SIZE;
        if (start >= this.file.length) throw new Error(`The watch asked for chunk ${n}, past the end of the file.`);
        this.expected = n + 1;
        this.lastChunk = n;
        if (n === 0 || n % 200 === 0) this.onEvent(`watch asked for chunk ${n + 1} of ${this.chunkCount}`);
        const chunk = this.file.subarray(start, Math.min(start + CHUNK_SIZE, this.file.length));
        for (let i = 0; i < chunk.length; i += this.packetSize) await this.sendFile(chunk.subarray(i, i + this.packetSize));
        this.onProgress(Math.min(1, (start + CHUNK_SIZE) / this.file.length));
      } else if (startsWith(bytes, PREP) && bytes.length >= 9) {
        const checksum = ((bytes[5] << 24) | (bytes[6] << 16) | (bytes[7] << 8) | bytes[8]) >>> 0;
        this.finished = true;
        clearTimeout(this.timer);
        this.onEvent(`watch reports all data received (checksum ${checksum.toString(16)})`);
        await this.send(Uint8Array.from([...PREP, 0, 0, 0, 0]));
        await this.send(Uint8Array.from(SHOW_UPLOADED_FACE));
        this.onProgress(1);
        this.resolve(checksum);
      }
      // Anything else is unrelated fee3 traffic (activity updates etc.); ignore it.
    } catch (err) {
      this.fail(err);
    }
  }
}

// Basic sanity check before sending: MoYoung face files start with 0x04, 0x81 or 0x84.
export function looksLikeFaceFile(bytes) {
  return bytes.length > 64 && [0x04, 0x81, 0x84].includes(bytes[0]);
}
