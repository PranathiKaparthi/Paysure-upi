package com.paysure.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paysure.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"paysure.seed.enabled=true", "paysure.seed.demo-password=" + IntegrationTest.PASSWORD})
class DemoDataSeederIntegrationTest extends IntegrationTest {

    @Autowired
    DemoDataSeeder seeder;

    @Test
    void seedsLoginableDemoUsersWithFundedAccountsAndVpasIdempotently() throws Exception {
        for (DemoDataSeeder.DemoUser demo : DemoDataSeeder.DEMO_USERS) {
            TestUser user = login(demo.email());
            mvc.perform(get("/api/accounts").header("Authorization", user.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].vpas[0].vpa").value(demo.handle() + "@paysure"))
                    .andExpect(jsonPath("$[0].balancePaise").value(1_000_000));
        }
        TestUser admin = login("labadmin@example.com");
        mvc.perform(get("/api/auth/me").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.role").value("ADMIN_DEMO"));

        Long before = jdbc.queryForObject("select count(*) from ledger_entries", Long.class);
        seeder.run(new DefaultApplicationArguments());
        Long after = jdbc.queryForObject("select count(*) from ledger_entries", Long.class);
        assertThat(after).as("re-running the seeder must not create or fund anything").isEqualTo(before);
        assertLedgerInvariants();
    }

    @Test
    void openApiDocumentIsServed() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/accounts']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists());
    }

    @Test
    void requestIdIsEchoedAndIncludedInProblems() throws Exception {
        mvc.perform(post("/api/auth/login").header("X-Request-Id", "demo-req-12345")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.com\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").value("demo-req-12345"));
    }
}
