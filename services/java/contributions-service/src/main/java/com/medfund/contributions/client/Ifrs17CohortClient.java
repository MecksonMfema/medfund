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
 * Thin wrapper around the user-service cohort lock-in endpoint (Phase 15
 * §6 / I18). Called from {@code PolicyIssuedConsumer} after the earning
 * schedule has been written so the cohort's yield curve gets locked at
 * the coverage start of the first policy in the cohort. Idempotent on
 * both ends — subsequent calls after the first are cheap no-ops.
 *
 * <p>Failure semantics mirror {@link UserServiceClient}: network / non-2xx
 * / malformed → log + {@link Mono#empty()}. The lock-in is not required
 * for {@code earning_schedule} projection to succeed; treating it as
 * best-effort prevents a user-service outage from blocking premium
 * earning. Application-level backfill (a startup task) can populate any
 * cohorts that were missed.
 */
@Slf4j
@Component
public class Ifrs17CohortClient {

    private final WebClient http;

    public Ifrs17CohortClient(WebClient.Builder builder,
                              @Value("${services.user.base-url:http://localhost:8082}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public Mono<Void> lockInIfFirstPolicy(UUID cohortId, String currency, LocalDate effectiveDate) {
        if (cohortId == null || currency == null || currency.isBlank() || effectiveDate == null) {
            return Mono.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", currency);
        body.put("effectiveDate", effectiveDate.toString());
        String path = "/api/v1/underwriting/cohorts/" + cohortId + "/lock-in-yield-curve";
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .then()
                    .doOnError(err -> log.warn("Ifrs17CohortClient lock-in {} failed: {}",
                            cohortId, err.getMessage()))
                    .onErrorResume(err -> Mono.empty());
        });
    }
}
