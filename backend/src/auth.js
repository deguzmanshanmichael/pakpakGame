import { scrypt, randomBytes, createHash, timingSafeEqual } from 'node:crypto';
import { HttpError } from './http.js';

// scrypt parameters follow the OWASP Password Storage Cheat Sheet (N=2^15, r=8, p=3).
const SCRYPT = { N: 2 ** 15, r: 8, p: 3, keylen: 64, maxmem: 64 * 1024 * 1024 };

function scryptAsync(secret, salt, params) {
  return new Promise((resolve, reject) => {
    scrypt(secret, salt, params.keylen, { N: params.N, r: params.r, p: params.p, maxmem: params.maxmem }, (err, key) => (err ? reject(err) : resolve(key)));
  });
}

export async function hashSecret(secret) {
  const salt = randomBytes(16);
  const key = await scryptAsync(secret.normalize('NFKC'), salt, SCRYPT);
  return `scrypt$${SCRYPT.N}$${SCRYPT.r}$${SCRYPT.p}$${salt.toString('base64')}$${key.toString('base64')}`;
}

export async function verifySecret(secret, stored) {
  const parts = stored.split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
  const [, N, r, p, saltB64, keyB64] = parts;
  const expected = Buffer.from(keyB64, 'base64');
  const key = await scryptAsync(secret.normalize('NFKC'), Buffer.from(saltB64, 'base64'),
    { N: Number(N), r: Number(r), p: Number(p), keylen: expected.length, maxmem: SCRYPT.maxmem });
  return key.length === expected.length && timingSafeEqual(key, expected);
}

// A pre-computed hash so failed logins for unknown usernames take as long as real ones.
let dummyHash;
export async function burnTime(secret) {
  dummyHash ??= await hashSecret('dummy-password-for-timing');
  await verifySecret(secret, dummyHash);
}

export const sha256 = (s) => createHash('sha256').update(s).digest('hex');
export const newToken = () => randomBytes(32).toString('base64url');

// Recovery codes: 16 chars from an unambiguous alphabet, shown once as XXXX-XXXX-XXXX-XXXX.
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
export function newRecoveryCode() {
  const bytes = randomBytes(16);
  let s = '';
  for (let i = 0; i < 16; i++) s += ALPHABET[bytes[i] % ALPHABET.length];
  return s.match(/.{4}/g).join('-');
}
export const normalizeRecoveryCode = (c) => String(c ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');

export function newPlayerCode() {
  const bytes = randomBytes(6);
  let s = '';
  for (let i = 0; i < 6; i++) s += ALPHABET[bytes[i] % ALPHABET.length];
  return `PK-${s}`;
}

export function createSession(db, userId, days) {
  const token = newToken();
  const now = Date.now();
  db.run('INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)',
    sha256(token), userId, now, now + days * 86400_000);
  // Keep at most 10 sessions per user.
  db.run(`DELETE FROM sessions WHERE user_id = ? AND token_hash NOT IN
          (SELECT token_hash FROM sessions WHERE user_id = ? ORDER BY created_at DESC LIMIT 10)`, userId, userId);
  return token;
}

export function authenticate(db, header) {
  if (!header || !header.startsWith('Bearer ')) return null;
  const token = header.slice(7).trim();
  if (token.length < 20 || token.length > 100) return null;
  const hash = sha256(token);
  const row = db.get(`SELECT s.token_hash, s.expires_at, u.id, u.username, u.player_code, u.role, u.status, u.last_seen_at, u.created_at
                      FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?`, hash);
  if (!row) return null;
  const now = Date.now();
  if (row.expires_at < now) {
    db.run('DELETE FROM sessions WHERE token_hash = ?', hash);
    return null;
  }
  if (row.status !== 'active') throw new HttpError(403, 'account_suspended', 'This account is suspended. Contact the developer.');
  if (!row.last_seen_at || now - row.last_seen_at > 30_000) db.run('UPDATE users SET last_seen_at = ? WHERE id = ?', now, row.id);
  return { id: row.id, username: row.username, playerCode: row.player_code, role: row.role, createdAt: row.created_at, tokenHash: hash };
}

// Fixed-window in-memory rate limiter. Good enough for a single-process deployment.
export class RateLimiter {
  constructor(scale = 1) { this.buckets = new Map(); this.scale = scale; }
  hit(key, limit, windowMs) {
    const now = Date.now();
    let b = this.buckets.get(key);
    if (!b || b.reset <= now) { b = { count: 0, reset: now + windowMs }; this.buckets.set(key, b); }
    b.count++;
    if (b.count > limit * this.scale) {
      throw new HttpError(429, 'rate_limited', 'Too many requests, slow down muna.', { retryAfterMs: b.reset - now });
    }
    if (this.buckets.size > 50_000) this.sweep(now);
  }
  sweep(now = Date.now()) {
    for (const [k, b] of this.buckets) if (b.reset <= now) this.buckets.delete(k);
  }
}
