package com.medfund.claims.pmb;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PmbClassificationExecutorTest {

    private static final String TENANT = UUID.randomUUID().toString();
    private static final String ACTOR = UUID.randomUUID().toString();
    private static final String ACTOR_EMAIL = "adjudicator@test";

    private PmbClassifier classifier;
    private ClaimRepository claimRepository;
    private AuditPublisher auditPublisher;
    private PmbClassificationExecutor executor;

    @BeforeEach
    void setUp() {
        classifier = mock(PmbClassifier.class);
        claimRepository = mock(ClaimRepository.class);
        auditPublisher = mock(AuditPublisher.class);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(claimRepository.save(any(Claim.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        executor = new PmbClassificationExecutor(classifier, claimRepository, auditPublisher);
    }

    @Test
    void classifyAndPersist_newPmbMatch_writesFieldsAndAudits() {
        Claim c = claim();
        when(classifier.classify(c)).thenReturn(Mono.just(PmbClassification.pmb("PMB-001")));

        StepVerifier.create(withTenant(executor.classifyAndPersist(c, ACTOR, ACTOR_EMAIL), TENANT))
                .assertNext(returned -> {
                    assertThat(returned.getIsPmb()).isTrue();
                    assertThat(returned.getPmbConditionCode()).isEqualTo("PMB-001");
                })
                .verifyComplete();

        verify(claimRepository, times(1)).save(c);
        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(ev.capture());
        AuditEvent captured = ev.getValue();
        assertThat(captured.tenantId()).isEqualTo(TENANT);
        assertThat(captured.actorId()).isEqualTo(ACTOR);
        assertThat(captured.actorEmail()).isEqualTo(ACTOR_EMAIL);
        assertThat(captured.entityType()).isEqualTo("claim.pmb_classification");
        assertThat(captured.entityName())
                .startsWith("PMB classification for claim ")
                .doesNotContain(captured.entityId());
        assertThat(captured.action()).isEqualTo("PMB_CLASSIFY");
        assertThat(captured.oldValue()).containsEntry("isPmb", false);
        assertThat(captured.newValue()).containsEntry("isPmb", true);
        assertThat(captured.newValue()).containsEntry("pmbConditionCode", "PMB-001");
        assertThat(captured.changedFields()).containsExactly("isPmb", "pmbConditionCode");
    }

    @Test
    void classifyAndPersist_verdictMatchesCurrentDbState_skipsSaveAndAudit() {
        Claim c = claim();
        c.setIsPmb(true);
        c.setPmbConditionCode("PMB-001");
        when(classifier.classify(c)).thenReturn(Mono.just(PmbClassification.pmb("PMB-001")));

        StepVerifier.create(withTenant(executor.classifyAndPersist(c, ACTOR, ACTOR_EMAIL), TENANT))
                .expectNext(c)
                .verifyComplete();

        verify(claimRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void classifyAndPersist_notPmbVerdict_leavesUntouchedIfAlreadyNotPmb() {
        Claim c = claim();
        when(classifier.classify(c)).thenReturn(Mono.just(PmbClassification.NOT_PMB));

        StepVerifier.create(withTenant(executor.classifyAndPersist(c, ACTOR, ACTOR_EMAIL), TENANT))
                .expectNext(c)
                .verifyComplete();

        verify(claimRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void classifyAndPersist_flipsPmbToNotPmb_clearsCode() {
        Claim c = claim();
        c.setIsPmb(true);
        c.setPmbConditionCode("PMB-OLD");
        when(classifier.classify(c)).thenReturn(Mono.just(PmbClassification.NOT_PMB));

        StepVerifier.create(withTenant(executor.classifyAndPersist(c, ACTOR, ACTOR_EMAIL), TENANT))
                .assertNext(returned -> {
                    assertThat(returned.getIsPmb()).isFalse();
                    assertThat(returned.getPmbConditionCode()).isNull();
                })
                .verifyComplete();

        verify(claimRepository, times(1)).save(any());
        verify(auditPublisher, times(1)).publish(any());
    }

    @Test
    void classifyAndPersist_classifierError_returnsOriginalClaim_noSaveOrAudit() {
        Claim c = claim();
        when(classifier.classify(c)).thenReturn(Mono.error(new RuntimeException("engine down")));

        StepVerifier.create(withTenant(executor.classifyAndPersist(c, ACTOR, ACTOR_EMAIL), TENANT))
                .expectNext(c)
                .verifyComplete();

        verify(claimRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void classifyAndPersist_nullClaim_shortCircuits() {
        StepVerifier.create(executor.classifyAndPersist(null, ACTOR, ACTOR_EMAIL))
                .verifyComplete();

        verify(classifier, never()).classify(any());
    }

    @Test
    void classifyAndPersist_noTenantContext_stampsAuditWithUnknown() {
        Claim c = claim();
        when(classifier.classify(c)).thenReturn(Mono.just(PmbClassification.pmb("PMB-001")));

        StepVerifier.create(executor.classifyAndPersist(c, ACTOR, ACTOR_EMAIL))
                .assertNext(returned -> assertThat(returned.getIsPmb()).isTrue())
                .verifyComplete();

        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(ev.capture());
        assertThat(ev.getValue().tenantId()).isEqualTo("unknown");
    }

    private static Claim claim() {
        Claim c = new Claim();
        c.setId(UUID.randomUUID());
        c.setClaimNumber("CLM-42");
        return c;
    }

    private static <T> Mono<T> withTenant(Mono<T> mono, String tenant) {
        return mono.contextWrite(Context.of(TenantContext.KEY, tenant));
    }
}
