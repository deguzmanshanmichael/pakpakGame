import { v, bad, conflict, forbidden, notFound } from '../http.js';
import { accountState, processRun, issueTicket, grantCoins, issueCopy, checkAchievements, collectionSummary, audit } from '../services.js';

const RUN_ID = /^[A-Za-z0-9-]{8,64}$/;

function parseRun(raw, data) {
  if (!raw || typeof raw !== 'object') throw bad('invalid_run', 'Run must be an object');
  const mode = v.oneOf(raw.mode, 'mode', ['endless', 'adventure']);
  const run = {
    clientRunId: v.str(raw.clientRunId, 'clientRunId', { min: 8, max: 64, pattern: RUN_ID }),
    ticketId: raw.ticketId == null ? null : v.str(raw.ticketId, 'ticketId', { min: 8, max: 64, pattern: RUN_ID }),
    mode,
    levelId: mode === 'adventure' ? v.str(raw.levelId, 'levelId', { max: 8 }) : null,
    score: v.int(raw.score, 'score', { max: 1_000_000 }),
    pipes: v.int(raw.pipes, 'pipes', { max: 1_000_000 }),
    gems: v.int(raw.gems ?? 0, 'gems', { max: 1000 }),
    coinsCollected: v.int(raw.coinsCollected ?? 0, 'coinsCollected', { max: 1_000_000 }),
    flaps: v.int(raw.flaps ?? 0, 'flaps', { max: 10_000_000 }),
    durationMs: v.int(raw.durationMs, 'durationMs', { max: 24 * 3600_000 }),
    distance: v.int(raw.distance ?? 0, 'distance', { max: 100_000_000 }),
    playedAt: v.int(raw.playedAt, 'playedAt', { min: 1_600_000_000_000, max: 4_000_000_000_000 }),
    claimedComplete: raw.completed === true,
    pickups: Array.isArray(raw.pickups) ? raw.pickups : [],
    megabird: raw.megabird == null || raw.megabird === '' ? null : v.str(raw.megabird, 'megabird', { max: 32, pattern: /^[a-z0-9_]+$/ }),
  };
  if (run.pickups.length > 500 || !run.pickups.every((p) => Number.isInteger(p) && p >= 0)) throw bad('invalid_pickups', 'pickups must be pipe indices');
  run.pickups = [...run.pickups].sort((a, b) => a - b);
  if (mode === 'adventure' && !data.levels.has(run.levelId)) throw bad('unknown_level', 'Unknown level');
  return run;
}

export function playerRoutes(r, { db, data, limiter }) {
  r.get('/api/health', 'none', () => ({ ok: true, time: Date.now() }));
  r.get('/api/config', 'none', () => data.publicConfig());
  r.get('/api/catalog', 'none', () => data.publicCatalog());
  r.get('/api/levels', 'none', () => data.levelsFile);

  r.get('/api/me/state', 'user', ({ user }) => accountState(db, data, user.id));

  r.patch('/api/me/profile', 'user', ({ user, body }) => {
    const sets = [];
    const vals = [];
    if (body.bio !== undefined) { sets.push('bio = ?'); vals.push(stripControl(v.str(body.bio, 'bio', { max: 80 }))); }
    if (body.avatar !== undefined) {
      const avatar = v.str(body.avatar, 'avatar', { max: 32 });
      if (!data.cfg.skins.some((s) => s.id === avatar)) throw bad('invalid_avatar', 'Unknown avatar');
      sets.push('avatar = ?'); vals.push(avatar);
    }
    if (body.profileVisibility !== undefined) { sets.push('profile_visibility = ?'); vals.push(v.oneOf(body.profileVisibility, 'profileVisibility', ['public', 'friends', 'private'])); }
    if (body.friendRequests !== undefined) { sets.push('friend_requests = ?'); vals.push(v.oneOf(body.friendRequests, 'friendRequests', ['everyone', 'nobody'])); }
    if (sets.length) db.run(`UPDATE profiles SET ${sets.join(', ')}, updated_at = ? WHERE user_id = ?`, ...vals, Date.now(), user.id);
    return accountState(db, data, user.id);
  });

  r.post('/api/runs/start', 'user', ({ user, body }) => {
    limiter.hit(`ticket:${user.id}`, 200, 3600_000);
    const mode = v.oneOf(body.mode, 'mode', ['endless', 'adventure']);
    const levelId = mode === 'adventure' ? v.str(body.levelId, 'levelId', { max: 8 }) : null;
    if (levelId && !data.levels.has(levelId)) throw bad('unknown_level', 'Unknown level');
    if (levelId && !isLevelUnlocked(db, data, user.id, levelId)) throw forbidden('That level is still locked');
    return issueTicket(db, data, user.id, mode, levelId);
  });

  // Batch submission used by the sync queue. Each run is processed in its own transaction so one
  // bad run cannot block the rest. Results are returned in input order.
  r.post('/api/runs/submit', 'user', ({ user, body }) => {
    limiter.hit(`submit:${user.id}`, 120, 3600_000);
    if (!Array.isArray(body.runs) || body.runs.length === 0 || body.runs.length > 50) throw bad('invalid_runs', 'runs must be an array of 1-50 runs');
    const results = body.runs.map((raw) => {
      try {
        const run = parseRun(raw, data);
        if (run.mode === 'adventure' && !isLevelUnlocked(db, data, user.id, run.levelId)) {
          return { clientRunId: run.clientRunId, status: 'rejected', reason: 'level_locked' };
        }
        return { clientRunId: run.clientRunId, ...db.tx(() => processRun(db, data, user, run)) };
      } catch (e) {
        if (e.status === 400) return { clientRunId: raw?.clientRunId ?? null, status: 'invalid', reason: e.code };
        throw e;
      }
    });
    return { results, state: accountState(db, data, user.id) };
  });

  // One-time import of a device's guest progress into a fresh account. Imported collectibles are
  // account-bound, unverified (not eligible for the completion prize) and never tradeable.
  r.post('/api/me/import-guest', 'user', ({ user, body }) => {
    const items = body.items && typeof body.items === 'object' ? body.items : {};
    const adventure = Array.isArray(body.adventure) ? body.adventure : [];
    const bestScore = v.int(body.bestScore ?? 0, 'bestScore', { max: data.cfg.antiCheat.maxScore });
    const coins = v.int(body.coins ?? 0, 'coins', { max: 10_000_000 });
    return db.tx(() => {
      const p = db.get('SELECT guest_imported FROM profiles WHERE user_id = ?', user.id);
      if (p.guest_imported) throw conflict('already_imported', 'Guest progress was already imported to this account');
      let copies = 0;
      for (const [id, count] of Object.entries(items).slice(0, 200)) {
        if (!data.items.has(id) || !Number.isInteger(count) || count <= 0) continue;
        const n = Math.min(count, 3);
        for (let i = 0; i < n && copies < 300; i++, copies++) issueCopy(db, data, user.id, id, { source: 'guest_import', bound: true, verified: false, originRef: 'guest' });
      }
      let levels = 0;
      const ordered = [...data.levels.values()].sort((a, b) => a.order - b.order);
      const claimed = new Set(adventure.filter((a) => a && a.completed).map((a) => a.levelId));
      // Only accept a contiguous prefix of the campaign, matching normal unlock rules.
      for (const l of ordered) {
        if (!claimed.has(l.id)) break;
        db.run(`INSERT OR IGNORE INTO adventure_progress (user_id, level_id, completed_at) VALUES (?,?,?)`, user.id, l.id, Date.now());
        grantCoins(db, user.id, 0, 'level_clear', l.id); // blocks re-claiming first-clear rewards
        levels++;
      }
      const importedCoins = grantCoins(db, user.id, Math.min(coins, 1000), 'guest_import', 'once');
      db.run('UPDATE profiles SET guest_imported = 1, best_score = MAX(best_score, ?), updated_at = ? WHERE user_id = ?', bestScore, Date.now(), user.id);
      checkAchievements(db, data, user.id);
      audit(db, user.id, 'user.import_guest', 'user', user.id, { copies, levels, coins: importedCoins });
      return { imported: { copies, levels, coins: importedCoins }, state: accountState(db, data, user.id) };
    });
  });

  r.post('/api/shop/buy-skin', 'user', ({ user, body }) => {
    const skinId = v.str(body.skinId, 'skinId', { max: 32 });
    const skin = data.cfg.skins.find((s) => s.id === skinId);
    if (!skin) throw notFound('Unknown skin');
    if (skin.source === 'wish') throw forbidden('This skin can only be won from Hiling ni Tala');
    return db.tx(() => {
      if (skin.price === 0 || db.get('SELECT 1 FROM skins_owned WHERE user_id = ? AND skin_id = ?', user.id, skinId)) throw conflict('already_owned', 'You already own this skin');
      grantCoins(db, user.id, -skin.price, 'skin', skinId);
      db.run('INSERT INTO skins_owned (user_id, skin_id, acquired_at) VALUES (?,?,?)', user.id, skinId, Date.now());
      return accountState(db, data, user.id);
    });
  });

  r.post('/api/shop/equip-skin', 'user', ({ user, body }) => {
    const skinId = v.str(body.skinId, 'skinId', { max: 32 });
    const owned = skinId === 'pakpak' || db.get('SELECT 1 FROM skins_owned WHERE user_id = ? AND skin_id = ?', user.id, skinId);
    if (!owned) throw forbidden('You do not own that skin');
    db.run('UPDATE profiles SET equipped_skin = ?, avatar = ?, updated_at = ? WHERE user_id = ?', skinId, skinId, Date.now(), user.id);
    return accountState(db, data, user.id);
  });

  r.get('/api/leaderboard', 'user', ({ user, query }) => {
    const scope = query.scope === 'friends' ? 'friends' : 'global';
    const friendFilter = scope === 'friends'
      ? `AND (p.user_id = ? OR p.user_id IN (SELECT user_b FROM friendships WHERE user_a = ? UNION SELECT user_a FROM friendships WHERE user_b = ?))`
      : '';
    const params = scope === 'friends' ? [user.id, user.id, user.id] : [];
    const rows = db.all(`SELECT u.id, u.username, p.avatar, p.best_score_verified AS score
                         FROM profiles p JOIN users u ON u.id = p.user_id
                         WHERE p.best_score_verified > 0 AND p.flagged = 0 AND u.status = 'active' ${friendFilter}
                         ORDER BY p.best_score_verified DESC, u.id LIMIT 50`, ...params);
    return { scope, entries: rows.map((r, i) => ({ rank: i + 1, ...r })) };
  });

  r.get('/api/redemptions/eligibility', 'user', ({ user }) => eligibility(db, data, user.id));

  r.post('/api/redemptions', 'user', ({ user, body }) => {
    limiter.hit(`redeem:${user.id}`, 5, 86400_000);
    const contactHandle = stripControl(v.str(body.contactHandle, 'contactHandle', { min: 3, max: 80 }));
    const note = stripControl(v.str(body.note ?? '', 'note', { max: 300 }));
    if (/\b(password|passcode|pin|otp|cvv)\b/i.test(note + ' ' + contactHandle)) {
      throw bad('sensitive_info', 'Do not include passwords, PINs, OTPs or card details. Shan will never ask for them.');
    }
    return db.tx(() => {
      const el = eligibility(db, data, user.id);
      if (!el.eligible) throw forbidden(el.reason);
      const active = db.get("SELECT id FROM redemption_requests WHERE user_id = ? AND status IN ('submitted','verified')", user.id);
      if (active) throw conflict('claim_exists', 'You already have an active claim');
      const res = db.run('INSERT INTO redemption_requests (user_id, contact_handle, note, snapshot, created_at) VALUES (?,?,?,?,?)',
        user.id, contactHandle, note, JSON.stringify(el.snapshot), Date.now());
      audit(db, user.id, 'redemption.submit', 'redemption', res.lastInsertRowid);
      return { id: Number(res.lastInsertRowid), status: 'submitted' };
    });
  });
}

export function isLevelUnlocked(db, data, userId, levelId) {
  const level = data.levels.get(levelId);
  if (!level) return false;
  if (level.order === 0) return true;
  const prev = [...data.levels.values()].find((l) => l.order === level.order - 1);
  return !!db.get('SELECT 1 FROM adventure_progress WHERE user_id = ? AND level_id = ? AND completed_at IS NOT NULL', userId, prev.id);
}

function eligibility(db, data, userId) {
  const col = collectionSummary(db, data, userId);
  const p = db.get('SELECT flagged FROM profiles WHERE user_id = ?', userId);
  const snapshot = { verifiedDistinct: col.verifiedDistinct, total: col.total, at: Date.now() };
  if (p.flagged) return { eligible: false, reason: 'This account has anti-cheat flags and needs a manual review first.', snapshot, missing: col.missing.length };
  if (col.verifiedDistinct < col.total) {
    return { eligible: false, reason: `You still need ${col.total - col.verifiedDistinct} verified collectibles.`, snapshot, missing: col.missing.length };
  }
  return { eligible: true, reason: 'Complete! You can submit a claim.', snapshot, missing: 0 };
}

export function stripControl(s) {
  return s.replace(/[\u0000-\u0008\u000B-\u001F\u007F​-‏‪-‮]/g, '');
}
