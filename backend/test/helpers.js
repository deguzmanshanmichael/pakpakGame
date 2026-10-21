import { randomUUID } from 'node:crypto';
import { createApp } from '../src/server.js';

export async function startApp(scale = 100) {
  const app = createApp({ DB_PATH: ':memory:', QUIET: '1', SERVER_SECRET: 'test-secret-test-secret', RATE_LIMIT_SCALE: String(scale) });
  await new Promise((r) => app.server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${app.server.address().port}`;
  const call = async (method, path, body, token) => {
    const res = await fetch(base + path, {
      method,
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, body: await res.json() };
  };
  let n = 0;
  const register = async (name = `player${++n}${Math.floor(Math.random() * 1000)}`) => {
    const r = await call('POST', '/api/auth/register', { username: name, password: 'flapflap123' });
    if (r.status !== 200) throw new Error(`register failed: ${JSON.stringify(r.body)}`);
    return { token: r.body.token, id: r.body.state.user.id, username: name, recoveryCode: r.body.recoveryCode };
  };
  return { app, base, call, register };
}

export function endlessRun(score, extra = {}) {
  return {
    clientRunId: randomUUID(), mode: 'endless', score, pipes: score, coinsCollected: 0, flaps: score * 3,
    durationMs: 2000 + score * 1500, distance: score * 110, playedAt: Date.now(), pickups: [], ...extra,
  };
}

export async function makeFriends(call, a, b) {
  const r = await call('POST', '/api/friends/requests', { userId: b.id }, a.token);
  await call('POST', `/api/friends/requests/${r.body.requestId}/respond`, { accept: true }, b.token);
}

// Gives a user tradeable copies directly through the service layer (stands in for lucky capsule drops).
export async function giveItems(app, userId, collectibleId, count, opts = { source: 'capsule' }) {
  const { issueCopy } = await import('../src/services.js');
  app.db.tx(() => { for (let i = 0; i < count; i++) issueCopy(app.db, app.data, userId, collectibleId, opts); });
}
