package com.medfund.contributions.client;

import com.medfund.shared.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thin wrapper around the user-service member/group action endpoints.
 * Used by {@code ArrearsEscalationExecutor} to auto-suspend, deactivate,
 * or reactivate an owner when its aged balance crosses a dunning
 * threshold — the contributions-service owns balances but the member/
 * group status columns live in user-service, so a Kafka event would be
 * overkill for what is a single idempotent HTTP call.
 *
 * <p>Failure semantics match {@link com.medfund.contributions.service.AiPricingClient}:
 * network / non-2xx / malformed → log + {@code Mono.empty()}. The
 * escalation job runs daily and is designed to be tried again — one
 * failed row must not block the rest.
 */
@Slf4j
@Component
public class UserServiceClient {

    private final WebClient http;

    public UserServiceClient(WebClient.Builder builder,
                              @Value("${services.user.base-url:http://localhost:8082}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public Mono<Void> suspendMember(UUID memberId, LocalDate effectiveDate, String reason) {
        return callMemberAction(memberId, "suspend", effectiveDate, reason);
    }

    public Mono<Void> deactivateMember(UUID memberId, LocalDate effectiveDate, String reason) {
        return callMemberAction(memberId, "deactivate", effectiveDate, reason);
    }

    public Mono<Void> reactivateMember(UUID memberId, String reason) {
        return callMemberAction(memberId, "activate", null, reason);
    }

    public Mono<Void> suspendGroup(UUID groupId, LocalDate effectiveDate, String reason) {
        return callGroupAction(groupId, "suspend", effectiveDate, reason);
    }

    public Mono<Void> deactivateGroup(UUID groupId, LocalDate effectiveDate, String reason) {
        return callGroupAction(groupId, "deactivate", effectiveDate, reason);
    }

    public Mono<Void> reactivateGroup(UUID groupId, String reason) {
        return callGroupAction(groupId, "activate", null, reason);
    }

    private Mono<Void> callMemberAction(UUID id, String action, LocalDate effectiveDate, String reason) {
        return callAction("/api/v1/members/" + id + "/actions/" + action, effectiveDate, reason);
    }

    private Mono<Void> callGroupAction(UUID id, String action, LocalDate effectiveDate, String reason) {
        return callAction("/api/v1/groups/" + id + "/actions/" + action, effectiveDate, reason);
    }

    private Mono<Void> callAction(String path, LocalDate effectiveDate, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (effectiveDate != null) body.put("effectiveDate", effectiveDate.toString());
        if (reason != null && !reason.isBlank()) body.put("reason", reason);
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .then()
                    .doOnError(err -> log.warn("UserService call {} failed: {}", path, err.getMessage()))
                    .onErrorResume(err -> Mono.empty());
        });
    }
}
