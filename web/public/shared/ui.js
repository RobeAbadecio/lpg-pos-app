// Small DOM + formatting helpers shared by the POS app and the admin dashboard.
// All user data goes through text nodes (never innerHTML), so names from the CSVs can't inject markup.
(function () {
  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else el.setAttribute(k, v === true ? '' : v);
    }
    append(el, children);
    return el;
  }

  function append(el, children) {
    for (const c of children.flat(Infinity)) {
      if (c == null || c === false) continue;
      el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
  }

  function clear(el, ...children) {
    el.replaceChildren();
    append(el, children);
    return el;
  }

  const peso = new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' });
  const pesoRound = new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP', maximumFractionDigits: 0 });
  const count = new Intl.NumberFormat('en-PH');

  function compactPeso(v) {
    const a = Math.abs(v);
    if (a >= 1e6) return '₱' + (v / 1e6).toFixed(a >= 1e7 ? 0 : 1).replace(/\.0$/, '') + 'M';
    if (a >= 1e4) return '₱' + (v / 1e3).toFixed(a >= 1e5 ? 0 : 1).replace(/\.0$/, '') + 'K';
    return pesoRound.format(v);
  }

  function parseTime(s) {
    // "yyyy-MM-dd HH:mm:ss" in server-local time
    const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(s || '');
    return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]) : null;
  }

  function fmtTime(s, opts) {
    const d = parseTime(s);
    if (!d) return s || '—';
    const today = new Date();
    const sameDay = d.toDateString() === today.toDateString();
    const time = d.toLocaleTimeString('en-PH', { hour: 'numeric', minute: '2-digit' });
    if (sameDay && !(opts && opts.full)) return 'Today, ' + time;
    return d.toLocaleDateString('en-PH', { month: 'short', day: 'numeric', year: d.getFullYear() === today.getFullYear() ? undefined : 'numeric' }) + ', ' + time;
  }

  function ago(s) {
    const d = parseTime(s);
    if (!d) return '—';
    const sec = Math.max(0, Math.round((Date.now() - d.getTime()) / 1000));
    if (sec < 45) return 'just now';
    if (sec < 3600) return Math.round(sec / 60) + ' min ago';
    if (sec < 86400) return Math.round(sec / 3600) + ' h ago';
    return Math.round(sec / 86400) + ' d ago';
  }

  let toastTimer;
  function toast(message, isError) {
    let el = document.getElementById('toast');
    if (!el) {
      el = h('div', { id: 'toast', class: 'toast', role: 'status', 'aria-live': 'polite' });
      document.body.append(el);
    }
    el.textContent = message;
    el.classList.toggle('error', !!isError);
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), isError ? 5000 : 3000);
  }

  async function request(method, path, data) {
    const opts = { method, headers: { 'X-LPG': '1' }, credentials: 'same-origin' };
    if (data) opts.body = new URLSearchParams(data);
    let res;
    try {
      res = await fetch(path, opts);
    } catch (_) { // no connection: say so in plain words instead of the browser's "Failed to fetch"
      throw new Error('Can’t reach the POS server. Check the internet connection and try again.');
    }
    let body = null;
    try { body = await res.json(); } catch (_) { /* empty body */ }
    if (!res.ok) {
      const err = new Error((body && body.error) || `Request failed (${res.status})`);
      err.status = res.status;
      throw err;
    }
    return body;
  }

  // Generic form dialog. fields: [{name, label, type, value, required, placeholder, step, min, max, full, help}]
  function formDialog({ title, message, fields = [], submit = 'Save', danger = false, extra, onSubmit }) {
    return new Promise((resolve) => {
      const dlg = h('dialog', { class: 'modal' });
      const err = h('p', { class: 'error-text', role: 'alert', hidden: true });
      const inputs = {};
      const rows = [];
      let pair = [];
      const flush = () => { if (pair.length) { rows.push(pair.length === 2 ? h('div', { class: 'grid-2' }, pair) : pair[0]); pair = []; } };
      for (const f of fields) {
        const input = f.type === 'select'
          ? h('select', { class: 'input', name: f.name, required: f.required },
              f.options.map((o) => h('option', { value: o.value }, o.label)))
          : h('input', {
              class: 'input', name: f.name, type: f.type || 'text', required: f.required,
              placeholder: f.placeholder, step: f.step, min: f.min, max: f.max,
              autocomplete: f.autocomplete || 'off', inputmode: f.inputmode,
            });
        if (f.value != null) input.value = f.value;
        inputs[f.name] = input;
        const label = h('label', { class: 'field' }, h('span', {}, f.label), input, f.help ? h('small', { class: 'muted' }, f.help) : null);
        if (f.full) { flush(); rows.push(label); } else { pair.push(label); if (pair.length === 2) flush(); }
      }
      flush();
      const cancel = h('button', { type: 'button', class: 'btn ghost', onclick: () => dlg.close() }, 'Cancel');
      const ok = h('button', { type: 'submit', class: danger ? 'btn destructive' : 'btn primary' }, submit);
      const form = h('form', { method: 'dialog' },
        h('h3', {}, title),
        message ? h('p', { class: 'msg' }, message) : null,
        rows,
        extra ? extra(inputs) : null,
        err,
        h('div', { class: 'row-end' }, cancel, ok));
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const values = {};
        for (const [k, el] of Object.entries(inputs)) values[k] = el.value.trim();
        ok.disabled = true;
        err.hidden = true;
        try {
          const result = onSubmit ? await onSubmit(values) : values;
          resolve(result === undefined ? values : result);
          dlg.close();
        } catch (ex) {
          err.textContent = ex.message;
          err.hidden = false;
        } finally {
          ok.disabled = false;
        }
      });
      dlg.addEventListener('close', () => { dlg.remove(); resolve(null); });
      dlg.append(form);
      document.body.append(dlg);
      dlg.showModal();
      const first = Object.values(inputs)[0];
      if (first) first.focus();
    });
  }

  // Dialog with any content. body/actions may be nodes or (close) => nodes. onSubmit(form) may throw to show an error.
  function openDialog({ title, message, body, actions, submit = 'Save', cancel = 'Cancel', danger = false, wide = false, onSubmit }) {
    return new Promise((resolve) => {
      const dlg = h('dialog', { class: 'modal' + (wide ? ' wide' : '') });
      const close = (value) => { resolve(value === undefined ? null : value); dlg.close(); };
      const err = h('p', { class: 'error-text', role: 'alert', hidden: true });
      const ok = submit ? h('button', { type: 'submit', class: danger ? 'btn destructive' : 'btn primary' }, submit) : null;
      const form = h('form', { method: 'dialog', novalidate: true },
        h('h3', {}, title),
        message ? h('p', { class: 'msg' }, message) : null,
        typeof body === 'function' ? body(close) : body,
        err,
        h('div', { class: 'row-end' },
          typeof actions === 'function' ? actions(close) : actions,
          cancel ? h('button', { type: 'button', class: 'btn ghost', onclick: () => close(null) }, cancel) : null,
          ok));
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (!onSubmit) return close(true);
        ok.disabled = true;
        err.hidden = true;
        try {
          const result = await onSubmit(form);
          close(result === undefined ? true : result);
        } catch (ex) {
          err.textContent = ex.message;
          err.hidden = false;
        } finally {
          ok.disabled = false;
        }
      });
      dlg.addEventListener('close', () => { dlg.remove(); resolve(null); });
      dlg.append(form);
      document.body.append(dlg);
      dlg.showModal();
      const first = form.querySelector('input:not([type=hidden]), select, textarea');
      if (first) first.focus();
    });
  }

  function confirmDialog(title, message, submit = 'Delete') {
    return formDialog({ title, message, submit, danger: true, onSubmit: () => true });
  }

  // Pages stay open for days (staff phones, the admin tab). When the server has a newer version
  // than the one running here, offer a reload. Never automatic: a sale might be half typed, so
  // "Later" puts it off for half an hour.
  function watchVersion() {
    let bar = null, snoozeUntil = 0;
    const check = async () => {
      if (bar || Date.now() < snoozeUntil || document.visibilityState !== 'visible' || !window.LPG_VERSION) return;
      try {
        const text = await fetch('/shared/version.js', { cache: 'no-store' }).then((r) => r.text());
        const m = text.match(/LPG_VERSION = '([^']+)'/);
        if (bar || !m || m[1] === window.LPG_VERSION) return;
        bar = h('div', { class: 'update-bar', role: 'status' },
          h('span', {}, `LPG POS ${m[1]} is ready. Reload to start using it.`),
          h('div', { class: 'update-actions' },
            h('button', { type: 'button', class: 'btn small ghost', onclick: () => { bar.remove(); bar = null; snoozeUntil = Date.now() + 30 * 60 * 1000; } }, 'Later'),
            h('button', { type: 'button', class: 'btn small primary', onclick: () => location.reload() }, 'Reload')));
        document.body.append(bar);
      } catch (_) { /* offline: try again later */ }
    };
    setInterval(check, 60000);
    document.addEventListener('visibilitychange', check);
  }

  window.UI = { h, clear, append, peso, pesoRound, count, compactPeso, parseTime, fmtTime, ago, toast, request, formDialog, confirmDialog, openDialog, watchVersion };
})();
