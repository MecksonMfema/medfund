package com.medfund.user.service;

import com.medfund.user.dto.PricingSuggestionRequest;
import io.r2dbc.spi.Readable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the shape + composition rules of the stub AI pricing service.
 * Two invariants the frontend depends on:
 *
 * <ul>
 *   <li>{@code stub = true} on every response so the UI can badge the
 *       number. If a future production model swap forgets to flip
 *       this, operators would mistake a real AI recommendation for a
 *       placeholder.</li>
 *   <li>Risk multipliers are applied in-order on top of the scheme's
 *       guideline. Regressions here (a swapped multiplier, a missing
 *       multiplier, an accidentally-cumulative add) would silently
 *       change every enrolment suggestion tenant-wide.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PricingSuggestionServiceTest {

    @Mock private AgeGroupResolver ageGroupResolver;
    @Mock private DatabaseClient db;

    @InjectMocks private PricingSuggestionService svc;

    @Test
    void suggest_noMatchingAgeBand_fallsBackWithStubBase_andStubFlagTrue() {
        // AgeGroupResolver returns empty → the fallback path fires. The
        // rationale must say "no age band matches" so the operator sees
        // the data-gap; the stub flag stays true.
        when(ageGroupResolver.resolveForSchemeAndDob(any(), any())).thenReturn(Mono.empty());

        var req = new PricingSuggestionRequest(
                UUID.randomUUID(),
                LocalDate.of(1990, 5, 15),
                "male", false, false, null, null);

        StepVerifier.create(svc.suggest(req))
                .assertNext(resp -> {
                    assertThat(resp.stub()).isTrue();
                    assertThat(resp.currencyCode()).isEqualTo("USD");
                    // Fallback base 100.00, no risk factors on this input.
                    assertThat(resp.suggestedAmount()).isEqualByComparingTo("100.00");
                    assertThat(resp.factors().get(0)).contains("No age band matches");
                })
                .verifyComplete();
    }

    @Test
    void suggest_ageBandFound_usesSchemeGuideline_asBase() {
        // AgeGroup resolves → SQL returns a guideline (name + amount +
        // currency). Suggested amount should equal that guideline
        // when no risk signals fire.
        UUID ageGroupId = UUID.randomUUID();
        when(ageGroupResolver.resolveForSchemeAndDob(any(), any()))
                .thenReturn(Mono.just(ageGroupId));
        stubGuidelineLookup("Adult", new BigDecimal("125.00"), "USD");

        var req = new PricingSuggestionRequest(
                UUID.randomUUID(),
                LocalDate.of(1990, 5, 15),
                "male", false, false, null, null);

        StepVerifier.create(svc.suggest(req))
                .assertNext(resp -> {
                    assertThat(resp.suggestedAmount()).isEqualByComparingTo("125.00");
                    assertThat(resp.currencyCode()).isEqualTo("USD");
                    assertThat(resp.factors().get(0)).contains("Scheme guideline")
                            .contains("Adult").contains("125");
                })
                .verifyComplete();
    }

    @Test
    void suggest_femaleApplicant_appliesGenderMultiplier() {
        // Female applies ×0.98. Base 100 → 98.00.
        when(ageGroupResolver.resolveForSchemeAndDob(any(), any()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        stubGuidelineLookup("Adult", new BigDecimal("100.00"), "USD");

        StepVerifier.create(svc.suggest(reqWith("female", false, false, null)))
                .assertNext(resp ->
                    assertThat(resp.suggestedAmount()).isEqualByComparingTo("98.00"))
                .verifyComplete();
    }

    @Test
    void suggest_smoker_applies130Multiplier() {
        when(ageGroupResolver.resolveForSchemeAndDob(any(), any()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        stubGuidelineLookup("Adult", new BigDecimal("100.00"), "USD");

        StepVerifier.create(svc.suggest(reqWith("male", true, false, null)))
                .assertNext(resp ->
                    assertThat(resp.suggestedAmount()).isEqualByComparingTo("130.00"))
                .verifyComplete();
    }

    @Test
    void suggest_chronicConditions_applies140Multiplier() {
        when(ageGroupResolver.resolveForSchemeAndDob(any(), any()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        stubGuidelineLookup("Adult", new BigDecimal("100.00"), "USD");

        StepVerifier.create(svc.suggest(reqWith("male", false, true, null)))
                .assertNext(resp ->
                    assertThat(resp.suggestedAmount()).isEqualByComparingTo("140.00"))
                .verifyComplete();
    }

    @Test
    void suggest_bmiOverThreshold_applies115Multiplier() {
        // BMI > 30 is the WHO obesity threshold. 30.1 fires; 30.0 does not.
        when(ageGroupResolver.resolveForSchemeAndDob(any(), any()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        stubGuidelineLookup("Adult", new BigDecimal("100.00"), "USD");

        StepVerifier.create(svc.suggest(reqWith("male", false, false, new BigDecimal("30.1"))))
                .assertNext(resp ->
                    assertThat(resp.suggestedAmount()).isEqualByComparingTo("115.00"))
                .verifyComplete();
    }

    @Test
    void suggest_bmiAtThreshold_doesNotApplyMultiplier() {
        // 30.0 must NOT fire — the CHECK is strict >.
        when(ageGroupResolver.resolveForSchemeAndDob(any(), any()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        stubGuidelineLookup("Adult", new BigDecimal("100.00"), "USD");

        StepVerifier.create(svc.suggest(reqWith("male", false, false, new BigDecimal("30.0"))))
                .assertNext(resp ->
                    assertThat(resp.suggestedAmount()).isEqualByComparingTo("100.00"))
                .verifyComplete();
    }

    @Test
    void suggest_allSignalsFire_composesMultipliers() {
        // Female + smoker + chronic + BMI-obese all set. Expected math:
        // 100 × 0.98 × 1.30 × 1.40 × 1.15 = 205.114 → 205.11 (half-up)
        when(ageGroupResolver.resolveForSchemeAndDob(any(), any()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        stubGuidelineLookup("Adult", new BigDecimal("100.00"), "USD");

        StepVerifier.create(svc.suggest(reqWith("female", true, true, new BigDecimal("32.0"))))
                .assertNext(resp -> {
                    assertThat(resp.suggestedAmount()).isEqualByComparingTo("205.11");
                    // All four multipliers listed in factors.
                    assertThat(resp.factors()).anyMatch(f -> f.contains("Female"));
                    assertThat(resp.factors()).anyMatch(f -> f.contains("Smoker"));
                    assertThat(resp.factors()).anyMatch(f -> f.contains("Chronic"));
                    assertThat(resp.factors()).anyMatch(f -> f.contains("BMI"));
                })
                .verifyComplete();
    }

    @Test
    void suggest_alwaysMarksStubTrue() {
        // Non-negotiable regression guard — production model swap must
        // remember to flip this to false.
        when(ageGroupResolver.resolveForSchemeAndDob(any(), any()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        stubGuidelineLookup("Adult", new BigDecimal("100.00"), "USD");

        StepVerifier.create(svc.suggest(reqWith("male", false, false, null)))
                .assertNext(resp -> assertThat(resp.stub()).isTrue())
                .verifyComplete();
    }

    // ------------------------------------------------------------------
    // Helpers — stub the DatabaseClient.sql(...).bind(...).map(...).one()
    // chain to return a Guideline row. Kept private + reusable so the
    // multiplier tests don't repeat the four-line dance.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubGuidelineLookup(String name, BigDecimal amount, String currency) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<Object> fetch = mock(RowsFetchSpec.class);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(Function.class))).thenAnswer(inv -> {
            Function<Readable, Object> fn = inv.getArgument(0);
            // Feed a synthetic Readable row to the fn so the service's
            // row.get("...", ...) calls resolve to the values we want.
            Readable row = mock(Readable.class);
            when(row.get("id", UUID.class)).thenReturn(UUID.randomUUID());
            when(row.get("name", String.class)).thenReturn(name);
            when(row.get("contribution_amount", BigDecimal.class)).thenReturn(amount);
            when(row.get("currency_code", String.class)).thenReturn(currency);
            Object result = fn.apply(row);
            when(fetch.one()).thenReturn(Mono.just(result));
            return fetch;
        });
    }

    private PricingSuggestionRequest reqWith(String gender, boolean smoker,
                                              boolean chronic, BigDecimal bmi) {
        return new PricingSuggestionRequest(
                UUID.randomUUID(),
                LocalDate.of(1990, 5, 15),
                gender, chronic, smoker, bmi, null);
    }
}
