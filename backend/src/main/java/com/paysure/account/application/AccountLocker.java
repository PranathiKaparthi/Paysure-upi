package com.paysure.account.application;

import com.paysure.account.domain.Account;
import com.paysure.common.error.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acquires {@code SELECT ... FOR UPDATE} row locks on accounts and guarantees the returned entity
 * reflects the row as of the lock.
 *
 * <p>Why not just a locking query? If the entity was already loaded earlier in the same persistence
 * context (e.g. by an ownership check), Hibernate returns the cached instance without overwriting its
 * state, so the code would compute a new balance from a stale value. Refreshing under the lock avoids
 * that lost-update hazard.
 *
 * <p>Multiple accounts are always locked in ascending canonical-UUID order so that two transactions
 * touching the same pair of accounts cannot deadlock by locking them in opposite orders.
 */
@Component
public class AccountLocker {

    private final EntityManager em;

    public AccountLocker(EntityManager em) {
        this.em = em;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Account lock(UUID accountId) {
        Account cached = em.find(Account.class, accountId);
        if (cached == null) {
            throw new NotFoundException("ACCOUNT_NOT_FOUND", "Account not found.");
        }
        em.refresh(cached, LockModeType.PESSIMISTIC_WRITE);
        return cached;
    }

    /** Locks all accounts in deterministic order; the returned map iterates in lock order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockAll(Collection<UUID> accountIds) {
        Map<UUID, Account> locked = new LinkedHashMap<>();
        accountIds.stream()
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(id -> locked.put(id, lock(id)));
        return locked;
    }
}
