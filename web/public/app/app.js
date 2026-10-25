// LPG POS — staff web app.
(function () {
  const { h, clear, peso, count, toast, request, formDialog, confirmDialog, openDialog, fmtTime } = UI;
  const $ = (id) => document.getElementById(id);

  const EMPTY_DATA = { customers: [], products: [], receipts: [], movements: [], suppliers: [], refills: [], brands: [], returns: [], deposits: [], undoMinutes: 30, me: null,
    staff: [], staffMoney: { total: 0, owed: [], recent: [] } };
  // own: tanks the receipt being edited already holds, so they count as available again.
  // laterPaid: payments recorded after the sale on the receipt being edited.
  // lastCustomerId: who the items were added for, so switching customers starts a fresh receipt.
  // date: the day the receipt is for ('' = today); when editing, the receipt's own day, which can be changed.
  const newSale = () => ({ customerId: null, lastCustomerId: null, lines: [], discount: '', pay: 'full', method: 'cash', paidNow: '', cash: '', note: '', date: '', editing: null, own: {}, laterPaid: 0, fromKept: false });
  const state = { me: null, data: EMPTY_DATA, etag: null, view: 'sell', sale: newSale(), historyFilter: 'all', customerFilter: 'all', sizeFilter: '', invTab: 'stock', dashMode: 'daily' };
  try {
    const m = localStorage.getItem('lpg-dash-mode');
    // "trends" (before 3.4) and "customers" (before 3.6) are now Sales.
    state.dashMode = ['trends', 'customers'].includes(m) ? 'sales' : ['sales', 'suppliers', 'money', 'salaries', 'start'].includes(m) ? m : 'daily';
    const t = localStorage.getItem('lpg-inv-tab');
    if (['stock', 'out', 'refiller', 'suppliers', 'staff', 'history'].includes(t)) state.invTab = t;
  } catch (_) { /* storage blocked */ }
  let lineKey = 0;

  // ------------------------------------------------------------------ API

  async function api(method, path, data) {
    try {
      return await request(method, path, data);
    } catch (e) {
      if (e.status === 401 && path !== '/api/login') showLogin('Your session ended. Please sign in again.');
      throw e;
    }
  }

  // Polls with If-None-Match: when nothing changed the server answers 304 and nothing is downloaded.
  async function refresh() {
    const headers = { 'X-LPG': '1' };
    if (state.etag) headers['If-None-Match'] = state.etag;
    let res;
    try {
      res = await fetch('/api/data', { headers, cache: 'no-store', credentials: 'same-origin' });
    } catch (_) {
      setOnline(false);
      throw new Error('Can’t reach the POS server. Check the internet connection and try again.');
    }
    setOnline(true);
    if (res.status === 304) return;
    if (res.status === 401) {
      showLogin('Your session ended. Please sign in again.');
      throw new Error('Please sign in');
    }
    if (!res.ok) throw new Error(`Couldn’t load data (${res.status})`);
    state.data = await res.json();
    state.etag = res.headers.get('ETag');
    if (state.data.me && state.me) state.me.dashboard = !!state.data.me.dashboard; // the admin can change it any time
    updateBadges();
    updateDashboardAccess();
    if (state.view === 'dashboard' && !state.me.dashboard) return go('sell');
    render(true);
  }

  // A saved change says so, with an Undo button when the server can take it back out.
  async function mutate(method, path, data, message) {
    const result = await api(method, path, data);
    await refresh();
    if (message) {
      const undo = result && !(data && data.replaceKind) && result.undo;
      toast(typeof message === 'function' ? message(result) : message, false, undo ? { label: 'Undo', onClick: () => undoNow(undo) } : null);
    }
    return result;
  }

  // ------------------------------------------------------------------ undo

  // Staff can undo what they entered themselves in the last half hour; staff with dashboard access, anything
  // (the server checks the same). Undoing takes the record out as if it was never entered.
  function canUndo(staff, time) {
    if (!state.me) return false;
    if (state.me.dashboard) return true;
    const t = UI.parseTime(time);
    return staff === state.me.username && !!t && Date.now() - t.getTime() < (state.data.undoMinutes || 30) * 60000;
  }

  async function undoNow(u) {
    try {
      const r = await api('POST', '/api/undo', u);
      await refresh();
      toast(`Undone: ${r.what}`);
      return true;
    } catch (e) {
      toast(e.message, true);
      return false;
    }
  }

  // what: names the record in the question ("Refill trip #4 to Petron depot").
  async function undoRecord(kind, id, what, more) {
    const ok = await confirmDialog(`Undo ${what}?`,
      `It’s taken out as if it was never entered${more ? ', ' + more : ''}. Stock and balances are worked out again, and the activity log keeps a note of it.`, 'Undo');
    return ok ? undoNow({ kind, id }) : false;
  }

  const undoButton = (kind, id, what, staff, time, more) => canUndo(staff, time)
    ? h('button', { type: 'button', class: 'btn small ghost danger', onclick: (e) => {
      e.stopPropagation();
      e.currentTarget.closest('dialog')?.close(); // the dialog it sits in would be out of date afterwards
      undoRecord(kind, id, what, more);
    } }, 'Undo') : null;

  // Edit: opens the record's own form filled in; saving replaces the record (same day) in one step.
  const editButton = (kind, id, staff, time) => canUndo(staff, time)
    ? h('button', { type: 'button', class: 'btn small ghost', onclick: (e) => {
      e.stopPropagation();
      e.currentTarget.closest('dialog')?.close();
      openEdit(kind, id);
    } }, 'Edit') : null;
  // Edit and Undo side by side.
  const changeButtons = (kind, id, what, staff, time, more) => [editButton(kind, id, staff, time), undoButton(kind, id, what, staff, time, more)];
  // Marks a form's values as replacing the record being edited.
  const asEdit = (v, edit) => (edit ? Object.assign(v, { replaceKind: edit.kind, replaceId: edit.id }) : v);
  const editTitle = (edit, title) => (edit ? `Correct: ${title.charAt(0).toLowerCase()}${title.slice(1)}` : title);

  async function openEdit(kind, id) {
    let e;
    try {
      e = await api('GET', `/api/record?kind=${encodeURIComponent(kind)}&id=${encodeURIComponent(id)}`);
    } catch (ex) {
      return toast(ex.message, true);
    }
    const { customers, receipts, refills, suppliers, products } = state.data;
    switch (kind) {
      case 'refill':
        if (e.oneGo) return recordRefillDialog(e.supplierId, e);
        if (e.items.some((i) => i.received + i.rejected > 0)) {
          return toast('Some tanks on this trip already came back. Edit “Back from refiller” in Stock history, or undo the trip.', true);
        }
        return sendRefillDialog(e.supplierId, e);
      case 'receive': return receiveRefillDialog(e.refillId, e);
      case 'movement': {
        const pr = byId(products, e.productId);
        if (!pr) return toast('That product was deleted.', true);
        if (e.type === 'purchase') return purchaseDialog(pr, e);
        if (['count', 'damaged', 'other'].includes(e.type)) return adjustDialog(pr, e);
        return toast('This older stock change can’t be edited. Undo it and enter it again.', true);
      }
      case 'payment': {
        const rc = e.receiptId && receipts.find((r) => r.id === e.receiptId);
        const c = byId(customers, e.customerId);
        if (!rc && !c) return toast('That customer was deleted.', true);
        return paymentDialog(rc ? { receipt: rc, edit: e } : { customer: c, edit: e });
      }
      case 'return': return returnDialog(byId(customers, e.customerId), e);
      case 'deposit': return keptDialog(byId(customers, e.customerId), e);
      case 'supplier-payment': {
        const trip = e.refillId && refills.find((r) => r.id === e.refillId);
        if (trip) return payRefillerDialog(trip, e);
        const sup = byId(suppliers, e.supplierId);
        return sup ? paySupplierDialog(sup, e) : toast('That supplier was deleted.', true);
      }
      case 'payback': {
        const owed = (state.data.staffMoney.owed.find((o) => o.staff === e.staff) || {}).owed || 0;
        return payBackDialog({ staff: e.staff, owed: round2(owed + e.amount) }, e);
      }
      default: return toast('This can’t be edited.', true);
    }
  }

  // ------------------------------------------------------------------ connection

  // Shows a bar while the server can't be reached, so nobody types a sale that won't save.
  const offlineBar = h('div', { class: 'offline-bar', role: 'alert', hidden: true }, 'Can’t reach the POS server. Nothing can be saved until the connection is back.');
  document.body.prepend(offlineBar);
  function setOnline(ok) { offlineBar.hidden = ok; }

  // ------------------------------------------------------------------ auth

  function showLogin(message) {
    state.me = null;
    state.etag = null;
    $('app').hidden = true;
    $('login').hidden = false;
    const err = $('login-error');
    err.hidden = !message;
    err.textContent = message || '';
    $('login-form').username.focus();
  }

  function showApp(me) {
    state.me = me;
    $('login').hidden = true;
    $('app').hidden = false;
    $('username').textContent = me.username;
    $('avatar').textContent = me.username.slice(0, 1);
    $('demo-badge').hidden = !me.demo;
    $('change-password').hidden = !!me.demo;
    $('logout').hidden = !!me.demo;
    $('demo-note').hidden = !me.demo;
    $('store-label').textContent = me.business ? me.business.name : '';
    $('team-link').hidden = me.role !== 'owner' || !!me.demo;
    $('billing-link').hidden = me.role !== 'owner' || !!me.demo;
    showBilling(me);
    updateDashboardAccess();
    go(location.hash.slice(1) || 'sell');
  }

  // Subscription reminder: the owner from a week before it's due, everyone once it's late.
  function showBilling(me) {
    const b = me.billing || {}, owner = me.role === 'owner', bar = $('billing-bar');
    const day = (s) => s ? new Date(s + 'T00:00:00').toLocaleDateString('en-PH', { month: 'short', day: 'numeric' }) : '';
    let text = null;
    if (b.state === 'unpaid') text = owner ? 'Welcome! Pay the first month on the Billing page to start saving sales. You can look around until then.'
      : 'The store hasn’t paid for LPG POS yet, so new records can’t be saved. Ask the store owner to pay.';
    else if (b.state === 'due' && owner) text = 'Your LPG POS subscription is paid until ' + day(b.paidUntil) + '. Pay now to keep it running.';
    else if (b.state === 'grace') text = owner ? 'Your LPG POS payment is late. New records stop saving on ' + day(b.lockDate) + ' unless it’s paid.'
      : 'The store’s LPG POS payment is late. Please remind the store owner.';
    else if (b.state === 'overdue') text = owner ? 'Your LPG POS subscription is overdue, so new records can’t be saved. You can still look at everything.'
      : 'The store’s LPG POS subscription is overdue, so new records can’t be saved. Ask the store owner to pay.';
    bar.hidden = !text || !!me.demo;
    bar.classList.toggle('is-locked', b.state === 'overdue' || b.state === 'unpaid');
    clear(bar, h('span', {}, text || ''), owner ? h('a', { class: 'btn small primary', href: '/billing.html' }, 'Pay now') : null);
  }

  // The Dashboard tab shows only for staff the admin has allowed to see it.
  function updateDashboardAccess() {
    for (const t of document.querySelectorAll('.tab[data-view="dashboard"]')) t.hidden = !(state.me && state.me.dashboard);
  }

  $('login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const btn = f.querySelector('button[type=submit]');
    btn.disabled = true;
    try {
      const me = await request('POST', '/api/login', { username: f.username.value.trim(), password: f.password.value });
      f.password.value = '';
      showApp(me);
    } catch (ex) {
      $('login-error').textContent = ex.message;
      $('login-error').hidden = false;
      f.password.select();
    } finally {
      btn.disabled = false;
    }
  });

  $('logout').addEventListener('click', async () => {
    await request('POST', '/api/logout').catch(() => {});
    document.querySelector('.usermenu').open = false;
    showLogin();
  });

  $('change-password').addEventListener('click', () => {
    document.querySelector('.usermenu').open = false;
    formDialog({
      title: 'Change password',
      message: 'You will stay signed in here; other devices using this account will be signed out.',
      submit: 'Change password',
      fields: [
        { name: 'current', label: 'Current password', type: 'password', required: true, full: true, autocomplete: 'current-password' },
        { name: 'password', label: 'New password', type: 'password', required: true, full: true, autocomplete: 'new-password', help: 'At least 8 characters' },
      ],
      onSubmit: (v) => api('POST', '/api/me/password', v).then(() => toast('Password changed')),
    });
  });

  // How to put each kind of mistake right, in one place (user menu → Fixing mistakes).
  $('help').addEventListener('click', () => {
    document.querySelector('.usermenu').open = false;
    const mins = state.data.undoMinutes || 30;
    const item = (title, how) => h('li', {}, h('strong', {}, title), h('span', {}, how));
    openDialog({
      title: 'Fixing mistakes',
      message: `Right after saving, tap Undo on the message at the bottom of the screen. Later, find the record and tap Edit to correct it (it keeps its date) or Undo to take it out. You can change what you entered in the last ${mins} minutes; staff with dashboard access can change anything.`,
      submit: null, cancel: 'Close', wide: true,
      body: h('ul', { class: 'help-list' },
        item('A sale', 'Receipts → open the receipt → Edit to change items, prices or payment, or Void to remove it.'),
        item('A customer payment', 'Open the receipt → Undo next to the payment.'),
        item('A refill, or tanks received', 'Inventory → At refiller (or Finished recently) → Undo, or Stock history → Undo next to it. Undoing tanks received puts them back at the refiller.'),
        item('New tanks bought, a stock count', 'Inventory → Stock history → Undo.'),
        item('A tank collected from a customer', 'Inventory → Tanks out → Recently returned → Undo.'),
        item('A customer’s kept tanks', 'Customers → Kept tanks → Undo under “Recorded lately”.'),
        item('A supplier payment or a payback', 'Inventory → Staff money → Undo, or Suppliers → History → Undo (dashboard).'),
        item('An expense, or the wrong day', 'Dashboard → Money: correct or delete an expense. A receipt’s day can be changed when editing it (dashboard).'),
        item('Undo says no', 'Something entered later depends on it (for example the tanks were already sold). It names what; undo or correct that first, or count the stock instead.')),
    });
  });

  // A half-typed sale is lost if the page is closed or reloaded: the browser asks first.
  window.addEventListener('beforeunload', (e) => {
    if (state.me && (state.sale.lines.length || state.sale.editing)) { e.preventDefault(); e.returnValue = ''; }
  });

  // ------------------------------------------------------------------ navigation

  const VIEWS = ['sell', 'history', 'customers', 'products', 'inventory', 'dashboard'];

  function go(view) {
    if (!VIEWS.includes(view) || (view === 'dashboard' && !(state.me && state.me.dashboard))) view = 'sell';
    state.view = view;
    $('work-date').textContent = new Date().toLocaleDateString('en-PH', { weekday: 'short', month: 'short', day: 'numeric' });
    if (location.hash.slice(1) !== view) history.replaceState(null, '', '#' + view);
    for (const s of document.querySelectorAll('.view')) s.hidden = s.id !== 'view-' + view;
    for (const t of document.querySelectorAll('.tab')) {
      if (t.dataset.view === view) t.setAttribute('aria-current', 'page');
      else t.removeAttribute('aria-current');
    }
    render(false);
    window.scrollTo({ top: 0 });
    if (state.me) refresh().catch((e) => toast(e.message, true));
  }

  document.addEventListener('click', (e) => {
    const t = e.target.closest('[data-view], [data-goto]');
    if (t) go(t.dataset.view || t.dataset.goto);
    // Open menus (account, a row's ⋯) close on a tap anywhere else.
    for (const menu of document.querySelectorAll('details.usermenu[open], details.rowmenu[open]')) {
      if (!menu.contains(e.target)) menu.open = false;
    }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') for (const menu of document.querySelectorAll('details.rowmenu[open]')) { menu.open = false; menu.querySelector('summary').focus(); }
  });
  window.addEventListener('scroll', () => { for (const menu of document.querySelectorAll('details.rowmenu[open]')) menu.open = false; }, { passive: true });

  // F1–F6 open the sections (as in the sidebar); Ctrl+Enter processes the sale. Not while a dialog is open.
  const KEYS = { F1: 'sell', F2: 'history', F3: 'customers', F4: 'products', F5: 'inventory', F6: 'dashboard' };
  document.addEventListener('keydown', (e) => {
    if (!state.me || document.querySelector('dialog[open]') || e.altKey || e.metaKey) return;
    const view = KEYS[e.key];
    if (view && !e.ctrlKey && !e.shiftKey) {
      e.preventDefault(); // F1 help and F5 reload would otherwise fire (and could lose a half-typed sale)
      if (view !== 'dashboard' || state.me.dashboard) go(view);
      return;
    }
    if (e.key === 'Enter' && e.ctrlKey && state.view === 'sell' && !$('process').disabled) {
      e.preventDefault();
      $('process').click();
    }
  });
  // The skip link is a focus shortcut, not a section route.
  document.querySelector('.skip-link').addEventListener('click', (e) => {
    e.preventDefault();
    $('main-content').focus();
    $('main-content').scrollIntoView({ block: 'start' });
  });

  // passive: a background refresh. The sale screen then leaves the receipt being typed alone.
  function render(passive) {
    if (!state.me) return;
    ({ sell: renderSell, history: renderHistory, customers: renderCustomers, products: renderProducts, inventory: renderInventory, dashboard: renderDashboard })[state.view](passive);
    updateCartBar();
  }

  function updateBadges() {
    const attention = state.data.products.filter((p) => p.status !== 'ok').length;
    for (const id of ['tab-alert', 'tab-alert-sm']) {
      $(id).hidden = !attention;
      $(id).textContent = attention;
    }
  }

  // ------------------------------------------------------------------ helpers

  const byId = (list, id) => list.find((x) => x.id === id);
  const fullName = (c) => `${c.firstName} ${c.lastName}`.trim();
  const initials = (c) => ((c.firstName || '?')[0] + (c.lastName || '')[0]).toUpperCase();
  const kg = (w) => (Number.isInteger(w) ? w.toFixed(1) : String(w)) + ' kg';
  const matches = (q, ...fields) => !q || fields.join(' ').toLowerCase().includes(q.toLowerCase());
  const plural = (n, word) => `${count.format(n)} ${word}${n === 1 ? '' : 's'}`;
  const round2 = (n) => Math.round(n * 100) / 100;
  const todayIso = () => {
    const n = new Date();
    return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')}`;
  };

  // The day a record is for, so something done on an earlier day can be entered later. Only staff with
  // dashboard access get it (the server checks too); it sends recordDate, which the server ignores when it's today.
  function dateField(inputs, edit) {
    if (!(state.me && state.me.dashboard)) return null;
    const input = h('input', { class: 'input', type: 'date', name: 'recordDate', value: edit ? edit.time.slice(0, 10) : todayIso(), max: todayIso(), 'aria-label': 'Date' });
    if (inputs) inputs.recordDate = input;
    return h('label', { class: 'field' }, h('span', {}, 'Date'), input);
  }
  const parseMoney = (v) => {
    const s = String(v).replace(/[₱,\s]/g, '');
    return s === '' ? NaN : Number(s);
  };

  const STOCK = { ok: ['●', 'In stock'], low: ['▲', 'Low'], out: ['✕', 'Out of stock'] };
  const PAY = { paid: ['ok', '●', 'Paid'], partial: ['low', '◐', 'Partly paid'], unpaid: ['out', '○', 'Unpaid'] };
  const pill = (cls, icon, label) => h('span', { class: 'status ' + cls }, h('span', { class: 'i', 'aria-hidden': 'true' }, icon), label);
  const stockPill = (p) => pill(p.status, ...STOCK[p.status]);
  const payPill = (status) => pill(...PAY[status]);
  const stockText = (p) => p.status === 'out' ? 'No tanks with load' : p.status === 'low' ? `Low · ${p.loaded} with load` : `${p.loaded} with load`;
  const itemsText = (r) => r.lines.map((l) => `${l.qty} × ${l.product}${l.owesTank ? ' (no empty)' : ''}`).join(', ');
  const lastText = (c) => c.lastPurchase ? `Last bought ${fmtTime(c.lastPurchase)}` : 'No purchases yet';
  const tanksBadge = (c) => c.tanksOwed > 0 ? h('span', { class: 'owes' }, `Owes ${plural(c.tanksOwed, 'tank')}`) : null;
  // The customer's own tanks the store keeps for them (not the store's stock).
  const keptBadge = (c) => c.keptTanks > 0 ? h('span', { class: 'kept' }, `We keep ${plural(c.keptTanks, 'tank')}`) : null;
  const keptText = (c) => (c.kept || []).map((k) => `${k.qty} × ${k.product}`).join(', ');
  const owedFrom = (o) => o.receiptId === 'start' ? 'had it on the start date' : `receipt #${o.receiptId}, ${fmtTime(o.time)}`;
  // "3 × Petron Gasul 11.0 kg, 1 × Solane 11.0 kg": a customer's owed tanks added up per product.
  const tanksText = (c) => {
    const by = new Map();
    for (const o of c.owedTanks) by.set(o.product, (by.get(o.product) || 0) + o.qty);
    return [...by].map(([p, n]) => `${n} × ${p}`).join(', ');
  };
  const owedText = (r) => r.status === 'paid' ? 'Paid' : r.status === 'partial' ? `Owes ${peso.format(r.balance)}` : `Unpaid ${peso.format(r.balance)}`;

  // On phones each row collapses to: title + figure, one detail line, then the actions.
  // Columns opt in with sm: 'title' | 'figure' | 'hide'; detail(row) builds the phone-only line.
  function table(columns, rows, empty, detail, rowClick) {
    if (!rows.length) return h('div', { class: 'empty' }, empty);
    const cls = (c) => [c.cls || (c.right ? 'right num' : ''), c.sm ? 'sm-' + c.sm : ''].join(' ').trim() || null;
    return h('table', { class: 'rtable' + (rowClick ? ' clickable' : '') },
      h('thead', {}, h('tr', {}, columns.map((c) => h('th', { class: c.right ? 'right' : null }, c.label)))),
      h('tbody', {}, rows.map((r) => h('tr', { onclick: rowClick ? (e) => { if (!e.target.closest('button')) rowClick(r); } : null },
        columns.map((c) => h('td', { class: cls(c) }, c.render(r))),
        detail ? h('td', { class: 'sm-detail' }, detail(r)) : null))));
  }

  function actions(...buttons) {
    return h('div', { class: 'row-actions' }, buttons);
  }

  // A row's main action as a button, and the rest (Delete last, in red) in a ⋯ menu.
  // items: [{ label, onClick, danger }] (falsy entries are skipped).
  function rowMenu(primary, items, label) {
    const list = items.filter(Boolean);
    const menu = h('details', { class: 'rowmenu' },
      h('summary', { class: 'btn small', 'aria-label': `More actions${label ? ' for ' + label : ''}`, title: 'More actions' }, '⋯'),
      h('div', { class: 'menu card', role: 'menu' }, list.map((it) => [
        it.danger && list.length > 1 ? h('hr', { class: 'menu-sep' }) : null,
        h('button', { type: 'button', role: 'menuitem', class: 'btn ghost block' + (it.danger ? ' danger' : ''),
          onclick: (e) => { e.stopPropagation(); menu.open = false; it.onClick(); } }, it.label)])));
    // The table scrolls sideways and would clip the menu, so it opens fixed under its button.
    menu.addEventListener('toggle', () => {
      if (!menu.open) return;
      for (const other of document.querySelectorAll('details.rowmenu[open]')) if (other !== menu) other.open = false;
      const box = menu.querySelector('.menu'), r = menu.querySelector('summary').getBoundingClientRect();
      box.style.right = Math.max(8, window.innerWidth - r.right) + 'px';
      const below = window.innerHeight - r.bottom;
      if (below < box.offsetHeight + 12 && r.top > below) { box.style.top = ''; box.style.bottom = (window.innerHeight - r.top + 6) + 'px'; }
      else { box.style.bottom = ''; box.style.top = (r.bottom + 6) + 'px'; }
    });
    menu.addEventListener('click', (e) => e.stopPropagation()); // not a click on the row
    return actions(primary ? h('button', { type: 'button', class: 'btn small' + (primary.primary ? ' primary' : ''), onclick: (e) => { e.stopPropagation(); primary.onClick(); } }, primary.label) : null,
      list.length ? menu : null);
  }

  function formBody(form) {
    const body = {};
    for (const [k, v] of new FormData(form).entries()) body[k] = String(v).trim();
    return body;
  }

  // Draw long lists in pages so phones stay quick; totals and search still cover everything.
  const PAGE = 100;
  const pages = { history: PAGE };
  function moreButton(key, shown, total, render) {
    return total > shown ? h('div', { class: 'more-row' },
      h('button', { class: 'btn', onclick: () => { pages[key] += PAGE; render(); } }, `Show more (${count.format(total - shown)} older)`)) : null;
  }

  // ------------------------------------------------------------------ new sale: customer

  $('sell-search').addEventListener('input', renderCustomerPick);
  $('quick-customer').addEventListener('click', async () => {
    const id = await customerDialog();
    if (id) { $('sell-search').value = ''; chooseCustomer(id); }
  });

  // Each customer starts with an empty receipt: picking someone else clears the items added so far.
  function chooseCustomer(id) {
    const sale = state.sale;
    if (!sale.editing && sale.lastCustomerId && sale.lastCustomerId !== id && sale.lines.length) {
      Object.assign(sale, { lines: [], discount: '', pay: 'full', paidNow: '', cash: '', note: '' });
      syncSaleInputs();
      toast('New receipt started for this customer');
    }
    sale.customerId = id;
    sale.lastCustomerId = id;
    renderCustomerPick();
    renderCart();
    renderTiles();
  }

  function renderCustomerPick() {
    const { customers, receipts } = state.data;
    const sale = state.sale;
    if (sale.customerId && !byId(customers, sale.customerId)) sale.customerId = null;
    const chosen = byId(customers, sale.customerId);
    $('sell-search-wrap').hidden = !!chosen;
    clear($('sell-selected'), chosen ? h('div', { class: 'pick selected-customer' },
      h('span', { class: 'avatar' }, initials(chosen)),
      h('div', {},
        h('div', { class: 'name' }, fullName(chosen), chosen.balance > 0 ? h('span', { class: 'owes' }, `Owes ${peso.format(chosen.balance)}`) : null, tanksBadge(chosen), keptBadge(chosen)),
        h('div', { class: 'meta' }, [chosen.contact, chosen.address].filter(Boolean).join(' · ') || 'No contact details'),
        h('div', { class: 'meta' }, lastText(chosen))),
      h('button', { class: 'btn small', onclick: () => { sale.customerId = null; renderCustomerPick(); renderTotals(); $('sell-search').focus(); } }, 'Change')) : null);
    if (chosen) return;

    const q = $('sell-search').value.trim();
    let list;
    if (q) {
      list = customers.filter((c) => matches(q, c.id, fullName(c), c.contact, c.address));
    } else {
      const order = []; // most recent buyers first
      for (const r of receipts) if (!order.includes(r.customerId)) order.push(r.customerId);
      const rank = (id) => { const i = order.indexOf(id); return i < 0 ? 1e9 - Number(id || 0) : i; };
      list = [...customers].sort((a, b) => rank(a.id) - rank(b.id));
    }
    const shown = list.slice(0, q ? 6 : 4);
    clear($('sell-results'),
      shown.map((c) => h('button', { class: 'pick', onclick: () => chooseCustomer(c.id) },
        h('span', { class: 'avatar' }, initials(c)),
        h('div', {},
          h('div', { class: 'name' }, fullName(c), c.balance > 0 ? h('span', { class: 'owes' }, `Owes ${peso.format(c.balance)}`) : null, tanksBadge(c), keptBadge(c)),
          h('div', { class: 'meta' }, [c.contact, c.address].filter(Boolean).join(' · ') || '—'),
          h('div', { class: 'meta' }, lastText(c))),
        h('span', { class: 'muted' }, '#' + c.id))),
      !customers.length ? h('div', { class: 'empty' }, 'No customers yet. Tap “+ New” to add one.')
        : !shown.length ? h('div', { class: 'empty' }, 'No customer matches “' + q + '”.')
        : list.length > shown.length ? h('div', { class: 'more-hint' }, `${shown.length} of ${list.length} customers · search to find another`) : null);
  }

  // ------------------------------------------------------------------ new sale: tanks and cart

  // Tanks with load this sale may use (when editing, the receipt's own tanks count as available).
  const available = (pid) => (byId(state.data.products, pid)?.loaded || 0) + (state.sale.own[pid] || 0);
  const inCart = (pid, except) => state.sale.lines.filter((l) => l.productId === pid && l !== except).reduce((n, l) => n + l.qty, 0);
  const cartQty = () => state.sale.lines.reduce((n, l) => n + l.qty, 0);

  // Tank-size chips over the grid: All sizes, then each size there is.
  function renderSizeChips() {
    const sizes = [...new Set(state.data.products.map((p) => p.weight))].sort((a, b) => a - b);
    if (state.sizeFilter !== '' && !sizes.includes(Number(state.sizeFilter))) state.sizeFilter = '';
    const chip = (value, label) => h('button', { type: 'button', class: 'chip', 'aria-pressed': String(state.sizeFilter === value),
      onclick: () => { state.sizeFilter = value; renderSizeChips(); renderTiles(); } }, label);
    clear($('size-chips'), sizes.length > 1 ? [chip('', 'All sizes'), sizes.map((w) => chip(String(w), kg(w)))] : null);
  }

  function renderTiles() {
    const query = $('sell-product-search').value.trim();
    const prods = state.data.products.filter((p) => matches(query, p.brand, p.label, kg(p.weight))
      && (state.sizeFilter === '' || p.weight === Number(state.sizeFilter)));
    clear($('sell-products'), prods.length ? prods.map((p) => {
      const n = inCart(p.id);
      const left = available(p.id) - n;
      return h('button', {
        class: 'product', disabled: available(p.id) <= 0, 'aria-pressed': String(n > 0),
        'aria-label': `${p.label}, refill ${peso.format(p.price)}, ${stockText(p)}${n ? `, ${n} on this receipt` : ''}`,
        onclick: () => addToCart(p),
      },
      h('span', { class: 'brand-name' }, p.brand),
      h('span', { class: 'kg' }, kg(p.weight)),
      h('span', { class: 'price num' }, peso.format(p.price)),
      available(p.id) <= 0 ? h('span', { class: 'status out' }, h('span', { class: 'i', 'aria-hidden': 'true' }, '✕'), 'Out of stock')
        : h('span', { class: 'stock ' + (left <= 0 && n ? 'out' : p.status) }, n ? `${left} more with load` : stockText(p)),
      n ? h('span', { class: 'in-cart', 'aria-hidden': 'true' }, n) : null);
    }) : h('div', { class: 'empty' }, query || state.sizeFilter ? 'No matching tanks. Try a different brand or size.' : 'No LPG products yet. Add them in the Products tab.'));
  }
  $('sell-product-search').addEventListener('input', renderTiles);

  function addToCart(p) {
    const sale = state.sale;
    if (inCart(p.id) + 1 > available(p.id)) return toast(`Only ${available(p.id)} × ${p.label} with load`, true);
    const same = sale.lines.find((l) => l.productId === p.id && l.emptyId === p.id && !l.remark && !l.priceEdited);
    if (same) same.qty++;
    else sale.lines.push({ key: ++lineKey, productId: p.id, qty: 1, unitPrice: p.price, priceEdited: false, emptyId: p.id, emptyBrand: '', swapFee: p.swapFee || 0, remark: '' });
    renderCart();
    renderTiles();
  }

  function clampQty(l, v) {
    const max = Math.max(1, Math.min(100, available(l.productId) - inCart(l.productId, l)));
    const n = Math.round(Number(v));
    if (!Number.isFinite(n) || String(v).trim() === '') return null;
    if (n > max) toast(`Only ${max} more × ${byId(state.data.products, l.productId)?.label || 'this tank'} with load`, true);
    return Math.min(max, Math.max(1, n));
  }

  // A swap: the customer's empty is another brand, which costs extra (the swap fee, per tank).
  // Generic (no brand) and other-brand empties count as swaps; they go into no product's stock.
  const LOOSE = ['generic', 'other'];
  const missingBrand = () => state.sale.lines.some((l) => l.emptyId === 'other' && !(l.emptyBrand || '').trim());
  const isSwap = (l) => {
    if (LOOSE.includes(l.emptyId)) return true; // generic or another brand we don't sell
    const p = byId(state.data.products, l.productId), e = l.emptyId && byId(state.data.products, l.emptyId);
    return !!(p && e && e.brand.toLowerCase() !== p.brand.toLowerCase());
  };
  const lineAmount = (l) => l.qty * (l.unitPrice + (isSwap(l) ? l.swapFee || 0 : 0));

  // The empty that comes in: the same tank, another brand of the same size, or none (the customer owes it).
  function emptyOptions(p, current) {
    const others = state.data.products.filter((x) => x.id !== p.id && x.weight === p.weight);
    const odd = current && current !== p.id && !others.some((x) => x.id === current) && byId(state.data.products, current);
    return [
      h('option', { value: p.id }, `Same: ${p.label}`),
      others.length ? h('optgroup', { label: 'Another brand (swap fee applies)' }, others.map((x) => h('option', { value: x.id }, x.label))) : null,
      h('optgroup', { label: 'Not a brand we sell (swap fee applies)' },
        h('option', { value: 'generic' }, `Generic (no brand) ${kg(p.weight)}`),
        h('option', { value: 'other' }, `Other brand ${kg(p.weight)}: type it`)),
      odd ? h('option', { value: odd.id }, `${odd.label} (different size, change it)`) : null,
      h('option', { value: '' }, 'None: will bring a tank back later'),
    ];
  }

  function lineEditor(l) {
    const p = byId(state.data.products, l.productId);
    if (!p) return null;
    const amount = h('span', { class: 'num line-amount' });
    const owesNote = h('div', { class: 'cl-row owe-note' });
    const showAmount = () => {
      amount.textContent = peso.format(lineAmount(l));
      owesNote.textContent = `No empty in: ${byId(state.data.customers, state.sale.customerId) ? fullName(byId(state.data.customers, state.sale.customerId)) : 'the customer'} owes ${plural(l.qty, 'tank')} and brings ${l.qty === 1 ? 'it' : 'them'} back later.`;
    };
    showAmount();

    const qty = h('input', { class: 'input num', type: 'number', min: 1, max: 100, step: 1, inputmode: 'numeric', 'aria-label': `Quantity of ${p.label}` });
    qty.value = l.qty;
    qty.addEventListener('input', () => {
      const n = clampQty(l, qty.value);
      if (n == null) return;
      l.qty = n;
      showAmount();
      renderTotals();
      renderTiles();
    });
    qty.addEventListener('blur', () => { qty.value = l.qty; });
    const step = (d) => { const n = clampQty(l, l.qty + d); if (n != null) { l.qty = n; qty.value = n; showAmount(); renderTotals(); renderTiles(); } };

    const price = h('input', { class: 'input num', inputmode: 'decimal', 'aria-label': `Price per tank for ${p.label}` });
    price.value = l.unitPrice.toFixed(2);
    price.addEventListener('input', () => {
      const v = parseMoney(price.value);
      if (!Number.isNaN(v) && v >= 0) { l.unitPrice = round2(v); l.priceEdited = true; showAmount(); renderTotals(); }
    });
    price.addEventListener('blur', () => { price.value = l.unitPrice.toFixed(2); });

    const remark = h('input', { class: 'input', maxlength: 80, placeholder: 'Remark on tank condition (optional)', 'aria-label': `Remark on the condition of ${p.label}` });
    remark.value = l.remark;
    remark.addEventListener('input', () => { l.remark = remark.value; });
    const fee = h('input', { class: 'input num', inputmode: 'decimal', 'aria-label': `Swap fee per tank for ${p.label}` });
    fee.value = (l.swapFee || 0).toFixed(2);
    fee.addEventListener('input', () => {
      const v = parseMoney(fee.value);
      if (!Number.isNaN(v) && v >= 0) { l.swapFee = round2(v); showAmount(); renderTotals(); }
    });
    fee.addEventListener('blur', () => { fee.value = (l.swapFee || 0).toFixed(2); });
    const swapRow = h('label', { class: 'cl-row swap-row' }, h('span', { class: 'muted' }, 'Swap fee ₱'), fee, h('small', { class: 'muted' }, 'each, for another brand'));
    const empty = h('select', { class: 'input', 'aria-label': `Empty tank returned for ${p.label}` }, emptyOptions(p, l.emptyId));
    empty.value = l.emptyId;
    const brand = h('input', { class: 'input', maxlength: 40, placeholder: 'Brand of the empty, e.g. Shine', autocomplete: 'off', 'aria-label': `Brand of the empty for ${p.label}` });
    brand.value = l.emptyBrand || '';
    brand.addEventListener('input', () => { l.emptyBrand = brand.value; renderTotals(); });
    const brandRow = h('div', { class: 'cl-row remark-row brand-row' }, brand);
    const showEmpty = () => {
      swapRow.hidden = !isSwap(l);
      owesNote.hidden = !!l.emptyId;
      brandRow.hidden = l.emptyId !== 'other';
    };
    showEmpty();
    empty.addEventListener('change', () => {
      l.emptyId = empty.value;
      showEmpty();
      if (l.emptyId === 'other' && !brand.value) brand.focus();
      showAmount();
      renderTotals();
    });

    return h('div', { class: 'cart-line' },
      h('div', { class: 'cl-head' },
        h('strong', {}, p.label),
        h('button', { type: 'button', class: 'btn ghost small', 'aria-label': `Remove ${p.label}`,
          onclick: () => { state.sale.lines = state.sale.lines.filter((x) => x !== l); renderCart(); renderTiles(); } }, 'Remove')),
      h('div', { class: 'cl-row' },
        h('div', { class: 'stepper' },
          h('button', { type: 'button', class: 'btn', 'aria-label': 'One less', onclick: () => step(-1) }, '−'),
          qty,
          h('button', { type: 'button', class: 'btn', 'aria-label': 'One more', onclick: () => step(1) }, '+')),
        h('label', { class: 'price-wrap' }, h('span', { class: 'muted' }, '× ₱'), price),
        amount),
      h('div', { class: 'cl-row empty-row' }, h('span', { class: 'muted' }, 'Empty in:'), empty),
      brandRow, swapRow, owesNote,
      h('div', { class: 'cl-row remark-row' }, remark));
  }

  function renderCart() {
    const sale = state.sale;
    $('cart-count').textContent = sale.lines.length ? plural(cartQty(), 'tank') : '';
    clear($('cart-lines'), sale.lines.length ? sale.lines.map(lineEditor) : h('div', { class: 'empty' }, 'Tap an LPG tank to add it.'), keptToggle());
    renderPayment();
  }

  // When the store keeps some of this customer's own tanks, the empties on a new sale can be those tanks.
  function keptToggle() {
    const sale = state.sale;
    const c = byId(state.data.customers, sale.customerId);
    if (!c || !c.keptTanks || sale.editing || !sale.lines.length) { sale.fromKept = false; return null; }
    const box = h('input', { type: 'checkbox', checked: sale.fromKept || null });
    box.addEventListener('change', () => { sale.fromKept = box.checked; });
    return h('label', { class: 'kept-toggle' }, box,
      h('span', {}, `The empties are ${fullName(c)}’s tanks we keep`, h('small', { class: 'muted' }, ` (${keptText(c)})`)));
  }

  function totals() {
    const sale = state.sale;
    const subtotal = round2(sale.lines.reduce((s, l) => s + lineAmount(l), 0));
    let discount = 0;
    let discountError = null;
    const d = sale.discount.trim();
    if (d) {
      const pct = d.endsWith('%');
      const v = parseMoney(pct ? d.slice(0, -1) : d);
      if (Number.isNaN(v) || v < 0 || (pct && v > 100)) discountError = pct ? 'Use 0–100%' : 'Enter an amount or a percent';
      else discount = round2(pct ? subtotal * v / 100 : v);
      if (!discountError && discount > subtotal) discountError = 'More than the subtotal';
    }
    const total = round2(Math.max(0, subtotal - discount));
    const due = round2(Math.max(0, total - sale.laterPaid)); // what can still be paid at the sale
    let paid = due;
    let payError = null;
    if (sale.pay === 'later') paid = 0;
    if (sale.pay === 'partial') {
      const v = parseMoney(sale.paidNow);
      if (Number.isNaN(v) || v < 0) { paid = 0; payError = 'Enter the amount paid now'; }
      else if (v > due + 0.005) { paid = v; payError = `More than the ${peso.format(due)} due`; }
      else paid = round2(v);
    }
    return { subtotal, discount, discountError, total, due, paid, payError, credit: round2(Math.max(0, due - paid)) };
  }

  function renderTotals() {
    const sale = state.sale;
    const t = totals();
    $('cart-subtotal').textContent = peso.format(t.subtotal);
    const da = $('discount-amount');
    da.textContent = t.discountError || (t.discount ? '−' + peso.format(t.discount) : '');
    da.classList.toggle('error-text', !!t.discountError);
    $('cart-total').textContent = peso.format(t.total);

    const cash = parseMoney(sale.cash);
    const showChange = sale.pay === 'full' && sale.method === 'cash' && !Number.isNaN(cash) && sale.lines.length;
    $('change').textContent = showChange ? (cash >= t.paid ? peso.format(cash - t.paid) : `Short ${peso.format(t.paid - cash)}`) : '—';
    $('change').classList.toggle('short', !!showChange && cash < t.paid);
    renderCashChips(t.paid);

    const c = byId(state.data.customers, sale.customerId);
    const note = $('credit-note');
    note.hidden = !sale.lines.length || !(t.credit > 0 || t.payError);
    note.textContent = t.payError || `${peso.format(t.credit)} goes on ${c ? fullName(c) + '’s' : 'the customer’s'} balance to pay later.`;
    note.classList.toggle('error-text', !!t.payError);

    const btn = $('process');
    btn.disabled = !(c && sale.lines.length && !t.discountError && !t.payError && !missingBrand());
    const ready = $('sale-ready');
    ready.hidden = !btn.disabled;
    ready.textContent = t.discountError || t.payError || (c && sale.lines.length && missingBrand() ? 'Type the brand of the other-brand empty.' : '') || (!c && !sale.lines.length ? 'Choose a customer and add a tank to begin.' : !c ? 'Choose a customer to finish this sale.' : 'Add a tank to this receipt.');
    btn.textContent = sale.editing ? (sale.lines.length ? `Save changes · ${peso.format(t.total)}` : 'Save changes')
      : !c && sale.lines.length ? 'Choose a customer'
      : !sale.lines.length ? 'Process sale'
      : t.credit > 0 ? `Process sale · ${peso.format(t.total)} (${peso.format(t.credit)} later)` : `Process sale · ${peso.format(t.total)}`;
    $('edit-banner').hidden = !sale.editing;
    $('edit-id').textContent = sale.editing ? '#' + sale.editing : '';
    updateCartBar();
  }

  // Quick cash amounts: the exact amount, then the next round notes above it.
  let cashChipsFor = null;
  function renderCashChips(due) {
    const key = state.sale.lines.length ? due : 0;
    if (cashChipsFor === key) return;
    cashChipsFor = key;
    const amounts = [];
    if (key > 0) for (const step of [100, 500, 1000]) {
      const v = Math.ceil((due + 0.001) / step) * step;
      if (v > due && !amounts.includes(v)) amounts.push(v);
    }
    const pick = (v) => { state.sale.cash = v.toFixed(2); $('cash').value = state.sale.cash; renderTotals(); };
    clear($('cash-chips'), key > 0 ? [h('button', { type: 'button', class: 'chip', onclick: () => pick(due) }, 'Exact'),
      amounts.slice(0, 3).map((v) => h('button', { type: 'button', class: 'chip num', onclick: () => pick(v) }, UI.pesoRound.format(v)))] : null);
  }

  function renderPayment() {
    const sale = state.sale;
    for (const b of document.querySelectorAll('[data-pay]')) b.setAttribute('aria-checked', String(b.dataset.pay === sale.pay));
    // Cash or GCash for what's paid now; nothing is paid now with "Pay later". Change is only for cash.
    for (const b of document.querySelectorAll('[data-method]')) b.setAttribute('aria-checked', String(b.dataset.method === sale.method));
    $('pay-method').hidden = sale.pay === 'later';
    $('pay-full').hidden = sale.pay !== 'full' || sale.method !== 'cash';
    $('pay-partial').hidden = sale.pay !== 'partial';
    renderTotals();
  }

  function syncSaleInputs() {
    const sale = state.sale;
    $('cart-discount').value = sale.discount;
    $('cash').value = sale.cash;
    $('paid-now').value = sale.paidNow;
    $('cart-note').value = sale.note;
    $('sale-date').value = sale.date || todayIso();
    $('sell-search').value = '';
    $('sell-product-search').value = '';
  }

  for (const b of document.querySelectorAll('[data-pay]')) {
    b.addEventListener('click', () => {
      state.sale.pay = b.dataset.pay;
      renderPayment();
      if (b.dataset.pay === 'partial') $('paid-now').focus();
    });
  }
  for (const b of document.querySelectorAll('[data-method]')) {
    b.addEventListener('click', () => { state.sale.method = b.dataset.method; renderPayment(); });
  }
  $('cart-discount').addEventListener('input', (e) => { state.sale.discount = e.target.value; renderTotals(); });
  $('sale-date').addEventListener('change', (e) => { state.sale.date = e.target.value; });
  $('cash').addEventListener('input', (e) => { state.sale.cash = e.target.value; renderTotals(); });
  $('paid-now').addEventListener('input', (e) => { state.sale.paidNow = e.target.value; renderTotals(); });
  $('cart-note').addEventListener('input', (e) => { state.sale.note = e.target.value; });
  $('process').addEventListener('click', submitSale);
  $('cancel-edit').addEventListener('click', () => { state.sale = newSale(); syncSaleInputs(); renderSell(false); toast('Edit cancelled'); });

  // Phones: the receipt sits below the tanks, so a bar keeps the running total in view.
  const cartBar = h('button', { class: 'cart-bar', hidden: true, onclick: () => document.querySelector('.order').scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' }) });
  $('app').append(cartBar);
  let orderInView = false; // the bar would only cover the receipt it points to
  new IntersectionObserver((entries) => { orderInView = entries[0].isIntersecting; updateCartBar(); })
    .observe(document.querySelector('.order'));
  function updateCartBar() {
    const n = cartQty();
    cartBar.hidden = !(state.view === 'sell' && n && !orderInView);
    if (n) clear(cartBar, h('span', {}, `${plural(n, 'tank')} · `, h('strong', { class: 'num' }, peso.format(totals().total))), h('span', {}, 'Review ↓'));
  }

  async function submitSale() {
    const sale = state.sale;
    const t = totals();
    const body = {
      customerId: sale.customerId, discount: t.discount, paid: t.paid, payMethod: sale.method, note: $('cart-note').value.trim(), items: sale.lines.length,
    };
    if (sale.fromKept && !sale.editing) body.fromKept = '1';
    const day = $('sale-date').value;
    // A new sale for another day sends recordDate; an edited receipt sends its (possibly changed) date.
    if (!$('sale-date-field').hidden && day) {
      if (sale.editing) body.date = day;
      else if (day !== todayIso()) body.recordDate = day;
    }
    sale.lines.forEach((l, i) => {
      Object.assign(body, {
        ['productId.' + i]: l.productId, ['qty.' + i]: l.qty, ['unitPrice.' + i]: l.unitPrice,
        ['emptyId.' + i]: l.emptyId, ['swapFee.' + i]: isSwap(l) ? l.swapFee || 0 : '', ['remark.' + i]: l.remark.trim(),
        ['emptyBrand.' + i]: l.emptyId === 'other' ? (l.emptyBrand || '').trim() : '',
      });
    });
    const btn = $('process');
    btn.disabled = true;
    const editing = sale.editing;
    try {
      const r = await api(editing ? 'PUT' : 'POST', editing ? '/api/receipts/' + encodeURIComponent(editing) : '/api/receipts', body);
      await refresh();
      state.sale = newSale();
      syncSaleInputs();
      renderSell(false);
      window.scrollTo({ top: 0, behavior: 'smooth' });
      toast(editing ? `Receipt #${r.id} updated` : `Receipt #${r.id} saved · ${peso.format(r.total)}`);
      showReceipt(r.id);
    } catch (e) {
      toast(e.message, true);
      renderTotals();
    }
  }

  function renderSell(passive) {
    const sale = state.sale;
    // Staff with dashboard access can date a sale on an earlier day, or move a receipt they're editing to another day.
    $('sale-date-field').hidden = !(state.me && state.me.dashboard);
    $('sale-date').max = todayIso();
    $('sale-date-field').querySelector('span').textContent = sale.editing ? 'Date of this receipt' : 'Date of sale';
    $('sale-date-help').textContent = sale.editing ? 'Change it if the receipt was entered on the wrong day.' : 'Change it to enter a sale made on another day.';
    if (!$('sale-date').value || $('sale-date').value > todayIso()) $('sale-date').value = sale.date || todayIso();
    const before = sale.lines.length;
    sale.lines = sale.lines.filter((l) => byId(state.data.products, l.productId));
    renderCustomerPick();
    renderSizeChips();
    renderTiles();
    if (passive && sale.lines.length === before) renderTotals(); // leave the receipt being typed alone
    else renderCart();
    const recent = state.data.receipts.slice(0, 5);
    clear($('sell-recent'), recent.length ? h('div', { class: 'mini-list' }, recent.map((r) => h('button', { class: 'mini-row', onclick: () => showReceipt(r.id) },
      h('div', {}, h('div', {}, r.customer), h('div', { class: 'meta' }, `#${r.id} · ${itemsText(r)} · ${fmtTime(r.time)}`)),
      h('div', { class: 'right' }, h('strong', { class: 'num' }, peso.format(r.total)), r.status !== 'paid' ? h('div', { class: 'meta owes-text' }, owedText(r)) : null)))) : h('div', { class: 'empty' }, 'No receipts yet.'));
  }

  // ------------------------------------------------------------------ receipts

  const emptyText = (l) => l.owesTank ? 'No empty in: owes the tank' : l.newTank ? 'New tank (no empty in)'
    : l.emptyKind ? `Empty in: ${l.emptyProduct} (swap)`
    : l.emptyProductId === l.productId ? 'Empty in' : `Empty in: ${l.emptyProduct} (swap)`;

  // onScreen: the receipt dialog, where a later payment entered by mistake can be undone (not on paper).
  function receiptView(rc, onScreen) {
    const row = (label, value, cls) => h('div', { class: 'rc-row' + (cls ? ' ' + cls : '') }, h('span', {}, label), h('span', { class: 'num' }, value));
    return h('div', { class: 'receipt' },
      h('div', { class: 'rc-head' }, h('strong', {}, 'LPG POS'), h('span', {}, `Receipt #${rc.id}`)),
      h('div', { class: 'rc-meta' }, `${fmtTime(rc.time, { full: true })} · Served by ${rc.staff}`),
      h('div', { class: 'rc-meta' }, `Customer: ${rc.customer}`),
      h('div', { class: 'rc-lines' }, rc.lines.map((l) => h('div', { class: 'rc-line' },
        h('div', {}, h('div', {}, `${l.qty} × ${l.product}`), h('div', { class: 'rc-sub' }, `${peso.format(l.unitPrice)} each · ${emptyText(l)}`),
          l.swapFee ? h('div', { class: 'rc-sub' }, `Swap fee ${peso.format(l.swapFee)} each`) : null,
          l.remark ? h('div', { class: 'rc-sub rc-remark' }, `Remark: ${l.remark}`) : null),
        h('span', { class: 'num' }, peso.format(l.amount))))),
      h('div', { class: 'rc-totals' },
        row('Subtotal', peso.format(rc.subtotal)),
        rc.discount ? row('Discount', '−' + peso.format(rc.discount)) : null,
        row('Total', peso.format(rc.total), 'strong'),
        row(rc.paidAtSale > 0 && rc.payMethod === 'gcash' ? 'Paid (GCash at the sale)' : 'Paid', peso.format(rc.paid)),
        rc.balance > 0 ? row('Balance to pay', peso.format(rc.balance), 'due') : null),
      rc.payments.length ? h('div', { class: 'rc-payments' },
        h('div', { class: 'rc-sub' }, 'Paid after the sale'),
        rc.payments.map((p) => h('div', { class: 'rc-pay' },
          row(`${fmtTime(p.time)} · ${p.method === 'gcash' ? 'GCash' : 'Cash'} · ${p.staff}${p.note ? ' · ' + p.note : ''}`, peso.format(p.amount)),
          onScreen ? changeButtons('payment', p.id, `the ${peso.format(p.amount)} payment from ${rc.customer}`, p.staff, p.time,
            'with any other part of the same payment on other receipts') : null))) : null,
      rc.note ? h('p', { class: 'rc-note' }, rc.note) : null,
      h('div', { class: 'rc-status' }, payPill(rc.status)));
  }

  function printReceipt(rc) {
    clear($('print-area'), receiptView(rc));
    window.print();
  }

  function showReceipt(id) {
    const rc = state.data.receipts.find((r) => r.id === String(id));
    if (!rc) return;
    const cust = byId(state.data.customers, rc.customerId);
    openDialog({
      title: `Receipt #${rc.id}`,
      submit: null,
      cancel: 'Close',
      body: receiptView(rc, true),
      actions: (close) => [
        h('button', { type: 'button', class: 'btn danger', onclick: () => { close(); voidReceipt(rc); } }, 'Void'),
        h('button', { type: 'button', class: 'btn', onclick: () => { close(); editReceipt(rc); } }, 'Edit'),
        h('button', { type: 'button', class: 'btn', onclick: () => printReceipt(rc) }, 'Print'),
        cust && cust.tanksOwed > 0 ? h('button', { type: 'button', class: 'btn', onclick: () => { close(); returnDialog(cust); } }, 'Collect tank') : null,
        rc.balance > 0 ? h('button', { type: 'button', class: 'btn primary', onclick: () => { close(); paymentDialog({ receipt: rc }); } }, 'Record payment') : null,
      ],
    });
  }

  function editReceipt(rc) {
    const sale = newSale();
    sale.editing = rc.id;
    sale.customerId = rc.customerId;
    sale.lines = rc.lines.map((l) => ({
      key: ++lineKey, productId: l.productId, qty: l.qty, unitPrice: l.unitPrice, priceEdited: true,
      emptyId: l.emptyKind || l.emptyProductId || '', emptyBrand: l.emptyBrand || '', swapFee: l.swapFee || 0, remark: l.remark || '',
    }));
    sale.lastCustomerId = rc.customerId;
    for (const l of rc.lines) sale.own[l.productId] = (sale.own[l.productId] || 0) + l.qty;
    sale.discount = rc.discount ? String(rc.discount) : '';
    sale.laterPaid = round2(rc.paid - rc.paidAtSale);
    const due = round2(rc.total - sale.laterPaid);
    if (rc.paidAtSale >= due - 0.005) sale.pay = 'full';
    else if (rc.paidAtSale <= 0.005) sale.pay = 'later';
    else { sale.pay = 'partial'; sale.paidNow = String(rc.paidAtSale); }
    sale.method = rc.payMethod === 'gcash' ? 'gcash' : 'cash';
    sale.note = rc.note || '';
    sale.date = (rc.time || '').slice(0, 10);
    state.sale = sale;
    syncSaleInputs();
    go('sell');
  }

  async function voidReceipt(rc) {
    const ok = await confirmDialog(`Void receipt #${rc.id}?`,
      `Removes ${itemsText(rc)} (${peso.format(rc.total)})${rc.paid ? ` and the ${peso.format(rc.paid)} recorded as paid` : ''}. The tanks go back into stock. This can’t be undone.`,
      'Void receipt');
    if (ok) mutate('DELETE', '/api/receipts/' + encodeURIComponent(rc.id), null, `Receipt #${rc.id} voided`).catch((e) => toast(e.message, true));
  }

  // edit: a payment being corrected (its amount is owed again once it's replaced).
  function paymentDialog({ customer, receipt, edit }) {
    const owed = round2((receipt ? receipt.balance : customer.balance) + (edit ? edit.amount : 0));
    const who = receipt ? receipt.customer : fullName(customer);
    return formDialog({
      title: editTitle(edit, receipt ? `Payment for receipt #${receipt.id}` : `Collect payment from ${who}`),
      message: receipt ? `${who} owes ${peso.format(owed)} on this receipt${edit ? ' without this payment' : ''}.`
        : `${who} owes ${peso.format(owed)}${edit ? ' without this payment' : ''}. The payment goes to ${customer.startOwed > 0 ? 'their starting balance first, then ' : ''}their oldest unpaid receipts first.`,
      submit: edit ? 'Save changes' : 'Record payment',
      fields: [
        { name: 'amount', label: 'Amount received (₱)', type: 'number', step: '0.01', min: '0.01', value: (edit ? edit.amount : owed).toFixed(2), required: true, inputmode: 'decimal', full: true },
        { name: 'method', label: 'Paid with', type: 'select', full: true, value: edit ? edit.method : 'cash', options: [{ value: 'cash', label: 'Cash' }, { value: 'gcash', label: 'GCash' }] },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. GCash reference number', value: edit ? edit.note : null },
      ],
      extra: (inputs) => dateField(inputs, edit),
      onSubmit: (v) => mutate('POST', '/api/payments', asEdit({ ...v, customerId: customer ? customer.id : '', receiptId: receipt ? receipt.id : '' }, edit),
        edit ? 'Payment corrected' : `Payment of ${peso.format(Number(v.amount))} recorded`),
    });
  }

  // ------------------------------------------------------------------ receipts list

  $('history-search').addEventListener('input', () => { pages.history = PAGE; renderHistory(); });
  for (const b of document.querySelectorAll('[data-filter]')) {
    b.addEventListener('click', () => { state.historyFilter = b.dataset.filter; pages.history = PAGE; renderHistory(); });
  }

  function renderHistory() {
    const q = $('history-search').value.trim();
    const found = state.data.receipts.filter((r) => matches(q, '#' + r.id, r.customer, r.staff, r.time, itemsText(r), r.note, r.lines.map((l) => l.remark).join(' ')));
    const unpaid = found.filter((r) => r.balance > 0);
    const counts = { all: found.length, unpaid: unpaid.length, paid: found.length - unpaid.length };
    for (const b of document.querySelectorAll('[data-filter]')) {
      b.setAttribute('aria-checked', String(b.dataset.filter === state.historyFilter));
      b.querySelector('.chip-count').textContent = count.format(counts[b.dataset.filter]);
    }
    const filtered = state.historyFilter !== 'all';
    const rows = state.historyFilter === 'unpaid' ? unpaid : state.historyFilter === 'paid' ? found.filter((r) => !(r.balance > 0)) : found;
    const total = rows.reduce((s, r) => s + r.total, 0);
    const owed = rows.reduce((s, r) => s + r.balance, 0);
    $('history-summary').textContent = `${plural(rows.length, 'receipt')} · ${peso.format(total)}${owed > 0 ? ` · ${peso.format(owed)} unpaid` : ''}`;
    const shown = rows.slice(0, pages.history);
    clear($('history-table'), table([
      { label: '#', render: (r) => r.id, cls: 'muted num', sm: 'hide' },
      { label: 'Date', render: (r) => fmtTime(r.time), sm: 'hide', cls: 'nowrap' },
      { label: 'Customer', render: (r) => r.customer, sm: 'title' },
      { label: 'Items', render: (r) => itemsText(r), sm: 'hide' },
      { label: 'Total', right: true, render: (r) => peso.format(r.total), sm: 'figure' },
      { label: 'Payment', render: (r) => [payPill(r.status), r.balance > 0 ? h('div', { class: 'cell-sub' }, `${peso.format(r.balance)} to pay`) : null], sm: 'hide' },
      { label: 'Sold by', render: (r) => r.staff, cls: 'secondary', sm: 'hide' },
      { label: 'Actions', right: true, cls: 'actions', render: (r) => {
        const cust = byId(state.data.customers, r.customerId);
        return rowMenu(r.balance > 0 ? { label: 'Record payment', primary: true, onClick: () => paymentDialog({ receipt: r }) } : { label: 'View', onClick: () => showReceipt(r.id) }, [
          { label: 'View receipt', onClick: () => showReceipt(r.id) },
          r.balance > 0 ? { label: 'Record payment', onClick: () => paymentDialog({ receipt: r }) } : null,
          cust && cust.tanksOwed > 0 ? { label: 'Collect tank', onClick: () => returnDialog(cust) } : null,
          { label: 'Edit receipt', onClick: () => editReceipt(r) },
          { label: 'Print', onClick: () => printReceipt(r) },
          { label: 'Void receipt…', danger: true, onClick: () => voidReceipt(r) },
        ], `receipt #${r.id}`);
      } },
    ], shown, q || filtered ? 'No receipts match.' : 'No receipts yet.',
    (r) => `#${r.id} · ${itemsText(r)} · ${fmtTime(r.time)} · ${r.staff} · ${owedText(r)}`,
    (r) => showReceipt(r.id)),
    moreButton('history', shown.length, rows.length, renderHistory));
  }

  // ------------------------------------------------------------------ customers

  $('customers-search').addEventListener('input', renderCustomers);
  $('add-customer').addEventListener('click', () => customerDialog());
  for (const b of document.querySelectorAll('[data-cfilter]')) {
    b.addEventListener('click', () => { state.customerFilter = b.dataset.cfilter; renderCustomers(); });
  }

  // Starts a new sale for this customer (leaves a receipt being edited alone).
  function saleFor(c) {
    if (state.sale.editing) return toast('Finish or cancel the receipt being edited first', true);
    go('sell');
    chooseCustomer(c.id);
  }

  function renderCustomers() {
    const q = $('customers-search').value.trim();
    const all = state.data.customers;
    const found = all.filter((c) => matches(q, c.id, fullName(c), c.contact, c.address));
    const pass = { all: () => true, owes: (c) => c.balance > 0, tanks: (c) => c.tanksOwed > 0 };
    for (const b of document.querySelectorAll('[data-cfilter]')) {
      b.setAttribute('aria-checked', String(b.dataset.cfilter === state.customerFilter));
      b.querySelector('.chip-count').textContent = count.format(found.filter(pass[b.dataset.cfilter]).length);
    }
    const rows = found.filter(pass[state.customerFilter] || pass.all);
    const owing = all.filter((c) => c.balance > 0);
    const owed = owing.reduce((s, c) => s + c.balance, 0);
    const tanksOut = all.reduce((n, c) => n + c.tanksOwed, 0);
    $('customers-summary').textContent = `${plural(all.length, 'customer')}${owed > 0 ? ` · ${peso.format(owed)} unpaid by ${plural(owing.length, 'customer')}` : ''}${tanksOut ? ` · ${plural(tanksOut, 'tank')} not returned` : ''}`;
    clear($('customers-table'), table([
      { label: '#', render: (c) => c.id, cls: 'muted num', sm: 'hide' },
      { label: 'Name', render: (c) => fullName(c), sm: 'title' },
      { label: 'Contact', render: (c) => c.contact || '—', sm: 'hide', cls: 'nowrap secondary' },
      { label: 'Address', render: (c) => c.address || '—', sm: 'hide' },
      { label: 'Last purchase', render: (c) => c.lastPurchase ? fmtTime(c.lastPurchase) : '—', sm: 'hide', cls: 'nowrap' },
      { label: 'Tanks out', right: true, render: (c) => c.tanksOwed ? h('span', { class: 'status low', title: tanksText(c) }, h('span', { class: 'i', 'aria-hidden': 'true' }, '▲'), `${count.format(c.tanksOwed)} out`) : '—', sm: 'hide' },
      { label: 'We keep', right: true, render: (c) => c.keptTanks ? h('span', { title: keptText(c) }, count.format(c.keptTanks)) : '—', sm: 'hide' },
      { label: 'Balance', right: true, render: (c) => c.balance > 0 ? h('span', { class: 'owes-text' }, peso.format(c.balance)) : '—', sm: 'figure' },
      { label: 'Actions', right: true, cls: 'actions', render: (c) => rowMenu(
        c.balance > 0 ? { label: 'Collect payment', primary: true, onClick: () => paymentDialog({ customer: c }) }
          : c.tanksOwed > 0 ? { label: 'Collect tank', onClick: () => returnDialog(c) }
          : { label: 'Edit', onClick: () => customerDialog(c) }, [
          c.balance > 0 ? { label: 'Collect payment', onClick: () => paymentDialog({ customer: c }) } : null,
          c.tanksOwed > 0 ? { label: 'Collect tank', onClick: () => returnDialog(c) } : null,
          { label: 'Kept tanks', onClick: () => keptDialog(c) },
          { label: 'Edit customer', onClick: () => customerDialog(c) },
          { label: 'Start a sale', onClick: () => saleFor(c) },
          { label: 'Delete customer…', danger: true, onClick: () => deleteCustomer(c) },
        ], fullName(c)) },
    ], rows, q || state.customerFilter !== 'all' ? 'No customers match.' : 'No customers yet.',
    (c) => [c.contact, lastText(c), c.tanksOwed ? `owes ${plural(c.tanksOwed, 'tank')}` : null, c.keptTanks ? `we keep ${plural(c.keptTanks, 'tank')}` : null].filter(Boolean).join(' · ')));
  }

  // A customer's own tanks kept at the store: they leave some, or take some back. (Using them as the empties on a sale is on the receipt.)
  function keptDialog(c, edit) {
    if (!needProducts()) return;
    if (!c) return toast('That customer was deleted.', true);
    const kept = c.kept || [];
    return formDialog({
      title: editTitle(edit, `${fullName(c)}’s own tanks`),
      message: kept.length ? `The store keeps ${keptText(c)} for them. These aren’t the store’s stock.` : 'The store keeps none of their tanks. Record tanks they leave here; they aren’t the store’s stock.',
      submit: edit ? 'Save changes' : 'Save',
      fields: [
        { name: 'kind', label: 'What happened', type: 'select', full: true, value: edit ? edit.type : 'in', options: [{ value: 'in', label: 'They left tanks with us' }].concat(kept.length || edit ? [{ value: 'back', label: 'We gave tanks back to them' }] : []) },
        { name: 'productId', label: 'Tank', type: 'select', full: true, value: edit ? edit.productId : (kept[0] || {}).productId || state.data.products[0].id, options: productOptions((x) => x.label) },
        { name: 'qty', label: 'How many', type: 'number', min: 1, max: 1000, step: 1, value: edit ? edit.qty : 1, required: true, inputmode: 'numeric' },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. to refill on Saturday', value: edit ? edit.note : null },
      ],
      extra: (inputs) => {
        const recent = edit ? [] : state.data.deposits.filter((d) => d.customerId === c.id).slice(0, 5);
        return [dateField(inputs, edit), recent.length ? h('div', { class: 'moves kept-recent' }, h('h4', { class: 'minor-head' }, 'Recorded lately'),
          recent.map((d) => h('div', { class: 'move' },
            h('div', {}, d.kind === 'back' ? `Gave back ${d.qty} × ${d.product}` : `Left ${d.qty} × ${d.product} with us`),
            h('div', {}),
            h('div', { class: 'meta' }, h('span', {}, [fmtTime(d.time), d.staff, d.note].filter(Boolean).join(' · ')),
              changeButtons('deposit', d.id, d.kind === 'back' ? `giving back ${d.qty} × ${d.product}` : `${fullName(c)} leaving ${d.qty} × ${d.product}`, d.staff, d.time))))) : null];
      },
      onSubmit: (v) => mutate('POST', `/api/customers/${encodeURIComponent(c.id)}/deposits`, asEdit(v, edit),
        edit ? 'Kept tanks corrected' : v.kind === 'back' ? `Gave back ${plural(Number(v.qty), 'tank')} to ${fullName(c)}` : `Keeping ${plural(Number(v.qty), 'tank')} for ${fullName(c)}`),
    });
  }

  // Empties brought back by a customer who took tanks without one. Another brand is accepted (say so in the remark).
  function returnDialog(c, edit) {
    if (!c) return toast('That customer was deleted.', true);
    const owes = c.tanksOwed + (edit ? edit.qty : 0);
    if (!owes) return toast(`${fullName(c)} doesn’t owe any tanks`, true);
    const first = byId(state.data.products, edit ? edit.productId : (c.owedTanks[0] || {}).productId) || state.data.products[0];
    // Generic (no brand) or another brand we don't sell, in each size they owe: "generic:<product id>".
    const owedSizes = [];
    for (const o of c.owedTanks.concat(edit ? [{ productId: edit.productId }] : [])) {
      const x = byId(state.data.products, o.productId);
      if (x && !owedSizes.some((y) => y.weight === x.weight)) owedSizes.push(x);
    }
    const looseOptions = owedSizes.flatMap((x) => [{ value: 'generic:' + x.id, label: `Generic (no brand) ${kg(x.weight)}` },
      { value: 'other:' + x.id, label: `Other brand ${kg(x.weight)}: type it` }]);
    formDialog({
      title: editTitle(edit, `Collect tank from ${fullName(c)}`),
      message: edit ? `Without this return, ${fullName(c)} owes ${plural(owes, 'tank')}.`
        : `Owes ${plural(c.tanksOwed, 'tank')}: ` + c.owedTanks.map((o) => `${o.qty} × ${o.product} (${owedFrom(o)})`).join('; ') + '.',
      submit: edit ? 'Save changes' : 'Record return',
      fields: [
        { name: 'productId', label: 'Empty tank brought back', type: 'select', full: true,
          value: edit && edit.emptyKind ? `${edit.emptyKind}:${edit.productId}` : first.id, options: productOptions((x) => x.label).concat(looseOptions) },
        { name: 'qty', label: 'How many', type: 'number', min: 1, max: owes, step: 1, value: edit ? edit.qty : c.tanksOwed, required: true, inputmode: 'numeric' },
        { name: 'remark', label: 'Remark', full: true, placeholder: 'e.g. brought Solane instead of Petron; dented', value: edit ? edit.remark : null },
      ],
      extra: (inputs) => {
        const hint = h('p', { class: 'warn-note', hidden: true });
        inputs.emptyBrand = h('input', { class: 'input', name: 'emptyBrand', maxlength: 40, placeholder: 'e.g. Shine', autocomplete: 'off', value: edit ? edit.emptyBrand || null : null });
        const brandField = h('label', { class: 'field' }, h('span', {}, 'Brand of the empty'), inputs.emptyBrand);
        const check = () => {
          brandField.hidden = !inputs.productId.value.startsWith('other:');
          const x = byId(state.data.products, inputs.productId.value);
          const brands = new Set(c.owedTanks.map((o) => byId(state.data.products, o.productId)?.brand.toLowerCase()));
          hint.hidden = !x || brands.has(x.brand.toLowerCase());
          hint.textContent = x ? `${x.brand} isn’t the brand they took. That’s fine; add a remark about it.` : '';
        };
        inputs.productId.addEventListener('change', check);
        check();
        return [brandField, hint];
      },
      onSubmit: (v) => {
        const [kind, pid] = v.productId.includes(':') ? v.productId.split(':') : ['', v.productId];
        if (kind === 'other' && !v.emptyBrand) throw new Error('Type the brand of the empty.');
        const body = { ...v, productId: pid, emptyKind: kind, emptyBrand: kind === 'other' ? v.emptyBrand : '' };
        return mutate('POST', `/api/customers/${encodeURIComponent(c.id)}/returns`, asEdit(body, edit), edit ? 'Return corrected' : `${fullName(c)} returned ${plural(Number(v.qty), 'tank')}`);
      },
    });
  }

  function customerDialog(c) {
    return formDialog({
      title: c ? `Edit customer #${c.id}` : 'New customer',
      submit: c ? 'Save changes' : 'Add customer',
      fields: [
        { name: 'firstName', label: 'First name', value: c && c.firstName, required: true },
        { name: 'lastName', label: 'Last name', value: c && c.lastName, required: true },
        { name: 'contact', label: 'Contact no.', value: c && c.contact, type: 'tel', inputmode: 'tel', placeholder: '09XX XXX XXXX', full: true },
        { name: 'address', label: 'Address', value: c && c.address, full: true },
      ],
      onSubmit: async (v) => {
        if (c) {
          await mutate('PUT', '/api/customers/' + c.id, v, 'Customer updated');
          return c.id;
        }
        const r = await mutate('POST', '/api/customers', v, `Added ${v.firstName} ${v.lastName}`);
        return r.id;
      },
    });
  }

  async function deleteCustomer(c) {
    const ok = await confirmDialog(`Delete ${fullName(c)}?`, 'This removes the customer record. Customers with receipts, starting balances or kept tanks can’t be deleted.');
    if (ok) mutate('DELETE', '/api/customers/' + c.id, null, 'Customer deleted').catch((e) => toast(e.message, true));
  }

  // ------------------------------------------------------------------ products

  $('add-product').addEventListener('click', () => productDialog());

  function renderProducts() {
    const all = state.data.products;
    const low = all.filter((p) => p.status !== 'ok').length;
    $('products-summary').textContent = `${plural(all.length, 'product')}${low ? ` · ${low} need${low === 1 ? 's' : ''} refilling` : ''} · price changes apply to new receipts`;
    clear($('products-table'), table([
      { label: '#', render: (p) => p.id, cls: 'muted num', sm: 'hide' },
      { label: 'Brand', render: (p) => p.brand, sm: 'title' },
      { label: 'Weight', right: true, render: (p) => kg(p.weight), sm: 'hide' },
      { label: 'Refill price', right: true, render: (p) => peso.format(p.price), sm: 'figure' },
      { label: 'Swap fee', right: true, render: (p) => p.swapFee ? peso.format(p.swapFee) : h('span', { class: 'muted' }, 'None'), sm: 'hide' },
      { label: 'Refill cost', right: true, render: (p) => p.refillCost ? peso.format(p.refillCost) : h('span', { class: 'muted' }, 'Not set'), sm: 'hide' },
      { label: 'Margin', right: true, render: (p) => p.refillCost ? peso.format(p.price - p.refillCost) : '—', sm: 'hide' },
      { label: 'Sold', right: true, render: (p) => count.format(p.sold), sm: 'hide' },
      { label: 'With load', right: true, render: (p) => count.format(p.loaded), sm: 'hide' },
      { label: 'Status', render: (p) => stockPill(p), sm: 'hide' },
      { label: 'Actions', right: true, cls: 'actions', render: (p) => rowMenu({ label: 'Edit', onClick: () => productDialog(p) }, [
        { label: 'Edit product', onClick: () => productDialog(p) },
        { label: 'Count stock', onClick: () => adjustDialog(p) },
        { label: 'Delete product…', danger: true, onClick: () => deleteProduct(p) },
      ], p.label) },
    ], all, 'No LPG products yet.',
    (p) => `${kg(p.weight)} · swap fee ${p.swapFee ? peso.format(p.swapFee) : 'none'} · refill cost ${p.refillCost ? peso.format(p.refillCost) : 'not set'} · ${count.format(p.sold)} sold · ${stockText(p)}`));
  }

  function productDialog(p) {
    return formDialog({
      title: p ? `Edit ${p.label}` : 'New LPG product',
      message: p ? 'Price changes apply to new receipts only; past receipts keep their prices.' : null,
      submit: p ? 'Save changes' : 'Add product',
      fields: [
        { name: 'brand', label: 'Brand', value: p && p.brand, required: true, full: true, placeholder: 'e.g. Petron Gasul' },
        { name: 'weight', label: 'Weight (kg)', type: 'number', step: '0.1', min: '0.1', value: p && p.weight, required: true, inputmode: 'decimal' },
        { name: 'reorderLevel', label: 'Reorder level', type: 'number', step: 1, min: 0, value: p ? p.reorderLevel : 5, inputmode: 'numeric',
          help: 'Low when tanks with load drop to this' },
        { name: 'price', label: 'Refill price (₱)', type: 'number', step: '0.01', min: '0.01', value: p && p.price, required: true, inputmode: 'decimal',
          help: 'Price per tank' },
        { name: 'swapFee', label: 'Swap fee (₱)', type: 'number', step: '0.01', min: '0', value: p && p.swapFee ? p.swapFee : '', inputmode: 'decimal',
          help: 'Extra per tank when the empty is another brand' },
        { name: 'refillCost', label: 'Refill cost (₱)', type: 'number', step: '0.01', min: '0', value: p && p.refillCost ? p.refillCost : '', inputmode: 'decimal',
          help: 'What the refiller charges per tank' },
        { name: 'tankCost', label: 'New tank cost (₱)', type: 'number', step: '0.01', min: '0', value: p && p.tankCost ? p.tankCost : '', inputmode: 'decimal',
          help: 'What you pay when you buy a new tank' },
        ...(p ? [] : [
          { name: 'openingLoaded', label: 'With load on hand now', type: 'number', step: 1, min: 0, value: 0, inputmode: 'numeric' },
          { name: 'openingEmpty', label: 'Empty tanks on hand now', type: 'number', step: 1, min: 0, value: 0, inputmode: 'numeric' },
        ]),
      ],
      onSubmit: (v) => p
        ? mutate('PUT', '/api/products/' + p.id, v, 'Product updated')
        : mutate('POST', '/api/products', v, `Added ${v.brand}`),
    });
  }

  async function deleteProduct(p) {
    const ok = await confirmDialog(`Delete ${p.label}?`, 'Products that appear on receipts can’t be deleted.');
    if (ok) mutate('DELETE', '/api/products/' + p.id, null, 'Product deleted').catch((e) => toast(e.message, true));
  }

  // ------------------------------------------------------------------ inventory

  $('refill-send').addEventListener('click', () => sendRefillDialog());
  $('refill-receive').addEventListener('click', () => receiveRefillDialog());
  $('refill-record').addEventListener('click', () => recordRefillDialog());
  $('stock-purchase').addEventListener('click', () => purchaseDialog());
  $('stock-adjust').addEventListener('click', () => adjustDialog());
  $('add-supplier').addEventListener('click', () => supplierDialog());

  const MOVE_LABEL = {
    delivery: 'Delivery', purchase: 'Bought new tanks', count: 'Stock count', damaged: 'Write-off', other: 'Adjustment',
    condition: 'Tank condition', dispose: 'Disposed', 'refill-send': 'Sent to refiller', 'refill-receive': 'Back from refiller',
    'refill-reject': 'Returned unfilled',
  };
  // Tanks with load carry the colour; empties and refiller trips are routine, so they stay neutral.
  const signed = (n, word) => n === 0 ? null
    : h('span', { class: word === 'with load' ? (n > 0 ? 'delta-pos' : 'delta-neg') : 'secondary' }, `${n > 0 ? '+' : '−'}${Math.abs(n)} ${word}`);
  const moveDeltas = (m) => [signed(m.loaded, 'with load'), signed(m.empty, 'empty'), signed(m.damaged, 'damaged'), signed(m.atRefiller, 'at refiller')]
    .filter(Boolean).flatMap((x, i) => (i ? [' ', x] : [x]));
  const stockLine = (p) => `${p.loaded} with load · ${p.empty} empty${p.atRefiller ? ` · ${p.atRefiller} at refiller` : ''}`;

  for (const b of document.querySelectorAll('[data-inv]')) {
    b.addEventListener('click', () => {
      state.invTab = b.dataset.inv;
      try { localStorage.setItem('lpg-inv-tab', state.invTab); } catch (_) { /* storage blocked */ }
      showInvTab();
    });
  }
  function showInvTab() {
    for (const b of document.querySelectorAll('[data-inv]')) {
      const on = b.dataset.inv === state.invTab;
      b.setAttribute('aria-selected', String(on));
      b.tabIndex = on ? 0 : -1;
    }
    for (const el of document.querySelectorAll('[data-inv-panel]')) el.hidden = el.dataset.invPanel !== state.invTab;
  }

  function renderInventory() {
    const { products, suppliers, refills, movements } = state.data;
    showInvTab();
    const tabCount = (id, text, warn) => { const el = $(id); el.textContent = text || ''; el.hidden = !text; el.classList.toggle('warn', !!warn); };
    const tanksOutAll = state.data.customers.reduce((n, c) => n + (c.tanksOwed || 0), 0);
    const openTrips = refills.filter((r) => r.open || r.owed > 0).length;
    tabCount('inv-count-out', tanksOutAll ? count.format(tanksOutAll) : '');
    tabCount('inv-count-refiller', openTrips ? plural(openTrips, 'trip') : '');
    tabCount('inv-count-suppliers', suppliers.length ? count.format(suppliers.length) : '', suppliers.some((s) => s.owed > 0));
    tabCount('inv-count-staff', state.data.staffMoney.total > 0 ? UI.pesoRound.format(state.data.staffMoney.total) : '', true);
    tabCount('inv-count-history', movements.length ? count.format(movements.length) : '');
    const attention = products.filter((p) => p.status !== 'ok');
    clear($('inventory-alert'), attention.length ? h('div', { class: 'alert-banner', role: 'status' },
      h('span', { class: 'i', 'aria-hidden': 'true' }, '⚠'),
      h('div', {},
        h('strong', {}, `${plural(attention.length, 'product')} need${attention.length === 1 ? 's' : ''} refilling`),
        h('p', {}, attention.map((p) => `${p.label}: ${p.loaded <= 0 ? 'none with load' : p.loaded + ' with load'}${p.empty ? `, ${p.empty} empty to send` : ''}`).join(' · '))),
      attention.some((p) => p.empty > 0) ? h('button', { type: 'button', class: 'btn small', onclick: () => sendRefillDialog((attention.find((p) => p.empty > 0 && p.supplierId) || {}).supplierId) }, 'Send empties now') : null) : null);

    const sum = (k) => products.reduce((n, p) => n + Math.max(0, p[k]), 0);
    const keptAll = state.data.customers.reduce((n, c) => n + (c.keptTanks || 0), 0);
    $('inventory-summary').textContent = `${count.format(sum('loaded'))} with load · ${count.format(sum('empty'))} empty · ${count.format(sum('atRefiller'))} at refiller · ${count.format(sum('loaded') + sum('empty') + sum('atRefiller'))} tanks in all`
      + (keptAll ? ` · plus ${plural(keptAll, 'customer tank')} we keep` : '');
    // Totals per tank size (kg), across brands.
    const sizes = [];
    for (const p of [...products].sort((a, b) => a.weight - b.weight)) {
      let s = sizes.find((x) => x.weight === p.weight);
      if (!s) sizes.push(s = { weight: p.weight, loaded: 0, empty: 0, atRefiller: 0 });
      s.loaded += Math.max(0, p.loaded);
      s.empty += Math.max(0, p.empty);
      s.atRefiller += Math.max(0, p.atRefiller);
    }
    for (const customer of state.data.customers) {
      for (const owed of customer.owedTanks || []) {
        const product = byId(state.data.products, owed.productId);
        const size = product && sizes.find((s) => s.weight === product.weight);
        if (size) size.withCustomers = (size.withCustomers || 0) + owed.qty;
      }
    }
    Charts.sizeTable($('size-totals'), sizes);
    const looseList = state.data.looseEmpties || [];
    clear($('loose-empties'), looseList.length ? h('table', { class: 'rtable' },
      h('thead', {}, h('tr', {}, h('th', {}, 'Size'), h('th', {}, 'Empty'), h('th', { class: 'right' }, 'Tanks'))),
      h('tbody', {}, looseList.map((x) => h('tr', {},
        h('td', { class: 'sm-title' }, kg(x.weight)), h('td', { class: 'sm-detail' }, x.kind === 'generic' ? 'Generic (no brand)' : `${x.label} (other brand)`),
        h('td', { class: 'sm-hide' }, x.kind === 'generic' ? 'Generic (no brand)' : `${x.label} (other brand)`),
        h('td', { class: 'right num sm-figure' }, count.format(x.qty))))))
      : h('div', { class: 'empty' }, 'None yet. Choose “Generic” or “Other brand” as the empty on a sale or tank return.'));
    renderStaffMoney();

    const out = state.data.customers.filter((c) => c.tanksOwed > 0).sort((a, b) => b.tanksOwed - a.tanksOwed);
    $('tanks-out-sub').textContent = out.length ? `${plural(out.reduce((n, c) => n + c.tanksOwed, 0), 'tank')} with ${plural(out.length, 'customer')}` : 'Customers who took a tank without an empty';
    clear($('tanks-out'),
      out.length ? h('div', { class: 'list' }, out.map((c) => h('div', { class: 'supplier-row' },
        h('div', {}, h('strong', {}, fullName(c)),
          h('div', { class: 'meta' }, `${tanksText(c)} · since ${c.owedTanks[0].receiptId === 'start' ? 'before the start date' : fmtTime(c.owedTanks[0].time)}`),
          c.contact ? h('div', { class: 'meta' }, c.contact) : null),
        actions(h('button', { class: 'btn small', onclick: () => returnDialog(c) }, 'Collect tank'))))) : h('div', { class: 'empty' }, 'Every tank has come back.'),
      keptAll ? h('h3', { class: 'minor-head' }, 'Customers’ own tanks we keep') : null,
      keptAll ? h('div', { class: 'list' }, state.data.customers.filter((c) => c.keptTanks > 0).map((c) => h('div', { class: 'supplier-row' },
        h('div', {}, h('strong', {}, fullName(c)), h('div', { class: 'meta' }, keptText(c))),
        actions(h('button', { class: 'btn small ghost', onclick: () => keptDialog(c) }, 'Kept tanks'))))) : null,
      state.data.returns.length ? h('h3', { class: 'minor-head' }, 'Recently returned') : null,
      state.data.returns.length ? h('div', { class: 'moves' }, state.data.returns.slice(0, 8).map((t) => h('div', { class: 'move' },
        h('div', {}, h('strong', {}, t.customer), ` · ${t.qty} × ${t.product}`),
        h('div', { class: 'num secondary' }, `+${t.qty} empty`),
        h('div', { class: 'meta' }, h('span', {}, [fmtTime(t.time), t.staff, t.remark].filter(Boolean).join(' · ')),
          changeButtons('return', t.id, `${t.customer} returning ${t.qty} × ${t.product}`, t.staff, t.time, 'so they owe the tank again'))))) : null);

    // Group the stock table by supplier, so each refiller's brands sit together.
    const groups = suppliers.map((s) => ({ s, items: products.filter((p) => p.supplierId === s.id) })).filter((g) => g.items.length);
    const loose = products.filter((p) => !p.supplierId);
    if (loose.length) groups.push({ s: null, items: loose });
    const cols = 6;
    clear($('inventory-table'), products.length ? h('table', { class: 'rtable stock-table' },
      h('thead', {}, h('tr', {}, ['Product', 'With load', 'Empty', 'At refiller', 'Status', ''].map((t, i) => h('th', { class: i && i < 4 ? 'right' : null }, t)))),
      h('tbody', {}, groups.flatMap((g) => [
        h('tr', { class: 'group-row' }, h('td', { colspan: cols },
          g.s ? [h('strong', {}, g.s.name), g.s.contact ? h('span', { class: 'muted' }, ` · ${g.s.contact}`) : null,
            h('button', { class: 'btn small ghost', onclick: () => sendRefillDialog(g.s.id) }, 'Send empties')]
            : [h('strong', {}, 'No supplier set'), h('span', { class: 'muted' }, ' · add one in the Suppliers tab')])),
        ...g.items.map((p) => h('tr', {},
          h('td', { class: 'sm-title' }, p.label),
          h('td', { class: 'right num sm-hide' }, count.format(p.loaded)),
          h('td', { class: 'right num sm-hide' }, count.format(p.empty)),
          h('td', { class: 'right num sm-hide' }, count.format(p.atRefiller)),
          h('td', { class: 'sm-figure' }, stockPill(p)),
          h('td', { class: 'actions' }, actions(
            h('button', { class: 'btn small ghost', onclick: () => adjustDialog(p) }, 'Count'))),
          h('td', { class: 'sm-detail' }, `${stockLine(p)} · reorder at ${p.reorderLevel}`))),
      ]))) : h('div', { class: 'empty' }, 'No LPG products yet. Add them in the Products tab.'));

    // Trips still out, plus finished ones the refiller hasn't been fully paid for; then the last few finished ones,
    // so a refill entered by mistake can be found and undone.
    const trips = refills.filter((r) => r.open || r.owed > 0);
    const done = refills.filter((r) => !r.open && !(r.owed > 0) && !r.start).slice(0, 5);
    const tripWhat = (r) => `refill trip #${r.id} to ${r.supplier}`;
    const tripUndo = (r) => r.start ? null : changeButtons('refill', r.id, tripWhat(r), r.staff, r.time, 'with its tanks and every payment made on it');
    clear($('refills'), trips.length ? h('div', { class: 'list' }, trips.map((r) => {
      const waiting = r.items.filter((i) => i.outstanding > 0);
      const back = r.items.reduce((n, i) => n + i.received + i.rejected, 0);
      return h('div', { class: 'refill-row' },
        h('div', {},
          h('div', {}, h('strong', {}, r.start ? `${r.supplier} · already there on the start date` : `Trip #${r.id} · ${r.supplier}`)),
          h('div', { class: 'meta' }, `${r.start ? 'Starting balance' : `Sent ${fmtTime(r.time)} by ${r.staff}`}${r.note && !r.start ? ' · ' + r.note : ''}${back ? ` · ${back} back so far` : ''}`),
          waiting.length ? h('div', { class: 'meta' }, 'Waiting: ' + waiting.map((i) => `${i.outstanding} × ${i.product}`).join(', ')) : h('div', { class: 'meta' }, 'All tanks back'),
          r.cost ? h('div', { class: 'meta' }, `Bill ${peso.format(r.cost)} · paid ${peso.format(r.paid)}${r.paidStaff ? ` (${peso.format(r.paidStaff)} staff money)` : ''}`,
            r.owed > 0 ? h('span', { class: 'owes-text' }, ` · ${peso.format(r.owed)} still to pay`) : null) : null),
        actions(
          tripUndo(r),
          r.owed > 0 ? h('button', { class: 'btn small', onclick: () => payRefillerDialog(r) }, 'Pay refiller') : null,
          r.open ? h('button', { class: 'btn small primary', onclick: () => receiveRefillDialog(r.id) }, 'Receive') : null));
    })) : h('div', { class: 'empty' }, 'No tanks at the refiller right now.'),
    done.length ? h('h3', { class: 'minor-head' }, 'Finished recently') : null,
    done.length ? h('div', { class: 'list' }, done.map((r) => h('div', { class: 'refill-row' },
      h('div', {},
        h('strong', {}, `Trip #${r.id} · ${r.supplier}`),
        h('div', { class: 'meta' }, `${fmtTime(r.time)} by ${r.staff} · ${r.items.map((i) => `${i.received} × ${i.product}${i.rejected ? ` (+${i.rejected} unfilled)` : ''}`).join(', ')}`),
        r.cost ? h('div', { class: 'meta' }, `Bill ${peso.format(r.cost)} · paid`) : null),
      actions(tripUndo(r))))) : null);

    const assigned = new Set(suppliers.flatMap((s) => s.brands.map((b) => b.toLowerCase())));
    const unassigned = state.data.brands.filter((b) => !assigned.has(b.toLowerCase()));
    clear($('suppliers'),
      unassigned.length ? h('p', { class: 'warn-note' }, `No supplier yet for: ${unassigned.join(', ')}`) : null,
      suppliers.length ? h('div', { class: 'list' }, suppliers.map((s) => h('div', { class: 'supplier-row' },
        h('div', {},
          h('strong', {}, s.name),
          h('div', { class: 'meta' }, [s.contact, s.brands.join(', ')].filter(Boolean).join(' · ')),
          s.note ? h('div', { class: 'meta' }, s.note) : null,
          s.owed > 0 ? h('div', { class: 'meta owes-text' }, `Owes ${peso.format(s.owed)} (${plural(s.bills.length, 'bill')})`) : null),
        supplierActions(s)))) : h('div', { class: 'empty' }, 'No suppliers yet. Add who refills each brand.'));

    clear($('movements'), movements.length ? h('div', { class: 'moves' }, movements.map((m) => h('div', { class: 'move' },
      h('div', {}, h('strong', {}, MOVE_LABEL[m.type] || m.type), ' · ', m.product, m.refillId ? h('span', { class: 'muted' }, ` · trip #${m.refillId}`) : null),
      h('div', { class: 'num' }, moveDeltas(m)),
      h('div', { class: 'meta' }, h('span', {}, [fmtTime(m.time), m.staff, m.unitCost ? `${peso.format(m.unitCost)} each` : null, m.note].filter(Boolean).join(' · ')), moveUndo(m))))) : h('div', { class: 'empty' }, 'Nothing recorded yet.'));
  }

  // Pay when something is owed, else History (dashboard staff) or Edit; the rest in the ⋯ menu.
  function supplierActions(s) {
    const history = state.me && state.me.dashboard ? { label: 'History', onClick: () => supplierHistoryDialog(s) } : null;
    return rowMenu(s.owed > 0 ? { label: 'Pay', primary: true, onClick: () => paySupplierDialog(s) } : history || { label: 'Edit', onClick: () => supplierDialog(s) }, [
      s.owed > 0 ? { label: 'Pay', onClick: () => paySupplierDialog(s) } : null,
      history,
      { label: 'Send empties', onClick: () => sendRefillDialog(s.id) },
      { label: 'Edit supplier', onClick: () => supplierDialog(s) },
      { label: 'Delete supplier…', danger: true, onClick: () => deleteSupplier(s) },
    ], s.name);
  }

  // A refill's stock changes are undone with their trip; tanks received on a later day can be undone on their own,
  // so they show as at the refiller again.
  function moveUndo(m) {
    if (m.type.startsWith('refill-')) {
      const trip = state.data.refills.find((r) => r.id === m.refillId);
      const sentThen = m.type === 'refill-send' || (trip ? trip.time === m.time
        : state.data.movements.some((x) => x.refillId === m.refillId && x.type === 'refill-send' && x.time === m.time));
      if (trip && trip.start && m.type !== 'refill-send') {
        return changeButtons('receive', m.id, `receiving tanks from ${trip.supplier}`, m.staff, m.time, 'so they show as at the refiller again; anything paid then is taken out too');
      }
      return sentThen ? changeButtons('refill', m.refillId, `refill trip #${m.refillId}`, m.staff, m.time, 'with its tanks and every payment made on it')
        : changeButtons('receive', m.id, `receiving tanks on trip #${m.refillId}`, m.staff, m.time, 'so they show as at the refiller again; anything paid then is taken out too');
    }
    return changeButtons('movement', m.id, `this ${(MOVE_LABEL[m.type] || 'stock change').toLowerCase()} (${m.product})`, m.staff, m.time,
      m.type === 'purchase' ? 'with what was paid for them' : null);
  }

  const needProducts = () => {
    if (state.data.products.length) return true;
    toast('Add an LPG product first', true);
    return false;
  };
  const productOptions = (fmt) => state.data.products.map((x) => ({ value: x.id, label: fmt(x) }));

  function sendRefillDialog(supplierId, edit) {
    const { suppliers, products } = state.data;
    if (!suppliers.length) return toast('Add a supplier first (Inventory → Suppliers)', true);
    const sentBefore = (pid) => edit ? ((edit.items.find((i) => i.productId === pid) || {}).sent || 0) : 0;
    const sel = h('select', { class: 'input', name: 'supplierId' }, suppliers.map((s) => h('option', { value: s.id }, `${s.name} — ${s.brands.join(', ')}`)));
    if (supplierId) sel.value = supplierId;
    const rowsBox = h('div', { class: 'qty-rows' });
    const draw = () => {
      const list = products.filter((p) => p.supplierId === sel.value);
      clear(rowsBox, list.length ? list.map((p, i) => h('label', { class: 'qty-row' },
        h('span', {}, h('strong', {}, p.label), h('small', { class: 'muted' }, `${p.empty + sentBefore(p.id)} empty on hand`)),
        h('input', { type: 'hidden', name: 'productId.' + i, value: p.id }),
        h('input', { class: 'input num', type: 'number', name: 'qty.' + i, min: 0, max: p.empty + sentBefore(p.id), step: 1, value: edit ? sentBefore(p.id) : p.empty, inputmode: 'numeric', 'aria-label': `Empty ${p.label} tanks to send` })))
        : h('p', { class: 'muted' }, 'None of your products use this supplier’s brand yet.'));
    };
    sel.addEventListener('change', draw);
    draw();
    return openDialog({
      title: edit ? `Correct trip #${edit.id}` : 'Send empty tanks to the refiller',
      message: 'They leave the store now and show as “at refiller” until you receive them back.',
      submit: edit ? 'Save changes' : 'Send tanks',
      body: [h('div', { class: 'grid-2' }, h('label', { class: 'field' }, h('span', {}, 'Refiller'), sel), dateField(null, edit)), rowsBox,
        h('label', { class: 'field' }, h('span', {}, 'Note (optional)'), h('input', { class: 'input', name: 'note', maxlength: 120, placeholder: 'e.g. truck plate, delivery receipt no.', value: edit ? edit.note : null }))],
      onSubmit: (form) => {
        const body = formBody(form);
        body.items = rowsBox.querySelectorAll('input[type=number]').length;
        return mutate('POST', '/api/refills', asEdit(body, edit), edit ? 'Trip corrected' : 'Tanks sent to the refiller');
      },
    });
  }

  // edit: a receiving being corrected; its trip is offered as if those tanks were still at the refiller.
  function receiveRefillDialog(refillId, edit) {
    let open = state.data.refills.filter((r) => r.open);
    if (edit) {
      const trip = state.data.refills.find((r) => r.id === edit.refillId);
      if (!trip) return toast('That trip is too old to edit here. Undo it from Stock history and enter it again.', true);
      const back = (pid) => edit.items.find((i) => i.productId === pid) || { received: 0, rejected: 0, cost: 0 };
      const thisCost = edit.items.reduce((n, i) => n + i.received * i.cost, 0);
      const thisPaid = edit.paid.sales + edit.paid.owner + edit.paid.staff;
      open = [{ ...trip, owed: Math.max(0, round2(trip.owed - thisCost + thisPaid)),
        items: trip.items.map((i) => ({ ...i, outstanding: i.outstanding + back(i.productId).received + back(i.productId).rejected })) }];
    }
    if (!open.length) return toast('No tanks are at the refiller', true);
    const was = (pid) => (edit && edit.items.find((i) => i.productId === pid)) || null;
    const sel = h('select', { class: 'input' }, open.map((r) => h('option', { value: r.id }, `Trip #${r.id} · ${r.supplier} · sent ${fmtTime(r.time)}`)));
    if (refillId) sel.value = refillId;
    const rowsBox = h('div', { class: 'qty-rows' });
    const draw = () => {
      const r = open.find((x) => x.id === sel.value);
      const waiting = r.items.filter((i) => i.outstanding > 0);
      clear(rowsBox, h('div', { class: 'qty-row head four' }, h('span', {}), h('span', {}, 'Back with load'), h('span', {}, 'Unfilled'), h('span', {}, 'Cost each (₱)')),
        waiting.map((it, i) => {
          const p = byId(state.data.products, it.productId);
          return h('div', { class: 'qty-row four' },
            h('span', {}, h('strong', {}, it.product), h('small', { class: 'muted' }, `${it.outstanding} waiting`)),
            h('input', { type: 'hidden', name: 'productId.' + i, value: it.productId }),
            // On phones the column headings are hidden, so each box carries its own small label.
            h('label', {}, h('small', { class: 'm-only' }, 'Back with load'),
              h('input', { class: 'input num', type: 'number', name: 'received.' + i, min: 0, max: it.outstanding, step: 1, value: edit ? (was(it.productId) || {}).received || 0 : it.outstanding, inputmode: 'numeric', 'aria-label': `${it.product} back with load` })),
            h('label', {}, h('small', { class: 'm-only' }, 'Unfilled'),
              h('input', { class: 'input num', type: 'number', name: 'rejected.' + i, min: 0, max: it.outstanding, step: 1, value: edit ? (was(it.productId) || {}).rejected || 0 : 0, inputmode: 'numeric', 'aria-label': `${it.product} returned unfilled` })),
            h('label', {}, h('small', { class: 'm-only' }, 'Cost each (₱)'),
              h('input', { class: 'input num', type: 'number', name: 'cost.' + i, min: 0, step: '0.01', value: was(it.productId) && was(it.productId).cost ? was(it.productId).cost.toFixed(2) : p && p.refillCost ? p.refillCost.toFixed(2) : '', placeholder: '0.00', inputmode: 'decimal', 'aria-label': `Refill cost per ${it.product}` })));
        }));
      if (pay) pay.reset();
      updateCost();
    };
    // The bill follows the rows: tanks back with load × cost each. Unfilled tanks aren't charged.
    const costLine = h('div', { class: 'cost-line' });
    let cost = 0;
    const tripOwed = () => (open.find((x) => x.id === sel.value) || {}).owed || 0;
    function updateCost() {
      cost = 0;
      rowsBox.querySelectorAll('.qty-row.four:not(.head)').forEach((row) => {
        cost += (Number(row.querySelector('[name^="received."]').value) || 0) * (Number(row.querySelector('[name^="cost."]').value) || 0);
      });
      clear(costLine, h('span', {}, 'Refill bill'), h('strong', { class: 'num' }, peso.format(cost)),
        tripOwed() ? h('small', { class: 'muted' }, ` + ${peso.format(tripOwed())} still owed on this trip`) : null,
        zeroCostNote(rowsBox, 'received.'));
      if (pay) pay.refresh();
    }
    let pay = null;
    pay = paySource({}, () => round2(cost + tripOwed()), edit && edit.paid);
    rowsBox.addEventListener('input', updateCost);
    sel.addEventListener('change', draw);
    draw();
    return openDialog({
      title: edit ? `Correct tanks received on trip #${edit.refillId}` : 'Receive from the refiller',
      message: 'Tanks back with load go on sale. Tanks returned unfilled come back as empties (not charged); say why in the note. Anything not entered stays “at refiller”.',
      submit: edit ? 'Save changes' : 'Receive tanks',
      wide: true,
      body: [h('div', { class: 'grid-2' }, h('label', { class: 'field' }, h('span', {}, 'Trip'), sel), dateField(null, edit)), rowsBox, costLine, pay.el,
        h('label', { class: 'field' }, h('span', {}, 'Note (optional)'), h('input', { class: 'input', name: 'note', maxlength: 120, placeholder: 'e.g. delivery receipt no.', value: edit ? edit.note : null }))],
      onSubmit: (form) => {
        const body = formBody(form);
        body.items = rowsBox.querySelectorAll('input[name^="productId."]').length;
        return mutate('POST', `/api/refills/${encodeURIComponent(sel.value)}/receive`, asEdit(body, edit), edit ? 'Tanks received corrected' : 'Tanks received');
      },
    });
  }

  function payRefillerDialog(r, edit) {
    const before = edit ? round2(edit.paid.sales + edit.paid.owner + edit.paid.staff) : 0;
    const owed = round2(r.owed + before);
    formDialog({
      title: editTitle(edit, `Pay ${r.supplier}`),
      message: `Trip #${r.id}: bill ${peso.format(r.cost)}, ${peso.format(owed)} still to pay${edit ? ' without this payment' : ''}.`,
      submit: edit ? 'Save changes' : 'Record payment',
      extra: (inputs) => [paySource(inputs, owed, edit && edit.paid).el, dateField(inputs, edit), noteField(inputs, 'e.g. cash, bank transfer ref.', edit)],
      onSubmit: (v) => mutate('POST', `/api/refills/${encodeURIComponent(r.id)}/pay`, asEdit(v, edit), edit ? 'Payment corrected' : `Paid ${peso.format(paidTotal(v))} to ${r.supplier}`),
    });
  }

  // Tanks entered without a cost each would leave them off the supplier's bill: easy to miss, so say so.
  function zeroCostNote(rowsBox, qtyName) {
    const free = [...rowsBox.querySelectorAll(`[name^="${qtyName}"]`)].filter((q) => Number(q.value) > 0
      && !(Number(q.closest('.qty-row').querySelector('[name^="cost."]').value) > 0));
    return free.length ? h('small', { class: 'warn-text' }, `No cost each entered for ${plural(free.length, 'product')}, so the supplier’s bill won’t include ${free.length === 1 ? 'it' : 'them'}.`) : null;
  }

  const paidTotal = (v) => round2((Number(v.paidSales) || 0) + (Number(v.paidOwner) || 0) + (Number(v.paidStaff) || 0));
  const noteField = (inputs, placeholder, edit) => {
    inputs.note = h('input', { class: 'input', name: 'note', maxlength: 120, placeholder, value: edit ? edit.note : null });
    return h('label', { class: 'field' }, h('span', {}, 'Note (optional)'), inputs.note);
  };

  // Where the money paid to a supplier came from: the day's sales, the owner's personal money, and/or
  // a staff member's own pocket (the store owes it back to them), paid by cash, check or online. Whatever isn't
  // paid now is paid later. "From sales" follows what's due until it's typed in.
  // Adds method / paidSales / paidOwner / paidStaff / paidBy to the dialog's inputs (and form, by name).
  // init: what a record being corrected paid ({ sales, owner, staff, paidBy, method }), kept until the rows change.
  function paySource(inputs, due, init) {
    const owedNow = () => round2(typeof due === 'function' ? due() : due);
    const num = (el) => Number(el.value) || 0;
    const sales = h('input', { class: 'input num', name: 'paidSales', type: 'number', min: 0, step: '0.01', inputmode: 'decimal' });
    const staff = h('input', { class: 'input num', name: 'paidStaff', type: 'number', min: 0, step: '0.01', inputmode: 'decimal', placeholder: '0.00' });
    const owner = h('input', { class: 'input num', name: 'paidOwner', type: 'number', min: 0, step: '0.01', inputmode: 'decimal', placeholder: '0.00' });
    const method = h('select', { class: 'input', name: 'method' }, h('option', { value: 'cash' }, 'Cash'), h('option', { value: 'check' }, 'Check'),
      h('option', { value: 'online' }, 'Online (GCash, bank)'));
    const who = h('select', { class: 'input', name: 'paidBy' }, (state.data.staff.length ? state.data.staff : [state.me.username]).map((u) => h('option', { value: u }, u)));
    who.value = state.me.username;
    const whoField = h('label', { class: 'field' }, h('span', {}, 'Whose money'), who);
    const line = h('p', { class: 'cost-line' });
    let edited = !!init, keepInit = !!init;
    if (init) {
      sales.value = (init.sales || 0).toFixed(2);
      if (init.owner) owner.value = init.owner.toFixed(2);
      if (init.staff) staff.value = init.staff.toFixed(2);
      if (init.paidBy) who.value = init.paidBy;
      method.value = init.method || 'cash';
    }
    const refresh = () => {
      const d = owedNow();
      if (!edited) sales.value = Math.max(0, round2(d - num(owner) - num(staff))).toFixed(2);
      whoField.hidden = !(num(staff) > 0);
      const total = round2(num(sales) + num(owner) + num(staff));
      const left = round2(d - total);
      clear(line, h('span', {}, 'Total paid'), h('strong', { class: 'num' }, peso.format(total)),
        left > 0 ? h('small', { class: 'owes-text' }, ` · ${peso.format(left)} left to pay later`) : null,
        left < 0 ? h('small', { class: 'error-text' }, ` · ${peso.format(-left)} more than what’s owed`) : null,
        num(staff) > 0 ? h('small', { class: 'muted' }, ` · the store owes ${who.value} ${peso.format(num(staff))}`) : null);
    };
    sales.addEventListener('input', () => { edited = true; refresh(); });
    staff.addEventListener('input', refresh);
    owner.addEventListener('input', refresh);
    who.addEventListener('change', refresh);
    Object.assign(inputs, { method, paidSales: sales, paidOwner: owner, paidStaff: staff, paidBy: who });
    const el = h('fieldset', { class: 'pay-source' },
      h('legend', {}, 'Paid now'),
      h('label', { class: 'field' }, h('span', {}, 'Paid by'), method),
      h('div', { class: 'grid-2' },
        h('label', { class: 'field' }, h('span', {}, 'From sales (₱)'), sales),
        h('label', { class: 'field' }, h('span', {}, 'Owner’s personal money (₱)'), owner),
        h('label', { class: 'field' }, h('span', {}, 'Staff’s own money (₱)'), staff)),
      whoField, line);
    refresh();
    return { el, refresh, reset: () => { if (keepInit) keepInit = false; else edited = false; refresh(); } };
  }

  function purchaseDialog(p, edit) {
    if (!needProducts()) return;
    formDialog({
      title: editTitle(edit, 'Buy new tanks'),
      message: 'Brand-new tanks with load from the supplier (no empties swapped). Pay now, or pay part and the rest later.',
      submit: edit ? 'Save changes' : 'Add to stock',
      fields: [
        { name: 'productId', label: 'LPG product', type: 'select', full: true, value: (p || state.data.products[0]).id, options: productOptions((x) => `${x.label} (${x.loaded} with load)`) },
        { name: 'qty', label: 'Tanks bought', type: 'number', min: 1, step: 1, required: true, inputmode: 'numeric', value: edit ? edit.loaded : null },
        { name: 'unitCost', label: 'Cost per tank (₱)', type: 'number', min: 0, step: '0.01', inputmode: 'decimal', value: edit ? edit.unitCost : (p || state.data.products[0]).tankCost || '' },
      ],
      // Picking another product fills in its usual new-tank cost; what's paid follows the bill until typed in.
      extra: (inputs) => {
        const bill = () => round2((Number(inputs.qty.value) || 0) * (Number(inputs.unitCost.value) || 0));
        const total = h('p', { class: 'cost-line' });
        const pay = paySource(inputs, bill, edit && edit.paid);
        const show = () => {
          const x = byId(state.data.products, inputs.productId.value);
          clear(total, h('span', {}, 'Bill'), h('strong', { class: 'num' }, peso.format(bill())),
            x && x.supplier ? h('small', { class: 'muted' }, ` · owed to ${x.supplier} until paid`) : null);
          pay.refresh();
        };
        inputs.productId.addEventListener('change', () => {
          const x = byId(state.data.products, inputs.productId.value);
          if (x) inputs.unitCost.value = x.tankCost || '';
          show();
        });
        inputs.qty.addEventListener('input', show);
        inputs.unitCost.addEventListener('input', show);
        show();
        return [total, pay.el, dateField(inputs, edit), noteField(inputs, 'e.g. supplier, receipt no.', edit)];
      },
      onSubmit: (v) => mutate('POST', '/api/stock/purchase', asEdit(v, edit), edit ? 'Purchase corrected' : `Added ${v.qty} new tanks`),
    });
  }

  // Pays what a supplier is owed (refill trips and new tanks), oldest bills first.
  function paySupplierDialog(s, edit) {
    const owed = round2(s.owed + (edit ? edit.paid.sales + edit.paid.owner + edit.paid.staff : 0));
    formDialog({
      title: editTitle(edit, `Pay ${s.name}`),
      message: edit ? `Without this payment, ${s.name} is owed ${peso.format(owed)}. Oldest bills are paid first.`
        : `Owed ${peso.format(s.owed)}: ` + s.bills.map((b) => `${b.label} (${fmtTime(b.time)}) ${peso.format(b.owed)}`).join('; ') + '. Oldest bills are paid first.',
      submit: edit ? 'Save changes' : 'Record payment',
      extra: (inputs) => [paySource(inputs, owed, edit && edit.paid).el, dateField(inputs, edit), noteField(inputs, 'e.g. cash, bank transfer ref.', edit)],
      onSubmit: (v) => mutate('POST', `/api/suppliers/${encodeURIComponent(s.id)}/pay`, asEdit(v, edit), edit ? 'Payment corrected' : `Paid ${peso.format(paidTotal(v))} to ${s.name}`),
    });
  }

  // Read-only record of everything done with a supplier (staff with dashboard access only).
  async function supplierHistoryDialog(s) {
    let data;
    try {
      data = await api('GET', `/api/suppliers/${encodeURIComponent(s.id)}/history`);
    } catch (e) {
      if (e.status === 403) { state.me.dashboard = false; updateDashboardAccess(); renderInventory(); }
      return toast(e.message, true);
    }
    const from = h('input', { class: 'input', type: 'date', 'aria-label': 'From date' });
    const to = h('input', { class: 'input', type: 'date', 'aria-label': 'To date' });
    const kind = h('select', { class: 'input', 'aria-label': 'Show' },
      [['', 'Everything'], ['refill', 'Refill trips'], ['purchase', 'New tanks bought'], ['payment', 'Payments']].map(([value, label]) => h('option', { value }, label)));
    const totals = h('div', { class: 'history-totals' });
    const list = h('div', { class: 'moves' });
    const money = (label, value, cls) => h('div', {}, h('small', { class: 'muted' }, label), h('div', { class: 'num ' + (cls || '') }, peso.format(value)));
    const payer = (e) => (e.method === 'check' ? 'by check, ' : 'cash, ')
      + (e.source === 'staff' ? `${e.paidBy || 'staff'}’s own money` : e.source === 'owner' ? 'owner’s money' : 'from sales');
    const historyUndo = (e) => e.kind === 'payment' ? changeButtons('supplier-payment', e.ref, `the ${peso.format(e.amount)} payment to ${data.supplier.name}`, e.staff, e.time, 'with every bill it paid')
      : e.kind === 'purchase' && String(e.ref).startsWith('P') ? changeButtons('movement', String(e.ref).slice(1), `buying ${e.label}`, e.staff, e.time, 'with what was paid for them')
      : e.kind === 'refill' && !e.label.startsWith('Already') ? changeButtons('refill', e.ref, `refill trip #${e.ref}`, e.staff, e.time, 'with its tanks and every payment made on it') : null;
    const billLine = (e) => e.cost ? h('div', { class: 'meta' }, `Bill ${peso.format(e.cost)} · paid ${peso.format(e.paid)}`,
      e.owed > 0 ? h('span', { class: 'owes-text' }, ` · ${peso.format(e.owed)} still to pay`) : null) : null;
    const draw = () => {
      const shown = data.entries.filter((e) => (!kind.value || e.kind === kind.value)
        && (!from.value || e.time.slice(0, 10) >= from.value) && (!to.value || e.time.slice(0, 10) <= to.value));
      const sum = (k, f) => shown.filter(f).reduce((n, e) => n + (e[k] || 0), 0);
      const t = data.totals;
      const filtered = from.value || to.value || kind.value;
      clear(totals,
        money('Billed', filtered ? sum('cost', (e) => e.kind !== 'payment') : t.billed),
        money('Paid', filtered ? sum('amount', (e) => e.kind === 'payment') : t.paid),
        money('Still owed', filtered ? sum('owed', (e) => e.kind !== 'payment') : t.owed, t.owed > 0 && !filtered ? 'owes-text' : ''),
        filtered ? null : h('small', { class: 'muted history-split' },
          `Paid ${peso.format(t.paidSales)} from sales${t.paidOwner ? ` · ${peso.format(t.paidOwner)} owner’s money` : ''}${t.paidStaff ? ` · ${peso.format(t.paidStaff)} from staff’s own money` : ''}${t.paidCheck ? ` · ${peso.format(t.paidCheck)} by check` : ''}`));
      clear(list, shown.length ? shown.map((e) => h('div', { class: 'move' },
        h('div', {}, h('strong', {}, e.kind === 'payment' ? 'Payment' : e.kind === 'purchase' ? 'Bought new tanks' : e.label),
          e.kind === 'payment' ? ` · for ${e.label}` : e.kind === 'purchase' ? ` · ${e.label}` : null),
        h('div', { class: 'num' }, e.kind === 'payment' ? peso.format(e.amount) : e.cost ? peso.format(e.cost) : ''),
        e.kind === 'refill' && e.items.length ? h('div', { class: 'meta' }, e.items.map((i) =>
          `${i.product}: ${i.sent} sent, ${i.received} back with load${i.rejected ? `, ${i.rejected} returned unfilled` : ''}${i.sent - i.received - i.rejected > 0 ? `, ${i.sent - i.received - i.rejected} still out` : ''}`).join(' · ')) : null,
        e.kind !== 'payment' ? billLine(e) : null,
        h('div', { class: 'meta' }, h('span', {}, [fmtTime(e.time), e.kind === 'payment' ? payer(e) : null, e.staff ? `by ${e.staff}` : null, e.note].filter(Boolean).join(' · ')),
          historyUndo(e))))
        : h('div', { class: 'empty' }, filtered ? 'Nothing in this period.' : 'Nothing recorded with this supplier yet.'));
    };
    for (const el of [from, to, kind]) el.addEventListener('input', draw);
    draw();
    return openDialog({
      title: `${data.supplier.name} — history`,
      message: [data.supplier.contact, data.supplier.brands.join(', ')].filter(Boolean).join(' · ') || null,
      wide: true, submit: null, cancel: 'Close',
      body: h('div', { class: 'history' },
        h('div', { class: 'history-filters' },
          h('label', { class: 'field' }, h('span', {}, 'From'), from),
          h('label', { class: 'field' }, h('span', {}, 'To'), to),
          h('label', { class: 'field' }, h('span', {}, 'Show'), kind)),
        totals, list),
    });
  }

  // Staff who paid suppliers from their own pocket, and paying them back.
  function renderStaffMoney() {
    const m = state.data.staffMoney;
    $('staff-money-sub').textContent = m.total > 0 ? `The store owes staff ${peso.format(m.total)}` : 'Nobody is owed anything';
    const what = (e) => e.kind === 'advance' ? `${e.staff} paid ${e.supplier} (${e.bill})` : `Paid back to ${e.staff}`;
    clear($('staff-money'),
      m.owed.length ? h('div', { class: 'list' }, m.owed.map((o) => h('div', { class: 'supplier-row' },
        h('div', {}, h('strong', {}, o.staff), h('div', { class: 'meta owes-text' }, `Owed ${peso.format(o.owed)}`)),
        actions(h('button', { class: 'btn small', onclick: () => payBackDialog(o) }, 'Pay back'))))) : h('div', { class: 'empty' }, 'When staff pay a supplier with their own money, it shows here until they’re paid back.'),
      m.recent.length ? h('h3', { class: 'minor-head' }, 'Recent') : null,
      m.recent.length ? h('div', { class: 'moves' }, m.recent.slice(0, 8).map((e) => h('div', { class: 'move' },
        h('div', {}, h('strong', {}, what(e))),
        h('div', { class: 'num ' + (e.kind === 'advance' ? 'owes-text' : 'secondary') }, (e.kind === 'advance' ? '+' : '−') + peso.format(e.amount)),
        h('div', { class: 'meta' }, h('span', {}, [fmtTime(e.time), e.by, e.note].filter(Boolean).join(' · ')),
          e.kind === 'advance' ? changeButtons('supplier-payment', e.id, `the ${peso.format(e.amount)} ${e.staff} paid ${e.supplier}`, e.by, e.time, 'with the rest of that payment to the supplier')
            : changeButtons('payback', e.id, `paying back ${peso.format(e.amount)} to ${e.staff}`, e.by, e.time))))) : null);
  }

  function payBackDialog(o, edit) {
    formDialog({
      title: editTitle(edit, `Pay back ${o.staff}`),
      message: `${o.staff} is owed ${peso.format(o.owed)}${edit ? ' without this payback' : ''} for suppliers they paid from their own money. Record what the store gives back (from sales).`,
      submit: edit ? 'Save changes' : 'Record payback',
      fields: [
        { name: 'amount', label: 'Amount paid back (₱)', type: 'number', min: '0.01', step: '0.01', value: (edit ? edit.amount : o.owed).toFixed(2), required: true, inputmode: 'decimal', full: true },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. cash from the drawer', value: edit ? edit.note : null },
      ],
      extra: (inputs) => dateField(inputs, edit),
      onSubmit: (v) => mutate('POST', `/api/staff/${encodeURIComponent(o.staff)}/payback`, asEdit(v, edit), edit ? 'Payback corrected' : `Paid back ${peso.format(Number(v.amount))} to ${o.staff}`),
    });
  }

  // edit: a count or write-off being corrected. Without it the stock would be what it is now minus its change,
  // so the change shown is measured from there.
  function adjustDialog(p, edit) {
    if (!needProducts()) return;
    const start = p || state.data.products[0];
    const base = (x) => edit && x.id === edit.productId
      ? { loaded: x.loaded - edit.loaded, empty: x.empty - edit.empty } : { loaded: x.loaded, empty: x.empty };
    formDialog({
      title: editTitle(edit, 'Count or adjust stock'),
      message: 'Enter what is physically in the store now. The difference is recorded. Tanks at the refiller aren’t counted here.',
      submit: edit ? 'Save changes' : 'Save count',
      fields: [
        { name: 'productId', label: 'LPG product', type: 'select', full: true, value: start.id, options: productOptions((x) => x.label) },
        { name: 'loaded', label: 'With load', type: 'number', min: 0, step: 1, value: Math.max(0, start.loaded), required: true, inputmode: 'numeric' },
        { name: 'empty', label: 'Empty', type: 'number', min: 0, step: 1, value: Math.max(0, start.empty), required: true, inputmode: 'numeric' },
        { name: 'reason', label: 'Reason', type: 'select', full: true, value: edit ? edit.type : 'count', options: [
          { value: 'count', label: 'Stock count (recount)' },
          { value: 'damaged', label: 'Write-off (lost, stolen, leaking, scrapped)' },
          { value: 'other', label: 'Other' },
        ] },
        { name: 'note', label: 'Note', full: true, placeholder: 'Required for write-offs and other changes', value: edit ? edit.note : null },
      ],
      // Says what saving will change before it's saved, so a typo (110 for 11) shows up.
      extra: (inputs) => {
        const diff = h('p', { class: 'cost-line', role: 'status' });
        const show = () => {
          const x = byId(state.data.products, inputs.productId.value);
          if (!x) return clear(diff);
          const d = (v, now, word) => { const n = (Number(v) || 0) - now; return n ? `${n > 0 ? '+' : '−'}${Math.abs(n)} ${word}` : null; };
          const b = base(x);
          const parts = [d(inputs.loaded.value, Math.max(0, b.loaded), 'with load'), d(inputs.empty.value, Math.max(0, b.empty), 'empty')].filter(Boolean);
          clear(diff, h('span', {}, 'Change'), h('strong', {}, parts.length ? parts.join(', ') : 'none (same as now)'),
            h('small', { class: 'muted' }, `${edit ? 'Without this change' : 'Now'} ${Math.max(0, b.loaded)} with load, ${Math.max(0, b.empty)} empty`));
        };
        inputs.productId.addEventListener('change', () => {
          const x = byId(state.data.products, inputs.productId.value);
          if (x) {
            inputs.loaded.value = Math.max(0, x.loaded);
            inputs.empty.value = Math.max(0, x.empty);
          }
          show();
        });
        inputs.loaded.addEventListener('input', show);
        inputs.empty.addEventListener('input', show);
        show();
        return [diff, dateField(inputs, edit)];
      },
      onSubmit: (v) => mutate('POST', '/api/stock/adjust', asEdit(v, edit), edit ? 'Stock change corrected' : 'Stock updated'),
    });
  }

  function supplierDialog(s) {
    // A supplier refills only its own brand, and each brand has one supplier.
    const taken = new Set(state.data.suppliers.filter((x) => !s || x.id !== s.id).flatMap((x) => x.brands.map((b) => b.toLowerCase())));
    const free = state.data.brands.filter((b) => !taken.has(b.toLowerCase()));
    if (!free.length) return toast(state.data.brands.length ? 'Every brand already has a supplier' : 'Add an LPG product first', true);
    const own = s && free.find((b) => s.brands.some((x) => x.toLowerCase() === b.toLowerCase()));
    formDialog({
      title: s ? `Edit ${s.name}` : 'New supplier',
      submit: s ? 'Save changes' : 'Add supplier',
      fields: [
        { name: 'name', label: 'Name', value: s && s.name, required: true, full: true, placeholder: 'e.g. Petron depot' },
        { name: 'contact', label: 'Contact', value: s && s.contact, full: true, placeholder: 'Phone or person' },
        { name: 'brand', label: 'Brand', type: 'select', required: true, full: true, value: own || free[0],
          options: free.map((b) => ({ value: b, label: b })), help: 'A supplier refills only its own brand.' },
        { name: 'note', label: 'Note (optional)', value: s && s.note, full: true, placeholder: 'e.g. picks up Mon/Thu, 2-day turnaround' },
      ],
      onSubmit: (v) => s
        ? mutate('PUT', '/api/suppliers/' + s.id, v, 'Supplier updated')
        : mutate('POST', '/api/suppliers', v, `Added ${v.name}`),
    });
  }

  async function deleteSupplier(s) {
    const ok = await confirmDialog(`Delete ${s.name}?`, 'Its brand will show as “No supplier set”. Past refill trips stay in the history.');
    if (ok) mutate('DELETE', '/api/suppliers/' + s.id, null, 'Supplier deleted').catch((e) => toast(e.message, true));
  }

  // ------------------------------------------------------------------ dashboard (staff the admin allows)

  for (const b of document.querySelectorAll('[data-dash]')) {
    b.addEventListener('click', () => {
      state.dashMode = b.dataset.dash;
      try { localStorage.setItem('lpg-dash-mode', state.dashMode); } catch (_) { /* storage blocked */ }
      renderDashboard();
    });
  }

  // The daily summary; Sales, Suppliers and Money by day, week, month or quarter; and the starting balances.
  const dashViews = {};
  const report = (name) => (q) => api('GET', `/api/reports/${name}?` + new URLSearchParams(q)).catch((e) => {
    if (e.status === 403) { state.me.dashboard = false; updateDashboardAccess(); go('sell'); }
    throw e;
  });
  let dashAt = 0;
  function renderDashboard(passive) {
    const mode = state.dashMode;
    for (const b of document.querySelectorAll('[data-dash]')) b.setAttribute('aria-checked', String(b.dataset.dash === mode));
    for (const el of document.querySelectorAll('[data-dash-view]')) el.hidden = el.dataset.dashView !== mode;
    if (!dashViews[mode]) {
      dashAt = Date.now();
      dashViews[mode] = {
        daily: () => Daily.mount($('dash-daily'), (date) => api('GET', '/api/daily?date=' + encodeURIComponent(date))),
        sales: () => Reports.mountSales($('dash-sales'), report('sales')),
        suppliers: () => Reports.mountSuppliers($('dash-suppliers'), report('suppliers'), { recordRefill: recordRefillDialog }),
        money: () => Reports.mountMoney($('dash-money'), report('money'), { addExpense: expenseDialog, editExpense: expenseDialog, deleteExpense }),
        salaries: () => (payrollView = Payroll.mount($('dash-salaries'), { load: () => api('GET', '/api/payroll').catch((e) => {
          if (e.status === 403) { state.me.dashboard = false; updateDashboardAccess(); go('sell'); }
          throw e;
        }), actions: payrollActions })),
        start: () => Opening.mount($('dash-start'), {
          load: () => api('GET', '/api/opening'),
          save: (body) => mutate('POST', '/api/opening', body, 'Starting balances saved'),
          data: () => state.data,
          addCustomer: () => customerDialog(),
        }),
      }[mode]();
    } else if (passive || Date.now() - dashAt > 3000) {
      dashAt = Date.now();
      dashViews[mode].refresh();
    }
  }

  Explain.bind($('view-dashboard'), (url) => api('GET', url), (el) => ({ date: el.dataset.date }));

  setInterval(() => {
    // Don't redraw under an open form.
    if (state.view === 'dashboard' && document.visibilityState === 'visible' && !document.querySelector('dialog[open]')) renderDashboard(true);
  }, 30000);

  // A refill done in one go: the supplier takes the empties and brings them back filled (e.g. several
  // suppliers delivering on one day). Picks the supplier's products; cost each defaults to the refill cost.
  function recordRefillDialog(supplierId, edit) {
    const { suppliers, products } = state.data;
    const was = (pid) => (edit && edit.items.find((i) => i.productId === pid)) || null;
    if (!suppliers.length) { toast('Add a supplier first (Inventory → Suppliers)', true); return Promise.resolve(null); }
    const sel = h('select', { class: 'input', name: 'supplierId' }, suppliers.map((s) => h('option', { value: s.id }, `${s.name} — ${s.brands.join(', ')}`)));
    if (supplierId && suppliers.some((s) => s.id === supplierId)) sel.value = supplierId;
    const rowsBox = h('div', { class: 'qty-rows' });
    const costLine = h('div', { class: 'cost-line' });
    let cost = 0;
    const pay = paySource({}, () => cost, edit && edit.paid);
    const draw = () => {
      const list = products.filter((p) => p.supplierId === sel.value);
      clear(rowsBox, list.length ? [h('div', { class: 'qty-row head three refill' }, h('span', {}), h('span', {}, 'Tanks refilled'), h('span', {}, 'Cost each (₱)')),
        list.map((p, i) => h('div', { class: 'qty-row three refill' },
          h('span', {}, h('strong', {}, p.label), h('small', { class: 'muted' }, `${p.empty + ((was(p.id) || {}).received || 0)} empty on hand`)),
          h('input', { type: 'hidden', name: 'productId.' + i, value: p.id }),
          h('label', {}, h('small', { class: 'm-only' }, 'Tanks refilled'),
            h('input', { class: 'input num', type: 'number', name: 'qty.' + i, min: 0, max: p.empty + ((was(p.id) || {}).received || 0), step: 1, value: (was(p.id) || {}).received || 0, inputmode: 'numeric', 'aria-label': `${p.label} tanks refilled` })),
          h('label', {}, h('small', { class: 'm-only' }, 'Cost each (₱)'),
            h('input', { class: 'input num', type: 'number', name: 'cost.' + i, min: 0, step: '0.01', value: was(p.id) && was(p.id).cost ? was(p.id).cost.toFixed(2) : p.refillCost ? p.refillCost.toFixed(2) : '', placeholder: '0.00', inputmode: 'decimal', 'aria-label': `Refill cost per ${p.label}` }))))]
        : h('p', { class: 'muted' }, 'None of your products use this supplier’s brand yet.'));
      pay.reset();
      update();
    };
    function update() {
      cost = 0;
      rowsBox.querySelectorAll('.qty-row.three:not(.head)').forEach((row) => {
        cost += (Number(row.querySelector('[name^="qty."]').value) || 0) * (Number(row.querySelector('[name^="cost."]').value) || 0);
      });
      cost = round2(cost);
      clear(costLine, h('span', {}, 'Refill bill'), h('strong', { class: 'num' }, peso.format(cost)), zeroCostNote(rowsBox, 'qty.'));
      pay.refresh();
    }
    rowsBox.addEventListener('input', update);
    sel.addEventListener('change', draw);
    draw();
    return openDialog({
      title: edit ? `Correct refill trip #${edit.id}` : 'Record a refill',
      message: 'Empty tanks went to the supplier and came back filled. They’re added to stock as tanks with load.',
      submit: edit ? 'Save changes' : 'Save refill',
      wide: true,
      body: [h('div', { class: 'grid-2' }, h('label', { class: 'field' }, h('span', {}, 'Supplier'), sel), dateField(null, edit)), rowsBox, costLine, pay.el,
        h('label', { class: 'field' }, h('span', {}, 'Note (optional)'), h('input', { class: 'input', name: 'note', maxlength: 120, placeholder: 'e.g. delivery receipt no.', value: edit ? edit.note : null }))],
      onSubmit: (form) => {
        const body = formBody(form);
        body.items = rowsBox.querySelectorAll('input[name^="productId."]').length;
        return mutate('POST', '/api/refills/record', asEdit(body, edit), edit ? 'Refill corrected' : 'Refill saved');
      },
    });
  }

  // Delivery gasoline, salaries, salary advances and other costs. A salary shows the employee's unpaid
  // advances and takes them off, so what's handed over is the salary minus the advance.
  // With entry, corrects that expense (date included) instead of adding one.
  function expenseDialog(report, entry) {
    const advances = Object.fromEntries((report.advances || []).map((a) => [a.employee.toLowerCase(), a.owed]));
    // When correcting a salary, the advance it already took off is available to it again.
    if (entry && entry.category === 'salary' && entry.advanceDeducted) {
      const k = entry.employee.toLowerCase();
      advances[k] = round2((advances[k] || 0) + entry.advanceDeducted);
    }
    const names = h('datalist', { id: 'employee-names' }, (report.employees || []).map((n) => h('option', { value: n })));
    // Salaries, advances and advances paid back are entered under Dashboard → Salaries (4.1), not as expenses.
    const isPayroll = (v) => ['salary', 'advance', 'repaid'].includes(v);
    const cat = h('select', { class: 'input', name: 'category' },
      Object.entries(Reports.CATEGORY_ONE).filter(([v]) => !!(entry && isPayroll(entry.category)) === isPayroll(v)).map(([v, label]) => h('option', { value: v }, label)));
    const employee = h('input', { class: 'input', name: 'employee', list: 'employee-names', maxlength: 60, autocomplete: 'off' });
    const amount = h('input', { class: 'input num', type: 'number', min: 0, step: '0.01', inputmode: 'decimal', required: true });
    const deducted = h('input', { class: 'input num', name: 'advanceDeducted', type: 'number', min: 0, step: '0.01', inputmode: 'decimal', placeholder: '0.00' });
    const method = h('select', { class: 'input', name: 'method' }, h('option', { value: 'cash' }, 'Cash'), h('option', { value: 'online' }, 'Online (GCash, bank)'), h('option', { value: 'check' }, 'Check'));
    const amountLabel = h('span', {}, 'Amount (₱)');
    const employeeField = h('label', { class: 'field' }, h('span', {}, 'Employee'), employee);
    const deductedField = h('label', { class: 'field' }, h('span', {}, 'Advance to take off (₱)'), deducted);
    const info = h('p', { class: 'cost-line' });
    let deductedEdited = !!entry;
    if (entry) {
      cat.value = entry.category;
      employee.value = entry.employee || '';
      amount.value = round2(entry.amount + (entry.category === 'salary' ? entry.advanceDeducted || 0 : 0)).toFixed(2);
      deducted.value = entry.advanceDeducted ? entry.advanceDeducted.toFixed(2) : '';
      method.value = entry.method;
    }
    const owed = () => advances[employee.value.trim().toLowerCase()] || 0;
    const update = () => {
      const c = cat.value, salary = c === 'salary';
      employeeField.hidden = !(salary || c === 'advance');
      deductedField.hidden = !salary;
      amountLabel.textContent = salary ? 'Weekly salary (₱)' : c === 'advance' ? 'Advance given (₱)' : 'Amount (₱)';
      if (salary && !deductedEdited) deducted.value = owed() ? Math.min(owed(), Number(amount.value) || owed()).toFixed(2) : '';
      const take = salary ? Number(deducted.value) || 0 : 0;
      clear(info,
        salary ? [h('span', {}, 'Hand over'), h('strong', { class: 'num' }, peso.format(Math.max(0, round2((Number(amount.value) || 0) - take))))] : null,
        (salary || c === 'advance') && employee.value.trim() ? h('small', { class: 'muted' }, owed() ? ` · ${employee.value.trim()} has ${peso.format(owed())} in unpaid advances` : ` · no unpaid advances`) : null);
    };
    [cat, employee, amount].forEach((el) => el.addEventListener('input', update));
    cat.addEventListener('change', update);
    deducted.addEventListener('input', () => { deductedEdited = true; update(); });
    update();
    const date = dateField();
    if (entry && date) date.querySelector('input').value = entry.time.slice(0, 10);
    return openDialog({
      title: entry ? 'Correct this expense' : 'Add an expense',
      submit: entry ? 'Save changes' : 'Save expense',
      body: [names,
        h('div', { class: 'grid-2' }, h('label', { class: 'field' }, h('span', {}, 'What for'), cat), date),
        employeeField,
        h('div', { class: 'grid-2' }, h('label', { class: 'field' }, amountLabel, amount), deductedField),
        h('label', { class: 'field' }, h('span', {}, 'Paid by'), method),
        info,
        h('label', { class: 'field' }, h('span', {}, 'Note (optional)'), h('input', { class: 'input', name: 'note', maxlength: 120, placeholder: 'e.g. gas station receipt, week of Oct 6', value: entry ? entry.note || '' : null }))],
      onSubmit: (form) => {
        const body = formBody(form);
        const take = body.category === 'salary' ? Number(body.advanceDeducted) || 0 : 0;
        if (!(Number(amount.value) > 0)) throw new Error('Enter the amount.');
        if (take > (Number(amount.value) || 0) + 0.005) throw new Error('The advance taken off is more than the salary.');
        body.amount = round2((Number(amount.value) || 0) - take).toFixed(2);
        if (body.category !== 'salary') delete body.advanceDeducted;
        if (entry) {
          body.date = body.recordDate; // the corrected day of an existing expense
          delete body.recordDate;
          return mutate('PUT', '/api/expenses/' + encodeURIComponent(entry.id), body, 'Expense updated');
        }
        return mutate('POST', '/api/expenses', body, 'Expense saved');
      },
    });
  }

  async function deleteExpense(e) {
    const ok = await confirmDialog('Delete this expense?', `${Reports.CATEGORY_ONE[e.category]}${e.employee ? ' for ' + e.employee : ''}, ${peso.format(e.amount)} on ${fmtTime(e.time, { full: true })}. Only delete something entered by mistake.`);
    if (!ok) return false;
    try {
      await mutate('DELETE', '/api/expenses/' + encodeURIComponent(e.id), null, 'Expense deleted');
      return true;
    } catch (ex) {
      toast(ex.message, true);
      return false;
    }
  }

  // ------------------------------------------------------------------ staff salaries (4.1)

  let payrollView = null;
  // Saves, refreshes the POS data and the salaries table, and says what was saved.
  const payrollSave = async (method, path, body, message) => {
    const r = await mutate(method, path, body, message);
    if (payrollView) payrollView.refresh();
    return r;
  };
  const method3 = () => [{ value: 'cash', label: 'Cash' }, { value: 'online', label: 'Online (GCash, bank)' }, { value: 'check', label: 'Check' }];

  // Add someone to the payroll (name, salary, schedule), or change them. name: prefill for a name on older records.
  function employeeDialog(e, name) {
    const nameIn = h('input', { class: 'input', name: 'name', maxlength: 60, required: true, autocomplete: 'off', value: e ? e.name : name || null });
    const salary = h('input', { class: 'input num', name: 'salary', type: 'number', min: 0, step: '0.01', inputmode: 'decimal', required: true, value: e ? e.salary.toFixed(2) : null });
    const sched = h('select', { class: 'input', name: 'schedule' }, [['weekly', 'Weekly'], ['twice', 'Twice a month'], ['monthly', 'Monthly']].map(([v, l]) => h('option', { value: v }, l)));
    sched.value = e ? e.schedule : 'weekly';
    const d1 = h('select', { class: 'input', name: 'day1' }), d2 = h('select', { class: 'input', name: 'day2' });
    const l1 = h('span', {});
    const d2Field = h('label', { class: 'field' }, h('span', {}, 'Second pay day'), d2);
    const monthDays = () => Array.from({ length: 31 }, (_, i) => h('option', { value: i + 1 }, Payroll.nth(i + 1) + (i >= 28 ? ' (or the last day)' : '')));
    const fill = (first) => {
      const s = sched.value;
      clear(d1, s === 'weekly' ? Payroll.DAYS.slice(1).map((day, i) => h('option', { value: i + 1 }, day)) : monthDays());
      clear(d2, monthDays());
      l1.textContent = s === 'weekly' ? 'Pay day' : s === 'twice' ? 'First pay day' : 'Pay day of the month';
      d2Field.hidden = s !== 'twice';
      const same = first && e && e.schedule === s;
      d1.value = same ? e.day1 : s === 'weekly' ? 6 : s === 'twice' ? 15 : 30;
      d2.value = same && e.day2 ? e.day2 : 30;
    };
    sched.addEventListener('change', () => fill(false));
    fill(true);
    return openDialog({
      title: e ? `Edit ${e.name}` : 'Add employee',
      message: e ? 'Changing the name also renames their salary and advance records.' : 'Their salary and when it’s paid. Advances are taken off it on pay day.',
      submit: e ? 'Save changes' : 'Add employee',
      body: [h('label', { class: 'field' }, h('span', {}, 'Name'), nameIn),
        h('label', { class: 'field' }, h('span', {}, 'Salary per pay day (₱)'), salary),
        h('div', { class: 'grid-2' }, h('label', { class: 'field' }, h('span', {}, 'Paid'), sched), h('label', { class: 'field' }, l1, d1)),
        d2Field,
        h('label', { class: 'field' }, h('span', {}, 'Note (optional)'), h('input', { class: 'input', name: 'note', maxlength: 120, value: e ? e.note || null : null }))],
      onSubmit: async (form) => {
        const body = formBody(form);
        if (!body.name) throw new Error('Enter the employee’s name.');
        if (!(Number(body.salary) >= 0) || body.salary === '') throw new Error('Enter the salary.');
        if (body.schedule !== 'twice') body.day2 = '';
        await payrollSave(e ? 'PUT' : 'POST', '/api/employees' + (e ? '/' + encodeURIComponent(e.id) : ''), body, e ? `${body.name} updated` : `${body.name} added to the payroll`);
      },
    });
  }

  // Money given ahead of pay day; it's taken off the salary (or paid back) later.
  function advanceDialog(e) {
    return formDialog({
      title: `Salary advance for ${e.name}`,
      message: `Salary ${peso.format(e.salary)} · already advanced ${peso.format(e.advanced)} · salary still owed ${peso.format(e.owed)}.`,
      submit: 'Give advance',
      fields: [
        { name: 'amount', label: 'Advance (₱)', type: 'number', min: '0.01', step: '0.01', required: true, inputmode: 'decimal', full: true },
        { name: 'method', label: 'Paid by', type: 'select', full: true, value: 'cash', options: method3() },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. for school fees' },
      ],
      extra: (inputs) => {
        const line = h('p', { class: 'cost-line' });
        const show = () => {
          const after = round2(e.balance - (Number(inputs.amount.value) || 0));
          clear(line, h('span', {}, 'Balance on pay day'), h('strong', { class: 'num' + (after < 0 ? ' owes-text' : '') }, peso.format(after)),
            after < 0 ? h('small', { class: 'owes-text' }, ' · more than the salary: the rest carries to the next pay day') : null);
        };
        inputs.amount.addEventListener('input', show);
        show();
        return [line, dateField(inputs)];
      },
      onSubmit: (v) => payrollSave('POST', '/api/expenses', { ...v, category: 'advance', employee: e.name }, `Advance of ${peso.format(Number(v.amount))} for ${e.name} saved`),
    });
  }

  // Pay day: the salary for one pay day (the oldest not fully paid, or the next one), with unpaid advances taken off (they're cleared).
  function paySalaryDialog(e) {
    const past = (e.periods || []).filter((p) => p.status !== 'paid' && p.payday <= e.lastPayday.payday);
    // Owed pay days oldest first, then the next one, then older ones with no record (to enter a salary paid back then).
    const open = past.filter((p) => p.status !== 'unknown').reverse()
      .concat(e.upcoming && e.upcoming.status !== 'paid' ? [e.upcoming] : [], past.filter((p) => p.status === 'unknown'));
    const leftFor = (day) => { const p = open.find((x) => x.payday === day); return p ? (p.left == null ? e.salary : p.left) : e.salary; };
    const label = (p) => `${Payroll.shortDay(p.payday)} · ${Payroll.STATUS[p.status][2]}` + (p.status === 'unknown' ? '' : ` · ${peso.format(p.left)} left`);
    if (!open.length) { toast(`${e.name}’s pay days are all paid, up to the next one.`); return null; }
    return formDialog({
      title: `Pay ${e.name}’s salary`,
      message: (e.advanced > 0 ? `${e.name} has ${peso.format(e.advanced)} in unpaid advances. Taking them off clears them.` : `${e.name} has no unpaid advances.`)
        + ' Pick the pay day this salary is for, even when paying early or late.',
      submit: 'Record salary',
      fields: [
        { name: 'payday', label: 'For pay day', type: 'select', full: true, value: open[0].payday, options: open.map((p) => ({ value: p.payday, label: label(p) })) },
        { name: 'salary', label: 'Salary paid (₱)', type: 'number', min: '0', step: '0.01', required: true, inputmode: 'decimal', value: leftFor(open[0].payday).toFixed(2) },
        { name: 'advanceDeducted', label: 'Advance to take off (₱)', type: 'number', min: '0', max: e.advanced, step: '0.01', inputmode: 'decimal', value: Math.min(e.advanced, e.salary).toFixed(2) },
        { name: 'method', label: 'Paid by', type: 'select', full: true, value: 'cash', options: method3() },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. pay for Oct 18–24' },
      ],
      extra: (inputs) => {
        const line = h('p', { class: 'cost-line' });
        const show = () => {
          const hand = round2((Number(inputs.salary.value) || 0) - (Number(inputs.advanceDeducted.value) || 0));
          clear(line, h('span', {}, 'Hand over'), h('strong', { class: 'num' }, peso.format(Math.max(0, hand))),
            (Number(inputs.advanceDeducted.value) || 0) > 0 ? h('small', { class: 'muted' }, ` · ${peso.format(Number(inputs.advanceDeducted.value))} advance cleared`) : null);
        };
        inputs.salary.addEventListener('input', show);
        inputs.advanceDeducted.addEventListener('input', show);
        inputs.payday.addEventListener('change', () => { inputs.salary.value = leftFor(inputs.payday.value).toFixed(2); show(); });
        show();
        return [line, dateField(inputs)];
      },
      onSubmit: (v) => {
        const sal = Number(v.salary) || 0, take = Number(v.advanceDeducted) || 0;
        if (sal <= 0) throw new Error('Enter the salary.');
        if (sal > leftFor(v.payday) + 0.005) throw new Error(`Only ${peso.format(leftFor(v.payday))} is left to pay for ${Payroll.shortDay(v.payday)}.`);
        if (take > e.advanced + 0.005) throw new Error(`${e.name} has only ${peso.format(e.advanced)} in unpaid advances.`);
        if (take > sal + 0.005) throw new Error('The advance taken off is more than the salary. Take off part now; the rest stays as an advance.');
        const { salary, ...rest } = v;
        return payrollSave('POST', '/api/expenses', { ...rest, category: 'salary', employee: e.name, amount: round2(sal - take).toFixed(2), advanceDeducted: take ? take.toFixed(2) : '' },
          `Salary for ${e.name} (pay day ${Payroll.shortDay(v.payday)}) saved · hand over ${peso.format(round2(sal - take))}`);
      },
    });
  }

  // Clear an advance: they pay it back, or it's taken off a salary (Pay salary).
  function clearAdvanceDialog(e) {
    return formDialog({
      title: `Clear ${e.name}’s advance`,
      message: `${e.name} has ${peso.format(e.advanced)} in unpaid advances. Record money they paid back, or take it off their salary on pay day.`,
      submit: 'Record paid back',
      fields: [
        { name: 'amount', label: 'Paid back (₱)', type: 'number', min: '0.01', max: e.advanced, step: '0.01', required: true, inputmode: 'decimal', value: e.advanced.toFixed(2), full: true },
        { name: 'method', label: 'Paid with', type: 'select', full: true, value: 'cash', options: method3().slice(0, 2) },
        { name: 'note', label: 'Note (optional)', full: true },
      ],
      extra: (inputs) => [dateField(inputs), e.id ? h('button', { type: 'button', class: 'btn', onclick: (ev) => { ev.currentTarget.closest('dialog').close(); paySalaryDialog(e); } },
        'Take it off a salary instead') : null],
      onSubmit: (v) => {
        if ((Number(v.amount) || 0) > e.advanced + 0.005) throw new Error(`${e.name} has only ${peso.format(e.advanced)} in unpaid advances.`);
        return payrollSave('POST', '/api/expenses', { ...v, category: 'repaid', employee: e.name }, `${e.name} paid back ${peso.format(Number(v.amount))}`);
      },
    });
  }

  // Everything paid to someone, newest first. A wrong entry is deleted and entered again.
  function payrollHistory(e) {
    const list = h('div', { class: 'moves' });
    const draw = (items) => clear(list, items.length ? items.map((x) => h('div', { class: 'move' },
      h('div', {}, h('strong', {}, { salary: 'Salary paid', advance: 'Advance given', repaid: 'Advance paid back' }[x.category])),
      h('div', { class: 'num ' + (x.category === 'repaid' ? 'delta-pos' : '') }, (x.category === 'repaid' ? '+' : '−') + peso.format(x.amount)),
      h('div', { class: 'meta' }, h('span', {}, Payroll.historyLine(x)),
        state.me.dashboard ? h('button', { type: 'button', class: 'btn small ghost danger', onclick: async () => {
          if (await deleteExpense({ ...x, employee: e.name })) {
            if (payrollView) await payrollView.refresh();
            const now = payrollView && payrollView.data() && payrollView.data().employees.find((y) => y.name.toLowerCase() === e.name.toLowerCase());
            draw(now ? now.history : []);
          }
        } }, 'Delete') : null))) : h('div', { class: 'empty' }, 'Nothing paid yet.'));
    draw(e.history || []);
    // Each recent pay day: how it stands and the salaries that paid it.
    const days = (e.periods || []).length ? h('div', { class: 'moves' }, e.periods.map((p) => h('div', { class: 'move' },
      h('div', {}, h('strong', {}, Payroll.shortDay(p.payday)), ' ', Payroll.chip(p.status)),
      h('div', { class: 'num' }, p.status === 'unknown' ? '—' : peso.format(p.paid)),
      h('div', { class: 'meta' }, h('span', {}, [Payroll.periodText(p, e.salary)].concat(p.records.map((x) => `${UI.fmtTime(x.time)} ${peso.format(x.amount + (x.advanceDeducted || 0))}`
        + (x.recordedBy ? ` by ${x.recordedBy}` : ''))).join(' · ')))))) : null;
    return openDialog({
      title: `${e.name} — salary history`,
      message: [e.salary != null ? `Salary ${peso.format(e.salary)}` : null, e.schedule ? Payroll.scheduleText(e) : null, e.owed != null ? `still owed ${peso.format(e.owed)}` : null,
        `advanced ${peso.format(e.advanced)}`].filter(Boolean).join(' · '),
      wide: true, submit: null, cancel: 'Close', body: h('div', { class: 'history' }, days ? [h('h3', {}, 'Pay days'), days, h('h3', {}, 'All records')] : null, list),
    });
  }

  const payrollActions = {
    menu: rowMenu,
    add: (name) => employeeDialog(null, name),
    edit: (e) => employeeDialog(e),
    remove: async (e) => {
      if (!(await confirmDialog(`Remove ${e.name} from the payroll?`, 'Their salary and advance records stay. Someone with unpaid advances can’t be removed; clear them first.', 'Remove'))) return;
      try {
        await payrollSave('DELETE', '/api/employees/' + encodeURIComponent(e.id), null, `${e.name} removed from the payroll`);
      } catch (x) { toast(x.message, true); }
    },
    advance: advanceDialog, pay: paySalaryDialog, clear: clearAdvanceDialog, history: payrollHistory,
  };

  // ------------------------------------------------------------------ boot

  window.addEventListener('hashchange', () => state.me && go(location.hash.slice(1)));

  // Keep in sync with other devices, but never while someone is typing in a dialog.
  setInterval(() => {
    if (state.me && document.visibilityState === 'visible' && !document.querySelector('dialog[open], details.rowmenu[open]')) {
      refresh().catch(() => {});
    }
  }, 20000);
  document.addEventListener('visibilitychange', () => {
    if (state.me && document.visibilityState === 'visible') refresh().catch(() => {});
  });

  UI.watchVersion();

  // Only a failed /api/me means "signed out"; a bug while drawing must not pose as a sign-in prompt.
  request('GET', '/api/me').then(showApp, () => showLogin());
})();
