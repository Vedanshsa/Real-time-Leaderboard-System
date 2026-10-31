package com.example.leaderboard.web;

import com.example.leaderboard.dto.LeaderboardDtos.PageResponse;
import com.example.leaderboard.dto.LeaderboardDtos.ScoreHistoryItem;
import com.example.leaderboard.dto.LeaderboardDtos.SubmitScoreRequest;
import com.example.leaderboard.dto.LeaderboardDtos.SubmitScoreResponse;
import com.example.leaderboard.security.AuthUser;
import com.example.leaderboard.service.ScoreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {

    private final ScoreService scores;

    public ScoreController(ScoreService scores) {
        this.scores = scores;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmitScoreResponse submit(@AuthenticationPrincipal AuthUser user,
                                      @Valid @RequestBody SubmitScoreRequest request) {
        return scores.submit(user, request.game(), request.score());
    }

    @GetMapping("/history")
    public PageResponse<ScoreHistoryItem> history(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) @Pattern(regexp = "^[a-z0-9_-]{1,32}$") String game,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return scores.history(user, game, page, size);
    }
}
