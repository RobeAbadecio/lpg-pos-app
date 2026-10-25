// Team page for the store owner: approve, add, disable staff; dashboard access; store details.
(function () {
  const { h, clear, toast, request, formDialog, confirmDialog } = UI;
  const $ = (id) => document.getElementById(id);
  const STATUS = { active: 'Active', pending: 'Waiting for approval', disabled: 'Disabled' };

  function fail(message) {
    $('problem').textContent = message;
    $('problem').hidden = false;
  }

  async function act(username, action, data, done) {
    try {
      await request('POST', `/api/team/${encodeURIComponent(username)}/${action}`, data);
      toast(done);
      await load();
    } catch (e) {
      toast(e.message, true);
    }
  }

  function row(a) {
    const buttons = [];
    if (a.role === 'owner') buttons.push(h('span', { class: 'muted' }, 'Store owner (you)'));
    else if (a.status === 'pending') {
      buttons.push(h('button', { class: 'btn primary small', type: 'button', onclick: () => act(a.username, 'approve', null, a.username + ' approved') }, 'Approve'));
      buttons.push(h('button', { class: 'btn small', type: 'button', onclick: async () => {
        if (await confirmDialog('Reject ' + a.username + '?', 'They will not get access. They can sign up again.', 'Reject')) act(a.username, 'reject', null, 'Sign-up rejected');
      } }, 'Reject'));
    } else if (a.status === 'active') {
      buttons.push(h('button', { class: 'btn small', type: 'button', onclick: () => resetPassword(a.username) }, 'Reset password'));
      buttons.push(h('button', { class: 'btn small danger', type: 'button', onclick: async () => {
        if (await confirmDialog('Disable ' + a.username + '?', 'They are signed out now and can’t sign in until you enable them again. Their past records stay.', 'Disable')) act(a.username, 'disable', null, a.username + ' disabled');
      } }, 'Disable'));
    } else {
      buttons.push(h('button', { class: 'btn small', type: 'button', onclick: () => act(a.username, 'enable', null, a.username + ' enabled') }, 'Enable'));
    }
    const dash = a.role === 'owner' ? h('span', { class: 'muted' }, 'Always')
      : a.status !== 'active' ? h('span', { class: 'muted' }, '—')
      : h('input', { type: 'checkbox', 'aria-label': 'Dashboard access for ' + a.username, checked: a.dashboard,
        onchange: (e) => act(a.username, 'dashboard', { on: e.target.checked ? '1' : '0' }, e.target.checked ? 'Dashboard access given' : 'Dashboard access removed') });
    return h('tr', {},
      h('td', {}, h('b', {}, a.username), a.online ? h('span', { class: 'muted' }, ' · online') : null),
      h('td', {}, a.fullName || '—'),
      h('td', {}, h('span', { class: 'st st-' + a.status }, STATUS[a.status] || a.status)),
      h('td', {}, dash),
      h('td', { class: 'actions' }, buttons));
  }

  function resetPassword(username) {
    formDialog({ title: 'New password for ' + username, message: 'They are signed out everywhere. Tell them the new password in person.',
      fields: [{ name: 'password', label: 'New password', type: 'text', required: true, help: 'At least 8 characters' }],
      submit: 'Set password',
      onSubmit: (v) => request('POST', `/api/team/${encodeURIComponent(username)}/password`, v).then(() => v) })
      .then((v) => { if (v) toast('Password changed'); });
  }

  $('add-staff').addEventListener('click', () => {
    formDialog({ title: 'Add staff', message: 'The account works right away. Give them the username and password.',
      fields: [
        { name: 'username', label: 'Username', required: true, help: '3–32 letters, numbers, dot, dash or underscore' },
        { name: 'fullName', label: 'Name' },
        { name: 'password', label: 'Password', type: 'text', required: true, help: 'At least 8 characters' }],
      submit: 'Add staff',
      onSubmit: (v) => request('POST', '/api/team', v).then(() => v) })
      .then((v) => { if (v) { toast('Staff account added'); load(); } });
  });

  $('store-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const data = {};
    for (const el of f.elements) if (el.name) data[el.name] = el.value.trim();
    try {
      await request('PUT', '/api/business', data);
      $('store-name').textContent = data.name;
      toast('Store details saved');
    } catch (ex) {
      toast(ex.message, true);
    }
  });

  async function load() {
    const team = await request('GET', '/api/team');
    $('store-code').textContent = team.code;
    clear($('staff-rows'), team.accounts.map(row));
  }

  (async function start() {
    let me;
    try {
      me = await request('GET', '/api/me');
    } catch (e) {
      if (e.status === 401) { location.href = '/'; return; }
      fail(e.message);
      return;
    }
    if (me.role !== 'owner') { fail('Only the store owner can manage staff.'); return; }
    $('store-name').textContent = me.business.name;
    const b = await request('GET', '/api/business');
    for (const k of ['name', 'contactName', 'phone', 'email', 'address']) $('store-form')[k].value = b[k] || '';
    $('staff-card').hidden = false;
    $('store-card').hidden = false;
    await load().catch((e) => fail(e.message));
  })();
})();
