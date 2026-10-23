// Loads the shared game data files and exposes the live (admin-editable) config and catalog.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const DATA_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'data');
const readJson = (name) => JSON.parse(readFileSync(join(DATA_DIR, name), 'utf8'));

// Config sections an admin may override. Each override replaces the whole section.
export const EDITABLE_SECTIONS = ['drops', 'economy', 'medals', 'antiCheat', 'notice', 'skins', 'achievements', 'wish', 'megabirds'];

export class GameData {
  constructor(db) {
    this.db = db;
    this.defaults = readJson('config.json');
    this.levelsFile = readJson('levels.json');
    this.levels = new Map(this.levelsFile.levels.map((l) => [l.id, l]));
    this.seedCatalog(readJson('catalog.json'));
    this.reload();
  }

  // Inserts catalog items that are missing. Existing rows are left alone so admin edits survive restarts.
  seedCatalog(catalog) {
    const now = Date.now();
    this.db.tx(() => {
      for (const it of catalog.items) {
        this.db.run(`INSERT OR IGNORE INTO collectibles (id, name, rarity, shape, palette, pattern, description, acquisition,
                       tradeable, in_drop_pool, max_supply, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
          it.id, it.name, it.rarity, it.shape, JSON.stringify(it.palette), it.pattern, it.description,
          JSON.stringify(it.acquisition), it.tradeable ? 1 : 0, it.inDropPool ? 1 : 0, it.maxSupply ?? null, now);
      }
    });
    this.rarities = catalog.rarities;
  }

  reload() {
    const cfg = structuredClone(this.defaults);
    for (const row of this.db.all('SELECT key, value FROM game_config')) {
      if (EDITABLE_SECTIONS.includes(row.key)) cfg[row.key] = JSON.parse(row.value);
    }
    this.cfg = cfg;
    this.loadCatalog();
  }

  loadCatalog() {
    const rows = this.db.all('SELECT * FROM collectibles ORDER BY id');
    this.items = new Map(rows.map((r) => [r.id, r]));
    this.catalogVersion = rows.reduce((m, r) => Math.max(m, r.updated_at), 0);
    this.pool = new Map();
    for (const r of rows) {
      if (!r.in_drop_pool || !r.enabled) continue;
      if (!this.pool.has(r.rarity)) this.pool.set(r.rarity, []);
      this.pool.get(r.rarity).push(r.id);
    }
  }

  get enabledItemCount() {
    let n = 0;
    for (const r of this.items.values()) if (r.enabled) n++;
    return n;
  }

  publicCatalog() {
    return {
      version: this.catalogVersion,
      rarities: this.rarities,
      items: [...this.items.values()].map((r) => ({
        id: r.id, name: r.name, rarity: r.rarity, shape: r.shape, palette: JSON.parse(r.palette), pattern: r.pattern,
        description: r.description, acquisition: JSON.parse(r.acquisition), tradeable: !!r.tradeable,
        inDropPool: !!r.in_drop_pool, maxSupply: r.max_supply, issued: r.issued, enabled: !!r.enabled,
      })),
    };
  }

  publicConfig() {
    const { drops, economy, medals, notice, skins, achievements, antiCheat, wish, megabirds } = this.cfg;
    return { drops, economy, medals, notice, skins, achievements, wish, megabirds, antiCheat: { minMsPerPipe: antiCheat.minMsPerPipe }, catalogVersion: this.catalogVersion };
  }

  setSection(key, value, actorId) {
    this.db.run(`INSERT INTO game_config (key, value, updated_at, updated_by) VALUES (?, ?, ?, ?)
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at, updated_by = excluded.updated_by`,
      key, JSON.stringify(value), Date.now(), actorId);
    this.reload();
  }

  resetSection(key) {
    this.db.run('DELETE FROM game_config WHERE key = ?', key);
    this.reload();
  }
}
