package com.medfund.contributions.service;

import com.medfund.contributions.entity.Contribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Calls the AI service's per-member risk-scorer (Phase C). Returns a
 * {@code Mono<Double>} multiplier the billing engine multiplies into
 * the base contribution amount.
 *
 * <p>Failure semantics: any network error, non-2xx response, or
 * malformed payload yields {@code Mono.empty()} — the caller treats
 * that as "no adjustment, use the base price". The billing run is
 * never blocked by an unreachable AI service.
 *
 * <p>Member-signal lookup hits members.medical_history JSONB directly
 * via DatabaseClient so we don't carry every signal through the
 * pricing pipeline. One query per contribution under AI_DRIVEN mode
 * is acceptable for the MVP; if it becomes a hot path the natural
 * next step is batching members per period into a single round-trip
 * to the scorer.
 */
@Component
public class AiPricingClient {

    private static final Logger log = LoggerFactory.getLogger(AiPricingClient.class);

    private final WebClient http;
    private final DatabaseClient db;

    public AiPricingClient(@Value("${ai.service.url:http://localhost:8000}") String aiServiceUrl,
                            DatabaseClient db) {
        this.http = WebClient.builder().baseUrl(aiServiceUrl).build();
        this.db = db;
    }

    /**
     * Score a contribution. Looks up the member's medical history
     * (chronic conditions, smoking, BMI, medications), wraps it with
     * the base amount + currency, POSTs to /api/v1/pricing/score, and
     * returns the {@code multiplier} field from the response.
     */
    public Mono<Double> score(Contribution c) {
        if (c.getMemberId() == null || c.getAmount() == null) return Mono.empty();
        return loadMemberSignals(c.getMemberId()).flatMap(signals -> resolveInsuranceLine(c.getSchemeId())
                .flatMap(line -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("member_id",       c.getMemberId().toString());
                    body.put("tenant_id",       "unknown"); // populated downstream from TenantContext
                    body.put("insurance_line",  line);
                    body.put("base_amount",     c.getAmount().doubleValue());
                    body.put("currency_code",   c.getCurrencyCode() != null ? c.getCurrencyCode() : "USD");
                    // Line-agnostic attribute bag — the line's scorer reads
                    // whatever keys it knows. HEALTH signals from medical_history
                    // go here verbatim; future MOTOR/PROPERTY builders populate
                    // vehicle.* / property.* keys the same way.
                    body.put("attributes",      signals);
                    return http.post()
                            .uri("/api/v1/pricing/score")
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .map(resp -> {
                                Object m = resp.get("multiplier");
                                if (m instanceof Number n) return n.doubleValue();
                                return 1.0;
                            });
                }));
    }

    /**
     * Resolve the scheme's insurance_line so the scorer can pick the
     * right line-specific function. Falls back to HEALTH on lookup
     * failures — that's the only line shipped today and the safest
     * default if a tenant somehow has a scheme with a null/missing
     * line column.
     */
    private Mono<String> resolveInsuranceLine(UUID schemeId) {
        if (schemeId == null) return Mono.just("HEALTH");
        return db.sql("SELECT insurance_line FROM schemes WHERE id = :id")
                .bind("id", schemeId)
                .map(row -> {
                    Object v = row.get("insurance_line");
                    return v != null ? v.toString() : "HEALTH";
                })
                .one()
                .defaultIfEmpty("HEALTH")
                .onErrorReturn("HEALTH");
    }

    /**
     * Project the member's signals from the database into the field
     * shape ai-service /pricing/score expects. Returns empty values
     * when medical_history is null — the scorer treats absent signals
     * as "no contribution to the multiplier".
     */
    private Mono<Map<String, Object>> loadMemberSignals(UUID memberId) {
        return db.sql("""
                SELECT date_of_birth, gender, medical_history
                  FROM members
                 WHERE id = :memberId
                """)
                .bind("memberId", memberId)
                .map((row, meta) -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    Object dob = row.get("date_of_birth");
                    if (dob instanceof java.time.LocalDate d) {
                        out.put("age", (int) java.time.temporal.ChronoUnit.YEARS.between(d, java.time.LocalDate.now()));
                    }
                    Object g = row.get("gender");
                    if (g != null) out.put("gender", g.toString());
                    Object mh = row.get("medical_history");
                    if (mh != null) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node =
                                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(mh.toString());
                            if (node.has("chronic_conditions") && node.get("chronic_conditions").isArray()) {
                                out.put("chronic_condition_count", node.get("chronic_conditions").size());
                            }
                            if (node.hasNonNull("smoking_status")) {
                                out.put("smoking_status", node.get("smoking_status").asText());
                            }
                            if (node.hasNonNull("bmi")) {
                                out.put("bmi", node.get("bmi").asDouble());
                            }
                            if (node.hasNonNull("medication_count")) {
                                out.put("medication_count", node.get("medication_count").asInt());
                            }
                        } catch (Exception e) {
                            log.debug("[ai-pricing] medical_history parse failed for member {}: {}",
                                    memberId, e.getMessage());
                        }
                    }
                    return out;
                })
                .one()
                .defaultIfEmpty(Map.of())
                .onErrorReturn(Map.of());
    }
}
