package com.paysure.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paysure.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AccountIntegrationTest extends IntegrationTest {

    private static final long SEED = 1_000_000L; // ₹10,000.00 (application-test.yml)

    @Test
    void openAccountIsFundedThroughABalancedLedgerJournal() throws Exception {
        TestUser user = registerAndLogin("open");
        String handle = uniqueHandle("ana");

        JsonNode account = readJson(openAccount(user, "Savings", handle)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balancePaise").value(SEED))
                .andExpect(jsonPath("$.availablePaise").value(SEED))
                .andExpect(jsonPath("$.reservedPaise").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.vpas[0].vpa").value(handle + "@paysure"))
                .andExpect(jsonPath("$.maskedAccountNumber").value(org.hamcrest.Matchers.matchesPattern("X{8}\\d{4}")))
                .andReturn().getResponse().getContentAsString());
        UUID accountId = UUID.fromString(account.get("id").asText());

        mvc.perform(get("/api/accounts/{id}/ledger", accountId).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$[0].amountPaise").value(SEED))
                .andExpect(jsonPath("$[0].balanceAfterPaise").value(SEED));

        Long legs = jdbc.queryForObject("""
                select count(*) from ledger_entries e join ledger_journals j on j.id = e.journal_id
                where j.journal_type = 'SANDBOX_FUNDING' and j.reference_id = ?""", Long.class, accountId);
        assertThat(legs).isEqualTo(2);
        assertLedgerInvariants();
    }

    @Test
    void balanceInquiry() throws Exception {
        TestUser user = registerAndLogin("bal");
        UUID accountId = createdAccountId(user, null);
        mvc.perform(get("/api/accounts/{id}/balance", accountId).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(SEED))
                .andExpect(jsonPath("$.availablePaise").value(SEED))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void usersOnlySeeTheirOwnAccounts() throws Exception {
        TestUser owner = registerAndLogin("owner");
        TestUser other = registerAndLogin("other");
        UUID accountId = createdAccountId(owner, null);

        mvc.perform(get("/api/accounts").header("Authorization", other.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // Someone else's account is indistinguishable from a non-existent one.
        mvc.perform(get("/api/accounts/{id}", accountId).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(get("/api/accounts/{id}/ledger", accountId).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/accounts/{id}/deactivate", accountId).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/accounts/{id}/vpas", accountId).header("Authorization", other.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"" + uniqueHandle("hij") + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void accountLimitIsEnforced() throws Exception {
        TestUser user = registerAndLogin("limit");
        for (int i = 0; i < 3; i++) {
            openAccount(user, "Acc " + i, null).andExpect(status().isCreated());
        }
        openAccount(user, "One too many", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_REACHED"));
    }

    @Test
    void vpaMustBeUniqueAcrossUsers() throws Exception {
        TestUser first = registerAndLogin("first");
        TestUser second = registerAndLogin("second");
        String handle = uniqueHandle("shared");
        openAccount(first, "Mine", handle).andExpect(status().isCreated());

        openAccount(second, "Also mine", handle.toUpperCase())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VPA_TAKEN"));
        // The failed request rolled back entirely: no orphan account was created.
        mvc.perform(get("/api/accounts").header("Authorization", second.bearer()))
                .andExpect(jsonPath("$.length()").value(0));
        assertLedgerInvariants();
    }

    @Test
    void invalidAndReservedHandlesAreRejected() throws Exception {
        TestUser user = registerAndLogin("handles");
        UUID accountId = createdAccountId(user, null);
        linkVpa(user, accountId, "ab").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VPA_HANDLE"));
        linkVpa(user, accountId, "bad handle!").andExpect(status().isBadRequest());
        linkVpa(user, accountId, "-leadingdash").andExpect(status().isBadRequest());
        linkVpa(user, accountId, "admin").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VPA_HANDLE_RESERVED"));
    }

    @Test
    void vpaLimitPerAccount() throws Exception {
        TestUser user = registerAndLogin("vlimit");
        UUID accountId = createdAccountId(user, null);
        for (int i = 0; i < 3; i++) {
            linkVpa(user, accountId, uniqueHandle("v" + i)).andExpect(status().isCreated());
        }
        linkVpa(user, accountId, uniqueHandle("v4")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VPA_LIMIT_REACHED"));
    }

    @Test
    void resolveVpaAndDeactivationLifecycle() throws Exception {
        TestUser payee = registerAndLogin("payee");
        TestUser payer = registerAndLogin("payer");
        String handle = uniqueHandle("rahul");
        UUID accountId = createdAccountId(payee, handle);
        String vpa = handle + "@paysure";

        mvc.perform(get("/api/vpas/{vpa}/resolve", vpa.toUpperCase()).header("Authorization", payer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vpa").value(vpa))
                .andExpect(jsonPath("$.payeeName").value("payee Tester"))
                .andExpect(jsonPath("$.accountNumber").doesNotExist());

        mvc.perform(get("/api/vpas/{vpa}/resolve", "nobody-" + handle + "@paysure").header("Authorization", payer.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VPA_NOT_FOUND"));
        mvc.perform(get("/api/vpas/{vpa}/resolve", "not a vpa").header("Authorization", payer.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VPA"));

        // Deactivated account: VPA stops resolving and new VPAs cannot be linked.
        mvc.perform(post("/api/accounts/{id}/deactivate", accountId).header("Authorization", payee.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(get("/api/vpas/{vpa}/resolve", vpa).header("Authorization", payer.bearer()))
                .andExpect(status().isNotFound());
        linkVpa(payee, accountId, uniqueHandle("late")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));

        mvc.perform(post("/api/accounts/{id}/activate", accountId).header("Authorization", payee.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/vpas/{vpa}/resolve", vpa).header("Authorization", payer.bearer()))
                .andExpect(status().isOk());

        // Only the owner can deactivate a VPA.
        mvc.perform(post("/api/vpas/{vpa}/deactivate", vpa).header("Authorization", payer.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/vpas/{vpa}/deactivate", vpa).header("Authorization", payee.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(get("/api/vpas/{vpa}/resolve", vpa).header("Authorization", payer.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminDemoTopUpGoesThroughTheLedger() throws Exception {
        TestUser user = registerAndLogin("topup");
        TestUser admin = adminDemo();
        UUID accountId = createdAccountId(user, null);

        mvc.perform(post("/api/dev/accounts/{id}/top-up", accountId).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountPaise\":25050,\"reason\":\"demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(SEED + 25_050));

        mvc.perform(post("/api/dev/accounts/{id}/top-up", accountId).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountPaise\":-5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/dev/accounts/{id}/top-up", accountId).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountPaise\":10000001}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TOP_UP_AMOUNT"));
        // The treasury (SYSTEM account) cannot be topped up through the API.
        mvc.perform(post("/api/dev/accounts/{id}/top-up", "00000000-0000-0000-0000-000000000001")
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountPaise\":100}"))
                .andExpect(status().isNotFound());
        assertLedgerInvariants();
    }

    @Test
    void ordinaryApisCannotSetBalances() throws Exception {
        TestUser user = registerAndLogin("sneaky");
        Map<String, Object> body = new HashMap<>();
        body.put("displayName", "Rich");
        body.put("balancePaise", 999_999_999_99L);
        mvc.perform(post("/api/accounts").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balancePaise").value(SEED));
    }

    private ResultActions openAccount(TestUser user, String name, String handle) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("displayName", name);
        if (handle != null) {
            body.put("vpaHandle", handle);
        }
        return mvc.perform(post("/api/accounts").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private UUID createdAccountId(TestUser user, String handle) throws Exception {
        String body = openAccount(user, "Primary", handle).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(readJson(body).get("id").asText());
    }

    private ResultActions linkVpa(TestUser user, UUID accountId, String handle) throws Exception {
        return mvc.perform(post("/api/accounts/{id}/vpas", accountId).header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("handle", handle))));
    }
}
