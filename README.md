# PakPak

An original pixel-art flapping game for **Android and iOS** with Endless and Adventure modes, a 133-piece collection book, and an online layer for friends, messages and trading. The online layer runs on a small Node.js backend.

Everything in the game is original: the art, music, sounds, names and levels. None of it is taken from Flappy Bird, and the obstacles are bamboo, palm trunks, cave rock and sky crystals rather than pipes.

```
flappyBird/
├── mobile/    Kotlin Multiplatform app
│   ├── shared/      the whole game (Compose Multiplatform): loop, rendering, screens, saves, sync, API client
│   ├── androidApp/  Android launcher (MainActivity), icons, signing
│   └── iosApp/      iOS launcher (SwiftUI), app icon, XcodeGen project spec
├── site/      public download page (GitHub Pages): Android APK + iPhone options
├── .github/   CI: iOS build on macOS runners, Android + backend tests, releases, download page
├── backend/   Node.js 22 API with zero npm dependencies (built-in node:sqlite, crypto.scrypt, node:http) + admin panel
├── shared/    Generated game data: catalog.json (133 items), levels.json (20 levels), config.json (economy)
├── tools/     gen_data.mjs (game data) and gen_assets.py (icons, splash, sound effects, music)
└── docs/      API.md, ADMIN.md, ECONOMY.md, TESTING.md
```

## Why this stack

- **Kotlin Multiplatform and Compose Multiplatform.** One Kotlin codebase runs on Android and iOS: the game, the screens, saves, sync and networking. Each frame is drawn into a small "virtual pixel" image, about 216 px wide, then scaled up by a whole number, so pixels stay sharp on every screen. Only file, HTTP, network-status and sound hooks are written per platform. See [docs/IOS.md](docs/IOS.md).
- **Node 22 with one dependency:** `@libsql/client`. It talks to a local SQLite file in development or a hosted Turso database in production, with the same SQL either way. Password hashing and HTTP are built into Node. Write transactions are serialized in-process, which keeps trades and reward claims atomic.
- **One generator for the shared data.** The client and server load the same item catalog, levels and drop rates, so they can't disagree.

## Quick start (development)

Requirements: Node 22.13 or newer, JDK 17+ (Android Studio's bundled JBR works), and Android SDK 36. iOS builds need macOS with Xcode, or the included GitHub Actions workflow.

```bash
# 1. Backend
cd backend
cp .env.example .env            # set SERVER_SECRET to a long random string
npm start                       # http://127.0.0.1:8080, admin panel at /admin/
npm run create-admin -- shan    # prints a one-time password + recovery code

# 2. Android
cd ../mobile
cp local.properties.example local.properties   # point sdk.dir + pakpak.apiBaseUrl.debug at your setup
./gradlew :androidApp:assembleDebug            # -> mobile/dist/PakPak-debug.apk
adb install -r dist/PakPak-debug.apk

# 3. iOS: push to GitHub and download the build from Actions, or on a Mac:
cd iosApp && xcodegen generate && open PakPak.xcodeproj   # see docs/IOS.md
```

To test on a **physical phone**, start the backend with `HOST=0.0.0.0` and set `pakpak.apiBaseUrl.debug=http://<your-PC-LAN-IP>:8080` (Android) or `PAKPAK_API_BASE_URL` in `mobile/iosApp/Config/Debug.xcconfig` (iOS). Debug builds allow plain HTTP to local servers. Release builds are HTTPS-only.

When the project sits inside OneDrive, Gradle builds into `%USERPROFILE%\.pakpak-build`, because OneDrive locks build files. Finished APKs are always copied to `mobile/dist/`.

If the server URL is empty, you get an offline-only build: every offline feature works, and the online buttons explain why they're unavailable.

## Release build

```bash
cd mobile
cp keystore.properties.example keystore.properties   # fill in your keystore (see the comment inside)
./gradlew :androidApp:assembleRelease -Ppakpak.apiBaseUrl.release=https://your-domain   # or set it in local.properties
# -> mobile/dist/PakPak-release.apk (unsigned unless a keystore is configured)
./gradlew :androidApp:bundleRelease   # .aab for Google Play
```

The build refuses a release URL that isn't `https://`. No server address or secret is hardcoded in the source.

## Deployment (backend)

**Free hosting:** Render (server) + Turso (database), with a one-click Blueprint in `render.yaml`. Step-by-step: [docs/DEPLOY_FREE.md](docs/DEPLOY_FREE.md).

**Your own server:**

1. Use any small Linux VM with Node 22+. Copy `backend/` there and create `.env` with `NODE_ENV=production`, a strong `SERVER_SECRET`, `HOST=127.0.0.1`, and `TRUST_PROXY=1`.
2. Run it as a service, for example with systemd:
   ```ini
   [Service]
   WorkingDirectory=/opt/pakpak/backend
   ExecStart=/usr/bin/npm start
   Restart=always
   User=pakpak
   ```
3. Put TLS in front. With Caddy, this is the whole config: `pakpak.example.com { reverse_proxy 127.0.0.1:8080 }`.
4. Back up `data/pakpak.db`, for example `sqlite3 data/pakpak.db ".backup backup.db"` on a cron.
5. Create the admin account: `npm run create-admin -- shan`.

In production the server refuses to start if `SERVER_SECRET` is the placeholder. Rate limits are in-memory and assume one process. Migrations in `backend/migrations/` apply automatically at startup.

## Changing game content

- Edit `tools/gen_data.mjs`, then run `node tools/gen_data.mjs`. This rewrites `shared/` and copies the files into the mobile assets (used by Android and iOS) and the backend.
- Run `python tools/gen_assets.py` (needs Pillow) to regenerate the Android and iOS icons, launch screens and all audio.
- Live tuning needs no app release. Drop rates, rewards, medal thresholds and the completion notice are editable in the admin panel, and the app fetches them when it comes online.

## What's implemented

| Area | Details |
|---|---|
| Core gameplay | Tap to flap, gravity, fixed 120 Hz physics, circle-vs-rect collision, scoring, pause/resume (including auto-pause when the app is backgrounded), difficulty ramp, death and results flow |
| Endless | Procedural obstacles, smooth world crossfades every 30 points across 4 themes (with wider "breather" gaps after each change), coins, mystery capsules, distance, best score, Bronze/Silver/Gold/Platinum medals at 10/25/50/100 |
| Adventure | 4 chapters × 5 levels; finish, collect-gems and survive objectives; moving obstacles, spike orbs and wind; 3 stars per level (clear, all gems, flap budget); locked, unlocked, completed and mastered states; replays; first-clear and bonus rewards; mastery items |
| Collectibles | 133 designs (50/30/20/15/10/5/3), all original procedural pixel art; collection book with per-rarity progress and duplicate counts; transparent odds page; configurable weights; depth bonus; no pity timer; optional global supply caps |
| Completion | Progress page, confetti, the Taglish notice (editable from the backend), eligibility and process text, claim submission and status tracking |
| Offline / online | Connectivity detection (Android validated network plus server health checks), status badge, offline run queue with idempotent sync, server wins on conflicts, one-time guest import |
| Accounts | Register, login, logout, logout everywhere, change password, one-time recovery codes (no email or phone collected), scrypt hashes, hashed session tokens, lockout and rate limits |
| Social | Search by username or `PK-XXXXXX` ID, friend requests, presence, profiles with privacy settings, invites, blocking, reports |
| Messaging | Friends-only 1:1 chat, timestamps, history, unread badges, sent/delivered/read status, in-app notification banner, rate limits, automatic masking of phone numbers, emails and links |
| Trading | Friends only, tradeable items only, both sides must offer something, both parties confirm a final summary, atomic execution that rechecks ownership, cancel and decline, history, suspicious-trade flags |
| Economy | Virtual Pak Coins only, daily caps, idempotent ledger, cosmetic skin shop with no gameplay advantage, no real-money purchases |
| Megabirds & Tala Wish | 6 megabirds with powers (shield, revive, magnet, lightning, light wings, double coins), Endless-only and kept off medals/leaderboard; Hiling ni Tala gacha wish with 150-coin tokens, 200-pull hard pity, soft pity, 4★ every 10, 50/50 featured guarantee, published odds, server-side rolls |
| Skins | 17 skins: 12 from the coin shop, 5 exclusive to Tala Wish |
| Admin | Web panel: redemptions (verify, reject or mark fulfilled, with a required reason), reports, flagged trades and runs, collectible editor (tradeable, drop pool, max supply), JSON config editor, notice editor, user suspend and flag, audit log |
| App polish | Launcher plus adaptive icon, Android 12+ splash, chiptune music and 11 sound effects with settings, particles, fades, screen shake, haptics, atomic saves with backup recovery |

## Known limitations (read before release)

- **Client-reported runs.** The server can't replay a run, so it applies plausibility checks: time per obstacle, coins ≤ obstacles, capsule spacing, ticket age, runs per hour, and daily caps. A modified client could still submit fake but plausible scores. Only runs that started online with a server ticket count for the leaderboard, and completion claims are reviewed by hand. Full input-replay verification would be the next step.
- **No push notifications.** Messages are polled every 20 s, or every 4 s while a chat is open, and show an in-app banner. Push would need Firebase credentials, and that's intentionally not set up.
- **No account recovery by email.** Recovery uses the one-time code shown at sign-up. This was chosen to avoid collecting personal data from younger players. If a code is lost, Shan has to verify the player manually.
- **Single-process backend.** The rate limiter is in memory and the database is SQLite. That's fine for thousands of players. For horizontal scaling, move to Postgres and Redis.
- **Not tested on a physical device or emulator in this environment.** The Android 36.1 system image here was an incomplete download. Rendering was verified with real-code screenshots through Robolectric native graphics, and networking with a live client-to-server integration test. See [docs/TESTING.md](docs/TESTING.md) for the on-device checklist.
- **iOS has not been compiled yet.** It can only be built on macOS, so the first GitHub Actions run is its first compile. The iOS-specific code is small (`Platform.ios.kt`, `MainViewController.kt`, `iOSApp.swift`), and any error shows up in the workflow log.

See [docs/DOWNLOAD_PAGE.md](docs/DOWNLOAD_PAGE.md) to put the download page live, [docs/IOS.md](docs/IOS.md) for iOS builds and installs, [docs/ADMIN.md](docs/ADMIN.md) for the redemption and moderation workflow, [docs/API.md](docs/API.md) for the API, and [docs/ECONOMY.md](docs/ECONOMY.md) for drop rates and rewards.
