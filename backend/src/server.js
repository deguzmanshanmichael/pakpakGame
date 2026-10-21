import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadEnv } from './config.js';
import { openDb } from './db.js';
import { GameData } from './gamedata.js';
import { Router, HttpError, readJson, sendJson, serveStatic } from './http.js';
import { authenticate, RateLimiter } from './auth.js';
import { authRoutes } from './routes/auth.js';
import { playerRoutes } from './routes/player.js';
import { socialRoutes } from './routes/social.js';
import { tradeRoutes } from './routes/trades.js';
import { adminRoutes } from './routes/admin.js';

const PUBLIC_DIR = resolve(join(dirname(fileURLToPath(import.meta.url)), '..', 'public'));

export function createApp(overrides = {}) {
  const env = loadEnv(overrides);
  const db = openDb(env.dbPath);
  const data = new GameData(db);
  data.secret = env.secret;
  const limiter = new RateLimiter(env.rateLimitScale);
  const ctx = { db, data, env, limiter };
  const router = new Router();
  authRoutes(router, ctx);
  playerRoutes(router, ctx);
  socialRoutes(router, ctx);
  tradeRoutes(router, ctx);
  adminRoutes(router, ctx);
  const sweeper = setInterval(() => {
    limiter.sweep();
    db.run('DELETE FROM sessions WHERE expires_at < ?', Date.now());
  }, 10 * 60_000);
  sweeper.unref();

  const server = createServer(async (req, res) => {
    const started = Date.now();
    const url = new URL(req.url, 'http://localhost');
    const ip = env.trustProxy ? (String(req.headers['x-forwarded-for'] ?? '').split(',')[0].trim() || req.socket.remoteAddress) : req.socket.remoteAddress;
    let status = 200;
    try {
      if (url.pathname === '/' || url.pathname === '/admin') {
        res.writeHead(302, { Location: '/admin/' });
        return res.end();
      }
      if (url.pathname.startsWith('/admin/') && req.method === 'GET') {
        const rel = url.pathname === '/admin/' ? 'admin/index.html' : url.pathname.slice(1);
        if (await serveStatic(res, PUBLIC_DIR, rel)) return;
        throw new HttpError(404, 'not_found', 'Not found');
      }
      const m = router.match(req.method, url.pathname);
      if (!m) throw new HttpError(404, 'not_found', 'No such endpoint');
      if (m.methodNotAllowed) throw new HttpError(405, 'method_not_allowed', 'Method not allowed');
      const { route, params } = m;
      let user = null;
      if (route.auth !== 'none') {
        user = authenticate(db, req.headers.authorization);
        if (!user) throw new HttpError(401, 'unauthenticated', 'Please sign in first');
        if (route.auth === 'admin' && user.role !== 'admin') throw new HttpError(403, 'forbidden', 'Admins only');
      }
      limiter.hit(`global:${ip}`, 600, 60_000);
      const body = await readJson(req);
      const out = await route.handler({ req, params, query: Object.fromEntries(url.searchParams), body, user, ip });
      sendJson(res, 200, out ?? { ok: true });
    } catch (e) {
      if (e instanceof HttpError) {
        status = e.status;
        sendJson(res, e.status, { error: e.code, message: e.message, ...(e.extra ?? {}) });
      } else if (e?.code === 'ERR_SQLITE_ERROR' && /UNIQUE|constraint/i.test(e.message)) {
        status = 409;
        sendJson(res, 409, { error: 'conflict', message: 'That conflicts with existing data' });
      } else {
        status = 500;
        console.error('[error]', req.method, url.pathname, e);
        sendJson(res, 500, { error: 'server_error', message: 'Something went wrong on our side' });
      }
    } finally {
      // Never log headers or bodies: they contain tokens, passwords and messages.
      if (!env.quiet) console.log(`${new Date().toISOString()} ${req.method} ${url.pathname} ${status} ${Date.now() - started}ms`);
    }
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 10_000;
  return { server, db, data, env, close: () => new Promise((r) => { clearInterval(sweeper); server.close(() => { db.close(); r(); }); }) };
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const app = createApp();
  app.server.listen(app.env.port, app.env.host, () => {
    console.log(`PakPak backend listening on http://${app.env.host}:${app.env.port} (db: ${app.env.dbPath})`);
    if (!app.env.production) console.log('Development mode: put HTTPS (e.g. Caddy) in front before going live.');
  });
  const stop = () => app.close().then(() => process.exit(0));
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}
