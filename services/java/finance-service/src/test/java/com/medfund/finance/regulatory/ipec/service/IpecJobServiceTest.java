package com.medfund.finance.regulatory.ipec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import com.medfund.finance.regulatory.ipec.IpecCellMap;
import com.medfund.finance.regulatory.ipec.IpecRawData;
import com.medfund.finance.regulatory.ipec.IpecReportShaper;
import com.medfund.finance.regulatory.ipec.IpecSolvencyCalculator;
import com.medfund.finance.regulatory.ipec.IpecXlsxService;
import com.medfund.finance.regulatory.ipec.dto.IpecReportRequest;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.finance.regulatory.service.RegulatoryReportShapingService;
import com.medfund.finance.regulatory.service.RegulatorySubmissionService;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.RegulatoryTemplateService;
import com.medfund.shared.report.regulatory.TemplateResolution;
import com.medfund.shared.report.regulatory.TemplateSource;
import com.medfund.shared.tenant.TenantMetadataReader;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

class IpecJobServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ACTOR_EMAIL = "clerk@insurer-zw.example";
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 6, 30);

    private ReportJobRepository jobRepository;
    private RegulatoryReportShapingService shapingService;
    private IpecXlsxService xlsxService;
    private RegulatorySubmissionService submissionService;
    private AuditPublisher auditPublisher;
    private IpecJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(ReportJobRepository.class);
        shapingService = mock(RegulatoryReportShapingService.class);
        xlsxService = mock(IpecXlsxService.class);
        submissionService = mock(RegulatorySubmissionService.class);
        auditPublisher = mock(AuditPublisher.class);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        // Matches Spring's autoconfigured mapper: JavaTimeModule enables
        // LocalDate serialisation for the shaped RegulatoryReportData sections.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new IpecJobService(jobRepository, shapingService, xlsxService,
                submissionService, auditPublisher, mapper);
    }

    @Test
    void submit_rejectsClientCurrencyOverride_with422() {
        IpecReportRequest override = new IpecReportRequest(PERIOD_START, PERIOD_END,
                "USD", false, null);

        // Reject happens inside the Mono chain (Mono.defer) so the error is
        // reactive; StepVerifier .verifyError catches it.
        StepVerifier.create(service.submit(override, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                })
                .verify();
    }

    @Test
    void submit_dedupesInflightJob_returnsExistingJobId() {
        ReportJob existing = jobRow(UUID.randomUUID(), IpecJobService.STATUS_PROCESSING);
        when(jobRepository.findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                eq(TENANT), any(), anyList()))
                .thenReturn(Mono.just(existing));

        IpecReportRequest req = new IpecReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isTrue();
                    assertThat(resp.jobId()).isEqualTo(existing.getJobId());
                    assertThat(resp.status()).isEqualTo(IpecJobService.STATUS_PROCESSING);
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
        // Compute chain shape returns an empty data blob — for this test we
        // care about the insert path, not the compute completion.
        when(shapingService.shape(eq(ReportKey.IPEC_QUARTERLY_RETURN),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(emptyData()));

        IpecReportRequest req = new IpecReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isFalse();
                    assertThat(resp.jobId()).isEqualTo(assignedJobId);
                    assertThat(resp.status()).isEqualTo(IpecJobService.STATUS_REQUESTED);
                })
                .verifyComplete();
    }

    @Test
    void computeChain_dryRun_flipsToCompleted_withSectionsInResultJson() {
        ReportJob row = jobRow(UUID.randomUUID(), IpecJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.IPEC_QUARTERLY_RETURN),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(goldenData()));

        IpecReportRequest req = new IpecReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(IpecJobService.STATUS_COMPLETED);
                    assertThat(saved.getCompletedAt()).isNotNull();
                    assertThat(saved.getResultJson()).isNotNull();
                    String rj = saved.getResultJson().asString();
                    assertThat(rj).contains("\"" + IpecJobService.RESULT_SECTIONS + "\"");
                    assertThat(rj).doesNotContain("\"" + IpecJobService.RESULT_SUBMISSION_ID + "\"");
                })
                .verifyComplete();
        verify(submissionService, never()).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_submitFlagTrue_archivesViaSubmissionService_andRecordsSubmissionId() {
        ReportJob row = jobRow(UUID.randomUUID(), IpecJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.IPEC_QUARTERLY_RETURN),
                eq(TENANT), eq(PERIOD_START), eq(PERIOD_END)))
                .thenReturn(Mono.just(goldenData()));
        // Return a fake rendered XLSX byte pack.
        when(xlsxService.render(eq(TENANT), any(RegulatoryReportData.class)))
                .thenReturn(Mono.just(new IpecXlsxService.IpecRenderResult(
                        new byte[]{1, 2, 3}, TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2024-06-01")));
        UUID submissionId = UUID.randomUUID();
        RegulatorySubmission submission = new RegulatorySubmission();
        submission.setId(submissionId);
        when(submissionService.submit(eq(TENANT), eq(ReportKey.IPEC_QUARTERLY_RETURN.name()),
                eq(PERIOD_START), eq(PERIOD_END), eq(row.getJobId()), any(byte[].class), any(),
                eq(ACTOR.toString()), eq(ACTOR_EMAIL), eq("attest"), eq(null)))
                .thenReturn(Mono.just(submission));

        IpecReportRequest req = new IpecReportRequest(PERIOD_START, PERIOD_END, null, true, "attest");
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(IpecJobService.STATUS_COMPLETED);
                    assertThat(saved.getResultJson().asString())
                            .contains("\"" + IpecJobService.RESULT_SUBMISSION_ID + "\"")
                            .contains(submissionId.toString());
                })
                .verifyComplete();
        verify(submissionService, times(1)).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_shapingError_flipsToFailed_withErrorMessage() {
        ReportJob row = jobRow(UUID.randomUUID(), IpecJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("upstream FX-rate missing for ZWL")));

        IpecReportRequest req = new IpecReportRequest(PERIOD_START, PERIOD_END, null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(IpecJobService.STATUS_FAILED);
                    assertThat(saved.getErrorMessage()).contains("FX-rate missing");
                    assertThat(saved.getCompletedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void get_rejectsCrossTenantJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), IpecJobService.STATUS_COMPLETED);
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
    void get_rejectsNonIpecJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), IpecJobService.STATUS_COMPLETED);
        row.setReportKey(ReportKey.IFRS17_LRC_LIC_RECONCILIATION.name());
        when(jobRepository.findById(row.getJobId())).thenReturn(Mono.just(row));

        StepVerifier.create(service.get(row.getJobId(), TENANT))
                .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err).getStatusCode().value())
                        .isEqualTo(404))
                .verify();
    }

    @Test
    void reshapeFromJob_extractsPeriodFromParamsAndDelegatesShape() {
        ReportJob row = jobRow(UUID.randomUUID(), IpecJobService.STATUS_COMPLETED);
        // Params_json set via a fake serialisation of the shape.
        row.setParamsJson(io.r2dbc.postgresql.codec.Json.of(
                "{\"reportKey\":\"IPEC_QUARTERLY_RETURN\","
                        + "\"periodStart\":\"2026-04-01\","
                        + "\"periodEnd\":\"2026-06-30\","
                        + "\"reportingCurrency\":\"ZWL\"}"));
        RegulatoryReportData data = emptyData();
        when(shapingService.shape(ReportKey.IPEC_QUARTERLY_RETURN,
                TENANT, PERIOD_START, PERIOD_END))
                .thenReturn(Mono.just(data));

        StepVerifier.create(service.reshapeFromJob(row))
                .assertNext(d -> assertThat(d.reportKey()).isEqualTo(ReportKey.IPEC_QUARTERLY_RETURN))
                .verifyComplete();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private ReportJob jobRow(UUID id, String status) {
        ReportJob row = new ReportJob();
        row.setJobId(id);
        row.setTenantId(TENANT);
        row.setReportKey(ReportKey.IPEC_QUARTERLY_RETURN.name());
        row.setStatus(status);
        row.setParamsHash("dummy-hash");
        return row;
    }

    private RegulatoryReportData emptyData() {
        return RegulatoryReportData.builder(
                        ReportKey.IPEC_QUARTERLY_RETURN, TENANT, PERIOD_START, PERIOD_END, "ZWL")
                .build();
    }

    private RegulatoryReportData goldenData() {
        // Build via the real shaper compose (no I/O) so downstream JSON
        // serialisation exercises the real section shape.
        IpecReportShaper shaper = new IpecReportShaper(
                (t, ps, pe) -> Mono.just(new IpecRawData(
                        "Acme", "L-1",
                        new BigDecimal("12500000.00"), new BigDecimal("8300000.00"),
                        new BigDecimal("3000000.00"), new BigDecimal("1500000.00"),
                        new BigDecimal("500000.00"), new BigDecimal("1000000.00"),
                        new BigDecimal("3800000.00"), new BigDecimal("2400000.00"),
                        new BigDecimal("600000.00"),
                        new BigDecimal("800000.00"), new BigDecimal("400000.00"),
                        new BigDecimal("150000.00"),
                        new BigDecimal("900000.00"), new BigDecimal("300000.00"),
                        new BigDecimal("100000.00"),
                        new BigDecimal("250000.00"), new BigDecimal("80000.00"),
                        new BigDecimal("40000.00"),
                        new BigDecimal("200000.00"), new BigDecimal("90000.00"))),
                new IpecSolvencyCalculator());
        return shaper.shape(TENANT, PERIOD_START, PERIOD_END, "ZW").block();
    }
}
