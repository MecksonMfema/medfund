package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantRegulatoryRecipientRequest;
import com.medfund.tenancy.dto.UpdateTenantRegulatoryRecipientRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantRegulatoryRecipient;
import com.medfund.tenancy.repository.TenantRegulatoryRecipientRepository;
import com.medfund.tenancy.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantRegulatoryRecipientServiceTest {

    @Mock
    private TenantRegulatoryRecipientRepository repository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;
    @Mock
    private AuditPublisher auditPublisher;

    @Captor
    private ArgumentCaptor<AuditEvent> auditCaptor;

    @InjectMocks
    private TenantRegulatoryRecipientService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();

    @BeforeEach
    void stubTenantAndAudit() {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setSlug("acme");
        lenient().when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(t));
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
    }

    @Test
    void add_defaultsAllTiersWhenNull_lowercasesEmail_publishesAudit() {
        var req = new AddTenantRegulatoryRecipientRequest(
                "Compliance@Acme.COM", "Compliance Team", null, null);
        when(r2dbcTemplate.insert(any(TenantRegulatoryRecipient.class))).thenAnswer(inv -> {
            TenantRegulatoryRecipient saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@acme"))
                .assertNext(row -> {
                    assertThat(row.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(row.getEmail()).isEqualTo("compliance@acme.com");
                    assertThat(row.getDisplayName()).isEqualTo("Compliance Team");
                    assertThat(row.getSubscribedEventTiers())
                            .containsExactlyInAnyOrder(
                                    "DUE_DATE_7D", "DUE_DATE_1D",
                                    "DUE_DATE_0D", "DUE_DATE_OVERDUE");
                    assertThat(row.getIsActive()).isTrue();
                    assertThat(row.getActorEmail()).isEqualTo("admin@acme");
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("CREATE");
        assertThat(ev.entityName()).contains("compliance@acme.com").contains("acme");
        assertThat(ev.actorEmail()).isEqualTo("admin@acme");
    }

    @Test
    void add_customTiers_normalisesUpperCaseAndDedupes() {
        var req = new AddTenantRegulatoryRecipientRequest(
                "cfo@acme.com", null,
                List.of("due_date_1d", "DUE_DATE_1D", " due_date_overdue "), true);
        when(r2dbcTemplate.insert(any(TenantRegulatoryRecipient.class))).thenAnswer(inv -> {
            TenantRegulatoryRecipient saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@acme"))
                .assertNext(row -> assertThat(row.getSubscribedEventTiers())
                        .containsExactlyInAnyOrder("DUE_DATE_1D", "DUE_DATE_OVERDUE"))
                .verifyComplete();
    }

    @Test
    void add_unknownTier_rejects() {
        var req = new AddTenantRegulatoryRecipientRequest(
                "cfo@acme.com", null, List.of("DUE_DATE_YESTERDAY"), true);
        assertThatThrownBy(() -> service.add(TENANT_ID, req, ACTOR, "admin@acme").block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subscribedEventTiers");
        verify(r2dbcTemplate, never()).insert(any(TenantRegulatoryRecipient.class));
    }

    @Test
    void add_blankActor_rejects() {
        var req = new AddTenantRegulatoryRecipientRequest(
                "cfo@acme.com", null, null, null);
        assertThatThrownBy(() -> service.add(TENANT_ID, req, "", "").block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actorId");
        verify(r2dbcTemplate, never()).insert(any(TenantRegulatoryRecipient.class));
    }

    @Test
    void update_crossTenantRow_rejects() {
        TenantRegulatoryRecipient row = seededRow(OTHER_TENANT, "cfo@acme.com");
        when(repository.findById(row.getId())).thenReturn(Mono.just(row));

        var req = new UpdateTenantRegulatoryRecipientRequest("New name", null, false);
        assertThatThrownBy(() -> service.update(TENANT_ID, row.getId(), req, ACTOR, "admin@acme").block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to tenant");
        verify(repository, never()).save(any(TenantRegulatoryRecipient.class));
    }

    @Test
    void update_activatesAndCapturesChangedFields() {
        TenantRegulatoryRecipient row = seededRow(TENANT_ID, "cfo@acme.com");
        row.setIsActive(false);
        when(repository.findById(row.getId())).thenReturn(Mono.just(row));
        when(repository.save(any(TenantRegulatoryRecipient.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var req = new UpdateTenantRegulatoryRecipientRequest(null, null, true);
        StepVerifier.create(service.update(TENANT_ID, row.getId(), req, ACTOR, "admin@acme"))
                .assertNext(saved -> assertThat(saved.getIsActive()).isTrue())
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("UPDATE");
        assertThat(ev.changedFields()).contains("isActive");
    }

    @Test
    void delete_missingRow_404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.delete(TENANT_ID, id, ACTOR, "admin@acme").block())
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void delete_publishesDeleteAudit() {
        TenantRegulatoryRecipient row = seededRow(TENANT_ID, "cfo@acme.com");
        when(repository.findById(row.getId())).thenReturn(Mono.just(row));
        when(repository.delete(row)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(TENANT_ID, row.getId(), ACTOR, "admin@acme"))
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("DELETE");
        assertThat(ev.entityName()).contains("cfo@acme.com");
    }

    @Test
    void activeFor_delegatesToRepository() {
        TenantRegulatoryRecipient row = seededRow(TENANT_ID, "cfo@acme.com");
        when(repository.findByTenantIdAndIsActiveTrue(TENANT_ID)).thenReturn(Flux.just(row));

        StepVerifier.create(service.activeFor(TENANT_ID))
                .expectNext(row)
                .verifyComplete();
    }

    @Test
    void normaliseTiers_emptyList_returnsAllFourTiers() {
        String[] tiers = TenantRegulatoryRecipientService.normaliseTiers(List.of());
        assertThat(tiers).containsExactlyInAnyOrder(
                "DUE_DATE_7D", "DUE_DATE_1D", "DUE_DATE_0D", "DUE_DATE_OVERDUE");
    }

    private TenantRegulatoryRecipient seededRow(UUID tenantId, String email) {
        TenantRegulatoryRecipient r = new TenantRegulatoryRecipient();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setEmail(email);
        r.setDisplayName("Existing");
        r.setSubscribedEventTiers(new String[]{"DUE_DATE_7D", "DUE_DATE_1D"});
        r.setIsActive(true);
        r.setActorId(UUID.fromString(ACTOR));
        r.setActorEmail("admin@acme");
        return r;
    }
}
