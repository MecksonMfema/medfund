package com.medfund.user.reports.lifecycle.service;

import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.user.reports.lifecycle.dto.GroupCensusResult;
import com.medfund.user.reports.lifecycle.dto.GroupCensusRow;
import com.medfund.user.reports.lifecycle.repository.PolicyLifecycleReportQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupCensusReportServiceTest {

    @Mock PolicyLifecycleReportQueryRepository queryRepository;
    @Mock ReportEnvelopeBuilder envelopeBuilder;

    @Test
    void generate_wrapsRowsIntoResultWithAsOf() {
        LocalDate asOf = LocalDate.of(2026, 8, 1);
        UUID g1 = UUID.randomUUID();
        GroupCensusRow row = new GroupCensusRow(g1, "Acme Ltd", "REG-123", "Alice", "alice@acme",
                12L, 1L, 0L, 3L, 16L);
        when(queryRepository.groupCensusRows(eq(asOf), eq(null), eq(null)))
                .thenReturn(Flux.just(row));
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.GROUP_CENSUS),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(ReportResponse.of(
                        ReportKey.GROUP_CENSUS, null, "USD",
                        new GroupCensusResult(asOf, List.of(row)),
                        Map.of(), Map.of(), List.of())));

        GroupCensusReportService svc = new GroupCensusReportService(queryRepository, envelopeBuilder);
        StepVerifier.create(svc.generate(asOf, null, null, null))
                .assertNext(response -> {
                    org.assertj.core.api.Assertions.assertThat(response.data().asOf()).isEqualTo(asOf);
                    org.assertj.core.api.Assertions.assertThat(response.data().groups()).hasSize(1);
                })
                .verifyComplete();
    }
}
