package com.medfund.finance.regulatory.tax.wht.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.finance.regulatory.service.RegulatoryReportShapingService;
import com.medfund.finance.regulatory.service.RegulatorySubmissionService;
import com.medfund.finance.regulatory.tax.wht.TaxWithheldXlsxService;
import com.medfund.finance.regulatory.tax.wht.dto.TaxWithheldReturnReportRequest;
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

class TaxWithheldReturnJobServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String ACTOR_EMAIL = "tax@insurer-za.example";
    private static final LocalDate PS = LocalDate.of(2026, 4, 1);
    private static final LocalDate PE = LocalDate.of(2026, 6, 30);

    private ReportJobRepository jobRepository;
    private RegulatoryReportShapingService shapingService;
    private TaxWithheldXlsxService xlsxService;
    private RegulatorySubmissionService submissionService;
    private AuditPublisher auditPublisher;
    private TaxWithheldReturnJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(ReportJobRepository.class);
        shapingService = mock(RegulatoryReportShapingService.class);
        xlsxService = mock(TaxWithheldXlsxService.class);
        submissionService = mock(RegulatorySubmissionService.class);
        auditPublisher = mock(AuditPublisher.class);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new TaxWithheldReturnJobService(jobRepository, shapingService, xlsxService,
                submissionService, auditPublisher, mapper);
    }

    @Test
    void submit_rejectsClientCurrencyOverride_with422() {
        TaxWithheldReturnReportRequest override = new TaxWithheldReturnReportRequest(PS, PE,
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
        ReportJob existing = jobRow(UUID.randomUUID(), TaxWithheldReturnJobService.STATUS_PROCESSING);
        when(jobRepository.findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                eq(TENANT), any(), anyList()))
                .thenReturn(Mono.just(existing));
        TaxWithheldReturnReportRequest req = new TaxWithheldReturnReportRequest(PS, PE, null, false, null);

        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isTrue();
                    assertThat(resp.jobId()).isEqualTo(existing.getJobId());
                })
                .verifyComplete();
        verify(jobRepository, never()).save(any());
    }

    @Test
    void submit_insertsNewJob_returnsJobId() {
        when(jobRepository.findFirstByTenantIdAndParamsHashAndStatusInOrderByRequestedAtDesc(
                eq(TENANT), any(), anyList()))
                .thenReturn(Mono.empty());
        UUID assignedJobId = UUID.randomUUID();
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> {
            ReportJob r = inv.getArgument(0);
            if (r.getJobId() == null) r.setJobId(assignedJobId);
            return Mono.just(r);
        });
        when(shapingService.shape(eq(ReportKey.TAX_WITHHELD_RETURN),
                eq(TENANT), eq(PS), eq(PE)))
                .thenReturn(Mono.just(emptyData()));

        TaxWithheldReturnReportRequest req = new TaxWithheldReturnReportRequest(PS, PE, null, false, null);
        StepVerifier.create(service.submit(req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.deduplicated()).isFalse();
                    assertThat(resp.jobId()).isEqualTo(assignedJobId);
                    assertThat(resp.status()).isEqualTo(TaxWithheldReturnJobService.STATUS_REQUESTED);
                })
                .verifyComplete();
    }

    @Test
    void computeChain_dryRun_flipsToCompleted_withSectionsInResultJson() {
        ReportJob row = jobRow(UUID.randomUUID(), TaxWithheldReturnJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.TAX_WITHHELD_RETURN),
                eq(TENANT), eq(PS), eq(PE)))
                .thenReturn(Mono.just(emptyData()));

        TaxWithheldReturnReportRequest req = new TaxWithheldReturnReportRequest(PS, PE, null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(TaxWithheldReturnJobService.STATUS_COMPLETED);
                    String rj = saved.getResultJson().asString();
                    assertThat(rj).contains("\"" + TaxWithheldReturnJobService.RESULT_SECTIONS + "\"");
                    assertThat(rj).doesNotContain("\"" + TaxWithheldReturnJobService.RESULT_SUBMISSION_ID + "\"");
                })
                .verifyComplete();
        verify(submissionService, never()).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_submitFlagTrue_archivesViaSubmissionService_andRecordsSubmissionId() {
        ReportJob row = jobRow(UUID.randomUUID(), TaxWithheldReturnJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(eq(ReportKey.TAX_WITHHELD_RETURN),
                eq(TENANT), eq(PS), eq(PE)))
                .thenReturn(Mono.just(emptyData()));
        when(xlsxService.render(eq(TENANT), any(RegulatoryReportData.class)))
                .thenReturn(Mono.just(new TaxWithheldXlsxService.TaxWithheldRenderResult(
                        new byte[]{1, 2, 3}, TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2024-01-01")));
        UUID submissionId = UUID.randomUUID();
        RegulatorySubmission submission = new RegulatorySubmission();
        submission.setId(submissionId);
        when(submissionService.submit(eq(TENANT), eq(ReportKey.TAX_WITHHELD_RETURN.name()),
                eq(PS), eq(PE), eq(row.getJobId()), any(byte[].class), any(),
                eq(ACTOR.toString()), eq(ACTOR_EMAIL), eq("attest"), eq(null)))
                .thenReturn(Mono.just(submission));

        TaxWithheldReturnReportRequest req = new TaxWithheldReturnReportRequest(PS, PE, null, true, "attest");
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(TaxWithheldReturnJobService.STATUS_COMPLETED);
                    assertThat(saved.getResultJson().asString()).contains(submissionId.toString());
                })
                .verifyComplete();
        verify(submissionService, times(1)).submit(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeChain_shapingError_flipsToFailed() {
        ReportJob row = jobRow(UUID.randomUUID(), TaxWithheldReturnJobService.STATUS_REQUESTED);
        when(jobRepository.save(any(ReportJob.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(shapingService.shape(any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("FX rate missing for ZAR")));

        TaxWithheldReturnReportRequest req = new TaxWithheldReturnReportRequest(PS, PE, null, false, null);
        StepVerifier.create(service.computeChain(row, req, TENANT, null, ACTOR.toString(), ACTOR_EMAIL))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(TaxWithheldReturnJobService.STATUS_FAILED);
                    assertThat(saved.getErrorMessage()).contains("FX rate missing");
                })
                .verifyComplete();
    }

    @Test
    void get_rejectsCrossTenantJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), TaxWithheldReturnJobService.STATUS_COMPLETED);
        row.setTenantId(UUID.randomUUID());
        when(jobRepository.findById(row.getJobId())).thenReturn(Mono.just(row));

        StepVerifier.create(service.get(row.getJobId(), TENANT))
                .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err).getStatusCode().value())
                        .isEqualTo(404))
                .verify();
    }

    @Test
    void get_rejectsNonWhtJob_with404() {
        ReportJob row = jobRow(UUID.randomUUID(), TaxWithheldReturnJobService.STATUS_COMPLETED);
        row.setReportKey(ReportKey.VAT_RETURN.name());
        when(jobRepository.findById(row.getJobId())).thenReturn(Mono.just(row));

        StepVerifier.create(service.get(row.getJobId(), TENANT))
                .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err).getStatusCode().value())
                        .isEqualTo(404))
                .verify();
    }

    @Test
    void reshapeFromJob_extractsPeriodFromParamsAndDelegatesShape() {
        ReportJob row = jobRow(UUID.randomUUID(), TaxWithheldReturnJobService.STATUS_COMPLETED);
        row.setParamsJson(io.r2dbc.postgresql.codec.Json.of(
                "{\"reportKey\":\"TAX_WITHHELD_RETURN\","
                        + "\"periodStart\":\"2026-04-01\","
                        + "\"periodEnd\":\"2026-06-30\","
                        + "\"reportingCurrency\":\"COUNTRY_NATIVE\"}"));
        when(shapingService.shape(ReportKey.TAX_WITHHELD_RETURN, TENANT, PS, PE))
                .thenReturn(Mono.just(emptyData()));

        StepVerifier.create(service.reshapeFromJob(row))
                .assertNext(d -> assertThat(d.reportKey()).isEqualTo(ReportKey.TAX_WITHHELD_RETURN))
                .verifyComplete();
    }

    private ReportJob jobRow(UUID id, String status) {
        ReportJob row = new ReportJob();
        row.setJobId(id);
        row.setTenantId(TENANT);
        row.setReportKey(ReportKey.TAX_WITHHELD_RETURN.name());
        row.setStatus(status);
        row.setParamsHash("dummy-hash");
        return row;
    }

    private RegulatoryReportData emptyData() {
        return RegulatoryReportData.builder(
                        ReportKey.TAX_WITHHELD_RETURN, TENANT, PS, PE, "ZAR")
                .build();
    }
}
