package com.example.leaderboard.service;

import com.example.leaderboard.config.AppProperties;
import com.example.leaderboard.dto.LeaderboardDtos.LeaderboardResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes leaderboard snapshots to connected clients over Server-Sent Events.
 *
 * <p>Every score submission publishes the game name on a Redis pub/sub channel, so every application
 * instance hears about it and updates its own connected clients. Updates are coalesced: at most one
 * snapshot per subscriber is sent per flush interval, however many scores arrive in between.
 */
@Service
public class LeaderboardStreamService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardStreamService.class);
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private record Subscriber(String game, int limit, SseEmitter emitter) {
    }

    private final LeaderboardService leaderboard;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Set<String> dirtyGames = ConcurrentHashMap.newKeySet();

    public LeaderboardStreamService(LeaderboardService leaderboard, AppProperties props) {
        this.leaderboard = leaderboard;
    }

    /** {@code game == null} subscribes to the global leaderboard. */
    public SseEmitter subscribe(String game, int limit) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Subscriber subscriber = new Subscriber(game, limit, emitter);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> subscribers.remove(subscriber));

        send(subscriber, leaderboard.top(game, limit));   // initial snapshot
        return emitter;
    }

    /** Redis pub/sub callback: the message body is the game that just changed. */
    public void onMessage(Message message, byte[] pattern) {
        dirtyGames.add(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    @Scheduled(fixedDelayString = "${app.stream.flush-ms:500}")
    void flush() {
        Set<String> dirty = new HashSet<>();
        for (String game : Set.copyOf(dirtyGames)) {
            dirty.add(game);
            dirtyGames.remove(game);
        }
        if (dirty.isEmpty()) {
            return;
        }

        Map<String, LeaderboardResponse> snapshots = new HashMap<>();
        for (Subscriber subscriber : subscribers) {
            // The global board changes whenever any game changes; a game board only when that game does.
            if (subscriber.game() != null && !dirty.contains(subscriber.game())) {
                continue;
            }
            LeaderboardResponse snapshot = snapshots.computeIfAbsent(
                    subscriber.game() + "#" + subscriber.limit(),
                    k -> leaderboard.top(subscriber.game(), subscriber.limit()));
            send(subscriber, snapshot);
        }
    }

    /** Keeps idle connections open through proxies and lets us notice clients that went away. */
    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.emitter().send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(subscriber);
            }
        }
    }

    private void send(Subscriber subscriber, LeaderboardResponse snapshot) {
        try {
            subscriber.emitter().send(SseEmitter.event().name("leaderboard").data(snapshot));
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping closed SSE subscriber: {}", e.toString());
            subscribers.remove(subscriber);
        }
    }
}
