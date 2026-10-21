// Admin endpoints. Every route here requires role = 'admin' (enforced by the server dispatcher),
// and every change is written to the audit log.
import { v, bad, notFound } from '../http.js';
import { audit, collectionSummary } from '../services.js';
import { RARITY_ORDER } from '../rules.js';
import { EDITABLE_SECTIONS } from '../gamedata.js';

const num = (x) => typeof x === 'number' && Number.isFinite(x);

function validateSection(key, value) {
  if (!value || typeof value !== 'object') throw bad('invalid_config', 'Config section must be an object or array');
  if (key === 'drops') {
    const w = value.weights;
    if (!w || !RARITY_ORDER.every((r) => num(w[r]) && w[r] >= 0)) throw bad('invalid_config', 'drops.weights needs a non-negative number for every rarity');
    if (!RARITY_ORDER.some((r) => w[r] > 0)) throw bad('invalid_config', 'At least one rarity weight must be positive');
    if (!num(value.capsuleChance) || value.capsuleChance < 0 || value.capsuleChance > 1) throw bad('invalid_config', 'capsuleChance must be 0-1');
    if (!Number.isInteger(value.capsuleMinGap) || value.capsuleMinGap < 1) throw bad('invalid_config', 'capsuleMinGap must be >= 1');
    if (!Number.isInteger(value.capsuleStartPipe) || value.capsuleStartPipe < 0) throw bad('invalid_config', 'capsuleStartPipe must be >= 0');
    const d = value.depthBonus;
    if (!d || !num(d.perPipes) || d.perPipes <= 0 || !num(d.step) || !num(d.max) || d.max < 1 || !RARITY_ORDER.includes(d.fromRarity)) {
      throw bad('invalid_config', 'depthBonus needs perPipes > 0, step, max >= 1 and a valid fromRarity');
    }
  }
  if (key === 'medals') {
    const vals = ['bronze', 'silver', 'gold', 'platinum'].map((m) => value[m]);
    if (!vals.every((x) => Number.isInteger(x) && x > 0) || !vals.every((x, i) => i === 0 || x > vals[i - 1])) {
      throw bad('invalid_config', 'Medal thresholds must be increasing positive integers');
    }
  }
  if (key === 'economy') {
    for (const f of ['dailyEndlessCoinCap', 'dailyCapsuleCap', 'replayReward', 'dailyReplayCap', 'overflowCapsuleCoins']) {
      if (!Number.isInteger(value[f]) || value[f] < 0) throw bad('invalid_config', `economy.${f} must be a non-negative integer`);
    }
    if (!Array.isArray(value.endlessMilestones)) throw bad('invalid_config', 'economy.endlessMilestones must be a list of [score, coins]');
  }
  if (key === 'notice') {
    for (const f of ['title', 'body', 'eligibility', 'process', 'contact']) {
      if (typeof value[f] !== 'string' || value[f].length > 2000) throw bad('invalid_config', `notice.${f} must be text up to 2000 chars`);
    }
  }
  if (key === 'antiCheat') {
    for (const f of ['minMsPerPipe', 'maxRunsPerHour', 'maxScore', 'ticketTtlHours']) {
      if (!Number.isInteger(value[f]) || value[f] <= 0) throw bad('invalid_config', `antiCheat.${f} must be a positive integer`);
    }
  }
  if ((key === 'skins' || key === 'achievements') && !Array.isArray(value)) throw bad('invalid_config', `${key} must be a list`);
}

export function adminRoutes(r, { db, data }) {
  r.get('/api/admin/overview', 'admin', () => ({
    users: db.get('SELECT COUNT(*) AS n FROM users').n,
    activeToday: db.get('SELECT COUNT(*) AS n FROM users WHERE last_seen_at > ?', Date.now() - 86400_000).n,
    runsToday: db.get('SELECT COUNT(*) AS n FROM runs WHERE received_at > ?', Date.now() - 86400_000).n,
    openReports: db.get("SELECT COUNT(*) AS n FROM reports WHERE status = 'open'").n,
    flaggedTrades: db.get('SELECT COUNT(*) AS n FROM trades WHERE flagged = 1 AND reviewed_by IS NULL').n,
    flaggedRuns: db.get('SELECT COUNT(*) AS n FROM runs WHERE flag_reason IS NOT NULL AND received_at > ?', Date.now() - 7 * 86400_000).n,
    pendingRedemptions: db.get("SELECT COUNT(*) AS n FROM redemption_requests WHERE status = 'submitted'").n,
    issuedByRarity: db.all('SELECT rarity, SUM(issued) AS issued FROM collectibles GROUP BY rarity'),
  }));

  r.get('/api/admin/config', 'admin', () => ({
    config: Object.fromEntries(EDITABLE_SECTIONS.map((k) => [k, data.cfg[k]])),
    overridden: db.all('SELECT key, updated_at, updated_by FROM game_config'),
  }));

  r.put('/api/admin/config/:key', 'admin', ({ user, params, body }) => {
    const key = v.oneOf(params.key, 'key', EDITABLE_SECTIONS);
    if (body.reset === true) {
      data.resetSection(key);
      audit(db, user.id, 'config.reset', 'config', key);
      return { [key]: data.cfg[key] };
    }
    validateSection(key, body.value);
    const before = data.cfg[key];
    data.setSection(key, body.value, user.id);
    audit(db, user.id, 'config.update', 'config', key, { before, after: body.value });
    return { [key]: data.cfg[key] };
  });

  r.get('/api/admin/collectibles', 'admin', () => data.publicCatalog());

  r.patch('/api/admin/collectibles/:id', 'admin', ({ user, params, body }) => {
    const id = params.id;
    const row = db.get('SELECT * FROM collectibles WHERE id = ?', id);
    if (!row) throw notFound('Collectible not found');
    const sets = [];
    const vals = [];
    const changes = {};
    const set = (col, val) => { sets.push(`${col} = ?`); vals.push(val); changes[col] = val; };
    if (body.name !== undefined) set('name', v.str(body.name, 'name', { min: 2, max: 40 }));
    if (body.description !== undefined) set('description', v.str(body.description, 'description', { max: 200 }));
    if (body.rarity !== undefined) set('rarity', v.oneOf(body.rarity, 'rarity', RARITY_ORDER));
    if (body.tradeable !== undefined) set('tradeable', body.tradeable ? 1 : 0);
    if (body.inDropPool !== undefined) set('in_drop_pool', body.inDropPool ? 1 : 0);
    if (body.enabled !== undefined) set('enabled', body.enabled ? 1 : 0);
    if (body.maxSupply !== undefined) {
      const ms = body.maxSupply === null ? null : v.int(body.maxSupply, 'maxSupply', { min: 0, max: 1_000_000_000 });
      if (ms !== null && ms < row.issued) throw bad('invalid_maxSupply', `Already issued ${row.issued} copies`);
      set('max_supply', ms);
    }
    if (!sets.length) throw bad('no_changes', 'Nothing to update');
    db.tx(() => {
      db.run(`UPDATE collectibles SET ${sets.join(', ')}, updated_at = ? WHERE id = ?`, ...vals, Date.now(), id);
      audit(db, user.id, 'collectible.update', 'collectible', id, changes);
    });
    data.loadCatalog();
    return data.publicCatalog().items.find((i) => i.id === id);
  });

  r.get('/api/admin/reports', 'admin', ({ query }) => {
    const status = ['open', 'resolved', 'dismissed'].includes(query.status) ? query.status : 'open';
    return {
      reports: db.all(`SELECT r.*, ru.username AS reporter, tu.username AS target FROM reports r
                       JOIN users ru ON ru.id = r.reporter_id LEFT JOIN users tu ON tu.id = r.target_user_id
                       WHERE r.status = ? ORDER BY r.created_at DESC LIMIT 100`, status),
    };
  });

  r.post('/api/admin/reports/:id/resolve', 'admin', ({ user, params, body }) => {
    const id = v.id(params.id, 'id');
    const status = v.oneOf(body.status, 'status', ['resolved', 'dismissed']);
    const resolution = v.str(body.resolution, 'resolution', { min: 3, max: 500 });
    const res = db.run("UPDATE reports SET status = ?, resolution = ?, resolved_by = ?, resolved_at = ? WHERE id = ? AND status = 'open'",
      status, resolution, user.id, Date.now(), id);
    if (!res.changes) throw notFound('Open report not found');
    audit(db, user.id, `report.${status}`, 'report', id, { resolution });
    return { ok: true };
  });

  r.get('/api/admin/trades/flagged', 'admin', ({ query }) => {
    const all = query.all === '1';
    return {
      trades: db.all(`SELECT t.*, p.username AS proposer, rc.username AS recipient FROM trades t
                      JOIN users p ON p.id = t.proposer_id JOIN users rc ON rc.id = t.recipient_id
                      WHERE t.flagged = 1 ${all ? '' : 'AND t.reviewed_by IS NULL'} ORDER BY t.completed_at DESC LIMIT 100`)
        .map((t) => ({ ...t, items: db.all('SELECT side, collectible_id, qty FROM trade_items WHERE trade_id = ?', t.id) })),
    };
  });

  r.post('/api/admin/trades/:id/review', 'admin', ({ user, params, body }) => {
    const id = v.id(params.id, 'id');
    const note = v.str(body.note, 'note', { min: 3, max: 500 });
    const res = db.run('UPDATE trades SET reviewed_by = ?, review_note = ? WHERE id = ? AND flagged = 1', user.id, note, id);
    if (!res.changes) throw notFound('Flagged trade not found');
    audit(db, user.id, 'trade.review', 'trade', id, { note });
    return { ok: true };
  });

  r.get('/api/admin/runs/flagged', 'admin', () => ({
    runs: db.all(`SELECT r.id, r.user_id, u.username, r.mode, r.level_id, r.score, r.pipes, r.duration_ms, r.flag_reason, r.received_at
                  FROM runs r JOIN users u ON u.id = r.user_id WHERE r.flag_reason IS NOT NULL ORDER BY r.received_at DESC LIMIT 100`),
  }));

  r.get('/api/admin/redemptions', 'admin', ({ query }) => {
    const status = ['submitted', 'verified', 'rejected', 'fulfilled'].includes(query.status) ? query.status : 'submitted';
    const rows = db.all(`SELECT r.*, u.username, u.player_code, p.flagged, p.suspicious_runs FROM redemption_requests r
                         JOIN users u ON u.id = r.user_id JOIN profiles p ON p.user_id = r.user_id
                         WHERE r.status = ? ORDER BY r.created_at LIMIT 100`, status);
    return {
      redemptions: rows.map((r) => {
        const col = collectionSummary(db, data, r.user_id);
        return { ...r, snapshot: JSON.parse(r.snapshot), current: { verifiedDistinct: col.verifiedDistinct, total: col.total } };
      }),
    };
  });

  // Verify, reject, or record manual fulfilment. A reason is always required and logged.
  r.post('/api/admin/redemptions/:id/decision', 'admin', ({ user, params, body }) => {
    const id = v.id(params.id, 'id');
    const status = v.oneOf(body.status, 'status', ['verified', 'rejected', 'fulfilled']);
    const reason = v.str(body.reason, 'reason', { min: 3, max: 500 });
    return db.tx(() => {
      const r0 = db.get('SELECT * FROM redemption_requests WHERE id = ?', id);
      if (!r0) throw notFound('Redemption not found');
      const allowed = { submitted: ['verified', 'rejected'], verified: ['fulfilled', 'rejected'] }[r0.status] ?? [];
      if (!allowed.includes(status)) throw bad('invalid_transition', `Cannot move a ${r0.status} claim to ${status}`);
      db.run('UPDATE redemption_requests SET status = ?, reviewer_id = ?, decision_reason = ?, decided_at = ? WHERE id = ?', status, user.id, reason, Date.now(), id);
      audit(db, user.id, `redemption.${status}`, 'redemption', id, { reason });
      return { ok: true, status };
    });
  });

  r.get('/api/admin/users', 'admin', ({ query }) => {
    const q = String(query.q ?? '').trim();
    return {
      users: db.all(`SELECT u.id, u.username, u.player_code, u.role, u.status, u.created_at, u.last_seen_at, p.coins, p.best_score,
                       p.best_score_verified, p.flagged, p.suspicious_runs, p.trades_completed
                     FROM users u JOIN profiles p ON p.user_id = u.id
                     WHERE (? = '' OR u.username LIKE ? OR u.player_code = ?) ORDER BY u.id DESC LIMIT 100`, q, `%${q}%`, q.toUpperCase()),
    };
  });

  r.post('/api/admin/users/:id/status', 'admin', ({ user, params, body }) => {
    const id = v.id(params.id, 'id');
    const status = v.oneOf(body.status, 'status', ['active', 'suspended']);
    const reason = v.str(body.reason, 'reason', { min: 3, max: 500 });
    if (id === user.id) throw bad('self_action', 'You cannot change your own status');
    db.tx(() => {
      db.run('UPDATE users SET status = ? WHERE id = ?', status, id);
      if (status === 'suspended') db.run('DELETE FROM sessions WHERE user_id = ?', id);
      audit(db, user.id, `user.${status}`, 'user', id, { reason });
    });
    return { ok: true };
  });

  r.post('/api/admin/users/:id/flag', 'admin', ({ user, params, body }) => {
    const id = v.id(params.id, 'id');
    const flagged = body.flagged === true;
    const reason = v.str(body.reason, 'reason', { min: 3, max: 500 });
    db.run('UPDATE profiles SET flagged = ?, suspicious_runs = CASE WHEN ? = 0 THEN 0 ELSE suspicious_runs END WHERE user_id = ?', flagged ? 1 : 0, flagged ? 1 : 0, id);
    audit(db, user.id, flagged ? 'user.flag' : 'user.unflag', 'user', id, { reason });
    return { ok: true };
  });

  r.get('/api/admin/audit', 'admin', ({ query }) => {
    const limit = Math.min(500, Math.max(1, Number(query.limit) || 100));
    return {
      entries: db.all(`SELECT a.*, u.username AS actor FROM audit_log a LEFT JOIN users u ON u.id = a.actor_id
                       ORDER BY a.id DESC LIMIT ?`, limit).map((e) => ({ ...e, details: e.details ? JSON.parse(e.details) : null })),
    };
  });
}
