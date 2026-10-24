// Dashboard → Starting balances: the business as it stood when the start date began (cash in the drawer,
// tanks, what customers and suppliers owe, our tanks customers have, customers' own tanks we keep, salary
// advances). Everything after counts on from these. deps: load() and save(body) for /api/opening, data()
// for the POS data (products, suppliers, customers), addCustomer() to add a customer from here.
(function () {
  const { h, clear, peso, toast, fmtTime } = UI;

  const todayIso = () => {
    const n = new Date();
    return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')}`;
  };
  const longDate = (iso) => { const [y, m, d] = iso.split('-').map(Number); return new Date(y, m - 1, d).toLocaleDateString('en-PH', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' }); };
  const name = (c) => `${c.firstName} ${c.lastName}`.trim();
  const card = (title, sub, ...body) => h('section', { class: 'card' },
    h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, title), sub ? h('div', { class: 'sub' }, sub) : null)), body);
  const numInput = (value, label, onInput, decimals) => {
    const el = h('input', { class: 'input num', type: 'number', min: 0, step: decimals ? '0.01' : 1, inputmode: decimals ? 'decimal' : 'numeric',
      placeholder: decimals ? '0.00' : '0', 'aria-label': label });
    if (value) el.value = decimals ? Number(value).toFixed(2) : value;
    el.addEventListener('input', () => onInput(el.value));
    return el;
  };

  function mount(container, deps) {
    let saved = null; // what the server has
    let m = null; // what's being edited
    let dirty = false;

    // The form model, from the saved balances (or empty). Rows are kept in the order entered.
    function model(o) {
      const stock = {};
      for (const s of o.stock || []) stock[s.productId] = { loaded: s.loaded, empty: s.empty };
      const refiller = {};
      for (const r of o.refiller || []) refiller[r.productId] = (refiller[r.productId] || 0) + r.qty;
      const sup = {};
      for (const s of o.suppliers || []) sup[s.supplierId] = s.amount;
      return {
        start: o.start || o.firstRecord || todayIso(), cash: o.cash || '', stock, refiller, sup,
        owes: (o.owes || []).map((x) => ({ ...x })), tanks: (o.tanks || []).map((x) => ({ ...x })),
        kept: (o.kept || []).map((x) => ({ ...x })), advances: (o.advances || []).map((x) => ({ ...x })),
      };
    }

    async function load() {
      container.classList.add('loading');
      try {
        saved = await deps.load();
        m = model(saved);
        dirty = false;
        draw();
      } catch (e) {
        clear(container, h('div', { class: 'empty' }, e.message));
      } finally {
        container.classList.remove('loading');
      }
    }

    const touch = () => { dirty = true; };

    function customerSelect(row) {
      const customers = [...deps.data().customers].sort((a, b) => name(a).localeCompare(name(b)));
      const sel = h('select', { class: 'input', 'aria-label': 'Customer' },
        h('option', { value: '' }, 'Choose a customer'),
        customers.map((c) => h('option', { value: c.id }, `${name(c)} (#${c.id})`)));
      sel.value = row.customerId || '';
      sel.addEventListener('change', () => { row.customerId = sel.value; touch(); });
      return sel;
    }

    function productSelect(row) {
      const sel = h('select', { class: 'input', 'aria-label': 'Tank' }, deps.data().products.map((p) => h('option', { value: p.id }, p.label)));
      if (!row.productId && deps.data().products.length) row.productId = deps.data().products[0].id;
      sel.value = row.productId;
      sel.addEventListener('change', () => { row.productId = sel.value; touch(); });
      return sel;
    }

    const removeBtn = (list, row, label) => h('button', { type: 'button', class: 'btn small ghost danger', 'aria-label': `Remove ${label}`,
      onclick: () => { list.splice(list.indexOf(row), 1); touch(); draw(); } }, 'Remove');

    // Rows of customer + amount (owes) or customer + tank + how many (tanks, kept).
    function customerRows(list, withTank, empty, addLabel) {
      const rows = list.map((row) => h('div', { class: 'start-row' + (withTank ? ' tank' : '') },
        customerSelect(row),
        withTank ? productSelect(row) : null,
        withTank ? numInput(row.qty, 'How many', (v) => { row.qty = v; touch(); })
          : numInput(row.amount, 'Amount owed (₱)', (v) => { row.amount = v; touch(); }, true),
        removeBtn(list, row, withTank ? 'tanks' : 'amount'),
        !withTank && row.left != null && row.amount > row.left + 0.005
          ? h('small', { class: 'muted start-note' }, `${peso.format(row.amount - row.left)} already paid since the start`) : null));
      return [rows.length ? h('div', { class: 'start-rows' },
        h('div', { class: 'start-row head' + (withTank ? ' tank' : '') }, h('span', {}, 'Customer'), withTank ? h('span', {}, 'Tank') : null,
          h('span', {}, withTank ? 'How many' : 'Owes (₱)'), h('span', {})), rows) : h('p', { class: 'muted' }, empty),
      h('div', { class: 'start-actions' },
        h('button', { type: 'button', class: 'btn small', onclick: () => { list.push(withTank ? { customerId: '', productId: '', qty: '' } : { customerId: '', amount: '' }); touch(); draw(); } }, addLabel))];
    }

    function draw() {
      const { products, suppliers } = deps.data();
      const o = saved;
      const date = h('input', { class: 'input', type: 'date', value: m.start, max: o.firstRecord && o.firstRecord < todayIso() ? o.firstRecord : todayIso(), required: true, 'aria-label': 'Start date' });
      date.addEventListener('change', () => { m.start = date.value; touch(); });
      const head = card('Starting balances',
        'The business as it stood when the start date began. Sales, stock and money all count on from these.',
        o.start ? h('p', { class: 'start-saved' }, `Starting from ${longDate(o.start)}. Saved${o.staff ? ' by ' + o.staff : ''}${o.savedAt ? ' ' + fmtTime(o.savedAt) : ''}.`)
          : h('p', { class: 'warn-note' }, 'Not entered yet. Fill in what you had on the start date and save.'),
        o.firstRecord ? h('p', { class: 'muted' }, `Records start on ${longDate(o.firstRecord)}, so the start date can’t be later than that.`) : null,
        h('div', { class: 'grid-2' },
          h('label', { class: 'field' }, h('span', {}, 'Start date'), date),
          h('label', { class: 'field' }, h('span', {}, 'Cash in the drawer (₱)'), numInput(m.cash, 'Cash in the drawer', (v) => { m.cash = v; touch(); }, true))));

      const stock = card('Tanks in the store', 'Count every tank of each kind',
        products.length ? h('div', { class: 'start-rows' },
          h('div', { class: 'start-row head stock' }, h('span', {}, 'Tank'), h('span', {}, 'With load'), h('span', {}, 'Empty')),
          products.map((p) => {
            const v = m.stock[p.id] || (m.stock[p.id] = { loaded: '', empty: '' });
            return h('div', { class: 'start-row stock' }, h('strong', {}, p.label),
              numInput(v.loaded, `${p.label} with load`, (x) => { v.loaded = x; touch(); }),
              numInput(v.empty, `Empty ${p.label}`, (x) => { v.empty = x; touch(); }));
          })) : h('p', { class: 'muted' }, 'Add your LPG products first (Products tab).'));

      const withSupplier = products.filter((p) => p.supplierId);
      const refiller = card('Tanks at the refiller', 'Empties the refiller had on the start date. Receive them later like any refill trip.',
        withSupplier.length ? h('div', { class: 'start-rows' },
          h('div', { class: 'start-row head stock' }, h('span', {}, 'Tank'), h('span', {}, 'Refiller'), h('span', {}, 'Tanks there')),
          withSupplier.map((p) => h('div', { class: 'start-row stock' }, h('strong', {}, p.label), h('span', { class: 'muted' }, p.supplier),
            numInput(m.refiller[p.id], `${p.label} at ${p.supplier}`, (x) => { m.refiller[p.id] = x; touch(); }))))
          : h('p', { class: 'muted' }, 'Add suppliers first (Stock → Suppliers) to enter tanks at a refiller.'));

      const owes = card('Customers who owe money', 'Unpaid on the start date. They pay it off with Collect payment, before newer receipts.',
        customerRows(m.owes, false, 'Nobody owed money.', 'Add a customer who owes'),
        h('button', { type: 'button', class: 'btn small ghost', onclick: async () => { if (await deps.addCustomer()) draw(); } }, 'New customer'));
      const tanks = card('Customers who have our tanks', 'They took a tank without leaving an empty. Collect tank settles these first.',
        customerRows(m.tanks, true, 'Nobody had our tanks.', 'Add a customer with our tanks'));
      const kept = card('Customers’ own tanks we keep', 'Tanks customers left with the store. They aren’t the store’s stock.',
        customerRows(m.kept, true, 'We kept no customer tanks.', 'Add a customer’s kept tanks'));

      const sup = card('Owed to suppliers', 'Unpaid on the start date. Pay it with Pay on the supplier, before newer bills.',
        suppliers.length ? h('div', { class: 'start-rows' }, suppliers.map((s) => {
          const left = (o.suppliers || []).find((x) => x.supplierId === s.id);
          return h('div', { class: 'start-row supplier' }, h('strong', {}, s.name),
            numInput(m.sup[s.id], `Owed to ${s.name}`, (x) => { m.sup[s.id] = x; touch(); }, true),
            left && left.amount > left.left + 0.005 ? h('small', { class: 'muted start-note' }, `${peso.format(left.amount - left.left)} already paid since the start`) : null);
        })) : h('p', { class: 'muted' }, 'No suppliers yet.'));

      const names = h('datalist', { id: 'start-employees' }, (o.employees || []).map((n) => h('option', { value: n })));
      const adv = card('Salary advances still owed', 'Taken off their next salaries, like any advance.',
        names,
        m.advances.length ? h('div', { class: 'start-rows' },
          h('div', { class: 'start-row head adv' }, h('span', {}, 'Employee'), h('span', {}, 'Advance (₱)'), h('span', {})),
          m.advances.map((row) => {
            const who = h('input', { class: 'input', list: 'start-employees', maxlength: 60, value: row.employee || '', 'aria-label': 'Employee', autocomplete: 'off' });
            who.addEventListener('input', () => { row.employee = who.value; touch(); });
            return h('div', { class: 'start-row adv' }, who, numInput(row.amount, 'Advance (₱)', (v) => { row.amount = v; touch(); }, true), removeBtn(m.advances, row, 'advance'));
          })) : h('p', { class: 'muted' }, 'No unpaid advances.'),
        h('div', { class: 'start-actions' }, h('button', { type: 'button', class: 'btn small', onclick: () => { m.advances.push({ employee: '', amount: '' }); touch(); draw(); } }, 'Add an advance')));

      const saveBtn = h('button', { type: 'button', class: 'btn primary', onclick: () => save(saveBtn) }, o.start ? 'Save changes' : 'Save starting balances');
      const bar = h('div', { class: 'start-save' },
        h('span', { class: 'muted' }, o.start ? 'Changes are checked against everything recorded since the start.' : 'You can correct these later.'),
        saveBtn);

      clear(container, head, h('div', { class: 'dash-two' }, stock, refiller), owes, h('div', { class: 'dash-two' }, tanks, kept), h('div', { class: 'dash-two' }, sup, adv), bar);
    }

    async function save(btn) {
      const body = { start: m.start, cash: m.cash || 0 };
      const add = (list, rows) => { body[list] = rows.length; rows.forEach((r, i) => Object.entries(r).forEach(([k, v]) => { body[`${list}.${i}.${k}`] = v; })); };
      add('stock', Object.entries(m.stock).filter(([, v]) => Number(v.loaded) || Number(v.empty))
        .map(([productId, v]) => ({ productId, loaded: v.loaded || 0, empty: v.empty || 0 })));
      const products = deps.data().products;
      add('refiller', Object.entries(m.refiller).filter(([, n]) => Number(n)).map(([productId, qty]) => ({ supplierId: (products.find((p) => p.id === productId) || {}).supplierId || '', productId, qty })));
      const done = (r) => r.customerId || Number(r.amount) || Number(r.qty);
      const missing = [...m.owes, ...m.tanks, ...m.kept].filter(done).some((r) => !r.customerId);
      if (missing) return toast('Choose the customer on each row, or remove the row', true);
      add('owes', m.owes.filter(done).map((r) => ({ customerId: r.customerId, amount: r.amount || 0 })));
      add('tanks', m.tanks.filter(done).map((r) => ({ customerId: r.customerId, productId: r.productId, qty: r.qty || 0 })));
      add('kept', m.kept.filter(done).map((r) => ({ customerId: r.customerId, productId: r.productId, qty: r.qty || 0 })));
      add('sup', Object.entries(m.sup).filter(([, v]) => Number(v)).map(([supplierId, amount]) => ({ supplierId, amount })));
      const advances = m.advances.filter((r) => (r.employee || '').trim() || Number(r.amount));
      if (advances.some((r) => !(r.employee || '').trim())) return toast('Enter the employee’s name on each advance, or remove the row', true);
      add('adv', advances.map((r) => ({ employee: r.employee.trim(), amount: r.amount || 0 })));
      btn.disabled = true;
      try {
        await deps.save(body);
        await load();
      } catch (e) {
        toast(e.message, true);
        btn.disabled = false;
      }
    }

    load();
    // Background refreshes leave a form being filled in alone.
    return { refresh: () => { if (!dirty) load(); } };
  }

  window.Opening = { mount };
})();
