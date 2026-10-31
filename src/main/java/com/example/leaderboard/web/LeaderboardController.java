package com.example.leaderboard.web;

import com.example.leaderboard.dto.LeaderboardDtos.LeaderboardResponse;
import com.example.leaderboard.dto.LeaderboardDtos.RankResponse;
import com.example.leaderboard.security.AuthUser;
import com.example.leaderboard.service.LeaderboardService;
import com.example.leaderboard.service.LeaderboardStreamService;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    private static final String GAME_PATTERN = "^[a-z0-9_-]{1,32}$";

    private final LeaderboardService leaderboard;
    private final LeaderboardStreamService stream;

    public LeaderboardController(LeaderboardService leaderboard, LeaderboardStreamService stream) {
        this.leaderboard = leaderboard;
        this.stream = stream;
    }

    /** Global leaderboard, or a single game's when {@code game} is given. */
    @GetMapping
    public LeaderboardResponse top(@RequestParam(required = false) @Pattern(regexp = GAME_PATTERN) String game,
                                   @RequestParam(required = false) Integer limit) {
        return leaderboard.top(game, leaderboard.clampLimit(limit));
    }

    /** The caller's own position. */
    @GetMapping("/me")
    public RankResponse myRank(@AuthenticationPrincipal AuthUser user,
                               @RequestParam(required = false) @Pattern(regexp = GAME_PATTERN) String game) {
        return leaderboard.rankOf(game, user.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No score recorded yet" + (game == null ? "" : " for game '" + game + "'")));
    }

    /** Server-Sent Events: a fresh snapshot is pushed whenever the leaderboard changes. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) @Pattern(regexp = GAME_PATTERN) String game,
                             @RequestParam(required = false) Integer limit) {
        return stream.subscribe(game, leaderboard.clampLimit(limit));
    }
}
