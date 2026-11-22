'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { loadSpa } = require('./load-spa');
const { FakeNode, createDocument, find, findAll } = require('./fake-dom');

globalThis.Node = FakeNode;

const APP = path.join(__dirname, '..', '..', 'app');

// A page shaped like index.html: two tabs in #tabs, dialogs in #overlays, and the live regions already there (unless `noRegions`).
// Timers are mocked: the dialogs' 260 ms and the live regions' 0 and 7000 ms steps run when the test says so.
function boot(t, o) {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const document = createDocument();
  const add = (id, parent) => {
    const el = document.createElement('div');
    el.id = id;
    (parent || document.body).appendChild(el);
    return el;
  };
  const tabs = add('tabs');
  const overlays = add('overlays');
  const toasts = add('toasts');
  const status = o && o.noRegions ? null : add('sr-status');
  const alert = o && o.noRegions ? null : add('sr-alert');
  document.getElementById = (id) => find(document.body, (n) => n.id === id);
  const win = loadSpa(['core.js'], { document: document, realToast: true });
  const App = win.App;
  App.registerTab('a', add('tab-a', tabs));
  App.registerTab('b', add('tab-b', tabs));
  App.showTab('a');
  return { App: App, win: win, h: App.h, document: document, tabs: tabs, overlays: overlays, toasts: toasts, status: status, alert: alert, tick: (ms) => t.mock.timers.tick(ms) };
}

const button = (root, text) => find(root, (n) => n.localName === 'button' && n.textContent === text);
const byLabel = (root, label) => find(root, (n) => n.getAttribute('aria-label') === label);
const lastSheet = (overlays) => find(overlays.children[overlays.children.length - 1], (n) => n.classList.contains('sheet'));
const tag = (root, name) => find(root, (n) => n.localName === name);

// A key pressed at the document, where the SPA listens; `prevented` counts the preventDefault() calls.
function key(document, k, extra) {
  const ev = Object.assign({ type: 'keydown', key: k, prevented: 0 }, extra);
  ev.preventDefault = () => {
    ev.prevented++;
  };
  document.dispatchEvent(ev);
  return ev;
}

test('a sheet is a modal dialog named by its title, or by a label when it has none', (t) => {
  const { App, overlays } = boot(t);
  const s = App.sheet({ title: 'Ajouter un torrent', body: [App.btn('Choisir')] });
  const title = tag(s.el, 'h3');
  assert.equal(s.el.getAttribute('role'), 'dialog');
  assert.equal(s.el.getAttribute('aria-modal'), 'true');
  assert.ok(title.id);
  assert.equal(s.el.getAttribute('aria-labelledby'), title.id);
  assert.equal(title.textContent, 'Ajouter un torrent');
  assert.ok(overlays.contains(s.el));

  const bare = App.sheet({ body: [App.btn('OK')] });
  assert.equal(bare.el.getAttribute('aria-labelledby'), null);
  assert.equal(bare.el.getAttribute('aria-label'), 'Fenêtre');
  assert.equal(tag(bare.el, 'h3').getAttribute('aria-hidden'), 'true');
  bare.setTitle('Détails');
  assert.equal(bare.el.getAttribute('aria-label'), null);
  assert.equal(bare.el.getAttribute('aria-labelledby'), tag(bare.el, 'h3').id);

  assert.equal(App.sheet({ ariaLabel: 'Aperçu' }).el.getAttribute('aria-label'), 'Aperçu');
});

test('focus starts on the first control of the dialog', (t) => {
  const { App, h, document, overlays, tick } = boot(t);
  const next = (s) => {
    s.close();
    tick(300);
  };
  next(App.sheet({ title: 'T', body: [h('p', 'texte'), App.btn('Premier'), App.btn('Second')], actions: [App.btn('Valider')] }));
  const body = App.sheet({ title: 'T', body: [h('p', 'texte'), App.btn('Premier')], actions: [App.btn('Valider')] });
  assert.equal(document.activeElement, button(body.el, 'Premier'));
  next(body);

  const actions = App.sheet({ title: 'T', body: [h('p', 'texte')], actions: [App.btn('Valider')] });
  assert.equal(document.activeElement, button(actions.el, 'Valider'));
  next(actions);

  const bare = App.sheet({ title: 'T', body: [h('p', 'texte')] });
  assert.equal(document.activeElement, byLabel(bare.el, 'Fermer'));
  next(bare);

  const pinned = App.sheet({ title: 'T', body: [App.btn('Premier')] });
  next(pinned);
  const chosen = App.btn('Choisi');
  const given = App.sheet({ title: 'T', body: [App.btn('Premier'), chosen], initialFocus: chosen });
  assert.equal(document.activeElement, chosen);
  next(given);
  assert.equal(overlays.children.length, 0);
});

test('a destructive confirm starts on « Annuler », an ordinary one on its first control', async (t) => {
  const { App, document, overlays, tick } = boot(t);
  const checks = [{ key: 'files', label: 'Supprimer les fichiers' }];

  const danger = App.confirm({ title: 'Supprimer ?', danger: true, checks: checks });
  assert.equal(document.activeElement, button(lastSheet(overlays), 'Annuler'));
  button(lastSheet(overlays), 'Annuler').click();
  assert.equal(await danger, false);
  tick(300);

  const plain = App.confirm({ title: 'Continuer ?', checks: checks });
  assert.equal(document.activeElement.localName, 'input');
  button(lastSheet(overlays), 'Confirmer').click();
  assert.deepEqual(await plain, { files: false });
});

test('a prompt names its field and is announced before the field takes focus', (t) => {
  const { App, document, overlays, tick } = boot(t);
  App.prompt({ title: 'Renommer', label: 'Nouveau nom', value: 'Dune' });
  const dlg = lastSheet(overlays);
  const input = tag(dlg, 'input');
  assert.equal(tag(dlg, 'label').getAttribute('for'), input.id);
  assert.equal(document.activeElement, dlg);
  tick(260);
  assert.equal(document.activeElement, input);
  button(dlg, 'Annuler').click();
  tick(300);

  App.prompt({ title: 'Catégorie', placeholder: 'Nom de la catégorie' });
  assert.equal(tag(lastSheet(overlays), 'input').getAttribute('aria-label'), 'Nom de la catégorie');
});

test('Escape closes the top dialog of the visible tab only, and not one that opted out', (t) => {
  const { App, document } = boot(t);
  const open = (title, o) => App.sheet(Object.assign({ title: title, body: [App.btn('x')] }, o));

  const a = open('A');
  const b = open('B');
  assert.equal(key(document, 'Escape').prevented, 1);
  assert.ok(b.closed && !a.closed);
  key(document, 'Escape');
  assert.ok(a.closed);
  assert.equal(key(document, 'Escape').prevented, 0, 'nothing left to close: the key is not swallowed');

  // A dialog of another tab is not reachable from this one.
  const c = open('C');
  App.showTab('b');
  key(document, 'Escape');
  assert.ok(!c.closed);
  App.showTab('a');
  key(document, 'Escape');
  assert.ok(c.closed);

  // dismiss:false: neither Escape nor the backdrop closes it, but the code still can.
  const d = open('D', { dismiss: false });
  key(document, 'Escape');
  d.ov.children[0].click();
  assert.ok(!d.closed);
  d.close();
  assert.ok(d.closed);

  // A key already handled (a menu inside the dialog, say) is left alone, and the backdrop closes an ordinary dialog.
  const e = open('E');
  key(document, 'Escape', { defaultPrevented: true });
  assert.ok(!e.closed);
  e.ov.children[0].click();
  assert.ok(e.closed);
});

test('Tab stays inside the dialog', (t) => {
  const { App, document } = boot(t);
  const s = App.sheet({ title: 'T', body: [App.btn('Un'), App.btn('Deux')], actions: [App.btn('OK')] });
  const first = byLabel(s.el, 'Fermer');
  const last = button(s.el, 'OK');

  last.focus();
  assert.equal(key(document, 'Tab').prevented, 1);
  assert.equal(document.activeElement, first);
  assert.equal(key(document, 'Tab', { shiftKey: true }).prevented, 1);
  assert.equal(document.activeElement, last);

  button(s.el, 'Un').focus();
  assert.equal(key(document, 'Tab').prevented, 0, 'inside the dialog the browser moves focus itself');
  assert.equal(key(document, 'Tab', { shiftKey: true }).prevented, 0);
});

test('closing a dialog returns focus to its opener, or to the page title when the opener is gone', (t) => {
  const { App, h, document } = boot(t);
  const page = App.page({ title: 'Films' });
  const opener = h('button', { type: 'button' }, 'Ouvrir');
  page.el.appendChild(opener);
  App.push('a', page);

  opener.focus();
  const s = App.sheet({ title: 'T', body: [App.btn('x')] });
  assert.notEqual(document.activeElement, opener);
  s.close();
  assert.equal(document.activeElement, opener);

  const s2 = App.sheet({ title: 'T', body: [App.btn('x')] });
  opener.remove();
  s2.close();
  assert.equal(document.activeElement, page.titleEl);
});

test('closeOverlays returns focus to where the first dialog came from', (t) => {
  const { App, h, document, tabs } = boot(t);
  const page = App.page({ title: 'Films' });
  const opener = h('button', { type: 'button' }, 'Ouvrir');
  page.el.appendChild(opener);
  App.push('a', page);
  opener.focus();

  const a = App.sheet({ title: 'A', body: [App.btn('Suite')] });
  assert.equal(document.activeElement, button(a.el, 'Suite'));
  App.sheet({ title: 'B', body: [App.btn('Fin')] });
  App.closeOverlays();
  assert.equal(document.activeElement, opener);
  assert.ok(!tabs.inert);
});

test('the page behind a dialog is inert only while a dialog of the visible tab is open', (t) => {
  const { App, document, tabs } = boot(t);
  const open = () => App.sheet({ title: 'T', body: [App.btn('x')] });

  // Closed one by one: the page opens up again with the last one, and each dialog in turn becomes reachable.
  const a = open();
  const b = open();
  assert.equal(tabs.inert, true);
  assert.deepEqual([a.ov.inert, b.ov.inert], [true, false]);
  b.close();
  assert.equal(tabs.inert, true);
  assert.equal(a.ov.inert, false);
  a.close();
  assert.equal(tabs.inert, false);

  // closeOverlays() and Back leave nothing behind.
  open();
  open();
  App.closeOverlays();
  assert.equal(tabs.inert, false);
  open();
  assert.equal(App.back(), true);
  assert.equal(tabs.inert, false);

  // On a tab without a dialog the page is reachable, and it is inert again when the dialog's own tab comes back.
  const c = open();
  App.showTab('b');
  assert.equal(tabs.inert, false);
  assert.equal(c.ov.style.visibility, 'hidden');
  const d = open();
  assert.equal(tabs.inert, true);
  d.close();
  assert.equal(tabs.inert, false);
  document.activeElement.blur(); // a browser drops focus from what is hidden
  App.showTab('a');
  assert.equal(tabs.inert, true);
  assert.equal(c.ov.inert, false);
  assert.ok(c.ov.contains(document.activeElement));
  c.close();
  assert.equal(tabs.inert, false);
});

test('toasts are read once from the live regions and are hidden from screen readers themselves', (t) => {
  const { App, status, alert, toasts, tick } = boot(t);
  App.toast('Torrent ajouté', 'ok');
  assert.equal(status.textContent, '');
  tick(10);
  assert.equal(status.textContent, 'Torrent ajouté');
  assert.equal(alert.textContent, '');
  assert.equal(toasts.children[0].getAttribute('aria-hidden'), 'true');
  assert.match(toasts.children[0].textContent, /Torrent ajouté/);
  tick(7000);
  assert.equal(status.textContent, '', 'the region is emptied again once read');

  // The region is emptied first, so the same text sounds again; two toasts in a row are both read.
  App.toast('Copié', 'ok');
  tick(10);
  App.toast('Copié', 'ok');
  assert.equal(status.textContent, '');
  tick(10);
  assert.equal(status.textContent, 'Copié');
  tick(7000);
  App.toast('Un');
  App.toast('Deux');
  tick(10);
  assert.equal(status.textContent, 'Un. Deux');

  App.toast('Connexion refusée', 'err');
  tick(10);
  assert.equal(alert.textContent, 'Connexion refusée');
  assert.equal(status.textContent, 'Un. Deux');
});

test('a toast creates its live region when the page has none', (t) => {
  const { App, document, tick } = boot(t, { noRegions: true });
  App.toast('Oups', 'err');
  tick(10);
  const alert = find(document.body, (n) => n.id === 'sr-alert');
  assert.equal(alert.textContent, 'Oups');
  assert.equal(alert.getAttribute('role'), 'alert');
  assert.equal(alert.getAttribute('aria-live'), 'assertive');
});

test('search results are announced politely and errors assertively, without a toast', (t) => {
  const { App, status, alert, toasts, tick } = boot(t);
  const said = (r, region) => {
    App.sayResults(r);
    tick(10);
    const text = region.textContent;
    tick(7000);
    return text;
  };
  assert.equal(said(3, status), '3 résultats');
  assert.equal(said(1, status), '1 résultat');
  assert.equal(said([1, 2], status), '2 résultats');
  assert.equal(said(0, status), 'Aucun résultat');
  assert.equal(said(null, status), 'Aucun résultat');
  assert.equal(said(new Error('Radarr injoignable'), alert), 'Radarr injoignable');
  assert.equal(toasts.children.length, 0);
});

test('a field ties its label and hint to the control it holds', (t) => {
  const { App, h } = boot(t);
  const input = App.input({});
  const f = App.field('Adresse', input, 'Sans barre finale');
  const hint = find(f, (n) => n.classList.contains('hint'));
  assert.ok(input.id);
  assert.equal(tag(f, 'label').getAttribute('for'), input.id);
  assert.equal(input.getAttribute('aria-describedby'), hint.id);
  assert.equal(hint.textContent, 'Sans barre finale');

  // A wrapper: the label goes to the field in it, not to the button next to it.
  const eye = h('button', { type: 'button' }, 'Afficher');
  const pw = App.input({ type: 'password' });
  const g = App.field('Mot de passe', h('div.secret', pw, eye));
  assert.equal(tag(g, 'label').getAttribute('for'), pw.id);
  assert.equal(eye.id, '');
  assert.equal(g.getAttribute('role'), null);

  // No single control, as for a row of chips: the field is a named group.
  const chips = App.field('Étiquettes', h('div.chips', h('button', 'a'), h('button', 'b')));
  const lab = tag(chips, 'label');
  assert.ok(lab.id);
  assert.equal(chips.getAttribute('role'), 'group');
  assert.equal(chips.getAttribute('aria-labelledby'), lab.id);

  // A field built by hand is tied the same way and keeps the description it already had.
  const sel = App.select([{ value: 'a', label: 'A' }], 'a', { 'aria-describedby': 'ailleurs' });
  const lab2 = h('label', 'Profil');
  const hint2 = h('div.hint', 'Aide');
  App.tie(lab2, sel, hint2);
  assert.equal(lab2.getAttribute('for'), sel.id);
  assert.equal(sel.getAttribute('aria-describedby'), 'ailleurs ' + hint2.id);
});

test('segments and chips keep aria-pressed in step with their look', (t) => {
  const { App, h } = boot(t);
  const seen = [];
  const seg = App.seg(
    [
      { label: 'Tout', value: 'all' },
      { label: 'Actifs', value: 'act' }
    ],
    'all',
    (v) => seen.push(v)
  );
  const [a, b] = seg.children;
  const state = () => [a, b].map((x) => x.getAttribute('aria-pressed') + '/' + x.classList.contains('on'));
  assert.deepEqual(state(), ['true/true', 'false/false']);
  b.click();
  assert.deepEqual(state(), ['false/false', 'true/true']);
  seg.set('all');
  assert.deepEqual(state(), ['true/true', 'false/false']);
  a.click();
  assert.deepEqual(seen, ['act'], 'choosing the current segment again changes nothing');

  const chip = h('button.chip', 'zen-farm');
  App.setOn(chip, true);
  assert.deepEqual([chip.getAttribute('aria-pressed'), chip.classList.contains('on')], ['true', true]);
  App.setOn(chip, false);
  assert.deepEqual([chip.getAttribute('aria-pressed'), chip.classList.contains('on')], ['false', false]);
});

test('a menu marks the current choice and starts on it', (t) => {
  const { App, document } = boot(t);
  const m = App.menu({ title: 'Trier', items: [{ label: 'Nom' }, { label: 'Taille', on: true }, { label: 'Date' }] });
  const items = findAll(m.el, (n) => n.classList.contains('menu-item'));
  assert.deepEqual(
    items.map((x) => x.getAttribute('aria-current')),
    [null, 'true', null]
  );
  assert.equal(document.activeElement, items[1]);
});

test('pressable makes an element act as a button, and leaves the keys typed in a control inside it alone', (t) => {
  const { App, h } = boot(t);
  let n = 0;
  const field = h('input', { type: 'text' });
  const row = h('div.row', 'Dune', field);
  App.pressable(row, () => n++, { label: 'Ouvrir Dune', expanded: false, pressed: true });
  assert.equal(row.getAttribute('role'), 'button');
  assert.equal(row.getAttribute('tabindex'), '0');
  assert.equal(row.getAttribute('aria-label'), 'Ouvrir Dune');
  assert.equal(row.getAttribute('aria-expanded'), 'false');
  assert.equal(row.getAttribute('aria-pressed'), 'true');

  const press = (type, k, extra) => {
    const ev = Object.assign({ type: type, key: k, target: row, prevented: 0 }, extra);
    ev.preventDefault = () => {
      ev.prevented++;
    };
    row.dispatchEvent(ev);
    return ev;
  };
  press('keydown', 'Enter');
  assert.equal(n, 1);
  press('keyup', 'Enter');
  press('keydown', 'Enter', { repeat: true });
  assert.equal(n, 1, 'Enter acts once on keydown, and not while held');

  assert.equal(press('keydown', ' ').prevented, 1, 'Space must not scroll the page');
  assert.equal(n, 1);
  assert.equal(press('keyup', ' ').prevented, 1);
  assert.equal(n, 2, 'Space acts on keyup');

  assert.equal(press('keydown', 'Enter', { ctrlKey: true }).prevented, 0);
  assert.equal(press('keydown', 'a').prevented, 0);
  assert.equal(press('keydown', ' ', { target: field }).prevented, 0, 'a space typed in the nested field stays a space');
  press('keydown', 'Enter', { target: field });
  assert.equal(n, 2);
});

test('pressableIn serves the rows of a list from one listener, and not the buttons inside them', (t) => {
  const { App, h } = boot(t);
  let n = 0;
  const inner = h('button', { type: 'button' }, 'Plus');
  const row = h('div.row', 'Dune', inner);
  row.setAttribute('role', 'button');
  row.setAttribute('tabindex', '0');
  row.addEventListener('click', () => n++);
  const list = App.pressableIn(h('div.list', row));
  const dispatch = (type, k, target) => list.dispatchEvent({ type: type, key: k, target: target });
  dispatch('keydown', 'Enter', row);
  assert.equal(n, 1);
  dispatch('keyup', ' ', row);
  assert.equal(n, 2);
  dispatch('keydown', 'Enter', inner);
  dispatch('keydown', 'Enter', list);
  assert.equal(n, 2, 'a real button handles its own keys; the list itself is not a row');
});

test('a labelled bar is a progressbar that follows its value, an unlabelled one is hidden', (t) => {
  const { App } = boot(t);
  const bar = App.bar(0.424, 'var(--pri)', 'Espace utilisé');
  assert.equal(bar.getAttribute('role'), 'progressbar');
  assert.equal(bar.getAttribute('aria-label'), 'Espace utilisé');
  assert.deepEqual(['aria-valuemin', 'aria-valuemax', 'aria-valuenow'].map((a) => bar.getAttribute(a)), ['0', '100', '42']);
  bar.set(0.8);
  assert.equal(bar.getAttribute('aria-valuenow'), '80');
  bar.set(3);
  assert.equal(bar.getAttribute('aria-valuenow'), '100');

  const plain = App.bar(0.5);
  assert.equal(plain.getAttribute('aria-hidden'), 'true');
  assert.equal(plain.getAttribute('role'), null);
  plain.set(0.9);
  assert.equal(plain.getAttribute('aria-valuenow'), null);
});

test('controls without visible text get a name', (t) => {
  const { App } = boot(t);
  assert.equal(App.btn('', { icon: 'plus', title: 'Ajouter' }).getAttribute('aria-label'), 'Ajouter');
  assert.equal(App.btn('Ajouter', { title: 'Ajouter un torrent' }).getAttribute('aria-label'), null);
  assert.equal(App.iconBtn('x-mark', 'Fermer').getAttribute('aria-label'), 'Fermer');
  assert.equal(App.input({ ariaLabel: 'Rechercher' }).getAttribute('aria-label'), 'Rechercher');

  const sw = App.switchEl('Notifications', true, null, 'Une par torrent terminé');
  const input = tag(sw, 'input');
  const byId = (id) => find(sw, (n) => n.id === id);
  assert.equal(input.getAttribute('role'), 'switch');
  assert.equal(byId(input.getAttribute('aria-labelledby')).textContent, 'Notifications');
  assert.equal(byId(input.getAttribute('aria-describedby')).textContent, 'Une par torrent terminé');

  assert.equal(tag(App.secTitle('Fichiers'), 'span').getAttribute('aria-level'), '2');
});

test('a page title is a focusable level-1 heading, and focus follows pages in and out', (t) => {
  const { App, h, document } = boot(t);
  const root = App.page({ title: 'Films' });
  const open = h('button', { type: 'button' }, 'Dune');
  root.el.appendChild(open);
  App.push('a', root);
  assert.deepEqual(['role', 'aria-level', 'tabindex'].map((a) => root.titleEl.getAttribute(a)), ['heading', '1', '-1']);

  open.focus();
  const details = App.page({ title: '', back: true });
  App.push('a', details);
  assert.equal(document.activeElement, details.titleEl, 'a page is announced by its title when it opens');
  details.setName('Dune');
  assert.equal(details.titleEl.getAttribute('aria-label'), 'Dune');
  details.setName('');
  assert.equal(details.titleEl.getAttribute('aria-label'), null);

  assert.equal(App.pop('a'), true);
  assert.equal(document.activeElement, open, 'going back lands on the control that led there');
});

test('a list that is rebuilt or reordered hands focus back to the same row', (t) => {
  const { App, h, document } = boot(t);
  const rows = () => ['a', 'b', 'c'].map((x) => h('button', { type: 'button' }, x));
  const list = h('div.list');
  document.body.appendChild(list);
  App.fill(list, rows());

  // A rebuilt list: whatever now sits at the same position. A browser drops focus together with the element, hence the blur().
  list.children[1].focus();
  const place = App.keepPlace(list);
  list.children[1].blur();
  App.fill(list, rows());
  place();
  assert.equal(document.activeElement.textContent, 'b');

  // A reordered list: the very same element.
  const moving = list.children[2];
  moving.focus();
  const hold = App.holdFocus(list);
  const kids = list.children.reverse();
  kids.forEach((k) => k.remove());
  moving.blur();
  kids.forEach((k) => list.appendChild(k));
  hold();
  assert.equal(document.activeElement, moving);
});

test('scrolling by code jumps, instead of gliding, when the system asks for less motion', (t) => {
  const { App, win } = boot(t);
  assert.equal(App.scrollBehavior(), 'smooth', 'a browser without matchMedia keeps the glide');
  win.matchMedia = (q) => ({ matches: q === '(prefers-reduced-motion: reduce)' });
  assert.equal(App.scrollBehavior(), 'auto');
  win.matchMedia = () => ({ matches: false });
  assert.equal(App.scrollBehavior(), 'smooth');
});

test('the page ships its live regions, and the dim text and control rims keep their contrast', () => {
  const html = fs.readFileSync(path.join(APP, 'index.html'), 'utf8');
  assert.match(html, /<div id="sr-status"[^>]*role="status"/);
  assert.match(html, /<div id="sr-alert"[^>]*role="alert"/);

  const css = fs.readFileSync(path.join(APP, 'app.css'), 'utf8');
  const root = css.slice(css.indexOf(':root'), css.indexOf('}'));
  const c = {};
  root.replace(/--([\w-]+):\s*#([0-9a-f]{6})/gi, (m, k, v) => {
    c[k] = v;
    return m;
  });
  const lum = (hex) => {
    const [r, g, b] = [0, 2, 4].map((i) => {
      const v = parseInt(hex.slice(i, i + 2), 16) / 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const ratio = (a, b) => {
    const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
  };
  for (const bg of ['bg', 'bg2', 'card', 'card2']) {
    for (const fg of ['text', 'muted', 'dim']) assert.ok(ratio(c[fg], c[bg]) >= 4.5, fg + ' on ' + bg + ' is ' + ratio(c[fg], c[bg]).toFixed(2));
  }
  for (const bg of ['bg', 'bg2', 'card']) assert.ok(ratio(c.ctl, c[bg]) >= 3, 'control rim on ' + bg + ' is ' + ratio(c.ctl, c[bg]).toFixed(2));
});
