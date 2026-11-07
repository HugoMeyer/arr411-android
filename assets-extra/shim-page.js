// Injected into C411 pages. Defines `chrome` for content.js (passed as a parameter, not a global)
// and hooks downloads so an "armed" .torrent goes to qBittorrent instead of the phone.
const chrome = (() => {
  if (window.__qbtChrome) return window.__qbtChrome;
  const N = window.QbtPage;
  const cbs = {};
  const listeners = [];
  let seq = 0;
  // Unique per page load: a late reply meant for the previous page must not resolve a new request.
  const load = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const nextId = (kind) => kind + load + '_' + (++seq);
  window.__qbtReply = (id, s) => { const cb = cbs[id]; delete cbs[id]; if (cb) cb(JSON.parse(s)); };
  window.__qbtEvent = (s) => { const m = JSON.parse(s); listeners.forEach((f) => f(m)); };

  const armed = () => (window.__qbtArmedUntil || 0) > Date.now();
  const handOff = (url, filename) => { window.__qbtArmedUntil = 0; N.download(url, filename || ''); };
  const isFile = (a) => a.hasAttribute('download') || a.href.startsWith('blob:') || /\.torrent(\?|$)/i.test(a.href);
  const grab = (a) => { if (!a.href || !armed() || !isFile(a)) return false; handOff(a.href, a.getAttribute('download')); return true; };

  const origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () { if (grab(this)) return; return origClick.call(this); };
  document.addEventListener('click', (e) => {
    const a = e.target && e.target.closest && e.target.closest('a[href]');
    if (a && grab(a)) { e.preventDefault(); e.stopPropagation(); }
  }, true);
  const origOpen = window.open;
  window.open = function (u, ...rest) {
    if (armed() && u) { handOff(new URL(u, location.href).href, ''); return null; }
    return origOpen.call(this, u, ...rest);
  };

  // Regular (non-qBittorrent) blob download: read it here and let the app save it.
  window.__qbtSaveBlob = async (url, name) => {
    try {
      const blob = await (await fetch(url)).blob();
      const bytes = new Uint8Array(await blob.arrayBuffer());
      let bin = '';
      for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      N.saveFile(name || 'fichier.torrent', btoa(bin), blob.type || 'application/x-bittorrent');
    } catch (e) {
      N.toast('Téléchargement impossible : ' + e.message);
    }
  };

  // content.js's fetch(url) of the .torrent: try the page first, then a native download
  // (cross-origin URLs fail in the page with "Failed to fetch").
  // Keep the Blob objects themselves: WebView may refuse to re-read a blob: URL later.
  const blobs = new Map();
  const origCreate = URL.createObjectURL;
  URL.createObjectURL = function (obj) {
    const u = origCreate.call(URL, obj);
    if (obj && typeof obj.arrayBuffer === 'function' && obj.size < 20 * 1024 * 1024) {
      blobs.set(u, obj);
      setTimeout(() => blobs.delete(u), 300000);
    }
    return u;
  };

  window.__qbtFetch = async (url, opts) => {
    const kept = blobs.get(String(url));
    if (kept) return new Response(kept, { status: 200 });
    try {
      return await window.fetch(url, opts);
    } catch (e) {
      const abs = new URL(url, location.href).href;
      if (!/^https?:/i.test(abs)) throw new Error('Fichier .torrent expiré, réessaie (' + e.message + ')');
      const r = await new Promise((resolve) => {
        const id = nextId('f');
        cbs[id] = resolve;
        N.fetchBytes(abs, location.href, id);
      });
      if (r.error) throw new Error('Téléchargement du .torrent impossible : ' + r.error);
      const bin = atob(r.base64);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return new Response(bytes, { status: r.status });
    }
  };

  const api = {
    runtime: {
      lastError: undefined,
      sendMessage(msg, cb) {
        if (msg.type === 'arm') window.__qbtArmedUntil = Date.now() + 20000;
        if (msg.type === 'disarm') window.__qbtArmedUntil = 0;
        const p = new Promise((resolve) => {
          const id = nextId('p');
          cbs[id] = resolve;
          N.send(JSON.stringify(msg), id);
        });
        if (cb) p.then(cb);
        return p;
      },
      onMessage: { addListener(f) { listeners.push(f); } }
    }
  };
  window.__qbtChrome = api;
  return api;
})();
// Shadows the global fetch for content.js inside the injected bundle.
const fetch = window.__qbtFetch;
