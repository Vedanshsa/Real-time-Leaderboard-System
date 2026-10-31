package com.example.leaderboard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9_]{3,20}$",
                    message = "must be 3-20 characters: letters, digits or underscore")
            String username,

            @NotBlank @Email @Size(max = 254)
            String email,

            @NotBlank @Size(min = 8, max = 72, message = "must be 8-72 characters")
            String password) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record AuthResponse(String token, String tokenType, long expiresInSeconds, String username) {
    }
}
