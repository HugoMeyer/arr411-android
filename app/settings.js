/* Arr411 settings: qBittorrent / Radarr / Sonarr / Lidarr connections, C411 and notification options. */
(function () {
  'use strict';
  const App = window.App;
  const h = App.h;
  const TAB = 'settings';
  const S = (App.settings = { anchors: {} });
  const cfg = () => App.config;
  const trimSlash = (s) => String(s || '').trim().replace(/[\\/]+$/, '');
  const forms = [];
  const refreshers = [];
  let page = null;
  let scroll = null;
  let pendingAnchor = null;

  // Rebuilds a <select>. A saved value missing from the options stays visible (missing(v) gives its label).
  function fillSel(sel, opts, value, emptyLabel, missing) {
    const v = value == null ? '' : String(value);
    const list = (emptyLabel != null ? [{ value: '', label: emptyLabel }] : []).concat(opts);
    if (v && !list.some((o) => String(o.value) === v)) list.push({ value: v, label: missing ? missing(v) : v + ' (enregistrée)' });
    App.clear(sel);
    for (const o of list) sel.appendChild(h('option', { value: String(o.value) }, o.label));
    sel.value = v;
    if (sel.selectedIndex < 0 && sel.options.length) sel.selectedIndex = 0;
  }
  S.fillSel = fillSel;

  function secret(placeholder) {
    const inp = App.input({ type: 'password', placeholder: placeholder || '', autocomplete: 'new-password' });
    const eye = App.iconBtn('eye', 'Afficher', () => {
      const show = inp.type === 'password';
      inp.type = show ? 'text' : 'password';
      eye.setAttribute('aria-pressed', show ? 'true' : 'false');
      App.fill(eye, App.icon(show ? 'eye-slash' : 'eye'));
    });
    eye.setAttribute('aria-pressed', 'false');
    const row = h('div.pw', inp, eye);
    row.input = inp;
    return row;
  }
  const okNote = (t) => App.note(t, 'ok', 'check-circle');
  const waitNote = () => App.note('Connexion…', 'wait', 'arrow-path');
  function card(key, ic, title) {
    const kids = Array.prototype.slice.call(arguments, 3);
    const el = h('div.card', h('div.card-t', App.icon(ic), h('span', title)), kids);
    if (key) S.anchors[key] = el;
    return el;
  }

  // Text fields saved together ("Enregistrer et tester") or automatically when leaving the settings.
  function form(fields) {
    let dirty = false;
    for (const f of fields) f.el.addEventListener('input', () => { dirty = true; });
    const F = {
      dirty: () => dirty,
      refresh() {
        if (dirty) return;
        for (const f of fields) f.el.value = cfg()[f.key] || '';
      },
      save() {
        const patch = {};
        for (const f of fields) {
          const v = f.get();
          if (f.el.value !== v) f.el.value = v;
          if (v !== (cfg()[f.key] || '')) patch[f.key] = v;
        }
        dirty = false;
        if (Object.keys(patch).length) App.saveCfg(patch);
        return patch;
      }
    };
    forms.push(F);
    refreshers.push(F.refresh);
    F.refresh();
    return F;
  }

  // ---------- qBittorrent categories (shared by several selects) ----------
  const catSels = [];
  let catSig = null;
  function catSelect(key) {
    const sel = App.select([{ value: '', label: 'Aucune' }], '');
    sel.addEventListener('change', () => App.saveCfg({ [key]: sel.value }));
    catSels.push({ sel: sel, key: key });
    return sel;
  }
  function fillCats() {
    const names = App.qbit && App.qbit.store.loaded ? App.qbit.categoryNames() : [];
    const sig = names.join('\n') + '|' + catSels.map((c) => cfg()[c.key] || '').join('\n');
    if (sig === catSig) return;
    catSig = sig;
    const opts = names.map((n) => ({ value: n, label: n }));
    for (const c of catSels) fillSel(c.sel, opts, cfg()[c.key] || '', 'Aucune');
  }
  function loadCats() {
    fillCats();
    if (App.qbit && App.qb.configured()) App.qbit.ensure(8000).then(fillCats, () => {});
  }
  App.on('qbit', () => {
    if (page) fillCats();
  });

  // ---------- qBittorrent ----------
  function qbitCard() {
    const url = App.input({ type: 'url', inputmode: 'url', placeholder: 'https://qbittorrent.exemple.fr' });
    const user = App.input({ placeholder: 'admin' });
    const pass = secret('Mot de passe');
    const status = h('div', { role: 'status' });
    const F = form([
      { el: url, key: 'qbUrl', get: () => App.cleanUrl(url.value) },
      { el: user, key: 'username', get: () => user.value.trim() },
      { el: pass.input, key: 'password', get: () => pass.input.value }
    ]);
    async function test() {
      const patch = F.save();
      if (!App.qb.configured()) return App.fill(status, App.note('Renseigne l’adresse de ton qBittorrent.', 'warn'));
      App.fill(status, waitNote());
      try {
        await App.qb.login();
        const v = String(await App.qb.call('/app/version', { noRetry: true })).trim();
        App.fill(status, okNote('Connecté à qBittorrent ' + v + '.'));
        if (!Object.keys(patch).length && App.qbit) App.qbit.refresh();
        loadCats();
      } catch (e) {
        App.fill(status, App.note(e.message || String(e), 'err'));
      }
    }
    return card('qbit', 'arrow-down-tray', 'qBittorrent',
      App.field('Adresse (URL)', url),
      App.field('Utilisateur', user, 'Laisse vide si l’authentification est désactivée.'),
      App.field('Mot de passe', pass),
      status,
      h('div.btn-row', App.btn('Enregistrer et tester', { cls: 'pri', icon: 'check', onClick: test })),
      h('div.set-sep'),
      App.field('Catégorie par défaut', catSelect('defaultCategory'), 'Pré-sélectionnée quand tu envoies un torrent depuis C411.')
    );
  }

  // ---------- Radarr / Sonarr / Lidarr ----------
  function arrCard(svc) {
    const isR = svc === 'radarr';
    const isL = svc === 'lidarr';
    const name = App.arrName(svc);
    const url = App.input({ type: 'url', inputmode: 'url', placeholder: 'https://' + svc + '.exemple.fr' });
    const key = secret('32 caractères');
    const status = h('div', { role: 'status' });
    const F = form([
      { el: url, key: svc + 'Url', get: () => App.cleanUrl(url.value) },
      { el: key.input, key: svc + 'Key', get: () => key.input.value.trim() }
    ]);
    const profSel = App.select([], '');
    const rootSel = App.select([], '');
    const metaSel = isL ? App.select([], '') : null;
    const rootNote = h('div');
    profSel.addEventListener('change', () => App.saveCfg({ [svc + 'Profile']: profSel.value }));
    rootSel.addEventListener('change', () => App.saveCfg({ [svc + 'Root']: rootSel.value }));
    if (metaSel) metaSel.addEventListener('change', () => App.saveCfg({ lidarrMetaProfile: metaSel.value }));
    let loadedSig = null;
    function idle(msg) {
      for (const s of [profSel, rootSel, metaSel]) {
        if (!s) continue;
        fillSel(s, [], '', msg);
        s.disabled = true;
      }
    }
    async function loadLists(force) {
      const api = App.arr(svc);
      if (!api.configured) {
        loadedSig = null;
        App.clear(rootNote);
        return idle('Configure ' + name + ' d’abord');
      }
      const sig = api.base + '|' + api.key;
      if (sig === loadedSig && !force) return;
      loadedSig = sig;
      idle('Chargement…');
      let m;
      try {
        const M = App.arrUI && App.arrUI[svc];
        m = M ? await M.meta(true) : {
          profiles: await api.get('/qualityprofile'),
          roots: await api.get('/rootfolder'),
          metaProfiles: isL ? await api.get('/metadataprofile') : null
        };
      } catch (e) {
        if (loadedSig === sig) {
          loadedSig = null;
          idle('Liste indisponible');
        }
        throw e;
      }
      if (loadedSig !== sig) return;
      const P = Array.isArray(m.profiles) ? m.profiles : [];
      const R = Array.isArray(m.roots) ? m.roots : [];
      const c = cfg();
      const patch = {};
      let prof = String(c[svc + 'Profile'] || '');
      if (!prof && P.length) prof = patch[svc + 'Profile'] = String(P[0].id);
      const saved = String(c[svc + 'Root'] || '');
      const match = R.find((r) => trimSlash(r.path) === trimSlash(saved));
      let root = match ? match.path : saved;
      if (!root && R.length) root = patch[svc + 'Root'] = R[0].path;
      // Lidarr: "None" only lists hand-added albums, so the default goes to the first real profile.
      const MP = isL && Array.isArray(m.metaProfiles) ? m.metaProfiles : [];
      const isNone = (p) => /^none$/i.test(p.name || '');
      let meta = isL ? String(c.lidarrMetaProfile || '') : '';
      if (isL && !meta && MP.length) meta = patch.lidarrMetaProfile = String((MP.find((p) => !isNone(p)) || MP[0]).id);
      if (Object.keys(patch).length) App.saveCfg(patch);
      fillSel(profSel, P.map((p) => ({ value: String(p.id), label: p.name })), prof, P.length ? null : 'Aucun profil', (v) => 'Profil introuvable (#' + v + ')');
      const free = (r) => (r.freeSpace != null ? ' · ' + App.fmt.bytes(r.freeSpace) + ' libres' : '');
      fillSel(rootSel, R.map((r) => ({ value: r.path, label: r.path + free(r) })), root, R.length ? null : 'Aucun dossier racine', (v) => v + ' (introuvable)');
      profSel.disabled = !P.length;
      rootSel.disabled = !R.length;
      if (metaSel) {
        fillSel(metaSel, MP.map((p) => ({ value: String(p.id), label: isNone(p) ? 'None (seulement les albums ajoutés à la main)' : p.name })),
          meta, MP.length ? null : 'Aucun profil', (v) => 'Profil introuvable (#' + v + ')');
        metaSel.disabled = !MP.length;
      }
      App.fill(rootNote, R.length ? null : App.note('Aucun dossier racine dans ' + name + ' : ajoutes-en un dans ' + name + ' › Réglages › Dossiers racine.', 'warn'));
    }
    async function test() {
      F.save();
      const api = App.arr(svc);
      if (!api.configured) return App.fill(status, App.note('Renseigne l’adresse et la clé API de ' + name + '.', 'warn'));
      App.fill(status, waitNote());
      try {
        const st = await api.get('/system/status');
        if (!st || typeof st !== 'object') throw App.mkErr(name + ' : réponse inattendue (adresse incorrecte ?)');
        const other = st.appName && !new RegExp(svc, 'i').test(st.appName);
        App.fill(status, other
          ? App.note('Cette adresse répond « ' + st.appName + ' » et pas ' + name + ' : vérifie l’URL.', 'warn')
          : okNote('Connecté à ' + name + ' ' + String(st.version || '').trim() + '.'));
        await loadLists(true);
      } catch (e) {
        App.fill(status, App.note(e.message || String(e), 'err'));
      }
    }
    refreshers.push(() => loadLists(false).catch(() => {}));
    const films = isR ? 'films' : isL ? 'albums' : 'séries';
    const kind = isR ? 'film' : isL ? 'musique' : 'série';
    const rootHint = isR ? null : isL ? 'Pré-sélectionné : le dossier est demandé à chaque ajout.' : 'Pré-sélectionné : le dossier (séries ou animes) est demandé à chaque ajout.';
    return card(svc, isR ? 'film' : isL ? 'musical-note' : 'tv', name,
      App.field('Adresse (URL)', url),
      App.field('Clé API', key, 'Dans ' + name + ' : Paramètres › Général › Sécurité › Clé API.'),
      status,
      h('div.btn-row', App.btn('Enregistrer et tester', { cls: 'pri', icon: 'check', onClick: test })),
      h('div.set-sep'),
      App.field('Profil de qualité par défaut', profSel),
      metaSel ? App.field('Profil de métadonnées par défaut', metaSel, 'Types d’albums (albums, EP, singles…) que Lidarr liste pour un nouvel artiste.') : null,
      App.field('Dossier racine par défaut', rootSel, rootHint),
      rootNote,
      App.field('Catégorie qBittorrent pour ' + (isL ? 'la musique' : 'les ' + films), catSelect(svc + 'Category'), 'Utilisée quand tu envoies un torrent de ' + kind + ' depuis C411.'),
      App.note(isL
        ? 'Les artistes et albums ajoutés ne sont jamais surveillés : Lidarr ne lance aucune recherche ni aucun téléchargement de lui-même.'
        : 'Les ' + films + ' ajouté' + (isR ? 's' : 'es') + ' ne sont jamais surveillé' + (isR ? 's' : 'es') + ' : ' + name + ' ne lance aucune recherche ni aucun téléchargement de lui-même.', '', 'shield-check')
    );
  }

  // ---------- C411, notifications, about ----------
  function sw(key, def, label, sub, after) {
    const val = () => (cfg()[key] == null ? def : !!cfg()[key]);
    const el = App.switchEl(label, val(), (v) => {
      App.saveCfg({ [key]: v });
      if (after) after(v);
    }, sub);
    refreshers.push(() => {
      el.input.checked = val();
      if (after) after(val(), true);
    });
    return el;
  }
  function c411Card() {
    const start = App.select([
      { value: 'c411', label: 'C411' },
      { value: 'radarr', label: 'Radarr' },
      { value: 'sonarr', label: 'Sonarr' },
      { value: 'lidarr', label: 'Lidarr' },
      { value: 'qbit', label: 'qBittorrent' },
      { value: 'last', label: 'Dernier onglet utilisé' }
    ], cfg().startTab || 'c411');
    start.addEventListener('change', () => App.saveCfg({ startTab: start.value }));
    refreshers.push(() => {
      start.value = cfg().startTab || 'c411';
    });
    return card('c411', 'globe-alt', 'C411',
      sw('listBadges', true, 'Badges dans les listes', 'Signale les torrents déjà présents dans qBittorrent.'),
      sw('desktopMode', false, 'Mode ordinateur', 'Affiche la version bureau du site.', (v, quiet) => {
        if (!quiet) App.nat('reloadC411');
      }),
      App.field('Onglet à l’ouverture de l’app', start)
    );
  }
  function notifCard() {
    const all = sw('notifyAll', false, 'Pour tous les torrents', 'Pas seulement ceux envoyés depuis C411.');
    const en = sw('notifyEnabled', true, 'Téléchargement terminé', 'Notification quand un torrent envoyé depuis C411 est fini.', (v) => {
      all.classList.toggle('off', !v);
      all.input.disabled = !v;
    });
    all.classList.toggle('off', !en.input.checked);
    all.input.disabled = !en.input.checked;
    return card('notif', 'bell', 'Notifications', en, all);
  }

  // The app writes down its incidents (crashes, memory, pages that stop) in a log kept on the phone; the report gathers it into one text to copy or send.
  function diagCard() {
    if (!window.Native || typeof window.Native.diagReport !== 'function') return null;
    const out = h('pre.diag-out.sel.mono');
    out.hidden = true;
    const toggle = App.btn('Afficher', {
      icon: 'document-text',
      onClick: () => {
        out.hidden = !out.hidden;
        toggle.querySelector('span').textContent = out.hidden ? 'Afficher' : 'Masquer';
        if (!out.hidden) out.textContent = String(App.nat('diagReport') || 'Rapport indisponible.');
      }
    });
    const clear = () =>
      App.confirm({
        title: 'Effacer le journal ?',
        message: 'Le journal et le dernier incident enregistré seront supprimés de ce téléphone. Envoie d’abord le rapport si tu veux le garder.',
        ok: 'Effacer',
        danger: true
      }).then((ok) => {
        if (!ok) return;
        App.nat('diagClear');
        if (!out.hidden) out.textContent = String(App.nat('diagReport') || '');
        App.toast('Journal effacé', 'ok');
      });
    return card('diag', 'wrench-screwdriver', 'Diagnostic',
      App.note('Quand l’app se ferme toute seule, son journal permet de comprendre pourquoi. Il reste sur ce téléphone ; les mots de passe, clés API et jetons y sont masqués, mais pas les adresses de tes serveurs.', '', 'information-circle'),
      h('div.btn-row',
        App.btn('Copier le rapport', { icon: 'clipboard-document', cls: 'pri', onClick: () => App.copy(App.nat('diagReport'), 'Rapport') }),
        App.btn('Partager', { icon: 'share', onClick: () => App.nat('diagShare') })
      ),
      h('div.btn-row', toggle, App.btn('Effacer', { icon: 'trash', onClick: clear })),
      out
    );
  }
  function about() {
    const v = App.nat('appVersion');
    return h('div.about', h('img.about-logo', { src: 'logo.png', alt: '' }), h('div.logo', 'Arr', h('span', '411')), h('div', 'C411 · qBittorrent · Radarr · Sonarr · Lidarr'), v ? h('div', 'Version ' + v) : null);
  }

  // ---------- Score des torrents ----------
  // Same ids and weights as the shared engine (arr411-chrome background.js), which scores the torrents of c411.org.
  const SCORE_PRESETS = [
    { value: 'equilibre', label: 'Équilibré (par défaut)', hint: 'Privilégie l’équilibre : langue et qualité d’abord, puis seeders et taille.' },
    { value: 'qualite', label: 'Qualité d’image', hint: 'Privilégie la qualité d’image : un Remux ou une 2160p passent devant les encodages compacts.' },
    { value: 'vf', label: 'VF / MULTI d’abord', hint: 'Privilégie la langue : MULTI.VFF et VOF loin devant VF2, VFQ et VOSTFR.' },
    { value: 'leger', label: 'Petite taille', hint: 'Privilégie la petite taille : la meilleure qualité pour le moins de Go.' },
    { value: 'ratio', label: 'Ratio (seeders)', hint: 'Privilégie les seeders, pour faire du ratio.' },
    { value: 'perso', label: 'Personnalisé', hint: 'Privilégie ce que tu choisis : règle le poids de chaque critère.' }
  ];
  const SCORE_WEIGHTS = [
    { key: 'lang', label: 'Langue (VF/MULTI)', def: 30 },
    { key: 'quality', label: 'Qualité', def: 30 },
    { key: 'health', label: 'Santé (seeders)', def: 20 },
    { key: 'size', label: 'Taille', def: 20 }
  ];
  // An integer from 0 to 100, or the fallback when v is not a usable number.
  function toWeight(v, fallback) {
    const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() ? Number(v) : NaN;
    return isFinite(n) ? Math.min(100, Math.max(0, Math.round(n))) : fallback;
  }
  const curPreset = () => (SCORE_PRESETS.some((p) => p.value === cfg().scorePreset) ? cfg().scorePreset : 'equilibre');
  // The saved weights; the default ones when none is saved or all are 0 (the engine scores that as « équilibre »).
  function savedWeights() {
    const src = cfg().scoreWeights && typeof cfg().scoreWeights === 'object' ? cfg().scoreWeights : {};
    const out = {};
    for (const k of SCORE_WEIGHTS) out[k.key] = toWeight(src[k.key], k.def);
    if (SCORE_WEIGHTS.every((k) => out[k.key] === 0)) for (const k of SCORE_WEIGHTS) out[k.key] = k.def;
    return out;
  }
  function scoreCard() {
    const sel = App.select(SCORE_PRESETS, curPreset());
    const hint = h('div.hint');
    const inputs = {};
    const grid = h('div.score-grid');
    for (const k of SCORE_WEIGHTS) {
      inputs[k.key] = App.input({ type: 'number', inputmode: 'numeric', min: '0', max: '100', step: '1', 'aria-label': k.label });
      grid.appendChild(App.field(k.label, inputs[k.key]));
    }
    const zero = App.note('Au moins un poids doit être supérieur à 0 : réglage non enregistré.', 'warn');
    zero.classList.add('hidden');
    const weights = h('div', grid, zero, h('div.hint.score-hint', 'Poids de 0 à 100 : seul leur rapport compte, et un poids à 0 ignore le critère.'));

    // An empty or invalid field keeps the saved weight; all zeros is refused (the last valid weights stay saved).
    function commit(final) {
      if (curPreset() !== 'perso') return;
      const saved = savedWeights();
      const w = {};
      for (const k of SCORE_WEIGHTS) w[k.key] = toWeight(inputs[k.key].value, saved[k.key]);
      const ok = SCORE_WEIGHTS.some((k) => w[k.key] > 0);
      zero.classList.toggle('hidden', ok);
      if (final) for (const k of SCORE_WEIGHTS) inputs[k.key].value = String(w[k.key]);
      if (!ok || SCORE_WEIGHTS.every((k) => w[k.key] === saved[k.key])) return;
      App.saveCfg({ scorePreset: 'perso', scoreWeights: w });
    }
    function sync() {
      const p = curPreset();
      const w = savedWeights();
      sel.value = p;
      for (const k of SCORE_WEIGHTS) inputs[k.key].value = String(w[k.key]);
      zero.classList.add('hidden');
      hint.textContent = SCORE_PRESETS.find((x) => x.value === p).hint;
      weights.classList.toggle('hidden', p !== 'perso');
    }
    sel.addEventListener('change', () => {
      const v = sel.value;
      if (!SCORE_PRESETS.some((p) => p.value === v)) return;
      App.saveCfg(v === 'perso' ? { scorePreset: v, scoreWeights: savedWeights() } : { scorePreset: v });
      sync();
    });
    const typing = App.debounce(() => commit(false), 600);
    for (const k of SCORE_WEIGHTS) {
      inputs[k.key].addEventListener('input', typing);
      inputs[k.key].addEventListener('change', () => commit(true));
    }
    refreshers.push(sync);
    sync();
    const presetLab = h('label', 'Profil de score');
    App.tie(presetLab, sel, hint);
    return card('score', 'chart-bar', 'Score des torrents',
      h('div.field', presetLab, sel, hint),
      weights,
      h('div.hint', 'S’applique aux badges de score sur c411.org (recharge la page si elle est ouverte).')
    );
  }

  // ---------- Mises à jour ----------
  // The host answers updateInfo() with a JSON string and pushes every change through App.onUpdate (see the update contract).
  const hasUpdInfo = () => !!(window.Native && typeof window.Native.updateInfo === 'function');
  const hasUpdCheck = () => !!(window.Native && typeof window.Native.updateCheck === 'function');
  const hasUpdInstall = () => !!(window.Native && typeof window.Native.updateInstall === 'function');
  const UPD_STATUS = ['idle', 'checking', 'uptodate', 'downloading', 'ready', 'installing', 'error'];
  const UPD_BUSY = ['checking', 'downloading', 'installing'];
  const UPD_MODES = [
    { value: 'auto', label: 'Automatique (recommandé)', hint: 'Recherche, télécharge et installe les mises à jour toute seule, quand tu n’utilises pas l’app.' },
    { value: 'notify', label: 'Me prévenir seulement', hint: 'Recherche régulièrement et te prévient ; l’installation reste manuelle.' },
    { value: 'off', label: 'Désactivée', hint: 'Aucune recherche automatique ; tu peux toujours vérifier à la main.' }
  ];
  const updMode = (v) => (UPD_MODES.some((m) => m.value === v) ? v : null);
  let upd = null;
  let updUi = null;
  const curMode = () => updMode(cfg().autoUpdate) || (upd && upd.mode) || 'auto';

  // The state as an object or JSON text, reduced to the known fields; null when unusable.
  function parseUpdate(raw) {
    let o = raw;
    if (typeof o === 'string') {
      try {
        o = JSON.parse(o);
      } catch (e) {
        return null;
      }
    }
    if (!o || typeof o !== 'object' || Array.isArray(o)) return null;
    const str = (v) => (typeof v === 'string' ? v : '');
    const num = (v) => (typeof v === 'number' && isFinite(v) ? v : null);
    const progress = num(o.progress);
    const checkedAt = num(o.checkedAt);
    return {
      supported: o.supported !== false,
      current: str(o.current),
      latest: str(o.latest),
      available: o.available === true,
      status: UPD_STATUS.indexOf(o.status) >= 0 ? o.status : 'idle',
      progress: progress == null ? null : Math.max(0, Math.min(1, progress)),
      notes: str(o.notes),
      url: str(o.url),
      checkedAt: checkedAt > 0 ? checkedAt : null,
      canInstall: o.canInstall === true,
      error: str(o.error),
      mode: updMode(o.mode)
    };
  }
  // Release notes are GitHub markdown; they are shown as plain text (textContent, never HTML).
  function plainNotes(md) {
    return String(md || '')
      .replace(/\r\n?/g, '\n')
      .replace(/<!--[\s\S]*?-->/g, '')
      .replace(/^[ \t]*(```|~~~).*$/gm, '')
      .replace(/^[ \t]*#{1,6}[ \t]+/gm, '')
      .replace(/^[ \t]*([-*_])([ \t]*\1){2,}[ \t]*$/gm, '')
      .replace(/^[ \t]*>[ \t]?/gm, '')
      .replace(/<\/?[a-z][^>]*>/gi, '')
      .replace(/!?\[([^\]\n]*)\]\([^)\s]*\)/g, '$1')
      .replace(/^([ \t]*)[-*+][ \t]+/gm, '$1• ')
      .replace(/\*\*([^*\n]+)\*\*/g, '$1')
      .replace(/(^|[\s(])\*(?=\S)([^*\n]*?\S)\*(?=[\s).,;:!?]|$)/gm, '$1$2')
      .replace(/`([^`\n]*)`/g, '$1')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
  }
  // The status line: { kind, ic, text, bar } or null when there is nothing to say.
  function updStatus(u) {
    switch (u.status) {
      case 'checking':
        return { kind: 'wait', ic: 'arrow-path', text: 'Vérification en cours…' };
      case 'downloading':
        return { kind: 'wait', ic: 'arrow-path', text: u.progress == null ? 'Téléchargement de la mise à jour…' : 'Téléchargement de la mise à jour : ' + Math.round(u.progress * 100) + ' %', bar: u.progress };
      case 'ready':
        return { kind: 'ok', ic: 'check-circle', text: 'Mise à jour prête à installer.' };
      case 'installing':
        return { kind: 'wait', ic: 'arrow-path', text: 'Installation en cours…' };
      case 'error':
        return { kind: 'err', ic: 'exclamation-triangle', text: 'Erreur : ' + (u.error || 'cause inconnue') };
    }
    if (u.available) return { kind: 'ok', ic: 'arrow-down-tray', text: 'Une nouvelle version est disponible.' };
    if (u.status === 'uptodate' || (u.latest && u.checkedAt)) return { kind: 'ok', ic: 'check-circle', text: 'Arr411 est à jour.' };
    return null;
  }
  const updNew = () => hasUpdInfo() && !!(upd && upd.supported && upd.available);
  // body.upd-new lights the dot on the « ⋯ » button of the main pages (app.css).
  function syncDot() {
    if (document.body) document.body.classList.toggle('upd-new', updNew());
  }
  function setText(el, t) {
    if (el.textContent !== t) el.textContent = t;
  }

  function renderUpdate() {
    const ui = updUi;
    if (!ui) return;
    const u = upd;
    const busy = !!u && UPD_BUSY.indexOf(u.status) >= 0;
    const st = u ? updStatus(u) : null;
    ui.root.classList.toggle('hidden', !!u && !u.supported);
    ui.badge.classList.toggle('hidden', !(u && u.available));
    setText(ui.cur, (u && u.current) || ui.appVer || '—');
    setText(ui.last, (u && u.latest) || '—');
    setText(ui.when, u && u.checkedAt ? 'Vérifié ' + App.fmt.rel(Math.min(u.checkedAt, Date.now())) : 'Pas encore vérifié');
    ui.when.classList.toggle('hidden', !u);

    const cls = 'note upd-st' + (st ? ' ' + st.kind : ' hidden');
    if (ui.noteCls !== cls) ui.note.className = ui.noteCls = cls;
    if (st) {
      if (ui.noteIcName !== st.ic) {
        const ic = App.icon(st.ic);
        ui.note.replaceChild(ic, ui.noteIc);
        ui.noteIc = ic;
        ui.noteIcName = st.ic;
      }
      setText(ui.msg, st.text);
      ui.bar.classList.toggle('hidden', st.bar == null);
      if (st.bar != null) ui.bar.set(st.bar);
    }

    const notes = u ? u.notes : '';
    if (notes !== ui.notesRaw) {
      ui.notesRaw = notes;
      const t = plainNotes(notes);
      ui.notes.textContent = t;
      ui.foldBox.classList.toggle('hidden', !t);
    }
    setText(ui.foldLabel, u && u.latest ? 'Nouveautés de la version ' + u.latest : 'Nouveautés');

    const canInstall = !!u && u.available && u.canInstall && hasUpdInstall();
    ui.install.classList.toggle('hidden', !canInstall);
    ui.install.disabled = busy;
    setText(ui.install.querySelector('span'), u && u.latest ? 'Installer la version ' + u.latest : 'Installer la mise à jour');
    ui.page.classList.toggle('hidden', !(u && u.available && !canInstall && u.url));
    ui.check.disabled = busy;
  }
  function setUpdate(raw) {
    upd = parseUpdate(raw);
    syncDot();
    renderUpdate();
    updLoop.schedule();
  }
  // While the card is on screen and the host is working, the state is read every second (a push may have been missed).
  const updLoop = App.loop(
    () => setUpdate(App.nat('updateInfo')),
    () => (updUi && upd && UPD_BUSY.indexOf(upd.status) >= 0 && App.visible(TAB) ? 1000 : null)
  );

  function updateCard() {
    if (!hasUpdInfo()) return null;
    const ui = {
      appVer: App.nat('appVersion'),
      badge: App.badge('Nouveau', 'green'),
      cur: h('dd.sel'),
      last: h('dd.sel'),
      when: h('div.hint.upd-when'),
      msg: h('div'),
      bar: App.bar(0),
      noteIc: App.icon('arrow-path'),
      noteIcName: 'arrow-path',
      noteCls: null,
      notesRaw: null,
      foldLabel: h('span'),
      notes: h('div.diag-out.upd-notes.sel.hidden')
    };
    ui.note = h('div.note.upd-st.hidden', ui.noteIc, h('div.upd-body', ui.msg, ui.bar));
    ui.fold = h('button.upd-fold', { type: 'button', 'aria-expanded': 'false' }, App.icon('chevron-right'), ui.foldLabel);
    ui.fold.addEventListener('click', () => {
      const open = ui.notes.classList.contains('hidden');
      ui.notes.classList.toggle('hidden', !open);
      ui.fold.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    ui.foldBox = h('div.hidden', ui.fold, ui.notes);

    // Ask the host, show what it answers at once (the push that follows keeps it current), and keep the button busy a moment.
    const ask = (method) => () => {
      App.nat(method);
      setUpdate(App.nat('updateInfo'));
      return App.sleep(900);
    };
    ui.install = App.btn('Installer la mise à jour', { icon: 'arrow-down-tray', cls: 'pri', onClick: ask('updateInstall') });
    ui.page = App.btn('Ouvrir la page de téléchargement', { icon: 'arrow-top-right-on-square', onClick: () => upd && upd.url && App.openExternal(upd.url) });
    ui.check = App.btn('Vérifier maintenant', { icon: 'arrow-path', onClick: ask('updateCheck') });
    if (!hasUpdCheck()) ui.check.classList.add('hidden');

    const select = App.select(UPD_MODES, curMode());
    const modeHint = h('div.hint');
    const showMode = () => {
      const m = UPD_MODES.find((x) => x.value === curMode());
      select.value = m.value;
      modeHint.textContent = m.hint;
    };
    select.addEventListener('change', () => {
      if (!updMode(select.value)) return;
      App.saveCfg({ autoUpdate: select.value });
      showMode();
    });

    const modeLab = h('label', 'Mise à jour automatique');
    App.tie(modeLab, select, modeHint);
    ui.root = card('update', 'arrow-down-tray', 'Mises à jour',
      h('dl.kv', h('dt', 'Version actuelle'), ui.cur, h('dt', 'Dernière version'), ui.last),
      ui.when,
      ui.note,
      ui.foldBox,
      h('div.btn-row', ui.install, ui.page, ui.check),
      h('div.set-sep'),
      h('div.field', modeLab, select, modeHint)
    );
    ui.root.firstChild.appendChild(ui.badge);
    updUi = ui;
    refreshers.push(() => {
      showMode();
      setUpdate(App.nat('updateInfo'));
    });
    showMode();
    renderUpdate();
    return ui.root;
  }

  // Available from startup, before the settings are ever opened.
  App.onUpdate = setUpdate;
  // An entry above « Réglages » in the « ⋯ » menus while an update is waiting.
  App.menuItems = function (items) {
    try {
      const i = updNew() ? items.findIndex((it) => it && it !== '-' && it.icon === 'cog-6-tooth') : -1;
      if (i < 0) return items;
      const entry = { icon: 'arrow-down-tray', label: 'Mise à jour disponible', sub: upd.latest ? 'Version ' + upd.latest : null, right: App.badge('Nouveau', 'green'), onClick: () => App.openSettings('update') };
      return items.slice(0, i).concat([entry], items.slice(i));
    } catch (e) {
      return items;
    }
  };
  // The app may have been in the background while the host pushed (and the page could not hear it).
  let wasForeground = App.state.fg;
  App.on('visibility', () => {
    if (App.state.fg && !wasForeground && hasUpdInfo()) setUpdate(App.nat('updateInfo'));
    wasForeground = App.state.fg;
  });
  if (hasUpdInfo()) setUpdate(App.nat('updateInfo'));
  // Exposed for the unit tests (test\spa).
  Object.assign(S, { updateCard: updateCard, scoreCard: scoreCard, parseUpdate: parseUpdate, plainNotes: plainNotes });

  // ---------- Page ----------
  function reloadCfg() {
    if (!window.Native) return;
    let c = null;
    try {
      c = JSON.parse(App.nat('config') || 'null');
    } catch (e) {
      c = null;
    }
    if (c && typeof c === 'object') App.config = c;
  }
  function leave() {
    const p = App.state.prevTab;
    App.goTab(p && p !== TAB ? p : 'qbit');
    return true;
  }
  function scrollToAnchor() {
    const el = pendingAnchor && S.anchors[pendingAnchor];
    pendingAnchor = null;
    if (!el || !scroll) return;
    setTimeout(() => {
      scroll.scrollTop += el.getBoundingClientRect().top - scroll.getBoundingClientRect().top - 8;
    }, 60);
  }
  function onShow() {
    reloadCfg();
    for (const r of refreshers) r();
    loadCats();
    scrollToAnchor();
  }
  // For the backup card (backup.js): typed values are saved before an export or an import, and the page is redrawn after an import.
  S.flushForms = () => {
    for (const F of forms) if (F.dirty()) F.save();
  };
  S.refresh = onShow;
  function backupCard() {
    return App.backup ? card('backup', 'shield-check', 'Sauvegarde des réglages', App.backup.body()) : null;
  }
  S.init = function () {
    if (page) return;
    page = App.page({ title: 'Réglages', subtitle: 'Arr411', back: true, onBack: leave });
    scroll = h('div.scroll', qbitCard(), arrCard('radarr'), arrCard('sonarr'), arrCard('lidarr'), c411Card(), scoreCard(), notifCard(), updateCard(), backupCard(), diagCard(), about());
    page.el.appendChild(scroll);
    page.onShow = onShow;
    App.push(TAB, page);
    App.setTabHooks(TAB, { onBack: leave });
    // Typed values are kept when leaving the settings without pressing "Enregistrer et tester".
    App.on('visibility', () => {
      if (App.visible(TAB)) return;
      let n = 0;
      for (const F of forms) if (F.dirty() && Object.keys(F.save()).length) n++;
      if (n) App.toast('Réglages enregistrés', 'ok');
    });
  };
  App.openSettings = function (section) {
    if (!page) S.init();
    App.popToRoot(TAB);
    pendingAnchor = section || null;
    if (App.state.tab === TAB) {
      if (scroll && !section) scroll.scrollTop = 0;
      scrollToAnchor();
    } else {
      if (scroll && !section) scroll.scrollTop = 0;
      App.goTab(TAB);
    }
  };
})();
