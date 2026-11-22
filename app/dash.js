/* Arr411 - qBittorrent: ratio and Zen dashboard, hit-and-run alerts and manual clean-up rules. */
(function () {
  'use strict';
  const App = window.App;
  const h = App.h;
  const fmt = App.fmt;
  const qb = App.qb;
  const Q = App.qbit;
  const A = Q.act;
  const D = (App.dash = {});

  const HOUR = 3600;
  const DAY = 86400;
  // C411 asks 48 h of seeding for one's own uploads, whatever the minimum for downloaded torrents is.
  const UPLOAD_MIN = 48 * HOUR;
  const BATCH = 200;
  const INLINE = 10;
  const SAMPLE = 50;
  const MAX_RULES = 10;
  const DASH_EVERY = 2000;
  const BANNER_EVERY = 10000;
  const LOW_SPACE = 10 * 1024 * 1024 * 1024;
  const STATES = ['all', 'paused', 'error', 'seeding'];
  const ACTIONS = ['addTag', 'removeTag', 'pause', 'resume', 'delete', 'deleteFiles'];
  const STATE_LABEL = { all: 'Tous les états', paused: 'En pause', error: 'En erreur', seeding: 'En seed' };
  const ACTION_LABEL = {
    addTag: 'Ajouter une étiquette',
    removeTag: 'Retirer une étiquette',
    pause: 'Mettre en pause',
    resume: 'Reprendre',
    delete: 'Supprimer les torrents (fichiers conservés)',
    deleteFiles: 'Supprimer les torrents et leurs fichiers'
  };

  // What a torrent is doing for the tracker: only « seed » earns seed time, ratio and Zen.
  const KIND = Object.create(null);
  const KINDS = {
    seed: ['uploading', 'forcedUP', 'stalledUP'],
    queued: ['queuedUP', 'queuedDL'],
    paused: ['pausedUP', 'stoppedUP', 'pausedDL', 'stoppedDL'],
    error: ['error', 'missingFiles'],
    check: ['checkingUP', 'checkingDL', 'checkingResumeData', 'allocating', 'moving', 'unknown'],
    dl: ['downloading', 'forcedDL', 'metaDL', 'forcedMetaDL', 'stalledDL']
  };
  for (const k in KINDS) for (const s of KINDS[k]) KIND[s] = k;
  // A state qBittorrent adds later is treated as transitional.
  const kindOf = (t) => KIND[t.state] || 'check';

  let latest = null;
  let latestAt = 0;
  let jobUI = null;
  let busy = false;

  const nowSec = () => Date.now() / 1000;
  const ratioTxt = (r) => (r == null ? '—' : r >= 9999 ? '∞' : fmt.ratio(r));

  // ---------- Settings ----------
  function toNum(v) {
    if (typeof v === 'number') return isFinite(v) ? v : null;
    if (typeof v !== 'string') return null;
    const s = v.trim().replace(',', '.');
    if (!s) return null;
    const n = Number(s);
    return isFinite(n) ? n : null;
  }
  function clamp(v, lo, hi, d) {
    const n = toNum(v);
    return n == null ? d : Math.min(hi, Math.max(lo, n));
  }
  const str = (v, max) => (typeof v === 'string' ? v.trim().slice(0, max) : '');

  D.cleanRule = function (r) {
    if (!r || typeof r !== 'object') return null;
    const minRatio = toNum(r.minRatio);
    const maxRatio = toNum(r.maxRatio);
    return {
      name: str(r.name, 60) || 'Règle',
      tag: str(r.tag, 100),
      notTag: str(r.notTag, 100),
      category: typeof r.category === 'string' ? r.category.trim().slice(0, 200) : null,
      minSeedHours: Math.max(0, toNum(r.minSeedHours) || 0),
      minRatio: minRatio != null && minRatio >= 0 ? minRatio : null,
      maxRatio: maxRatio != null && maxRatio >= 0 ? maxRatio : null,
      idleDays: Math.max(0, toNum(r.idleDays) || 0),
      states: STATES.indexOf(r.states) >= 0 ? r.states : 'all',
      action: ACTIONS.indexOf(r.action) >= 0 ? r.action : 'addTag',
      actionTag: str(r.actionTag, 100).replace(/,/g, '')
    };
  };
  // Stored settings are read through here: wrong types fall back to the defaults, numbers are clamped.
  D.readCfg = function (raw) {
    const o = raw && typeof raw === 'object' ? raw : {};
    const rules = [];
    if (Array.isArray(o.rules)) {
      for (const r of o.rules) {
        const c = D.cleanRule(r);
        if (c && rules.length < MAX_RULES) rules.push(c);
      }
    }
    return {
      farmTag: typeof o.farmTag === 'string' ? o.farmTag.trim().slice(0, 100) : 'zen-farm',
      minSeedHours: clamp(o.minSeedHours, 0, 8760, 72),
      safeRatio: clamp(o.safeRatio, 0, 100, 1),
      idleDays: clamp(o.idleDays, 1, 3650, 30),
      rules: rules
    };
  };
  D.ruleProblem = function (rule) {
    const r = D.cleanRule(rule);
    if (!r) return 'Règle invalide.';
    if ((r.action === 'addTag' || r.action === 'removeTag') && !r.actionTag) return 'Indique l’étiquette à ajouter ou à retirer.';
    const any = r.tag || r.notTag || r.category !== null || r.minSeedHours > 0 || r.minRatio !== null || r.maxRatio !== null || r.idleDays > 0 || r.states !== 'all';
    if (!any) return 'Ajoute au moins un critère : sans critère, la règle viserait tous les torrents.';
    if (r.minRatio !== null && r.maxRatio !== null && r.minRatio > r.maxRatio) return 'Le ratio minimum dépasse le ratio maximum.';
    return '';
  };
  D.presets = function (c) {
    const farm = c.farmTag;
    return [
      D.cleanRule({ name: (farm ? 'Zen-farm inactifs' : 'Inactifs') + ' depuis ' + c.idleDays + ' jours', tag: farm, idleDays: c.idleDays, action: 'addTag', actionTag: 'inactif' }),
      D.cleanRule({ name: (farm ? 'Hors zen-farm, ratio' : 'Ratio') + ' ≥ 2 et seed ≥ 7 jours', notTag: farm, minRatio: 2, minSeedHours: 168, states: 'seeding', action: 'pause' })
    ];
  };
  const cfg = () => D.readCfg(App.config && App.config.dash);
  // saveCfg replaces the whole « dash » key: keep what is not being changed (the rules, notably).
  function saveDash(patch) {
    latest = null;
    const next = Object.assign({}, cfg(), patch);
    App.saveCfg({ dash: next });
    return next;
  }

  // ---------- Torrent tests ----------
  // Tag test memoised per distinct tags string: thousands of torrents share a handful of them.
  function tagTester(tag) {
    const want = String(tag || '').trim().toLowerCase();
    if (!want) return null;
    const memo = new Map();
    return (tags) => {
      let v = memo.get(tags);
      if (v === undefined) {
        v = false;
        if (tags) {
          const parts = String(tags).split(',');
          for (let i = 0; i < parts.length; i++) {
            if (parts[i].trim().toLowerCase() === want) {
              v = true;
              break;
            }
          }
        }
        if (memo.size > 4000) memo.clear();
        memo.set(tags, v);
      }
      return v;
    };
  }

  // Only the host of a tracker URL is ever read: the rest of it carries the passkey, which is never shown, logged or returned.
  const HOST_RE = /^[a-z][a-z0-9+.-]*:\/\/(?:[^/?#@]*@)?([^/?#:]+)/i;
  const c411Cache = new WeakMap();
  function hostIsC411(url) {
    const m = HOST_RE.exec(String(url));
    return !!m && m[1].toLowerCase().indexOf('c411') >= 0;
  }
  function isC411(t) {
    const tr = typeof t.tracker === 'string' ? t.tracker : '';
    const mg = typeof t.magnet_uri === 'string' ? t.magnet_uri : '';
    const e = c411Cache.get(t);
    if (e && e.tr === tr && e.mg === mg) return e.v;
    let v = null;
    if (tr) v = hostIsC411(tr);
    // A paused torrent has no current tracker: its magnet link still lists them.
    if (v !== true && mg) {
      const re = /[?&]tr=([^&#]*)/g;
      let seen = false;
      let m;
      while ((m = re.exec(mg))) {
        seen = true;
        let u = m[1];
        try {
          u = decodeURIComponent(u);
        } catch (_) { /* keep the raw value */ }
        if (hostIsC411(u)) {
          v = true;
          break;
        }
      }
      if (v !== true && seen) v = false;
    }
    // No tracker information at all: a false alarm is better than a missed hit-and-run.
    if (v === null) v = true;
    c411Cache.set(t, { tr: tr, mg: mg, v: v });
    return v;
  }

  function ratioOf(t) {
    const r = t.ratio;
    if (typeof r === 'number' && r >= 0) return r;
    const d = +t.downloaded || 0;
    const u = +t.uploaded || 0;
    return d > 0 ? u / d : u > 0 ? 9999 : 0;
  }
  const needOf = (t, c) => ((+t.downloaded || 0) > 0 ? c.minSeedHours * HOUR : UPLOAD_MIN);

  // ---------- Aggregation ----------
  const newGroup = () => ({ n: 0, size: 0, up: 0, down: 0, seed: 0, queued: 0, paused: 0, error: 0, check: 0, dl: 0, idle: 0, ratio: Infinity });

  // One pass over the torrents: both groups, the hit-and-run candidates and the other alerts (as hashes).
  D.compute = function (torrents, server, opts, now) {
    const c = D.readCfg(opts);
    const sv = server || {};
    if (now == null) now = nowSec();
    const isFarm = tagTester(c.farmTag);
    const idleSecs = c.idleDays * DAY;
    const minSecs = c.minSeedHours * HOUR;
    const safe = c.safeRatio;
    const farm = newGroup();
    const other = newGroup();
    const hr = [];
    const hrN = { paused: 0, queued: 0, other: 0 };
    const seedMin = [];
    const queuedUp = [];
    const errors = [];
    let minRemaining = Infinity;
    for (const k in torrents) {
      const t = torrents[k];
      const kind = kindOf(t);
      const g = isFarm && isFarm(t.tags) ? farm : other;
      const down = +t.downloaded || 0;
      g.n++;
      g.size += +t.size || 0;
      g.up += +t.uploaded || 0;
      g.down += down;
      g[kind]++;
      // An unknown last activity counts from the day the torrent was added.
      const la = +t.last_activity;
      const since = la > 0 ? la : +t.added_on;
      if (since > 0 && now - since > idleSecs) g.idle++;
      if (kind === 'error') errors.push(t.hash || k);
      else if (t.state === 'queuedUP') queuedUp.push(t.hash || k);
      if (t.progress >= 1) {
        const secs = +t.seeding_time || 0;
        const need = down > 0 ? minSecs : UPLOAD_MIN;
        if (secs < need && ratioOf(t) < safe && isC411(t)) {
          if (kind === 'seed') {
            seedMin.push(t.hash || k);
            if (need - secs < minRemaining) minRemaining = need - secs;
          } else {
            hr.push(t.hash || k);
            hrN[kind === 'paused' || kind === 'queued' ? kind : 'other']++;
          }
        }
      }
    }
    farm.ratio = farm.down > 0 ? farm.up / farm.down : Infinity;
    other.ratio = other.down > 0 ? other.up / other.down : Infinity;
    const tu = farm.up + other.up;
    const td = farm.down + other.down;
    const hasAll = typeof sv.alltime_ul === 'number' || typeof sv.alltime_dl === 'number';
    const gu = hasAll ? +sv.alltime_ul || 0 : tu;
    const gd = hasAll ? +sv.alltime_dl || 0 : td;
    const queued = sv.queueing ? queuedUp : [];
    return {
      farm: farm,
      other: other,
      hasFarm: !!isFarm,
      total: { n: farm.n + other.n, size: farm.size + other.size, up: tu, down: td },
      global: {
        ratio: gd > 0 ? gu / gd : gu > 0 ? Infinity : null,
        up: gu,
        down: gd,
        upSpeed: +sv.up_info_speed || 0,
        dlSpeed: +sv.dl_info_speed || 0,
        free: typeof sv.free_space_on_disk === 'number' && sv.free_space_on_disk >= 0 ? sv.free_space_on_disk : null,
        queueing: !!sv.queueing
      },
      hr: hr,
      hrN: hrN,
      seedMin: seedMin,
      minRemaining: seedMin.length ? minRemaining : null,
      queued: queued,
      errors: errors,
      counts: { hr: hr.length, seedMin: seedMin.length, queued: queued.length, errors: errors.length },
      // Alerts that need an action; the seed-minimum list is only informative.
      alertCount: (hr.length ? 1 : 0) + (queued.length ? 1 : 0) + (errors.length ? 1 : 0)
    };
  };

  // The n biggest torrents among the hashes, in one pass (no sort of the whole list).
  D.top = function (torrents, hashes, n) {
    const out = [];
    if (!(n > 0)) return [];
    for (let i = 0; i < hashes.length; i++) {
      const t = torrents[hashes[i]];
      if (!t) continue;
      const s = +t.size || 0;
      if (out.length >= n && s <= out[out.length - 1].s) continue;
      let lo = 0;
      let hi = out.length;
      while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (out[mid].s >= s) lo = mid + 1;
        else hi = mid;
      }
      out.splice(lo, 0, { s: s, t: t });
      if (out.length > n) out.pop();
    }
    return out.map((e) => e.t);
  };

  // The torrents a rule would act on. Torrents the action would not change are left out, so the counts are exact.
  D.match = function (torrents, rule, opts, now) {
    const c = D.readCfg(opts);
    const r = D.cleanRule(rule) || D.cleanRule({});
    if (now == null) now = nowSec();
    const hasTag = tagTester(r.tag);
    const lacksTag = tagTester(r.notTag);
    const marked = r.action === 'addTag' || r.action === 'removeTag' ? tagTester(r.actionTag) : null;
    const del = r.action === 'delete' || r.action === 'deleteFiles';
    const want = r.states === 'seeding' ? 'seed' : r.states === 'all' ? null : r.states;
    const minSecs = r.minSeedHours * HOUR;
    const idleSecs = r.idleDays * DAY;
    const hashes = [];
    let size = 0;
    let prot = 0;
    for (const k in torrents) {
      const t = torrents[k];
      if (hasTag && !hasTag(t.tags)) continue;
      if (lacksTag && lacksTag(t.tags)) continue;
      if (r.category !== null && (t.category || '') !== r.category) continue;
      if (minSecs > 0 && !((+t.seeding_time || 0) >= minSecs)) continue;
      if (r.minRatio !== null || r.maxRatio !== null) {
        const ra = ratioOf(t);
        if (r.minRatio !== null && ra < r.minRatio) continue;
        if (r.maxRatio !== null && ra > r.maxRatio) continue;
      }
      if (idleSecs > 0) {
        const la = +t.last_activity;
        const since = la > 0 ? la : +t.added_on;
        if (!(since > 0 && now - since > idleSecs)) continue;
      }
      const kind = kindOf(t);
      if (want && kind !== want) continue;
      if (r.action === 'addTag' && marked && marked(t.tags)) continue;
      if (r.action === 'removeTag' && marked && !marked(t.tags)) continue;
      if (r.action === 'pause' && kind === 'paused') continue;
      if (r.action === 'resume' && kind !== 'paused') continue;
      // Deletions spare what is not finished and what could still count as a hit-and-run.
      if (del && (!(t.progress >= 1) || ((+t.seeding_time || 0) < needOf(t, c) && ratioOf(t) < c.safeRatio))) {
        prot++;
        continue;
      }
      hashes.push(t.hash || k);
      size += +t.size || 0;
    }
    return { hashes: hashes, size: size, protected: prot };
  };

  // ---------- Bulk calls ----------
  D.batches = function (hashes, size) {
    const n = size > 0 ? size : BATCH;
    const out = [];
    for (let i = 0; i < hashes.length; i += n) out.push(hashes.slice(i, i + n));
    return out;
  };
  // Sequential: one batch at a time, stopping at the first error.
  D.run = async function (hashes, post, onProgress, size) {
    const total = hashes.length;
    let done = 0;
    if (onProgress) onProgress(0, total);
    for (const b of D.batches(hashes, size)) {
      try {
        await post(b);
      } catch (e) {
        return { done: done, total: total, error: e || new Error('Erreur') };
      }
      done += b.length;
      if (onProgress) onProgress(done, total);
    }
    return { done: done, total: total, error: null };
  };
  D.postFor = function (rule, files) {
    const r = D.cleanRule(rule) || D.cleanRule({});
    switch (r.action) {
      case 'addTag': return (b) => A.addTags(b, [r.actionTag]);
      case 'removeTag': return (b) => A.removeTags(b, [r.actionTag]);
      case 'pause': return (b) => A.pause(b);
      case 'resume': return (b) => A.resume(b);
      case 'delete': return (b) => A.remove(b, false);
      default: return (b) => A.remove(b, !!files);
    }
  };

  // ---------- Texts ----------
  function actionText(r) {
    switch (r.action) {
      case 'addTag': return 'ajouter l’étiquette « ' + r.actionTag + ' »';
      case 'removeTag': return 'retirer l’étiquette « ' + r.actionTag + ' »';
      case 'pause': return 'mettre en pause';
      case 'resume': return 'reprendre';
      case 'delete': return 'supprimer (fichiers conservés)';
      default: return 'supprimer, avec les fichiers si tu le coches';
    }
  }
  function ruleSummary(r) {
    const p = [];
    if (r.tag) p.push('étiquette ' + r.tag);
    if (r.notTag) p.push('sans l’étiquette ' + r.notTag);
    if (r.category !== null) p.push(r.category === '' ? 'sans catégorie' : 'catégorie ' + r.category);
    if (r.minSeedHours > 0) p.push('seed ≥ ' + fmt.dur(r.minSeedHours * HOUR));
    if (r.minRatio !== null) p.push('ratio ≥ ' + r.minRatio);
    if (r.maxRatio !== null) p.push('ratio ≤ ' + r.maxRatio);
    if (r.idleDays > 0) p.push('inactif depuis plus de ' + r.idleDays + ' j');
    if (r.states !== 'all') p.push(STATE_LABEL[r.states].toLowerCase());
    return (p.join(', ') || 'tous les torrents') + ' → ' + actionText(r);
  }
  function doneText(r, n, files) {
    const w = fmt.plural(n, 'torrent');
    switch (r.action) {
      case 'addTag': return 'Étiquette « ' + r.actionTag + ' » ajoutée à ' + w;
      case 'removeTag': return 'Étiquette « ' + r.actionTag + ' » retirée de ' + w;
      case 'pause': return w + ' mis en pause';
      case 'resume': return w + ' repris';
      case 'delete': return w + ' supprimé' + (n > 1 ? 's' : '') + ', fichiers conservés';
      default: return w + ' supprimé' + (n > 1 ? 's' : '') + (files ? ' avec leurs fichiers' : ', fichiers conservés');
    }
  }
  function infoFor(key, c) {
    if (key === 'hr') return (t) => Q.stateLabel(t) + ' · seed ' + fmt.dur(+t.seeding_time || 0) + ' / ' + fmt.dur(needOf(t, c)) + ' · ratio ' + ratioTxt(ratioOf(t));
    if (key === 'queue') return (t) => 'Ratio ' + ratioTxt(ratioOf(t)) + ' · seed ' + fmt.dur(+t.seeding_time || 0);
    if (key === 'errors') return (t) => Q.stateLabel(t) + (t.progress < 1 ? ' · ' + fmt.pct(t.progress) : '');
    return (t) => Q.stateLabel(t) + ' · ratio ' + ratioTxt(ratioOf(t)) + ' · seed ' + fmt.dur(+t.seeding_time || 0);
  }
  const rowOf = (t, info) => [t.hash, t.name || t.hash, info(t), fmt.bytes(+t.size || 0)];

  // ---------- Shared pieces ----------
  // The aggregation is shared: the banner and the page reuse a recent one instead of starting another pass.
  function aggregate(maxAge) {
    const now = Date.now();
    if (!latest || now - latestAt >= maxAge) {
      latest = D.compute(Q.store.torrents, Q.store.server, cfg(), now / 1000);
      latestAt = now;
    }
    return latest;
  }

  function rowEl(r) {
    return h('button.dash-row', {
      type: 'button',
      onclick: () => {
        App.closeOverlays();
        App.run(() => Q.openDetails(r[0]));
      }
    }, h('span.main', h('span.t.nowrap', r[1]), h('span.s.nowrap', r[2])), h('span.r', r[3]));
  }

  function openList(title, intro, hashes, info) {
    const tors = Q.store.torrents;
    const list = [];
    for (let i = 0; i < hashes.length; i++) if (tors[hashes[i]]) list.push(tors[hashes[i]]);
    list.sort((a, b) => (+b.size || 0) - (+a.size || 0));
    const rows = h('div.dash-rows');
    let ch = null;
    const s = App.sheet({
      title: title + ' · ' + fmt.num(list.length),
      full: true,
      body: [intro ? h('div.dash-text', intro) : null, rows],
      onClose: () => {
        if (ch) ch.dispose();
      }
    });
    ch = App.chunked(rows, list, (t) => rowEl(rowOf(t, info)), 60, s.body);
    return s;
  }

  const ALERT_TITLE = { hr: 'Risque de hit-and-run', queue: 'En file d’attente : pas de Zen', errors: 'En erreur ou fichiers manquants' };
  function openAlertList(key) {
    const res = aggregate(0);
    const hashes = key === 'hr' ? res.hr : key === 'queue' ? res.queued : res.errors;
    return openList(ALERT_TITLE[key], '', hashes, infoFor(key, cfg()));
  }

  async function runJob(label, hashes, post, onProgress) {
    if (busy) throw App.mkErr('Une autre opération est en cours : attends sa fin.');
    busy = true;
    try {
      return await D.run(hashes, post, (done, total) => {
        if (jobUI) jobUI.show(label, done, total);
        if (onProgress) onProgress(done, total);
      });
    } finally {
      busy = false;
      latest = null;
      if (jobUI) jobUI.hide();
      Q.refresh();
    }
  }
  function report(r, okMsg) {
    if (!r.error) {
      App.toast(okMsg, 'ok');
      return;
    }
    App.toast(fmt.num(r.done) + ' / ' + fmt.num(r.total) + ' traités, arrêt sur erreur : ' + ((r.error && r.error.message) || String(r.error)), 'err');
  }
  function needStore() {
    if (!Q.store.loaded) throw App.mkErr('qBittorrent n’est pas connecté.');
  }
  const sumSize = (hashes) => {
    let n = 0;
    for (const x of hashes) n += +(Q.store.torrents[x] || {}).size || 0;
    return n;
  };

  async function resumeRisk() {
    needStore();
    const tors = Q.store.torrents;
    const hs = D.compute(tors, Q.store.server, cfg(), nowSec()).hr.filter((x) => tors[x] && Q.isPaused(tors[x]));
    if (!hs.length) {
      App.toast('Aucun torrent en pause à reprendre', 'ok');
      return;
    }
    const ok = await App.confirm({
      title: 'Reprendre ' + fmt.plural(hs.length, 'torrent') + ' ?',
      message: [h('div', h('b', fmt.plural(hs.length, 'torrent') + ' (' + fmt.bytes(sumSize(hs)) + ')')), h('div', 'En pause, avec un seed minimum non atteint : ils reprennent pour le terminer.')],
      ok: 'Reprendre'
    });
    if (!ok) return;
    report(await runJob('Reprise', hs, (b) => A.resume(b)), fmt.plural(hs.length, 'torrent') + ' repris');
  }
  async function recheckErrors() {
    needStore();
    const hs = aggregate(0).errors.slice();
    if (!hs.length) return;
    const ok = await App.confirm({
      title: 'Revérifier ' + fmt.plural(hs.length, 'torrent') + ' ?',
      message: [h('div', h('b', fmt.plural(hs.length, 'torrent') + ' (' + fmt.bytes(sumSize(hs)) + ')')), h('div', 'qBittorrent revérifie leurs fichiers sur le disque : cela peut durer et solliciter le disque.')],
      ok: 'Revérifier'
    });
    if (!ok) return;
    report(await runJob('Revérification', hs, (b) => A.recheck(b)), 'Revérification lancée pour ' + fmt.plural(hs.length, 'torrent'));
  }

  // Always asked, with what will happen. Returns false, or { files } for the deletion with files.
  async function confirmRule(r, m) {
    const n = m.hashes.length;
    const del = r.action === 'delete' || r.action === 'deleteFiles';
    const lines = [h('div', h('b', fmt.plural(n, 'torrent') + ' (' + fmt.bytes(m.size) + ')')), h('div', 'Action : ' + actionText(r) + '.')];
    let title;
    let ok;
    switch (r.action) {
      case 'addTag':
        title = 'Ajouter l’étiquette « ' + r.actionTag + ' » ?';
        ok = 'Ajouter';
        break;
      case 'removeTag':
        title = 'Retirer l’étiquette « ' + r.actionTag + ' » ?';
        ok = 'Retirer';
        break;
      case 'pause':
        title = 'Mettre en pause ?';
        ok = 'Mettre en pause';
        break;
      case 'resume':
        title = 'Reprendre ?';
        ok = 'Reprendre';
        break;
      default:
        title = 'Supprimer ' + fmt.plural(n, 'torrent') + ' ?';
        ok = 'Supprimer';
    }
    if (del) {
      lines.push(h('div', r.action === 'delete' ? 'Les fichiers restent sur le disque.' : 'Sans la case ci-dessous, seuls les torrents sont retirés : leurs fichiers restent sur le disque.'));
      if (m.protected) lines.push(h('div', 'Protégés, non supprimés : ' + fmt.num(m.protected) + ' (torrents incomplets ou à risque de hit-and-run).'));
    }
    const res = await App.confirm({
      title: title,
      message: lines,
      ok: ok,
      danger: del,
      checks: r.action === 'deleteFiles' ? [{ key: 'files', label: 'Supprimer aussi les fichiers du disque', checked: false }] : undefined
    });
    return res ? { files: !!res.files } : false;
  }

  function openPreview(rule) {
    needStore();
    const r = D.cleanRule(rule);
    const problem = D.ruleProblem(r);
    if (problem) throw App.mkErr(problem);
    const m = D.match(Q.store.torrents, r, cfg(), nowSec());
    const del = r.action === 'delete' || r.action === 'deleteFiles';
    const n = m.hashes.length;
    const info = infoFor('rule', cfg());
    const prog = h('div.dash-text.hidden');
    const body = [h('div.dash-count', h('b', fmt.plural(n, 'torrent')), n ? ' · ' + fmt.bytes(m.size) : ''), h('div.dash-text', 'Action : ' + actionText(r) + '.')];
    if (del) body.push(h('div.dash-text', App.icon('shield-check', 'sm'), ' ' + fmt.plural(m.protected, 'protégé') + ' (torrents incomplets ou à risque de hit-and-run, jamais supprimés)'));
    if (r.action === 'deleteFiles') body.push(App.note('Les fichiers ne seront supprimés que si tu coches la case à l’étape suivante.', 'warn'));
    if (n) {
      body.push(h('div.section-title', h('span', n > SAMPLE ? 'Les ' + SAMPLE + ' plus gros' : 'Torrents concernés')));
      body.push(h('div.dash-rows', D.top(Q.store.torrents, m.hashes, SAMPLE).map((t) => rowEl(rowOf(t, info)))));
      if (n > SAMPLE) {
        body.push(App.btn('Voir tout (' + fmt.num(n) + ')', { cls: 'sm', icon: 'list-bullet', onClick: () => openList(r.name, '', D.match(Q.store.torrents, r, cfg(), nowSec()).hashes, info) }));
      }
    } else {
      body.push(App.note('Aucun torrent ne correspond à cette règle pour le moment.', 'ok', 'check-circle'));
    }
    body.push(prog);
    let s = null;
    s = App.sheet({
      title: r.name,
      body: body,
      actions: [
        App.btn('Fermer', { onClick: () => s.close() }),
        App.btn('Appliquer', {
          cls: del ? 'danger-solid' : 'pri',
          disabled: !n,
          onClick: async () => {
            needStore();
            const fresh = D.match(Q.store.torrents, r, cfg(), nowSec());
            if (!fresh.hashes.length) throw App.mkErr('Plus aucun torrent ne correspond à cette règle.');
            const ok = await confirmRule(r, fresh);
            if (!ok) return;
            const res = await runJob(r.name, fresh.hashes, D.postFor(r, ok.files), (d, t) => {
              prog.classList.remove('hidden');
              prog.textContent = fmt.num(d) + ' / ' + fmt.num(t);
            });
            report(res, doneText(r, res.done, ok.files));
            s.close();
          }
        })
      ]
    });
  }

  // ---------- Editors ----------
  function tagOptions(first, current) {
    const seen = new Set();
    const out = [{ value: '', label: first }];
    const add = (t) => {
      if (t && !seen.has(t)) {
        seen.add(t);
        out.push({ value: t, label: t });
      }
    };
    Q.store.tags.slice().sort(Q.cmpName).forEach(add);
    add(current);
    return out;
  }
  const numField = (v) => App.input({ type: 'number', inputmode: 'decimal', min: '0', step: 'any', value: v === '' || v == null ? '' : String(v) });

  function editRule(rule, index, done) {
    const r = D.cleanRule(rule);
    const names = Q.categoryNames();
    if (r.category && names.indexOf(r.category) < 0) names.push(r.category);
    const catOpts = [{ value: '*', label: 'Toutes les catégories' }, { value: '-', label: 'Sans catégorie' }].concat(names.map((n) => ({ value: 'c:' + n, label: n })));
    const name = App.input({ value: r.name, maxlength: '60', placeholder: 'Nom de la règle' });
    const tag = App.select(tagOptions('Toutes les étiquettes', r.tag), r.tag);
    const notTag = App.select(tagOptions('Aucune exclusion', r.notTag), r.notTag);
    const cat = App.select(catOpts, r.category === null ? '*' : r.category === '' ? '-' : 'c:' + r.category);
    const seedH = numField(r.minSeedHours || '');
    const idle = numField(r.idleDays || '');
    const minR = numField(r.minRatio);
    const maxR = numField(r.maxRatio);
    const states = App.select(STATES.map((v) => ({ value: v, label: STATE_LABEL[v] })), r.states);
    const action = App.select(ACTIONS.map((v) => ({ value: v, label: ACTION_LABEL[v] })), r.action);
    const actTag = App.input({ value: r.actionTag, maxlength: '100', placeholder: 'ex. inactif' });
    const tagField = App.field('Étiquette de l’action', actTag);
    const warn = App.note('Rien n’est supprimé sans ta confirmation, et les torrents incomplets ou encore à risque de hit-and-run sont toujours épargnés.', 'warn');
    function sync() {
      tagField.classList.toggle('hidden', action.value !== 'addTag' && action.value !== 'removeTag');
      warn.classList.toggle('hidden', action.value !== 'delete' && action.value !== 'deleteFiles');
    }
    action.addEventListener('change', sync);
    sync();
    function readForm() {
      const c = cat.value;
      return D.cleanRule({
        name: name.value,
        tag: tag.value,
        notTag: notTag.value,
        category: c === '*' ? null : c === '-' ? '' : c.slice(2),
        minSeedHours: seedH.value,
        minRatio: minR.value,
        maxRatio: maxR.value,
        idleDays: idle.value,
        states: states.value,
        action: action.value,
        actionTag: actTag.value
      });
    }
    let s = null;
    const remove = index >= 0
      ? App.btn('Supprimer la règle', {
        cls: 'danger block',
        icon: 'trash',
        onClick: async () => {
          const ok = await App.confirm({ title: 'Supprimer cette règle ?', message: r.name, ok: 'Supprimer', danger: true });
          if (!ok) return;
          saveDash({ rules: cfg().rules.filter((_, i) => i !== index) });
          s.close();
          done();
        }
      })
      : null;
    s = App.sheet({
      title: index >= 0 ? 'Modifier la règle' : 'Nouvelle règle',
      body: [
        App.field('Nom', name),
        App.field('Étiquette', tag),
        App.field('Sans l’étiquette', notTag),
        App.field('Catégorie', cat),
        h('div.dash-grid', App.field('Seed au moins (heures)', seedH), App.field('Inactif depuis (jours)', idle), App.field('Ratio au moins', minR), App.field('Ratio au plus', maxR)),
        App.field('États', states),
        App.field('Action', action),
        tagField,
        warn,
        remove
      ],
      actions: [
        App.btn('Annuler', { onClick: () => s.close() }),
        App.btn('Enregistrer', {
          cls: 'pri',
          onClick: () => {
            const next = readForm();
            const problem = D.ruleProblem(next);
            if (problem) throw App.mkErr(problem);
            const rules = cfg().rules.slice();
            if (index >= 0 && index < rules.length) rules[index] = next;
            else if (rules.length >= MAX_RULES) throw App.mkErr('Maximum ' + MAX_RULES + ' règles : supprime-en une avant d’en ajouter.');
            else rules.push(next);
            saveDash({ rules: rules });
            s.close();
            done();
          }
        })
      ]
    });
  }

  function openSettings(done) {
    const c = cfg();
    const farm = App.select(tagOptions('(aucune)', c.farmTag), c.farmTag);
    const minSeed = numField(c.minSeedHours);
    const safe = numField(c.safeRatio);
    const idle = numField(c.idleDays);
    let s = null;
    s = App.sheet({
      title: 'Réglages du tableau de bord',
      body: [
        App.field('Étiquette Zen-farm', farm, 'Les torrents portant cette étiquette forment le groupe « Zen-farm ».'),
        App.field('Seed minimum (heures)', minSeed, 'Durée de seed attendue après un téléchargement. Pour tes propres uploads : 48 h.'),
        App.field('Ratio de sécurité', safe, 'Au-dessus, un torrent n’est plus considéré à risque de hit-and-run.'),
        App.field('Inactif depuis (jours)', idle, 'Sert au décompte des inactifs et aux modèles de règles.'),
        App.note('C411 demande de seeder « une durée raisonnable » après chaque téléchargement : adapte selon les règles de ton compte.')
      ],
      actions: [
        App.btn('Annuler', { onClick: () => s.close() }),
        App.btn('Enregistrer', {
          cls: 'pri',
          onClick: () => {
            const next = D.readCfg({ farmTag: farm.value, minSeedHours: minSeed.value, safeRatio: safe.value, idleDays: idle.value, rules: c.rules });
            saveDash(next);
            App.toast('Réglages enregistrés', 'ok');
            s.close();
            done();
          }
        })
      ]
    });
  }

  // ---------- Page ----------
  Q.openDash = function () {
    if (App.state.tab !== 'qbit') App.goTab('qbit');
    const stack = App.tabs.qbit.stack;
    const top = stack[stack.length - 1];
    if (top && top.isDash) return;

    const page = App.page({ title: 'Ratio et Zen', back: true });
    page.isDash = true;
    const jobEl = h('div.dash-job.hidden');
    const sumEl = h('div');
    const grpEl = h('div');
    const alertsEl = h('div');
    const rulesEl = h('div');
    App.append(page.el, h('div.scroll', jobEl, sumEl, grpEl, alertsEl, rulesEl));
    let alive = true;
    let last = 0;
    let sigs = {};
    let limits = null;
    let lastSub = null;

    const ui = {
      show(label, done, total) {
        jobEl.classList.remove('hidden');
        App.fill(jobEl, h('div', label + ' · ' + fmt.num(done) + ' / ' + fmt.num(total)), App.bar(total ? done / total : 0));
      },
      hide() {
        jobEl.classList.add('hidden');
        last = 0;
      }
    };
    jobUI = ui;

    // A section is rebuilt only when what it shows changed (the view-model holds strings, numbers and booleans only).
    function section(el, key, vm, build) {
      const sig = JSON.stringify(vm);
      if (sigs[key] === sig) return;
      sigs[key] = sig;
      App.fill(el, build(vm));
    }

    function buildSummary(s) {
      return h('div.card.dash-sum',
        h('div.card-t', App.icon('chart-bar'), h('span', 'Vue d’ensemble'), App.badge(s.count, 'blue')),
        h('div.dash-ratio', h('div.big' + (s.low ? '.low' : ''), s.ratio), h('div.muted', 'Ratio global')),
        App.kv([
          ['Envoyé au total', s.up],
          ['Téléchargé au total', s.down],
          ['Débit', s.speed],
          ['Espace libre', s.free ? h('span' + (s.lowFree ? '.dash-low' : ''), s.free) : null]
        ]));
    }
    function groupVm(g, meta, c) {
      const parts = [];
      if (g.queued) parts.push(fmt.num(g.queued) + ' en file d’attente');
      if (g.paused) parts.push(fmt.num(g.paused) + ' en pause');
      if (g.error) parts.push(fmt.num(g.error) + ' en erreur');
      if (g.check) parts.push(fmt.num(g.check) + ' en vérification');
      if (g.dl) parts.push(fmt.num(g.dl) + ' en téléchargement');
      return {
        icon: meta.icon,
        title: meta.title,
        sub: meta.sub,
        tone: meta.tone,
        emptyText: meta.empty,
        count: fmt.plural(g.n, 'torrent'),
        empty: g.n === 0,
        ratio: g.up === 0 && g.down === 0 ? '—' : ratioTxt(g.ratio),
        low: g.down > 0 && g.up / g.down < c.safeRatio,
        up: fmt.bytes(g.up),
        down: fmt.bytes(g.down),
        size: fmt.bytes(g.size),
        frac: g.n ? Math.round((g.seed / g.n) * 1000) / 1000 : 0,
        seed: fmt.num(g.seed) + ' / ' + fmt.num(g.n),
        notSeeding: g.n - g.seed,
        notSeedingN: fmt.num(g.n - g.seed),
        parts: parts.join(', '),
        idle: g.idle ? fmt.num(g.idle) : '',
        idleDays: c.idleDays
      };
    }
    function buildGroup(g) {
      const kids = [h('div.card-t', App.icon(g.icon), h('span', g.title), App.badge(g.count, g.tone))];
      if (g.sub) kids.push(h('div.hint', g.sub));
      if (g.empty) {
        kids.push(h('div.dash-line', g.emptyText));
      } else {
        kids.push(h('div.dash-ratio', h('div.big' + (g.low ? '.low' : ''), g.ratio), h('div.muted', 'ratio')));
        kids.push(App.kv([['Envoyé', g.up], ['Téléchargé', g.down], ['Taille', g.size]]));
        kids.push(App.bar(g.frac));
        kids.push(h('div.dash-line', 'En seed maintenant ', h('b', g.seed)));
        if (g.notSeeding) kids.push(h('div.dash-line', 'Ne seedent pas ', h('b', g.notSeedingN), g.parts ? ' : ' + g.parts : ''));
        if (g.idle) kids.push(h('div.dash-line', 'Inactifs depuis plus de ' + g.idleDays + ' j : ', h('b', g.idle)));
      }
      return h('div.card.dash-grp', kids);
    }
    function buildGroups(vm) {
      return [vm.farm ? buildGroup(vm.farm) : null, buildGroup(vm.other)];
    }

    const limText = () => {
      if (!limits) return '';
      const f = (n) => (n < 0 ? '∞' : fmt.num(n));
      return 'Limites actuelles de qBittorrent : ' + f(limits.up) + ' envois actifs, ' + f(limits.tot) + ' torrents actifs.';
    };
    function rowsOf(hashes, info) {
      return D.top(Q.store.torrents, hashes, INLINE).map((t) => rowOf(t, info));
    }
    function alertsVm(res, c) {
      const list = [];
      const add = (a, hashes, info) => {
        a.n = hashes.length;
        a.count = fmt.num(hashes.length);
        if (info) a.rows = rowsOf(hashes, info);
        list.push(a);
      };
      if (res.hr.length) add({ key: 'hr', tone: 'red', icon: 'exclamation-triangle', title: ALERT_TITLE.hr, paused: res.hrN.paused, queued: res.hrN.queued, other: res.hrN.other }, res.hr, infoFor('hr', c));
      if (res.seedMin.length) add({ key: 'seedMin', tone: 'blue', icon: 'clock', title: 'Seed minimum pas encore atteint', remain: fmt.dur(res.minRemaining) }, res.seedMin);
      if (res.queued.length) add({ key: 'queue', tone: 'amber', icon: 'queue-list', title: ALERT_TITLE.queue, lim: limText() }, res.queued, infoFor('queue', c));
      if (res.errors.length) add({ key: 'errors', tone: 'red', icon: 'exclamation-triangle', title: ALERT_TITLE.errors }, res.errors, infoFor('errors', c));
      return { min: fmt.dur(c.minSeedHours * HOUR), safe: fmt.ratio(c.safeRatio), none: !(res.hr.length || res.queued.length || res.errors.length), list: list };
    }
    function alertCard(a, kids) {
      const rows = a.rows && a.rows.length ? h('div.dash-rows', a.rows.map(rowEl)) : null;
      const btns = [];
      if (a.key === 'hr' && a.paused) btns.push(App.btn('Reprendre ' + fmt.num(a.paused), { cls: 'pri sm', icon: 'play', onClick: () => resumeRisk() }));
      if (a.key === 'errors') btns.push(App.btn('Revérifier ' + a.count, { cls: 'pri sm', icon: 'arrow-path', onClick: () => recheckErrors() }));
      if ((a.key === 'hr' && a.queued) || a.key === 'queue') btns.push(App.btn('Préférences qBittorrent', { cls: 'sm', icon: 'adjustments-horizontal', onClick: () => Q.openPrefs() }));
      if (a.rows && a.n > a.rows.length) btns.push(App.btn('Voir tout (' + a.count + ')', { cls: 'sm', icon: 'list-bullet', onClick: () => openAlertList(a.key) }));
      return h('div.card.dash-alert.' + a.tone, h('div.card-t', App.icon(a.icon), h('span', a.title), App.badge(a.count, a.tone)), kids, rows, btns.length ? h('div.btn-row', btns) : null);
    }
    function buildAlerts(vm) {
      const out = [App.secTitle('Alertes')];
      if (vm.none) out.push(App.note('Aucune alerte : rien à signaler pour le moment.', 'ok', 'shield-check'));
      for (const a of vm.list) {
        if (a.key === 'hr') {
          const parts = [];
          if (a.paused) parts.push(fmt.num(a.paused) + ' en pause');
          if (a.queued) parts.push(fmt.num(a.queued) + ' en file d’attente');
          if (a.other) parts.push(fmt.num(a.other) + ' dans un autre état');
          out.push(alertCard(a, [
            h('div.dash-text', 'Seed minimum non atteint (' + vm.min + ', ' + fmt.dur(UPLOAD_MIN) + ' pour tes propres uploads) et ratio sous ' + vm.safe + ', mais plus en seed : risque d’être compté comme hit-and-run.'),
            parts.length ? h('div.dash-text', 'Dont ' + parts.join(', ') + '.') : null,
            a.queued ? App.note('Les torrents en file d’attente ne sont pas annoncés au tracker : relève la limite d’envois actifs dans les préférences de qBittorrent.', 'warn', 'queue-list') : null
          ]));
        } else if (a.key === 'seedMin') {
          out.push(alertCard(a, h('div.dash-text', 'En seed normal, mais le minimum n’est pas encore atteint : évite de les arrêter. Le premier l’atteint dans ' + a.remain + '.')));
        } else if (a.key === 'queue') {
          out.push(alertCard(a, [
            h('div.dash-text', 'Les torrents en file d’attente ne sont pas annoncés au tracker : aucun seed ni Zen n’est compté tant qu’ils attendent.'),
            a.lim ? h('div.dash-text', a.lim) : null
          ]));
        } else {
          out.push(alertCard(a, h('div.dash-text', 'Ces torrents ne seedent pas. Vérifie que leurs fichiers sont bien sur le disque, puis lance une revérification.')));
        }
      }
      return out;
    }

    function renderRules() {
      const c = cfg();
      const kids = [App.secTitle('Règles de nettoyage', c.rules.length < MAX_RULES ? h('button', { type: 'button', onclick: () => App.run(addRule) }, '+ Ajouter') : null)];
      kids.push(App.note('Rien ne s’applique tout seul : « Analyser » montre d’abord ce que la règle toucherait, et ta confirmation est toujours demandée avant d’agir. Les torrents incomplets ou encore à risque de hit-and-run ne sont jamais supprimés.'));
      if (c.rules.length) {
        kids.push(h('div.group', c.rules.map((r, i) => h('div.item.dash-rule',
          h('button.main', { type: 'button', onclick: () => App.run(() => editRule(r, i, renderRules)) }, h('div.t', r.name), h('div.s', ruleSummary(r))),
          App.btn('Analyser', { cls: 'sm', icon: 'funnel', onClick: () => openPreview(r) })))));
      } else {
        kids.push(h('div.section-title', h('span', 'Modèles')));
        kids.push(h('div.group', D.presets(c).map((r) => h('button.item', { type: 'button', onclick: () => App.run(() => editRule(r, -1, renderRules)) },
          App.icon('sparkles'), h('div.main', h('div.t', r.name), h('div.s', ruleSummary(r))), App.icon('chevron-right', 'sm')))));
      }
      App.fill(rulesEl, kids);
    }
    async function addRule() {
      const c = cfg();
      if (c.rules.length >= MAX_RULES) throw App.mkErr('Maximum ' + MAX_RULES + ' règles : supprime-en une avant d’en ajouter.');
      const pre = D.presets(c);
      const v = await App.pick({
        title: 'Nouvelle règle',
        options: [
          { value: 'blank', label: 'Règle vide', icon: 'plus' },
          { value: 0, label: pre[0].name, sub: ruleSummary(pre[0]), icon: 'sparkles' },
          { value: 1, label: pre[1].name, sub: ruleSummary(pre[1]), icon: 'sparkles' }
        ]
      });
      if (v === undefined) return;
      editRule(v === 'blank' ? D.cleanRule({ name: 'Règle ' + (c.rules.length + 1) }) : pre[v], -1, renderRules);
    }

    function showState() {
      const err = Q.store.error;
      const key = err ? 'e:' + (err.message || '') : 'l';
      if (sigs.state === key) return;
      sigs = { state: key };
      App.clear(grpEl);
      App.clear(alertsEl);
      App.fill(sumEl, err
        ? App.empty('exclamation-triangle', 'qBittorrent indisponible', err.message || 'Connexion impossible.', [App.btn('Réessayer', { icon: 'arrow-path', onClick: () => Q.refresh() })])
        : App.loading());
      lastSub = '';
      page.setSub('');
    }
    function paint(res) {
      if (!Q.store.loaded) {
        showState();
        return;
      }
      sigs.state = null;
      const c = cfg();
      res = res || aggregate(0);
      const g = res.global;
      section(sumEl, 'sum', {
        count: fmt.plural(res.total.n, 'torrent'),
        ratio: ratioTxt(g.ratio),
        low: g.ratio != null && g.ratio < c.safeRatio,
        up: fmt.bytes(g.up),
        down: fmt.bytes(g.down),
        speed: '↑ ' + fmt.speed(g.upSpeed) + ' · ↓ ' + fmt.speed(g.dlSpeed),
        free: g.free == null ? '' : fmt.bytes(g.free),
        lowFree: g.free != null && g.free < LOW_SPACE
      }, buildSummary);
      section(grpEl, 'grp', {
        farm: res.hasFarm ? groupVm(res.farm, { icon: 'sparkles', title: 'Zen-farm', sub: 'Étiquette « ' + c.farmTag + ' »', tone: 'green', empty: 'Aucun torrent avec cette étiquette.' }, c) : null,
        other: groupVm(res.other, { icon: 'circle-stack', title: res.hasFarm ? 'Autres' : 'Tous les torrents', sub: res.hasFarm ? 'Sans l’étiquette « ' + c.farmTag + ' »' : '', tone: 'blue', empty: 'Aucun torrent.' }, c)
      }, buildGroups);
      section(alertsEl, 'alerts', alertsVm(res, c), buildAlerts);
      const sub = fmt.plural(res.total.n, 'torrent') + (res.alertCount ? ' · ' + fmt.plural(res.alertCount, 'alerte') : '');
      if (sub !== lastSub) {
        lastSub = sub;
        page.setSub(sub);
      }
    }
    // At most one aggregation per DASH_EVERY, whatever the number of syncs.
    function tick(force) {
      if (!alive) return;
      const now = Date.now();
      if (!force && now - last < DASH_EVERY) return;
      last = now;
      paint();
    }

    async function loadLimits() {
      try {
        const p = await qb.json('/app/preferences');
        const up = +(p && p.max_active_uploads);
        const tot = +(p && p.max_active_torrents);
        if (!alive || !isFinite(up) || !isFinite(tot)) return;
        limits = { up: up, tot: tot };
        sigs.alerts = null;
        if (latest) paint(latest);
      } catch (e) {
        limits = null;
      }
    }

    const off = App.on('qbit', () => {
      if (alive && App.isTop(page)) tick(false);
    });
    App.append(page.actions, App.iconBtn('arrow-path', 'Actualiser', () => {
      Q.refresh();
      tick(true);
    }), App.iconBtn('adjustments-horizontal', 'Réglages du tableau de bord', () => openSettings(() => {
      sigs = {};
      tick(true);
      renderRules();
    })));
    page.onShow = () => {
      if (!alive) return;
      if (!Q.store.loaded) Q.refresh();
      tick(true);
    };
    page.onDestroy = () => {
      alive = false;
      off();
      if (jobUI === ui) jobUI = null;
    };
    renderRules();
    App.push('qbit', page);
    loadLimits();
  };

  // ---------- Banner above the torrent list ----------
  D.banner = function (el) {
    let shown = -1;
    function hide() {
      if (shown === 0) return;
      shown = 0;
      el.classList.add('hidden');
      App.clear(el);
    }
    function onTop() {
      const t = App.tabs.qbit;
      return !!t && App.visible('qbit') && t.stack.length > 0 && t.stack[t.stack.length - 1].el === el.parentNode;
    }
    function update() {
      if (!Q.store.loaded) {
        hide();
        return;
      }
      if (!onTop()) return;
      const n = aggregate(BANNER_EVERY).counts.hr;
      if (!n) {
        hide();
        return;
      }
      if (n === shown) return;
      shown = n;
      App.fill(el, h('button', { type: 'button', onclick: () => App.run(() => Q.openDash()) }, App.icon('exclamation-triangle'), h('span', fmt.plural(n, 'torrent') + ' à risque de hit-and-run'), App.icon('chevron-right', 'sm')));
      el.classList.remove('hidden');
    }
    App.on('qbit', update);
    App.on('visibility', update);
    App.on('config', (p) => {
      if (p && p.dash) {
        latest = null;
        update();
      }
    });
  };
  // Sub line of the menu entry, from the last aggregation (nothing is computed for a menu).
  D.menuSub = function () {
    return Q.store.loaded && latest && latest.alertCount ? fmt.plural(latest.alertCount, 'alerte') : '';
  };
})();
