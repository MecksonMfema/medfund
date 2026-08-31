package com.medfund.finance.regulatory.naic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import com.medfund.finance.regulatory.naic.NaicSchedulePCalculator;
import com.medfund.finance.regulatory.naic.NaicSchedulePRawData;
import com.medfund.finance.regulatory.naic.NaicSchedulePReportShaper;
import com.medfund.finance.regulatory.naic.NaicSchedulePXlsxService;
import com.medfund.finance.regulatory.naic.UsTenantNaicConfigReader;
import com.medfund.finance.regulatory.naic.dto.NaicSchedulePReportRequest;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.finance.regulatory.service.RegulatoryReportShapingService;
import com.medfund.finance.regulatory.service.RegulatorySubmissionService;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.TemplateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NaicSchedulePJobServiceTest {

    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String ACTOR_EMAIL = "clerk@insurer-us.example";
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 12, 31);

    private ReportJobRepository jobRepository;
    private RegulatoryReportShapingService shapingService;
    private NaicSchedulePXlsxService xlsxService;
    private RegulatorySubmissionService submissionService;
    private AuditPublisher auditPublisher;
    private UsTenantNaicConfigReader configReader;
    private NaicSchedulePJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(ReportJobRepository.class);
        shapingService = mock(RegulatoryReportShapingService.class);
        xlsxService = mock(NaicSchedulePXlsxService.class);
        submissionService = mock(RegulatorySubmissionService.class);
        auditPublisher = mock(AuditPublisher.class);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        configReader = mock(UsTenantNaicConfigReader.class);
        when(configReader.hasEffectiveConfig(TENANT)).thenReturn(Mono.just(true));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new NaicSchedulePJobService(jobRepository, shapingService, xlsxService,
                submissionService, auditPublisher, mapper, Optional.of(configReader));
    }

    @Test
    void submit_rejectsClientCurrencyOverride_with422() {
        NaicSchedulePReportRequest override = new NaicSchedulePReportRequest(PERIOD_START, PERIOD_END,
                "ZAR", false, null);

        StepVerifier.create(service.submit(override, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                })
                .verify();
    }

    @Test
    void submit_rejectsWith422_whenConfigReaderBeanAbsent() {
        // No configReader wired → Phase 12 pre-Phase-14 default: gate fails closed.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        NaicSchedulePJobService svcNoReader = new NaicSchedulePJobService(
                jobRepository, shapingService, xlsxService, submissionService,
                auditPublisher, mapper, Optional.empty());

        NaicSchedulePReportRequest req = new NaicSchedulePReportRequest(PERIOD_START, PERIOD_END,
                null, false, null);
        StepVerifier.create(svcNoReader.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                    assertThat(((ResponseStatusException) err).getReason())
                            .contains("us_tenant_naic_config");
                })
                .verify();
        // Never touched the repository — gate short-circuited before persistence.
        verify(jobRepository, never()).findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                any(), any(), anyList());
    }

    @Test
    void submit_rejectsWith422_whenConfigReaderReturnsFalse() {
        when(configReader.hasEffectiveConfig(TENANT)).thenReturn(Mono.just(false));

        NaicSchedulePReportRequest req = new NaicSchedulePReportRequest(PERIOD_START, PERIOD_END,
                null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                })
                .verify();
        verify(jobRepository, never()).findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                any(), any(), anyList());
    }

    @Test
    void submit_rejectsWith422_whenConfigReaderReturnsEmptyMono() {
        // Empty-Mono is defensive: an empty reactive lookup is treated as "no config".
        when(configReader.hasEffectiveConfig(TENANT)).thenReturn(Mono.empty());

        NaicSchedulePReportRequest req = new NaicSchedulePReportRequest(PERIOD_START, PERIOD_END,
                null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err).getStatusCode().value())
                        .isEqualTo(422))
                .verify();
    }

    @Test
    void submit_dedupesInflightJob_returnsExistingJobId_whenConfigPresent() {
        ReportJob existing = jobRow(UUID.randomUUID(), NaicSchedulePJobService.STATUS_PROCESSING);
        when(jobRepository.findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                eq(TENANT), any(), anyList()))
                .thenReturn(Mono.just(existing));

        NaicSchedulePReportRequest req = new NaicSchedulePReportRequest(PERIOD_START, PERIOD_END,
                null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isTrue();
                    assertThat(resp.jobId()).isEqualTo(existing.getJobId());
                    assertThat(resp.status()).isEqualTo(NaicSchedulePJobService.STATUS_PROCESSING);
                })
                .verifyComplete();
        verify(jobRepository, never()).save(any());
    }

    @Test
    void submit_insertsNewJob_setsPrudentialStatutoryRetention_andReturnsJobId() {
        when(jobRepository.findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                eq(TENANT), any(), anyList()))
                .thenReturn(Mono.empty());
        UUID assignedJobId = UUID.randomUUID();
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> {
            ReportJob r = inv.getArgument(0);
            if (r.getJobId() == null) r.setJobId(assignedJobId);
            return Mono.just(r);
        });
        when(shapingService.shape(eq(ReportKey.NAIC_SCHEDULE_P),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(emptyData()));

        NaicSchedulePReportRequest req = new NaicSchedulePReportRequest(PERIOD_START, PERIOD_END,
                null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isFalse();
                    assertThat(resp.jobId()).isEqualTo(assignedJobId);
                    assertThat(resp.status()).isEqualTo(NaicSchedulePJobService.STATUS_REQUESTED);
                })
                .verifyComplete();
    }

    @Test
    void computeChain_dryRun_flipsToCompleted_withSectionsInResultJson() {
        ReportJob row = jobRow(UUID.randomUUID(), NaicSchedulePJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.NAIC_SCHEDULE_P),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(goldenData()));

        NaicSchedulePReportRequest req = new NaicSchedulePReportRequest(PERIOD_START, PERIOD_END,
                null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(NaicSchedulePJobService.STATUS_COMPLETED);
                    assertThat(saved.getCompletedAt()).isNotNull();
                    assertThat(saved.getResultJson()).isNotNull();
                    String rj = saved.getResultJson().asString();
                    assertThat(rj).contains("\"" + NaicSchedulePJobService.RESULT_SECTIONS + "\"");
                    assertThat(rj).doesNotContain("\"" + NaicSchedulePJobService.RESULT_SUBMISSION_ID + "\"");
                })
                .verifyComplete();
        verify(submissionService, never()).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_submitFlagTrue_archivesViaSubmissionService_andRecordsSubmissionId() {
        ReportJob row = jobRow(UUID.randomUUID(), NaicSchedulePJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.NAIC_SCHEDULE_P),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(goldenData()));
        when(xlsxService.render(eq(TENANT), any(RegulatoryReportData.class)))
                .thenReturn(Mono.just(new NaicSchedulePXlsxService.NaicSchedulePRenderResult(
                        new byte[]{1, 2, 3}, TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2026-08-30")));
        UUID submissionId = UUID.randomUUID();
        RegulatorySubmission submission = new RegulatorySubmission();
        submission.setId(submissionId);
        when(submissionService.submit(eq(TENANT), eq(ReportKey.NAIC_SCHEDULE_P.name()),
                eq(PERIOD_START), eq(PERIOD_END), eq(row.getJobId()), any(byte[].class), any(),
                eq(ACTOR.toString()), eq(ACTOR_EMAIL), eq("attest"), eq(null)))
                .thenReturn(Mono.just(submission));

        NaicSchedulePReportRequest req = new NaicSchedulePReportRequest(PERIOD_START, PERIOD_END,
                null, true, "attest");
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(NaicSchedulePJobService.STATUS_COMPLETED);
                    assertThat(saved.getResultJson().asString())
                            .contains("\"" + NaicSchedulePJobService.RESULT_SUBMISSION_ID + "\"")
                            .contains(submissionId.toString());
                })
                .verifyComplete();
        verify(submissionService, times(1)).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_shapingError_flipsToFailed_withErrorMessage() {
        ReportJob row = jobRow(UUID.randomUUID(), NaicSchedulePJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("upstream FX-rate missing for USD")));

        NaicSchedulePReportRequest req = new NaicSchedulePReportRequest(PERIOD_START, PERIOD_END,
                null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(NaicSchedulePJobService.STATUS_FAILED);
                    assertThat(saved.getErrorMessage()).contains("FX-rate missing");
                    assertThat(saved.getCompletedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void get_rejectsCrossTenantJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), NaicSchedulePJobService.STATUS_COMPLETED);
        row.setTenantId(UUID.randomUUID());  // different tenant
        when(jobRepository.findById(row.getJobId())).thenReturn(Mono.just(row));

        StepVerifier.create(service.get(row.getJobId(), TENANT))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void get_rejectsNonNaicPJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), NaicSchedulePJobService.STATUS_COMPLETED);
        row.setReportKey(ReportKey.IPEC_QUARTERLY_RETURN.name());
        when(jobRepository.findById(row.getJobId())).thenReturn(Mono.just(row));

        StepVerifier.create(service.get(row.getJobId(), TENANT))
                .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err).getStatusCode().value())
                        .isEqualTo(404))
                .verify();
    }

    @Test
    void reshapeFromJob_extractsPeriodFromParamsAndDelegatesShape() {
        ReportJob row = jobRow(UUID.randomUUID(), NaicSchedulePJobService.STATUS_COMPLETED);
        row.setParamsJson(io.r2dbc.postgresql.codec.Json.of(
                "{\"reportKey\":\"NAIC_SCHEDULE_P\","
                        + "\"periodStart\":\"2026-01-01\","
                        + "\"periodEnd\":\"2026-12-31\","
                        + "\"reportingCurrency\":\"USD\"}"));
        RegulatoryReportData data = emptyData();
        when(shapingService.shape(ReportKey.NAIC_SCHEDULE_P,
                TENANT, PERIOD_START, PERIOD_END))
                .thenReturn(Mono.just(data));

        StepVerifier.create(service.reshapeFromJob(row))
                .assertNext(d -> assertThat(d.reportKey()).isEqualTo(ReportKey.NAIC_SCHEDULE_P))
                .verifyComplete();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private ReportJob jobRow(UUID id, String status) {
        ReportJob row = new ReportJob();
        row.setJobId(id);
        row.setTenantId(TENANT);
        row.setReportKey(ReportKey.NAIC_SCHEDULE_P.name());
        row.setStatus(status);
        row.setParamsHash("dummy-hash");
        return row;
    }

    private RegulatoryReportData emptyData() {
        return RegulatoryReportData.builder(
                        ReportKey.NAIC_SCHEDULE_P, TENANT, PERIOD_START, PERIOD_END, "USD")
                .build();
    }

    private RegulatoryReportData goldenData() {
        NaicSchedulePReportShaper shaper = new NaicSchedulePReportShaper(
                (t, ps, pe) -> Mono.just(new NaicSchedulePRawData(
                        "Acme", "12345", "0999", "12-3456789", "IL",
                        new BigDecimal("100000000.00"),
                        new BigDecimal("60000000.00"),
                        new BigDecimal("20000000.00"),
                        new BigDecimal("20000000.00"),
                        new BigDecimal("30000000.00"),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("5000000.00"),
                        new BigDecimal("10000000.00"),
                        new BigDecimal("30000000.00"),
                        new BigDecimal("180000000.00"),
                        new BigDecimal("190000000.00"),
                        new BigDecimal("200000000.00"))),
                new NaicSchedulePCalculator());
        return shaper.shape(TENANT, PERIOD_START, PERIOD_END, "US").block();
    }
}
