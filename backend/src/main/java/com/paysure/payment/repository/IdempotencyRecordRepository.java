package com.paysure.payment.repository;

import com.paysure.payment.domain.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByCallerUserIdAndIdempotencyKey(UUID callerUserId, String idempotencyKey);

    /**
     * Atomically claims a key. Returns 1 if this transaction now owns the key, 0 if it already exists.
     * A concurrent claimer of the same key blocks on the unique index until the first transaction
     * commits (then gets 0) or rolls back (then gets 1) — so exactly one request can ever win.
     */
    @Modifying
    @Query(value = """
            insert into idempotency_records (id, caller_user_id, idempotency_key, request_hash, payment_id, created_at, expires_at)
            values (:id, :caller, :key, :hash, :paymentId, :now, :expiresAt)
            on conflict (caller_user_id, idempotency_key) do nothing""", nativeQuery = true)
    int tryClaim(@Param("id") UUID id, @Param("caller") UUID caller, @Param("key") String key,
                 @Param("hash") String hash, @Param("paymentId") UUID paymentId,
                 @Param("now") Instant now, @Param("expiresAt") Instant expiresAt);

    /** Stored count of duplicate requests answered from this key (feeds the reliability score). */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = """
            update idempotency_records set replay_count = replay_count + 1, last_replayed_at = :now
            where caller_user_id = :caller and idempotency_key = :key""", nativeQuery = true)
    int recordReplay(@Param("caller") UUID caller, @Param("key") String key, @Param("now") Instant now);

    /** Retention: expired keys are removed only once their payment reached a terminal state. */
    @Modifying
    @Query(value = """
            delete from idempotency_records r
            where r.expires_at < :now
              and exists (select 1 from payments p where p.id = r.payment_id and p.status in ('SUCCESS', 'FAILED'))""",
            nativeQuery = true)
    int deleteExpired(@Param("now") Instant now);
}
