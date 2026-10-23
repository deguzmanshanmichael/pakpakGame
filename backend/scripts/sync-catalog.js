// Inserts collectibles from data/catalog.json that are missing in the database (existing rows,
// including admin edits, are kept). Runs automatically at server start; this is for manual use.
import { loadEnv } from '../src/config.js';
import { openDb } from '../src/db.js';
import { GameData } from '../src/gamedata.js';

const env = loadEnv();
const db = await openDb(env.dbPath, env.dbAuthToken);
const data = await GameData.create(db);
console.log(`Catalog has ${data.items.size} collectibles in ${env.dbPath}`);
db.close();
