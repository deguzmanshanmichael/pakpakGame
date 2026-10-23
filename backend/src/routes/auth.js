import { v, bad, HttpError } from '../http.js';
import {
  hashSecret, verifySecret, burnTime, createSession, newRecoveryCode, normalizeRecoveryCode, newPlayerCode,
} from '../auth.js';
import { audit, accountState } from '../services.js';

const USERNAME = /^[A-Za-z0-9_]{3,16}$/;
const RESERVED = /^(admin|shan|moderator|support|system|pakpak)/i;

function checkPassword(pw, username) {
  if (typeof pw !== 'string' || pw.length < 8 || pw.length > 128) throw bad('weak_password', 'Password must be 8-128 characters');
  if (!/[A-Za-z]/.test(pw) || !/[0-9]/.test(pw)) throw bad('weak_password', 'Password needs at least one letter and one number');
  if (username && pw.toLowerCase().includes(username.toLowerCase())) throw bad('weak_password', 'Password must not contain your username');
  return pw;
}

export function authRoutes(r, { db, data, env, limiter }) {
  r.post('/api/auth/register', 'none', async ({ body, ip }) => {
    limiter.hit(`register:${ip}`, 5, 3600_000);
    const username = v.str(body.username, 'username', { min: 3, max: 16, pattern: USERNAME });
    if (RESERVED.test(username)) throw bad('username_reserved', 'That username is reserved');
    const password = checkPassword(body.password, username);
    if (await db.get('SELECT 1 FROM users WHERE username = ?', username)) throw new HttpError(409, 'username_taken', 'Username is already taken');
    const recoveryCode = newRecoveryCode();
    const [pwHash, recHash] = await Promise.all([hashSecret(password), hashSecret(normalizeRecoveryCode(recoveryCode))]);
    const now = Date.now();
    const userId = await db.tx(async () => {
      if (await db.get('SELECT 1 FROM users WHERE username = ?', username)) throw new HttpError(409, 'username_taken', 'Username is already taken');
      let code;
      do code = newPlayerCode(); while (await db.get('SELECT 1 FROM users WHERE player_code = ?', code));
      const res = await db.run('INSERT INTO users (username, player_code, password_hash, recovery_hash, created_at, last_seen_at) VALUES (?,?,?,?,?,?)',
        username, code, pwHash, recHash, now, now);
      const id = Number(res.lastInsertRowid);
      await db.run('INSERT INTO profiles (user_id, updated_at) VALUES (?, ?)', id, now);
      await audit(db, id, 'user.register', 'user', id);
      return id;
    });
    const token = await createSession(db, userId, env.sessionDays);
    return { token, recoveryCode, state: await accountState(db, data, userId) };
  });

  r.post('/api/auth/login', 'none', async ({ body, ip }) => {
    const username = v.str(body.username, 'username', { min: 1, max: 32 });
    const password = v.str(body.password, 'password', { min: 1, max: 128 });
    limiter.hit(`login-ip:${ip}`, 30, 15 * 60_000);
    limiter.hit(`login-user:${username.toLowerCase()}`, 10, 15 * 60_000);
    const u = await db.get('SELECT id, password_hash, status, failed_logins, locked_until FROM users WHERE username = ?', username);
    const invalid = new HttpError(401, 'invalid_credentials', 'Wrong username or password');
    if (!u) { await burnTime(password); throw invalid; }
    if (u.locked_until && u.locked_until > Date.now()) {
      throw new HttpError(423, 'account_locked', 'Too many failed attempts. Try again in a few minutes or reset your password.');
    }
    if (!(await verifySecret(password, u.password_hash))) {
      const fails = u.failed_logins + 1;
      await db.run('UPDATE users SET failed_logins = ?, locked_until = ? WHERE id = ?', fails >= 8 ? 0 : fails, fails >= 8 ? Date.now() + 15 * 60_000 : null, u.id);
      throw invalid;
    }
    if (u.status !== 'active') throw new HttpError(403, 'account_suspended', 'This account is suspended. Contact the developer.');
    await db.run('UPDATE users SET failed_logins = 0, locked_until = NULL, last_seen_at = ? WHERE id = ?', Date.now(), u.id);
    const token = await createSession(db, u.id, env.sessionDays);
    return { token, state: await accountState(db, data, u.id) };
  });

  r.post('/api/auth/logout', 'user', async ({ user }) => {
    await db.run('DELETE FROM sessions WHERE token_hash = ?', user.tokenHash);
    return { ok: true };
  });

  r.post('/api/auth/logout-all', 'user', async ({ user }) => {
    await db.run('DELETE FROM sessions WHERE user_id = ?', user.id);
    return { ok: true };
  });

  // Account recovery: username + the one-time recovery code shown at registration. No email or phone
  // number is collected, which keeps personal data out of the system for younger players.
  r.post('/api/auth/recover', 'none', async ({ body, ip }) => {
    limiter.hit(`recover-ip:${ip}`, 10, 3600_000);
    const username = v.str(body.username, 'username', { min: 1, max: 32 });
    limiter.hit(`recover-user:${username.toLowerCase()}`, 5, 3600_000);
    const code = normalizeRecoveryCode(body.recoveryCode);
    if (code.length !== 16) throw bad('invalid_recovery_code', 'Recovery code should look like ABCD-EFGH-JKLM-NPQR');
    const password = checkPassword(body.newPassword, username);
    const u = await db.get('SELECT id, recovery_hash FROM users WHERE username = ?', username);
    const invalid = new HttpError(401, 'invalid_recovery', 'Username or recovery code is wrong');
    if (!u) { await burnTime(code); throw invalid; }
    if (!(await verifySecret(code, u.recovery_hash))) throw invalid;
    const newCode = newRecoveryCode();
    const [pwHash, recHash] = await Promise.all([hashSecret(password), hashSecret(normalizeRecoveryCode(newCode))]);
    await db.tx(async () => {
      await db.run('UPDATE users SET password_hash = ?, recovery_hash = ?, failed_logins = 0, locked_until = NULL WHERE id = ?', pwHash, recHash, u.id);
      await db.run('DELETE FROM sessions WHERE user_id = ?', u.id);
      await audit(db, u.id, 'user.recover', 'user', u.id);
    });
    const token = await createSession(db, u.id, env.sessionDays);
    return { token, recoveryCode: newCode, state: await accountState(db, data, u.id) };
  });

  r.post('/api/auth/change-password', 'user', async ({ body, user }) => {
    limiter.hit(`chpw:${user.id}`, 5, 3600_000);
    const current = v.str(body.currentPassword, 'currentPassword', { min: 1, max: 128 });
    const password = checkPassword(body.newPassword, user.username);
    const u = await db.get('SELECT password_hash FROM users WHERE id = ?', user.id);
    if (!(await verifySecret(current, u.password_hash))) throw new HttpError(401, 'invalid_credentials', 'Current password is wrong');
    const hash = await hashSecret(password);
    await db.tx(async () => {
      await db.run('UPDATE users SET password_hash = ? WHERE id = ?', hash, user.id);
      await db.run('DELETE FROM sessions WHERE user_id = ? AND token_hash != ?', user.id, user.tokenHash);
      await audit(db, user.id, 'user.change_password', 'user', user.id);
    });
    return { ok: true };
  });
}
