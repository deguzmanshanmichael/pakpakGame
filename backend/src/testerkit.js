// Tester kit: fills an admin account with everything so the live app can be tested end to end.
// Used by scripts/grant-tester.js. Only admins can receive it, and every grant is audit logged.
import { audit, grantCoins } from './services.js';

export const TESTER_COINS = 9_999_999;
export const TESTER_TOKENS = 99_999;

/**
 * Tops up coins and wish tokens, unlocks every skin and megabird, and gives one copy of each
 * enabled collectible the account doesn't own yet. Safe to run again: it only adds what's missing.
 * Items are bound (untradeable) unless `tradeable` is set, so test items stay out of the player economy.
 */
export async function grantTesterKit(db, data, userId, { tradeable = false, actorId = null } = {}) {
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

    // One copy of every enabled collectible not owned yet, in bulk (a statement per item is slow on a remote database).
    const ref = `tester_kit:${now}`;
    const items = (await db.run(`INSERT INTO item_copies (collectible_id, owner_id, serial, source, bound, verified, origin_ref, acquired_at)
        SELECT c.id, ?, c.issued + 1, 'admin', CASE WHEN ? OR c.tradeable = 0 THEN 1 ELSE 0 END, 1, ?, ?
        FROM collectibles c
        WHERE c.enabled = 1 AND (c.max_supply IS NULL OR c.issued < c.max_supply)
          AND NOT EXISTS (SELECT 1 FROM item_copies i WHERE i.owner_id = ? AND i.collectible_id = c.id)`,
      userId, tradeable ? 0 : 1, ref, now, userId)).changes;
    await db.run('UPDATE collectibles SET issued = issued + 1 WHERE id IN (SELECT collectible_id FROM item_copies WHERE owner_id = ? AND origin_ref = ?)', userId, ref);
    for (const r of await db.all('SELECT c.id, c.issued FROM collectibles c JOIN item_copies i ON i.collectible_id = c.id WHERE i.owner_id = ? AND i.origin_ref = ?', userId, ref)) {
      const item = data.items.get(r.id);
      if (item) item.issued = r.issued;
    }

    const result = { coins, tokens: TESTER_TOKENS, skins, megabirds, items };
    await audit(db, actorId, 'admin.tester_kit', 'user', userId, { ...result, tradeable });
    return result;
  });
}
