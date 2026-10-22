'use strict';
// Real Express/bcrypt/JWT. Optional real PostgreSQL is confined to a named local CI database.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const source = path.resolve(process.argv[2]), output = path.resolve(process.argv[3]);
const load = createRequire(path.join(path.resolve(process.env.ORGANIZER_AUDIT_NODE_MODULES), '..', 'package.json'));
const database = process.env.ORGANIZER_SYNTHETIC_PG_URL;
if (database) {
  const u = new URL(database);
  assert(['localhost', '127.0.0.1'].includes(u.hostname) && u.pathname === '/organizer_synthetic_safety');
  assert.equal(process.env.ORGANIZER_SYNTHETIC_ONLY, '1');
}
const users = new Map(), documents = new Map();let nextId = 1, server, pool, ready;
const listening = new Promise(resolve => { ready = resolve; });
// A serial transaction test double for host route tests. CI also runs against real PostgreSQL.
let lock = Promise.resolve();
class FakePool {
  constructor() { pool = this; }
  async query(sql, v = []) {
    if (/CREATE TABLE/.test(sql) || /^(BEGIN|ROLLBACK|COMMIT)$/.test(sql)) return { rows: [] };
    if (sql === 'SELECT 1') return { rows: [{ value: 1 }] };
    if (sql.startsWith('INSERT INTO organizer_users')) {
      if ([...users.values()].some(u => u.email === v[0])) throw Object.assign(new Error('duplicate'), { code: '23505' });
      const u = { id: nextId++, email: v[0], password_hash: v[1] }; users.set(u.id, u); return { rows: [u] };
    }
    if (sql.startsWith('SELECT id,email,password_hash FROM organizer_users WHERE email=')) return { rows: [...users.values()].filter(u => u.email === v[0]) };
    if (/^SELECT (id|id,email|id,email,password_hash) FROM organizer_users WHERE id=/.test(sql)) return { rows: users.has(Number(v[0])) ? [users.get(Number(v[0]))] : [] };
    if (sql.startsWith('SELECT payload,rev,updated_at')) return { rows: documents.has(v[0]) ? [documents.get(v[0])] : [] };
    if (/INSERT INTO organizer_sync/.test(sql)) { assert(users.has(v[0])); documents.set(v[0], { payload: v[1], rev: v[2], updated_at: v[3] }); return { rows: [] }; }
    if (sql.startsWith('DELETE FROM organizer_users')) { users.delete(v[0]); documents.delete(v[0]); return { rows: [] }; }
    throw new Error('Unhandled synthetic SQL: ' + sql);
  }
  async connect() {
    let release; const before = lock; lock = new Promise(resolve => { release = resolve; }); await before;
    return { query: this.query.bind(this), release };
  }
  async end() {}
}
const RealPool = database ? load('pg').Pool : undefined;
class RecordedPool extends (RealPool || FakePool) { constructor(options) { super(options); pool = this; } }
const realExpress = load('express');
function express() { const app = realExpress(); const listen = app.listen; app.listen = (_p, _h, cb) => { server = listen.call(app, 0, '127.0.0.1', () => { cb(); ready(); }); return server; }; return app; }
Object.assign(express, realExpress);
const sandbox = {
  require(name) { return name === 'pg' ? { Pool: RecordedPool } : name === 'express' ? express : name.startsWith('.') ? require(path.resolve(path.dirname(source), name)) : load(name); },
  __dirname: path.dirname(source), process: { env: { DATABASE_URL: database || 'synthetic-only-adapter', JWT_SECRET: 'synthetic-isolated-safety-key-never-production', TOKEN_DAYS: '30' }, exit(c) { throw Error('server exit ' + c); } },
  console: { log() {}, error() {} }, Buffer, setTimeout, clearTimeout,
};
vm.runInNewContext(fs.readFileSync(source, 'utf8'), sandbox, { filename: source });
const results = [];
async function test(name, fn) { try { await fn(); results.push({ name, result: 'PASS' }); } catch (e) { results.push({ name, result: 'FAIL', error: String(e) }); console.error(name, e); } }
function snapshot(title = 'synthetic') { const r = { format: 'my-organizer-backup', version: 7, createdAt: 0, cloudSchema: 'organizer-sync-v3' }; for (const k of ['notes', 'tasks', 'projects', 'projectStages', 'projectJournal', 'projectNoteLinks', 'money', 'reminders']) r[k] = []; r.notes.push({ id: 1, title, body: 'body', createdAt: 1, updatedAt: 2 }); return JSON.stringify(r); }
(async () => {
  await listening; const base = 'http://127.0.0.1:' + server.address().port;
  async function req(route, method = 'GET', body, token) { const r = await fetch(base + route, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }); const text = await r.text(); return { status: r.status, body: r.headers.get('content-type')?.includes('application/json') ? JSON.parse(text) : text }; }
  let a, b;
  await test('Synthetic accounts register with bcrypt and distinct stable JWT subjects', async () => {
    const ra = await req('/api/auth/register', 'POST', { email: 'a@example.invalid', password: 'synthetic-password' });
    const rb = await req('/api/auth/register', 'POST', { email: 'b@example.invalid', password: 'synthetic-password' });
    assert.equal(ra.status, 201); assert.equal(rb.status, 201); a = ra.body.token; b = rb.body.token; assert.notEqual(load('jsonwebtoken').decode(a).sub, load('jsonwebtoken').decode(b).sub);
  });
  await test('The authenticated account advertises deletion support', async () => { const r = await req('/api/account', 'GET', undefined, a); assert.equal(r.body.email, 'a@example.invalid'); assert.equal(r.body.accountDeletion, true); });
  await test('Unauthenticated sync and deletion are refused', async () => { assert.equal((await req('/api/sync')).status, 401); assert.equal((await req('/api/account/delete', 'POST', {})).status, 401); });
  for (const [name, payload] of [['invalid JSON', '{invalid'], ['incomplete notes-only JSON', '{"notes":[]}'], ['array root', '[]'], ['missing collections', JSON.stringify({ format: 'my-organizer-backup', version: 7, createdAt: 0 })]]) await test('Reject ' + name + ' before database changes', async () => { assert.equal((await req('/api/sync', 'PUT', { payload, baseRev: 0 }, a)).status, 400); assert.equal((await req('/api/sync', 'GET', undefined, a)).body.exists, false); });
  for (const section of ['notes', 'tasks', 'projects', 'projectStages', 'projectJournal', 'projectNoteLinks', 'money', 'reminders']) await test('Reject wrong collection type: ' + section, async () => { const root = JSON.parse(snapshot()); root[section] = {}; assert.equal((await req('/api/sync', 'PUT', { payload: JSON.stringify(root), baseRev: 0 }, a)).status, 400); });
  for (const rev of [-1, 0.5, '0', null, Number.MAX_SAFE_INTEGER + 1]) await test('Reject invalid baseRev: ' + JSON.stringify(rev), async () => { assert.equal((await req('/api/sync', 'PUT', { payload: snapshot(), baseRev: rev }, a)).status, 400); });
  await test('Two simultaneous first uploads preserve one winner and return the other as 409', async () => {
    const writes = await Promise.all([req('/api/sync', 'PUT', { payload: snapshot('first'), baseRev: 0 }, a), req('/api/sync', 'PUT', { payload: snapshot('second'), baseRev: 0 }, a)]);
    assert.deepEqual(writes.map(x => x.status).sort(), [200, 409]); const saved = (await req('/api/sync', 'GET', undefined, a)).body; assert.equal(saved.rev, 1); assert.equal(writes.find(x => x.status === 409).body.payload, saved.payload);
  });
  await test('Wrong field type and duplicate record IDs cannot replace an existing snapshot', async () => {
    const old = (await req('/api/sync', 'GET', undefined, a)).body;
    for (const kind of ['type', 'duplicate', 'settings', 'attachments']) { const r = JSON.parse(snapshot()); if (kind === 'type') r.notes[0].updatedAt = 'wrong'; if (kind === 'duplicate') r.notes.push(r.notes[0]); if (kind === 'settings') r.settings = {}; if (kind === 'attachments') r.attachments = []; assert.equal((await req('/api/sync', 'PUT', { payload: JSON.stringify(r), baseRev: 1 }, a)).status, 400); }
    assert.deepEqual((await req('/api/sync', 'GET', undefined, a)).body, old);
  });
  await test('A complete empty v7 snapshot remains valid', async () => { const r = JSON.parse(snapshot()); r.notes = []; assert.equal((await req('/api/sync', 'PUT', { payload: JSON.stringify(r), baseRev: 0 }, b)).status, 200); });
  await test('The standalone external deletion page is served without the deferred web app', async () => { const r = await req('/account-deletion'); assert.equal(r.status, 200); assert.match(r.body, /Account deletion/); assert.match(r.body, /\/api\/account\/delete/); assert.doesNotMatch(r.body, /localStorage|sessionStorage/); });
  await test('Missing confirmation and another account email do not delete the user', async () => { for (const body of [{}, { password: 'synthetic-password', confirmation: 'b@example.invalid' }]) assert.equal((await req('/api/account/delete', 'POST', body, a)).status, 400); assert.equal((await req('/api/account', 'GET', undefined, a)).status, 200); });
  await test('Wrong password cannot delete the user or cloud snapshot', async () => { const before = (await req('/api/sync', 'GET', undefined, a)).body; assert.equal((await req('/api/account/delete', 'POST', { password: 'wrong-password', confirmation: 'a@example.invalid' }, a)).status, 401); assert.deepEqual((await req('/api/sync', 'GET', undefined, a)).body, before); });
  await test('Correct reauthentication deletes only the selected account and cascades its snapshot', async () => { const other = (await req('/api/sync', 'GET', undefined, b)).body; assert.equal((await req('/api/account/delete', 'POST', { password: 'synthetic-password', confirmation: 'a@example.invalid' }, a)).body.deleted, true); assert.deepEqual((await req('/api/sync', 'GET', undefined, b)).body, other); });
  await test('Old JWTs cannot read, upload, or delete after account deletion', async () => { assert.equal((await req('/api/account', 'GET', undefined, a)).status, 401); assert.equal((await req('/api/sync', 'GET', undefined, a)).status, 401); assert.equal((await req('/api/sync', 'PUT', { payload: snapshot(), baseRev: 0 }, a)).status, 401); assert.equal((await req('/api/account/delete', 'POST', { password: 'synthetic-password', confirmation: 'a@example.invalid' }, a)).status, 401); });
  await test('The same email can register again with a distinct account identity and no old snapshot', async () => { const oldSub = load('jsonwebtoken').decode(a).sub; const r = await req('/api/auth/register', 'POST', { email: 'a@example.invalid', password: 'synthetic-password' }); assert.equal(r.status, 201); assert.notEqual(load('jsonwebtoken').decode(r.body.token).sub, oldSub); assert.equal((await req('/api/sync', 'GET', undefined, r.body.token)).body.exists, false); });
  await test('Concurrent deletion and upload never resurrect the deleted user or its sync row', async () => {
    const writes = await Promise.all([req('/api/account/delete', 'POST', { password: 'synthetic-password', confirmation: 'b@example.invalid' }, b), req('/api/sync', 'PUT', { payload: snapshot('during deletion'), baseRev: 1 }, b)]);
    assert.equal(writes[0].body.deleted, true); assert([200, 401].includes(writes[1].status)); assert.equal((await req('/api/sync', 'GET', undefined, b)).status, 401);
    if (database) { const id = Number(load('jsonwebtoken').decode(b).sub); assert.equal((await pool.query('SELECT 1 FROM organizer_sync WHERE user_id=$1', [id])).rows.length, 0); }
  });
  await test('Unknown API routes return JSON 404', async () => { assert.equal((await req('/api/unknown')).status, 404); });
  const result = { suite: 'sync-server-safety', database: database ? 'real disposable PostgreSQL' : 'serial in-memory pg test double', passed: results.filter(x => x.result === 'PASS').length, total: results.length, results };
  fs.writeFileSync(output, JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
  server.close(); await pool.end(); if (result.passed !== result.total) process.exitCode = 1;
})().catch(async e => { console.error(e); server?.close(); await pool?.end(); process.exitCode = 1; });
