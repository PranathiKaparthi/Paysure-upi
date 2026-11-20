package com.paysure.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.embedded.RedisServer;

/**
 * One real Redis for the test JVM: a Testcontainers {@code redis:7} when Docker is reachable (or forced with
 * {@code -Dpaysure.test.db=testcontainers}), otherwise a real {@code redis-server} process from embedded-redis.
 */
public final class TestRedis {

    public record Connection(String host, int port, String backend) {
    }

    private static Connection connection;

    private TestRedis() {
    }

    public static synchronized Connection get() {
        if (connection == null) {
            connection = start();
        }
        return connection;
    }

    /** A free local TCP port (used to start/stop private Redis instances and to point at a dead one). */
    public static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static RedisServer startEmbedded(int port) {
        RedisServer server = RedisServer.builder().port(port).setting("maxmemory 64M").build();
        server.start();
        return server;
    }

    private static Connection start() {
        String mode = System.getProperty("paysure.test.db", "auto");
        boolean containers = switch (mode) {
            case "testcontainers" -> true;
            case "embedded" -> false;
            default -> dockerAvailable();
        };
        if (containers) {
            GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            redis.start();
            return new Connection(redis.getHost(), redis.getMappedPort(6379), "testcontainers");
        }
        int port = freePort();
        RedisServer server = startEmbedded(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return new Connection("localhost", port, "embedded");
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
