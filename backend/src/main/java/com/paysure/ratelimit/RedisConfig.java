package com.paysure.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kept separate from the MVC config so the Redis connection factory does not depend on anything that uses Redis. */
@Configuration
public class RedisConfig {

    /**
     * Fail fast when Redis is down. Lettuce's default buffers commands while disconnected, which would make every
     * request wait for the command timeout; rejecting them lets callers fall back immediately.
     */
    @Bean
    static LettuceClientConfigurationBuilderCustomizer lettuceFailFast() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
                .build());
    }
}
