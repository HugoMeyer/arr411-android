'use strict';
// Just enough DOM for the SPA helpers (App.h, App.btn, App.select...) to build a settings card that a test can click, type in and read back.
// The SPA tests `x instanceof Node`, so a test sets globalThis.Node = FakeNode.

class FakeNode {
  constructor() {
    this.parentNode = null;
    this.childNodes = [];
  }
  get firstChild() {
    return this.childNodes[0] || null;
  }
  get lastChild() {
    return this.childNodes[this.childNodes.length - 1] || null;
  }
  appendChild(c) {
    if (c.parentNode) c.parentNode.removeChild(c);
    c.parentNode = this;
    this.childNodes.push(c);
    return c;
  }
  removeChild(c) {
    const i = this.childNodes.indexOf(c);
    if (i < 0) throw new Error('not a child');
    this.childNodes.splice(i, 1);
    c.parentNode = null;
    return c;
  }
  replaceChild(n, o) {
    if (n.parentNode) n.parentNode.removeChild(n);
    const i = this.childNodes.indexOf(o);
    if (i < 0) throw new Error('not a child');
    this.childNodes[i] = n;
    n.parentNode = this;
    o.parentNode = null;
    return o;
  }
  // The node itself or one of its descendants.
  contains(n) {
    for (let x = n; x; x = x.parentNode) if (x === this) return true;
    return false;
  }
  remove() {
    if (this.parentNode) this.parentNode.removeChild(this);
  }
}

class FakeText extends FakeNode {
  constructor(data) {
    super();
    this.data = String(data);
  }
  get textContent() {
    return this.data;
  }
}

class FakeElement extends FakeNode {
  constructor(tag, doc) {
    super();
    this.ownerDocument = doc || null;
    this.localName = tag.toLowerCase();
    this.attrs = {};
    this.handlers = {};
    this.classes = [];
    this.checked = false;
    this.disabled = false;
    this.inert = false;
    this.id = '';
    this.style = {
      setProperty(k, v) {
        this[k] = v;
      }
    };
    const el = this;
    this.classList = {
      add(...c) {
        for (const x of c) if (!el.classes.includes(x)) el.classes.push(x);
      },
      remove(...c) {
        el.classes = el.classes.filter((x) => !c.includes(x));
      },
      contains: (c) => el.classes.includes(c),
      toggle(c, force) {
        const on = force === undefined ? !el.classes.includes(c) : !!force;
        if (on) this.add(c);
        else this.remove(c);
        return on;
      }
    };
  }
  get className() {
    return this.classes.join(' ');
  }
  set className(v) {
    this.classes = String(v).split(/\s+/).filter(Boolean);
  }
  get value() {
    return this.val === undefined ? '' : this.val;
  }
  set value(v) {
    this.val = String(v);
  }
  get children() {
    return this.childNodes.filter((c) => c.localName);
  }
  get textContent() {
    return this.childNodes.map((c) => c.textContent).join('');
  }
  set textContent(v) {
    for (const c of this.childNodes) c.parentNode = null;
    this.childNodes = [];
    if (String(v) !== '') this.appendChild(new FakeText(v));
  }
  setAttribute(k, v) {
    if (k === 'class') this.className = v;
    else if (k === 'disabled') this.disabled = true;
    else if (k === 'id') this.id = String(v);
    else this.attrs[k] = String(v);
  }
  getAttribute(k) {
    if (k === 'id') return this.id || null;
    return k === 'class' ? this.className : k in this.attrs ? this.attrs[k] : null;
  }
  hasAttribute(k) {
    return this.getAttribute(k) !== null;
  }
  removeAttribute(k) {
    if (k === 'class') this.classes = [];
    else if (k === 'disabled') this.disabled = false;
    else if (k === 'id') this.id = '';
    else delete this.attrs[k];
  }
  // As in a browser: not rendered (the `hidden` class is display:none in app.css) or detached means no boxes.
  getClientRects() {
    return isShown(this) && this.ownerDocument && this.ownerDocument.body.contains(this) ? [{}] : [];
  }
  getBoundingClientRect() {
    return {};
  }
  // Only what a browser would let take focus: connected, rendered, not inert, and a control or an element with a tabindex.
  focus() {
    const doc = this.ownerDocument;
    if (!doc || this.disabled || !doc.body.contains(this) || !isShown(this)) return;
    for (let n = this; n; n = n.parentNode) if (n.inert || (n.style && n.style.visibility === 'hidden')) return;
    const t = this.localName;
    const takes = t === 'button' || t === 'select' || t === 'textarea' || (t === 'input' && this.getAttribute('type') !== 'hidden') || (t === 'a' && this.hasAttribute('href')) || this.hasAttribute('tabindex');
    if (takes) doc.active = this;
  }
  blur() {
    if (this.ownerDocument && this.ownerDocument.active === this) this.ownerDocument.active = null;
  }
  addEventListener(type, fn) {
    (this.handlers[type] = this.handlers[type] || []).push(fn);
  }
  dispatchEvent(ev) {
    const e = Object.assign({ target: this, stopPropagation() {}, preventDefault() {} }, ev);
    for (const fn of (this.handlers[e.type] || []).slice()) fn(e);
    return true;
  }
  // A disabled button gets no click, as in a browser.
  click() {
    if (!this.disabled) this.dispatchEvent({ type: 'click' });
  }
  // `tag`, `.class` or `tag.class` only.
  querySelector(sel) {
    const m = /^([a-z][a-z0-9]*)?((?:\.[\w-]+)*)$/i.exec(sel);
    if (!m) throw new Error('unsupported selector ' + sel);
    const tag = m[1] && m[1].toLowerCase();
    const classes = m[2].split('.').filter(Boolean);
    return find(this, (n) => (!tag || n.localName === tag) && classes.every((c) => n.classList.contains(c)));
  }
}

class FakeSelect extends FakeElement {
  get options() {
    return this.childNodes.filter((c) => c.localName === 'option');
  }
  get selectedIndex() {
    const n = this.options.length;
    if (this.idx === undefined) return n ? 0 : -1;
    return this.idx < n ? this.idx : -1;
  }
  set selectedIndex(i) {
    this.idx = i;
  }
  get value() {
    const o = this.options[this.selectedIndex];
    return o ? o.value : '';
  }
  set value(v) {
    this.idx = this.options.findIndex((o) => o.value === String(v));
  }
}

function createDocument() {
  const listeners = {};
  const doc = {
    createElement: (tag) => (tag === 'select' ? new FakeSelect(tag, doc) : new FakeElement(tag, doc)),
    createElementNS: (ns, tag) => new FakeElement(tag, doc),
    createTextNode: (t) => new FakeText(t),
    getElementById: () => null,
    active: null,
    // The focused element, or the body when nothing is (or the focused element left the document).
    get activeElement() {
      return doc.active && doc.body.contains(doc.active) ? doc.active : doc.body;
    },
    addEventListener(type, fn) {
      (listeners[type] = listeners[type] || []).push(fn);
    },
    // No bubbling: a test dispatches a key at the document, where the SPA listens, with `target` set if it matters.
    dispatchEvent(ev) {
      const e = Object.assign({ target: doc.activeElement, stopPropagation() {}, preventDefault() {} }, ev);
      for (const fn of (listeners[e.type] || []).slice()) fn(e);
      return true;
    }
  };
  doc.body = doc.createElement('body');
  return doc;
}

// Elements below `root` (not `root` itself), depth first.
function findAll(root, pred) {
  const out = [];
  (function walk(n) {
    for (const c of n.childNodes) {
      if (!c.localName) continue;
      if (pred(c)) out.push(c);
      walk(c);
    }
  })(root);
  return out;
}
function find(root, pred) {
  return findAll(root, pred)[0] || null;
}
// False when the element or one of its ancestors carries the `hidden` class.
function isShown(el) {
  for (let n = el; n && n.classList; n = n.parentNode) if (n.classList.contains('hidden')) return false;
  return true;
}

module.exports = { FakeNode, createDocument, find, findAll, isShown };
