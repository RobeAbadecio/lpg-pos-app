// Platform admin: sign-in, payments to check, MariBank emails, billing settings, businesses (approve, suspend,
// reactivate), their accounts, health, backups, audit log.
(function () {
  const { h, clear, toast, request, formDialog, confirmDialog, openDialog } = UI;
  const $ = (id) => document.getElementById(id);
  const LABEL = { pending: 'Waiting for approval', active: 'Active', suspended: 'Suspended', deactivated: 'Deactivated', rejected: 'Rejected', disabled: 'Disabled' };
  const BILL = { free: 'No fee', unpaid: 'Not paid yet', active: 'Paid', due: 'Due soon', grace: 'Late', overdue: 'Overdue' };
  const PAY = { pending: 'Waiting', approved: 'Approved', rejected: 'Rejected' };
  const MAIL = { new: 'No payment sent', matched: 'Matches a payment', used: 'Used', ignored: 'Not a fee', untrusted: 'Not trusted' };
  const peso = (c) => '₱' + (c / 100).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const state = { businesses: [], open: null };
  let setupMode = false;

  const st = (s) => h('span', { class: 'st st-' + s }, LABEL[s] || s);
  const bytes = (n) => n == null ? '?' : n > 1e9 ? (n / 1e9).toFixed(1) + ' GB' : n > 1e6 ? (n / 1e6).toFixed(1) + ' MB' : Math.round(n / 1e3) + ' KB';
  const btn = (label, onclick, cls = 'btn small') => h('button', { type: 'button', class: cls, onclick }, label);

  // ------------------------------------------------------------------ sign-in

  async function start() {
    const s = await request('GET', '/api/admin/state');
    if (s.signedIn) return showConsole();
    setupMode = s.setup;
    $('gate-title').textContent = setupMode ? 'Set the platform admin password' : 'Platform admin';
    $('gate-hint').textContent = setupMode ? 'First visit: choose the password for this dashboard. Keep it private; it controls every business.' : 'Sign in to manage businesses.';
    $('gate-confirm').hidden = !setupMode;
    $('gate-submit').textContent = setupMode ? 'Set password' : 'Sign in';
    $('gate').hidden = false;
    $('gate').password.focus();
  }

  $('gate').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const err = f.querySelector('.acct-error');
    err.hidden = true;
    if (setupMode && f.password.value !== f.confirm.value) {
      err.textContent = 'The two passwords are different';
      err.hidden = false;
      return;
    }
    try {
      await request('POST', setupMode ? '/api/admin/setup' : '/api/admin/login', { password: f.password.value });
      f.reset();
      $('gate').hidden = true;
      showConsole();
    } catch (ex) {
      err.textContent = ex.message;
      err.hidden = false;
    }
  });

  $('logout').addEventListener('click', async () => {
    await request('POST', '/api/admin/logout').catch(() => {});
    location.reload();
  });

  $('change-password').addEventListener('click', () => {
    formDialog({ title: 'Change admin password', fields: [
      { name: 'current', label: 'Current password', type: 'password', required: true },
      { name: 'password', label: 'New password', type: 'password', required: true, help: 'At least 8 characters' }],
    submit: 'Change', onSubmit: (v) => request('POST', '/api/admin/password', v) }).then((v) => { if (v) toast('Password changed'); });
  });

  async function showConsole() {
    $('top-actions').hidden = false;
    $('console').hidden = false;
    await refresh();
  }

  // ------------------------------------------------------------------ businesses

  async function refresh() {
    try {
      const p = await request('GET', '/api/platform');
      state.businesses = p.businesses;
      renderHealth(p.health);
      renderBusinesses();
      await loadBilling();
      if (state.open) await openAccounts(state.open);
      await loadAudit();
    } catch (e) {
      if (e.status === 401) location.reload();
      else toast(e.message, true);
    }
  }

  function renderHealth(hl) {
    clear($('health'),
      h('span', {}, 'Database: ', h('b', {}, hl.database === 'ok' ? 'OK' : hl.database), ' (' + bytes(hl.databaseBytes) + ')'),
      h('span', {}, 'Disk free: ', h('b', {}, bytes(hl.diskFreeBytes)), ' of ' + bytes(hl.diskTotalBytes)),
      h('span', {}, 'Last backup: ', h('b', {}, hl.lastBackup || 'none yet'), ' · ' + (hl.backups || 0) + ' kept'));
  }

  async function setStatus(b, status, question, detail) {
    if (question && !(await confirmDialog(question, detail, LABEL[status] === 'Active' ? 'Confirm' : question.split(' ')[0]))) return;
    try {
      await request('POST', `/api/businesses/${b.id}/status`, { status });
      toast(b.name + ': ' + (LABEL[status] || status).toLowerCase());
      await refresh();
    } catch (e) {
      toast(e.message, true);
    }
  }

  async function openStore(b) {
    await request('POST', `/api/businesses/${b.id}/select`);
    location.href = '/';
  }

  function renderBusinesses() {
    const rows = state.businesses.map((b) => {
      const actions = [];
      if (b.status === 'pending') {
        actions.push(btn('Approve', () => setStatus(b, 'active', null), 'btn primary small'));
        actions.push(btn('Reject', () => setStatus(b, 'rejected', 'Reject ' + b.name + '?', 'The owner can’t sign in, and their username becomes free again.')));
      } else if (b.status === 'active') {
        actions.push(btn('Open store', () => openStore(b), 'btn primary small'));
        actions.push(btn('Suspend', () => setStatus(b, 'suspended', 'Suspend ' + b.name + '?', 'Everyone in this business is signed out and can’t use the POS. Their records are kept.')));
      } else if (b.status === 'suspended' || b.status === 'deactivated') {
        actions.push(btn('Reactivate', () => setStatus(b, 'active', null)));
        if (b.status === 'suspended') actions.push(btn('Deactivate', () => setStatus(b, 'deactivated', 'Deactivate ' + b.name + '?', 'For businesses that have left. Records are kept and it can be reactivated.')));
      }
      if (b.status !== 'rejected') actions.push(btn('Accounts', () => openAccounts(b.id)));
      if (b.status !== 'rejected' && b.status !== 'pending') actions.push(btn('Billing', () => editBilling(b)));
      actions.push(btn('Details', () => editBusiness(b)));
      return h('tr', { class: state.open === b.id ? 'is-selected' : '' },
        h('td', {}, h('b', {}, b.name), h('div', { class: 'muted' }, [b.contactName, b.phone, b.email].filter(Boolean).join(' · '))),
        h('td', {}, b.owner || '—'),
        h('td', {}, st(b.status)),
        h('td', {}, billingCell(b)),
        h('td', {}, String(b.staff) + (b.pendingStaff ? ' (+' + b.pendingStaff + ' waiting)' : '') + (b.online ? ' · ' + b.online + ' online' : '')),
        h('td', { class: 'nowrap' }, (b.created || '').slice(0, 10)),
        h('td', { class: 'actions' }, actions));
    });
    clear($('business-rows'), rows.length ? rows : h('tr', {}, h('td', { colspan: 7, class: 'muted' }, 'No businesses yet.')));
  }

  const PROFILE = [
    { name: 'name', label: 'Store name', required: true },
    { name: 'contactName', label: 'Owner’s name' },
    { name: 'phone', label: 'Mobile number' },
    { name: 'email', label: 'Email', type: 'email' },
    { name: 'address', label: 'Address', full: true }];

  function editBusiness(b) {
    formDialog({ title: b.name, fields: PROFILE.map((f) => ({ ...f, value: b[f.name] || '' })), submit: 'Save',
      onSubmit: (v) => request('PUT', `/api/businesses/${b.id}`, v) }).then((v) => { if (v) { toast('Saved'); refresh(); } });
  }

  $('add-business').addEventListener('click', () => {
    formDialog({ title: 'Add business', message: 'For a business you set up yourself. It is active at once, with this owner account.',
      fields: [...PROFILE,
        { name: 'username', label: 'Owner username', required: true },
        { name: 'password', label: 'Owner password', type: 'text', required: true, help: 'At least 8 characters' }],
      submit: 'Add business', onSubmit: (v) => request('POST', '/api/businesses', v) })
      .then((v) => { if (v) { toast('Business added'); refresh(); } });
  });

  $('backup').addEventListener('click', async () => {
    try {
      await request('POST', '/api/platform/backup');
      toast('Backup made');
      refresh();
    } catch (e) {
      toast(e.message, true);
    }
  });

  // ------------------------------------------------------------------ billing

  function billingCell(b) {
    const s = b.billing || {};
    if (b.status === 'pending' || b.status === 'rejected') return h('span', { class: 'muted' }, '—');
    return [h('span', { class: 'st st-' + s.state }, BILL[s.state] || s.state),
      s.state !== 'free' && s.paidUntil ? h('div', { class: 'muted' }, 'until ' + s.paidUntil) : null];
  }

  function editBilling(b) {
    formDialog({ title: 'Billing · ' + b.name,
      message: 'Use “Paid until” for a payment made another way (for example cash). A free store never gets an invoice.',
      fields: [
        { name: 'exempt', label: 'Fee', type: 'select', value: b.billingExempt ? '1' : '0', options: [{ value: '0', label: 'Pays the monthly fee' }, { value: '1', label: 'Free of charge' }] },
        { name: 'paidUntil', label: 'Paid until', type: 'date', value: (b.billing && b.billing.paidUntil) || '' }],
      submit: 'Save',
      onSubmit: (v) => {
        const data = {};
        if (v.exempt !== (b.billingExempt ? '1' : '0')) data.exempt = v.exempt;
        if (v.paidUntil && v.paidUntil !== ((b.billing && b.billing.paidUntil) || '')) data.paidUntil = v.paidUntil;
        return Object.keys(data).length ? request('POST', `/api/businesses/${b.id}/billing`, data) : true;
      } }).then((v) => { if (v) { toast('Billing saved'); refresh(); } });
  }

  async function loadBilling() {
    const { settings, payments, mail, emails } = await request('GET', '/api/billing');
    renderMail(mail, emails);
    const f = $('billing-form');
    if (!f.dataset.loaded) { // don't overwrite what the admin is typing on a refresh
      f.on.checked = settings.on;
      f.price.value = (settings.price / 100).toFixed(2);
      f.trialDays.value = settings.trialDays;
      f.graceDays.value = settings.graceDays;
      f.payee.value = settings.payee;
      f.note.value = settings.note;
      f.dataset.loaded = '1';
    }
    clear($('billing-state'), h('span', { class: 'st st-' + (settings.on ? 'active' : 'pending') }, settings.on ? 'Charging' : 'Off'));
    $('qr-thumb').hidden = !settings.qr;
    if (settings.qr) $('qr-thumb').src = '/api/billing/qr?' + Date.now();
    $('qr-hint').textContent = settings.qr ? 'Owners see this QR on their Billing page. Pick a new file to replace it.'
      : 'Upload your MariBank QR (PNG or JPEG, at most 2 MB). Owners see it on their Billing page.';

    const waiting = payments.filter((p) => p.status === 'pending').length;
    $('queue-count').textContent = waiting ? waiting + ' waiting' : 'Nothing waiting';
    clear($('payment-rows'), payments.length ? payments.map(paymentRow)
      : h('tr', {}, h('td', { colspan: 7, class: 'muted' }, 'No payments yet.')));
  }

  function paymentRow(p) {
    const actions = [];
    if (p.receipt) actions.push(btn('Receipt', () => openDialog({ title: 'Receipt · ' + p.reference, wide: true, submit: null, cancel: 'Close',
      body: h('img', { class: 'receipt-img', src: `/api/payments/${p.id}/receipt`, alt: 'Receipt screenshot sent by ' + p.business }) })));
    if (p.status === 'pending') {
      actions.push(btn('Approve', async () => {
        if (await confirmDialog('Approve ' + peso(p.amount) + ' from ' + p.business + '?',
          'Only if your MariBank history shows this payment (reference ' + p.reference + '). The store gets one more month.', 'Approve')) payAction(p, 'approve', null, 'Approved');
      }, 'btn primary small'));
      actions.push(btn('Reject', () => formDialog({ title: 'Reject payment ' + p.reference + '?',
        message: 'The owner sees the reason and can send it again.',
        fields: [{ name: 'note', label: 'Reason', full: true, placeholder: 'e.g. No payment with this reference' }], submit: 'Reject', danger: true,
        onSubmit: (v) => request('POST', `/api/payments/${p.id}/reject`, v) }).then((v) => { if (v) { toast('Rejected'); refresh(); } }), 'btn small danger'));
    }
    return h('tr', {},
      h('td', { class: 'nowrap' }, (p.submitted || '').slice(0, 16)),
      h('td', {}, h('b', {}, p.business || '#' + p.businessId), h('div', { class: 'muted' }, p.submittedBy === 'bank email' ? 'from the MariBank email' : 'by ' + p.submittedBy)),
      h('td', { class: 'nowrap' }, p.invoice ? [p.invoice.number, h('div', { class: 'muted' }, 'asks ' + peso(p.invoice.amount))] : '—'),
      h('td', {}, h('b', {}, peso(p.amount)), h('div', { class: 'muted' }, 'on ' + p.paidOn)),
      h('td', {}, p.reference, p.bank ? h('div', { class: 'bank-ok' }, '✓ MariBank email: ' + peso(p.bank.amount) + ' at ' + p.bank.time) : null,
        p.warnings && p.warnings.length ? h('ul', { class: 'warn-list' }, p.warnings.map((w) => h('li', {}, w))) : null),
      h('td', {}, h('span', { class: 'st st-' + p.status }, PAY[p.status] || p.status), p.note ? h('div', { class: 'muted' }, p.note) : null),
      h('td', { class: 'actions' }, actions));
  }

  // ------------------------------------------------------------------ MariBank emails

  function renderMail(mail, emails) {
    const f = $('mail-form');
    if (!f.dataset.loaded) {
      f.on.checked = mail.on;
      f.auto.checked = mail.auto;
      f.user.value = mail.user;
      f.dataset.loaded = '1';
    }
    $('mail-pw-hint').textContent = mail.password ? 'Saved. Leave empty to keep it.' : 'Kept only on this computer, never shown again.';
    clear($('mail-state'), h('span', { class: 'st st-' + (mail.on ? 'active' : 'pending') }, mail.on ? (mail.auto ? 'On · auto-approve' : 'On · suggest only') : 'Off'));
    $('mail-status').textContent = mail.lastError ? 'Last check ' + mail.lastCheck + ' failed: ' + mail.lastError
      : mail.lastCheck ? 'Last checked ' + mail.lastCheck + '.' : 'Not checked yet.';
    $('mail-status').classList.toggle('acct-error', !!mail.lastError);
    clear($('email-rows'), emails.length ? emails.map(emailRow)
      : h('tr', {}, h('td', { colspan: 6, class: 'muted' }, 'No MariBank emails yet.')));
  }

  function emailRow(e) {
    const actions = [];
    let match;
    if (e.status === 'new' && e.invoice) {
      match = [h('span', { class: 'st st-new' }, 'No payment sent'), h('div', { class: 'muted' }, 'amount fits ' + e.invoice.number + ' · ' + e.business)];
      actions.push(btn('Approve for ' + e.business, async () => {
        if (await confirmDialog('Approve ' + peso(e.amount) + ' for ' + e.business + '?',
          'Nobody sent this reference, but the amount is exactly what invoice ' + e.invoice.number + ' asks. The store gets one more month.', 'Approve')) {
          mailAction(e, 'apply', 'Approved');
        }
      }, 'btn primary small'));
    } else {
      match = [h('span', { class: 'st st-' + e.status }, MAIL[e.status] || e.status),
        e.status === 'new' ? h('div', { class: 'muted' }, 'no invoice asks this amount') : null,
        e.problem ? h('div', { class: 'muted' }, e.problem) : null,
        e.business ? h('div', { class: 'muted' }, e.business) : null];
    }
    if (e.status === 'new') actions.push(btn('Not a fee', () => mailAction(e, 'ignore', 'Marked')));
    return h('tr', {},
      h('td', { class: 'nowrap' }, e.time),
      h('td', {}, h('b', {}, peso(e.amount))),
      h('td', {}, e.reference),
      h('td', {}, e.from || '—'),
      h('td', {}, match),
      h('td', { class: 'actions' }, actions));
  }

  async function mailAction(e, action, done) {
    try {
      await request('POST', `/api/bank-emails/${e.id}/${action}`);
      toast(done);
      await refresh();
    } catch (ex) {
      toast(ex.message, true);
    }
  }

  $('mail-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    try {
      await request('PUT', '/api/mail', { on: f.on.checked ? '1' : '0', auto: f.auto.checked ? '1' : '0', user: f.user.value.trim(), password: f.password.value });
      f.password.value = '';
      toast('Email settings saved');
      await refresh();
    } catch (ex) {
      toast(ex.message, true);
    }
  });

  $('mail-check').addEventListener('click', async (e) => {
    e.target.disabled = true;
    try {
      const { added } = await request('POST', '/api/mail/check');
      toast(added ? added + ' new MariBank email' + (added > 1 ? 's' : '') : 'No new MariBank emails');
    } catch (ex) {
      toast(ex.message, true);
    } finally {
      e.target.disabled = false;
      await refresh();
    }
  });

  async function payAction(p, action, data, done) {
    try {
      await request('POST', `/api/payments/${p.id}/${action}`, data);
      toast(done);
      await refresh();
    } catch (e) {
      toast(e.message, true);
    }
  }

  $('billing-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    try {
      await request('PUT', '/api/billing', { on: f.on.checked ? '1' : '0', price: f.price.value.trim(), trialDays: f.trialDays.value,
        graceDays: f.graceDays.value, payee: f.payee.value.trim(), note: f.note.value.trim() });
      toast('Billing settings saved');
      await refresh();
    } catch (ex) {
      toast(ex.message, true);
    }
  });

  $('qr-file').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      if (file.size > 2 * 1024 * 1024) throw new Error('The picture is too big (at most 2 MB)');
      const image = await new Promise((resolve, reject) => {
        const r = new FileReader();
        r.onload = () => resolve(r.result);
        r.onerror = () => reject(new Error('That file can’t be read'));
        r.readAsDataURL(file);
      });
      await request('POST', '/api/billing/qr', { image });
      toast('QR code uploaded');
      await loadBilling();
    } catch (ex) {
      toast(ex.message, true);
    } finally {
      e.target.value = '';
    }
  });

  // ------------------------------------------------------------------ accounts of one business

  async function accountAction(a, path, data, done) {
    try {
      await request('POST', `/api/accounts/${encodeURIComponent(a.username)}/${path}`, data);
      toast(done);
      await refresh();
    } catch (e) {
      toast(e.message, true);
    }
  }

  async function openAccounts(id) {
    state.open = id;
    renderBusinesses();
    const b = state.businesses.find((x) => x.id === id);
    const { accounts } = await request('GET', `/api/businesses/${id}/accounts`);
    $('accounts-title').textContent = 'Accounts · ' + (b ? b.name : '');
    clear($('account-rows'), accounts.map((a) => {
      const actions = [];
      if (a.status === 'pending' && a.role !== 'owner') {
        actions.push(btn('Approve', () => accountAction(a, 'status', { status: 'active' }, 'Approved'), 'btn primary small'));
        actions.push(btn('Reject', () => accountAction(a, 'reject', null, 'Rejected')));
      }
      if (a.status === 'active') {
        actions.push(btn('Reset password', () => formDialog({ title: 'New password for ' + a.username,
          fields: [{ name: 'password', label: 'New password', type: 'text', required: true, help: 'At least 8 characters' }], submit: 'Set password',
          onSubmit: (v) => request('POST', `/api/accounts/${encodeURIComponent(a.username)}/password`, v) }).then((v) => { if (v) toast('Password changed'); })));
        if (a.role !== 'owner') {
          actions.push(btn('Make owner', async () => {
            if (await confirmDialog('Make ' + a.username + ' the owner?', 'The current owner becomes regular staff. A business has one owner.', 'Make owner')) accountAction(a, 'owner', null, a.username + ' is the owner now');
          }));
          actions.push(btn('Disable', () => accountAction(a, 'status', { status: 'disabled' }, 'Disabled'), 'btn small danger'));
        }
      }
      if (a.status === 'disabled') actions.push(btn('Enable', () => accountAction(a, 'status', { status: 'active' }, 'Enabled')));
      return h('tr', {},
        h('td', {}, h('b', {}, a.username), a.online ? h('span', { class: 'muted' }, ' · online') : null),
        h('td', {}, a.fullName || '—'),
        h('td', {}, a.role === 'owner' ? 'Owner' : 'Staff'),
        h('td', {}, st(a.status)),
        h('td', { class: 'actions' }, actions));
    }));
    $('accounts-card').hidden = false;
    await loadAudit();
  }

  async function loadAudit() {
    const { audit } = await request('GET', '/api/platform/audit' + (state.open ? '?business=' + state.open : ''));
    const names = Object.fromEntries(state.businesses.map((b) => [b.id, b.name]));
    $('audit-scope').textContent = state.open ? names[state.open] || '' : 'All businesses';
    clear($('audit-rows'), audit.map((a) => h('tr', {},
      h('td', { class: 'nowrap' }, a.time),
      h('td', {}, a.actor),
      h('td', {}, a.businessId == null ? '—' : names[a.businessId] || '#' + a.businessId),
      h('td', {}, a.action, a.detail ? h('div', { class: 'muted' }, a.detail) : null))));
  }

  start().catch((e) => toast(e.message, true));
})();
