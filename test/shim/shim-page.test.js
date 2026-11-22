'use strict';
// Runs assets-extra\shim-page.js (shared by Android and Desktop) on a stub of the page and of the native QbtPage bridge.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const SHIM = fs.readFileSync(path.join(__dirname, '..', '..', 'assets-extra', 'shim-page.js'), 'utf8');
const TORRENT = 'https://c411.org/download/abc.torrent';
const KEY = '0123456789abcdef0123456789abcdef';

// Returns the `chrome` object the shim defines for content.js, with what the native side received. The Android bundle declares `__qbtKey`
// around the shim (the bridge only answers a call that starts with it): `key` is that value, and `declared: false` leaves it out, as the Desktop bundle does.
function loadShim({ declared = true, key = KEY } = {}) {
  const sent = [];
  const downloads = [];
  const pageClicks = [];
  const calls = [];
  const win = {
    QbtPage: {
      send: (...args) => {
        calls.push({ name: 'send', args });
        sent.push({ msg: JSON.parse(args[1]), id: args[2] });
      },
      download: (...args) => {
        calls.push({ name: 'download', args });
        downloads.push({ url: args[1], name: args[2] });
      },
      toast: (...args) => void calls.push({ name: 'toast', args }),
      saveFile: (...args) => void calls.push({ name: 'saveFile', args }),
      fetchBytes: (...args) => void calls.push({ name: 'fetchBytes', args })
    },
    open: () => null
  };
  class Anchor {
    constructor(href) {
      this.href = href;
    }
    hasAttribute() {
      return false;
    }
    getAttribute() {
      return null;
    }
    closest() {
      return this;
    }
    click() {
      pageClicks.push(this.href);
    }
  }
  const doc = { addEventListener() {} };
  const location = { href: 'https://c411.org/torrents/1' };
  // The shim assigns URL.createObjectURL: give it its own constructor, not Node's.
  class PageURL extends URL {}
  const names = ['window', 'document', 'HTMLAnchorElement', 'URL', 'location'];
  const values = [win, doc, Anchor, PageURL, location];
  if (declared) {
    names.push('__qbtKey');
    values.push(key);
  }
  const chrome = new Function(...names, SHIM + '\nreturn chrome;')(...values);
  return { chrome, win, sent, downloads, pageClicks, calls, link: (href) => new Anchor(href) };
}

test('a refused arm disarms the shim at once: the next download is the page\'s own, not swallowed', async () => {
  const { chrome, win, sent, downloads, pageClicks, link } = loadShim();
  const reply = chrome.runtime.sendMessage({ type: 'arm' });
  assert.equal(sent[0].msg.type, 'arm');
  assert.ok(win.__qbtArmedUntil > Date.now(), 'armed while the answer is awaited');

  win.__qbtReply(sent[0].id, JSON.stringify({ ok: false, error: 'Action refusée : l\'onglet C411 n\'est pas actif' }));
  const r = await reply;
  assert.equal(r.ok, false);
  assert.equal(r.error, 'Action refusée : l\'onglet C411 n\'est pas actif', 'the refusal reaches content.js, which shows it');
  assert.equal(win.__qbtArmedUntil, 0);

  link(TORRENT).click();
  assert.deepEqual(downloads, []);
  assert.deepEqual(pageClicks, [TORRENT]);
});

test('the callback form (the one content.js uses) sees the shim already disarmed', async () => {
  const { chrome, win, sent } = loadShim();
  const seen = new Promise((resolve) => chrome.runtime.sendMessage({ type: 'arm' }, (res) => resolve({ res, armedUntil: win.__qbtArmedUntil })));
  win.__qbtReply(sent[0].id, JSON.stringify({ ok: false, error: 'Message refusé' }));
  const { res, armedUntil } = await seen;
  assert.equal(res.error, 'Message refusé');
  assert.equal(armedUntil, 0);
});

test('an accepted arm hands the next .torrent link over to the app, once', async () => {
  const { chrome, win, sent, downloads, pageClicks, link } = loadShim();
  const reply = chrome.runtime.sendMessage({ type: 'arm' });
  win.__qbtReply(sent[0].id, JSON.stringify({ ok: true }));
  assert.equal((await reply).ok, true);
  assert.ok(win.__qbtArmedUntil > Date.now(), 'still armed');

  link(TORRENT).click();
  assert.deepEqual(downloads, [{ url: TORRENT, name: '' }]);
  assert.deepEqual(pageClicks, []);
  assert.equal(win.__qbtArmedUntil, 0, 'one arm, one download');

  link(TORRENT).click();
  assert.equal(downloads.length, 1);
  assert.deepEqual(pageClicks, [TORRENT]);
});

test('an empty answer counts as a failed arm, and disarm clears an armed shim', async () => {
  const { chrome, win, sent } = loadShim();
  const failed = chrome.runtime.sendMessage({ type: 'arm' });
  win.__qbtReply(sent[0].id, 'null');
  assert.equal(await failed, null);
  assert.equal(win.__qbtArmedUntil, 0);

  chrome.runtime.sendMessage({ type: 'arm' });
  assert.ok(win.__qbtArmedUntil > Date.now());
  chrome.runtime.sendMessage({ type: 'disarm' });
  assert.equal(win.__qbtArmedUntil, 0);
});

test('every call of the shim to the native bridge starts with the key of the app', async () => {
  const { chrome, win, sent, calls, link } = loadShim();

  chrome.runtime.sendMessage({ type: 'getCategories' }); // send
  chrome.runtime.sendMessage({ type: 'arm' });
  win.__qbtReply(sent[1].id, JSON.stringify({ ok: true }));
  link(TORRENT).click(); // download

  win.fetch = async () => new Response(new Blob(['d4:infoe']));
  await win.__qbtSaveBlob('blob:https://c411.org/1', 'a.torrent'); // saveFile
  win.fetch = async () => {
    throw new Error('boom');
  };
  await win.__qbtSaveBlob('blob:https://c411.org/1', 'a.torrent'); // toast: a blob that cannot be read

  const fetched = win.__qbtFetch(TORRENT); // fetchBytes: the page cannot fetch it itself
  await new Promise((resolve) => setImmediate(resolve));
  const fetchCall = calls.find((c) => c.name === 'fetchBytes');
  assert.ok(fetchCall, 'the shim asked the app for the file');
  win.__qbtReply(fetchCall.args[3], JSON.stringify({ status: 200, base64: 'ZDQ6aW5mb2U=' }));
  assert.equal((await fetched).status, 200);

  assert.deepEqual([...new Set(calls.map((c) => c.name))].sort(), ['download', 'fetchBytes', 'saveFile', 'send', 'toast']);
  for (const c of calls) assert.equal(c.args[0], KEY, c.name + ' starts with the key');
  assert.equal(calls.find((c) => c.name === 'saveFile').args[1], 'a.torrent', 'the other arguments follow the key');
  assert.equal(calls.find((c) => c.name === 'fetchBytes').args[1], TORRENT);
});

test('the key stays inside the shim: it is not a property of the page nor of the chrome object', () => {
  const { chrome, win } = loadShim();
  const holders = [win, chrome, chrome.runtime];
  for (const holder of holders) {
    for (const name of Object.getOwnPropertyNames(holder)) {
      if (holder === win && name === 'QbtPage') continue;
      assert.notEqual(holder[name], KEY, name);
      assert.ok(!name.includes('Key'), name);
    }
  }
  assert.ok(!('__qbtKey' in win));
});

test('without a key declared around the shim (Desktop), or with one that is not a string, the shim sends an empty key', () => {
  for (const options of [{ declared: false }, { key: 42 }, { key: null }]) {
    const { chrome, calls } = loadShim(options);
    chrome.runtime.sendMessage({ type: 'getCategories' });
    assert.equal(calls[0].name, 'send', JSON.stringify(options));
    assert.equal(calls[0].args[0], '', JSON.stringify(options));
  }
});
