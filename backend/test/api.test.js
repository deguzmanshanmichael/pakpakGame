import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { startApp, endlessRun, makeFriends, giveItems } from './helpers.js';

let ctx;
before(async () => { ctx = await startApp(); });
after(async () => { await ctx.app.close(); });

const tradeableCommon = () => [...ctx.app.data.items.values()].find((i) => i.rarity === 'common' && i.tradeable).id;
const tradeableIds = () => [...ctx.app.data.items.values()].filter((i) => i.tradeable).map((i) => i.id);

test('health and public config', async () => {
  const h = await ctx.call('GET', '/api/health');
  assert.equal(h.body.ok, true);
  const c = await ctx.call('GET', '/api/config');
  assert.ok(c.body.notice.body.includes('Sabi ni Shan'));
  const cat = await ctx.call('GET', '/api/catalog');
  assert.equal(cat.body.items.length, 133);
});

test('registration validation, login, logout and recovery', async () => {
  const { call } = ctx;
  assert.equal((await call('POST', '/api/auth/register', { username: 'ab', password: 'flapflap123' })).body.error, 'invalid_username');
  assert.equal((await call('POST', '/api/auth/register', { username: 'birdy_1', password: 'short1' })).body.error, 'weak_password');
  assert.equal((await call('POST', '/api/auth/register', { username: 'birdy_1', password: 'onlyletters' })).body.error, 'weak_password');
  const reg = await call('POST', '/api/auth/register', { username: 'birdy_1', password: 'flapflap123' });
  assert.equal(reg.status, 200);
  assert.match(reg.body.recoveryCode, /^[A-Z2-9]{4}(-[A-Z2-9]{4}){3}$/);
  assert.match(reg.body.state.user.playerCode, /^PK-/);
  assert.equal((await call('POST', '/api/auth/register', { username: 'BIRDY_1', password: 'flapflap123' })).status, 409);

  assert.equal((await call('POST', '/api/auth/login', { username: 'birdy_1', password: 'wrongpass1' })).status, 401);
  assert.equal((await call('POST', '/api/auth/login', { username: 'nobody_here', password: 'wrongpass1' })).status, 401);
  const login = await call('POST', '/api/auth/login', { username: 'birdy_1', password: 'flapflap123' });
  assert.equal(login.status, 200);
  assert.equal((await call('GET', '/api/me/state', null, login.body.token)).status, 200);
  await call('POST', '/api/auth/logout', {}, login.body.token);
  assert.equal((await call('GET', '/api/me/state', null, login.body.token)).status, 401);
  assert.equal((await call('GET', '/api/me/state')).status, 401);

  // The password hash is never plaintext.
  const row = await ctx.app.db.get('SELECT password_hash FROM users WHERE username = ?', 'birdy_1');
  assert.match(row.password_hash, /^scrypt\$/);
  assert.ok(!row.password_hash.includes('flapflap123'));

  const bad = await call('POST', '/api/auth/recover', { username: 'birdy_1', recoveryCode: 'AAAA-BBBB-CCCC-DDDD', newPassword: 'newflap456' });
  assert.equal(bad.status, 401);
  const rec = await call('POST', '/api/auth/recover', { username: 'birdy_1', recoveryCode: reg.body.recoveryCode.toLowerCase(), newPassword: 'newflap456' });
  assert.equal(rec.status, 200);
  assert.notEqual(rec.body.recoveryCode, reg.body.recoveryCode);
  assert.equal((await call('GET', '/api/me/state', null, reg.body.token)).status, 401, 'old sessions revoked');
  assert.equal((await call('POST', '/api/auth/login', { username: 'birdy_1', password: 'newflap456' })).status, 200);
  // A recovery code works once.
  assert.equal((await call('POST', '/api/auth/recover', { username: 'birdy_1', recoveryCode: reg.body.recoveryCode, newPassword: 'again789x' })).status, 401);
});

test('endless run awards coins, medals, milestone items and is idempotent', async () => {
  const u = await ctx.register();
  const run = endlessRun(26, { coinsCollected: 8, pickups: [4, 12, 20] });
  const r1 = await ctx.call('POST', '/api/runs/submit', { runs: [run] }, u.token);
  assert.equal(r1.status, 200);
  const res = r1.body.results[0];
  assert.equal(res.status, 'accepted');
  assert.equal(res.medal, 'silver');
  const capsules = res.items.filter((i) => i.reason === 'capsule');
  assert.equal(capsules.length, 3);
  const milestone = res.items.filter((i) => i.reason.startsWith('medal_')).map((i) => i.reason).sort();
  assert.deepEqual(milestone, ['medal_bronze', 'medal_silver']);
  assert.ok(res.achievements.some((a) => a.id === 'runs_1'));
  const state1 = r1.body.state;
  assert.equal(state1.stats.medals.silver, 1);
  assert.equal(state1.stats.bestScore, 26);
  assert.equal(state1.stats.bestScoreVerified, 0, 'no ticket means not leaderboard-verified');
  const coinsAfter = state1.profile.coins;
  assert.ok(coinsAfter >= 8 + 15);

  // Resubmitting the same run (e.g. sync retry) gives the same result and no extra rewards.
  const r2 = await ctx.call('POST', '/api/runs/submit', { runs: [run] }, u.token);
  assert.equal(r2.body.results[0].duplicate, true);
  assert.equal(r2.body.state.profile.coins, coinsAfter);
  assert.equal(r2.body.state.stats.totalRuns, 1);
  const copies = (await ctx.app.db.get('SELECT COUNT(*) AS n FROM item_copies WHERE owner_id = ?', u.id)).n;
  assert.equal(copies, 5);

  // Milestone items are only granted once even after another silver run.
  const r3 = await ctx.call('POST', '/api/runs/submit', { runs: [endlessRun(30)] }, u.token);
  assert.equal(r3.body.results[0].items.length, 0);
});

test('ticketed runs are verified and count for the leaderboard', async () => {
  const u = await ctx.register();
  const t = await ctx.call('POST', '/api/runs/start', { mode: 'endless' }, u.token);
  assert.ok(t.body.ticketId);
  const run = endlessRun(12, { ticketId: t.body.ticketId, durationMs: 1 });
  // Too short for 12 pipes: rejected.
  const bad = await ctx.call('POST', '/api/runs/submit', { runs: [run] }, u.token);
  assert.equal(bad.body.results[0].status, 'rejected');
  assert.equal(bad.body.results[0].reason, 'too_fast');
  // Duration longer than the ticket has existed: rejected.
  const t2 = await ctx.call('POST', '/api/runs/start', { mode: 'endless' }, u.token);
  const long = await ctx.call('POST', '/api/runs/submit', { runs: [endlessRun(12, { ticketId: t2.body.ticketId, durationMs: 60_000 })] }, u.token);
  assert.equal(long.body.results[0].reason, 'duration_exceeds_ticket');
  // Fake clock: backdate a ticket so a 20s run is plausible.
  const t3 = await ctx.call('POST', '/api/runs/start', { mode: 'endless' }, u.token);
  await ctx.app.db.run('UPDATE run_tickets SET issued_at = ? WHERE id = ?', Date.now() - 30_000, t3.body.ticketId);
  const ok = await ctx.call('POST', '/api/runs/submit', { runs: [endlessRun(12, { ticketId: t3.body.ticketId, durationMs: 20_000 })] }, u.token);
  assert.equal(ok.body.results[0].verified, true);
  assert.equal(ok.body.state.stats.bestScoreVerified, 12);
  const lb = await ctx.call('GET', '/api/leaderboard', null, u.token);
  assert.ok(lb.body.entries.some((e) => e.id === u.id && e.score === 12));
  // The ticket cannot be reused.
  const reuse = await ctx.call('POST', '/api/runs/submit', { runs: [endlessRun(12, { ticketId: t3.body.ticketId, durationMs: 20_000 })] }, u.token);
  assert.equal(reuse.body.results[0].reason, 'ticket_reused');
});

test('daily capsule and coin caps stop farming', async () => {
  const u = await ctx.register();
  const cap = ctx.app.data.cfg.economy.dailyCapsuleCap;
  const runs = [];
  for (let i = 0; i < 12; i++) runs.push(endlessRun(20, { pickups: [3, 9, 15], coinsCollected: 20 }));
  const res = await ctx.call('POST', '/api/runs/submit', { runs }, u.token);
  const opened = res.body.results.reduce((s, r) => s + r.items.filter((i) => i.reason === 'capsule').length, 0);
  assert.equal(opened, cap);
  assert.ok(res.body.results.some((r) => r.notes.includes('daily_capsule_cap')));
  const totalRunCoins = (await ctx.app.db.get("SELECT SUM(delta) AS s FROM currency_ledger WHERE user_id = ? AND reason = 'run'", u.id)).s;
  const overflow = (12 * 3 - cap) * ctx.app.data.cfg.economy.overflowCapsuleCoins;
  assert.ok(totalRunCoins <= ctx.app.data.cfg.economy.dailyEndlessCoinCap + overflow);
});

test('adventure: unlock order, first clear reward once, bonus objectives and replays', async () => {
  const u = await ctx.register();
  const L = ctx.app.data.levels;
  const l1 = L.get('1-1');
  const advRun = (level, extra = {}) => ({
    clientRunId: randomUUID(), mode: 'adventure', levelId: level.id, score: level.length, pipes: level.length, gems: level.gems,
    coinsCollected: 0, flaps: 10, durationMs: level.length * 1600, distance: 0, playedAt: Date.now(), completed: true, pickups: [], ...extra,
  });
  const locked = await ctx.call('POST', '/api/runs/submit', { runs: [advRun(L.get('1-2'))] }, u.token);
  assert.equal(locked.body.results[0].reason, 'level_locked');
  assert.equal((await ctx.call('POST', '/api/runs/start', { mode: 'adventure', levelId: '1-2' }, u.token)).status, 403);

  const first = await ctx.call('POST', '/api/runs/submit', { runs: [advRun(l1)] }, u.token);
  const r = first.body.results[0];
  assert.equal(r.completed, true);
  assert.ok(r.coins >= l1.reward + 2 * l1.bonusReward);
  assert.ok(r.items.some((i) => i.reason === 'first_clear'));
  const p = first.body.state.adventure.find((a) => a.levelId === '1-1');
  assert.ok(p.masteredAt && p.bonusAllGems && p.bonusFlaps);

  const replay = await ctx.call('POST', '/api/runs/submit', { runs: [advRun(l1)] }, u.token);
  assert.equal(replay.body.results[0].coins, ctx.app.data.cfg.economy.replayReward);
  assert.equal(replay.body.results[0].items.length, 0);
  // Failing to finish but claiming completion is rejected.
  const cheat = await ctx.call('POST', '/api/runs/submit', { runs: [advRun(L.get('1-2'), { pipes: 2, score: 2, durationMs: 4000 })] }, u.token);
  assert.equal(cheat.body.results[0].reason, 'objective_not_met');
});

test('mastering a boss level grants its account-bound mastery collectible', async () => {
  const u = await ctx.register();
  const levels = [...ctx.app.data.levels.values()].filter((l) => l.chapter === 1).sort((a, b) => a.order - b.order);
  const runs = levels.map((level) => ({
    clientRunId: randomUUID(), mode: 'adventure', levelId: level.id, score: level.length, pipes: level.length, gems: level.gems,
    coinsCollected: 0, flaps: 10, durationMs: level.length * 1600, playedAt: Date.now(), completed: true, pickups: [],
  }));
  for (const run of runs) await ctx.call('POST', '/api/runs/submit', { runs: [run] }, u.token);
  const boss = levels[4];
  const copy = await ctx.app.db.get('SELECT * FROM item_copies WHERE owner_id = ? AND collectible_id = ?', u.id, boss.masteryItem);
  assert.ok(copy, 'mastery item issued');
  assert.equal(copy.bound, 1);
  const state = await ctx.call('GET', '/api/me/state', null, u.token);
  assert.ok(state.body.achievements.some((a) => a.id === 'chapter_1'));
});

test('friends, privacy and messaging safety', async () => {
  const { call } = ctx;
  const a = await ctx.register();
  const b = await ctx.register();
  const c = await ctx.register();
  // Not friends: cannot message.
  assert.equal((await call('POST', '/api/messages', { toUserId: b.id, body: 'hi' }, a.token)).status, 403);
  const search = await call('GET', `/api/users/search?q=${b.username.slice(0, 5)}`, null, a.token);
  assert.ok(search.body.users.some((u) => u.id === b.id));
  const byCode = await call('GET', `/api/users/search?q=${(await call('GET', '/api/me/state', null, b.token)).body.user.playerCode}`, null, a.token);
  assert.equal(byCode.body.users[0].id, b.id);
  // Friends-only profile is hidden from strangers.
  assert.equal((await call('GET', `/api/users/${b.id}/profile`, null, a.token)).body.visible, false);

  const req = await call('POST', '/api/friends/requests', { userId: b.id }, a.token);
  assert.equal(req.body.status, 'pending');
  assert.equal((await call('POST', '/api/friends/requests', { userId: b.id }, a.token)).status, 409);
  const notif = await call('GET', '/api/notifications', null, b.token);
  assert.equal(notif.body.friendRequests, 1);
  // Only the recipient can respond.
  assert.equal((await call('POST', `/api/friends/requests/${req.body.requestId}/respond`, { accept: true }, c.token)).status, 404);
  const acc = await call('POST', `/api/friends/requests/${req.body.requestId}/respond`, { accept: true }, b.token);
  assert.equal(acc.body.status, 'accepted');
  assert.equal((await call('GET', `/api/users/${b.id}/profile`, null, a.token)).body.visible, true);
  // The friend list carries each friend's id and name (a missing await once dropped them).
  const fl = await call('GET', '/api/friends', null, a.token);
  assert.equal(fl.body.friends.length, 1);
  assert.equal(fl.body.friends[0].id, b.id);
  assert.equal(fl.body.friends[0].username, b.username);
  assert.equal(fl.body.friends[0].friend, true);

  const m = await call('POST', '/api/messages', { toUserId: b.id, body: 'text me at 0917 123 4567 or me@mail.com', clientMsgId: 'msg-00000001' }, a.token);
  assert.equal(m.status, 200);
  assert.ok(!m.body.body.includes('0917') && !m.body.body.includes('@'), m.body.body);
  const dup = await call('POST', '/api/messages', { toUserId: b.id, body: 'again', clientMsgId: 'msg-00000001' }, a.token);
  assert.equal(dup.body.duplicate, true);
  assert.equal((await call('GET', '/api/notifications', null, b.token)).body.unreadMessages, 1);
  const convs = await call('GET', '/api/conversations', null, b.token);
  assert.equal(convs.body.conversations[0].unread, 1);
  const msgs = await call('GET', `/api/conversations/${a.id}/messages`, null, b.token);
  assert.equal(msgs.body.messages.length, 1);
  await call('POST', `/api/conversations/${a.id}/read`, {}, b.token);
  const seen = await call('GET', `/api/conversations/${b.id}/messages`, null, a.token);
  assert.equal(seen.body.messages[0].status, 'read');
  // A third user cannot read the conversation between a and b.
  assert.equal((await call('GET', `/api/conversations/${a.id}/messages`, null, c.token)).body.messages.length, 0);

  // Reports copy the message text as evidence.
  const rep = await call('POST', '/api/reports', { userId: a.id, messageId: msgs.body.messages[0].id, category: 'spam' }, b.token);
  assert.equal(rep.status, 200);
  assert.equal((await call('POST', '/api/reports', { userId: a.id, messageId: msgs.body.messages[0].id, category: 'spam' }, c.token)).status, 404);

  // Blocking removes the friendship and stops messages.
  await call('POST', '/api/blocks', { userId: a.id }, b.token);
  assert.equal((await call('POST', '/api/messages', { toUserId: b.id, body: 'hello?' }, a.token)).status, 403);
  assert.equal((await call('POST', '/api/friends/requests', { userId: b.id }, a.token)).status, 404);
  // Privacy: refusing friend requests.
  await call('PATCH', '/api/me/profile', { friendRequests: 'nobody' }, c.token);
  assert.equal((await call('POST', '/api/friends/requests', { userId: c.id }, a.token)).status, 403);
});

test('trades require both confirmations and move ownership atomically', async () => {
  const { call, app } = ctx;
  const a = await ctx.register();
  const b = await ctx.register();
  const c = await ctx.register();
  await makeFriends(call, a, b);
  await makeFriends(call, a, c);
  const [x, y] = tradeableIds();
  await giveItems(app, a.id, x, 1);
  await giveItems(app, b.id, y, 1);
  await giveItems(app, c.id, y, 1);

  // Not friends -> forbidden; account-bound items -> rejected.
  assert.equal((await call('POST', '/api/trades', { toUserId: c.id, give: [{ collectibleId: y }], want: [{ collectibleId: y }] }, b.token)).status, 403);
  const bound = [...app.data.items.values()].find((i) => !i.tradeable).id;
  assert.equal((await call('POST', '/api/trades', { toUserId: b.id, give: [{ collectibleId: bound }], want: [{ collectibleId: y }] }, a.token)).body.error, 'not_tradeable');
  assert.equal((await call('POST', '/api/trades', { toUserId: b.id, give: [{ collectibleId: x, qty: 2 }], want: [{ collectibleId: y }] }, a.token)).body.error, 'not_enough_items');

  // a offers the same single copy of x to both b and c.
  const t1 = (await call('POST', '/api/trades', { toUserId: b.id, give: [{ collectibleId: x }], want: [{ collectibleId: y }] }, a.token)).body;
  const t2 = (await call('POST', '/api/trades', { toUserId: c.id, give: [{ collectibleId: x }], want: [{ collectibleId: y }] }, a.token)).body;
  assert.equal(t1.status, 'open');

  const b1 = await call('POST', `/api/trades/${t1.id}/confirm`, {}, b.token);
  assert.equal(b1.body.status, 'open', 'one confirmation is not enough');
  const a1 = await call('POST', `/api/trades/${t1.id}/confirm`, {}, a.token);
  assert.equal(a1.body.status, 'completed');
  const ownerX = (await app.db.get('SELECT owner_id FROM item_copies WHERE collectible_id = ? AND owner_id IN (?, ?)', x, a.id, b.id)).owner_id;
  assert.equal(ownerX, b.id);

  // The second trade can no longer execute: no duplication.
  await call('POST', `/api/trades/${t2.id}/confirm`, {}, c.token);
  const a2 = await call('POST', `/api/trades/${t2.id}/confirm`, {}, a.token);
  assert.equal(a2.body.status, 'failed');
  assert.match(a2.body.failReason, /no longer has/);
  const xCopies = await app.db.all('SELECT owner_id FROM item_copies WHERE collectible_id = ? AND owner_id IN (?, ?, ?)', x, a.id, b.id, c.id);
  assert.deepEqual(xCopies.map((r) => r.owner_id), [b.id]);

  // Cancel before confirmation, and history.
  await giveItems(app, a.id, x, 1);
  const t3 = (await call('POST', '/api/trades', { toUserId: b.id, give: [{ collectibleId: x }], want: [{ collectibleId: x }] }, a.token)).body;
  const cancelled = await call('POST', `/api/trades/${t3.id}/cancel`, {}, b.token);
  assert.equal(cancelled.body.status, 'declined');
  assert.equal((await call('POST', `/api/trades/${t3.id}/confirm`, {}, a.token)).status, 409);
  const hist = await call('GET', '/api/trades?status=history', null, a.token);
  assert.deepEqual(hist.body.trades.map((t) => t.status).sort(), ['completed', 'declined', 'failed']);
});

test('redemption needs a fully verified collection; admin reviews with reasons', async () => {
  const { call, app } = ctx;
  const u = await ctx.register();
  const el = await call('GET', '/api/redemptions/eligibility', null, u.token);
  assert.equal(el.body.eligible, false);
  assert.equal((await call('POST', '/api/redemptions', { contactHandle: 'fb: birdlover' }, u.token)).status, 403);

  // Guest-imported items never count.
  await call('POST', '/api/me/import-guest', { items: Object.fromEntries([...app.data.items.keys()].map((id) => [id, 1])), coins: 99999 }, u.token);
  assert.equal((await call('GET', '/api/redemptions/eligibility', null, u.token)).body.eligible, false);
  assert.equal((await call('POST', '/api/me/import-guest', { items: {} }, u.token)).status, 409, 'import is once only');
  const st = await call('GET', '/api/me/state', null, u.token);
  assert.ok(st.body.profile.coins <= 1000 + 1000, 'imported coins are capped');

  for (const id of app.data.items.keys()) await giveItems(app, u.id, id, 1);
  assert.equal((await call('GET', '/api/redemptions/eligibility', null, u.token)).body.eligible, true);
  assert.equal((await call('POST', '/api/redemptions', { contactHandle: 'fb: birdlover', note: 'my password is 1234' }, u.token)).body.error, 'sensitive_info');
  const claim = await call('POST', '/api/redemptions', { contactHandle: 'fb: birdlover' }, u.token);
  assert.equal(claim.body.status, 'submitted');
  assert.equal((await call('POST', '/api/redemptions', { contactHandle: 'fb: birdlover' }, u.token)).status, 409);

  // Players cannot use admin endpoints.
  assert.equal((await call('GET', '/api/admin/redemptions', null, u.token)).status, 403);
  const admin = await ctx.register();
  await app.db.run("UPDATE users SET role = 'admin' WHERE id = ?", admin.id);
  const list = await call('GET', '/api/admin/redemptions', null, admin.token);
  assert.equal(list.body.redemptions[0].id, claim.body.id);
  assert.equal((await call('POST', `/api/admin/redemptions/${claim.body.id}/decision`, { status: 'fulfilled', reason: 'skip' }, admin.token)).body.error, 'invalid_transition');
  assert.equal((await call('POST', `/api/admin/redemptions/${claim.body.id}/decision`, { status: 'verified' }, admin.token)).status, 400, 'reason required');
  assert.equal((await call('POST', `/api/admin/redemptions/${claim.body.id}/decision`, { status: 'verified', reason: 'Checked collection and runs' }, admin.token)).status, 200);
  const mine = await call('GET', '/api/me/state', null, u.token);
  assert.equal(mine.body.redemption.status, 'verified');
  const auditRows = await call('GET', '/api/admin/audit', null, admin.token);
  assert.ok(auditRows.body.entries.some((e) => e.action === 'redemption.verified'));
});

test('admin can tune drop rates and the notice; invalid configs are rejected', async () => {
  const { call, app } = ctx;
  const admin = await ctx.register();
  await app.db.run("UPDATE users SET role = 'admin' WHERE id = ?", admin.id);
  const drops = structuredClone(app.data.cfg.drops);
  drops.weights.celestial = 1;
  assert.equal((await call('PUT', '/api/admin/config/drops', { value: drops }, admin.token)).status, 200);
  assert.equal((await call('GET', '/api/config')).body.drops.weights.celestial, 1);
  assert.equal((await call('PUT', '/api/admin/config/drops', { value: { ...drops, weights: { common: -1 } } }, admin.token)).status, 400);
  assert.equal((await call('PUT', '/api/admin/config/medals', { value: { bronze: 10, silver: 5, gold: 50, platinum: 100 } }, admin.token)).status, 400);
  const notice = { ...app.data.cfg.notice, contact: 'Message Shan on the official PakPak page' };
  await call('PUT', '/api/admin/config/notice', { value: notice }, admin.token);
  assert.equal((await call('GET', '/api/config')).body.notice.contact, notice.contact);
  await call('PUT', '/api/admin/config/drops', { reset: true }, admin.token);
  assert.equal(app.data.cfg.drops.weights.celestial, 8);

  // Global supply cap: a collectible capped at 1 copy can only be issued once.
  const celestial = [...app.data.items.values()].find((i) => i.rarity === 'celestial');
  const patch = await call('PATCH', `/api/admin/collectibles/${celestial.id}`, { maxSupply: celestial.issued + 1 }, admin.token);
  assert.equal(patch.status, 200);
  const p1 = await ctx.register();
  await giveItems(app, p1.id, celestial.id, 3);
  const n = (await app.db.get('SELECT COUNT(*) AS n FROM item_copies WHERE collectible_id = ? AND owner_id = ?', celestial.id, p1.id)).n;
  assert.equal(n, 1);
});

test('rejects malformed input and oversized bodies', async () => {
  const u = await ctx.register();
  const r = await ctx.call('POST', '/api/runs/submit', { runs: [{ mode: 'endless', score: 'lots' }] }, u.token);
  assert.equal(r.body.results[0].status, 'invalid');
  const big = await fetch(ctx.base + '/api/messages', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${u.token}` }, body: JSON.stringify({ body: 'x'.repeat(100_000) }),
  });
  assert.equal(big.status, 413);
  assert.equal((await ctx.call('GET', '/api/nope')).status, 404);
});

test('rate limits apply at production strength', async () => {
  const strict = await (await import('./helpers.js')).startApp(1);
  try {
    const statuses = [];
    for (let i = 0; i < 7; i++) {
      statuses.push((await strict.call('POST', '/api/auth/register', { username: `rl_user${i}`, password: 'flapflap123' })).status);
    }
    assert.deepEqual(statuses.slice(0, 5), [200, 200, 200, 200, 200]);
    assert.equal(statuses[5], 429);
    const logins = [];
    for (let i = 0; i < 12; i++) logins.push((await strict.call('POST', '/api/auth/login', { username: 'rl_user0', password: 'nope-nope-1' })).status);
    assert.ok(logins.includes(429) || logins.includes(423), `brute force must be throttled: ${logins}`);
  } finally {
    await strict.app.close();
  }
});
