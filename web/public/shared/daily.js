// Daily summary, shared by the POS dashboard and the admin dashboard: one day at a glance (sales, money
// in and out, cash on hand), tanks moved, that day's receipts, and the days before. The details of
// sales, suppliers and money are in their own views (shared/reports.js).
(function () {
  const { h, clear, peso, count, fmtTime } = UI;
  const plural = (n, word) => `${count.format(n)} ${word}${n === 1 ? '' : 's'}`;
  const STATUS = { paid: ['ok', '●', 'Paid'], partial: ['low', '◐', 'Partly paid'], unpaid: ['out', '○', 'Unpaid'] };
  const pill = (s) => h('span', { class: 'status ' + STATUS[s][0] }, h('span', { class: 'i', 'aria-hidden': 'true' }, STATUS[s][1]), STATUS[s][2]);

  const todayIso = () => {
    const n = new Date();
    return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')}`;
  };

  function longDate(iso, short) {
    const [y, m, d] = iso.split('-').map(Number);
    const dt = new Date(y, m - 1, d);
    return dt.toLocaleDateString('en-PH', short ? { weekday: 'short', month: 'short', day: 'numeric' }
      : { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });
  }

  // metric: the figure the value explains when clicked (see shared/explain.js), for that day.
  const tile = (label, value, note, metric, date) => h('div', { class: 'card tile' },
    h('div', { class: 'label' }, label),
    metric && window.Explain ? Explain.mark(h('div', { class: 'value', 'data-explain': metric, 'data-date': date }, value)) : h('div', { class: 'value' }, value),
    note ? h('div', { class: 'tile-note' }, note) : null);
  const row = (label, value, cls) => h('div', { class: 'owe-row' + (cls ? ' ' + cls : '') }, h('span', {}, label), h('strong', { class: 'num' }, value));

  // container: where to draw. load(date) returns the /api/daily data for that date ('' = today).
  function mount(container, load) {
    let current = '';
    let data = null;

    async function open(date) {
      current = date || '';
      container.classList.add('loading');
      try {
        data = await load(current);
        draw();
      } catch (e) {
        clear(container, h('div', { class: 'empty' }, e.message));
      } finally {
        container.classList.remove('loading');
      }
    }

    function draw() {
      const d = data, t = d.totals;
      const picker = h('input', { type: 'date', class: 'input day-pick', value: d.date, max: todayIso(), 'aria-label': 'Pick a day' });
      picker.addEventListener('change', () => picker.value && open(picker.value));
      const nav = h('div', { class: 'day-nav' },
        h('button', { type: 'button', class: 'btn', 'aria-label': 'Previous day', onclick: () => open(d.previous) }, '‹'),
        h('div', { class: 'day-title' }, h('strong', {}, longDate(d.date)), d.today ? h('span', { class: 'pill' }, 'Today') : null),
        h('button', { type: 'button', class: 'btn', 'aria-label': 'Next day', disabled: !d.next, onclick: () => d.next && open(d.next) }, '›'),
        picker,
        d.today ? null : h('button', { type: 'button', class: 'btn small ghost', onclick: () => open('') }, 'Back to today'));

      const profit = t.profit == null ? '—' : UI.pesoRound.format(t.profit);
      const tiles = h('div', { class: 'kpis' },
        tile('Sales', UI.pesoRound.format(t.revenue), `${plural(t.receipts, 'receipt')} · ${plural(t.tanks, 'tank')}`, 'revenue', d.date),
        tile('Cash from customers', UI.pesoRound.format(t.collected),
          [t.collectedLater ? `${peso.format(t.collectedAtSale)} at sale · ${peso.format(t.collectedLater)} paid later` : 'All at the sale',
            t.collectedGcash ? `${peso.format(t.collectedGcash)} by GCash` : ''].filter(Boolean).join(' · '), 'collected', d.date),
        tile('Paid to suppliers', UI.pesoRound.format(t.paidSuppliers),
          t.paidStaff || t.paidOwner ? [`${peso.format(t.paidSales)} from sales`, t.paidOwner ? `${peso.format(t.paidOwner)} owner’s money` : '', t.paidStaff ? `${peso.format(t.paidStaff)} staff money` : ''].filter(Boolean).join(' · ')
            : t.paidSuppliers ? 'All from sales' : 'Nothing paid', 'supplierPaid', d.date),
        tile('Expenses', UI.pesoRound.format(t.expenses), t.expenses ? (t.expensesCash < t.expenses ? `${peso.format(t.expensesCash)} in cash` : 'All in cash') : 'Gasoline and other costs', null, d.date),
        tile('Salaries', UI.pesoRound.format(t.salaries || 0), t.salaries ? (t.salariesCash < t.salaries ? `${peso.format(t.salariesCash)} in cash` : 'All in cash') : 'Salaries and advances, less paid back', null, d.date),
        tile('Cash on hand', UI.pesoRound.format(t.cashLeft), 'Cash in − cash paid to suppliers, expenses, salaries and staff', 'cashLeft', d.date),
        // From the starting balances on: the drawer at the end of the day (start of the day + that day's cash on hand).
        t.drawer == null ? null : tile('Cash in the drawer', UI.pesoRound.format(t.drawer),
          t.startCash != null ? `Starting cash ${peso.format(t.startCash)} + cash on hand` : `${peso.format(t.drawerBefore)} at the start of the day + cash on hand`),
        tile('Gross profit (est.)', profit, t.profit == null ? 'Set refill costs in Products' : d.uncosted.length ? `Leaves out ${d.uncosted.join(', ')}` : null, 'profit', d.date));

      const sold = h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, 'Tanks sold'))),
        d.byProduct.length ? d.byProduct.map((p) => row(`${p.qty} × ${p.label}`, peso.format(p.revenue))) : h('div', { class: 'empty' }, 'No sales this day.'));

      const tanks = h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, 'Tanks in and out'), h('div', { class: 'sub' }, 'Refills, new tanks and customers’ tanks'))),
        row('Sent to the refiller', plural(t.refillSent, 'tank')),
        row('Back from the refiller', plural(t.refillReceived, 'tank') + (t.refillBill ? ` · bill ${peso.format(t.refillBill)}` : '')),
        row('New tanks bought', plural(t.bought, 'tank') + (t.purchaseBill ? ` · ${peso.format(t.purchaseBill)}` : '')),
        row('Taken without an empty', plural(t.tanksLent, 'tank')),
        row('Tanks returned by customers', plural(t.tanksReturned, 'tank')));

      const receipts = h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, 'Receipts'), h('div', { class: 'sub' }, plural(d.receipts.length, 'receipt')))),
        d.receipts.length ? h('div', { class: 'list' }, d.receipts.map((r) => h('div', { class: 'owe-row' },
          h('div', {}, h('div', {}, `#${r.id} · ${r.customer}`), h('div', { class: 'meta' }, `${fmtTime(r.time).replace(/^Today, /, '')} · ${r.items} · ${r.staff}`)),
          h('div', { class: 'right' }, h('div', { class: 'num' }, peso.format(r.total)), r.status !== 'paid' ? pill(r.status) : null))))
          : h('div', { class: 'empty' }, 'No receipts this day.'));

      // Earlier days: tap a row to open that day.
      const drawerCol = d.days.some((x) => x.drawer != null);
      const days = h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, 'Previous days'), h('div', { class: 'sub' }, 'Tap a day to see its summary'))),
        h('div', { class: 'table-wrap' }, h('table', { class: 'days-table' },
          h('thead', {}, h('tr', {}, h('th', {}, 'Day'), h('th', { class: 'right' }, 'Sales'), h('th', { class: 'right opt' }, 'Tanks'),
            h('th', { class: 'right opt' }, 'Cash in'), h('th', { class: 'right opt' }, 'Suppliers'), h('th', { class: 'right opt' }, 'Expenses'), h('th', { class: 'right opt' }, 'Salaries'), h('th', { class: 'right' }, 'Cash on hand'),
            drawerCol ? h('th', { class: 'right opt' }, 'In the drawer') : null)),
          h('tbody', {}, d.days.map((x) => h('tr', {
            class: x.date === d.date ? 'selected' : null, tabindex: 0, 'aria-current': x.date === d.date ? 'date' : null,
            onclick: () => open(x.date), onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(x.date); } },
          },
          h('td', {}, longDate(x.date, true)),
          h('td', { class: 'right num' }, peso.format(x.revenue)),
          h('td', { class: 'right num opt' }, count.format(x.tanks)),
          h('td', { class: 'right num opt' }, peso.format(x.collected)),
          h('td', { class: 'right num opt' }, peso.format(x.paidSuppliers)),
          h('td', { class: 'right num opt' }, peso.format(x.expenses || 0)),
          h('td', { class: 'right num opt' }, peso.format(x.salaries || 0)),
          h('td', { class: 'right num' + (x.cashLeft < 0 ? ' neg' : '') }, peso.format(x.cashLeft)),
          drawerCol ? h('td', { class: 'right num opt' }, x.drawer == null ? '—' : peso.format(x.drawer)) : null))))));

      clear(container, nav, tiles, h('div', { class: 'dash-two' }, sold, tanks), receipts, days);
    }

    open('');
    return { refresh: () => open(current) };
  }

  window.Daily = { mount };
})();
