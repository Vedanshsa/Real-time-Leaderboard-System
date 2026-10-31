package com.example.leaderboard.service;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Redis key layout. Every leaderboard is a sorted set with member = username.
 *
 * <pre>
 * lb:global                              sum of each user's best score per game
 * lb:game:{game}                         each user's best score in one game
 * lb:period:{period}:{startDate}         points earned in a calendar window, all games
 * lb:period:{period}:{startDate}:{game}  points earned in a calendar window, one game
 * </pre>
 */
public final class RedisKeys {

    public static final String GLOBAL = "lb:global";
    public static final String UPDATES_CHANNEL = "lb:updates";
    public static final String PREFIX = "lb:";

    private RedisKeys() {
    }

    public static String game(String game) {
        return PREFIX + "game:" + game;
    }

    /** {@code game == null} selects the global leaderboard. */
    public static String forScope(String game) {
        return game == null ? GLOBAL : game(game);
    }

    /** Marker proving a score entry was already applied to Redis; makes retries idempotent. */
    public static String applied(long entryId) {
        return PREFIX + "applied:" + entryId;
    }

    public static String period(Period period, LocalDate anchor, String game) {
        String base = PREFIX + "period:" + period.name().toLowerCase(Locale.ROOT) + ":" + period.start(anchor);
        return game == null ? base : base + ":" + game;
    }
}
