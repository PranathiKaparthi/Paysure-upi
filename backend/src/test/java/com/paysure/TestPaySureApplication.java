package com.paysure;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import redis.embedded.RedisServer;

/**
 * Runs the full backend locally WITHOUT Docker:
 * a real embedded PostgreSQL 17 (data kept in {@code backend/target/dev-postgres}), a real in-process Kafka broker
 * (KRaft) and a real {@code redis-server} process. Start with:
 *
 * <pre>  ./mvnw spring-boot:test-run</pre>
 *
 * Uses the {@code dev} profile (demo users seeded, dev tools on, sandbox webhook receiver on). Kafka and Redis are
 * optional for the application itself: if either fails to start here the app still runs (Redis-backed features fall back,
 * and outbox events simply wait until a broker is available).
 */
public class TestPaySureApplication {

    private static final Logger log = LoggerFactory.getLogger(TestPaySureApplication.class);

    public static void main(String[] args) {
        startKafka();
        startRedis();
        SpringApplication.from(PaySureApplication::main)
                .with(EmbeddedDatabase.class)
                .withAdditionalProfiles("dev")
                .run(args);
    }

    private static void startKafka() {
        try {
            EmbeddedKafkaBroker broker = new EmbeddedKafkaKraftBroker(1, 3);
            broker.afterPropertiesSet();
            System.setProperty("spring.kafka.bootstrap-servers", broker.getBrokersAsString());
            Runtime.getRuntime().addShutdownHook(new Thread(broker::destroy));
            log.info("Embedded Kafka broker (KRaft) at {}", broker.getBrokersAsString());
        } catch (RuntimeException e) {
            log.warn("Embedded Kafka could not start ({}); events will wait in the outbox", e.toString());
        }
    }

    private static void startRedis() {
        try (ServerSocket s = new ServerSocket(0)) {
            int port = s.getLocalPort();
            s.close();
            RedisServer redis = RedisServer.builder().port(port).setting("maxmemory 64M").build();
            redis.start();
            System.setProperty("spring.data.redis.port", Integer.toString(port));
            Runtime.getRuntime().addShutdownHook(new Thread(redis::stop));
            log.info("Embedded redis-server on port {}", port);
        } catch (IOException | RuntimeException e) {
            log.warn("Embedded Redis could not start ({}); rate limiting uses the in-memory fallback", e.toString());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EmbeddedDatabase {

        @Bean(destroyMethod = "close")
        EmbeddedPostgres embeddedPostgres() throws IOException {
            return EmbeddedPostgres.builder()
                    .setPort(54329)
                    .setDataDirectory(Path.of("target", "dev-postgres"))
                    .setCleanDataDirectory(false)
                    .start();
        }

        @Bean
        DataSource dataSource(EmbeddedPostgres pg) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(pg.getJdbcUrl("postgres", "postgres"));
            ds.setUsername("postgres");
            ds.setPassword("postgres");
            ds.setMaximumPoolSize(20);
            return ds;
        }
    }
}
