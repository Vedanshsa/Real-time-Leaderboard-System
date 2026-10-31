package com.example.leaderboard.service;

import com.example.leaderboard.dto.LeaderboardDtos.LeaderboardEntry;
import com.example.leaderboard.dto.LeaderboardDtos.TopPlayersReport;
import com.example.leaderboard.repo.ScoreRepository;
import com.example.leaderboard.repo.ScoreRepository.PlayerTotal;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Top players for a time window. Calendar windows (day / week / month) are answered from Redis sorted sets
 * while they exist, and from the score history otherwise; arbitrary ranges always use the history.
 */
@Service
public class ReportService {

    private static final Duration MAX_CUSTOM_RANGE = Duration.ofDays(366);

    private final StringRedisTemplate redis;
    private final LeaderboardService leaderboard;
    private final ScoreRepository scores;

    public ReportService(StringRedisTemplate redis, LeaderboardService leaderboard, ScoreRepository scores) {
        this.redis = redis;
        this.leaderboard = leaderboard;
        this.scores = scores;
    }

    /** The window containing {@code date} (today, in UTC, when null). */
    public TopPlayersReport forPeriod(Period period, LocalDate date, String game, int limit) {
        LocalDate anchor = date != null ? date : LocalDate.now(ZoneOffset.UTC);
        Instant from = period.startInstant(anchor);
        Instant to = period.endInstant(anchor);

        String key = RedisKeys.period(period, anchor, game);
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            return new TopPlayersReport(period, from, to, game, "redis", leaderboard.topFromKey(key, limit));
        }
        return new TopPlayersReport(period, from, to, game, "database", fromHistory(game, from, to, limit));
    }

    /** An arbitrary half-open range [from, to). */
    public TopPlayersReport forRange(Instant from, Instant to, String game, int limit) {
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be before 'to'");
        }
        if (Duration.between(from, to).compareTo(MAX_CUSTOM_RANGE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Range cannot exceed 366 days");
        }
        return new TopPlayersReport(null, from, to, game, "database", fromHistory(game, from, to, limit));
    }

    private List<LeaderboardEntry> fromHistory(String game, Instant from, Instant to, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        List<PlayerTotal> rows = game == null
                ? scores.topPlayers(from, to, page)
                : scores.topPlayersForGame(game, from, to, page);
        List<LeaderboardEntry> entries = new ArrayList<>(rows.size());
        long rank = 1;
        for (PlayerTotal row : rows) {
            entries.add(new LeaderboardEntry(rank++, row.getUsername(), row.getTotal()));
        }
        return entries;
    }
}
