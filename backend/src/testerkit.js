// Tester kit: fills an admin account with everything so the live app can be tested end to end.
// Used by scripts/grant-tester.js. Only admins can receive it, and every grant is audit logged.
import { audit, grantCoins, issueCopy } from './services.js';

export const TESTER_COINS = 9_999_999;
export const TESTER_TOKENS = 99_999;

/**
 * Tops up coins and wish tokens, unlocks every skin and megabird, and gives one copy of each
 * enabled collectible the account doesn't own yet. Safe to run again: it only adds what's missing.
 * Items are bound (untradeable) unless `tradeable` is set, so test items stay out of the player economy.
 */
export async function grantTesterKit(db, data, userId, { tradeable = false } = {}) {
  return db.tx(async () => {
    const user = await db.get('SELECT id, role FROM users WHERE id = ?', userId);
    if (!user) throw new Error(`No user with id ${userId}`);
    if (user.role !== 'admin') throw new Error('The tester kit can only be given to admin accounts');
    const now = Date.now();

    const prof = await db.get('SELECT coins FROM profiles WHERE user_id = ?', userId);
    const coins = Math.max(0, TESTER_COINS - prof.coins);
    if (coins > 0) await grantCoins(db, userId, coins, 'admin_tester_kit', String(now));

    await db.run(`INSERT INTO wish_state (user_id, tokens, updated_at) VALUES (?, ?, ?)
                  ON CONFLICT(user_id) DO UPDATE SET tokens = MAX(tokens, excluded.tokens), updated_at = excluded.updated_at`,
      userId, TESTER_TOKENS, now);

    let skins = 0;
    for (const s of data.cfg.skins) {
      skins += (await db.run('INSERT OR IGNORE INTO skins_owned (user_id, skin_id, acquired_at) VALUES (?,?,?)', userId, s.id, now)).changes;
    }
    let megabirds = 0;
    for (const m of data.cfg.megabirds) {
      megabirds += (await db.run('INSERT OR IGNORE INTO megabirds_owned (user_id, megabird_id, acquired_at) VALUES (?,?,?)', userId, m.id, now)).changes;
    }

    const owned = new Set((await db.all('SELECT DISTINCT collectible_id AS id FROM item_copies WHERE owner_id = ?', userId)).map((r) => r.id));
    let items = 0;
    for (const item of data.items.values()) {
      if (!item.enabled || owned.has(item.id)) continue;
      if (await issueCopy(db, data, userId, item.id, { source: 'admin', bound: !tradeable, originRef: `tester_kit:${now}` })) items++;
    }

    const result = { coins, tokens: TESTER_TOKENS, skins, megabirds, items };
    await audit(db, null, 'admin.tester_kit', 'user', userId, result);
    return result;
  });
}
