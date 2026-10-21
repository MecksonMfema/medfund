package com.medfund.claims.reports.provider.service;

import com.medfund.claims.client.ProviderClient;
import com.medfund.claims.reports.provider.dto.ProviderUtilizationResult;
import com.medfund.claims.reports.provider.dto.ProviderUtilizationRow;
import com.medfund.claims.reports.provider.repository.ProviderUtilizationQueryRepository;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportingCurrencyResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderNetworkUtilizationReportServiceTest {

    @Mock ProviderUtilizationQueryRepository queryRepository;
    @Mock ProviderClient providerClient;
    @Mock ReportingCurrencyResolver currencyResolver;

    private ProviderNetworkUtilizationReportService service() {
        return new ProviderNetworkUtilizationReportService(queryRepository, providerClient, currencyResolver);
    }

    @Test
    void generate_enrichesRows_andGroupsSummaryByTier() {
        LocalDate ps = LocalDate.of(2026, 6, 1);
        LocalDate pe = LocalDate.of(2026, 6, 30);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        ProviderUtilizationRow r1 = new ProviderUtilizationRow(
                p1, null, "STANDARD", "HEALTH", "USD",
                10L, new BigDecimal("1000"), new BigDecimal("800"), 2L, 5L);
        ProviderUtilizationRow r2 = new ProviderUtilizationRow(
                p2, null, "STANDARD", "HEALTH", "USD",
                6L, new BigDecimal("600"), new BigDecimal("500"), 1L, 3L);

        Map<UUID, ProviderClient.ProviderMetadata> meta = new HashMap<>();
        meta.put(p1, new ProviderClient.ProviderMetadata(p1, "Alpha Clinic", "TIER_1"));
        meta.put(p2, new ProviderClient.ProviderMetadata(p2, "Beta Practice", "STANDARD"));

        when(currencyResolver.resolve(any(), any())).thenReturn(Mono.just("USD"));
        when(queryRepository.aggregate(eq(ps), eq(pe), any())).thenReturn(Flux.just(r1, r2));
        when(providerClient.batchLookup(anySet(), any())).thenReturn(Mono.just(meta));

        StepVerifier.create(service().generate(ps, pe, null, null, null))
                .assertNext((ReportResponse<ProviderUtilizationResult> response) -> {
                    ProviderUtilizationResult res = response.data();
                    org.assertj.core.api.Assertions.assertThat(res.detail()).hasSize(2);
                    // TIER_1 has one provider; STANDARD has one provider.
                    org.assertj.core.api.Assertions.assertThat(res.summary().keySet())
                            .containsExactlyInAnyOrder("TIER_1", "STANDARD");
                    org.assertj.core.api.Assertions.assertThat(res.summary().get("TIER_1").providerCount())
                            .isEqualTo(1L);
                    org.assertj.core.api.Assertions.assertThat(res.summary().get("TIER_1").totalPaid())
                            .isEqualByComparingTo("800");
                })
                .verifyComplete();
    }

    @Test
    void generate_tierFilter_narrowsRowsToTier() {
        LocalDate ps = LocalDate.of(2026, 6, 1);
        LocalDate pe = LocalDate.of(2026, 6, 30);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        ProviderUtilizationRow r1 = new ProviderUtilizationRow(
                p1, null, "STANDARD", "HEALTH", "USD", 1L,
                BigDecimal.ONE, BigDecimal.ONE, 0L, 1L);
        ProviderUtilizationRow r2 = new ProviderUtilizationRow(
                p2, null, "STANDARD", "HEALTH", "USD", 1L,
                BigDecimal.ONE, BigDecimal.ONE, 0L, 1L);
        Map<UUID, ProviderClient.ProviderMetadata> meta = Map.of(
                p1, new ProviderClient.ProviderMetadata(p1, "Alpha", "TIER_1"),
                p2, new ProviderClient.ProviderMetadata(p2, "Beta",  "STANDARD"));
        when(currencyResolver.resolve(any(), any())).thenReturn(Mono.just("USD"));
        when(queryRepository.aggregate(any(), any(), any())).thenReturn(Flux.just(r1, r2));
        when(providerClient.batchLookup(anySet(), any())).thenReturn(Mono.just(meta));

        StepVerifier.create(service().generate(ps, pe, null, "TIER_1", null))
                .assertNext(response -> {
                    org.assertj.core.api.Assertions.assertThat(response.data().detail()).hasSize(1);
                    org.assertj.core.api.Assertions.assertThat(response.data().detail().get(0).networkTier())
                            .isEqualTo("TIER_1");
                })
                .verifyComplete();
    }

    @Test
    void generate_peerDown_placeholderNames_warningInEnvelope() {
        LocalDate ps = LocalDate.of(2026, 6, 1);
        LocalDate pe = LocalDate.of(2026, 6, 30);
        UUID p1 = UUID.randomUUID();
        ProviderUtilizationRow r1 = new ProviderUtilizationRow(
                p1, null, "STANDARD", "HEALTH", "USD", 5L,
                new BigDecimal("500"), new BigDecimal("400"), 0L, 2L);

        // Peer-down: batchLookup returns placeholder entries + pushes warning into the sink.
        when(currencyResolver.resolve(any(), any())).thenReturn(Mono.just("USD"));
        when(queryRepository.aggregate(any(), any(), any())).thenReturn(Flux.just(r1));
        when(providerClient.batchLookup(anySet(), any())).thenAnswer(inv -> {
            List<String> sink = inv.getArgument(1);
            sink.add("provider metadata unavailable for 1 providers");
            Map<UUID, ProviderClient.ProviderMetadata> map = Map.of(
                    p1, new ProviderClient.ProviderMetadata(p1, "Provider Unknown", "STANDARD"));
            return Mono.just(map);
        });

        StepVerifier.create(service().generate(ps, pe, null, null, null))
                .assertNext(response -> {
                    org.assertj.core.api.Assertions.assertThat(response.data().detail().get(0).providerName())
                            .isEqualTo("Provider Unknown");
                    org.assertj.core.api.Assertions.assertThat(response.warnings())
                            .anyMatch(w -> w.contains("provider metadata unavailable"));
                })
                .verifyComplete();
    }

    @Test
    void generate_emptyAggregate_returnsEmptyResult() {
        when(currencyResolver.resolve(any(), any())).thenReturn(Mono.just("USD"));
        when(queryRepository.aggregate(any(), any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(service().generate(LocalDate.now().minusDays(30), LocalDate.now(),
                        null, null, null))
                .assertNext(response -> {
                    org.assertj.core.api.Assertions.assertThat(response.data().detail()).isEmpty();
                    org.assertj.core.api.Assertions.assertThat(response.data().summary()).isEmpty();
                })
                .verifyComplete();
    }
}
