package com.medfund.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class KeycloakSyncService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakSyncService.class);

    private final WebClient webClient;

    @Value("${keycloak.admin.url:http://localhost:9080}")
    private String keycloakUrl;

    @Value("${keycloak.admin.username:admin}")
    private String adminUsername;

    @Value("${keycloak.admin.password:admin}")
    private String adminPassword;

    @Value("${keycloak.client.id:medfund-web}")
    private String keycloakClientId;

    public KeycloakSyncService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public Mono<String> createUser(String realm, String email, String firstName, String lastName, List<String> roles) {
        return getAdminToken()
            .flatMap(token -> createKeycloakUser(token, realm, email, firstName, lastName))
            .flatMap(userId -> {
                if (roles != null && !roles.isEmpty()) {
                    return assignRealmRoles(realm, userId, roles).thenReturn(userId);
                }
                return Mono.just(userId);
            })
            .doOnError(e -> log.warn("Keycloak user creation failed for {}: {}", email, e.getMessage()))
            .onErrorResume(e -> Mono.empty());
    }

    public Mono<Void> assignRealmRoles(String realm, String keycloakUserId, List<String> roleNames) {
        return getAdminToken()
            .flatMap(token -> {
                Mono<Void> chain = Mono.empty();
                for (String roleName : roleNames) {
                    chain = chain.then(assignSingleRole(token, realm, keycloakUserId, roleName));
                }
                return chain;
            })
            .doOnError(e -> log.warn("Role assignment failed for user {}: {}", keycloakUserId, e.getMessage()))
            .onErrorResume(e -> Mono.empty());
    }

    public Mono<Void> removeRealmRole(String realm, String keycloakUserId, String roleName) {
        return getAdminToken()
            .flatMap(token -> webClient.get()
                .uri(keycloakUrl + "/admin/realms/{realm}/roles/{roleName}", realm, roleName)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(role -> webClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(keycloakUrl + "/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                        realm, keycloakUserId)
                    .header("Authorization", "Bearer " + token)
                    .bodyValue(List.of(role))
                    .retrieve()
                    .bodyToMono(Void.class)))
            .doOnError(e -> log.warn("Role removal failed: {}", e.getMessage()))
            .onErrorResume(e -> Mono.empty());
    }

    /**
     * Triggers Keycloak to send an invite email so the new user can set their password.
     * Keycloak must have SMTP configured (Mailpit on port 1025 in local dev).
     * Errors are propagated — the caller receives a failure if email delivery cannot be initiated.
     */
    public Mono<Void> sendInviteEmail(String realm, String keycloakUserId) {
        // lifespan = how long the magic link in the email stays valid (seconds).
        // Mirrors StaffUserService.INVITE_TTL — kept here as a literal to avoid
        // a cross-package import in this thin Keycloak adapter.
        long lifespanSeconds = java.time.Duration.ofDays(7).toSeconds();
        String path = String.format(
                "%s/admin/realms/%s/users/%s/execute-actions-email?client_id=%s&lifespan=%d",
                keycloakUrl, realm, keycloakUserId, keycloakClientId, lifespanSeconds);
        return getAdminToken()
            .flatMap(token -> webClient.put()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .bodyValue(List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"))
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                    .flatMap(body -> Mono.error(new RuntimeException(
                        "Keycloak execute-actions-email failed [" + response.statusCode() + "]: " + body))))
                .bodyToMono(Void.class))
            .doOnSuccess(v -> log.info("Invite email triggered for Keycloak user {} in realm {}", keycloakUserId, realm))
            .doOnError(e -> log.error("Failed to send invite email to Keycloak user {}: {}", keycloakUserId, e.getMessage()));
    }

    public Mono<Void> disableUser(String realm, String keycloakUserId) {
        return getAdminToken()
            .flatMap(token -> webClient.put()
                .uri(keycloakUrl + "/admin/realms/{realm}/users/{userId}", realm, keycloakUserId)
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of("enabled", false))
                .retrieve()
                .bodyToMono(Void.class))
            .doOnError(e -> log.warn("Failed to disable Keycloak user {}: {}", keycloakUserId, e.getMessage()))
            .onErrorResume(e -> Mono.empty());
    }

    public Mono<Void> enableUser(String realm, String keycloakUserId) {
        return getAdminToken()
            .flatMap(token -> webClient.put()
                .uri(keycloakUrl + "/admin/realms/{realm}/users/{userId}", realm, keycloakUserId)
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of("enabled", true))
                .retrieve()
                .bodyToMono(Void.class))
            .doOnError(e -> log.warn("Failed to enable Keycloak user {}: {}", keycloakUserId, e.getMessage()))
            .onErrorResume(e -> Mono.empty());
    }

    private Mono<String> getAdminToken() {
        return webClient.post()
            .uri(keycloakUrl + "/realms/master/protocol/openid-connect/token")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .bodyValue("grant_type=password&client_id=admin-cli&username=" + adminUsername + "&password=" + adminPassword)
            .retrieve()
            .bodyToMono(Map.class)
            .map(response -> (String) response.get("access_token"));
    }

    @SuppressWarnings("unchecked")
    private Mono<String> createKeycloakUser(String token, String realm, String email, String firstName, String lastName) {
        Map<String, Object> userRepresentation = Map.of(
            "username", email,
            "email", email,
            "firstName", firstName,
            "lastName", lastName,
            "enabled", true,
            "emailVerified", false,
            "requiredActions", List.of("UPDATE_PASSWORD", "VERIFY_EMAIL")
        );

        return webClient.post()
            .uri(keycloakUrl + "/admin/realms/{realm}/users", realm)
            .header("Authorization", "Bearer " + token)
            .bodyValue(userRepresentation)
            .retrieve()
            .toBodilessEntity()
            .map(response -> {
                String location = response.getHeaders().getFirst("Location");
                if (location != null) {
                    return location.substring(location.lastIndexOf('/') + 1);
                }
                throw new RuntimeException("No Location header in Keycloak response");
            });
    }

    private Mono<Void> assignSingleRole(String token, String realm, String userId, String roleName) {
        return webClient.get()
            .uri(keycloakUrl + "/admin/realms/{realm}/roles/{roleName}", realm, roleName)
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .bodyToMono(Map.class)
            .flatMap(role -> webClient.post()
                .uri(keycloakUrl + "/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                    realm, userId)
                .header("Authorization", "Bearer " + token)
                .bodyValue(List.of(role))
                .retrieve()
                .bodyToMono(Void.class))
            .doOnError(e -> log.warn("Failed to assign role {} to user {}: {}", roleName, userId, e.getMessage()))
            .onErrorResume(e -> Mono.empty());
    }
}
