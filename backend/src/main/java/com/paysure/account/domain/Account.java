package com.paysure.account.domain;

import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A simulated bank account. {@code balancePaise} is a materialised view of the ledger: it is only
 * changed by {@link com.paysure.ledger.application.LedgerService} while the row is locked, in the
 * same transaction that appends the matching immutable ledger entries.
 *
 * <p>{@code reservedPaise} holds funds earmarked for in-flight payments (used from Phase 2), so
 * {@code available = balance - reserved}.
 */
@Entity
@Table(name = "accounts")
public class Account extends AuditedEntity {

    public static final UUID TREASURY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String SANDBOX_IFSC = "PSBK0000001";

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType type;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(nullable = false, length = 11)
    private String ifsc;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Column(nullable = false, length = 3, columnDefinition = "bpchar(3)")
    private String currency;

    @Column(name = "balance_paise", nullable = false)
    private long balancePaise;

    @Column(name = "reserved_paise", nullable = false)
    private long reservedPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    protected Account() {
    }

    public static Account openCustomerAccount(UUID userId, String accountNumber, String displayName) {
        Account a = new Account();
        a.id = UUID.randomUUID();
        a.userId = Objects.requireNonNull(userId);
        a.type = AccountType.CUSTOMER;
        a.accountNumber = Objects.requireNonNull(accountNumber);
        a.ifsc = SANDBOX_IFSC;
        a.displayName = displayName.trim();
        a.currency = "INR";
        a.balancePaise = 0;
        a.reservedPaise = 0;
        a.status = AccountStatus.ACTIVE;
        return a;
    }

    /** Increases the balance. Caller must hold the row lock and record a ledger CREDIT. */
    public void credit(long amountPaise) {
        requirePositive(amountPaise);
        if (type == AccountType.CUSTOMER && status != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("ACCOUNT_INACTIVE", "Account is not active.");
        }
        balancePaise = Math.addExact(balancePaise, amountPaise);
    }

    /** Decreases the balance. Customer accounts can never spend more than their available funds. */
    public void debit(long amountPaise) {
        requirePositive(amountPaise);
        if (type == AccountType.CUSTOMER) {
            if (status != AccountStatus.ACTIVE) {
                throw new BusinessRuleException("ACCOUNT_INACTIVE", "Account is not active.");
            }
            if (availablePaise() < amountPaise) {
                throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Insufficient available balance.");
            }
        }
        balancePaise = Math.subtractExact(balancePaise, amountPaise);
    }

    /**
     * Credit that settles a transfer the bank has already committed. It must land even if the payee
     * deactivated the account after the payment was initiated, so the active check is skipped.
     */
    public void creditSettlement(long amountPaise) {
        requirePositive(amountPaise);
        balancePaise = Math.addExact(balancePaise, amountPaise);
    }

    /**
     * Earmarks funds for an in-flight payment. No ledger entry is written: the money has not moved
     * yet, it simply can no longer be spent elsewhere ({@code available = balance - reserved}).
     */
    public void reserve(long amountPaise) {
        requirePositive(amountPaise);
        if (status != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("ACCOUNT_INACTIVE", "Account is not active.");
        }
        if (availablePaise() < amountPaise) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Insufficient available balance.");
        }
        reservedPaise = Math.addExact(reservedPaise, amountPaise);
    }

    /** Releases a hold (payment failed, or about to be captured by a ledger debit). */
    public void releaseReserve(long amountPaise) {
        requirePositive(amountPaise);
        if (reservedPaise < amountPaise) {
            throw new IllegalStateException("cannot release more than is reserved");
        }
        reservedPaise -= amountPaise;
    }

    public void deactivate() {
        if (reservedPaise > 0) {
            throw new BusinessRuleException("ACCOUNT_HAS_HOLDS",
                    "Account has funds reserved for in-flight payments and cannot be deactivated yet.");
        }
        status = AccountStatus.INACTIVE;
    }

    public void activate() {
        status = AccountStatus.ACTIVE;
    }

    public long availablePaise() {
        return balancePaise - reservedPaise;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean isOwnedBy(UUID candidateUserId) {
        return userId != null && userId.equals(candidateUserId);
    }

    private static void requirePositive(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amount must be positive paise");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public AccountType getType() {
        return type;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getMaskedAccountNumber() {
        int n = accountNumber.length();
        return "X".repeat(Math.max(0, n - 4)) + accountNumber.substring(Math.max(0, n - 4));
    }

    public String getIfsc() {
        return ifsc;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public long getReservedPaise() {
        return reservedPaise;
    }

    public AccountStatus getStatus() {
        return status;
    }
}
