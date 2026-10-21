package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.shared.report.CrossServiceCallHelper;
import com.medfund.shared.report.ScheduledRenderRequest;
import com.medfund.shared.security.M2MTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 17 §A.3 — collapses the 8 cross-service adapters onto a single
 * helper: acquire an M2M token, POST the {@link ScheduledRenderRequest},
 * receive the XLSX bytes, guard the whole thing with {@link
 * CrossServiceCallHelper}.
 *
 * <p>Each adapter class stays tiny — it declares its
 * {@code ReportKey / ReportPeriodShape / base URL / path} and hands the
 * fire context off. The scoped permission the M2M token requests is
 * {@code scheduled_report:render}.
 *
 * <p>{@link M2MTokenProvider} is optional — services that don't wire
 * Keycloak M2M credentials (test slices, single-service dev boots)
 * get {@link Optional#empty()} and the helper short-circuits with a
 * clear error rather than silently 401'ing at the owner service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossServiceRenderHelper {

    static final Duration SHAPE_TIMEOUT = Duration.ofSeconds(60);
    static final int MAX_RETRIES = 1;
    static final Duration BACKOFF = Duration.ofSeconds(2);
    static final String M2M_SCOPE = "scheduled_report:render";

    private final WebClient.Builder webClientBuilder;
    private final Optional<M2MTokenProvider> tokenProvider;

    public Mono<byte[]> render(ScheduledFireContext ctx, String baseUrl, String path, String callName) {
        if (tokenProvider.isEmpty()) {
            return Mono.error(new IllegalStateException(
                    "M2MTokenProvider not configured - cannot call " + callName
                            + "; wire keycloak.m2m.client-id / client-secret"));
        }
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        ScheduledRenderRequest body = new ScheduledRenderRequest(
                ctx.tenantId(),
                ctx.periodStart(),
                ctx.periodEnd(),
                ctx.asOf(),
                ctx.reportingCurrency(),
                ctx.cadenceLabel(),
                ctx.scheduleId(),
                ctx.scheduleUpdatedByActorId(),
                ctx.scheduleUpdatedByActorEmail(),
                ctx.scheduleParams());
        List<String> warnings = new ArrayList<>();
        byte[] emptySentinel = new byte[0];
        return tokenProvider.get().getServiceToken(M2M_SCOPE)
                .flatMap(token -> {
                    Mono<byte[]> call = client.post()
                            .uri(path)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(byte[].class);
                    return CrossServiceCallHelper.guarded(
                            callName, call, emptySentinel, warnings,
                            SHAPE_TIMEOUT, MAX_RETRIES, BACKOFF);
                })
                .flatMap(bytes -> {
                    if (bytes.length == 0) {
                        // guarded returned the empty fallback — surface as an error so
                        // the orchestrator classifies + marks failed.
                        String reason = warnings.isEmpty() ? "unknown" : String.join("; ", warnings);
                        return Mono.error(new IllegalStateException(
                                callName + " returned no bytes: " + reason));
                    }
                    return Mono.just(bytes);
                });
    }
}
