'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadSpa } = require('./load-spa');

test('App.collator ignores case and accents and sorts numbers naturally', () => {
  const App = loadSpa(['core.js']).App;
  const names = ['Film 10', 'film 2', 'Étoile', 'etoile', 'Zèbre', 'abeille'];
  assert.deepEqual(names.sort(App.collator.compare), ['abeille', 'Étoile', 'etoile', 'film 2', 'Film 10', 'Zèbre']);
});

test('the qBittorrent name comparator shares that collator and tolerates missing names', () => {
  const App = loadSpa(['core.js', 'qbit.js']).App;
  assert.ok(App.qbit.cmpName('Épisode 2', 'episode 10') < 0);
  assert.equal(App.qbit.cmpName('Alien', 'alien'), 0);
  assert.ok(App.qbit.cmpName(undefined, 'a') < 0);
  assert.equal(App.qbit.cmpName(null, undefined), 0);
});

test('trimSlash, parentOf, rootOf and rootOptions handle both path styles', () => {
  const App = loadSpa(['core.js']).App;
  assert.equal(App.trimSlash('/data/films//'), '/data/films');
  assert.equal(App.trimSlash('C:\\Media\\Films\\'), 'C:\\Media\\Films');
  assert.equal(App.trimSlash(null), '');
  assert.equal(App.parentOf('/data/films/Alien (1979)'), '/data/films');
  assert.equal(App.parentOf('C:\\Media\\Films\\Alien\\'), 'C:\\Media\\Films');
  assert.equal(App.rootOf({ rootFolderPath: '/data/films/' }), '/data/films');
  assert.equal(App.rootOf({ path: '/data/films/Alien' }), '/data/films');
  const opts = App.rootOptions([{ path: '/data/films/' }, { path: '/data/tv', freeSpace: 1073741824 }]);
  assert.deepEqual(opts[0], { value: '/data/films', label: '/data/films' });
  assert.equal(opts[1].value, '/data/tv');
  assert.match(opts[1].label, /^\/data\/tv · .+ libres$/);
});

test('safeAssign copies like Object.assign but drops keys that would reach a prototype', () => {
  const App = loadSpa(['core.js']).App;
  const dst = { a: 1 };
  assert.equal(App.safeAssign(dst, { b: 2 }), dst);
  assert.deepEqual(dst, { a: 1, b: 2 });
  assert.equal(App.safeAssign(dst, null), dst);

  const hostile = JSON.parse('{"ok":1,"__proto__":{"polluted":"yes"},"constructor":{"prototype":{"polluted":"yes"}},"prototype":3}');
  const out = App.safeAssign({}, hostile);
  assert.deepEqual(Object.keys(out), ['ok']);
  assert.equal(Object.getPrototypeOf(out), Object.prototype);
  assert.equal({}.polluted, undefined);

  const bare = Object.create(null);
  bare.__proto__ = 'inert';
  bare.x = 1;
  assert.deepEqual(Object.keys(App.safeAssign({}, bare)), ['x']);
  assert.ok(App.unsafeKey('__proto__') && App.unsafeKey('constructor') && App.unsafeKey('prototype'));
  assert.ok(!App.unsafeKey('hash') && !App.unsafeKey('Constructor'));
});

test('openExternal hands only http, https and magnet links to the platform', () => {
  const opened = [];
  const win = loadSpa(['core.js'], { Native: { openUrl: (u) => opened.push(u) } });
  const App = win.App;
  const magnet = 'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Film';
  App.openExternal('https://example.org/a b');
  App.openExternal('  http://qb.local:8080  ');
  App.openExternal(magnet);
  assert.deepEqual(opened, ['https://example.org/a%20b', 'http://qb.local:8080/', magnet]);
  assert.equal(win.toasts.length, 0);

  for (const bad of ['javascript:alert(1)', 'JaVaScRiPt:alert(1)', 'java\nscript:alert(1)', 'intent://scan/#Intent;scheme=zxing;end', 'file:///etc/passwd', 'data:text/html,<script>alert(1)</script>', 'ftp://example.org/x', 'market://details?id=a.b', '/relative/path', 'not a url', 42]) {
    App.openExternal(bad);
  }
  assert.equal(opened.length, 3);
  assert.equal(win.toasts.length, 11);
  assert.ok(win.toasts.every((t) => t.type === 'err' && /^Lien non pris en charge/.test(t.msg)));

  App.openExternal('');
  App.openExternal(null);
  assert.equal(win.toasts.length, 11);
});

test('openExternal falls back to window.open without noopener leaks when the bridge has no openUrl', () => {
  const win = loadSpa(['core.js']);
  win.App.openExternal('https://example.org');
  win.App.openExternal('javascript:alert(1)');
  assert.deepEqual(win.opened, [['https://example.org/', '_blank', 'noopener,noreferrer']]);
  assert.equal(win.toasts.length, 1);
});
