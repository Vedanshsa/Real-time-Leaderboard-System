package com.example.leaderboard.service;

import com.example.leaderboard.domain.AppUser;
import com.example.leaderboard.dto.AuthDtos.AuthResponse;
import com.example.leaderboard.dto.AuthDtos.LoginRequest;
import com.example.leaderboard.dto.AuthDtos.RegisterRequest;
import com.example.leaderboard.repo.UserRepository;
import com.example.leaderboard.security.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public AuthResponse register(RegisterRequest request) {
        // Usernames are case-insensitive: the lower-cased form is also the Redis member.
        String username = request.username().toLowerCase(Locale.ROOT);
        String email = request.email().toLowerCase(Locale.ROOT);

        if (users.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken");
        }
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        try {
            AppUser saved = users.save(new AppUser(username, email, encoder.encode(request.password())));
            return tokenFor(saved);
        } catch (DataIntegrityViolationException race) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or email is already registered");
        }
    }

    public AuthResponse login(LoginRequest request) {
        AppUser user = users.findByUsername(request.username().toLowerCase(Locale.ROOT))
                .filter(candidate -> encoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        return tokenFor(user);
    }

    private AuthResponse tokenFor(AppUser user) {
        return new AuthResponse(jwt.issue(user), "Bearer", jwt.ttlSeconds(), user.getUsername());
    }
}
