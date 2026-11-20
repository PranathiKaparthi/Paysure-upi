package com.paysure.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Common optimistic-lock version and creation/update timestamps.
 *
 * <p>The version is a nullable wrapper on purpose: with application-assigned UUID ids, Spring Data decides
 * "new vs existing" from the version. A {@code null} version means new → {@code persist} (the same instance
 * becomes managed). With a primitive {@code 0} it would {@code merge} instead and return a different
 * instance, silently dropping later changes made to the original object.
 */
@MappedSuperclass
public abstract class AuditedEntity {

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Must expose the raw (nullable) value: Spring Data reads it through this getter to detect new entities. */
    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
