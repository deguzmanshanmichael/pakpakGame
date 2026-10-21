# Admin guide (for Shan)

## Getting in
1. On the server, run `npm run create-admin -- shan`. It creates the account and prints a one-time password and recovery code. If the account already exists, it's promoted to admin instead. The usernames `shan…` and `admin…` are reserved for this script, so nobody can register a look-alike account.
2. Open `https://your-domain/admin/` and sign in, then change your password in the app (Profile → Account).
3. Admin access is checked on every request. A player token gets `403` on every `/api/admin/*` route.

## The cash-prize claim workflow
The prize is a personal promise from you. The game never pays anyone or approves anything automatically.

1. **The player qualifies.** The server checks that their account owns every enabled collectible with *verified* copies: items earned online or through synced runs. Guest imports don't count, and accounts flagged for cheating are excluded.
2. **The player submits a claim** in the app (Collectibles → Complete → Submit claim) with a contact handle they choose. The app warns them never to share passwords, PINs or OTPs, and the server rejects notes that look like they contain them.
3. **You review it** under *Redemptions*. Each claim shows the collection count at submission and right now, plus anti-cheat flags and suspicious-run counts. Also check *Flagged runs*, *Flagged trades* and the *Users* tab for that player.
4. **Decide, with a reason:**
   - **Verify (eligible).** The player sees "Verified as eligible… the prize itself is handled manually by Shan."
   - **Reject.** The player sees your reason and can submit again later.
5. **Pay manually**, outside the game, through a channel you trust. Then mark the claim **Record prize as fulfilled**, again with a note.
6. Every step is recorded in the *Audit log*.

Never ask players for bank passwords or OTPs. Ask only for what the payout method needs, through your own official channel.

## Editing the Taglish notice
Go to *Completion notice* in the admin panel and edit the title, body, eligibility, process and contact text. Apps pick up the change the next time they come online. Put your real, official contact method in **Contact**. The default text tells players to submit an in-app claim.

## Drop rates and economy
*Drop rates & config* has one JSON editor per section. Edits are validated (weights can't be negative, medal thresholds must increase, and so on), take effect immediately on the server, and can be reset to the defaults.

- `drops.weights` are relative, and the panel shows the resulting percentages. Celestial defaults to 8 out of 10000 (0.08%) per capsule.
- To make an item **globally limited**, for example one copy in existence, set its *Max supply* under *Collectibles*. The server enforces this inside the issuing transaction, and sold-out designs fall back to a more common rarity. Serial numbers are recorded per copy.
- Turn off *Tradeable* to make a design account-bound. Turn off *Pool* so it never drops from capsules.

## Moderation
- **Reports**: you see the reported message text (copied at report time), the reporter, and the target. Resolve or dismiss with a note, or suspend the user. Suspending kills their sessions immediately.
- **Flagged trades** are lopsided value, repeated swaps between the same pair, or new accounts trading valuable items. Review them and leave a note.
- **Flagged runs** are submissions that failed plausibility checks, such as too fast or too many capsules. Five rejected runs automatically flag an account, which blocks prize eligibility until you clear it under *Users*.
