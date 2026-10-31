package com.example.leaderboard.service;

import com.example.leaderboard.config.AppProperties;
import com.example.leaderboard.repo.ScoreRepository;
import com.example.leaderboard.repo.ScoreRepository.PeriodTotal;
import com.example.leaderboard.repo.ScoreRepository.UserGameBest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis holds derived data; the score history in PostgreSQL is the source of truth. If Redis comes up
 * empty (first start, flush, lost volume) the leaderboards and the current day/week/month windows are
 * rebuilt from history right after startup.
 */
@Component
public class LeaderboardRebuilder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardRebuilder.class);

    private final StringRedisTemplate redis;
    private final ScoreRepository scores;
    private final AppProperties props;

    public LeaderboardRebuilder(StringRedisTemplate redis, ScoreRepository scores, AppProperties props) {
        this.redis = redis;
        this.scores = scores;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.leaderboard().rebuildOnStartup()) {
            return;
        }
        if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.GLOBAL))) {
            return;
        }
        rebuild();
    }

    public void rebuild() {
        Set<String> stale = redis.keys(RedisKeys.PREFIX + "*");
        if (stale != null && !stale.isEmpty()) {
            redis.delete(stale);
        }

        rebuildBestScores();
        rebuildCurrentPeriods();
        log.info("Rebuilt Redis leaderboards from score history");
    }

    private void rebuildBestScores() {
        List<UserGameBest> rows = scores.bestScorePerUserAndGame();
        Map<String, Set<TypedTuple<String>>> perGame = new HashMap<>();
        Map<String, Long> globalTotals = new HashMap<>();

        for (UserGameBest row : rows) {
            perGame.computeIfAbsent(RedisKeys.game(row.getGame()), k -> new HashSet<>())
                    .add(new DefaultTypedTuple<>(row.getUsername(), row.getBest().doubleValue()));
            globalTotals.merge(row.getUsername(), row.getBest(), Long::sum);
        }

        perGame.forEach((key, tuples) -> redis.opsForZSet().add(key, tuples));

        Set<TypedTuple<String>> global = new HashSet<>();
        globalTotals.forEach((user, total) -> global.add(new DefaultTypedTuple<>(user, total.doubleValue())));
        if (!global.isEmpty()) {
            redis.opsForZSet().add(RedisKeys.GLOBAL, global);
        }
    }

    private void rebuildCurrentPeriods() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (Period period : Period.values()) {
            List<PeriodTotal> rows = scores.periodTotalsByUserAndGame(
                    period.startInstant(today), period.endInstant(today));
            Map<String, Set<TypedTuple<String>>> byKey = new HashMap<>();
            Map<String, Long> allGames = new HashMap<>();

            for (PeriodTotal row : rows) {
                byKey.computeIfAbsent(RedisKeys.period(period, today, row.getGame()), k -> new HashSet<>())
                        .add(new DefaultTypedTuple<>(row.getUsername(), row.getTotal().doubleValue()));
                allGames.merge(row.getUsername(), row.getTotal(), Long::sum);
            }

            Set<TypedTuple<String>> global = new HashSet<>();
            allGames.forEach((user, total) -> global.add(new DefaultTypedTuple<>(user, total.doubleValue())));
            if (!global.isEmpty()) {
                byKey.put(RedisKeys.period(period, today, null), global);
            }

            byKey.forEach((key, tuples) -> {
                redis.opsForZSet().add(key, tuples);
                redis.expire(key, period.redisTtl());
            });
        }
    }
}
