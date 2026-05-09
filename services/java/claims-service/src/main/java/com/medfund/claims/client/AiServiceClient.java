package com.medfund.claims.client;

import com.medfund.claims.dto.AiSignals;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin reactive client over the Python ai-service. Used by
 * {@link com.medfund.claims.service.AdjudicationPipeline} to fetch AI
 * recommendations, fraud risk scores, and duplicate-claim matches in
 * parallel with the deterministic stages.
 *
 * <p><b>Fail-open contract:</b> any timeout, 5xx, or unreachable host
 * returns {@link AiSignals#empty()} rather than failing the pipeline. The
 * decision engine treats absent signals as "no signal" — the deterministic
 * stages still produce a verdict, and the operator sees a banner on the
 * detail page when AI was skipped.
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);
    private static final Duration AI_TIMEOUT = Duration.ofSeconds(8);

    private final WebClient webClient;

    public AiServiceClient(@Value("${ai-service.url:http://localhost:8000}") String aiServiceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(aiServiceUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Run all three AI calls in parallel. Each leg fails open independently —
     * the duplicate check might succeed even if the recommendation engine is
     * unreachable. Returns a populated {@link AiSignals} merging whichever
     * legs returned successfully.
     */
    public Mono<AiSignals> evaluate(Claim claim, List<ClaimLine> lines) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            if (tenantId == null) {
                log.debug("No tenant context — skipping AI evaluation");
                return Mono.just(AiSignals.empty());
            }

            Mono<RecommendationLeg> recommendation = recommend(claim, lines, tenantId)
                    .onErrorResume(e -> {
                        log.warn("AI recommendation failed for claim {}: {}", claim.getId(), e.toString());
                        return Mono.just(RecommendationLeg.empty());
                    });

            Mono<FraudLeg> fraud = checkFraud(claim, lines, tenantId)
                    .onErrorResume(e -> {
                        log.warn("AI fraud check failed for claim {}: {}", claim.getId(), e.toString());
                        return Mono.just(FraudLeg.empty());
                    });

            return Mono.zip(recommendation, fraud)
                    .map(tuple -> merge(tuple.getT1(), tuple.getT2()))
                    .timeout(AI_TIMEOUT)
                    .onErrorResume(e -> {
                        log.warn("AI evaluation timed out for claim {}: {}", claim.getId(), e.toString());
                        return Mono.just(AiSignals.empty());
                    });
        });
    }

    // ── Per-endpoint legs ────────────────────────────────────────────────

    private Mono<RecommendationLeg> recommend(Claim claim, List<ClaimLine> lines, String tenantId) {
        Map<String, Object> body = Map.of(
                "claim_id", String.valueOf(claim.getId()),
                "member_id", String.valueOf(claim.getMemberId()),
                "provider_id", String.valueOf(claim.getProviderId()),
                "diagnosis_codes", parseStringList(claim.getDiagnosisCodes()),
                "procedure_codes", lines.stream()
                        .map(ClaimLine::getTariffCode)
                        .filter(c -> c != null && !c.isBlank())
                        .toList(),
                "claimed_amount", claim.getClaimedAmount() != null
                        ? claim.getClaimedAmount().doubleValue() : 0d,
                "currency_code", claim.getCurrencyCode() != null ? claim.getCurrencyCode() : "USD",
                "claim_type", claim.getClaimType() != null ? claim.getClaimType() : "medical",
                "service_date", claim.getServiceDate() != null
                        ? claim.getServiceDate().toString() : ""
        );
        return webClient.post()
                .uri("/api/v1/ai/adjudication/recommend")
                .header("X-Tenant-ID", tenantId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(AI_TIMEOUT)
                .retryWhen(Retry.backoff(1, Duration.ofMillis(250))
                        .filter(this::isRetryable))
                .map(this::parseRecommendation);
    }

    private Mono<FraudLeg> checkFraud(Claim claim, List<ClaimLine> lines, String tenantId) {
        Map<String, Object> body = Map.of(
                "claim_id", String.valueOf(claim.getId()),
                "member_id", String.valueOf(claim.getMemberId()),
                "provider_id", String.valueOf(claim.getProviderId()),
                "claimed_amount", claim.getClaimedAmount() != null
                        ? claim.getClaimedAmount().doubleValue() : 0d,
                "service_date", claim.getServiceDate() != null
                        ? claim.getServiceDate().toString() : "",
                "diagnosis_codes", parseStringList(claim.getDiagnosisCodes()),
                "procedure_codes", lines.stream()
                        .map(ClaimLine::getTariffCode)
                        .filter(c -> c != null && !c.isBlank())
                        .toList()
        );
        return webClient.post()
                .uri("/api/v1/ai/fraud/check")
                .header("X-Tenant-ID", tenantId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(AI_TIMEOUT)
                .retryWhen(Retry.backoff(1, Duration.ofMillis(250))
                        .filter(this::isRetryable))
                .map(this::parseFraud);
    }

    // ── Parsing & merging ────────────────────────────────────────────────

    private RecommendationLeg parseRecommendation(Map<?, ?> response) {
        String recommendation = stringField(response, "recommendation");
        Double confidence = doubleField(response, "confidence");
        Double suggested = doubleField(response, "approved_amount");
        BigDecimal suggestedBd = suggested != null ? BigDecimal.valueOf(suggested) : null;
        String reasoning = stringField(response, "reasoning");
        List<String> flags = stringListField(response, "flags");
        String version = stringField(response, "model_version");
        return new RecommendationLeg(recommendation, confidence, suggestedBd, reasoning, flags, version);
    }

    private FraudLeg parseFraud(Map<?, ?> response) {
        Double risk = doubleField(response, "risk_score");
        String level = stringField(response, "risk_level");
        List<String> indicators = stringListField(response, "indicators");
        String version = stringField(response, "model_version");
        return new FraudLeg(risk, level, indicators, version);
    }

    private AiSignals merge(RecommendationLeg rec, FraudLeg fraud) {
        // Prefer recommendation's model version since it's the headline call.
        String version = rec.modelVersion() != null ? rec.modelVersion() : fraud.modelVersion();
        return new AiSignals(
                rec.recommendation(),
                rec.confidence(),
                rec.suggestedAmount(),
                rec.reasoning(),
                rec.flags() != null ? rec.flags() : List.of(),
                fraud.riskScore(),
                fraud.riskLevel(),
                fraud.indicators() != null ? fraud.indicators() : List.of(),
                null,
                null,
                version);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean isRetryable(Throwable t) {
        // Retry once on network/timeout failures; never retry on 4xx (the
        // request body is wrong and won't get better with another try).
        return !(t instanceof org.springframework.web.reactive.function.client.WebClientResponseException ex
                && ex.getStatusCode().is4xxClientError());
    }

    private String stringField(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private Double doubleField(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignore) {}
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringListField(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) if (item != null) out.add(item.toString());
            return out;
        }
        return List.of();
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        // Most claims store diagnosis_codes as a JSON array; fall back to
        // comma-separated for legacy rows. This is best-effort — bad data
        // returns an empty list rather than throwing.
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception ignore) { return List.of(); }
        }
        return List.of(trimmed.split("\\s*,\\s*"));
    }

    // ── Per-leg holders (internal) ──────────────────────────────────────

    private record RecommendationLeg(
            String recommendation, Double confidence, BigDecimal suggestedAmount,
            String reasoning, List<String> flags, String modelVersion) {
        static RecommendationLeg empty() { return new RecommendationLeg(null, null, null, null, List.of(), null); }
    }

    private record FraudLeg(Double riskScore, String riskLevel, List<String> indicators, String modelVersion) {
        static FraudLeg empty() { return new FraudLeg(null, null, List.of(), null); }
    }
}
