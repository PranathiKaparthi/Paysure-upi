package com.paysure.identity.application;

import com.paysure.identity.domain.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated principal, derived only from a signature-verified JWT. Never from request bodies.
 */
public record Caller(UUID userId, Role role) {

    public static Caller from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        Role role = roles != null && roles.contains(Role.ADMIN_DEMO.name()) ? Role.ADMIN_DEMO : Role.USER;
        return new Caller(UUID.fromString(jwt.getSubject()), role);
    }

    public boolean isAdminDemo() {
        return role == Role.ADMIN_DEMO;
    }
}
