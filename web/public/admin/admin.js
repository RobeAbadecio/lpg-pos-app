// LPG POS — admin dashboard (served on localhost only).
(function () {
  const { h, clear, peso, count, toast, request, formDialog, confirmDialog, fmtTime, ago } = UI;
  const { columnChart, hbars, delta, sizeTable, bucketLabel, fmtDate } = Charts;
  const $ = (id) => document.getElementById(id);

  const state = { period: 'day', supPeriod: 'day', supplier: '', stats: null, revenue: null, suppliers: null, overview: null, lastStats: 0, table: false };
  try {
    state.period = localStorage.getItem('lpg-admin-period') || 'day';
    state.supPeriod = localStorage.getItem('lpg-admin-sup-period') || 'day';
    state.supplier = localStorage.getItem('lpg-admin-supplier') || '';
  } catch (_) { /* storage blocked */ }

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
      const [stats, revenue, suppliers] = await Promise.all([
        request('GET', '/api/stats?range=' + encodeURIComponent(statsRange())),
        request('GET', '/api/revenue?period=' + encodeURIComponent(state.period)),
        request('GET', '/api/supplier-summary?period=' + encodeURIComponent(state.supPeriod) + '&supplier=' + encodeURIComponent(state.supplier))]);
      state.stats = stats;
      state.revenue = revenue;
      state.suppliers = suppliers;
      state.lastStats = Date.now();
      renderStats();
      renderRevenue();
      renderSuppliers();
    } catch (e) {
      setStatus(false, 'Server unreachable');
    } finally {
      $('analytics').classList.remove('loading');
    }
  }

  // One Daily / Monthly / Quarterly switch drives the whole Customers tab: today, this month or this quarter so far.
  const statsRange = () => ({ day: 'today', month: 'month', quarter: 'quarter' }[state.period] || 'today');

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

    clear($('url-lan'), o.server.lanUrls.length
      ? o.server.lanUrls.map((u) => h('div', { class: 'tunnel-row' }, h('a', { class: 'url', href: u, target: '_blank', rel: 'noopener' }, u.replace('http://', '')), copyButton(u)))
      : h('span', { class: 'muted' }, 'Disabled. Staff use the Cloudflare internet link.'));

    renderTunnel(o.tunnel);
    renderInventory(o.inventory);
    renderReceivables(o.receivables);
    renderTanksOut(o.tanksOut);
    renderStaffMoney(o.staffMoney);
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

    $('inventory-sub').textContent = `${count.format(inv.totalLoaded)} with load · ${count.format(inv.totalEmpty)} empty · ${count.format(inv.totalRefiller)} at refiller · ${count.format(inv.totalTanks)} tanks in all`;
    sizeTable($('by-size'), inv.bySize);
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

  // Staff who paid suppliers from their own pocket: what the store still owes them.
  function renderStaffMoney(m) {
    $('staff-money-sub').textContent = m.total > 0 ? `The store owes staff ${peso.format(m.total)}` : 'Nobody is owed anything';
    clear($('staff-money'),
      m.owed.length ? h('div', { class: 'list' }, m.owed.map((o) => h('div', { class: 'owe-row' }, h('span', {}, o.staff), h('strong', { class: 'num' }, peso.format(o.owed)))))
        : h('div', { class: 'empty' }, 'Staff paying suppliers with their own money shows here until they’re paid back.'),
      m.recent.length ? h('h3', { class: 'minor-head' }, 'Recent') : null,
      m.recent.slice(0, 6).map((e) => h('div', { class: 'move-row' },
        h('div', {}, h('strong', {}, e.kind === 'advance' ? `${e.staff} paid ${e.supplier}` : `Paid back to ${e.staff}`), e.kind === 'advance' ? ` · ${e.bill}` : ''),
        h('div', { class: 'num ' + (e.kind === 'advance' ? 'owes-text' : 'secondary') }, (e.kind === 'advance' ? '+' : '−') + peso.format(e.amount)),
        h('div', { class: 'meta' }, [fmtTime(e.time), e.by, e.note].filter(Boolean).join(' · ')))));
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
    clear($('users'), users.length ? h('div', { class: 'staff-list' }, users.map((u) => h('article', { class: 'staff-account' },
      h('div', { class: 'staff-identity' },
        h('span', { class: 'avatar', 'aria-hidden': 'true' }, u.username.slice(0, 1)),
        h('div', { class: 'staff-details' },
          h('div', { class: 'staff-name' }, u.username),
          h('div', { class: 'staff-meta' }, 'Added ' + fmtTime(u.created))),
        u.online ? h('span', { class: 'pill via' }, h('span', { class: 'dot good' }), 'Online') : null),
      h('div', { class: 'staff-permission' },
        h('div', {}, h('strong', {}, 'Dashboard access'), h('div', { class: 'staff-meta' }, 'View sales and stock in the POS')),
        h('label', { class: 'switch', title: 'Let this person see the sales and stock dashboard in the POS' },
          h('input', { type: 'checkbox', role: 'switch', checked: u.dashboard, 'aria-label': `Dashboard for ${u.username}`,
            onchange: (e) => setDashboard(u.username, e.target.checked) }),
          h('span', { class: 'track', 'aria-hidden': 'true' }),
          h('span', {}, u.dashboard ? 'On' : 'Off'))),
      h('div', { class: 'staff-actions' },
        h('button', { class: 'btn small', 'aria-label': `Reset password for ${u.username}`, onclick: () => resetPassword(u.username) }, 'Reset password'),
        h('button', { class: 'btn small ghost danger', 'aria-label': `Delete account for ${u.username}`, onclick: () => deleteUser(u.username) }, 'Delete account'))))) : h('div', { class: 'empty' }, 'No staff accounts yet. Create one below so staff can sign in.'));
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
    const link = (o.tunnel.status === 'online' && o.tunnel.url) || 'Start the Cloudflare internet link before staff sign in.';
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
      .catch((e) => { toast(e.message, true); loadOverview(); }); // put the switch back to what's saved
  }

  async function deleteUser(username) {
    if (!(await confirmDialog(`Delete ${username}?`, 'They will be signed out and can no longer sign in. Their past sales stay recorded under their name.'))) return;
    request('DELETE', '/api/users/' + encodeURIComponent(username)).then(loadOverview).then(() => toast('Account deleted')).catch((e) => toast(e.message, true));
  }

  // Business records are loaded on demand, never replaced by background refreshes.
  // Raw CSV columns get plain names, readable lookups (customer/product/supplier names) and typed inputs.
  const database = { data: null, page: 0, loading: false, sort: null, names: {}, last: null };
  const PAGE = 25;
  const TABLES = {
    Receipts: ['Receipts', 'One row per sale: customer, discount and money paid at the sale. The items are in Sale lines.'],
    Transactions: ['Sale lines', 'Each item sold on a receipt: product, quantity, price, empty tank brought back.'],
    Payments: ['Customer payments', 'Money customers paid later for a receipt.'],
    Customers: ['Customers', 'People who buy LPG.'],
    LPGs: ['Products', 'Each LPG brand and size with its prices and costs.'],
    StockMovements: ['Stock changes', 'Every change to tank counts: deliveries, counts, refiller trips, write-offs. Stock is calculated from these and from sales.'],
    TankReturns: ['Tank returns', 'Empty tanks customers brought back for tanks they owed. Delete one to undo a collection.'],
    Suppliers: ['Suppliers', 'Refillers, one brand each.'],
    Refills: ['Refiller trips', 'Each trip of empties sent to a refiller. The tanks are in Stock changes.'],
    SupplierPayments: ['Supplier payments', 'Money paid to refillers and for new tanks.'],
    StaffPaybacks: ['Staff paybacks', 'Money the store gave back to staff who paid suppliers from their own pocket.'],
    Expenses: ['Expenses', 'Delivery gasoline, salaries, salary advances and other costs. For a salary, Amount is what was handed over.'],
    TankDeposits: ['Kept customer tanks', 'Customers’ own tanks left with the store, given back, or handed over as the empty on a sale. They are not the store’s stock.'],
    StartingBalances: ['Starting balances', 'The business as it stood on the start date. Staff with dashboard access change these in Dashboard → Starting balances.'],
  };
  const MOVE_TYPES = { delivery: 'Delivery', purchase: 'Bought new tanks', count: 'Stock count', damaged: 'Write-off', other: 'Adjustment',
    condition: 'Tank condition', 'refill-send': 'Sent to refiller', 'refill-receive': 'Back from refiller', 'refill-reject': 'Returned unfilled' };
  // kind: text | long | money | int | signed | datetime | yesno | ref | choice | staff
  const FIELDS = {
    id: ['ID', 'text'], dateTime: ['Date & time', 'datetime'],
    customerId: ['Customer', 'ref', { ref: 'Customers' }], lpgId: ['Product', 'ref', { ref: 'LPGs' }],
    emptyLpgId: ['Empty brought back', 'ref', { ref: 'LPGs', optional: 'None — customer owes the tank', hint: 'The brand of empty tank the customer gave.' }],
    supplierId: ['Supplier', 'ref', { ref: 'Suppliers' }],
    receiptId: ['Receipt #', 'text', { hint: 'Blank on sales made before receipts existed.' }],
    refillId: ['Refiller trip #', 'text', { hint: 'Blank unless this is part of a refiller trip.' }],
    qty: ['Quantity', 'int'], unitPrice: ['Price each (₱)', 'money'], staff: ['Staff', 'staff'],
    returnedEmpty: ['Empty returned', 'yesno'], remark: ['Remark', 'long'], owesTank: ['Customer owes the tank', 'yesno'],
    swapFee: ['Swap fee (₱)', 'money'], firstName: ['First name', 'text'], lastName: ['Last name', 'text'],
    contactNo: ['Contact no.', 'text'], address: ['Address', 'long'], brand: ['Brand', 'text'],
    price: ['Refill price (₱)', 'money'], weight: ['Size (kg)', 'money'], reorderLevel: ['Reorder at (tanks)', 'int'],
    tankPrice: ['New-tank price (₱)', 'money'], refillCost: ['Refill cost (₱)', 'money'], tankCost: ['New-tank cost (₱)', 'money'],
    discount: ['Discount (₱)', 'money'], paidAtSale: ['Paid at sale (₱)', 'money'], note: ['Note', 'long'], amount: ['Amount (₱)', 'money'],
    type: ['Type', 'choice', { options: MOVE_TYPES }],
    loadedDelta: ['With load (+/−)', 'signed'], emptyDelta: ['Empty (+/−)', 'signed'], damagedDelta: ['Damaged (+/−)', 'signed'],
    refillerDelta: ['At refiller (+/−)', 'signed'], unitCost: ['Cost each (₱)', 'money'],
    name: ['Name', 'text'], contact: ['Contact', 'text'],
    kind: ['For', 'choice', { options: { refill: 'Refill trip', purchase: 'New tanks', start: 'Owed on the start date' } }],
    source: ['Paid from', 'choice', { options: { sales: 'Sales money', owner: 'Owner’s personal money', staff: 'Staff’s own money' }, optional: 'Sales money (older record)' }],
    paidBy: ['Staff who paid', 'staff', { hint: 'Only when paid from staff’s own money.' }], recordedBy: ['Recorded by', 'staff'],
    category: ['What for', 'choice', { options: { gasoline: 'Delivery gasoline', salary: 'Salary', advance: 'Salary advance', other: 'Other' } }],
    employee: ['Employee', 'text'], advanceDeducted: ['Advance taken off (₱)', 'money'],
    method: ['Paid by', 'choice', { options: { cash: 'Cash', check: 'Check', online: 'Online' }, optional: 'Cash (older record)' }],
  };
  const OVERRIDES = {
    'SupplierPayments.refillId': ['Bill', 'text', { hint: 'Refiller trip #, P + the stock change # of new tanks bought, or O + the supplier # for what was owed on the start date.' }],
    'Payments.receiptId': ['Receipt #', 'text', { hint: 'O + the customer # for a payment on their starting balance.' }],
    'Refills.start': ['Already there on the start date', 'yesno'],
    'TankDeposits.kind': ['What happened', 'choice', { options: { in: 'Left with the store', back: 'Given back', used: 'Used as the empty on a sale' } }],
    'TankDeposits.receiptId': ['Receipt #', 'text', { hint: 'Only when used as the empty on a sale.' }],
    'StartingBalances.kind': ['What', 'choice', { options: { start: 'Start date', cash: 'Cash on hand', loaded: 'Tanks with load', empty: 'Empty tanks',
      refiller: 'At the refiller', 'customer-money': 'Customer owes money', 'customer-tanks': 'Customer has our tanks', deposit: 'Customer tank kept',
      supplier: 'Owed to supplier', advance: 'Salary advance owed' } }],
    'StartingBalances.ref': ['Customer, supplier or employee', 'text', { hint: 'Customer # or supplier # (employee name for an advance).' }],
    'StartingBalances.lpgId': ['Product', 'ref', { ref: 'LPGs', optional: 'None' }],
    'StartingBalances.trip': ['Refiller trip #', 'text'],
    'StaffPaybacks.staff': ['Paid back to', 'staff'],
    'Payments.method': ['Paid with', 'choice', { options: { cash: 'Cash', gcash: 'GCash' }, optional: 'Cash (older record)' }],
  };
  const meta = (table, name) => {
    const [label, kind, opts] = OVERRIDES[table + '.' + name] || FIELDS[name] || [name, 'text'];
    return { name, label, kind, ...(opts || {}) };
  };
  const tableLabel = (t) => (TABLES[t] || [t])[0];
  const NAME_OF = {
    Customers: (r) => `${r[1]} ${r[2]}`.trim(),
    LPGs: (r) => `${r[1]} ${isNaN(r[3]) ? r[3] : Number(r[3])} kg`,
    Suppliers: (r) => r[1],
  };
  const pad = (n) => String(n).padStart(2, '0');
  const nowText = () => { const d = new Date(); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`; };
  // How a stored value reads in the table and in change summaries.
  function shown(m, v) {
    if (v == null || v === '') return m.kind === 'ref' && m.optional ? 'None' : '';
    if (m.kind === 'ref') { const n = (database.names[m.ref] || {})[v]; return n ? `${n} (#${v})` : `#${v} (missing)`; }
    if (m.kind === 'yesno') return v === '1' ? 'Yes' : v === '0' ? 'No' : v;
    if (m.kind === 'choice') return m.options[v] || v;
    if (m.kind === 'money' && v !== '' && !isNaN(v)) return count.format(Number(v));
    if (m.kind === 'signed' && Number(v) > 0) return '+' + v;
    return v;
  }

  clear($('database-table'), Object.entries(TABLES).map(([value, [label]]) => h('option', { value }, label)));
  try { const t = localStorage.getItem('lpg-admin-table'); if (TABLES[t]) $('database-table').value = t; } catch (_) { /* storage blocked */ }

  async function loadDatabase() {
    if (database.loading) return;
    database.loading = true;
    const table = $('database-table').value;
    $('database-add').disabled = true;
    $('database-help').textContent = TABLES[table][1];
    clear($('database-status'), 'Loading records…');
    try {
      // The three lookup tables turn IDs into names.
      const [data, ...lookups] = await Promise.all([table, 'Customers', 'LPGs', 'Suppliers']
        .map((t) => request('GET', '/api/database/' + encodeURIComponent(t))));
      if ($('database-table').value !== table) return;
      database.names = {};
      for (const l of lookups) database.names[l.table] = Object.fromEntries(l.rows.map((r) => [r[0], NAME_OF[l.table](r)]));
      if (!database.data || database.data.table !== table) {
        database.page = 0;
        database.sort = { col: data.fields.indexOf('dateTime') >= 0 ? data.fields.indexOf('dateTime') : 0, desc: true };
      }
      database.data = data;
      renderDatabase();
    } catch (e) {
      database.data = null;
      clear($('database-records')); clear($('database-pages'));
      clear($('database-status'), e.message);
    } finally { database.loading = false; }
  }

  function renderDatabase() {
    const data = database.data;
    if (!data) return;
    const metas = data.fields.map((f) => meta(data.table, f));
    const query = $('database-search').value.toLowerCase().trim();
    const rows = data.rows.filter((row) => !query || row.some((v, i) => `${v} ${metas[i] ? shown(metas[i], v) : ''}`.toLowerCase().includes(query)));
    const { col, desc } = database.sort;
    const num = (v) => v !== '' && !isNaN(v);
    rows.sort((a, b) => {
      const x = a[col] || '', y = b[col] || '';
      const c = num(x) && num(y) ? x - y : String(x).localeCompare(String(y));
      return desc ? -c : c;
    });
    const pages = Math.max(1, Math.ceil(rows.length / PAGE));
    database.page = Math.min(database.page, pages - 1);
    const first = database.page * PAGE;
    $('database-add').disabled = false;
    if (!database.last) clear($('database-status'), rows.length
      ? `Showing ${first + 1}–${Math.min(first + PAGE, rows.length)} of ${rows.length}${query ? ` matching (${data.rows.length} in all)` : ''}. Click a row to edit it.`
      : '');
    const sortBy = (i) => { database.sort = { col: i, desc: database.sort.col === i ? !database.sort.desc : i === 0 || metas[i].kind === 'datetime' }; renderDatabase(); };
    clear($('database-records'), rows.length ? h('table', { class: 'db-table' },
      h('thead', {}, h('tr', {}, metas.map((m, i) => h('th', { title: m.name, 'aria-sort': col === i ? (desc ? 'descending' : 'ascending') : null },
        h('button', { type: 'button', class: 'th-sort', onclick: () => sortBy(i) }, m.label, col === i ? (desc ? ' ↓' : ' ↑') : ''))), h('th', {}, ''))),
      h('tbody', {}, rows.slice(first, first + PAGE).map((row) => h('tr', { class: 'clickable', onclick: () => editDatabase('edit', row) },
        metas.map((m, i) => h('td', { class: ['money', 'int', 'signed'].includes(m.kind) ? 'right num' : m.kind === 'datetime' || i === 0 ? 'nowrap' : m.kind === 'ref' ? 'ref' : null }, shown(m, row[i] || ''))),
        h('td', { class: 'nowrap' },
          h('button', { type: 'button', class: 'btn small', onclick: (e) => { e.stopPropagation(); editDatabase('edit', row); } }, 'Edit'), ' ',
          h('button', { type: 'button', class: 'btn small danger', onclick: (e) => { e.stopPropagation(); editDatabase('delete', row); } }, 'Delete'))))))
      : h('div', { class: 'empty' }, query ? 'No records match your search.' : 'This table is empty.'));
    clear($('database-pages'), pages > 1 ? [
      h('button', { type: 'button', class: 'btn small', disabled: database.page === 0, onclick: () => { database.page--; renderDatabase(); } }, '‹ Newer'),
      h('span', { class: 'muted' }, `Page ${database.page + 1} of ${pages}`),
      h('button', { type: 'button', class: 'btn small', disabled: database.page >= pages - 1, onclick: () => { database.page++; renderDatabase(); } }, 'Older ›')] : null);
  }

  // One typed input per column. get() returns the stored CSV text.
  function fieldInput(m, value, locked) {
    let el, get = () => el.value;
    const select = (pairs) => h('select', { class: 'input' }, pairs.map(([v, label]) => h('option', { value: v }, label)));
    if (locked) el = h('input', { class: 'input', readonly: true });
    else if (m.kind === 'ref' || m.kind === 'choice') {
      const pairs = Object.entries(m.kind === 'ref' ? database.names[m.ref] || {} : m.options).map(([v, n]) => [v, m.kind === 'ref' ? `${n} (#${v})` : n]);
      if (m.kind === 'ref') pairs.sort((a, b) => a[1].localeCompare(b[1]));
      if (m.optional) pairs.unshift(['', m.optional]);
      if (value !== '' && !pairs.some(([v]) => v === value)) pairs.push([value, m.kind === 'ref' ? `#${value} (missing)` : value]);
      if (value === '' && !m.optional) pairs.unshift(['', '— choose —']);
      el = select(pairs);
    } else if (m.kind === 'yesno') {
      el = select([...(value === '' ? [['', '— not set (older record)']] : []), ['1', 'Yes'], ['0', 'No']]);
    } else if (m.kind === 'datetime') {
      el = h('input', { class: 'input', type: 'datetime-local', step: 1 });
      get = () => { const v = el.value.replace('T', ' '); return v.length === 16 ? v + ':00' : v; };
      el.value = value.replace(' ', 'T');
      return { el, get };
    } else if (['money', 'int', 'signed'].includes(m.kind)) {
      el = h('input', { class: 'input num', type: 'number', step: m.kind === 'money' ? 'any' : 1, min: m.kind === 'signed' ? null : 0, inputmode: m.kind === 'signed' ? null : 'decimal' });
    } else if (m.kind === 'staff') {
      el = h('input', { class: 'input', list: 'db-staff', autocomplete: 'off' });
    } else if (m.kind === 'long') {
      el = h('textarea', { class: 'input', rows: 2, maxlength: 4000 });
    } else el = h('input', { class: 'input', maxlength: 4000 });
    el.value = value;
    return { el, get };
  }

  async function editDatabase(mode, row = []) {
    const data = database.data;
    if (!data) return;
    const metas = data.fields.map((f) => meta(data.table, f));
    const removing = mode === 'delete', adding = mode === 'add';
    if (adding) {
      const ids = data.rows.map((r) => Number(r[0])).filter(Number.isFinite);
      row = data.fields.map((f) => f === 'id' ? String(ids.length ? Math.max(...ids) + 1 : 1) : f === 'dateTime' ? nowText() : '');
    }
    const inputs = metas.map((m, i) => fieldInput(m, row[i] || '', i === 0 && !adding));
    const values = () => inputs.map((x) => x.get());
    const changes = h('div', { class: 'db-changes', 'aria-live': 'polite' });
    const diff = () => metas.map((m, i) => [m, row[i] || '', values()[i]]).filter(([, a, b]) => a !== b);
    const showChanges = () => {
      if (adding || removing) return;
      const d = diff();
      clear(changes, d.length ? [h('strong', {}, d.length === 1 ? '1 change' : `${d.length} changes`),
        h('ul', {}, d.map(([m, a, b]) => h('li', {}, `${m.label}: `, h('s', {}, shown(m, a) || 'blank'), ' → ', h('b', {}, shown(m, b) || 'blank'))))]
        : h('span', { class: 'muted' }, 'No changes yet.'));
    };
    const reason = h('input', { class: 'input', maxlength: 200, placeholder: removing ? 'e.g. Entered twice by mistake' : 'e.g. Wrong quantity typed' });
    const staffList = h('datalist', { id: 'db-staff' }, ((state.overview && state.overview.users) || []).map((u) => h('option', { value: u.username })));
    const body = h('div', {},
      staffList,
      removing
        ? h('dl', { class: 'db-view' }, metas.flatMap((m, i) => [h('dt', {}, m.label), h('dd', {}, shown(m, row[i] || '') || '—')]))
        : h('div', { class: 'db-edit-fields' }, metas.map((m, i) => h('label', { class: 'field' + (m.kind === 'long' ? ' wide' : '') },
          h('span', {}, m.label), inputs[i].el, m.hint ? h('small', { class: 'muted' }, m.hint) : null))),
      changes,
      h('label', { class: 'field' }, h('span', {}, 'Why are you making this change?'), reason));
    body.addEventListener('input', showChanges);
    body.addEventListener('change', showChanges);
    showChanges();
    const what = `${tableLabel(data.table)}${row[0] ? ' #' + row[0] : ''}`;
    const result = await UI.openDialog({
      title: `${removing ? 'Delete' : adding ? 'Add to' : 'Edit'} ${what}`,
      message: removing ? 'This removes the record. Balances and stock are recalculated. A backup is saved first, and you can undo it afterwards.'
        : adding ? 'A backup is saved first. Linked balances and stock update automatically.'
        : 'Change what’s wrong. The ID can’t change. A backup is saved first, and you can undo it afterwards.',
      wide: true, danger: removing, submit: removing ? 'Delete record' : adding ? 'Add record' : 'Save changes',
      body,
      onSubmit: async () => {
        if (mode === 'edit' && !diff().length) throw new Error('Nothing has changed.');
        if (!reason.value.trim()) { reason.focus(); throw new Error('Say why you’re making this change. It’s kept with the backup.'); }
        const after = removing ? null : values();
        const saved = await saveRecord(data.table, mode, row[0] || '', after, reason.value.trim(), data.revision);
        return { ...saved, before: adding ? null : row, after };
      },
    });
    if (result) {
      database.last = { table: data.table, mode, before: result.before, after: result.after, reason: reason.value.trim(), backup: result.backup };
      await afterSave(`${removing ? 'Deleted' : adding ? 'Added' : 'Saved'} ${what}.`);
    }
  }

  function saveRecord(table, mode, recordId, row, reason, revision) {
    const payload = { mode, recordId, revision, reason };
    (row || []).forEach((v, i) => { payload['field' + i] = v; });
    return request('PUT', '/api/database/' + encodeURIComponent(table), payload);
  }

  async function afterSave(message) {
    const last = database.last;
    await loadDatabase();
    loadOverview(); loadStats(); daily.refresh();
    clear($('database-status'), h('span', { class: 'db-saved' }, '✓ ', message), ' ',
      last ? h('button', { type: 'button', class: 'btn small', onclick: undoLast }, 'Undo') : null,
      h('small', { class: 'muted db-backup', title: last && last.backup }, ' Backup saved.'));
    toast(message);
  }

  // Undo = the opposite correction, checked by the server like any other (it refuses if records changed since).
  async function undoLast() {
    const last = database.last;
    if (!last) return;
    if (!(await confirmDialog('Undo this change?', `This reverses your last change to ${tableLabel(last.table)} and saves another backup first.`, 'Undo'))) return;
    try {
      const fresh = await request('GET', '/api/database/' + encodeURIComponent(last.table));
      const id = (last.after || last.before)[0];
      if (last.mode === 'add') await saveRecord(last.table, 'delete', id, null, 'Undo: ' + last.reason, fresh.revision);
      else if (last.mode === 'delete') await saveRecord(last.table, 'add', '', last.before, 'Undo: ' + last.reason, fresh.revision);
      else await saveRecord(last.table, 'edit', id, last.before, 'Undo: ' + last.reason, fresh.revision);
      database.last = null;
      $('database-table').value = last.table;
      await afterSave('Change undone.');
    } catch (e) { toast(e.message, true); }
  }

  $('database-reload').addEventListener('click', () => { database.last = null; loadDatabase(); });
  $('database-add').addEventListener('click', () => editDatabase('add'));
  $('database-search').addEventListener('input', () => { database.page = 0; database.last = null; renderDatabase(); });
  $('database-table').addEventListener('change', () => {
    try { localStorage.setItem('lpg-admin-table', $('database-table').value); } catch (_) { /* storage blocked */ }
    database.data = null; database.last = null;
    $('database-search').value = '';
    clear($('database-records')); clear($('database-pages'));
    loadDatabase();
  });
  loadDatabase();

  // ------------------------------------------------------------------ stats

  for (const chip of document.querySelectorAll('[data-period]')) {
    chip.addEventListener('click', () => {
      state.period = chip.dataset.period;
      try { localStorage.setItem('lpg-admin-period', state.period); } catch (_) { /* ignore */ }
      markRange();
      loadStats();
    });
  }

  for (const chip of document.querySelectorAll('[data-sup-period]')) {
    chip.addEventListener('click', () => {
      state.supPeriod = chip.dataset.supPeriod;
      try { localStorage.setItem('lpg-admin-sup-period', state.supPeriod); } catch (_) { /* ignore */ }
      markRange();
      loadStats();
    });
  }

  $('sup-supplier').addEventListener('change', (e) => {
    state.supplier = e.target.value;
    try { localStorage.setItem('lpg-admin-supplier', state.supplier); } catch (_) { /* ignore */ }
    loadStats();
  });

  function markRange() {
    for (const c of document.querySelectorAll('[data-sup-period]')) c.setAttribute('aria-pressed', String(c.dataset.supPeriod === state.supPeriod));
    for (const c of document.querySelectorAll('[data-period]')) c.setAttribute('aria-pressed', String(c.dataset.period === state.period));
  }

  function renderStats() {
    const s = state.stats;
    const t = s.totals, p = s.previous;
    $('kpi-count').textContent = count.format(t.count);
    $('kpi-qty').textContent = count.format(t.qty);
    $('kpi-avg').textContent = peso.format(t.avg);
    $('kpi-customers').textContent = count.format(t.customers);
    delta($('kpi-count-delta'), t.count, p && p.count, s.compare);
    delta($('kpi-qty-delta'), t.qty, p && p.qty, s.compare);
    delta($('kpi-avg-delta'), t.avg, p && p.avg, s.compare);
    delta($('kpi-customers-delta'), t.customers, p && p.customers, s.compare);
    $('kpi-collected').textContent = UI.pesoRound.format(t.collected);
    delta($('kpi-collected-delta'), t.collected, p && p.collected, s.compare);
    $('kpi-collected-note').textContent = t.collected ? `${peso.format(t.collected - t.collectedGcash)} cash · ${peso.format(t.collectedGcash)} GCash` : '';
    $('kpi-profit').textContent = t.profit == null ? '—' : UI.pesoRound.format(t.profit);
    delta($('kpi-profit-delta'), t.profit, p && p.profit, s.compare);
    // Profit = sales − each tank's refill (or new-tank) cost. Products without a cost can't be counted.
    $('kpi-profit-note').textContent = t.profit == null
      ? (s.uncosted.length ? `Set a refill cost in Products for ${s.uncosted.join(', ')}` : '')
      : s.uncosted.length ? `Leaves out ${s.uncosted.join(', ')} (no refill cost set)`
      : `${t.costedRevenue ? Math.round(t.profit / t.costedRevenue * 100) : 0}% of sales`;

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

  // Revenue card: today, this month or this quarter so far, against the previous one up to the same point.
  const PERIODS = {
    day: { label: 'Revenue today', title: 'Revenue by day', sub: 'Last 30 days', compare: 'vs same time yesterday' },
    month: { label: 'Revenue this month', title: 'Revenue by month', sub: 'Last 12 months', compare: 'vs same point last month' },
    quarter: { label: 'Revenue this quarter', title: 'Revenue by quarter', sub: 'Last 8 quarters', compare: 'vs same point last quarter' },
  };

  function renderRevenue() {
    const r = state.revenue;
    if (!r) return;
    const p = PERIODS[r.period];
    $('revenue-label').textContent = p.label;
    $('period-revenue').textContent = UI.pesoRound.format(r.current.revenue);
    delta($('period-revenue-delta'), r.current.revenue, r.previous.revenue, p.compare);
    $('trend-title').textContent = p.title;
    $('trend-sub').textContent = p.sub;
    renderTrend();
  }

  // Supplier summary: the same periods, for refill trips, tanks and money paid out.
  const SUP_PERIODS = {
    day: { now: 'today', compare: 'vs same time yesterday', head: 'Day' },
    month: { now: 'this month', compare: 'vs same point last month', head: 'Month' },
    quarter: { now: 'this quarter', compare: 'vs same point last quarter', head: 'Quarter' },
  };

  function renderSuppliers() {
    const s = state.suppliers;
    if (!s) return;
    const p = SUP_PERIODS[s.period], c = s.current, v = s.previous;
    const tanks = (n) => `${count.format(n)} tank${n === 1 ? '' : 's'}`;
    // A supplier that was deleted (or not loaded yet) falls back to all suppliers.
    const pick = $('sup-supplier');
    const ids = s.suppliers.map((x) => x.id).join(',');
    if (pick.dataset.ids !== ids) {
      pick.dataset.ids = ids;
      clear(pick, h('option', { value: '' }, 'All suppliers'), s.suppliers.map((x) => h('option', { value: x.id }, x.name)));
    }
    pick.value = s.supplierId;
    const one = s.suppliers.find((x) => x.id === s.supplierId);
    $('sup-sub').textContent = `${one ? one.name : 'All suppliers'}: refill trips, tanks and money ${p.now}`;
    $('sup-billed').textContent = UI.pesoRound.format(c.billed);
    delta($('sup-billed-delta'), c.billed, v.billed, p.compare, true);
    $('sup-paid').textContent = UI.pesoRound.format(c.paid);
    delta($('sup-paid-delta'), c.paid, v.paid, p.compare, true);
    $('sup-how-title').textContent = `How it was paid, ${p.now}`;
    clear($('sup-how'), [['Cash', c.paidCash, 'sup-cash'], ['Check', c.paidCheck, 'sup-check'], ['Online', c.paidOnline || 0, 'sup-online'], ['Owner’s personal money', c.paidOwner, 'sup-owner'],
      ['Staff’s own money', c.paidStaff, 'sup-staff'], ['Pay later (still unpaid of this period’s bills)', c.unpaid, 'sup-unpaid']]
      .map(([label, amount, metric]) => h('div', { class: 'sup-how-item' }, h('span', { class: 'muted' }, label),
        Explain.mark(h('strong', { class: 'num', 'data-explain': metric }, peso.format(amount))))));
    $('sup-trips').textContent = count.format(c.trips);
    delta($('sup-trips-delta'), c.trips, v.trips, p.compare);
    $('sup-refilled').textContent = count.format(c.refilled);
    delta($('sup-refilled-delta'), c.refilled, v.refilled, p.compare);
    $('sup-refilled-note').textContent = [c.sent ? `${tanks(c.sent)} sent` : '', c.rejected ? `${tanks(c.rejected)} back unfilled` : ''].filter(Boolean).join(' · ');
    $('sup-bought').textContent = count.format(c.bought);
    delta($('sup-bought-delta'), c.bought, v.bought, p.compare);

    const heads = ['Trips', 'Back with load', 'New tanks', 'Cost', 'Cash', 'Check', 'Online', 'Owner’s money', 'Staff money', 'Pay later'];
    const cells = (r) => [count.format(r.trips), count.format(r.refilled), count.format(r.bought), peso.format(r.billed),
      peso.format(r.paidCash), peso.format(r.paidCheck), peso.format(r.paidOnline || 0), peso.format(r.paidOwner), peso.format(r.paidStaff), peso.format(r.unpaid)]
      .map((t) => h('td', { class: 'right num' }, t));
    const table = (first, rows, label) => h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, first), heads.map((t) => h('th', { class: 'right' }, t)))),
      h('tbody', {}, rows.map((r) => h('tr', {}, h('td', {}, label(r)), cells(r)))));

    $('sup-by-title').textContent = `By supplier, ${p.now}`;
    $('sup-by-title').hidden = $('sup-by-supplier').hidden = !!one;
    clear($('sup-by-supplier'), s.bySupplier.length ? table('Supplier', s.bySupplier, (r) => r.name)
      : h('div', { class: 'empty' }, `Nothing with suppliers ${p.now}.`));
    const active = s.series.filter((r) => r.trips || r.sent || r.refilled || r.rejected || r.bought || r.billed || r.paid || r.unpaid).reverse();
    $('sup-history-title').textContent = { day: 'Last 30 days', month: 'Last 12 months', quarter: 'Last 8 quarters' }[s.period];
    clear($('sup-history'), active.length ? table(p.head, active, (r) => bucketLabel(r.key, s.period, s.period !== 'day'))
      : h('div', { class: 'empty' }, 'Nothing with suppliers in this time.'));
  }

  $('trend-toggle').addEventListener('click', () => {
    state.table = !state.table;
    $('trend-toggle').setAttribute('aria-pressed', String(state.table));
    $('trend-toggle').textContent = state.table ? 'Chart' : 'Table';
    renderTrend();
  });

  function renderTrend() {
    const s = state.revenue;
    if (!s) return;
    $('trend-chart').hidden = state.table;
    $('trend-table').hidden = !state.table;
    if (state.table) {
      const rows = [...s.series].reverse();
      clear($('trend-table'), h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, { day: 'Day', month: 'Month', quarter: 'Quarter' }[s.period]), h('th', { class: 'right' }, 'Receipts'), h('th', { class: 'right' }, 'Cylinders'), h('th', { class: 'right' }, 'Revenue'), h('th', { class: 'right' }, 'Cash in'), h('th', { class: 'right' }, 'GCash in'))),
        h('tbody', {}, rows.map((r) => h('tr', {}, h('td', {}, bucketLabel(r.key, s.period, true)), h('td', { class: 'right num' }, count.format(r.count)), h('td', { class: 'right num' }, count.format(r.qty)), h('td', { class: 'right num' }, peso.format(r.revenue)),
          h('td', { class: 'right num' }, peso.format(r.cash)), h('td', { class: 'right num' }, peso.format(r.gcash)))))));
    } else {
      columnChart($('trend-chart'), s.series, s.period);
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

  // ------------------------------------------------------------------ tabs and clickable figures

  // Customers, Suppliers, Money, or Store & staff; the choice is remembered on this computer.
  let money = null;
  function showTab(tab) {
    if (!['customers', 'suppliers', 'money', 'store'].includes(tab)) tab = 'customers';
    for (const b of document.querySelectorAll('[data-tab]')) {
      b.setAttribute('aria-selected', String(b.dataset.tab === tab));
      b.tabIndex = b.dataset.tab === tab ? 0 : -1;
    }
    for (const p of document.querySelectorAll('[data-panel]')) {
      p.hidden = p.dataset.panel !== tab;
      p.setAttribute('aria-labelledby', 'tab-button-' + p.dataset.panel);
      p.tabIndex = 0;
    }
    try { localStorage.setItem('lpg-admin-tab', tab); } catch (_) { /* storage blocked */ }
    if (tab === 'customers') renderTrend(); // the chart needs a visible width
    // Money (cash on hand, expenses) is read-only here; expenses are entered in the POS dashboard.
    if (tab === 'money') {
      if (!money) money = Reports.mountMoney($('money-report'), (q) => request('GET', '/api/reports/money?' + new URLSearchParams(q)));
      else money.refresh();
    }
  }
  for (const b of document.querySelectorAll('[data-tab]')) b.addEventListener('click', () => showTab(b.dataset.tab));
  let savedTab = 'customers';
  try { savedTab = localStorage.getItem('lpg-admin-tab') || 'customers'; } catch (_) { /* storage blocked */ }
  showTab(savedTab);

  // Supplier figures follow the supplier card's period and picker, revenue-card figures its switch, the rest the range chips.
  Explain.bind(document.querySelector('main'), (url) => request('GET', url), (el) => {
    const m = el.dataset.explain;
    if (el.dataset.date) return { date: el.dataset.date }; // a daily-summary figure
    if (m.startsWith('sup-')) return { period: state.supPeriod, supplier: state.supplier };
    if (m.startsWith('period-')) return { period: state.period };
    return { range: statsRange() };
  });

  markRange();
  UI.watchVersion();
  const daily = Daily.mount($('daily'), (date) => request('GET', '/api/daily?date=' + encodeURIComponent(date)));
  setInterval(() => { if (document.visibilityState === 'visible' && !document.querySelector('dialog[open]')) daily.refresh(); }, 30000);
  loadOverview();
  loadStats();
  setInterval(() => { if (document.visibilityState === 'visible') loadOverview(); }, 4000);
  setInterval(() => { if (document.visibilityState === 'visible' && !document.querySelector('dialog[open]')) loadStats(); }, 10000);
  setInterval(tickUpdated, 5000);
})();
