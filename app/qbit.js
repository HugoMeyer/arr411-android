/* Arr411 - qBittorrent: store, torrent list, add sheet and shared torrent actions. */
(function () {
  'use strict';
  const App = window.App;
  const h = App.h;
  const fmt = App.fmt;
  const qb = App.qb;
  const Q = (App.qbit = {});

  // ---------- Torrent states ----------
  const STATE = {
    error: 'err', missingFiles: 'err',
    uploading: 'up', forcedUP: 'up',
    stalledUP: 'seed',
    queuedUP: 'queued', queuedDL: 'queued', unknown: 'queued',
    pausedUP: 'done', stoppedUP: 'done',
    downloading: 'dl', forcedDL: 'dl', metaDL: 'dl', forcedMetaDL: 'dl',
    stalledDL: 'stalled',
    pausedDL: 'paused', stoppedDL: 'paused',
    checkingUP: 'check', checkingDL: 'check', checkingResumeData: 'check', allocating: 'check', moving: 'check'
  };
  const LABEL = {
    error: 'Erreur', missingFiles: 'Fichiers manquants',
    uploading: 'Seed actif', forcedUP: 'Seed forcé', stalledUP: 'En seed',
    queuedUP: 'En file (seed)', queuedDL: 'En file d’attente', unknown: 'Inconnu',
    pausedUP: 'Terminé', stoppedUP: 'Terminé',
    downloading: 'Téléchargement', forcedDL: 'Téléchargement forcé', metaDL: 'Métadonnées…', forcedMetaDL: 'Métadonnées…',
    stalledDL: 'Bloqué', pausedDL: 'En pause', stoppedDL: 'En pause',
    checkingUP: 'Vérification', checkingDL: 'Vérification', checkingResumeData: 'Vérification', allocating: 'Allocation', moving: 'Déplacement'
  };
  const DOWNLOADING = { downloading: 1, forcedDL: 1, metaDL: 1, forcedMetaDL: 1, stalledDL: 1, queuedDL: 1, checkingDL: 1, allocating: 1 };
  const SEEDING = { uploading: 1, forcedUP: 1, stalledUP: 1, queuedUP: 1, checkingUP: 1 };
  Q.stateKey = (t) => STATE[t.state] || 'queued';
  Q.stateLabel = (t) => LABEL[t.state] || String(t.state || '?');
  Q.isPaused = (t) => /^(paused|stopped)(DL|UP)$/.test(t.state || '');
  const splitTags = (s) => String(s || '').split(',').map((x) => x.trim()).filter(Boolean);
  Q.tagList = (t) => splitTags(t && t.tags);
  const collate = App.collator.compare;
  const cmpName = (Q.cmpName = (a, b) => collate(String(a || ''), String(b || '')));

  // ---------- Store (incremental /sync/maindata) ----------
  // Keyed stores have no prototype: a hash or category named "__proto__" (data written by whoever can add to qBittorrent) stays an inert key.
  const store = (Q.store = { rid: 0, torrents: Object.create(null), categories: Object.create(null), tags: [], server: {}, loaded: false, error: null, appVersion: null });
  let waiters = [];
  let gen = 0;
  // mem: torrents added/removed or category/tags changed. data: a listed field changed on a torrent of the current list. srv: server state changed.
  const rev = { mem: 0, data: 0, srv: 0 };
  const LISTED = new Set(['name', 'state', 'progress', 'dlspeed', 'upspeed', 'eta', 'size', 'ratio', 'added_on', 'num_seeds', 'completion_on', 'last_activity']);
  let lastSig = null;
  let dlN = 0;
  Q.categoryNames = () => Object.keys(store.categories).sort(cmpName);
  Q.get = (hash) => store.torrents[String(hash || '').toLowerCase()];
  // Torrent counts per category and per tag, recomputed only when membership, categories or tags changed.
  let tallyRev = -1;
  let tallyVal = null;
  Q.tally = () => {
    if (tallyRev !== rev.mem) {
      tallyRev = rev.mem;
      const cats = Object.create(null);
      const tags = Object.create(null);
      const byTags = new Map();
      let total = 0;
      for (const k in store.torrents) {
        const t = store.torrents[k];
        total++;
        cats[t.category || ''] = (cats[t.category || ''] || 0) + 1;
        byTags.set(t.tags || '', (byTags.get(t.tags || '') || 0) + 1);
      }
      byTags.forEach((n, s) => splitTags(s).forEach((g) => {
        tags[g] = (tags[g] || 0) + n;
      }));
      tallyVal = { total, cats, tags };
    }
    return tallyVal;
  };

  function resetStore() {
    gen++;
    Object.assign(store, { rid: 0, torrents: Object.create(null), categories: Object.create(null), tags: [], server: {}, loaded: false, error: null, appVersion: null });
    rev.mem++;
    rev.data++;
    rev.srv++;
    lastSig = null;
    dlN = 0;
    if (Q._clearRows) Q._clearRows();
  }
  function merge(d) {
    syncHide();
    if (d.full_update) {
      store.torrents = Object.create(null);
      store.categories = Object.create(null);
      store.tags = [];
      store.server = {};
      dlN = 0;
      rev.mem++;
      rev.data++;
      rev.srv++;
    }
    store.rid = d.rid || 0;
    const ts = d.torrents || {};
    for (const k in ts) {
      if (App.unsafeKey(k)) continue;
      const p = ts[k];
      let t = store.torrents[k];
      if (!t) {
        t = store.torrents[k] = App.safeAssign({ hash: k }, p);
        t.hash = k;
        if (DOWNLOADING[t.state]) dlN++;
        rev.mem++;
        continue;
      }
      let moved = false;
      let listed = false;
      for (const f in p) {
        if (App.unsafeKey(f)) continue;
        const old = t[f];
        if (old === p[f]) continue;
        t[f] = p[f];
        if (f === 'category' || f === 'tags') moved = true;
        else if (LISTED.has(f)) {
          listed = true;
          if (f === 'state') dlN += (DOWNLOADING[p[f]] ? 1 : 0) - (DOWNLOADING[old] ? 1 : 0);
        }
      }
      t.hash = k;
      if (moved) rev.mem++;
      else if (listed && inBase(t)) rev.data++;
    }
    for (const k of d.torrents_removed || []) {
      const t = store.torrents[k];
      if (!t) continue;
      if (DOWNLOADING[t.state]) dlN--;
      delete store.torrents[k];
      rev.mem++;
    }
    const cs = d.categories || {};
    for (const k in cs) {
      if (!App.unsafeKey(k)) App.safeAssign(store.categories[k] || (store.categories[k] = { name: k }), cs[k]);
    }
    for (const k of d.categories_removed || []) delete store.categories[k];
    if (d.tags) store.tags = Array.from(new Set(store.tags.concat(d.tags)));
    if (d.tags_removed) store.tags = store.tags.filter((t) => d.tags_removed.indexOf(t) < 0);
    if (d.server_state) {
      App.safeAssign(store.server, d.server_state);
      rev.srv++;
    }
  }
  async function sync() {
    const g = gen;
    let dirty = false;
    if (!qb.configured()) {
      store.error = App.mkErr('qBittorrent n’est pas configuré', 0, 'config');
    } else {
      try {
        const d = await qb.json('/sync/maindata', { query: { rid: store.rid } });
        if (g !== gen) return;
        const before = rev.mem + rev.data + rev.srv;
        merge(d);
        dirty = rev.mem + rev.data + rev.srv !== before;
        store.loaded = true;
        store.error = null;
        if (store.appVersion == null) {
          store.appVersion = '';
          qb.call('/app/version').then((v) => {
            if (g === gen) store.appVersion = String(v).trim();
            if (Q._render) Q._render();
          }, () => {});
          qb.version().catch(() => '');
        }
      } catch (e) {
        if (g !== gen) return;
        store.error = e;
      }
    }
    const ws = waiters;
    waiters = [];
    ws.forEach((w) => w());
    updateBadge();
    App.emit('qbit', store);
    // An empty delta leaves the list as it is: no render, hence none of its work.
    const sig = [qb.configured(), store.loaded, store.error ? store.error.message : ''].join('|');
    if (dirty || sig !== lastSig) {
      lastSig = sig;
      if (Q._render) Q._render();
    }
  }
  const loop = App.loop(sync, () => {
    if (!App.state.fg || !qb.configured()) return null;
    // Refused credentials or banned IP: automatic retries would only extend qBittorrent's ban.
    if (store.error && (store.error.status === 401 || store.error.status === 403)) return null;
    if (App.state.tab === 'qbit') return store.error ? 6000 : 2000;
    return store.error ? 60000 : 15000;
  });
  Q.refresh = () => loop.kick();
  Q.reset = () => {
    resetStore();
    if (Q._render) Q._render();
    loop.kick();
  };
  Q.ensure = function (timeout) {
    if (store.loaded) return Promise.resolve(store);
    if (!qb.configured()) return Promise.reject(App.mkErr('qBittorrent n’est pas configuré', 0, 'config'));
    return new Promise((resolve, reject) => {
      let done = false;
      const timer = setTimeout(() => {
        if (done) return;
        done = true;
        reject(store.error || App.mkErr('qBittorrent ne répond pas'));
      }, timeout || 15000);
      waiters.push(() => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        if (store.loaded) resolve(store);
        else reject(store.error || App.mkErr('qBittorrent indisponible'));
      });
      loop.kick();
    });
  };

  let lastBadge = -1;
  function updateBadge() {
    const n = store.loaded ? dlN : 0;
    if (n !== lastBadge) {
      lastBadge = n;
      App.nat('setBadge', n);
    }
  }

  // ---------- Torrent actions ----------
  const join = (hs) => (Array.isArray(hs) ? hs.join('|') : hs);
  const A = (Q.act = {
    pause: (hs) => qb.pauseResume(join(hs), true),
    resume: (hs) => qb.pauseResume(join(hs), false),
    force: (hs, v) => qb.post('/torrents/setForceStart', { hashes: join(hs), value: v !== false }),
    recheck: (hs) => qb.post('/torrents/recheck', { hashes: join(hs) }),
    reannounce: (hs) => qb.post('/torrents/reannounce', { hashes: join(hs) }),
    remove: (hs, files) => qb.post('/torrents/delete', { hashes: join(hs), deleteFiles: !!files }),
    category: (hs, c) => qb.post('/torrents/setCategory', { hashes: join(hs), category: c || '' }),
    addTags: (hs, tags) => qb.post('/torrents/addTags', { hashes: join(hs), tags: tags.join(',') }),
    removeTags: (hs, tags) => qb.post('/torrents/removeTags', { hashes: join(hs), tags: tags.join(',') }),
    location: (hs, loc) => qb.post('/torrents/setLocation', { hashes: join(hs), location: loc }),
    prio: (hs, which) => qb.post('/torrents/' + which, { hashes: join(hs) }),
    seq: (hs) => qb.post('/torrents/toggleSequentialDownload', { hashes: join(hs) }),
    firstLast: (hs) => qb.post('/torrents/toggleFirstLastPiecePrio', { hashes: join(hs) }),
    autoTMM: (hs, v) => qb.post('/torrents/setAutoManagement', { hashes: join(hs), enable: !!v }),
    superSeed: (hs, v) => qb.post('/torrents/setSuperSeeding', { hashes: join(hs), value: !!v }),
    dlLimit: (hs, bytes) => qb.post('/torrents/setDownloadLimit', { hashes: join(hs), limit: bytes }),
    upLimit: (hs, bytes) => qb.post('/torrents/setUploadLimit', { hashes: join(hs), limit: bytes }),
    rename: (hash, name) => qb.post('/torrents/rename', { hash: hash, name: name })
  });
  Q.togglePause = async function (hash) {
    const t = store.torrents[hash];
    if (!t) return;
    const paused = Q.isPaused(t);
    await (paused ? A.resume([hash]) : A.pause([hash]));
    App.haptic();
    App.toast(paused ? 'Torrent repris' : 'Torrent en pause', 'ok');
    loop.kick();
  };

  // ---------- Shared dialogs ----------
  Q.askDelete = async function (hashes, after) {
    const ts = hashes.map((x) => store.torrents[x]).filter(Boolean);
    const one = hashes.length === 1;
    const r = await App.confirm({
      title: one ? 'Supprimer ce torrent ?' : 'Supprimer ' + hashes.length + ' torrents ?',
      message: one && ts[0] ? ts[0].name : 'Les torrents seront retirés de qBittorrent.',
      ok: 'Supprimer',
      danger: true,
      checks: [{ key: 'files', label: 'Supprimer aussi les fichiers du disque', checked: false }]
    });
    if (!r) return false;
    await A.remove(hashes, r.files);
    App.toast((one ? 'Torrent supprimé' : hashes.length + ' torrents supprimés') + (r.files ? ' avec les fichiers' : ''), 'ok');
    loop.kick();
    if (after) after();
    return true;
  };

  Q.askNewCategory = function () {
    return new Promise((resolve) => {
      const name = App.input({ placeholder: 'films, series, anime…' });
      const path = App.input({ placeholder: 'Optionnel' });
      let done = false;
      let s = null;
      s = App.sheet({
        title: 'Nouvelle catégorie',
        body: [App.field('Nom', name), App.field('Dossier de destination', path, 'Laisse vide pour utiliser le dossier par défaut.')],
        actions: [
          App.btn('Annuler', { onClick: () => s.close() }),
          App.btn('Créer', {
            cls: 'pri',
            onClick: async () => {
              const n = name.value.trim();
              if (!n) throw App.mkErr('Indique un nom de catégorie');
              await qb.post('/torrents/createCategory', { category: n, savePath: path.value.trim() });
              done = true;
              s.close();
              loop.kick();
              App.toast('Catégorie « ' + n + ' » créée', 'ok');
              resolve(n);
            }
          })
        ],
        onClose: () => {
          if (!done) resolve(null);
        }
      });
      setTimeout(() => name.focus(), 260);
    });
  };

  Q.askCategory = async function (hashes) {
    const cur = hashes.length === 1 && store.torrents[hashes[0]] ? store.torrents[hashes[0]].category || '' : undefined;
    const opts = [{ value: '', label: 'Aucune catégorie', icon: 'minus-circle' }]
      .concat(Q.categoryNames().map((c) => ({ value: c, label: c, sub: store.categories[c].savePath || '', icon: 'folder' })))
      .concat(['-', { value: '\u0000new', label: 'Nouvelle catégorie…', icon: 'plus' }]);
    let v = await App.pick({ title: 'Catégorie', options: opts, value: cur });
    if (v === undefined) return;
    if (v === '\u0000new') {
      v = await Q.askNewCategory();
      if (!v) return;
    }
    await A.category(hashes, v);
    App.toast('Catégorie : ' + (v || 'aucune'), 'ok');
    loop.kick();
  };

  Q.askTags = function (hashes) {
    const ts = hashes.map((x) => store.torrents[x]).filter(Boolean);
    const has = Object.create(null);
    ts.forEach((t) => Q.tagList(t).forEach((g) => {
      has[g] = (has[g] || 0) + 1;
    }));
    const all = Array.from(new Set(store.tags.concat(Object.keys(has)))).sort(cmpName);
    const state = Object.create(null);
    const rows = all.map((g) => {
      const r = App.checkRow(g, has[g] === ts.length, (v) => {
        state[g] = v;
      });
      if (has[g] && has[g] < ts.length) r.input.indeterminate = true;
      return r;
    });
    const extra = App.input({ placeholder: 'Séparées par des virgules' });
    let s = null;
    s = App.sheet({
      title: 'Étiquettes',
      body: [rows.length ? h('div', rows) : h('p.muted', 'Aucune étiquette pour l’instant.'), App.field('Nouvelles étiquettes', extra)],
      actions: [
        App.btn('Annuler', { onClick: () => s.close() }),
        App.btn('Appliquer', {
          cls: 'pri',
          onClick: async () => {
            const add = Object.keys(state).filter((g) => state[g] && has[g] !== ts.length);
            const rem = Object.keys(state).filter((g) => !state[g] && has[g]);
            const fresh = extra.value.split(',').map((x) => x.trim()).filter(Boolean);
            if (fresh.length) await qb.post('/torrents/createTags', { tags: fresh.join(',') });
            const toAdd = add.concat(fresh);
            if (toAdd.length) await A.addTags(hashes, toAdd);
            if (rem.length) await A.removeTags(hashes, rem);
            s.close();
            App.toast('Étiquettes mises à jour', 'ok');
            loop.kick();
          }
        })
      ]
    });
  };

  Q.askLocation = async function (hashes) {
    const t = store.torrents[hashes[0]];
    const v = await App.prompt({
      title: 'Déplacer',
      label: 'Nouveau dossier',
      value: t ? t.save_path : '',
      hint: 'qBittorrent déplace les fichiers. La gestion automatique est désactivée pour ces torrents.'
    });
    if (v == null || !v.trim()) return;
    await A.location(hashes, v.trim());
    App.toast('Déplacement lancé', 'ok');
    loop.kick();
  };

  Q.askLimits = function (hashes) {
    const t = hashes.length === 1 ? store.torrents[hashes[0]] : null;
    const kb = (v) => (v > 0 ? String(Math.round(v / 1024)) : '');
    const dl = App.input({ type: 'number', inputmode: 'numeric', placeholder: 'Illimitée', value: t ? kb(t.dl_limit) : '' });
    const up = App.input({ type: 'number', inputmode: 'numeric', placeholder: 'Illimitée', value: t ? kb(t.up_limit) : '' });
    let s = null;
    s = App.sheet({
      title: 'Limites de vitesse',
      body: [App.field('Téléchargement (Ko/s)', dl), App.field('Envoi (Ko/s)', up, 'Vide ou 0 = illimitée.')],
      actions: [
        App.btn('Annuler', { onClick: () => s.close() }),
        App.btn('Appliquer', {
          cls: 'pri',
          onClick: async () => {
            await A.dlLimit(hashes, Math.max(0, parseInt(dl.value, 10) || 0) * 1024);
            await A.upLimit(hashes, Math.max(0, parseInt(up.value, 10) || 0) * 1024);
            s.close();
            App.toast('Limites enregistrées', 'ok');
            loop.kick();
          }
        })
      ]
    });
  };

  // ---------- List filters & sort ----------
  const FILTERS = [
    { k: 'all', label: 'Tous', test: () => true },
    { k: 'dl', label: 'Téléchargement', test: (t) => !!DOWNLOADING[t.state] },
    { k: 'seed', label: 'Seed', test: (t) => !!SEEDING[t.state] },
    { k: 'done', label: 'Terminés', test: (t) => t.progress >= 1 },
    { k: 'paused', label: 'En pause', test: (t) => Q.isPaused(t) },
    { k: 'active', label: 'Actifs', test: (t) => t.dlspeed > 0 || t.upspeed > 0 },
    { k: 'stalled', label: 'Bloqués', test: (t) => t.state === 'stalledDL', hideEmpty: true },
    { k: 'err', label: 'Erreurs', test: (t) => STATE[t.state] === 'err', hideEmpty: true }
  ];
  const SORTS = [
    { k: 'added_on', label: 'Date d’ajout', desc: true },
    { k: 'name', label: 'Nom', desc: false },
    { k: 'size', label: 'Taille', desc: true },
    { k: 'progress', label: 'Progression', desc: true },
    { k: 'dlspeed', label: 'Vitesse de téléchargement', desc: true },
    { k: 'upspeed', label: 'Vitesse d’envoi', desc: true },
    { k: 'eta', label: 'Temps restant', desc: false },
    { k: 'ratio', label: 'Ratio', desc: true },
    { k: 'num_seeds', label: 'Seeds connectés', desc: true },
    { k: 'completion_on', label: 'Date de fin', desc: true },
    { k: 'last_activity', label: 'Dernière activité', desc: true },
    { k: 'category', label: 'Catégorie', desc: false }
  ];
  // Torrents carrying one of these tags are hidden until the user turns the filter off (ratio farming adds tens of thousands).
  const HIDE_DEFAULT = ['zen-farm'];
  const hideStored = App.pref('qb.hide', null);
  const prefs = {
    filter: App.pref('qb.filter', 'all'),
    sort: App.pref('qb.sort', 'added_on'),
    desc: App.pref('qb.desc', true),
    cat: App.pref('qb.cat', null),
    tag: App.pref('qb.tag', null),
    hide: Array.isArray(hideStored) ? hideStored.filter((g) => g && typeof g === 'string') : HIDE_DEFAULT.slice(),
    hideOn: App.pref('qb.hideOn', true) !== false,
    q: ''
  };
  function savePrefs() {
    ['filter', 'sort', 'desc', 'cat', 'tag', 'hide', 'hideOn'].forEach((k) => App.setPref('qb.' + k, prefs[k]));
  }
  function sortVal(t, k) {
    const v = t[k];
    if (k === 'eta') return v >= 8640000 || v < 0 ? Infinity : v;
    if (k === 'completion_on') return v > 0 ? v : 0;
    return v == null ? 0 : v;
  }
  function comparator() {
    const k = prefs.sort;
    const dir = prefs.desc ? -1 : 1;
    if (k === 'name' || k === 'category') return (a, b) => dir * cmpName(a[k], b[k]) || cmpName(a.name, b.name);
    return (a, b) => {
      const x = sortVal(a, k);
      const y = sortVal(b, k);
      return (x < y ? -1 : x > y ? 1 : 0) * dir || cmpName(a.name, b.name);
    };
  }
  // Tags to hide right now: a tag picked in the tag filter is never hidden, so those torrents stay reachable.
  let hideSet = new Set();
  let hideSig = null;
  const hideMemo = new Map();
  function syncHide() {
    const tags = prefs.hide.filter((g) => g !== prefs.tag);
    const sig = tags.join('\n');
    if (sig === hideSig) return;
    hideSig = sig;
    hideSet = new Set(tags);
    hideMemo.clear();
  }
  // Memoised per distinct `tags` string: thousands of torrents share a handful of them.
  function hasHiddenTag(t) {
    const k = t.tags;
    if (!k || !hideSet.size) return false;
    let v = hideMemo.get(k);
    if (v === undefined) {
      if (hideMemo.size > 4000) hideMemo.clear();
      v = Q.tagList(t).some((g) => hideSet.has(g));
      hideMemo.set(k, v);
    }
    return v;
  }
  function countHideTagged(all) {
    let n = 0;
    for (const t of all) if (hasHiddenTag(t)) n++;
    return n;
  }
  function inBase(t) {
    if (prefs.hideOn && hasHiddenTag(t)) return false;
    if (prefs.cat != null && (t.category || '') !== prefs.cat) return false;
    if (prefs.tag != null) {
      const tags = Q.tagList(t);
      if (prefs.tag === '' ? tags.length : tags.indexOf(prefs.tag) < 0) return false;
    }
    return true;
  }
  // The list is memoised in layers, each rebuilt only when its inputs changed: base (hidden tags, category, tag),
  // then sorted + counted per filter, then search. A sync tick that changes nothing visible costs nothing.
  const CHUNK = 300;
  const V = { bKey: null, mem: -1, total: 0, hideTagged: 0, base: [], lKey: null, data: -1, counts: [], list: [], q: null, shown: [] };
  const normMemo = new WeakMap();
  function normName(t) {
    let e = normMemo.get(t);
    if (!e || e.name !== t.name) normMemo.set(t, (e = { name: t.name, n: App.norm(t.name) }));
    return e.n;
  }
  function getView() {
    syncHide();
    const bKey = JSON.stringify([hideSig, prefs.hideOn, prefs.cat, prefs.tag]);
    if (V.bKey !== bKey || V.mem !== rev.mem) {
      const all = Object.values(store.torrents);
      V.bKey = bKey;
      V.mem = rev.mem;
      V.total = all.length;
      V.hideTagged = hideSet.size ? countHideTagged(all) : 0;
      V.base = all.filter(inBase);
      V.lKey = null;
    }
    const lKey = prefs.filter + '|' + prefs.sort + '|' + prefs.desc;
    if (V.lKey !== lKey || V.data !== rev.data) {
      const f = FILTERS.find((x) => x.k === prefs.filter) || FILTERS[0];
      const counts = FILTERS.map(() => 0);
      // Sorted in place: the previous order is nearly right after a tick, which the sort exploits.
      V.base.sort(comparator());
      for (const t of V.base) for (let i = 0; i < FILTERS.length; i++) if (FILTERS[i].test(t)) counts[i]++;
      V.counts = counts;
      V.list = f.k === 'all' ? V.base : V.base.filter(f.test);
      V.lKey = lKey;
      V.data = rev.data;
      V.q = null;
    }
    if (V.q !== prefs.q) {
      V.q = prefs.q;
      const terms = App.norm(prefs.q).split(/\s+/).filter(Boolean);
      V.shown = terms.length ? V.list.filter((t) => {
        const n = normName(t);
        return terms.every((w) => n.indexOf(w) >= 0);
      }) : V.list;
    }
    return V;
  }
  function visibleList() {
    return getView().shown;
  }
  Q.setFilter = function (o) {
    if ('cat' in o) prefs.cat = o.cat;
    if ('tag' in o) prefs.tag = o.tag;
    if (o.filter) prefs.filter = o.filter;
    savePrefs();
    App.popToRoot('qbit');
    render();
  };

  // ---------- Main page ----------
  let ui = null;
  const rows = new Map();
  let selecting = false;
  const selected = new Set();
  Q._clearRows = () => {
    rows.forEach((r) => r.el.remove());
    rows.clear();
    if (ui) ui.limit = CHUNK;
  };

  function buildMain() {
    const page = App.page({ title: 'qBittorrent' });
    const searchBtn = App.iconBtn('magnifying-glass', 'Rechercher', () => setSearch(!ui.searchOpen));
    searchBtn.setAttribute('aria-expanded', 'false');
    const filterDot = h('span.dot.hidden');
    const filterBtn = App.iconBtn('funnel', 'Filtrer', () => askFilter());
    filterBtn.appendChild(filterDot);
    const normalActions = [searchBtn, filterBtn, App.iconBtn('bars-arrow-down', 'Trier', () => sortMenu()), App.iconBtn('ellipsis-vertical', 'Plus d’actions', () => mainMenu())];
    App.append(page.actions, normalActions);
    const selActions = [
      App.iconBtn('check-circle', 'Tout sélectionner', () => selectAll()),
      App.iconBtn('play', 'Reprendre', () => bulk((hs) => A.resume(hs), 'Repris')),
      App.iconBtn('pause', 'Mettre en pause', () => bulk((hs) => A.pause(hs), 'En pause')),
      App.iconBtn('trash', 'Supprimer', () => selHashes().length && Q.askDelete(selHashes(), endSelect)),
      App.iconBtn('ellipsis-vertical', 'Plus d’actions', () => bulkMenu())
    ];
    const selClose = App.iconBtn('x-mark', 'Quitter la sélection', () => endSelect());

    const searchInput = App.input({ type: 'search', placeholder: 'Rechercher un torrent', ariaLabel: 'Rechercher un torrent', enterkeyhint: 'search' });
    // Announced once typing pauses, so a screen reader is not flooded while a word is being typed.
    const sayFound = App.debounce(() => {
      if (prefs.q && store.loaded) App.sayResults(visibleList().length);
    }, 800);
    searchInput.addEventListener('input', App.debounce(() => {
      prefs.q = searchInput.value;
      render();
      sayFound();
    }, 150));
    const searchRow = h('div.hidden', { style: { padding: '0 12px 10px', flex: 'none' } }, h('div.search', App.icon('magnifying-glass'), searchInput));

    const dlTxt = h('span');
    const upTxt = h('span');
    const freeEl = h('span.free');
    const strip = h('button.speedstrip.hidden', { type: 'button', onclick: () => openTransfer() }, h('span.spd.dl', App.icon('arrow-down'), dlTxt), h('span.spd.up', App.icon('arrow-up'), upTxt), freeEl);

    const chips = h('div.chips');
    const catTxt = h('span');
    const tagTxt = h('span');
    const catChip = h('button.chip.on.hidden', { type: 'button', 'aria-pressed': 'true', onclick: () => Q.setFilter({ cat: null }) }, App.icon('folder'), catTxt, App.icon('x-mark', 'xs'));
    const tagChip = h('button.chip.on.hidden', { type: 'button', 'aria-pressed': 'true', onclick: () => Q.setFilter({ tag: null }) }, App.icon('tag'), tagTxt, App.icon('x-mark', 'xs'));
    const hideTxt = h('span');
    const hideN = h('span.n');
    const hideOnIc = App.icon('eye-slash');
    const hideOffIc = App.icon('eye');
    const hideX = App.icon('x-mark', 'xs');
    const hideChip = h('button.chip.hidden', { type: 'button', onclick: () => toggleHide() }, hideOnIc, hideOffIc, hideTxt, hideN, hideX);
    App.append(chips, hideChip, catChip, tagChip);
    const chipEls = FILTERS.map((f) => {
      const n = h('span.n');
      const b = h('button.chip', {
        type: 'button',
        onclick: () => {
          prefs.filter = f.k;
          savePrefs();
          render();
        }
      }, h('span', f.label), n);
      b.n = n;
      chips.appendChild(b);
      return b;
    });

    const stateBox = h('div');
    const list = h('div.list');
    App.pressableIn(list);
    const more = h('div.more.hidden');
    const scroll = h('div.scroll', stateBox, list, more);
    const fab = h('button.fab', { type: 'button', 'aria-label': 'Ajouter un torrent', onclick: () => App.run(() => Q.openAdd()) }, App.icon('plus'));
    const dashBanner = h('div.dash-banner.hidden');
    App.append(page.el, searchRow, strip, chips, dashBanner, scroll, fab);
    if (App.dash) App.dash.banner(dashBanner);
    ui = { page, searchBtn, filterDot, normalActions, selActions, selClose, searchRow, searchInput, dlTxt, upTxt, freeEl, strip, hideChip, hideOnIc, hideOffIc, hideX, hideTxt, hideN, catChip, catTxt, tagChip, tagTxt, chipEls, stateBox, list, more, scroll, fab, searchOpen: false, stateKey: null, limit: CHUNK, lsig: null, moreN: 0, io: null };
    // Scrolling near the end appends the next chunk; the button below stays as a fallback.
    if (typeof IntersectionObserver === 'function') {
      ui.io = new IntersectionObserver((es) => {
        if (es.some((e) => e.isIntersecting)) showMore();
      }, { root: scroll, rootMargin: '0px 0px 600px 0px' });
      ui.io.observe(more);
    }
    App.push('qbit', page);
  }

  function showMore() {
    // Activated from the keyboard, the button is replaced by the rows it adds: focus follows to the first of them.
    const fromBtn = ui.more.contains(document.activeElement);
    const first = ui.list.children.length;
    ui.limit += CHUNK;
    render();
    const row = fromBtn ? ui.list.children[first] : null;
    const main = row && row.querySelector('.main');
    if (main) main.focus();
    if (ui.io) {
      // Re-observing reports the marker again, so a tall window keeps loading until it is out of reach.
      ui.io.unobserve(ui.more);
      ui.io.observe(ui.more);
    }
  }

  function setSearch(open) {
    ui.searchOpen = open;
    ui.searchBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
    ui.searchRow.classList.toggle('hidden', !open);
    App.fill(ui.searchBtn, App.icon(open ? 'x-mark' : 'magnifying-glass'));
    if (open) {
      setTimeout(() => ui.searchInput.focus(), 60);
    } else {
      ui.searchInput.value = '';
      ui.searchInput.blur();
      prefs.q = '';
      render();
    }
  }

  // ---------- Selection mode ----------
  function startSelect(hash) {
    selecting = true;
    selected.clear();
    if (hash) selected.add(hash);
    ui.page.head.insertBefore(ui.selClose, ui.page.head.firstChild);
    ui.page.head.classList.add('has-back');
    App.fill(ui.page.actions, ui.selActions);
    ui.fab.classList.add('hidden');
    render();
  }
  function endSelect() {
    if (!selecting) return;
    // The selection buttons go away: focus that was on one moves to the page title rather than to nothing.
    const a = document.activeElement;
    const lost = !!a && (ui.selClose.contains(a) || ui.page.actions.contains(a));
    selecting = false;
    selected.clear();
    ui.selClose.remove();
    ui.page.head.classList.remove('has-back');
    App.fill(ui.page.actions, ui.normalActions);
    ui.fab.classList.remove('hidden');
    render();
    if (lost) ui.page.titleEl.focus({ preventScroll: true });
  }
  function toggleSel(hash) {
    if (selected.has(hash)) selected.delete(hash);
    else selected.add(hash);
    render();
  }
  function selHashes() {
    return Array.from(selected).filter((x) => store.torrents[x]);
  }
  function selectAll() {
    const vis = visibleList().map((t) => t.hash);
    if (vis.length && vis.every((x) => selected.has(x))) selected.clear();
    else vis.forEach((x) => selected.add(x));
    render();
  }
  async function bulk(fn, msg) {
    const hs = selHashes();
    if (!hs.length) {
      App.toast('Aucun torrent sélectionné');
      return;
    }
    await fn(hs);
    App.toast(msg + ' : ' + fmt.plural(hs.length, 'torrent'), 'ok');
    loop.kick();
  }
  function bulkMenu() {
    const hs = selHashes();
    if (!hs.length) {
      App.toast('Aucun torrent sélectionné');
      return;
    }
    App.menu({
      title: fmt.plural(hs.length, 'torrent sélectionné', 'torrents sélectionnés'),
      items: [
        { icon: 'forward', label: 'Forcer la reprise', onClick: () => bulk((x) => A.force(x, true), 'Reprise forcée') },
        { icon: 'folder', label: 'Catégorie…', onClick: () => Q.askCategory(hs) },
        { icon: 'tag', label: 'Étiquettes…', onClick: () => Q.askTags(hs) },
        { icon: 'arrows-right-left', label: 'Déplacer…', onClick: () => Q.askLocation(hs) },
        { icon: 'adjustments-horizontal', label: 'Limiter la vitesse…', onClick: () => Q.askLimits(hs) },
        '-',
        { icon: 'shield-check', label: 'Vérifier les données', onClick: () => bulk(A.recheck, 'Vérification lancée') },
        { icon: 'megaphone', label: 'Réannoncer aux trackers', onClick: () => bulk(A.reannounce, 'Réannonce') },
        { icon: 'queue-list', label: 'Téléchargement séquentiel', sub: 'Inverse l’option', onClick: () => bulk(A.seq, 'Option inversée') },
        { icon: 'arrow-up', label: 'Haut de la file d’attente', onClick: () => bulk((x) => A.prio(x, 'topPrio'), 'Priorité modifiée') },
        { icon: 'arrow-down', label: 'Bas de la file d’attente', onClick: () => bulk((x) => A.prio(x, 'bottomPrio'), 'Priorité modifiée') },
        { icon: 'link', label: 'Copier les liens magnet', onClick: () => App.copy(hs.map((x) => store.torrents[x].magnet_uri).filter(Boolean).join('\n'), 'Liens magnet') },
        '-',
        { icon: 'trash', label: 'Supprimer…', danger: true, onClick: () => Q.askDelete(hs, endSelect) }
      ]
    });
  }

  // ---------- Menus ----------
  function mainMenu() {
    const hid = prefs.hideOn ? getView().hideTagged : 0;
    const incl = hid ? 'Y compris ' + fmt.plural(hid, 'torrent masqué', 'torrents masqués') : null;
    App.menu({
      title: 'qBittorrent',
      items: [
        { icon: 'check-circle', label: 'Sélectionner des torrents', onClick: () => startSelect() },
        {
          icon: 'play',
          label: 'Tout reprendre',
          sub: incl,
          onClick: async () => {
            await A.resume('all');
            App.toast('Tous les torrents ont repris', 'ok');
            loop.kick();
          }
        },
        {
          icon: 'pause',
          label: 'Tout mettre en pause',
          sub: incl,
          onClick: async () => {
            await A.pause('all');
            App.toast('Tous les torrents sont en pause', 'ok');
            loop.kick();
          }
        },
        '-',
        App.dash ? { icon: 'chart-bar', label: 'Tableau de bord ratio', sub: App.dash.menuSub(), onClick: () => Q.openDash() } : null,
        { icon: 'folder', label: 'Catégories et étiquettes', onClick: () => Q.openCategories() },
        { icon: 'adjustments-horizontal', label: 'Préférences qBittorrent', onClick: () => Q.openPrefs() },
        { icon: 'document-text', label: 'Journal', onClick: () => Q.openLog() },
        { icon: 'arrow-top-right-on-square', label: 'Ouvrir l’interface web', onClick: () => App.openExternal(qb.base()) },
        '-',
        { icon: 'cog-6-tooth', label: 'Réglages de l’application', onClick: () => App.openSettings('qbit') }
      ]
    });
  }
  function sortMenu() {
    App.menu({
      title: 'Trier par',
      checks: true,
      items: SORTS.map((s) => ({
        label: s.label,
        on: prefs.sort === s.k,
        sub: prefs.sort === s.k ? (prefs.desc ? 'Décroissant' : 'Croissant') + ' · toucher pour inverser' : null,
        right: prefs.sort === s.k ? App.icon(prefs.desc ? 'arrow-down' : 'arrow-up', 'sm') : null,
        onClick: () => {
          if (prefs.sort === s.k) prefs.desc = !prefs.desc;
          else {
            prefs.sort = s.k;
            prefs.desc = s.desc;
          }
          savePrefs();
          render();
        }
      }))
    });
  }
  async function askFilter() {
    const tally = Q.tally();
    const counts = tally.cats;
    const opts = [
      { value: null, label: 'Toutes les catégories', sub: fmt.plural(tally.total, 'torrent'), icon: 'squares-2x2' },
      { value: '', label: 'Sans catégorie', sub: fmt.plural(counts[''] || 0, 'torrent'), icon: 'minus-circle' }
    ].concat(Q.categoryNames().map((c) => ({ value: c, label: c, sub: fmt.plural(counts[c] || 0, 'torrent'), icon: 'folder' })));
    if (store.tags.length || prefs.hide.length) {
      opts.push('-');
      if (store.tags.length) opts.push({ value: '\u0001tags', label: 'Filtrer par étiquette…', icon: 'tag' });
      opts.push({ value: '\u0001hide', label: 'Masquer des étiquettes…', sub: prefs.hide.length ? prefs.hide.join(', ') + (prefs.hideOn ? '' : ' (affiché)') : 'Aucune', icon: 'eye-slash' });
    }
    const v = await App.pick({ title: 'Catégorie', options: opts, value: prefs.cat });
    if (v === undefined) return;
    if (v === '\u0001tags') return askTagFilter();
    if (v === '\u0001hide') return askHideTags();
    Q.setFilter({ cat: v });
  }
  async function askTagFilter() {
    const counts = Q.tally().tags;
    const opts = [
      { value: null, label: 'Toutes les étiquettes', icon: 'squares-2x2' },
      { value: '', label: 'Sans étiquette', icon: 'minus-circle' }
    ].concat(store.tags.slice().sort(cmpName).map((g) => ({ value: g, label: g, sub: fmt.plural(counts[g] || 0, 'torrent'), icon: 'tag' })));
    const v = await App.pick({ title: 'Étiquette', options: opts, value: prefs.tag });
    if (v !== undefined) Q.setFilter({ tag: v });
  }
  function toggleHide() {
    prefs.hideOn = !prefs.hideOn;
    savePrefs();
    render();
  }
  function askHideTags() {
    const counts = Q.tally().tags;
    const names = Array.from(new Set(store.tags.concat(prefs.hide))).sort(cmpName);
    const picked = new Set(prefs.hide);
    const rows = names.map((g) => App.checkRow(g + ' · ' + fmt.plural(counts[g] || 0, 'torrent'), picked.has(g), (v) => {
      if (v) picked.add(g);
      else picked.delete(g);
    }));
    let s = null;
    s = App.sheet({
      title: 'Masquer des étiquettes',
      body: [
        h('p.muted.wrap', 'Les torrents qui portent une de ces étiquettes ne sont pas listés. Une pastille au-dessus de la liste permet de les réafficher.'),
        rows.length ? h('div', rows) : h('p.muted', 'Aucune étiquette pour l’instant.')
      ],
      actions: [
        App.btn('Annuler', { onClick: () => s.close() }),
        App.btn('Appliquer', {
          cls: 'pri',
          onClick: () => {
            prefs.hide = names.filter((g) => picked.has(g));
            prefs.hideOn = true;
            savePrefs();
            s.close();
            render();
          }
        })
      ]
    });
  }

  // ---------- Rendering ----------
  function makeRow(hash) {
    const r = { hash: hash, sig: '', paused: null, pr: null };
    r.cb = h('input.cb.hidden', { type: 'checkbox', tabindex: '-1', 'aria-hidden': 'true' });
    r.cb.style.pointerEvents = 'none';
    r.nm = h('div.nm');
    r.bar = App.bar(0);
    r.st = h('span.st');
    r.sz = h('span');
    r.dn = h('span.down');
    r.up = h('span.upl');
    r.eta = h('span');
    r.ratio = h('span');
    r.cat = h('span.dim');
    r.pbtn = App.iconBtn('pause', 'Mettre en pause', () => Q.togglePause(hash));
    // The button role sits on .main, not on the row, so the pause button beside it is not nested in another control. Enter and Space come from the list (App.pressableIn).
    r.main = h('div.main', { role: 'button', tabindex: '0' }, r.nm, r.bar, h('div.meta', r.st, r.sz, r.dn, r.up, r.eta, r.ratio, r.cat));
    r.el = h('div.tor', { 'data-s': 'queued' }, r.cb, r.main, r.pbtn);
    r.el.addEventListener('click', () => {
      if (selecting) toggleSel(hash);
      else Q.openDetails(hash);
    });
    App.longPress(r.el, () => {
      if (selecting) toggleSel(hash);
      else startSelect(hash);
    });
    return r;
  }
  function updateRow(r, t) {
    const sig = [t.name, t.state, t.progress, t.dlspeed, t.upspeed, t.eta, t.size, t.ratio, t.category].join('|');
    if (sig !== r.sig) {
      r.sig = sig;
      const done = t.progress >= 1;
      r.el.setAttribute('data-s', Q.stateKey(t));
      r.nm.textContent = t.name || t.hash;
      r.bar.set(t.progress);
      r.st.textContent = Q.stateLabel(t);
      r.sz.textContent = done ? fmt.bytes(t.size) : fmt.pct(t.progress) + ' · ' + fmt.bytes(t.size);
      r.dn.textContent = t.dlspeed > 0 ? '↓ ' + fmt.speed(t.dlspeed) : '';
      r.up.textContent = t.upspeed > 0 ? '↑ ' + fmt.speed(t.upspeed) : '';
      r.eta.textContent = !done && t.dlspeed > 0 && t.eta > 0 && t.eta < 8640000 ? fmt.dur(t.eta) : '';
      r.ratio.textContent = done || t.ratio > 0.005 ? 'ratio ' + fmt.ratio(t.ratio) : '';
      r.cat.textContent = t.category || '';
    }
    const paused = Q.isPaused(t);
    if (r.paused !== paused) {
      r.paused = paused;
      App.fill(r.pbtn, App.icon(paused ? 'play' : 'pause'));
      r.pbtn.setAttribute('aria-label', paused ? 'Reprendre' : 'Mettre en pause');
      r.pbtn.title = paused ? 'Reprendre' : 'Mettre en pause';
    }
    const sel = selecting && selected.has(t.hash);
    r.el.classList.toggle('selected', sel);
    r.cb.classList.toggle('hidden', !selecting);
    r.cb.checked = sel;
    // While selecting, a row is a toggle; otherwise it opens the torrent.
    const pr = selecting ? String(sel) : null;
    if (r.pr !== pr) {
      r.pr = pr;
      if (pr) r.main.setAttribute('aria-pressed', pr);
      else r.main.removeAttribute('aria-pressed');
    }
  }
  function stateContent(key) {
    const kind = key.split(':')[0];
    const msg = store.error ? store.error.message : '';
    const settingsBtn = () => App.btn('Réglages', { icon: 'cog-6-tooth', onClick: () => App.openSettings('qbit') });
    if (kind === 'cfg') {
      return App.empty('cog-6-tooth', 'qBittorrent n’est pas configuré', 'Ajoute l’adresse et les identifiants de ton qBittorrent pour gérer tes torrents ici.', [
        App.btn('Configurer', { cls: 'pri', icon: 'cog-6-tooth', onClick: () => App.openSettings('qbit') })
      ]);
    }
    if (kind === 'err') return App.empty('exclamation-triangle', 'Connexion impossible', msg, [App.btn('Réessayer', { icon: 'arrow-path', onClick: () => loop.kick() }), settingsBtn()]);
    if (kind === 'loading') return App.loading();
    if (kind === 'warn') return App.note('Connexion perdue : ' + msg, 'err');
    if (kind === 'none') return App.empty('inbox-arrow-down', 'Aucun torrent', 'Envoie un torrent depuis C411 ou ajoute-le avec le bouton +.');
    if (kind === 'nores') {
      const hid = key === 'nores:hidden';
      return App.empty('funnel', 'Aucun résultat', hid ? 'Aucun torrent visible ne correspond aux filtres. Des torrents sont masqués par étiquette.' : 'Aucun torrent ne correspond aux filtres.', [
        App.btn('Réinitialiser les filtres', {
          onClick: () => {
            prefs.filter = 'all';
            if (ui.searchOpen) setSearch(false);
            Q.setFilter({ cat: null, tag: null });
          }
        }),
        hid ? App.btn('Afficher les masqués', { onClick: () => toggleHide() }) : null
      ]);
    }
    return null;
  }
  function render() {
    if (!ui) return;
    const sv = store.server || {};
    const view = getView();
    const hideTagged = view.hideTagged;
    const hiddenN = prefs.hideOn ? hideTagged : 0;
    if (selecting) {
      ui.page.setTitle(selected.size ? fmt.plural(selected.size, 'sélectionné') : 'Sélection');
      ui.page.setSub('Appui long ou toucher pour (dé)sélectionner');
    } else {
      ui.page.setTitle('qBittorrent');
      ui.page.setSub(store.loaded ? fmt.plural(view.total - hiddenN, 'torrent') + (hiddenN ? ' · ' + fmt.plural(hiddenN, 'masqué') : '') + (store.appVersion ? ' · ' + store.appVersion : '') : '');
    }

    ui.strip.classList.toggle('hidden', !store.loaded);
    if (store.loaded) {
      ui.dlTxt.textContent = fmt.speed(sv.dl_info_speed);
      ui.upTxt.textContent = fmt.speed(sv.up_info_speed);
      const lines = [];
      const free = sv.free_space_on_disk;
      if (free != null) {
        const low = free >= 0 && free < 10 * 1024 ** 3;
        lines.push(h('div' + (low ? '.low' : ''), { title: low ? 'Espace disque faible' : null }, 'Libre ' + fmt.bytes(free)));
      }
      if (sv.use_alt_speed_limits) lines.push(h('div.alt', 'Vitesse alternative'));
      else if (sv.connection_status === 'firewalled') lines.push(h('div.alt', 'Derrière un pare-feu'));
      else if (sv.connection_status === 'disconnected') lines.push(h('div', { style: { color: 'var(--red)' } }, 'Déconnecté'));
      App.fill(ui.freeEl, lines);
    }

    ui.catChip.classList.toggle('hidden', prefs.cat == null);
    ui.catTxt.textContent = prefs.cat === '' ? 'Sans catégorie' : prefs.cat || '';
    ui.tagChip.classList.toggle('hidden', prefs.tag == null);
    ui.tagTxt.textContent = prefs.tag === '' ? 'Sans étiquette' : prefs.tag || '';
    ui.filterDot.classList.toggle('hidden', prefs.cat == null && prefs.tag == null);
    const hideNames = Array.from(hideSet);
    const hideWhat = hideNames.length === 1 ? hideNames[0] : fmt.plural(hideNames.length, 'étiquette');
    ui.hideChip.classList.toggle('hidden', !hideTagged);
    App.setOn(ui.hideChip, prefs.hideOn);
    ui.hideChip.title = prefs.hideOn ? 'Afficher les torrents masqués' : 'Masquer ces torrents';
    ui.hideOnIc.classList.toggle('hidden', !prefs.hideOn);
    ui.hideX.classList.toggle('hidden', !prefs.hideOn);
    ui.hideOffIc.classList.toggle('hidden', prefs.hideOn);
    ui.hideTxt.textContent = prefs.hideOn ? hideWhat + (hideNames.length === 1 ? ' masqué' : ' masquées') : 'Masquer ' + hideWhat;
    ui.hideN.textContent = hideTagged;
    FILTERS.forEach((f, i) => {
      const n = view.counts[i];
      const el = ui.chipEls[i];
      el.n.textContent = n;
      App.setOn(el, prefs.filter === f.k);
      el.classList.toggle('hidden', !!f.hideEmpty && !n && prefs.filter !== f.k);
    });

    const shown = store.loaded ? view.shown : [];
    let key = '';
    if (!qb.configured()) key = 'cfg';
    else if (!store.loaded && store.error) key = 'err:' + store.error.message;
    else if (!store.loaded) key = 'loading';
    else if (store.error) key = 'warn:' + store.error.message;
    else if (!view.total) key = 'none';
    else if (!shown.length) key = hiddenN ? 'nores:hidden' : 'nores';
    if (key !== ui.stateKey) {
      ui.stateKey = key;
      App.fill(ui.stateBox, key ? stateContent(key) : null);
    }

    // Only the first `limit` rows exist in the DOM; a new filter, sort or search starts over from the first chunk.
    const lsig = view.bKey + '|' + view.lKey + '|' + prefs.q;
    if (lsig !== ui.lsig) {
      ui.lsig = lsig;
      ui.limit = CHUNK;
    }
    const want = shown.length > ui.limit ? shown.slice(0, ui.limit) : shown;
    const kids = ui.list.children;
    let from = -1;
    want.forEach((t, i) => {
      let r = rows.get(t.hash);
      if (!r) {
        r = makeRow(t.hash);
        rows.set(t.hash, r);
      }
      updateRow(r, t);
      if (from < 0 && kids[i] !== r.el) from = i;
    });
    if (from < 0 && kids.length > want.length) from = want.length;
    if (from >= 0) {
      // Rows before the first difference stay untouched, so a tick or "show more" only appends.
      const restoreFocus = App.holdFocus(ui.list);
      while (kids.length > from) ui.list.lastChild.remove();
      const frag = document.createDocumentFragment();
      for (let i = from; i < want.length; i++) frag.appendChild(rows.get(want[i].hash).el);
      ui.list.appendChild(frag);
      restoreFocus();
    }
    if (rows.size > want.length) {
      const keep = new Set(want.map((t) => t.hash));
      rows.forEach((r, hash) => {
        if (!keep.has(hash)) rows.delete(hash);
      });
    }
    const rest = shown.length - want.length;
    ui.more.classList.toggle('hidden', rest <= 0);
    if (rest > 0 && rest !== ui.moreN) App.fill(ui.more, App.btn('Afficher plus (' + fmt.plural(rest, 'restant') + ')', { cls: 'sm', icon: 'chevron-down', onClick: showMore }));
    ui.moreN = rest;
    if (selecting) {
      for (const x of Array.from(selected)) if (!store.torrents[x]) selected.delete(x);
    }
  }
  Q._render = render;

  // ---------- Transfer sheet ----------
  const CONN = { connected: 'Connecté', firewalled: 'Derrière un pare-feu', disconnected: 'Déconnecté' };
  async function askGlobalLimit(kind) {
    const sv = store.server || {};
    const cur = kind === 'dl' ? sv.dl_rate_limit : sv.up_rate_limit;
    const r = await App.prompt({
      title: kind === 'dl' ? 'Limite de téléchargement' : 'Limite d’envoi',
      label: 'Ko/s (0 = illimitée)',
      value: cur > 0 ? Math.round(cur / 1024) : 0,
      type: 'number',
      inputmode: 'numeric',
      hint: sv.use_alt_speed_limits ? 'La vitesse alternative est active : c’est sa limite qui sera modifiée.' : 'Limite globale de qBittorrent.'
    });
    if (r == null) return;
    const n = Math.max(0, parseInt(r, 10) || 0);
    await qb.post('/transfer/' + (kind === 'dl' ? 'setDownloadLimit' : 'setUploadLimit'), { limit: n * 1024 });
    App.toast('Limite enregistrée', 'ok');
    loop.kick();
  }
  function openTransfer() {
    let off = null;
    const s = App.sheet({ title: 'Transfert', onClose: () => off && off() });
    const dl = h('span');
    const up = h('span');
    const alt = App.switchEl('Vitesse alternative', false, async (v, inp) => {
      try {
        await qb.post('/transfer/toggleSpeedLimitsMode');
      } catch (e) {
        inp.checked = !v;
        throw e;
      }
      store.server.use_alt_speed_limits = v;
      App.toast(v ? 'Vitesse alternative activée' : 'Vitesse normale', 'ok');
      loop.kick();
    }, 'Utilise les limites alternatives définies dans qBittorrent');
    const limDl = h('span.r');
    const limUp = h('span.r');
    const stats = h('div');
    App.append(s.body,
      h('div.card', h('div', { style: { display: 'flex', gap: '22px', justifyContent: 'center' } }, h('span.spd.dl', App.icon('arrow-down'), dl), h('span.spd.up', App.icon('arrow-up'), up))),
      h('div.card', { style: { padding: '0 14px' } }, alt),
      h('div.group',
        h('button.item', { type: 'button', onclick: () => App.run(() => askGlobalLimit('dl')) }, App.icon('arrow-down'), h('div.main', h('div.t', 'Limite de téléchargement')), limDl, App.icon('chevron-right', 'sm')),
        h('button.item', { type: 'button', onclick: () => App.run(() => askGlobalLimit('up')) }, App.icon('arrow-up'), h('div.main', h('div.t', 'Limite d’envoi')), limUp, App.icon('chevron-right', 'sm'))
      ),
      App.secTitle('Statistiques'),
      h('div.card', stats),
      h('div.btn-row',
        App.btn('Préférences', { icon: 'adjustments-horizontal', onClick: () => { s.close(); Q.openPrefs(); } }),
        App.btn('Interface web', { icon: 'arrow-top-right-on-square', onClick: () => App.openExternal(qb.base()) })
      )
    );
    function upd() {
      const v = store.server || {};
      dl.textContent = fmt.speed(v.dl_info_speed);
      up.textContent = fmt.speed(v.up_info_speed);
      alt.input.checked = !!v.use_alt_speed_limits;
      limDl.textContent = v.dl_rate_limit > 0 ? fmt.speed(v.dl_rate_limit) : 'Illimitée';
      limUp.textContent = v.up_rate_limit > 0 ? fmt.speed(v.up_rate_limit) : 'Illimitée';
      App.fill(stats, App.kv([
        ['Téléchargé (session)', fmt.bytes(v.dl_info_data)],
        ['Envoyé (session)', fmt.bytes(v.up_info_data)],
        ['Téléchargé (total)', v.alltime_dl != null ? fmt.bytes(v.alltime_dl) : null],
        ['Envoyé (total)', v.alltime_ul != null ? fmt.bytes(v.alltime_ul) : null],
        ['Ratio global', v.global_ratio != null ? String(v.global_ratio) : null],
        ['Espace libre', v.free_space_on_disk != null ? fmt.bytes(v.free_space_on_disk) : null],
        ['Connexion', CONN[v.connection_status] || v.connection_status],
        ['Nœuds DHT', v.dht_nodes != null ? fmt.num(v.dht_nodes) : null],
        ['Pairs connectés', v.total_peer_connections != null ? fmt.num(v.total_peer_connections) : null],
        ['Version', store.appVersion || null]
      ]));
    }
    upd();
    off = App.on('qbit', upd);
  }

  // ---------- Add torrent ----------
  function readB64(file) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result).replace(/^data:[^,]*,/, ''));
      r.onerror = () => reject(App.mkErr('Lecture du fichier impossible'));
      r.readAsDataURL(file);
    });
  }
  Q.openAdd = async function (o) {
    o = o || {};
    if (!qb.configured()) {
      App.toast('Configure d’abord qBittorrent', 'err');
      App.openSettings('qbit');
      return;
    }
    await Q.ensure(8000).catch(() => null);
    const files = (o.files || []).filter((f) => f && f.base64);
    const urls = h('textarea.input', {
      placeholder: 'magnet:?xt=urn:btih:…\nhttps://exemple.org/fichier.torrent',
      'aria-label': 'Liens magnet ou URL de fichiers .torrent',
      autocapitalize: 'off',
      spellcheck: 'false',
      value: (o.urls || []).join('\n')
    });
    const fileInput = h('input', { type: 'file', accept: '.torrent,application/x-bittorrent', multiple: true, style: { display: 'none' } });
    const fileGroup = h('div.group');
    function renderFiles() {
      fileGroup.classList.toggle('hidden', !files.length);
      App.fill(fileGroup, files.map((f, i) =>
        h('div.item', App.icon('document'), h('div.main', h('div.t.nowrap', f.name || 'fichier.torrent'), h('div.s', fmt.bytes(Math.floor((f.base64.length * 3) / 4)))),
          App.iconBtn('x-mark', 'Retirer', () => {
            files.splice(i, 1);
            renderFiles();
          }))
      ));
    }
    fileInput.addEventListener('change', () => App.run(async () => {
      for (const f of Array.from(fileInput.files || [])) {
        // Android's picker ignores `accept`: keep big non-torrent files (videos…) out of memory.
        if (f.size > 20 * 1024 * 1024) {
          App.toast((f.name || 'Fichier') + ' : trop gros pour un .torrent', 'err');
          continue;
        }
        files.push({ name: f.name, base64: await readB64(f) });
      }
      fileInput.value = '';
      renderFiles();
    }));
    renderFiles();
    const linkBox = App.field(null, urls, 'Un lien magnet ou une URL de fichier .torrent par ligne.');
    const fileBox = h('div', { style: { marginBottom: '14px' } }, fileGroup, App.btn('Choisir des fichiers .torrent', { icon: 'document', cls: 'block', onClick: () => fileInput.click() }), fileInput);
    const show = (v) => {
      linkBox.classList.toggle('hidden', v !== 'url');
      fileBox.classList.toggle('hidden', v !== 'file');
    };
    const seg = App.seg([{ value: 'url', label: 'Liens' }, { value: 'file', label: 'Fichiers .torrent' }], files.length ? 'file' : 'url', show);
    show(seg.value);

    const cats = Q.categoryNames();
    const defCat = o.category != null ? o.category : App.config.lastCategory || App.config.defaultCategory || '';
    const catOpts = [{ value: '', label: 'Aucune catégorie' }].concat(cats.map((c) => ({ value: c, label: c })));
    if (defCat && cats.indexOf(defCat) < 0) catOpts.push({ value: defCat, label: defCat + ' (sera créée)' });
    const cat = App.select(catOpts, defCat);
    const tags = App.input({ placeholder: 'Optionnel, séparées par des virgules' });
    const path = App.input({ placeholder: 'Dossier par défaut' });
    const pathField = App.field('Dossier de destination', path, 'Désactive la gestion automatique pour ce torrent.');
    const updPath = () => {
      const c = store.categories[cat.value];
      path.placeholder = (c && c.savePath) || 'Dossier par défaut';
    };
    cat.addEventListener('change', updPath);
    updPath();
    pathField.classList.add('hidden');
    const customPath = App.switchEl('Choisir le dossier de destination', false, (v) => pathField.classList.toggle('hidden', !v));
    const paused = App.switchEl('Ajouter en pause', false);
    const seq = App.switchEl('Téléchargement séquentiel', false, null, 'Pratique pour lire pendant le téléchargement');
    const fl = App.switchEl('Premières et dernières pièces d’abord', false);
    const skip = App.switchEl('Ne pas vérifier les données', false, null, 'Pour des fichiers déjà complets sur le disque');
    const rename = App.input({ placeholder: 'Nom d’origine' });
    const layout = App.select([
      { value: '', label: 'Par défaut' },
      { value: 'Original', label: 'Original' },
      { value: 'Subfolder', label: 'Créer un sous-dossier' },
      { value: 'NoSubfolder', label: 'Ne pas créer de sous-dossier' }
    ], '');
    const dlLim = App.input({ type: 'number', inputmode: 'numeric', placeholder: 'Illimitée' });
    const upLim = App.input({ type: 'number', inputmode: 'numeric', placeholder: 'Illimitée' });
    const half = (el) => h('div', { style: { flex: '1', minWidth: '0' } }, el);
    const adv = h('div.hidden', { role: 'group', 'aria-label': 'Options avancées', tabindex: '-1' },
      App.field('Renommer', rename, 'Uniquement pour un seul torrent.'),
      App.field('Disposition du contenu', layout),
      h('div', { style: { display: 'flex', gap: '10px' } }, half(App.field('Limite ↓ (Ko/s)', dlLim)), half(App.field('Limite ↑ (Ko/s)', upLim))),
      h('div.card', { style: { padding: '0 14px' } }, fl, skip)
    );
    const advBtn = App.btn('Plus d’options', {
      cls: 'ghost sm',
      icon: 'chevron-down',
      onClick: () => {
        adv.classList.remove('hidden');
        advBtn.classList.add('hidden');
        // The button that had focus is gone: the options it reveals take it.
        adv.focus({ preventScroll: true });
      }
    });

    let s = null;
    async function submit() {
      const links = urls.value.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
      if (!links.length && !files.length) throw App.mkErr('Ajoute au moins un lien ou un fichier .torrent');
      const parts = [];
      const add = (k, v) => {
        if (v != null && v !== '') parts.push({ name: k, value: String(v) });
      };
      if (links.length) add('urls', links.join('\n'));
      for (const f of files) parts.push({ name: 'torrents', filename: f.name || 'fichier.torrent', contentType: 'application/x-bittorrent', base64: f.base64 });
      add('category', cat.value);
      add('tags', tags.value.split(',').map((x) => x.trim()).filter(Boolean).join(','));
      if (customPath.input.checked && path.value.trim()) {
        add('autoTMM', 'false');
        add('savepath', path.value.trim());
      }
      if (paused.input.checked) {
        add('paused', 'true');
        add('stopped', 'true');
      }
      if (seq.input.checked) add('sequentialDownload', 'true');
      if (fl.input.checked) add('firstLastPiecePrio', 'true');
      if (skip.input.checked) add('skip_checking', 'true');
      add('contentLayout', layout.value);
      if (links.length + files.length === 1) add('rename', rename.value.trim());
      const d = parseInt(dlLim.value, 10);
      const u = parseInt(upLim.value, 10);
      if (d > 0) add('dlLimit', d * 1024);
      if (u > 0) add('upLimit', u * 1024);
      const r = String(await qb.call('/torrents/add', { parts: parts })).trim();
      let failed = /^fails/i.test(r);
      try {
        const j = JSON.parse(r);
        if (j && typeof j === 'object' && j.failure_count > 0 && !j.success_count && !j.pending_count) failed = true;
      } catch (e) { /* plain text answer */ }
      if (failed) throw App.mkErr('qBittorrent a refusé l’ajout (torrent déjà présent ou invalide)');
      if (cat.value !== (App.config.lastCategory || '')) App.saveCfg({ lastCategory: cat.value });
      s.close();
      const n = links.length + files.length;
      App.toast(n > 1 ? n + ' torrents ajoutés' : 'Torrent ajouté', 'ok');
      App.haptic();
      loop.kick();
      setTimeout(() => loop.kick(), 1500);
    }
    s = App.sheet({
      title: 'Ajouter un torrent',
      body: [
        seg, linkBox, fileBox,
        App.field('Catégorie', cat),
        App.field('Étiquettes', tags),
        h('div.card', { style: { padding: '0 14px' } }, paused, seq, customPath),
        pathField, advBtn, adv
      ],
      actions: [App.btn('Annuler', { onClick: () => s.close() }), App.btn('Ajouter', { cls: 'pri', icon: 'plus', onClick: submit })]
    });
  };

  // ---------- Init ----------
  Q.init = function () {
    buildMain();
    App.setTabHooks('qbit', {
      onBack() {
        if (selecting) {
          endSelect();
          return true;
        }
        if (ui.searchOpen) {
          setSearch(false);
          return true;
        }
        return false;
      }
    });
    App.on('config', (p) => {
      if ('qbUrl' in p || 'username' in p || 'password' in p) Q.reset();
    });
    render();
    loop.kick();
  };
})();
