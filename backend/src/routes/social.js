import { v, bad, conflict, forbidden, notFound } from '../http.js';
import { audit, checkAchievements, collectionSummary } from '../services.js';
import { stripControl } from './player.js';

const ONLINE_MS = 2 * 60_000;
const pair = (a, b) => (a < b ? [a, b] : [b, a]);

export const areFriends = (db, a, b) => !!db.get('SELECT 1 FROM friendships WHERE user_a = ? AND user_b = ?', ...pair(a, b));
export const isBlockedEither = (db, a, b) =>
  !!db.get('SELECT 1 FROM blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)', a, b, b, a);

function presence(lastSeen, now = Date.now()) {
  if (!lastSeen) return 'offline';
  if (now - lastSeen < ONLINE_MS) return 'online';
  if (now - lastSeen < 24 * 3600_000) return 'recent';
  return 'offline';
}

// Masks phone numbers, e-mail addresses and links so kids cannot casually share contact details.
export function sanitizeMessage(text) {
  let s = stripControl(text).replace(/\s+/g, (m) => (m.includes('\n') ? '\n' : ' ')).trim();
  s = s.replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, '[hidden]');
  s = s.replace(/(?:\+?\d[\s.()-]*){7,}/g, '[hidden]');
  s = s.replace(/\b(?:https?:\/\/|www\.)\S+/gi, '[link hidden]');
  return s;
}

function userCard(db, row, viewerId) {
  return {
    id: row.id, username: row.username, playerCode: row.player_code, avatar: row.avatar,
    status: presence(row.last_seen_at), friend: viewerId ? areFriends(db, viewerId, row.id) : false,
  };
}

export function socialRoutes(r, { db, data, limiter }) {
  r.get('/api/users/search', 'user', ({ user, query }) => {
    limiter.hit(`search:${user.id}`, 60, 60_000);
    const q = String(query.q ?? '').trim();
    if (q.length < 2 || q.length > 16) throw bad('invalid_query', 'Search needs 2-16 characters');
    const code = q.toUpperCase().startsWith('PK-') ? q.toUpperCase() : null;
    const rows = code
      ? db.all(`SELECT u.id, u.username, u.player_code, u.last_seen_at, p.avatar FROM users u JOIN profiles p ON p.user_id = u.id
                WHERE u.player_code = ? AND u.status = 'active'`, code)
      : db.all(`SELECT u.id, u.username, u.player_code, u.last_seen_at, p.avatar FROM users u JOIN profiles p ON p.user_id = u.id
                WHERE u.username LIKE ? ESCAPE '\\' AND u.status = 'active' AND u.id != ? ORDER BY length(u.username) LIMIT 20`,
        q.replace(/[\\%_]/g, (m) => '\\' + m) + '%', user.id);
    return { users: rows.filter((r) => !isBlockedEither(db, user.id, r.id)).map((r) => userCard(db, r, user.id)) };
  });

  r.get('/api/users/:id/profile', 'user', ({ user, params }) => {
    const id = v.id(params.id, 'id');
    const row = db.get(`SELECT u.id, u.username, u.player_code, u.last_seen_at, u.created_at, p.* FROM users u JOIN profiles p ON p.user_id = u.id
                        WHERE u.id = ? AND u.status = 'active'`, id);
    if (!row || isBlockedEither(db, user.id, id)) throw notFound('Player not found');
    const friend = areFriends(db, user.id, id);
    const self = id === user.id;
    const canView = self || row.profile_visibility === 'public' || (row.profile_visibility === 'friends' && friend);
    const card = userCard(db, row, user.id);
    const pending = db.get("SELECT id, from_id FROM friend_requests WHERE status = 'pending' AND ((from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?))", user.id, id, id, user.id);
    const base = { ...card, pendingRequest: pending ? (pending.from_id === user.id ? 'outgoing' : 'incoming') : null, visible: canView };
    if (!canView) return base;
    const col = collectionSummary(db, data, id);
    return {
      ...base, bio: row.bio, memberSince: row.created_at,
      stats: {
        bestScore: row.best_score, totalRuns: row.total_runs, totalPipes: row.total_pipes,
        medals: { bronze: row.medal_bronze, silver: row.medal_silver, gold: row.medal_gold, platinum: row.medal_platinum },
        adventureCompleted: db.get('SELECT COUNT(*) AS n FROM adventure_progress WHERE user_id = ? AND completed_at IS NOT NULL', id).n,
        collection: col.distinct, collectionTotal: col.total, tradesCompleted: row.trades_completed,
      },
      achievements: db.all('SELECT achievement_id AS id FROM achievements_unlocked WHERE user_id = ?', id).map((a) => a.id),
    };
  });

  r.get('/api/friends', 'user', ({ user }) => {
    const friends = db.all(`SELECT u.id, u.username, u.player_code, u.last_seen_at, p.avatar, p.best_score FROM friendships f
                            JOIN users u ON u.id = CASE WHEN f.user_a = ? THEN f.user_b ELSE f.user_a END
                            JOIN profiles p ON p.user_id = u.id WHERE f.user_a = ? OR f.user_b = ? ORDER BY u.last_seen_at DESC`, user.id, user.id, user.id);
    const incoming = db.all(`SELECT fr.id, fr.created_at, u.id AS user_id, u.username, u.player_code, p.avatar FROM friend_requests fr
                             JOIN users u ON u.id = fr.from_id JOIN profiles p ON p.user_id = u.id
                             WHERE fr.to_id = ? AND fr.status = 'pending' ORDER BY fr.id DESC`, user.id);
    const outgoing = db.all(`SELECT fr.id, fr.created_at, u.id AS user_id, u.username, u.player_code, p.avatar FROM friend_requests fr
                             JOIN users u ON u.id = fr.to_id JOIN profiles p ON p.user_id = u.id
                             WHERE fr.from_id = ? AND fr.status = 'pending' ORDER BY fr.id DESC`, user.id);
    return {
      friends: friends.map((f) => ({ ...userCard(db, f, null), friend: true, bestScore: f.best_score })),
      incoming, outgoing,
    };
  });

  r.post('/api/friends/requests', 'user', ({ user, body }) => {
    limiter.hit(`freq:${user.id}`, 30, 3600_000);
    const to = v.id(body.userId, 'userId');
    if (to === user.id) throw bad('self_request', 'You cannot add yourself, lods');
    return db.tx(() => {
      const target = db.get(`SELECT u.id, p.friend_requests FROM users u JOIN profiles p ON p.user_id = u.id WHERE u.id = ? AND u.status = 'active'`, to);
      if (!target || isBlockedEither(db, user.id, to)) throw notFound('Player not found');
      if (areFriends(db, user.id, to)) throw conflict('already_friends', 'You are already friends');
      // If they already asked us, accept instead of creating a second request.
      const reverse = db.get("SELECT id FROM friend_requests WHERE from_id = ? AND to_id = ? AND status = 'pending'", to, user.id);
      if (reverse) return acceptRequest(db, data, reverse.id, user.id);
      if (target.friend_requests === 'nobody') throw forbidden('This player is not accepting friend requests');
      const pendingCount = db.get("SELECT COUNT(*) AS n FROM friend_requests WHERE from_id = ? AND status = 'pending'", user.id).n;
      if (pendingCount >= 50) throw conflict('too_many_pending', 'You have too many pending requests');
      if (db.get("SELECT 1 FROM friend_requests WHERE from_id = ? AND to_id = ? AND status = 'pending'", user.id, to)) throw conflict('already_requested', 'Request already sent');
      const res = db.run('INSERT INTO friend_requests (from_id, to_id, created_at) VALUES (?,?,?)', user.id, to, Date.now());
      return { requestId: Number(res.lastInsertRowid), status: 'pending' };
    });
  });

  r.post('/api/friends/requests/:id/respond', 'user', ({ user, params, body }) => {
    const id = v.id(params.id, 'id');
    const accept = body.accept === true;
    return db.tx(() => {
      const req = db.get("SELECT * FROM friend_requests WHERE id = ? AND status = 'pending'", id);
      if (!req || req.to_id !== user.id) throw notFound('Request not found');
      if (accept) return acceptRequest(db, data, id, user.id);
      db.run("UPDATE friend_requests SET status = 'rejected', responded_at = ? WHERE id = ?", Date.now(), id);
      return { status: 'rejected' };
    });
  });

  r.delete('/api/friends/requests/:id', 'user', ({ user, params }) => {
    const id = v.id(params.id, 'id');
    const res = db.run("UPDATE friend_requests SET status = 'cancelled', responded_at = ? WHERE id = ? AND from_id = ? AND status = 'pending'", Date.now(), id, user.id);
    if (!res.changes) throw notFound('Request not found');
    return { status: 'cancelled' };
  });

  r.delete('/api/friends/:id', 'user', ({ user, params }) => {
    const id = v.id(params.id, 'id');
    db.tx(() => {
      db.run('DELETE FROM friendships WHERE user_a = ? AND user_b = ?', ...pair(user.id, id));
      cancelOpenTrades(db, user.id, id, 'Friendship removed');
    });
    return { ok: true };
  });

  // Invite a friend to an activity. Delivered as a special message in their inbox.
  r.post('/api/friends/:id/invite', 'user', ({ user, params, body }) => {
    limiter.hit(`invite:${user.id}`, 20, 3600_000);
    const id = v.id(params.id, 'id');
    const activity = v.oneOf(body.activity, 'activity', ['challenge', 'trade', 'adventure']);
    if (!areFriends(db, user.id, id) || isBlockedEither(db, user.id, id)) throw forbidden('You can only invite friends');
    const p = db.get('SELECT best_score FROM profiles WHERE user_id = ?', user.id);
    const text = {
      challenge: `${user.username} challenges you to beat their Endless best of ${p.best_score}!`,
      trade: `${user.username} wants to trade collectibles with you.`,
      adventure: `${user.username} invites you to play Adventure mode together. Race to the Tala Gate!`,
    }[activity];
    return db.tx(() => sendMessage(db, user.id, id, text, 'invite', { activity, score: p.best_score }, null));
  });

  r.get('/api/blocks', 'user', ({ user }) => ({
    blocked: db.all('SELECT u.id, u.username, b.created_at FROM blocks b JOIN users u ON u.id = b.blocked_id WHERE b.blocker_id = ?', user.id),
  }));

  r.post('/api/blocks', 'user', ({ user, body }) => {
    const id = v.id(body.userId, 'userId');
    if (id === user.id) throw bad('self_block', 'You cannot block yourself');
    db.tx(() => {
      db.run('INSERT OR IGNORE INTO blocks (blocker_id, blocked_id, created_at) VALUES (?,?,?)', user.id, id, Date.now());
      db.run('DELETE FROM friendships WHERE user_a = ? AND user_b = ?', ...pair(user.id, id));
      db.run("UPDATE friend_requests SET status = 'cancelled' WHERE status = 'pending' AND ((from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?))", user.id, id, id, user.id);
      cancelOpenTrades(db, user.id, id, 'Player blocked');
    });
    return { ok: true };
  });

  r.delete('/api/blocks/:id', 'user', ({ user, params }) => {
    db.run('DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?', user.id, v.id(params.id, 'id'));
    return { ok: true };
  });

  r.post('/api/reports', 'user', ({ user, body }) => {
    limiter.hit(`report:${user.id}`, 20, 86400_000);
    const targetUserId = v.id(body.userId, 'userId');
    const category = v.oneOf(body.category, 'category', ['harassment', 'spam', 'inappropriate', 'personal_info', 'cheating', 'scam', 'other']);
    const details = stripControl(v.str(body.details ?? '', 'details', { max: 500 }));
    let messageBody = null;
    let messageId = null;
    if (body.messageId != null) {
      messageId = v.id(body.messageId, 'messageId');
      const m = db.get('SELECT body, sender_id, recipient_id FROM messages WHERE id = ?', messageId);
      // Only the recipient of a message can report it, and its text is copied as evidence.
      if (!m || m.recipient_id !== user.id || m.sender_id !== targetUserId) throw notFound('Message not found');
      messageBody = m.body;
    }
    const res = db.run('INSERT INTO reports (reporter_id, target_user_id, message_id, message_body, category, details, created_at) VALUES (?,?,?,?,?,?,?)',
      user.id, targetUserId, messageId, messageBody, category, details, Date.now());
    return { id: Number(res.lastInsertRowid), status: 'open' };
  });

  // ---- Messaging. Only friends can message each other. ----

  r.get('/api/conversations', 'user', ({ user }) => {
    const rows = db.all(`SELECT c.id, c.updated_at, u.id AS other_id, u.username, u.last_seen_at, p.avatar, m.body AS last_body, m.sender_id AS last_sender,
                           m.kind AS last_kind, (SELECT COUNT(*) FROM messages x WHERE x.conversation_id = c.id AND x.recipient_id = ? AND x.read_at IS NULL) AS unread
                         FROM conversations c
                         JOIN users u ON u.id = CASE WHEN c.user_a = ? THEN c.user_b ELSE c.user_a END
                         JOIN profiles p ON p.user_id = u.id
                         LEFT JOIN messages m ON m.id = c.last_message_id
                         WHERE c.user_a = ? OR c.user_b = ? ORDER BY c.updated_at DESC LIMIT 100`, user.id, user.id, user.id, user.id);
    return {
      conversations: rows.filter((r) => !isBlockedEither(db, user.id, r.other_id)).map((r) => ({
        id: r.id, updatedAt: r.updated_at, unread: r.unread, lastMessage: r.last_body, lastFromMe: r.last_sender === user.id,
        user: { id: r.other_id, username: r.username, avatar: r.avatar, status: presence(r.last_seen_at), friend: areFriends(db, user.id, r.other_id) },
      })),
    };
  });

  r.get('/api/conversations/:userId/messages', 'user', ({ user, params, query }) => {
    const other = v.id(params.userId, 'userId');
    const [a, b] = pair(user.id, other);
    const conv = db.get('SELECT id FROM conversations WHERE user_a = ? AND user_b = ?', a, b);
    if (!conv) return { messages: [] };
    const after = Number(query.after ?? 0) || 0;
    const before = Number(query.before ?? 0) || 0;
    const rows = before
      ? db.all('SELECT * FROM messages WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT 50', conv.id, before).reverse()
      : after
        ? db.all('SELECT * FROM messages WHERE conversation_id = ? AND id > ? ORDER BY id LIMIT 100', conv.id, after)
        : db.all('SELECT * FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 50', conv.id).reverse();
    const now = Date.now();
    db.run('UPDATE messages SET delivered_at = ? WHERE conversation_id = ? AND recipient_id = ? AND delivered_at IS NULL', now, conv.id, user.id);
    return {
      messages: rows.map((m) => ({
        id: m.id, fromMe: m.sender_id === user.id, senderId: m.sender_id, kind: m.kind, body: m.body, meta: m.meta ? JSON.parse(m.meta) : null,
        createdAt: m.created_at,
        status: m.sender_id === user.id ? (m.read_at ? 'read' : m.delivered_at ? 'delivered' : 'sent') : undefined,
      })),
    };
  });

  r.post('/api/messages', 'user', ({ user, body }) => {
    limiter.hit(`msg-min:${user.id}`, 20, 60_000);
    limiter.hit(`msg-day:${user.id}`, 500, 86400_000);
    const to = v.id(body.toUserId, 'toUserId');
    const raw = v.str(body.body, 'body', { min: 1, max: 500 });
    const clientMsgId = body.clientMsgId == null ? null : v.str(body.clientMsgId, 'clientMsgId', { min: 8, max: 64, pattern: /^[A-Za-z0-9-]+$/ });
    if (!areFriends(db, user.id, to)) throw forbidden('You can only message friends');
    if (isBlockedEither(db, user.id, to)) throw forbidden('You cannot message this player');
    const text = sanitizeMessage(raw);
    if (!text) throw bad('empty_message', 'Message is empty');
    return db.tx(() => sendMessage(db, user.id, to, text, 'text', null, clientMsgId));
  });

  r.post('/api/conversations/:userId/read', 'user', ({ user, params }) => {
    const other = v.id(params.userId, 'userId');
    const now = Date.now();
    const res = db.run(`UPDATE messages SET read_at = ?, delivered_at = COALESCE(delivered_at, ?)
                        WHERE sender_id = ? AND recipient_id = ? AND read_at IS NULL`, now, now, other, user.id);
    return { marked: res.changes };
  });

  // Polled by the client for badges and in-app notifications.
  r.get('/api/notifications', 'user', ({ user }) => ({
    unreadMessages: db.get('SELECT COUNT(*) AS n FROM messages WHERE recipient_id = ? AND read_at IS NULL', user.id).n,
    friendRequests: db.get("SELECT COUNT(*) AS n FROM friend_requests WHERE to_id = ? AND status = 'pending'", user.id).n,
    tradesAwaiting: db.get(`SELECT COUNT(*) AS n FROM trades WHERE status = 'open' AND
                              ((recipient_id = ? AND recipient_confirmed = 0) OR (proposer_id = ? AND proposer_confirmed = 0))`, user.id, user.id).n,
    latestMessage: db.get(`SELECT m.id, m.body, m.kind, u.username, u.id AS user_id FROM messages m JOIN users u ON u.id = m.sender_id
                           WHERE m.recipient_id = ? AND m.read_at IS NULL ORDER BY m.id DESC LIMIT 1`, user.id) ?? null,
    serverTime: Date.now(),
  }));
}

function acceptRequest(db, data, requestId, userId) {
  const req = db.get('SELECT * FROM friend_requests WHERE id = ?', requestId);
  const now = Date.now();
  db.run("UPDATE friend_requests SET status = 'accepted', responded_at = ? WHERE id = ?", now, requestId);
  db.run('INSERT OR IGNORE INTO friendships (user_a, user_b, created_at) VALUES (?,?,?)', ...pair(req.from_id, req.to_id), now);
  checkAchievements(db, data, req.from_id);
  checkAchievements(db, data, req.to_id);
  return { status: 'accepted', friendId: req.from_id === userId ? req.to_id : req.from_id };
}

export function sendMessage(db, from, to, text, kind, meta, clientMsgId) {
  if (clientMsgId) {
    const dup = db.get('SELECT id, created_at FROM messages WHERE sender_id = ? AND client_msg_id = ?', from, clientMsgId);
    if (dup) return { id: dup.id, createdAt: dup.created_at, status: 'sent', duplicate: true };
  }
  const [a, b] = pair(from, to);
  const now = Date.now();
  db.run('INSERT OR IGNORE INTO conversations (user_a, user_b, updated_at) VALUES (?,?,?)', a, b, now);
  const conv = db.get('SELECT id FROM conversations WHERE user_a = ? AND user_b = ?', a, b);
  const res = db.run('INSERT INTO messages (conversation_id, sender_id, recipient_id, kind, body, meta, client_msg_id, created_at) VALUES (?,?,?,?,?,?,?,?)',
    conv.id, from, to, kind, text, meta ? JSON.stringify(meta) : null, clientMsgId, now);
  const id = Number(res.lastInsertRowid);
  db.run('UPDATE conversations SET last_message_id = ?, updated_at = ? WHERE id = ?', id, now, conv.id);
  return { id, createdAt: now, status: 'sent', body: text };
}

export function cancelOpenTrades(db, a, b, reason) {
  const rows = db.all(`SELECT id FROM trades WHERE status = 'open' AND ((proposer_id = ? AND recipient_id = ?) OR (proposer_id = ? AND recipient_id = ?))`, a, b, b, a);
  for (const t of rows) {
    db.run("UPDATE trades SET status = 'cancelled', fail_reason = ?, cancelled_by = ?, updated_at = ? WHERE id = ?", reason, a, Date.now(), t.id);
    audit(db, a, 'trade.auto_cancel', 'trade', t.id, { reason });
  }
}
