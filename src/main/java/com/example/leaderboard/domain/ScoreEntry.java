package com.example.leaderboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** One row per submitted score: the durable score history and the source of truth for rebuilding Redis. */
@Entity
@Table(name = "score_entries", indexes = {
        @Index(name = "idx_scores_user_time", columnList = "user_id, submitted_at"),
        @Index(name = "idx_scores_game_time", columnList = "game, submitted_at"),
        @Index(name = "idx_scores_time", columnList = "submitted_at")
})
public class ScoreEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false, length = 32)
    private String game;

    @Column(nullable = false)
    private long score;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    protected ScoreEntry() {
    }

    public ScoreEntry(AppUser user, String game, long score, Instant submittedAt) {
        this.user = user;
        this.game = game;
        this.score = score;
        this.submittedAt = submittedAt;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getGame() {
        return game;
    }

    public long getScore() {
        return score;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
