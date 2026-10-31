package com.example.leaderboard.repo;

import com.example.leaderboard.domain.ScoreEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ScoreRepository extends JpaRepository<ScoreEntry, Long> {

    interface PlayerTotal {
        String getUsername();

        Long getTotal();
    }

    interface PeriodTotal {
        String getUsername();

        String getGame();

        Long getTotal();
    }

    interface UserGameBest {
        String getUsername();

        String getGame();

        Long getBest();
    }

    Page<ScoreEntry> findByUserId(Long userId, Pageable pageable);

    Page<ScoreEntry> findByUserIdAndGame(Long userId, String game, Pageable pageable);

    /** Sum of all scores submitted in [since, until), across every game. */
    @Query("""
            select s.user.username as username, sum(s.score) as total
            from ScoreEntry s
            where s.submittedAt >= :since and s.submittedAt < :until
            group by s.user.username
            order by sum(s.score) desc, s.user.username desc
            """)
    List<PlayerTotal> topPlayers(@Param("since") Instant since, @Param("until") Instant until, Pageable pageable);

    /** Sum of the scores submitted in [since, until) for a single game. */
    @Query("""
            select s.user.username as username, sum(s.score) as total
            from ScoreEntry s
            where s.game = :game and s.submittedAt >= :since and s.submittedAt < :until
            group by s.user.username
            order by sum(s.score) desc, s.user.username desc
            """)
    List<PlayerTotal> topPlayersForGame(@Param("game") String game,
                                        @Param("since") Instant since,
                                        @Param("until") Instant until,
                                        Pageable pageable);

    /** Points per user and game inside [since, until); used to rebuild the current period leaderboards. */
    @Query("""
            select s.user.username as username, s.game as game, sum(s.score) as total
            from ScoreEntry s
            where s.submittedAt >= :since and s.submittedAt < :until
            group by s.user.username, s.game
            """)
    List<PeriodTotal> periodTotalsByUserAndGame(@Param("since") Instant since, @Param("until") Instant until);

    /** Every user's best score per game; used to rebuild the Redis leaderboards from scratch. */
    @Query("""
            select s.user.username as username, s.game as game, max(s.score) as best
            from ScoreEntry s
            group by s.user.username, s.game
            """)
    List<UserGameBest> bestScorePerUserAndGame();
}
