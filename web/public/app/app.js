// LPG POS — staff web app.
(function () {
  const { h, clear, peso, count, toast, request, formDialog, confirmDialog, openDialog, fmtTime } = UI;
  const $ = (id) => document.getElementById(id);

  const EMPTY_DATA = { customers: [], products: [], receipts: [], movements: [], suppliers: [], refills: [], brands: [] };
  // own: tanks the receipt being edited already holds, so they count as available again.
  // laterPaid: payments recorded after the sale on the receipt being edited.
  const newSale = () => ({ customerId: null, lines: [], discount: '', pay: 'full', paidNow: '', cash: '', note: '', editing: null, own: {}, laterPaid: 0 });
  const state = { me: null, data: EMPTY_DATA, etag: null, view: 'sell', sale: newSale(), historyFilter: 'all' };
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
    updateBadges();
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
    go(location.hash.slice(1) || 'sell');
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

  const VIEWS = ['sell', 'history', 'customers', 'products', 'inventory'];

  function go(view) {
    if (!VIEWS.includes(view)) view = 'sell';
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
    ({ sell: renderSell, history: renderHistory, customers: renderCustomers, products: renderProducts, inventory: renderInventory })[state.view](passive);
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
  const itemsText = (r) => r.lines.map((l) => `${l.qty} × ${l.product}`).join(', ');
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
    if (id) { state.sale.customerId = id; $('sell-search').value = ''; renderCustomerPick(); renderTotals(); }
  });

  function renderCustomerPick() {
    const { customers, receipts } = state.data;
    const sale = state.sale;
    if (sale.customerId && !byId(customers, sale.customerId)) sale.customerId = null;
    const chosen = byId(customers, sale.customerId);
    $('sell-search-wrap').hidden = !!chosen;
    clear($('sell-selected'), chosen ? h('div', { class: 'pick selected-customer' },
      h('span', { class: 'avatar' }, initials(chosen)),
      h('div', {},
        h('div', { class: 'name' }, fullName(chosen), chosen.balance > 0 ? h('span', { class: 'owes' }, `Owes ${peso.format(chosen.balance)}`) : null),
        h('div', { class: 'meta' }, [chosen.contact, chosen.address].filter(Boolean).join(' · ') || 'No contact details')),
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
      shown.map((c) => h('button', { class: 'pick', onclick: () => { sale.customerId = c.id; renderCustomerPick(); renderTotals(); } },
        h('span', { class: 'avatar' }, initials(c)),
        h('div', {},
          h('div', { class: 'name' }, fullName(c), c.balance > 0 ? h('span', { class: 'owes' }, `Owes ${peso.format(c.balance)}`) : null),
          h('div', { class: 'meta' }, [c.contact, c.address].filter(Boolean).join(' · ') || '—')),
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
      p.tankPriceSet ? h('span', { class: 'tank-price' }, `New tank ${peso.format(p.tankPrice)}`) : null,
      h('span', { class: 'stock ' + (left <= 0 && n ? 'out' : p.status) }, n ? `${left} more with load` : stockText(p)),
      n ? h('span', { class: 'in-cart', 'aria-hidden': 'true' }, n) : null);
    }) : h('div', { class: 'empty' }, 'No LPG products yet. Add them in the Products tab.'));
  }

  function addToCart(p) {
    const sale = state.sale;
    if (inCart(p.id) + 1 > available(p.id)) return toast(`Only ${available(p.id)} × ${p.label} with load`, true);
    const same = sale.lines.find((l) => l.productId === p.id && l.emptyId === p.id && !l.remark && !l.priceEdited);
    if (same) same.qty++;
    else sale.lines.push({ key: ++lineKey, productId: p.id, qty: 1, unitPrice: p.price, priceEdited: false, emptyId: p.id, remark: '' });
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

  function emptyOptions(p) {
    const others = state.data.products.filter((x) => x.id !== p.id);
    const sameSize = others.filter((x) => x.weight === p.weight);
    const rest = others.filter((x) => x.weight !== p.weight);
    return [
      h('option', { value: p.id }, `Same: ${p.label}`),
      sameSize.length ? h('optgroup', { label: 'Other brand, same size' }, sameSize.map((x) => h('option', { value: x.id }, x.label))) : null,
      rest.length ? h('optgroup', { label: 'Other size' }, rest.map((x) => h('option', { value: x.id }, x.label))) : null,
      h('option', { value: '' }, 'None — new tank'),
    ];
  }

  function lineEditor(l) {
    const p = byId(state.data.products, l.productId);
    if (!p) return null;
    const amount = h('span', { class: 'num line-amount' });
    const showAmount = () => { amount.textContent = peso.format(l.qty * l.unitPrice); };
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
    const empty = h('select', { class: 'input', 'aria-label': `Empty tank returned for ${p.label}` }, emptyOptions(p));
    empty.value = l.emptyId;
    empty.addEventListener('change', () => {
      l.emptyId = empty.value;
      if (!l.priceEdited) { // refill price with an empty, new-tank price without
        l.unitPrice = l.emptyId ? p.price : p.tankPrice;
        price.value = l.unitPrice.toFixed(2);
        showAmount();
        renderTotals();
      }
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
    const subtotal = round2(sale.lines.reduce((s, l) => s + l.qty * l.unitPrice, 0));
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
        ['emptyId.' + i]: l.emptyId, ['remark.' + i]: l.remark.trim(),
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

  const emptyText = (l) => !l.emptyProductId ? 'New tank (no empty in)'
    : l.emptyProductId === l.productId ? 'Empty in' : `Empty in: ${l.emptyProduct}`;

  function receiptView(rc) {
    const row = (label, value, cls) => h('div', { class: 'rc-row' + (cls ? ' ' + cls : '') }, h('span', {}, label), h('span', { class: 'num' }, value));
    return h('div', { class: 'receipt' },
      h('div', { class: 'rc-head' }, h('strong', {}, 'LPG POS'), h('span', {}, `Receipt #${rc.id}`)),
      h('div', { class: 'rc-meta' }, `${fmtTime(rc.time, { full: true })} · Served by ${rc.staff}`),
      h('div', { class: 'rc-meta' }, `Customer: ${rc.customer}`),
      h('div', { class: 'rc-lines' }, rc.lines.map((l) => h('div', { class: 'rc-line' },
        h('div', {}, h('div', {}, `${l.qty} × ${l.product}`), h('div', { class: 'rc-sub' }, `${peso.format(l.unitPrice)} each · ${emptyText(l)}`),
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
    openDialog({
      title: `Receipt #${rc.id}`,
      submit: null,
      cancel: 'Close',
      body: receiptView(rc),
      actions: (close) => [
        h('button', { type: 'button', class: 'btn danger', onclick: () => { close(); voidReceipt(rc); } }, 'Void'),
        h('button', { type: 'button', class: 'btn', onclick: () => { close(); editReceipt(rc); } }, 'Edit'),
        h('button', { type: 'button', class: 'btn', onclick: () => printReceipt(rc) }, 'Print'),
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
      emptyId: l.emptyProductId || '', remark: l.remark || '',
    }));
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
    $('customers-summary').textContent = `${plural(all.length, 'customer')}${owed > 0 ? ` · ${peso.format(owed)} unpaid by ${plural(owing.length, 'customer')}` : ''}`;
    clear($('customers-table'), table([
      { label: '#', render: (c) => c.id, cls: 'muted num', sm: 'hide' },
      { label: 'Name', render: (c) => fullName(c), sm: 'title' },
      { label: 'Contact', render: (c) => c.contact || '—', sm: 'hide' },
      { label: 'Address', render: (c) => c.address || '—', sm: 'hide' },
      { label: 'Receipts', right: true, render: (c) => c.receipts, sm: 'hide' },
      { label: 'Balance', right: true, render: (c) => c.balance > 0 ? h('span', { class: 'owes-text' }, peso.format(c.balance)) : '—', sm: 'figure' },
      { label: '', cls: 'actions', render: (c) => actions(
        c.balance > 0 ? h('button', { class: 'btn small ghost', onclick: () => paymentDialog({ customer: c }) }, 'Collect payment') : null,
        h('button', { class: 'btn small ghost', onclick: () => customerDialog(c) }, 'Edit'),
        h('button', { class: 'btn small ghost danger', onclick: () => deleteCustomer(c) }, 'Delete')) },
    ], rows, q ? 'No customers match your search.' : 'No customers yet.',
    (c) => [c.contact, c.address, plural(c.receipts, 'receipt')].filter(Boolean).join(' · ')));
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
      { label: 'New tank price', right: true, render: (p) => p.tankPriceSet ? peso.format(p.tankPrice) : h('span', { class: 'muted' }, 'Not set'), sm: 'hide' },
      { label: 'Refill cost', right: true, render: (p) => p.refillCost ? peso.format(p.refillCost) : h('span', { class: 'muted' }, 'Not set'), sm: 'hide' },
      { label: 'Margin', right: true, render: (p) => p.refillCost ? peso.format(p.price - p.refillCost) : '—', sm: 'hide' },
      { label: 'Sold', right: true, render: (p) => count.format(p.sold), sm: 'hide' },
      { label: 'With load', right: true, render: (p) => count.format(p.loaded), sm: 'hide' },
      { label: '', cls: 'actions', render: (p) => actions(
        h('button', { class: 'btn small ghost', onclick: () => productDialog(p) }, 'Edit'),
        h('button', { class: 'btn small ghost danger', onclick: () => deleteProduct(p) }, 'Delete')) },
    ], all, 'No LPG products yet.',
    (p) => `${kg(p.weight)} · new tank ${p.tankPriceSet ? peso.format(p.tankPrice) : 'not set'} · refill cost ${p.refillCost ? peso.format(p.refillCost) : 'not set'} · ${count.format(p.sold)} sold · ${stockText(p)}`));
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
          help: 'Customer brings an empty tank' },
        { name: 'tankPrice', label: 'New tank price (₱)', type: 'number', step: '0.01', min: '0.01', value: p && p.tankPriceSet ? p.tankPrice : '', inputmode: 'decimal',
          help: 'No empty in; includes the tank' },
        { name: 'refillCost', label: 'Refill cost (₱)', type: 'number', step: '0.01', min: '0', value: p && p.refillCost ? p.refillCost : '', inputmode: 'decimal',
          help: 'What the refiller charges per tank' },
        { name: 'tankCost', label: 'New tank cost (₱)', type: 'number', step: '0.01', min: '0', value: p && p.tankCost ? p.tankCost : '', inputmode: 'decimal',
          help: 'What you pay for a new tank with load' },
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
    $('inventory-summary').textContent = `${count.format(sum('loaded'))} with load · ${count.format(sum('empty'))} empty · ${count.format(sum('atRefiller'))} at refiller`;

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
          s.note ? h('div', { class: 'meta' }, s.note) : null),
        actions(
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
        : h('p', { class: 'muted' }, 'None of your products use this supplier’s brands yet.'));
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
      message: 'Brand-new tanks with load from the supplier (no empties swapped).',
      submit: 'Add to stock',
      fields: [
        { name: 'productId', label: 'LPG product', type: 'select', full: true, value: (p || state.data.products[0]).id, options: productOptions((x) => `${x.label} (${x.loaded} with load)`) },
        { name: 'qty', label: 'Tanks bought', type: 'number', min: 1, step: 1, required: true, inputmode: 'numeric' },
        { name: 'unitCost', label: 'Cost per tank (₱)', type: 'number', min: 0, step: '0.01', inputmode: 'decimal', value: (p || state.data.products[0]).tankCost || '' },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. supplier, receipt no.' },
      ],
      // Picking another product fills in its usual new-tank cost.
      extra: (inputs) => {
        inputs.productId.addEventListener('change', () => {
          const x = byId(state.data.products, inputs.productId.value);
          if (x) inputs.unitCost.value = x.tankCost || '';
        });
        const total = h('p', { class: 'cost-line' });
        const show = () => {
          const t = (Number(inputs.qty.value) || 0) * (Number(inputs.unitCost.value) || 0);
          clear(total, h('span', {}, 'Paid to supplier'), h('strong', { class: 'num' }, peso.format(t)));
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
    const brands = state.data.brands;
    formDialog({
      title: s ? `Edit ${s.name}` : 'New supplier',
      submit: s ? 'Save changes' : 'Add supplier',
      fields: [
        { name: 'name', label: 'Name', value: s && s.name, required: true, full: true, placeholder: 'e.g. Petron depot' },
        { name: 'contact', label: 'Contact', value: s && s.contact, full: true, placeholder: 'Phone or person' },
        { name: 'brands', label: 'Brands they refill', value: s ? s.brands.join(', ') : '', required: true, full: true,
          help: brands.length ? `Separate with commas. Your brands: ${brands.join(', ')}` : 'Separate with commas' },
        { name: 'note', label: 'Note (optional)', value: s && s.note, full: true, placeholder: 'e.g. picks up Mon/Thu, 2-day turnaround' },
      ],
      onSubmit: (v) => s
        ? mutate('PUT', '/api/suppliers/' + s.id, v, 'Supplier updated')
        : mutate('POST', '/api/suppliers', v, `Added ${v.name}`),
    });
  }

  async function deleteSupplier(s) {
    const ok = await confirmDialog(`Delete ${s.name}?`, 'Its brands will show as “No supplier set”. Past refill trips stay in the history.');
    if (ok) mutate('DELETE', '/api/suppliers/' + s.id, null, 'Supplier deleted').catch((e) => toast(e.message, true));
  }

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
