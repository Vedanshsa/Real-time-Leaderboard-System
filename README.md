# 🏆 Real-time Leaderboard System

> Players sign up, submit scores and watch the rankings move **live**, and the numbers stay correct even when thousands of requests arrive in the same instant.

A backend for competitive games and activities: global and per-game leaderboards, personal rankings, live updates and top-player reports.

This is my solution to the [Real-time Leaderboard System](https://roadmap.sh/projects/realtime-leaderboard-system) project on roadmap.sh.

![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)

---

## ✨ Features

| | Feature | In plain words |
|---|---|---|
| 🔐 | **Accounts** | Register and log in. Every score belongs to a verified player. |
| 🎮 | **Score submission** | Submit scores for any game. A full history is kept for every player. |
| 🌍 | **Global leaderboard** | The top players across all games. |
| ♟️ | **Per-game leaderboards** | A separate board for each game. |
| 📍 | **My ranking** | A player can see their own rank and score, overall or per game. |
| 📈 | **Top players reports** | The best players for a day, a week, a month, or any date range. |
| ⚡ | **Live updates** | Open screens receive the new leaderboard within about half a second of a change. |

## 🧮 How scoring works

| Board | What the score means |
|---|---|
| Per-game | Your **best** score in that game |
| Global | The **sum of your best scores** across games, so improving any game moves you up |
| Reports | **Points earned** during the period, counting every submission |

## 🛡️ Built to stay correct

- **Never loses a score.** It is saved to permanent history before any board changes.
- **Safe to retry.** A submission counts exactly once, even if the same request is repeated after a timeout.
- **All or nothing.** Concurrent requests can never leave a board half-updated.
- **Self-healing.** If the fast ranking store is wiped, every board is rebuilt from the history at startup.

## ⚡ Performance

Load-tested by releasing thousands of requests at the very same moment, then checking every final ranking against what was submitted.

| Scenario | Requests | Failed | Throughput | p95 latency |
|---|---|---|---|---|
| Score submissions, all at once | 10,000 | **0** | 1,770 req/s | 5.5 s |
| Reads and submissions mixed, all at once | 10,000 (7,000 reads + 3,000 writes) | **0** | 3,526 req/s | 2.7 s |

- ✅ **0 ranking mismatches** across 100 players and 3 games.
- The latency figures include every request waiting in the same instant-sized queue.
- Measured on one laptop that also ran the database, the cache and the load generator, so treat these numbers as a **lower bound**.

## 🚀 Getting started

**You need:** JDK 21, Maven 3.9+ and Docker Desktop.

```bash
docker compose up -d      # starts the database and the cache
mvn spring-boot:run       # starts the service on http://localhost:8080
```

Open <http://localhost:8080/actuator/health>. It should report `UP`.

Next, follow the step-by-step examples in the **[API guide](docs/API.md)** to register a player, submit scores and watch the leaderboard update.

### ⚙️ Configuration

Everything has a working local default. Set these environment variables to change it.

| Variable | Default | Purpose |
|---|---|---|
| `JWT_SECRET` | local placeholder | Signing secret for login tokens (32+ characters). **Change it for any real deployment.** |
| `JWT_TTL_MINUTES` | `120` | How long a login lasts |
| `PORT` | `8080` | Port the service listens on |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Docker database | Database connection |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Ranking store connection |

## 🧪 Testing

```bash
mvn test                                                   # unit tests
java loadtest/LoadTest.java http://localhost:8080 100 10000 7000 3000   # load test
```

The load test registers players, fires thousands of simultaneous requests and then verifies **every** score, rank and daily total through the public API. It exits with `PASS` only if nothing failed and all values match.


## 🗺️ What's next

- [ ] Client-supplied idempotency keys so a retried request never creates a duplicate history row
- [ ] Background repair job that re-applies a score if the ranking store is down right after it is saved
- [ ] Integration tests with real containers
- [ ] Database migrations in place of automatic schema updates
- [ ] Rate limiting and anti-cheat checks on score submission

## 📄 License

Released under the [MIT License](LICENSE).

---

<sub>Project idea from roadmap.sh: <https://roadmap.sh/projects/realtime-leaderboard-system></sub>
