// Platform admin: sign-in, businesses (approve, suspend, reactivate), their accounts, health, backups, audit log.
(function () {
  const { h, clear, toast, request, formDialog, confirmDialog } = UI;
  const $ = (id) => document.getElementById(id);
  const LABEL = { pending: 'Waiting for approval', active: 'Active', suspended: 'Suspended', deactivated: 'Deactivated', rejected: 'Rejected', disabled: 'Disabled' };
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
      actions.push(btn('Details', () => editBusiness(b)));
      return h('tr', { class: state.open === b.id ? 'is-selected' : '' },
        h('td', {}, h('b', {}, b.name), h('div', { class: 'muted' }, [b.contactName, b.phone, b.email].filter(Boolean).join(' · '))),
        h('td', {}, b.owner || '—'),
        h('td', {}, st(b.status)),
        h('td', {}, String(b.staff) + (b.pendingStaff ? ' (+' + b.pendingStaff + ' waiting)' : '') + (b.online ? ' · ' + b.online + ' online' : '')),
        h('td', { class: 'nowrap' }, (b.created || '').slice(0, 10)),
        h('td', { class: 'actions' }, actions));
    });
    clear($('business-rows'), rows.length ? rows : h('tr', {}, h('td', { colspan: 6, class: 'muted' }, 'No businesses yet.')));
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
