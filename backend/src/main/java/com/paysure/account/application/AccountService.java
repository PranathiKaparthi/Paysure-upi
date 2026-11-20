package com.paysure.account.application;

import com.paysure.account.domain.Account;
import com.paysure.account.domain.AccountType;
import com.paysure.account.domain.UpiHandle;
import com.paysure.account.repository.AccountRepository;
import com.paysure.account.repository.UpiHandleRepository;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.NotFoundException;
import com.paysure.config.SandboxProperties;
import com.paysure.identity.repository.UserRepository;
import com.paysure.ledger.application.LedgerService;
import com.paysure.ledger.application.LedgerService.JournalCommand;
import com.paysure.ledger.application.LedgerService.Leg;
import com.paysure.ledger.domain.JournalType;
import com.paysure.ledger.domain.LedgerEntry;
import com.paysure.ledger.repository.LedgerEntryRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final UpiHandleRepository handles;
    private final UserRepository users;
    private final LedgerService ledger;
    private final LedgerEntryRepository ledgerEntries;
    private final AccountNumberGenerator numberGenerator;
    private final VpaService vpaService;
    private final SandboxProperties sandbox;
    private final AccountLocker locker;

    public AccountService(AccountRepository accounts, UpiHandleRepository handles, UserRepository users,
                          LedgerService ledger, LedgerEntryRepository ledgerEntries,
                          AccountNumberGenerator numberGenerator, VpaService vpaService, SandboxProperties sandbox,
                          AccountLocker locker) {
        this.accounts = accounts;
        this.handles = handles;
        this.users = users;
        this.ledger = ledger;
        this.ledgerEntries = ledgerEntries;
        this.numberGenerator = numberGenerator;
        this.vpaService = vpaService;
        this.sandbox = sandbox;
        this.locker = locker;
    }

    /**
     * Opens a sandbox account, funds it from the simulated treasury via a balanced journal and,
     * optionally, links a first VPA — all in one transaction (a taken VPA rolls everything back).
     */
    @Transactional
    public AccountView openAccount(UUID userId, String displayName, String vpaHandle) {
        // Lock the user row so concurrent requests cannot both pass the per-user account limit.
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found."));
        if (accounts.countByUserId(userId) >= sandbox.maxAccountsPerUser()) {
            throw new BusinessRuleException("ACCOUNT_LIMIT_REACHED",
                    "A user can hold at most " + sandbox.maxAccountsPerUser() + " sandbox accounts.");
        }

        Account account = accounts.saveAndFlush(
                Account.openCustomerAccount(userId, uniqueAccountNumber(), displayName));

        if (sandbox.seedBalancePaise() > 0) {
            ledger.post(new JournalCommand(JournalType.SANDBOX_FUNDING, "ACCOUNT", account.getId(),
                    "Sandbox opening balance",
                    List.of(Leg.debit(Account.TREASURY_ID, sandbox.seedBalancePaise()),
                            Leg.credit(account.getId(), sandbox.seedBalancePaise()))));
        }
        if (vpaHandle != null && !vpaHandle.isBlank()) {
            vpaService.link(userId, account.getId(), vpaHandle);
        }
        log.info("Opened sandbox account {} for user {}", account.getId(), userId);
        return view(account);
    }

    @Transactional(readOnly = true)
    public List<AccountView> listAccounts(UUID userId) {
        List<Account> owned = accounts.findByUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, List<UpiHandle>> vpasByAccount = handles
                .findByAccountIdInOrderByCreatedAtAsc(owned.stream().map(Account::getId).toList())
                .stream().collect(Collectors.groupingBy(UpiHandle::getAccountId));
        return owned.stream()
                .map(a -> new AccountView(a, vpasByAccount.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountView getAccount(UUID userId, UUID accountId) {
        return view(requireOwned(userId, accountId));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> recentLedger(UUID userId, UUID accountId, int limit) {
        requireOwned(userId, accountId);
        return ledgerEntries.findByAccountIdOrderByCreatedAtDescIdDesc(accountId,
                PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    @Transactional
    public AccountView deactivate(UUID userId, UUID accountId) {
        requireOwned(userId, accountId);
        Account locked = lockAccount(accountId);
        locked.deactivate();
        log.info("Deactivated account {}", accountId);
        return view(locked);
    }

    @Transactional
    public AccountView activate(UUID userId, UUID accountId) {
        requireOwned(userId, accountId);
        Account locked = lockAccount(accountId);
        locked.activate();
        log.info("Activated account {}", accountId);
        return view(locked);
    }

    /**
     * Development-only funding performed by an ADMIN_DEMO user. Goes through the ledger like any
     * other balance change; there is no API that writes a balance directly.
     */
    @Transactional
    public AccountView sandboxTopUp(UUID accountId, long amountPaise, String reason) {
        if (amountPaise <= 0 || amountPaise > sandbox.maxTopUpPaise()) {
            throw new BusinessRuleException("INVALID_TOP_UP_AMOUNT",
                    "Top-up must be between 1 and " + sandbox.maxTopUpPaise() + " paise.");
        }
        Account account = accounts.findById(accountId)
                .filter(a -> a.getType() == AccountType.CUSTOMER)
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "Account not found."));
        ledger.post(new JournalCommand(JournalType.SANDBOX_TOP_UP, "TOP_UP", UUID.randomUUID(),
                reason == null || reason.isBlank() ? "Sandbox top-up" : reason.trim(),
                List.of(Leg.debit(Account.TREASURY_ID, amountPaise), Leg.credit(account.getId(), amountPaise))));
        log.info("Sandbox top-up of {} paise to account {}", amountPaise, accountId);
        return view(account);
    }

    /** Ownership check. Accounts owned by someone else are reported as not found (no existence leak). */
    Account requireOwned(UUID userId, UUID accountId) {
        return accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "Account not found."));
    }

    private Account lockAccount(UUID accountId) {
        return locker.lock(accountId);
    }

    private AccountView view(Account account) {
        return new AccountView(account, handles.findByAccountIdInOrderByCreatedAtAsc(List.of(account.getId())));
    }

    private String uniqueAccountNumber() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = numberGenerator.next();
            if (!accounts.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not allocate a unique sandbox account number");
    }

    /** Read model returned to the API layer. */
    public record AccountView(Account account, List<UpiHandle> vpas) {
    }
}
