package com.medfund.finance.report.schedule.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.finance.report.schedule.ReportPayloadStore;
import com.medfund.finance.report.schedule.download.ScheduledDownloadTokenIssuer;
import com.medfund.finance.report.schedule.download.ScheduledDownloadTokenVerifier;
import com.medfund.shared.security.SecurityEventPublisher;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledReportDownloadControllerTest {

    private static final String SECRET = "hmac-shared-secret";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID SCHEDULE = UUID.randomUUID();

    @Mock private ReportJobRepository repo;
    @Mock private ReportPayloadStore payloadStore;
    @Mock private SecurityEventPublisher securityEventPublisher;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private ScheduledReportDownloadController controller;
    private ScheduledDownloadTokenIssuer issuer;

    @BeforeEach
    void setUp() {
        var verifier = new ScheduledDownloadTokenVerifier(SECRET, mapper, clock);
        issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, clock);
        controller = new ScheduledReportDownloadController(
                verifier, repo, Optional.of(payloadStore), securityEventPublisher, mapper);
    }

    @Test
    void download_returnsXlsxAndEmitsSecurityEventOnHappyPath() {
        String token = issuer.issue(JOB, TENANT, "cfo@acme.com");
        ReportJob job = completedJob("COMMISSION_STATEMENT",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        byte[] bytes = "hello-xlsx".getBytes();
        when(repo.findById(JOB)).thenReturn(Mono.just(job));
        when(payloadStore.getXlsx("ref/2026/08/job.xlsx")).thenReturn(Mono.just(bytes));
        when(securityEventPublisher.publishDataAccess(anyString(), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(Mono.empty());

        StepVerifier.create(controller.download(JOB, token))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(resp.getBody()).isEqualTo(bytes);
                    assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                            .contains("commission_statement_2026-08-01_2026-08-31.xlsx");
                })
                .verifyComplete();
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(securityEventPublisher).publishDataAccess(
                eq(TENANT.toString()),
                eq("cfo@acme.com"),
                eq("cfo@acme.com"),
                eq("COMMISSION_STATEMENT"),
                details.capture());
        assertThat(details.getValue()).containsEntry("source", "SCHEDULED_LINK_DOWNLOAD");
        assertThat(details.getValue()).containsEntry("jobId", JOB.toString());
        assertThat(details.getValue()).containsEntry("scheduleId", SCHEDULE.toString());
        assertThat(details.getValue()).containsEntry("sizeBytes", bytes.length);
    }

    @Test
    void download_returnsForbiddenOnMissingToken() {
        StepVerifier.create(controller.download(JOB, ""))
                .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verifyComplete();
        verify(repo, never()).findById(any(UUID.class));
    }

    @Test
    void download_returnsForbiddenOnTamperedToken() {
        String token = issuer.issue(JOB, TENANT, "r@x.io");
        String tampered = token.substring(0, token.length() - 1) + "X";
        StepVerifier.create(controller.download(JOB, tampered))
                .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verifyComplete();
        verify(repo, never()).findById(any(UUID.class));
    }

    @Test
    void download_returnsForbiddenWhenPathJobIdMismatchesToken() {
        UUID otherJob = UUID.randomUUID();
        String token = issuer.issue(otherJob, TENANT, "r@x.io");
        StepVerifier.create(controller.download(JOB, token))
                .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verifyComplete();
        verify(repo, never()).findById(any(UUID.class));
    }

    @Test
    void download_returnsNotFoundWhenJobBelongsToDifferentTenant() {
        String token = issuer.issue(JOB, TENANT, "r@x.io");
        ReportJob job = completedJob("LOSS_RATIO",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        job.setTenantId(UUID.randomUUID());
        when(repo.findById(JOB)).thenReturn(Mono.just(job));

        StepVerifier.create(controller.download(JOB, token))
                .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
        verify(payloadStore, never()).getXlsx(anyString());
    }

    @Test
    void download_returnsNotFoundWhenJobNotCompleted() {
        String token = issuer.issue(JOB, TENANT, "r@x.io");
        ReportJob job = completedJob("LOSS_RATIO",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        job.setStatus("failed");
        when(repo.findById(JOB)).thenReturn(Mono.just(job));

        StepVerifier.create(controller.download(JOB, token))
                .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
        verify(payloadStore, never()).getXlsx(anyString());
    }

    @Test
    void download_returnsNotFoundWhenJobMissing() {
        String token = issuer.issue(JOB, TENANT, "r@x.io");
        when(repo.findById(JOB)).thenReturn(Mono.empty());
        StepVerifier.create(controller.download(JOB, token))
                .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    @Test
    void download_returns500WhenMinioFetchFails() {
        String token = issuer.issue(JOB, TENANT, "r@x.io");
        ReportJob job = completedJob("LOSS_RATIO",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        when(repo.findById(JOB)).thenReturn(Mono.just(job));
        when(payloadStore.getXlsx(anyString()))
                .thenReturn(Mono.error(new RuntimeException("minio down")));

        StepVerifier.create(controller.download(JOB, token))
                .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR))
                .verifyComplete();
        verify(securityEventPublisher, never()).publishDataAccess(anyString(), anyString(),
                anyString(), anyString(), anyMap());
    }

    @Test
    void download_returnsServiceUnavailableWhenPayloadStoreDisabled() {
        controller = new ScheduledReportDownloadController(
                new ScheduledDownloadTokenVerifier(SECRET, mapper, clock),
                repo, Optional.empty(), securityEventPublisher, mapper);
        String token = issuer.issue(JOB, TENANT, "r@x.io");

        StepVerifier.create(controller.download(JOB, token))
                .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .verifyComplete();
        verify(repo, never()).findById(any(UUID.class));
    }

    @Test
    void download_returnsNotFoundWhenResultJsonMissingXlsxRef() {
        String token = issuer.issue(JOB, TENANT, "r@x.io");
        ReportJob job = completedJob("LOSS_RATIO", null, null);
        job.setResultJson(Json.of("{}".getBytes()));
        when(repo.findById(JOB)).thenReturn(Mono.just(job));

        StepVerifier.create(controller.download(JOB, token))
                .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
        verify(payloadStore, never()).getXlsx(anyString());
    }

    @Test
    void filenameFor_handlesRangeSingleAndMissingPeriods() {
        ReportJob a = new ReportJob();
        a.setReportKey("COMMISSION_STATEMENT");
        a.setPeriodStart(LocalDate.parse("2026-08-01"));
        a.setPeriodEnd(LocalDate.parse("2026-08-31"));
        assertThat(ScheduledReportDownloadController.filenameFor(a))
                .isEqualTo("commission_statement_2026-08-01_2026-08-31.xlsx");

        ReportJob b = new ReportJob();
        b.setReportKey("AGED_DEBTORS");
        b.setPeriodStart(LocalDate.parse("2026-08-31"));
        b.setPeriodEnd(LocalDate.parse("2026-08-31"));
        assertThat(ScheduledReportDownloadController.filenameFor(b))
                .isEqualTo("aged_debtors_2026-08-31.xlsx");

        ReportJob c = new ReportJob();
        c.setReportKey("LOSS_RATIO");
        assertThat(ScheduledReportDownloadController.filenameFor(c)).isEqualTo("loss_ratio.xlsx");
    }

    private ReportJob completedJob(String key, LocalDate periodStart, LocalDate periodEnd) {
        ReportJob job = new ReportJob();
        job.setJobId(JOB);
        job.setTenantId(TENANT);
        job.setScheduleId(SCHEDULE);
        job.setReportKey(key);
        job.setStatus("completed");
        job.setPeriodStart(periodStart);
        job.setPeriodEnd(periodEnd);
        job.setResultJson(Json.of("{\"xlsxRef\":\"ref/2026/08/job.xlsx\",\"sizeBytes\":10}".getBytes()));
        job.setRequestedAt(OffsetDateTime.now(clock));
        job.setCompletedAt(OffsetDateTime.now(clock));
        return job;
    }
}
