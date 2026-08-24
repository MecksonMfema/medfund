package com.medfund.user.reports.lifecycle.service;

import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.user.reports.lifecycle.dto.PersistencyCohortResult;
import com.medfund.user.reports.lifecycle.dto.PersistencyCohortRow;
import com.medfund.user.reports.lifecycle.repository.PolicyLifecycleReportQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistencyCohortReportServiceTest {

    @Mock PolicyLifecycleReportQueryRepository queryRepository;
    @Mock ReportEnvelopeBuilder envelopeBuilder;

    private PersistencyCohortReportService service() {
        return new PersistencyCohortReportService(queryRepository, envelopeBuilder);
    }

    @Test
    void generate_defaultCheckpoints_applied_whenNullOrEmpty() {
        LocalDate ps = LocalDate.of(2024, 1, 1);
        LocalDate pe = LocalDate.of(2024, 12, 31);

        when(queryRepository.persistencyCohortRows(eq(ps), eq(pe),
                eq(PersistencyCohortReportService.DEFAULT_CHECKPOINTS), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.latestContribPresenceRefreshAt())
                .thenReturn(Mono.just(Instant.now()));
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.PERSISTENCY_COHORT),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.PERSISTENCY_COHORT, null, "USD",
                        new PersistencyCohortResult(List.of(), null),
                        Map.of(), Map.of(), List.of())));

        StepVerifier.create(service().generate(ps, pe, null, null, null))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void generate_freshMatview_hasNoFreshnessWarning() {
        LocalDate ps = LocalDate.of(2024, 1, 1);
        LocalDate pe = LocalDate.of(2024, 12, 31);

        PersistencyCohortRow row = new PersistencyCohortRow(
                LocalDate.of(2024, 1, 1), "HEALTH", 12,
                100L, 80L, new BigDecimal("80.00"));

        when(queryRepository.persistencyCohortRows(any(), any(), any(), any()))
                .thenReturn(Flux.just(row));
        when(queryRepository.latestContribPresenceRefreshAt())
                .thenReturn(Mono.just(Instant.now().minusSeconds(60)));

        ArgumentCaptor<Mono<PersistencyCohortResult>> captor = ArgumentCaptor.forClass(Mono.class);
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.PERSISTENCY_COHORT),
                any(ReportPeriod.class), any(), captor.capture()))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.PERSISTENCY_COHORT, null, "USD",
                        new PersistencyCohortResult(List.of(row), null),
                        Map.of(), Map.of(), List.of())));

        service().generate(ps, pe, List.of(12), null, null).block();
        PersistencyCohortResult inbound = captor.getValue().block();
        org.assertj.core.api.Assertions.assertThat(inbound.freshnessWarning()).isNull();
    }

    @Test
    void generate_staleMatview_addsFreshnessWarning() {
        LocalDate ps = LocalDate.of(2024, 1, 1);
        LocalDate pe = LocalDate.of(2024, 12, 31);

        when(queryRepository.persistencyCohortRows(any(), any(), any(), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.latestContribPresenceRefreshAt())
                .thenReturn(Mono.just(Instant.now().minusSeconds(60L * 60L * 48L)));

        ArgumentCaptor<Mono<PersistencyCohortResult>> captor = ArgumentCaptor.forClass(Mono.class);
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.PERSISTENCY_COHORT),
                any(ReportPeriod.class), any(), captor.capture()))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.PERSISTENCY_COHORT, null, "USD",
                        new PersistencyCohortResult(List.of(), "warning"),
                        Map.of(), Map.of(), List.of())));

        service().generate(ps, pe, List.of(12), null, null).block();
        PersistencyCohortResult inbound = captor.getValue().block();
        org.assertj.core.api.Assertions.assertThat(inbound.freshnessWarning())
                .contains("stale");
    }

    @Test
    void generate_neverRefreshed_addsWarning() {
        LocalDate ps = LocalDate.of(2024, 1, 1);
        LocalDate pe = LocalDate.of(2024, 12, 31);

        when(queryRepository.persistencyCohortRows(any(), any(), any(), any()))
                .thenReturn(Flux.empty());
        when(queryRepository.latestContribPresenceRefreshAt()).thenReturn(Mono.empty());

        ArgumentCaptor<Mono<PersistencyCohortResult>> captor = ArgumentCaptor.forClass(Mono.class);
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.PERSISTENCY_COHORT),
                any(ReportPeriod.class), any(), captor.capture()))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.PERSISTENCY_COHORT, null, "USD",
                        new PersistencyCohortResult(List.of(), "warn"),
                        Map.of(), Map.of(), List.of())));

        service().generate(ps, pe, List.of(12), null, null).block();
        PersistencyCohortResult inbound = captor.getValue().block();
        org.assertj.core.api.Assertions.assertThat(inbound.freshnessWarning())
                .contains("not yet been refreshed");
    }
}
