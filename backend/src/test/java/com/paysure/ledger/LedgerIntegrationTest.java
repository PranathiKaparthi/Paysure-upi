package com.paysure.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paysure.account.application.AccountService;
import com.paysure.account.application.VpaService;
import com.paysure.account.domain.Account;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.ConflictException;
import com.paysure.identity.domain.Role;
import com.paysure.ledger.application.LedgerService;
import com.paysure.ledger.application.LedgerService.JournalCommand;
import com.paysure.ledger.application.LedgerService.Leg;
import com.paysure.ledger.domain.JournalType;
import com.paysure.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** Database-enforced ledger guarantees and concurrency behaviour, tested against real PostgreSQL. */
class LedgerIntegrationTest extends IntegrationTest {

    @Autowired
    LedgerService ledger;
    @Autowired
    AccountService accounts;
    @Autowired
    VpaService vpas;
    @Autowired
    TransactionTemplate tx;

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() throws Exception {
        UUID accountId = newFundedAccount();
        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount_paise = 1 where account_id = ?", accountId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where account_id = ?", accountId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_journals where reference_id = ?", accountId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsAnUnbalancedJournalAtCommit() throws Exception {
        UUID accountId = newFundedAccount();
        UUID journalId = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("insert into ledger_journals (id, journal_type, reference_type, reference_id) values (?, 'SANDBOX_TOP_UP', 'TEST', ?)",
                    journalId, UUID.randomUUID());
            jdbc.update("insert into ledger_entries (id, journal_id, account_id, direction, amount_paise, balance_after_paise) values (?, ?, ?, 'CREDIT', 500, 0)",
                    UUID.randomUUID(), journalId, accountId);
        })).rootCause().hasMessageContaining("unbalanced");
        Long persisted = jdbc.queryForObject("select count(*) from ledger_journals where id = ?", Long.class, journalId);
        assertThat(persisted).isZero();
    }

    @Test
    void databaseRejectsCustomerOverdraftEvenIfApplicationCodeIsBypassed() throws Exception {
        UUID accountId = newFundedAccount();
        assertThatThrownBy(() -> jdbc.update("update accounts set balance_paise = -1 where id = ?", accountId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_accounts_customer_funds");
    }

    @Test
    void serviceRejectsUnbalancedAndNonPositiveLegs() {
        UUID ref = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(new JournalCommand(
                JournalType.SANDBOX_TOP_UP, "TEST", ref, null,
                List.of(Leg.debit(Account.TREASURY_ID, 100), Leg.credit(Account.TREASURY_ID, 99))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(new JournalCommand(
                JournalType.SANDBOX_TOP_UP, "TEST", ref, null,
                List.of(Leg.debit(Account.TREASURY_ID, 0), Leg.credit(Account.TREASURY_ID, 0))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ledgerPostingRequiresAnEnclosingTransaction() {
        assertThatThrownBy(() -> ledger.post(new JournalCommand(JournalType.SANDBOX_TOP_UP, "TEST", UUID.randomUUID(), null,
                List.of(Leg.debit(Account.TREASURY_ID, 1), Leg.credit(Account.TREASURY_ID, 1)))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void sameBusinessReferenceIsPostedAtMostOnce() throws Exception {
        UUID accountId = newFundedAccount();
        UUID reference = UUID.randomUUID();
        JournalCommand command = new JournalCommand(JournalType.SANDBOX_TOP_UP, "TEST", reference, "once",
                List.of(Leg.debit(Account.TREASURY_ID, 700), Leg.credit(accountId, 700)));

        tx.executeWithoutResult(s -> ledger.post(command));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(command)))
                .isInstanceOf(ConflictException.class);

        assertThat(balance(accountId)).isEqualTo(1_000_000L + 700);
        assertLedgerInvariants();
    }

    @Test
    void customerDebitCannotExceedAvailableFunds() throws Exception {
        UUID accountId = newFundedAccount();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(new JournalCommand(
                JournalType.SANDBOX_TOP_UP, "TEST", UUID.randomUUID(), null,
                List.of(Leg.debit(accountId, 1_000_001L), Leg.credit(Account.TREASURY_ID, 1_000_001L))))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient");
        assertThat(balance(accountId)).isEqualTo(1_000_000L);
        assertLedgerInvariants();
    }

    @Test
    void concurrentPostingsNeverLoseUpdates() throws Exception {
        UUID a = newFundedAccount();
        UUID b = newFundedAccount();
        int threads = 16;
        int perThread = 5;

        // Half the threads move a -> b, half b -> a, so locks are requested in "opposite" business
        // order; deterministic lock ordering must prevent deadlocks and no update may be lost.
        List<Callable<Void>> work = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            boolean forward = t % 2 == 0;
            work.add(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    UUID from = forward ? a : b;
                    UUID to = forward ? b : a;
                    tx.executeWithoutResult(s -> ledger.post(new JournalCommand(JournalType.SANDBOX_TOP_UP, "TEST",
                            UUID.randomUUID(), "concurrency", List.of(Leg.debit(from, 100), Leg.credit(to, 100)))));
                }
                return null;
            });
        }
        runConcurrently(work, start);

        // Equal number of transfers in both directions => both balances unchanged, total preserved.
        assertThat(balance(a)).isEqualTo(1_000_000L);
        assertThat(balance(b)).isEqualTo(1_000_000L);
        Long entries = jdbc.queryForObject("select count(*) from ledger_entries where account_id in (?, ?)", Long.class, a, b);
        assertThat(entries).isEqualTo(2 + 2L * threads * perThread);
        assertLedgerInvariants();
    }

    @Test
    void concurrentDebitsCannotOverspendOneAccount() throws Exception {
        UUID payer = newFundedAccount();   // ₹10,000
        UUID payee = newFundedAccount();
        int attempts = 20;                 // 20 x ₹700 = ₹14,000 requested; at most 14 can succeed
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            work.add(() -> {
                start.await();
                try {
                    tx.executeWithoutResult(s -> ledger.post(new JournalCommand(JournalType.SANDBOX_TOP_UP, "TEST",
                            UUID.randomUUID(), null, List.of(Leg.debit(payer, 70_000), Leg.credit(payee, 70_000)))));
                    succeeded.incrementAndGet();
                } catch (BusinessRuleException insufficient) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        runConcurrently(work, start);

        assertThat(succeeded.get()).isEqualTo(14);
        assertThat(rejected.get()).isEqualTo(6);
        assertThat(balance(payer)).isEqualTo(1_000_000L - 14 * 70_000L);
        assertThat(balance(payee)).isEqualTo(1_000_000L + 14 * 70_000L);
        assertLedgerInvariants();
    }

    @Test
    void concurrentClaimsOfTheSameVpaHaveExactlyOneWinner() throws Exception {
        int contenders = 8;
        List<UUID[]> owners = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            UUID userId = authService.createUser(uniqueEmail("race"), PASSWORD, "Racer " + i, Role.USER).getId();
            UUID accountId = accounts.openAccount(userId, "Race " + i, null).account().getId();
            owners.add(new UUID[] {userId, accountId});
        }
        String handle = uniqueHandle("race");
        AtomicInteger won = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> work = new ArrayList<>();
        for (UUID[] owner : owners) {
            work.add(() -> {
                start.await();
                try {
                    vpas.link(owner[0], owner[1], handle);
                    won.incrementAndGet();
                } catch (ConflictException taken) {
                    conflicted.incrementAndGet();
                }
                return null;
            });
        }
        runConcurrently(work, start);

        assertThat(won.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(contenders - 1);
        Long rows = jdbc.queryForObject("select count(*) from upi_handles where vpa = ?", Long.class, handle + "@paysure");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void concurrentAccountOpeningRespectsPerUserLimit() throws Exception {
        UUID userId = authService.createUser(uniqueEmail("burst"), PASSWORD, "Burst", Role.USER).getId();
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int n = i;
            work.add(() -> {
                start.await();
                try {
                    accounts.openAccount(userId, "Burst " + n, null);
                    opened.incrementAndGet();
                } catch (BusinessRuleException limit) {
                    limited.incrementAndGet();
                }
                return null;
            });
        }
        runConcurrently(work, start);

        assertThat(opened.get()).isEqualTo(3);
        assertThat(limited.get()).isEqualTo(5);
        assertLedgerInvariants();
    }

    private UUID newFundedAccount() throws Exception {
        UUID userId = authService.createUser(uniqueEmail("ledger"), PASSWORD, "Ledger Tester", Role.USER).getId();
        return accounts.openAccount(userId, "Ledger", null).account().getId();
    }

    private long balance(UUID accountId) {
        return jdbc.queryForObject("select balance_paise from accounts where id = ?", Long.class, accountId);
    }

    private static void runConcurrently(List<Callable<Void>> work, CountDownLatch start) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> c : work) {
                futures.add(pool.submit(c));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS); // rethrows unexpected failures (e.g. deadlocks)
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
