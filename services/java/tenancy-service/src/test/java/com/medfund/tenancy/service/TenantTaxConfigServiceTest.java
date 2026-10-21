package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantTaxConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantTaxConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantTaxConfig;
import com.medfund.tenancy.repository.TenantRepository;
import com.medfund.tenancy.repository.TenantTaxConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
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
class TenantTaxConfigServiceTest {

    @Mock
    private TenantTaxConfigRepository repository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;
    @Mock
    private AuditPublisher auditPublisher;

    @Captor
    private ArgumentCaptor<AuditEvent> auditCaptor;

    @InjectMocks
    private TenantTaxConfigService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();

    @BeforeEach
    void stubTenantAndAudit() {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setSlug("acme-za");
        lenient().when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(t));
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
    }

    // ── add ─────────────────────────────────────────────────────────────────

    @Test
    void add_populatesRow_upcasesEveryCode_defaultsEffectiveFrom_publishesFriendlyEntityName() {
        var req = new AddTenantTaxConfigRequest(
                "za", "vat", "admin_fee", "zar",
                new BigDecimal("0.15000"),
                true, "VAT-4-000-0001", null, null, "Confirmed with SARS 2026-08");
        when(r2dbcTemplate.insert(any(TenantTaxConfig.class))).thenAnswer(inv -> {
            TenantTaxConfig saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@insurer-za"))
                .assertNext(row -> {
                    assertThat(row.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(row.getCountryCode()).isEqualTo("ZA");
                    assertThat(row.getTaxType()).isEqualTo("VAT");
                    assertThat(row.getTransactionCategory()).isEqualTo("ADMIN_FEE");
                    assertThat(row.getCurrency()).isEqualTo("ZAR");
                    assertThat(row.getRate()).isEqualByComparingTo("0.15000");
                    assertThat(row.isRegistered()).isTrue();
                    assertThat(row.getRegistrationNumber()).isEqualTo("VAT-4-000-0001");
                    assertThat(row.getEffectiveFrom()).isEqualTo(LocalDate.now());
                    assertThat(row.getSourceNote()).isEqualTo("Confirmed with SARS 2026-08");
                    assertThat(row.getUpdatedByEmail()).isEqualTo("admin@insurer-za");
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("CREATE");
        assertThat(ev.entityName())
                .contains("acme-za")
                .contains("ZA")
                .contains("VAT")
                .contains("ADMIN_FEE")
                .contains("ZAR")
                .contains("0.15000");
    }

    @Test
    void add_defaultsRegisteredTrue_whenNullOnRequest() {
        var req = new AddTenantTaxConfigRequest(
                "ZW", "VAT", "PREMIUM", "ZWL",
                new BigDecimal("0.00000"),
                null, null, null, null, null);
        when(r2dbcTemplate.insert(any(TenantTaxConfig.class))).thenAnswer(inv -> {
            TenantTaxConfig saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@insurer-zw"))
                .assertNext(row -> assertThat(row.isRegistered()).isTrue())
                .verifyComplete();
    }

    @Test
    void add_blankCountryCode_rejectsWithClear400() {
        var req = new AddTenantTaxConfigRequest(
                "  ", "VAT", "PREMIUM", "ZAR",
                new BigDecimal("0.15"), true, null, null, null, null);
        // requireBlank throws synchronously (before the reactive chain is created).
        assertThatThrownBy(() -> service.add(TENANT_ID, req, ACTOR, "admin@insurer-za"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("countryCode");
    }

    @Test
    void add_blankTaxType_rejects() {
        var req = new AddTenantTaxConfigRequest(
                "ZA", "", "PREMIUM", "ZAR",
                new BigDecimal("0.15"), true, null, null, null, null);
        assertThatThrownBy(() -> service.add(TENANT_ID, req, ACTOR, "admin@insurer-za"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taxType");
    }

    @Test
    void add_blankTransactionCategory_rejects() {
        var req = new AddTenantTaxConfigRequest(
                "ZA", "VAT", "  ", "ZAR",
                new BigDecimal("0.15"), true, null, null, null, null);
        assertThatThrownBy(() -> service.add(TENANT_ID, req, ACTOR, "admin@insurer-za"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionCategory");
    }

    // ── update ──────────────────────────────────────────────────────────────

    @Test
    void update_appliesOnlyNonNullFields_publishesChangedFieldsMap() {
        TenantTaxConfig existing = row("ZA", "VAT", "COMMISSION", "ZAR",
                new BigDecimal("0.15000"), true);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any(TenantTaxConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var patch = new UpdateTenantTaxConfigRequest(
                new BigDecimal("0.17500"), null, "VAT-4-999", null, null);
        StepVerifier.create(service.update(TENANT_ID, existing.getId(), patch, ACTOR, "admin@insurer-za"))
                .assertNext(row -> {
                    assertThat(row.getRate()).isEqualByComparingTo("0.17500");
                    assertThat(row.getRegistrationNumber()).isEqualTo("VAT-4-999");
                    // Unchanged
                    assertThat(row.getTaxType()).isEqualTo("VAT");
                    assertThat(row.getCurrency()).isEqualTo("ZAR");
                    assertThat(row.isRegistered()).isTrue();
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("UPDATE");
        assertThat(ev.changedFields()).contains("rate", "registrationNumber");
    }

    @Test
    void update_blankRegistrationNumber_clearsField() {
        TenantTaxConfig existing = row("ZA", "VAT", "COMMISSION", "ZAR",
                new BigDecimal("0.15000"), true);
        existing.setRegistrationNumber("VAT-4-000-0001");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any(TenantTaxConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var patch = new UpdateTenantTaxConfigRequest(
                null, false, "  ", null, null);
        StepVerifier.create(service.update(TENANT_ID, existing.getId(), patch, ACTOR, "admin@insurer-za"))
                .assertNext(row -> {
                    assertThat(row.getRegistrationNumber()).isNull();
                    assertThat(row.isRegistered()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void update_crossTenantRow_rejected_withoutSave() {
        TenantTaxConfig other = row("ZA", "VAT", "PREMIUM", "ZAR",
                new BigDecimal("0.00000"), true);
        other.setTenantId(OTHER_TENANT);
        when(repository.findById(other.getId())).thenReturn(Mono.just(other));

        var patch = new UpdateTenantTaxConfigRequest(
                new BigDecimal("0.99999"), null, null, null, null);
        StepVerifier.create(service.update(TENANT_ID, other.getId(), patch, ACTOR, "admin@insurer-za"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("does not belong");
                })
                .verify();
        verify(repository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void update_missingRow_rejectsWithNoSuchElement() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Mono.empty());

        var patch = new UpdateTenantTaxConfigRequest(
                new BigDecimal("0.17"), null, null, null, null);
        StepVerifier.create(service.update(TENANT_ID, missingId, patch, ACTOR, "admin@insurer-za"))
                .expectError(NoSuchElementException.class)
                .verify();
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test
    void delete_publishesDeleteAudit_withPriorSnapshot() {
        TenantTaxConfig existing = row("ZW", "WITHHOLDING", "COMMISSION", "ZWL",
                new BigDecimal("0.10000"), true);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.delete(existing)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(TENANT_ID, existing.getId(), ACTOR, "admin@insurer-zw"))
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("DELETE");
        assertThat(ev.entityName()).contains("ZW")
                .contains("WITHHOLDING")
                .contains("ZWL")
                .contains("0.10000");
        assertThat(ev.oldValue()).isNotNull();
        assertThat(ev.newValue()).isNull();
    }

    @Test
    void delete_crossTenantRow_rejected() {
        TenantTaxConfig other = row("ZA", "VAT", "PREMIUM", "ZAR",
                new BigDecimal("0.00000"), true);
        other.setTenantId(OTHER_TENANT);
        when(repository.findById(other.getId())).thenReturn(Mono.just(other));

        StepVerifier.create(service.delete(TENANT_ID, other.getId(), ACTOR, "admin@insurer-za"))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(repository, never()).delete(any(TenantTaxConfig.class));
    }

    @Test
    void listByType_nullOrBlank_fallsBackToUnfilteredList() {
        when(repository.findByTenantIdOrderByEffectiveFromDesc(TENANT_ID))
                .thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(service.listByType(TENANT_ID, null)).verifyComplete();
        StepVerifier.create(service.listByType(TENANT_ID, "")).verifyComplete();
        StepVerifier.create(service.listByType(TENANT_ID, "  ")).verifyComplete();

        verify(repository, never()).findByTenantIdAndTaxTypeOrderByEffectiveFromDesc(any(), any());
    }

    @Test
    void listByType_upcasesFilterBeforeQuery() {
        when(repository.findByTenantIdAndTaxTypeOrderByEffectiveFromDesc(TENANT_ID, "VAT"))
                .thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(service.listByType(TENANT_ID, "vat")).verifyComplete();

        verify(repository).findByTenantIdAndTaxTypeOrderByEffectiveFromDesc(TENANT_ID, "VAT");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private TenantTaxConfig row(String country, String taxType, String category,
                                String currency, BigDecimal rate, boolean registered) {
        TenantTaxConfig r = new TenantTaxConfig();
        r.setId(UUID.randomUUID());
        r.setTenantId(TENANT_ID);
        r.setCountryCode(country);
        r.setTaxType(taxType);
        r.setTransactionCategory(category);
        r.setCurrency(currency);
        r.setRate(rate);
        r.setRegistered(registered);
        r.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return r;
    }
}
