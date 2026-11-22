'use strict';
// Runs assets-extra\shim-page.js (shared by Android and Desktop) on a stub of the page and of the native QbtPage bridge.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const SHIM = fs.readFileSync(path.join(__dirname, '..', '..', 'assets-extra', 'shim-page.js'), 'utf8');
const TORRENT = 'https://c411.org/download/abc.torrent';

// Returns the `chrome` object the shim defines for content.js, with what the native side received.
function loadShim() {
  const sent = [];
  const downloads = [];
  const pageClicks = [];
  const win = {
    QbtPage: {
      send: (json, id) => sent.push({ msg: JSON.parse(json), id }),
      download: (url, name) => downloads.push({ url, name }),
      toast() {},
      saveFile() {},
      fetchBytes() {}
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
  const chrome = new Function('window', 'document', 'HTMLAnchorElement', 'URL', 'location', SHIM + '\nreturn chrome;')(win, doc, Anchor, PageURL, location);
  return { chrome, win, sent, downloads, pageClicks, link: (href) => new Anchor(href) };
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
