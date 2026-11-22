'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadSpa } = require('./load-spa');

// Boots the qBittorrent store on a fake /sync/maindata; feed() delivers one delta and resolves once it was merged.
function boot() {
  const badges = [];
  const win = loadSpa(['core.js', 'qbit.js'], { Native: { setBadge: (n) => badges.push(n) } });
  const App = win.App;
  const Q = App.qbit;
  App.config = { qbUrl: 'http://qb.test:8080' };
  App.state.fg = false;
  let next = '';
  App.http = async (req) => ({ status: 200, text: /\/sync\/maindata/.test(req.url) ? next : '5.0.0' });
  let renders = 0;
  Q._render = () => {
    renders++;
  };
  const feed = (json) =>
    new Promise((resolve) => {
      next = typeof json === 'string' ? json : JSON.stringify(json);
      const off = App.on('qbit', () => {
        off();
        setImmediate(resolve);
      });
      Q.refresh();
    });
  return { Q, badges, feed, renders: () => renders };
}

test('a sync tick re-renders the list only when something listed changed', async () => {
  const { feed, renders } = boot();
  await feed({
    rid: 1,
    full_update: true,
    torrents: {
      vis: { name: 'Real.Movie', state: 'downloading', progress: 0.1, size: 100, tags: '' },
      farm: { name: 'Farm.1', state: 'stalledUP', progress: 1, size: 100, tags: 'zen-farm' }
    },
    server_state: { dl_info_speed: 1 }
  });
  const base = renders();
  assert.ok(base >= 1);

  await feed({ rid: 2 });
  assert.equal(renders(), base, 'empty delta');
  await feed({ rid: 3, torrents: { farm: { upspeed: 10, ratio: 1.5 } } });
  assert.equal(renders(), base, 'torrent hidden by the default zen-farm filter');
  await feed({ rid: 4, torrents: { vis: { progress: 0.2 } } });
  assert.equal(renders(), base + 1, 'visible torrent');
  await feed({ rid: 5, server_state: { dl_info_speed: 2 } });
  assert.equal(renders(), base + 2, 'speeds in the header');
  await feed({ rid: 6, torrents: { farm: { tags: '' } } });
  assert.equal(renders(), base + 3, 'torrent leaving the hidden tag');
});

test('the download badge follows state changes, additions and removals', async () => {
  const { badges, feed } = boot();
  await feed({ rid: 1, full_update: true, torrents: { a: { state: 'downloading' }, b: { state: 'stalledDL' }, c: { state: 'pausedUP' } } });
  assert.equal(badges.at(-1), 2);
  await feed({ rid: 2, torrents: { a: { state: 'pausedDL' } } });
  assert.equal(badges.at(-1), 1);
  await feed({ rid: 3, torrents: { d: { state: 'metaDL' } } });
  assert.equal(badges.at(-1), 2);
  await feed({ rid: 4, torrents_removed: ['b'] });
  assert.equal(badges.at(-1), 1);
  await feed({ rid: 5, full_update: true, torrents: {} });
  assert.equal(badges.at(-1), 0);
});

// Last on purpose: a merge without the guard would pollute the Object.prototype shared by every test of this file.
test('keys named __proto__ / constructor in maindata never reach a prototype nor the store', async () => {
  const { Q, feed } = boot();
  await feed(
    '{"rid":1,"full_update":true,"tags":["constructor"],'
    + '"torrents":{"aaa":{"name":"A","state":"pausedUP","progress":1,"category":"__proto__","tags":"constructor,prototype","__proto__":{"polluted":"field"}},'
    + '"__proto__":{"polluted":"torrent"},"constructor":{"name":"C"}},'
    + '"categories":{"__proto__":{"savePath":"/x"},"constructor":{"savePath":"/y"},"Films":{"savePath":"/films","__proto__":{"polluted":"category"}}},'
    + '"server_state":{"dl_info_speed":5,"__proto__":{"polluted":"server"}}}'
  );
  assert.equal({}.polluted, undefined);
  assert.equal(Object.getPrototypeOf(Q.store.torrents), null);
  assert.equal(Object.getPrototypeOf(Q.store.categories), null);
  assert.deepEqual(Object.keys(Q.store.torrents), ['aaa']);
  assert.deepEqual(Q.categoryNames(), ['Films']);
  assert.equal(Q.store.categories.Films.savePath, '/films');
  assert.equal(Q.store.server.dl_info_speed, 5);
  assert.equal(Object.getPrototypeOf(Q.store.server), Object.prototype);
  assert.equal(Q.get('aaa').polluted, undefined);
  assert.equal(Q.get('aaa').category, '__proto__');

  // A category or tag that is called "__proto__" is only a value: the counters keep it as an ordinary entry.
  const tally = Q.tally();
  assert.equal(tally.total, 1);
  assert.deepEqual(Object.keys(tally.cats), ['__proto__']);
  assert.equal(tally.cats.__proto__, 1);
  assert.deepEqual(Object.keys(tally.tags).sort(), ['constructor', 'prototype']);

  await feed('{"rid":2,"torrents":{"aaa":{"progress":0.5,"constructor":"x","__proto__":{"polluted":"delta"}}}}');
  assert.equal(Q.get('aaa').progress, 0.5);
  assert.equal(typeof Q.get('aaa').constructor, 'function');
  assert.equal({}.polluted, undefined);

  await feed({ rid: 3, torrents_removed: ['aaa'], categories_removed: ['Films'] });
  assert.deepEqual(Object.keys(Q.store.torrents), []);
  assert.deepEqual(Q.categoryNames(), []);
});
