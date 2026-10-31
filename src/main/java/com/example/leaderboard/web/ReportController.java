package com.example.leaderboard.web;

import com.example.leaderboard.dto.LeaderboardDtos.TopPlayersReport;
import com.example.leaderboard.service.LeaderboardService;
import com.example.leaderboard.service.Period;
import com.example.leaderboard.service.ReportService;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reports;
    private final LeaderboardService leaderboard;

    public ReportController(ReportService reports, LeaderboardService leaderboard) {
        this.reports = reports;
        this.leaderboard = leaderboard;
    }

    /**
     * Either a calendar window ({@code period} + optional {@code date}) or an explicit range
     * ({@code from} + {@code to}, ISO-8601 instants). Add {@code game} to restrict to one game.
     */
    @GetMapping("/top-players")
    public TopPlayersReport topPlayers(
            @RequestParam(required = false) Period period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) @Pattern(regexp = "^[a-z0-9_-]{1,32}$") String game,
            @RequestParam(required = false) Integer limit) {

        int size = leaderboard.clampLimit(limit);
        boolean customRange = from != null || to != null;

        if (customRange) {
            if (from == null || to == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide both 'from' and 'to'");
            }
            if (period != null || date != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Use either period/date or from/to, not both");
            }
            return reports.forRange(from, to, game, size);
        }
        return reports.forPeriod(period != null ? period : Period.WEEKLY, date, game, size);
    }
}
