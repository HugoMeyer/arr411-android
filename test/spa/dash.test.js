'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadSpa } = require('./load-spa');
const { FakeNode, createDocument } = require('./fake-dom');

globalThis.Node = FakeNode;

const PASS = 'FAKEPASSKEY0123456789';
const C411 = 'https://tracker.c411.org/announce/' + PASS;
const NOW = 1800000000;
const H = 3600;
const DAY = 86400;

function boot() {
  const document = createDocument();
  const win = loadSpa(['core.js', 'qbit.js', 'dash.js'], { document: document });
  const App = win.App;
  return { win: win, App: App, Q: App.qbit, D: App.dash, document: document };
}
const { D } = boot();

// A finished torrent that has seeded for 100 h and never uploaded: the base the tests bend.
let seq = 0;
function tor(o) {
  return Object.assign({ hash: 'h' + ++seq, name: 'Torrent', state: 'stalledUP', progress: 1, size: 1000, uploaded: 0, downloaded: 1000, seeding_time: 100 * H, last_activity: NOW - 60, added_on: NOW - 100 * DAY, tags: '', category: '', tracker: C411 }, o);
}
function mapOf(list) {
  const m = Object.create(null);
  for (const t of list) m[t.hash] = t;
  return m;
}
const hashes = (list) => list.map((t) => t.hash).sort();
const compute = (list, server, opts) => D.compute(mapOf(list), server || {}, opts, NOW);

// ---------- Settings ----------

test('stored settings fall back to the defaults, and numbers are clamped', () => {
  const dflt = { farmTag: 'zen-farm', minSeedHours: 72, safeRatio: 1, idleDays: 30, rules: [] };
  assert.deepEqual(D.readCfg(undefined), dflt);
  assert.deepEqual(D.readCfg('nope'), dflt);
  assert.deepEqual(D.readCfg({ farmTag: 5, minSeedHours: 'abc', safeRatio: null, idleDays: {}, rules: 'x' }), dflt);
  const c = D.readCfg({ farmTag: '  farm ', minSeedHours: '48', safeRatio: '1,5', idleDays: 99999 });
  assert.deepEqual([c.farmTag, c.minSeedHours, c.safeRatio, c.idleDays], ['farm', 48, 1.5, 3650]);
  const low = D.readCfg({ minSeedHours: -4, safeRatio: -1, idleDays: 0 });
  assert.deepEqual([low.minSeedHours, low.safeRatio, low.idleDays], [0, 0, 1]);
  assert.equal(D.readCfg({ farmTag: '' }).farmTag, '', 'an empty farm tag is allowed: no farm group');
});

test('saved rules are cleaned, capped at 10, and an unknown action never becomes a deletion', () => {
  const rules = [null, 'x', 7, { name: '  A  ', tag: ' t ', action: 'rm -rf', states: 'weird', minRatio: '2', maxRatio: 'x', actionTag: 'a,b' }];
  for (let i = 0; i < 12; i++) rules.push({ name: 'r' + i, tag: 'x', action: 'pause' });
  const c = D.readCfg({ rules: rules });
  assert.equal(c.rules.length, 10);
  assert.deepEqual(c.rules[0], { name: 'A', tag: 't', notTag: '', category: null, minSeedHours: 0, minRatio: 2, maxRatio: null, idleDays: 0, states: 'all', action: 'addTag', actionTag: 'ab' });
  assert.equal(D.cleanRule({ action: 'DELETE' }).action, 'addTag');
  assert.equal(D.cleanRule({ action: 'deleteFiles' }).action, 'deleteFiles');
  assert.equal(D.cleanRule({ category: '' }).category, '');
});

test('a rule needs a criterion, and the tag actions need a tag', () => {
  assert.notEqual(D.ruleProblem({ action: 'pause' }), '');
  assert.notEqual(D.ruleProblem({ action: 'delete' }), '', 'a deletion aimed at every torrent is refused');
  assert.notEqual(D.ruleProblem({ tag: 'x', action: 'addTag' }), '');
  assert.notEqual(D.ruleProblem({ tag: 'x', action: 'removeTag', actionTag: ' ' }), '');
  assert.notEqual(D.ruleProblem({ minRatio: 3, maxRatio: 1, action: 'pause' }), '');
  assert.equal(D.ruleProblem({ tag: 'x', action: 'addTag', actionTag: 'y' }), '');
  assert.equal(D.ruleProblem({ states: 'paused', action: 'resume' }), '');
  assert.equal(D.ruleProblem({ category: '', action: 'pause' }), '', 'no category is a criterion');
  for (const p of D.presets(D.readCfg())) assert.equal(D.ruleProblem(p), '', p.name);
  for (const p of D.presets(D.readCfg({ farmTag: '' }))) assert.equal(D.ruleProblem(p), '', p.name);
});

// ---------- Aggregation ----------

test('both groups add up uploaded / downloaded and break down what is not seeding', () => {
  const r = compute([
    tor({ tags: 'zen-farm', uploaded: 3000, downloaded: 1000, size: 10, state: 'uploading' }),
    tor({ tags: 'tv, Zen-Farm', uploaded: 1000, downloaded: 1000, size: 20, state: 'queuedUP' }),
    tor({ tags: 'zen-farm', uploaded: 0, downloaded: 0, size: 30, state: 'pausedUP' }),
    tor({ tags: 'zen-farm', size: 40, state: 'error' }),
    tor({ tags: 'zen-farm', size: 50, state: 'checkingUP' }),
    tor({ tags: 'zen-farm', size: 60, state: 'downloading', progress: 0.3 }),
    tor({ tags: 'zen-farm-old', uploaded: 400, downloaded: 200, size: 5 }),
    tor({ tags: '', uploaded: 50, downloaded: 0, size: 5 })
  ]);
  const f = r.farm;
  assert.deepEqual([f.n, f.size, f.up, f.down, f.ratio], [6, 210, 4000, 5000, 0.8]);
  assert.deepEqual([f.seed, f.queued, f.paused, f.error, f.check, f.dl], [1, 1, 1, 1, 1, 1]);
  const o = r.other;
  assert.deepEqual([o.n, o.size, o.up, o.down, o.ratio, o.seed], [2, 10, 450, 200, 2.25, 2]);
  assert.equal(r.hasFarm, true);
  assert.deepEqual([r.total.n, r.total.up, r.total.down], [8, 4450, 5200]);
});

test('the ratio of a group is Infinity when nothing was downloaded, and a missing farm tag means one group', () => {
  const r = compute([tor({ tags: '', uploaded: 50, downloaded: 0 })]);
  assert.equal(r.other.ratio, Infinity);
  assert.equal(r.farm.n, 0);
  assert.equal(r.farm.ratio, Infinity);
  assert.equal(r.global.ratio, Infinity);
  assert.equal(compute([]).global.ratio, null, 'nothing at all: no ratio');
  const one = compute([tor({ tags: 'zen-farm' })], {}, { farmTag: '' });
  assert.equal(one.hasFarm, false);
  assert.deepEqual([one.farm.n, one.other.n], [0, 1]);
});

test('the global figures come from the server state when it has them', () => {
  const list = [tor({ uploaded: 100, downloaded: 200 })];
  const own = compute(list);
  assert.deepEqual([own.global.up, own.global.down, own.global.ratio], [100, 200, 0.5]);
  const sv = compute(list, { alltime_ul: 900, alltime_dl: 300, up_info_speed: 5, dl_info_speed: 6, free_space_on_disk: 123, queueing: true });
  assert.deepEqual([sv.global.up, sv.global.down, sv.global.ratio, sv.global.upSpeed, sv.global.dlSpeed, sv.global.free, sv.global.queueing], [900, 300, 3, 5, 6, 123, true]);
  assert.equal(own.global.free, null);
});

test('idle torrents count from last_activity, or from added_on when it is unknown', () => {
  const list = [
    tor({ last_activity: NOW - 40 * DAY }),
    tor({ last_activity: NOW - 5 * DAY, added_on: NOW - 90 * DAY }),
    tor({ last_activity: 0, added_on: NOW - 40 * DAY }),
    tor({ last_activity: -1, added_on: NOW - 3 * DAY }),
    tor({ last_activity: 0, added_on: 0 })
  ];
  assert.equal(compute(list).other.idle, 2);
  assert.equal(compute(list, {}, { idleDays: 4 }).other.idle, 3);
});

test('an unknown state is transitional: never counted as seeding', () => {
  const r = compute([tor({ state: 'frobnicating' }), tor({ state: 'unknown' })]);
  assert.deepEqual([r.other.seed, r.other.check], [0, 2]);
});

// ---------- Hit-and-run ----------

const verdict = (over, opts) => {
  const r = compute([tor(over)], {}, opts);
  return r.hr.length ? 'hr' : r.seedMin.length ? 'seedMin' : '';
};

test('hit-and-run: the seed time and ratio boundaries', () => {
  const risky = { state: 'pausedUP', seeding_time: 10 * H, uploaded: 100, downloaded: 1000 };
  assert.equal(verdict(risky), 'hr');
  assert.equal(verdict({ ...risky, seeding_time: 72 * H - 1 }), 'hr', 'one second short of the minimum');
  assert.equal(verdict({ ...risky, seeding_time: 72 * H }), '', 'minimum reached');
  assert.equal(verdict({ ...risky, uploaded: 999 }), 'hr', 'ratio 0.999');
  assert.equal(verdict({ ...risky, uploaded: 1000 }), '', 'a ratio at the safe ratio is safe');
  assert.equal(verdict({ ...risky, ratio: 1.5 }), '', 'the ratio reported by qBittorrent wins');
  assert.equal(verdict({ ...risky, ratio: -1 }), 'hr', 'an invalid reported ratio is recomputed');
  assert.equal(verdict({ ...risky, progress: 0.99 }), '', 'unfinished torrents are not hit-and-run');
  assert.equal(verdict(risky, { minSeedHours: 5 }), '', 'the minimum is a setting');
  assert.equal(verdict(risky, { minSeedHours: 24 }), 'hr');
  assert.equal(verdict(risky, { safeRatio: 0.05 }), '', 'the safe ratio is a setting');
  assert.equal(verdict(risky, { safeRatio: 0 }), '', 'a safe ratio of 0 flags nothing');
});

test('hit-and-run: only a torrent that is not seeding is an alert, a seeding one is informational', () => {
  const young = { seeding_time: 10 * H, uploaded: 100, downloaded: 1000 };
  for (const state of ['pausedUP', 'stoppedUP', 'queuedUP', 'error', 'missingFiles', 'checkingUP']) assert.equal(verdict({ ...young, state }), 'hr', state);
  for (const state of ['uploading', 'stalledUP', 'forcedUP']) assert.equal(verdict({ ...young, state }), 'seedMin', state);
  const r = compute([tor({ ...young, state: 'pausedUP' }), tor({ ...young, state: 'queuedUP' }), tor({ ...young, state: 'error' }), tor({ ...young, state: 'stalledUP', seeding_time: 70 * H }), tor({ ...young, state: 'uploading' })]);
  assert.deepEqual(r.hrN, { paused: 1, queued: 1, other: 1 });
  assert.equal(r.hr.length, 3);
  assert.equal(r.seedMin.length, 2);
  assert.equal(r.minRemaining, 2 * H, 'the shortest time left among the torrents still seeding');
  assert.equal(r.alertCount, 2, 'hit-and-run and errors; the seed minimum is informational');
  assert.equal(compute([tor({})]).minRemaining, null);
});

test('hit-and-run: an own upload (downloaded = 0) needs 48 h whatever the minimum is', () => {
  const own = { state: 'pausedUP', downloaded: 0, uploaded: 0, seeding_time: 47 * H };
  assert.equal(verdict(own), 'hr');
  assert.equal(verdict({ ...own, seeding_time: 48 * H }), '');
  assert.equal(verdict({ ...own, seeding_time: 48 * H - 1 }, { minSeedHours: 10 }), 'hr', 'a short minimum does not shorten the 48 h');
  assert.equal(verdict({ ...own, seeding_time: 100 * H }, { minSeedHours: 200 }), '', 'a long minimum does not lengthen them');
  assert.equal(verdict({ ...own, uploaded: 5000 }), '', 'an upload that already shared is not at risk');
  assert.equal(verdict({ ...own, state: 'stalledUP' }), 'seedMin');
});

test('C411 is recognised by the tracker host or the magnet link, and no information counts', () => {
  const risky = { state: 'pausedUP', seeding_time: 0, uploaded: 0 };
  const hr = (over) => verdict({ ...risky, ...over }) === 'hr';
  assert.equal(hr({ tracker: C411 }), true);
  assert.equal(hr({ tracker: 'https://TRACKER.C411.ORG:443/announce/' + PASS }), true, 'any case, with a port');
  assert.equal(hr({ tracker: 'https://tracker.example.org/announce/c411-' + PASS }), false, 'c411 in the path is not the host');
  assert.equal(hr({ tracker: 'https://c411:' + PASS + '@tracker.example.org/announce' }), false, 'nor in the credentials');
  assert.equal(hr({ tracker: '', magnet_uri: 'magnet:?xt=urn:btih:abc&dn=x&tr=' + encodeURIComponent(C411) }), true, 'a paused torrent: the magnet link lists the trackers');
  assert.equal(hr({ tracker: '', magnet_uri: 'magnet:?xt=urn:btih:abc&tr=' + C411 }), true, 'a tr value that is not encoded');
  assert.equal(hr({ tracker: '', magnet_uri: 'magnet:?xt=urn:btih:abc&tr=' + encodeURIComponent('https://tracker.example.org/announce/k') }), false);
  assert.equal(hr({ tracker: 'https://tracker.example.org/announce', magnet_uri: 'magnet:?tr=' + encodeURIComponent(C411) }), true, 'one of several trackers');
  assert.equal(hr({ tracker: '', magnet_uri: 'magnet:?xt=urn:btih:abc' }), true, 'a magnet link without tracker is no information');
  assert.equal(hr({ tracker: '' }), true, 'no information at all: a false alarm beats a missed hit-and-run');
});

test('no result carries a tracker URL or its passkey', () => {
  const list = [
    tor({ tags: 'zen-farm', state: 'pausedUP', seeding_time: 0, magnet_uri: 'magnet:?xt=urn:btih:a&tr=' + encodeURIComponent(C411) }),
    tor({ tags: 'zen-farm', state: 'queuedUP', seeding_time: 0 }),
    tor({ state: 'error', tracker: '', magnet_uri: 'magnet:?tr=' + encodeURIComponent(C411) }),
    tor({ state: 'stalledUP', seeding_time: 1, tracker: 'https://tracker.example.org/announce/' + PASS })
  ];
  const m = mapOf(list);
  const res = D.compute(m, { queueing: true }, undefined, NOW);
  assert.ok(res.hr.length + res.queued.length + res.errors.length >= 3);
  const out = [JSON.stringify(res)];
  for (const rule of [{ tag: 'zen-farm', action: 'pause' }, { minRatio: 0, action: 'deleteFiles' }, { idleDays: 1, action: 'addTag', actionTag: 'x' }]) out.push(JSON.stringify(D.match(m, rule, undefined, NOW + 90 * DAY)));
  for (const s of out) {
    assert.ok(!s.includes(PASS), 'passkey');
    assert.ok(!/c411\.org|announce|magnet:/.test(s), 'tracker data');
  }
});

test('queued and failing torrents are listed; the queue alert needs queueing on', () => {
  const list = [tor({ state: 'queuedUP' }), tor({ state: 'queuedUP' }), tor({ state: 'queuedDL', progress: 0.2 }), tor({ state: 'error' }), tor({ state: 'missingFiles', progress: 0.5 }), tor({ state: 'stalledUP' })];
  const on = compute(list, { queueing: true });
  assert.deepEqual([on.counts.queued, on.counts.errors, on.alertCount], [2, 2, 2]);
  assert.equal(on.other.queued, 3);
  const off = compute(list, { queueing: false });
  assert.deepEqual([off.counts.queued, off.counts.errors, off.alertCount], [0, 2, 1]);
  assert.equal(off.other.queued, 3, 'the states are still counted');
});

// ---------- Rules ----------

test('a rule matches by tag, idle time, ratio, category and state, and skips what the action would not change', () => {
  const a = tor({ tags: 'zen-farm', last_activity: NOW - 40 * DAY, size: 100 });
  const b = tor({ tags: 'zen-farm, inactif', last_activity: NOW - 40 * DAY, size: 200 });
  const c = tor({ tags: 'zen-farm', last_activity: NOW - DAY, size: 400 });
  const d = tor({ tags: '', last_activity: NOW - 40 * DAY, size: 800 });
  const m = mapOf([a, b, c, d]);
  const add = D.match(m, { tag: 'zen-farm', idleDays: 30, action: 'addTag', actionTag: 'inactif' }, undefined, NOW);
  assert.deepEqual(add, { hashes: [a.hash], size: 100, protected: 0 });
  const rem = D.match(m, { tag: 'zen-farm', idleDays: 30, action: 'removeTag', actionTag: 'inactif' }, undefined, NOW);
  assert.deepEqual(rem.hashes, [b.hash]);
  assert.deepEqual(D.match(m, { notTag: 'ZEN-FARM', idleDays: 30, action: 'pause' }, undefined, NOW).hashes, [d.hash]);
  assert.deepEqual(D.match(m, { idleDays: 30, action: 'pause' }, { idleDays: 1 }, NOW).hashes.sort(), hashes([a, b, d]));
});

test('the manual rule of the second preset only touches seeding torrents outside the farm', () => {
  const seeding = tor({ state: 'uploading', ratio: 3, seeding_time: 200 * H });
  const paused = tor({ state: 'pausedUP', ratio: 3, seeding_time: 200 * H });
  const farm = tor({ state: 'uploading', ratio: 3, seeding_time: 200 * H, tags: 'zen-farm' });
  const lowRatio = tor({ state: 'uploading', ratio: 1.2, seeding_time: 200 * H });
  const young = tor({ state: 'uploading', ratio: 3, seeding_time: 100 * H });
  const m = mapOf([seeding, paused, farm, lowRatio, young]);
  const preset = D.presets(D.readCfg())[1];
  assert.equal(preset.action, 'pause');
  assert.deepEqual(D.match(m, preset, undefined, NOW).hashes, [seeding.hash]);
  assert.deepEqual(D.match(m, { states: 'paused', action: 'resume' }, undefined, NOW).hashes, [paused.hash]);
  assert.equal(D.match(m, { minRatio: 0, action: 'pause' }, undefined, NOW).hashes.length, 4, 'what is already paused is left alone');
  assert.equal(D.match(m, { minRatio: 0, action: 'resume' }, undefined, NOW).hashes.length, 1);
  assert.deepEqual(D.match(m, { maxRatio: 2, action: 'pause' }, undefined, NOW).hashes, [lowRatio.hash]);
});

test('categories: a name, none at all, or any', () => {
  const films = tor({ category: 'films' });
  const none = tor({ category: '' });
  const tv = tor({ category: 'tv' });
  const m = mapOf([films, none, tv]);
  const by = (category) => D.match(m, { category: category, minRatio: 0, action: 'pause' }, undefined, NOW).hashes;
  assert.deepEqual(by('films'), [films.hash]);
  assert.deepEqual(by(''), [none.hash]);
  assert.equal(by(null).length, 3);
});

test('deletions spare incomplete torrents and those still at hit-and-run risk', () => {
  const done = tor({ tags: 'x', seeding_time: 100 * H });
  const rich = tor({ tags: 'x', seeding_time: H, ratio: 2 });
  const young = tor({ tags: 'x', seeding_time: H, ratio: 0.2 });
  const partial = tor({ tags: 'x', progress: 0.4, seeding_time: 500 * H, ratio: 5 });
  const own = tor({ tags: 'x', downloaded: 0, uploaded: 0, seeding_time: 49 * H });
  const ownYoung = tor({ tags: 'x', downloaded: 0, uploaded: 0, seeding_time: 20 * H });
  const m = mapOf([done, rich, young, partial, own, ownYoung]);
  for (const action of ['delete', 'deleteFiles']) {
    const r = D.match(m, { tag: 'x', action: action }, undefined, NOW);
    assert.deepEqual(r.hashes.slice().sort(), hashes([done, rich, own]), action);
    assert.equal(r.protected, 3, action);
    assert.equal(r.size, 3000, action);
  }
  const keep = D.match(m, { tag: 'x', action: 'addTag', actionTag: 'y' }, undefined, NOW);
  assert.deepEqual([keep.hashes.length, keep.protected], [6, 0], 'only deletions protect');
  assert.deepEqual(D.match(m, { tag: 'x', action: 'delete' }, { safeRatio: 0.1 }, NOW).protected, 2, 'the safe ratio is a setting');
});

test('the biggest torrents among some hashes, in one pass', () => {
  const list = [5, 50, 20, 20, 1].map((size) => tor({ size: size }));
  const m = mapOf(list);
  const ids = list.map((t) => t.hash).concat(['gone']);
  assert.deepEqual(D.top(m, ids, 3).map((t) => t.size), [50, 20, 20]);
  assert.equal(D.top(m, ids, 99).length, 5, 'unknown hashes are skipped');
  assert.deepEqual(D.top(m, ids, 0), []);
});

// ---------- Bulk calls ----------

test('bulk calls go 200 hashes at a time, one after the other, with a progress count', async () => {
  const hs = Array.from({ length: 450 }, (_, i) => 'h' + i);
  assert.deepEqual(D.batches(hs).map((b) => b.length), [200, 200, 50]);
  assert.deepEqual(D.batches(hs, 100).map((b) => b.length), [100, 100, 100, 100, 50]);
  assert.deepEqual(D.batches([]), []);
  const sizes = [];
  const progress = [];
  let flying = 0;
  let overlap = 0;
  const res = await D.run(hs, async (b) => {
    overlap = Math.max(overlap, ++flying);
    await new Promise((r) => setImmediate(r));
    sizes.push(b.length);
    flying--;
  }, (done, total) => progress.push([done, total]));
  assert.deepEqual(sizes, [200, 200, 50]);
  assert.equal(overlap, 1, 'sequential');
  assert.deepEqual(res, { done: 450, total: 450, error: null });
  assert.deepEqual(progress, [[0, 450], [200, 450], [400, 450], [450, 450]]);
});

test('a failing batch stops the run and says how many hashes were done', async () => {
  const hs = Array.from({ length: 450 }, (_, i) => 'h' + i);
  let calls = 0;
  const res = await D.run(hs, async () => {
    if (++calls === 2) throw new Error('boom');
  });
  assert.equal(calls, 2, 'no batch after the failure');
  assert.equal(res.done, 200);
  assert.equal(res.total, 450);
  assert.equal(res.error.message, 'boom');
  assert.deepEqual(await D.run([], async () => assert.fail('no call')), { done: 0, total: 0, error: null });
});

test('each rule action posts the right call, and files go only with an explicit deleteFiles', () => {
  const { Q, D: dash } = boot();
  const calls = [];
  for (const k of ['pause', 'resume', 'remove', 'addTags', 'removeTags']) Q.act[k] = (...a) => calls.push([k].concat(a));
  const hs = ['a', 'b'];
  const rule = (o) => Object.assign({ tag: 'x' }, o);
  dash.postFor(rule({ action: 'addTag', actionTag: 'inactif' }))(hs);
  dash.postFor(rule({ action: 'removeTag', actionTag: 'inactif' }))(hs);
  dash.postFor(rule({ action: 'pause' }))(hs);
  dash.postFor(rule({ action: 'resume' }))(hs);
  dash.postFor(rule({ action: 'delete' }), true)(hs);
  dash.postFor(rule({ action: 'deleteFiles' }), false)(hs);
  dash.postFor(rule({ action: 'deleteFiles' }))(hs);
  dash.postFor(rule({ action: 'deleteFiles' }), true)(hs);
  assert.deepEqual(calls, [
    ['addTags', hs, ['inactif']],
    ['removeTags', hs, ['inactif']],
    ['pause', hs],
    ['resume', hs],
    ['remove', hs, false],
    ['remove', hs, false],
    ['remove', hs, false],
    ['remove', hs, true]
  ]);
});

// ---------- Performance ----------

function bigStore() {
  const list = [];
  for (let i = 0; i < 21000; i++) {
    const farm = i % 100 !== 0;
    const k = i % 29;
    list.push({
      hash: 'x' + i,
      name: 'Torrent ' + i,
      size: 1e6 + i,
      progress: 1,
      state: k === 0 ? 'queuedUP' : k === 1 ? 'pausedUP' : k === 2 ? 'error' : k === 3 ? 'stalledUP' : 'uploading',
      tags: farm ? (i % 7 === 0 ? 'zen-farm, tv' : 'zen-farm') : '',
      category: farm ? '' : 'films',
      uploaded: i * 10,
      downloaded: 1e6,
      seeding_time: k < 3 ? H * (i % 80) : 90 * H,
      last_activity: NOW - (i % 60) * DAY,
      added_on: NOW - 100 * DAY,
      tracker: k === 1 ? '' : C411
    });
  }
  return mapOf(list);
}
const medianMs = (build, fn) => {
  const t = [];
  for (let i = 0; i < 5; i++) {
    const input = build();
    const t0 = process.hrtime.bigint();
    fn(input);
    t.push(Number(process.hrtime.bigint() - t0) / 1e6);
  }
  return t.sort((x, y) => x - y)[2];
};

test('one pass over 21 000 torrents is fast: aggregation and rule matching', () => {
  const res = D.compute(bigStore(), { queueing: true }, undefined, NOW);
  assert.equal(res.farm.n + res.other.n, 21000);
  assert.ok(res.hr.length > 0 && res.queued.length > 0 && res.errors.length > 0);
  const compute21 = medianMs(bigStore, (m) => D.compute(m, { queueing: true }, undefined, NOW));
  const match21 = medianMs(bigStore, (m) => D.match(m, { tag: 'zen-farm', idleDays: 30, action: 'addTag', actionTag: 'inactif' }, undefined, NOW));
  const del21 = medianMs(bigStore, (m) => D.match(m, { tag: 'zen-farm', states: 'seeding', action: 'delete' }, undefined, NOW));
  console.log('# 21 000 torrents, median of 5 cold runs: compute ' + compute21.toFixed(1) + ' ms, match ' + match21.toFixed(1) + ' ms, delete preview ' + del21.toFixed(1) + ' ms');
  assert.ok(compute21 < 80, 'compute ' + compute21);
  assert.ok(match21 < 80, 'match ' + match21);
  assert.ok(del21 < 80, 'match with protection ' + del21);
});

// ---------- Page ----------

// Opens the dashboard on `torrents` in a fake qBittorrent tab; `tick(ms)` moves the clock and delivers one sync.
function openPage(torrents) {
  FakeNode.prototype.remove = function () {
    if (this.parentNode) this.parentNode.removeChild(this);
  };
  FakeNode.prototype.getBoundingClientRect = () => ({});
  const b = boot();
  const { App, Q, D: dash, document } = b;
  const root = document.createElement('div');
  const home = { el: document.createElement('div') };
  root.appendChild(home.el);
  App.tabs.qbit = { name: 'qbit', el: root, stack: [home], hooks: {} };
  App.state.tab = 'qbit';
  App.state.fg = true;
  App.config = { qbUrl: 'http://qb.test:8080' };
  // The refresh loop wakes on every visibility change: a sync that never answers keeps it quiet.
  App.http = async (req) => (/\/sync\/maindata/.test(req.url) ? new Promise(() => {}) : { status: 200, text: JSON.stringify({ max_active_uploads: 3, max_active_torrents: 5 }) });
  Q.store.loaded = true;
  Q.store.torrents = torrents;
  Q.store.server = { queueing: true, up_info_speed: 10, dl_info_speed: 20 };
  b.passes = 0;
  const real = dash.compute;
  dash.compute = (...a) => {
    b.passes++;
    return real(...a);
  };
  b.clock = NOW * 1000;
  b.realNow = Date.now;
  Date.now = () => b.clock;
  b.tick = (ms) => {
    b.clock += ms;
    App.emit('qbit');
  };
  b.stack = App.tabs.qbit.stack;
  return b;
}
const countNodes = (n) => 1 + n.childNodes.reduce((s, c) => s + countNodes(c), 0);
const rowsOf = (root) => {
  let n = 0;
  (function walk(x) {
    for (const c of x.childNodes) {
      if (c.classList && c.classList.contains('dash-row')) n++;
      walk(c);
    }
  })(root);
  return n;
};

test('the dashboard builds aggregates and a few sample rows, never a node per torrent', () => {
  const b = openPage(bigStore());
  try {
    b.Q.openDash();
    const page = b.stack[1];
    assert.equal(b.passes, 1);
    const text = page.el.textContent;
    for (const s of ['Ratio global', 'Zen-farm', 'Autres', 'Risque de hit-and-run', 'En file d’attente : pas de Zen', 'En erreur ou fichiers manquants', 'Règles de nettoyage']) assert.ok(text.includes(s), s);
    const rows = rowsOf(page.el);
    assert.ok(rows > 0 && rows <= 50, 'sample rows: ' + rows);
    assert.ok(countNodes(page.el) < 1000, 'nodes: ' + countNodes(page.el));
    assert.match(page.el.textContent, /alertes?/);
  } finally {
    Date.now = b.realNow;
  }
});

test('the dashboard recomputes at most every 2 s, and not at all while covered, hidden or closed', async () => {
  const hr = tor({ state: 'pausedUP', seeding_time: 0, size: 5000 });
  const b = openPage(mapOf([hr, tor({})]));
  const { App } = b;
  try {
    b.Q.openDash();
    await new Promise((r) => setImmediate(r));
    const page = b.stack[1];
    assert.equal(b.passes, 1, 'opening: one pass; the limits arriving repaint without another');
    assert.match(page.el.textContent, /1 alerte/, 'the sub line of the page');
    assert.match(page.el.textContent, /Risque de hit-and-run1/, 'the alert and its count');
    b.tick(500);
    b.tick(500);
    assert.equal(b.passes, 1, 'syncs within 2 s');
    b.tick(1000);
    assert.equal(b.passes, 2);

    const card = () => page.el.childNodes[1].childNodes[1].firstChild;
    const first = card();
    b.tick(2000);
    assert.equal(b.passes, 3);
    assert.equal(card(), first, 'unchanged numbers do not rebuild the section');
    b.Q.store.server = { queueing: true, up_info_speed: 99, dl_info_speed: 20 };
    b.tick(2000);
    assert.notEqual(card(), first, 'a new speed rebuilds it');

    const before = b.passes;
    b.stack.push({ el: App.h('div'), tab: 'qbit' });
    b.tick(5000);
    assert.equal(b.passes, before, 'another page covers the dashboard');
    App.state.fg = false;
    b.stack.pop();
    b.tick(5000);
    assert.equal(b.passes, before, 'app in the background');
    App.state.fg = true;
    App.state.tab = 'arr';
    b.tick(5000);
    assert.equal(b.passes, before, 'other tab');
    App.state.tab = 'qbit';

    assert.ok(App.pop('qbit'), 'back to the list');
    const closed = b.passes;
    b.tick(5000);
    b.tick(5000);
    assert.equal(b.passes, closed, 'a closed dashboard no longer listens');
  } finally {
    Date.now = b.realNow;
  }
});

// ---------- Banner ----------

test('the banner shows the at-risk count, runs at most every 10 s, and does nothing while the list is not on screen', () => {
  const { App, Q, D: dash, document } = boot();
  const parent = document.createElement('div');
  const el = document.createElement('div');
  el.classList.add('hidden');
  parent.appendChild(el);
  App.tabs.qbit = { name: 'qbit', el: parent, stack: [{ el: parent }], hooks: {} };
  App.state.tab = 'qbit';
  App.state.fg = true;
  Q.store.loaded = true;
  const risky = tor({ state: 'pausedUP', seeding_time: 0 });
  Q.store.torrents = mapOf([risky, tor({})]);
  let passes = 0;
  const real = dash.compute;
  dash.compute = (...a) => {
    passes++;
    return real(...a);
  };
  let clock = NOW * 1000;
  const realNow = Date.now;
  Date.now = () => clock;
  try {
    dash.banner(el);
    App.emit('qbit');
    assert.equal(passes, 1);
    assert.equal(el.classList.contains('hidden'), false);
    assert.match(el.textContent, /1 torrent à risque de hit-and-run/);
    clock += 5000;
    App.emit('qbit');
    assert.equal(passes, 1, 'at most one pass every 10 s');

    clock += 6000;
    App.state.tab = 'arr';
    App.emit('qbit');
    App.emit('visibility');
    assert.equal(passes, 1, 'qBittorrent tab hidden');

    App.state.tab = 'qbit';
    App.tabs.qbit.stack.push({ el: document.createElement('div') });
    App.emit('qbit');
    assert.equal(passes, 1, 'another page covers the list');

    App.tabs.qbit.stack.pop();
    App.emit('visibility');
    assert.equal(passes, 2, 'back on the list: one fresh pass');

    risky.state = 'stalledUP';
    clock += 11000;
    App.emit('qbit');
    assert.equal(passes, 3);
    assert.equal(el.classList.contains('hidden'), true, 'nothing at risk any more');
    assert.equal(el.textContent, '');
  } finally {
    Date.now = realNow;
  }
});
