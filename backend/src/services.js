// Database-backed game services: currency ledger, item issuance, run processing, achievements,
// and the account state snapshot sent to clients. All mutating functions expect to run inside (await db.tx()).
import { randomUUID } from 'node:crypto';
import { HttpError, conflict } from './http.js';
import {
  RARITY_ORDER, MEDAL_ORDER, medalFor, milestoneCoins, effectiveWeights, pickRarity, secretUniform, validateRun, utcDay,
} from './rules.js';

export async function audit(db, actorId, action, targetType, targetId, details) {
  await db.run('INSERT INTO audit_log (actor_id, action, target_type, target_id, details, created_at) VALUES (?,?,?,?,?,?)',
    actorId ?? null, action, targetType ?? null, targetId == null ? null : String(targetId), details ? JSON.stringify(details) : null, Date.now());
}

// Adds coins exactly once per (reason, ref). Returns the delta actually applied (0 if already claimed).
export async function grantCoins(db, userId, delta, reason, ref) {
  const prof = await db.get('SELECT coins FROM profiles WHERE user_id = ?', userId);
  const balance = prof.coins + delta;
  if (balance < 0) throw conflict('insufficient_coins', 'Not enough Pak Coins');
  const res = await db.run('INSERT OR IGNORE INTO currency_ledger (user_id, delta, balance, reason, ref, created_at) VALUES (?,?,?,?,?,?)',
    userId, delta, balance, reason, ref, Date.now());
  if (res.changes === 0) return 0;
  await db.run('UPDATE profiles SET coins = ?, updated_at = ? WHERE user_id = ?', balance, Date.now(), userId);
  return delta;
}

// Issues one copy of a collectible, respecting max supply. Returns the copy row or null if supply is exhausted.
export async function issueCopy(db, data, userId, collectibleId, { source, bound = false, verified = true, originRef = null }) {
  const c = await db.get('SELECT id, max_supply, issued, tradeable FROM collectibles WHERE id = ?', collectibleId);
  if (!c) throw new HttpError(500, 'unknown_collectible', `Unknown collectible ${collectibleId}`);
  if (c.max_supply != null && c.issued >= c.max_supply) return null;
  const serial = c.issued + 1;
  await db.run('UPDATE collectibles SET issued = ? WHERE id = ?', serial, c.id);
  const res = await db.run(`INSERT INTO item_copies (collectible_id, owner_id, serial, source, bound, verified, origin_ref, acquired_at)
                      VALUES (?,?,?,?,?,?,?,?)`, c.id, userId, serial, source, bound || !c.tradeable ? 1 : 0, verified ? 1 : 0, originRef, Date.now());
  const item = data.items.get(c.id);
  if (item) item.issued = serial;
  return { copyId: Number(res.lastInsertRowid), collectibleId: c.id, serial, rarity: item?.rarity };
}

// Rolls and issues a capsule. `u1` picks the rarity, `u2` picks the design.
export async function openCapsule(db, data, userId, pipeIndex, u1, u2, originRef, minRarity = 'common') {
  // A "Rare+ capsule" keeps the normal relative odds between Rare and rarer; only lower tiers are removed.
  const minIdx = RARITY_ORDER.indexOf(minRarity);
  const weights = effectiveWeights(data.cfg.drops, pipeIndex).filter(([r]) => RARITY_ORDER.indexOf(r) >= minIdx);
  let rarity = pickRarity(weights, u1);
  // If a rarity's pool is empty or sold out, fall back to the next more common rarity.
  for (let idx = RARITY_ORDER.indexOf(rarity); idx >= Math.max(0, minIdx); idx--) {
    const r = RARITY_ORDER[idx];
    const pool = (data.pool.get(r) ?? []).filter((id) => {
      const it = data.items.get(id);
      return it.max_supply == null || it.issued < it.max_supply;
    });
    if (!pool.length) continue;
    const id = pool[Math.min(pool.length - 1, Math.floor(u2 * pool.length))];
    const copy = await issueCopy(db, data, userId, id, { source: 'capsule', originRef });
    if (copy) return { ...copy, rarity: r };
  }
  return null;
}

async function dailyRow(db, userId, day) {
  await db.run('INSERT OR IGNORE INTO daily_counters (user_id, day) VALUES (?, ?)', userId, day);
  return await db.get('SELECT * FROM daily_counters WHERE user_id = ? AND day = ?', userId, day);
}

export async function issueTicket(db, data, userId, mode, levelId) {
  const now = Date.now();
  const id = randomUUID();
  await db.run('INSERT INTO run_tickets (id, user_id, mode, level_id, issued_at, expires_at) VALUES (?,?,?,?,?,?)',
    id, userId, mode, levelId ?? null, now, now + data.cfg.antiCheat.ticketTtlHours * 3600_000);
  await db.run('DELETE FROM run_tickets WHERE user_id = ? AND expires_at < ?', userId, now - 86400_000);
  return { ticketId: id, issuedAt: now };
}

async function grantMilestoneItem(db, data, userId, key, granted) {
  const item = [...data.items.values()].find((i) => {
    const a = JSON.parse(i.acquisition);
    return a.type === 'milestone' && a.key === key;
  });
  if (!item || !item.enabled) return;
  if (await db.get('SELECT 1 FROM item_copies WHERE owner_id = ? AND origin_ref = ?', userId, `milestone:${key}`)) return;
  const copy = await issueCopy(db, data, userId, item.id, { source: 'milestone', bound: true, originRef: `milestone:${key}` });
  if (copy) granted.items.push({ ...copy, reason: key });
}

/**
 * Validates and applies one submitted run. Idempotent per (user, clientRunId): resubmitting returns the
 * stored result. Throws HttpError for malformed input; returns { status: 'rejected' } for implausible runs.
 */
export async function processRun(db, data, user, run, now = Date.now()) {
  const existing = await db.get('SELECT result FROM runs WHERE user_id = ? AND client_run_id = ?', user.id, run.clientRunId);
  if (existing) return { ...JSON.parse(existing.result), duplicate: true };

  const cfg = data.cfg;
  const level = run.mode === 'adventure' ? data.levels.get(run.levelId) : null;
  const ticket = run.ticketId ? await db.get('SELECT * FROM run_tickets WHERE id = ? AND user_id = ?', run.ticketId, user.id) : null;
  if (run.ticketId && !ticket) run.ticketId = null;

  const recent = (await db.get('SELECT COUNT(*) AS n FROM runs WHERE user_id = ? AND received_at > ?', user.id, now - 3600_000)).n;
  let check = recent >= cfg.antiCheat.maxRunsPerHour ? { ok: false, reason: 'too_many_runs' } : validateRun(run, { cfg, level, ticket, now });
  const mega = run.megabird ? (cfg.megabirds ?? []).find((m) => m.id === run.megabird) : null;
  if (check.ok && run.megabird) {
    if (run.mode !== 'endless') check = { ok: false, reason: 'megabird_not_allowed' };
    else if (!mega || !await db.get('SELECT 1 FROM megabirds_owned WHERE user_id = ? AND megabird_id = ?', user.id, run.megabird)) check = { ok: false, reason: 'megabird_not_owned' };
  }

  const insertRun = (result, extra) => db.run(`INSERT INTO runs (user_id, client_run_id, ticket_id, mode, level_id, score, pipes, gems,
      coins_collected, flaps, duration_ms, completed, verified, medal, coins_awarded, capsules_awarded, flag_reason, result, played_at, received_at, megabird)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
    user.id, run.clientRunId, run.ticketId ?? null, run.mode, run.levelId ?? null, run.score, run.pipes, run.gems, run.coinsCollected,
    run.flaps, run.durationMs, extra.completed ? 1 : 0, extra.verified ? 1 : 0, extra.medal ?? null, extra.coins ?? 0, extra.capsules ?? 0,
    extra.flag ?? null, JSON.stringify(result), run.playedAt, now, run.megabird ?? null);

  if (!check.ok) {
    const result = { status: 'rejected', reason: check.reason, coins: 0, items: [], achievements: [] };
    await insertRun(result, { flag: check.reason });
    await db.run('UPDATE profiles SET suspicious_runs = suspicious_runs + 1, flagged = CASE WHEN suspicious_runs + 1 >= 5 THEN 1 ELSE flagged END WHERE user_id = ?', user.id);
    return result;
  }
  if (ticket) await db.run('UPDATE run_tickets SET used = 1 WHERE id = ?', ticket.id);

  const granted = { coins: 0, items: [], achievements: [], overflowCapsules: 0, notes: [] };
  const day = await dailyRow(db, user.id, utcDay(now));
  const ref = `run:${run.clientRunId}`;
  let medal = null;

  if (run.mode === 'endless') {
    // Megabird runs earn coins and capsules but never medals, milestone items, Classic best or the leaderboard.
    medal = mega ? null : medalFor(run.score, cfg.medals);
    const coinMult = mega?.power === 'coins' ? (mega.params?.multiplier ?? 2) : 1;
    const raw = run.coinsCollected * coinMult + milestoneCoins(run.score, cfg.economy);
    const room = Math.max(0, cfg.economy.dailyEndlessCoinCap - day.endless_coins);
    const coins = Math.min(raw, room);
    if (coins < raw) granted.notes.push('daily_coin_cap');
    let capsuleRoom = Math.max(0, cfg.economy.dailyCapsuleCap - day.capsules);
    let opened = 0;
    for (const [i, pipeIndex] of run.pickups.entries()) {
      if (capsuleRoom <= 0) { granted.overflowCapsules++; continue; }
      const seedKey = run.ticketId ?? `${user.id}:${run.clientRunId}`;
      const copy = await openCapsule(db, data, user.id, pipeIndex,
        secretUniform(data.secret, 'rarity', seedKey, i), secretUniform(data.secret, 'design', seedKey, i), ref);
      if (copy) { granted.items.push({ ...copy, reason: 'capsule', pipeIndex }); opened++; capsuleRoom--; }
    }
    const overflowCoins = granted.overflowCapsules * cfg.economy.overflowCapsuleCoins;
    if (granted.overflowCapsules) granted.notes.push('daily_capsule_cap');
    granted.coins += await grantCoins(db, user.id, coins + overflowCoins, 'run', run.clientRunId);
    await db.run('UPDATE daily_counters SET endless_coins = endless_coins + ?, capsules = capsules + ? WHERE user_id = ? AND day = ?',
      coins, opened, user.id, day.day);

    const medalCol = medal ? `, medal_${medal} = medal_${medal} + 1` : '';
    if (mega) await db.run('UPDATE profiles SET best_score_mega = MAX(best_score_mega, ?) WHERE user_id = ?', run.score, user.id);
    else await db.run(`UPDATE profiles SET best_score = MAX(best_score, ?),
              best_score_verified = CASE WHEN ? THEN MAX(best_score_verified, ?) ELSE best_score_verified END${medalCol}
            WHERE user_id = ?`, run.score, check.verified ? 1 : 0, run.score, user.id);
    if (medal) for (const m of MEDAL_ORDER.slice(0, MEDAL_ORDER.indexOf(medal) + 1)) await grantMilestoneItem(db, data, user.id, `medal_${m}`, granted);
  } else if (check.completed) {
    const prog = await db.get('SELECT * FROM adventure_progress WHERE user_id = ? AND level_id = ?', user.id, level.id);
    const firstClear = !prog?.completed_at;
    if (firstClear) {
      granted.coins += await grantCoins(db, user.id, level.reward, 'level_clear', level.id);
      if (cfg.drops.adventureFirstClearCapsule) {
        const copy = await openCapsule(db, data, user.id, level.order * 5,
          secretUniform(data.secret, 'lvl-rarity', user.id, level.id), secretUniform(data.secret, 'lvl-design', user.id, level.id), `level:${level.id}`);
        if (copy) granted.items.push({ ...copy, reason: 'first_clear' });
      }
    } else {
      const room = Math.max(0, cfg.economy.dailyReplayCap - day.replay_coins);
      const coins = Math.min(cfg.economy.replayReward, room);
      if (coins > 0) {
        granted.coins += await grantCoins(db, user.id, coins, 'level_replay', run.clientRunId);
        await db.run('UPDATE daily_counters SET replay_coins = replay_coins + ? WHERE user_id = ? AND day = ?', coins, user.id, day.day);
      }
    }
    const allGems = run.gems >= level.gems;
    const flapsOk = run.flaps <= level.bonus.find((b) => b.type === 'maxFlaps').target;
    if (allGems) granted.coins += await grantCoins(db, user.id, level.bonusReward, 'level_bonus', `${level.id}:allGems`);
    if (flapsOk) granted.coins += await grantCoins(db, user.id, level.bonusReward, 'level_bonus', `${level.id}:maxFlaps`);
    await db.run(`INSERT INTO adventure_progress (user_id, level_id, completed_at, best_gems, best_flaps, bonus_all_gems, bonus_flaps)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(user_id, level_id) DO UPDATE SET
              completed_at = COALESCE(completed_at, excluded.completed_at),
              best_gems = MAX(best_gems, excluded.best_gems),
              best_flaps = CASE WHEN best_flaps IS NULL THEN excluded.best_flaps ELSE MIN(best_flaps, excluded.best_flaps) END,
              bonus_all_gems = MAX(bonus_all_gems, excluded.bonus_all_gems),
              bonus_flaps = MAX(bonus_flaps, excluded.bonus_flaps)`,
      user.id, level.id, now, run.gems, run.flaps, allGems ? 1 : 0, flapsOk ? 1 : 0);
    const after = await db.get('SELECT * FROM adventure_progress WHERE user_id = ? AND level_id = ?', user.id, level.id);
    if (after.bonus_all_gems && after.bonus_flaps && !after.mastered_at) {
      await db.run('UPDATE adventure_progress SET mastered_at = ? WHERE user_id = ? AND level_id = ?', now, user.id, level.id);
      if (level.masteryItem) {
        const copy = await issueCopy(db, data, user.id, level.masteryItem, { source: 'adventure', bound: true, originRef: `mastery:${level.id}` });
        if (copy) granted.items.push({ ...copy, reason: 'mastery' });
      }
    }
  }

  await db.run(`UPDATE profiles SET total_runs = total_runs + 1, total_pipes = total_pipes + ?, total_distance = total_distance + ?,
          total_flaps = total_flaps + ?, updated_at = ? WHERE user_id = ?`, run.pipes, run.distance, run.flaps, now, user.id);
  granted.achievements = await checkAchievements(db, data, user.id);
  for (const a of granted.achievements) granted.coins += a.reward;

  const result = { status: 'accepted', verified: check.verified && !mega, completed: check.completed, medal, megabird: mega?.id ?? null, ...granted };
  await insertRun(result, { verified: check.verified, completed: check.completed, medal, coins: granted.coins, capsules: granted.items.length });
  return result;
}

// Unlocks any newly satisfied achievements and pays their rewards. Returns the newly unlocked ones.
export async function checkAchievements(db, data, userId) {
  const p = await db.get('SELECT * FROM profiles WHERE user_id = ?', userId);
  const have = new Set((await db.all('SELECT achievement_id FROM achievements_unlocked WHERE user_id = ?', userId)).map((r) => r.achievement_id));
  // Imported guest items are unverified, so they never pay out achievement coins.
  const distinct = (await db.get("SELECT COUNT(DISTINCT collectible_id) AS n FROM item_copies WHERE owner_id = ? AND source != 'guest_import'", userId)).n;
  const rarities = new Set((await db.all(`SELECT DISTINCT c.rarity FROM item_copies i JOIN collectibles c ON c.id = i.collectible_id
                                   WHERE i.owner_id = ? AND i.source != 'guest_import'`, userId)).map((r) => r.rarity));
  const progress = await db.all('SELECT level_id, completed_at, mastered_at FROM adventure_progress WHERE user_id = ?', userId);
  const done = new Set(progress.filter((r) => r.completed_at).map((r) => r.level_id));
  const mastered = new Set(progress.filter((r) => r.mastered_at).map((r) => r.level_id));
  const friends = (await db.get('SELECT COUNT(*) AS n FROM friendships WHERE user_a = ? OR user_b = ?', userId, userId)).n;
  const levels = [...data.levels.values()];
  const medalCount = (m) => p[`medal_${m}`] ?? 0;
  const met = (a) => {
    switch (a.type) {
      case 'runs': return p.total_runs >= a.value;
      case 'pipes': return p.total_pipes >= a.value;
      case 'medal': return MEDAL_ORDER.slice(MEDAL_ORDER.indexOf(a.value)).some((m) => medalCount(m) > 0);
      case 'chapter': return levels.filter((l) => l.chapter === a.value).every((l) => done.has(l.id));
      case 'masterAll': return levels.every((l) => mastered.has(l.id));
      case 'distinct': return distinct >= a.value;
      case 'rarity': return RARITY_ORDER.slice(RARITY_ORDER.indexOf(a.value)).some((r) => rarities.has(r));
      case 'friends': return friends >= a.value;
      case 'trades': return p.trades_completed >= a.value;
      default: return false;
    }
  };
  const unlocked = [];
  for (const a of data.cfg.achievements) {
    if (have.has(a.id) || !met(a)) continue;
    await db.run('INSERT OR IGNORE INTO achievements_unlocked (user_id, achievement_id, unlocked_at) VALUES (?,?,?)', userId, a.id, Date.now());
    const paid = await grantCoins(db, userId, a.reward, 'achievement', a.id);
    unlocked.push({ id: a.id, name: a.name, reward: paid });
  }
  return unlocked;
}

export async function collectionSummary(db, data, userId) {
  const rows = await db.all(`SELECT collectible_id AS id, COUNT(*) AS count, SUM(bound) AS bound,
                         SUM(CASE WHEN verified = 1 AND source != 'guest_import' THEN 1 ELSE 0 END) AS verified
                       FROM item_copies WHERE owner_id = ? GROUP BY collectible_id`, userId);
  const enabled = [...data.items.values()].filter((i) => i.enabled);
  const owned = new Set(rows.map((r) => r.id));
  const verifiedSet = new Set(rows.filter((r) => r.verified > 0).map((r) => r.id));
  return {
    items: rows,
    distinct: owned.size,
    verifiedDistinct: enabled.filter((i) => verifiedSet.has(i.id)).length,
    total: enabled.length,
    missing: enabled.filter((i) => !verifiedSet.has(i.id)).map((i) => i.id),
  };
}

export async function accountState(db, data, userId) {
  const u = await db.get('SELECT id, username, player_code, role, created_at FROM users WHERE id = ?', userId);
  const p = await db.get('SELECT * FROM profiles WHERE user_id = ?', userId);
  const col = await collectionSummary(db, data, userId);
  return {
    user: { id: u.id, username: u.username, playerCode: u.player_code, role: u.role, createdAt: u.created_at },
    profile: {
      avatar: p.avatar, bio: p.bio, equippedSkin: p.equipped_skin, profileVisibility: p.profile_visibility, friendRequests: p.friend_requests,
      coins: p.coins, guestImported: !!p.guest_imported,
    },
    stats: {
      bestScore: p.best_score, bestScoreVerified: p.best_score_verified, totalRuns: p.total_runs, totalPipes: p.total_pipes,
      totalDistance: p.total_distance, totalFlaps: p.total_flaps, tradesCompleted: p.trades_completed, bestScoreMega: p.best_score_mega,
      medals: { bronze: p.medal_bronze, silver: p.medal_silver, gold: p.medal_gold, platinum: p.medal_platinum },
      friends: (await db.get('SELECT COUNT(*) AS n FROM friendships WHERE user_a = ? OR user_b = ?', userId, userId)).n,
    },
    collection: { items: col.items, distinct: col.distinct, verifiedDistinct: col.verifiedDistinct, total: col.total },
    adventure: (await db.all('SELECT level_id AS levelId, completed_at AS completedAt, best_gems AS bestGems, best_flaps AS bestFlaps, bonus_all_gems AS bonusAllGems, bonus_flaps AS bonusFlaps, mastered_at AS masteredAt FROM adventure_progress WHERE user_id = ?', userId))
      .map((r) => ({ ...r, bonusAllGems: !!r.bonusAllGems, bonusFlaps: !!r.bonusFlaps })),
    achievements: await db.all('SELECT achievement_id AS id, unlocked_at AS unlockedAt FROM achievements_unlocked WHERE user_id = ?', userId),
    skins: ['pakpak', ...(await db.all('SELECT skin_id FROM skins_owned WHERE user_id = ?', userId)).map((r) => r.skin_id)],
    megabirds: (await db.all('SELECT megabird_id FROM megabirds_owned WHERE user_id = ?', userId)).map((r) => r.megabird_id),
    wish: await wishStateView(db, userId),
    redemption: await db.get('SELECT id, status, decision_reason AS decisionReason, created_at AS createdAt, decided_at AS decidedAt FROM redemption_requests WHERE user_id = ? ORDER BY id DESC LIMIT 1', userId) ?? null,
    serverTime: Date.now(),
  };
}

export async function wishStateRow(db, userId) {
  await db.run('INSERT OR IGNORE INTO wish_state (user_id, updated_at) VALUES (?, ?)', userId, Date.now());
  return await db.get('SELECT * FROM wish_state WHERE user_id = ?', userId);
}

export async function wishStateView(db, userId) {
  const w = await db.get('SELECT * FROM wish_state WHERE user_id = ?', userId);
  return { tokens: w?.tokens ?? 0, pity5: w?.pity5 ?? 0, pity4: w?.pity4 ?? 0, guaranteedFeatured: !!w?.guaranteed_featured, totalPulls: w?.total_pulls ?? 0 };
}
