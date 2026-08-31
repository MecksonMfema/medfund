package com.medfund.finance.regulatory.service;

import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import com.medfund.finance.regulatory.repository.RegulatorySubmissionRepository;
import com.medfund.shared.report.ReportCadence;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.tenant.TenantMetadataReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegulatoryDueDateServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 8, 30);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            REFERENCE_DATE.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @Mock
    private RegulatorySubmissionRepository submissions;
    @Mock
    private TenantMetadataReader tenantMetadata;

    private RegulatoryDueDateService service() {
        return new RegulatoryDueDateService(submissions, tenantMetadata, FIXED_CLOCK);
    }

    @Test
    void bannerRows_zaCmsTenant_emitsCmsAndPmbAndAmlOnly() {
        when(tenantMetadata.load(TENANT_ID))
                .thenReturn(Mono.just(new TenantMetadataReader.TenantMetadata(
                        "ZA_CMS_MEDICAL_SCHEME", "ZA")));
        lenient().when(submissions
                .findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                        any(), any(), any()))
                .thenReturn(Flux.empty());

        List<String> keys = service().bannerRowsFor(TENANT_ID)
                .map(r -> r.reportKey())
                .collectList()
                .block();

        // ZA_CMS_MEDICAL_SCHEME jurisdiction → CMS_ASR + PMB_SPEND.
        // ZA country → AML_STR + TAX_WITHHELD_RETURN + VAT_RETURN.
        assertThat(keys).containsExactlyInAnyOrder(
                "CMS_ASR", "PMB_SPEND",
                "AML_STR", "TAX_WITHHELD_RETURN", "VAT_RETURN");
    }

    @Test
    void bannerRows_noJurisdictionOrCountry_emitsNothing() {
        when(tenantMetadata.load(TENANT_ID))
                .thenReturn(Mono.just(TenantMetadataReader.TenantMetadata.empty()));

        StepVerifier.create(service().bannerRowsFor(TENANT_ID))
                .verifyComplete();
    }

    @Test
    void bannerRow_ipecQuarterlyPendingAndOverdue_isRed() {
        // Reference 2026-08-30 → Q2 2026 (Apr-Jun) is the current filing slot.
        // Due 30 days after quarter end → 2026-07-30 → 31 days overdue → RED.
        when(tenantMetadata.load(TENANT_ID))
                .thenReturn(Mono.just(new TenantMetadataReader.TenantMetadata(
                        "ZW_IPEC_SHORT_TERM", "ZW")));
        lenient().when(submissions
                .findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                        any(), any(), any()))
                .thenReturn(Flux.empty());

        var ipec = service().bannerRowsFor(TENANT_ID)
                .filter(r -> "IPEC_QUARTERLY_RETURN".equals(r.reportKey()))
                .blockFirst();

        assertThat(ipec).isNotNull();
        assertThat(ipec.cadence()).isEqualTo(ReportCadence.QUARTERLY.name());
        assertThat(ipec.periodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(ipec.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(ipec.dueDate()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(ipec.daysUntilDue()).isEqualTo(-31);
        assertThat(ipec.submissionStatus()).isEqualTo("PENDING");
        assertThat(ipec.severity()).isEqualTo("RED");
        assertThat(ipec.reportLabel()).isEqualTo(ReportKey.IPEC_QUARTERLY_RETURN.getLabel());
    }

    @Test
    void bannerRow_taxWithheldSubmittedShowsInfoRegardlessOfDate() {
        when(tenantMetadata.load(TENANT_ID))
                .thenReturn(Mono.just(new TenantMetadataReader.TenantMetadata(null, "ZW")));
        // Default: any submission lookup returns empty (row shows PENDING). Override
        // only for TAX_WITHHELD_RETURN to prove SUBMITTED short-circuits severity.
        lenient().when(submissions
                .findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                        any(), any(), any()))
                .thenReturn(Flux.empty());
        RegulatorySubmission submitted = new RegulatorySubmission();
        submitted.setStatus("SUBMITTED");
        when(submissions
                .findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                        eq(TENANT_ID), eq("TAX_WITHHELD_RETURN"), any()))
                .thenReturn(Flux.just(submitted));

        var row = service().bannerRowsFor(TENANT_ID)
                .filter(r -> "TAX_WITHHELD_RETURN".equals(r.reportKey()))
                .blockFirst();

        assertThat(row).isNotNull();
        assertThat(row.periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(row.periodEnd()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(row.dueDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(row.submissionStatus()).isEqualTo("SUBMITTED");
        assertThat(row.severity()).isEqualTo("INFO");
    }

    @Test
    void severityLadderBoundaries() {
        // >7 days out → INFO. 7 days → AMBER (≤7). 0 → RED. -1 → RED.
        assertThat(RegulatoryDueDateService.severityOf(8, "PENDING")).isEqualTo("INFO");
        assertThat(RegulatoryDueDateService.severityOf(7, "PENDING")).isEqualTo("AMBER");
        assertThat(RegulatoryDueDateService.severityOf(1, "PENDING")).isEqualTo("AMBER");
        assertThat(RegulatoryDueDateService.severityOf(0, "PENDING")).isEqualTo("RED");
        assertThat(RegulatoryDueDateService.severityOf(-1, "PENDING")).isEqualTo("RED");
        // AMENDED short-circuits to INFO regardless.
        assertThat(RegulatoryDueDateService.severityOf(-100, "AMENDED")).isEqualTo("INFO");
        // A SUPERSEDED with no newer row is a data anomaly — treat like PENDING.
        assertThat(RegulatoryDueDateService.severityOf(-1, "SUPERSEDED")).isEqualTo("RED");
    }

    @Test
    void currentPeriodFor_monthly_isPreviousCalendarMonth() {
        RegulatoryDueDateService.Period p = RegulatoryDueDateService.currentPeriodFor(
                ReportCadence.MONTHLY, LocalDate.of(2026, 8, 30));
        assertThat(p.start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(p.end()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void currentPeriodFor_quarterly_isPreviousCompleteQuarter() {
        // Aug 30 → Q2 2026 is the most recently completed quarter.
        RegulatoryDueDateService.Period q2 = RegulatoryDueDateService.currentPeriodFor(
                ReportCadence.QUARTERLY, LocalDate.of(2026, 8, 30));
        assertThat(q2.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(q2.end()).isEqualTo(LocalDate.of(2026, 6, 30));
        // Feb 15 → Q4 2025 is the most recently completed.
        RegulatoryDueDateService.Period q4Prev = RegulatoryDueDateService.currentPeriodFor(
                ReportCadence.QUARTERLY, LocalDate.of(2026, 2, 15));
        assertThat(q4Prev.start()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(q4Prev.end()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void currentPeriodFor_annual_isPreviousCalendarYear() {
        RegulatoryDueDateService.Period p = RegulatoryDueDateService.currentPeriodFor(
                ReportCadence.ANNUAL, LocalDate.of(2026, 8, 30));
        assertThat(p.start()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(p.end()).isEqualTo(LocalDate.of(2025, 12, 31));
    }
}
