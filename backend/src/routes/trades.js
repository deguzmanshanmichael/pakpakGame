// Trading. The server decides ownership, eligibility and execution; the client only proposes.
// Flow: proposer creates an offer -> both parties review the same summary and confirm ->
// on the second confirmation the swap executes atomically, re-checking ownership inside the transaction.
import { v, bad, conflict, forbidden, notFound, mapSeq } from '../http.js';
import { audit, checkAchievements } from '../services.js';
import { RARITY_VALUE } from '../rules.js';
import { areFriends, isBlockedEither } from './social.js';
import { stripControl } from './player.js';

const MAX_OPEN = 10;
const MAX_LINES = 8;
const MAX_QTY = 20;

function parseSide(list, field, data) {
  if (!Array.isArray(list)) throw bad('invalid_' + field, `${field} must be a list`);
  if (list.length > MAX_LINES) throw bad('invalid_' + field, `At most ${MAX_LINES} different items per side`);
  const seen = new Set();
  return list.map((e) => {
    const id = v.str(e?.collectibleId, 'collectibleId', { max: 16 });
    const qty = v.int(e?.qty ?? 1, 'qty', { min: 1, max: MAX_QTY });
    const item = data.items.get(id);
    if (!item || !item.enabled) throw bad('unknown_collectible', `Unknown collectible ${id}`);
    if (!item.tradeable) throw bad('not_tradeable', `${item.name} is account-bound and cannot be traded`);
    if (seen.has(id)) throw bad('duplicate_line', 'Each item may appear once per side');
    seen.add(id);
    return { collectibleId: id, qty };
  });
}

// Copies the user can trade: tradeable design, not bound, owned by them.
async function tradeableCopies(db, userId, collectibleId, limit) {
  return await db.all(`SELECT i.id FROM item_copies i JOIN collectibles c ON c.id = i.collectible_id
                 WHERE i.owner_id = ? AND i.collectible_id = ? AND i.bound = 0 AND c.tradeable = 1 AND c.enabled = 1
                 ORDER BY i.serial DESC LIMIT ?`, userId, collectibleId, limit);
}

async function tradeView(db, data, t, viewerId) {
  const lines = await db.all('SELECT side, collectible_id AS collectibleId, qty FROM trade_items WHERE trade_id = ?', t.id);
  const names = await db.all('SELECT id, username FROM users WHERE id IN (?, ?)', t.proposer_id, t.recipient_id);
  const nameOf = (id) => names.find((n) => n.id === id)?.username ?? '?';
  const decorate = (l) => {
    const it = data.items.get(l.collectibleId);
    return { ...l, name: it?.name, rarity: it?.rarity };
  };
  const mine = t.proposer_id === viewerId ? 'proposer' : 'recipient';
  return {
    id: t.id, status: t.status, note: t.note, failReason: t.fail_reason,
    createdAt: t.created_at, updatedAt: t.updated_at, completedAt: t.completed_at,
    proposer: { id: t.proposer_id, username: nameOf(t.proposer_id), confirmed: !!t.proposer_confirmed },
    recipient: { id: t.recipient_id, username: nameOf(t.recipient_id), confirmed: !!t.recipient_confirmed },
    mySide: mine,
    proposerGives: lines.filter((l) => l.side === 'proposer').map(decorate),
    recipientGives: lines.filter((l) => l.side === 'recipient').map(decorate),
  };
}

function sideValue(data, lines) {
  return lines.reduce((s, l) => s + (RARITY_VALUE[data.items.get(l.collectibleId)?.rarity] ?? 0) * l.qty, 0);
}

async function suspicionFor(db, data, t, lines) {
  const reasons = [];
  const give = sideValue(data, lines.filter((l) => l.side === 'proposer'));
  const get = sideValue(data, lines.filter((l) => l.side === 'recipient'));
  const hi = Math.max(give, get);
  const lo = Math.min(give, get);
  if (hi >= 60 && hi > 10 * Math.max(1, lo)) reasons.push(`lopsided value ${give} vs ${get}`);
  const day = Date.now() - 86400_000;
  const pairCount = (await db.get(`SELECT COUNT(*) AS n FROM trades WHERE status = 'completed' AND completed_at > ? AND
                             ((proposer_id = ? AND recipient_id = ?) OR (proposer_id = ? AND recipient_id = ?))`,
    day, t.proposer_id, t.recipient_id, t.recipient_id, t.proposer_id)).n;
  if (pairCount >= 3) reasons.push(`${pairCount + 1} trades between same pair in 24h`);
  const young = await db.all('SELECT id FROM users WHERE id IN (?, ?) AND created_at > ?', t.proposer_id, t.recipient_id, day);
  if (young.length && hi >= 20) reasons.push('new account trading valuable items');
  return reasons;
}

async function execute(db, data, t) {
  const lines = await db.all('SELECT side, collectible_id AS collectibleId, qty FROM trade_items WHERE trade_id = ?', t.id);
  const transfers = [];
  for (const l of lines) {
    const from = l.side === 'proposer' ? t.proposer_id : t.recipient_id;
    const to = l.side === 'proposer' ? t.recipient_id : t.proposer_id;
    const copies = await tradeableCopies(db, from, l.collectibleId, l.qty);
    if (copies.length < l.qty) {
      const who = from === t.proposer_id ? 'proposer' : 'recipient';
      return { ok: false, reason: `${who} no longer has ${l.qty}x ${data.items.get(l.collectibleId)?.name}` };
    }
    for (const c of copies) transfers.push({ copyId: c.id, from, to });
  }
  for (const tr of transfers) {
    const res = await db.run('UPDATE item_copies SET owner_id = ? WHERE id = ? AND owner_id = ?', tr.to, tr.copyId, tr.from);
    if (res.changes !== 1) throw new Error('ownership changed during trade');
    await db.run('INSERT INTO trade_transfers (trade_id, copy_id, from_id, to_id) VALUES (?,?,?,?)', t.id, tr.copyId, tr.from, tr.to);
  }
  const reasons = await suspicionFor(db, data, t, lines);
  const now = Date.now();
  await db.run(`UPDATE trades SET status = 'completed', completed_at = ?, updated_at = ?, flagged = ?, flag_reason = ? WHERE id = ?`,
    now, now, reasons.length ? 1 : 0, reasons.length ? reasons.join('; ') : null, t.id);
  await db.run('UPDATE profiles SET trades_completed = trades_completed + 1 WHERE user_id IN (?, ?)', t.proposer_id, t.recipient_id);
  await checkAchievements(db, data, t.proposer_id);
  await checkAchievements(db, data, t.recipient_id);
  await audit(db, null, 'trade.completed', 'trade', t.id, { transfers: transfers.length, flags: reasons });
  return { ok: true };
}

export function tradeRoutes(r, { db, data, limiter }) {
  r.get('/api/trades', 'user', async ({ user, query }) => {
    const history = query.status === 'history';
    const rows = await db.all(`SELECT * FROM trades WHERE (proposer_id = ? OR recipient_id = ?) AND ${history ? "status != 'open'" : "status = 'open'"}
                         ORDER BY updated_at DESC LIMIT 50`, user.id, user.id);
    return { trades: await mapSeq(rows, (t) => tradeView(db, data, t, user.id)) };
  });

  r.get('/api/trades/:id', 'user', async ({ user, params }) => {
    const t = await db.get('SELECT * FROM trades WHERE id = ?', v.id(params.id, 'id'));
    if (!t || (t.proposer_id !== user.id && t.recipient_id !== user.id)) throw notFound('Trade not found');
    return await tradeView(db, data, t, user.id);
  });

  // What the user can put into a trade (tradeable, unbound copies), and a friend's tradeable items to ask for.
  r.get('/api/trades/inventory/:userId', 'user', async ({ user, params }) => {
    const target = v.id(params.userId, 'userId');
    if (target !== user.id && !await areFriends(db, user.id, target)) throw forbidden('You can only view friends\' tradeable items');
    const rows = await db.all(`SELECT i.collectible_id AS collectibleId, COUNT(*) AS qty FROM item_copies i JOIN collectibles c ON c.id = i.collectible_id
                         WHERE i.owner_id = ? AND i.bound = 0 AND c.tradeable = 1 AND c.enabled = 1 GROUP BY i.collectible_id`, target);
    return { userId: target, items: rows };
  });

  r.post('/api/trades', 'user', async ({ user, body }) => {
    limiter.hit(`trade-create:${user.id}`, 20, 3600_000);
    const to = v.id(body.toUserId, 'toUserId');
    if (to === user.id) throw bad('self_trade', 'You cannot trade with yourself');
    const give = parseSide(body.give ?? [], 'give', data);
    const want = parseSide(body.want ?? [], 'want', data);
    if (!give.length && !want.length) throw bad('empty_trade', 'Add at least one item');
    if (!give.length || !want.length) throw bad('one_sided_trade', 'Both sides must offer at least one item. Gifts are not supported.');
    const note = stripControl(v.str(body.note ?? '', 'note', { max: 120 }));
    return await db.tx(async () => {
      if (!await areFriends(db, user.id, to)) throw forbidden('You can only trade with friends');
      if (await isBlockedEither(db, user.id, to)) throw forbidden('You cannot trade with this player');
      const open = (await db.get("SELECT COUNT(*) AS n FROM trades WHERE status = 'open' AND (proposer_id = ? OR recipient_id = ?)", user.id, user.id)).n;
      if (open >= MAX_OPEN) throw conflict('too_many_trades', `You can have at most ${MAX_OPEN} open trades`);
      for (const l of give) {
        if ((await tradeableCopies(db, user.id, l.collectibleId, l.qty)).length < l.qty) throw conflict('not_enough_items', `You do not have ${l.qty}x ${data.items.get(l.collectibleId).name} to trade`);
      }
      for (const l of want) {
        if ((await tradeableCopies(db, to, l.collectibleId, l.qty)).length < l.qty) throw conflict('not_enough_items', `They do not have ${l.qty}x ${data.items.get(l.collectibleId).name} to trade`);
      }
      const now = Date.now();
      const res = await db.run('INSERT INTO trades (proposer_id, recipient_id, note, created_at, updated_at) VALUES (?,?,?,?,?)', user.id, to, note, now, now);
      const id = Number(res.lastInsertRowid);
      for (const l of give) await db.run('INSERT INTO trade_items (trade_id, side, collectible_id, qty) VALUES (?,?,?,?)', id, 'proposer', l.collectibleId, l.qty);
      for (const l of want) await db.run('INSERT INTO trade_items (trade_id, side, collectible_id, qty) VALUES (?,?,?,?)', id, 'recipient', l.collectibleId, l.qty);
      await audit(db, user.id, 'trade.create', 'trade', id);
      return await tradeView(db, data, await db.get('SELECT * FROM trades WHERE id = ?', id), user.id);
    });
  });

  r.post('/api/trades/:id/confirm', 'user', async ({ user, params }) => {
    const id = v.id(params.id, 'id');
    return await db.tx(async () => {
      const t = await db.get('SELECT * FROM trades WHERE id = ?', id);
      if (!t || (t.proposer_id !== user.id && t.recipient_id !== user.id)) throw notFound('Trade not found');
      if (t.status !== 'open') throw conflict('trade_closed', `This trade is already ${t.status}`);
      if (await isBlockedEither(db, t.proposer_id, t.recipient_id) || !await areFriends(db, t.proposer_id, t.recipient_id)) throw forbidden('Trade partners must be friends');
      const col = t.proposer_id === user.id ? 'proposer_confirmed' : 'recipient_confirmed';
      await db.run(`UPDATE trades SET ${col} = 1, updated_at = ? WHERE id = ?`, Date.now(), id);
      const after = await db.get('SELECT * FROM trades WHERE id = ?', id);
      if (after.proposer_confirmed && after.recipient_confirmed) {
        const result = await execute(db, data, after);
        if (!result.ok) {
          await db.run("UPDATE trades SET status = 'failed', fail_reason = ?, updated_at = ? WHERE id = ?", result.reason, Date.now(), id);
          await audit(db, user.id, 'trade.failed', 'trade', id, { reason: result.reason });
        }
      }
      return await tradeView(db, data, await db.get('SELECT * FROM trades WHERE id = ?', id), user.id);
    });
  });

  r.post('/api/trades/:id/cancel', 'user', async ({ user, params }) => {
    const id = v.id(params.id, 'id');
    return await db.tx(async () => {
      const t = await db.get('SELECT * FROM trades WHERE id = ?', id);
      if (!t || (t.proposer_id !== user.id && t.recipient_id !== user.id)) throw notFound('Trade not found');
      if (t.status !== 'open') throw conflict('trade_closed', `This trade is already ${t.status}`);
      const status = t.proposer_id === user.id ? 'cancelled' : 'declined';
      await db.run('UPDATE trades SET status = ?, cancelled_by = ?, updated_at = ? WHERE id = ?', status, user.id, Date.now(), id);
      await audit(db, user.id, `trade.${status}`, 'trade', id);
      return await tradeView(db, data, await db.get('SELECT * FROM trades WHERE id = ?', id), user.id);
    });
  });
}
