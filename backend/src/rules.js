// Pure game rules shared by the run-submission pipeline and the tests.
// The Android client implements the same formulas (game/Rules.kt) for offline play;
// for accounts, the server's result always wins.
import { createHmac } from 'node:crypto';

export const RARITY_ORDER = ['common', 'uncommon', 'elite', 'rare', 'ultra', 'legendary', 'celestial'];
export const MEDAL_ORDER = ['bronze', 'silver', 'gold', 'platinum'];

export function medalFor(score, medals) {
  let best = null;
  for (const m of MEDAL_ORDER) if (score >= medals[m]) best = m;
  return best;
}

export function milestoneCoins(score, economy) {
  let sum = 0;
  for (const [threshold, coins] of economy.endlessMilestones) if (score >= threshold) sum += coins;
  return sum;
}

export function depthMultiplier(pipeIndex, depthBonus) {
  return Math.min(depthBonus.max, 1 + Math.floor(pipeIndex / depthBonus.perPipes) * depthBonus.step);
}

// Effective rarity weights for a capsule picked up at `pipeIndex`.
export function effectiveWeights(drops, pipeIndex) {
  const from = RARITY_ORDER.indexOf(drops.depthBonus.fromRarity);
  const mult = depthMultiplier(pipeIndex, drops.depthBonus);
  return RARITY_ORDER.map((r, i) => [r, (drops.weights[r] ?? 0) * (i >= from ? mult : 1)]);
}

// u in [0, 1). Returns a rarity key.
export function pickRarity(weights, u) {
  const total = weights.reduce((s, [, w]) => s + w, 0);
  let x = u * total;
  for (const [r, w] of weights) {
    if (x < w) return r;
    x -= w;
  }
  return weights[0][0];
}

// Deterministic, unpredictable-to-client uniform number from the server secret.
export function secretUniform(secret, ...parts) {
  const h = createHmac('sha256', secret).update(parts.join(':')).digest();
  return h.readUIntBE(0, 6) / 2 ** 48;
}

// Validates a submitted run. Returns { ok, reason, completed } without touching the database.
export function validateRun(run, { cfg, level, ticket, now }) {
  const ac = cfg.antiCheat;
  const drops = cfg.drops;
  if (run.durationMs > 6 * 3600_000) return { ok: false, reason: 'duration_too_long' };
  if (run.score > ac.maxScore) return { ok: false, reason: 'score_too_high' };
  if (run.mode === 'endless' && run.score !== run.pipes) return { ok: false, reason: 'score_mismatch' };
  if (run.pipes > 0 && run.durationMs < run.pipes * ac.minMsPerPipe) return { ok: false, reason: 'too_fast' };
  if (run.coinsCollected > run.pipes + 1) return { ok: false, reason: 'too_many_coins' };
  if (run.playedAt > now + 5 * 60_000) return { ok: false, reason: 'future_timestamp' };

  if (run.mode === 'endless') {
    const p = run.pickups;
    const maxPickups = Math.floor(run.pipes / drops.capsuleMinGap) + 1;
    if (p.length > maxPickups) return { ok: false, reason: 'too_many_capsules' };
    for (let i = 0; i < p.length; i++) {
      if (p[i] < drops.capsuleStartPipe || p[i] > run.pipes + 1) return { ok: false, reason: 'capsule_out_of_range' };
      if (i > 0 && p[i] - p[i - 1] < drops.capsuleMinGap) return { ok: false, reason: 'capsules_too_close' };
    }
  } else {
    if (!level) return { ok: false, reason: 'unknown_level' };
    if (run.pickups.length) return { ok: false, reason: 'unexpected_capsules' };
    if (run.gems > level.gems) return { ok: false, reason: 'too_many_gems' };
  }

  let verified = false;
  if (ticket) {
    if (ticket.used) return { ok: false, reason: 'ticket_reused' };
    if (ticket.expires_at < now) verified = false;
    else if (ticket.mode !== run.mode || (ticket.level_id ?? null) !== (run.levelId ?? null)) return { ok: false, reason: 'ticket_mismatch' };
    else if (run.durationMs > now - ticket.issued_at + 10_000) return { ok: false, reason: 'duration_exceeds_ticket' };
    else verified = true;
  }

  let completed = false;
  if (run.mode === 'adventure') {
    const obj = level.objective;
    if (obj.type === 'survive') completed = run.claimedComplete && run.durationMs >= obj.target * 1000;
    else if (obj.type === 'gems') completed = run.claimedComplete && run.pipes >= level.length && run.gems >= obj.target;
    else completed = run.claimedComplete && run.pipes >= level.length;
    if (run.claimedComplete && !completed) return { ok: false, reason: 'objective_not_met' };
  }
  return { ok: true, verified, completed };
}

export const RARITY_VALUE = { common: 1, uncommon: 3, elite: 8, rare: 20, ultra: 60, legendary: 200, celestial: 1000 };

export function utcDay(ts) {
  return new Date(ts).toISOString().slice(0, 10);
}
