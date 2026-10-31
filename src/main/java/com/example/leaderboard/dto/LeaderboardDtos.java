package com.example.leaderboard.dto;

import com.example.leaderboard.service.Period;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

public final class LeaderboardDtos {

    private LeaderboardDtos() {
    }

    public record SubmitScoreRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z0-9_-]{1,32}$",
                    message = "must be 1-32 characters: lowercase letters, digits, '-' or '_'")
            String game,

            @Min(0) @Max(1_000_000_000L)
            long score) {
    }

    public record SubmitScoreResponse(
            String game,
            long submittedScore,
            boolean newPersonalBest,
            long gameBest,
            long gameRank,
            long globalScore,
            long globalRank) {
    }

    public record LeaderboardEntry(long rank, String username, long score) {
    }

    public record LeaderboardResponse(String scope, long totalPlayers, List<LeaderboardEntry> entries) {
    }

    public record RankResponse(String scope, String username, long rank, long score, long totalPlayers) {
    }

    public record ScoreHistoryItem(Long id, String game, long score, Instant submittedAt) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    }

    public record TopPlayersReport(
            Period period,
            Instant from,
            Instant to,
            String game,
            String source,
            List<LeaderboardEntry> players) {
    }
}
