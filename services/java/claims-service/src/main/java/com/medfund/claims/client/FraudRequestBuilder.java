package com.medfund.claims.client;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.shared.insurance.InsuranceLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the line-aware /fraud/check payload for the Python AI service.
 * Every claim carries an {@code insurance_line}; the {@code line_features}
 * bag is per-line-shaped.
 *
 * <p>Non-HEALTH lines don't yet have detail sub-entities on the claims-service
 * side (LifeClaim, VehicleClaim, etc. are Tranche 1 follow-up). Until they
 * land, the builder emits an empty {@code line_features} bag for those lines
 * and logs a warning. The AI service tolerates an empty bag — indicators
 * degrade to "insufficient_data".
 */
@Slf4j
@Component
public class FraudRequestBuilder {

    public Map<String, Object> build(Claim claim, List<ClaimLine> lines) {
        InsuranceLine line = InsuranceLine.from(claim.getInsuranceLine());
        String lineCode = line != null ? line.name() : "HEALTH";

        UUID subjectId = subjectIdFor(claim, line);
        Map<String, Object> payload = new HashMap<>();
        payload.put("claim_id", String.valueOf(claim.getId()));
        payload.put("insurance_line", lineCode);
        payload.put("subject_id", subjectId != null ? subjectId.toString() : null);
        payload.put("provider_id", claim.getProviderId() != null
                ? claim.getProviderId().toString() : null);
        payload.put("claimed_amount", claim.getClaimedAmount() != null
                ? claim.getClaimedAmount().doubleValue() : 0d);
        payload.put("currency_code", claim.getCurrencyCode() != null
                ? claim.getCurrencyCode() : "USD");
        payload.put("service_date", claim.getServiceDate() != null
                ? claim.getServiceDate().toString() : "");
        payload.put("line_features", lineFeaturesFor(claim, lines, line));
        return payload;
    }

    private UUID subjectIdFor(Claim claim, InsuranceLine line) {
        // For asset-centric lines (VEHICLE, PROPERTY) the claim's memberId
        // may still be populated for invoice routing, but the subject of
        // the risk is the asset. Once VehicleClaim / PropertyClaim entities
        // land on claims-service, this helper will fetch the asset id from
        // the corresponding sub-entity. For Tranche 0 we fall back to the
        // member id — the AI service treats it as an opaque identifier.
        return claim.getMemberId();
    }

    private Map<String, Object> lineFeaturesFor(
            Claim claim, List<ClaimLine> lines, InsuranceLine line) {
        if (line == null || line == InsuranceLine.HEALTH) {
            return healthFeatures(claim, lines);
        }
        log.debug("No sub-entity yet for line={} claim={} — emitting empty line_features",
                line, claim.getId());
        return Map.of();
    }

    private Map<String, Object> healthFeatures(Claim claim, List<ClaimLine> lines) {
        Map<String, Object> features = new HashMap<>();
        features.put("diagnosis_codes", parseStringList(claim.getDiagnosisCodes()));
        features.put("procedure_codes", lines.stream()
                .map(ClaimLine::getTariffCode)
                .filter(c -> c != null && !c.isBlank())
                .toList());
        return features;
    }

    private static List<String> parseStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(trimmed,
                                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception ignore) { return List.of(); }
        }
        return List.of(trimmed.split("\\s*,\\s*"));
    }
}
