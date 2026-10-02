// Daily summary, shared by the POS dashboard and the admin dashboard: one day's sales, money in
// and out (and whose money paid the suppliers), tanks moved, that day's receipts, and the days before.
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

  const tile = (label, value, note) => h('div', { class: 'card tile' },
    h('div', { class: 'label' }, label), h('div', { class: 'value' }, value), note ? h('div', { class: 'tile-note' }, note) : null);
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
        tile('Sales', UI.pesoRound.format(t.revenue), `${plural(t.receipts, 'receipt')} · ${plural(t.tanks, 'tank')}`),
        tile('Cash from customers', UI.pesoRound.format(t.collected),
          t.collectedLater ? `${peso.format(t.collectedAtSale)} at sale · ${peso.format(t.collectedLater)} paid later` : 'All at the sale'),
        tile('Paid to suppliers', UI.pesoRound.format(t.paidSuppliers),
          t.paidStaff ? `${peso.format(t.paidSales)} from sales · ${peso.format(t.paidStaff)} staff money` : t.paidSuppliers ? 'All from sales' : 'Nothing paid'),
        tile('Cash left from sales', UI.pesoRound.format(t.cashLeft), 'Cash in − paid from sales − paid back to staff'),
        tile('Gross profit (est.)', profit, t.profit == null ? 'Set refill costs in Products' : d.uncosted.length ? `Leaves out ${d.uncosted.join(', ')}` : null));

      const sold = h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, 'Tanks sold'), h('div', { class: 'sub' }, plural(t.tanks, 'tank')))),
        d.byProduct.length ? d.byProduct.map((p) => row(`${p.qty} × ${p.label}`, peso.format(p.revenue))) : h('div', { class: 'empty' }, 'No sales this day.'));

      const tanks = h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, 'Tanks in and out'), h('div', { class: 'sub' }, 'Refills, new tanks and customers’ tanks'))),
        row('Sent to the refiller', plural(t.refillSent, 'tank')),
        row('Back from the refiller', plural(t.refillReceived, 'tank') + (t.refillBill ? ` · bill ${peso.format(t.refillBill)}` : '')),
        row('New tanks bought', plural(t.bought, 'tank') + (t.purchaseBill ? ` · ${peso.format(t.purchaseBill)}` : '')),
        row('Taken without an empty', plural(t.tanksLent, 'tank')),
        row('Tanks returned by customers', plural(t.tanksReturned, 'tank')));

      const money = h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, 'Money details'))),
        row('Paid to suppliers from sales', peso.format(t.paidSales)),
        row('Paid to suppliers with staff’s own money', peso.format(t.paidStaff)),
        ...d.paidByStaff.map((s) => row(`· ${s.staff}`, peso.format(s.amount), 'sub-row')),
        row('Paid back to staff', peso.format(t.paidBack)),
        row('Not paid yet by customers', peso.format(t.credit)),
        row('Discounts given', peso.format(t.discounts)),
        row('Swap fees', peso.format(t.swapFees)));

      const receipts = h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, 'Receipts'), h('div', { class: 'sub' }, plural(d.receipts.length, 'receipt')))),
        d.receipts.length ? h('div', { class: 'list' }, d.receipts.map((r) => h('div', { class: 'owe-row' },
          h('div', {}, h('div', {}, `#${r.id} · ${r.customer}`), h('div', { class: 'meta' }, `${fmtTime(r.time).replace(/^Today, /, '')} · ${r.items} · ${r.staff}`)),
          h('div', { class: 'right' }, h('div', { class: 'num' }, peso.format(r.total)), r.status !== 'paid' ? pill(r.status) : null))))
          : h('div', { class: 'empty' }, 'No receipts this day.'));

      // Earlier days: tap a row to open that day.
      const days = h('section', { class: 'card' },
        h('div', { class: 'card-head' }, h('div', {}, h('h2', {}, 'Previous days'), h('div', { class: 'sub' }, 'Tap a day to see its summary'))),
        h('div', { class: 'table-wrap' }, h('table', { class: 'days-table' },
          h('thead', {}, h('tr', {}, h('th', {}, 'Day'), h('th', { class: 'right' }, 'Sales'), h('th', { class: 'right opt' }, 'Tanks'),
            h('th', { class: 'right opt' }, 'Cash in'), h('th', { class: 'right opt' }, 'Paid out'), h('th', { class: 'right' }, 'Cash left'))),
          h('tbody', {}, d.days.map((x) => h('tr', {
            class: x.date === d.date ? 'selected' : null, tabindex: 0, 'aria-current': x.date === d.date ? 'date' : null,
            onclick: () => open(x.date), onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(x.date); } },
          },
          h('td', {}, longDate(x.date, true)),
          h('td', { class: 'right num' }, peso.format(x.revenue)),
          h('td', { class: 'right num opt' }, count.format(x.tanks)),
          h('td', { class: 'right num opt' }, peso.format(x.collected)),
          h('td', { class: 'right num opt' }, peso.format(x.paidSuppliers)),
          h('td', { class: 'right num' + (x.cashLeft < 0 ? ' neg' : '') }, peso.format(x.cashLeft))))))));

      clear(container, nav, tiles, h('div', { class: 'dash-two' }, sold, tanks), h('div', { class: 'dash-two' }, money, receipts), days);
    }

    open('');
    return { refresh: () => open(current) };
  }

  window.Daily = { mount };
})();
