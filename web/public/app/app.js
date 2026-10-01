// LPG POS — staff web app.
(function () {
  const { h, clear, peso, count, toast, request, formDialog, confirmDialog, openDialog, fmtTime } = UI;
  const $ = (id) => document.getElementById(id);

  const EMPTY_DATA = { customers: [], products: [], receipts: [], movements: [], suppliers: [], refills: [], brands: [], returns: [], me: null };
  // own: tanks the receipt being edited already holds, so they count as available again.
  // laterPaid: payments recorded after the sale on the receipt being edited.
  // lastCustomerId: who the items were added for, so switching customers starts a fresh receipt.
  const newSale = () => ({ customerId: null, lastCustomerId: null, lines: [], discount: '', pay: 'full', paidNow: '', cash: '', note: '', editing: null, own: {}, laterPaid: 0 });
  const state = { me: null, data: EMPTY_DATA, etag: null, view: 'sell', sale: newSale(), historyFilter: 'all', summary: null, summaryRange: '7', summaryAt: 0 };
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
    const res = await fetch('/api/data', { headers, cache: 'no-store', credentials: 'same-origin' });
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

  async function mutate(method, path, data, message) {
    const result = await api(method, path, data);
    await refresh();
    if (message) toast(typeof message === 'function' ? message(result) : message);
    return result;
  }

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
    updateDashboardAccess();
    go(location.hash.slice(1) || 'sell');
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

  // ------------------------------------------------------------------ navigation

  const VIEWS = ['sell', 'history', 'customers', 'products', 'inventory', 'dashboard'];

  function go(view) {
    if (!VIEWS.includes(view) || (view === 'dashboard' && !(state.me && state.me.dashboard))) view = 'sell';
    state.view = view;
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
    const menu = document.querySelector('.usermenu');
    if (menu.open && !e.target.closest('.usermenu')) menu.open = false;
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
  const kgOf = (n) => `${count.format(round2(n))} kg`;
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
        h('div', { class: 'name' }, fullName(chosen), chosen.balance > 0 ? h('span', { class: 'owes' }, `Owes ${peso.format(chosen.balance)}`) : null, tanksBadge(chosen)),
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
    const shown = list.slice(0, 6);
    clear($('sell-results'),
      shown.map((c) => h('button', { class: 'pick', onclick: () => chooseCustomer(c.id) },
        h('span', { class: 'avatar' }, initials(c)),
        h('div', {},
          h('div', { class: 'name' }, fullName(c), c.balance > 0 ? h('span', { class: 'owes' }, `Owes ${peso.format(c.balance)}`) : null, tanksBadge(c)),
          h('div', { class: 'meta' }, [c.contact, c.address].filter(Boolean).join(' · ') || '—'),
          h('div', { class: 'meta' }, lastText(c))),
        h('span', { class: 'muted' }, '#' + c.id))),
      !customers.length ? h('div', { class: 'empty' }, 'No customers yet. Tap “+ New” to add one.')
        : !shown.length ? h('div', { class: 'empty' }, 'No customer matches “' + q + '”.')
        : list.length > shown.length ? h('div', { class: 'more-hint' }, `Showing 6 of ${list.length} — keep typing to narrow down`) : null);
  }

  // ------------------------------------------------------------------ new sale: tanks and cart

  // Tanks with load this sale may use (when editing, the receipt's own tanks count as available).
  const available = (pid) => (byId(state.data.products, pid)?.loaded || 0) + (state.sale.own[pid] || 0);
  const inCart = (pid, except) => state.sale.lines.filter((l) => l.productId === pid && l !== except).reduce((n, l) => n + l.qty, 0);
  const cartQty = () => state.sale.lines.reduce((n, l) => n + l.qty, 0);

  function renderTiles() {
    const prods = state.data.products;
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
      h('span', { class: 'stock ' + (left <= 0 && n ? 'out' : p.status) }, n ? `${left} more with load` : stockText(p)),
      n ? h('span', { class: 'in-cart', 'aria-hidden': 'true' }, n) : null);
    }) : h('div', { class: 'empty' }, 'No LPG products yet. Add them in the Products tab.'));
  }

  function addToCart(p) {
    const sale = state.sale;
    if (inCart(p.id) + 1 > available(p.id)) return toast(`Only ${available(p.id)} × ${p.label} with load`, true);
    const same = sale.lines.find((l) => l.productId === p.id && l.emptyId === p.id && !l.remark && !l.priceEdited);
    if (same) same.qty++;
    else sale.lines.push({ key: ++lineKey, productId: p.id, qty: 1, unitPrice: p.price, priceEdited: false, emptyId: p.id, swapFee: p.swapFee || 0, remark: '' });
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
  const isSwap = (l) => {
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
    const showEmpty = () => {
      swapRow.hidden = !isSwap(l);
      owesNote.hidden = !!l.emptyId;
    };
    showEmpty();
    empty.addEventListener('change', () => {
      l.emptyId = empty.value;
      showEmpty();
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
      swapRow, owesNote,
      h('div', { class: 'cl-row remark-row' }, remark));
  }

  function renderCart() {
    const sale = state.sale;
    $('cart-count').textContent = sale.lines.length ? plural(cartQty(), 'tank') : '';
    clear($('cart-lines'), sale.lines.length ? sale.lines.map(lineEditor) : h('div', { class: 'empty' }, 'Tap an LPG tank to add it.'));
    renderPayment();
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
    $('change').textContent = sale.pay === 'full' && !Number.isNaN(cash) && sale.lines.length
      ? (cash >= t.paid ? `Change: ${peso.format(cash - t.paid)}` : `Short by ${peso.format(t.paid - cash)}`) : '';

    const c = byId(state.data.customers, sale.customerId);
    const note = $('credit-note');
    note.hidden = !sale.lines.length || !(t.credit > 0 || t.payError);
    note.textContent = t.payError || `${peso.format(t.credit)} goes on ${c ? fullName(c) + '’s' : 'the customer’s'} balance to pay later.`;
    note.classList.toggle('error-text', !!t.payError);

    const btn = $('process');
    btn.disabled = !(c && sale.lines.length && !t.discountError && !t.payError);
    btn.textContent = sale.editing ? 'Save changes' : !c && sale.lines.length ? 'Choose a customer' : t.credit > 0 && sale.lines.length ? `Process sale · ${peso.format(t.credit)} to pay later` : 'Process sale';
    $('edit-banner').hidden = !sale.editing;
    $('edit-id').textContent = sale.editing ? '#' + sale.editing : '';
    updateCartBar();
  }

  function renderPayment() {
    const sale = state.sale;
    for (const b of document.querySelectorAll('[data-pay]')) b.setAttribute('aria-checked', String(b.dataset.pay === sale.pay));
    $('pay-full').hidden = sale.pay !== 'full';
    $('pay-partial').hidden = sale.pay !== 'partial';
    renderTotals();
  }

  function syncSaleInputs() {
    const sale = state.sale;
    $('cart-discount').value = sale.discount;
    $('cash').value = sale.cash;
    $('paid-now').value = sale.paidNow;
    $('cart-note').value = sale.note;
    $('sell-search').value = '';
  }

  for (const b of document.querySelectorAll('[data-pay]')) {
    b.addEventListener('click', () => {
      state.sale.pay = b.dataset.pay;
      renderPayment();
      if (b.dataset.pay === 'partial') $('paid-now').focus();
    });
  }
  $('cart-discount').addEventListener('input', (e) => { state.sale.discount = e.target.value; renderTotals(); });
  $('cash').addEventListener('input', (e) => { state.sale.cash = e.target.value; renderTotals(); });
  $('paid-now').addEventListener('input', (e) => { state.sale.paidNow = e.target.value; renderTotals(); });
  $('cart-note').addEventListener('input', (e) => { state.sale.note = e.target.value; });
  $('process').addEventListener('click', submitSale);
  $('cancel-edit').addEventListener('click', () => { state.sale = newSale(); syncSaleInputs(); renderSell(false); toast('Edit cancelled'); });

  // Phones: the receipt sits below the tanks, so a bar keeps the running total in view.
  const cartBar = h('button', { class: 'cart-bar', hidden: true, onclick: () => document.querySelector('.order').scrollIntoView({ behavior: 'smooth', block: 'start' }) });
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
      customerId: sale.customerId, discount: t.discount, paid: t.paid, note: $('cart-note').value.trim(), items: sale.lines.length,
    };
    sale.lines.forEach((l, i) => {
      Object.assign(body, {
        ['productId.' + i]: l.productId, ['qty.' + i]: l.qty, ['unitPrice.' + i]: l.unitPrice,
        ['emptyId.' + i]: l.emptyId, ['swapFee.' + i]: isSwap(l) ? l.swapFee || 0 : '', ['remark.' + i]: l.remark.trim(),
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
    const before = sale.lines.length;
    sale.lines = sale.lines.filter((l) => byId(state.data.products, l.productId));
    renderCustomerPick();
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
    : l.emptyProductId === l.productId ? 'Empty in' : `Empty in: ${l.emptyProduct} (swap)`;

  function receiptView(rc) {
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
        row('Paid', peso.format(rc.paid)),
        rc.balance > 0 ? row('Balance to pay', peso.format(rc.balance), 'due') : null),
      rc.payments.length ? h('div', { class: 'rc-payments' },
        h('div', { class: 'rc-sub' }, 'Paid after the sale'),
        rc.payments.map((p) => row(`${fmtTime(p.time)} · ${p.staff}${p.note ? ' · ' + p.note : ''}`, peso.format(p.amount)))) : null,
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
      body: receiptView(rc),
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
      emptyId: l.emptyProductId || '', swapFee: l.swapFee || 0, remark: l.remark || '',
    }));
    sale.lastCustomerId = rc.customerId;
    for (const l of rc.lines) sale.own[l.productId] = (sale.own[l.productId] || 0) + l.qty;
    sale.discount = rc.discount ? String(rc.discount) : '';
    sale.laterPaid = round2(rc.paid - rc.paidAtSale);
    const due = round2(rc.total - sale.laterPaid);
    if (rc.paidAtSale >= due - 0.005) sale.pay = 'full';
    else if (rc.paidAtSale <= 0.005) sale.pay = 'later';
    else { sale.pay = 'partial'; sale.paidNow = String(rc.paidAtSale); }
    sale.note = rc.note || '';
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

  function paymentDialog({ customer, receipt }) {
    const owed = receipt ? receipt.balance : customer.balance;
    const who = receipt ? receipt.customer : fullName(customer);
    return formDialog({
      title: receipt ? `Payment for receipt #${receipt.id}` : `Collect payment from ${who}`,
      message: receipt ? `${who} owes ${peso.format(owed)} on this receipt.`
        : `${who} owes ${peso.format(owed)}. The payment goes to their oldest unpaid receipts first.`,
      submit: 'Record payment',
      fields: [
        { name: 'amount', label: 'Amount received (₱)', type: 'number', step: '0.01', min: '0.01', value: owed.toFixed(2), required: true, inputmode: 'decimal', full: true },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. cash, GCash reference' },
      ],
      onSubmit: (v) => mutate('POST', '/api/payments', { ...v, customerId: customer ? customer.id : '', receiptId: receipt ? receipt.id : '' },
        `Payment of ${peso.format(Number(v.amount))} recorded`),
    });
  }

  // ------------------------------------------------------------------ receipts list

  $('history-search').addEventListener('input', () => { pages.history = PAGE; renderHistory(); });
  for (const b of document.querySelectorAll('[data-filter]')) {
    b.addEventListener('click', () => { state.historyFilter = b.dataset.filter; pages.history = PAGE; renderHistory(); });
  }

  function renderHistory() {
    for (const b of document.querySelectorAll('[data-filter]')) b.setAttribute('aria-checked', String(b.dataset.filter === state.historyFilter));
    const q = $('history-search').value.trim();
    const unpaidOnly = state.historyFilter === 'unpaid';
    const rows = state.data.receipts.filter((r) => (!unpaidOnly || r.balance > 0)
      && matches(q, '#' + r.id, r.customer, r.staff, r.time, itemsText(r), r.note, r.lines.map((l) => l.remark).join(' ')));
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
      { label: '', cls: 'actions', render: (r) => actions(
        h('button', { class: 'btn small ghost', onclick: () => showReceipt(r.id) }, 'View'),
        r.balance > 0 ? h('button', { class: 'btn small ghost', onclick: () => paymentDialog({ receipt: r }) }, 'Record payment') : null) },
    ], shown, q || unpaidOnly ? 'No receipts match.' : 'No receipts yet.',
    (r) => `#${r.id} · ${itemsText(r)} · ${fmtTime(r.time)} · ${r.staff} · ${owedText(r)}`,
    (r) => showReceipt(r.id)),
    moreButton('history', shown.length, rows.length, renderHistory));
  }

  // ------------------------------------------------------------------ customers

  $('customers-search').addEventListener('input', renderCustomers);
  $('add-customer').addEventListener('click', () => customerDialog());

  function renderCustomers() {
    const q = $('customers-search').value.trim();
    const all = state.data.customers;
    const rows = all.filter((c) => matches(q, c.id, fullName(c), c.contact, c.address));
    const owing = all.filter((c) => c.balance > 0);
    const owed = owing.reduce((s, c) => s + c.balance, 0);
    const tanksOut = all.reduce((n, c) => n + c.tanksOwed, 0);
    $('customers-summary').textContent = `${plural(all.length, 'customer')}${owed > 0 ? ` · ${peso.format(owed)} unpaid by ${plural(owing.length, 'customer')}` : ''}${tanksOut ? ` · ${plural(tanksOut, 'tank')} not returned` : ''}`;
    clear($('customers-table'), table([
      { label: '#', render: (c) => c.id, cls: 'muted num', sm: 'hide' },
      { label: 'Name', render: (c) => fullName(c), sm: 'title' },
      { label: 'Contact', render: (c) => c.contact || '—', sm: 'hide' },
      { label: 'Address', render: (c) => c.address || '—', sm: 'hide' },
      { label: 'Last purchase', render: (c) => c.lastPurchase ? fmtTime(c.lastPurchase) : '—', sm: 'hide', cls: 'nowrap' },
      { label: 'Tanks out', right: true, render: (c) => c.tanksOwed ? h('span', { class: 'owes-text', title: tanksText(c) }, count.format(c.tanksOwed)) : '—', sm: 'hide' },
      { label: 'Balance', right: true, render: (c) => c.balance > 0 ? h('span', { class: 'owes-text' }, peso.format(c.balance)) : '—', sm: 'figure' },
      { label: '', cls: 'actions', render: (c) => actions(
        c.tanksOwed > 0 ? h('button', { class: 'btn small ghost', onclick: () => returnDialog(c) }, 'Collect tank') : null,
        c.balance > 0 ? h('button', { class: 'btn small ghost', onclick: () => paymentDialog({ customer: c }) }, 'Collect payment') : null,
        h('button', { class: 'btn small ghost', onclick: () => customerDialog(c) }, 'Edit'),
        h('button', { class: 'btn small ghost danger', onclick: () => deleteCustomer(c) }, 'Delete')) },
    ], rows, q ? 'No customers match your search.' : 'No customers yet.',
    (c) => [c.contact, lastText(c), c.tanksOwed ? `owes ${plural(c.tanksOwed, 'tank')}` : null].filter(Boolean).join(' · ')));
  }

  // Empties brought back by a customer who took tanks without one. Another brand is accepted (say so in the remark).
  function returnDialog(c) {
    if (!c.tanksOwed) return toast(`${fullName(c)} doesn’t owe any tanks`, true);
    const first = byId(state.data.products, c.owedTanks[0].productId) || state.data.products[0];
    formDialog({
      title: `Collect tank from ${fullName(c)}`,
      message: `Owes ${plural(c.tanksOwed, 'tank')}: ` + c.owedTanks.map((o) => `${o.qty} × ${o.product} (receipt #${o.receiptId}, ${fmtTime(o.time)})`).join('; ') + '.',
      submit: 'Record return',
      fields: [
        { name: 'productId', label: 'Empty tank brought back', type: 'select', full: true, value: first.id, options: productOptions((x) => x.label) },
        { name: 'qty', label: 'How many', type: 'number', min: 1, max: c.tanksOwed, step: 1, value: c.tanksOwed, required: true, inputmode: 'numeric' },
        { name: 'remark', label: 'Remark', full: true, placeholder: 'e.g. brought Solane instead of Petron; dented' },
      ],
      extra: (inputs) => {
        const hint = h('p', { class: 'warn-note', hidden: true });
        const check = () => {
          const x = byId(state.data.products, inputs.productId.value);
          const brands = new Set(c.owedTanks.map((o) => byId(state.data.products, o.productId)?.brand.toLowerCase()));
          hint.hidden = !x || brands.has(x.brand.toLowerCase());
          hint.textContent = x ? `${x.brand} isn’t the brand they took. That’s fine; add a remark about it.` : '';
        };
        inputs.productId.addEventListener('change', check);
        check();
        return hint;
      },
      onSubmit: (v) => mutate('POST', `/api/customers/${encodeURIComponent(c.id)}/returns`, v, `${fullName(c)} returned ${plural(Number(v.qty), 'tank')}`),
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
    const ok = await confirmDialog(`Delete ${fullName(c)}?`, 'This removes the customer record. Customers with receipts can’t be deleted.');
    if (ok) mutate('DELETE', '/api/customers/' + c.id, null, 'Customer deleted').catch((e) => toast(e.message, true));
  }

  // ------------------------------------------------------------------ products

  $('add-product').addEventListener('click', () => productDialog());

  function renderProducts() {
    const all = state.data.products;
    $('products-summary').textContent = plural(all.length, 'product');
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
      { label: '', cls: 'actions', render: (p) => actions(
        h('button', { class: 'btn small ghost', onclick: () => productDialog(p) }, 'Edit'),
        h('button', { class: 'btn small ghost danger', onclick: () => deleteProduct(p) }, 'Delete')) },
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

  function renderInventory() {
    const { products, suppliers, refills, movements } = state.data;
    const attention = products.filter((p) => p.status !== 'ok');
    clear($('inventory-alert'), attention.length ? h('div', { class: 'alert-banner', role: 'status' },
      h('span', { class: 'i', 'aria-hidden': 'true' }, '⚠'),
      h('div', {},
        h('strong', {}, `${plural(attention.length, 'product')} need${attention.length === 1 ? 's' : ''} refilling`),
        h('p', {}, attention.map((p) => `${p.label}: ${p.loaded <= 0 ? 'none with load' : p.loaded + ' with load'}${p.empty ? `, ${p.empty} empty to send` : ''}`).join(' · ')))) : null);

    const sum = (k) => products.reduce((n, p) => n + Math.max(0, p[k]), 0);
    const kgLoaded = products.reduce((n, p) => n + Math.max(0, p.loaded) * p.weight, 0);
    $('inventory-summary').textContent = `${count.format(sum('loaded'))} with load · ${count.format(sum('empty'))} empty · ${count.format(sum('atRefiller'))} at refiller · ${kgOf(kgLoaded)} of LPG`;
    // Totals per tank size (kg), across brands.
    const sizes = [];
    for (const p of [...products].sort((a, b) => a.weight - b.weight)) {
      let s = sizes.find((x) => x.weight === p.weight);
      if (!s) sizes.push(s = { weight: p.weight, loaded: 0, empty: 0, atRefiller: 0, kgLoaded: 0 });
      s.loaded += Math.max(0, p.loaded);
      s.empty += Math.max(0, p.empty);
      s.atRefiller += Math.max(0, p.atRefiller);
      s.kgLoaded = round2(s.loaded * s.weight);
    }
    Charts.sizeTable($('size-totals'), sizes, round2(kgLoaded));

    const out = state.data.customers.filter((c) => c.tanksOwed > 0).sort((a, b) => b.tanksOwed - a.tanksOwed);
    $('tanks-out-sub').textContent = out.length ? `${plural(out.reduce((n, c) => n + c.tanksOwed, 0), 'tank')} with ${plural(out.length, 'customer')}` : 'Customers who took a tank without an empty';
    clear($('tanks-out'),
      out.length ? h('div', { class: 'list' }, out.map((c) => h('div', { class: 'supplier-row' },
        h('div', {}, h('strong', {}, fullName(c)),
          h('div', { class: 'meta' }, `${tanksText(c)} · since ${fmtTime(c.owedTanks[0].time)}`),
          c.contact ? h('div', { class: 'meta' }, c.contact) : null),
        actions(h('button', { class: 'btn small', onclick: () => returnDialog(c) }, 'Collect tank'))))) : h('div', { class: 'empty' }, 'Every tank has come back.'),
      state.data.returns.length ? h('h3', { class: 'minor-head' }, 'Recently returned') : null,
      state.data.returns.length ? h('div', { class: 'moves' }, state.data.returns.slice(0, 8).map((t) => h('div', { class: 'move' },
        h('div', {}, h('strong', {}, t.customer), ` · ${t.qty} × ${t.product}`),
        h('div', { class: 'num secondary' }, `+${t.qty} empty`),
        h('div', { class: 'meta' }, [fmtTime(t.time), t.staff, t.remark].filter(Boolean).join(' · '))))) : null);

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
            : [h('strong', {}, 'No supplier set'), h('span', { class: 'muted' }, ' · add one under Suppliers below')])),
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

    // Trips still out, plus finished ones the refiller hasn't been fully paid for.
    const trips = refills.filter((r) => r.open || r.owed > 0);
    clear($('refills'), trips.length ? h('div', { class: 'list' }, trips.map((r) => {
      const waiting = r.items.filter((i) => i.outstanding > 0);
      const back = r.items.reduce((n, i) => n + i.received + i.rejected, 0);
      return h('div', { class: 'refill-row' },
        h('div', {},
          h('div', {}, h('strong', {}, `Trip #${r.id} · ${r.supplier}`)),
          h('div', { class: 'meta' }, `Sent ${fmtTime(r.time)} by ${r.staff}${r.note ? ' · ' + r.note : ''}${back ? ` · ${back} back so far` : ''}`),
          waiting.length ? h('div', { class: 'meta' }, 'Waiting: ' + waiting.map((i) => `${i.outstanding} × ${i.product}`).join(', ')) : h('div', { class: 'meta' }, 'All tanks back'),
          r.cost ? h('div', { class: 'meta' }, `Bill ${peso.format(r.cost)} · paid ${peso.format(r.paid)}`,
            r.owed > 0 ? h('span', { class: 'owes-text' }, ` · ${peso.format(r.owed)} still to pay`) : null) : null),
        actions(
          r.owed > 0 ? h('button', { class: 'btn small', onclick: () => payRefillerDialog(r) }, 'Pay refiller') : null,
          r.open ? h('button', { class: 'btn small primary', onclick: () => receiveRefillDialog(r.id) }, 'Receive') : null));
    })) : h('div', { class: 'empty' }, 'No tanks at the refiller right now.'));

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
        actions(
          s.owed > 0 ? h('button', { class: 'btn small', onclick: () => paySupplierDialog(s) }, 'Pay') : null,
          h('button', { class: 'btn small ghost', onclick: () => supplierDialog(s) }, 'Edit'),
          h('button', { class: 'btn small ghost danger', onclick: () => deleteSupplier(s) }, 'Delete'))))) : h('div', { class: 'empty' }, 'No suppliers yet. Add who refills each brand.'));

    clear($('movements'), movements.length ? h('div', { class: 'moves' }, movements.map((m) => h('div', { class: 'move' },
      h('div', {}, h('strong', {}, MOVE_LABEL[m.type] || m.type), ' · ', m.product, m.refillId ? h('span', { class: 'muted' }, ` · trip #${m.refillId}`) : null),
      h('div', { class: 'num' }, moveDeltas(m)),
      h('div', { class: 'meta' }, [fmtTime(m.time), m.staff, m.unitCost ? `${peso.format(m.unitCost)} each` : null, m.note].filter(Boolean).join(' · '))))) : h('div', { class: 'empty' }, 'Nothing recorded yet.'));
  }

  const needProducts = () => {
    if (state.data.products.length) return true;
    toast('Add an LPG product first', true);
    return false;
  };
  const productOptions = (fmt) => state.data.products.map((x) => ({ value: x.id, label: fmt(x) }));

  function sendRefillDialog(supplierId) {
    const { suppliers, products } = state.data;
    if (!suppliers.length) return toast('Add a supplier first (Inventory → Suppliers)', true);
    const sel = h('select', { class: 'input', name: 'supplierId' }, suppliers.map((s) => h('option', { value: s.id }, `${s.name} — ${s.brands.join(', ')}`)));
    if (supplierId) sel.value = supplierId;
    const rowsBox = h('div', { class: 'qty-rows' });
    const draw = () => {
      const list = products.filter((p) => p.supplierId === sel.value);
      clear(rowsBox, list.length ? list.map((p, i) => h('label', { class: 'qty-row' },
        h('span', {}, h('strong', {}, p.label), h('small', { class: 'muted' }, `${p.empty} empty on hand`)),
        h('input', { type: 'hidden', name: 'productId.' + i, value: p.id }),
        h('input', { class: 'input num', type: 'number', name: 'qty.' + i, min: 0, max: p.empty, step: 1, value: p.empty, inputmode: 'numeric', 'aria-label': `Empty ${p.label} tanks to send` })))
        : h('p', { class: 'muted' }, 'None of your products use this supplier’s brand yet.'));
    };
    sel.addEventListener('change', draw);
    draw();
    return openDialog({
      title: 'Send empty tanks to the refiller',
      message: 'They leave the store now and show as “at refiller” until you receive them back.',
      submit: 'Send tanks',
      body: [h('label', { class: 'field' }, h('span', {}, 'Refiller'), sel), rowsBox,
        h('label', { class: 'field' }, h('span', {}, 'Note (optional)'), h('input', { class: 'input', name: 'note', maxlength: 120, placeholder: 'e.g. truck plate, delivery receipt no.' }))],
      onSubmit: (form) => {
        const body = formBody(form);
        body.items = rowsBox.querySelectorAll('input[type=number]').length;
        return mutate('POST', '/api/refills', body, 'Tanks sent to the refiller');
      },
    });
  }

  function receiveRefillDialog(refillId) {
    const open = state.data.refills.filter((r) => r.open);
    if (!open.length) return toast('No tanks are at the refiller', true);
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
              h('input', { class: 'input num', type: 'number', name: 'received.' + i, min: 0, max: it.outstanding, step: 1, value: it.outstanding, inputmode: 'numeric', 'aria-label': `${it.product} back with load` })),
            h('label', {}, h('small', { class: 'm-only' }, 'Unfilled'),
              h('input', { class: 'input num', type: 'number', name: 'rejected.' + i, min: 0, max: it.outstanding, step: 1, value: 0, inputmode: 'numeric', 'aria-label': `${it.product} returned unfilled` })),
            h('label', {}, h('small', { class: 'm-only' }, 'Cost each (₱)'),
              h('input', { class: 'input num', type: 'number', name: 'cost.' + i, min: 0, step: '0.01', value: p && p.refillCost ? p.refillCost.toFixed(2) : '', placeholder: '0.00', inputmode: 'decimal', 'aria-label': `Refill cost per ${it.product}` })));
        }));
      paidEdited = false;
      updateCost();
    };
    // The bill follows the rows: tanks back with load × cost each. Unfilled tanks aren't charged.
    const costLine = h('div', { class: 'cost-line' });
    const paid = h('input', { class: 'input num', name: 'paid', type: 'number', min: 0, step: '0.01', inputmode: 'decimal' });
    let paidEdited = false;
    paid.addEventListener('input', () => { paidEdited = true; updateCost(); });
    function updateCost() {
      let cost = 0;
      rowsBox.querySelectorAll('.qty-row.four:not(.head)').forEach((row) => {
        cost += (Number(row.querySelector('[name^="received."]').value) || 0) * (Number(row.querySelector('[name^="cost."]').value) || 0);
      });
      const trip = open.find((x) => x.id === sel.value);
      const due = round2(cost + (trip ? trip.owed : 0));
      if (!paidEdited) paid.value = due.toFixed(2);
      const left = round2(due - (Number(paid.value) || 0));
      clear(costLine, h('span', {}, 'Refill bill'), h('strong', { class: 'num' }, peso.format(cost)),
        trip && trip.owed ? h('small', { class: 'muted' }, ` + ${peso.format(trip.owed)} still owed on this trip`) : null,
        left > 0 ? h('small', { class: 'owes-text' }, ` · ${peso.format(left)} left to pay later`) : null,
        left < 0 ? h('small', { class: 'error-text' }, `Paid is ${peso.format(-left)} more than the bill`) : null);
    }
    rowsBox.addEventListener('input', updateCost);
    sel.addEventListener('change', draw);
    draw();
    return openDialog({
      title: 'Receive from the refiller',
      message: 'Tanks back with load go on sale. Tanks returned unfilled come back as empties (not charged); say why in the note. Anything not entered stays “at refiller”.',
      submit: 'Receive tanks',
      wide: true,
      body: [h('label', { class: 'field' }, h('span', {}, 'Trip'), sel), rowsBox, costLine,
        h('label', { class: 'field inline' }, h('span', {}, 'Paid to refiller (₱)'), paid),
        h('label', { class: 'field' }, h('span', {}, 'Note (optional)'), h('input', { class: 'input', name: 'note', maxlength: 120, placeholder: 'e.g. delivery receipt no.' }))],
      onSubmit: (form) => {
        const body = formBody(form);
        body.items = rowsBox.querySelectorAll('input[name^="productId."]').length;
        return mutate('POST', `/api/refills/${encodeURIComponent(sel.value)}/receive`, body, 'Tanks received');
      },
    });
  }

  function payRefillerDialog(r) {
    formDialog({
      title: `Pay ${r.supplier}`,
      message: `Trip #${r.id}: bill ${peso.format(r.cost)}, paid ${peso.format(r.paid)}, ${peso.format(r.owed)} still to pay.`,
      submit: 'Record payment',
      fields: [
        { name: 'amount', label: 'Amount paid (₱)', type: 'number', min: '0.01', step: '0.01', value: r.owed.toFixed(2), required: true, inputmode: 'decimal', full: true },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. cash, bank transfer ref.' },
      ],
      onSubmit: (v) => mutate('POST', `/api/refills/${encodeURIComponent(r.id)}/pay`, v, `Paid ${peso.format(Number(v.amount))} to ${r.supplier}`),
    });
  }

  function purchaseDialog(p) {
    if (!needProducts()) return;
    formDialog({
      title: 'Buy new tanks',
      message: 'Brand-new tanks with load from the supplier (no empties swapped). Pay now, or pay part and the rest later.',
      submit: 'Add to stock',
      fields: [
        { name: 'productId', label: 'LPG product', type: 'select', full: true, value: (p || state.data.products[0]).id, options: productOptions((x) => `${x.label} (${x.loaded} with load)`) },
        { name: 'qty', label: 'Tanks bought', type: 'number', min: 1, step: 1, required: true, inputmode: 'numeric' },
        { name: 'unitCost', label: 'Cost per tank (₱)', type: 'number', min: 0, step: '0.01', inputmode: 'decimal', value: (p || state.data.products[0]).tankCost || '' },
        { name: 'paid', label: 'Paid to supplier now (₱)', type: 'number', min: 0, step: '0.01', inputmode: 'decimal', full: true },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. supplier, receipt no.' },
      ],
      // Picking another product fills in its usual new-tank cost; "paid" follows the bill until typed in.
      extra: (inputs) => {
        let paidEdited = false;
        inputs.paid.addEventListener('input', () => { paidEdited = true; show(); });
        inputs.productId.addEventListener('change', () => {
          const x = byId(state.data.products, inputs.productId.value);
          if (x) inputs.unitCost.value = x.tankCost || '';
        });
        const total = h('p', { class: 'cost-line' });
        const show = () => {
          const t = round2((Number(inputs.qty.value) || 0) * (Number(inputs.unitCost.value) || 0));
          if (!paidEdited) inputs.paid.value = t ? t.toFixed(2) : '';
          const left = round2(t - (Number(inputs.paid.value) || 0));
          const x = byId(state.data.products, inputs.productId.value);
          clear(total, h('span', {}, 'Bill'), h('strong', { class: 'num' }, peso.format(t)),
            left > 0 ? h('small', { class: 'owes-text' }, ` · ${peso.format(left)} to pay later${x && x.supplier ? ` (owed to ${x.supplier})` : ''}`) : null,
            left < 0 ? h('small', { class: 'error-text' }, ` Paid is ${peso.format(-left)} more than the bill`) : null);
        };
        inputs.qty.addEventListener('input', show);
        inputs.unitCost.addEventListener('input', show);
        inputs.productId.addEventListener('change', show);
        show();
        return total;
      },
      onSubmit: (v) => mutate('POST', '/api/stock/purchase', v, `Added ${v.qty} new tanks`),
    });
  }

  // Pays what a supplier is owed (refill trips and new tanks), oldest bills first.
  function paySupplierDialog(s) {
    formDialog({
      title: `Pay ${s.name}`,
      message: `Owed ${peso.format(s.owed)}: ` + s.bills.map((b) => `${b.label} (${fmtTime(b.time)}) ${peso.format(b.owed)}`).join('; ') + '. Oldest bills are paid first.',
      submit: 'Record payment',
      fields: [
        { name: 'amount', label: 'Amount paid (₱)', type: 'number', min: '0.01', step: '0.01', value: s.owed.toFixed(2), required: true, inputmode: 'decimal', full: true },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. cash, bank transfer ref.' },
      ],
      onSubmit: (v) => mutate('POST', `/api/suppliers/${encodeURIComponent(s.id)}/pay`, v, `Paid ${peso.format(Number(v.amount))} to ${s.name}`),
    });
  }

  function adjustDialog(p) {
    if (!needProducts()) return;
    const start = p || state.data.products[0];
    formDialog({
      title: 'Count or adjust stock',
      message: 'Enter what is physically in the store now. The difference is recorded. Tanks at the refiller aren’t counted here.',
      submit: 'Save count',
      fields: [
        { name: 'productId', label: 'LPG product', type: 'select', full: true, value: start.id, options: productOptions((x) => x.label) },
        { name: 'loaded', label: 'With load', type: 'number', min: 0, step: 1, value: Math.max(0, start.loaded), required: true, inputmode: 'numeric' },
        { name: 'empty', label: 'Empty', type: 'number', min: 0, step: 1, value: Math.max(0, start.empty), required: true, inputmode: 'numeric' },
        { name: 'reason', label: 'Reason', type: 'select', full: true, value: 'count', options: [
          { value: 'count', label: 'Stock count (recount)' },
          { value: 'damaged', label: 'Write-off (lost, stolen, leaking, scrapped)' },
          { value: 'other', label: 'Other' },
        ] },
        { name: 'note', label: 'Note', full: true, placeholder: 'Required for write-offs and other changes' },
      ],
      extra: (inputs) => {
        inputs.productId.addEventListener('change', () => {
          const x = byId(state.data.products, inputs.productId.value);
          if (x) {
            inputs.loaded.value = Math.max(0, x.loaded);
            inputs.empty.value = Math.max(0, x.empty);
          }
        });
        return null;
      },
      onSubmit: (v) => mutate('POST', '/api/stock/adjust', v, 'Stock updated'),
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

  for (const chip of document.querySelectorAll('#view-dashboard [data-range]')) {
    chip.addEventListener('click', () => { state.summaryRange = chip.dataset.range; state.summaryAt = 0; renderDashboard(); });
  }

  async function renderDashboard() {
    for (const c of document.querySelectorAll('#view-dashboard [data-range]')) c.setAttribute('aria-pressed', String(c.dataset.range === state.summaryRange));
    if (Date.now() - state.summaryAt > 3000) {
      state.summaryAt = Date.now();
      $('dash-body').classList.add('loading');
      try {
        state.summary = await api('GET', '/api/summary?range=' + encodeURIComponent(state.summaryRange));
      } catch (e) {
        if (e.status === 403) { state.me.dashboard = false; updateDashboardAccess(); return go('sell'); }
        toast(e.message, true);
      } finally {
        $('dash-body').classList.remove('loading');
      }
    }
    drawDashboard();
  }

  function drawDashboard() {
    const d = state.summary;
    if (!d || state.view !== 'dashboard') return;
    const s = d.stats, t = s.totals, p = s.previous, inv = d.inventory;
    $('d-revenue').textContent = UI.pesoRound.format(t.revenue);
    Charts.delta($('d-revenue-delta'), t.revenue, p && p.revenue, s.compare);
    $('d-trend-title').textContent = `Revenue by ${{ hour: 'hour', day: 'day', month: 'month' }[s.bucket]}`;
    $('d-trend-sub').textContent = s.range === 'today' ? 'Today, hour by hour' : `${Charts.fmtDate(s.start)} – today`;
    Charts.columnChart($('d-trend'), s.series, s.bucket);
    const tile = (id, value, cur, prev, upIsBad) => { $(id).textContent = value; Charts.delta($(id + '-delta'), cur, prev, s.compare, upIsBad); };
    tile('d-count', count.format(t.count), t.count, p && p.count);
    tile('d-qty', count.format(t.qty), t.qty, p && p.qty);
    tile('d-collected', UI.pesoRound.format(t.collected), t.collected, p && p.collected);
    tile('d-supplier', UI.pesoRound.format(t.supplierPaid), t.supplierPaid, p && p.supplierPaid, true);
    tile('d-profit', t.profit == null ? '—' : UI.pesoRound.format(t.profit), t.profit, p && p.profit);
    $('d-profit-note').textContent = t.profit == null ? 'Set refill costs in Products to see profit'
      : s.uncosted.length ? `Leaves out ${s.uncosted.join(', ')} (no refill cost)` : `${t.costedRevenue ? Math.round(t.profit / t.costedRevenue * 100) : 0}% of sales`;
    Charts.hbars($('d-products'), s.byProduct, (r) => `${count.format(r.qty)} tank${r.qty === 1 ? '' : 's'}`, 'No sales in this period.');
    $('d-size-sub').textContent = `${count.format(inv.kgLoaded)} kg of LPG with load`;
    Charts.sizeTable($('d-sizes'), inv.bySize, inv.kgLoaded);

    const owedRow = (name, meta, figure) => h('div', { class: 'owe-row' }, h('div', {}, h('div', {}, name), meta ? h('div', { class: 'meta' }, meta) : null), h('strong', { class: 'num' }, figure));
    const to = d.tanksOut;
    $('d-tanks-sub').textContent = to.total ? `${plural(to.total, 'tank')} with ${plural(to.customers, 'customer')}` : 'Every tank has come back';
    clear($('d-tanks'), to.top.length ? to.top.map((c) => owedRow(c.customer, `${c.items}${c.since ? ` · since ${fmtTime(c.since)}` : ''}`, plural(c.tanks, 'tank')))
      : h('div', { class: 'empty' }, 'No customer owes a tank.'));
    const rv = d.receivables;
    $('d-unpaid-sub').textContent = rv.customers ? `${peso.format(rv.total)} from ${plural(rv.customers, 'customer')}` : 'Nobody owes anything';
    clear($('d-unpaid'), rv.top.length ? rv.top.map((c) => owedRow(c.customer, `${plural(c.receipts, 'unpaid receipt')}${c.since ? ` · oldest ${fmtTime(c.since)}` : ''}`, peso.format(c.balance)))
      : h('div', { class: 'empty' }, 'All receipts are paid.'));
    const owing = inv.suppliers.filter((x) => x.owed > 0);
    $('d-suppliers-sub').textContent = inv.owedToSuppliers > 0 ? `${peso.format(inv.owedToSuppliers)} still to pay` : 'Suppliers are fully paid';
    clear($('d-suppliers'), owing.length ? owing.map((x) => owedRow(x.name, x.brands.join(', '), peso.format(x.owed)))
      : h('div', { class: 'empty' }, 'Nothing owed to suppliers.'));
    $('d-updated').textContent = 'Updated ' + new Date().toLocaleTimeString('en-PH', { hour: 'numeric', minute: '2-digit' });
  }

  let dashResize;
  new ResizeObserver(() => { clearTimeout(dashResize); dashResize = setTimeout(drawDashboard, 80); }).observe($('d-trend'));
  setInterval(() => {
    if (state.view === 'dashboard' && document.visibilityState === 'visible') { state.summaryAt = 0; renderDashboard(); }
  }, 30000);

  // ------------------------------------------------------------------ boot

  window.addEventListener('hashchange', () => state.me && go(location.hash.slice(1)));

  // Keep in sync with other devices, but never while someone is typing in a dialog.
  setInterval(() => {
    if (state.me && document.visibilityState === 'visible' && !document.querySelector('dialog[open]')) {
      refresh().catch(() => {});
    }
  }, 20000);
  document.addEventListener('visibilitychange', () => {
    if (state.me && document.visibilityState === 'visible') refresh().catch(() => {});
  });

  // Only a failed /api/me means "signed out"; a bug while drawing must not pose as a sign-in prompt.
  request('GET', '/api/me').then(showApp, () => showLogin());
})();
