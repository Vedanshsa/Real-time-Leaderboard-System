import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concurrency load test + correctness check for the leaderboard service.
 *
 * Run (JDK 21, no build step):
 *   java loadtest/LoadTest.java [baseUrl] [users] [writes] [mixedReads] [mixedWrites]
 *   java loadtest/LoadTest.java http://localhost:8080 100 10000 7000 3000
 *
 * Phase A: `writes` score submissions released at the same instant.
 * Phase B: `mixedReads` reads + `mixedWrites` writes released at the same instant.
 * Then the final rankings are checked through the public API.
 *
 * Correctness model: a write answered 201 is CONFIRMED. A write that never connected is NOT applied.
 * A write that failed after reaching the server (503, timeout, ...) is UNCERTAIN: it may or may not have
 * been applied. Every final value must therefore lie between "confirmed only" and "confirmed + uncertain".
 */
public class LoadTest {

    record Resp(int status, String body, long nanos) {
    }

    record Write(int user, String game, long score) {
    }

    record Op(String kind, Callable<Resp> call, Write write) {
    }

    record Storm(List<Resp> results, long wallNanos) {
    }

    /** Lower bound = confirmed writes only; upper bound = confirmed + uncertain writes. */
    static class Ledger {
        final Map<String, Long> bestLow = new HashMap<>();   // user|game -> best score
        final Map<String, Long> bestHigh = new HashMap<>();
        final Map<String, Long> sumLow = new HashMap<>();    // user|game -> total points
        final Map<String, Long> sumHigh = new HashMap<>();
        long confirmed;
        long uncertain;
        long neverReached;

        void confirmed(Write w) {
            confirmed++;
            String key = w.user() + "|" + w.game();
            bestLow.merge(key, w.score(), Math::max);
            bestHigh.merge(key, w.score(), Math::max);
            sumLow.merge(key, w.score(), Long::sum);
            sumHigh.merge(key, w.score(), Long::sum);
        }

        void uncertain(Write w) {
            uncertain++;
            String key = w.user() + "|" + w.game();
            bestHigh.merge(key, w.score(), Math::max);
            sumHigh.merge(key, w.score(), Long::sum);
        }
    }

    static String base;
    static HttpClient client;
    static final AtomicLong connectRetries = new AtomicLong();

    public static void main(String[] args) throws Exception {
        base = args.length > 0 ? args[0] : "http://localhost:8080";
        int users = Math.min(100, intArg(args, 1, 50));
        int writes = intArg(args, 2, 1000);
        int mixedReads = intArg(args, 3, 700);
        int mixedWrites = intArg(args, 4, 300);

        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        System.out.printf("Target %s | users=%d | phase A writes=%d | phase B reads=%d writes=%d%n",
                base, users, writes, mixedReads, mixedWrites);

        Resp health = call("GET", "/actuator/health", null, null);
        if (health.status() != 200) {
            System.out.println("App not reachable (" + health.status() + " " + health.body() + ")");
            System.exit(2);
        }

        // unique names per run so repeated runs never collide and every game board starts empty
        String run = pad(Long.toString(Math.floorMod(System.nanoTime(), 60_466_176L), 36));
        String[] games = {"lt" + run + "-a", "lt" + run + "-b", "lt" + run + "-c"};
        String[] names = new String[users];
        String[] tokens = new String[users];

        System.out.println("\nRegistering " + users + " users (BCrypt is slow, so 8 at a time)...");
        registerUsers(run, names, tokens);

        Random rnd = new Random(42);
        Ledger ledger = new Ledger();
        long[] failed = {0};

        // Phase A: pure write storm
        List<Op> phaseA = new ArrayList<>();
        for (int i = 0; i < writes; i++) {
            phaseA.add(writeOp(rnd, users, games, tokens));
        }
        runPhase("Phase A (writes only)", phaseA, ledger, failed);

        // Phase B: reads and writes mixed, all at once
        List<Op> phaseB = new ArrayList<>();
        for (int i = 0; i < mixedWrites; i++) {
            phaseB.add(writeOp(rnd, users, games, tokens));
        }
        for (int i = 0; i < mixedReads; i++) {
            phaseB.add(readOp(rnd, users, games, tokens));
        }
        Collections.shuffle(phaseB, rnd);
        runPhase("Phase B (reads + writes mixed)", phaseB, ledger, failed);

        int[] outcome = verify(users, names, tokens, games, ledger);
        int mismatches = outcome[0];
        int ambiguous = outcome[1];

        System.out.println("\n================ RESULT ================");
        System.out.println("Writes confirmed (201):                 " + ledger.confirmed);
        System.out.println("Writes never connected (not applied):   " + ledger.neverReached);
        System.out.println("Writes uncertain (failed after reaching server): " + ledger.uncertain);
        System.out.println("Failed requests in total:               " + failed[0]);
        System.out.println("Values outside the possible range:      " + mismatches + "  <- real inconsistency");
        System.out.println("Values inside the range but not exact:  " + ambiguous + "  <- explained by uncertain writes");
        if (mismatches > 0) {
            System.out.println("FAIL: leaderboard data is inconsistent");
            System.exit(1);
        } else if (failed[0] > 0) {
            System.out.println("DATA CONSISTENT, but " + failed[0] + " requests failed (see status codes above)");
            System.exit(3);
        }
        System.out.println("PASS: no errors and Redis matches the submitted scores");
    }

    // ---------------------------------------------------------------- setup

    static void registerUsers(String run, String[] names, String[] tokens) throws Exception {
        Semaphore limit = new Semaphore(8);
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                names[i] = "lt" + run + "_" + i;
                futures.add(ex.submit(() -> {
                    limit.acquire();
                    try {
                        String body = "{\"username\":\"" + names[idx] + "\",\"email\":\"" + names[idx]
                                + "@load.test\",\"password\":\"password123\"}";
                        Resp r = call("POST", "/api/auth/register", null, body);
                        if (r.status() != 201) {
                            throw new IllegalStateException("register failed: " + r.status() + " " + r.body());
                        }
                        tokens[idx] = first(r.body(), "\"token\":\"([^\"]+)\"");
                    } finally {
                        limit.release();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
    }

    static Op writeOp(Random rnd, int users, String[] games, String[] tokens) {
        int user = rnd.nextInt(users);
        String game = games[rnd.nextInt(games.length)];
        long score = rnd.nextInt(100_000);
        String body = "{\"game\":\"" + game + "\",\"score\":" + score + "}";
        return new Op("write", () -> call("POST", "/api/scores", tokens[user], body),
                new Write(user, game, score));
    }

    static Op readOp(Random rnd, int users, String[] games, String[] tokens) {
        int pick = rnd.nextInt(4);
        String game = games[rnd.nextInt(games.length)];
        int user = rnd.nextInt(users);
        return switch (pick) {
            case 0 -> new Op("read-global", () -> call("GET", "/api/leaderboard?limit=10", null, null), null);
            case 1 -> new Op("read-game", () -> call("GET", "/api/leaderboard?game=" + game + "&limit=10", null, null), null);
            case 2 -> new Op("read-me", () -> call("GET", "/api/leaderboard/me", tokens[user], null), null);
            default -> new Op("read-report", () -> call("GET", "/api/reports/top-players?period=DAILY&limit=10", null, null), null);
        };
    }

    // ---------------------------------------------------------------- phases

    static void runPhase(String title, List<Op> ops, Ledger ledger, long[] failed) throws Exception {
        System.out.println("\n--- " + title + ": " + ops.size() + " requests released simultaneously ---");
        Storm storm = storm(ops);

        Map<String, List<Long>> latencyByKind = new TreeMap<>();
        Map<Integer, Integer> statusCodes = new TreeMap<>();
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            Resp r = storm.results().get(i);
            statusCodes.merge(r.status(), 1, Integer::sum);
            latencyByKind.computeIfAbsent(op.kind(), k -> new ArrayList<>()).add(r.nanos());

            boolean ok = op.write() != null ? r.status() == 201
                    : r.status() == 200 || (op.kind().equals("read-me") && r.status() == 404);
            if (ok) {
                if (op.write() != null) {
                    ledger.confirmed(op.write());
                }
                continue;
            }

            failed[0]++;
            if (failed[0] <= 5) {
                System.out.println("  failed: " + op.kind() + " -> " + r.status() + " " + abbreviate(r.body()));
            }
            if (op.write() != null) {
                if (neverReached(r)) {
                    ledger.neverReached++;
                } else {
                    ledger.uncertain(op.write());
                }
            }
        }

        double seconds = storm.wallNanos() / 1e9;
        System.out.printf("wall time %.2fs | throughput %.0f req/s%n", seconds, ops.size() / seconds);
        System.out.println("status codes: " + statusCodes);
        System.out.println("refused connections retried (cumulative): " + connectRetries.get());
        System.out.println("latency (ms)         count     min     avg     p50     p95     p99     max");
        latencyByKind.forEach((kind, list) -> printLatency(kind, list));
    }

    static Storm storm(List<Op> ops) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Resp>> futures = new ArrayList<>();
            for (Op op : ops) {
                futures.add(ex.submit(() -> {
                    start.await();
                    return op.call().call();
                }));
            }
            Thread.sleep(1000); // let every thread reach the starting gate
            long t0 = System.nanoTime();
            start.countDown();
            List<Resp> results = new ArrayList<>();
            for (Future<Resp> f : futures) {
                results.add(f.get());
            }
            return new Storm(results, System.nanoTime() - t0);
        }
    }

    /** True when the request provably never reached the server, so it cannot have been applied. */
    static boolean neverReached(Resp r) {
        return r.status() == -1
                && (r.body().contains("ConnectException") || r.body().contains("HttpConnectTimeoutException"));
    }

    // ---------------------------------------------------------------- verification

    /** Returns { valuesOutsideRange, valuesInsideRangeButNotExact }. */
    static int[] verify(int users, String[] names, String[] tokens, String[] games, Ledger l) {
        System.out.println("\n--- Verifying final state ---");
        int bad = 0;
        int ambiguous = 0;

        for (int u = 0; u < users; u++) {
            long lowGlobal = 0;
            long highGlobal = 0;
            boolean mustExist = false;
            boolean mayExist = false;
            for (String g : games) {
                String key = u + "|" + g;
                if (l.bestLow.containsKey(key)) {
                    lowGlobal += l.bestLow.get(key);
                    mustExist = true;
                }
                if (l.bestHigh.containsKey(key)) {
                    highGlobal += l.bestHigh.get(key);
                    mayExist = true;
                }
            }

            Resp me = call("GET", "/api/leaderboard/me", tokens[u], null);
            if (me.status() == 404) {
                if (mustExist) {
                    bad += outside("global score for " + names[u], lowGlobal + ".." + highGlobal, "not ranked");
                }
            } else if (me.status() == 200) {
                long actual = Long.parseLong(first(me.body(), "\"score\":(\\d+)"));
                if (!mayExist || actual < lowGlobal || actual > highGlobal) {
                    bad += outside("global score for " + names[u], lowGlobal + ".." + highGlobal, actual + "");
                } else if (actual != lowGlobal) {
                    ambiguous++;
                }
            } else {
                bad += outside("global rank for " + names[u], "200/404", "HTTP " + me.status());
            }

            for (String g : games) {
                String key = u + "|" + g;
                long low = l.bestLow.getOrDefault(key, 0L);
                long high = l.bestHigh.getOrDefault(key, 0L);
                Resp r = call("GET", "/api/leaderboard/me?game=" + g, tokens[u], null);
                if (r.status() == 404) {
                    if (l.bestLow.containsKey(key)) {
                        bad += outside("best score " + names[u] + " in " + g, low + ".." + high, "not ranked");
                    }
                    continue;
                }
                if (r.status() != 200) {
                    bad += outside("best score " + names[u] + " in " + g, low + ".." + high, "HTTP " + r.status());
                    continue;
                }
                long actual = Long.parseLong(first(r.body(), "\"score\":(\\d+)"));
                if (!l.bestHigh.containsKey(key) || actual < low || actual > high) {
                    bad += outside("best score " + names[u] + " in " + g, low + ".." + high, actual + "");
                } else if (actual != low) {
                    ambiguous++;
                }
            }
        }

        // The daily report sums every score submitted today, so it checks the period leaderboards.
        Pattern entry = Pattern.compile("\\{\"rank\":\\d+,\"username\":\"([^\"]+)\",\"score\":(\\d+)\\}");
        for (String g : games) {
            Resp r = call("GET", "/api/reports/top-players?period=DAILY&game=" + g + "&limit=100", null, null);
            Map<String, Long> actual = new HashMap<>();
            Matcher m = entry.matcher(r.body());
            while (m.find()) {
                actual.put(m.group(1), Long.parseLong(m.group(2)));
            }
            for (int u = 0; u < users; u++) {
                String key = u + "|" + g;
                long low = l.sumLow.getOrDefault(key, 0L);
                long high = l.sumHigh.getOrDefault(key, 0L);
                long got = actual.getOrDefault(names[u], 0L);
                if (got < low || got > high) {
                    bad += outside("daily points " + names[u] + " in " + g, low + ".." + high, got + "");
                } else if (got != low) {
                    ambiguous++;
                }
            }
            if (!r.body().contains("\"source\":\"redis\"")) {
                System.out.println("  note: report for " + g + " was served from the database, not Redis");
            }
        }

        System.out.println(bad == 0 ? "no value outside its possible range" : bad + " values outside their possible range");
        return new int[]{bad, ambiguous};
    }

    static int outside(String what, String expectedRange, String actual) {
        System.out.println("  INCONSISTENT " + what + ": possible " + expectedRange + ", got " + actual);
        return 1;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A refused TCP connection never reached the server, so retrying it is safe even for writes (no
     * duplicate can exist). Real clients do the same; this keeps the OS accept-queue limit on a
     * connection burst from being reported as an application failure. Any other error is not retried.
     */
    static Resp call(String method, String path, String token, String body) {
        long t0 = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(60));
                if (token != null) {
                    b.header("Authorization", "Bearer " + token);
                }
                if (body != null) {
                    b.header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(body));
                } else {
                    b.method(method, HttpRequest.BodyPublishers.noBody());
                }
                HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                return new Resp(r.statusCode(), r.body(), System.nanoTime() - t0);
            } catch (ConnectException e) {
                if (attempt >= 25) {
                    return new Resp(-1, e + " (gave up after " + attempt + " attempts)", System.nanoTime() - t0);
                }
                connectRetries.incrementAndGet();
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(20, Math.min(60L * attempt, 1500L) + 21));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new Resp(-1, ie.toString(), System.nanoTime() - t0);
                }
            } catch (Exception e) {
                return new Resp(-1, e.toString(), System.nanoTime() - t0);
            }
        }
    }

    static void printLatency(String kind, List<Long> nanos) {
        long[] ms = nanos.stream().mapToLong(n -> n / 1_000_000).sorted().toArray();
        double avg = Arrays.stream(ms).average().orElse(0);
        System.out.printf("%-18s %7d %7d %7.0f %7d %7d %7d %7d%n", kind, ms.length, ms[0], avg,
                pct(ms, 50), pct(ms, 95), pct(ms, 99), ms[ms.length - 1]);
    }

    static long pct(long[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    static String first(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("pattern " + regex + " not found in: " + abbreviate(text));
        }
        return m.group(1);
    }

    static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    static String pad(String s) {
        return "0".repeat(Math.max(0, 5 - s.length())) + s;
    }

    static int intArg(String[] a, int i, int def) {
        return a.length > i ? Integer.parseInt(a[i]) : def;
    }
}
