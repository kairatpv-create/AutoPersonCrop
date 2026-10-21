'use strict';
// Run the existing Express routes with a deliberately small, in-memory PostgreSQL adapter.
// This tests HTTP/auth/validation behavior, not real PostgreSQL isolation or Render.
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const sourcePath = path.resolve(process.argv[2]);
const outputPath = path.resolve(process.argv[3]);
const load = createRequire(path.join(path.resolve(process.env.ORGANIZER_AUDIT_NODE_MODULES), '..', 'package.json'));
const results = [];
const users = new Map(), documents = new Map();
let server, ready;
const listening = new Promise(resolve => { ready = resolve; });
class Pool {
  async query(sql, values = []) {
    if (/CREATE TABLE/.test(sql) || /^(BEGIN|ROLLBACK|COMMIT)$/.test(sql)) return { rows: [] };
    if (sql === 'SELECT 1') return { rows: [{ '?column?': 1 }] };
    if (sql.startsWith('INSERT INTO organizer_users')) {
      if ([...users.values()].some(u => u.email === values[0])) throw Object.assign(new Error('duplicate'), { code: '23505' });
      const u = { id: users.size + 1, email: values[0], password_hash: values[1] }; users.set(u.id, u); return { rows: [u] };
    }
    if (sql.startsWith('SELECT id,email,password_hash')) return { rows: [...users.values()].filter(u => u.email === values[0]) };
    if (sql.startsWith('SELECT payload,rev,updated_at')) return { rows: documents.has(values[0]) ? [documents.get(values[0])] : [] };
    if (/INSERT INTO organizer_sync/.test(sql)) {
      documents.set(values[0], { payload: values[1], rev: values[2], updated_at: values[3] }); return { rows: [] };
    }
    throw new Error('Unhandled host adapter SQL: ' + sql);
  }
  async connect() { return { query: this.query.bind(this), release() {} }; }
}
const realExpress = load('express');
function express() {
  const app = realExpress();
  const listen = app.listen;
  app.listen = (_port, _host, cb) => {
    server = listen.call(app, 0, '127.0.0.1', () => { cb(); ready(); }); return server;
  };
  return app;
}
Object.assign(express, realExpress);
const sandbox = {
  require(name) { return name === 'pg' ? { Pool } : name === 'express' ? express : load(name); },
  __dirname: path.dirname(sourcePath),
  process: { env: { DATABASE_URL: 'synthetic-adapter-no-connection', JWT_SECRET: 'synthetic-audit-key-never-used-in-production', TOKEN_DAYS: '30' }, exit(code) { throw new Error('server exit ' + code); } },
  console: { log() {}, error() {} }, Buffer, setTimeout, clearTimeout,
};
vm.runInNewContext(fs.readFileSync(sourcePath, 'utf8'), sandbox, { filename: sourcePath });
async function test(name, risk, fn) {
  try { await fn(); results.push({ name, result: risk ? 'RISK_REPRODUCED' : 'PASS' }); }
  catch (e) { results.push({ name, result: 'HARNESS_FAILURE', error: String(e) }); }
}
(async () => {
  await listening;
  const base = 'http://127.0.0.1:' + server.address().port;
  let token;
  async function req(route, method = 'GET', body, auth = token, headers = {}) {
    const r = await fetch(base + route, { method, headers: { 'Content-Type': 'application/json', ...(auth ? { Authorization: 'Bearer ' + auth } : {}), ...headers }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: r.status, body: await r.json() };
  }
  await test('Health checks the supplied database adapter', false, async () => { const r = await req('/health'); assert.equal(r.status, 200); assert.equal(r.body.ok, true); });
  await test('Unauthenticated sync is rejected', false, async () => { assert.equal((await req('/api/sync')).status, 401); });
  await test('Invalid email registration is rejected', false, async () => { assert.equal((await req('/api/auth/register', 'POST', { email: 'bad', password: 'abcdef' })).status, 400); });
  await test('Short password is rejected', false, async () => { assert.equal((await req('/api/auth/register', 'POST', { email: 'audit@example.invalid', password: 'abc' })).status, 400); });
  await test('Registration uses bcrypt and returns a JWT', false, async () => {
    const r = await req('/api/auth/register', 'POST', { email: 'Audit@Example.Invalid', password: 'synthetic-password' });
    assert.equal(r.status, 201); token = r.body.token; assert(token); assert.equal(r.body.email, 'audit@example.invalid'); assert.match(users.get(1).password_hash, /^\$2/);
  });
  await test('Duplicate account is rejected', false, async () => { assert.equal((await req('/api/auth/register', 'POST', { email: 'audit@example.invalid', password: 'abcdef' })).status, 409); });
  await test('Wrong password is rejected', false, async () => { assert.equal((await req('/api/auth/login', 'POST', { email: 'audit@example.invalid', password: 'wrong-password' })).status, 401); });
  await test('Existing-account password login succeeds', false, async () => { const r = await req('/api/auth/login', 'POST', { email: 'audit@example.invalid', password: 'synthetic-password' }); assert.equal(r.status, 200); token = r.body.token; });
  await test('JWT expiration is 30 days', false, async () => { const c = load('jsonwebtoken').decode(token); assert.equal(c.exp - c.iat, 30 * 86400); });
  await test('New account has no sync document', false, async () => { const r = await req('/api/sync'); assert.equal(r.body.exists, false); assert.equal(r.body.rev, 0); });
  await test('Snapshot PUT/GET and stale revision rejection', false, async () => {
    const payload = JSON.stringify({ format: 'my-organizer-backup', version: 7, notes: [] });
    assert.equal((await req('/api/sync', 'PUT', { payload, baseRev: 0 })).body.rev, 1);
    assert.equal((await req('/api/sync')).body.payload, payload);
    const stale = await req('/api/sync', 'PUT', { payload: 'other', baseRev: 0 }); assert.equal(stale.status, 409); assert.equal(stale.body.payload, payload);
  });
  await test('A forbidden browser Origin is rejected; native Origin omission works', false, async () => { assert.equal((await req('/api/account', 'GET', undefined, token, { Origin: 'https://example.invalid' })).status, 400); assert.equal((await req('/api/account')).status, 200); });
  await test('Missing deletion endpoint returns 404', true, async () => { assert.equal((await req('/api/account', 'DELETE')).status, 404); assert.equal((await req('/api/account/delete', 'POST', {})).status, 404); });
  await test('Server accepts malformed snapshot JSON', true, async () => { assert.equal((await req('/api/sync', 'PUT', { payload: '{not-json', baseRev: 1 })).status, 200); assert.equal((await req('/api/sync')).body.payload, '{not-json'); });
  await test('Unknown API endpoints return JSON 404', false, async () => { assert.equal((await req('/api/unknown')).status, 404); });
  fs.writeFileSync(outputPath, JSON.stringify({ adapter: 'in-memory PostgreSQL API test double; real Express, bcrypt and JWT', results }, null, 2));
  console.log(JSON.stringify(results, null, 2));
  server.close(); if (results.some(x => x.result === 'HARNESS_FAILURE')) process.exitCode = 1;
})().catch(e => { console.error(e); server?.close(); process.exitCode = 1; });
