package com.medfund.tenancy.service;

import com.medfund.tenancy.entity.PlatformSettings;
import com.medfund.tenancy.entity.Tenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages Keycloak realms for tenants via the Keycloak Admin REST API.
 * Creates realm, OIDC clients (Angular + Flutter), and default roles.
 */
@Slf4j
@Service
public class KeycloakRealmService {

    private final WebClient webClient;

    @Value("${keycloak.admin.url:http://localhost:9080}")
    private String keycloakUrl;

    @Value("${keycloak.admin.username:admin}")
    private String adminUsername;

    @Value("${keycloak.admin.password:admin}")
    private String adminPassword;

    /** Realm the super-admin portal authenticates against. */
    @Value("${keycloak.platform-realm:medfund-platform}")
    private String platformRealm;

    /**
     * Origin the browser uses to reach the platform. The logo endpoint is
     * served relative to the gateway, but Keycloak renders the login page on
     * its own origin, so the {@code <img src>} it embeds has to be absolute.
     */
    @Value("${platform.public-base-url:http://localhost:3000}")
    private String publicBaseUrl;

    public KeycloakRealmService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public Mono<Void> createRealm(String realmName, Tenant tenant) {
        return getAdminToken()
                .flatMap(token -> createRealmRequest(token, realmName, tenant))
                .doOnSuccess(v -> log.info("Keycloak realm created: {}", realmName))
                .doOnError(e -> log.error("Failed to create Keycloak realm: {}", realmName, e))
                .onErrorResume(e -> {
                    log.warn("Keycloak realm creation failed for {}. Will retry on next startup.", realmName);
                    return Mono.empty();
                });
    }

    private Mono<String> getAdminToken() {
        return webClient.post()
                .uri(keycloakUrl + "/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("grant_type=password&client_id=admin-cli&username=" + adminUsername + "&password=" + adminPassword)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> (String) response.get("access_token"));
    }

    private Mono<Void> createRealmRequest(String token, String realmName, Tenant tenant) {
        Map<String, Object> realmConfig = new HashMap<>();
        realmConfig.put("realm", realmName);
        realmConfig.put("enabled", true);
        realmConfig.put("displayName", tenant.getName());
        realmConfig.put("loginTheme", "keycloak");
        realmConfig.put("registrationAllowed", false);
        realmConfig.put("resetPasswordAllowed", true);
        realmConfig.put("bruteForceProtected", true);
        realmConfig.put("permanentLockout", false);
        realmConfig.put("maxFailureWaitSeconds", 900);
        realmConfig.put("minimumQuickLoginWaitSeconds", 60);
        realmConfig.put("waitIncrementSeconds", 60);
        realmConfig.put("quickLoginCheckMilliSeconds", 1000);
        realmConfig.put("maxDeltaTimeSeconds", 43200);
        realmConfig.put("failureFactor", 5);
        // Seed `tenant_admin` and `group_liaison`. tenant_admin is the only
        // operational role pre-seeded; everything else is tenant-defined via
        // the Roles & Permissions UI (createRealmRole() mirrors them). The
        // group_liaison role is platform-defined (not tenant-customisable):
        // it backs the group-portal login flow for liaisons who manage their
        // group's invoices. Members and pure-liaison Keycloak users receive
        // this role via GroupService.grantLiaisonRole when assigned to a
        // group — the role needs to exist in the realm first.
        realmConfig.put("roles", Map.of("realm", List.of(
                Map.of("name", "tenant_admin", "description", "Tenant administrator"),
                Map.of("name", "group_liaison", "description", "Manages their group's invoices via the group portal")
        )));

        return webClient.post()
                .uri(keycloakUrl + "/admin/realms")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(realmConfig)
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    // ── Realm-role CRUD (called from RoleController on tenant role mutations) ───

    /**
     * Create a realm role in the tenant's realm. Idempotent — a 409 from
     * Keycloak (role already exists) resolves to {@code Mono.empty()} so the
     * call is safe to retry.
     */
    public Mono<Void> createRealmRole(String realmName, String roleName, String description) {
        return getAdminToken().flatMap(token -> webClient.post()
                .uri(keycloakUrl + "/admin/realms/" + realmName + "/roles")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", roleName,
                        "description", description != null ? description : ""))
                .retrieve()
                .toBodilessEntity()
                .then()
                .onErrorResume(WebClientResponseException.class, e ->
                        e.getStatusCode() == HttpStatus.CONFLICT ? Mono.empty() : Mono.error(e)));
    }

    /**
     * Delete a realm role. Idempotent — 404 (role missing) resolves cleanly.
     * Any user-role assignments to this realm role are removed by Keycloak
     * automatically when the role is deleted.
     */
    public Mono<Void> deleteRealmRole(String realmName, String roleName) {
        return getAdminToken().flatMap(token -> webClient.delete()
                .uri(keycloakUrl + "/admin/realms/" + realmName + "/roles/" + roleName)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity()
                .then()
                .onErrorResume(WebClientResponseException.class, e ->
                        e.getStatusCode() == HttpStatus.NOT_FOUND ? Mono.empty() : Mono.error(e)));
    }

    /**
     * Assign a realm role to a user. Keycloak's role-mappings endpoint is
     * additive — calling it for a role the user already has is a no-op.
     */
    public Mono<Void> assignRealmRoleToUser(String realmName, String userId, String roleName) {
        return getAdminToken().flatMap(token -> resolveRealmRole(token, realmName, roleName)
                .flatMap(role -> webClient.post()
                        .uri(keycloakUrl + "/admin/realms/" + realmName
                                + "/users/" + userId + "/role-mappings/realm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(List.of(role))
                        .retrieve()
                        .toBodilessEntity()
                        .then()));
    }

    /** Revoke a realm role from a user. 404 (mapping absent) is treated as success. */
    public Mono<Void> revokeRealmRoleFromUser(String realmName, String userId, String roleName) {
        return getAdminToken().flatMap(token -> resolveRealmRole(token, realmName, roleName)
                .flatMap(role -> webClient.method(org.springframework.http.HttpMethod.DELETE)
                        .uri(keycloakUrl + "/admin/realms/" + realmName
                                + "/users/" + userId + "/role-mappings/realm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(List.of(role))
                        .retrieve()
                        .toBodilessEntity()
                        .then()
                        .onErrorResume(WebClientResponseException.class, e ->
                                e.getStatusCode() == HttpStatus.NOT_FOUND ? Mono.empty() : Mono.error(e))));
    }

    /**
     * Look up a realm role by name. Required because the role-mappings
     * endpoint takes a {@code RoleRepresentation} (object with id+name), not
     * just a name string.
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> resolveRealmRole(String token, String realmName, String roleName) {
        return webClient.get()
                .uri(keycloakUrl + "/admin/realms/" + realmName + "/roles/" + roleName)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m);
    }

    // ── Platform realm branding (called on every platform-settings mutation) ───

    /**
     * Patch the platform realm so the Keycloak-hosted login screen reflects
     * the saved platform branding. Keycloak's realm update applies only the
     * fields present in the payload, so this is a partial patch and leaves
     * every other realm setting alone.
     *
     * <p>Best-effort by design: a Keycloak outage must not fail the admin's
     * save. The row is already committed and the audit event already emitted
     * by the time this runs, so a failure is logged and swallowed, matching
     * {@link #createRealm}.
     */
    public Mono<Void> updatePlatformRealmBranding(PlatformSettings settings) {
        Map<String, Object> realmPatch = new HashMap<>();
        if (settings.getPlatformName() != null) {
            realmPatch.put("displayName", settings.getPlatformName());
        }
        String htmlName = buildDisplayNameHtml(settings);
        if (htmlName != null) {
            realmPatch.put("displayNameHtml", htmlName);
        }
        if (realmPatch.isEmpty()) {
            return Mono.empty();
        }

        return getAdminToken()
                .flatMap(token -> webClient.put()
                        .uri(keycloakUrl + "/admin/realms/" + platformRealm)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(realmPatch)
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .doOnSuccess(v -> log.info("Keycloak realm branding synced: {}", platformRealm))
                .onErrorResume(e -> {
                    log.warn("Keycloak realm branding sync failed for {}; "
                            + "settings persisted, login screen unchanged", platformRealm, e);
                    return Mono.empty();
                });
    }

    /**
     * Hero block rendered above the login form by Keycloak's default theme.
     * {@code displayNameHtml} is emitted raw by the theme, so every operator
     * supplied value is HTML-escaped here. The logo src is server-derived
     * (see {@link PlatformSettings#logoPath()}) and made absolute against the
     * gateway origin because Keycloak serves the login page from its own.
     */
    private String buildDisplayNameHtml(PlatformSettings s) {
        var sb = new StringBuilder();
        String logoPath = s.logoPath();
        if (logoPath != null) {
            sb.append("<img src=\"").append(HtmlUtils.htmlEscape(publicBaseUrl + logoPath))
                    .append("\" alt=\"").append(HtmlUtils.htmlEscape(
                            s.getPlatformName() != null ? s.getPlatformName() : "logo"))
                    .append("\" />");
        }
        if (s.getHeroTitle() != null && !s.getHeroTitle().isBlank()) {
            sb.append("<h1>").append(HtmlUtils.htmlEscape(s.getHeroTitle())).append("</h1>");
        }
        if (s.getHeroSubtitle() != null && !s.getHeroSubtitle().isBlank()) {
            sb.append("<p>").append(HtmlUtils.htmlEscape(s.getHeroSubtitle())).append("</p>");
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
