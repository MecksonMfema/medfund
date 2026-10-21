package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.RenewTreatyRequest;
import com.medfund.finance.reinsurance.dto.TreatyResponse;
import com.medfund.finance.reinsurance.dto.UpdateTreatyRequest;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreatyServiceTest {

    @Mock TreatyRepository repository;
    @Mock TreatyValidationService validationService;
    @Mock AuditPublisher auditPublisher;
    @Mock TreatyActivationBackfillJob backfillJob;
    @InjectMocks TreatyService service;

    @Test
    void createDraft_expiryBeforeInception_errors() {
        var req = new CreateTreatyRequest("REF-1", "QUOTA_SHARE", "USD",
                LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31),
                null, null, null, null);

        StepVerifier.create(service.createDraft(req, "sys", "a@b"))
                .expectErrorMatches(err -> err instanceof IllegalArgumentException
                        && err.getMessage().contains("expiryDate must be after"))
                .verify();
    }

    @Test
    void createDraft_persistsAndAudits() {
        var req = new CreateTreatyRequest("HEALTH-QS-2026", "QUOTA_SHARE", "USD",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                new BigDecimal("1000000.00"), "USD",
                new BigDecimal("50000.00"), "PROD-1");
        when(repository.save(any())).thenAnswer(inv -> {
            Treaty t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return Mono.just(t);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.createDraft(req, UUID.randomUUID().toString(), "u@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-x"))
        )
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("DRAFT");
                    assertThat(resp.treatyRef()).isEqualTo("HEALTH-QS-2026");
                    assertThat(resp.activatedAt()).isNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("CREATE");
        assertThat(cap.getValue().entityName()).isEqualTo("HEALTH-QS-2026");
    }

    @Test
    void update_onActiveTreaty_conflicts() {
        Treaty existing = draftTreaty("HEALTH-QS-2026");
        existing.setStatus("ACTIVE");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));

        StepVerifier.create(
                service.update(existing.getId(),
                        new UpdateTreatyRequest("HEALTH-QS-2026", "QUOTA_SHARE", "USD",
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                                null, null, null, null),
                        "sys", "u@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-x"))
        )
                .expectErrorMatches(err -> err instanceof IllegalStateException
                        && err.getMessage().contains("only DRAFT treaties are editable"))
                .verify();

        verify(repository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void activate_notDraft_conflicts() {
        Treaty existing = draftTreaty("HEALTH-QS-2026");
        existing.setStatus("EXPIRED");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));

        StepVerifier.create(
                service.activate(existing.getId(), "sys", "u@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-x"))
        )
                .expectErrorMatches(err -> err instanceof IllegalStateException)
                .verify();

        verify(validationService, never()).validateForActivation(any());
    }

    @Test
    void activate_draft_runsValidationAndTransitions() {
        Treaty existing = draftTreaty("HEALTH-QS-2026");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(validationService.validateForActivation(existing)).thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.activate(existing.getId(), "sys", "u@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-x"))
        )
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("ACTIVE");
                    assertThat(resp.activatedAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("ACTIVATE");
    }

    @Test
    void activate_validationFails_noSaveNoAudit() {
        Treaty existing = draftTreaty("HEALTH-QS-2026");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(validationService.validateForActivation(existing))
                .thenReturn(Mono.error(new IllegalArgumentException("shares must sum to 100")));

        StepVerifier.create(
                service.activate(existing.getId(), "sys", "u@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-x"))
        )
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void voidDraft_notDraft_conflicts() {
        Treaty existing = draftTreaty("HEALTH-QS-2026");
        existing.setStatus("ACTIVE");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));

        StepVerifier.create(
                service.voidDraft(existing.getId(), "sys", "u@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-x"))
        )
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void voidDraft_setsLapsed() {
        Treaty existing = draftTreaty("HEALTH-QS-2026");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.voidDraft(existing.getId(), "sys", "u@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-x"))
        )
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("LAPSED"))
                .verifyComplete();
    }

    @Test
    void renew_priorNotActiveOrExpired_conflicts() {
        Treaty prior = draftTreaty("REF-1");
        when(repository.findById(prior.getId())).thenReturn(Mono.just(prior));

        StepVerifier.create(
                service.renew(prior.getId(),
                        new RenewTreatyRequest("REF-2", LocalDate.of(2027, 1, 1),
                                LocalDate.of(2027, 12, 31), null, null, null),
                        "sys", "u@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-x"))
        )
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void renew_active_createsSuccessorDraft_withRenewedFromLink() {
        Treaty prior = draftTreaty("REF-1");
        prior.setStatus("ACTIVE");
        when(repository.findById(prior.getId())).thenReturn(Mono.just(prior));
        when(repository.save(any())).thenAnswer(inv -> {
            Treaty t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return Mono.just(t);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.renew(prior.getId(),
                        new RenewTreatyRequest("REF-2", LocalDate.of(2027, 1, 1),
                                LocalDate.of(2027, 12, 31), null, null, null),
                        "sys", "u@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-x"))
        )
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("DRAFT");
                    assertThat(resp.treatyRef()).isEqualTo("REF-2");
                    assertThat(resp.renewedFromTreatyId()).isEqualTo(prior.getId());
                })
                .verifyComplete();
    }

    @Test
    void renewalChain_walksBackToRoot() {
        Treaty root = draftTreaty("REF-1");
        Treaty mid  = draftTreaty("REF-2");
        mid.setRenewedFromTreatyId(root.getId());
        Treaty leaf = draftTreaty("REF-3");
        leaf.setRenewedFromTreatyId(mid.getId());
        when(repository.findById(leaf.getId())).thenReturn(Mono.just(leaf));
        when(repository.findById(mid.getId())).thenReturn(Mono.just(mid));
        when(repository.findById(root.getId())).thenReturn(Mono.just(root));

        StepVerifier.create(service.renewalChain(leaf.getId()))
                .assertNext(chain -> {
                    assertThat(chain).hasSize(3);
                    assertThat(chain.stream().map(TreatyResponse::treatyRef).toList())
                            .containsExactly("REF-1", "REF-2", "REF-3");
                })
                .verifyComplete();
    }

    @Test
    void requireDraft_active_conflicts() {
        Treaty existing = draftTreaty("HEALTH-QS-2026");
        existing.setStatus("ACTIVE");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));

        StepVerifier.create(service.requireDraft(existing.getId()))
                .expectError(IllegalStateException.class)
                .verify();
    }

    private Treaty draftTreaty(String ref) {
        Treaty t = new Treaty();
        t.setId(UUID.randomUUID());
        t.setTreatyRef(ref);
        t.setTreatyType("QUOTA_SHARE");
        t.setDeclaredCurrency("USD");
        t.setInceptionDate(LocalDate.of(2026, 1, 1));
        t.setExpiryDate(LocalDate.of(2026, 12, 31));
        t.setStatus("DRAFT");
        t.setCreatedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        return t;
    }
}
