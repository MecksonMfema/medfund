package com.medfund.tenancy.service;

import com.medfund.tenancy.entity.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages Keycloak realms for tenants via the Keycloak Admin REST API.
 * Creates realm, OIDC clients (Angular + Flutter), and default roles.
 */
@Service
public class KeycloakRealmService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakRealmService.class);

    private final WebClient webClient;

    @Value("${keycloak.admin.url:http://localhost:9080}")
    private String keycloakUrl;

    @Value("${keycloak.admin.username:admin}")
    private String adminUsername;

    @Value("${keycloak.admin.password:admin}")
    private String adminPassword;

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
        // Seed only `tenant_admin` — every other role is tenant-defined via the
        // Roles & Permissions UI, which calls createRealmRole() to mirror new
        // roles into Keycloak. Pre-seeding extra realm roles would create dead
        // roles that no DB row references.
        realmConfig.put("roles", Map.of("realm", List.of(
                Map.of("name", "tenant_admin", "description", "Tenant administrator")
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
}
