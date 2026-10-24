// The dashboard's Sales, Suppliers and Money views, shared by the POS and the admin dashboard. Each
// shows one day, week, month or quarter at a time, with the periods before it in a table: tap a row
// to open that period. load(query) returns the /api/reports/... data for that query.
(function () {
  const { h, clear, peso, count } = UI;
  const pesoR = (v) => UI.pesoRound.format(v);
  const plural = (n, word) => `${count.format(n)} ${word}${n === 1 ? '' : 's'}`;

  const PERIODS = [['day', 'Daily'], ['week', 'Weekly'], ['month', 'Monthly'], ['quarter', 'Quarterly']];
  const NOW_LABEL = { day: 'Today', week: 'This week', month: 'This month', quarter: 'This quarter' };

  const date = (iso) => { const [y, m, d] = iso.split('-').map(Number); return new Date(y, m - 1, d); };
  const fmt = (iso, o) => date(iso).toLocaleDateString('en-PH', o);

  // "Mon, Oct 6", "Oct 6 – 12", "October 2026", "Q4 2026 · Oct – Dec"
  function periodLabel(period, row, long) {
    switch (period) {
      case 'week': return `${fmt(row.start, { month: 'short', day: 'numeric' })} – ${fmt(row.end, { month: date(row.start).getMonth() === date(row.end).getMonth() ? undefined : 'short', day: 'numeric' })}`;
      case 'month': return fmt(row.start, { month: long ? 'long' : 'short', year: 'numeric' });
      case 'quarter': return `Q${Math.floor(date(row.start).getMonth() / 3) + 1} ${date(row.start).getFullYear()}` + (long ? ` · ${fmt(row.start, { month: 'short' })} – ${fmt(row.end, { month: 'short' })}` : '');
      default: return fmt(row.start, long ? { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' } : { weekday: 'short', month: 'short', day: 'numeric' });
    }
  }

  const tile = (label, value, note, cls) => h('div', { class: 'card tile' + (cls ? ' ' + cls : '') },
    h('div', { class: 'label' }, label), h('div', { class: 'value' }, value), note ? h('div', { class: 'tile-note' }, note) : null);
  // A figure on a line; owed amounts (advances, unpaid balances) use owedLine, which stands out.
  const line = (label, value, cls) => h('div', { class: 'owe-row plain' + (cls ? ' ' + cls : '') }, h('span', {}, label), h('strong', { class: 'num' }, value));
  const owedLine = (label, value) => h('div', { class: 'owe-row' }, h('span', {}, label), h('strong', { class: 'num' }, value));
  const card = (title, sub, ...body) => h('section', { class: 'card' },
    h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, title), sub ? h('div', { class: 'sub' }, sub) : null)), body);
  const empty = (text) => h('div', { class: 'empty' }, text);

  // Daily / Weekly / Monthly / Quarterly
  function periodSwitch(current, onPick) {
    return h('div', { class: 'filters', role: 'group', 'aria-label': 'Period' },
      PERIODS.map(([p, label]) => h('button', { type: 'button', class: 'chip', 'aria-pressed': String(p === current), onclick: () => onPick(p) }, label)));
  }

  // The periods, newest first; the open one is highlighted. columns: [[heading, (row) => text, right?]]
  function periodTable(period, rows, selected, onPick, columns) {
    return h('div', { class: 'table-wrap' }, h('table', { class: 'days-table' },
      h('thead', {}, h('tr', {}, h('th', {}, { day: 'Day', week: 'Week', month: 'Month', quarter: 'Quarter' }[period]),
        columns.map(([label, , opt]) => h('th', { class: 'right' + (opt ? ' opt' : '') }, label)))),
      h('tbody', {}, rows.map((r) => h('tr', {
        class: r.key === selected ? 'selected' : null, tabindex: 0, 'aria-current': r.key === selected ? 'true' : null,
        onclick: () => onPick(r.key), onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onPick(r.key); } },
      }, h('td', {}, periodLabel(period, r)), columns.map(([, value, opt, neg]) => h('td', { class: 'right num' + (opt ? ' opt' : '') + (neg && neg(r) ? ' neg' : '') }, value(r))))))));
  }

  // Shared plumbing: which period and row are open, loading and redrawing. draw(data, row, view) returns the nodes.
  function view(container, storageKey, load, draw) {
    const v = { period: 'day', key: null, data: null, extra: {} };
    try { const p = localStorage.getItem(storageKey); if (PERIODS.some(([x]) => x === p)) v.period = p; } catch (_) { /* storage blocked */ }
    let busy = 0;
    v.reload = async () => {
      const mine = ++busy;
      container.classList.add('loading');
      try {
        const data = await load({ period: v.period, ...v.extra });
        if (mine !== busy) return;
        v.data = data;
        if (!data.rows.some((r) => r.key === v.key)) v.key = data.rows.length ? data.rows[0].key : null;
        v.redraw();
      } catch (e) {
        if (mine === busy) clear(container, empty(e.message));
      } finally {
        if (mine === busy) container.classList.remove('loading');
      }
    };
    v.redraw = () => {
      if (!v.data) return;
      const row = v.data.rows.find((r) => r.key === v.key);
      clear(container, draw(v.data, row, v));
    };
    v.setPeriod = (p) => {
      v.period = p;
      v.key = null;
      try { localStorage.setItem(storageKey, p); } catch (_) { /* storage blocked */ }
      v.reload();
    };
    v.open = (key) => { v.key = key; v.redraw(); };
    v.heading = (row, ...extra) => h('div', { class: 'day-nav' },
      h('div', { class: 'day-title' }, h('strong', {}, row ? periodLabel(v.period, row, true) : '—'),
        row && row.key === v.data.rows[0].key && !v.extra.from ? h('span', { class: 'pill' }, NOW_LABEL[v.period]) : null), extra);
    return v;
  }

  // ------------------------------------------------------------------ sales

  function mountSales(container, load) {
    const v = view(container, 'lpg-report-sales', load, (d, r, v) => {
      const top = periodSwitch(v.period, v.setPeriod);
      if (!r) return [top, empty('No sales yet.')];
      const tiles = h('div', { class: 'kpis' },
        tile('Total sales', pesoR(r.revenue), `${plural(r.receipts, 'receipt')} · ${plural(r.tanks, 'tank')}` + (r.discounts ? ` · ${peso.format(r.discounts)} discounts` : '')),
        tile('Paid in cash', pesoR(r.cash), r.later ? `Includes balances paid later` : 'Money received from customers'),
        tile('Paid online (GCash)', pesoR(r.online), 'Goes to the e-wallet, not the drawer'),
        tile('Unpaid by customers', pesoR(r.unpaid), r.unpaid ? `${plural(r.unpaidCustomers, 'customer')} still owe for these sales` : 'All of these sales are paid', r.unpaid ? 'warn' : null));
      const paidHow = card('How customers paid', 'Money received in this period, whatever sale it was for',
        line('Cash at the sale', peso.format(round(r.cash - r.laterCash))),
        line('Cash paid later on balances', peso.format(r.laterCash)),
        line('Online (GCash)', peso.format(r.online)),
        line('Total received', peso.format(r.collected), 'total-row'));
      const sold = card('Tanks sold', `${plural(r.tanks, 'tank')} by LPG type`,
        r.products.length ? r.products.map((p) => line(`${p.qty} × ${p.label}`, peso.format(p.revenue))) : empty('No sales in this period.'));
      const owing = card('Still unpaid', 'From these sales, as of now',
        r.owing.length ? r.owing.map((o) => owedLine(o.customer, peso.format(o.balance))) : empty('Nobody owes for these sales.'));
      const table = card('Earlier', 'Tap a row to open it', periodTable(v.period, d.rows, r.key, v.open, [
        ['Sales', (x) => peso.format(x.revenue)], ['Cash', (x) => peso.format(x.cash), true], ['Online', (x) => peso.format(x.online), true],
        ['Unpaid', (x) => peso.format(x.unpaid)]]));
      return [top, v.heading(r), tiles, h('div', { class: 'dash-two' }, sold, h('div', { class: 'stack' }, paidHow, owing)), table];
    });
    v.reload();
    return { refresh: v.reload };
  }

  const round = (x) => Math.round(x * 100) / 100;

  // ------------------------------------------------------------------ suppliers

  // actions.recordRefill(): opens the one-step refill form (POS only); resolves truthy when saved.
  function mountSuppliers(container, load, actions) {
    const v = view(container, 'lpg-report-suppliers', (q) => load(q), (d, r, v) => {
      const pick = h('select', { class: 'input sup-pick', 'aria-label': 'Supplier' },
        h('option', { value: '' }, 'All suppliers'), d.suppliers.map((s) => h('option', { value: s.id }, s.name)));
      pick.value = v.extra.supplier || '';
      pick.addEventListener('change', () => { v.extra.supplier = pick.value; v.reload(); });
      const owing = d.suppliers.filter((s) => s.owed > 0);
      const bar = h('div', { class: 'report-bar' },
        periodSwitch(v.period, v.setPeriod), pick,
        actions && actions.recordRefill ? h('button', { type: 'button', class: 'btn primary', onclick: async () => { if (await actions.recordRefill(v.extra.supplier)) v.reload(); } }, 'Record refill') : null);
      const owedNote = h('p', { class: 'muted report-note' }, owing.length
        ? `Owed to suppliers now: ${owing.map((s) => `${s.name} ${peso.format(s.owed)}`).join(' · ')}` : 'Suppliers are fully paid.');
      if (!r) return [bar, owedNote, empty('Nothing recorded yet.')];
      const who = v.extra.supplier ? (d.suppliers.find((s) => s.id === v.extra.supplier) || {}).name : 'all suppliers';
      const tiles = h('div', { class: 'kpis' },
        tile('Tanks refilled', count.format(r.refilled), [r.suppliers.filter((s) => s.refilled).length ? plural(r.suppliers.filter((s) => s.refilled).length, 'supplier') : '',
          r.trips ? `${plural(r.trips, 'trip')} sent` : '', r.bought ? `${plural(r.bought, 'new tank')} bought` : ''].filter(Boolean).join(' · ') || 'None in this period'),
        tile('Cost', pesoR(r.billed), r.bought ? `${peso.format(r.refillCost)} refills · ${peso.format(r.purchaseCost)} new tanks` : `Refills from ${who}`),
        tile('Paid', pesoR(r.paid), payWays(r) || 'Nothing paid'),
        tile('Still unpaid', pesoR(r.unpaid), r.unpaid ? 'Of this period’s bills, as of now' : 'This period’s bills are paid', r.unpaid ? 'warn' : null));
      const list = r.suppliers.length ? r.suppliers.map((s) => supplierCard(s, v)) : [empty(`No refills or payments ${v.period === 'day' ? 'this day' : 'in this period'}.`)];
      const nodes = [bar, owedNote, v.heading(r), tiles, h('div', { class: 'sup-cards' }, list)];
      // A week, month or quarter opens day by day: which tanks each supplier refilled on each day.
      if (v.period !== 'day') nodes.push(dayByDay(load, v, r));
      nodes.push(card('Earlier', 'Tap a row to open it', periodTable(v.period, d.rows, r.key, v.open, [
        ['Tanks', (x) => count.format(x.refilled)], ['Cost', (x) => peso.format(x.billed)], ['Cash', (x) => peso.format(x.paidCash), true],
        ['Check', (x) => peso.format(x.paidCheck), true], ['Online', (x) => peso.format(x.paidOnline), true], ['Unpaid', (x) => peso.format(x.unpaid)]])));
      return nodes;
    });
    v.reload();
    return { refresh: v.reload };
  }

  const payWays = (s) => [s.paidCash ? `${peso.format(s.paidCash)} cash` : '', s.paidCheck ? `${peso.format(s.paidCheck)} check` : '',
    s.paidOnline ? `${peso.format(s.paidOnline)} online` : ''].filter(Boolean).join(' · ')
    + (s.paidOwner || s.paidStaff ? ` (${[s.paidOwner ? `${peso.format(s.paidOwner)} owner’s money` : '', s.paidStaff ? `${peso.format(s.paidStaff)} staff money` : ''].filter(Boolean).join(', ')})` : '');

  // One supplier in one period: tanks refilled per LPG type with the amount, and how it was paid.
  function supplierCard(s, v) {
    const products = s.products.filter((p) => p.refilled || p.bought);
    return h('section', { class: 'card sup-card' },
      h('div', { class: 'card-head' },
        h('div', {}, h('h2', {}, s.name), h('div', { class: 'sub' }, `${plural(s.refilled, 'tank')} refilled` + (s.bought ? ` · ${plural(s.bought, 'new tank')}` : '') + ` · ${peso.format(s.billed)}`)),
        !v.extra.supplier && s.id ? h('button', { type: 'button', class: 'btn small ghost', onclick: () => { v.extra.supplier = s.id; v.reload(); } }, 'Only this supplier') : null),
      products.length ? h('div', { class: 'table-wrap' }, h('table', { class: 'size-table' },
        h('thead', {}, h('tr', {}, h('th', {}, 'LPG'), h('th', { class: 'right' }, 'Refilled'), s.bought ? h('th', { class: 'right' }, 'New') : null, h('th', { class: 'right' }, 'Amount'))),
        h('tbody', {}, products.map((p) => h('tr', {}, h('td', {}, p.label), h('td', { class: 'right num' }, count.format(p.refilled)),
          s.bought ? h('td', { class: 'right num' }, count.format(p.bought)) : null, h('td', { class: 'right num' }, peso.format(p.amount)))))))
        : h('p', { class: 'muted' }, 'No tanks in this period.'),
      h('div', { class: 'sup-pay' },
        line('Paid by cash', peso.format(s.paidCash)), line('Paid by check', peso.format(s.paidCheck)), line('Paid online', peso.format(s.paidOnline)),
        s.paidOwner || s.paidStaff ? h('p', { class: 'tile-note' }, payWays(s)) : null,
        s.unpaid ? line('Still unpaid', peso.format(s.unpaid), 'warn-row') : null));
  }

  function dayByDay(load, v, r) {
    const box = h('div', { class: 'loading-box' }, h('p', { class: 'muted' }, 'Loading each day…'));
    const section = card('Day by day', 'Which tanks each supplier refilled on each day', box);
    load({ period: 'day', from: r.start, to: r.end, supplier: v.extra.supplier || '' }).then((d) => {
      const days = d.rows.filter((x) => x.suppliers.length);
      clear(box, days.length ? days.map((x) => h('div', { class: 'day-block' },
        h('div', { class: 'day-block-head' }, h('strong', {}, periodLabel('day', x)), h('span', { class: 'num muted' }, `${plural(x.refilled, 'tank')} · ${peso.format(x.billed)}`)),
        x.suppliers.map((s) => h('div', { class: 'owe-row' },
          h('div', {}, h('div', {}, s.name), h('div', { class: 'meta' }, s.products.filter((p) => p.refilled || p.bought)
            .map((p) => `${p.refilled || p.bought} × ${p.label}${p.bought ? ' (new)' : ''}`).join(', ') || (s.trips ? 'Empties sent for refilling' : 'Payment only'))),
          h('div', { class: 'right' }, h('div', { class: 'num' }, peso.format(s.billed)), s.paid ? h('div', { class: 'meta' }, `Paid ${payWays(s)}`) : null)))))
        : empty('No refills on any day of this period.'));
    }, (e) => clear(box, empty(e.message)));
    return section;
  }

  // ------------------------------------------------------------------ money

  const CATEGORY = { gasoline: 'Delivery gasoline', salary: 'Salaries', advance: 'Salary advances', repaid: 'Advances paid back', other: 'Other expenses' };
  const CATEGORY_ONE = { gasoline: 'Delivery gasoline', salary: 'Salary', advance: 'Salary advance', repaid: 'Advance paid back', other: 'Other' };
  const METHOD = { cash: 'Cash', online: 'Online', check: 'Check' };

  // actions.addExpense(data), actions.editExpense(data, entry) and actions.deleteExpense(entry) (POS only)
  // resolve truthy when something changed.
  function mountMoney(container, load, actions) {
    const v = view(container, 'lpg-report-money', load, (d, r, v) => {
      const bar = h('div', { class: 'report-bar' }, periodSwitch(v.period, v.setPeriod),
        actions && actions.addExpense ? h('button', { type: 'button', class: 'btn primary', onclick: async () => { if (await actions.addExpense(d)) v.reload(); } }, 'Add expense') : null);
      if (!r) return [bar, empty('Nothing recorded yet.')];
      const c = r.byCategory;
      const tiles = h('div', { class: 'kpis' },
        tile('Cash on hand', pesoR(r.cashOnHand), 'Cash in − cash out in this period', r.cashOnHand < 0 ? 'neg' : null),
        tile('Cash in', pesoR(r.cashIn), r.onlineIn ? `Plus ${peso.format(r.onlineIn)} online (GCash)` : 'From customers'),
        tile('Spent', pesoR(r.spent), `${peso.format(r.supplierPaid)} suppliers · ${peso.format(r.expenses)} expenses · ${peso.format(r.salaries || 0)} salaries`),
        tile('Cash out', pesoR(r.cashOut), 'Paid in cash from the drawer'),
        r.drawer == null ? null : tile('Cash in the drawer', pesoR(r.drawer), 'At the end of this period, counted from the starting cash'));
      const where = card('Where the money went', 'Everything paid out in this period, by cash, check or online',
        line('Refills and new tanks (suppliers)', peso.format(r.supplierPaid)),
        r.supplierPaid ? h('p', { class: 'tile-note sub-note' }, [`${peso.format(r.supplierPaid - r.supplierCheck - r.supplierOnline)} cash`,
          r.supplierCheck ? `${peso.format(r.supplierCheck)} check` : '', r.supplierOnline ? `${peso.format(r.supplierOnline)} online` : '',
          r.supplierOwnMoney ? `${peso.format(r.supplierOwnMoney)} from the owner’s or staff’s own money` : ''].filter(Boolean).join(' · ')) : null,
        ['gasoline', 'other'].map((k) => line(CATEGORY[k], peso.format(c[k] || 0))),
        line('Salaries and advances', peso.format(r.salaries || 0)),
        c.salary || c.advance || c.repaid ? h('p', { class: 'tile-note sub-note' }, [`${peso.format(c.salary || 0)} salaries`, `${peso.format(c.advance || 0)} advances`,
          c.repaid ? `less ${peso.format(c.repaid)} advances paid back` : ''].filter(Boolean).join(' · ')) : null,
        line('Total spent', peso.format(r.spent), 'total-row'),
        r.paidBack ? line('Paid back to staff (for suppliers they paid)', peso.format(r.paidBack), 'sub-row') : null);
      const worked = card('Cash on hand, worked out', 'Checks and online payments come from the bank, so only cash counts',
        line('Cash received from customers', peso.format(r.cashIn)),
        line('− Cash paid to suppliers from sales', peso.format(r.supplierCash)),
        line('− Expenses paid in cash', peso.format(r.expensesCash)),
        line('− Salaries and advances paid in cash (less paid back)', peso.format(r.salariesCash || 0)),
        r.paidBack ? line('− Paid back to staff', peso.format(r.paidBack)) : null,
        line('= Cash on hand', peso.format(r.cashOnHand), 'total-row' + (r.cashOnHand < 0 ? ' neg' : '')),
        r.drawer == null ? null : [
          line(r.startCash != null ? 'Starting cash (starting balances)' : 'In the drawer when this period began', peso.format(r.startCash != null ? r.startCash : r.drawerBefore), 'sub-row'),
          line('In the drawer at the end (+ cash on hand)', peso.format(r.drawer), 'total-row' + (r.drawer < 0 ? ' neg' : ''))]);
      const entries = card('Expenses', r.entries.length ? plural(r.entries.length, 'entry').replace('entrys', 'entries') : null,
        r.entries.length ? h('div', { class: 'list' }, r.entries.map((e) => h('div', { class: 'owe-row' },
          h('div', {}, h('div', {}, CATEGORY_ONE[e.category] + (e.employee ? ` · ${e.employee}` : '')),
            h('div', { class: 'meta' }, [UI.fmtTime(e.time), METHOD[e.method], e.advanceDeducted ? `${peso.format(e.advanceDeducted)} advance taken off (salary ${peso.format(e.amount + e.advanceDeducted)})` : '', e.note, e.recordedBy ? `by ${e.recordedBy}` : ''].filter(Boolean).join(' · '))),
          h('div', { class: 'right' }, h('div', { class: 'num' }, peso.format(e.amount)),
            actions && actions.editExpense ? h('button', { type: 'button', class: 'btn small ghost', 'aria-label': `Edit ${CATEGORY_ONE[e.category]} ${peso.format(e.amount)}`,
              onclick: async () => { if (await actions.editExpense(d, e)) v.reload(); } }, 'Edit') : null,
            actions && actions.deleteExpense ? h('button', { type: 'button', class: 'btn small ghost danger', 'aria-label': `Delete ${CATEGORY_ONE[e.category]} ${peso.format(e.amount)}`,
              onclick: async () => { if (await actions.deleteExpense(e)) v.reload(); } }, 'Delete') : null))))
          : empty('No expenses in this period.'));
      const advances = card('Salary advances', 'Not yet cleared. Salaries and advances are kept apart from expenses: see Salaries.',
        d.advances.length ? d.advances.map((a) => owedLine(a.employee, peso.format(a.owed))) : empty('No unpaid advances.'));
      const table = card('Earlier', 'Tap a row to open it', periodTable(v.period, d.rows, r.key, v.open, [
        ['Cash in', (x) => peso.format(x.cashIn)], ['Suppliers', (x) => peso.format(x.supplierPaid), true], ['Expenses', (x) => peso.format(x.expenses), true], ['Salaries', (x) => peso.format(x.salaries || 0), true],
        ['Cash on hand', (x) => peso.format(x.cashOnHand), false, (x) => x.cashOnHand < 0],
        ...(d.rows.some((x) => x.drawer != null) ? [['In the drawer', (x) => x.drawer == null ? '—' : peso.format(x.drawer), true]] : [])]));
      return [bar, v.heading(r), tiles, h('div', { class: 'dash-two' }, where, worked), h('div', { class: 'dash-two' }, entries, advances), table];
    });
    v.reload();
    return { refresh: v.reload };
  }

  window.Reports = { mountSales, mountSuppliers, mountMoney, periodLabel, CATEGORY_ONE };
})();
