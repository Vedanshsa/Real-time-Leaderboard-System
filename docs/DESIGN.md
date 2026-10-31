# 🧭 Design notes

Internal notes for the author. Kept out of the README on purpose, so the public repo page stays focused on usage.

## Redis layout

Every leaderboard is a sorted set whose **member is the lower-cased username**.

| Key | Score meaning |
|---|---|
| `lb:global` | Sum of each user's **best** score per game |
| `lb:game:{game}` | User's **best** score in that game |
| `lb:period:{daily\|weekly\|monthly}:{startDate}` | Points earned in that UTC window, all games |
| `lb:period:{...}:{startDate}:{game}` | Same, for one game |

Commands used: `ZADD`, `ZINCRBY`, `ZREVRANGE ... WITHSCORES`, `ZREVRANK`, `ZSCORE`, `ZCARD`.

## Write path (`POST /api/scores`)

1. Insert into `score_entries` (PostgreSQL), committed before touching Redis.
2. ONE call to `scripts/submit_score.lua`, which atomically:
   - sets `lb:applied:{entryId}` (`SET NX`, 24 h). If it already exists the entry was applied before and the rest is skipped, so the call is **idempotent** and safe to retry after a timeout;
   - raises the game best and adds only the **difference** to `lb:global`;
   - `ZINCRBY` + `EXPIRE` on the 3 period windows × (all games, this game);
   - `PUBLISH lb:updates <game>` for the SSE clients;
   - returns the user's game best, game rank, global score and global rank (no extra round trips).
3. On a Redis error the call is retried up to 3 times. If it still fails the API answers `503` ("your score was saved"); the score is in PostgreSQL and the next rebuild picks it up.

Why one script instead of several commands: an earlier version used a Lua script for the best score plus a Redis pipeline for the period windows. The pipeline opens its own connection per call, which failed under ~10,000 simultaneous requests and left requests half-applied (best score updated, request answered 503). A single atomic script removes both problems.

## Ranking rules

- Rank is `ZREVRANK + 1`. Equal scores are ordered by Redis (reverse lexicographic on username), so ties are stable but not "first to reach the score wins".
- Global score rewards breadth: improving your best in any game raises it. Swap to plain `ZINCRBY` of every submission if you want a cumulative total instead.

## Reports

- Day / week / month windows are answered from the period sorted sets while the key exists, else from `score_entries` (`"source"` in the response says which).
- Custom `from`/`to` ranges (max 366 days) always use SQL, summing scores in the range.
- Weeks start Monday, all windows are UTC.

## Live updates

Redis pub/sub → `LeaderboardStreamService` marks the game dirty → a 500 ms scheduler sends one coalesced snapshot per subscriber. A 25 s heartbeat keeps proxies from closing idle streams.

## Recovery

Redis is derived data. If `lb:global` is missing at startup, `LeaderboardRebuilder` deletes `lb:*` and rebuilds best scores plus the current day/week/month windows from `score_entries`.
A failure between the DB commit and the Redis update returns an error to the client but never loses the score.

## Known limits / next steps

- `ddl-auto: update` → move to Flyway migrations.
- No rate limiting or anti-cheat on score submission (add per-user limits, server-side validation).
- A client that retries a request that returned an error creates a second history row (Redis is protected per entry, the history is not); add a client-supplied idempotency key.
- If Redis stays down after the database insert, that score is only reflected after a rebuild; a background sweeper that re-applies recent entries (safe because the script is idempotent) would close this.
- Add integration tests with Testcontainers (Redis + PostgreSQL).
