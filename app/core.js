/* Arr411 core: native bridge, API clients, UI helpers and navigation. */
(function () {
  'use strict';
  const App = (window.App = window.App || {});

  // ---------- Native bridge ----------
  function nat(name) {
    const N = window.Native;
    if (!N || typeof N[name] !== 'function') return undefined;
    try {
      return N[name].apply(N, Array.prototype.slice.call(arguments, 1));
    } catch (e) {
      console.error('Native.' + name, e);
      return undefined;
    }
  }
  App.nat = nat;

  const pendingHttp = new Map();
  let httpSeq = 0;
  window.__nativeDone = function (id, status, error) {
    const p = pendingHttp.get(id);
    const text = nat('take', id);
    if (!p) return;
    pendingHttp.delete(id);
    if (error) p.reject(mkErr(error, 0, 'network'));
    else p.resolve({ status: status, text: text == null ? '' : String(text) });
  };

  App.http = function (req) {
    if (!window.Native) {
      return fetch(req.url, {
        method: req.method || 'GET',
        headers: req.headers || {},
        body: req.body && req.body.type === 'text' ? req.body.text : undefined
      }).then((r) => r.text().then((text) => ({ status: r.status, text: text })), (e) => {
        throw mkErr('Réseau : ' + e.message, 0, 'network');
      });
    }
    return new Promise((resolve, reject) => {
      const id = 'h' + ++httpSeq;
      pendingHttp.set(id, { resolve: resolve, reject: reject });
      try {
        window.Native.http(JSON.stringify(req), id);
      } catch (e) {
        pendingHttp.delete(id);
        reject(e);
      }
    });
  };

  function mkErr(msg, status, code) {
    const e = new Error(msg);
    e.status = status || 0;
    if (code) e.code = code;
    return e;
  }
  App.mkErr = mkErr;

  function stripHtml(t) {
    return String(t || '').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
  }

  // ---------- Config & prefs ----------
  App.config = {};
  App.loadCfg = function () {
    let c = {};
    try {
      c = JSON.parse(nat('config') || '{}') || {};
    } catch (e) {
      c = {};
    }
    App.config = c;
    return c;
  };
  App.saveCfg = function (patch) {
    Object.assign(App.config, patch);
    nat('saveConfig', JSON.stringify(patch));
    App.emit('config', patch);
  };
  App.pref = function (k, d) {
    try {
      const v = localStorage.getItem('arr411.' + k);
      return v == null ? d : JSON.parse(v);
    } catch (e) {
      return d;
    }
  };
  App.setPref = function (k, v) {
    try {
      localStorage.setItem('arr411.' + k, JSON.stringify(v));
    } catch (e) { /* storage unavailable */ }
  };

  // ---------- Events ----------
  const listeners = {};
  App.on = function (ev, fn) {
    (listeners[ev] = listeners[ev] || []).push(fn);
    return () => {
      listeners[ev] = (listeners[ev] || []).filter((f) => f !== fn);
    };
  };
  App.emit = function (ev, data) {
    (listeners[ev] || []).slice().forEach((fn) => {
      try {
        fn(data);
      } catch (e) {
        console.error(e);
      }
    });
  };

  // ---------- Encoding helpers ----------
  function enc(s) {
    return encodeURIComponent(s == null ? '' : String(s));
  }
  function qs(o) {
    return Object.keys(o)
      .filter((k) => o[k] != null)
      .map((k) => enc(k) + '=' + enc(typeof o[k] === 'boolean' ? String(o[k]) : o[k]))
      .join('&');
  }
  App.qs = qs;
  App.cmpVer = function (a, b) {
    const pa = String(a || '0').split('.').map((x) => parseInt(x, 10) || 0);
    const pb = String(b || '0').split('.').map((x) => parseInt(x, 10) || 0);
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
      const d = (pa[i] || 0) - (pb[i] || 0);
      if (d) return d > 0 ? 1 : -1;
    }
    return 0;
  };
  App.cleanUrl = function (u) {
    u = String(u || '').trim().replace(/\/+$/, '');
    if (u && !/^https?:\/\//i.test(u)) u = 'https://' + u;
    return u;
  };

  // ---------- qBittorrent client ----------
  let qbLogin = null;
  const qb = (App.qb = {
    api: null,
    base() {
      return String(App.config.qbUrl || '').trim().replace(/\/+$/, '');
    },
    configured() {
      return /^https?:\/\/./i.test(qb.base());
    },
    login() {
      if (!qbLogin) {
        qbLogin = (async () => {
          const c = App.config;
          const r = await App.http({
            url: qb.base() + '/api/v2/auth/login',
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: { type: 'text', text: qs({ username: c.username || '', password: c.password || '' }) }
          });
          const t = (r.text || '').trim();
          // Recent qBittorrent answers 204 with an empty body; older versions answer 200 "Ok." / "Fails.".
          if (r.status === 204 || (r.status === 200 && /^ok/i.test(t))) return;
          if (r.status === 403) throw mkErr('qBittorrent : IP bannie après trop d’échecs de connexion', 403);
          if (r.status === 401 || (r.status === 200 && /^fails/i.test(t))) throw mkErr('qBittorrent : identifiants refusés (' + r.status + ' ' + stripHtml(t).slice(0, 60) + ')', 401);
          // Neither accepted nor refused (proxy page, 5xx...): status 0 so the refresh loop keeps retrying.
          throw mkErr('qBittorrent : réponse inattendue à la connexion (HTTP ' + r.status + ')', 0, 'unexpected');
        })().finally(() => {
          qbLogin = null;
        });
      }
      return qbLogin;
    },
    async call(path, o) {
      o = o || {};
      if (!qb.configured()) throw mkErr('qBittorrent n’est pas configuré', 0, 'config');
      const req = {
        url: qb.base() + '/api/v2' + path + (o.query ? '?' + qs(o.query) : ''),
        method: o.method || (o.form || o.parts ? 'POST' : 'GET'),
        headers: {}
      };
      if (o.form) {
        req.headers['Content-Type'] = 'application/x-www-form-urlencoded';
        req.body = { type: 'text', text: qs(o.form) };
      } else if (o.parts) {
        req.body = { type: 'multipart', parts: o.parts };
      }
      let r = await App.http(req);
      if ((r.status === 401 || r.status === 403) && !o.noRetry) {
        await qb.login();
        r = await App.http(req);
      }
      if (r.status < 200 || r.status >= 300) {
        let t = stripHtml(r.text).slice(0, 160);
        if (r.status === 404) t = 'fonction indisponible (' + path + ')';
        else if (r.status === 415) t = 'fichier torrent invalide';
        throw mkErr('qBittorrent : ' + (t || 'HTTP ' + r.status), r.status);
      }
      return r.text;
    },
    async json(path, o) {
      const t = await qb.call(path, o);
      try {
        return JSON.parse(t);
      } catch (e) {
        throw mkErr('qBittorrent : réponse inattendue');
      }
    },
    post(path, form) {
      return qb.call(path, { form: form || {} });
    },
    async version() {
      if (qb.api == null) {
        try {
          qb.api = (await qb.call('/app/webapiVersion')).trim();
        } catch (e) {
          if (e.code === 'network' || e.status === 401) throw e;
          qb.api = '';
        }
      }
      return qb.api;
    },
    isV5() {
      return App.cmpVer(qb.api || '0', '2.11') >= 0;
    },
    // qBittorrent 5 renamed pause/resume to stop/start.
    async pauseResume(hashes, pause) {
      await qb.version().catch(() => '');
      const names = pause ? ['stop', 'pause'] : ['start', 'resume'];
      if (!qb.isV5()) names.reverse();
      try {
        await qb.post('/torrents/' + names[0], { hashes: hashes });
      } catch (e) {
        if (e.status !== 404) throw e;
        await qb.post('/torrents/' + names[1], { hashes: hashes });
      }
    }
  });
  App.on('config', (p) => {
    if ('qbUrl' in p || 'username' in p || 'password' in p) qb.api = null;
  });

  // ---------- Radarr / Sonarr / Lidarr client ----------
  const ARR_NAME = { radarr: 'Radarr', sonarr: 'Sonarr', lidarr: 'Lidarr' };
  function arrErrText(r) {
    let t = r.text || '';
    try {
      const j = JSON.parse(t);
      if (Array.isArray(j)) t = j.map((x) => x.errorMessage || x.message || '').filter(Boolean).join(' · ');
      else if (j && typeof j === 'object') t = j.message || j.error || j.title || '';
      else t = String(j);
    } catch (e) {
      t = stripHtml(t);
    }
    if (r.status === 401) return 'clé API refusée (401)';
    return (t || 'HTTP ' + r.status).slice(0, 240);
  }
  App.arrName = (svc) => ARR_NAME[svc];
  App.arr = function (svc) {
    const c = App.config;
    const base = String(c[svc + 'Url'] || '').trim().replace(/\/+$/, '');
    const key = String(c[svc + 'Key'] || '').trim();
    const name = ARR_NAME[svc];
    const ver = svc === 'lidarr' ? '/api/v1' : '/api/v3';
    async function call(method, path, query, body) {
      if (!base || !key) throw mkErr(name + ' n’est pas configuré', 0, 'config');
      const req = {
        url: base + ver + path + (query ? '?' + qs(query) : ''),
        method: method,
        headers: { 'X-Api-Key': key, Accept: 'application/json' }
      };
      if (body !== undefined) {
        req.headers['Content-Type'] = 'application/json';
        req.body = { type: 'text', text: JSON.stringify(body) };
      }
      const r = await App.http(req);
      if (r.status < 200 || r.status >= 300) throw mkErr(name + ' : ' + arrErrText(r), r.status);
      if (!r.text) return null;
      try {
        return JSON.parse(r.text);
      } catch (e) {
        if (/^\s*</.test(r.text)) throw mkErr(name + ' : réponse HTML inattendue (URL incorrecte ?)', r.status);
        return r.text;
      }
    }
    return {
      svc: svc,
      name: name,
      base: base,
      key: key,
      ver: ver,
      configured: !!(base && key),
      get: (p, q) => call('GET', p, q),
      post: (p, b, q) => call('POST', p, q, b === undefined ? {} : b),
      put: (p, b, q) => call('PUT', p, q, b),
      del: (p, q, b) => call('DELETE', p, q, b),
      command: (cmd, extra) => call('POST', '/command', null, Object.assign({ name: cmd }, extra || {}))
    };
  };

  // ---------- DOM helpers ----------
  const PROPS = { value: 1, checked: 1, selected: 1, indeterminate: 1, textContent: 1 };
  function appendKids(el, kids) {
    for (const k of kids) {
      if (k == null || k === false || k === true) continue;
      if (Array.isArray(k)) appendKids(el, k);
      else el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
    }
  }
  function h(sel, attrs) {
    const kids = Array.prototype.slice.call(arguments, 2);
    let tag = 'div';
    let id = null;
    const classes = [];
    sel.replace(/([#.]?)([^#.]+)/g, (m, p, v) => {
      if (p === '#') id = v;
      else if (p === '.') classes.push(v);
      else tag = v;
      return '';
    });
    const el = document.createElement(tag);
    if (id) el.id = id;
    if (classes.length) el.className = classes.join(' ');
    if (attrs != null && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) {
      kids.unshift(attrs);
      attrs = null;
    }
    const props = [];
    if (attrs) {
      for (const k in attrs) {
        const v = attrs[k];
        if (v == null || v === false) continue;
        if (k === 'class') el.className = (el.className ? el.className + ' ' : '') + v;
        else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
        else if (k.slice(0, 2) === 'on' && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else if (PROPS[k]) props.push([k, v]);
        else el.setAttribute(k, v === true ? '' : v);
      }
    }
    appendKids(el, kids);
    for (const p of props) el[p[0]] = p[1];
    return el;
  }
  App.h = h;
  App.append = (el, ...kids) => {
    appendKids(el, kids);
    return el;
  };
  App.clear = (el) => {
    while (el.firstChild) el.removeChild(el.firstChild);
    return el;
  };
  App.fill = (el, ...kids) => {
    App.clear(el);
    appendKids(el, kids);
    return el;
  };

  const SVGNS = 'http://www.w3.org/2000/svg';
  function icon(name, cls) {
    const s = document.createElementNS(SVGNS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('fill', 'none');
    s.setAttribute('stroke', 'currentColor');
    s.setAttribute('stroke-width', '1.6');
    s.setAttribute('class', 'ic' + (cls ? ' ' + cls : ''));
    s.setAttribute('aria-hidden', 'true');
    for (const d of (window.ICONS && window.ICONS[name]) || []) {
      const p = document.createElementNS(SVGNS, 'path');
      p.setAttribute('d', d);
      p.setAttribute('stroke-linecap', 'round');
      p.setAttribute('stroke-linejoin', 'round');
      s.appendChild(p);
    }
    return s;
  }
  App.icon = icon;

  // ---------- Accessibility helpers ----------
  let uid = 0;
  const uniqueId = (prefix) => 'a11y-' + prefix + ++uid;

  // A non-button element that acts like one: Enter activates on keydown and Space on keyup, as for a real button.
  // Only a key pressed on the element itself counts, so typing in a control nested inside it still works.
  function pressKey(e, el, down) {
    if (e.target !== el || e.ctrlKey || e.metaKey || e.altKey) return;
    const enter = e.key === 'Enter';
    if (!enter && e.key !== ' ' && e.key !== 'Spacebar') return;
    e.preventDefault(); // Space would scroll the page
    if (enter ? down && !e.repeat : !down) el.click();
  }
  // o: { label (aria-label), expanded (initial aria-expanded), pressed (initial aria-pressed) }. fn is the click handler, if the element has none yet.
  App.pressable = function (el, fn, o) {
    o = o || {};
    el.setAttribute('role', 'button');
    el.setAttribute('tabindex', '0');
    if (o.label) el.setAttribute('aria-label', o.label);
    if (o.expanded != null) el.setAttribute('aria-expanded', o.expanded ? 'true' : 'false');
    if (o.pressed != null) el.setAttribute('aria-pressed', o.pressed ? 'true' : 'false');
    if (fn) el.addEventListener('click', fn);
    el.addEventListener('keydown', (e) => pressKey(e, el, true));
    el.addEventListener('keyup', (e) => pressKey(e, el, false));
    return el;
  };
  // Same for a long list, with one pair of listeners instead of one per row: the rows only carry role="button" and tabindex="0".
  App.pressableIn = function (container) {
    const on = (down) => (e) => {
      const el = e.target;
      if (el && el !== container && el.getAttribute && el.getAttribute('role') === 'button') pressKey(e, el, down);
    };
    container.addEventListener('keydown', on(true));
    container.addEventListener('keyup', on(false));
    return container;
  };

  // Scrolling by code is motion too: with "remove animations" (prefers-reduced-motion) it jumps, the CSS rule alone cannot tell it to.
  App.scrollBehavior = () => (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth');

  // ---- Keyboard focus ----
  // On a touch screen a text field that takes focus opens the soft keyboard, so focus is never moved into one on its own there.
  const coarse = () => !!(window.matchMedia && window.matchMedia('(pointer: coarse)').matches);
  function isTextEntry(el) {
    return el.localName === 'textarea' || (el.localName === 'input' && /^(text|search|email|url|tel|password|number)$/i.test(el.getAttribute('type') || 'text'));
  }
  function canFocus(el) {
    const tag = el.localName;
    if (!tag || el.disabled) return false;
    const ti = el.getAttribute('tabindex');
    if (ti != null && ti !== '' && +ti < 0) return false;
    if (tag === 'input') return el.getAttribute('type') !== 'hidden';
    if (tag === 'button' || tag === 'select' || tag === 'textarea') return true;
    if (tag === 'a') return el.getAttribute('href') != null;
    return ti != null && ti !== '';
  }
  // Rendered and not visibility:hidden (checkVisibility is missing from older WebViews, hence the fallback).
  const shown = (el) =>
    typeof el.checkVisibility === 'function'
      ? el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true })
      : typeof el.getClientRects !== 'function' || el.getClientRects().length > 0;
  // First (or last, in document order) element below `root` that the keyboard can reach.
  function edge(root, last, noText) {
    const kids = root.children;
    const n = kids ? kids.length : 0;
    const ok = (c) => canFocus(c) && shown(c) && !(noText && isTextEntry(c));
    for (let k = 0; k < n; k++) {
      const c = kids[last ? n - 1 - k : k];
      if (!last && ok(c)) return c;
      const d = edge(c, last, noText);
      if (d) return d;
      if (last && ok(c)) return c;
    }
    return null;
  }
  function focusEl(el) {
    if (!el || typeof el.focus !== 'function') return false;
    try {
      el.focus({ preventScroll: true });
    } catch (e) {
      return false;
    }
    return document.activeElement === el;
  }
  const nothingFocused = () => !document.activeElement || document.activeElement === document.body || document.activeElement === document.documentElement;
  // The title is how a page is announced when it opens. It takes focus from script only, never from Tab.
  function focusTitle(page) {
    const el = page && (page.titleEl || page.el);
    if (!el) return false;
    if (el.getAttribute('tabindex') == null) el.setAttribute('tabindex', '-1');
    return focusEl(el);
  }
  // Re-sorted rows leave the document for a moment, which drops keyboard focus: the returned function hands it back to the same element.
  App.holdFocus = function (root) {
    const a = document.activeElement;
    const keep = a && a !== document.body && root.contains(a) ? a : null;
    return () => {
      if (keep && document.activeElement !== keep) focusEl(keep);
    };
  };
  // Same for a list rebuilt from scratch: focus goes back to whatever now sits at the same position.
  App.keepPlace = function (container) {
    const at = Array.prototype.indexOf.call(container.children, document.activeElement);
    return () => {
      const el = at >= 0 ? container.children[at] : null;
      if (el && document.activeElement !== el) focusEl(el);
    };
  };

  App.run = function (fn, btn) {
    let r;
    try {
      r = fn();
    } catch (e) {
      App.err(e);
      return;
    }
    if (r && typeof r.then === 'function') {
      if (btn) btn.classList.add('busy');
      r.catch(App.err).then(() => {
        if (btn) btn.classList.remove('busy');
      });
    }
  };

  App.btn = function (label, o) {
    o = o || {};
    const cls = o.cls ? '.' + o.cls.trim().split(/\s+/).join('.') : '';
    const named = label != null && label !== '';
    // An icon-only button has no text to be named from.
    const b = h('button.btn' + cls, { type: 'button', disabled: !!o.disabled, title: o.title, 'aria-label': o.ariaLabel || (named ? null : o.title) }, o.icon ? icon(o.icon) : null, named ? h('span', label) : null);
    if (o.onClick) {
      b.addEventListener('click', (e) => {
        e.stopPropagation();
        if (!b.classList.contains('busy')) App.run(() => o.onClick(b, e), b);
      });
    }
    return b;
  };
  App.iconBtn = function (name, label, onClick, cls) {
    const b = h('button.icon-btn' + (cls ? '.' + cls.trim().split(/\s+/).join('.') : ''), { type: 'button', 'aria-label': label, title: label }, icon(name));
    if (onClick) {
      b.addEventListener('click', (e) => {
        e.stopPropagation();
        App.run(() => onClick(b, e));
      });
    }
    return b;
  };
  App.actBtn = function (ic, label, fn, danger) {
    const b = h('button.act' + (danger ? '.danger' : ''), { type: 'button' }, icon(ic), h('span', label));
    b.addEventListener('click', () => App.run(fn));
    return b;
  };
  App.input = function (o) {
    o = Object.assign({ type: 'text', autocomplete: 'off', autocapitalize: 'off', autocorrect: 'off', spellcheck: 'false' }, o || {});
    if (o.ariaLabel) o['aria-label'] = o.ariaLabel;
    delete o.ariaLabel;
    return h('input.input', o);
  };
  App.select = function (options, value, o) {
    const s = h('select.input', o || {});
    for (const op of options) s.appendChild(h('option', { value: String(op.value) }, op.label));
    s.value = value == null ? '' : String(value);
    if (s.selectedIndex < 0 && s.options.length) s.selectedIndex = 0;
    return s;
  };
  // The control a label stands for: the element itself or, for a wrapper, the first input, select or textarea inside it.
  function controlOf(el) {
    if (!el || el.localName === undefined) return null;
    const tag = el.localName;
    if (tag === 'select' || tag === 'textarea') return el;
    if (tag === 'input') return el.getAttribute('type') === 'hidden' ? null : el;
    const kids = el.children || [];
    for (let i = 0; i < kids.length; i++) {
      const c = controlOf(kids[i]);
      if (c) return c;
    }
    return null;
  }
  // Ties a label (and a hint) to its control. App.field does it for the usual markup; a field built by hand calls it directly.
  App.tie = function (lab, ctl, hintEl) {
    if (!ctl.id) ctl.id = uniqueId('f');
    if (lab) lab.setAttribute('for', ctl.id);
    if (hintEl) {
      if (!hintEl.id) hintEl.id = uniqueId('h');
      const prev = ctl.getAttribute('aria-describedby');
      ctl.setAttribute('aria-describedby', prev ? prev + ' ' + hintEl.id : hintEl.id);
    }
  };
  // Ties the label (and the hint) to the control. A field without a single control, such as a list of chips, becomes a named group.
  App.field = function (label, input, hint) {
    const lab = label ? h('label', label) : null;
    const hintEl = hint ? h('div.hint', hint) : null;
    const f = h('div.field', lab, input, hintEl);
    const ctl = controlOf(input);
    if (ctl) App.tie(lab, ctl, hintEl);
    else if (lab) {
      lab.id = uniqueId('l');
      f.setAttribute('role', 'group');
      f.setAttribute('aria-labelledby', lab.id);
    }
    return f;
  };
  // The label is read as the name and the sub-text as the description, not both as one long name. label.switch and .lbl > span stay as they are (Desktop hides a switch by its caption).
  App.switchEl = function (label, checked, onChange, sub) {
    const inp = h('input', { type: 'checkbox', role: 'switch', checked: !!checked });
    if (onChange) inp.addEventListener('change', () => App.run(() => onChange(inp.checked, inp)));
    const capId = uniqueId('s');
    inp.setAttribute('aria-labelledby', capId);
    let subEl = null;
    if (sub) {
      const subId = uniqueId('d');
      subEl = h('span.sub#' + subId, sub);
      inp.setAttribute('aria-describedby', subId);
    }
    const el = h('label.switch', h('span.lbl', h('span#' + capId, label), subEl), inp);
    el.input = inp;
    return el;
  };
  App.checkRow = function (label, checked, onChange) {
    const inp = h('input.cb', { type: 'checkbox', checked: !!checked });
    if (onChange) inp.addEventListener('change', () => onChange(inp.checked));
    const el = h('label.check-row', inp, h('span', label));
    el.input = inp;
    return el;
  };
  // The selected look of a chip or segment and its aria-pressed always change together.
  App.setOn = function (el, on) {
    el.classList.toggle('on', !!on);
    const v = on ? 'true' : 'false';
    if (el.getAttribute('aria-pressed') !== v) el.setAttribute('aria-pressed', v);
    return el;
  };
  App.seg = function (items, value, onChange) {
    const el = h('div.seg');
    const btns = items.map((it) => {
      const b = h('button', { type: 'button' }, it.label);
      b.addEventListener('click', () => {
        if (el.value === it.value) return;
        set(it.value);
        if (onChange) onChange(it.value);
      });
      el.appendChild(b);
      return b;
    });
    function set(v) {
      el.value = v;
      items.forEach((it, i) => App.setOn(btns[i], it.value === v));
    }
    set(value);
    el.set = set;
    return el;
  };
  App.badge = function (text, color, ic) {
    return h('span.badge' + (color ? '.' + color : ''), ic ? icon(ic) : null, text);
  };
  // With a label the bar is a progressbar of its own. Without one it only repeats a figure written next to it, so screen readers skip it.
  App.bar = function (frac, color, label) {
    const pct = (f) => Math.max(0, Math.min(100, (f || 0) * 100));
    const i = h('i', { style: { width: pct(frac).toFixed(1) + '%' } });
    const el = h('div.bar', i);
    if (color) el.style.setProperty('--c', color);
    if (label) {
      el.setAttribute('role', 'progressbar');
      el.setAttribute('aria-valuemin', '0');
      el.setAttribute('aria-valuemax', '100');
      el.setAttribute('aria-valuenow', String(Math.round(pct(frac))));
      el.setAttribute('aria-label', label);
    } else {
      el.setAttribute('aria-hidden', 'true');
    }
    el.set = (f) => {
      const p = pct(f);
      i.style.width = p.toFixed(1) + '%';
      if (label) el.setAttribute('aria-valuenow', String(Math.round(p)));
    };
    return el;
  };
  App.empty = function (ic, title, sub, actions) {
    return h('div.empty', icon(ic), h('div.empty-t', title), sub ? h('div.empty-s', sub) : null, actions && actions.length ? h('div.btn-row', actions) : null);
  };
  App.loading = () => h('div.loading', h('div.spin'));
  App.secTitle = function (text, right) {
    return h('div.section-title', h('span', { role: 'heading', 'aria-level': '2' }, text), h('span.grow'), right || null);
  };
  App.kv = function (pairs) {
    const dl = h('dl.kv');
    for (const p of pairs) {
      if (!p || p[1] == null || p[1] === '') continue;
      dl.appendChild(h('dt', p[0]));
      dl.appendChild(h('dd.sel', p[1]));
    }
    return dl;
  };
  App.note = function (text, kind, ic) {
    return h('div.note' + (kind ? '.' + kind : ''), icon(ic || (kind === 'err' ? 'exclamation-triangle' : kind === 'warn' ? 'exclamation-triangle' : 'information-circle')), h('div', text));
  };
  App.flag = function (cc) {
    cc = String(cc || '').toUpperCase();
    if (!/^[A-Z]{2}$/.test(cc)) return '🏳';
    return String.fromCodePoint(0x1f1e6 + cc.charCodeAt(0) - 65, 0x1f1e6 + cc.charCodeAt(1) - 65);
  };

  // ---------- Formatting ----------
  const UNITS = ['o', 'Ko', 'Mo', 'Go', 'To', 'Po'];
  function toDate(v) {
    if (v == null || v === '' || v === -1) return null;
    if (v instanceof Date) return v;
    if (typeof v === 'number') return v > 0 ? new Date(v < 1e12 ? v * 1000 : v) : null;
    const d = new Date(v);
    return isNaN(d) || d.getFullYear() < 1971 ? null : d;
  }
  const fmt = (App.fmt = {
    toDate: toDate,
    bytes(n) {
      n = +n || 0;
      if (n < 0) n = 0;
      let i = 0;
      while (n >= 1024 && i < UNITS.length - 1) {
        n /= 1024;
        i++;
      }
      return (i === 0 ? String(Math.round(n)) : n.toFixed(1)) + ' ' + UNITS[i];
    },
    speed(n) {
      return fmt.bytes(n) + '/s';
    },
    pct(p) {
      p = Math.max(0, Math.min(1, +p || 0));
      return (p >= 1 ? '100' : p === 0 ? '0' : (Math.floor(p * 1000) / 10).toFixed(1)) + '%';
    },
    dur(s) {
      s = Math.round(+s);
      if (!isFinite(s) || s < 0 || s >= 8640000) return '∞';
      const d = Math.floor(s / 86400);
      const hh = Math.floor((s % 86400) / 3600);
      const m = Math.floor((s % 3600) / 60);
      if (d) return d + ' j' + (hh ? ' ' + hh + ' h' : '');
      if (hh) return hh + ' h ' + String(m).padStart(2, '0');
      if (m) return m + ' min' + (m < 10 && s % 60 ? ' ' + (s % 60) + ' s' : '');
      return s + ' s';
    },
    runtime(min) {
      min = +min || 0;
      if (!min) return '';
      return min >= 60 ? Math.floor(min / 60) + ' h ' + String(min % 60).padStart(2, '0') : min + ' min';
    },
    date(v) {
      const d = toDate(v);
      return d ? d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' }) : '';
    },
    dateTime(v) {
      const d = toDate(v);
      if (!d) return '';
      const sameYear = d.getFullYear() === new Date().getFullYear();
      return d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: sameYear ? undefined : 'numeric' }) + ' ' + fmt.time(d);
    },
    time(v) {
      const d = toDate(v);
      return d ? d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : '';
    },
    day(v) {
      const d = toDate(v);
      return d ? d.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' }) : '';
    },
    rel(v) {
      const d = toDate(v);
      if (!d) return '';
      const s = (d.getTime() - Date.now()) / 1000;
      const a = Math.abs(s);
      let t;
      if (a < 60) return 'à l’instant';
      if (a < 3600) t = Math.round(a / 60) + ' min';
      else if (a < 86400) t = Math.round(a / 3600) + ' h';
      else if (a < 86400 * 30) t = Math.round(a / 86400) + ' j';
      else if (a < 86400 * 365) t = Math.round(a / (86400 * 30)) + ' mois';
      else t = Math.round(a / (86400 * 365)) + ' an' + (Math.round(a / (86400 * 365)) > 1 ? 's' : '');
      return s < 0 ? 'il y a ' + t : 'dans ' + t;
    },
    num(n) {
      return (+n || 0).toLocaleString('fr-FR');
    },
    ratio(r) {
      r = +r;
      if (!isFinite(r) || r < 0 || r > 1e4) return '∞';
      return r.toFixed(2);
    },
    plural(n, one, many) {
      return fmt.num(n) + ' ' + (Math.abs(n) > 1 ? many || one + 's' : one);
    }
  });
  App.norm = function (s) {
    return String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  };
  // One shared collator: building one per comparison (localeCompare with options) dominates the cost of sorting thousands of names.
  App.collator = new Intl.Collator('fr', { sensitivity: 'base', numeric: true });
  App.trimSlash = (p) => String(p || '').replace(/[\\/]+$/, '');
  App.parentOf = (p) => App.trimSlash(p).replace(/[\\/][^\\/]*$/, '');
  App.rootOf = (x) => App.trimSlash(x.rootFolderPath || App.parentOf(x.path));
  App.rootOptions = (roots) => roots.map((r) => ({ value: App.trimSlash(r.path), label: App.trimSlash(r.path) + (r.freeSpace ? ' · ' + fmt.bytes(r.freeSpace) + ' libres' : '') }));
  // Keys that would reach Object.prototype (or swap an object's prototype) when copied from remote data into a plain object.
  App.unsafeKey = (k) => k === '__proto__' || k === 'constructor' || k === 'prototype';
  // Object.assign when no unsafe key is an own property of src (the usual case, and a native fast path), else a filtered copy.
  App.safeAssign = function (dst, src) {
    if (!src) return dst;
    const has = Object.prototype.hasOwnProperty;
    if (!has.call(src, '__proto__') && !has.call(src, 'constructor') && !has.call(src, 'prototype')) return Object.assign(dst, src);
    for (const k in src) if (!App.unsafeKey(k)) dst[k] = src[k];
    return dst;
  };
  App.debounce = function (fn, ms) {
    let t = null;
    return function () {
      const args = arguments;
      clearTimeout(t);
      t = setTimeout(() => fn.apply(null, args), ms);
    };
  };
  App.sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // ---------- Toasts & errors ----------
  // A toast is brief and visual. Screen readers get its text once, from a live region that outlives it, so the toast itself is hidden from them.
  // The region is created when the page does not carry it. Texts are queued: two toasts in the same tick must both be read, and clearing
  // the region before filling it makes a repeated message sound again.
  const live = { alert: { q: [], n: 0 }, status: { q: [], n: 0 } };
  function liveRegion(urgent) {
    const id = urgent ? 'sr-alert' : 'sr-status';
    let el = document.getElementById(id);
    if (!el && document.body) {
      el = h('div#' + id + '.sr-only', { role: urgent ? 'alert' : 'status', 'aria-live': urgent ? 'assertive' : 'polite', 'aria-atomic': 'true' });
      document.body.appendChild(el);
    }
    return el;
  }
  function announce(msg, urgent) {
    const el = liveRegion(urgent);
    if (!el) return;
    const st = live[urgent ? 'alert' : 'status'];
    st.q.push(String(msg));
    if (st.q.length > 1) return;
    el.textContent = '';
    setTimeout(() => {
      el.textContent = st.q.splice(0).join('. ');
      const n = ++st.n;
      setTimeout(() => {
        if (n === st.n) el.textContent = '';
      }, 7000);
    }, 0);
  }
  App.toast = function (msg, type) {
    announce(msg, type === 'err');
    const box = document.getElementById('toasts');
    if (!box) return;
    const el = h('div.toast' + (type ? '.' + type : ''), { 'aria-hidden': 'true' }, icon(type === 'err' ? 'exclamation-triangle' : type === 'ok' ? 'check-circle' : 'information-circle'), h('span', String(msg)));
    box.appendChild(el);
    while (box.children.length > 3) box.removeChild(box.firstChild);
    el.getBoundingClientRect();
    el.classList.add('in');
    setTimeout(() => {
      el.classList.remove('in');
      setTimeout(() => el.remove(), 250);
    }, type === 'err' ? 4800 : 2600);
  };
  App.err = function (e) {
    if (!e) return;
    console.error(e);
    App.toast(e.message || String(e), 'err');
  };
  // The outcome of a search (a count, or the Error it ended with) is already on screen: screen readers get it too, with no toast.
  App.sayResults = (r) => {
    if (r instanceof Error) return announce(r.message, true);
    const n = typeof r === 'number' ? r : r ? r.length : 0;
    announce(n ? fmt.plural(n, 'résultat') : 'Aucun résultat', false);
  };

  // ---------- Overlays ----------
  const overlays = [];
  const entryOf = new WeakMap(); // .overlay element -> its entry
  const topEntry = () => {
    for (let i = overlays.length - 1; i >= 0; i--) if (overlays[i].tab === App.state.tab) return overlays[i];
    return null;
  };
  // While a dialog is open, what lies behind it is out of reach of keyboard, pointer and screen reader. This is computed from the stack on every
  // change, so a closed dialog, closeOverlays() or a tab switch can never leave the page inert.
  function syncModal() {
    const top = topEntry();
    const tabs = document.getElementById('tabs');
    if (tabs && !!tabs.inert !== !!top) tabs.inert = !!top;
    for (const e of overlays) if (!!e.ov.inert !== (e !== top)) e.ov.inert = e !== top;
  }
  // Keyboard focus goes back to the control that opened the dialog or, failing that, to the title of the page underneath. It never throws.
  function restoreFocus(entry) {
    try {
      if (entry.tab !== App.state.tab) return;
      if (!nothingFocused() && !entry.ov.contains(document.activeElement)) return; // focus has already moved on
      let t = entry.opener;
      // The opener may sit in a dialog that closed along with this one (closeOverlays): go back to where that one came from.
      for (let i = 0; i < 8 && t; i++) {
        let up = t;
        let e;
        while (up && !(e = entryOf.get(up))) up = up.parentNode;
        if (!e || !e.closed) break;
        t = e.opener;
      }
      if (t && !(coarse() && isTextEntry(t)) && focusEl(t)) return;
      const tab = App.tabs[App.state.tab];
      focusTitle(tab && tab.stack[tab.stack.length - 1]);
    } catch (e) {
      // Focus is a courtesy: closing the dialog must not depend on it.
    }
  }
  function focusInitial(entry, o) {
    let t = typeof o.initialFocus === 'function' ? o.initialFocus() : o.initialFocus;
    if (t === 'dialog') t = entry.dlg;
    if (!focusEl(t)) focusEl(edge(entry.dlg, false, coarse()) || entry.dlg);
  }
  // One document-level listener, installed with the first dialog: Escape closes the top dialog of the visible tab and Tab stays inside it.
  let keysOn = false;
  function onKey(e) {
    if (e.defaultPrevented) return;
    const top = topEntry();
    if (!top) return;
    if (e.key === 'Escape') {
      if (top.dismiss && !e.isComposing) {
        e.preventDefault();
        top.close();
      }
    } else if (e.key === 'Tab' && !e.ctrlKey && !e.altKey && !e.metaKey) {
      const first = edge(top.dlg, false);
      const last = edge(top.dlg, true);
      const act = document.activeElement;
      if (!first) {
        e.preventDefault();
        focusEl(top.dlg);
      } else if (!act || !top.dlg.contains(act) || (e.shiftKey ? act === first || act === top.dlg : act === last)) {
        e.preventDefault();
        focusEl(e.shiftKey ? last : first);
      }
    }
  }
  // o: { onClose, dismiss (false: neither backdrop nor Escape closes it), initialFocus (element, function returning one, or 'dialog') }.
  function openOverlay(content, o) {
    o = o || {};
    const opener = nothingFocused() ? null : document.activeElement;
    const backdrop = h('div.backdrop');
    const ov = h('div.overlay', backdrop, content);
    document.getElementById('overlays').appendChild(ov);
    const entry = { ov: ov, dlg: content, tab: App.state.tab, closed: false, dismiss: o.dismiss !== false, opener: opener };
    entryOf.set(ov, entry);
    entry.close = function () {
      if (entry.closed) return;
      entry.closed = true;
      const i = overlays.indexOf(entry);
      if (i >= 0) overlays.splice(i, 1);
      ov.classList.remove('open');
      ov.style.pointerEvents = 'none';
      ov.inert = true;
      setTimeout(() => ov.remove(), 260);
      syncModal();
      restoreFocus(entry);
      if (o.onClose) {
        try {
          o.onClose();
        } catch (e) {
          console.error(e);
        }
      }
    };
    if (entry.dismiss) backdrop.addEventListener('click', () => entry.close());
    overlays.push(entry);
    syncModal();
    ov.getBoundingClientRect();
    ov.classList.add('open');
    if (!keysOn && document.addEventListener) {
      keysOn = true;
      document.addEventListener('keydown', onKey);
    }
    focusInitial(entry, o);
    return entry;
  }
  App.closeOverlays = function () {
    overlays.slice().forEach((e) => e.close());
  };

  App.sheet = function (o) {
    o = o || {};
    const titleEl = h('h3#' + uniqueId('t'), { 'aria-level': '2' }, o.title || '');
    const body = h('div.sheet-body');
    const actions = o.actions ? h('div.sheet-actions') : null;
    let s;
    const closeBtn = App.iconBtn('x-mark', 'Fermer', () => s.close());
    // A modal dialog named by its title. Without a title, it is named by o.ariaLabel and the empty heading is hidden.
    const el = h(
      'div.sheet' + (o.full ? '.full' : '') + (o.cls ? '.' + o.cls : ''),
      { role: 'dialog', 'aria-modal': 'true', tabindex: '-1' },
      h('div.grip'),
      h('div.sheet-head', titleEl, o.headActions || null, closeBtn),
      body,
      actions
    );
    const name = (t) => {
      if (t) {
        el.setAttribute('aria-labelledby', titleEl.id);
        el.removeAttribute('aria-label');
        titleEl.removeAttribute('aria-hidden');
      } else {
        el.removeAttribute('aria-labelledby');
        el.setAttribute('aria-label', o.ariaLabel || 'Fenêtre');
        titleEl.setAttribute('aria-hidden', 'true');
      }
    };
    name(o.title);
    if (o.body) appendKids(body, [o.body]);
    if (actions) appendKids(actions, [o.actions]);
    // Focus starts on the first control of the content, else of the actions, else on the close button.
    const first = () => edge(body, false, coarse()) || (actions && edge(actions, false)) || closeBtn;
    s = openOverlay(el, Object.assign({}, o, { initialFocus: o.initialFocus || first }));
    s.el = el;
    s.body = body;
    s.actionsEl = actions;
    s.setTitle = (t) => {
      titleEl.textContent = t;
      name(t);
    };
    return s;
  };

  App.menu = function (o) {
    const m = h('div.menu');
    let s = null;
    let current = null;
    // App.menuItems (settings.js) may add an entry, e.g. the update notice above « Réglages ».
    const items = (App.menuItems ? App.menuItems(o.items || []) : o.items || []).filter(Boolean);
    const hasIcons = items.some((it) => it !== '-' && it.icon);
    for (const it of items) {
      if (it === '-') {
        m.appendChild(h('div.menu-sep'));
        continue;
      }
      let lead = null;
      if (it.icon) lead = icon(it.icon);
      else if (o.checks || !hasIcons) lead = it.on ? icon('check') : h('span', { style: { width: '22px', flex: 'none' } });
      const b = h(
        'button.menu-item' + (it.danger ? '.danger' : '') + (it.on ? '.on' : ''),
        { type: 'button', disabled: !!it.disabled, 'aria-current': it.on ? 'true' : null },
        lead,
        h('span.mi-text', h('span', it.label), it.sub ? h('span.sub', it.sub) : null),
        it.on && it.icon ? icon('check', 'sm') : null,
        it.right || null
      );
      if (it.on && !current) current = b;
      b.addEventListener('click', () => {
        s.close();
        if (it.onClick) App.run(it.onClick);
      });
      m.appendChild(b);
    }
    // The check mark is only drawn, so the current choice is also exposed as such; keyboard focus starts on it.
    s = App.sheet({ title: o.title, body: m, onClose: o.onClose, initialFocus: current });
    return s;
  };

  App.pick = function (o) {
    return new Promise((resolve) => {
      App.menu({
        title: o.title,
        checks: true,
        items: o.options.map((op) =>
          op === '-' ? '-' : { label: op.label, sub: op.sub, icon: op.icon, on: o.value !== undefined && op.value === o.value, danger: op.danger, onClick: () => resolve(op.value) }
        ),
        onClose: () => setTimeout(() => resolve(undefined), 0)
      });
    });
  };

  App.confirm = function (o) {
    return new Promise((resolve) => {
      const vals = {};
      let done = false;
      const body = [];
      if (o.message) body.push(h('p.muted.wrap', o.message));
      for (const c of o.checks || []) {
        vals[c.key] = !!c.checked;
        body.push(App.checkRow(c.label, c.checked, (v) => {
          vals[c.key] = v;
        }));
      }
      let s = null;
      const cancel = App.btn('Annuler', { onClick: () => s.close() });
      const ok = App.btn(o.ok || 'Confirmer', {
        cls: o.danger ? 'danger-solid' : 'pri',
        onClick: () => {
          done = true;
          s.close();
          resolve(vals);
        }
      });
      // A destructive action is never what a stray Enter or Space should trigger, so focus starts on « Annuler ».
      s = App.sheet({
        title: o.title,
        body: body,
        actions: [cancel, ok],
        initialFocus: o.danger ? cancel : null,
        onClose: () => {
          if (!done) resolve(false);
        }
      });
    });
  };

  App.prompt = function (o) {
    return new Promise((resolve) => {
      let done = false;
      const inp = o.multiline
        ? h('textarea.input', { placeholder: o.placeholder || '', autocapitalize: 'off', spellcheck: 'false', value: o.value || '' })
        : App.input({ type: o.type || 'text', placeholder: o.placeholder || '', value: o.value == null ? '' : String(o.value), inputmode: o.inputmode });
      let s = null;
      const submit = () => {
        done = true;
        const v = inp.value;
        s.close();
        resolve(v);
      };
      if (!o.multiline) {
        inp.addEventListener('keydown', (e) => {
          if (e.key === 'Enter') submit();
        });
      }
      if (!o.label) inp.setAttribute('aria-label', o.placeholder || o.title || 'Saisie');
      // The dialog is announced first; the field takes focus once the sheet has slid in, as before.
      s = App.sheet({
        title: o.title,
        body: [o.message ? h('p.muted.wrap', o.message) : null, App.field(o.label, inp, o.hint)],
        actions: [App.btn('Annuler', { onClick: () => s.close() }), App.btn(o.ok || 'OK', { cls: 'pri', onClick: submit })],
        initialFocus: 'dialog',
        onClose: () => {
          if (!done) resolve(null);
        }
      });
      setTimeout(() => {
        if (s.closed) return;
        inp.focus();
        if (inp.select && o.selectAll !== false) inp.select();
      }, 260);
    });
  };

  // ---------- Tabs & page stacks ----------
  // Everything the user reads or operates lives in #tabs: it is the main landmark of the page.
  const tabsRoot = document.getElementById('tabs');
  if (tabsRoot) tabsRoot.setAttribute('role', 'main');
  App.state = { tab: 'c411', fg: true, prevTab: 'c411' };
  App.tabs = {};
  App.registerTab = function (name, el, hooks) {
    App.tabs[name] = { name: name, el: el, stack: [], hooks: hooks || {} };
    el.classList.add('hidden');
  };
  App.setTabHooks = function (name, hooks) {
    Object.assign(App.tabs[name].hooks, hooks);
  };
  App.showTab = function (name) {
    if (name !== App.state.tab) App.state.prevTab = App.state.tab;
    App.state.tab = name;
    for (const k in App.tabs) App.tabs[k].el.classList.toggle('hidden', k !== name);
    for (const e of overlays) e.ov.style.visibility = e.tab === name ? '' : 'hidden';
    syncModal();
    // Back on a tab whose dialog is still open: keyboard focus belongs in it.
    const dlg = topEntry();
    if (dlg && !dlg.ov.contains(document.activeElement)) focusEl(dlg.dlg);
    const t = App.tabs[name];
    if (t && t.hooks.onShow) {
      try {
        t.hooks.onShow();
      } catch (e) {
        console.error(e);
      }
    }
    const top = t && t.stack[t.stack.length - 1];
    if (top && top.onShow) top.onShow();
    App.emit('visibility', name);
    return true;
  };
  // Switches the SPA tab immediately, then lets Android update its bottom bar / visible WebView.
  App.goTab = function (name) {
    App.showTab(name);
    nat('selectTab', name);
  };
  App.openC411 = function (url) {
    if (window.Native && typeof window.Native.openC411 === 'function') {
      App.showTab('c411');
      nat('openC411', url);
    } else window.open(url, '_blank');
  };
  App.c411Search = function (q) {
    const terms = App.norm(q)
      .replace(/['’`]/g, ' ')
      .replace(/[^\p{L}\p{N}]+/gu, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    App.openC411('https://c411.org/torrents?q=' + encodeURIComponent(terms) + '&sortBy=relevance');
  };
  // Only web and magnet links leave the app: the URL often comes from a server's data (links, wiki, tracker pages).
  App.openExternal = function (url) {
    if (!url) return;
    let u = null;
    try {
      u = new URL(String(url).trim());
    } catch (e) {}
    if (!u || (u.protocol !== 'https:' && u.protocol !== 'http:' && u.protocol !== 'magnet:')) {
      App.toast('Lien non pris en charge (http, https ou magnet uniquement)', 'err');
      return;
    }
    if (window.Native && typeof window.Native.openUrl === 'function') nat('openUrl', u.href);
    else window.open(u.href, '_blank', 'noopener,noreferrer');
  };
  App.copy = function (text, label) {
    text = String(text == null ? '' : text);
    if (window.Native && typeof window.Native.copy === 'function') nat('copy', text);
    else if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {});
    App.toast((label || 'Texte') + ' copié', 'ok');
  };
  App.haptic = () => nat('haptic');

  App.page = function (o) {
    o = o || {};
    // The page title is the level-1 heading of the screen, and what is announced when the page opens.
    const titleEl = h('div.title', { role: 'heading', 'aria-level': '1', tabindex: '-1' }, o.title || '');
    const subEl = h('div.subtitle', o.subtitle || '');
    const actions = h('div.head-actions');
    const backBtn = o.back
      ? App.iconBtn('chevron-left', 'Retour', () => {
          if (o.onBack) o.onBack();
          else App.back();
        })
      : null;
    const head = h('div.head' + (o.back ? '.has-back' : ''), backBtn, h('div.head-text', titleEl, subEl), actions);
    const el = h('div.page', head);
    if (o.actions) appendKids(actions, [o.actions]);
    return {
      el: el,
      head: head,
      actions: actions,
      titleEl: titleEl,
      setTitle(t) {
        titleEl.textContent = t || '';
      },
      // A details page shows its title in the header only once scrolled: this is the name screen readers get meanwhile.
      setName(t) {
        if (t) titleEl.setAttribute('aria-label', t);
        else titleEl.removeAttribute('aria-label');
      },
      setSub(t) {
        subEl.textContent = t || '';
      }
    };
  };
  // Keyboard focus follows navigation: a page opens on its title (before its own onShow, which may focus something better), and going back
  // returns to the control that led there.
  App.push = function (tabName, page) {
    const t = App.tabs[tabName];
    const prev = t.stack[t.stack.length - 1];
    if (prev) {
      prev.lastFocus = !nothingFocused() && prev.el.contains(document.activeElement) ? document.activeElement : null;
      prev.el.classList.add('hidden');
      if (prev.onHide) prev.onHide();
    }
    page.tab = tabName;
    t.stack.push(page);
    if (t.stack.length > 1) page.el.classList.add('enter');
    t.el.appendChild(page.el);
    page.el.getBoundingClientRect();
    page.el.classList.remove('enter');
    if (prev && App.state.tab === tabName) focusTitle(page);
    if (page.onShow && App.state.tab === tabName) page.onShow();
    return page;
  };
  App.pop = function (tabName) {
    const t = App.tabs[tabName || App.state.tab];
    if (!t || t.stack.length <= 1) return false;
    const p = t.stack.pop();
    const hadFocus = nothingFocused() || p.el.contains(document.activeElement);
    if (p.onHide) p.onHide();
    if (p.onDestroy) p.onDestroy();
    p.el.remove();
    const top = t.stack[t.stack.length - 1];
    top.el.classList.remove('hidden');
    if (hadFocus && t.name === App.state.tab) {
      const back = top.lastFocus;
      top.lastFocus = null;
      if (!(back && back.isConnected !== false && !(coarse() && isTextEntry(back)) && focusEl(back))) focusTitle(top);
    }
    if (top.onShow) top.onShow();
    App.emit('visibility', t.name);
    return true;
  };
  App.popToRoot = function (tabName) {
    while (App.pop(tabName)) { /* unwind */ }
  };
  App.isTop = function (page) {
    const t = App.tabs[page.tab];
    return !!t && App.state.fg && App.state.tab === page.tab && t.stack[t.stack.length - 1] === page;
  };
  // Back button: closes the top overlay of the visible tab, then pops a page. Returns true when handled.
  App.back = function () {
    const top = topEntry();
    if (top) {
      top.close();
      return true;
    }
    if (App.pop(App.state.tab)) return true;
    const t = App.tabs[App.state.tab];
    if (t && t.hooks.onBack) return !!t.hooks.onBack();
    return false;
  };
  App.setForeground = function (fg) {
    App.state.fg = !!fg;
    App.emit('visibility', App.state.tab);
  };
  App.visible = (tab) => App.state.fg && App.state.tab === tab;

  // Repeating task whose delay depends on visibility (null = paused).
  App.loop = function (fn, delayFn) {
    let timer = null;
    let running = false;
    let stopped = false;
    let again = false;
    async function tick() {
      timer = null;
      if (stopped) return;
      if (running) {
        again = true;
        return;
      }
      running = true;
      try {
        await fn();
      } catch (e) {
        console.error(e);
      }
      running = false;
      if (again) {
        again = false;
        return tick();
      }
      schedule();
    }
    function schedule() {
      if (stopped || timer || running) return;
      const d = delayFn();
      if (d == null) return;
      timer = setTimeout(tick, d);
    }
    function kick() {
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      tick();
    }
    const off = App.on('visibility', () => {
      const d = delayFn();
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      if (d != null && d <= 5000) tick();
      else schedule();
    });
    return {
      kick: kick,
      schedule: schedule,
      stop() {
        stopped = true;
        clearTimeout(timer);
        off();
      }
    };
  };

  // ---------- Gestures ----------
  App.longPress = function (el, fn) {
    let timer = null;
    let sx = 0;
    let sy = 0;
    let fired = false;
    el.addEventListener('touchstart', (e) => {
      fired = false;
      const p = e.touches[0];
      sx = p.clientX;
      sy = p.clientY;
      clearTimeout(timer);
      timer = setTimeout(() => {
        timer = null;
        fired = true;
        App.haptic();
        fn(e);
      }, 480);
    }, { passive: true });
    el.addEventListener('touchmove', (e) => {
      if (!timer) return;
      const p = e.touches[0];
      if (Math.abs(p.clientX - sx) > 10 || Math.abs(p.clientY - sy) > 10) {
        clearTimeout(timer);
        timer = null;
      }
    }, { passive: true });
    el.addEventListener('touchend', () => {
      clearTimeout(timer);
      timer = null;
    });
    el.addEventListener('touchcancel', () => {
      clearTimeout(timer);
      timer = null;
    });
    el.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      if (fired) return;
      if (timer) {
        clearTimeout(timer);
        timer = null;
        fired = true;
      }
      fn(e);
    });
    el.addEventListener('click', (e) => {
      if (fired) {
        fired = false;
        e.stopImmediatePropagation();
        e.preventDefault();
      }
    }, true);
  };

  // ---------- Images ----------
  App.img = function (srcs) {
    srcs = (srcs || []).filter(Boolean);
    const img = h('img', { alt: '', loading: 'lazy', decoding: 'async', referrerpolicy: 'no-referrer' });
    let i = 0;
    img.addEventListener('error', () => {
      i++;
      if (i < srcs.length) img.src = srcs[i];
      else img.classList.add('broken');
    });
    if (srcs.length) img.src = srcs[0];
    else img.classList.add('broken');
    return img;
  };

  // Lazily renders a long list in chunks as the user scrolls.
  App.chunked = function (container, items, renderItem, chunk, root) {
    chunk = chunk || 60;
    let idx = 0;
    const sentinel = h('div', { style: { height: '1px' } });
    let io = null;
    function more() {
      const end = Math.min(items.length, idx + chunk);
      const frag = document.createDocumentFragment();
      for (; idx < end; idx++) frag.appendChild(renderItem(items[idx], idx));
      container.appendChild(frag);
      if (idx >= items.length) {
        if (io) io.disconnect();
        sentinel.remove();
      }
    }
    more();
    if (idx < items.length) {
      container.parentNode.insertBefore(sentinel, container.nextSibling);
      io = new IntersectionObserver((ents) => {
        if (ents.some((e) => e.isIntersecting)) more();
      }, { root: root || null, rootMargin: '600px' });
      io.observe(sentinel);
    }
    return {
      dispose() {
        if (io) io.disconnect();
        sentinel.remove();
      }
    };
  };
})();
