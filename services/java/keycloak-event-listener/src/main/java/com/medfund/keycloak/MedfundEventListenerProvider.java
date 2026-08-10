package com.medfund.keycloak;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Publishes Keycloak user-facing auth events to the
 * {@code medfund.security.events} Kafka topic so they appear in the
 * platform audit log.
 *
 * <p>Admin events (role assignments, realm config changes) are intentionally
 * skipped here — those are handled by the Java services via
 * {@code AuditPublisher → medfund.audit.events}.
 *
 * <h3>Tenant scoping</h3>
 * The {@code tenantId} on the outgoing message is resolved from the acting
 * user's Keycloak {@code tenant_id} attribute, not from the realm. The previous
 * implementation used {@code event.getRealmId()} — the Keycloak-internal realm
 * UUID — which, in a shared-realm deployment, was identical for every tenant
 * and therefore cross-tenant-leaked every LOGIN to whichever tenant happened
 * to have a record with an id equal to that realm UUID.
 *
 * <p>When the attribute is absent (super admins, service accounts, users
 * whose Keycloak record hasn't been backfilled), the outgoing {@code tenantId}
 * is empty. The audit-service store's {@code tenant_id = $1} predicate will
 * then never match a tenant-scoped query — the row is only reachable via the
 * platform-scope query, which is the correct behaviour for a platform-level
 * authentication event.
 */
public class MedfundEventListenerProvider implements EventListenerProvider {

    /** Keycloak user attribute holding the business tenant UUID. */
    static final String TENANT_ID_ATTRIBUTE = "tenant_id";

    /** The set of Keycloak event types we want to surface in the audit log. */
    private static final Set<EventType> TRACKED = Set.of(
            EventType.LOGIN,
            EventType.LOGIN_ERROR,
            EventType.LOGOUT,
            EventType.REGISTER,
            EventType.REGISTER_ERROR,
            EventType.RESET_PASSWORD,
            EventType.RESET_PASSWORD_ERROR,
            EventType.SEND_RESET_PASSWORD,
            EventType.UPDATE_PASSWORD,
            EventType.UPDATE_PASSWORD_ERROR,
            EventType.VERIFY_EMAIL,
            EventType.SEND_VERIFY_EMAIL,
            EventType.UPDATE_EMAIL,
            EventType.UPDATE_PROFILE,
            EventType.IDENTITY_PROVIDER_LOGIN,
            EventType.CODE_TO_TOKEN,
            EventType.CODE_TO_TOKEN_ERROR
    );

    private final KeycloakSession session;
    private final SecurityEventPublisher publisher;

    public MedfundEventListenerProvider(KeycloakSession session, SecurityEventPublisher publisher) {
        this.session = session;
        this.publisher = publisher;
    }

    @Override
    public void onEvent(Event event) {
        if (!TRACKED.contains(event.getType())) return;

        Map<String, String> details = event.getDetails() != null
                ? event.getDetails() : Map.of();

        // Keycloak puts the username in details["username"] and email in details["email"].
        // Use email when available, fall back to username, fall back to userId.
        String username = details.getOrDefault("username", "");
        String email    = details.getOrDefault("email",
                username.isEmpty() ? (event.getUserId() != null ? event.getUserId() : "") : username);

        SecurityEventMessage msg = new SecurityEventMessage(
                UUID.randomUUID().toString(),
                resolveTenantId(event),
                event.getType().name(),
                event.getUserId() != null ? event.getUserId() : "",
                email,
                event.getIpAddress() != null ? event.getIpAddress() : "",
                details.getOrDefault("user_agent", ""),
                serializeDetails(details),
                Instant.ofEpochMilli(event.getTime()).toString()
        );

        publisher.publish(msg);
    }

    /**
     * Reads the acting user's {@code tenant_id} attribute from Keycloak.
     * Returns {@code ""} when the event has no user id (e.g. a pre-auth
     * LOGIN_ERROR), when the user can't be loaded, or when the attribute is
     * absent — never falls back to a realm-level identifier.
     */
    private String resolveTenantId(Event event) {
        if (event.getUserId() == null || event.getRealmId() == null) {
            return "";
        }
        RealmModel realm = session.realms().getRealm(event.getRealmId());
        if (realm == null) {
            return "";
        }
        UserModel user = session.users().getUserById(realm, event.getUserId());
        if (user == null) {
            return "";
        }
        String tenantId = user.getFirstAttribute(TENANT_ID_ATTRIBUTE);
        return tenantId != null ? tenantId : "";
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        // Intentionally no-op — see class-level Javadoc.
    }

    @Override
    public void close() {}

    private String serializeDetails(Map<String, String> details) {
        if (details.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : details.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escape(e.getKey()))
              .append("\":\"").append(escape(e.getValue())).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
