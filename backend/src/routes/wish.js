// Hiling ni Tala (the lucky spin). All rolls, token balances and pity counters are server-side.
import { randomInt } from 'node:crypto';
import { v, bad, conflict } from '../http.js';
import { grantCoins, openCapsule, accountState, wishStateRow, audit } from '../services.js';
import { rollPull } from '../wish.js';

const CLIENT_ID = /^[A-Za-z0-9-]{8,64}$/;
const secureRandom = () => randomInt(0, 2 ** 47) / 2 ** 47;

async function historyView(rows, data) {
  return rows.map((r) => {
    let name = r.prize_id;
    if (r.prize_type === 'megabird') name = data.cfg.megabirds.find((m) => m.id === r.prize_id)?.name ?? r.prize_id;
    if (r.prize_type === 'skin') name = data.cfg.skins.find((s) => s.id === r.prize_id)?.name ?? r.prize_id;
    if (r.prize_type === 'collectible') name = data.items.get(r.prize_id)?.name ?? r.prize_id;
    if (r.prize_type === 'coins') name = `${r.amount} ${data.cfg.economy.currencyName}`;
    return {
      pullNo: r.pull_no, tier: r.tier, type: r.prize_type, id: r.prize_id, name, amount: r.amount,
      duplicate: !!r.duplicate, featured: !!r.featured, pity: r.pity_at_pull, rarity: r.prize_type === 'collectible' ? data.items.get(r.prize_id)?.rarity : undefined,
      createdAt: r.created_at,
    };
  });
}

export function wishRoutes(r, { db, data, limiter }) {
  r.get('/api/wish/state', 'user', async ({ user }) => {
    const w = await wishStateRow(db, user.id);
    const recent = await db.all('SELECT * FROM wish_history WHERE user_id = ? ORDER BY id DESC LIMIT 50', user.id);
    return {
      tokens: w.tokens, pity5: w.pity5, pity4: w.pity4, guaranteedFeatured: !!w.guaranteed_featured, totalPulls: w.total_pulls,
      history: await historyView(recent, data),
    };
  });

  r.post('/api/wish/buy-tokens', 'user', async ({ user, body }) => {
    limiter.hit(`wish-buy:${user.id}`, 60, 3600_000);
    const count = v.int(body.count, 'count', { min: 1, max: 50 });
    const clientBuyId = v.str(body.clientBuyId, 'clientBuyId', { min: 8, max: 64, pattern: CLIENT_ID });
    const price = data.cfg.wish.tokenPrice;
    return await db.tx(async () => {
      await wishStateRow(db, user.id);
      // grantCoins is idempotent per clientBuyId, so a retried purchase never charges twice.
      const charged = await grantCoins(db, user.id, -price * count, 'wish_tokens', clientBuyId);
      if (charged !== 0) await db.run('UPDATE wish_state SET tokens = tokens + ?, updated_at = ? WHERE user_id = ?', count, Date.now(), user.id);
      return { bought: charged !== 0 ? count : 0, state: await accountState(db, data, user.id) };
    });
  });

  r.post('/api/wish/pull', 'user', async ({ user, body }) => {
    limiter.hit(`wish-pull:${user.id}`, 120, 3600_000);
    const count = v.oneOf(body.count, 'count', [1, 10]);
    const clientWishId = v.str(body.clientWishId, 'clientWishId', { min: 8, max: 64, pattern: CLIENT_ID });
    const cfg = data.cfg.wish;
    const megabirds = data.cfg.megabirds.map((m) => m.id);
    const wishSkins = data.cfg.skins.filter((s) => s.source === 'wish').map((s) => s.id);
    if (!megabirds.includes(cfg.featured)) throw bad('wish_misconfigured', 'The featured megabird is not configured');

    return await db.tx(async () => {
      const done = await db.all('SELECT * FROM wish_history WHERE user_id = ? AND client_wish_id = ? ORDER BY pull_no', user.id, clientWishId);
      if (done.length) return { results: await historyView(done, data), duplicate: true, state: await accountState(db, data, user.id) };

      const w = await wishStateRow(db, user.id);
      if (w.tokens < count) throw conflict('not_enough_tokens', `You need ${count} ${cfg.tokenName}${count > 1 ? 's' : ''}`);
      let state = { pity5: w.pity5, pity4: w.pity4, guaranteedFeatured: !!w.guaranteed_featured };
      const now = Date.now();
      const rows = [];
      for (let i = 0; i < count; i++) {
        const pull = rollPull(state, cfg, megabirds, wishSkins, secureRandom);
        state = pull.next;
        const ref = `${clientWishId}:${i}`;
        let prizeId = pull.id ?? null;
        let amount = pull.amount ?? 0;
        let duplicate = false;
        if (pull.type === 'megabird') {
          const res = await db.run('INSERT OR IGNORE INTO megabirds_owned (user_id, megabird_id, acquired_at) VALUES (?,?,?)', user.id, prizeId, now);
          if (res.changes === 0) { duplicate = true; amount = await grantCoins(db, user.id, cfg.duplicateMegabirdCoins, 'wish_duplicate', ref); }
        } else if (pull.type === 'skin') {
          const res = await db.run('INSERT OR IGNORE INTO skins_owned (user_id, skin_id, acquired_at) VALUES (?,?,?)', user.id, prizeId, now);
          if (res.changes === 0) { duplicate = true; amount = await grantCoins(db, user.id, cfg.duplicateSkinCoins, 'wish_duplicate', ref); }
        } else if (pull.type === 'coins') {
          amount = await grantCoins(db, user.id, amount, 'wish_coins', ref);
        } else {
          // Same published capsule odds as Endless (Rare+ keeps the relative odds of Rare and rarer).
          const copy = await openCapsule(db, data, user.id, 0, secureRandom(), secureRandom(), `wish:${ref}`, pull.minRarity);
          prizeId = copy?.collectibleId ?? null;
        }
        await db.run(`INSERT INTO wish_history (user_id, client_wish_id, pull_no, tier, prize_type, prize_id, amount, duplicate, featured, pity_at_pull, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
          user.id, clientWishId, i, pull.tier, pull.type, prizeId, amount, duplicate ? 1 : 0, pull.featured ? 1 : 0, pull.pityAtPull, now);
        rows.push(await db.get('SELECT * FROM wish_history WHERE user_id = ? AND client_wish_id = ? AND pull_no = ?', user.id, clientWishId, i));
      }
      await db.run(`UPDATE wish_state SET tokens = tokens - ?, pity5 = ?, pity4 = ?, guaranteed_featured = ?, total_pulls = total_pulls + ?, updated_at = ?
              WHERE user_id = ?`, count, state.pity5, state.pity4, state.guaranteedFeatured ? 1 : 0, count, now, user.id);
      const megas = rows.filter((x) => x.prize_type === 'megabird');
      if (megas.length) await audit(db, user.id, 'wish.megabird', 'user', user.id, { megabirds: megas.map((m) => m.prize_id) });
      return { results: await historyView(rows, data), state: await accountState(db, data, user.id) };
    });
  });
}
