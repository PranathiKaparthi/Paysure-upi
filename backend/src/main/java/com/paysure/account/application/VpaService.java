package com.paysure.account.application;

import com.paysure.account.domain.Account;
import com.paysure.account.domain.UpiHandle;
import com.paysure.account.domain.Vpa;
import com.paysure.account.repository.AccountRepository;
import com.paysure.account.repository.UpiHandleRepository;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.ConflictException;
import com.paysure.common.error.NotFoundException;
import com.paysure.config.SandboxProperties;
import com.paysure.identity.domain.User;
import com.paysure.identity.repository.UserRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VpaService {

    private static final Logger log = LoggerFactory.getLogger(VpaService.class);

    private final UpiHandleRepository handles;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final SandboxProperties sandbox;
    private final AccountLocker locker;

    public VpaService(UpiHandleRepository handles, AccountRepository accounts, UserRepository users,
                      SandboxProperties sandbox, AccountLocker locker) {
        this.handles = handles;
        this.accounts = accounts;
        this.users = users;
        this.sandbox = sandbox;
        this.locker = locker;
    }

    /**
     * Links a new VPA to an account the caller owns. Uniqueness is decided by the database's unique
     * constraint, so two users racing for the same handle can never both win.
     */
    @Transactional
    public UpiHandle link(UUID userId, UUID accountId, String rawHandle) {
        Vpa vpa = Vpa.fromHandle(rawHandle, sandbox.vpaSuffix());
        // Lock the account so the per-account VPA limit holds under concurrency.
        accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "Account not found."));
        Account account = locker.lock(accountId);
        if (!account.isActive()) {
            throw new BusinessRuleException("ACCOUNT_INACTIVE", "Cannot link a VPA to an inactive account.");
        }
        if (handles.countByAccountId(accountId) >= sandbox.maxVpasPerAccount()) {
            throw new BusinessRuleException("VPA_LIMIT_REACHED",
                    "An account can have at most " + sandbox.maxVpasPerAccount() + " VPAs.");
        }
        if (handles.existsByVpa(vpa.value())) {
            throw vpaTaken(vpa);
        }
        try {
            UpiHandle saved = handles.saveAndFlush(new UpiHandle(vpa, accountId));
            log.info("Linked VPA {} to account {}", vpa, accountId);
            return saved;
        } catch (DataIntegrityViolationException race) {
            throw vpaTaken(vpa);
        }
    }

    @Transactional
    public UpiHandle deactivate(UUID userId, String rawVpa) {
        UpiHandle handle = findOwned(userId, rawVpa);
        handle.deactivate();
        return handle;
    }

    /**
     * Resolves a payee VPA to the registered name, as UPI apps show before paying.
     * Inactive VPAs or VPAs on inactive accounts do not resolve.
     */
    @Transactional(readOnly = true)
    public ResolvedVpa resolve(String rawVpa) {
        Vpa vpa = Vpa.parse(rawVpa);
        UpiHandle handle = handles.findByVpa(vpa.value()).filter(UpiHandle::isActive)
                .orElseThrow(VpaService::notFound);
        Account account = accounts.findById(handle.getAccountId()).filter(Account::isActive)
                .orElseThrow(VpaService::notFound);
        String name = users.findById(account.getUserId()).map(User::getFullName).orElse("PaySure user");
        return new ResolvedVpa(handle.getVpa(), name, account.getIfsc());
    }

    private UpiHandle findOwned(UUID userId, String rawVpa) {
        Vpa vpa = Vpa.parse(rawVpa);
        UpiHandle handle = handles.findByVpa(vpa.value()).orElseThrow(VpaService::notFound);
        accounts.findById(handle.getAccountId()).filter(a -> a.isOwnedBy(userId)).orElseThrow(VpaService::notFound);
        return handle;
    }

    private static NotFoundException notFound() {
        return new NotFoundException("VPA_NOT_FOUND", "VPA not found or not active.");
    }

    private static ConflictException vpaTaken(Vpa vpa) {
        return new ConflictException("VPA_TAKEN", "The VPA " + vpa + " is already registered.");
    }

    public record ResolvedVpa(String vpa, String payeeName, String ifsc) {
    }
}
