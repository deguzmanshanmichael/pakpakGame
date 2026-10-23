import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { rollPull, star5Rate, star4Rate, expectedPullsFor5 } from '../src/wish.js';
import { startApp, endlessRun } from './helpers.js';

const cfg = JSON.parse(readFileSync(new URL('../data/config.json', import.meta.url)));
const W = cfg.wish;
const MEGAS = cfg.megabirds.map((m) => m.id);
const WISH_SKINS = cfg.skins.filter((s) => s.source === 'wish').map((s) => s.id);

function lcg(seed) {
  let x = seed >>> 0;
  return () => { x = (Math.imul(x, 1664525) + 1013904223) >>> 0; return x / 2 ** 32; };
}

test('rates: base, soft pity ramp and hard pity at 200', () => {
  assert.equal(star5Rate(1, W.star5), 0.005);
  assert.equal(star5Rate(150, W.star5), 0.005);
  assert.ok(Math.abs(star5Rate(151, W.star5) - 0.025) < 1e-9);
  assert.ok(star5Rate(180, W.star5) > star5Rate(160, W.star5));
  assert.equal(star5Rate(200, W.star5), 1);
  assert.equal(star4Rate(1, W.star4), 0.05);
  assert.equal(star4Rate(10, W.star4), 1);
  const e = expectedPullsFor5(W.star5);
  assert.ok(e > 90 && e < 200, `expected pulls for a 5-star: ${e}`);
  assert.ok(cfg.megabirds.length >= 5);
});

test('simulation: never more than 200 pulls without a megabird, never 10 without a 4-star+, 50/50 works', () => {
  const rand = lcg(12345);
  let state = { pity5: 0, pity4: 0, guaranteedFeatured: false };
  let since5 = 0, since4 = 0, max5 = 0, max4 = 0;
  let fives = 0, featured = 0, lostThenFeatured = 0, lostCount = 0, lastLost = false;
  let collectibles = 0, rarePlus = 0;
  const N = 400_000;
  for (let i = 0; i < N; i++) {
    const p = rollPull(state, W, MEGAS, WISH_SKINS, rand);
    since5++; since4++;
    if (p.tier === 5) {
      max5 = Math.max(max5, since5); since5 = 0; since4 = 0; fives++;
      if (p.featured) featured++;
      if (lastLost) { assert.equal(p.id, W.featured, 'after losing the 50/50 the next 5-star is featured'); lostThenFeatured++; }
      lastLost = !p.featured;
      if (lastLost) lostCount++;
    } else if (p.tier === 4) {
      max4 = Math.max(max4, since4); since4 = 0;
      if (p.type === 'collectible') { assert.equal(p.minRarity, 'rare'); rarePlus++; }
    } else if (p.type === 'collectible') {
      collectibles++;
    }
    state = p.next;
  }
  assert.ok(max5 <= 200, `max pulls without 5-star: ${max5}`);
  assert.ok(max4 <= 10, `max pulls without 4-star: ${max4}`);
  const avg = N / fives;
  assert.ok(Math.abs(avg - expectedPullsFor5(W.star5)) < 6, `average pulls per 5-star ${avg}`);
  // Roughly 2/3 of 5-stars are featured once the guarantee is included (50/50 + guarantee).
  assert.ok(featured / fives > 0.6 && featured / fives < 0.72, `featured share ${featured / fives}`);
  assert.equal(lostThenFeatured, lostCount - (lastLost ? 1 : 0));
  assert.ok(collectibles > 0 && rarePlus > 0);
});

let ctx;
before(async () => { ctx = await startApp(); });
after(async () => { await ctx.app.close(); });

const setCoins = (id, n) => ctx.app.db.run('UPDATE profiles SET coins = ? WHERE user_id = ?', n, id);

test('tokens cost 150 coins and purchases are idempotent', async () => {
  const u = await ctx.register();
  await setCoins(u.id, 1000);
  const buyId = randomUUID();
  const r1 = await ctx.call('POST', '/api/wish/buy-tokens', { count: 2, clientBuyId: buyId }, u.token);
  assert.equal(r1.status, 200);
  assert.equal(r1.body.state.wish.tokens, 2);
  assert.equal(r1.body.state.profile.coins, 1000 - 2 * W.tokenPrice);
  const r2 = await ctx.call('POST', '/api/wish/buy-tokens', { count: 2, clientBuyId: buyId }, u.token);
  assert.equal(r2.body.bought, 0, 'retry does not charge again');
  assert.equal(r2.body.state.profile.coins, 1000 - 2 * W.tokenPrice);
  const poor = await ctx.call('POST', '/api/wish/buy-tokens', { count: 50, clientBuyId: randomUUID() }, u.token);
  assert.equal(poor.body.error, 'insufficient_coins');
});

test('pulls spend tokens, grant prizes, track pity, and retries return the same results', async () => {
  const u = await ctx.register();
  await setCoins(u.id, 150 * 30);
  await ctx.call('POST', '/api/wish/buy-tokens', { count: 30, clientBuyId: randomUUID() }, u.token);
  const wishId = randomUUID();
  const r = await ctx.call('POST', '/api/wish/pull', { count: 10, clientWishId: wishId }, u.token);
  assert.equal(r.status, 200);
  assert.equal(r.body.results.length, 10);
  assert.ok(r.body.results.some((x) => x.tier >= 4), '10-pull always contains a 4-star or better');
  assert.equal(r.body.state.wish.tokens, 20);
  assert.equal(r.body.state.wish.totalPulls, 10);
  const again = await ctx.call('POST', '/api/wish/pull', { count: 10, clientWishId: wishId }, u.token);
  assert.equal(again.body.duplicate, true);
  assert.deepEqual(again.body.results.map((x) => x.tier), r.body.results.map((x) => x.tier));
  assert.equal(again.body.state.wish.tokens, 20, 'retry does not spend tokens');
  const st = await ctx.call('GET', '/api/wish/state', null, u.token);
  assert.equal(st.body.history.length, 10);
  assert.equal((await ctx.call('POST', '/api/wish/pull', { count: 10, clientWishId: randomUUID() }, (await ctx.register()).token)).body.error, 'not_enough_tokens');
});

test('hard pity: the 200th pull without a 5-star is always a megabird', async () => {
  const u = await ctx.register();
  await ctx.app.db.run('INSERT OR REPLACE INTO wish_state (user_id, tokens, pity5, pity4, guaranteed_featured, total_pulls, updated_at) VALUES (?, 1, 199, 0, 1, 199, ?)', u.id, Date.now());
  const r = await ctx.call('POST', '/api/wish/pull', { count: 1, clientWishId: randomUUID() }, u.token);
  assert.equal(r.body.results[0].tier, 5);
  assert.equal(r.body.results[0].id, W.featured, 'guaranteed featured after a lost 50/50');
  assert.deepEqual(r.body.state.megabirds, [W.featured]);
  assert.equal(r.body.state.wish.pity5, 0);
  assert.equal(r.body.state.wish.guaranteedFeatured, false);
});

test('megabird runs: owned only, Endless only, no medals/best/leaderboard, coin power doubles coins', async () => {
  const u = await ctx.register();
  const notOwned = await ctx.call('POST', '/api/runs/submit', { runs: [endlessRun(30, { megabird: 'pipit' })] }, u.token);
  assert.equal(notOwned.body.results[0].reason, 'megabird_not_owned');
  await ctx.app.db.run('INSERT INTO megabirds_owned (user_id, megabird_id, acquired_at) VALUES (?, ?, ?), (?, ?, ?)', u.id, 'pipit', Date.now(), u.id, 'haribon', Date.now());
  const mega = await ctx.call('POST', '/api/runs/submit', { runs: [endlessRun(30, { megabird: 'pipit', coinsCollected: 10 })] }, u.token);
  const res = mega.body.results[0];
  assert.equal(res.status, 'accepted');
  assert.equal(res.medal, null);
  assert.equal(res.verified, false);
  assert.ok(res.coins >= 10 * 2 + 15, `double coins: ${res.coins}`);
  assert.equal(mega.body.state.stats.bestScore, 0, 'Classic best untouched');
  assert.equal(mega.body.state.stats.bestScoreMega, 30);
  assert.equal(mega.body.state.stats.medals.silver, 0);
  const advRun = { clientRunId: randomUUID(), mode: 'adventure', levelId: '1-1', score: 10, pipes: 10, gems: 0, coinsCollected: 0, flaps: 20, durationMs: 20000, playedAt: Date.now(), completed: true, pickups: [], megabird: 'haribon' };
  const adv = await ctx.call('POST', '/api/runs/submit', { runs: [advRun] }, u.token);
  assert.equal(adv.body.results[0].reason, 'megabird_not_allowed');
  // A normal run still counts as usual.
  const classic = await ctx.call('POST', '/api/runs/submit', { runs: [endlessRun(26)] }, u.token);
  assert.equal(classic.body.results[0].medal, 'silver');
});

test('wish-exclusive skins cannot be bought; new shop skins can', async () => {
  const u = await ctx.register();
  await setCoins(u.id, 5000);
  assert.equal((await ctx.call('POST', '/api/shop/buy-skin', { skinId: 'jeepney' }, u.token)).status, 403);
  const ok = await ctx.call('POST', '/api/shop/buy-skin', { skinId: 'kulasisi' }, u.token);
  assert.equal(ok.status, 200);
  assert.ok(ok.body.skins.includes('kulasisi'));
});

test('Rare+ wish capsules never give Common/Uncommon/Elite', async () => {
  const { openCapsule } = await import('../src/services.js');
  const u = await ctx.register();
  const rarities = new Set();
  await ctx.app.db.tx(async () => {
    for (let i = 0; i < 300; i++) {
      const c = await openCapsule(ctx.app.db, ctx.app.data, u.id, 0, Math.random(), Math.random(), 'test', 'rare');
      rarities.add(c.rarity);
    }
  });
  assert.ok(![...rarities].some((r) => ['common', 'uncommon', 'elite'].includes(r)), [...rarities].join(','));
  assert.ok(rarities.has('rare'));
});
