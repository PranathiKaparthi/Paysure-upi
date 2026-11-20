package com.paysure.collect.application;

import com.paysure.account.domain.Account;
import com.paysure.account.domain.UpiHandle;
import com.paysure.account.domain.Vpa;
import com.paysure.account.repository.AccountRepository;
import com.paysure.account.repository.UpiHandleRepository;
import com.paysure.collect.domain.CollectRequest;
import com.paysure.collect.repository.CollectRequestRepository;
import com.paysure.common.error.BadRequestException;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.ForbiddenException;
import com.paysure.common.error.NotFoundException;
import com.paysure.payment.application.PaymentCommand;
import com.paysure.payment.application.PaymentProperties;
import com.paysure.payment.application.PaymentService;
import com.paysure.payment.domain.Payment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CollectService {

    private static final Logger log = LoggerFactory.getLogger(CollectService.class);
    static final Duration DEFAULT_TTL = Duration.ofHours(24);
    static final Duration MAX_TTL = Duration.ofDays(7);

    private final CollectRequestRepository requests;
    private final UpiHandleRepository handles;
    private final AccountRepository accounts;
    private final PaymentService paymentService;
    private final PaymentProperties paymentProps;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CollectService(CollectRequestRepository requests, UpiHandleRepository handles, AccountRepository accounts,
                          PaymentService paymentService, PaymentProperties paymentProps, EntityManager em,
                          TransactionTemplate tx, Clock clock) {
        this.requests = requests;
        this.handles = handles;
        this.accounts = accounts;
        this.paymentService = paymentService;
        this.paymentProps = paymentProps;
        this.em = em;
        this.tx = tx;
        this.clock = clock;
    }

    public record Decision(CollectRequest request, Payment payment) {
    }

    public CollectRequest create(UUID callerId, String requesterVpaRaw, String payerVpaRaw, long amountPaise, String note,
                                 Integer expiresInMinutes) {
        if (amountPaise <= 0 || amountPaise > paymentProps.maxAmountPaise()) {
            throw new BadRequestException("INVALID_AMOUNT", "Amount must be between 1 and " + paymentProps.maxAmountPaise() + " paise.");
        }
        Duration ttl = expiresInMinutes == null ? DEFAULT_TTL : Duration.ofMinutes(expiresInMinutes);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
            throw new BadRequestException("INVALID_EXPIRY", "Expiry must be between 1 minute and 7 days.");
        }
        Vpa requesterVpa = Vpa.parse(requesterVpaRaw);
        Vpa payerVpa = Vpa.parse(payerVpaRaw);
        Account requester = activeAccount(requesterVpa)
                .filter(a -> a.isOwnedBy(callerId))
                .orElseThrow(() -> new ForbiddenException("REQUESTER_NOT_OWNED", "You can only request money to your own active VPA."));
        Account payer = activeAccount(payerVpa)
                .orElseThrow(() -> new NotFoundException("PAYER_NOT_FOUND", "Payer VPA not found or not active."));
        if (payer.getId().equals(requester.getId())) {
            throw new BusinessRuleException("SELF_TRANSFER_NOT_ALLOWED", "Requester and payer resolve to the same account.");
        }
        CollectRequest saved = tx.execute(s -> requests.save(new CollectRequest(callerId, requester.getId(), requesterVpa.value(),
                payer.getUserId(), payer.getId(), payerVpa.value(), amountPaise, note, clock.instant().plus(ttl))));
        log.info("Collect request {} created: {} asks {}", saved.getId(), requesterVpa, payerVpa);
        return saved;
    }

    /**
     * Approves and pays. Idempotent: approving twice (or concurrently) yields the same single payment because
     * (1) the request row is locked while its status changes, (2) the payment is created with a deterministic
     * Idempotency-Key derived from the request id, and (3) the database allows one payment per request.
     * If the process crashed after approval but before paying, approving again completes the payment.
     */
    public Decision approve(UUID callerId, boolean callerIsAdmin, UUID requestId) {
        CollectRequest approved = tx.execute(s -> {
            CollectRequest r = lockOwnedByPayer(callerId, requestId);
            if (r.getStatus() != com.paysure.collect.domain.CollectStatus.APPROVED) {
                r.approve(clock.instant());
            }
            return r;
        });
        PaymentService.Initiated paid = paymentService.initiate(new PaymentCommand(callerId, callerIsAdmin,
                "collect:" + requestId, approved.getPayerVpa(), approved.getRequesterVpa(), approved.getAmountPaise(),
                approved.getNote(), null, requestId));
        CollectRequest linked = tx.execute(s -> {
            CollectRequest r = lock(requestId);
            r.linkPayment(paid.payment().getId());
            return r;
        });
        return new Decision(linked, paid.payment());
    }

    public CollectRequest decline(UUID callerId, UUID requestId) {
        return tx.execute(s -> {
            CollectRequest r = lockOwnedByPayer(callerId, requestId);
            r.decline(clock.instant());
            return r;
        });
    }

    public CollectRequest cancel(UUID callerId, UUID requestId) {
        return tx.execute(s -> {
            CollectRequest r = lock(requestId);
            if (!r.getRequesterUserId().equals(callerId)) {
                throw notFound();
            }
            r.cancel(clock.instant());
            return r;
        });
    }

    public List<CollectRequest> incoming(UUID callerId, int limit) {
        return requests.findByPayerUserIdOrderByCreatedAtDesc(callerId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    public List<CollectRequest> outgoing(UUID callerId, int limit) {
        return requests.findByRequesterUserIdOrderByCreatedAtDesc(callerId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    private CollectRequest lockOwnedByPayer(UUID callerId, UUID requestId) {
        CollectRequest r = lock(requestId);
        if (!r.getPayerUserId().equals(callerId)) {
            throw r.getRequesterUserId().equals(callerId)
                    ? new ForbiddenException("NOT_THE_PAYER", "Only the payer can approve or decline this request.")
                    : notFound();
        }
        if (r.expireIfDue(clock.instant())) {
            throw new BusinessRuleException("COLLECT_EXPIRED", "This request has expired.");
        }
        return r;
    }

    private CollectRequest lock(UUID requestId) {
        CollectRequest r = em.find(CollectRequest.class, requestId, LockModeType.PESSIMISTIC_WRITE);
        if (r == null) {
            throw notFound();
        }
        return r;
    }

    private java.util.Optional<Account> activeAccount(Vpa vpa) {
        return handles.findByVpa(vpa.value()).filter(UpiHandle::isActive)
                .flatMap(h -> accounts.findById(h.getAccountId())).filter(Account::isActive);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("COLLECT_NOT_FOUND", "Collect request not found.");
    }

    public Instant now() {
        return clock.instant();
    }
}
