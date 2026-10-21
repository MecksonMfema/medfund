package com.medfund.finance.regulatory.service;

import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import com.medfund.finance.regulatory.repository.RegulatorySubmissionRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.security.MfaStepUpGuard;
import com.medfund.shared.security.MfaStepUpRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegulatorySubmissionServiceTest {

    @Mock
    private RegulatorySubmissionRepository repository;
    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;
    @Mock
    private AuditPublisher auditPublisher;
    @Mock
    private MfaStepUpGuard mfaGuard;

    @Captor
    private ArgumentCaptor<AuditEvent> auditCaptor;
    @Captor
    private ArgumentCaptor<RegulatorySubmission> rowCaptor;

    @InjectMocks
    private RegulatorySubmissionService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID SOURCE_RUN = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();
    private static final Jwt STUB_JWT = Jwt.withTokenValue("t").header("alg", "none")
            .subject(ACTOR).claim("email", "admin@acme").build();

    @BeforeEach
    void wireMfaAndAudit() {
        // Every submit path invokes the MFA guard. Default to pass so we can vary per test.
        lenient().when(mfaGuard.requireStepUp(any())).thenReturn(Mono.empty());
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
    }

    @Test
    void submit_firstEver_insertsWithNumberOneAndNoSupersedesId() {
        LocalDate p = LocalDate.of(2026, 1, 1);
        when(repository.findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                TENANT_ID, "IPEC_QUARTERLY_RETURN", p)).thenReturn(Flux.empty());
        when(r2dbcTemplate.insert(any(RegulatorySubmission.class))).thenAnswer(inv -> {
            RegulatorySubmission r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });

        StepVerifier.create(service.submit(
                TENANT_ID, "IPEC_QUARTERLY_RETURN", p, p.plusMonths(3), SOURCE_RUN,
                new byte[]{1, 2, 3}, STUB_JWT, ACTOR, "admin@acme",
                "I certify …", null))
                .assertNext(row -> {
                    assertThat(row.getSubmissionNumber()).isEqualTo(1);
                    assertThat(row.getSupersedesId()).isNull();
                    assertThat(row.getStatus()).isEqualTo("SUBMITTED");
                    assertThat(row.getXlsxContentHash()).hasSize(64);
                    assertThat(row.getSubmittedByActorEmail()).isEqualTo("admin@acme");
                    assertThat(row.getAttestationNote()).isEqualTo("I certify …");
                })
                .verifyComplete();

        verify(mfaGuard).requireStepUp(STUB_JWT);
        // No prior row → save() never called for the SUPERSEDED update
        verify(repository, never()).save(any());
        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("CREATE");
        assertThat(ev.entityName())
                .contains("IPEC_QUARTERLY_RETURN")
                .contains("2026-01-01")
                .contains("#1");
    }

    @Test
    void submit_secondForSamePeriod_incrementsNumberAndMarksPriorSuperseded() {
        LocalDate p = LocalDate.of(2026, 1, 1);
        RegulatorySubmission prior = new RegulatorySubmission();
        prior.setId(UUID.randomUUID());
        prior.setTenantId(TENANT_ID);
        prior.setReportKey("CMS_ASR");
        prior.setPeriodStart(p);
        prior.setSubmissionNumber(1);
        prior.setStatus("SUBMITTED");

        when(repository.findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                TENANT_ID, "CMS_ASR", p)).thenReturn(Flux.just(prior));
        when(repository.save(any(RegulatorySubmission.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(r2dbcTemplate.insert(any(RegulatorySubmission.class))).thenAnswer(inv -> {
            RegulatorySubmission r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });

        StepVerifier.create(service.submit(
                TENANT_ID, "CMS_ASR", p, p.plusYears(1), SOURCE_RUN,
                new byte[]{9, 9, 9}, STUB_JWT, ACTOR, "admin@acme", null, "restatement"))
                .assertNext(row -> {
                    assertThat(row.getSubmissionNumber()).isEqualTo(2);
                    assertThat(row.getSupersedesId()).isEqualTo(prior.getId());
                    assertThat(row.getStatus()).isEqualTo("SUBMITTED");
                    assertThat(row.getReasonNote()).isEqualTo("restatement");
                })
                .verifyComplete();

        // Prior row was saved with SUPERSEDED status
        verify(repository).save(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getStatus()).isEqualTo("SUPERSEDED");
        // One insert (the new row), one CREATE audit
        verify(auditPublisher).publish(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("CREATE");
    }

    @Test
    void submit_thirdForSamePeriod_incrementsToThree() {
        LocalDate p = LocalDate.of(2026, 1, 1);
        RegulatorySubmission n2 = new RegulatorySubmission();
        n2.setId(UUID.randomUUID());
        n2.setTenantId(TENANT_ID);
        n2.setReportKey("CMS_ASR");
        n2.setPeriodStart(p);
        n2.setSubmissionNumber(2);
        n2.setStatus("SUBMITTED");

        when(repository.findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                TENANT_ID, "CMS_ASR", p)).thenReturn(Flux.just(n2));
        when(repository.save(any(RegulatorySubmission.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(r2dbcTemplate.insert(any(RegulatorySubmission.class))).thenAnswer(inv -> {
            RegulatorySubmission r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });

        StepVerifier.create(service.submit(
                TENANT_ID, "CMS_ASR", p, p.plusYears(1), SOURCE_RUN,
                new byte[]{7}, STUB_JWT, ACTOR, "admin@acme", null, null))
                .assertNext(row -> assertThat(row.getSubmissionNumber()).isEqualTo(3))
                .verifyComplete();
    }

    @Test
    void submit_failsMfaGate_neverTouchesInsertOrAudit() {
        when(mfaGuard.requireStepUp(any())).thenReturn(
                Mono.error(new MfaStepUpRequiredException("no mfa")));
        // Java eagerly evaluates the .then(...) argument even though the
        // reactive chain short-circuits on the mfaGuard error, so the repo
        // fluent chain must not be null. Return an empty Flux — it is never
        // actually subscribed.
        lenient().when(repository.findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                any(), any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(service.submit(
                TENANT_ID, "IPEC_QUARTERLY_RETURN", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31), SOURCE_RUN,
                new byte[]{1}, STUB_JWT, ACTOR, "admin@acme", null, null))
                .expectError(MfaStepUpRequiredException.class)
                .verify();

        verify(r2dbcTemplate, never()).insert(any(RegulatorySubmission.class));
        verify(auditPublisher, never()).publish(any(AuditEvent.class));
    }

    @Test
    void submit_rejectsInvalidInputs() {
        // Empty xlsxBytes → 400 without hitting the MFA gate
        StepVerifier.create(service.submit(
                TENANT_ID, "K", LocalDate.now(), LocalDate.now(), SOURCE_RUN,
                new byte[0], STUB_JWT, ACTOR, "e", null, null))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Null reportKey
        StepVerifier.create(service.submit(
                TENANT_ID, null, LocalDate.now(), LocalDate.now(), SOURCE_RUN,
                new byte[]{1}, STUB_JWT, ACTOR, "e", null, null))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(mfaGuard, never()).requireStepUp(any());
    }

    @Test
    void setFilingRef_ownRow_updatesAndAudits() {
        UUID id = UUID.randomUUID();
        RegulatorySubmission row = new RegulatorySubmission();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setReportKey("VAT_RETURN");
        row.setPeriodStart(LocalDate.of(2026, 8, 1));
        row.setPeriodEnd(LocalDate.of(2026, 8, 31));
        row.setSubmissionNumber(1);
        when(repository.findById(id)).thenReturn(Mono.just(row));
        when(repository.save(any(RegulatorySubmission.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.setFilingRef(TENANT_ID, id, "ZIMRA-2026-VAT-000123", ACTOR, "admin@acme"))
                .assertNext(saved -> assertThat(saved.getFilingRef()).isEqualTo("ZIMRA-2026-VAT-000123"))
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("UPDATE");
        assertThat(List.of(auditCaptor.getValue().changedFields())).contains("filingRef");
    }

    @Test
    void setFilingRef_crossTenant_rejects() {
        UUID id = UUID.randomUUID();
        RegulatorySubmission row = new RegulatorySubmission();
        row.setId(id);
        row.setTenantId(OTHER_TENANT);
        when(repository.findById(id)).thenReturn(Mono.just(row));

        StepVerifier.create(service.setFilingRef(TENANT_ID, id, "x", ACTOR, "admin@acme"))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repository, never()).save(any(RegulatorySubmission.class));
        verify(auditPublisher, never()).publish(any(AuditEvent.class));
    }

    @Test
    void get_missing_404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.get(TENANT_ID, id))
                .expectError(NoSuchElementException.class)
                .verify();
    }

    @Test
    void list_returnsRepoFlux() {
        LocalDate p = LocalDate.of(2026, 1, 1);
        RegulatorySubmission a = new RegulatorySubmission(); a.setSubmissionNumber(2);
        RegulatorySubmission b = new RegulatorySubmission(); b.setSubmissionNumber(1);
        when(repository.findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                TENANT_ID, "K", p)).thenReturn(Flux.just(a, b));

        StepVerifier.create(service.list(TENANT_ID, "K", p))
                .expectNext(a, b)
                .verifyComplete();

        // Freshness sanity — the stubbed Jwt supports a real Instant now for reference.
        assertThat(Instant.now()).isNotNull();
    }
}
