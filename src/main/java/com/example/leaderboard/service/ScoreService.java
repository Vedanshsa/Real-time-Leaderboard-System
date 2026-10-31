package com.example.leaderboard.service;

import com.example.leaderboard.domain.ScoreEntry;
import com.example.leaderboard.dto.LeaderboardDtos.PageResponse;
import com.example.leaderboard.dto.LeaderboardDtos.ScoreHistoryItem;
import com.example.leaderboard.dto.LeaderboardDtos.SubmitScoreResponse;
import com.example.leaderboard.repo.ScoreRepository;
import com.example.leaderboard.repo.UserRepository;
import com.example.leaderboard.security.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class ScoreService {

    private static final Logger log = LoggerFactory.getLogger(ScoreService.class);
    private static final int REDIS_ATTEMPTS = 3;

    private final ScoreRepository scores;
    private final UserRepository users;
    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> submitScore;
    private final Clock clock = Clock.systemUTC();

    @SuppressWarnings("rawtypes")
    public ScoreService(ScoreRepository scores,
                        UserRepository users,
                        StringRedisTemplate redis,
                        RedisScript<List> submitScoreScript) {
        this.scores = scores;
        this.users = users;
        this.redis = redis;
        this.submitScore = submitScoreScript;
    }

    /**
     * Persists the score (durable history), then applies it to Redis with ONE atomic script call.
     *
     * <p>The script is idempotent per score entry, so a failed or timed-out call can simply be retried:
     * the update is applied once or not at all, never partially and never twice. The database write is
     * committed first, so a Redis outage cannot lose a score; the leaderboards are rebuilt from the
     * history if Redis loses its data.
     */
    public SubmitScoreResponse submit(AuthUser user, String game, long score) {
        Instant now = clock.instant();
        ScoreEntry saved = scores.save(new ScoreEntry(users.getReferenceById(user.id()), game, score, now));
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();

        // Key and argument order is the contract with scripts/submit_score.lua.
        List<String> keys = new ArrayList<>(9);
        keys.add(RedisKeys.game(game));
        keys.add(RedisKeys.GLOBAL);
        keys.add(RedisKeys.applied(saved.getId()));
        for (Period period : Period.values()) {
            keys.add(RedisKeys.period(period, day, null));
            keys.add(RedisKeys.period(period, day, game));
        }

        List<String> args = new ArrayList<>(7);
        args.add(user.username());
        args.add(Long.toString(score));
        for (Period period : Period.values()) {
            args.add(Long.toString(period.redisTtl().toSeconds()));
        }
        args.add(RedisKeys.UPDATES_CHANNEL);
        args.add(game);

        List<?> result = applyToRedis(keys, args, saved.getId());
        return new SubmitScoreResponse(
                game,
                score,
                number(result, 0) > 0,
                number(result, 1),
                number(result, 2),
                number(result, 3),
                number(result, 4));
    }

    public PageResponse<ScoreHistoryItem> history(AuthUser user, String game, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100)),
                Sort.by(Sort.Direction.DESC, "submittedAt", "id"));
        Page<ScoreEntry> result = game == null
                ? scores.findByUserId(user.id(), pageable)
                : scores.findByUserIdAndGame(user.id(), game, pageable);
        List<ScoreHistoryItem> items = result.getContent().stream()
                .map(e -> new ScoreHistoryItem(e.getId(), e.getGame(), e.getScore(), e.getSubmittedAt()))
                .toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    private List<?> applyToRedis(List<String> keys, List<String> args, Long entryId) {
        DataAccessException last = null;
        for (int attempt = 1; attempt <= REDIS_ATTEMPTS; attempt++) {
            try {
                List<?> result = redis.execute(submitScore, keys, args.toArray());
                if (result != null && result.size() == 5) {
                    return result;
                }
                throw new IllegalStateException("Unexpected result from submit_score.lua: " + result);
            } catch (DataAccessException e) {
                last = e;
                log.warn("Redis update for score entry {} failed (attempt {}/{}): {}",
                        entryId, attempt, REDIS_ATTEMPTS, e.toString());
                pause(25L * attempt);
            }
        }
        log.error("Score entry {} is saved but could not be applied to Redis", entryId, last);
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Leaderboard store is temporarily unavailable; your score was saved");
    }

    private static long number(List<?> result, int index) {
        return ((Number) result.get(index)).longValue();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
