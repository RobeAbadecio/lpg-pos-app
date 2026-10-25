// "Where does this number come from?" Click a figure marked data-explain (it shows a small ?) or a ? button to see
// what the figure is, how it's worked out, the amounts that add up to it and the records behind it. Shared by the admin
// dashboard and the POS. The server works it out with the same rules as the figure, so the two always match.
window.Explain = (function () {
  const { h, clear, peso, count, fmtTime } = UI;

  const fmt = (v, type) => {
    if (v == null || v === '') return '';
    if (type === 'money') return peso.format(v);
    if (type === 'int') return count.format(v);
    if (type === 'date') return fmtTime(v);
    return String(v);
  };

  function body(ex) {
    const total = h('div', { class: 'explain-total' }, h('span', { class: 'num' }, fmt(ex.total, ex.type)));
    const parts = (ex.parts || []).length ? h('div', { class: 'explain-parts' },
      ex.parts.map((p) => h('div', { class: 'explain-part' }, h('span', {}, p.label),
        h('strong', { class: 'num' + (p.amount < 0 ? ' delta-neg' : '') }, (p.amount < 0 ? '− ' : '') + fmt(Math.abs(p.amount), p.type)))),
      ex.parts.length > 1 && ex.parts.every((p) => p.type === ex.type)
        ? h('div', { class: 'explain-part sum' }, h('span', {}, '= Result'), h('strong', { class: 'num' }, fmt(ex.total, ex.type))) : null) : null;
    const rows = ex.rows || [];
    const table = rows.length ? h('div', { class: 'table-wrap explain-table' }, h('table', {},
      h('thead', {}, h('tr', {}, ex.columns.map((c) => h('th', { class: c.type === 'money' || c.type === 'int' ? 'right' : null }, c.label)))),
      h('tbody', {}, rows.map((r) => h('tr', {}, r.map((v, i) => {
        const t = ex.columns[i].type;
        return h('td', { class: t === 'money' || t === 'int' ? 'right num' : t === 'date' ? 'nowrap secondary' : null }, fmt(v, t));
      }))))))
      : h('div', { class: 'empty' }, 'No records in this period, so the figure is zero.');
    return h('div', { class: 'explain' },
      total,
      h('p', { class: 'explain-how' }, ex.how),
      parts ? h('h4', { class: 'explain-h' }, 'How it adds up') : null,
      parts,
      rows.length ? h('h4', { class: 'explain-h' }, ex.rowCount === 1 ? 'The record behind it' : `The ${count.format(ex.rowCount)} records behind it`) : null,
      table,
      ex.rowCount > rows.length ? h('p', { class: 'muted' }, `Showing the newest ${count.format(rows.length)}. The rest are in Edit records.`) : null,
      ex.note ? h('p', { class: 'muted explain-note' }, ex.note) : null);
  }

  async function open(load, metric, params) {
    const query = new URLSearchParams({ metric, ...params }).toString();
    let ex;
    try {
      ex = await load('/api/explain?' + query);
    } catch (e) {
      UI.toast(e.message, true);
      return;
    }
    UI.openDialog({ title: ex.title, body: body(ex), wide: true, submit: null, cancel: 'Close' });
  }

  // Marks every [data-explain] figure inside root as clickable. params() gives the current range/period/supplier.
  function bind(root, load, params) {
    const go = (el) => open(load, el.dataset.explain, params(el));
    root.addEventListener('click', (e) => {
      const el = e.target.closest('[data-explain]');
      if (el && root.contains(el)) go(el);
    });
    root.addEventListener('keydown', (e) => {
      const el = e.target.closest('[data-explain]');
      if (el && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); go(el); }
    });
    for (const el of root.querySelectorAll('[data-explain]')) mark(el);
  }

  function mark(el) {
    el.classList.add('explainable');
    el.setAttribute('role', 'button');
    el.setAttribute('tabindex', '0');
    if (!el.title) el.title = 'Tap to see how this number is worked out';
    return el;
  }

  // A ? button beside a figure that isn't itself clickable (in a list row, say). params: { id, date, range... }.
  function help(load, metric, params, what) {
    return h('button', {
      type: 'button', class: 'help-q', title: 'How is this worked out?', 'aria-label': `How is ${what || 'this'} worked out?`,
      onclick: (e) => { e.stopPropagation(); open(load, metric, params || {}); },
    }, '?');
  }

  return { open, bind, mark, help };
})();
