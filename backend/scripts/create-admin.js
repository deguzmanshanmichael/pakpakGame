// Creates an admin account, or promotes an existing account to admin.
// Usage: npm run create-admin -- <username>
// A new account gets a random password and recovery code, printed once. Change the password after first login.
import { randomBytes } from 'node:crypto';
import { loadEnv } from '../src/config.js';
import { openDb } from '../src/db.js';
import { hashSecret, newRecoveryCode, normalizeRecoveryCode, newPlayerCode } from '../src/auth.js';

const username = process.argv[2];
if (!username || !/^[A-Za-z0-9_]{3,16}$/.test(username)) {
  console.error('Usage: npm run create-admin -- <username>   (3-16 letters, digits or _)');
  process.exit(1);
}
const env = loadEnv();
const db = await openDb(env.dbPath, env.dbAuthToken);
const existing = await db.get('SELECT id, role FROM users WHERE username = ?', username);
const now = Date.now();
if (existing) {
  await db.run("UPDATE users SET role = 'admin' WHERE id = ?", existing.id);
  await db.run('INSERT INTO audit_log (actor_id, action, target_type, target_id, created_at) VALUES (NULL, ?, ?, ?, ?)', 'admin.promote_cli', 'user', String(existing.id), now);
  console.log(`Promoted existing user "${username}" to admin.`);
} else {
  const password = randomBytes(12).toString('base64url');
  const recovery = newRecoveryCode();
  const id = await db.tx(async () => {
    const res = await db.run("INSERT INTO users (username, player_code, password_hash, recovery_hash, role, created_at) VALUES (?,?,?,?, 'admin', ?)",
      username, newPlayerCode(), 'pending', 'pending', now);
    const uid = Number(res.lastInsertRowid);
    await db.run('INSERT INTO profiles (user_id, updated_at) VALUES (?, ?)', uid, now);
    return uid;
  });
  await db.run('UPDATE users SET password_hash = ?, recovery_hash = ? WHERE id = ?', await hashSecret(password), await hashSecret(normalizeRecoveryCode(recovery)), id);
  await db.run('INSERT INTO audit_log (actor_id, action, target_type, target_id, created_at) VALUES (NULL, ?, ?, ?, ?)', 'admin.create_cli', 'user', String(id), now);
  console.log(`Created admin "${username}".`);
  console.log(`  Password:      ${password}`);
  console.log(`  Recovery code: ${recovery}`);
  console.log('Store these somewhere safe; they are not shown again.');
}
db.close();
