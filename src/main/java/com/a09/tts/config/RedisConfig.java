package com.a09.tts.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
@EnableCaching
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.connect-timeout:1s}") Duration connectTimeout,
            @Value("${spring.data.redis.timeout:2s}") Duration commandTimeout) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        standalone.setDatabase(database);
        if (password != null && !password.isBlank()) {
            standalone.setPassword(RedisPassword.of(password));
        }

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(connectTimeout)
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .protocolVersion(ProtocolVersion.RESP2)
                .socketOptions(socketOptions)
                .build();
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(commandTimeout)
                .build();
        return new LettuceConnectionFactory(standalone, clientConfiguration);
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> "zjys:" + cacheName + "::");

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(
                        "voiceList", defaults.entryTtl(Duration.ofMinutes(10)),
                        "voiceById", defaults.entryTtl(Duration.ofMinutes(30))
                ))
                .build();
    }

    @Bean
    public RedisScript<List> advanceDialogueScript() {
        return RedisScript.of("""
                local owner = redis.call('HGET', KEYS[1], 'username')
                if not owner then
                    return {}
                end
                if owner ~= ARGV[1] then
                    return {'FORBIDDEN'}
                end
                local scenario = redis.call('HGET', KEYS[1], 'scenarioId')
                if not scenario then
                    return {}
                end
                local turn = redis.call('HINCRBY', KEYS[1], 'currentTurn', 1)
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
                return {'OK', scenario, tostring(turn), owner}
                """, List.class);
    }
}
