// Staff salaries (4.1): who is on the payroll, their pay schedule, advances not yet cleared and what's left to hand
// over on pay day. Kept apart from the expenses. Shared by the POS dashboard (with actions) and the admin page (view only).
(function () {
  const { h, clear, peso, count } = UI;
  const DAYS = ['', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  const nth = (d) => d + (d % 10 === 1 && d !== 11 ? 'st' : d % 10 === 2 && d !== 12 ? 'nd' : d % 10 === 3 && d !== 13 ? 'rd' : 'th');
  const KIND = { salary: 'Salary paid', advance: 'Advance given', repaid: 'Advance paid back' };
  // How a pay day stands (5.2.1): worked out from the salaries recorded for it.
  const STATUS = { paid: ['ok', '●', 'Paid'], partial: ['low', '◐', 'Partially paid'], unpaid: ['low', '○', 'Unpaid'],
    overdue: ['out', '!', 'Overdue'], unknown: ['none', '?', 'Unknown'], upcoming: ['none', '○', 'Not due yet'] };
  const chip = (status) => {
    const [cls, icon, label] = STATUS[status] || STATUS.unknown;
    return h('span', { class: 'status ' + cls }, h('span', { class: 'i', 'aria-hidden': 'true' }, icon), label);
  };
  const shortDay = (iso) => new Date(iso + 'T00:00:00').toLocaleDateString('en-PH', { weekday: 'short', month: 'short', day: 'numeric' });
  // "₱1,000 of ₱3,000 paid · ₱2,000 left", or why it's unknown.
  function periodText(p, salary) {
    if (p.status === 'unknown') return 'Before they were added: no record of it';
    // The salary of that pay day (it may have changed since): the server sends it with the pay day.
    return [`${peso.format(p.paid)} of ${peso.format(p.salary != null ? p.salary : salary)} paid`, p.left > 0 ? `${peso.format(p.left)} left` : null].filter(Boolean).join(' · ');
  }

  function scheduleText(e) {
    if (!e.schedule) return '—';
    if (e.schedule === 'weekly') return `Weekly, every ${DAYS[e.day1]}`;
    if (e.schedule === 'twice') return `Twice a month, ${nth(e.day1)} & ${nth(e.day2)}`;
    return `Monthly, ${nth(e.day1)}`;
  }

  function dayText(iso, today) {
    if (!iso) return '—';
    const d = new Date(iso + 'T00:00:00');
    const days = Math.round((d - new Date(today + 'T00:00:00')) / 86400000);
    const date = d.toLocaleDateString('en-PH', { weekday: 'short', month: 'short', day: 'numeric' });
    return days === 0 ? `Today (${date})` : days === 1 ? `Tomorrow (${date})` : `${date} · in ${days} days`;
  }

  // One line per salary, advance or pay-back.
  function historyLine(x) {
    const amount = x.category === 'salary' && x.advanceDeducted ? `${peso.format(x.amount)} handed over (salary ${peso.format(x.amount + x.advanceDeducted)}, ${peso.format(x.advanceDeducted)} advance cleared)`
      : peso.format(x.amount);
    const forDay = x.payday ? `for pay day ${shortDay(x.payday)}${x.paydayGuessed ? ' (by date)' : ''}` : null;
    return [KIND[x.category] || x.category, amount, forDay, UI.fmtTime(x.time), x.method !== 'cash' ? x.method : null, x.note, x.recordedBy ? `by ${x.recordedBy}` : null]
      .filter(Boolean).join(' · ');
  }

  // actions (POS only): add(), edit(e), remove(e), advance(e), pay(e), clear(e), history(e), menu(primary, items, label)
  // why(metric, params, what): a ? button that explains a figure (shared/explain.js), or leave it out.
  function mount(el, { load, actions, why }) {
    const q = (metric, params, what) => (why ? why(metric, params, what) : null);
    let data = null;
    const draw = () => {
      if (!data) return;
      const list = data.employees;
      const setUp = list.filter((e) => e.id);
      const owing = list.filter((e) => e.advanced > 0);
      const head = h('div', { class: 'card-head wrap' },
        h('div', {}, h('h2', {}, 'Staff salaries'),
          h('div', { class: 'sub' }, `${count.format(setUp.length)} on the payroll · ${peso.format(data.totalSalary)} per pay day in salaries`
            + (data.totalOwed > 0 ? ` · ${peso.format(data.totalOwed)} salary still owed` : ' · all pay days settled'),
          data.totalOwed > 0 ? q('salary-owed', {}, 'the salary still owed') : null,
          data.totalAdvanced > 0 ? ` · ${peso.format(data.totalAdvanced)} advanced to ${count.format(owing.length)}` : ' · no unpaid advances')),
        actions ? h('button', { type: 'button', class: 'btn primary', onclick: () => actions.add() }, '+ Add employee') : null);
      const row = (e) => {
        const setup = !!e.id;
        const lp = e.lastPayday;
        const cells = [
          h('td', { class: 'sm-title' }, h('strong', {}, e.name), !setup ? h('div', { class: 'cell-sub' }, 'Not set up yet: add their salary') : null),
          h('td', { class: 'right num sm-figure' }, setup ? peso.format(e.salary) : '—'),
          h('td', { class: 'sm-hide' }, scheduleText(e)),
          h('td', { class: 'sm-hide' }, lp ? [h('div', { class: 'nowrap' }, shortDay(lp.payday), ' ', chip(lp.status)),
            h('div', { class: 'cell-sub' }, periodText(lp, e.salary))] : '—'),
          h('td', { class: 'right num sm-hide' }, setup ? [h('strong', { class: e.owed > 0 ? 'owes-text' : null }, peso.format(e.owed)), q('salary-owed', { id: e.id }, `${e.name}’s salary still owed`)] : '—'),
          h('td', { class: 'sm-hide nowrap' }, e.nextPayday ? [dayText(e.nextPayday, data.today),
            e.upcoming && e.upcoming.paid > 0 && e.upcoming.payday === e.nextPayday ? h('div', { class: 'cell-sub' }, `${peso.format(e.upcoming.paid)} paid ahead`) : null] : '—'),
          h('td', { class: 'right num sm-hide' }, e.advanced > 0
            ? h('span', { class: 'status low' }, h('span', { class: 'i', 'aria-hidden': 'true' }, '▲'), peso.format(e.advanced)) : '—'),
          h('td', { class: 'sm-detail' }, lp ? h('div', {}, `Last pay day ${shortDay(lp.payday)} `, chip(lp.status)) : null,
            [setup ? scheduleText(e) : 'Not set up', setup ? `still owed ${peso.format(e.owed)}` : null, e.nextPayday ? `next pay ${dayText(e.nextPayday, data.today)}` : null,
              e.advanced > 0 ? `advanced ${peso.format(e.advanced)}` : null].filter(Boolean).join(' · ')),
        ];
        if (actions) {
          cells.push(h('td', { class: 'actions' }, actions.menu(
            !setup ? { label: 'Set salary', primary: true, onClick: () => actions.add(e.name) }
              : e.owed > 0 ? { label: 'Pay salary', onClick: () => actions.pay(e) } : { label: 'Give advance', onClick: () => actions.advance(e) }, [
              setup ? { label: 'Give advance', onClick: () => actions.advance(e) } : null,
              setup ? { label: 'Pay salary', onClick: () => actions.pay(e) } : null,
              e.advanced > 0 ? { label: 'Clear advance', onClick: () => actions.clear(e) } : null,
              { label: 'History', onClick: () => actions.history(e) },
              setup ? { label: 'Edit employee', onClick: () => actions.edit(e) } : null,
              setup ? { label: 'Remove from payroll…', danger: true, onClick: () => actions.remove(e) } : null,
            ], e.name)));
        }
        return h('tr', {}, cells);
      };
      clear(el, h('section', { class: 'card flush payroll' },
        h('div', { class: 'payroll-head' }, head),
        list.length ? h('div', { class: 'table-wrap' }, h('table', { class: 'rtable' },
          h('thead', {}, h('tr', {}, ['Name', 'Salary', 'Schedule', 'Last payday', 'Still owed', 'Next payday', 'Advanced'].concat(actions ? ['Actions'] : [])
            .map((t, i) => h('th', { class: [1, 4, 6].includes(i) || t === 'Actions' ? 'right' : null }, t)))),
          h('tbody', {}, list.map(row))))
          : h('div', { class: 'empty' }, actions ? 'No one on the payroll yet. Add an employee with their salary and pay schedule.' : 'No one on the payroll yet.'),
        h('p', { class: 'muted payroll-note' }, 'Still owed = salary not yet paid for pay days that have come since they were put on the payroll. '
          + 'Each salary counts for the pay day it was paid for, early or late. Advances are shown apart: take them off when paying. '
          + 'Salaries and advances are kept apart from the expenses, but cash paid out still comes out of cash on hand.')));
    };
    const reload = async () => {
      try {
        data = await load();
        draw();
      } catch (e) {
        clear(el, h('div', { class: 'card' }, h('p', { class: 'error-text' }, e.message)));
      }
    };
    reload();
    return { refresh: reload, data: () => data };
  }

  window.Payroll = { mount, scheduleText, historyLine, chip, STATUS, shortDay, periodText, DAYS, nth };
})();
