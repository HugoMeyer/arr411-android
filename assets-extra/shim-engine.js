// Runs in the hidden "engine" WebView before background.js: emulates the chrome.* APIs it uses.
// HTTP goes through the native proxy (no CORS, cookies kept natively, Origin/Referer set for qBittorrent).
(() => {
  const N = window.QbtEngine;
  const httpCbs = {};
  let seq = 0;
  window.__httpDone = (id, s) => { const cb = httpCbs[id]; delete httpCbs[id]; if (cb) cb(JSON.parse(s)); };

  async function blobToB64(blob) {
    const bytes = new Uint8Array(await blob.arrayBuffer());
    let bin = '';
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return btoa(bin);
  }

  async function encodeBody(body) {
    if (body == null) return { type: 'none' };
    if (typeof body === 'string') return { type: 'text', text: body, contentType: 'text/plain;charset=UTF-8' };
    if (body instanceof URLSearchParams) {
      return { type: 'text', text: body.toString(), contentType: 'application/x-www-form-urlencoded;charset=UTF-8' };
    }
    if (body instanceof FormData) {
      const parts = [];
      for (const [name, v] of body.entries()) {
        if (typeof v === 'string') parts.push({ name, value: v });
        else parts.push({ name, filename: v.name || 'blob', contentType: v.type || 'application/octet-stream', base64: await blobToB64(v) });
      }
      return { type: 'multipart', parts };
    }
    throw new TypeError('Unsupported body');
  }

  const asResponse = (r) => ({
    ok: r.status >= 200 && r.status < 300,
    status: r.status,
    text: async () => r.text,
    json: async () => JSON.parse(r.text)
  });
  // qBittorrent bans the IP after a few failed logins, and background.js logs in again on every
  // poll: a refused login is not retried with the same URL + credentials for 10 minutes.
  const refusedLogins = {};

  window.fetch = async (url, opts = {}) => {
    const headers = { ...(opts.headers || {}) };
    const body = await encodeBody(opts.body);
    if (body.contentType && !Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) {
      headers['Content-Type'] = body.contentType;
    }
    delete body.contentType;
    const loginKey = /\/api\/v2\/auth\/login$/.test(String(url)) ? String(url) + '\n' + (body.text || '') : '';
    const refused = loginKey && refusedLogins[loginKey];
    if (refused && Date.now() - refused.at < 600000) return asResponse(refused.r);
    const r = await new Promise((resolve) => {
      const id = 'h' + (++seq);
      httpCbs[id] = resolve;
      N.http(JSON.stringify({ url: String(url), method: opts.method || 'GET', headers, body }), id);
    });
    if (r.error) throw new TypeError(r.error);
    if (loginKey) {
      // Only a real refusal is remembered; success is 204 (recent qBittorrent) or 200 "Ok.".
      const refusedNow = r.status === 401 || r.status === 403 || (r.status === 200 && /^fails/i.test(String(r.text || '').trim()));
      if (refusedNow) refusedLogins[loginKey] = { at: Date.now(), r };
      else delete refusedLogins[loginKey];
    }
    return asResponse(r);
  };

  function pick(all, keys) {
    if (keys == null) return { ...all };
    const out = {};
    if (typeof keys === 'string') { if (keys in all) out[keys] = all[keys]; }
    else if (Array.isArray(keys)) keys.forEach((k) => { if (k in all) out[k] = all[k]; });
    else for (const k in keys) out[k] = k in all ? all[k] : keys[k];
    return out;
  }
  const area = (read, write) => ({
    get(keys, cb) { const r = pick(read(), keys); if (cb) cb(r); return Promise.resolve(r); },
    set(obj, cb) { write(obj); if (cb) cb(); return Promise.resolve(); }
  });
  let session = {};

  const alarms = {};
  const alarmListeners = [];
  let msgListener = null;
  let dlListener = null;
  let dlHandled = false;
  const noopEvent = { addListener() {} };

  window.chrome = {
    storage: {
      local: area(() => JSON.parse(N.storageGet()), (o) => N.storageSet(JSON.stringify(o))),
      session: area(() => session, (o) => { session = { ...session, ...o }; })
    },
    declarativeNetRequest: { updateSessionRules: () => Promise.resolve() },
    tabs: {
      TAB_ID_NONE: -1,
      create: ({ url }) => N.openUrl(url || ''),
      sendMessage: (tabId, msg) => { dlHandled = true; N.emit(JSON.stringify(msg)); }
    },
    downloads: {
      onDeterminingFilename: { addListener(f) { dlListener = f; } },
      cancel: (id, cb) => { if (cb) cb(); },
      erase: () => {}
    },
    alarms: {
      get: (name, cb) => cb(alarms[name]),
      create: (name, { periodInMinutes }) => {
        alarms[name] = { name, timer: setInterval(() => alarmListeners.forEach((f) => f({ name })), periodInMinutes * 60000) };
      },
      onAlarm: { addListener(f) { alarmListeners.push(f); } }
    },
    notifications: {
      create: (id, o) => N.notify(o.title || '', o.message || ''),
      clear: () => {},
      onClicked: noopEvent
    },
    runtime: {
      onInstalled: noopEvent,
      onStartup: noopEvent,
      onMessage: { addListener(f) { msgListener = f; } }
    }
  };

  window.__engineHandle = (id, raw) => {
    let done = false;
    const reply = (r) => { if (done) return; done = true; N.reply(id, JSON.stringify(r === undefined ? { ok: true } : r)); };
    let msg;
    try { msg = JSON.parse(raw); } catch (e) { reply({ ok: false, error: 'Message invalide' }); return; }
    if (msg.type === '__download') {
      dlHandled = false;
      if (dlListener) dlListener({ id: 0, url: msg.url, finalUrl: msg.url, filename: msg.filename || '' }, () => {});
      reply({ ok: true, handled: dlHandled });
      return;
    }
    if (!msgListener) { reply({ ok: false, error: 'Moteur non prêt' }); return; }
    msgListener(msg, { tab: { id: 0 } }, reply);
  };
})();
