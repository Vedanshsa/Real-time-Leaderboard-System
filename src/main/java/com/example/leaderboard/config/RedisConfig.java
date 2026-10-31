package com.example.leaderboard.config;

import com.example.leaderboard.service.LeaderboardStreamService;
import com.example.leaderboard.service.RedisKeys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration
public class RedisConfig {

    /**
     * Applies one score submission to every leaderboard atomically and idempotently
     * (see scripts/submit_score.lua). Returns { improvement, gameBest, gameRank, globalScore, globalRank }.
     */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> submitScoreScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/submit_score.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    public RedisMessageListenerContainer redisListenerContainer(RedisConnectionFactory factory,
                                                                LeaderboardStreamService stream) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(stream::onMessage, new ChannelTopic(RedisKeys.UPDATES_CHANNEL));
        return container;
    }
}
