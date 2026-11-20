package com.paysure.ledger.application;

import com.paysure.account.application.AccountLocker;
import com.paysure.account.domain.Account;
import com.paysure.common.error.ConflictException;
import com.paysure.ledger.domain.EntryDirection;
import com.paysure.ledger.domain.JournalType;
import com.paysure.ledger.domain.LedgerEntry;
import com.paysure.ledger.domain.LedgerJournal;
import com.paysure.ledger.repository.LedgerEntryRepository;
import com.paysure.ledger.repository.LedgerJournalRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only component allowed to change account balances.
 *
 * <ol>
 *   <li>Validates that the journal is balanced (sum of debits == sum of credits, all amounts &gt; 0).</li>
 *   <li>Rejects a second journal for the same (type, business reference) — exactly-once posting.</li>
 *   <li>Locks every involved account with {@code SELECT ... FOR UPDATE} in a deterministic order
 *       (ascending canonical UUID string), so two concurrent journals touching the same accounts
 *       cannot deadlock by acquiring locks in opposite orders.</li>
 *   <li>Applies balance changes through the {@link Account} domain rules (customers can never go
 *       below available funds), and appends immutable entries with running balances.</li>
 * </ol>
 *
 * The database independently re-checks balance (deferred constraint trigger), immutability
 * (update/delete triggers), uniqueness and non-negative customer balances (check constraints).
 */
@Service
public class LedgerService {

    private final AccountLocker locker;
    private final LedgerJournalRepository journals;
    private final LedgerEntryRepository entries;
    private final Clock clock;

    public LedgerService(AccountLocker locker, LedgerJournalRepository journals,
                         LedgerEntryRepository entries, Clock clock) {
        this.locker = locker;
        this.journals = journals;
        this.entries = entries;
        this.clock = clock;
    }

    /** Must run inside the caller's transaction so balance changes commit atomically with the business change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedJournal post(JournalCommand command) {
        command.validateBalanced();
        if (journals.existsByTypeAndReferenceId(command.type(), command.referenceId())) {
            throw new ConflictException("LEDGER_ALREADY_POSTED",
                    "A " + command.type() + " journal already exists for reference " + command.referenceId());
        }

        Map<UUID, Account> locked = locker.lockAll(command.legs().stream().map(Leg::accountId).toList());

        Instant now = clock.instant();
        LedgerJournal journal = journals.save(new LedgerJournal(
                command.type(), command.referenceType(), command.referenceId(), command.description(), now));

        List<LedgerEntry> posted = new ArrayList<>();
        for (Leg leg : command.legs()) {
            Account account = locked.get(leg.accountId());
            switch (leg.mode()) {
                case STANDARD -> {
                    if (leg.direction() == EntryDirection.DEBIT) {
                        account.debit(leg.amountPaise());
                    } else {
                        account.credit(leg.amountPaise());
                    }
                }
                case CAPTURE_HOLD -> {
                    // Converts previously reserved funds into a real debit atomically.
                    account.releaseReserve(leg.amountPaise());
                    account.debit(leg.amountPaise());
                }
                case SETTLEMENT -> account.creditSettlement(leg.amountPaise());
            }
            posted.add(new LedgerEntry(journal.getId(), account.getId(), leg.direction(), leg.amountPaise(),
                    account.getBalancePaise(), now));
        }
        entries.saveAll(posted);
        // Flush now so constraint violations (e.g. duplicate reference from a concurrent writer)
        // surface inside the caller's transaction rather than at commit time.
        journals.flush();
        return new PostedJournal(journal, posted);
    }

    /**
     * STANDARD applies the normal domain rules; CAPTURE_HOLD (debits only) first releases an existing
     * reservation of the same amount; SETTLEMENT (credits only) settles a bank-committed transfer even
     * into an account deactivated after initiation.
     */
    public enum LegMode { STANDARD, CAPTURE_HOLD, SETTLEMENT }

    public record Leg(UUID accountId, EntryDirection direction, long amountPaise, LegMode mode) {
        public Leg {
            Objects.requireNonNull(accountId);
            Objects.requireNonNull(direction);
            Objects.requireNonNull(mode);
            if (mode == LegMode.CAPTURE_HOLD && direction != EntryDirection.DEBIT
                    || mode == LegMode.SETTLEMENT && direction != EntryDirection.CREDIT) {
                throw new IllegalArgumentException(mode + " is not valid for a " + direction + " leg");
            }
        }

        public static Leg debit(UUID accountId, long amountPaise) {
            return new Leg(accountId, EntryDirection.DEBIT, amountPaise, LegMode.STANDARD);
        }

        public static Leg credit(UUID accountId, long amountPaise) {
            return new Leg(accountId, EntryDirection.CREDIT, amountPaise, LegMode.STANDARD);
        }

        public static Leg captureHold(UUID accountId, long amountPaise) {
            return new Leg(accountId, EntryDirection.DEBIT, amountPaise, LegMode.CAPTURE_HOLD);
        }

        public static Leg settlementCredit(UUID accountId, long amountPaise) {
            return new Leg(accountId, EntryDirection.CREDIT, amountPaise, LegMode.SETTLEMENT);
        }
    }

    public record JournalCommand(JournalType type, String referenceType, UUID referenceId, String description,
                                 List<Leg> legs) {
        public JournalCommand {
            Objects.requireNonNull(type);
            Objects.requireNonNull(referenceType);
            Objects.requireNonNull(referenceId);
            legs = List.copyOf(legs);
        }

        void validateBalanced() {
            if (legs.size() < 2) {
                throw new IllegalArgumentException("a journal needs at least two legs");
            }
            long debits = 0;
            long credits = 0;
            for (Leg leg : legs) {
                if (leg.amountPaise() <= 0) {
                    throw new IllegalArgumentException("ledger amounts must be positive paise");
                }
                if (leg.direction() == EntryDirection.DEBIT) {
                    debits = Math.addExact(debits, leg.amountPaise());
                } else {
                    credits = Math.addExact(credits, leg.amountPaise());
                }
            }
            if (debits != credits) {
                throw new IllegalArgumentException("unbalanced journal: debits=" + debits + " credits=" + credits);
            }
        }
    }

    public record PostedJournal(LedgerJournal journal, List<LedgerEntry> entries) {
    }
}
