// Database access through libSQL: a local SQLite file in development/tests, or a hosted Turso
// database in production (DATABASE_URL=libsql://..., DATABASE_AUTH_TOKEN=...). Same SQL either way.
import { createClient } from '@libsql/client';
import { AsyncLocalStorage } from 'node:async_hooks';
import { readdirSync, readFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const MIGRATIONS_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'migrations');

function toUrl(path) {
  if (/^(libsql|https?|wss?|file):/i.test(path)) return path;
  const abs = resolve(path).split('\\').join('/');
  mkdirSync(dirname(abs), { recursive: true });
  return `file:${abs}`;
}

export async function openDb(path, authToken) {
  const url = toUrl(path);
  const client = createClient({ url, authToken: authToken || undefined, intMode: 'number' });
  if (url.startsWith('file:')) {
    await client.execute('PRAGMA journal_mode = WAL');
    await client.execute('PRAGMA busy_timeout = 5000');
  }
  await client.execute('PRAGMA foreign_keys = ON');
  await migrate(client);
  return wrap(client);
}

async function migrate(client) {
  await client.execute('CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at INTEGER NOT NULL)');
  const applied = new Set((await client.execute('SELECT name FROM schema_migrations')).rows.map((r) => r.name));
  const files = readdirSync(MIGRATIONS_DIR).filter((f) => f.endsWith('.sql')).sort();
  for (const f of files) {
    if (applied.has(f)) continue;
    const sql = readFileSync(join(MIGRATIONS_DIR, f), 'utf8');
    try {
      await client.executeMultiple(sql);
      await client.execute({ sql: 'INSERT INTO schema_migrations (name, applied_at) VALUES (?, ?)', args: [f, Date.now()] });
    } catch (e) {
      throw new Error(`migration ${f} failed: ${e.message}`);
    }
  }
}

const toArg = (v) => (v === undefined ? null : typeof v === 'boolean' ? (v ? 1 : 0) : v);
const plain = (rs, row) => (row ? Object.fromEntries(rs.columns.map((c) => [c, row[c]])) : undefined);

/**
 * Async helper around the libSQL client.
 * Transactions: db.tx(async () => { ... }) runs the body in one write transaction. Statements made
 * through `db` inside the body (even in called functions) automatically use that transaction,
 * thanks to AsyncLocalStorage. A process-wide lock runs write transactions one at a time, which is
 * what keeps trades and reward claims atomic.
 */
function wrap(client) {
  const als = new AsyncLocalStorage();
  let chain = Promise.resolve();
  const target = () => als.getStore() ?? client;
  const exec = (sql, params) => target().execute({ sql, args: params.map(toArg) });
  return {
    client,
    async get(sql, ...p) { const rs = await exec(sql, p); return plain(rs, rs.rows[0]); },
    async all(sql, ...p) { const rs = await exec(sql, p); return rs.rows.map((r) => plain(rs, r)); },
    async run(sql, ...p) {
      const rs = await exec(sql, p);
      return { changes: rs.rowsAffected, lastInsertRowid: rs.lastInsertRowid == null ? 0 : Number(rs.lastInsertRowid) };
    },
    exec: (sql) => client.executeMultiple(sql),
    async tx(fn) {
      if (als.getStore()) return fn();
      const run = async () => {
        const tx = await client.transaction('write');
        try {
          const out = await als.run(tx, fn);
          await tx.commit();
          return out;
        } catch (e) {
          await tx.rollback().catch(() => {});
          throw e;
        } finally {
          tx.close();
        }
      };
      const result = chain.then(run, run);
      chain = result.catch(() => {});
      return result;
    },
    close: () => client.close(),
  };
}
