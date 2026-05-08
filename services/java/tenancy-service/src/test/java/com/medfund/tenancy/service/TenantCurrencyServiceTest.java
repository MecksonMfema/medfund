package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.entity.TenantCurrencyConfig;
import com.medfund.tenancy.exception.CurrencyConflictException;
import com.medfund.tenancy.repository.CurrencyRepository;
import com.medfund.tenancy.repository.TenantCurrencyConfigRepository;
import com.medfund.tenancy.service.TenantCurrencyService.AddCurrencyRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCurrencyServiceTest {

    @Mock private TenantCurrencyConfigRepository repository;
    @Mock private CurrencyRepository currencyRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private AuditPublisher auditPublisher;
    @Mock private CurrencyEventPublisher eventPublisher;

    @Test
    void add_unknownCurrencyCode_rejected() {
        var service = new TenantCurrencyService(repository, currencyRepository, r2dbcTemplate, auditPublisher, eventPublisher);
        UUID tenantId = UUID.randomUUID();
        when(currencyRepository.existsActiveByCode("XYZ")).thenReturn(Mono.just(false));

        StepVerifier.create(service.add(tenantId,
                        new AddCurrencyRequest("XYZ", false, true, true, true, "manual"),
                        "actor", "actor@test"))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void add_alreadyConfigured_throwsConflict() {
        var service = new TenantCurrencyService(repository, currencyRepository, r2dbcTemplate, auditPublisher, eventPublisher);
        UUID tenantId = UUID.randomUUID();

        TenantCurrencyConfig existing = configFor(tenantId, "ZAR", false);

        when(currencyRepository.existsActiveByCode("ZAR")).thenReturn(Mono.just(true));
        when(repository.findByTenantIdAndCurrencyCode(tenantId, "ZAR")).thenReturn(Mono.just(existing));

        StepVerifier.create(service.add(tenantId,
                        new AddCurrencyRequest("ZAR", false, true, true, true, "manual"),
                        "actor", "actor@test"))
                .expectError(CurrencyConflictException.class)
                .verify();
    }

    @Test
    void add_promotesToDefault_clearsPriorDefault() {
        var service = new TenantCurrencyService(repository, currencyRepository, r2dbcTemplate, auditPublisher, eventPublisher);
        UUID tenantId = UUID.randomUUID();

        TenantCurrencyConfig priorDefault = configFor(tenantId, "USD", true);

        when(currencyRepository.existsActiveByCode("ZAR")).thenReturn(Mono.just(true));
        when(repository.findByTenantIdAndCurrencyCode(tenantId, "ZAR")).thenReturn(Mono.empty());
        when(repository.findDefaultByTenantId(tenantId)).thenReturn(Mono.just(priorDefault));
        when(repository.save(any(TenantCurrencyConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(r2dbcTemplate.insert(any(TenantCurrencyConfig.class)))
                .thenAnswer(inv -> {
                    TenantCurrencyConfig c = inv.getArgument(0);
                    c.setId(UUID.randomUUID());
                    return Mono.just(c);
                });
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
        when(eventPublisher.publishTenantCurrencyUpdated(any(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.add(tenantId,
                        new AddCurrencyRequest("ZAR", true, true, true, true, "manual"),
                        "actor", "actor@test"))
                .assertNext(saved -> {
                    assertThat(saved.getCurrencyCode()).isEqualTo("ZAR");
                    assertThat(saved.getIsDefault()).isTrue();
                })
                .verifyComplete();

        // Prior default must be demoted before insert.
        verify(repository).save(any(TenantCurrencyConfig.class));
        verify(r2dbcTemplate).insert(any(TenantCurrencyConfig.class));
    }

    @Test
    void remove_default_throwsConflict() {
        var service = new TenantCurrencyService(repository, currencyRepository, r2dbcTemplate, auditPublisher, eventPublisher);
        UUID tenantId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();

        TenantCurrencyConfig def = configFor(tenantId, "USD", true);
        def.setId(configId);

        when(repository.findById(configId)).thenReturn(Mono.just(def));

        StepVerifier.create(service.remove(tenantId, configId, "actor", "actor@test"))
                .expectError(CurrencyConflictException.class)
                .verify();

        verify(repository, never()).delete(any());
    }

    private TenantCurrencyConfig configFor(UUID tenantId, String code, boolean isDefault) {
        TenantCurrencyConfig c = new TenantCurrencyConfig();
        c.setId(UUID.randomUUID());
        c.setTenantId(tenantId);
        c.setCurrencyCode(code);
        c.setIsDefault(isDefault);
        c.setIsActive(true);
        c.setIsBillingCurrency(true);
        c.setIsClaimsCurrency(true);
        c.setIsPaymentCurrency(true);
        c.setExchangeRateSource("manual");
        return c;
    }
}
