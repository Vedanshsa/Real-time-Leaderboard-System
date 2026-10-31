package com.example.leaderboard.security;

/** The authenticated principal, rebuilt from the JWT on every request (no database hit). */
public record AuthUser(Long id, String username) {
}
