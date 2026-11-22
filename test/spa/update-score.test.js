'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadSpa } = require('./load-spa');
const { FakeNode, createDocument, find, findAll, isShown } = require('./fake-dom');

globalThis.Node = FakeNode;

const MIN = 60 * 1000;
const URL_410 = 'https://github.com/HugoMeyer/arr411-android/releases/tag/v2.4.0';
const uptodate = () => ({ supported: true, current: '2.3.1', latest: '2.3.1', available: false, status: 'uptodate', progress: null, notes: '', url: '', checkedAt: Date.now() - 5 * MIN, canInstall: true, error: null, mode: 'auto' });
const available = () => Object.assign(uptodate(), { latest: '2.4.0', available: true, status: 'idle', url: URL_410 });

// A host bridge double: updateInfo() answers `state` as JSON text, like the real hosts.
function bridge(state, extra) {
  const N = {
    state: state,
    saved: [],
    calls: [],
    appVersion: () => '2.3.1',
    updateInfo: () => (N.raw !== undefined ? N.raw : JSON.stringify(N.state)),
    updateCheck: () => {
      N.calls.push('updateCheck');
    },
    updateInstall: () => {
      N.calls.push('updateInstall');
    },
    saveConfig: (json) => {
      N.saved.push(json);
    },
    openUrl: (u) => {
      N.calls.push('openUrl ' + u);
    }
  };
  return Object.assign(N, extra);
}
function boot(Native) {
  const document = createDocument();
  const win = loadSpa(['core.js', 'settings.js'], { Native: Native, document: document });
  return { win: win, App: win.App, S: win.App.settings, body: document.body };
}
const withClass = (root, c) => find(root, (n) => n.classList.contains(c));
const button = (root, label) => find(root, (n) => n.localName === 'button' && n.textContent === label);
const select = (root) => find(root, (n) => n.localName === 'select');
const change = (el, value) => {
  el.value = value;
  el.dispatchEvent({ type: 'change' });
};
const flush = () => new Promise((resolve) => setImmediate(resolve));

// ---------- Mises à jour ----------

test('a host without updateInfo has no updater: no card, no dot, menus untouched', () => {
  for (const Native of [undefined, { saveConfig() {} }]) {
    const { App, S, body } = boot(Native);
    assert.equal(S.updateCard(), null);
    App.onUpdate(available());
    assert.equal(body.classList.contains('upd-new'), false);
    const items = [{ icon: 'cog-6-tooth', label: 'Réglages' }];
    assert.equal(App.menuItems(items), items);
  }
});

test('App.onUpdate lights the dot and the menu entry, and re-renders the card', () => {
  const { App, S, body } = boot(bridge(uptodate()));
  const card = S.updateCard();
  const [cur, last] = findAll(card, (n) => n.localName === 'dd');
  const badge = withClass(card, 'badge');
  assert.equal(cur.textContent, '2.3.1');
  assert.equal(withClass(card, 'upd-st').textContent, 'Arr411 est à jour.');
  assert.equal(withClass(card, 'upd-when').textContent, 'Vérifié il y a 5 min');
  assert.equal(isShown(badge), false);
  assert.equal(body.classList.contains('upd-new'), false);

  App.onUpdate(available());
  assert.equal(body.classList.contains('upd-new'), true);
  assert.equal(last.textContent, '2.4.0');
  assert.equal(withClass(card, 'upd-st').textContent, 'Une nouvelle version est disponible.');
  assert.equal(isShown(badge), true);
  assert.equal(isShown(button(card, 'Installer la version 2.4.0')), true);
  App.openSettings = (section) => {
    App.opened = section;
  };
  const menu = App.menuItems([{ icon: 'x' }, '-', { icon: 'cog-6-tooth', label: 'Réglages' }]);
  assert.deepEqual(menu.map((it) => it.label), [undefined, undefined, 'Mise à jour disponible', 'Réglages']);
  assert.equal(menu[2].sub, 'Version 2.4.0');
  menu[2].onClick();
  assert.equal(App.opened, 'update');

  App.onUpdate(JSON.stringify(uptodate())); // a JSON string is accepted too
  assert.equal(body.classList.contains('upd-new'), false);
  assert.equal(isShown(badge), false);
  assert.equal(App.menuItems([{ icon: 'cog-6-tooth' }]).length, 1);

  App.onUpdate(Object.assign(available(), { supported: false }));
  assert.equal(body.classList.contains('upd-new'), false);
  assert.equal(isShown(card), false);
});

test('the dot is set at startup and refreshed when the app returns to the foreground', () => {
  const N = bridge(available());
  const { App, body } = boot(N);
  assert.equal(body.classList.contains('upd-new'), true);
  App.setForeground(false);
  N.state = uptodate(); // a push is not heard while in the background
  App.setForeground(true);
  assert.equal(body.classList.contains('upd-new'), false);
});

test('each status has its own line, and checking, downloading and installing lock the buttons', () => {
  const { App, S } = boot(bridge(uptodate()));
  const card = S.updateCard();
  const note = withClass(card, 'upd-st');
  const check = button(card, 'Vérifier maintenant');
  const rows = [
    [{ status: 'checking' }, 'Vérification en cours…', true],
    [{ status: 'downloading', progress: 0.42 }, 'Téléchargement de la mise à jour : 42 %', true],
    [{ status: 'downloading', progress: null }, 'Téléchargement de la mise à jour…', true],
    [{ status: 'ready' }, 'Mise à jour prête à installer.', false],
    [{ status: 'installing' }, 'Installation en cours…', true],
    [{ status: 'error', error: 'Signature invalide' }, 'Erreur : Signature invalide', false],
    [{ status: 'error', error: null }, 'Erreur : cause inconnue', false]
  ];
  for (const [patch, text, locked] of rows) {
    App.onUpdate(Object.assign(available(), patch));
    assert.equal(note.textContent, text, text);
    assert.equal(isShown(note), true);
    assert.equal(check.disabled, locked, text);
    assert.equal(button(card, 'Installer la version 2.4.0').disabled, locked, text);
  }
  App.onUpdate(Object.assign(available(), { status: 'downloading', progress: 0.42 }));
  assert.equal(find(withClass(card, 'bar'), (n) => n.localName === 'i').style.width, '42.0%');
  assert.equal(isShown(withClass(card, 'bar')), true);
  App.onUpdate(Object.assign(available(), { status: 'ready' }));
  assert.equal(isShown(withClass(card, 'bar')), false);
});

test('without canInstall the card opens the download page instead of installing', () => {
  const N = bridge(Object.assign(available(), { canInstall: false }));
  const { S } = boot(N);
  const card = S.updateCard();
  assert.equal(isShown(button(card, 'Installer la version 2.4.0')), false);
  const page = button(card, 'Ouvrir la page de téléchargement');
  assert.equal(isShown(page), true);
  page.click();
  assert.deepEqual(N.calls, ['openUrl ' + URL_410]);
});

test('the buttons call the host, and a method the host lacks hides its button', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const N = bridge(available());
  const { win, S } = boot(N);
  const card = S.updateCard();
  button(card, 'Vérifier maintenant').click();
  button(card, 'Installer la version 2.4.0').click();
  assert.deepEqual(N.calls, ['updateCheck', 'updateInstall']);
  assert.deepEqual(win.toasts, []);

  const old = boot(bridge(available(), { updateCheck: undefined, updateInstall: undefined }));
  const oldCard = old.S.updateCard();
  assert.equal(isShown(button(oldCard, 'Vérifier maintenant')), false);
  assert.equal(isShown(button(oldCard, 'Installer la version 2.4.0')), false);
  assert.equal(isShown(button(oldCard, 'Ouvrir la page de téléchargement')), true);
});

test('the mode select saves autoUpdate as JSON text, and no saved value means automatic', () => {
  const N = bridge(uptodate());
  const { App, S } = boot(N);
  const card = S.updateCard();
  const sel = select(card);
  assert.deepEqual(sel.options.map((o) => [o.value, o.textContent]), [['auto', 'Automatique (recommandé)'], ['notify', 'Me prévenir seulement'], ['off', 'Désactivée']]);
  assert.equal(sel.value, 'auto');
  change(sel, 'notify');
  assert.deepEqual(N.saved, ['{"autoUpdate":"notify"}']);
  assert.equal(App.config.autoUpdate, 'notify');
  assert.equal(findAll(card, (n) => n.classList.contains('hint')).pop().textContent, 'Recherche régulièrement et te prévient ; l’installation reste manuelle.');

  App.config = { autoUpdate: 'off' };
  assert.equal(select(boot(bridge(uptodate())).S.updateCard()).value, 'auto');
  const again = boot(bridge(uptodate()));
  again.App.config = { autoUpdate: 'off' };
  assert.equal(select(again.S.updateCard()).value, 'off');
});

test('unusable payloads are ignored: no error, no status line, no dot', () => {
  const N = bridge(available());
  const { win, App, S, body } = boot(N);
  const card = S.updateCard();
  for (const junk of ['{oops', '', null, undefined, 42, [1], 'null', '"x"']) {
    App.onUpdate(available());
    assert.equal(body.classList.contains('upd-new'), true);
    assert.doesNotThrow(() => App.onUpdate(junk));
    assert.equal(isShown(withClass(card, 'upd-st')), false);
    assert.equal(body.classList.contains('upd-new'), false);
  }
  assert.equal(findAll(card, (n) => n.localName === 'dd')[0].textContent, '2.3.1'); // from appVersion()
  N.raw = '{broken';
  button(card, 'Vérifier maintenant').click();
  assert.deepEqual(win.toasts, []);
  assert.equal(isShown(withClass(card, 'upd-st')), false);
  assert.equal(S.parseUpdate({ available: 'yes', status: 'bogus', progress: 7, checkedAt: 'x' }).available, false);
  assert.equal(S.parseUpdate({ status: 'bogus' }).status, 'idle');
  assert.equal(S.parseUpdate({ progress: 7 }).progress, 1);
  assert.equal(S.parseUpdate({ checkedAt: 'x' }).checkedAt, null);
});

test('while the host works and the card is on screen, updateInfo() is read every second until it settles', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const N = bridge(Object.assign(available(), { status: 'checking' }));
  let reads = 0;
  const read = N.updateInfo;
  N.updateInfo = () => {
    reads++;
    return read();
  };
  const { App, S } = boot(N);
  const card = S.updateCard();
  const note = withClass(card, 'upd-st');
  App.state.tab = 'settings';
  App.onUpdate(N.state);
  let n = reads;
  N.state = Object.assign(available(), { status: 'downloading', progress: 0.42 });
  t.mock.timers.tick(1000);
  await flush();
  assert.equal(reads, n + 1);
  assert.equal(note.textContent, 'Téléchargement de la mise à jour : 42 %');
  N.state = Object.assign(available(), { status: 'ready' });
  t.mock.timers.tick(1000);
  await flush();
  assert.equal(note.textContent, 'Mise à jour prête à installer.');
  n = reads;
  t.mock.timers.tick(10000);
  await flush();
  assert.equal(reads, n);

  App.state.tab = 'qbit'; // not on screen: no polling
  App.onUpdate(Object.assign(available(), { status: 'checking' }));
  n = reads;
  t.mock.timers.tick(10000);
  await flush();
  assert.equal(reads, n);
});

test('release notes are plain text behind a « Nouveautés » fold', () => {
  const { App, S } = boot(bridge(uptodate()));
  const card = S.updateCard();
  const fold = withClass(card, 'upd-fold');
  const notes = withClass(card, 'upd-notes');
  assert.equal(isShown(fold), false);
  App.onUpdate(Object.assign(available(), { notes: '## Correctifs\n- **Mémoire** : <b>bornée</b>\n' }));
  assert.equal(isShown(fold), true);
  assert.equal(fold.textContent, 'Nouveautés de la version 2.4.0');
  assert.equal(isShown(notes), false);
  fold.click();
  assert.equal(isShown(notes), true);
  assert.equal(fold.getAttribute('aria-expanded'), 'true');
  assert.equal(notes.textContent, 'Correctifs\n• Mémoire : bornée');
  App.onUpdate(available());
  assert.equal(isShown(fold), false);
});

test('plainNotes turns the GitHub release markdown into readable text', () => {
  const { S } = boot(undefined);
  const md = [
    'Installe **Arr411.apk** par-dessus la version précédente (même clé de signature : réglages conservés).',
    '',
    '### Correctif des fermetures inattendues :',
    '',
    '- **Mémoire** : le cache des listes est borné.',
    '* **Notifications** : les torrents `zen-farm` ne réveillent plus l’app.',
    '',
    '---',
    '',
    'Nouveau, un **journal de diagnostic** (*Réglages → Diagnostic*) : voir [le guide](https://example.org/doc).',
    '',
    '',
    '',
    '> Empreinte SHA-256 de `Arr411.apk` : `5fb29e9f…`',
    '```',
    'code',
    '```'
  ].join('\r\n');
  assert.equal(
    S.plainNotes(md),
    [
      'Installe Arr411.apk par-dessus la version précédente (même clé de signature : réglages conservés).',
      '',
      'Correctif des fermetures inattendues :',
      '',
      '• Mémoire : le cache des listes est borné.',
      '• Notifications : les torrents zen-farm ne réveillent plus l’app.',
      '',
      'Nouveau, un journal de diagnostic (Réglages → Diagnostic) : voir le guide.',
      '',
      'Empreinte SHA-256 de Arr411.apk : 5fb29e9f…',
      '',
      'code'
    ].join('\n')
  );
  assert.equal(S.plainNotes(null), '');
  assert.equal(S.plainNotes('snake_case_name, __init__ et 2 * 3 * 4'), 'snake_case_name, __init__ et 2 * 3 * 4');
});

// ---------- Score des torrents ----------

function scoreBoot(config) {
  const saved = [];
  const t = boot({ saveConfig: (json) => saved.push(JSON.parse(json)) });
  t.App.config = config || {};
  const card = t.S.scoreCard();
  const inputs = findAll(card, (n) => n.localName === 'input');
  return { saved: saved, card: card, sel: select(card), inputs: inputs, weights: withClass(card, 'score-grid'), zero: withClass(card, 'warn') };
}
const type = (input, v) => change(input, v);

test('the score presets keep the ids and labels of the shared engine', () => {
  const { sel } = scoreBoot();
  assert.deepEqual(sel.options.map((o) => [o.value, o.textContent]), [
    ['equilibre', 'Équilibré (par défaut)'],
    ['qualite', 'Qualité d’image'],
    ['vf', 'VF / MULTI d’abord'],
    ['leger', 'Petite taille'],
    ['ratio', 'Ratio (seeders)'],
    ['perso', 'Personnalisé']
  ]);
  assert.equal(sel.value, 'equilibre');
  assert.equal(scoreBoot({ scorePreset: 'inconnu' }).sel.value, 'equilibre');
});

test('a preset saves {scorePreset}; « Personnalisé » shows the weights and saves them with the defaults 30/30/20/20', () => {
  const { saved, card, sel, inputs, weights } = scoreBoot();
  const hint = () => withClass(withClass(card, 'field'), 'hint').textContent;
  assert.equal(isShown(weights), false);
  assert.match(hint(), /^Privilégie l’équilibre/);
  change(sel, 'qualite');
  assert.deepEqual(saved, [{ scorePreset: 'qualite' }]);
  assert.match(hint(), /^Privilégie la qualité d’image/);
  assert.equal(isShown(weights), false);
  change(sel, 'perso');
  assert.deepEqual(saved[1], { scorePreset: 'perso', scoreWeights: { lang: 30, quality: 30, health: 20, size: 20 } });
  assert.equal(isShown(weights), true);
  assert.deepEqual(inputs.map((i) => i.value), ['30', '30', '20', '20']);
  assert.deepEqual(inputs.map((i) => i.attrs.min + '-' + i.attrs.max + '/' + i.attrs.step), ['0-100/1', '0-100/1', '0-100/1', '0-100/1']);
  change(sel, 'ratio');
  assert.deepEqual(saved[2], { scorePreset: 'ratio' });
  assert.equal(isShown(weights), false);
});

test('saved weights come back in the fields, and all-zero saved weights fall back to the defaults', () => {
  const kept = scoreBoot({ scorePreset: 'perso', scoreWeights: { lang: 10, quality: 5, health: 0, size: 85 } });
  assert.equal(isShown(kept.weights), true);
  assert.deepEqual(kept.inputs.map((i) => i.value), ['10', '5', '0', '85']);
  const zeros = scoreBoot({ scorePreset: 'perso', scoreWeights: { lang: 0, quality: 0, health: 0, size: 0 } });
  assert.deepEqual(zeros.inputs.map((i) => i.value), ['30', '30', '20', '20']);
});

test('weights are clamped to integers from 0 to 100, and an empty field keeps the saved weight', () => {
  const { saved, inputs } = scoreBoot({ scorePreset: 'perso' });
  const [lang, quality, health, size] = inputs;
  type(lang, '150');
  assert.equal(lang.value, '100');
  assert.deepEqual(saved.pop(), { scorePreset: 'perso', scoreWeights: { lang: 100, quality: 30, health: 20, size: 20 } });
  type(quality, '-5');
  assert.equal(quality.value, '0');
  assert.equal(saved.pop().scoreWeights.quality, 0);
  type(health, '12.6');
  assert.equal(health.value, '13');
  assert.equal(saved.pop().scoreWeights.health, 13);
  type(size, '');
  assert.equal(size.value, '20');
  type(size, 'abc');
  assert.equal(size.value, '20');
  assert.deepEqual(saved, []);
});

test('four weights at 0 are refused with an inline message and nothing is saved', () => {
  const { saved, inputs, zero } = scoreBoot({ scorePreset: 'perso' });
  const [lang, quality, health, size] = inputs;
  for (const i of [lang, quality, health]) type(i, '0');
  const n = saved.length;
  assert.equal(isShown(zero), false);
  type(size, '0');
  assert.equal(saved.length, n);
  assert.equal(isShown(zero), true);
  assert.match(zero.textContent, /Au moins un poids doit être supérieur à 0/);
  assert.equal(size.value, '0');
  type(size, '7');
  assert.deepEqual(saved[n], { scorePreset: 'perso', scoreWeights: { lang: 0, quality: 0, health: 0, size: 7 } });
  assert.equal(isShown(zero), false);
});

test('typing saves after a short pause, not on every keystroke', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { saved, inputs } = scoreBoot({ scorePreset: 'perso' });
  const lang = inputs[0];
  for (const v of ['4', '40']) {
    lang.value = v;
    lang.dispatchEvent({ type: 'input' });
  }
  assert.equal(saved.length, 0);
  t.mock.timers.tick(600);
  assert.equal(saved.length, 1);
  assert.equal(saved[0].scoreWeights.lang, 40);
  type(lang, '40');
  assert.equal(saved.length, 1);
});
