package com.paysure.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.paysure.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Mandatory case 24: JWT validation and authorization, plus registration/login behaviour. */
class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void registerLoginAndFetchProfile() throws Exception {
        TestUser user = registerAndLogin("ananya");

        mvc.perform(get("/api/auth/me").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.email()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String storedHash = jdbc.queryForObject("select password_hash from users where id = ?", String.class, user.id());
        assertThat(storedHash).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void emailIsCaseInsensitiveAndUnique() throws Exception {
        String email = uniqueEmail("dup");
        register(email).andExpect(status().isCreated());
        register(email.toUpperCase())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registrationValidatesInput() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "not-an-email", "password", "short", "fullName", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fullName")));
    }

    @Test
    void malformedJsonReturnsProblem() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void loginFailuresAreGenericAndIdentical() throws Exception {
        TestUser user = registerAndLogin("rahul");

        String wrongPassword = loginBody(user.email(), "WrongPass1");
        String unknownUser = loginBody(uniqueEmail("ghost"), "WrongPass1");

        assertThat(readJson(wrongPassword).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(readJson(wrongPassword).get("detail").asText())
                .isEqualTo(readJson(unknownUser).get("detail").asText());
    }

    @Test
    void missingTokenIs401WithProblemBody() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void garbageTokenIs401() throws Exception {
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void expiredTokenIs401() throws Exception {
        TestUser user = registerAndLogin("expired");
        Instant past = Instant.now().minusSeconds(3600);
        String token = sign(jwtEncoder, claims(user.id(), List.of("USER")).issuedAt(past.minusSeconds(60)).expiresAt(past).build());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKeyIs401() throws Exception {
        TestUser user = registerAndLogin("forged");
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "attacker-controlled-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        // Even claiming ADMIN_DEMO, a token not signed with our key must be rejected.
        String token = sign(attacker, claims(user.id(), List.of("ADMIN_DEMO")).build());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithWrongIssuerIs401() throws Exception {
        TestUser user = registerAndLogin("issuer");
        String token = sign(jwtEncoder, claims(user.id(), List.of("USER")).issuer("someone-else").build());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userRoleCannotUseDevToolsButAdminDemoCan() throws Exception {
        TestUser user = registerAndLogin("plain");
        String body = json.writeValueAsString(Map.of("amountPaise", 100));
        mvc.perform(post("/api/dev/accounts/{id}/top-up", UUID.randomUUID())
                        .header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        TestUser admin = adminDemo();
        mvc.perform(post("/api/dev/accounts/{id}/top-up", UUID.randomUUID())
                        .header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicRegistrationCannotEscalateRole() throws Exception {
        String email = uniqueEmail("sneaky");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD,
                                "fullName", "Sneaky", "role", "ADMIN_DEMO"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void healthIsPublicButMetricsEndpointNeedsAdmin() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        TestUser user = registerAndLogin("metrics");
        mvc.perform(get("/actuator/metrics").header("Authorization", user.bearer())).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions register(String email) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD, "fullName", "Dup Tester"))));
    }

    private String loginBody(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    private static JwtClaimsSet.Builder claims(UUID subject, List<String> roles) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder().issuer("paysure-sandbox").subject(subject.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", roles);
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
