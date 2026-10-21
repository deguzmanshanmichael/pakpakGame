-- PakPak backend schema. Timestamps are Unix epoch milliseconds (UTC).

CREATE TABLE users (
  id              INTEGER PRIMARY KEY,
  username        TEXT    NOT NULL UNIQUE COLLATE NOCASE,
  player_code     TEXT    NOT NULL UNIQUE,
  password_hash   TEXT    NOT NULL,
  recovery_hash   TEXT    NOT NULL,
  role            TEXT    NOT NULL DEFAULT 'player' CHECK (role IN ('player', 'admin')),
  status          TEXT    NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
  failed_logins   INTEGER NOT NULL DEFAULT 0,
  locked_until    INTEGER,
  created_at      INTEGER NOT NULL,
  last_seen_at    INTEGER
);

CREATE TABLE sessions (
  token_hash  TEXT    PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX idx_sessions_user ON sessions(user_id);

CREATE TABLE profiles (
  user_id             INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  avatar              TEXT    NOT NULL DEFAULT 'pakpak',
  bio                 TEXT    NOT NULL DEFAULT '',
  equipped_skin       TEXT    NOT NULL DEFAULT 'pakpak',
  profile_visibility  TEXT    NOT NULL DEFAULT 'friends' CHECK (profile_visibility IN ('public', 'friends', 'private')),
  friend_requests     TEXT    NOT NULL DEFAULT 'everyone' CHECK (friend_requests IN ('everyone', 'nobody')),
  coins               INTEGER NOT NULL DEFAULT 0 CHECK (coins >= 0),
  best_score          INTEGER NOT NULL DEFAULT 0,
  best_score_verified INTEGER NOT NULL DEFAULT 0,
  total_runs          INTEGER NOT NULL DEFAULT 0,
  total_pipes         INTEGER NOT NULL DEFAULT 0,
  total_distance      INTEGER NOT NULL DEFAULT 0,
  total_flaps         INTEGER NOT NULL DEFAULT 0,
  medal_bronze        INTEGER NOT NULL DEFAULT 0,
  medal_silver        INTEGER NOT NULL DEFAULT 0,
  medal_gold          INTEGER NOT NULL DEFAULT 0,
  medal_platinum      INTEGER NOT NULL DEFAULT 0,
  trades_completed    INTEGER NOT NULL DEFAULT 0,
  guest_imported      INTEGER NOT NULL DEFAULT 0,
  suspicious_runs     INTEGER NOT NULL DEFAULT 0,
  flagged             INTEGER NOT NULL DEFAULT 0,
  updated_at          INTEGER NOT NULL
);

CREATE TABLE collectibles (
  id            TEXT    PRIMARY KEY,
  name          TEXT    NOT NULL UNIQUE,
  rarity        TEXT    NOT NULL CHECK (rarity IN ('common','uncommon','elite','rare','ultra','legendary','celestial')),
  shape         TEXT    NOT NULL,
  palette       TEXT    NOT NULL,
  pattern       TEXT    NOT NULL,
  description   TEXT    NOT NULL,
  acquisition   TEXT    NOT NULL,
  tradeable     INTEGER NOT NULL DEFAULT 1,
  in_drop_pool  INTEGER NOT NULL DEFAULT 1,
  max_supply    INTEGER,
  issued        INTEGER NOT NULL DEFAULT 0,
  enabled       INTEGER NOT NULL DEFAULT 1,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX idx_collectibles_rarity ON collectibles(rarity, in_drop_pool, enabled);

-- Every owned copy is its own row with a serial number, so duplicates, trades and supply caps are auditable.
CREATE TABLE item_copies (
  id              INTEGER PRIMARY KEY,
  collectible_id  TEXT    NOT NULL REFERENCES collectibles(id),
  owner_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  serial          INTEGER NOT NULL,
  source          TEXT    NOT NULL CHECK (source IN ('capsule','adventure','milestone','guest_import','admin')),
  bound           INTEGER NOT NULL DEFAULT 0,
  verified        INTEGER NOT NULL DEFAULT 1,
  origin_ref      TEXT,
  acquired_at     INTEGER NOT NULL,
  UNIQUE (collectible_id, serial)
);
CREATE INDEX idx_copies_owner ON item_copies(owner_id, collectible_id);
-- Earned-once rewards (milestones, mastery) can only be issued once per user.
CREATE UNIQUE INDEX idx_copies_once ON item_copies(owner_id, origin_ref) WHERE source IN ('milestone', 'adventure');

-- Every currency change. UNIQUE(user_id, reason, ref) makes reward claims idempotent.
CREATE TABLE currency_ledger (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  delta       INTEGER NOT NULL,
  balance     INTEGER NOT NULL,
  reason      TEXT    NOT NULL,
  ref         TEXT    NOT NULL,
  created_at  INTEGER NOT NULL,
  UNIQUE (user_id, reason, ref)
);

CREATE TABLE run_tickets (
  id          TEXT    PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  mode        TEXT    NOT NULL,
  level_id    TEXT,
  issued_at   INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  used        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_tickets_user ON run_tickets(user_id, issued_at);

CREATE TABLE runs (
  id               INTEGER PRIMARY KEY,
  user_id          INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_run_id    TEXT    NOT NULL,
  ticket_id        TEXT,
  mode             TEXT    NOT NULL CHECK (mode IN ('endless', 'adventure')),
  level_id         TEXT,
  score            INTEGER NOT NULL,
  pipes            INTEGER NOT NULL,
  gems             INTEGER NOT NULL DEFAULT 0,
  coins_collected  INTEGER NOT NULL DEFAULT 0,
  flaps            INTEGER NOT NULL DEFAULT 0,
  duration_ms      INTEGER NOT NULL,
  completed        INTEGER NOT NULL DEFAULT 0,
  verified         INTEGER NOT NULL DEFAULT 0,
  medal            TEXT,
  coins_awarded    INTEGER NOT NULL DEFAULT 0,
  capsules_awarded INTEGER NOT NULL DEFAULT 0,
  flag_reason      TEXT,
  result           TEXT,
  played_at        INTEGER NOT NULL,
  received_at      INTEGER NOT NULL,
  UNIQUE (user_id, client_run_id)
);
CREATE INDEX idx_runs_leaderboard ON runs(mode, verified, score DESC);
CREATE INDEX idx_runs_user_time ON runs(user_id, received_at);
CREATE INDEX idx_runs_flagged ON runs(flag_reason) WHERE flag_reason IS NOT NULL;

CREATE TABLE adventure_progress (
  user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  level_id       TEXT    NOT NULL,
  completed_at   INTEGER,
  best_gems      INTEGER NOT NULL DEFAULT 0,
  best_flaps     INTEGER,
  bonus_all_gems INTEGER NOT NULL DEFAULT 0,
  bonus_flaps    INTEGER NOT NULL DEFAULT 0,
  mastered_at    INTEGER,
  PRIMARY KEY (user_id, level_id)
);

CREATE TABLE achievements_unlocked (
  user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  achievement_id  TEXT    NOT NULL,
  unlocked_at     INTEGER NOT NULL,
  PRIMARY KEY (user_id, achievement_id)
);

CREATE TABLE skins_owned (
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  skin_id      TEXT    NOT NULL,
  acquired_at  INTEGER NOT NULL,
  PRIMARY KEY (user_id, skin_id)
);

CREATE TABLE daily_counters (
  user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day           TEXT    NOT NULL,
  endless_coins INTEGER NOT NULL DEFAULT 0,
  capsules      INTEGER NOT NULL DEFAULT 0,
  replay_coins  INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day)
);

CREATE TABLE friend_requests (
  id            INTEGER PRIMARY KEY,
  from_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  to_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status        TEXT    NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','accepted','rejected','cancelled')),
  created_at    INTEGER NOT NULL,
  responded_at  INTEGER
);
CREATE UNIQUE INDEX idx_friend_req_pending ON friend_requests(from_id, to_id) WHERE status = 'pending';
CREATE INDEX idx_friend_req_to ON friend_requests(to_id, status);

CREATE TABLE friendships (
  user_a      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  user_b      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (user_a, user_b),
  CHECK (user_a < user_b)
);
CREATE INDEX idx_friendships_b ON friendships(user_b);

CREATE TABLE blocks (
  blocker_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (blocker_id, blocked_id)
);

CREATE TABLE conversations (
  id               INTEGER PRIMARY KEY,
  user_a           INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  user_b           INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  last_message_id  INTEGER,
  updated_at       INTEGER NOT NULL,
  UNIQUE (user_a, user_b),
  CHECK (user_a < user_b)
);
CREATE INDEX idx_conversations_b ON conversations(user_b);

CREATE TABLE messages (
  id               INTEGER PRIMARY KEY,
  conversation_id  INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  sender_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind             TEXT    NOT NULL DEFAULT 'text' CHECK (kind IN ('text', 'invite')),
  body             TEXT    NOT NULL,
  meta             TEXT,
  client_msg_id    TEXT,
  created_at       INTEGER NOT NULL,
  delivered_at     INTEGER,
  read_at          INTEGER,
  UNIQUE (sender_id, client_msg_id)
);
CREATE INDEX idx_messages_conv ON messages(conversation_id, id);
CREATE INDEX idx_messages_unread ON messages(recipient_id, read_at);

CREATE TABLE reports (
  id              INTEGER PRIMARY KEY,
  reporter_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_user_id  INTEGER REFERENCES users(id) ON DELETE CASCADE,
  message_id      INTEGER REFERENCES messages(id) ON DELETE SET NULL,
  message_body    TEXT,
  category        TEXT    NOT NULL,
  details         TEXT    NOT NULL DEFAULT '',
  status          TEXT    NOT NULL DEFAULT 'open' CHECK (status IN ('open','resolved','dismissed')),
  resolution      TEXT,
  resolved_by     INTEGER REFERENCES users(id),
  created_at      INTEGER NOT NULL,
  resolved_at     INTEGER
);
CREATE INDEX idx_reports_status ON reports(status, created_at);

CREATE TABLE trades (
  id                   INTEGER PRIMARY KEY,
  proposer_id          INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status               TEXT    NOT NULL DEFAULT 'open' CHECK (status IN ('open','completed','cancelled','declined','failed')),
  proposer_confirmed   INTEGER NOT NULL DEFAULT 0,
  recipient_confirmed  INTEGER NOT NULL DEFAULT 0,
  note                 TEXT    NOT NULL DEFAULT '',
  fail_reason          TEXT,
  cancelled_by         INTEGER,
  flagged              INTEGER NOT NULL DEFAULT 0,
  flag_reason          TEXT,
  reviewed_by          INTEGER,
  review_note          TEXT,
  created_at           INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL,
  completed_at         INTEGER
);
CREATE INDEX idx_trades_proposer ON trades(proposer_id, status);
CREATE INDEX idx_trades_recipient ON trades(recipient_id, status);
CREATE INDEX idx_trades_flagged ON trades(flagged) WHERE flagged = 1;

CREATE TABLE trade_items (
  trade_id        INTEGER NOT NULL REFERENCES trades(id) ON DELETE CASCADE,
  side            TEXT    NOT NULL CHECK (side IN ('proposer', 'recipient')),
  collectible_id  TEXT    NOT NULL REFERENCES collectibles(id),
  qty             INTEGER NOT NULL CHECK (qty > 0),
  PRIMARY KEY (trade_id, side, collectible_id)
);

CREATE TABLE trade_transfers (
  trade_id  INTEGER NOT NULL REFERENCES trades(id) ON DELETE CASCADE,
  copy_id   INTEGER NOT NULL REFERENCES item_copies(id),
  from_id   INTEGER NOT NULL,
  to_id     INTEGER NOT NULL,
  PRIMARY KEY (trade_id, copy_id)
);

CREATE TABLE redemption_requests (
  id               INTEGER PRIMARY KEY,
  user_id          INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status           TEXT    NOT NULL DEFAULT 'submitted' CHECK (status IN ('submitted','verified','rejected','fulfilled')),
  contact_handle   TEXT    NOT NULL,
  note             TEXT    NOT NULL DEFAULT '',
  snapshot         TEXT    NOT NULL,
  reviewer_id      INTEGER REFERENCES users(id),
  decision_reason  TEXT,
  created_at       INTEGER NOT NULL,
  decided_at       INTEGER
);
CREATE UNIQUE INDEX idx_redemption_active ON redemption_requests(user_id) WHERE status IN ('submitted', 'verified');

CREATE TABLE game_config (
  key         TEXT    PRIMARY KEY,
  value       TEXT    NOT NULL,
  updated_at  INTEGER NOT NULL,
  updated_by  INTEGER
);

CREATE TABLE audit_log (
  id           INTEGER PRIMARY KEY,
  actor_id     INTEGER,
  action       TEXT    NOT NULL,
  target_type  TEXT,
  target_id    TEXT,
  details      TEXT,
  created_at   INTEGER NOT NULL
);
CREATE INDEX idx_audit_time ON audit_log(created_at);
