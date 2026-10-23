# Economy, drops and rules

All values below are defaults from `tools/gen_data.mjs`. They can be tuned live in the admin panel.

## Medals (Endless, best single run)
Bronze 10 · Silver 25 · Gold 50 · Platinum 100. Medals come from skill only.

An automated test plays real Endless runs with a simple autopilot and reaches Platinum on 5 seeds and 4 screen sizes, so the thresholds are achievable. The first time you earn each medal you also get an account-bound collectible.

## Mystery capsules
- They can appear from obstacle 3 onward, with a 12% chance per obstacle and at most one every 6 obstacles.
- Rarity odds per capsule:

  | Common | Uncommon | Elite | Rare | Ultra | Legendary | Celestial |
  |---|---|---|---|---|---|---|
  | 61% | 23% | 9.5% | 4.5% | 1.5% | 0.42% | 0.08% |

- **Depth bonus:** Rare and rarer odds are multiplied by `1 + 0.25 × floor(obstacleIndex / 25)`, capped at ×2. Going further in a run rewards skill.
- There is no pity timer.
- Signed in, capsules are opened **by the server**, using an HMAC of a server secret. The client can't choose or re-roll the result, and offline capsules stay sealed until you sync. As a guest, capsules open locally on the device.

## Coins (virtual Pak Coins, no real-money value)

| Source | Amount | Cap |
|---|---|---|
| Endless coin pickups | 1 each (35% of gaps) | Shared 400/day with milestones |
| Endless score milestones | 10→5, 25→10, 50→20, 100→40, 150→60, 200→100 | Same 400/day |
| Capsules over the 30/day cap | 3 coins each | – |
| Adventure first clear | 30 + 8 × level order (30–182) | Once per level |
| Adventure bonus objectives | 15 each (all gems, flap budget) | Once each |
| Adventure replay | 5 | 100/day |
| Achievements (23) | 10–500 | Once each |

Coins are spent only on cosmetic bird skins (150–1500). Nothing affects scores, drops or trading.

Every coin change goes through `currency_ledger`, where the pair `(user, reason, ref)` is unique, so a reward can never be claimed twice.

## Trading rules
- Friends only, neither player blocked, at most 10 open trades and 8 item types per side, and both sides must offer something (no gifts).
- Account-bound items can't be traded: milestone and mastery rewards, all Celestials, and imported guest items.
- Both players confirm the same final summary. On the second confirmation the server rechecks ownership and moves the copies in one transaction. If an item is gone, the trade fails cleanly, so items are never duplicated or lost.

## Conflict rules (offline vs online)
- The guest profile lives only on the device.
- For an account, the server is the source of truth. Local numbers are optimistic until queued runs are accepted, then they're replaced by the server's state.
- Each run has a client UUID. Retries return the stored result instead of rewarding again.
- Daily caps reset at 00:00 UTC on both the client and the server.

## Hiling ni Tala (lucky spin) and Megabirds

**Tokens:** 1 Bituin Token = 150 Pak Coins = 1 wish. Tokens can only be bought with coins earned by playing. There's no real money anywhere in PakPak.

| Tier | Prize | Odds |
|---|---|---|
| ★5 | A megabird | 0.5% base. From pull 151 the chance rises by 2 percentage points per pull, and pull 200 is guaranteed (hard pity). That averages about 110 pulls. |
| ★4 | 50% a wish-only skin (5, equal chance) / 50% a Rare+ collectible | 5% base, 55% on the 9th pull, guaranteed by the 10th |
| ★3 | 50% coins (30 / 50 / 80) / 50% a collectible | Everything else |

- **50/50:** a ★5 is the featured megabird half the time. If you lose the 50/50, the next ★5 is guaranteed featured. Change the featured bird under `wish.featured` in the admin panel.
- **Collectibles stay fair:** ★3 collectibles use exactly the Endless capsule odds. A ★4 "Rare+" keeps the normal relative odds of Rare, Ultra, Legendary and Celestial, and only the lower tiers are removed.
- **Duplicates:** a megabird you already own becomes 1,500 coins; a skin becomes 300 coins.
- **Server authority:** for accounts, every wish is rolled by the server, which also stores tokens and pity. Pity never resets on its own. Guests roll locally, offline.
- **Odds are shown in the game** (Tala Wish → Odds), including the overall ★5 and ★4 rates and the average pulls to a megabird.

**Megabirds** (Endless only):

| Megabird | Power |
|---|---|
| Haribon | Survives 3 hits; the 4th ends the run |
| Minokawa | Comes back to life once per run, with 2 s of protection |
| Kalaw | Magnet: pulls in nearby coins, gems and capsules |
| Kidlat | Every 15 points, 3 s of flying through obstacles |
| Ulap | Lighter gravity and softer falls |
| Gintong Pipit | Double coins (the daily coin cap still applies) |

Megabird runs earn coins and capsules, under the same daily caps. They don't count for medals, milestone items, the Classic best score or the leaderboard, and they keep their own "Mega best". The server rejects a megabird run if the player doesn't own that bird, or if it's used outside Endless.

## World changes in Endless
Every 30 points the world changes. The old world crossfades into the new one over 2.5 seconds, a banner names the new world, and the first two obstacles of each world have wider, gentler gaps. Screen flashes were removed everywhere because players reported being disoriented by them. Hits and rare prizes now show a soft glow around the screen edge, and screen shake can be turned off in Settings.
