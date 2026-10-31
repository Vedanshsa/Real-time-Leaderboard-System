package com.example.leaderboard.service;

import com.example.leaderboard.config.AppProperties;
import com.example.leaderboard.dto.LeaderboardDtos.LeaderboardEntry;
import com.example.leaderboard.dto.LeaderboardDtos.LeaderboardResponse;
import com.example.leaderboard.dto.LeaderboardDtos.RankResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read side of the leaderboards: thin wrappers over ZREVRANGE / ZREVRANK / ZSCORE / ZCARD. */
@Service
public class LeaderboardService {

    private final StringRedisTemplate redis;
    private final AppProperties props;

    public LeaderboardService(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** {@code game == null} means the global leaderboard. */
    public LeaderboardResponse top(String game, int limit) {
        String key = RedisKeys.forScope(game);
        Long total = redis.opsForZSet().size(key);
        return new LeaderboardResponse(scope(game), total == null ? 0 : total, topFromKey(key, limit));
    }

    public List<LeaderboardEntry> topFromKey(String key, int limit) {
        Set<TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);
        List<LeaderboardEntry> entries = new ArrayList<>();
        if (tuples == null) {
            return entries;
        }
        long rank = 1;
        for (TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null && tuple.getScore() != null) {
                entries.add(new LeaderboardEntry(rank++, tuple.getValue(), Math.round(tuple.getScore())));
            }
        }
        return entries;
    }

    public Optional<RankResponse> rankOf(String game, String username) {
        String key = RedisKeys.forScope(game);
        Long rank = redis.opsForZSet().reverseRank(key, username);
        Double score = redis.opsForZSet().score(key, username);
        if (rank == null || score == null) {
            return Optional.empty();
        }
        Long total = redis.opsForZSet().size(key);
        return Optional.of(new RankResponse(scope(game), username, rank + 1, Math.round(score),
                total == null ? 0 : total));
    }

    /** 1-based rank, or 0 when the user is not on that leaderboard. */
    public long rankInKey(String key, String username) {
        Long rank = redis.opsForZSet().reverseRank(key, username);
        return rank == null ? 0 : rank + 1;
    }

    public long scoreInKey(String key, String username) {
        Double score = redis.opsForZSet().score(key, username);
        return score == null ? 0 : Math.round(score);
    }

    public int clampLimit(Integer requested) {
        int max = props.leaderboard().maxLimit();
        if (requested == null) {
            return Math.min(props.leaderboard().defaultLimit(), max);
        }
        return Math.max(1, Math.min(requested, max));
    }

    private static String scope(String game) {
        return game == null ? "global" : game;
    }
}
