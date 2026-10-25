// Billing page for the store owner: what is owed, the payment QR, sending a payment's reference number, past payments.
(function () {
  const { h, clear, toast, request } = UI;
  const $ = (id) => document.getElementById(id);
  const STATE = { free: 'No fee', unpaid: 'Not paid yet', active: 'Paid', due: 'Due soon', grace: 'Late', overdue: 'Overdue' };
  const PAY = { pending: 'Being checked', approved: 'Approved', rejected: 'Not accepted' };
  const peso = (c) => '₱' + (c / 100).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const day = (s) => s ? new Date(s.slice(0, 10) + 'T00:00:00').toLocaleDateString('en-PH', { month: 'short', day: 'numeric', year: 'numeric' }) : '';
  const today = () => { const d = new Date(); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };
  let invoice = null;

  function fail(message) {
    $('problem').textContent = message;
    $('problem').hidden = false;
  }

  function statusText(s, graceDays) {
    switch (s.state) {
      case 'free': return 'This store has no subscription fee.';
      case 'unpaid': return 'Pay the first month below to start using the POS. Until the payment is checked, you and your staff can sign in and look around, but new records can’t be saved.';
      case 'active': return 'Paid until ' + day(s.paidUntil) + '. The next invoice shows up here a week before.';
      case 'due': return 'Paid until ' + day(s.paidUntil) + '. Pay the invoice below to keep the POS running without a break.';
      case 'grace': return 'The subscription ended on ' + day(s.paidUntil) + '. The POS keeps working until ' + day(s.lockDate)
        + '; after that, new records can’t be saved until it’s paid.';
      case 'overdue': return 'The subscription ended on ' + day(s.paidUntil) + '. New records can’t be saved until it’s paid. You and your staff can still sign in and see everything.';
      default: return '';
    }
  }

  async function load() {
    const b = await request('GET', '/api/billing');
    const s = b.summary;
    clear($('state'), h('span', { class: 'st st-' + s.state }, STATE[s.state] || s.state));
    $('status-text').textContent = statusText(s, b.graceDays);
    $('status-card').hidden = false;

    invoice = s.invoice;
    $('pay-card').hidden = !invoice;
    if (invoice) {
      $('inv-no').textContent = invoice.number;
      $('inv-period').textContent = day(invoice.periodStart) + ' – ' + day(invoice.periodEnd);
      $('amount').textContent = peso(invoice.amount);
      $('qr').hidden = !b.qr;
      if (b.qr) $('qr').src = '/api/billing/qr';
      $('no-qr').hidden = !!b.qr;
      $('payee').textContent = b.payee || '';
      $('note').textContent = b.note || '';
      $('note').hidden = !b.note;
      const waiting = b.payments.find((p) => p.invoiceId === invoice.id && p.status === 'pending');
      $('waiting').hidden = !waiting;
      if (waiting) $('waiting').textContent = 'Payment ' + peso(waiting.amount) + ' (ref ' + waiting.reference + ') was sent on '
        + day(waiting.submitted) + '. It’s being checked; this usually takes less than a day.';
      $('pay-form').hidden = !!waiting;
      const f = $('pay-form');
      if (!f.amount.value) f.amount.value = (invoice.amount / 100).toFixed(2);
      if (!f.paidOn.value) f.paidOn.value = today();
      f.paidOn.max = today();
    }

    const invNo = Object.fromEntries(b.invoices.map((i) => [i.id, i.number]));
    $('history-card').hidden = !b.payments.length;
    clear($('payment-rows'), b.payments.map((p) => h('tr', {},
      h('td', { class: 'nowrap' }, day(p.submitted)),
      h('td', {}, invNo[p.invoiceId] || '—'),
      h('td', {}, peso(p.amount)),
      h('td', {}, p.reference),
      h('td', {}, h('span', { class: 'st st-' + p.status }, PAY[p.status] || p.status), p.note ? h('div', { class: 'muted' }, p.note) : null))));
  }

  // Phone screenshots are big: shrink to at most 1600 px as JPEG before sending.
  async function shrink(file) {
    if (!file) return '';
    let bmp;
    try {
      bmp = await createImageBitmap(file);
    } catch (_) {
      throw new Error('That picture can’t be read. Use a PNG or JPEG screenshot.');
    }
    const scale = Math.min(1, 1600 / Math.max(bmp.width, bmp.height));
    const c = document.createElement('canvas');
    c.width = Math.round(bmp.width * scale);
    c.height = Math.round(bmp.height * scale);
    c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
    return c.toDataURL('image/jpeg', 0.85);
  }

  $('pay-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target, err = f.querySelector('.acct-error'), button = f.querySelector('button[type=submit]');
    err.hidden = true;
    button.disabled = true;
    try {
      const receipt = await shrink(f.receipt.files[0]);
      await request('POST', '/api/billing/pay', { invoice: invoice.id, reference: f.reference.value.trim(), amount: f.amount.value.trim(),
        paidOn: f.paidOn.value, receipt });
      f.reset();
      toast('Payment sent. We’ll check it soon.');
      await load();
    } catch (ex) {
      err.textContent = ex.message;
      err.hidden = false;
    } finally {
      button.disabled = false;
    }
  });

  (async function start() {
    let me;
    try {
      me = await request('GET', '/api/me');
    } catch (e) {
      if (e.status === 401) { location.href = '/'; return; }
      fail(e.message);
      return;
    }
    if (me.role !== 'owner') { fail('Only the store owner can see billing.'); return; }
    $('store-name').textContent = me.business.name;
    Brand.apply(me.brand);
    await load().catch((e) => fail(e.message));
  })();
})();
