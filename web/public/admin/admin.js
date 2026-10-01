// LPG POS — admin dashboard (served on localhost only).
(function () {
  const { h, clear, peso, count, toast, request, formDialog, confirmDialog, fmtTime, ago } = UI;
  const { columnChart, hbars, delta, sizeTable, bucketLabel, fmtDate } = Charts;
  const $ = (id) => document.getElementById(id);

  const state = { range: '7', stats: null, overview: null, lastStats: 0, table: false };
  try { state.range = localStorage.getItem('lpg-admin-range') || '7'; } catch (_) { /* storage blocked */ }

  // ------------------------------------------------------------------ data loading

  async function loadOverview() {
    try {
      state.overview = await request('GET', '/api/overview');
      renderOverview();
    } catch (e) {
      setStatus(false, 'Server unreachable');
    }
  }

  async function loadStats() {
    $('analytics').classList.add('loading');
    try {
      state.stats = await request('GET', '/api/stats?range=' + encodeURIComponent(state.range));
      state.lastStats = Date.now();
      renderStats();
    } catch (e) {
      setStatus(false, 'Server unreachable');
    } finally {
      $('analytics').classList.remove('loading');
    }
  }

  function setStatus(ok, text) {
    const el = $('server-status');
    el.querySelector('.dot').className = 'dot ' + (ok ? 'good' : 'bad');
    el.lastChild.textContent = text;
  }

  // ------------------------------------------------------------------ overview: access, sessions, staff, activity

  function uptime(sec) {
    const d = Math.floor(sec / 86400), hr = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60);
    return d ? `${d}d ${hr}h` : hr ? `${hr}h ${m}m` : `${m}m`;
  }

  function copyButton(text, label = 'Copy') {
    return h('button', {
      class: 'btn small', type: 'button',
      onclick: () => navigator.clipboard.writeText(text).then(() => toast('Copied')).catch(() => toast('Copy failed — select the text instead', true)),
    }, label);
  }

  function renderOverview() {
    const o = state.overview;
    setStatus(true, 'Online · up ' + uptime(o.server.uptimeSeconds));
    $('open-pos').href = o.server.localUrl;

    $('url-local').href = o.server.localUrl;
    $('url-local').textContent = o.server.localUrl.replace('http://', '');
    clear($('url-lan'), o.server.lanUrls.length
      ? o.server.lanUrls.map((u) => h('div', { class: 'tunnel-row' }, h('a', { class: 'url', href: u, target: '_blank', rel: 'noopener' }, u.replace('http://', '')), copyButton(u)))
      : h('span', { class: 'muted' }, 'Not connected to a network'));

    renderTunnel(o.tunnel);
    renderInventory(o.inventory);
    renderReceivables(o.receivables);
    renderTanksOut(o.tanksOut);
    renderSessions(o.sessions);
    renderUsers(o.users);
    renderActivity(o.activity);
  }

  function renderTunnel(t) {
    const dot = $('tunnel-dot'), pill = $('tunnel-state'), body = $('tunnel-body');
    const states = {
      online: ['good', 'Public link on'],
      starting: ['warn', 'Starting…'],
      reconnecting: ['warn', 'Reconnecting…'],
      stopping: ['warn', 'Stopping…'],
      error: ['bad', 'Stopped with an error'],
      stopped: ['', 'Off'],
    };
    const [cls, label] = t.installed ? (states[t.status] || states.stopped) : ['', 'Not set up'];
    dot.className = 'dot ' + cls;
    pill.textContent = label;

    if (!t.installed) {
      clear(body,
        h('p', { class: 'hint' }, 'To open the POS from anywhere (mobile data, another branch), install Cloudflare’s free tunnel tool in Terminal:'),
        h('div', { class: 'tunnel-row' }, h('span', { class: 'code' }, 'brew install cloudflared'), copyButton('brew install cloudflared')),
        h('p', { class: 'muted hint' }, 'This card switches on automatically once it’s installed.'));
      return;
    }
    if (t.status === 'online' && t.url) {
      clear(body,
        h('div', { class: 'tunnel-row' }, h('a', { class: 'url', href: t.url, target: '_blank', rel: 'noopener' }, t.url.replace('https://', '')), copyButton(t.url),
          h('button', { class: 'btn small danger', onclick: () => tunnel('stop') }, 'Turn off')),
        h('p', { class: 'muted hint' }, 'Send this to staff: it works on any phone or laptop, on Wi-Fi or mobile data. They still sign in. The address changes whenever the link restarts (e.g. after this Mac restarts), so re-send it if it changes.'));
      return;
    }
    if (t.status === 'starting' || t.status === 'stopping') {
      clear(body, h('p', { class: 'hint' }, t.status === 'starting' ? 'Connecting to Cloudflare… this takes a few seconds.' : 'Turning off…'));
      return;
    }
    if (t.status === 'reconnecting') {
      clear(body,
        h('p', { class: 'hint' }, 'The connection dropped. Retrying automatically; you’ll get a new address to send to staff once it’s back.'),
        h('p', { class: 'muted hint' }, t.error || ''),
        h('div', { class: 'tunnel-row' }, h('button', { class: 'btn small danger', onclick: () => tunnel('stop') }, 'Turn off')));
      return;
    }
    clear(body,
      t.status === 'error' ? h('p', { class: 'error-text' }, t.error || 'The tunnel stopped unexpectedly.') : null,
      t.status === 'error' && t.log.length ? h('pre', { class: 'log' }, t.log.join('\n')) : null,
      h('div', { class: 'tunnel-row' }, h('button', { class: 'btn primary', onclick: () => tunnel('start') }, t.status === 'error' ? 'Try again' : 'Turn on public link')),
      h('p', { class: 'muted hint' }, 'Creates a temporary https://….trycloudflare.com address so staff can reach the POS from anywhere. Only the POS is shared; this dashboard stays on this Mac.'));
  }

  async function tunnel(action) {
    try {
      state.overview.tunnel = await request('POST', '/api/tunnel/' + action);
      renderTunnel(state.overview.tunnel);
    } catch (e) {
      toast(e.message, true);
    }
  }

  const STATUS = { ok: ['●', 'In stock'], low: ['▲', 'Low'], out: ['✕', 'Out of stock'] };
  const MOVE_LABEL = {
    delivery: 'Delivery', purchase: 'Bought new tanks', count: 'Stock count', damaged: 'Write-off', other: 'Adjustment',
    condition: 'Tank condition', dispose: 'Disposed', 'refill-send': 'Sent to refiller', 'refill-receive': 'Back from refiller',
    'refill-reject': 'Returned unfilled',
  };
  const PAY = { paid: ['ok', '●', 'Paid'], partial: ['low', '◐', 'Partly paid'], unpaid: ['out', '○', 'Unpaid'] };
  const payPill = (status) => h('span', { class: 'status ' + PAY[status][0] }, h('span', { class: 'i', 'aria-hidden': 'true' }, PAY[status][1]), PAY[status][2]);
  const statusPill = (status) => h('span', { class: 'status ' + status }, h('span', { class: 'i', 'aria-hidden': 'true' }, STATUS[status][0]), STATUS[status][1]);
  // Full cylinders carry the colour; empties moving in and out are routine, so they stay neutral.
  const signed = (n, word) => n === 0 ? null
    : h('span', { class: word === 'with load' ? (n > 0 ? 'delta-pos' : 'delta-neg') : 'secondary' }, `${n > 0 ? '+' : '−'}${Math.abs(n)} ${word}`);
  const moveDeltas = (m) => [signed(m.loaded, 'with load'), signed(m.empty, 'empty'), signed(m.damaged, 'damaged'), signed(m.atRefiller, 'at refiller')]
    .filter(Boolean).flatMap((x, i) => (i ? [' ', x] : [x]));

  function daysText(d, full) {
    if (full <= 0) return 'none left';
    if (d == null) return 'no sales in 14 days';
    if (d < 1) return 'less than a day left';
    return `about ${Math.round(d)} day${Math.round(d) === 1 ? '' : 's'} left`;
  }

  function renderInventory(inv) {
    const attention = inv.items.filter((i) => i.status !== 'ok');
    clear($('stock-banner'), attention.length ? h('div', { class: 'alert-banner', role: 'status' },
      h('span', { class: 'i', 'aria-hidden': 'true' }, '⚠'),
      h('div', {},
        h('strong', {}, `${attention.length} product${attention.length === 1 ? ' needs' : 's need'} refilling`),
        h('p', {}, attention.map((i) => (i.loaded <= 0 ? `${i.label}: none with load` : `${i.label}: ${i.loaded} with load (${daysText(i.daysLeft, i.loaded)})`)
          + (i.atRefiller ? `, ${i.atRefiller} at refiller` : i.empty ? `, ${i.empty} empty to send` : '')).join(' · ')))) : null);

    $('inventory-sub').textContent = `${count.format(inv.totalLoaded)} with load · ${count.format(inv.totalEmpty)} empty · ${count.format(inv.totalRefiller)} at refiller · ${count.format(inv.kgLoaded)} kg of LPG`;
    sizeTable($('by-size'), inv.bySize, inv.kgLoaded);
    // Group by supplier (most urgent product first within each), so each refiller's brands sit together.
    const groups = [];
    for (const i of inv.items) {
      let g = groups.find((x) => x.id === i.supplierId);
      if (!g) groups.push(g = { id: i.supplierId, name: i.supplier, items: [] });
      g.items.push(i);
    }
    const sup = (id) => inv.suppliers.find((s) => s.id === id);
    clear($('inventory'), inv.items.length ? groups.map((g) => [
      h('div', { class: 'inv-group' }, g.name || 'No supplier set', g.id && sup(g.id)?.contact ? h('span', { class: 'muted' }, ` · ${sup(g.id).contact}`) : null),
      h('div', { class: 'list' }, g.items.map((i) => h('div', { class: 'inv-row' },
        h('div', {},
          h('div', { class: 'name' }, i.label, i.status !== 'ok' ? statusPill(i.status) : null),
          h('div', { class: 'meta' }, [
            i.perDay <= 0 ? 'No sales in 14 days' : i.perDay < 0.1 ? 'Sells <0.1/day' : `Sells ~${i.perDay.toFixed(1)}/day`,
            i.loaded <= 0 || i.daysLeft != null ? daysText(i.daysLeft, i.loaded) : null,
            `reorder at ${i.reorderLevel}`,
          ].filter(Boolean).join(' · '))),
        h('div', { class: 'qty' }, h('strong', {}, count.format(i.loaded)),
          h('small', {}, 'with load'),
          h('small', {}, [`${count.format(i.empty)} empty`,
            i.atRefiller ? ` · ${i.atRefiller} at refiller` : null])))))]) : h('div', { class: 'empty' }, 'No products yet.'));

    clear($('refills'),
      inv.owedToSuppliers > 0 ? h('div', { class: 'owe-line' }, h('span', {}, 'Still owed to suppliers'), h('strong', { class: 'num' }, peso.format(inv.owedToSuppliers))) : null,
      inv.suppliers.filter((s) => s.owed > 0).map((s) => h('div', { class: 'owe-row' }, h('span', {}, s.name), h('strong', { class: 'num' }, peso.format(s.owed)))),
      inv.refills.length ? inv.refills.map((r) => h('div', { class: 'move-row' },
        h('div', {}, h('strong', {}, r.supplier), ` · trip #${r.id}`),
        h('div', { class: 'num' }, r.tanks ? `${r.tanks} tank${r.tanks === 1 ? '' : 's'}` : 'All back'),
        h('div', { class: 'meta' }, [
          `Sent ${fmtTime(r.time)}${r.days ? ` (${r.days} day${r.days === 1 ? '' : 's'} ago)` : ''}`,
          r.items.length ? r.items.map((x) => `${x.outstanding} × ${x.product}`).join(', ') : null,
          r.cost ? `bill ${peso.format(r.cost)}, paid ${peso.format(r.paid)}` : null,
        ].filter(Boolean).join(' · '), r.owed > 0 ? h('span', { class: 'owes-text' }, ` · ${peso.format(r.owed)} to pay`) : null))) : h('div', { class: 'empty' }, 'No tanks at the refiller.'));

    clear($('stock-moves'), inv.movements.length ? inv.movements.map((m) => h('div', { class: 'move-row' },
      h('div', {}, h('strong', {}, MOVE_LABEL[m.type] || m.type), ' · ', m.product),
      h('div', { class: 'num' }, moveDeltas(m)),
      h('div', { class: 'meta' }, [fmtTime(m.time), m.staff, m.note].filter(Boolean).join(' · ')))) : h('div', { class: 'empty' }, 'Nothing recorded yet.'));
  }

  function renderReceivables(rv) {
    $('receivables-sub').textContent = rv.customers ? `${rv.customers} customer${rv.customers === 1 ? '' : 's'} with pay-later balances` : 'Nobody owes anything';
    clear($('receivables'), rv.customers ? [
      h('div', { class: 'big-owed num' }, peso.format(rv.total)),
      h('div', { class: 'list' }, rv.top.map((c) => h('div', { class: 'owe-row' },
        h('div', {}, h('div', {}, c.customer),
          h('div', { class: 'meta' }, `${c.receipts} unpaid receipt${c.receipts === 1 ? '' : 's'}${c.since ? ` · oldest ${fmtTime(c.since)}${c.days ? ` (${c.days} days)` : ''}` : ''}`)),
        h('strong', { class: 'num' }, peso.format(c.balance))))),
    ] : h('div', { class: 'empty' }, 'All receipts are paid.'));
  }

  function renderTanksOut(t) {
    $('tanks-out-sub').textContent = t.total ? `${count.format(t.total)} tank${t.total === 1 ? '' : 's'} with ${t.customers} customer${t.customers === 1 ? '' : 's'}` : 'Every tank has come back';
    clear($('tanks-out'), t.top.length ? h('div', { class: 'list' }, t.top.map((c) => h('div', { class: 'owe-row' },
      h('div', {}, h('div', {}, c.customer),
        h('div', { class: 'meta' }, `${c.items}${c.since ? ` · since ${fmtTime(c.since)}${c.days ? ` (${c.days} days)` : ''}` : ''}`)),
      h('strong', { class: 'num' }, `${c.tanks} tank${c.tanks === 1 ? '' : 's'}`)))) : h('div', { class: 'empty' }, 'No customer owes a tank.'));
  }

  function renderSessions(sessions) {
    $('sessions-sub').textContent = sessions.length ? `${sessions.length} active session${sessions.length === 1 ? '' : 's'}` : 'Nobody is signed in';
    clear($('sessions'), sessions.length ? h('div', { class: 'list' }, sessions.map((s) => h('div', { class: 'list-row' },
      h('span', { class: 'avatar' }, s.username.slice(0, 1)),
      h('div', { class: 'grow' },
        h('div', { class: 'name' }, s.username, h('span', { class: 'pill via' }, s.via)),
        h('div', { class: 'meta' }, `${s.device} · ${s.ip} · active ${ago(s.lastSeen)}`)),
      h('button', {
        class: 'btn small ghost danger', title: 'Sign this device out',
        onclick: async () => {
          if (!(await confirmDialog(`Sign out ${s.username}?`, `Ends the session on ${s.device} (${s.ip}). They can sign in again unless you also reset their password.`, 'Sign out'))) return;
          request('DELETE', '/api/sessions/' + encodeURIComponent(s.id)).then(loadOverview).then(() => toast('Signed out')).catch((e) => toast(e.message, true));
        },
      }, 'Sign out')))) : h('div', { class: 'empty' }, 'Staff sessions appear here when someone signs in.'));
  }

  function renderUsers(users) {
    clear($('users'), users.length ? h('div', { class: 'list' }, users.map((u) => h('div', { class: 'list-row' },
      h('span', { class: 'avatar' }, u.username.slice(0, 1)),
      h('div', { class: 'grow' },
        h('div', { class: 'name' }, u.username, u.online ? h('span', { class: 'pill via' }, h('span', { class: 'dot good' }), 'online') : null),
        h('div', { class: 'meta' }, 'Added ' + fmtTime(u.created) + (u.dashboard ? ' · sees the dashboard' : ''))),
      // The admin chooses who may open the summary dashboard in the POS.
      h('button', { class: 'btn small' + (u.dashboard ? '' : ' ghost'), 'aria-pressed': String(u.dashboard), title: 'Let this person see the sales and stock summary in the POS',
        onclick: () => setDashboard(u.username, !u.dashboard) }, u.dashboard ? 'Dashboard: on' : 'Dashboard: off'),
      h('button', { class: 'btn small ghost', onclick: () => resetPassword(u.username) }, 'Reset password'),
      h('button', { class: 'btn small ghost danger', onclick: () => deleteUser(u.username) }, 'Delete')))) : h('div', { class: 'empty' }, 'No staff accounts yet. Create one below so staff can sign in.'));
  }

  function renderActivity(items) {
    clear($('activity'), items.length ? items.map((a) => h('div', { class: 'act' },
      h('div', {}, h('span', { class: 'who' }, a.actor), ' ', a.message),
      h('div', { class: 'meta' }, `${fmtTime(a.time)} · ${a.ip}`))) : h('div', { class: 'empty' }, 'Nothing yet.'));
  }

  async function newPassword() {
    return (await request('GET', '/api/password')).password;
  }

  $('gen-password').addEventListener('click', async () => {
    $('add-user').password.value = await newPassword();
  });

  $('add-user').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const username = f.username.value.trim().toLowerCase();
    const password = f.password.value;
    const err = $('add-user-error');
    err.hidden = true;
    try {
      await request('POST', '/api/users', { username, password });
      f.reset();
      showCredentials(username, password);
      loadOverview();
    } catch (ex) {
      err.textContent = ex.message;
      err.hidden = false;
    }
  });

  function showCredentials(username, password) {
    const o = state.overview;
    const link = (o.tunnel.status === 'online' && o.tunnel.url) || o.server.lanUrls[0] || o.server.localUrl;
    const text = `LPG POS sign-in\nAddress: ${link}\nUsername: ${username}\nPassword: ${password}`;
    const box = $('new-credentials');
    clear(box,
      h('strong', {}, `Account “${username}” is ready. Share these details:`),
      h('div', { class: 'cred-row' }, h('span', { class: 'muted' }, 'Address'), h('span', { class: 'code' }, link)),
      h('div', { class: 'cred-row' }, h('span', { class: 'muted' }, 'Username'), h('span', { class: 'code' }, username)),
      h('div', { class: 'cred-row' }, h('span', { class: 'muted' }, 'Password'), h('span', { class: 'code' }, password)),
      h('div', { class: 'tunnel-row' }, copyButton(text, 'Copy all'), h('button', { class: 'btn small ghost', onclick: () => { box.hidden = true; } }, 'Done')));
    box.hidden = false;
  }

  async function resetPassword(username) {
    const suggested = await newPassword();
    const result = await formDialog({
      title: `Reset password for ${username}`,
      message: 'They will be signed out on every device and need the new password to sign in.',
      submit: 'Reset password',
      fields: [{ name: 'password', label: 'New password', value: suggested, required: true, full: true, help: 'At least 8 characters. A random one is filled in for you.' }],
      onSubmit: (v) => request('PUT', `/api/users/${encodeURIComponent(username)}/password`, v).then(() => v),
    });
    if (result) {
      showCredentials(username, result.password);
      loadOverview();
    }
  }

  function setDashboard(username, on) {
    request('PUT', `/api/users/${encodeURIComponent(username)}/dashboard`, { on: on ? '1' : '0' })
      .then(loadOverview).then(() => toast(on ? `${username} can now see the dashboard` : `${username} no longer sees the dashboard`))
      .catch((e) => toast(e.message, true));
  }

  async function deleteUser(username) {
    if (!(await confirmDialog(`Delete ${username}?`, 'They will be signed out and can no longer sign in. Their past sales stay recorded under their name.'))) return;
    request('DELETE', '/api/users/' + encodeURIComponent(username)).then(loadOverview).then(() => toast('Account deleted')).catch((e) => toast(e.message, true));
  }

  // ------------------------------------------------------------------ stats

  for (const chip of document.querySelectorAll('[data-range]')) {
    chip.addEventListener('click', () => {
      state.range = chip.dataset.range;
      try { localStorage.setItem('lpg-admin-range', state.range); } catch (_) { /* ignore */ }
      markRange();
      loadStats();
    });
  }

  function markRange() {
    for (const c of document.querySelectorAll('[data-range]')) c.setAttribute('aria-pressed', String(c.dataset.range === state.range));
  }

  function renderStats() {
    const s = state.stats;
    const t = s.totals, p = s.previous;
    $('kpi-revenue').textContent = UI.pesoRound.format(t.revenue);
    $('kpi-count').textContent = count.format(t.count);
    $('kpi-qty').textContent = count.format(t.qty);
    $('kpi-avg').textContent = peso.format(t.avg);
    $('kpi-customers').textContent = count.format(t.customers);
    delta($('kpi-revenue-delta'), t.revenue, p && p.revenue, s.compare);
    delta($('kpi-count-delta'), t.count, p && p.count, s.compare);
    delta($('kpi-qty-delta'), t.qty, p && p.qty, s.compare);
    delta($('kpi-avg-delta'), t.avg, p && p.avg, s.compare);
    delta($('kpi-customers-delta'), t.customers, p && p.customers, s.compare);
    $('kpi-collected').textContent = UI.pesoRound.format(t.collected);
    delta($('kpi-collected-delta'), t.collected, p && p.collected, s.compare);
    $('kpi-supplier').textContent = UI.pesoRound.format(t.supplierPaid);
    delta($('kpi-supplier-delta'), t.supplierPaid, p && p.supplierPaid, s.compare, true);
    $('kpi-profit').textContent = t.profit == null ? '—' : UI.pesoRound.format(t.profit);
    delta($('kpi-profit-delta'), t.profit, p && p.profit, s.compare);
    // Profit = sales − each tank's refill (or new-tank) cost. Products without a cost can't be counted.
    $('kpi-profit-note').textContent = t.profit == null
      ? (s.uncosted.length ? `Set a refill cost in Products for ${s.uncosted.join(', ')}` : '')
      : s.uncosted.length ? `Leaves out ${s.uncosted.join(', ')} (no refill cost set)`
      : `${t.costedRevenue ? Math.round(t.profit / t.costedRevenue * 100) : 0}% of sales`;

    const per = { hour: 'hour', day: 'day', month: 'month' }[s.bucket];
    $('trend-title').textContent = `Revenue by ${per}`;
    $('trend-sub').textContent = s.range === 'today' ? 'Today, hour by hour' : s.range === 'all' ? `Since ${fmtDate(s.start)}` : `${fmtDate(s.start)} – today`;
    renderTrend();

    hbars($('by-product'), s.byProduct, (r) => `${count.format(r.qty)} cylinder${r.qty === 1 ? '' : 's'} · ${count.format(r.count)} receipt${r.count === 1 ? '' : 's'}${r.profit != null ? ` · ≈${UI.pesoRound.format(r.profit)} profit` : ''}`, 'No sales in this period.');
    hbars($('by-staff'), s.byStaff, (r) => `${count.format(r.count)} receipt${r.count === 1 ? '' : 's'}`, 'No sales in this period.');

    clear($('top-customers'), s.topCustomers.length ? h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'Customer'), h('th', { class: 'right' }, 'Receipts'), h('th', { class: 'right' }, 'Revenue'))),
      h('tbody', {}, s.topCustomers.map((c) => h('tr', {}, h('td', {}, c.label), h('td', { class: 'right num' }, count.format(c.count)), h('td', { class: 'right num' }, peso.format(c.revenue))))))
      : h('div', { class: 'empty' }, 'No sales in this period.'));

    $('totals-sub').textContent = `${count.format(s.counts.receipts)} receipts on record · ${count.format(s.counts.customers)} customers · ${count.format(s.counts.products)} products`;
    clear($('recent'), s.recent.length ? h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'When'), h('th', {}, 'Receipt'), h('th', { class: 'right' }, 'Total'))),
      h('tbody', {}, s.recent.map((r) => h('tr', {},
        h('td', { class: 'secondary nowrap' }, fmtTime(r.time)),
        h('td', {}, h('div', {}, `#${r.id} · ${r.customer}`), h('div', { class: 'muted' }, h('small', {}, `${r.items} · ${r.staff}`))),
        h('td', { class: 'right' }, h('div', { class: 'num' }, peso.format(r.total)), r.status !== 'paid' ? payPill(r.status) : null)))))
      : h('div', { class: 'empty' }, 'No receipts recorded yet.'));
    tickUpdated();
  }

  $('trend-toggle').addEventListener('click', () => {
    state.table = !state.table;
    $('trend-toggle').setAttribute('aria-pressed', String(state.table));
    $('trend-toggle').textContent = state.table ? 'Chart' : 'Table';
    renderTrend();
  });

  function renderTrend() {
    const s = state.stats;
    if (!s) return;
    $('trend-chart').hidden = state.table;
    $('trend-table').hidden = !state.table;
    if (state.table) {
      const rows = [...s.series].reverse();
      clear($('trend-table'), h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, s.bucket === 'hour' ? 'Hour' : s.bucket === 'month' ? 'Month' : 'Day'), h('th', { class: 'right' }, 'Receipts'), h('th', { class: 'right' }, 'Cylinders'), h('th', { class: 'right' }, 'Revenue'))),
        h('tbody', {}, rows.map((r) => h('tr', {}, h('td', {}, bucketLabel(r.key, s.bucket, true)), h('td', { class: 'right num' }, count.format(r.count)), h('td', { class: 'right num' }, count.format(r.qty)), h('td', { class: 'right num' }, peso.format(r.revenue)))))));
    } else {
      columnChart($('trend-chart'), s.series, s.bucket);
    }
  }

  // ------------------------------------------------------------------ refresh loop

  function tickUpdated() {
    if (!state.lastStats) return;
    const sec = Math.round((Date.now() - state.lastStats) / 1000);
    $('updated').textContent = sec < 5 ? 'Updated just now' : `Updated ${sec}s ago`;
  }

  let resizeTimer;
  new ResizeObserver(() => { clearTimeout(resizeTimer); resizeTimer = setTimeout(renderTrend, 80); }).observe($('trend-chart'));

  markRange();
  loadOverview();
  loadStats();
  setInterval(() => { if (document.visibilityState === 'visible') loadOverview(); }, 4000);
  setInterval(() => { if (document.visibilityState === 'visible' && !document.querySelector('dialog[open]')) loadStats(); }, 10000);
  setInterval(tickUpdated, 5000);
})();
