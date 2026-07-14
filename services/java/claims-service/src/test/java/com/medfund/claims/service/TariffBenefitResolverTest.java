package com.medfund.claims.service;

import com.medfund.claims.entity.TariffCode;
import com.medfund.claims.repository.TariffCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V063 unit tests for {@link TariffBenefitResolver} — the resolver
 * routes a tariff code to a scheme_benefit UUID (or empty when the
 * category is cap-only / unmapped for the scheme).
 *
 * <p>The DatabaseClient chain is mocked as builder(spec).bind(spec).fetch(fetch).one(Mono&lt;row&gt;)
 * — mirrors the pattern in {@link AdjudicationPipelineTest}. Individual
 * tests override the row returned to exercise each branch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TariffBenefitResolverTest {

    @Mock private TariffCodeRepository tariffCodeRepository;
    @Mock private DatabaseClient databaseClient;

    private TariffBenefitResolver resolver;

    private DatabaseClient.GenericExecuteSpec spec;
    private FetchSpec<Map<String, Object>> fetch;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resolver = new TariffBenefitResolver(tariffCodeRepository, databaseClient);
        spec = mock(DatabaseClient.GenericExecuteSpec.class);
        fetch = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
    }

    @Test
    void resolve_blankTariff_returnsEmpty() {
        StepVerifier.create(resolver.resolve("", UUID.randomUUID()))
                .verifyComplete();
        StepVerifier.create(resolver.resolve(null, UUID.randomUUID()))
                .verifyComplete();
        StepVerifier.create(resolver.resolve("TC001", null))
                .verifyComplete();
    }

    @Test
    void resolve_tariffNotFound_returnsEmpty() {
        when(tariffCodeRepository.findByCode("MISSING")).thenReturn(Mono.empty());

        StepVerifier.create(resolver.resolve("MISSING", UUID.randomUUID()))
                .verifyComplete();
    }

    // Note: a tariff row with a null category_id is impossible after V063
    // (NOT NULL FK), so we don't test that branch — the DB rejects it and
    // Reactor's Mono.map cannot emit null anyway.

    @Test
    void resolve_categoryIsCapOnly_returnsEmpty() {
        UUID categoryId = UUID.randomUUID();
        var tc = tariff(categoryId);
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(tc));

        // is_cap_only=true → resolver returns empty regardless of benefit availability
        Map<String, Object> row = new HashMap<>();
        row.put("scheme_benefit_id", UUID.randomUUID());
        row.put("is_cap_only", true);
        when(fetch.one()).thenReturn(Mono.just(row));

        StepVerifier.create(resolver.resolve("TC001", UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void resolve_categoryHasNoBenefitInScheme_returnsEmpty() {
        UUID categoryId = UUID.randomUUID();
        var tc = tariff(categoryId);
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(tc));

        // LEFT JOIN produces a row where scheme_benefit_id is null when the
        // category has no covering benefit for the requested scheme.
        Map<String, Object> row = new HashMap<>();
        row.put("scheme_benefit_id", null);
        row.put("is_cap_only", false);
        when(fetch.one()).thenReturn(Mono.just(row));

        StepVerifier.create(resolver.resolve("TC001", UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void resolve_categoryMappedToActiveBenefit_returnsBenefitId() {
        UUID categoryId = UUID.randomUUID();
        UUID schemeBenefitId = UUID.randomUUID();
        var tc = tariff(categoryId);
        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(tc));

        Map<String, Object> row = new HashMap<>();
        row.put("scheme_benefit_id", schemeBenefitId);
        row.put("is_cap_only", false);
        when(fetch.one()).thenReturn(Mono.just(row));

        StepVerifier.create(resolver.resolve("TC001", UUID.randomUUID()))
                .expectNext(schemeBenefitId)
                .verifyComplete();
    }

    private TariffCode tariff(UUID categoryId) {
        var tc = new TariffCode();
        tc.setId(UUID.randomUUID());
        tc.setCode("TC001");
        tc.setCategoryId(categoryId);
        return tc;
    }
}
