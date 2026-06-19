package com.medfund.finance.service;

import com.medfund.finance.entity.ProviderBalance;
import com.medfund.finance.repository.ProviderBalanceRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderBalanceServiceTest {

    @Mock
    private ProviderBalanceRepository providerBalanceRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private ProviderBalanceService providerBalanceService;

    @Test
    void findByProviderId_existing_returnsBalance() {
        var balance = createTestBalance();
        when(providerBalanceRepository.findByProviderId(balance.getProviderId()))
                .thenReturn(Mono.just(balance));

        StepVerifier.create(providerBalanceService.findByProviderId(balance.getProviderId()))
                .assertNext(result -> {
                    assertThat(result.getProviderId()).isEqualTo(balance.getProviderId());
                    assertThat(result.getTotalClaimed()).isEqualByComparingTo(new BigDecimal("1000.00"));
                    assertThat(result.getCurrencyCode()).isEqualTo("USD");
                })
                .verifyComplete();

        verify(providerBalanceRepository).findByProviderId(balance.getProviderId());
    }

    @Test
    void findByProviderId_notFound_returnsDefaultZeroBalance() {
        UUID providerId = UUID.randomUUID();
        when(providerBalanceRepository.findByProviderId(providerId))
                .thenReturn(Mono.empty());

        StepVerifier.create(providerBalanceService.findByProviderId(providerId))
                .assertNext(result -> {
                    assertThat(result.getProviderId()).isEqualTo(providerId);
                    assertThat(result.getTotalClaimed()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(result.getTotalApproved()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(result.getTotalPaid()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(result.getOutstandingBalance()).isEqualByComparingTo(BigDecimal.ZERO);
                })
                .verifyComplete();
    }

    @Test
    void findAllByOutstandingBalance_delegates() {
        var b1 = createTestBalance();
        var b2 = createTestBalance();
        when(providerBalanceRepository.findAllByOutstandingBalanceGreaterThanZero())
                .thenReturn(Flux.just(b1, b2));

        StepVerifier.create(providerBalanceService.findAllByOutstandingBalance())
                .expectNext(b1)
                .expectNext(b2)
                .verifyComplete();

        verify(providerBalanceRepository).findAllByOutstandingBalanceGreaterThanZero();
    }

    @Test
    void updateBalance_accumulatesDeltasCorrectly() {
        var balance = createTestBalance();
        UUID providerId = balance.getProviderId();
        String actorId = UUID.randomUUID().toString();

        when(providerBalanceRepository.findByProviderIdAndCurrencyCode(providerId, "USD"))
                .thenReturn(Mono.just(balance));
        when(providerBalanceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                providerBalanceService.updateBalance(
                        providerId, "USD",
                        new BigDecimal("200.00"), new BigDecimal("100.00"), new BigDecimal("50.00"),
                        actorId, "actor@test.example"
                ).contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getTotalClaimed()).isEqualByComparingTo(new BigDecimal("1200.00"));
                    assertThat(saved.getTotalApproved()).isEqualByComparingTo(new BigDecimal("900.00"));
                    assertThat(saved.getTotalPaid()).isEqualByComparingTo(new BigDecimal("550.00"));
                    assertThat(saved.getOutstandingBalance()).isEqualByComparingTo(new BigDecimal("350.00"));
                })
                .verifyComplete();

        verify(providerBalanceRepository).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void updateBalance_newProvider_createsBalance() {
        UUID providerId = UUID.randomUUID();
        String actorId = UUID.randomUUID().toString();

        when(providerBalanceRepository.findByProviderIdAndCurrencyCode(providerId, "USD"))
                .thenReturn(Mono.empty());
        when(providerBalanceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                providerBalanceService.updateBalance(
                        providerId, "USD",
                        new BigDecimal("500.00"), new BigDecimal("400.00"), new BigDecimal("100.00"),
                        actorId, "actor@test.example"
                ).contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getProviderId()).isEqualTo(providerId);
                    assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                    assertThat(saved.getTotalClaimed()).isEqualByComparingTo(new BigDecimal("500.00"));
                    assertThat(saved.getTotalApproved()).isEqualByComparingTo(new BigDecimal("400.00"));
                    assertThat(saved.getTotalPaid()).isEqualByComparingTo(new BigDecimal("100.00"));
                    assertThat(saved.getOutstandingBalance()).isEqualByComparingTo(new BigDecimal("300.00"));
                })
                .verifyComplete();

        verify(providerBalanceRepository).save(any());
    }

    // ---- Helper ----

    private ProviderBalance createTestBalance() {
        var b = new ProviderBalance();
        b.setId(UUID.randomUUID());
        b.setProviderId(UUID.randomUUID());
        b.setTotalClaimed(new BigDecimal("1000.00"));
        b.setTotalApproved(new BigDecimal("800.00"));
        b.setTotalPaid(new BigDecimal("500.00"));
        b.setOutstandingBalance(new BigDecimal("300.00"));
        b.setCurrencyCode("USD");
        b.setCreatedAt(Instant.now());
        return b;
    }
}
