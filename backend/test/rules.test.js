import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { medalFor, milestoneCoins, effectiveWeights, pickRarity, depthMultiplier, validateRun, secretUniform, RARITY_ORDER } from '../src/rules.js';

const cfg = JSON.parse(readFileSync(new URL('../data/config.json', import.meta.url)));
const catalog = JSON.parse(readFileSync(new URL('../data/catalog.json', import.meta.url)));
const levels = JSON.parse(readFileSync(new URL('../data/levels.json', import.meta.url)));

test('catalog has the required rarity counts and 133 unique items', () => {
  const want = { common: 50, uncommon: 30, elite: 20, rare: 15, ultra: 10, legendary: 5, celestial: 3 };
  for (const [r, n] of Object.entries(want)) assert.equal(catalog.items.filter((i) => i.rarity === r).length, n, r);
  assert.equal(catalog.items.length, 133);
  assert.equal(new Set(catalog.items.map((i) => i.id)).size, 133);
  assert.equal(new Set(catalog.items.map((i) => i.name)).size, 133);
  for (const i of catalog.items) {
    assert.ok(i.description && i.acquisition?.label && i.palette.length === 4, i.id);
  }
  // Every rarity keeps at least one item in the random pool.
  for (const r of RARITY_ORDER) assert.ok(catalog.items.some((i) => i.rarity === r && i.inDropPool), r);
});

test('medal thresholds are performance based', () => {
  assert.equal(medalFor(9, cfg.medals), null);
  assert.equal(medalFor(10, cfg.medals), 'bronze');
  assert.equal(medalFor(25, cfg.medals), 'silver');
  assert.equal(medalFor(49, cfg.medals), 'silver');
  assert.equal(medalFor(50, cfg.medals), 'gold');
  assert.equal(medalFor(100, cfg.medals), 'platinum');
  assert.equal(milestoneCoins(0, cfg.economy), 0);
  assert.equal(milestoneCoins(25, cfg.economy), 15);
});

test('rarity distribution matches the configured weights', () => {
  const N = 400_000;
  const counts = Object.fromEntries(RARITY_ORDER.map((r) => [r, 0]));
  const w = effectiveWeights(cfg.drops, 0);
  for (let i = 0; i < N; i++) counts[pickRarity(w, secretUniform('dist', i))]++;
  const total = Object.values(cfg.drops.weights).reduce((a, b) => a + b, 0);
  for (const r of RARITY_ORDER) {
    const expected = cfg.drops.weights[r] / total;
    const got = counts[r] / N;
    const tol = Math.max(0.0003, expected * 0.08);
    assert.ok(Math.abs(got - expected) < tol, `${r}: expected ${expected.toFixed(4)} got ${got.toFixed(4)}`);
  }
  // Celestial must be exceptionally rare: under 0.2% per capsule even with the maximum depth bonus.
  const deep = effectiveWeights(cfg.drops, 10_000);
  const deepTotal = deep.reduce((s, [, x]) => s + x, 0);
  const celestial = deep.find(([r]) => r === 'celestial')[1] / deepTotal;
  assert.ok(celestial < 0.002, `celestial deep rate ${celestial}`);
  assert.ok(cfg.drops.weights.celestial / total < 0.001);
});

test('depth bonus increases rare odds with progress, capped', () => {
  assert.equal(depthMultiplier(0, cfg.drops.depthBonus), 1);
  assert.equal(depthMultiplier(25, cfg.drops.depthBonus), 1.25);
  assert.equal(depthMultiplier(100_000, cfg.drops.depthBonus), cfg.drops.depthBonus.max);
  const shallow = Object.fromEntries(effectiveWeights(cfg.drops, 0));
  const deep = Object.fromEntries(effectiveWeights(cfg.drops, 200));
  assert.equal(shallow.common, deep.common);
  assert.ok(deep.legendary > shallow.legendary);
});

test('validateRun rejects impossible runs', () => {
  const now = Date.now();
  const base = { mode: 'endless', score: 30, pipes: 30, gems: 0, coinsCollected: 10, flaps: 90, durationMs: 40_000, playedAt: now, pickups: [5, 15] };
  assert.equal(validateRun(base, { cfg, now }).ok, true);
  assert.equal(validateRun({ ...base, durationMs: 1000 }, { cfg, now }).reason, 'too_fast');
  assert.equal(validateRun({ ...base, score: 31 }, { cfg, now }).reason, 'score_mismatch');
  assert.equal(validateRun({ ...base, coinsCollected: 99 }, { cfg, now }).reason, 'too_many_coins');
  assert.equal(validateRun({ ...base, pickups: [5, 6] }, { cfg, now }).reason, 'capsules_too_close');
  assert.equal(validateRun({ ...base, pickups: [1] }, { cfg, now }).reason, 'capsule_out_of_range');
  const level = levels.levels[0];
  const adv = { mode: 'adventure', levelId: level.id, score: level.length, pipes: level.length, gems: 2, coinsCollected: 0, flaps: 30, durationMs: 30_000, playedAt: now, pickups: [], claimedComplete: true };
  assert.equal(validateRun(adv, { cfg, level, now }).completed, true);
  assert.equal(validateRun({ ...adv, pipes: 3, score: 3, durationMs: 5000 }, { cfg, level, now }).reason, 'objective_not_met');
  assert.equal(validateRun({ ...adv, gems: 99 }, { cfg, level, now }).reason, 'too_many_gems');
  // A ticket proves the run started online, and the run cannot be longer than the ticket's age.
  const ticket = { mode: 'endless', level_id: null, issued_at: now - 60_000, expires_at: now + 3600_000, used: 0 };
  assert.equal(validateRun(base, { cfg, ticket, now }).verified, true);
  assert.equal(validateRun({ ...base, durationMs: 200_000, pipes: 30 }, { cfg, ticket, now }).reason, 'duration_exceeds_ticket');
  assert.equal(validateRun(base, { cfg, ticket: { ...ticket, used: 1 }, now }).reason, 'ticket_reused');
});
