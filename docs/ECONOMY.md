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
