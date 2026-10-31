package com.example.leaderboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Leaderboard leaderboard, Stream stream) {

    public record Jwt(String secret, long ttlMinutes) {
    }

    public record Leaderboard(int maxLimit, int defaultLimit, boolean rebuildOnStartup) {
    }

    public record Stream(long flushMs) {
    }
}
