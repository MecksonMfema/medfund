package com.medfund.user.service;

import com.medfund.user.dto.PricingSuggestionRequest;
import com.medfund.user.dto.PricingSuggestionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stub AI pricing-suggestion service. The eventual production model
 * (via the Python ai-service) will replace {@link #suggest} without
 * changing the request/response contract — the frontend "Suggest with
 * AI" button won't have to be re-wired.
 *
 * <p><b>How the stub composes a number.</b> The scheme's age-band price
 * is the guideline the operator would normally charge; the stub takes
 * that as the base and layers a small set of tenant-tracked
 * lifestyle-risk multipliers on top (gender, smoking, chronic
 * conditions, BMI). This matches how a real actuarial model works:
 * schemes ship guideline pricing per age band, and individual pricing
 * is an override with a per-applicant risk lens applied.
 *
 * <p>Falls back to a hand-picked base premium when no age band on the
 * scheme covers the applicant's age — that's a data-gap, not a stub
 * failure, and the operator should see a suggestion regardless. The
 * rationale field explains which branch fired so the operator can act.
 *
 * <p>{@code PricingSuggestionResponse.stub = true} on every path so the
 * frontend can badge the number and set expectations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingSuggestionService {

    /** Absolute fallback base when the scheme has no matching age band. */
    private static final BigDecimal FALLBACK_BASE = new BigDecimal("100.00");
    private static final String FALLBACK_CURRENCY = "USD";

    private final AgeGroupResolver ageGroupResolver;
    private final DatabaseClient db;

    /**
     * Compute a suggested premium. Two-phase: resolve the scheme's
     * guideline (age-band price), then layer risk multipliers. Reactive
     * throughout so the swap-in to a real ai-service call is one line.
     */
    public Mono<PricingSuggestionResponse> suggest(PricingSuggestionRequest req) {
        return ageGroupResolver.resolveForSchemeAndDob(req.schemeId(), req.dateOfBirth())
                .flatMap(this::loadAgeGroupGuideline)
                .defaultIfEmpty(fallbackGuideline())
                .map(guideline -> layerRiskFactors(req, guideline));
    }

    /**
     * Fetch the age-group's guideline price + currency in one SQL round-
     * trip. Skips the age_group_prices lateral (used by billing for
     * time-varying pricing) because the suggestion is advisory —
     * the operator sees a plausible number, not a load-bearing one.
     */
    private Mono<Guideline> loadAgeGroupGuideline(UUID ageGroupId) {
        return db.sql("""
                    SELECT id, name, contribution_amount, currency_code
                      FROM age_groups
                     WHERE id = :id
                    """)
                .bind("id", ageGroupId)
                .map(row -> new Guideline(
                        row.get("id", UUID.class),
                        row.get("name", String.class),
                        row.get("contribution_amount", BigDecimal.class),
                        row.get("currency_code", String.class),
                        /* isFallback */ false))
                .one()
                .onErrorResume(err -> {
                    log.warn("[pricing-suggestion] age-group lookup failed for {}: {}",
                            ageGroupId, err.getMessage());
                    return Mono.just(fallbackGuideline());
                });
    }

    private Guideline fallbackGuideline() {
        return new Guideline(null, null, FALLBACK_BASE, FALLBACK_CURRENCY, /* isFallback */ true);
    }

    /**
     * Combine the scheme's guideline with the risk signals declared on
     * the request. Multipliers are conservative — the stub aims for
     * plausibility, not precision. Real production numbers come from
     * the ai-service later.
     */
    private PricingSuggestionResponse layerRiskFactors(PricingSuggestionRequest req, Guideline base) {
        BigDecimal amount = base.price();
        List<String> factors = new ArrayList<>();
        if (base.isFallback()) {
            factors.add("No age band matches on scheme " + req.schemeId()
                    + " — using stub fallback base " + base.currency() + " " + base.price());
        } else {
            factors.add("Scheme guideline: " + base.currency() + " " + base.price()
                    + " (age-band '" + base.ageGroupName() + "')");
        }

        // Age is already baked into the age-band lookup — DO NOT apply
        // an age-bucket multiplier here or we'd double-count. Only the
        // tenant-tracked lifestyle-risk signals land on top.

        if ("female".equalsIgnoreCase(req.gender())) {
            amount = amount.multiply(new BigDecimal("0.98"));
            factors.add("Female applicant (×0.98)");
        }

        if (Boolean.TRUE.equals(req.smoker())) {
            amount = amount.multiply(new BigDecimal("1.30"));
            factors.add("Smoker (×1.30)");
        }

        if (Boolean.TRUE.equals(req.hasChronicConditions())) {
            amount = amount.multiply(new BigDecimal("1.40"));
            factors.add("Chronic condition(s) declared (×1.40)");
        }

        // BMI > 30 is the WHO obesity threshold — 15% loading.
        if (req.bmi() != null && req.bmi().compareTo(new BigDecimal("30")) > 0) {
            amount = amount.multiply(new BigDecimal("1.15"));
            factors.add("BMI " + req.bmi() + " > 30 (×1.15)");
        }

        amount = amount.setScale(2, RoundingMode.HALF_UP);

        String rationale = "Stub AI suggestion — awaiting production model. "
                + "Takes the scheme's guideline (age-band price) as the base and layers "
                + "declared risk signals on top. Operator retains final say — this pre-fills "
                + "the Custom-premium amount field but is fully editable.";

        log.debug("[pricing-suggestion] scheme={} dob={} → {} {} (factors: {})",
                req.schemeId(), req.dateOfBirth(), amount, base.currency(), factors);

        return new PricingSuggestionResponse(
                amount,
                base.currency(),
                rationale,
                factors,
                /* stub */ true);
    }

    /**
     * Internal record — the scheme's guideline as loaded from
     * {@code age_groups}. {@code isFallback} marks the "no band
     * matches" branch so the rationale can call it out to the operator.
     */
    private record Guideline(UUID ageGroupId, String ageGroupName,
                              BigDecimal price, String currency,
                              boolean isFallback) {}
}
