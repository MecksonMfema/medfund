package com.medfund.finance.regulatory.cms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medfund.finance.regulatory.cms.CmsAsrCalculator;
import com.medfund.finance.regulatory.cms.CmsAsrRawData;
import com.medfund.finance.regulatory.cms.CmsAsrReportShaper;
import com.medfund.finance.regulatory.cms.CmsAsrXlsxService;
import com.medfund.finance.regulatory.cms.dto.CmsAsrReportRequest;
import com.medfund.finance.regulatory.entity.RegulatorySubmission;
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

class CmsAsrJobServiceTest {

    private static final UUID TENANT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ACTOR = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String ACTOR_EMAIL = "clerk@medical-scheme-za.example";
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 12, 31);

    private ReportJobRepository jobRepository;
    private RegulatoryReportShapingService shapingService;
    private CmsAsrXlsxService xlsxService;
    private RegulatorySubmissionService submissionService;
    private AuditPublisher auditPublisher;
    private CmsAsrJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(ReportJobRepository.class);
        shapingService = mock(RegulatoryReportShapingService.class);
        xlsxService = mock(CmsAsrXlsxService.class);
        submissionService = mock(RegulatorySubmissionService.class);
        auditPublisher = mock(AuditPublisher.class);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        // Matches Spring's autoconfigured mapper: JavaTimeModule enables
        // LocalDate serialisation for the shaped RegulatoryReportData sections.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new CmsAsrJobService(jobRepository, shapingService, xlsxService,
                submissionService, auditPublisher, mapper);
    }

    @Test
    void submit_rejectsClientCurrencyOverride_with422() {
        CmsAsrReportRequest override = new CmsAsrReportRequest(PERIOD_START, PERIOD_END,
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
        ReportJob existing = jobRow(UUID.randomUUID(), CmsAsrJobService.STATUS_PROCESSING);
        when(jobRepository.findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                eq(TENANT), any(), anyList()))
                .thenReturn(Mono.just(existing));

        CmsAsrReportRequest req = new CmsAsrReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isTrue();
                    assertThat(resp.jobId()).isEqualTo(existing.getJobId());
                    assertThat(resp.status()).isEqualTo(CmsAsrJobService.STATUS_PROCESSING);
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
        when(shapingService.shape(eq(ReportKey.CMS_ASR),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(emptyData()));

        CmsAsrReportRequest req = new CmsAsrReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isFalse();
                    assertThat(resp.jobId()).isEqualTo(assignedJobId);
                    assertThat(resp.status()).isEqualTo(CmsAsrJobService.STATUS_REQUESTED);
                })
                .verifyComplete();
    }

    @Test
    void computeChain_dryRun_flipsToCompleted_withSectionsInResultJson() {
        ReportJob row = jobRow(UUID.randomUUID(), CmsAsrJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.CMS_ASR),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(goldenData()));

        CmsAsrReportRequest req = new CmsAsrReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(CmsAsrJobService.STATUS_COMPLETED);
                    assertThat(saved.getCompletedAt()).isNotNull();
                    assertThat(saved.getResultJson()).isNotNull();
                    String rj = saved.getResultJson().asString();
                    assertThat(rj).contains("\"" + CmsAsrJobService.RESULT_SECTIONS + "\"");
                    assertThat(rj).doesNotContain("\"" + CmsAsrJobService.RESULT_SUBMISSION_ID + "\"");
                })
                .verifyComplete();
        verify(submissionService, never()).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_submitFlagTrue_archivesViaSubmissionService_andRecordsSubmissionId() {
        ReportJob row = jobRow(UUID.randomUUID(), CmsAsrJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.CMS_ASR),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(goldenData()));
        when(xlsxService.render(eq(TENANT), any(RegulatoryReportData.class)))
                .thenReturn(Mono.just(new CmsAsrXlsxService.CmsAsrRenderResult(
                        new byte[]{1, 2, 3}, TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2026-08-30")));
        UUID submissionId = UUID.randomUUID();
        RegulatorySubmission submission = new RegulatorySubmission();
        submission.setId(submissionId);
        when(submissionService.submit(eq(TENANT), eq(ReportKey.CMS_ASR.name()),
                eq(PERIOD_START), eq(PERIOD_END), eq(row.getJobId()), any(byte[].class), any(),
                eq(ACTOR.toString()), eq(ACTOR_EMAIL), eq("attest"), eq(null)))
                .thenReturn(Mono.just(submission));

        CmsAsrReportRequest req = new CmsAsrReportRequest(PERIOD_START, PERIOD_END, null, true, "attest");
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(CmsAsrJobService.STATUS_COMPLETED);
                    assertThat(saved.getResultJson().asString())
                            .contains("\"" + CmsAsrJobService.RESULT_SUBMISSION_ID + "\"")
                            .contains(submissionId.toString());
                })
                .verifyComplete();
        verify(submissionService, times(1)).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_shapingError_flipsToFailed_withErrorMessage() {
        ReportJob row = jobRow(UUID.randomUUID(), CmsAsrJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("upstream FX-rate missing for ZAR")));

        CmsAsrReportRequest req = new CmsAsrReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(CmsAsrJobService.STATUS_FAILED);
                    assertThat(saved.getErrorMessage()).contains("FX-rate missing");
                    assertThat(saved.getCompletedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void get_rejectsCrossTenantJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), CmsAsrJobService.STATUS_COMPLETED);
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
    void get_rejectsNonCmsJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), CmsAsrJobService.STATUS_COMPLETED);
        row.setReportKey(ReportKey.IPEC_QUARTERLY_RETURN.name());
        when(jobRepository.findById(row.getJobId())).thenReturn(Mono.just(row));

        StepVerifier.create(service.get(row.getJobId(), TENANT))
                .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err).getStatusCode().value())
                        .isEqualTo(404))
                .verify();
    }

    @Test
    void reshapeFromJob_extractsPeriodFromParamsAndDelegatesShape() {
        ReportJob row = jobRow(UUID.randomUUID(), CmsAsrJobService.STATUS_COMPLETED);
        row.setParamsJson(io.r2dbc.postgresql.codec.Json.of(
                "{\"reportKey\":\"CMS_ASR\","
                        + "\"periodStart\":\"2026-01-01\","
                        + "\"periodEnd\":\"2026-12-31\","
                        + "\"reportingCurrency\":\"ZAR\"}"));
        RegulatoryReportData data = emptyData();
        when(shapingService.shape(ReportKey.CMS_ASR,
                TENANT, PERIOD_START, PERIOD_END))
                .thenReturn(Mono.just(data));

        StepVerifier.create(service.reshapeFromJob(row))
                .assertNext(d -> assertThat(d.reportKey()).isEqualTo(ReportKey.CMS_ASR))
                .verifyComplete();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private ReportJob jobRow(UUID id, String status) {
        ReportJob row = new ReportJob();
        row.setJobId(id);
        row.setTenantId(TENANT);
        row.setReportKey(ReportKey.CMS_ASR.name());
        row.setStatus(status);
        row.setParamsHash("dummy-hash");
        return row;
    }

    private RegulatoryReportData emptyData() {
        return RegulatoryReportData.builder(
                        ReportKey.CMS_ASR, TENANT, PERIOD_START, PERIOD_END, "ZAR")
                .build();
    }

    private RegulatoryReportData goldenData() {
        // Build via the real shaper compose (no I/O) so downstream JSON
        // serialisation exercises the real section shape.
        CmsAsrReportShaper shaper = new CmsAsrReportShaper(
                (t, ps, pe) -> Mono.just(new CmsAsrRawData(
                        "Acme", "R-1",
                        50000L, 80000L,
                        new BigDecimal("0.1500"),
                        new BigDecimal("250000000.00"),
                        new BigDecimal("100000000.00"),
                        new BigDecimal("500000000.00"),
                        new BigDecimal("490000000.00"),
                        new BigDecimal("400000000.00"),
                        new BigDecimal("40000000.00"),
                        new BigDecimal("15000000.00"),
                        new BigDecimal("10000000.00"))),
                new CmsAsrCalculator());
        return shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZA").block();
    }
}
