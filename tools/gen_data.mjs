// Generates the shared game data used by both the Android client and the backend:
//   shared/catalog.json  - 133 collectible definitions
//   shared/levels.json   - adventure levels
//   shared/config.json   - default drop rates, economy, medals, achievements, skins, notice
// Then copies them into mobile/shared/src/commonMain/assets/data (Android + iOS) and backend/data.
//
// Usage: node tools/gen_data.mjs
import { writeFileSync, mkdirSync, copyFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

const RARITIES = [
  { key: 'common', label: 'Common', count: 50, color: '#9aa5b1' },
  { key: 'uncommon', label: 'Uncommon', count: 30, color: '#5fcf62' },
  { key: 'elite', label: 'Elite', count: 20, color: '#3fa7f0' },
  { key: 'rare', label: 'Rare', count: 15, color: '#b06cf2' },
  { key: 'ultra', label: 'Ultra', count: 10, color: '#f25c9b' },
  { key: 'legendary', label: 'Legendary', count: 5, color: '#ffb52e' },
  { key: 'celestial', label: 'Celestial', count: 3, color: '#7df9ff' },
];

// Shapes are drawn client-side from pixel masks (see Sprites.kt). Order matters for variety.
const SHAPES = ['feather', 'gem', 'star', 'orb', 'shell', 'leaf', 'mask', 'crown', 'key',
  'fish', 'mushroom', 'potion', 'bell', 'ring', 'shard', 'egg', 'flower', 'heart'];

const NOUN = {
  feather: 'Feather', gem: 'Gem', star: 'Star', orb: 'Orb', shell: 'Shell', leaf: 'Leaf',
  mask: 'Mask', crown: 'Crown', key: 'Key', fish: 'Fish', mushroom: 'Kabute', potion: 'Potion',
  bell: 'Bell', ring: 'Ring', shard: 'Shard', egg: 'Egg', flower: 'Bloom', heart: 'Heart',
};

const SHAPE_FLAVOR = {
  feather: 'Nalaglag mula sa ibong sobrang bilis lumipad.',
  gem: 'Cut by wind, polished by rain.',
  star: 'A tiny star that fell asleep in the clouds.',
  orb: 'Swirls when you shake it. Wag masyado.',
  shell: 'You can hear the sea inside. Or is that wifi?',
  leaf: 'Picked from the tallest bamboo in the bukid.',
  mask: 'Worn during the great Pakpak festival.',
  crown: 'Fit for the king of the sky (or a very proud maya).',
  key: 'Opens something. Nobody knows what.',
  fish: 'Lumipad daw ito. Seryoso.',
  mushroom: 'Do not eat. Pang-collection lang.',
  potion: 'Smells like kalamansi and courage.',
  bell: 'Rings once for every perfect flap.',
  ring: 'A loop of pure determination.',
  shard: 'Broken piece of a storm cloud.',
  egg: 'Something is moving inside...',
  flower: 'Blooms only above the clouds.',
  heart: 'For every bird who never gave up.',
};

const RARITY_FLAVOR = {
  common: 'Common find, pero cute pa rin.',
  uncommon: 'Swerte mo today!',
  elite: 'Gilded edges, elite energy.',
  rare: 'It sparkles when nobody is looking.',
  ultra: 'Glows faintly and hums softly.',
};

const RARITY_PREFIX = { common: '', uncommon: 'Lucky ', elite: 'Gilded ', rare: 'Shining ', ultra: 'Radiant ' };

// [outline, base, shade, highlight]
const PALETTES = {
  common: [
    ['Mango', ['#5a3a06', '#ffc93c', '#e89a1c', '#fff2a8']],
    ['Ube', ['#2b1340', '#8e5cc9', '#6a3d9e', '#d7b8ff']],
    ['Kalamansi', ['#1f3b06', '#8fd43a', '#5fa31f', '#e2ffb0']],
    ['Buko', ['#2c3a21', '#f4f1de', '#9cc276', '#ffffff']],
    ['Gulaman', ['#4a1030', '#ff8fc2', '#e0609c', '#ffd6ea']],
    ['Tsokolate', ['#2a140a', '#8b5a3c', '#643c24', '#c9926a']],
    ['Dagat', ['#0b2546', '#2f7fd6', '#1f5aa3', '#9fd3ff']],
    ['Pandan', ['#0f3320', '#3fbf7f', '#2a8a59', '#b3f5d0']],
    ['Langit', ['#16384d', '#7fd1ff', '#4aa6dc', '#e0f6ff']],
    ['Kape', ['#21160e', '#b07b4f', '#7e5333', '#e3bf97']],
  ],
  uncommon: [
    ['Sampaguita', ['#3a3a24', '#fffbe6', '#e7dfb4', '#ffffff']],
    ['Rosas', ['#4a0d18', '#ff4d6d', '#c9304e', '#ffc2cf']],
    ['Bayabas', ['#3b1d13', '#ffa07a', '#7fbf4f', '#ffe0d0']],
    ['Sunog', ['#3d1004', '#ff7b29', '#d4480f', '#ffd09e']],
    ['Ulap', ['#2b3440', '#e6edf5', '#b4c3d4', '#ffffff']],
    ['Abo', ['#1c1c22', '#8a8d99', '#5f626e', '#c9ccd6']],
  ],
  elite: [
    ['Ginto', ['#4a3000', '#ffd23f', '#d9a400', '#fff6c2']],
    ['Pilak', ['#23272e', '#c9d1db', '#8f99a8', '#ffffff']],
    ['Perlas', ['#3d3346', '#f6e9ff', '#d3bfe6', '#ffffff']],
    ['Hade', ['#0a2e22', '#2ecc8f', '#1c9467', '#a6ffd9']],
    ['Narra', ['#2e0e06', '#b9462a', '#86301b', '#f29a7c']],
  ],
  rare: [
    ['Rubi', ['#3d0010', '#ff1f4b', '#b8002c', '#ff9fb4']],
    ['Sapiro', ['#000d3d', '#2953ff', '#1534b8', '#a3b6ff']],
    ['Esmeralda', ['#00331a', '#16d46b', '#0a944a', '#9dffc8']],
    ['Amatista', ['#24003d', '#a64dff', '#7311d1', '#e0bfff']],
    ['Topasyo', ['#3d2400', '#ffad1f', '#d17d00', '#ffe2a8']],
  ],
  ultra: [
    ['Aurora', ['#06283d', '#47ffd1', '#ff5fd2', '#f0fffb']],
    ['Lava', ['#2a0500', '#ff4a1c', '#ffb01f', '#fff0a8']],
    ['Glacier', ['#062a3d', '#9cf0ff', '#4cb8ff', '#ffffff']],
    ['Bagyo', ['#14142e', '#6a6ad9', '#3d3d99', '#d9d9ff']],
    ['Bulaklak', ['#3d0630', '#ff6ad5', '#ffd23f', '#ffe6f7']],
  ],
};

const LEGENDARIES = [
  { name: 'Sarimanok Plume', shape: 'feather', palette: ['#2a0b3d', '#ff3d7f', '#ffd23f', '#3dfcff'],
    description: 'A plume from the legendary Sarimanok. Bringer of good fortune, ayon sa mga lola.' },
  { name: 'Bakunawa Scale', shape: 'shard', palette: ['#0a1f2e', '#3d9cff', '#7dff5c', '#e0fff4'],
    description: 'Shed by the great sea serpent who once tried to swallow the moon.' },
  { name: 'Diwata Crown', shape: 'crown', palette: ['#1a3300', '#9dff3d', '#ffd23f', '#ffffff'],
    description: 'Worn by the forest guardian. Bawal hiramin.' },
  { name: 'Mutya Pearl', shape: 'orb', palette: ['#33001f', '#ffd6f2', '#c08cff', '#ffffff'],
    description: 'A charm said to grant one wish. Wala pang nakakaubos.' },
  { name: 'Agimat Ring', shape: 'ring', palette: ['#2e1a00', '#ffb52e', '#ff4a1c', '#fff6c2'],
    description: 'An amulet ring that makes its owner un-bonkable. Mostly.' },
];

const CELESTIALS = [
  { name: 'Araw ni Bathala', shape: 'sun', palette: ['#4a1a00', '#ffe14d', '#ff8a1f', '#ffffff'],
    description: 'The sun of Bathala himself. Sobrang init, ingat.' },
  { name: 'Buwan ni Mayari', shape: 'moon', palette: ['#0d1033', '#e6ecff', '#9fb2ff', '#ffffff'],
    description: 'Moonlight from Mayari, goddess of the moon. Only 1 in thousands ever see it.' },
  { name: 'Kometa ni Tala', shape: 'comet', palette: ['#001433', '#7df9ff', '#c08cff', '#ffffff'],
    description: 'A comet thrown by Tala, goddess of the stars. Make a wish, lods.' },
];

const PATTERN = { common: 'plain', uncommon: 'stripe', elite: 'spots', rare: 'shine', ultra: 'glow', legendary: 'prism', celestial: 'cosmic' };

function buildCatalog() {
  const items = [];
  let n = 1;
  const id = () => `PK${String(n++).padStart(3, '0')}`;
  const shapeOffset = { common: 0, uncommon: 2, elite: 5, rare: 9, ultra: 13 };
  for (const r of RARITIES) {
    if (r.key === 'legendary' || r.key === 'celestial') continue;
    const pals = PALETTES[r.key];
    const seen = new Set();
    for (let i = 0; i < r.count; i++) {
      const p = i % pals.length;
      const k = Math.floor(i / pals.length);
      const shape = SHAPES[(p + shapeOffset[r.key] + k * 5) % SHAPES.length];
      const key = `${p}:${shape}`;
      if (seen.has(key)) throw new Error(`duplicate combo ${r.key} ${key}`);
      seen.add(key);
      const [palName, colors] = pals[p];
      items.push({
        id: id(), name: `${RARITY_PREFIX[r.key]}${palName} ${NOUN[shape]}`, rarity: r.key,
        shape, palette: colors, pattern: PATTERN[r.key],
        description: `${SHAPE_FLAVOR[shape]} ${RARITY_FLAVOR[r.key]}`,
        acquisition: { type: 'capsule', label: 'Mystery capsules (Endless and Adventure)' },
        tradeable: true, inDropPool: true, maxSupply: null,
      });
    }
  }
  for (const L of LEGENDARIES) {
    items.push({ id: id(), name: L.name, rarity: 'legendary', shape: L.shape, palette: L.palette, pattern: PATTERN.legendary,
      description: L.description, acquisition: { type: 'capsule', label: 'Mystery capsules (very rare)' },
      tradeable: true, inDropPool: true, maxSupply: null });
  }
  for (const C of CELESTIALS) {
    items.push({ id: id(), name: C.name, rarity: 'celestial', shape: C.shape, palette: C.palette, pattern: PATTERN.celestial,
      description: C.description, acquisition: { type: 'capsule', label: 'Mystery capsules (exceptionally rare)' },
      tradeable: false, inDropPool: true, maxSupply: null });
  }
  // Special, non-random acquisition. These are account-bound because they are earned, not found.
  const byRarity = (r) => items.filter((it) => it.rarity === r);
  const special = (it, acquisition) => Object.assign(it, { acquisition, inDropPool: false, tradeable: false });
  const elites = byRarity('elite');
  const rares = byRarity('rare');
  const ultras = byRarity('ultra');
  special(elites[0], { type: 'milestone', key: 'medal_bronze', label: 'Earn your first Bronze medal' });
  special(elites[1], { type: 'milestone', key: 'medal_silver', label: 'Earn your first Silver medal' });
  special(rares[4], { type: 'milestone', key: 'medal_gold', label: 'Earn your first Gold medal' });
  special(ultras[0], { type: 'milestone', key: 'medal_platinum', label: 'Earn your first Platinum medal' });
  for (let c = 1; c <= 4; c++) special(rares[c - 1], { type: 'adventure', level: `${c}-5`, label: `Master Adventure level ${c}-5` });

  const names = new Set(items.map((i) => i.name));
  if (names.size !== items.length) throw new Error('duplicate names');
  if (items.length !== 133) throw new Error(`expected 133 items, got ${items.length}`);
  return { version: 1, rarities: RARITIES, items };
}

const CHAPTERS = [
  { n: 1, name: 'Bukid Meadows', theme: 'meadow', obstacle: 'bamboo',
    levels: ['First Flaps', 'Rice Terraces', 'Carabao Crossing', 'Windmill Way', 'Bamboo Gate'] },
  { n: 2, name: 'Baybay Shores', theme: 'beach', obstacle: 'palm',
    levels: ['Low Tide', 'Coconut Run', 'Bangka Bay', 'Shifting Sands', 'Tidal Tower'] },
  { n: 3, name: 'Kweba Caverns', theme: 'cave', obstacle: 'rock',
    levels: ['Echo Entrance', 'Crystal Drip', 'Spiky Hollow', 'Bat Tunnel', 'Heart of the Cave'] },
  { n: 4, name: 'Bituin Skies', theme: 'sky', obstacle: 'crystal',
    levels: ['Cloud Steps', 'Gusty Heights', 'Starlit Drift', 'Comet Trail', 'Tala Gate'] },
];

function buildLevels() {
  const levels = [];
  let g = 0;
  for (const ch of CHAPTERS) {
    ch.levels.forEach((name, i) => {
      const t = g / 19; // 0..1 across the whole campaign
      const length = 10 + i * 4 + (ch.n - 1) * 3;
      const speed = Math.round(66 + t * 34);
      const gapStart = Math.round(94 - t * 14);
      const gapEnd = Math.round(gapStart - 6 - t * 6);
      const gems = 3 + Math.min(4, Math.floor((i + ch.n) / 2));
      let objective;
      if (i === 1 || i === 3) objective = { type: 'gems', target: Math.max(2, gems - 1), label: `Collect ${Math.max(2, gems - 1)} gems and reach the gate` };
      else if (i === 2 && ch.n >= 2) objective = { type: 'survive', target: 15 + ch.n * 5, label: `Survive ${15 + ch.n * 5} seconds` };
      else objective = { type: 'finish', target: length, label: `Pass all ${length} obstacles` };
      const spacing = 112 - Math.round(t * 8);
      const flapBudget = Math.ceil(length * 2.4 + 8);
      levels.push({
        id: `${ch.n}-${i + 1}`, chapter: ch.n, chapterName: ch.name, index: i + 1, order: g,
        name, theme: ch.theme, obstacle: ch.obstacle, seed: 1000 + g * 7919,
        length, speed, spacing, gapStart, gapEnd,
        moveAmp: ch.n >= 2 ? Math.round(6 + (ch.n - 2) * 4 + i * 2) : 0,
        moveChance: ch.n >= 2 ? Math.min(0.8, 0.25 + (ch.n - 2) * 0.2 + i * 0.05) : 0,
        hazardChance: ch.n >= 3 ? Math.min(0.5, 0.15 + (ch.n - 3) * 0.15 + i * 0.04) : 0,
        wind: ch.n >= 4 ? Math.round(80 + i * 15) : 0,
        gems,
        objective,
        bonus: [
          { type: 'allGems', label: `Collect all ${gems} gems` },
          { type: 'maxFlaps', target: flapBudget, label: `Finish using ${flapBudget} flaps or fewer` },
        ],
        reward: 30 + g * 8,
        bonusReward: 15,
        masteryItem: null,
      });
      g++;
    });
  }
  return { version: 1, chapters: CHAPTERS.map(({ levels, ...c }) => c), levels };
}

function buildConfig(catalog) {
  // Link mastery items to levels.
  return {
    version: 1,
    drops: {
      capsuleChance: 0.12, capsuleStartPipe: 3, capsuleMinGap: 6,
      // Weights out of 10000 per opened capsule.
      weights: { common: 6100, uncommon: 2300, elite: 950, rare: 450, ultra: 150, legendary: 42, celestial: 8 },
      // Rare and rarer weights get multiplied by min(max, 1 + floor(pipeIndex / perPipes) * step).
      depthBonus: { perPipes: 25, step: 0.25, max: 2.0, fromRarity: 'rare' },
      adventureFirstClearCapsule: true,
    },
    economy: {
      currencyName: 'Pak Coins',
      coinChance: 0.35,
      endlessMilestones: [[10, 5], [25, 10], [50, 20], [100, 40], [150, 60], [200, 100]],
      dailyEndlessCoinCap: 400,
      dailyCapsuleCap: 30,
      replayReward: 5,
      dailyReplayCap: 100,
      overflowCapsuleCoins: 3,
    },
    medals: { bronze: 10, silver: 25, gold: 50, platinum: 100 },
    antiCheat: { minMsPerPipe: 700, maxRunsPerHour: 120, maxScore: 5000, ticketTtlHours: 48 },
    skins: [
      { id: 'pakpak', name: 'Pakpak', price: 0, colors: ['#3a2a00', '#ffd23f', '#fff2a8', '#ff8a1f', '#ff5a1f'] },
      { id: 'maya', name: 'Maya', price: 150, colors: ['#2a1608', '#a8693a', '#f2d9b8', '#6e3f1c', '#4a4a4a'] },
      { id: 'kalamansi', name: 'Kalamansi', price: 400, colors: ['#1f3b06', '#8fd43a', '#e2ffb0', '#5fa31f', '#ff8a1f'] },
      { id: 'ube', name: 'Ube', price: 500, colors: ['#2b1340', '#a66cf2', '#e6d1ff', '#6a3d9e', '#ffd23f'] },
      { id: 'kalapati', name: 'Kalapati', price: 300, colors: ['#22262e', '#c9d1db', '#ffffff', '#8f99a8', '#ff7aa8'] },
      { id: 'agila', name: 'Agila', price: 800, colors: ['#1e0f05', '#6e3f1c', '#ffffff', '#3d2010', '#ffd23f'] },
      { id: 'sarimanok', name: 'Sarimanok', price: 1500, colors: ['#2a0b3d', '#ff3d7f', '#3dfcff', '#ffd23f', '#ffd23f'] },
      { id: 'pugo', name: 'Pugo', price: 250, colors: ['#2a1a0a', '#9c7a52', '#e8d3b0', '#5e432a', '#3a3a3a'] },
      { id: 'tikling', name: 'Tikling', price: 350, colors: ['#1d1d14', '#7a6f4a', '#c9c2a0', '#4a4430', '#d94a2a'] },
      { id: 'kulasisi', name: 'Kulasisi', price: 600, colors: ['#0f2a0a', '#3fbf3a', '#d9f25a', '#1f7a2a', '#e8322a'] },
      { id: 'lawin', name: 'Lawin', price: 900, colors: ['#1a120c', '#8a5a34', '#f0e6d6', '#4a2f1a', '#ffcf3a'] },
      { id: 'banog', name: 'Banog', price: 1100, colors: ['#101418', '#5a6470', '#e6ebf0', '#2e353d', '#ffd23f'] },
      // Tala Wish exclusives (★4): never sold for coins.
      { id: 'bahaghari', name: 'Bahaghari', price: 0, source: 'wish', colors: ['#2a0f3d', '#ff5fa2', '#5fe3ff', '#ffd23f', '#7dff5c'] },
      { id: 'hatinggabi', name: 'Hatinggabi', price: 0, source: 'wish', colors: ['#05060f', '#2a2a5a', '#8f7dff', '#141433', '#c9b8ff'] },
      { id: 'sampaguita', name: 'Sampaguita', price: 0, source: 'wish', colors: ['#3a3418', '#fffbe6', '#ffffff', '#ffd23f', '#ffb52e'] },
      { id: 'halohalo', name: 'Halo-Halo', price: 0, source: 'wish', colors: ['#3d1f33', '#c9a2ff', '#ffe6f2', '#ff8fc2', '#ffe14d'] },
      { id: 'jeepney', name: 'Jeepney', price: 0, source: 'wish', colors: ['#1c1c1c', '#c9d1db', '#ffe14d', '#e8322a', '#3d8bff'] },
    ],
    // Megabirds: Endless-only birds with powers, won from Hiling ni Tala (★5).
    megabirds: [
      { id: 'haribon', name: 'Haribon', power: 'shield', params: { hits: 3 },
        powerName: 'Kalasag', desc: 'The mighty Philippine eagle shrugs off 3 hits. Only the 4th one ends the run.',
        colors: ['#1a1008', '#8a5a2e', '#f2e6cc', '#4a2e16', '#3d6bff'], aura: '#ffd23f' },
      { id: 'minokawa', name: 'Minokawa', power: 'revive', params: { count: 1, invulnerable: 2.0 },
        powerName: 'Muling Bangon', desc: 'The giant mythic bird rises again once per run, with 2 seconds of protection.',
        colors: ['#1a0a2e', '#5a2a9c', '#ffd6f2', '#ffd23f', '#ff8a1f'], aura: '#c08cff' },
      { id: 'kalaw', name: 'Kalaw', power: 'magnet', params: { radius: 48 },
        powerName: 'Batobalani', desc: 'The hornbill pulls nearby coins, gems and capsules straight into its beak.',
        colors: ['#141414', '#2a2a2a', '#f0f0f0', '#141414', '#ffb52e'], aura: '#ff4d6d' },
      { id: 'kidlat', name: 'Kidlat', power: 'lightning', params: { every: 15, duration: 3.0 },
        powerName: 'Kidlat', desc: 'Every 15 points, lightning lets it fly through obstacles for 3 seconds.',
        colors: ['#0a1a3d', '#3d8bff', '#e0f0ff', '#ffe14d', '#ffe14d'], aura: '#7df9ff' },
      { id: 'ulap', name: 'Ulap', power: 'float', params: { gravity: 0.78, fall: 0.75, flap: 0.86 },
        powerName: 'Gaan', desc: 'Light as a cloud: gentler gravity and softer falls for precise flying.',
        colors: ['#2b3440', '#f4f8ff', '#ffffff', '#b4c8e6', '#ff9fc2'], aura: '#e0f6ff' },
      { id: 'pipit', name: 'Gintong Pipit', power: 'coins', params: { multiplier: 2 },
        powerName: 'Ginto', desc: 'The golden sparrow doubles every coin it picks up (daily coin cap still applies).',
        colors: ['#4a3000', '#ffd23f', '#fff6c2', '#d9a400', '#ff8a1f'], aura: '#ffd23f' },
    ],
    // Hiling ni Tala ("Tala's Wish"): the lucky spin. Works like a gacha wish with soft and hard pity.
    wish: {
      name: 'Hiling ni Tala',
      tokenName: 'Bituin Token',
      tokenPrice: 150,
      featured: 'haribon',
      star5: { base: 0.005, softStart: 151, softStep: 0.02, hardPity: 200, featuredChance: 0.5 },
      star4: { base: 0.05, softAt: 9, softBonus: 0.5, hardPity: 10, skinShare: 0.5 },
      star3: { coinShare: 0.5, coins: [[30, 50], [50, 35], [80, 15]] },
      duplicateMegabirdCoins: 1500,
      duplicateSkinCoins: 300,
    },
    achievements: [
      { id: 'runs_1', name: 'First Flight', desc: 'Play your first run', type: 'runs', value: 1, reward: 10 },
      { id: 'runs_50', name: 'Frequent Flyer', desc: 'Play 50 runs', type: 'runs', value: 50, reward: 50 },
      { id: 'runs_250', name: 'Sky Addict', desc: 'Play 250 runs', type: 'runs', value: 250, reward: 150 },
      { id: 'pipes_100', name: 'Century Hopper', desc: 'Pass 100 obstacles in total', type: 'pipes', value: 100, reward: 25 },
      { id: 'pipes_1000', name: 'Thousand Gates', desc: 'Pass 1,000 obstacles in total', type: 'pipes', value: 1000, reward: 100 },
      { id: 'pipes_10000', name: 'Endless Wings', desc: 'Pass 10,000 obstacles in total', type: 'pipes', value: 10000, reward: 300 },
      { id: 'medal_bronze', name: 'Bronze Wings', desc: 'Earn a Bronze medal', type: 'medal', value: 'bronze', reward: 20 },
      { id: 'medal_silver', name: 'Silver Wings', desc: 'Earn a Silver medal', type: 'medal', value: 'silver', reward: 40 },
      { id: 'medal_gold', name: 'Golden Wings', desc: 'Earn a Gold medal', type: 'medal', value: 'gold', reward: 80 },
      { id: 'medal_platinum', name: 'Platinum Wings', desc: 'Earn a Platinum medal', type: 'medal', value: 'platinum', reward: 200 },
      { id: 'chapter_1', name: 'Meadow Master', desc: 'Complete Bukid Meadows', type: 'chapter', value: 1, reward: 50 },
      { id: 'chapter_2', name: 'Shore Surfer', desc: 'Complete Baybay Shores', type: 'chapter', value: 2, reward: 75 },
      { id: 'chapter_3', name: 'Cave Diver', desc: 'Complete Kweba Caverns', type: 'chapter', value: 3, reward: 100 },
      { id: 'chapter_4', name: 'Star Chaser', desc: 'Complete Bituin Skies', type: 'chapter', value: 4, reward: 150 },
      { id: 'master_all', name: 'Adventure Legend', desc: 'Master every adventure level', type: 'masterAll', value: 1, reward: 300 },
      { id: 'collect_25', name: 'Collector', desc: 'Discover 25 collectibles', type: 'distinct', value: 25, reward: 40 },
      { id: 'collect_75', name: 'Curator', desc: 'Discover 75 collectibles', type: 'distinct', value: 75, reward: 120 },
      { id: 'collect_133', name: 'Completionist', desc: 'Discover all 133 collectibles', type: 'distinct', value: 133, reward: 500 },
      { id: 'own_rare', name: 'Shiny!', desc: 'Find a Rare or rarer collectible', type: 'rarity', value: 'rare', reward: 30 },
      { id: 'own_legendary', name: 'Alamat', desc: 'Find a Legendary collectible', type: 'rarity', value: 'legendary', reward: 100 },
      { id: 'own_celestial', name: 'Blessed by the Sky', desc: 'Find a Celestial collectible', type: 'rarity', value: 'celestial', reward: 250 },
      { id: 'friend_1', name: 'Barkada', desc: 'Make your first friend', type: 'friends', value: 1, reward: 20 },
      { id: 'trade_1', name: 'Swap Meet', desc: 'Complete your first trade', type: 'trades', value: 1, reward: 20 },
    ],
    notice: {
      title: 'CONGRATS, COMPLETO MO NA LAHAT! \u{1F389}',
      body: 'Sabi ni Shan, ang developer, may cash prize raw kapag nakumpleto mo lahat ng collectibles. Totoo ba? Ewan, boss. HAHAHA!\n\nI-screenshot mo ang collection mo, tapos i-contact si Shan para ma-verify kung qualified ka. Subject to verification, availability, at kung hindi nagtatago si Shan. Bawal dayain ang system, lods!',
      eligibility: 'Eligible: signed-in accounts that own all 133 collectibles, where every design was earned on the server (online play or synced runs). Guest/offline-only items and imported guest items do not count. Accounts with cheating flags are not eligible.',
      process: 'How to claim: 1) Sign in. 2) Open Collectibles > Completion and tap Submit Claim. 3) Shan reviews your account manually. Your claim status will show here. The prize is a personal promise from Shan, not an automatic payment, and nothing is approved until Shan marks it verified. Never share passwords, PINs, OTPs or bank logins with anyone.',
      contact: 'Submit a claim in-app. Shan will reach out through the contact handle you provide.',
    },
    catalogVersion: catalog.version,
  };
}

const catalog = buildCatalog();
const levels = buildLevels();
for (const it of catalog.items) {
  if (it.acquisition.type === 'adventure') levels.levels.find((l) => l.id === it.acquisition.level).masteryItem = it.id;
}
const config = buildConfig(catalog);

const out = { 'catalog.json': catalog, 'levels.json': levels, 'config.json': config };
const targets = [join(root, 'shared'), join(root, 'mobile', 'shared', 'src', 'commonMain', 'assets', 'data'), join(root, 'backend', 'data')];
for (const [name, data] of Object.entries(out)) {
  const src = join(targets[0], name);
  mkdirSync(dirname(src), { recursive: true });
  writeFileSync(src, JSON.stringify(data, null, 2) + '\n');
  for (const t of targets.slice(1)) {
    mkdirSync(t, { recursive: true });
    copyFileSync(src, join(t, name));
  }
}
const counts = Object.fromEntries(RARITIES.map((r) => [r.key, catalog.items.filter((i) => i.rarity === r.key).length]));
console.log('items:', catalog.items.length, counts, 'levels:', levels.levels.length);
