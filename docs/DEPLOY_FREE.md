# Put the online server live for free (Render + Turso)

| Piece | Service | Free tier |
|---|---|---|
| Game server (API, admin panel) | [Render](https://render.com) free web service | 750 hours a month (enough for one service all month), HTTPS included |
| Database (accounts, items, trades, pity) | [Turso](https://turso.tech) | Hosted SQLite, free plan with 5 GB |

**The trade-off:** Render's free server goes to sleep after 15 minutes with no players. The next player waits about a minute while it wakes up, and the game shows "Server waking up" in the meantime. No data is lost, because everything lives in Turso. To remove the sleep, switch the Render service to a paid instance; nothing else changes.

## 1. Create the database (Turso), about 3 minutes
1. Sign up at **turso.tech**. GitHub login works.
2. **Create Database**: name it `pakpak` and pick the location closest to you. For the Philippines, use Singapore, or else Tokyo or Mumbai.
3. Open the database and copy its **URL**. It looks like `libsql://pakpak-yourname.aws-ap-....turso.io`.
4. Create a **token** for it with read and write access, and copy it.

The server creates all of its tables automatically on first start.

## 2. Create the server (Render), about 5 minutes
1. Sign up at **render.com** with GitHub, and allow access to the `pakpakGame` repository.
2. Choose **New → Blueprint**, pick the `pakpakGame` repo, and Render reads `render.yaml`.
3. It asks for two values. Paste the ones from Turso:
   - `DATABASE_URL`: the libsql URL
   - `DATABASE_AUTH_TOKEN`: the token
4. Click **Apply**. The first build takes 2–3 minutes. Your server address appears at the top of the service page, for example `https://pakpak-api.onrender.com`.
5. Check it: opening `https://<your-service>.onrender.com/api/health` should show `{"ok":true,...}`.

`SERVER_SECRET` is generated for you, and `NODE_ENV`, `HOST` and `TRUST_PROXY` are pre-filled.

## 3. Point the apps at the server
Add a GitHub repository variable: **Settings → Secrets and variables → Actions → Variables → New variable**
- Name: `PAKPAK_API_BASE_URL`
- Value: `https://<your-service>.onrender.com` (no trailing slash)

Then publish a release with `git tag vX.Y.Z` and `git push origin vX.Y.Z`. The new Android APK and iPhone build will connect online.

## 4. Create your admin account
Render's free plan has no shell, so run this on your PC. It connects to the same Turso database:

```bash
cd backend
DATABASE_URL="libsql://..." DATABASE_AUTH_TOKEN="..." npm run create-admin -- shan
```

Then open `https://<your-service>.onrender.com/admin/` and sign in. On Windows PowerShell, set the variables first: `$env:DATABASE_URL="..."; $env:DATABASE_AUTH_TOKEN="..."; npm run create-admin -- shan`

### Tester kit for the admin account
Easiest: sign in at `https://<your-service>.onrender.com/admin/`, open **Users**, and press **Give my account the tester kit**. Or from your computer:
It gives 9,999,999 coins, 99,999 wish tokens, every skin, every megabird and one of every collectible. From a terminal, with the same two variables set:

```bash
npm run grant-tester -- shan
```

It only works on admin accounts, only adds what's missing (safe to run again) and is recorded in the audit log. Kit collectibles are untradeable so they stay out of the player economy; add `--tradeable` if you want to test trading with them.

## Notes
- **Updates:** every push to `main` redeploys the server automatically (`autoDeploy: true`).
- **Rate limits** are kept in memory, so they reset when the free server sleeps. That's fine at this size.
- **Backups:** Turso's free plan includes a 1-day point-in-time restore. Its dashboard can also export the database.
- **Secrets:** never commit the Turso token. It only belongs in Render's environment settings and in your local shell when running admin scripts.
