package com.paysure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysure.identity.application.AuthService;
import com.paysure.identity.domain.Role;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for full-stack integration tests against a real PostgreSQL (see {@link TestDatabase}).
 * Tests isolate themselves by creating uniquely-named users rather than truncating tables
 * (ledger tables are append-only by design).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String PASSWORD = "Passw0rd!";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected AuthService authService;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.Connection db = TestDatabase.get();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    protected static String uniqueHandle(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Registers through the public API and returns a bearer token. */
    protected TestUser registerAndLogin(String prefix) throws Exception {
        String email = uniqueEmail(prefix);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD, "fullName", prefix + " Tester"))))
                .andExpect(status().isCreated());
        return login(email);
    }

    /** Creates an ADMIN_DEMO user directly (there is intentionally no public API for that). */
    protected TestUser adminDemo() throws Exception {
        String email = uniqueEmail("admin");
        authService.createUser(email, PASSWORD, "Lab Admin", Role.ADMIN_DEMO);
        return login(email);
    }

    protected TestUser login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return new TestUser(UUID.fromString(node.at("/user/id").asText()), email, node.get("accessToken").asText());
    }

    protected JsonNode readJson(String body) throws Exception {
        return json.readTree(body);
    }

    /**
     * Global ledger invariants, asserted after every money-moving test:
     * every journal nets to zero, and every account's balance equals its net ledger movement.
     */
    protected void assertLedgerInvariants() {
        Long unbalancedJournals = jdbc.queryForObject("""
                select count(*) from (
                  select journal_id from ledger_entries
                  group by journal_id
                  having sum(case when direction = 'CREDIT' then amount_paise else -amount_paise end) <> 0
                ) j""", Long.class);
        assertThat(unbalancedJournals).as("unbalanced journals").isZero();

        Long drifted = jdbc.queryForObject("""
                select count(*) from accounts a
                where a.balance_paise <> coalesce((
                  select sum(case when e.direction = 'CREDIT' then e.amount_paise else -e.amount_paise end)
                  from ledger_entries e where e.account_id = a.id), 0)""", Long.class);
        assertThat(drifted).as("accounts whose balance differs from the ledger").isZero();

        Long overspent = jdbc.queryForObject("""
                select count(*) from accounts
                where account_type = 'CUSTOMER' and (balance_paise < 0 or reserved_paise > balance_paise)""", Long.class);
        assertThat(overspent).as("customer accounts below zero available").isZero();

        Long holdDrift = jdbc.queryForObject("""
                select count(*) from accounts a
                where a.reserved_paise <> coalesce((
                  select sum(p.amount_paise) from payments p
                  where p.payer_account_id = a.id and p.hold_status = 'HELD'), 0)""", Long.class);
        assertThat(holdDrift).as("accounts whose reserved funds differ from held payments").isZero();

        Long badPostings = jdbc.queryForObject("""
                select count(*) from payments p
                where (select count(*) from ledger_journals j
                       where j.journal_type = 'PAYMENT_TRANSFER' and j.reference_id = p.id)
                      <> case when p.status = 'SUCCESS' then 1 else 0 end""", Long.class);
        assertThat(badPostings).as("payments without exactly one transfer journal iff SUCCESS").isZero();
    }

    public record TestUser(UUID id, String email, String token) {
        public String bearer() {
            return "Bearer " + token;
        }
    }
}
