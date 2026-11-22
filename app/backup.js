/* Arr411 backup: writes the settings to a JSON file (plain, or encrypted with a passphrase) and reads them back. */
(function () {
  'use strict';
  const App = window.App;
  const h = App.h;
  const B = (App.backup = {});
  const hasOwn = Object.prototype.hasOwnProperty;

  const FORMAT = 'arr411-settings';
  const VERSION = 1;
  const AAD = FORMAT + '/' + VERSION;
  const ITERATIONS = 600000; // OWASP 2023, PBKDF2-HMAC-SHA-256
  const MIN_ITER = 100000; // a crafted file must not make the device grind for minutes
  const MAX_ITER = 2000000;
  const MAX_FILE = 1048576;
  const MAX_VALUE = 65536; // JSON length of one setting
  const MAX_PREF = 16384;
  const MAX_PREFS = 200;
  const MAX_TEXT = 4096;
  const MAX_DEPTH = 5;
  const MIN_PASS = 8;
  const NAME_RE = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
  const PREF_RE = /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/;
  // Deliberately wider than the plain list: a secret nobody thought of must not leak into a plain export.
  const SECRET_RE = /(pass|password|passwd|key|token|secret|cookie|sid|auth|credentials?)s?$/i;
  const TABS = ['c411', 'radarr', 'sonarr', 'lidarr', 'qbit', 'last'];
  const BAD_FILE = 'Ce fichier n’est pas un fichier de réglages Arr411';
  const BAD_PASS = 'Phrase de passe incorrecte ou fichier abîmé';
  const UNSUPPORTED = 'Fichier non pris en charge';

  // Settings this app knows, with the types they may hold. A fresh install has an empty config (the engine applies its defaults when reading), so the current config alone cannot tell what is allowed.
  const SCHEMA = Object.create(null);
  const declare = (types, names) => names.split(' ').forEach((n) => (SCHEMA[n] = types));
  declare(['string'], 'qbUrl username password defaultCategory lastCategory startTab autoUpdate scorePreset');
  ['radarr', 'sonarr', 'lidarr'].forEach((s) => {
    declare(['string'], [s + 'Url', s + 'Key', s + 'Root', s + 'Category'].join(' '));
    declare(['string', 'number'], s + 'Profile');
  });
  declare(['string', 'number'], 'lidarrMetaProfile');
  declare(['boolean'], 'notifyEnabled notifyAll listBadges desktopMode');
  declare(['object'], 'scoreWeights pagePrefs dash');

  // ---------- Pure layer (no DOM) ----------
  const kind = (v) => (v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v);
  const KIND_FR = { string: 'texte', number: 'nombre', boolean: 'booléen', object: 'objet', array: 'liste', null: 'vide' };
  const isMap = (v) => !!v && typeof v === 'object' && !Array.isArray(v);
  const forbidden = (k) => App.unsafeKey(k) || k === 'tracked';
  const cut = (s) => (s.length > 64 ? s.slice(0, 64) + '…' : s);
  const fail = (msg) => Object.assign(new Error(msg), { backup: true });
  const refuse = (reason) => Object.assign(new Error(reason), { reason: reason });
  B.isSecret = (k) => SECRET_RE.test(String(k));

  // Deep copy of untrusted JSON: no prototype-reaching keys, bounded depth and text length, finite numbers.
  function clone(v, ctx, depth) {
    if (v === null) return null;
    switch (typeof v) {
      case 'string':
        if (v.length > MAX_TEXT) throw refuse('texte trop long (' + MAX_TEXT + ' caractères au plus)');
        return v;
      case 'boolean':
        return v;
      case 'number':
        if (!isFinite(v)) throw refuse('nombre non valide');
        return v;
      case 'object':
        break;
      default:
        throw refuse('type non valide');
    }
    if (depth > MAX_DEPTH) throw refuse('structure trop profonde (' + MAX_DEPTH + ' niveaux au plus)');
    if (Array.isArray(v)) return v.map((x) => clone(x, ctx, depth + 1));
    const out = {};
    for (const k of Object.keys(v)) {
      if (forbidden(k)) continue;
      if (ctx.dropSecrets && SECRET_RE.test(k)) {
        ctx.dropped.push(cut(k));
        continue;
      }
      out[k] = clone(v[k], ctx, depth + 1);
    }
    return out;
  }
  // `dropped` receives the nested names left out because they look like secrets.
  function copyValue(v, max, dropSecrets, dropped) {
    const c = clone(v, { dropSecrets: dropSecrets, dropped: dropped }, 1);
    if (JSON.stringify(c).length > max) throw refuse('valeur trop volumineuse (' + Math.round(max / 1024) + ' Ko au plus)');
    return c;
  }

  // Local display preferences (localStorage `arr411.*`, without the prefix). `storage` is injectable for the tests.
  function cleanPrefs(src, dropSecrets, report) {
    const out = {};
    if (!isMap(src)) return out;
    let n = 0;
    for (const k of Object.keys(src)) {
      if (!PREF_RE.test(k) || App.unsafeKey(k)) {
        report(k, 'nom non valide');
        continue;
      }
      if (dropSecrets && SECRET_RE.test(k)) {
        report(k, 'nom sensible');
        continue;
      }
      if (n >= MAX_PREFS) {
        report(k, 'trop de préférences (' + MAX_PREFS + ' au plus)');
        continue;
      }
      const dropped = [];
      try {
        out[k] = copyValue(src[k], MAX_PREF, dropSecrets, dropped);
        n++;
      } catch (e) {
        report(k, e.reason || 'valeur non valide');
        continue;
      }
      for (const d of dropped) report(k + ' › ' + d, 'nom sensible ignoré');
    }
    return out;
  }
  B.collectPrefs = function (storage) {
    const out = {};
    try {
      const st = storage || localStorage;
      const names = [];
      if (typeof st.length === 'number' && typeof st.key === 'function') for (let i = 0; i < st.length; i++) names.push(st.key(i));
      else names.push.apply(names, Object.keys(st));
      let n = 0;
      for (const full of names.filter((x) => typeof x === 'string' && x.indexOf('arr411.') === 0).sort()) {
        const k = full.slice(7);
        if (n >= MAX_PREFS) break;
        if (!PREF_RE.test(k) || App.unsafeKey(k)) continue;
        const raw = st.getItem(full);
        if (typeof raw !== 'string' || raw.length > MAX_PREF) continue;
        try {
          out[k] = JSON.parse(raw);
          n++;
        } catch (e) { /* not JSON: not one of ours */ }
      }
    } catch (e) { /* storage unavailable */ }
    return out;
  };

  // What an export carries: {config, prefs, omitted:[{key, reason}]}. Secrets only when `secrets` is true.
  function collect(cfg, prefs, secrets) {
    const config = {};
    const omitted = [];
    cfg = isMap(cfg) ? cfg : {};
    for (const k of Object.keys(cfg).sort()) {
      const v = cfg[k];
      if (v == null || forbidden(k) || k.charAt(0) === '_' || (!secrets && SECRET_RE.test(k))) continue;
      if (!NAME_RE.test(k)) {
        omitted.push({ key: cut(k), reason: 'nom non valide' });
        continue;
      }
      const dropped = [];
      try {
        config[k] = copyValue(v, MAX_VALUE, !secrets, dropped);
      } catch (e) {
        omitted.push({ key: cut(k), reason: e.reason || 'valeur non valide' });
        continue;
      }
      for (const d of dropped) omitted.push({ key: cut(k + ' › ' + d), reason: 'nom sensible, non exporté' });
    }
    const p = cleanPrefs(prefs, !secrets, (k, why) => omitted.push({ key: 'prefs.' + cut(k), reason: why }));
    return { config: config, prefs: p, omitted: omitted };
  }
  B.collect = collect;

  function fileName(now, encrypted) {
    const d = new Date(now == null ? Date.now() : now);
    const p2 = (n) => String(n).padStart(2, '0');
    return 'arr411-reglages-' + d.getFullYear() + '-' + p2(d.getMonth() + 1) + '-' + p2(d.getDate()) + (encrypted ? '-chiffre' : '') + '.json';
  }
  B.fileName = fileName;

  // ----- Encryption: PBKDF2-HMAC-SHA-256 -> AES-256-GCM, the key never extractable -----
  const utf8 = (s) => new TextEncoder().encode(s);
  function toB64(bytes) {
    let s = '';
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(s);
  }
  function fromB64(s) {
    if (typeof s !== 'string' || s.length % 4 || !/^[A-Za-z0-9+\/]*={0,2}$/.test(s)) throw fail(UNSUPPORTED);
    const bin = atob(s);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function subtle() {
    if (typeof crypto === 'undefined' || !crypto.subtle) throw fail('Le chiffrement n’est pas disponible sur cet appareil');
    return crypto.subtle;
  }
  async function deriveKey(pass, salt, iterations) {
    const base = await subtle().importKey('raw', utf8(String(pass).normalize('NFC')), 'PBKDF2', false, ['deriveKey']);
    return subtle().deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt: salt, iterations: iterations }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  }
  B.passProblem = function (a, b) {
    if (Array.from(String(a == null ? '' : a)).length < MIN_PASS) return 'La phrase de passe doit faire au moins ' + MIN_PASS + ' caractères';
    if (a !== b) return 'Les deux phrases de passe sont différentes';
    return null;
  };
  B.encrypt = async function (text, pass, iterations) {
    iterations = iterations || ITERATIONS;
    const bad = B.passProblem(pass, pass);
    if (bad) throw fail(bad);
    if (!Number.isInteger(iterations) || iterations < MIN_ITER || iterations > MAX_ITER) throw fail(UNSUPPORTED);
    const sub = subtle();
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const key = await deriveKey(pass, salt, iterations);
    const data = new Uint8Array(await sub.encrypt({ name: 'AES-GCM', iv: iv, additionalData: utf8(AAD) }, key, utf8(text)));
    return {
      kdf: { name: 'PBKDF2', hash: 'SHA-256', iterations: iterations, salt: toB64(salt) },
      cipher: { name: 'AES-GCM', iv: toB64(iv) },
      data: toB64(data)
    };
  };
  // `crypt` is the checked block from parse(). A wrong passphrase and a damaged file are the same failure (GCM authentication).
  B.decrypt = async function (crypt, pass) {
    const wrong = () => Object.assign(fail(BAD_PASS), { badPass: true });
    if (!pass) throw wrong();
    const sub = subtle();
    try {
      const key = await deriveKey(pass, crypt.salt, crypt.iterations);
      const plain = await sub.decrypt({ name: 'AES-GCM', iv: crypt.iv, additionalData: utf8(AAD) }, key, crypt.data);
      return new TextDecoder('utf-8', { fatal: true }).decode(plain);
    } catch (e) {
      throw wrong();
    }
  };

  // ----- File -----
  // Builds the export: {file, text, name, omitted, config, prefs}. `o`: secrets, passphrase, iterations, app, now.
  B.makeFile = async function (cfg, prefs, o) {
    o = o || {};
    const secrets = !!o.secrets;
    const c = collect(cfg, prefs, secrets);
    const file = {
      format: FORMAT,
      version: VERSION,
      app: String(o.app || '').slice(0, 40),
      exportedAt: new Date(o.now == null ? Date.now() : o.now).toISOString(),
      secrets: secrets,
      encrypted: secrets
    };
    if (secrets) Object.assign(file, await B.encrypt(JSON.stringify({ config: c.config, prefs: c.prefs }), o.passphrase, o.iterations));
    else {
      file.config = c.config;
      file.prefs = c.prefs;
    }
    return { file: file, text: JSON.stringify(file, null, 2), name: fileName(o.now, secrets), omitted: c.omitted, config: c.config, prefs: c.prefs };
  };

  // Checks the envelope of a file's text. Encrypted files come back with a `crypt` block (decoded) for read().
  B.parse = function (text) {
    if (typeof text !== 'string' || text.length > MAX_FILE) throw fail('Fichier trop volumineux (1 Mo maximum)');
    let f = null;
    try {
      f = JSON.parse(text);
    } catch (e) { /* not JSON */ }
    if (!isMap(f) || f.format !== FORMAT) throw fail(BAD_FILE);
    if (f.version !== VERSION) throw fail(typeof f.version === 'number' && f.version > VERSION ? 'Fichier créé par une version plus récente d’Arr411 : mets l’app à jour pour l’importer' : BAD_FILE);
    if ((f.encrypted !== true && f.encrypted !== false) || f.secrets !== f.encrypted) throw fail(UNSUPPORTED);
    const head = {
      encrypted: f.encrypted,
      secrets: f.secrets,
      app: typeof f.app === 'string' ? f.app.slice(0, 40) : '',
      exportedAt: typeof f.exportedAt === 'string' ? f.exportedAt.slice(0, 40) : ''
    };
    if (!f.encrypted) {
      if (!isMap(f.config) || (f.prefs != null && !isMap(f.prefs))) throw fail(BAD_FILE);
      head.config = f.config;
      head.prefs = f.prefs || {};
      return head;
    }
    const kdf = f.kdf;
    const cipher = f.cipher;
    if (!isMap(kdf) || !isMap(cipher) || kdf.name !== 'PBKDF2' || kdf.hash !== 'SHA-256' || cipher.name !== 'AES-GCM') throw fail(UNSUPPORTED);
    if (!Number.isInteger(kdf.iterations) || kdf.iterations < MIN_ITER || kdf.iterations > MAX_ITER) throw fail(UNSUPPORTED);
    const crypt = { iterations: kdf.iterations, salt: fromB64(kdf.salt), iv: fromB64(cipher.iv), data: fromB64(f.data) };
    if (crypt.salt.length !== 16 || crypt.iv.length !== 12 || crypt.data.length < 16) throw fail(UNSUPPORTED);
    head.crypt = crypt;
    return head;
  };

  // The content of a parsed file: {secrets, config, prefs}. Asks nothing: an encrypted file needs `pass`.
  B.read = async function (file, pass) {
    if (!file.encrypted) return { secrets: false, config: file.config, prefs: file.prefs };
    let c = null;
    const text = await B.decrypt(file.crypt, pass);
    try {
      c = JSON.parse(text);
    } catch (e) { /* checked below */ }
    if (!isMap(c) || !isMap(c.config) || (c.prefs != null && !isMap(c.prefs))) throw fail(UNSUPPORTED);
    return { secrets: true, config: c.config, prefs: c.prefs || {} };
  };

  // ----- Import validation -----
  function goodUrl(v) {
    if (v === '') return true;
    try {
      const u = new URL(v);
      return (u.protocol === 'http:' || u.protocol === 'https:') && !u.username && !u.password;
    } catch (e) {
      return false;
    }
  }
  // The rules that go beyond the type of a setting; returns the reason it is refused, or null.
  function badValue(k, v) {
    if (/Url$/.test(k)) return goodUrl(v) ? null : 'adresse non valide (http ou https, sans identifiants)';
    if (k === 'autoUpdate') return v === 'auto' || v === 'notify' || v === 'off' ? null : 'mode inconnu';
    if (k === 'startTab') return TABS.indexOf(v) >= 0 ? null : 'onglet inconnu';
    if (k === 'scorePreset') return /^[a-z][a-z0-9_-]{0,31}$/.test(v) ? null : 'préréglage non valide';
    if (k === 'scoreWeights') return Object.keys(v).every((w) => typeof v[w] === 'number' && v[w] >= 0 && v[w] <= 100) ? null : 'pondérations non valides (nombres de 0 à 100)';
    return null;
  }
  // `content` comes from read(); `current` is the config in use. Returns what to apply and what was left out.
  B.validate = function (content, current) {
    current = isMap(current) ? current : {};
    const secrets = !!content.secrets;
    const patch = {};
    const skipped = [];
    let secretsReplaced = false;
    for (const k of Object.keys(content.config)) {
      const skip = (why) => skipped.push({ key: cut(k), reason: why });
      if (k === 'tracked') {
        skip('géré par l’app, jamais importé');
        continue;
      }
      if (!NAME_RE.test(k) || forbidden(k)) {
        skip('nom de réglage non valide');
        continue;
      }
      const secret = SECRET_RE.test(k);
      if (secret && !secrets) {
        skip('mot de passe ou clé : ignoré dans un fichier non chiffré');
        continue;
      }
      let types = SCHEMA[k];
      if (!types && hasOwn.call(current, k)) types = current[k] == null ? [] : [kind(current[k])];
      if (!types) {
        skip('inconnu de cette version');
        continue;
      }
      const dropped = [];
      let v;
      try {
        v = copyValue(content.config[k], MAX_VALUE, !secrets, dropped);
      } catch (e) {
        skip(e.reason || 'valeur non valide');
        continue;
      }
      const t = kind(v);
      if (types.length && types.indexOf(t) < 0) {
        skip('type inattendu (' + KIND_FR[t] + ' au lieu de ' + types.map((x) => KIND_FR[x]).join(' ou ') + ')');
        continue;
      }
      const bad = badValue(k, v);
      if (bad) {
        skip(bad);
        continue;
      }
      patch[k] = v;
      if (secret) secretsReplaced = true;
      for (const d of dropped) skipped.push({ key: cut(k + ' › ' + d), reason: 'nom sensible ignoré dans un fichier non chiffré' });
    }
    const prefs = cleanPrefs(content.prefs, !secrets, (k, why) => skipped.push({ key: 'prefs.' + cut(k), reason: why }));
    return { patch: patch, skipped: skipped, secretsReplaced: secretsReplaced, prefs: prefs };
  };

  // ----- Import preview (plain data; the UI turns it into nodes) -----
  const SECTIONS = [
    ['qBittorrent', (k) => ['qbUrl', 'username', 'password', 'defaultCategory', 'lastCategory'].indexOf(k) >= 0],
    ['Radarr', (k) => k.indexOf('radarr') === 0],
    ['Sonarr', (k) => k.indexOf('sonarr') === 0],
    ['Lidarr', (k) => k.indexOf('lidarr') === 0],
    ['Notifications', (k) => k === 'notifyEnabled' || k === 'notifyAll'],
    ['Score des torrents', (k) => k === 'scorePreset' || k === 'scoreWeights'],
    ['Mises à jour', (k) => k === 'autoUpdate'],
    ['Tableau de bord', (k) => k === 'dash']
  ];
  const URL_LABEL = { qbUrl: 'qBittorrent', radarrUrl: 'Radarr', sonarrUrl: 'Sonarr', lidarrUrl: 'Lidarr' };
  function hostOf(u) {
    if (!u) return '';
    try {
      return new URL(u).host;
    } catch (e) {
      return '?';
    }
  }
  B.summarize = function (res, file, current) {
    current = isMap(current) ? current : {};
    const counts = SECTIONS.map(() => 0);
    let others = 0;
    for (const k of Object.keys(res.patch)) {
      const i = SECTIONS.findIndex((s) => s[1](k));
      if (i < 0) others++;
      else counts[i]++;
    }
    const nPrefs = Object.keys(res.prefs).length;
    const sections = SECTIONS.map((s, i) => ({ label: s[0], count: counts[i] }));
    sections.push({ label: 'Affichage (préférences locales)', count: nPrefs }, { label: 'Autres', count: others });
    // Only the host is shown (never the path or the query), and only when it changes.
    const urls = [];
    for (const k of Object.keys(res.patch)) {
      if (!/Url$/.test(k)) continue;
      const from = hostOf(typeof current[k] === 'string' ? current[k] : '');
      const to = hostOf(res.patch[k]);
      if (from !== to) urls.push({ label: URL_LABEL[k] || k, from: from, to: to });
    }
    return {
      app: (file && file.app) || '',
      exportedAt: (file && file.exportedAt) || '',
      settings: Object.keys(res.patch).length,
      prefs: nPrefs,
      sections: sections.filter((s) => s.count > 0),
      secrets: res.secretsReplaced ? 'replaced' : 'kept',
      urls: urls,
      // The current secrets stay in place and will be sent to the new addresses.
      urlWarn: !res.secretsReplaced && urls.some((u) => u.to !== ''),
      skipped: res.skipped.slice(0, 10),
      skippedMore: Math.max(0, res.skipped.length - 10)
    };
  };

  // ---------- UI ----------
  // Every failure ends as a French toast, never as an uncaught error.
  function guard(fn) {
    return async function () {
      try {
        return await fn.apply(null, arguments);
      } catch (e) {
        if (!e || !e.backup) console.error(e);
        App.toast(e && e.backup ? e.message : 'Échec inattendu : ' + ((e && e.message) || e), 'err');
      }
    };
  }
  function reload() {
    if (window.Native && typeof window.Native.config === 'function') App.loadCfg();
    return App.config || {};
  }
  // Text typed in a settings field but not saved yet belongs to the settings too.
  function snapshot() {
    const S = App.settings;
    if (S && typeof S.flushForms === 'function') S.flushForms();
    return reload();
  }

  B.canSave = () => !window.Native || typeof window.Native.saveText === 'function';
  function download(res, sheet) {
    const url = URL.createObjectURL(new Blob([res.text], { type: 'application/json' }));
    const a = h('a', { href: url, download: res.name, hidden: true });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 4000);
    App.toast('Fichier téléchargé', 'ok');
    sheet.close();
  }
  // Android answers true or false at once and toasts by itself; Desktop answers undefined once its save dialog is open.
  async function saveFile(res, sheet) {
    const N = window.Native;
    if (!N) return download(res, sheet);
    let r;
    try {
      r = await N.saveText(res.name, res.text);
    } catch (e) {
      r = false;
    }
    if (r === false) throw fail('Enregistrement impossible : utilise « Copier »');
    if (r === true) sheet.close();
  }

  function openExport() {
    const cfg0 = snapshot();
    const prefs0 = B.collectPrefs();
    const mode = App.seg([{ value: 'plain', label: 'Sans mots de passe' }, { value: 'full', label: 'Complet (chiffré)' }], 'plain', () => sync());
    const desc = h('p.bk-desc.muted.wrap');
    const pass1 = App.input({ type: 'password', autocomplete: 'new-password' });
    const pass2 = App.input({ type: 'password', autocomplete: 'new-password' });
    const passBox = h('div.hidden',
      App.field('Phrase de passe', pass1, '12 caractères ou plus recommandés'),
      App.field('Confirme la phrase de passe', pass2),
      App.note('Sans cette phrase de passe, le fichier ne pourra pas être relu : elle n’est enregistrée nulle part.', 'warn')
    );
    const info = h('div');
    let sheet = null;
    function sync() {
      const full = mode.value === 'full';
      passBox.classList.toggle('hidden', !full);
      desc.textContent = full
        ? 'Tout, mots de passe et clés API compris, chiffré par une phrase de passe (AES-256).'
        : 'Tous tes réglages, sauf les mots de passe et les clés API (à ressaisir sur l’autre appareil).';
      const c = collect(cfg0, prefs0, full);
      const n = Object.keys(c.config).length;
      const p = Object.keys(c.prefs).length;
      App.fill(info,
        h('div.bk-count', n + ' réglage' + (n > 1 ? 's' : '') + ' et ' + p + ' préférence' + (p > 1 ? 's' : '') + ' d’affichage'),
        c.omitted.length
          ? App.note('Non exportés : ' + c.omitted.slice(0, 5).map((o) => o.key + ' (' + o.reason + ')').join(', ') + (c.omitted.length > 5 ? '…' : ''), 'warn')
          : null
      );
    }
    // A fresh snapshot each time: the settings may have been saved since the sheet opened.
    async function build() {
      const full = mode.value === 'full';
      if (full) {
        const bad = B.passProblem(pass1.value, pass2.value);
        if (bad) throw fail(bad);
      }
      return B.makeFile(snapshot(), B.collectPrefs(), { secrets: full, passphrase: pass1.value, app: App.nat('appVersion') || '' });
    }
    const canSave = B.canSave();
    const copyBtn = App.btn('Copier', {
      icon: 'clipboard-document',
      cls: canSave ? '' : 'pri',
      onClick: guard(async () => App.copy((await build()).text, 'Contenu'))
    });
    const saveBtn = canSave ? App.btn('Enregistrer', { icon: 'arrow-down-tray', cls: 'pri', onClick: guard(async () => saveFile(await build(), sheet)) }) : null;
    sync();
    sheet = App.sheet({
      title: 'Exporter les réglages',
      body: [mode, desc, passBox, info, canSave ? null : App.note('Cette version de l’app ne sait pas enregistrer de fichier : utilise « Copier » et colle le contenu dans un fichier .json.', 'warn')],
      actions: [copyBtn, saveBtn],
      onClose: () => {
        pass1.value = '';
        pass2.value = '';
      }
    });
  }

  function preview(sum) {
    const fmt = App.fmt;
    const when = sum.exportedAt && fmt.toDate(sum.exportedAt) ? fmt.date(sum.exportedAt) + ' à ' + fmt.time(sum.exportedAt) : '';
    const hasSrc = sum.app || when;
    return h('div.bk-prev',
      hasSrc ? h('div.bk-src', 'Fichier créé' + (sum.app ? ' par Arr411 ' + sum.app : '') + (when ? ', le ' + when : '')) : null,
      h('ul.bk-list', sum.sections.map((s) => h('li', h('span', s.label), h('b', String(s.count))))),
      sum.secrets === 'replaced'
        ? App.note('Mots de passe et clés API remplacés par ceux du fichier', 'warn', 'key')
        : App.note('Mots de passe et clés API actuels conservés', 'ok', 'key'),
      sum.urls.length
        ? h('div.bk-urls',
            h('div.bk-t', 'Adresses modifiées'),
            h('ul', sum.urls.map((u) => h('li', u.label + ' : ' + (u.from || 'aucune') + ' → ' + (u.to || 'aucune'))))
          )
        : null,
      sum.urlWarn ? App.note('Tes mots de passe et clés API actuels seront envoyés à ces nouvelles adresses. N’importe que des fichiers dont tu es sûr.', 'warn') : null,
      sum.prefs ? h('div.bk-hint', 'Les préférences d’affichage sont toutes prises en compte au prochain démarrage de l’app.') : null,
      sum.skipped.length
        ? h('div.bk-skip',
            h('div.bk-t', 'Ignoré'),
            h('ul', sum.skipped.map((s) => h('li', h('span.mono', s.key), ' : ' + s.reason)), sum.skippedMore ? h('li', '… et ' + sum.skippedMore + ' autre' + (sum.skippedMore > 1 ? 's' : '')) : null)
          )
        : null
    );
  }
  B.preview = preview;

  // Applies a validated import: one config write, the local preferences, then a refresh of the settings page.
  B.apply = function (res) {
    const wasDesktop = !!(App.config && App.config.desktopMode);
    if (Object.keys(res.patch).length) App.saveCfg(res.patch);
    for (const k of Object.keys(res.prefs)) App.setPref(k, res.prefs[k]);
    reload();
    if (hasOwn.call(res.patch, 'desktopMode') && !!res.patch.desktopMode !== wasDesktop) App.nat('reloadC411');
    const S = App.settings;
    if (S && typeof S.refresh === 'function') S.refresh();
    App.toast('Réglages importés — vérifie les connexions', 'ok');
  };

  function readText(f) {
    const fail2 = () => fail('Lecture du fichier impossible');
    if (typeof f.text === 'function') return f.text().catch(() => Promise.reject(fail2()));
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result));
      r.onerror = () => reject(fail2());
      r.readAsText(f);
    });
  }
  // Three tries; null when the user gives up.
  async function openEncrypted(file) {
    for (let i = 1; i <= 3; i++) {
      const pass = await App.prompt({
        title: 'Fichier chiffré',
        message: i === 1 ? 'Saisis la phrase de passe choisie lors de l’export.' : BAD_PASS + ' (essai ' + i + ' sur 3).',
        label: 'Phrase de passe',
        type: 'password',
        ok: 'Déchiffrer'
      });
      if (pass == null) return null;
      App.toast('Déchiffrement…');
      try {
        return await B.read(file, pass);
      } catch (e) {
        if (!e || !e.badPass || i === 3) throw e;
      }
    }
    return null;
  }
  B.importFile = async function (f) {
    if (f.size > MAX_FILE) throw fail('Fichier trop volumineux (1 Mo maximum)');
    const file = B.parse(await readText(f));
    const content = file.encrypted ? await openEncrypted(file) : await B.read(file);
    if (!content) return;
    const current = snapshot();
    const res = B.validate(content, current);
    const sum = B.summarize(res, file, current);
    if (!sum.settings && !sum.prefs) throw fail('Aucun réglage reconnu dans ce fichier' + (res.skipped.length ? ' (' + res.skipped.length + ' ignoré' + (res.skipped.length > 1 ? 's' : '') + ')' : ''));
    if (!(await App.confirm({ title: 'Importer ces réglages ?', message: preview(sum), ok: 'Importer' }))) return;
    B.apply(res);
  };

  function pickFile() {
    const old = document.getElementById('bk-file');
    if (old) old.remove();
    const inp = h('input#bk-file.hidden', { type: 'file' });
    inp.addEventListener('change', () => {
      const f = inp.files && inp.files[0];
      inp.remove();
      if (f) guard(() => B.importFile(f))();
    });
    (document.body || document.documentElement).appendChild(inp);
    inp.click();
  }

  B.openExport = openExport;
  B.body = function () {
    return [
      h('p.bk-lead.muted.wrap', 'Transfère tes réglages vers un autre appareil ou garde-les de côté.'),
      h('div.btn-row',
        App.btn('Exporter', { icon: 'arrow-up-tray', cls: 'pri', onClick: guard(openExport) }),
        App.btn('Importer', { icon: 'arrow-down-tray', onClick: guard(pickFile) })
      )
    ];
  };
})();
