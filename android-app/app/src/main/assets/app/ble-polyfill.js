// Web Bluetooth for the Watch Link Android app.
// The app injects this before the page's own scripts. It replaces navigator.bluetooth with an
// implementation backed by the app's Bluetooth service (window.NativeBle), which keeps the watch
// connected across page reloads and while the app is in the background.
(() => {
  if (!window.NativeBle) return;

  const full = u => {
    let s = (typeof u === "number" ? u.toString(16) : String(u)).toLowerCase().replace(/^0x/, "");
    if (/^[0-9a-f]{4}$/.test(s)) s = `0000${s}-0000-1000-8000-00805f9b34fb`;
    else if (/^[0-9a-f]{8}$/.test(s)) s = `${s}-0000-1000-8000-00805f9b34fb`;
    return s;
  };
  const fromB64 = b => Uint8Array.from(atob(b), c => c.charCodeAt(0));
  const toB64 = u8 => { let s = ""; for (const x of u8) s += String.fromCharCode(x); return btoa(s); };
  const toBytes = v => v instanceof ArrayBuffer ? new Uint8Array(v)
    : ArrayBuffer.isView(v) ? new Uint8Array(v.buffer, v.byteOffset, v.byteLength) : Uint8Array.from(v);
  const error = (name, message) => { const e = new Error(message); e.name = name; return e; };

  let seq = 0;
  const pending = new Map();
  const call = (method, args = {}) => new Promise((resolve, reject) => {
    const id = String(++seq);
    pending.set(id, { resolve, reject });
    window.NativeBle.call(method, JSON.stringify(args), id);
  });

  const devices = new Map();

  class NativeCharacteristic extends EventTarget {
    constructor(service, uuid, properties) {
      super();
      this.service = service; this.uuid = uuid; this.properties = properties; this.value = null;
    }
    get _args() { return { id: this.service.device.id, service: this.service.uuid, char: this.uuid }; }
    async readValue() {
      const bytes = fromB64(await call("read", this._args));
      this.value = new DataView(bytes.buffer);
      return this.value;
    }
    writeValueWithResponse(v) { return call("write", { ...this._args, value: toB64(toBytes(v)), withResponse: true }); }
    writeValueWithoutResponse(v) { return call("write", { ...this._args, value: toB64(toBytes(v)), withResponse: false }); }
    writeValue(v) { return this.properties.write ? this.writeValueWithResponse(v) : this.writeValueWithoutResponse(v); }
    async startNotifications() { await call("startNotifications", this._args); return this; }
    async stopNotifications() { await call("stopNotifications", this._args); return this; }
  }

  class NativeService {
    constructor(device, uuid) { this.device = device; this.uuid = uuid; this.isPrimary = true; this._chars = null; }
    async getCharacteristics(uuid) {
      if (!this._chars) {
        const list = await call("characteristics", { id: this.device.id, service: this.uuid });
        this._chars = list.map(c => this.device._char(this, full(c.uuid), c.properties));
      }
      return uuid ? this._chars.filter(c => c.uuid === full(uuid)) : this._chars;
    }
    async getCharacteristic(uuid) {
      const c = (await this.getCharacteristics()).find(c => c.uuid === full(uuid));
      if (!c) throw error("NotFoundError", `No characteristic ${full(uuid)}`);
      return c;
    }
  }

  class NativeDevice extends EventTarget {
    constructor({ id, name }) {
      super();
      this.id = id; this.name = name || null;
      this._services = new Map();   // uuid -> NativeService
      this._chars = new Map();      // "service|char" -> NativeCharacteristic (same object every time)
      const device = this;
      this.gatt = {
        device,
        get connected() { return window.NativeBle.isConnected(device.id); },
        async connect() { await call("connect", { id: device.id }); return device.gatt; },
        disconnect() { call("disconnect", { id: device.id }).catch(() => {}); },
        async getPrimaryServices(uuid) {
          const list = (await call("services", { id: device.id })).map(u => device._service(full(u)));
          return uuid ? list.filter(s => s.uuid === full(uuid)) : list;
        },
        async getPrimaryService(uuid) {
          const s = (await this.getPrimaryServices()).find(s => s.uuid === full(uuid));
          if (!s) throw error("NotFoundError", `No service ${full(uuid)}`);
          return s;
        },
      };
    }
    _service(uuid) {
      if (!this._services.has(uuid)) this._services.set(uuid, new NativeService(this, uuid));
      return this._services.get(uuid);
    }
    _char(service, uuid, properties) {
      const key = `${service.uuid}|${uuid}`;
      if (!this._chars.has(key)) this._chars.set(key, new NativeCharacteristic(service, uuid, properties));
      return this._chars.get(key);
    }
  }

  const deviceFor = info => {
    let d = devices.get(info.id);
    if (!d) { d = new NativeDevice(info); devices.set(info.id, d); }
    else if (info.name) d.name = info.name;
    return d;
  };

  window.__nativeBle = {
    resolve(id, json) {
      const p = pending.get(id); if (!p) return;
      pending.delete(id); p.resolve(JSON.parse(json));
    },
    reject(id, name, message) {
      const p = pending.get(id); if (!p) return;
      pending.delete(id); p.reject(error(name, message));
    },
    event(json) {
      const ev = JSON.parse(json);
      // Every native event also goes to the page as a "watchlink" window event (sync data, songs, connection).
      window.dispatchEvent(new CustomEvent("watchlink", { detail: ev }));
      if (ev.type === "app") return;
      const d = devices.get(ev.id); if (!d) return;
      if (ev.type === "notify") {
        const c = d._chars.get(`${full(ev.service)}|${full(ev.char)}`); if (!c) return;
        c.value = new DataView(fromB64(ev.value).buffer);
        c.dispatchEvent(new Event("characteristicvaluechanged"));
      } else if (ev.type === "disconnected") {
        d._services.clear(); d._chars.clear();   // services are rediscovered after reconnecting
        d.dispatchEvent(new Event("gattserverdisconnected"));
      }
    },
  };

  const bluetooth = {
    getAvailability: () => call("availability"),
    async requestDevice(options = {}) {
      const filters = (options.filters || []).map(f => ({
        ...(f.name ? { name: f.name } : {}),
        ...(f.namePrefix ? { namePrefix: f.namePrefix } : {}),
        ...(f.services ? { services: f.services.map(full) } : {}),
      }));
      return deviceFor(await call("requestDevice", { filters, acceptAllDevices: !!options.acceptAllDevices }));
    },
    async getDevices() { return (await call("getDevices")).map(deviceFor); },
  };
  Object.defineProperty(navigator, "bluetooth", { value: bluetooth, configurable: true });
  window.watchLinkApp = true;
  window.watchLinkMtu = () => call("mtu");   // bytes per write = MTU - 3
  window.watchLinkNative = (method, args) => call(method, args);   // the app's own features: sync, music, weather, Claude
})();
