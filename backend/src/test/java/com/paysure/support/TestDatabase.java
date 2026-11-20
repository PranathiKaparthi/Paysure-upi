package com.paysure.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Provides one real PostgreSQL 17 instance for the whole test JVM.
 *
 * <ul>
 *   <li>{@code -Dpaysure.test.db=testcontainers}: always use a Testcontainers container (CI).</li>
 *   <li>{@code -Dpaysure.test.db=embedded}: always use zonky embedded PostgreSQL binaries.</li>
 *   <li>{@code auto} (default): Testcontainers if a Docker daemon is reachable, otherwise embedded.</li>
 * </ul>
 *
 * Both are genuine PostgreSQL servers, so row locks, constraint triggers and check constraints
 * behave exactly as in production. H2 is deliberately not used.
 */
public final class TestDatabase {

    public record Connection(String jdbcUrl, String username, String password, String backend) {
    }

    private static Connection connection;

    private TestDatabase() {
    }

    public static synchronized Connection get() {
        if (connection == null) {
            connection = start();
        }
        return connection;
    }

    private static Connection start() {
        String mode = System.getProperty("paysure.test.db", "auto");
        boolean useContainers = switch (mode) {
            case "testcontainers" -> true;
            case "embedded" -> false;
            default -> dockerAvailable();
        };
        if (useContainers) {
            PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("paysure").withUsername("paysure").withPassword("paysure");
            pg.start();
            return new Connection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), "testcontainers");
        }
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // JVM is exiting; nothing useful to do.
                }
            }));
            return new Connection(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres", "embedded");
        } catch (IOException e) {
            throw new UncheckedIOException("could not start embedded PostgreSQL", e);
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
