// LPG POS — staff web app.
(function () {
  const { h, clear, peso, count, toast, request, formDialog, confirmDialog, fmtTime } = UI;
  const $ = (id) => document.getElementById(id);

  const state = {
    me: null,
    data: { customers: [], products: [], transactions: [], movements: [] },
    view: 'sell',
    sale: { customerId: null, productId: null, qty: 1 },
  };

  // ------------------------------------------------------------------ API

  async function api(method, path, data) {
    try {
      return await request(method, path, data);
    } catch (e) {
      if (e.status === 401 && path !== '/api/login') showLogin('Your session ended. Please sign in again.');
      throw e;
    }
  }

  async function refresh() {
    state.data = await api('GET', '/api/data');
    const attention = state.data.products.filter((p) => p.status !== 'ok').length;
    for (const id of ['tab-alert', 'tab-alert-sm']) {
      $(id).hidden = !attention;
      $(id).textContent = attention;
    }
    render();
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

  function go(view) {
    if (!['sell', 'history', 'customers', 'products', 'inventory'].includes(view)) view = 'sell';
    state.view = view;
    if (location.hash.slice(1) !== view) history.replaceState(null, '', '#' + view);
    for (const s of document.querySelectorAll('.view')) s.hidden = s.id !== 'view-' + view;
    for (const t of document.querySelectorAll('.tab')) {
      if (t.dataset.view === view) t.setAttribute('aria-current', 'page');
      else t.removeAttribute('aria-current');
    }
    render();
    window.scrollTo({ top: 0 });
    if (state.me) refresh().catch((e) => toast(e.message, true));
  }

  document.addEventListener('click', (e) => {
    const t = e.target.closest('[data-view], [data-goto]');
    if (t) go(t.dataset.view || t.dataset.goto);
    const menu = document.querySelector('.usermenu');
    if (menu.open && !e.target.closest('.usermenu')) menu.open = false;
  });

  function render() {
    if (!state.me) return;
    ({ sell: renderSell, history: renderHistory, customers: renderCustomers, products: renderProducts, inventory: renderInventory })[state.view]();
  }

  // ------------------------------------------------------------------ helpers

  const byId = (list, id) => list.find((x) => x.id === id);
  const fullName = (c) => `${c.firstName} ${c.lastName}`.trim();
  const initials = (c) => ((c.firstName || '?')[0] + (c.lastName || '')[0]).toUpperCase();
  const kg = (w) => (Number.isInteger(w) ? w.toFixed(1) : String(w)) + ' kg';
  const matches = (q, ...fields) => !q || fields.join(' ').toLowerCase().includes(q.toLowerCase());
  const STATUS = { ok: ['●', 'In stock'], low: ['▲', 'Low'], out: ['✕', 'Out of stock'] };
  const statusPill = (p) => h('span', { class: 'status ' + p.status }, h('span', { class: 'i', 'aria-hidden': 'true' }, STATUS[p.status][0]), STATUS[p.status][1]);
  const stockText = (p) => p.status === 'out' ? 'Out of stock' : p.status === 'low' ? `Low · ${p.full} left` : `${p.full} in stock`;
  const plural = (n, word) => `${count.format(n)} ${word}${n === 1 ? '' : 's'}`;

  // On phones each row collapses to: title + figure, one detail line, then the actions.
  // Columns opt in with sm: 'title' | 'figure' | 'hide'; detail(row) builds the phone-only line.
  function table(columns, rows, empty, detail) {
    if (!rows.length) return h('div', { class: 'empty' }, empty);
    const cls = (c) => [c.cls || (c.right ? 'right num' : ''), c.sm ? 'sm-' + c.sm : ''].join(' ').trim() || null;
    return h('table', { class: 'rtable' },
      h('thead', {}, h('tr', {}, columns.map((c) => h('th', { class: c.right ? 'right' : null }, c.label)))),
      h('tbody', {}, rows.map((r) => h('tr', {},
        columns.map((c) => h('td', { class: cls(c) }, c.render(r))),
        detail ? h('td', { class: 'sm-detail' }, detail(r)) : null))));
  }

  function actions(...buttons) {
    return h('div', { class: 'row-actions' }, buttons);
  }

  // ------------------------------------------------------------------ new sale

  $('sell-search').addEventListener('input', renderSell);
  $('qty').addEventListener('input', () => { state.sale.qty = clampQty($('qty').value); renderOrder(); });
  $('qty').addEventListener('blur', () => { $('qty').value = state.sale.qty; });
  $('qty-minus').addEventListener('click', () => setQty(state.sale.qty - 1));
  $('qty-plus').addEventListener('click', () => setQty(state.sale.qty + 1));
  $('quick-customer').addEventListener('click', async () => {
    const id = await customerDialog();
    if (id) { state.sale.customerId = id; $('sell-search').value = ''; renderSell(); }
  });
  $('process').addEventListener('click', processSale);

  function clampQty(v) {
    const p = byId(state.data.products, state.sale.productId);
    const max = p ? Math.max(1, Math.min(100, p.full)) : 100;
    const n = Math.round(Number(v));
    return Number.isFinite(n) ? Math.min(max, Math.max(1, n)) : 1;
  }

  function setQty(n) {
    state.sale.qty = clampQty(n);
    $('qty').value = state.sale.qty;
    renderOrder();
  }

  function renderSell() {
    const { customers, products, transactions } = state.data;
    const sale = state.sale;
    if (sale.customerId && !byId(customers, sale.customerId)) sale.customerId = null;
    const picked = byId(products, sale.productId);
    if (sale.productId && (!picked || picked.full <= 0)) sale.productId = null;

    // Customer: either the chosen one, or search results
    const chosen = byId(customers, sale.customerId);
    $('sell-search-wrap').hidden = !!chosen;
    clear($('sell-selected'), chosen ? h('div', { class: 'pick selected-customer' },
      h('span', { class: 'avatar' }, initials(chosen)),
      h('div', {}, h('div', { class: 'name' }, fullName(chosen)), h('div', { class: 'meta' }, [chosen.contact, chosen.address].filter(Boolean).join(' · ') || 'No contact details')),
      h('button', { class: 'btn small', onclick: () => { sale.customerId = null; renderSell(); $('sell-search').focus(); } }, 'Change')) : null);

    if (!chosen) {
      const q = $('sell-search').value.trim();
      let list;
      if (q) {
        list = customers.filter((c) => matches(q, c.id, fullName(c), c.contact, c.address));
      } else {
        // Most recent buyers first, then everyone else
        const order = [];
        for (const t of transactions) if (!order.includes(t.customerId)) order.push(t.customerId);
        list = [...customers].sort((a, b) => rank(order, a.id) - rank(order, b.id));
      }
      const shown = list.slice(0, 6);
      clear($('sell-results'),
        shown.map((c) => h('button', { class: 'pick', onclick: () => { sale.customerId = c.id; renderSell(); } },
          h('span', { class: 'avatar' }, initials(c)),
          h('div', {}, h('div', { class: 'name' }, fullName(c)), h('div', { class: 'meta' }, [c.contact, c.address].filter(Boolean).join(' · ') || '—')),
          h('span', { class: 'muted' }, '#' + c.id))),
        !customers.length ? h('div', { class: 'empty' }, 'No customers yet. Tap “+ New” to add one.')
          : !shown.length ? h('div', { class: 'empty' }, 'No customer matches “' + q + '”.')
          : list.length > shown.length ? h('div', { class: 'more-hint' }, `Showing 6 of ${list.length} — keep typing to narrow down`) : null);
    }

    // Products
    clear($('sell-products'),
      products.length ? products.map((p) => h('button', {
        class: 'product', 'aria-pressed': String(p.id === sale.productId), disabled: p.full <= 0,
        'aria-label': `${p.brand}, ${kg(p.weight)}, ${peso.format(p.price)}, ${stockText(p)}`,
        onclick: () => {
          sale.productId = sale.productId === p.id ? null : p.id;
          setQty(sale.qty);
          renderSell();
          if (sale.productId && sale.customerId && window.matchMedia('(max-width: 960px)').matches) {
            document.querySelector('.order').scrollIntoView({ behavior: 'smooth', block: 'center' });
          }
        },
      }, h('span', { class: 'brand-name' }, p.brand), h('span', { class: 'kg' }, kg(p.weight)), h('span', { class: 'price num' }, peso.format(p.price)), h('span', { class: 'stock ' + p.status }, stockText(p))))
        : h('div', { class: 'empty' }, 'No LPG products yet. Add them in the Products tab.'));

    // Latest sales
    const recent = transactions.slice(0, 5);
    clear($('sell-recent'), recent.length ? h('div', { class: 'mini-list' }, recent.map((t) => h('div', { class: 'mini-row' },
      h('div', {}, h('div', {}, t.customer), h('div', { class: 'meta' }, `${t.qty} × ${t.product} · ${fmtTime(t.time)}`)),
      h('strong', { class: 'num' }, peso.format(t.amount))))) : h('div', { class: 'empty' }, 'No sales yet.'));

    renderOrder();
  }

  function rank(order, id) {
    const i = order.indexOf(id);
    return i < 0 ? 1e9 - Number(id || 0) : i;
  }

  function renderOrder() {
    const c = byId(state.data.customers, state.sale.customerId);
    const p = byId(state.data.products, state.sale.productId);
    $('order-customer').textContent = c ? fullName(c) : 'Not selected';
    $('order-customer').classList.toggle('muted', !c);
    $('order-product').textContent = p ? p.label : 'Not selected';
    $('order-product').classList.toggle('muted', !p);
    $('order-unit').textContent = p ? peso.format(p.price) : '—';
    $('order-total').textContent = peso.format(p ? p.price * state.sale.qty : 0);
    $('process').disabled = !(c && p);
  }

  async function processSale() {
    const btn = $('process');
    btn.disabled = true;
    try {
      const { customerId, productId, qty } = state.sale;
      const returnedEmpty = $('returned-empty').checked ? '1' : '0';
      await mutate('POST', '/api/transactions', { customerId, productId, qty, returnedEmpty }, (r) => `Sale #${r.id} recorded · ${peso.format(r.amount)}`);
      state.sale = { customerId: null, productId: null, qty: 1 };
      $('qty').value = 1;
      $('returned-empty').checked = true;
      $('sell-search').value = '';
      renderSell();
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (e) {
      toast(e.message, true);
      renderOrder();
    }
  }

  // ------------------------------------------------------------------ sales history

  // Draw sales in pages so phones stay quick as history grows; totals and search still cover everything.
  const PAGE = 100;
  let historyLimit = PAGE;
  $('history-search').addEventListener('input', () => { historyLimit = PAGE; renderHistory(); });

  function renderHistory() {
    const q = $('history-search').value.trim();
    const rows = state.data.transactions.filter((t) => matches(q, '#' + t.id, t.customer, t.product, t.staff, t.time));
    const total = rows.reduce((s, t) => s + t.amount, 0);
    const shown = rows.slice(0, historyLimit);
    $('history-summary').textContent = `${count.format(rows.length)} sale${rows.length === 1 ? '' : 's'} · ${peso.format(total)}`;
    clear($('history-table'), table([
      { label: '#', render: (t) => t.id, cls: 'muted num', sm: 'hide' },
      { label: 'Date', render: (t) => fmtTime(t.time), sm: 'hide' },
      { label: 'Customer', render: (t) => t.customer, sm: 'title' },
      { label: 'Product', render: (t) => [t.product, t.returnedEmpty ? null : h('div', { class: 'cell-sub' }, 'New tank')], sm: 'hide' },
      { label: 'Qty', right: true, render: (t) => t.qty, sm: 'hide' },
      { label: 'Amount', right: true, render: (t) => peso.format(t.amount), sm: 'figure' },
      { label: 'Sold by', render: (t) => t.staff, cls: 'secondary', sm: 'hide' },
      { label: '', cls: 'actions', render: (t) => actions(
        h('button', { class: 'btn small ghost', onclick: () => editSale(t) }, 'Edit'),
        h('button', { class: 'btn small ghost danger', onclick: () => deleteSale(t) }, 'Delete')) },
    ], shown, q ? 'No sales match your search.' : 'No sales recorded yet.',
    (t) => `#${t.id} · ${t.qty} × ${t.product}${t.returnedEmpty ? '' : ' (new tank)'} · ${fmtTime(t.time)} · ${t.staff}`),
    rows.length > shown.length ? h('div', { class: 'more-row' },
      h('button', { class: 'btn', onclick: () => { historyLimit += PAGE; renderHistory(); } },
        `Show more (${count.format(rows.length - shown.length)} older)`)) : null);
  }

  function editSale(t) {
    const { customers, products } = state.data;
    const customerOptions = customers.map((c) => ({ value: c.id, label: `${fullName(c)} (#${c.id})` }));
    const productOptions = products.map((p) => ({ value: p.id, label: `${p.label} — ${peso.format(p.price)} (${p.full} in stock)` }));
    formDialog({
      title: `Edit sale #${t.id}`,
      message: `Recorded ${fmtTime(t.time, { full: true })} by ${t.staff}. The date stays the same.`,
      fields: [
        { name: 'customerId', label: 'Customer', type: 'select', options: customerOptions, value: t.customerId, full: true },
        { name: 'productId', label: 'LPG product', type: 'select', options: productOptions, value: t.productId, full: true },
        { name: 'qty', label: 'Quantity', type: 'number', min: 1, max: 100, step: 1, value: t.qty, required: true, inputmode: 'numeric' },
        { name: 'returnedEmpty', label: 'Empty returned?', type: 'select', value: t.returnedEmpty ? '1' : '0',
          options: [{ value: '1', label: 'Yes, refill swap' }, { value: '0', label: 'No, new tank' }] },
      ],
      onSubmit: (v) => mutate('PUT', '/api/transactions/' + t.id, v, `Sale #${t.id} updated`),
    });
  }

  async function deleteSale(t) {
    const ok = await confirmDialog(`Delete sale #${t.id}?`, `${t.qty} × ${t.product} to ${t.customer}, ${peso.format(t.amount)}. This can’t be undone.`);
    if (ok) mutate('DELETE', '/api/transactions/' + t.id, null, `Sale #${t.id} deleted`).catch((e) => toast(e.message, true));
  }

  // ------------------------------------------------------------------ customers

  $('customers-search').addEventListener('input', renderCustomers);
  $('add-customer').addEventListener('click', () => customerDialog());

  function renderCustomers() {
    const q = $('customers-search').value.trim();
    const all = state.data.customers;
    const rows = all.filter((c) => matches(q, c.id, fullName(c), c.contact, c.address));
    $('customers-summary').textContent = `${count.format(all.length)} customer${all.length === 1 ? '' : 's'}`;
    clear($('customers-table'), table([
      { label: '#', render: (c) => c.id, cls: 'muted num', sm: 'hide' },
      { label: 'Name', render: (c) => fullName(c), sm: 'title' },
      { label: 'Contact', render: (c) => c.contact || '—', sm: 'hide' },
      { label: 'Address', render: (c) => c.address || '—', sm: 'hide' },
      { label: 'Sales', right: true, render: (c) => c.sales, sm: 'hide' },
      { label: '', cls: 'actions', render: (c) => actions(
        h('button', { class: 'btn small ghost', onclick: () => customerDialog(c) }, 'Edit'),
        h('button', { class: 'btn small ghost danger', onclick: () => deleteCustomer(c) }, 'Delete')) },
    ], rows, q ? 'No customers match your search.' : 'No customers yet.',
    (c) => [c.contact, c.address, `${c.sales} sale${c.sales === 1 ? '' : 's'}`].filter(Boolean).join(' · ')));
  }

  function customerDialog(c) {
    return formDialog({
      title: c ? `Edit customer #${c.id}` : 'New customer',
      submit: c ? 'Save changes' : 'Add customer',
      fields: [
        { name: 'firstName', label: 'First name', value: c && c.firstName, required: true, autocomplete: 'off' },
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
    const ok = await confirmDialog(`Delete ${fullName(c)}?`, 'This removes the customer record. Customers with sales can’t be deleted.');
    if (ok) mutate('DELETE', '/api/customers/' + c.id, null, 'Customer deleted').catch((e) => toast(e.message, true));
  }

  // ------------------------------------------------------------------ products

  $('add-product').addEventListener('click', () => productDialog());

  function renderProducts() {
    const all = state.data.products;
    $('products-summary').textContent = `${count.format(all.length)} product${all.length === 1 ? '' : 's'}`;
    clear($('products-table'), table([
      { label: '#', render: (p) => p.id, cls: 'muted num', sm: 'hide' },
      { label: 'Brand', render: (p) => p.brand, sm: 'title' },
      { label: 'Weight', right: true, render: (p) => kg(p.weight), sm: 'hide' },
      { label: 'Price', right: true, render: (p) => peso.format(p.price), sm: 'figure' },
      { label: 'Sold', right: true, render: (p) => count.format(p.sold), sm: 'hide' },
      { label: 'In stock', right: true, render: (p) => count.format(p.full), sm: 'hide' },
      { label: '', cls: 'actions', render: (p) => actions(
        h('button', { class: 'btn small ghost', onclick: () => productDialog(p) }, 'Edit'),
        h('button', { class: 'btn small ghost danger', onclick: () => deleteProduct(p) }, 'Delete')) },
    ], all, 'No LPG products yet.', (p) => `${kg(p.weight)} · ${count.format(p.sold)} sold · ${stockText(p)}`));
  }

  function productDialog(p) {
    return formDialog({
      title: p ? `Edit ${p.label}` : 'New LPG product',
      message: p ? 'Price changes apply to new sales only; past sales keep the price they were sold at.' : null,
      submit: p ? 'Save changes' : 'Add product',
      fields: [
        { name: 'brand', label: 'Brand', value: p && p.brand, required: true, full: true, placeholder: 'e.g. Petron Gasul' },
        { name: 'weight', label: 'Weight (kg)', type: 'number', step: '0.1', min: '0.1', value: p && p.weight, required: true, inputmode: 'decimal' },
        { name: 'price', label: 'Price (₱)', type: 'number', step: '0.01', min: '0.01', value: p && p.price, required: true, inputmode: 'decimal' },
        { name: 'reorderLevel', label: 'Reorder level', type: 'number', step: 1, min: 0, value: p ? p.reorderLevel : 5, inputmode: 'numeric', full: true,
          help: 'Flag as “low” when full cylinders drop to this number' },
        ...(p ? [] : [
          { name: 'openingFull', label: 'Full on hand now', type: 'number', step: 1, min: 0, value: 0, inputmode: 'numeric' },
          { name: 'openingEmpty', label: 'Empties on hand now', type: 'number', step: 1, min: 0, value: 0, inputmode: 'numeric' },
        ]),
      ],
      onSubmit: (v) => p
        ? mutate('PUT', '/api/products/' + p.id, v, 'Product updated')
        : mutate('POST', '/api/products', v, `Added ${v.brand}`),
    });
  }

  async function deleteProduct(p) {
    const ok = await confirmDialog(`Delete ${p.label}?`, 'Products that appear in sales can’t be deleted.');
    if (ok) mutate('DELETE', '/api/products/' + p.id, null, 'Product deleted').catch((e) => toast(e.message, true));
  }

  // ------------------------------------------------------------------ inventory

  $('stock-delivery').addEventListener('click', () => deliveryDialog());
  $('stock-adjust').addEventListener('click', () => adjustDialog());

  const MOVE_LABEL = { delivery: 'Delivery', count: 'Stock count', damaged: 'Write-off (damaged)', other: 'Adjustment' };
  // Full cylinders carry the colour; empties moving in and out are routine, so they stay neutral.
  const signed = (n, word) => n === 0 ? null
    : h('span', { class: word === 'full' ? (n > 0 ? 'delta-pos' : 'delta-neg') : 'secondary' }, `${n > 0 ? '+' : '−'}${Math.abs(n)} ${word}`);

  function renderInventory() {
    const { products, movements } = state.data;
    const attention = products.filter((p) => p.status !== 'ok');
    clear($('inventory-alert'), attention.length ? h('div', { class: 'alert-banner', role: 'status' },
      h('span', { class: 'i', 'aria-hidden': 'true' }, '⚠'),
      h('div', {},
        h('strong', {}, `${plural(attention.length, 'product')} need${attention.length === 1 ? 's' : ''} restocking`),
        h('p', {}, attention.map((p) => `${p.label}: ${p.full <= 0 ? 'out of stock' : p.full + ' left'}`).join(' · ')))) : null);

    const full = products.reduce((n, p) => n + Math.max(0, p.full), 0);
    const empty = products.reduce((n, p) => n + Math.max(0, p.empty), 0);
    $('inventory-summary').textContent = `${plural(full, 'full cylinder')} · ${count.format(empty)} empty on hand`;
    clear($('inventory-table'), table([
      { label: 'Product', render: (p) => p.label, sm: 'title' },
      { label: 'Full', right: true, render: (p) => count.format(p.full), sm: 'hide' },
      { label: 'Empty', right: true, render: (p) => count.format(p.empty), sm: 'hide' },
      { label: 'Reorder at', right: true, render: (p) => count.format(p.reorderLevel), sm: 'hide' },
      { label: 'Status', render: statusPill, sm: 'figure' },
      { label: '', cls: 'actions', render: (p) => actions(
        h('button', { class: 'btn small ghost', onclick: () => deliveryDialog(p) }, 'Receive'),
        h('button', { class: 'btn small ghost', onclick: () => adjustDialog(p) }, 'Count')) },
    ], products, 'No LPG products yet. Add them in the Products tab.',
    (p) => `${count.format(p.full)} full · ${count.format(p.empty)} empty · reorder at ${p.reorderLevel}`));

    clear($('movements'), movements.length ? h('div', { class: 'moves' }, movements.map((m) => h('div', { class: 'move' },
      h('div', {}, h('strong', {}, MOVE_LABEL[m.type] || m.type), ' · ', m.product),
      h('div', { class: 'num' }, [signed(m.full, 'full'), m.full && m.empty ? ' ' : null, signed(m.empty, 'empty')]),
      h('div', { class: 'meta' }, [fmtTime(m.time), m.staff, m.note].filter(Boolean).join(' · '))))) : h('div', { class: 'empty' }, 'No deliveries or counts recorded yet.'));
  }

  function productSelect(p) {
    return {
      name: 'productId', label: 'LPG product', type: 'select', full: true, value: p && p.id,
      options: state.data.products.map((x) => ({ value: x.id, label: `${x.label} (${x.full} full, ${x.empty} empty)` })),
    };
  }

  function deliveryDialog(p) {
    if (!state.data.products.length) return toast('Add an LPG product first', true);
    formDialog({
      title: 'Record a delivery',
      message: 'Full cylinders received from the supplier, and empties you handed back.',
      submit: 'Save delivery',
      fields: [
        productSelect(p),
        { name: 'full', label: 'Full received', type: 'number', min: 1, step: 1, required: true, inputmode: 'numeric' },
        { name: 'emptiesReturned', label: 'Empties returned', type: 'number', min: 0, step: 1, value: 0, inputmode: 'numeric' },
        { name: 'note', label: 'Note (optional)', full: true, placeholder: 'e.g. supplier, delivery receipt no.' },
      ],
      onSubmit: (v) => mutate('POST', '/api/stock/delivery', v, `Delivery saved: +${v.full} full`),
    });
  }

  function adjustDialog(p) {
    if (!state.data.products.length) return toast('Add an LPG product first', true);
    const start = p || state.data.products[0];
    formDialog({
      title: 'Count or adjust stock',
      message: 'Enter what is physically in the store now. The difference is recorded.',
      submit: 'Save count',
      fields: [
        productSelect(start),
        { name: 'full', label: 'Full cylinders', type: 'number', min: 0, step: 1, value: Math.max(0, start.full), required: true, inputmode: 'numeric' },
        { name: 'empty', label: 'Empty cylinders', type: 'number', min: 0, step: 1, value: Math.max(0, start.empty), required: true, inputmode: 'numeric' },
        { name: 'reason', label: 'Reason', type: 'select', full: true, value: 'count', options: [
          { value: 'count', label: 'Stock count (recount)' },
          { value: 'damaged', label: 'Damaged or leaking (write-off)' },
          { value: 'other', label: 'Other' },
        ] },
        { name: 'note', label: 'Note', full: true, placeholder: 'Required for write-offs and other changes' },
      ],
      // Picking another product fills in its current counts.
      extra: (inputs) => {
        inputs.productId.addEventListener('change', () => {
          const x = byId(state.data.products, inputs.productId.value);
          if (x) { inputs.full.value = Math.max(0, x.full); inputs.empty.value = Math.max(0, x.empty); }
        });
        return null;
      },
      onSubmit: (v) => mutate('POST', '/api/stock/adjust', v, 'Stock updated'),
    });
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

  request('GET', '/api/me').then(showApp, () => showLogin());
})();
