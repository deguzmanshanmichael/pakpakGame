// Hiling ni Tala: pure pull logic shared by the server and the tests.
// Mirrors mobile/shared/.../game/Wish.kt (used for guest/offline wishes).
//
// Like a gacha "wish":
//   5-star (megabird): base rate, soft pity raises the rate every pull from softStart, hard pity guarantees it.
//   4-star (wish skin or Rare+ capsule): guaranteed at least every hardPity pulls.
//   50/50: a 5-star is the featured megabird with featuredChance; losing guarantees the next 5-star is featured.

export function star5Rate(n, c) {
  if (n >= c.hardPity) return 1;
  if (n >= c.softStart) return Math.min(1, c.base + (n - c.softStart + 1) * c.softStep);
  return c.base;
}

export function star4Rate(n, c) {
  if (n >= c.hardPity) return 1;
  if (n >= c.softAt) return Math.min(1, c.base + c.softBonus);
  return c.base;
}

/** Probability-weighted expected number of pulls to get a 5-star, for the published odds. */
export function expectedPullsFor5(c) {
  let reach = 1;
  let expected = 0;
  for (let n = 1; n <= c.hardPity; n++) {
    const p = star5Rate(n, c);
    expected += reach * p * n;
    reach *= 1 - p;
  }
  return expected;
}

function pickWeighted(pairs, u) {
  const total = pairs.reduce((s, [, w]) => s + w, 0);
  let x = u * total;
  for (const [v, w] of pairs) {
    if (x < w) return v;
    x -= w;
  }
  return pairs[pairs.length - 1][0];
}

/**
 * One pull. `state` = { pity5, pity4, guaranteedFeatured }, `rand` returns [0, 1).
 * Returns { tier, type, id?, amount?, featured?, pityAtPull, next } where `next` is the new state.
 * Collectible prizes are returned as type 'collectible' with minRarity; the caller rolls the design
 * using the normal capsule odds, so the wish never changes the collection's published rates.
 */
export function rollPull(state, cfg, megabirds, wishSkins, rand) {
  const n5 = state.pity5 + 1;
  const n4 = state.pity4 + 1;
  if (rand() < star5Rate(n5, cfg.star5)) {
    const featured = cfg.featured;
    const others = megabirds.filter((m) => m !== featured);
    let id;
    let isFeatured;
    if (state.guaranteedFeatured || !others.length || rand() < cfg.star5.featuredChance) {
      id = featured;
      isFeatured = true;
    } else {
      id = others[Math.min(others.length - 1, Math.floor(rand() * others.length))];
      isFeatured = false;
    }
    return {
      tier: 5, type: 'megabird', id, featured: isFeatured, pityAtPull: n5,
      next: { pity5: 0, pity4: 0, guaranteedFeatured: !isFeatured },
    };
  }
  if (rand() < star4Rate(n4, cfg.star4)) {
    const next = { pity5: n5, pity4: 0, guaranteedFeatured: state.guaranteedFeatured };
    if (wishSkins.length && rand() < cfg.star4.skinShare) {
      const id = wishSkins[Math.min(wishSkins.length - 1, Math.floor(rand() * wishSkins.length))];
      return { tier: 4, type: 'skin', id, pityAtPull: n5, next };
    }
    return { tier: 4, type: 'collectible', minRarity: 'rare', pityAtPull: n5, next };
  }
  const next = { pity5: n5, pity4: n4, guaranteedFeatured: state.guaranteedFeatured };
  if (rand() < cfg.star3.coinShare) {
    return { tier: 3, type: 'coins', amount: pickWeighted(cfg.star3.coins, rand()), pityAtPull: n5, next };
  }
  return { tier: 3, type: 'collectible', minRarity: 'common', pityAtPull: n5, next };
}
