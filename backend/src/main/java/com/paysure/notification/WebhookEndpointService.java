package com.paysure.notification;

import com.paysure.common.error.BadRequestException;
import com.paysure.common.error.BusinessRuleException;
import com.paysure.common.error.NotFoundException;
import com.paysure.config.SecurityProperties;
import com.paysure.events.EventTypes;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Webhook endpoint registration. Secrets are derived on demand and shown to the owner only at create/rotate time. */
@Service
public class WebhookEndpointService {

    private static final Pattern TYPE = Pattern.compile("^[a-z]+\\.[a-z]+$");

    private final JdbcTemplate jdbc;
    private final SsrfGuard ssrf;
    private final WebhookProperties props;
    private final SecurityProperties security;

    public WebhookEndpointService(JdbcTemplate jdbc, SsrfGuard ssrf, WebhookProperties props, SecurityProperties security) {
        this.jdbc = jdbc;
        this.ssrf = ssrf;
        this.props = props;
        this.security = security;
    }

    public record Endpoint(UUID id, UUID ownerId, String url, List<String> eventTypes, String description, boolean enabled,
                           int secretVersion, Instant createdAt) {
    }

    public record Created(Endpoint endpoint, String secret) {
    }

    @Transactional
    public Created create(UUID ownerId, String url, List<String> eventTypes, String description) {
        Long count = jdbc.queryForObject("select count(*) from webhook_endpoints where owner_user_id = ?", Long.class, ownerId);
        if (count != null && count >= props.maxEndpointsPerUser()) {
            throw new BusinessRuleException("WEBHOOK_LIMIT_REACHED", "At most " + props.maxEndpointsPerUser() + " webhook endpoints per user.");
        }
        ssrf.validate(url); // throws WEBHOOK_URL_BLOCKED
        String types = normaliseTypes(eventTypes);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into webhook_endpoints (id, owner_user_id, url, event_types, description) values (?, ?, ?, ?, ?)",
                id, ownerId, url.trim(), types, description == null || description.isBlank() ? null : description.trim());
        Endpoint e = get(ownerId, id);
        return new Created(e, secretFor(e));
    }

    public List<Endpoint> list(UUID ownerId) {
        return jdbc.query("select * from webhook_endpoints where owner_user_id = ? order by created_at", (rs, i) -> map(rs), ownerId);
    }

    public Endpoint get(UUID ownerId, UUID id) {
        return jdbc.query("select * from webhook_endpoints where id = ? and owner_user_id = ?", (rs, i) -> map(rs), id, ownerId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("WEBHOOK_ENDPOINT_NOT_FOUND", "Webhook endpoint not found."));
    }

    @Transactional
    public Endpoint setEnabled(UUID ownerId, UUID id, boolean enabled) {
        get(ownerId, id);
        jdbc.update("update webhook_endpoints set enabled = ?, updated_at = now() where id = ?", enabled, id);
        return get(ownerId, id);
    }

    /** Invalidates the old secret immediately; queued deliveries are signed with the new one when they are sent. */
    @Transactional
    public Created rotateSecret(UUID ownerId, UUID id) {
        get(ownerId, id);
        jdbc.update("update webhook_endpoints set secret_version = secret_version + 1, updated_at = now() where id = ?", id);
        Endpoint e = get(ownerId, id);
        return new Created(e, secretFor(e));
    }

    /** The signing secret of an endpoint (never stored; derived from the server key). */
    public String secretFor(Endpoint e) {
        return secretFor(e.id(), e.secretVersion());
    }

    public String secretFor(UUID endpointId, int version) {
        String key = props.secretKey() == null || props.secretKey().isBlank() ? security.jwt().secret() : props.secretKey();
        return WebhookSigner.deriveSecret(key, endpointId, version);
    }

    private static String normaliseTypes(List<String> types) {
        if (types == null || types.isEmpty() || types.contains("*")) {
            return "*";
        }
        List<String> clean = types.stream().map(String::trim).distinct().toList();
        for (String t : clean) {
            if (!TYPE.matcher(t).matches() || !EventTypes.ALL.contains(t)) {
                throw new BadRequestException("INVALID_EVENT_TYPE", "Unknown event type: " + t + ". Known: " + EventTypes.ALL);
            }
        }
        return String.join(",", clean);
    }

    private static Endpoint map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String types = rs.getString("event_types");
        return new Endpoint(rs.getObject("id", UUID.class), rs.getObject("owner_user_id", UUID.class), rs.getString("url"),
                "*".equals(types) ? List.of("*") : Arrays.asList(types.split(",")), rs.getString("description"),
                rs.getBoolean("enabled"), rs.getInt("secret_version"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
