package com.paysure.account.api;

import com.paysure.account.application.AccountService.AccountView;
import com.paysure.account.application.VpaService.ResolvedVpa;
import com.paysure.account.domain.Account;
import com.paysure.account.domain.AccountStatus;
import com.paysure.account.domain.UpiHandle;
import com.paysure.ledger.domain.EntryDirection;
import com.paysure.ledger.domain.LedgerEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response bodies for accounts and VPAs. Money is always integral paise. */
public final class AccountDtos {

    private AccountDtos() {
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 60) String displayName,
            /** Optional first VPA handle, e.g. "ananya" -> ananya@paysure. */
            @Size(max = 30) String vpaHandle) {
    }

    public record LinkVpaRequest(@NotBlank @Size(max = 30) String handle) {
    }

    public record TopUpRequest(
            @NotNull @Positive @Max(10_000_000_000L) Long amountPaise,
            @Size(max = 120) String reason) {
    }

    public record VpaResponse(UUID id, String vpa, UUID accountId, AccountStatus status, Instant createdAt) {
        public static VpaResponse of(UpiHandle h) {
            return new VpaResponse(h.getId(), h.getVpa(), h.getAccountId(), h.getStatus(), h.getCreatedAt());
        }
    }

    public record AccountResponse(
            UUID id,
            String displayName,
            String maskedAccountNumber,
            String ifsc,
            String currency,
            AccountStatus status,
            long balancePaise,
            long reservedPaise,
            long availablePaise,
            List<VpaResponse> vpas,
            Instant createdAt) {

        public static AccountResponse of(AccountView view) {
            Account a = view.account();
            return new AccountResponse(a.getId(), a.getDisplayName(), a.getMaskedAccountNumber(), a.getIfsc(),
                    a.getCurrency(), a.getStatus(), a.getBalancePaise(), a.getReservedPaise(), a.availablePaise(),
                    view.vpas().stream().map(VpaResponse::of).toList(), a.getCreatedAt());
        }
    }

    public record BalanceResponse(UUID accountId, long balancePaise, long reservedPaise, long availablePaise,
                                  String currency, Instant asOf) {
        public static BalanceResponse of(Account a) {
            return new BalanceResponse(a.getId(), a.getBalancePaise(), a.getReservedPaise(), a.availablePaise(),
                    a.getCurrency(), Instant.now());
        }
    }

    public record LedgerEntryResponse(UUID id, UUID journalId, EntryDirection direction, long amountPaise,
                                      long balanceAfterPaise, Instant createdAt) {
        public static LedgerEntryResponse of(LedgerEntry e) {
            return new LedgerEntryResponse(e.getId(), e.getJournalId(), e.getDirection(), e.getAmountPaise(),
                    e.getBalanceAfterPaise(), e.getCreatedAt());
        }
    }

    public record ResolveVpaResponse(String vpa, String payeeName, String ifsc) {
        public static ResolveVpaResponse of(ResolvedVpa r) {
            return new ResolveVpaResponse(r.vpa(), r.payeeName(), r.ifsc());
        }
    }
}
