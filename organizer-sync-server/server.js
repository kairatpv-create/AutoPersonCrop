'use strict';

const path = require('path');
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { Pool } = require('pg');

const PORT = Number(process.env.PORT || 10000);
const DATABASE_URL = process.env.DATABASE_URL || '';
const JWT_SECRET = process.env.JWT_SECRET || '';
const TOKEN_DAYS = Math.max(1, Number(process.env.TOKEN_DAYS || 30));
const ALLOWED_ORIGINS = (process.env.ALLOWED_ORIGINS || 'https://organizer-pro.onrender.com,https://organizer-pro.app,https://www.organizer-pro.app,https://kairatpv-create.github.io').split(',').map(s => s.trim()).filter(Boolean);
const WEB_DIR = path.resolve(__dirname, '..', 'organizer-web', 'static');

if (!DATABASE_URL) throw new Error('DATABASE_URL is required');
if (!JWT_SECRET || JWT_SECRET.length < 32) throw new Error('JWT_SECRET must be at least 32 characters');

const pool = new Pool({
  connectionString: DATABASE_URL,
  ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: false } : undefined,
  max: 10,
});

async function initDb() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS organizer_users (
      id BIGSERIAL PRIMARY KEY,
      email TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      created_at BIGINT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS organizer_sync (
      user_id BIGINT PRIMARY KEY REFERENCES organizer_users(id) ON DELETE CASCADE,
      payload TEXT NOT NULL,
      rev BIGINT NOT NULL DEFAULT 1,
      updated_at BIGINT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS organizer_users_email_idx ON organizer_users(email);
  `);
}

function cleanEmail(v) {
  return String(v || '').trim().toLowerCase();
}
function validEmail(v) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);
}
function publicError(res, status, code, message) {
  res.status(status).json({ error: code, message });
}
function makeToken(user) {
  return jwt.sign({ sub: String(user.id), email: user.email }, JWT_SECRET, {
    expiresIn: `${TOKEN_DAYS}d`, issuer: 'organizer-pro-sync', audience: 'organizer-pro'
  });
}
function auth(req, res, next) {
  const h = String(req.headers.authorization || '');
  const token = h.startsWith('Bearer ') ? h.slice(7) : '';
  if (!token) return publicError(res, 401, 'AUTH_REQUIRED', 'Требуется вход в аккаунт');
  try {
    const data = jwt.verify(token, JWT_SECRET, { issuer: 'organizer-pro-sync', audience: 'organizer-pro' });
    req.userId = Number(data.sub);
    req.userEmail = data.email;
    next();
  } catch (_) {
    return publicError(res, 401, 'SESSION_EXPIRED', 'Сессия завершена. Войдите снова.');
  }
}

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1);
app.use(helmet({
  crossOriginResourcePolicy: false,
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'self'"],
      scriptSrc: ["'self'", "'unsafe-inline'"],
      styleSrc: ["'self'", "'unsafe-inline'"],
      imgSrc: ["'self'", 'data:'],
      connectSrc: ["'self'"],
      objectSrc: ["'none'"],
      baseUri: ["'self'"],
      frameAncestors: ["'none'"]
    }
  }
}));
app.use(cors({
  origin(origin, cb) {
    if (!origin) return cb(null, true); // Android/native clients have no browser Origin.
    if (ALLOWED_ORIGINS.includes(origin)) return cb(null, true);
    return cb(new Error('Origin not allowed'));
  },
  methods: ['GET', 'POST', 'PUT', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization'],
  maxAge: 86400,
}));
app.use(express.json({ limit: '1mb' }));

const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 30, standardHeaders: 'draft-7', legacyHeaders: false });
const syncLimiter = rateLimit({ windowMs: 60 * 1000, limit: 120, standardHeaders: 'draft-7', legacyHeaders: false });

app.get('/health', async (_req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ ok: true, service: 'organizer-pro-sync', version: 2 });
  } catch (_) {
    res.status(503).json({ ok: false });
  }
});

app.get('/api/version', (_req, res) => {
  res.json({ service: 'organizer-pro-sync', version: 2, web: '0.5.1', android: '0.9.30' });
});

app.post('/api/auth/register', authLimiter, async (req, res) => {
  const email = cleanEmail(req.body && req.body.email);
  const password = String((req.body && req.body.password) || '');
  if (!validEmail(email)) return publicError(res, 400, 'EMAIL_INVALID', 'Введите корректный email');
  if (password.length < 6) return publicError(res, 400, 'PASSWORD_WEAK', 'Пароль должен быть не короче 6 символов');
  if (password.length > 200) return publicError(res, 400, 'PASSWORD_INVALID', 'Пароль слишком длинный');
  try {
    const hash = await bcrypt.hash(password, 12);
    const now = Date.now();
    const q = await pool.query('INSERT INTO organizer_users(email,password_hash,created_at) VALUES($1,$2,$3) RETURNING id,email', [email, hash, now]);
    const user = q.rows[0];
    res.status(201).json({ token: makeToken(user), email: user.email });
  } catch (e) {
    if (String(e.code) === '23505') return publicError(res, 409, 'EMAIL_EXISTS', 'Аккаунт с таким email уже существует');
    console.error('register', e);
    publicError(res, 500, 'SERVER_ERROR', 'Ошибка сервера');
  }
});

app.post('/api/auth/login', authLimiter, async (req, res) => {
  const email = cleanEmail(req.body && req.body.email);
  const password = String((req.body && req.body.password) || '');
  try {
    const q = await pool.query('SELECT id,email,password_hash FROM organizer_users WHERE email=$1', [email]);
    const user = q.rows[0];
    if (!user || !(await bcrypt.compare(password, user.password_hash))) {
      return publicError(res, 401, 'INVALID_LOGIN', 'Неверный email или пароль');
    }
    res.json({ token: makeToken(user), email: user.email });
  } catch (e) {
    console.error('login', e);
    publicError(res, 500, 'SERVER_ERROR', 'Ошибка сервера');
  }
});

app.get('/api/account', auth, (req, res) => {
  res.json({ email: req.userEmail });
});

app.get('/api/sync', syncLimiter, auth, async (req, res) => {
  try {
    const q = await pool.query('SELECT payload,rev,updated_at FROM organizer_sync WHERE user_id=$1', [req.userId]);
    if (!q.rows[0]) return res.json({ exists: false, rev: 0, updatedAt: 0, payload: null });
    const row = q.rows[0];
    res.json({ exists: true, rev: Number(row.rev), updatedAt: Number(row.updated_at), payload: row.payload });
  } catch (e) {
    console.error('sync get', e);
    publicError(res, 500, 'SERVER_ERROR', 'Ошибка сервера');
  }
});

app.put('/api/sync', syncLimiter, auth, async (req, res) => {
  const payload = typeof req.body?.payload === 'string' ? req.body.payload : '';
  const baseRev = Number(req.body?.baseRev || 0);
  if (!payload) return publicError(res, 400, 'PAYLOAD_REQUIRED', 'Нет данных для синхронизации');
  if (Buffer.byteLength(payload, 'utf8') > 850000) return publicError(res, 413, 'PAYLOAD_TOO_LARGE', 'Слишком большой объём данных');

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const currentQ = await client.query('SELECT payload,rev,updated_at FROM organizer_sync WHERE user_id=$1 FOR UPDATE', [req.userId]);
    const current = currentQ.rows[0];
    const currentRev = current ? Number(current.rev) : 0;
    if (current && baseRev !== currentRev) {
      await client.query('ROLLBACK');
      return res.status(409).json({
        error: 'SYNC_CONFLICT', message: 'Данные изменились на другом устройстве',
        rev: currentRev, updatedAt: Number(current.updated_at), payload: current.payload
      });
    }
    const now = Date.now();
    const nextRev = currentRev + 1;
    await client.query(`
      INSERT INTO organizer_sync(user_id,payload,rev,updated_at) VALUES($1,$2,$3,$4)
      ON CONFLICT(user_id) DO UPDATE SET payload=EXCLUDED.payload, rev=EXCLUDED.rev, updated_at=EXCLUDED.updated_at
    `, [req.userId, payload, nextRev, now]);
    await client.query('COMMIT');
    res.json({ ok: true, rev: nextRev, updatedAt: now });
  } catch (e) {
    try { await client.query('ROLLBACK'); } catch (_) {}
    console.error('sync put', e);
    publicError(res, 500, 'SERVER_ERROR', 'Ошибка сервера');
  } finally {
    client.release();
  }
});

// Unknown API routes return JSON, never the web shell.
app.use('/api', (_req, res) => publicError(res, 404, 'NOT_FOUND', 'Метод не найден'));

// The same service hosts Organizer Pro Web. This keeps login/sync on one origin.
app.use(express.static(WEB_DIR, { index: false, maxAge: '1h' }));
app.get('*', (req, res, next) => {
  if (req.method !== 'GET') return next();
  res.sendFile(path.join(WEB_DIR, 'index.html'));
});

app.use((err, _req, res, _next) => {
  console.error('request', err && err.message ? err.message : err);
  publicError(res, 400, 'REQUEST_ERROR', 'Ошибка запроса');
});

initDb().then(() => {
  app.listen(PORT, '0.0.0.0', () => console.log(`Organizer Pro listening on ${PORT}`));
}).catch(err => {
  console.error(err);
  process.exit(1);
});
