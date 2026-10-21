# PakPak API

Base URL: your server, for example `https://pakpak.example.com`. All bodies are JSON (maximum 64 KB).

Authenticated routes need `Authorization: Bearer <token>`. Tokens are opaque 256-bit values. The server stores only their SHA-256 hash, and they expire after `SESSION_DAYS`.

Errors look like `{ "error": "code", "message": "human text" }` and use these HTTP statuses: 400 validation, 401 not signed in, 403 forbidden, 404 not found, 409 conflict, 413 body too large, 423 account locked, 429 rate limited (the response includes `retryAfterMs`).

## Public
| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | `{ok, time}` |
| GET | `/api/config` | Drop rates, economy, medals, notice, skins, achievements |
| GET | `/api/catalog` | All collectibles with live flags (tradeable, inDropPool, maxSupply, issued) |
| GET | `/api/levels` | Adventure levels |

## Auth
| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | `{username, password}` | Returns `{token, recoveryCode, state}`. The recovery code is shown only once. Limit: 5 per hour per IP |
| POST | `/api/auth/login` | `{username, password}` | `{token, state}`. Locks for 15 minutes after 8 failures |
| POST | `/api/auth/logout` | – | Revokes this session |
| POST | `/api/auth/logout-all` | – | Revokes every session |
| POST | `/api/auth/recover` | `{username, recoveryCode, newPassword}` | Revokes all sessions and returns a new recovery code |
| POST | `/api/auth/change-password` | `{currentPassword, newPassword}` | Revokes other sessions |

Passwords must be 8–128 characters with at least one letter and one number, and can't contain the username. Usernames are `[A-Za-z0-9_]{3,16}`, case-insensitively unique.

## Player
| Method | Path | Notes |
|---|---|---|
| GET | `/api/me/state` | Full account snapshot (profile, stats, collection, adventure, achievements, skins, redemption) |
| PATCH | `/api/me/profile` | `{bio?, avatar?, profileVisibility?: public\|friends\|private, friendRequests?: everyone\|nobody}` |
| POST | `/api/runs/start` | `{mode, levelId?}` → `{ticketId}`. Proves the run started online. Locked levels return 403 |
| POST | `/api/runs/submit` | `{runs:[Run]}` (1–50). Idempotent per `clientRunId`. Returns per-run results plus `state` |
| POST | `/api/me/import-guest` | Once per account. Imported items are bound and unverified |
| POST | `/api/shop/buy-skin` / `/api/shop/equip-skin` | `{skinId}` |
| GET | `/api/leaderboard?scope=global\|friends` | Verified (ticketed) best scores only |
| GET | `/api/redemptions/eligibility` | `{eligible, reason, missing}` |
| POST | `/api/redemptions` | `{contactHandle, note?}`. Rejects passwords, PINs and OTPs. One active claim per account |

`Run` = `{clientRunId, ticketId?, mode: endless|adventure, levelId?, score, pipes, gems, coinsCollected, flaps, durationMs, distance, playedAt, completed, pickups:[pipeIndex]}`

Each run result is `{clientRunId, status: accepted|rejected|invalid, reason?, verified, completed, medal, coins, items:[{collectibleId, serial, rarity, reason}], achievements, overflowCapsules, notes, duplicate?}`.

## Social and messaging (friends only)
| Method | Path |
|---|---|
| GET | `/api/users/search?q=` (username prefix or `PK-XXXXXX`) |
| GET | `/api/users/:id/profile` (respects privacy and blocks) |
| GET | `/api/friends` → `{friends, incoming, outgoing}` |
| POST | `/api/friends/requests` `{userId}` (auto-accepts if they already asked you) |
| POST | `/api/friends/requests/:id/respond` `{accept}` |
| DELETE | `/api/friends/requests/:id` (cancel) · `/api/friends/:id` (unfriend; cancels open trades) |
| POST | `/api/friends/:id/invite` `{activity: challenge\|trade\|adventure}` |
| GET/POST/DELETE | `/api/blocks`, `/api/blocks` `{userId}`, `/api/blocks/:id` |
| POST | `/api/reports` `{userId, category, details?, messageId?}` |
| GET | `/api/conversations` |
| GET | `/api/conversations/:userId/messages?after=&before=` (marks messages as delivered) |
| POST | `/api/messages` `{toUserId, body, clientMsgId?}`. Limits: 20 per minute, 500 per day. Personal info is masked |
| POST | `/api/conversations/:userId/read` |
| GET | `/api/notifications` → unread messages, friend requests, trades awaiting you, latest message |

## Trading
| Method | Path |
|---|---|
| GET | `/api/trades` (open) · `/api/trades?status=history` · `/api/trades/:id` |
| GET | `/api/trades/inventory/:userId` (yourself or a friend: tradeable copies only) |
| POST | `/api/trades` `{toUserId, give:[{collectibleId, qty}], want:[...], note?}` |
| POST | `/api/trades/:id/confirm`: when both parties have confirmed, ownership is rechecked and swapped in one transaction |
| POST | `/api/trades/:id/cancel` (the proposer cancels, the recipient declines) |

## Admin (role = admin)
`GET /api/admin/overview`, `GET|PUT /api/admin/config[/:key]` (`{value}` or `{reset:true}`), `GET /api/admin/collectibles`, `PATCH /api/admin/collectibles/:id`, `GET /api/admin/reports`, `POST /api/admin/reports/:id/resolve`, `GET /api/admin/trades/flagged`, `POST /api/admin/trades/:id/review`, `GET /api/admin/runs/flagged`, `GET /api/admin/redemptions?status=`, `POST /api/admin/redemptions/:id/decision`, `GET /api/admin/users?q=`, `POST /api/admin/users/:id/status`, `POST /api/admin/users/:id/flag`, `GET /api/admin/audit`.

Every admin change writes to `audit_log`.
