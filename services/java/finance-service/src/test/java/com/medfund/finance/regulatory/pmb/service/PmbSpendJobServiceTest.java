package com.medfund.finance.regulatory.pmb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import com.medfund.finance.regulatory.pmb.PmbCategory;
import com.medfund.finance.regulatory.pmb.PmbSpendCalculator;
import com.medfund.finance.regulatory.pmb.PmbSpendRawData;
import com.medfund.finance.regulatory.pmb.PmbSpendReportShaper;
import com.medfund.finance.regulatory.pmb.PmbSpendXlsxService;
import com.medfund.finance.regulatory.pmb.dto.PmbSpendReportRequest;
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
import java.util.EnumMap;
import java.util.Map;
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

class PmbSpendJobServiceTest {

    private static final UUID TENANT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID ACTOR = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final String ACTOR_EMAIL = "compliance@medical-scheme-za.example";
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 12, 31);

    private ReportJobRepository jobRepository;
    private RegulatoryReportShapingService shapingService;
    private PmbSpendXlsxService xlsxService;
    private RegulatorySubmissionService submissionService;
    private AuditPublisher auditPublisher;
    private PmbSpendJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(ReportJobRepository.class);
        shapingService = mock(RegulatoryReportShapingService.class);
        xlsxService = mock(PmbSpendXlsxService.class);
        submissionService = mock(RegulatorySubmissionService.class);
        auditPublisher = mock(AuditPublisher.class);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new PmbSpendJobService(jobRepository, shapingService, xlsxService,
                submissionService, auditPublisher, mapper);
    }

    @Test
    void submit_rejectsClientCurrencyOverride_with422() {
        PmbSpendReportRequest override = new PmbSpendReportRequest(PERIOD_START, PERIOD_END,
                "USD", false, null);

        StepVerifier.create(service.submit(override, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                })
                .verify();
    }

    @Test
    void submit_dedupesInflightJob_returnsExistingJobId() {
        ReportJob existing = jobRow(UUID.randomUUID(), PmbSpendJobService.STATUS_PROCESSING);
        when(jobRepository.findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                eq(TENANT), any(), anyList()))
                .thenReturn(Mono.just(existing));

        PmbSpendReportRequest req = new PmbSpendReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isTrue();
                    assertThat(resp.jobId()).isEqualTo(existing.getJobId());
                    assertThat(resp.status()).isEqualTo(PmbSpendJobService.STATUS_PROCESSING);
                })
                .verifyComplete();
        verify(jobRepository, never()).save(any());
    }

    @Test
    void submit_insertsNewJob_setsComplianceStatutoryRetention_andReturnsJobId() {
        when(jobRepository.findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                eq(TENANT), any(), anyList()))
                .thenReturn(Mono.empty());
        UUID assignedJobId = UUID.randomUUID();
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> {
            ReportJob r = inv.getArgument(0);
            if (r.getJobId() == null) r.setJobId(assignedJobId);
            return Mono.just(r);
        });
        when(shapingService.shape(eq(ReportKey.PMB_SPEND),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(emptyData()));

        PmbSpendReportRequest req = new PmbSpendReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isFalse();
                    assertThat(resp.jobId()).isEqualTo(assignedJobId);
                    assertThat(resp.status()).isEqualTo(PmbSpendJobService.STATUS_REQUESTED);
                })
                .verifyComplete();
    }

    @Test
    void computeChain_dryRun_flipsToCompleted_withSectionsInResultJson() {
        ReportJob row = jobRow(UUID.randomUUID(), PmbSpendJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.PMB_SPEND),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(goldenData()));

        PmbSpendReportRequest req = new PmbSpendReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(PmbSpendJobService.STATUS_COMPLETED);
                    assertThat(saved.getCompletedAt()).isNotNull();
                    assertThat(saved.getResultJson()).isNotNull();
                    String rj = saved.getResultJson().asString();
                    assertThat(rj).contains("\"" + PmbSpendJobService.RESULT_SECTIONS + "\"");
                    assertThat(rj).doesNotContain("\"" + PmbSpendJobService.RESULT_SUBMISSION_ID + "\"");
                })
                .verifyComplete();
        verify(submissionService, never()).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_submitFlagTrue_archivesViaSubmissionService_andRecordsSubmissionId() {
        ReportJob row = jobRow(UUID.randomUUID(), PmbSpendJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.PMB_SPEND),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(goldenData()));
        when(xlsxService.render(eq(TENANT), any(RegulatoryReportData.class)))
                .thenReturn(Mono.just(new PmbSpendXlsxService.PmbSpendRenderResult(
                        new byte[]{1, 2, 3}, TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2026-08-30")));
        UUID submissionId = UUID.randomUUID();
        RegulatorySubmission submission = new RegulatorySubmission();
        submission.setId(submissionId);
        when(submissionService.submit(eq(TENANT), eq(ReportKey.PMB_SPEND.name()),
                eq(PERIOD_START), eq(PERIOD_END), eq(row.getJobId()), any(byte[].class), any(),
                eq(ACTOR.toString()), eq(ACTOR_EMAIL), eq("attest"), eq(null)))
                .thenReturn(Mono.just(submission));

        PmbSpendReportRequest req = new PmbSpendReportRequest(PERIOD_START, PERIOD_END, null, true, "attest");
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(PmbSpendJobService.STATUS_COMPLETED);
                    assertThat(saved.getResultJson().asString())
                            .contains("\"" + PmbSpendJobService.RESULT_SUBMISSION_ID + "\"")
                            .contains(submissionId.toString());
                })
                .verifyComplete();
        verify(submissionService, times(1)).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_shapingError_flipsToFailed_withErrorMessage() {
        ReportJob row = jobRow(UUID.randomUUID(), PmbSpendJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("upstream FX-rate missing for ZAR")));

        PmbSpendReportRequest req = new PmbSpendReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(PmbSpendJobService.STATUS_FAILED);
                    assertThat(saved.getErrorMessage()).contains("FX-rate missing");
                    assertThat(saved.getCompletedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void get_rejectsCrossTenantJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), PmbSpendJobService.STATUS_COMPLETED);
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
    void get_rejectsNonPmbJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), PmbSpendJobService.STATUS_COMPLETED);
        row.setReportKey(ReportKey.CMS_ASR.name());
        when(jobRepository.findById(row.getJobId())).thenReturn(Mono.just(row));

        StepVerifier.create(service.get(row.getJobId(), TENANT))
                .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err).getStatusCode().value())
                        .isEqualTo(404))
                .verify();
    }

    @Test
    void reshapeFromJob_extractsPeriodFromParamsAndDelegatesShape() {
        ReportJob row = jobRow(UUID.randomUUID(), PmbSpendJobService.STATUS_COMPLETED);
        row.setParamsJson(io.r2dbc.postgresql.codec.Json.of(
                "{\"reportKey\":\"PMB_SPEND\","
                        + "\"periodStart\":\"2026-01-01\","
                        + "\"periodEnd\":\"2026-12-31\","
                        + "\"reportingCurrency\":\"ZAR\"}"));
        RegulatoryReportData data = emptyData();
        when(shapingService.shape(ReportKey.PMB_SPEND,
                TENANT, PERIOD_START, PERIOD_END))
                .thenReturn(Mono.just(data));

        StepVerifier.create(service.reshapeFromJob(row))
                .assertNext(d -> assertThat(d.reportKey()).isEqualTo(ReportKey.PMB_SPEND))
                .verifyComplete();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private ReportJob jobRow(UUID id, String status) {
        ReportJob row = new ReportJob();
        row.setJobId(id);
        row.setTenantId(TENANT);
        row.setReportKey(ReportKey.PMB_SPEND.name());
        row.setStatus(status);
        row.setParamsHash("dummy-hash");
        return row;
    }

    private RegulatoryReportData emptyData() {
        return RegulatoryReportData.builder(
                        ReportKey.PMB_SPEND, TENANT, PERIOD_START, PERIOD_END, "ZAR")
                .build();
    }

    private RegulatoryReportData goldenData() {
        // Build via the real shaper compose (no I/O) so downstream JSON
        // serialisation exercises the real section shape.
        Map<PmbCategory, BigDecimal> paid = new EnumMap<>(PmbCategory.class);
        Map<PmbCategory, Long> count = new EnumMap<>(PmbCategory.class);
        for (PmbCategory c : PmbCategory.values()) {
            paid.put(c, new BigDecimal("1000000.00"));
            count.put(c, 100L);
        }
        PmbSpendReportShaper shaper = new PmbSpendReportShaper(
                (t, ps, pe) -> Mono.just(new PmbSpendRawData(
                        "Acme", "R-1", 10000L, paid, count,
                        new BigDecimal("50000000.00"))),
                new PmbSpendCalculator());
        return shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZA").block();
    }
}
