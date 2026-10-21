// Environment configuration. Values come from process.env (optionally loaded from .env by `npm start`).
const PLACEHOLDER_SECRET = 'change-me-to-a-long-random-string';

export function loadEnv(overrides = {}) {
  const env = { ...process.env, ...overrides };
  const cfg = {
    port: Number(env.PORT ?? 8080),
    host: env.HOST ?? '127.0.0.1',
    dbPath: env.DB_PATH ?? './data/pakpak.db',
    secret: env.SERVER_SECRET ?? '',
    trustProxy: env.TRUST_PROXY === '1',
    production: env.NODE_ENV === 'production',
    sessionDays: Number(env.SESSION_DAYS ?? 30),
    quiet: env.QUIET === '1',
    // Multiplies every rate limit. Keep at 1 in production; tests raise it.
    rateLimitScale: Number(env.RATE_LIMIT_SCALE ?? 1),
  };
  if (!cfg.secret || cfg.secret === PLACEHOLDER_SECRET) {
    if (cfg.production) throw new Error('SERVER_SECRET must be set to a long random value in production');
    cfg.secret = cfg.secret || 'dev-only-secret';
  }
  if (cfg.secret.length < 16 && cfg.production) throw new Error('SERVER_SECRET is too short');
  return cfg;
}
