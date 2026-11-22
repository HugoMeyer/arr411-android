'use strict';
// Runs scripts from app\ in this realm (so checks against Object.prototype are real) on top of a browser stub without a DOM.
const fs = require('fs');
const path = require('path');

const APP_DIR = path.join(__dirname, '..', '..', 'app');

function memoryStorage() {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => {
      m.set(k, String(v));
    },
    removeItem: (k) => {
      m.delete(k);
    }
  };
}

// Returns the stub `window` once `files` (in order) ran. `opts.Native` is the bridge object, if any;
// `opts.document` replaces the DOM-less stub (see fake-dom.js); `opts.realToast` keeps App.toast instead of recording its calls.
function loadSpa(files, opts) {
  const win = { Native: opts && opts.Native, opened: [], toasts: [] };
  win.open = (...args) => {
    win.opened.push(args);
  };
  const doc = (opts && opts.document) || { getElementById: () => null };
  const storage = memoryStorage();
  for (const f of files) {
    const src = fs.readFileSync(path.join(APP_DIR, f), 'utf8') + '\n//# sourceURL=' + f;
    new Function('window', 'document', 'navigator', 'localStorage', src)(win, doc, {}, storage);
    if (f === 'core.js' && !(opts && opts.realToast)) {
      win.App.toast = (msg, type) => {
        win.toasts.push({ msg: String(msg), type: type });
      };
    }
  }
  return win;
}

module.exports = { loadSpa };
