-- Hiling ni Tala (lucky spin) and Megabirds.

-- Per-player wish state. Pity counters live on the server so they can't be reset or edited by clients.
CREATE TABLE wish_state (
  user_id              INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  tokens               INTEGER NOT NULL DEFAULT 0 CHECK (tokens >= 0),
  pity5                INTEGER NOT NULL DEFAULT 0,   -- pulls since the last 5-star
  pity4                INTEGER NOT NULL DEFAULT 0,   -- pulls since the last 4-star or better
  guaranteed_featured  INTEGER NOT NULL DEFAULT 0,   -- lost the last 50/50, so the next 5-star is the featured megabird
  total_pulls          INTEGER NOT NULL DEFAULT 0,
  updated_at           INTEGER NOT NULL
);

-- Every pull, for transparency (shown to the player) and auditing. UNIQUE makes retries idempotent.
CREATE TABLE wish_history (
  id              INTEGER PRIMARY KEY,
  user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_wish_id  TEXT    NOT NULL,
  pull_no         INTEGER NOT NULL,
  tier            INTEGER NOT NULL CHECK (tier IN (3, 4, 5)),
  prize_type      TEXT    NOT NULL CHECK (prize_type IN ('megabird', 'skin', 'coins', 'collectible')),
  prize_id        TEXT,
  amount          INTEGER NOT NULL DEFAULT 0,
  duplicate       INTEGER NOT NULL DEFAULT 0,
  featured        INTEGER NOT NULL DEFAULT 0,
  pity_at_pull    INTEGER NOT NULL,
  created_at      INTEGER NOT NULL,
  UNIQUE (user_id, client_wish_id, pull_no)
);
CREATE INDEX idx_wish_history_user ON wish_history(user_id, id);

CREATE TABLE megabirds_owned (
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  megabird_id  TEXT    NOT NULL,
  acquired_at  INTEGER NOT NULL,
  PRIMARY KEY (user_id, megabird_id)
);

-- Megabird runs are tracked separately: they never count for medals, Classic best or the leaderboard.
ALTER TABLE runs ADD COLUMN megabird TEXT;
ALTER TABLE profiles ADD COLUMN best_score_mega INTEGER NOT NULL DEFAULT 0;
