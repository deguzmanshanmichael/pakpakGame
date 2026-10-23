// Gives an admin account the tester kit: max coins and wish tokens, every skin, every megabird and
// one copy of every collectible. Safe to run again.
// Usage: npm run grant-tester -- <admin username> [--tradeable]
import { loadEnv } from '../src/config.js';
import { openDb } from '../src/db.js';
import { GameData } from '../src/gamedata.js';
import { grantTesterKit } from '../src/testerkit.js';

const username = process.argv[2];
if (!username || username.startsWith('--')) {
  console.error('Usage: npm run grant-tester -- <admin username> [--tradeable]');
  process.exit(1);
}
const env = loadEnv();
const db = await openDb(env.dbPath, env.dbAuthToken);
try {
  const user = await db.get('SELECT id FROM users WHERE username = ?', username);
  if (!user) throw new Error(`No account named "${username}". Create it first: npm run create-admin -- ${username}`);
  const data = await GameData.create(db);
  const r = await grantTesterKit(db, data, user.id, { tradeable: process.argv.includes('--tradeable') });
  console.log(`Tester kit given to "${username}":`);
  console.log(`  +${r.coins} coins, ${r.tokens} wish tokens, ${r.skins} skins, ${r.megabirds} megabirds, ${r.items} collectibles`);
  console.log('Sign out and back in (or reopen the app) to see it.');
} catch (e) {
  console.error(e.message);
  process.exitCode = 1;
} finally {
  db.close();
}
