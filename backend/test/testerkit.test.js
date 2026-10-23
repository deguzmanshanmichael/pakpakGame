import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startApp } from './helpers.js';
import { grantTesterKit, TESTER_COINS, TESTER_TOKENS } from '../src/testerkit.js';

let ctx;
before(async () => { ctx = await startApp(); });
after(async () => { await ctx.app.close(); });

test('tester kit fills an admin account and refuses regular players', async () => {
  const { app, call, register } = ctx;
  const player = await register();
  await assert.rejects(grantTesterKit(app.db, app.data, player.id), /admin/);

  const admin = await register();
  await app.db.run("UPDATE users SET role = 'admin' WHERE id = ?", admin.id);
  const first = await grantTesterKit(app.db, app.data, admin.id);
  assert.equal(first.megabirds, app.data.cfg.megabirds.length);
  assert.equal(first.skins, app.data.cfg.skins.length);
  assert.equal(first.items, [...app.data.items.values()].filter((i) => i.enabled).length);

  const state = (await call('GET', '/api/me/state', null, admin.token)).body;
  assert.equal(state.profile?.coins ?? state.coins, TESTER_COINS);
  const wish = (await call('GET', '/api/wish/state', null, admin.token)).body;
  assert.equal(wish.tokens, TESTER_TOKENS);

  // Running it again only tops up what's missing.
  const again = await grantTesterKit(app.db, app.data, admin.id);
  assert.deepEqual([again.coins, again.skins, again.megabirds, again.items], [0, 0, 0, 0]);
  // Kit items are bound, so they can't leak into trades.
  const tradeable = (await call('GET', `/api/trades/inventory/${admin.id}`, null, admin.token)).body.items;
  assert.equal(tradeable.length, 0);
});

test('admins can give the tester kit from the admin panel API', async () => {
  const { app, call, register } = ctx;
  const admin = await register();
  await app.db.run("UPDATE users SET role = 'admin' WHERE id = ?", admin.id);
  const player = await register();
  assert.equal((await call('POST', `/api/admin/users/${admin.id}/tester-kit`, {}, player.token)).status, 403);
  assert.equal((await call('POST', `/api/admin/users/${player.id}/tester-kit`, {}, admin.token)).status, 400);
  const r = await call('POST', `/api/admin/users/${admin.id}/tester-kit`, { tradeable: true }, admin.token);
  assert.equal(r.status, 200);
  assert.equal(r.body.granted.megabirds, app.data.cfg.megabirds.length);
  // Tradeable kit items show up in the trade inventory, with fresh serial numbers.
  const inv = (await call('GET', `/api/trades/inventory/${admin.id}`, null, admin.token)).body.items;
  assert.ok(inv.length > 0);
  const dupes = await app.db.get('SELECT COUNT(*) AS n FROM (SELECT collectible_id, serial FROM item_copies GROUP BY 1, 2 HAVING COUNT(*) > 1)');
  assert.equal(dupes.n, 0);
  const log = (await call('GET', '/api/admin/audit', null, admin.token)).body.entries;
  assert.ok(log.some((e) => e.action === 'admin.tester_kit' && e.actor === admin.username));
});
