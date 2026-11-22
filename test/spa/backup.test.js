'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadSpa } = require('./load-spa');
const { FakeNode, createDocument, find, findAll, isShown } = require('./fake-dom');

globalThis.Node = FakeNode;
// The sheets and the passphrase prompt need three things the fake DOM leaves out.
FakeNode.prototype.remove = function () {
  if (this.parentNode) this.parentNode.removeChild(this);
};
FakeNode.prototype.getBoundingClientRect = () => ({});
FakeNode.prototype.focus = () => {};

const FAST = 100000; // the lowest iteration count a file may carry
const PASS = 'correct horse battery';
const NOW = new Date(2026, 10, 7, 9, 30).getTime();
const BAD_FILE = /pas un fichier de réglages Arr411/;
const BAD_PASS = /Phrase de passe incorrecte ou fichier abîmé/;
const UNSUPPORTED = /Fichier non pris en charge/;

const CFG = {
  qbUrl: 'http://192.168.1.20:8080',
  username: 'admin',
  password: 'qb-pass-123',
  radarrUrl: 'http://nas.lan:7878',
  radarrKey: 'radarr-api-key-0123',
  radarrRoot: '/data/films',
  radarrProfile: 4,
  sonarrUrl: 'http://nas.lan:8989',
  sonarrKey: 'sonarr-api-key-0456',
  notifyEnabled: true,
  startTab: 'last',
  scorePreset: 'qualite',
  scoreWeights: { lang: 60, quality: 90, health: 40, size: 10 },
  autoUpdate: 'notify',
  dash: { order: ['speed', 'active'], compact: true },
  tracked: ['abc', 'def'],
  _scratch: 'x'
};
const without = (o, ...keys) => Object.fromEntries(Object.entries(o).filter(([k]) => !keys.includes(k)));
const levels = (n) => (n <= 1 ? {} : { a: levels(n - 1) });
const plainFile = (config, prefs) => JSON.stringify({ format: 'arr411-settings', version: 1, app: '', exportedAt: '', secrets: false, encrypted: false, config: config, prefs: prefs || {} });
// A file's content as read() returns it. JSON.parse makes "__proto__" an own key, as in a real file.
const content = (json, secrets) => ({ secrets: !!secrets, config: JSON.parse(json), prefs: {} });

// A host bridge double; `extra` overrides what it offers (undefined takes a method away).
function bridge(config, extra) {
  const N = {
    cfg: Object.assign({}, config),
    saved: [],
    files: [],
    copied: [],
    calls: [],
    config: () => JSON.stringify(N.cfg),
    saveConfig: (json) => {
      const patch = JSON.parse(json);
      N.saved.push(patch);
      Object.assign(N.cfg, patch);
    },
    appVersion: () => '2.3.1',
    copy: (text) => {
      N.copied.push(text);
    },
    saveText: (name, text) => {
      N.files.push({ name: name, text: text });
      return true;
    }
  };
  return Object.assign(N, extra);
}
function boot(Native) {
  const document = createDocument();
  const overlays = document.createElement('div');
  document.getElementById = (id) => (id === 'overlays' ? overlays : null);
  const win = loadSpa(['core.js', 'backup.js'], { Native: Native, document: document });
  return { win: win, App: win.App, B: win.App.backup, document: document, overlays: overlays };
}
const flush = () => new Promise((resolve) => setImmediate(resolve));
async function until(cond) {
  for (let i = 0; i < 1000 && !cond(); i++) await new Promise((resolve) => setTimeout(resolve, 5));
  assert.ok(cond(), 'timed out');
}
const button = (root, label) => find(root, (n) => n.localName === 'button' && n.textContent === label);
// The topmost sheet still open (a closed one stays in the DOM for a moment).
function openSheet(overlays) {
  const open = overlays.childNodes.filter((n) => n.classList && n.classList.contains('open'));
  return open.length ? find(open[open.length - 1], (n) => n.classList.contains('sheet')) : null;
}
const titleOf = (sheet) => find(sheet, (n) => n.localName === 'h3').textContent;
const sheetTitled = (ctx, title) => {
  const s = openSheet(ctx.overlays);
  return s && titleOf(s) === title ? s : null;
};
const card = (ctx) => ctx.App.h('div', ctx.B.body());
// Clicks Importer, then hands the file to the input the card created.
function pick(ctx, textOrFile) {
  button(card(ctx), 'Importer').click();
  const input = find(ctx.document.body, (n) => n.localName === 'input');
  input.files = [typeof textOrFile === 'string' ? { size: textOrFile.length, text: async () => textOrFile } : textOrFile];
  input.dispatchEvent({ type: 'change' });
}
const errors = (ctx) => ctx.win.toasts.filter((t) => t.type === 'err');

// ---------- File content ----------

test('a plain export leaves out secrets, tracked and private keys, and carries the local preferences', async () => {
  const { B } = boot();
  const out = await B.makeFile(CFG, { 'qb.sort': 'added_on', 'qb.apiToken': 'zzz-token' }, { app: '2.3.1', now: NOW });
  const f = JSON.parse(out.text);
  assert.equal(out.name, 'arr411-reglages-2026-11-07.json');
  assert.deepEqual(Object.keys(f), ['format', 'version', 'app', 'exportedAt', 'secrets', 'encrypted', 'config', 'prefs']);
  assert.deepEqual([f.format, f.version, f.app, f.exportedAt, f.secrets, f.encrypted], ['arr411-settings', 1, '2.3.1', new Date(NOW).toISOString(), false, false]);
  assert.deepEqual(f.config, without(CFG, 'password', 'radarrKey', 'sonarrKey', 'tracked', '_scratch'));
  assert.deepEqual(f.prefs, { 'qb.sort': 'added_on' });
  for (const secret of ['qb-pass-123', 'radarr-api-key-0123', 'sonarr-api-key-0456', 'zzz-token']) assert.ok(!out.text.includes(secret), secret);
  assert.deepEqual(out.omitted.map((o) => o.key), ['prefs.qb.apiToken']);
});

test('a plain file never replaces the current secrets, not even a hand-edited one', async () => {
  const { B } = boot();
  const current = { qbUrl: 'http://old:8080', password: 'my-secret', radarrKey: 'my-key' };
  const out = await B.makeFile(CFG, {}, { now: NOW });
  const res = B.validate(await B.read(B.parse(out.text)), current);
  assert.equal(res.secretsReplaced, false);
  assert.equal(res.patch.qbUrl, CFG.qbUrl);
  assert.ok(!('password' in res.patch) && !('radarrKey' in res.patch));
  assert.deepEqual(current, { qbUrl: 'http://old:8080', password: 'my-secret', radarrKey: 'my-key' });

  const forged = B.validate(await B.read(B.parse(plainFile({ password: 'evil', radarrKey: 'evil', qbUrl: 'http://ok:1' }))), current);
  assert.deepEqual(forged.patch, { qbUrl: 'http://ok:1' });
  assert.deepEqual(forged.skipped.map((s) => s.key), ['password', 'radarrKey']);
  assert.equal(forged.secretsReplaced, false);
});

test('collectPrefs reads only the arr411.* keys that hold JSON, at most 200', () => {
  const { B } = boot();
  const store = (entries) => {
    const names = Object.keys(entries);
    return { length: names.length, key: (i) => names[i], getItem: (k) => (k in entries ? entries[k] : null) };
  };
  const found = B.collectPrefs(
    store({
      'arr411.qb.sort': '"added_on"',
      'arr411.qb.desc': 'true',
      'arr411.dash.cards': '["a","b"]',
      'arr411.big': JSON.stringify('x'.repeat(16400)),
      'arr411.broken': '{oops',
      'arr411.bad key': '1',
      'arr411.__proto__': '1',
      'other.thing': '1'
    })
  );
  assert.deepEqual(found, { 'qb.sort': 'added_on', 'qb.desc': true, 'dash.cards': ['a', 'b'] });
  const many = {};
  for (let i = 0; i < 250; i++) many['arr411.k' + i] = String(i);
  assert.equal(Object.keys(B.collectPrefs(store(many))).length, 200);
});

// ---------- Encryption ----------

test('an encrypted export keeps everything out of sight and round-trips, whatever the Unicode form of the passphrase', async () => {
  const { B } = boot();
  const out = await B.makeFile(CFG, { 'qb.sort': 'name' }, { secrets: true, passphrase: 'caf\u00e9-secret-1', iterations: FAST, app: '2.3.1', now: NOW });
  const f = JSON.parse(out.text);
  assert.equal(out.name, 'arr411-reglages-2026-11-07-chiffre.json');
  assert.deepEqual(Object.keys(f), ['format', 'version', 'app', 'exportedAt', 'secrets', 'encrypted', 'kdf', 'cipher', 'data']);
  assert.deepEqual([f.secrets, f.encrypted, f.kdf.name, f.kdf.hash, f.kdf.iterations, f.cipher.name], [true, true, 'PBKDF2', 'SHA-256', FAST, 'AES-GCM']);
  assert.equal(Buffer.from(f.kdf.salt, 'base64').length, 16);
  assert.equal(Buffer.from(f.cipher.iv, 'base64').length, 12);
  for (const clear of ['qb-pass-123', 'radarr-api-key-0123', 'nas.lan', 'qbUrl', 'qb.sort']) assert.ok(!out.text.includes(clear), clear);

  const back = await B.read(B.parse(out.text), 'cafe\u0301-secret-1');
  assert.equal(back.secrets, true);
  assert.deepEqual(back.config, without(CFG, 'tracked', '_scratch'));
  assert.deepEqual(back.prefs, { 'qb.sort': 'name' });
  const res = B.validate(back, {});
  assert.equal(res.secretsReplaced, true);
  assert.equal(res.patch.password, 'qb-pass-123');
  assert.equal(res.patch.radarrKey, 'radarr-api-key-0123');
  assert.deepEqual(res.skipped, []);
});

test('the encrypted file follows the documented recipe, so any standard crypto library can open it', async () => {
  const nodeCrypto = require('node:crypto');
  const { B } = boot();
  const out = await B.makeFile(CFG, { 'qb.sort': 'name' }, { secrets: true, passphrase: PASS, iterations: FAST });
  const f = JSON.parse(out.text);
  const key = nodeCrypto.pbkdf2Sync(PASS, Buffer.from(f.kdf.salt, 'base64'), f.kdf.iterations, 32, 'sha256');
  const data = Buffer.from(f.data, 'base64');
  const decipher = nodeCrypto.createDecipheriv('aes-256-gcm', key, Buffer.from(f.cipher.iv, 'base64'));
  decipher.setAAD(Buffer.from('arr411-settings/1'));
  decipher.setAuthTag(data.subarray(data.length - 16));
  const plain = Buffer.concat([decipher.update(data.subarray(0, data.length - 16)), decipher.final()]).toString('utf8');
  assert.deepEqual(JSON.parse(plain), { config: out.config, prefs: out.prefs });
});

test('a wrong passphrase and a damaged file fail alike, but a payload that is not ours does not ask again', async () => {
  const { B } = boot();
  const out = await B.makeFile(CFG, {}, { secrets: true, passphrase: PASS, iterations: FAST });
  const parsed = B.parse(out.text);
  for (const pass of ['wrong passphrase', '', null]) await assert.rejects(B.read(parsed, pass), (e) => e.badPass === true && BAD_PASS.test(e.message));

  const raw = JSON.parse(out.text);
  const bytes = Buffer.from(raw.data, 'base64');
  for (const at of [0, bytes.length - 1]) {
    const hit = Buffer.from(bytes);
    hit[at] ^= 1;
    const damaged = B.parse(JSON.stringify(Object.assign({}, raw, { data: hit.toString('base64') })));
    await assert.rejects(B.read(damaged, PASS), BAD_PASS);
  }

  const blob = await B.encrypt('{"nothing":1}', PASS, FAST);
  const alien = B.parse(JSON.stringify(Object.assign({ format: 'arr411-settings', version: 1, app: '', exportedAt: '', secrets: true, encrypted: true }, blob)));
  await assert.rejects(B.read(alien, PASS), (e) => e.badPass !== true && UNSUPPORTED.test(e.message));
});

test('a passphrase needs 8 characters and a matching confirmation', async () => {
  const { B } = boot();
  assert.match(B.passProblem('1234567', '1234567'), /au moins 8 caractères/);
  assert.match(B.passProblem('12345678', '12345679'), /différentes/);
  assert.equal(B.passProblem('12345678', '12345678'), null);
  assert.equal(B.passProblem('\u{1F600}'.repeat(8), '\u{1F600}'.repeat(8)), null);
  await assert.rejects(B.makeFile(CFG, {}, { secrets: true, passphrase: 'short', iterations: FAST }), /au moins 8 caractères/);
});

test('parse refuses foreign files and iteration counts outside 100 000 to 2 000 000', async () => {
  const { B } = boot();
  const enc = JSON.parse((await B.makeFile(CFG, {}, { secrets: true, passphrase: PASS, iterations: FAST })).text);
  const withKdf = (iterations) => JSON.stringify(Object.assign({}, enc, { kdf: Object.assign({}, enc.kdf, { iterations: iterations }) }));
  for (const it of [99999, 2000001, 600000.5, '600000', null, 0, -100000]) assert.throws(() => B.parse(withKdf(it)), { message: 'Fichier non pris en charge' }, 'iterations ' + it);
  for (const it of [100000, 600000, 2000000]) assert.equal(B.parse(withKdf(it)).crypt.iterations, it);
  for (const it of [99999, 2000001]) await assert.rejects(B.encrypt('{}', PASS, it), UNSUPPORTED);

  const plain = { format: 'arr411-settings', version: 1, app: '', exportedAt: '', secrets: false, encrypted: false, config: {}, prefs: {} };
  const refused = [
    ['{broken', BAD_FILE],
    ['[]', BAD_FILE],
    [JSON.stringify(Object.assign({}, plain, { format: 'other' })), BAD_FILE],
    [JSON.stringify(Object.assign({}, plain, { version: '1' })), BAD_FILE],
    [JSON.stringify(Object.assign({}, plain, { version: 2 })), /version plus récente/],
    [JSON.stringify(Object.assign({}, plain, { config: [] })), BAD_FILE],
    [JSON.stringify(Object.assign({}, plain, { secrets: true })), UNSUPPORTED],
    [JSON.stringify(Object.assign({}, enc, { secrets: false })), UNSUPPORTED],
    [JSON.stringify(Object.assign({}, enc, { cipher: { name: 'AES-CBC', iv: enc.cipher.iv } })), UNSUPPORTED],
    [JSON.stringify(Object.assign({}, enc, { data: 'AAAA' })), UNSUPPORTED],
    [JSON.stringify(Object.assign({}, enc, { data: '***' })), UNSUPPORTED],
    ['x'.repeat(1048577), /trop volumineux/]
  ];
  for (const [text, expected] of refused) assert.throws(() => B.parse(text), { message: expected }, text.slice(0, 60));
  assert.equal(B.parse(JSON.stringify(plain)).encrypted, false);
});

// ---------- Validation of an import ----------

test('validate keeps every key away from the prototypes, at any depth', () => {
  const { B } = boot();
  const json = '{"dash":{"ok":1,"__proto__":{"polluted":true},"x":{"constructor":{"prototype":{"polluted":true}},"y":[{"__proto__":{"polluted":true}}]}},' +
    '"__proto__":{"polluted":true},"constructor":{"prototype":{"polluted":true}},"scoreWeights":{"__proto__":{"lang":1},"lang":5}}';
  const res = B.validate(content(json), {});
  assert.equal({}.polluted, undefined);
  assert.equal(Object.prototype.polluted, undefined);
  assert.deepEqual(Object.keys(res.patch).sort(), ['dash', 'scoreWeights']);
  assert.deepEqual(res.patch.dash, { ok: 1, x: { y: [{}] } });
  assert.equal(Object.getPrototypeOf(res.patch.dash), Object.prototype);
  assert.deepEqual(res.patch.scoreWeights, { lang: 5 });
  assert.deepEqual(res.skipped.map((s) => s.key).sort(), ['__proto__', 'constructor']);
});

test('validate skips each hostile or malformed value with a reason, and keeps the good ones', () => {
  const { B } = boot();
  const cases = [
    ['qbUrl', 'http://admin:hunter2@nas:8080', /adresse non valide/],
    ['radarrUrl', 'javascript:alert(1)', /adresse non valide/],
    ['sonarrUrl', 'ftp://nas/files', /adresse non valide/],
    ['lidarrUrl', 'not a url', /adresse non valide/],
    ['radarrRoot', 'x'.repeat(4097), /texte trop long/],
    ['autoUpdate', 'sometimes', /mode inconnu/],
    ['startTab', 'settings', /onglet inconnu/],
    ['scorePreset', 'Not Valid!', /préréglage non valide/],
    ['notifyEnabled', 'yes', /type inattendu \(texte au lieu de booléen\)/],
    ['scoreWeights', { lang: 150 }, /pondérations non valides/],
    ['scoreWeights', { lang: '50' }, /pondérations non valides/],
    ['dash', levels(6), /structure trop profonde/],
    ['dash', { a: Array.from({ length: 17 }, () => 'x'.repeat(4000)) }, /trop volumineuse/],
    ['dash', 5, /type inattendu/],
    ['tracked', ['x'], /géré par l’app/],
    ['mystery', 1, /inconnu de cette version/]
  ];
  for (const [key, value, why] of cases) {
    const res = B.validate({ secrets: false, config: { [key]: value }, prefs: {} }, {});
    assert.deepEqual(res.patch, {}, key);
    assert.equal(res.skipped.length, 1, key);
    assert.equal(res.skipped[0].key, key);
    assert.match(res.skipped[0].reason, why, key);
  }
  const good = { qbUrl: '', radarrUrl: 'https://nas.lan:7878/radarr', autoUpdate: 'off', startTab: 'last', notifyEnabled: false, scoreWeights: { lang: 0, quality: 100 }, radarrProfile: 4, sonarrProfile: 'HD', scorePreset: 'perso', dash: levels(5) };
  const res = B.validate({ secrets: false, config: good, prefs: {} }, {});
  assert.deepEqual(res.patch, good);
  assert.deepEqual(res.skipped, []);
});

test('names that look like secrets are dropped from a plain file, nested ones included, and kept from an encrypted one', () => {
  const { B } = boot();
  const config = JSON.stringify({ dash: { layout: 'x', apiKey: 'zzz', nested: { token: 't', ok: 1 } } });
  const plain = B.validate(content(config), {});
  assert.deepEqual(plain.patch.dash, { layout: 'x', nested: { ok: 1 } });
  assert.deepEqual(plain.skipped.map((s) => s.key), ['dash › apiKey', 'dash › token']);
  const full = B.validate(content(config, true), {});
  assert.deepEqual(full.patch.dash, { layout: 'x', apiKey: 'zzz', nested: { token: 't', ok: 1 } });
  assert.deepEqual(full.skipped, []);
  const prefs = B.validate({ secrets: false, config: {}, prefs: { 'qb.sort': 'name', 'qb.apiToken': 'x', '__proto__': 1, 'bad key': 1 } }, {}).prefs;
  assert.deepEqual(prefs, { 'qb.sort': 'name' });
});

test('a fresh install takes the settings it knows; any other key must exist in the config with the same type', () => {
  const { B } = boot();
  const known = { qbUrl: 'http://nas:8080', startTab: 'qbit', lastCategory: 'films', scorePreset: 'vf', pagePrefs: { a: 1 }, dash: { a: 1 }, desktopMode: true, lidarrMetaProfile: 2 };
  const fresh = B.validate({ secrets: false, config: Object.assign({ whatever: 'x' }, known), prefs: {} }, {});
  assert.deepEqual(fresh.patch, known);
  assert.deepEqual(fresh.skipped.map((s) => [s.key, s.reason]), [['whatever', 'inconnu de cette version']]);

  const later = B.validate({ secrets: false, config: { whatever: 'y', other: 5 }, prefs: {} }, { whatever: 'x', other: 'text' });
  assert.deepEqual(later.patch, { whatever: 'y' });
  assert.match(later.skipped[0].reason, /type inattendu \(nombre au lieu de texte\)/);
});

test('the preview data counts settings per section, names only the host of a changed address and warns when kept secrets will meet a new server', () => {
  const { B } = boot();
  const patch = { qbUrl: 'https://qb.example.org:9443/private/path?x=1', username: 'u', lastCategory: 'films', radarrUrl: 'http://nas.lan:7878', radarrRoot: '/x', scorePreset: 'vf', autoUpdate: 'off', startTab: 'qbit' };
  const res = { patch: patch, skipped: [], secretsReplaced: false, prefs: { 'qb.sort': 'name' } };
  const current = { qbUrl: 'http://192.168.1.20:8080/x', radarrUrl: 'http://nas.lan:7878' };
  const sum = B.summarize(res, { app: '2.3.1', exportedAt: '2026-11-07T09:30:00.000Z' }, current);
  assert.deepEqual(sum.sections.map((s) => [s.label, s.count]), [['qBittorrent', 3], ['Radarr', 2], ['Score des torrents', 1], ['Mises à jour', 1], ['Affichage (préférences locales)', 1], ['Autres', 1]]);
  assert.deepEqual(sum.urls, [{ label: 'qBittorrent', from: '192.168.1.20:8080', to: 'qb.example.org:9443' }]);
  assert.ok(!JSON.stringify(sum).includes('private'));
  assert.deepEqual([sum.app, sum.secrets, sum.urlWarn, sum.settings, sum.prefs], ['2.3.1', 'kept', true, 8, 1]);

  const replaced = B.summarize(Object.assign({}, res, { secretsReplaced: true }), {}, current);
  assert.deepEqual([replaced.secrets, replaced.urlWarn], ['replaced', false]);
  const many = B.summarize(Object.assign({}, res, { skipped: Array.from({ length: 12 }, (_, i) => ({ key: 'k' + i, reason: 'r' })) }), {}, current);
  assert.deepEqual([many.skipped.length, many.skippedMore], [10, 2]);
});

// ---------- Export sheet ----------

test('Enregistrer calls Native.saveText(name, text) and closes the sheet only when the host answers true', async () => {
  const answers = [
    { answer: true, closed: true, toast: null },
    { answer: undefined, closed: false, toast: null },
    { answer: false, closed: false, toast: /Enregistrement impossible/ },
    { answer: new Error('disk full'), closed: false, toast: /Enregistrement impossible/ }
  ];
  for (const c of answers) {
    const N = bridge(CFG, {
      saveText: (name, text) => {
        N.files.push({ name: name, text: text });
        if (c.answer instanceof Error) throw c.answer;
        return c.answer;
      }
    });
    const ctx = boot(N);
    button(card(ctx), 'Exporter').click();
    button(openSheet(ctx.overlays), 'Enregistrer').click();
    await until(() => N.files.length === 1);
    await flush();
    const { name, text } = N.files[0];
    const file = JSON.parse(text);
    assert.match(name, /^arr411-reglages-\d{4}-\d{2}-\d{2}\.json$/);
    assert.equal(text, JSON.stringify(file, null, 2));
    assert.deepEqual([file.format, file.encrypted, file.config.qbUrl, 'password' in file.config], ['arr411-settings', false, CFG.qbUrl, false]);
    assert.equal(openSheet(ctx.overlays) === null, c.closed);
    if (c.toast) assert.match(errors(ctx).map((t) => t.msg).join(), c.toast);
    else assert.deepEqual(errors(ctx), []);
  }
});

test('a host that cannot save a file keeps Copier only, while a browser has both buttons', async () => {
  const N = bridge(CFG, { saveText: undefined });
  const ctx = boot(N);
  assert.equal(ctx.B.canSave(), false);
  button(card(ctx), 'Exporter').click();
  const sheet = openSheet(ctx.overlays);
  assert.equal(button(sheet, 'Enregistrer'), null);
  assert.ok(button(sheet, 'Copier').classList.contains('pri'));
  assert.match(sheet.textContent, /ne sait pas enregistrer de fichier/);
  button(sheet, 'Copier').click();
  await until(() => N.copied.length === 1);
  assert.equal(JSON.parse(N.copied[0]).config.qbUrl, CFG.qbUrl);
  assert.deepEqual(ctx.win.toasts.map((t) => t.msg), ['Contenu copié']);

  const browser = boot(undefined);
  assert.equal(browser.B.canSave(), true);
  button(card(browser), 'Exporter').click();
  assert.ok(button(openSheet(browser.overlays), 'Enregistrer'));
});

test('the encrypted export refuses a weak or unconfirmed passphrase, then writes the encrypted file', async () => {
  const N = bridge(CFG);
  const ctx = boot(N);
  button(card(ctx), 'Exporter').click();
  const sheet = openSheet(ctx.overlays);
  const [pass1, pass2] = findAll(sheet, (n) => n.localName === 'input');
  assert.equal(isShown(pass1), false);
  button(sheet, 'Complet (chiffré)').click();
  assert.equal(isShown(pass1), true);
  const save = async (a, b) => {
    pass1.value = a;
    pass2.value = b;
    button(sheet, 'Enregistrer').click();
    await flush();
  };
  await save('short', 'short');
  assert.match(errors(ctx).pop().msg, /au moins 8 caractères/);
  await save(PASS, PASS + 'x');
  assert.match(errors(ctx).pop().msg, /différentes/);
  assert.deepEqual(N.files, []);

  await save(PASS, PASS);
  await until(() => N.files.length === 1);
  const { name, text } = N.files[0];
  const file = JSON.parse(text);
  assert.match(name, /^arr411-reglages-\d{4}-\d{2}-\d{2}-chiffre\.json$/);
  assert.deepEqual([file.encrypted, file.secrets, file.kdf.iterations], [true, true, 600000]);
  assert.ok(!text.includes('qb-pass-123') && !text.includes('radarr-api-key-0123'));
  assert.equal((await ctx.B.read(ctx.B.parse(text), PASS)).config.password, 'qb-pass-123');
  assert.equal(errors(ctx).length, 2);
  assert.equal(openSheet(ctx.overlays), null);
  assert.deepEqual([pass1.value, pass2.value], ['', '']);
});

// ---------- Import ----------

test('Importer previews the file, then writes once, sets the prefs, refreshes and toasts on OK; Annuler changes nothing', async () => {
  const source = await boot().B.makeFile(Object.assign({}, CFG, { desktopMode: true }), { 'qb.sort': 'name', 'qb.desc': true }, { app: '2.3.1', now: NOW });
  const N = bridge({ qbUrl: 'http://old-host:8080', password: 'my-secret', radarrKey: 'my-key', desktopMode: false, tracked: ['t1'] }, {
    reloadC411: () => {
      N.calls.push('reloadC411');
    }
  });
  const ctx = boot(N);
  let flushed = 0;
  let refreshed = 0;
  ctx.App.settings = {
    flushForms: () => {
      flushed++;
    },
    refresh: () => {
      refreshed++;
    }
  };
  pick(ctx, source.text);
  await until(() => sheetTitled(ctx, 'Importer ces réglages ?'));
  const sheet = openSheet(ctx.overlays);
  assert.match(sheet.textContent, /Arr411 2\.3\.1/);
  assert.match(sheet.textContent, /Mots de passe et clés API actuels conservés/);
  assert.match(sheet.textContent, /qBittorrent : old-host:8080 → 192\.168\.1\.20:8080/);
  assert.match(sheet.textContent, /seront envoyés à ces nouvelles adresses/);
  assert.deepEqual(N.saved, []);

  button(sheet, 'Importer').click();
  await until(() => N.saved.length === 1);
  await flush();
  assert.equal(N.saved[0].qbUrl, CFG.qbUrl);
  assert.equal(N.saved[0].desktopMode, true);
  assert.ok(!('password' in N.saved[0]) && !('radarrKey' in N.saved[0]) && !('tracked' in N.saved[0]));
  assert.deepEqual([N.cfg.password, N.cfg.radarrKey, N.cfg.tracked], ['my-secret', 'my-key', ['t1']]);
  assert.deepEqual([ctx.App.pref('qb.sort'), ctx.App.pref('qb.desc')], ['name', true]);
  assert.deepEqual(N.calls, ['reloadC411']);
  assert.deepEqual([refreshed, flushed > 0], [1, true]);
  assert.deepEqual(ctx.win.toasts.filter((t) => t.type === 'ok').map((t) => t.msg), ['Réglages importés — vérifie les connexions']);
  assert.equal(openSheet(ctx.overlays), null);

  const N2 = bridge({ qbUrl: 'http://old-host:8080' });
  const second = boot(N2);
  pick(second, source.text);
  await until(() => sheetTitled(second, 'Importer ces réglages ?'));
  button(openSheet(second.overlays), 'Annuler').click();
  await flush();
  assert.deepEqual([N2.saved, second.win.toasts, openSheet(second.overlays)], [[], [], null]);
  assert.equal(second.App.pref('qb.sort'), undefined);
});

test('an encrypted import allows three passphrase attempts, and cancelling the prompt changes nothing', async () => {
  const out = await boot().B.makeFile(CFG, {}, { secrets: true, passphrase: PASS, iterations: FAST });
  const prompt = async (ctx) => {
    await until(() => sheetTitled(ctx, 'Fichier chiffré'));
    return openSheet(ctx.overlays);
  };
  const submit = (sheet, value) => {
    find(sheet, (n) => n.localName === 'input').value = value;
    button(sheet, 'Déchiffrer').click();
  };

  const N = bridge({ qbUrl: 'http://old:8080', password: 'my-secret' });
  const ctx = boot(N);
  pick(ctx, out.text);
  let sheet = await prompt(ctx);
  assert.doesNotMatch(sheet.textContent, /essai/);
  submit(sheet, 'wrong passphrase 1');
  sheet = await prompt(ctx);
  assert.match(sheet.textContent, /essai 2 sur 3/);
  submit(sheet, 'wrong passphrase 2');
  sheet = await prompt(ctx);
  assert.match(sheet.textContent, /essai 3 sur 3/);
  submit(sheet, PASS);
  await until(() => sheetTitled(ctx, 'Importer ces réglages ?'));
  assert.match(openSheet(ctx.overlays).textContent, /remplacés par ceux du fichier/);
  button(openSheet(ctx.overlays), 'Importer').click();
  await until(() => N.saved.length === 1);
  assert.equal(N.saved[0].password, 'qb-pass-123');

  const N2 = bridge({ password: 'my-secret' });
  const lost = boot(N2);
  pick(lost, out.text);
  for (let i = 1; i <= 3; i++) submit(await prompt(lost), 'wrong passphrase ' + i);
  await until(() => errors(lost).length === 1);
  assert.match(errors(lost)[0].msg, BAD_PASS);
  assert.deepEqual([N2.saved, openSheet(lost.overlays)], [[], null]);

  const gone = boot(bridge({}));
  pick(gone, out.text);
  button(await prompt(gone), 'Annuler').click();
  await flush();
  assert.deepEqual(gone.win.toasts, []);
});

test('a file that is not for us, too big, or without a known setting ends as a toast and changes nothing', async () => {
  const N = bridge({});
  const ctx = boot(N);
  const big = {
    size: 1048577,
    text: () => {
      throw new Error('must not be read');
    }
  };
  const cases = [
    ['{"hello":"world"}', BAD_FILE],
    ['not json at all', BAD_FILE],
    [plainFile({ mystery: 1 }), /Aucun réglage reconnu/],
    [big, /trop volumineux/]
  ];
  for (const [file, expected] of cases) {
    const before = errors(ctx).length;
    pick(ctx, file);
    await until(() => errors(ctx).length === before + 1);
    assert.match(errors(ctx)[before].msg, expected);
  }
  assert.deepEqual([N.saved, openSheet(ctx.overlays)], [[], null]);
});
