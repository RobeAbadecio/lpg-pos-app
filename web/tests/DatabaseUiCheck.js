// Exercises the admin "Edit records" section with a tiny DOM stub: loading, readable names, search, sorting,
// typed fields, change summary, required reason, revision payload, delete, add defaults and undo.
// It does not check visual layout; that was checked in a browser against fake data.
const fs = require('fs'), assert = require('assert');
const source = fs.readFileSync(require('path').join(__dirname, '../public/admin/admin.js'), 'utf8');
const start = source.indexOf('  // Business records are loaded on demand');
const end = source.indexOf('  // ------------------------------------------------------------------ stats', start);
const nodes = {};
const make = (tag, attrs = {}, ...children) => ({ tag, ...attrs, children: children.flat(Infinity), value: attrs.value || '', textContent: '',
  listeners: {}, addEventListener(name, f) { this.listeners[name] = f; }, focus() {} });
const $ = (id) => nodes[id] || (nodes[id] = make('div'));
const text = (n) => n == null || n === false ? '' : typeof n !== 'object' ? String(n) : (n.textContent || '') + (n.children || []).map(text).join('');
const h = make, clear = (el, ...children) => { el.children = children.flat(Infinity); el.textContent = ''; };
const find = (n, f) => { if (!n || typeof n !== 'object') return null; if (f(n)) return n; for (const c of n.children || []) { const r = find(c, f); if (r) return r; } return null; };
const all = (n, f, out = []) => { if (n && typeof n === 'object') { if (f(n)) out.push(n); for (const c of n.children || []) all(c, f, out); } return out; };
const tables = {
  TankReturns: { table: 'TankReturns', fields: ['id', 'dateTime', 'customerId', 'lpgId', 'qty', 'staff', 'remark'],
    rows: [['1', '2026-10-01 10:00:00', '1', '1', '2', 'ana', 'old'], ['2', '2026-10-02 09:00:00', '2', '1', '1', 'ben', '']], revision: 'abc' },
  Customers: { table: 'Customers', fields: ['id', 'firstName', 'lastName', 'contactNo', 'address'], rows: [['1', 'Juan', 'Cruz', '', ''], ['2', 'Ana', 'Reyes', '', '']], revision: 'abc' },
  LPGs: { table: 'LPGs', fields: ['id', 'brand', 'price', 'weight'], rows: [['1', 'Solane', '1000', '11.0']], revision: 'abc' },
  Suppliers: { table: 'Suppliers', fields: ['id', 'name'], rows: [], revision: 'abc' },
};
let dialog, confirmed = true, calls = [];
const request = async (method, url, payload) => {
  calls.push({ method, url, payload });
  return method === 'GET' ? tables[decodeURIComponent(url.split('/').pop())] : { ok: true, backup: '/fake/backup' };
};
const UI = { openDialog: async (config) => { dialog = config; return null; } };
$('database-table').value = 'TankReturns';
const funcs = new Function('$', 'h', 'clear', 'request', 'UI', 'loadOverview', 'loadStats', 'daily', 'toast', 'count', 'state', 'confirmDialog',
  source.slice(start, end) + '\nreturn {loadDatabase,renderDatabase,editDatabase,undoLast,database};')(
  $, h, clear, request, UI, async () => {}, async () => {}, { refresh() {} }, () => {}, new Intl.NumberFormat('en-PH'),
  { overview: { users: [{ username: 'ana' }] } }, async () => confirmed);
const field = (label) => find(dialog.body, (n) => n.tag === 'label' && text(n.children[0]) === label).children[1];
const reason = () => all(dialog.body, (n) => n.tag === 'label').at(-1).children[1];
(async () => {
  assert.equal($('database-table').children.length, 11);
  await new Promise((r) => setTimeout(r, 0)); // the section loads the chosen table on its own
  assert.equal($('database-add').disabled, false);
  assert.match(text($('database-status')), /Showing 1–2 of 2/);
  const rows = () => find($('database-records'), (n) => n.tag === 'tbody').children;
  assert.equal(text(rows()[0].children[0]), '2', 'newest first');
  assert.match(text(rows()[0].children[2]), /Ana Reyes \(#2\)/, 'customer ID shows the name');
  assert.match(text(rows()[0].children[3]), /Solane 11 kg \(#1\)/, 'product ID shows the name');
  $('database-search').value = 'juan'; funcs.renderDatabase();
  assert.equal(rows().length, 1, 'search matches readable names');
  $('database-search').value = ''; funcs.renderDatabase();

  await funcs.editDatabase('edit', tables.TankReturns.rows[0]);
  assert.equal(field('ID').readonly, true);
  assert.equal(field('Customer').tag, 'select');
  assert.equal(field('Date & time').type, 'datetime-local');
  assert.equal(field('Quantity').type, 'number');
  await assert.rejects(dialog.onSubmit(), /Nothing has changed/);
  field('Quantity').value = '1';
  dialog.body.listeners.input();
  assert.match(text(find(dialog.body, (n) => n.class === 'db-changes')), /Quantity: 2 → 1/);
  await assert.rejects(dialog.onSubmit(), /Say why/);
  reason().value = 'Undo half test';
  await dialog.onSubmit();
  const put = calls.at(-1).payload;
  assert.equal(put.field4, '1');
  assert.equal(put.field1, '2026-10-01 10:00:00', 'date/time keeps the stored format');
  assert.equal(put.revision, 'abc');

  await funcs.editDatabase('delete', tables.TankReturns.rows[0]);
  assert.equal(dialog.danger, true);
  assert.match(text(dialog.body), /Juan Cruz/);
  reason().value = 'Undo test';
  await dialog.onSubmit();
  assert.equal(calls.at(-1).payload.mode, 'delete');

  await funcs.editDatabase('add');
  assert.equal(field('ID').value, '3', 'next free ID');
  assert.match(field('Date & time').value, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d$/, 'date defaults to now');

  // Undo reverses the last saved change with a fresh revision.
  let close;
  UI.openDialog = (c) => new Promise((resolve) => { dialog = c; close = resolve; });
  const editing = funcs.editDatabase('edit', tables.TankReturns.rows[1]);
  await new Promise((r) => setTimeout(r, 0));
  field('Quantity').value = '5'; reason().value = 'typo';
  close(await dialog.onSubmit());
  await editing;
  assert.ok(funcs.database.last, 'last change kept for undo');
  tables.TankReturns.revision = 'def';
  await funcs.undoLast();
  const undo = calls.filter((c) => c.method === 'PUT').at(-1).payload;
  assert.equal(undo.mode, 'edit');
  assert.equal(undo.field4, '1', 'undo puts the old value back');
  assert.equal(undo.revision, 'def', 'undo uses the current revision');
  assert.match(undo.reason, /^Undo: /);
  console.log('PASS: auto-load, names for IDs, newest first, search by name, typed fields, fixed IDs, change summary, required reason, revision payload, delete, add defaults, undo');
})().catch((e) => { console.error(e); process.exitCode = 1; });
