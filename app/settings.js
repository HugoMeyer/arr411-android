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
      App.fill(eye, App.icon(show ? 'eye-slash' : 'eye'));
    });
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
    const status = h('div');
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
    const status = h('div');
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
  function about() {
    const v = App.nat('appVersion');
    return h('div.about', h('img.about-logo', { src: 'logo.png', alt: '' }), h('div.logo', 'Arr', h('span', '411')), h('div', 'C411 · qBittorrent · Radarr · Sonarr · Lidarr'), v ? h('div', 'Version ' + v) : null);
  }

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
  S.init = function () {
    if (page) return;
    page = App.page({ title: 'Réglages', subtitle: 'Arr411', back: true, onBack: leave });
    scroll = h('div.scroll', qbitCard(), arrCard('radarr'), arrCard('sonarr'), arrCard('lidarr'), c411Card(), notifCard(), about());
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
